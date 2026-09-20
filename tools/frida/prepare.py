"""Bundle a diagnostic with local runtime data. No Frida/device connection."""
import argparse
import json
from pathlib import Path
import re


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SCRIPTS = (
    "diag_scenario_static_text",
    "diag_scenario_branch_graph",
    "diag_characteroff_speaker",
)


def number(config, path):
    value = config
    for key in path.split("."):
        value = value[key]
    if isinstance(value, str):
        value = int(value, 16 if value.lower().startswith("0x") else 10)
    if type(value) is not int or not 0 <= value <= 2**53 - 1:
        raise ValueError(f"Invalid runtime integer: {path}")
    if path.startswith("RVA.") and value == 0:
        raise ValueError(f"Missing RVA: {path}")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("script", choices=SCRIPTS)
    parser.add_argument("--runtime", type=Path,
                        default=ROOT / "app/src/main/assets/runtime.json")
    parser.add_argument("--scene-filter", default="",
                        help="Branch graph only: substring of scene name or requested label")
    args = parser.parse_args()
    if args.scene_filter and args.script != "diag_scenario_branch_graph":
        parser.error("--scene-filter applies only to diag_scenario_branch_graph")
    source = (HERE / f"{args.script}.js").read_text(encoding="utf-8")
    try:
        runtime = json.loads(args.runtime.read_text(encoding="utf-8-sig"))
        version = runtime["GameVersion"]
        if not isinstance(version, str) or not version.strip():
            raise ValueError("GameVersion must be a nonempty string")
        paths = set(re.findall(r"runtimeNumber\('([^']+)'\)", source))
        paths.add("Layout.Il2CppArray.PointerSize")
        values = {path: number(runtime["RuntimeConfigs"], path) for path in sorted(paths)}
        if values["Layout.Il2CppArray.PointerSize"] != 8:
            raise ValueError("These diagnostics support only the arm64 layout")
    except (OSError, KeyError, ValueError, TypeError) as error:
        parser.error(f"Cannot load runtime {args.runtime}: {error}")

    output = ROOT / "build/frida" / f"{args.script}.js"
    if output.resolve() == args.runtime.resolve():
        parser.error("Runtime input cannot also be the bundle output")
    header = (
        "'use strict';\n(() => {\n"
        f"const HET_VALUES = Object.freeze({json.dumps(values)});\n"
        f"const HET_OPTIONS = {json.dumps({'sceneFilter': args.scene_filter})};\n"
        "function runtimeNumber(path) {\n"
        "  if (!Object.prototype.hasOwnProperty.call(HET_VALUES, path))\n"
        "    throw new Error('Missing runtime field: ' + path);\n"
        "  return HET_VALUES[path];\n}\n"
        "if (Process.arch !== 'arm64' || Process.pointerSize !== "
        "runtimeNumber('Layout.Il2CppArray.PointerSize'))\n"
        "  throw new Error('Runtime ABI mismatch: expected arm64');\n"
        f"console.log({json.dumps('[runtime] GameVersion=' + version + ' source=' + str(args.runtime.resolve()))});\n"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(header + source + "\n})();\n", encoding="utf-8", newline="\n")
    print(output)


if __name__ == "__main__":
    main()
