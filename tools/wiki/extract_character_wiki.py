#!/usr/bin/env python3
import argparse
import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path


BASE_URL = "https://wikiwiki.jp/housamo/"
LIST_PAGE = "☆３"
EXCLUDED_CHARACTERS = {"mc", "主人公", "サロモンくん"}
RELATION_TYPES = {"好意", "苦手"}
WIKI_NAME_ALIASES = {
    "∀アイザック": "アイザック",
    "Ｒ－１９": "R-19",
    "サナトクマラ": "サナト・クマラ",
}


def safe_cache_name(page_name: str) -> str:
    return urllib.parse.quote(page_name, safe="") + ".wiki"


def cache_path_for(cache_dir: Path, page_name: str) -> Path:
    return cache_dir / safe_cache_name(page_name)


def fetch_source(
    page_name: str,
    timeout: int = 30,
    cache_dir: Path | None = None,
    retries: int = 4,
    retry_sleep: float = 12.0,
) -> str:
    if cache_dir is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_path = cache_path_for(cache_dir, page_name)
        if cache_path.exists():
            return cache_path.read_text(encoding="utf-8")

    url = BASE_URL + "?cmd=source&page=" + urllib.parse.quote(page_name, safe="")
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 HET-WikiExtractor/0.1",
        },
    )
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", errors="replace")
            break
        except urllib.error.HTTPError as exc:
            last_error = exc
            if exc.code != 429 or attempt >= retries:
                raise
            wait = retry_sleep * (attempt + 1)
            print(f"[rate-limit] {page_name}: HTTP 429, sleep {wait:.1f}s")
            time.sleep(wait)
    else:
        raise RuntimeError(f"fetch failed: {page_name}: {last_error}")

    match = re.search(
        r'<pre\s+id="source"\s+class="wiki-source"><code>(.*?)</code></pre>',
        body,
        re.S,
    )
    if not match:
        raise RuntimeError(f"source block not found: {page_name}")
    source = html.unescape(match.group(1))
    if cache_dir is not None:
        cache_path.write_text(source, encoding="utf-8", newline="\n")
    return source


def wiki_links(text: str) -> list[str]:
    names: list[str] = []
    for match in re.finditer(r"\[\[([^\]]+)\]\]", text):
        body = match.group(1)
        target = body.rsplit(">", 1)[-1]
        target = target.split("#", 1)[0].strip()
        if not target or target.startswith(("http://", "https://")):
            continue
        names.append(target)
    return names


def ordered_unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def build_wiki_name_map(chardict: dict) -> dict[str, str]:
    name_map = {name: name for name in chardict.keys()}
    for wiki_name, dict_name in WIKI_NAME_ALIASES.items():
        if dict_name in chardict:
            name_map.setdefault(wiki_name, dict_name)

    for dict_name, entry in chardict.items():
        for alias in entry.get("alias") or []:
            names = alias.get("name", [])
            if isinstance(names, str):
                names = [names]
            for wiki_name in names:
                if wiki_name and dict_name in chardict:
                    name_map.setdefault(wiki_name, dict_name)
    return name_map


def ordered_unique_refs(items: list[dict[str, str]]) -> list[dict[str, str]]:
    seen: set[str] = set()
    result: list[dict[str, str]] = []
    for item in items:
        name = item["name"]
        if name in seen:
            continue
        seen.add(name)
        result.append(item)
    return result


def extract_character_list(list_source: str, name_map: dict[str, str]) -> list[dict[str, str]]:
    start = list_source.find("*仲間一覧")
    if start < 0:
        start = 0
    end = list_source.find("*コメント", start)
    if end < 0:
        end = len(list_source)
    body = list_source[start:end]

    result: list[dict[str, str]] = []
    for page_name in wiki_links(body):
        if page_name in EXCLUDED_CHARACTERS:
            continue
        dict_name = name_map.get(page_name)
        if dict_name is None:
            continue
        result.append({"page_name": page_name, "name": dict_name})
    return ordered_unique_refs(result)


