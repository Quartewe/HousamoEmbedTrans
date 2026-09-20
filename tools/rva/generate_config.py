#!/usr/bin/env python3
"""
Update app/src/main/assets/runtime.json from Il2CppDumper dump.cs or script.json.

Only the top-level "GameVersion" value and nested "RuntimeConfigs.RVA" object
are replaced. "RuntimeConfigs.Layout" is preserved exactly so manually
maintained offsets are not dropped.

Edit the constants below for the normal manual workflow, or override them with
command line arguments.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


# Manual workflow: fill these three values, then run this script.
GAME_VERSION = "5.19.0"
INPUT_PATH = r"D:\Code\HET_RVA_GetFlow\apk\Il2CppDumper-win-v6.7.46\dump.cs"
OUTPUT_PATH = r"D:\Code\HousamoEmbedTrans\app\src\main\assets\runtime.json"

# These are table columns, not C# field offsets. Verify them after game updates.
TEXT_COLUMNS = {
    "Raw": 8,
    "En": 9,
    "ZhTw": 10,
    "ZhCn": 11,
}


RVA_METHODS = {
    "RVA_FindScenarioData": ("AdvDataManager", "FindScenarioData(", "AdvScenarioData"),
    "RVA_InitBase": ("AdvCommand", "InitFromPageData(", "AdvScenarioPageData"),
    "RVA_InitText": ("AdvCommandText", "InitFromPageData(", "AdvScenarioPageData"),
    "RVA_PageTextChange": ("AdvMessageWindow", "PageTextChange(", "AdvPage"),
    "RVA_AddSelection": ("AdvSelectionManager", "AddSelection(", "string label, string text"),
    "RVA_ShowSelection": ("AdvSelectionManager", "Show(", "public void Show()"),
    "RVA_RemakeText": ("AdvPage", "RemakeText(", "public void RemakeText()"),
    "RVA_UguiSelectionInit": ("AdvUguiSelection", "Init(", "AdvSelection data"),
    "RVA_UguiSelectionClearAll": ("AdvUguiSelectionManager", "ClearAll(", "protected virtual void ClearAll()"),
    "RVA_UiTextSetText": ("Text", "set_text(", "public virtual void set_text(string value)"),
}

SCRIPT_NAMES = {
    "RVA_FindScenarioData": "Utage.AdvDataManager$$FindScenarioData",
    "RVA_InitBase": "Utage.AdvCommand$$InitFromPageData",
    "RVA_InitText": "Utage.AdvCommandText$$InitFromPageData",
    "RVA_PageTextChange": "Utage.AdvMessageWindow$$PageTextChange",
    "RVA_AddSelection": "Utage.AdvSelectionManager$$AddSelection",
    "RVA_ShowSelection": "Utage.AdvSelectionManager$$Show",
    "RVA_RemakeText": "Utage.AdvPage$$RemakeText",
    "RVA_UguiSelectionInit": "Utage.AdvUguiSelection$$Init",
    "RVA_UguiSelectionClearAll": "Utage.AdvUguiSelectionManager$$ClearAll",
    "RVA_UiTextSetText": "UnityEngine.UI.Text$$set_text",
}


def hex_str(value: int) -> str:
    return f"0x{value:X}"


def parse_int(value: Any) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise TypeError(f"unsupported integer value: {value!r}")


def find_class_block(text: str, class_name: str) -> str:
    pattern = re.compile(r"\bclass\s+" + re.escape(class_name) + r"(?=\s|:)[^{]*\{")
    match = pattern.search(text)
    if not match:
        raise ValueError(f"class not found: {class_name}")

    start = match.start()
    brace_pos = text.find("{", match.start())
    depth = 0
    for index in range(brace_pos, len(text)):
        ch = text[index]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]

    raise ValueError(f"class block is not closed: {class_name}")


def extract_method_rva(class_block: str, method_name: str, signature_hint: str) -> int:
    last_rva: Optional[int] = None

    for raw_line in class_block.splitlines():
        line = raw_line.strip()

        rva_match = re.search(r"//\s*RVA:\s*(0x[0-9A-Fa-f]+|-1)", line)
        if rva_match:
            value = rva_match.group(1)
            last_rva = None if value == "-1" else int(value, 16)
            continue

        if method_name not in line:
            continue

        if signature_hint not in line:
            continue

        if last_rva is None:
            raise ValueError(f"method has no concrete RVA: {line}")
        return last_rva

    raise ValueError(f"method not found: {method_name} / {signature_hint}")


def extract_field_offset(class_block: str, *needles: str) -> int:
    for raw_line in class_block.splitlines():
        line = raw_line.strip()
        if not all(needle in line for needle in needles):
            continue

        field_match = re.search(r"//\s*(0x[0-9A-Fa-f]+)\s*$", line)
        if field_match:
            return int(field_match.group(1), 16)

    joined = " + ".join(needles)
    raise ValueError(f"field offset not found: {joined}")


def parse_rva_from_dump_cs(text: str) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for config_key, (class_name, method_name, signature_hint) in RVA_METHODS.items():
        block = find_class_block(text, class_name)
        rva = extract_method_rva(block, method_name, signature_hint)
        result[config_key] = hex_str(rva)
    return result


def parse_layout_from_dump_cs(text: str) -> Dict[str, Any]:
    adv_command = find_class_block(text, "AdvCommand")
    adv_page = find_class_block(text, "AdvPage")
    adv_engine = find_class_block(text, "AdvEngine")
    adv_selection_manager = find_class_block(text, "AdvSelectionManager")
    adv_selection = find_class_block(text, "AdvSelection")
    adv_ugui_selection = find_class_block(text, "AdvUguiSelection")
    adv_scenario_page_data = find_class_block(text, "AdvScenarioPageData")
    row = find_class_block(text, "StringGridRow")
    character = find_class_block(text, "AdvCommandCharacter")
    char_info = find_class_block(text, "AdvCharacterInfo")
    selection = find_class_block(text, "AdvCommandSelection")
    jump = find_class_block(text, "AdvCommandJump")
    scenario_label = find_class_block(text, "AdvScenarioLabelData")
    scenario = find_class_block(text, "AdvScenarioData")

    return {
        "Il2CppString": {
            "Length": "0x10",
            "Chars": "0x14",
        },
        "Il2CppArray": {
            "Length": "0x18",
            "FirstElement": "0x20",
            "PointerSize": 8,
        },
        "Il2CppList": {
            "Items": "0x10",
            "Size": "0x18",
        },
        "AdvPage": {
            "CurrentData": hex_str(extract_field_offset(adv_page, "<CurrentData>")),
            "Engine": hex_str(extract_field_offset(adv_page, "private AdvEngine engine;")),
        },
        "AdvEngine": {
            "SelectionManager": hex_str(
                extract_field_offset(
                    adv_engine,
                    "private AdvSelectionManager selectionManager;",
                )
            ),
        },
        "AdvSelectionManager": {
            "Selections": hex_str(
                extract_field_offset(
                    adv_selection_manager,
                    "private List<AdvSelection> selections;",
                )
            ),
            "IsShowing": hex_str(
                extract_field_offset(
                    adv_selection_manager,
                    "private bool <IsShowing>k__BackingField;",
                )
            ),
        },
        "AdvSelection": {
            "Text": hex_str(
                extract_field_offset(adv_selection, "private string text;")
            ),
            "RowData": hex_str(
                extract_field_offset(adv_selection, "private StringGridRow row;")
            ),
        },
        "AdvUguiSelection": {
            "Text": hex_str(
                extract_field_offset(adv_ugui_selection, "public Text text;")
            ),
            "Data": hex_str(
                extract_field_offset(adv_ugui_selection, "protected AdvSelection data;")
            ),
        },
        "AdvScenarioPageData": {
            "CommandList": hex_str(extract_field_offset(adv_scenario_page_data, "<CommandList>")),
            "TextDataList": hex_str(extract_field_offset(adv_scenario_page_data, "<TextDataList>")),
            "ScenarioLabelData": hex_str(extract_field_offset(adv_scenario_page_data, "<ScenarioLabelData>")),
            "PageNo": hex_str(extract_field_offset(adv_scenario_page_data, "<PageNo>")),
            "MessageWindowName": hex_str(extract_field_offset(adv_scenario_page_data, "<MessageWindowName>")),
        },
        "AdvCommand": {
            "RowData": hex_str(extract_field_offset(adv_command, "<RowData>")),
            "Type": hex_str(extract_field_offset(adv_command, "<Id>")),
        },
        "StringGridRow": {
            "RowIndex": hex_str(extract_field_offset(row, "rowIndex")),
            "Strings": hex_str(extract_field_offset(row, "strings")),
        },
        "AdvCommandCharacter": {
            "CharacterInfo": hex_str(extract_field_offset(character, "characterInfo")),
            "NameText": hex_str(extract_field_offset(char_info, "<NameText>")),
        },
        "AdvCommandSelection": {
            "JumpLabel": hex_str(extract_field_offset(selection, "jumpLabel")),
        },
        "AdvCommandJump": {
            "JumpLabel": hex_str(extract_field_offset(jump, "jumpLabel")),
            "ExpressionParser": hex_str(extract_field_offset(jump, "ExpressionParser")),
            "ConditionColumn": 2,
        },
        "ScenarioLabelData": {
            "PageDataList": hex_str(extract_field_offset(scenario_label, "<PageDataList>")),
            "ScenarioLabel": hex_str(extract_field_offset(scenario_label, "<ScenarioLabel>")),
            "Next": hex_str(extract_field_offset(scenario_label, "<Next>")),
            "CommandList": hex_str(extract_field_offset(scenario_label, "<CommandList>")),
            "ScenarioLabelCommand": hex_str(extract_field_offset(scenario_label, "scenarioLabelCommand")),
        },
        "AdvScenarioData": {
            "Name": hex_str(extract_field_offset(scenario, "private string name")),
            "JumpDataList": hex_str(extract_field_offset(scenario, "jumpDataList")),
            "ScenarioLabels": hex_str(extract_field_offset(scenario, "scenarioLabels")),
        },
        "Il2CppDictionary": {
            "Entries": "0x18",
            "Count": "0x20",
        },
        "DictionaryEntry": {
            "HashCode": "0x00",
            "Key": "0x08",
            "Value": "0x10",
            "Size": "0x18",
        },
        "TextColumns": dict(TEXT_COLUMNS),
    }


def iter_dicts(value: Any) -> Iterable[Dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from iter_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_dicts(child)


def load_script_methods(path: Path) -> List[Dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [item for item in iter_dicts(data) if "Name" in item and "Address" in item]


def parse_rva_from_script_json(path: Path) -> Dict[str, str]:
    methods = load_script_methods(path)
    result: Dict[str, str] = {}

    for config_key, script_name in SCRIPT_NAMES.items():
        matches = [item for item in methods if item.get("Name") == script_name]
        if not matches:
            raise ValueError(f"script.json method not found: {script_name}")

        if config_key == "RVA_AddSelection":
            exact = [
                item
                for item in matches
                if "AddSelection (" in str(item.get("Signature", ""))
            ]
            if exact:
                matches = exact

        if config_key == "RVA_ShowSelection":
            exact = [
                item
                for item in matches
                if "AdvSelectionManager__Show" in str(item.get("Signature", ""))
            ]
            if exact:
                matches = exact

        result[config_key] = hex_str(parse_int(matches[0]["Address"]))

    return result


def parse_rva(input_path: Path) -> Dict[str, str]:
    if input_path.name.lower() == "dump.cs" or input_path.suffix.lower() == ".cs":
        text = input_path.read_text(encoding="utf-8", errors="replace")
        return parse_rva_from_dump_cs(text)

    if input_path.suffix.lower() == ".json":
        return parse_rva_from_script_json(input_path)

    raise ValueError("input must be dump.cs or script.json")


def default_layout() -> Dict[str, Any]:
    return {
        "Il2CppString": {"Length": "0x10", "Chars": "0x14"},
        "Il2CppArray": {"Length": "0x18", "FirstElement": "0x20", "PointerSize": 8},
        "Il2CppList": {"Items": "0x10", "Size": "0x18"},
        "AdvPage": {"CurrentData": "0x88", "Engine": "0xD0"},
        "AdvEngine": {"SelectionManager": "0x48"},
        "AdvSelectionManager": {"Selections": "0x28", "IsShowing": "0x38"},
        "AdvSelection": {"Text": "0x18", "RowData": "0x58"},
        "AdvUguiSelection": {"Text": "0x20", "Data": "0x28"},
        "AdvScenarioPageData": {
            "CommandList": "0x10",
            "TextDataList": "0x18",
            "ScenarioLabelData": "0x20",
            "PageNo": "0x38",
            "MessageWindowName": "0x40",
        },
        "AdvCommand": {"RowData": "0x10", "Type": "0x20"},
        "StringGridRow": {"RowIndex": "0x18", "Strings": "0x20"},
        "AdvCommandCharacter": {"CharacterInfo": "0x38", "NameText": "0x18"},
        "AdvCommandSelection": {"JumpLabel": "0x38"},
        "AdvCommandJump": {"JumpLabel": "0x38", "ExpressionParser": "0x40", "ConditionColumn": 2},
        "ScenarioLabelData": {
            "PageDataList": "0x10",
            "ScenarioLabel": "0x18",
            "Next": "0x20",
            "CommandList": "0x28",
            "ScenarioLabelCommand": "0x30",
        },
        "AdvScenarioData": {
            "Name": "0x10",
            "JumpDataList": "0x28",
            "ScenarioLabels": "0x30",
        },
        "Il2CppDictionary": {"Entries": "0x18", "Count": "0x20"},
        "DictionaryEntry": {"HashCode": "0x00", "Key": "0x08", "Value": "0x10", "Size": "0x18"},
        "TextColumns": dict(TEXT_COLUMNS),
    }


def build_config(input_path: Path, game_version: str) -> Dict[str, Any]:
    if input_path.name.lower() == "dump.cs" or input_path.suffix.lower() == ".cs":
        text = input_path.read_text(encoding="utf-8", errors="replace")
        rva = parse_rva_from_dump_cs(text)
        layout = parse_layout_from_dump_cs(text)
    elif input_path.suffix.lower() == ".json":
        rva = parse_rva_from_script_json(input_path)
        layout = default_layout()
    else:
        raise ValueError("input must be dump.cs or script.json")

    return {
        "GameVersion": game_version,
        "RuntimeConfigs": {
            "RVA": rva,
            "Layout": layout,
        },
    }


def skip_ws(text: str, index: int) -> int:
    while index < len(text) and text[index] in " \t\r\n":
        index += 1
    return index


def scan_json_string_end(text: str, start: int) -> int:
    if start >= len(text) or text[start] != '"':
        raise ValueError(f"expected JSON string at offset {start}")

    index = start + 1
    while index < len(text):
        ch = text[index]
        if ch == "\\":
            index += 2
            continue
        if ch == '"':
            return index + 1
        index += 1

    raise ValueError("unterminated JSON string")


def scan_json_value_end(text: str, start: int) -> int:
    start = skip_ws(text, start)
    if start >= len(text):
        raise ValueError("expected JSON value")

    first = text[start]
    if first == '"':
        return scan_json_string_end(text, start)

    if first in "{[":
        opens = {"{": "}", "[": "]"}
        stack = [opens[first]]
        index = start + 1

        while index < len(text):
            ch = text[index]
            if ch == '"':
                index = scan_json_string_end(text, index)
                continue
            if ch in "{[":
                stack.append(opens[ch])
            elif ch in "}]":
                if not stack or ch != stack[-1]:
                    raise ValueError(f"mismatched JSON bracket at offset {index}")
                stack.pop()
                if not stack:
                    return index + 1
            index += 1

        raise ValueError("unterminated JSON object/array")

    index = start
    while index < len(text) and text[index] not in ",}\r\n":
        index += 1
    return index


def find_top_level_value_span(text: str, key: str) -> Tuple[int, int]:
    index = skip_ws(text, 0)
    if index >= len(text) or text[index] != "{":
        raise ValueError("config root must be a JSON object")

    index += 1
    decoder = json.JSONDecoder()

    while True:
        index = skip_ws(text, index)
        if index >= len(text):
            raise ValueError("unterminated config root object")
        if text[index] == "}":
            break

        key_start = index
        key_end = scan_json_string_end(text, key_start)
        key_name = decoder.decode(text[key_start:key_end])

        index = skip_ws(text, key_end)
        if index >= len(text) or text[index] != ":":
            raise ValueError(f"expected ':' after key {key_name!r}")

        value_start = skip_ws(text, index + 1)
        value_end = scan_json_value_end(text, value_start)

        if key_name == key:
            return value_start, value_end

        index = skip_ws(text, value_end)
        if index < len(text) and text[index] == ",":
            index += 1
            continue
        if index < len(text) and text[index] == "}":
            break
        raise ValueError(f"expected ',' or '}}' after key {key_name!r}")

    raise KeyError(f"top-level key not found: {key}")


def line_indent_at(text: str, index: int) -> str:
    line_start = text.rfind("\n", 0, index) + 1
    prefix = text[line_start:index]
    return prefix[: len(prefix) - len(prefix.lstrip(" \t"))]


def format_object_value(value: Dict[str, Any], newline: str, key_indent: str) -> str:
    # First line is placed after `"RVA": `; following lines retain key depth.
    dumped = json.dumps(value, ensure_ascii=False, indent=4)
    lines = dumped.splitlines()
    return newline.join([lines[0], *(key_indent + line for line in lines[1:])])


def find_nested_value_span(text: str, parent_key: str, child_key: str) -> Tuple[int, int]:
    parent_start, parent_end = find_top_level_value_span(text, parent_key)
    child_start, child_end = find_top_level_value_span(
        text[parent_start:parent_end],
        child_key,
    )
    return parent_start + child_start, parent_start + child_end


def replace_runtime_values(
    runtime_text: str,
    game_version: str,
    rva: Dict[str, str],
) -> str:
    # Validate first, then patch only the two game-version-bound values. This
    # keeps hand-maintained Layout fields and surrounding formatting untouched.
    json.loads(runtime_text)

    version_start, version_end = find_top_level_value_span(
        runtime_text,
        "GameVersion",
    )
    rva_start, rva_end = find_nested_value_span(
        runtime_text,
        "RuntimeConfigs",
        "RVA",
    )
    newline = "\r\n" if "\r\n" in runtime_text else "\n"
    replacements = [
        (
            version_start,
            version_end,
            json.dumps(game_version, ensure_ascii=False),
        ),
        (
            rva_start,
            rva_end,
            format_object_value(
                rva,
                newline,
                line_indent_at(runtime_text, rva_start),
            ),
        ),
    ]

    result = runtime_text
    for start, end, replacement in sorted(replacements, reverse=True):
        result = result[:start] + replacement + result[end:]
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate HousamoEmbedTrans runtime.json")
    parser.add_argument("--version", default=GAME_VERSION, help="game version string")
    parser.add_argument("--input", default=INPUT_PATH, help="path to dump.cs or script.json")
    parser.add_argument("--output", default=OUTPUT_PATH, help="path to output runtime.json")
    parser.add_argument("--stdout", action="store_true", help="print JSON instead of writing")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.is_file():
        raise FileNotFoundError(input_path)

    rva = parse_rva(input_path)
    output_path = Path(args.output)

    if output_path.is_file():
        old_text = output_path.read_text(encoding="utf-8")
        text = replace_runtime_values(old_text, args.version, rva)
    else:
        config = build_config(input_path, args.version)
        text = json.dumps(config, ensure_ascii=False, indent=4) + "\n"

    if args.stdout:
        print(text, end="")
        return

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(text, encoding="utf-8")

    print(f"wrote {output_path}")
    print(f"GameVersion = {args.version}")
    print("RVA:")
    for key, value in rva.items():
        print(f"  {key} = {value}")


if __name__ == "__main__":
    main()
