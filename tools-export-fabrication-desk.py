#!/usr/bin/env python3
"""Write the JSON side of the fabrication desk: the block model for the OBJ, the blockstate, the item model, the loot table.

The geometry comes from art/models/fabrication_desk/build_fabrication_desk.py (headless Blender), which writes the OBJ this
script wraps. Standard library only. Run it again after changing the model's name or the block's properties; the recipes
(data/bwr/recipe/fabrication_desk*.json) and the language keys are written by hand, and the Mekanism recipe by
tools-generate-mekanism-recipes.py.
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")
MODELS = os.path.join(ASSETS, "models/block/fabrication_desk")

FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def main():
    if not os.path.isfile(os.path.join(MODELS, "fabrication_desk.obj")):
        raise SystemExit("run art/models/fabrication_desk/build_fabrication_desk.py in Blender first")
    write(os.path.join(MODELS, "fabrication_desk.json"), {
        "loader": "neoforge:obj",
        "model": "bwr:models/block/fabrication_desk/fabrication_desk.obj",
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": True,
        "emissive_ambient": False,
        "ambientocclusion": False,
        "textures": {"particle": "minecraft:block/white_concrete"},
        "render_type": "minecraft:solid",
    })
    variants = {}
    for facing, y in FACINGS:
        entry = {"model": "bwr:block/fabrication_desk/fabrication_desk"}
        if y:
            entry["y"] = y
        variants["facing=" + facing] = entry
    write(os.path.join(ASSETS, "blockstates/fabrication_desk.json"), {"variants": variants})
    write(os.path.join(ASSETS, "models/item/fabrication_desk.json"), {
        "parent": "bwr:block/fabrication_desk/fabrication_desk",
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.62, 0.62, 0.62]},
            "ground": {"scale": [0.4, 0.4, 0.4]},
            "fixed": {"scale": [0.5, 0.5, 0.5]},
        },
    })
    write(os.path.join(DATA, "loot_table/blocks/fabrication_desk.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1.0,
            "bonus_rolls": 0.0,
            "entries": [{"type": "minecraft:item", "name": "bwr:fabrication_desk"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })
    print("wrote the fabrication desk model, blockstate, item model and loot table")


if __name__ == "__main__":
    main()