def section_blocks(source: str, heading: str) -> list[str]:
    lines = source.splitlines()
    blocks: list[str] = []
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith("**" + heading):
            chunk: list[str] = []
            i += 1
            while i < len(lines):
                next_line = lines[i].strip()
                if next_line.startswith("**") and not next_line.startswith("***"):
                    break
                if next_line.startswith("*") and not next_line.startswith("**"):
                    break
                chunk.append(lines[i])
                i += 1
            blocks.append("\n".join(chunk))
            continue
        i += 1
    return blocks


def clean_inline_wiki(text: str) -> str:
    text = html.unescape(text)
    text = re.sub(r"&br;?", "\n", text)
    text = re.sub(r"&ruby\([^)]*\)\{([^{}]*)\};", r"\1", text)
    text = re.sub(r"&color\([^)]*\)\{([^{}]*)\};", r"\1", text)
    text = re.sub(r"&size\([^)]*\)\{([^{}]*)\};", r"\1", text)
    text = re.sub(r"&(?:attachref|ref)\([^;]*\);", "", text)
    text = re.sub(r"\[\[([^>\]]+)>[^\]]+\]\]", r"\1", text)
    text = re.sub(r"\[\[([^\]]+)\]\]", r"\1", text)
    text = text.replace("''", "")
    text = text.replace("'''", "")
    return text


def clean_body_lines(lines: list[str]) -> str:
    cleaned: list[str] = []
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        if line.startswith(("#fold", "#accordion", "#region", "#shadowheader")):
            continue
        if line in {"}}", "}}}", "{{", "{{{"}:
            continue
        line = clean_inline_wiki(line)
        line = re.sub(r"\s+", " ", line).strip()
        if line:
            cleaned.append(line)
    return "\n".join(cleaned).strip()


def extract_survey_files(source: str) -> list[dict[str, str]]:
    files: list[dict[str, str]] = []
    for block in section_blocks(source, "調査ファイル"):
        current_title = ""
        current_lines: list[str] = []

        def flush() -> None:
            nonlocal current_title, current_lines
            body = clean_body_lines(current_lines)
            if body:
                files.append({"title": current_title or "調査ファイル", "text": body})
            current_title = ""
            current_lines = []

        for raw in block.splitlines():
            line = raw.strip()
            if re.match(r"^調査ファイル\d*", line):
                flush()
                current_title = clean_inline_wiki(line).strip()
                continue
            current_lines.append(raw)
        flush()
    return files


def split_wiki_table_row(line: str) -> list[str]:
    cells = line.strip().strip("|").split("|")
    return [cell.strip() for cell in cells]


def extract_relationships(source: str, name_map: dict[str, str]) -> list[dict[str, str]]:
    relationships: list[dict[str, str]] = []
    for block in section_blocks(source, "相関"):
        for raw in block.splitlines():
            line = raw.strip()
            if not line.startswith("|"):
                continue
            cells = split_wiki_table_row(line)
            if not cells:
                continue
            relation_type = clean_inline_wiki(cells[0]).strip()
            if relation_type not in RELATION_TYPES:
                continue
            own_cell = cells[-1] if cells else ""
            for target_page in wiki_links(own_cell):
                if target_page in EXCLUDED_CHARACTERS:
                    continue
                target = name_map.get(target_page)
                if target is None:
                    continue
                relationships.append({"target": target, "type": relation_type})

    deduped: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for rel in relationships:
        key = (rel["target"], rel["type"])
        if key in seen:
            continue
        seen.add(key)
        deduped.append(rel)
    return deduped


def ordered_entry_with_info(entry: dict) -> dict:
    if "Info" in entry:
        return entry
    rebuilt = {}
    inserted = False
    for key, value in entry.items():
        if key == "description":
            rebuilt["Info"] = ""
            inserted = True
        rebuilt[key] = value
    if not inserted:
        rebuilt["Info"] = ""
    return rebuilt


