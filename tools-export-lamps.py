#!/usr/bin/env python3
"""Write the JSON side of the industrial lamps: block models, blockstates, item models, loot tables and recipes.

The geometry comes from art/models/industrial_lamps/build_lamps.py (Blender), which writes the OBJ files this script
wraps; the lens textures come from tools-make-lamp-textures.py. Standard library only. Re-run after changing the block
list, the property names or the recipes. The lang keys and the pickaxe tag are written by hand (see LIGHTING.md).
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")
MODELS = os.path.join(ASSETS, "models/block/lamp")

TYPES = ("lps", "hps", "mercury")
FIXTURES = ("high_bay", "wall_pack")
FACINGS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))

GLASS = {"lps": "minecraft:orange_stained_glass", "hps": "minecraft:glowstone", "mercury": "minecraft:light_blue_stained_glass"}

# Recipes, one per block: each lamp type has its own lens material. Iron, redstone and glass are common to all.
RECIPES = {
    ("lps", "high_bay"): (["III", "OGO", " R "], {"O": GLASS["lps"]}),
    ("lps", "wall_pack"): (["IRI", "OGO"], {"O": GLASS["lps"]}),
    ("hps", "high_bay"): (["III", "GLG", " R "], {"L": GLASS["hps"]}),
    ("hps", "wall_pack"): (["IRI", "GLG"], {"L": GLASS["hps"]}),
    ("mercury", "high_bay"): (["III", "GBG", " R "], {"B": GLASS["mercury"]}),
    ("mercury", "wall_pack"): (["IRI", "GBG"], {"B": GLASS["mercury"]}),
}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def obj_model(name):
    return {
        "loader": "neoforge:obj",
        "model": "bwr:models/block/lamp/%s.obj" % name,
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": True,
        "emissive_ambient": False,
        "ambientocclusion": False,
        "textures": {"particle": "minecraft:block/white_concrete"},
        "render_type": "minecraft:solid",
    }


def block_name(t, f):
    return "%s_%s" % (t, f)


def main():
    for t in TYPES:
        for f in FIXTURES:
            for state in ("on", "off"):
                write(os.path.join(MODELS, "%s_%s_%s.json" % (t, f, state)), obj_model("%s_%s_%s" % (t, f, state)))
    # Blockstates: the high bay has no facing; the wall pack turns to the four facings. Keys partition the states
    # (lit true or false, and facing), so each state matches exactly one variant.
    for t in TYPES:
        for f in FIXTURES:
            name = block_name(t, f)
            variants = {}
            if f == "high_bay":
                variants["lit=false"] = {"model": "bwr:block/lamp/%s_off" % name}
                variants["lit=true"] = {"model": "bwr:block/lamp/%s_on" % name}
            else:
                for facing, y in FACINGS:
                    for lit, state in (("false", "off"), ("true", "on")):
                        entry = {"model": "bwr:block/lamp/%s_%s" % (name, state)}
                        if y:
                            entry["y"] = y
                        variants["facing=%s,lit=%s" % (facing, lit)] = entry
            write(os.path.join(ASSETS, "blockstates", "%s.json" % name), {"variants": variants})
            write(os.path.join(ASSETS, "models", "item", "%s.json" % name),
                  {"parent": "bwr:block/lamp/%s_off" % name})
            write(os.path.join(DATA, "loot_table", "blocks", "%s.json" % name), {
                "type": "minecraft:block",
                "pools": [{
                    "rolls": 1.0,
                    "bonus_rolls": 0.0,
                    "entries": [{"type": "minecraft:item", "name": "bwr:%s" % name}],
                    "conditions": [{"condition": "minecraft:survives_explosion"}],
                }],
            })
            pattern, keys = RECIPES[(t, f)]
            key = {"I": {"item": "minecraft:iron_ingot"}, "R": {"item": "minecraft:redstone"},
                   "G": {"item": "minecraft:glass"}}
            for k, item in keys.items():
                key[k] = {"item": item}
            write(os.path.join(DATA, "recipe", "%s.json" % name), {
                "type": "minecraft:crafting_shaped",
                "category": "building",
                "pattern": pattern,
                "key": key,
                "result": {"id": "bwr:%s" % name, "count": 1},
            })
    print("lamp JSON written: %d blocks" % (len(TYPES) * len(FIXTURES)))


if __name__ == "__main__":
    main()
