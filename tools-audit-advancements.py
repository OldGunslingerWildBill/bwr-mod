#!/usr/bin/env python3
"""Validate Phase L3 advancement assets. No dependencies and no repository writes.

Run: py -3.13 tools-audit-advancements.py --self-test
Staged review also supports --repo, --resources and --lang-additions.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import sys

MILESTONES = {
    "first_criticality", "power_to_grid", "survive_scram", "refuel",
    "seal_containment", "build_control_room", "lower_head_failure", "melt_through",
}
EXPECTED = MILESTONES | {"root"}
DARK = {"lower_head_failure", "melt_through"}


def validate(definitions, language, registered, models):
    problems = []
    def require(ok, message):
        if not ok:
            problems.append(message)

    require(set(definitions) == EXPECTED,
            f"milestone definitions differ: missing={sorted(EXPECTED-set(definitions))}, extra={sorted(set(definitions)-EXPECTED)}")
    icon_owners = {}
    for name, data in definitions.items():
        if not isinstance(data, dict):
            problems.append(f"{name}: definition must be an object")
            continue
        display = data.get("display", {})
        icon = display.get("icon", {})
        require(icon.get("id") in registered, f"{name}: unregistered icon {icon.get('id')}")
        require(icon.get("id") in models, f"{name}: icon item model missing")
        icon_id = icon.get("id")
        require(icon_id not in icon_owners, f"{name}: duplicate icon {icon_id} also used by {icon_owners.get(icon_id)}")
        icon_owners[icon_id] = name
        require(icon.get("count", 1) == 1 and "item" not in icon, f"{name}: invalid 1.21 icon stack")
        for field in ("title", "description"):
            text = display.get(field, {})
            key = text.get("translate") if isinstance(text, dict) else None
            require(bool(key) and isinstance(language.get(key), str) and bool(language.get(key).strip()),
                    f"{name}: {field} must have a nonempty translation")
        require(display.get("frame") in {"task", "goal", "challenge"}, f"{name}: invalid frame")
        require(not data.get("rewards"), f"{name}: cosmetic milestone must not grant rewards")
        require(data.get("requirements") == [["achieved"]], f"{name}: criterion requirement changed")
        criteria = data.get("criteria", {})
        require(set(criteria) == {"achieved"}, f"{name}: expected exactly criterion achieved")
        expected_trigger = "minecraft:inventory_changed" if name == "root" else "minecraft:impossible"
        require(criteria.get("achieved", {}).get("trigger") == expected_trigger,
                f"{name}: incorrect trigger; milestones must depend on runtime events")
        if name in MILESTONES:
            require("conditions" not in criteria.get("achieved", {}), f"{name}: impossible criterion has conditions")
            require(data.get("parent", "").startswith("bwr:milestones/"), f"{name}: missing milestone parent")
        if name in DARK:
            require(display.get("hidden") is True and display.get("announce_to_chat") is False,
                    f"{name}: dark milestone must stay hidden and not broadcast")
        if name == "root":
            require("parent" not in data, "root: must have no parent")
            items = criteria.get("achieved", {}).get("conditions", {}).get("items", [])
            require(items == [{"items": "bwr:reactor_controller"}], "root: wrong inventory visibility condition")
        seen = {name}
        parent = data.get("parent")
        while parent:
            parent_name = parent.removeprefix("bwr:milestones/")
            if parent_name not in definitions:
                problems.append(f"{name}: missing parent {parent}")
                break
            if parent_name in seen:
                problems.append(f"{name}: parent cycle")
                break
            seen.add(parent_name)
            parent = definitions[parent_name].get("parent")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--resources", type=Path, help="Optional staged resources root")
    parser.add_argument("--lang-additions", type=Path)
    parser.add_argument("--self-test", action="store_true", help="Prove representative corruptions fail")
    args = parser.parse_args()
    repo = args.repo.resolve()
    base_resources = repo / "mod/src/main/resources"
    resources = args.resources.resolve() if args.resources else base_resources
    try:
        definitions = {p.stem: json.loads(p.read_text(encoding="utf-8-sig"))
                       for p in (resources / "data/bwr/advancement/milestones").glob("*.json")}
        language = json.loads((base_resources / "assets/bwr/lang/en_us.json").read_text(encoding="utf-8-sig"))
        if args.lang_additions:
            language.update(json.loads(args.lang_additions.read_text(encoding="utf-8-sig")))
        java = repo / "mod/src/main/java/dev/bwr/mod/registry"
        registered = set()
        for file in ("BwrBlocks.java", "BwrItems.java"):
            source = (java / file).read_text(encoding="utf-8-sig")
            registered.update("bwr:" + name for name in re.findall(r'(?:BLOCKS|ITEMS)\.register(?:SimpleItem)?\(\s*"([a-z0-9_]+)"', source))
        models = {"bwr:" + p.relative_to(base_resources / "assets/bwr/models/item").with_suffix("").as_posix()
                  for p in (base_resources / "assets/bwr/models/item").rglob("*.json")}
        problems = validate(definitions, language, registered, models)
        if args.self_test and not problems:
            mutations = [
                ("missing definition", lambda d: d.pop("power_to_grid")),
                ("free trigger", lambda d: d["power_to_grid"]["criteria"]["achieved"].update(trigger="minecraft:tick")),
                ("bad icon", lambda d: d["refuel"]["display"]["icon"].update(id="bwr:does_not_exist")),
                ("duplicate icon", lambda d: d["refuel"]["display"]["icon"].update(id=d["first_criticality"]["display"]["icon"]["id"])),
                ("missing translation", lambda d: d["refuel"]["display"]["title"].update(translate="missing.key")),
                ("parent cycle", lambda d: d["first_criticality"].update(parent="bwr:milestones/first_criticality")),
                ("missing parent", lambda d: d["first_criticality"].update(parent="bwr:milestones/missing")),
                ("free reward", lambda d: d["survive_scram"].update(rewards={"experience": 100})),
                ("visible accident", lambda d: d["lower_head_failure"]["display"].update(hidden=False)),
                ("empty criterion", lambda d: d["melt_through"].update(criteria={})),
            ]
            for label, mutate in mutations:
                changed = copy.deepcopy(definitions)
                mutate(changed)
                if not validate(changed, language, registered, models):
                    problems.append(f"self-test failed to detect {label}")
            print(f"Audit negative checks: {len(mutations)} representative corruptions")
        for problem in problems:
            print("ERROR:", problem)
        print(f"Advancement audit: {len(definitions)} definitions, {len(problems)} errors")
        return 1 if problems else 0
    except (OSError, ValueError, TypeError, AttributeError) as error:
        print("ERROR:", error)
        return 1


if __name__ == "__main__":
    sys.exit(main())