def write_tasks_markdown(records: list[dict], path: Path, batch_size: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("# Housamo character Info tasks\n\n")
        f.write("目标：根据每个角色的所有 `調査ファイル`，写一个中立、压缩、可给翻译模型使用的 `Info`。不要逐字翻译原文，不要加入页面没有支持的猜测。\n\n")
        f.write("输出建议：每个角色 1 到 3 句日文；优先保留身份、核心性格、神器/権能、重要关系或叙事定位。\n\n")
        for idx, rec in enumerate(records, 1):
            if batch_size > 0 and (idx - 1) % batch_size == 0:
                f.write(f"\n## Batch {(idx - 1) // batch_size + 1}\n\n")
            f.write(f"### {idx}. {rec['name']}\n\n")
            if rec.get("url"):
                f.write(f"- page: {rec['url']}\n")
            rels = rec.get("relationships") or []
            if rels:
                f.write("- relationships: " + ", ".join(f"{r['type']}->{r['target']}" for r in rels) + "\n")
            f.write("- Info: \n\n")
            for item in rec.get("survey_files") or []:
                f.write(f"#### {item['title']}\n\n")
                f.write(item["text"].strip() + "\n\n")


def write_task_batches(records: list[dict], out_dir: Path, batch_size: int) -> None:
    if batch_size <= 0:
        return
    out_dir.mkdir(parents=True, exist_ok=True)
    for batch_index, start in enumerate(range(0, len(records), batch_size), 1):
        batch_records = records[start : start + batch_size]
        path = out_dir / f"info_batch_{batch_index:03d}.md"
        with path.open("w", encoding="utf-8", newline="\n") as f:
            f.write(f"# Housamo character Info batch {batch_index:03d}\n\n")
            f.write("请为下面每个角色填写 `Info`：基于所有 `調査ファイル` 写 1 到 3 句中立日文总结。不要逐字翻译原文，不要加入页面没有支持的猜测。\n\n")
            for offset, rec in enumerate(batch_records, start + 1):
                f.write(f"## {offset}. {rec['name']}\n\n")
                f.write(f"- page: {rec['url']}\n")
                rels = rec.get("relationships") or []
                if rels:
                    f.write("- relationships: " + ", ".join(f"{r['type']}->{r['target']}" for r in rels) + "\n")
                f.write("- Info: \n\n")
                for item in rec.get("survey_files") or []:
                    f.write(f"### {item['title']}\n\n")
                    f.write(item["text"].strip() + "\n\n")


def apply_relationships_and_info(
    chardict: dict,
    records: list[dict],
    ensure_info_key: bool,
    apply_relationships: bool,
) -> dict:
    by_name = {rec["name"]: rec for rec in records}
    rewritten = {}
    for name, entry in chardict.items():
        item = dict(entry)
        if ensure_info_key:
            item = ordered_entry_with_info(item)
        if apply_relationships and name in by_name:
            rels = by_name[name].get("relationships") or []
            if rels:
                item["relationships"] = rels
        rewritten[name] = item
    return rewritten


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chardict", default="app/src/main/assets/chardict.json")
    parser.add_argument("--out-dir", default="build/wiki_character_extract")
    parser.add_argument("--cache-dir", default="")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--names", nargs="*", default=[])
    parser.add_argument("--sleep", type=float, default=1.2)
    parser.add_argument("--retry-sleep", type=float, default=12.0)
    parser.add_argument("--retries", type=int, default=4)
    parser.add_argument("--max-uncached", type=int, default=0)
    parser.add_argument("--cache-only", action="store_true")
    parser.add_argument("--fetch-only", action="store_true")
    parser.add_argument("--apply-relationships", action="store_true")
    parser.add_argument("--ensure-info-key", action="store_true")
    parser.add_argument("--batch-size", type=int, default=20)
    return parser.parse_args()


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)

    args = parse_args()
    chardict_path = Path(args.chardict)
    chardict = json.loads(chardict_path.read_text(encoding="utf-8"))
    name_map = build_wiki_name_map(chardict)
    out_dir = Path(args.out_dir)
    cache_dir = Path(args.cache_dir) if args.cache_dir else out_dir / "cache"

    list_source = fetch_source(
        LIST_PAGE,
        cache_dir=cache_dir,
        retries=args.retries,
        retry_sleep=args.retry_sleep,
    )
    character_refs = extract_character_list(list_source, name_map)
    if args.names:
        requested = set(args.names)
        character_refs = [
            ref
            for ref in character_refs
            if ref["name"] in requested or ref["page_name"] in requested
        ]
    if args.limit > 0:
        character_refs = character_refs[: args.limit]

    if args.fetch_only:
        fetched = 0
        pending = 0
        for index, ref in enumerate(character_refs, 1):
            page_name = ref["page_name"]
            if cache_path_for(cache_dir, page_name).exists():
                continue
            pending += 1
            if args.max_uncached > 0 and fetched >= args.max_uncached:
                break
            try:
                fetch_source(
                    page_name,
                    cache_dir=cache_dir,
                    retries=args.retries,
                    retry_sleep=args.retry_sleep,
                )
                fetched += 1
                print(
                    f"[fetch {fetched}] {index}/{len(character_refs)} "
                    f"{page_name}->{ref['name']}"
                )
            except Exception as exc:
                print(f"[fetch-error] {index}/{len(character_refs)} {page_name}: {exc}")
            if args.sleep > 0:
                time.sleep(args.sleep)
        print(f"fetch-only done: fetched={fetched} remaining_seen={pending} cache={cache_dir}")
        return

    records: list[dict] = []
    failures: list[dict[str, str]] = []
    pending: list[str] = []
    uncached_fetches = 0
    for index, ref in enumerate(character_refs, 1):
        name = ref["name"]
        page_name = ref["page_name"]
        cached = cache_path_for(cache_dir, page_name).exists()
        if args.cache_only and not cached:
            pending.append(page_name)
            continue
        if args.max_uncached > 0 and not cached and uncached_fetches >= args.max_uncached:
            pending.extend(ref["page_name"] for ref in character_refs[index - 1 :])
            print(f"[stop] max uncached fetches reached: {args.max_uncached}")
            break
        if not cached:
            uncached_fetches += 1
        try:
            source = fetch_source(
                page_name,
                cache_dir=cache_dir,
                retries=args.retries,
                retry_sleep=args.retry_sleep,
            )
            survey_files = extract_survey_files(source)
            relationships = extract_relationships(source, name_map)
            records.append(
                {
                    "name": name,
                    "page_name": page_name,
                    "url": BASE_URL + urllib.parse.quote(page_name, safe=""),
                    "survey_files": survey_files,
                    "relationships": relationships,
                }
            )
            suffix = "" if page_name == name else f" page={page_name}"
            print(
                f"[{index}/{len(character_refs)}] {name}{suffix}: "
                f"info={len(survey_files)} rel={len(relationships)}"
            )
        except Exception as exc:
            failures.append({"name": name, "page_name": page_name, "error": str(exc)})
            print(f"[{index}/{len(character_refs)}] {name}: ERROR {exc}")
        if args.sleep > 0:
            time.sleep(args.sleep)

    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "character_wiki_extract.json").write_text(
        json.dumps(
            {
                "source_list_page": BASE_URL + urllib.parse.quote(LIST_PAGE, safe=""),
                "character_count": len(character_refs),
                "records": records,
                "failures": failures,
                "pending": pending,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    (out_dir / "relationships.json").write_text(
        json.dumps(
            {rec["name"]: rec.get("relationships", []) for rec in records},
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    write_tasks_markdown(records, out_dir / "info_summary_tasks.md", args.batch_size)
    write_task_batches(records, out_dir / "batches", args.batch_size)

    if args.ensure_info_key or args.apply_relationships:
        updated = apply_relationships_and_info(
            chardict,
            records,
            ensure_info_key=args.ensure_info_key,
            apply_relationships=args.apply_relationships,
        )
        chardict_path.write_text(
            json.dumps(updated, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )

    print(
        "done: "
        f"characters={len(character_refs)} records={len(records)} failures={len(failures)} "
        f"pending={len(pending)} uncached_fetches={uncached_fetches} "
        f"out={out_dir}"
    )


if __name__ == "__main__":
    main()
