#!/usr/bin/env python3
"""Write the JSON side of the 45 degree desk corners (inside and outside, one to four angled units long): block model files
for every OBJ, item models, blockstates, loot tables.

The geometry comes from art/models/control_desk/build_desk_corner.py (Blender), which writes the OBJ files this script wraps
and art/models/control_desk/desk_corner_parts.json (the blocks of each corner). Standard library only. Re-run after
changing the lengths, the finish list or the models; recipes and lang keys are written by hand.
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")
PARTS = os.path.join(ROOT, "art/models/control_desk/desk_corner_parts.json")

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
KINDS = ("inside", "outside")
LENGTHS = (1, 2, 3, 4)
FINISHES = ("cream", "tan", "grey", "light_blue")


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


def block_name(kind, n):
    return "control_panel_desk_corner_" + kind + ("" if n == 1 else "_%d" % n)


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
                {"condition": "minecraft:block_state_property", "block": "bwr:" + block, "properties": {"left": "0", "deep": "0"}},
                {"condition": "minecraft:survives_explosion"},
            ],
        }],
    }


def main():
    plan = json.load(open(PARTS, encoding="utf-8"))
    for kind in KINDS:
        for n in LENGTHS:
            block = block_name(kind, n)
            variants = {}
            for left, deep in plan["%s_%d" % (kind, n)]:
                name = "desk_corner_%s_%d_%d_%d" % (kind, n, left, deep)
                write(os.path.join(ASSETS, "models/block/control_panel/%s.json" % name), obj_model(name))
                for facing, y in FACINGS:
                    variants["facing=%s,left=%d,deep=%d" % (facing, left, deep)] = {"model": "bwr:block/control_panel/" + name, "y": y}
            # combinations that are no block of this corner are never placed, but every state needs a model: the origin's
            for left in range(5):
                for deep in range(5):
                    if [left, deep] not in plan["%s_%d" % (kind, n)]:
                        for facing, y in FACINGS:
                            variants["facing=%s,left=%d,deep=%d" % (facing, left, deep)] = {
                                "model": "bwr:block/control_panel/desk_corner_%s_%d_0_0" % (kind, n), "y": y}
            write(os.path.join(ASSETS, "blockstates/%s.json" % block), {"variants": variants})
            item = "desk_corner_%s_%d_item" % (kind, n)
            write(os.path.join(ASSETS, "models/block/control_panel/%s.json" % item), obj_model(item))
            scale = 0.42 * 2 / (n + 1)
            write(os.path.join(ASSETS, "models/item/%s.json" % block), {
                "parent": "bwr:block/control_panel/" + item,
                "display": {
                    "gui": {"rotation": [30, 225, 0], "scale": [round(scale, 3)] * 3},
                    "ground": {"scale": [round(scale * 0.66, 3)] * 3},
                    "fixed": {"scale": [round(scale * 0.86, 3)] * 3},
                    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [round(scale * 0.52, 3)] * 3},
                    "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [round(scale * 0.66, 3)] * 3},
                },
            })
            write(os.path.join(DATA, "loot_table/blocks/%s.json" % block), loot(block))
            print("wrote", block, len(plan["%s_%d" % (kind, n)]), "blocks")


main()
