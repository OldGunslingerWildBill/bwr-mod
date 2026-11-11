#!/usr/bin/env python3
"""Write the JSON side of the modular 45 degree runs (CONTROL-PANELS-SPEC section 14): block model files for every OBJ,
item models, blockstates, loot tables, and data/bwr/angled_runs/pieces.json (the plan, read by the game tests).

The geometry comes from art/models/control_desk/build_angled_runs.py (Blender), which writes the OBJ files this script wraps
and art/models/control_desk/angled_runs.json (run_plan.py: the parts of each piece). Standard library only.
Blocks: control_panel_<fam>_run_<kind>_<role>, fam desk|tall, kind inside|outside, role bend_a|unit|bend_b.
"""
import json
import os
import shutil

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")
PLAN = os.path.join(ROOT, "art/models/control_desk/angled_runs.json")

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
FAMS = ("desk", "tall")
KINDS = ("inside", "outside")
ROLES = (("a", "bend_a"), ("unit", "unit"), ("b", "bend_b"))
FINISHES = ("cream", "tan", "grey", "light_blue")
PART_RANGE = 10                                     # the part property is 0..9


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def obj_model(name):
    return {
        "loader": "neoforge:obj",
        "model": "bwr:models/block/control_panel/%s.obj" % name,
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": True,
        "emissive_ambient": False,
        "ambientocclusion": False,
        "textures": {"particle": "bwr:block/control_panel/bezel"},
        "render_type": "minecraft:solid",
    }


def loot(block):
    children = []
    for finish in FINISHES:
        children.append({
            "type": "minecraft:item",
            "name": "bwr:" + block,
            "conditions": [{"condition": "minecraft:block_state_property", "block": "bwr:" + block,
                            "properties": {"finish": finish}}],
            "functions": [{"function": "minecraft:set_components",
                           "components": {"minecraft:block_state": {"finish": finish}}}],
        })
    children.append({"type": "minecraft:item", "name": "bwr:" + block})
    return {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:alternatives", "children": children}],
            "conditions": [
                {"condition": "minecraft:block_state_property", "block": "bwr:" + block, "properties": {"part": "0"}},
                {"condition": "minecraft:survives_explosion"},
            ],
        }],
    }


def main():
    plan = json.load(open(PLAN, encoding="utf-8"))
    for fam in FAMS:
        for kind in KINDS:
            pieces = plan["%s_%s" % (fam, kind)]
            for role, role_name in ROLES:
                block = "control_panel_%s_run_%s_%s" % (fam, kind, role_name)
                parts = pieces[role]["parts"]
                assert len(parts) <= PART_RANGE, (block, len(parts))
                variants = {}
                for j in range(PART_RANGE):
                    shown = j if j < len(parts) else 0
                    for mirror in (False, True):
                        name = "run_%s_%s_%s_%d%s" % (fam, kind, role_name, shown, "_m" if mirror else "")
                        if j < len(parts):
                            write(os.path.join(ASSETS, "models/block/control_panel/%s.json" % name), obj_model(name))
                        for facing, y in FACINGS:
                            variants["facing=%s,mirror=%s,part=%d" % (facing, "true" if mirror else "false", j)] = {
                                "model": "bwr:block/control_panel/" + name, "y": y}
                write(os.path.join(ASSETS, "blockstates/%s.json" % block), {"variants": variants})
                item = "run_%s_%s_%s_item" % (fam, kind, role_name)
                write(os.path.join(ASSETS, "models/block/control_panel/%s.json" % item), obj_model(item))
                span = max(max(p[0] for p in parts) - min(p[0] for p in parts),
                           max(p[1] for p in parts) - min(p[1] for p in parts),
                           max(p[2] for p in parts) - min(p[2] for p in parts)) + 1
                scale = round(0.42 * 2 / (span + 0.5) if fam == "desk" else 0.42 * 2 / (span + 1.5), 3)
                write(os.path.join(ASSETS, "models/item/%s.json" % block), {
                    "parent": "bwr:block/control_panel/" + item,
                    "display": {
                        "gui": {"rotation": [30, 225, 0], "scale": [scale] * 3},
                        "ground": {"scale": [round(scale * 0.66, 3)] * 3},
                        "fixed": {"scale": [round(scale * 0.86, 3)] * 3},
                        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [round(scale * 0.52, 3)] * 3},
                        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [round(scale * 0.66, 3)] * 3},
                    },
                })
                write(os.path.join(DATA, "loot_table/blocks/%s.json" % block), loot(block))
                print("wrote", block, len(parts), "parts")
    out = os.path.join(DATA, "angled_runs/pieces.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    shutil.copyfile(PLAN, out)


main()
