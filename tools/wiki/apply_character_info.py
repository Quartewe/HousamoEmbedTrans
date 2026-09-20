#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def ordered_entry_with_info(entry: dict, info_value: str | None) -> dict:
    rebuilt = {}
    inserted = False
    for key, value in entry.items():
        if key == "Info":
            rebuilt[key] = info_value if info_value is not None else value
            inserted = True
            continue
        if key == "description" and not inserted:
            rebuilt["Info"] = info_value if info_value is not None else ""
            inserted = True
        rebuilt[key] = value
    if not inserted:
        rebuilt["Info"] = info_value if info_value is not None else ""
    return rebuilt


def load_summaries(summary_dir: Path) -> dict[str, str]:
    summaries: dict[str, str] = {}
    if not summary_dir.exists():
        return summaries
    paths = sorted(summary_dir.glob("info_batch_*.json"))
    paths += sorted(summary_dir.glob("info_missing_*.json"))
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        for name, info in data.items():
            if not isinstance(info, str):
                raise ValueError(f"{path}: summary for {name} is not a string")
            summaries[name] = info.strip()
    return summaries


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chardict", default="app/src/main/assets/chardict.json")
    parser.add_argument("--extract", default="build/wiki_character_extract/character_wiki_extract.json")
    parser.add_argument("--summary-dir", default="build/wiki_character_extract/summaries")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    chardict_path = Path(args.chardict)
    extract_path = Path(args.extract)
    summary_dir = Path(args.summary_dir)

    chardict = json.loads(chardict_path.read_text(encoding="utf-8"))
    extract = json.loads(extract_path.read_text(encoding="utf-8"))
    summaries = load_summaries(summary_dir)
    records = {rec["name"]: rec for rec in extract.get("records", [])}

    updated = {}
    info_applied = 0
    info_missing = 0
    rel_applied = 0

    for name, entry in chardict.items():
        info = summaries.get(name)
        item = ordered_entry_with_info(dict(entry), info)
        if info:
            info_applied += 1
        elif name in records:
            info_missing += 1

        rels = records.get(name, {}).get("relationships") or []
        if rels:
            item["relationships"] = rels
            rel_applied += 1

        updated[name] = item

    print(
        f"summaries={len(summaries)} info_applied={info_applied} "
        f"info_missing_for_extracted={info_missing} rel_applied={rel_applied}"
    )
    if args.dry_run:
        return

    chardict_path.write_text(
        json.dumps(updated, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


if __name__ == "__main__":
    main()
