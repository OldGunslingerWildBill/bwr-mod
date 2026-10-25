#!/usr/bin/env python3
"""Write the JSON side of the industrial lamps: block models, multipart blockstates, item models, loot tables, recipes.

The geometry comes from art/models/industrial_lamps/build_lamps.py (Blender): a housing model per fixture and a lens
model per lamp type, fixture and stage (off, w0, w1, on), plus an item model per type and fixture. The lit lens models
set emissive_ambient, so they are fullbright (the OBJ loader takes the light level from the material's Ka). The lens
textures come from tools-make-lamp-textures.py. Standard library only. Re-run after changing the block list, the
properties, the models or the recipes. The lang keys and the pickaxe tag are written by hand (see LIGHTING.md).
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
STAGES = ("off", "w0", "w1", "on")
# Lit stages are emissive (fullbright); the unlit lens takes the world light.
EMISSIVE = {"off": False, "w0": True, "w1": True, "on": True}

GLASS = {"lps": "minecraft:orange_stained_glass", "hps": "minecraft:glowstone",
         "mercury": "minecraft:light_blue_stained_glass"}

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


def obj_model(name, emissive):
    return {
        "loader": "neoforge:obj",
        "model": "bwr:models/block/lamp/%s.obj" % name,
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": True,
        "emissive_ambient": emissive,
        "ambientocclusion": False,
        "textures": {"particle": "minecraft:block/white_concrete"},
        "render_type": "minecraft:solid",
    }


def lens(t, fixture, stage):
    return "bwr:block/lamp/%s_%s_lens_%s" % (t, fixture, stage)


def stage_when(stage):
    """Multipart conditions for a lens stage. Lit or not decides the part; cooling is named but any value takes it, so every
    state gets exactly one lens part (a lit lamp is never cooling in play, but the blockstate covers every state)."""
    if stage == "off":
        return {"lit": "false", "cooling": "0|1|2|3"}
    return {"lit": "true", "warmup": {"w0": "0", "w1": "1", "on": "2|3"}[stage], "cooling": "0|1|2|3"}


def multipart(t, fixture):
    parts = []
    if fixture == "high_bay":
        parts.append({"apply": {"model": "bwr:block/lamp/high_bay_housing"}})
        for stage in STAGES:
            parts.append({"when": stage_when(stage), "apply": {"model": lens(t, fixture, stage)}})
        return parts
    for facing, y in FACINGS:
        turn = {"y": y} if y else {}
        parts.append({"when": {"facing": facing}, "apply": dict({"model": "bwr:block/lamp/wall_pack_housing"}, **turn)})
        for stage in STAGES:
            when = dict(stage_when(stage), facing=facing)
            parts.append({"when": when, "apply": dict({"model": lens(t, fixture, stage)}, **turn)})
    return parts


def main():
    for fixture in FIXTURES:
        write(os.path.join(MODELS, "%s_housing.json" % fixture), obj_model("%s_housing" % fixture, False))
    for t in TYPES:
        for fixture in FIXTURES:
            for stage in STAGES:
                name = "%s_%s_lens_%s" % (t, fixture, stage)
                write(os.path.join(MODELS, name + ".json"), obj_model(name, EMISSIVE[stage]))
            item = "%s_%s_item" % (t, fixture)
            write(os.path.join(MODELS, item + ".json"), obj_model(item, False))
            block = "%s_%s" % (t, fixture)
            write(os.path.join(ASSETS, "blockstates", "%s.json" % block), {"multipart": multipart(t, fixture)})
            write(os.path.join(ASSETS, "models", "item", "%s.json" % block),
                  {"parent": "bwr:block/lamp/%s" % item})
            write(os.path.join(DATA, "loot_table", "blocks", "%s.json" % block), {
                "type": "minecraft:block",
                "pools": [{
                    "rolls": 1.0,
                    "bonus_rolls": 0.0,
                    "entries": [{"type": "minecraft:item", "name": "bwr:%s" % block}],
                    "conditions": [{"condition": "minecraft:survives_explosion"}],
                }],
            })
            pattern, keys = RECIPES[(t, fixture)]
            key = {"I": {"item": "minecraft:iron_ingot"}, "R": {"item": "minecraft:redstone"},
                   "G": {"item": "minecraft:glass"}}
            for k, glass_item in keys.items():
                key[k] = {"item": glass_item}
            write(os.path.join(DATA, "recipe", "%s.json" % block), {
                "type": "minecraft:crafting_shaped",
                "category": "building",
                "pattern": pattern,
                "key": key,
                "result": {"id": "bwr:%s" % block, "count": 1},
            })
    print("lamp JSON written: %d blocks, %d lens models" % (len(TYPES) * len(FIXTURES),
                                                           len(TYPES) * len(FIXTURES) * len(STAGES)))


if __name__ == "__main__":
    main()
