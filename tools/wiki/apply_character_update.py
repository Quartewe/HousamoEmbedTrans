#!/usr/bin/env python3
"""Preview or apply one AI-generated character batch to local CharDict (no Git)."""
import argparse
import copy
import difflib
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DICT = ROOT / "app/src/main/assets/term/chardict.json"
ARRAY_FIELDS = {"school", "guild", "origin_world"}
TEXT_FIELDS = {"info", "description", "speech_style"}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def parse_json(text):
    def invalid_constant(value):
        raise ValueError(f"Invalid JSON constant: {value}")
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def load(path):
    return parse_json(path.read_text(encoding="utf-8-sig"))


def merge(dictionary, generated, requested):
    if not isinstance(generated, dict) or set(generated) != {"characters", "relationships"}:
        raise ValueError("Expected exactly characters and relationships")
    characters = generated["characters"]
    if not isinstance(characters, dict) or set(characters) != set(requested):
        raise ValueError("characters must contain exactly the batch's requested dictionary keys")
    updated = copy.deepcopy(dictionary)
    for name, patch in characters.items():
        if not isinstance(patch, dict) or not set(patch) <= ARRAY_FIELDS | TEXT_FIELDS:
            raise ValueError(f"{name}: unsupported fields (language values/alias are not writable)")
        if not isinstance(patch.get("info"), str) or not patch["info"].strip():
            raise ValueError(f"{name}: nonempty lowercase info is required")
        for key, value in patch.items():
            if key in ARRAY_FIELDS:
                if not isinstance(value, list) or any(not isinstance(v, str) or not v.strip() for v in value):
                    raise ValueError(f"{name}.{key}: expected array of nonempty strings")
                if len(set(value)) != len(value):
                    raise ValueError(f"{name}.{key}: duplicate values")
            elif not isinstance(value, str):
                raise ValueError(f"{name}.{key}: expected string")
        if name not in updated:
            updated[name] = {
                "alias": [], "en": "", "zh-tw": "", "zh-cn": "",
                "school": [], "guild": [], "origin_world": [],
                "relationships": [], "info": "", "description": "", "speech_style": "",
            }
        updated[name].update(patch)
    edges = generated["relationships"]
    if not isinstance(edges, list):
        raise ValueError("relationships must be an array of directed edges")
    for edge in edges:
        if not isinstance(edge, dict) or set(edge) != {"source", "target", "type"}:
            raise ValueError("Each relationship needs exactly source, target, type")
        if any(not isinstance(value, str) for value in edge.values()):
            raise ValueError("Relationship fields must be strings")
        source, target, kind = edge["source"], edge["target"], edge["type"]
        if kind not in {"好意", "苦手"} or source == target:
            raise ValueError(f"Invalid relationship: {edge}")
        if source not in requested and target not in requested:
            raise ValueError(f"Relationship outside requested scope: {edge}")
        if source not in updated or target not in updated:
            raise ValueError(f"Unknown relationship endpoint; resolve exact dictionary key first: {edge}")
        relation = {"target": target, "type": kind}
        existing = updated[source].setdefault("relationships", [])
        if not isinstance(existing, list):
            raise ValueError(f"{source}.relationships is not an array")
        if relation not in existing:
            existing.append(relation)
    return updated


def render_changes(text, before, after):
    """Replace changed top-level values only; preserve unrelated bytes and key order."""
    decoder = json.JSONDecoder()
    newline = "\r\n" if "\r\n" in text else "\n"
    cursor = text.index("{") + 1
    replacements = []
    while True:
        while text[cursor].isspace() or text[cursor] == ",":
            cursor += 1
        if text[cursor] == "}":
            break
        name, cursor = decoder.raw_decode(text, cursor)
        while text[cursor].isspace() or text[cursor] == ":":
            cursor += 1
        start = cursor
        _, cursor = decoder.raw_decode(text, cursor)
        if before[name] != after[name]:
            value = json.dumps(after[name], ensure_ascii=False, indent=2).replace("\n", "\n  ")
            replacements.append((start, cursor, value))
    added = {key: value for key, value in after.items() if key not in before}
    if added:
        insertion = cursor
        while text[insertion - 1].isspace():
            insertion -= 1
        body = json.dumps(added, ensure_ascii=False, indent=2)[2:-2]
        replacements.append((insertion, cursor, (",\n" if before else "\n") + body + "\n"))
    for start, end, replacement in reversed(replacements):
        text = text[:start] + replacement.replace("\n", newline) + text[end:]
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch", type=Path, required=True)
    parser.add_argument("--result", type=Path, help="Defaults to BATCH/generated.json")
    parser.add_argument("--chardict", type=Path, default=DEFAULT_DICT)
    parser.add_argument("--write", action="store_true", help="Apply after successful preview; otherwise read-only")
    args = parser.parse_args()
    manifest = load(args.batch / "manifest.json")
    requested = [record["name"] for record in manifest["records"]]
    if not requested or len(set(requested)) != len(requested):
        raise ValueError("Empty or duplicate batch names")
    original = args.chardict.read_bytes()
    text = original.decode("utf-8-sig")
    dictionary = parse_json(text)
    updated = merge(dictionary, load(args.result or args.batch / "generated.json"), requested)
    changed = [name for name in updated if dictionary.get(name) != updated[name]]
    if not changed:
        print("No changes (already applied).")
        return
    if hashlib.sha256(original).hexdigest() != manifest["chardict_sha256"]:
        raise ValueError("CharDict changed since fetch; prepare a new batch and review against current entries")
    # Keep the existing newline convention and BOM; do not reserialize unrelated entries.
    rendered = render_changes(text, dictionary, updated)
    if parse_json(rendered) != updated:
        raise ValueError("Rendered dictionary does not match intended update")
    print("Changed entries: " + ", ".join(changed))
    print("".join(difflib.unified_diff(text.splitlines(True), rendered.splitlines(True),
                                    fromfile="chardict (before)", tofile="chardict (after)")))
    if not args.write:
        print("Preview only; rerun with --write to apply.")
        return
    output = rendered.encode("utf-8")
    if original.startswith(b"\xef\xbb\xbf"):
        output = b"\xef\xbb\xbf" + output
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=args.chardict.parent, prefix=".chardict-", suffix=".tmp", delete=False) as file:
            temporary = Path(file.name)
            file.write(output)
            file.flush()
            os.fsync(file.fileno())
        if args.chardict.read_bytes() != original:
            raise ValueError("CharDict changed during apply; no write performed")
        os.replace(temporary, args.chardict)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(f"Written: {args.chardict}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as error:
        sys.exit(str(error))
