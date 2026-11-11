#!/usr/bin/env python3
"""Write the JSON side of the two 45 degree desk corners: block model files for every OBJ, item models, blockstates, loot tables.

The geometry comes from art/models/control_desk/build_desk_corner.py (Blender), which writes the OBJ files this script
wraps. Standard library only. Re-run after changing the part names or the finish list; recipes and lang keys are written
by hand (and the recipes by tools-generate-mekanism-recipes.py for the Mekanism variants).
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))
PARTS = ("origin", "left", "deep", "deep_left")
KINDS = ("inside", "outside")
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
                {"condition": "minecraft:block_state_property", "block": "bwr:" + block, "properties": {"part": "origin"}},
                {"condition": "minecraft:survives_explosion"},
            ],
        }],
    }


def main():
    for kind in KINDS:
        block = "control_panel_desk_corner_" + kind
        variants = {}
        for part in PARTS:
            name = "desk_corner_%s_%s" % (kind, part)
            write(os.path.join(ASSETS, "models/block/control_panel/%s.json" % name), obj_model(name))
            for facing, y in FACINGS:
                variants["facing=%s,part=%s" % (facing, part)] = {"model": "bwr:block/control_panel/" + name, "y": y}
        write(os.path.join(ASSETS, "blockstates/%s.json" % block), {"variants": variants})
        item = "desk_corner_%s_item" % kind
        write(os.path.join(ASSETS, "models/block/control_panel/%s.json" % item), obj_model(item))
        write(os.path.join(ASSETS, "models/item/%s.json" % block), {
            "parent": "bwr:block/control_panel/" + item,
            "display": {
                "gui": {"rotation": [30, 225, 0], "scale": [0.42, 0.42, 0.42]},
                "ground": {"scale": [0.28, 0.28, 0.28]},
                "fixed": {"scale": [0.36, 0.36, 0.36]},
                "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.22, 0.22, 0.22]},
                "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.28, 0.28, 0.28]},
            },
        })
        write(os.path.join(DATA, "loot_table/blocks/%s.json" % block), loot(block))
        print("wrote", block)


main()
