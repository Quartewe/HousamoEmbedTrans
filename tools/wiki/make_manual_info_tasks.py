#!/usr/bin/env python3
import json
import urllib.parse
from pathlib import Path


BASE_URL = "https://wikiwiki.jp/housamo/"


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_summaries(summary_dir: Path) -> dict[str, str]:
    summaries: dict[str, str] = {}
    if not summary_dir.exists():
        return summaries
    for pattern in ("info_batch_*.json", "info_missing_*.json", "manual_info_*.json"):
        for path in sorted(summary_dir.glob(pattern)):
            data = load_json(path)
            for name, info in data.items():
                summaries[name] = str(info).strip()
    return summaries


def write_record(f, index: int, rec: dict) -> None:
    f.write(f"## {index}. {rec['name']}\n\n")
    f.write(f"- page: {rec.get('url') or BASE_URL + urllib.parse.quote(rec['name'], safe='')}\n")
    rels = rec.get("relationships") or []
    if rels:
        f.write("- relationships: " + ", ".join(f"{r['type']}->{r['target']}" for r in rels) + "\n")
    f.write("- Info: \n\n")
    for item in rec.get("survey_files") or []:
        f.write(f"### {item['title']}\n\n")
        f.write(item["text"].strip() + "\n\n")


def main() -> None:
    out_dir = Path("build/wiki_character_extract")
    extract = load_json(out_dir / "character_wiki_extract.json")
    summaries = load_summaries(out_dir / "summaries")

    records = extract.get("records", [])
    missing_info = [rec for rec in records if not summaries.get(rec["name"])]
    pending = extract.get("pending", [])

    manual_info_path = out_dir / "manual_missing_info.md"
    with manual_info_path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("# Housamo manual Info tasks for extracted characters\n\n")
        f.write("这些角色页面已经抓取完成，但还没有 `Info` 总结。请把本文件交给其他模型分批处理。\n\n")
        f.write("输出格式要求：返回一个 JSON 对象，键为角色日文名，值为 1 到 3 句中立日文 `Info`。不要逐字翻译原文，不要加入页面没有支持的猜测。\n\n")
        f.write("建议保留信息：身份、性格、神器/権能、重要关系、叙事定位。关系表已单独抽取，Info 不必重复列完整关系。\n\n")
        for index, rec in enumerate(missing_info, 1):
            write_record(f, index, rec)

    pending_path = out_dir / "manual_pending_fetch_list.md"
    with pending_path.open("w", encoding="utf-8", newline="\n") as f:
        f.write("# Housamo pending wiki pages\n\n")
        f.write("这些角色在 `☆３` 列表和 `chardict.json` 中匹配到了，但当前还没有抓到 wiki source 缓存。等 wiki 限速恢复后继续抓取，或手动分配给其他模型打开页面处理。\n\n")
        f.write("每个页面需要提取：全部 `調査ファイル` 的中立总结作为 `Info`，以及 `相関` 表中 `自分から` 方向的 `好意` / `苦手` 关系。\n\n")
        for index, name in enumerate(pending, 1):
            url = BASE_URL + urllib.parse.quote(name, safe="")
            f.write(f"{index}. {name} - {url}\n")

    status = {
        "target_character_count": extract.get("character_count", 0),
        "extracted_records": len(records),
        "summaries_available": len(summaries),
        "missing_info_for_extracted": len(missing_info),
        "pending_fetch": len(pending),
        "manual_missing_info": str(manual_info_path),
        "manual_pending_fetch_list": str(pending_path),
        "manual_dispatch_pack": str(out_dir / "manual_dispatch_pack.md"),
    }
    (out_dir / "manual_status.json").write_text(
        json.dumps(status, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )

    dispatch = out_dir / "manual_dispatch_pack.md"
    dispatch.write_text(
        "# Housamo manual dispatch pack\n\n"
        "## Current status\n\n"
        "```json\n"
        + json.dumps(status, ensure_ascii=False, indent=2)
        + "\n```\n\n---\n\n"
        + manual_info_path.read_text(encoding="utf-8")
        + "\n\n---\n\n"
        + pending_path.read_text(encoding="utf-8"),
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps(status, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
