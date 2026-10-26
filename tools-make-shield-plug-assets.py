#!/usr/bin/env python3
"""Generates the three shield plug concrete blocks' textures, block states, models, item models, loot tables,
recipes, the recipe base tag and the containment strength override.

Run from the repository root:  python tools-make-shield-plug-assets.py
Standard library plus Pillow. Everything is procedural (a seeded noise, no external art), so the files can be
regenerated and tweaked.

  shield_plug_concrete                          grey concrete with a sparse mesh of steel rebar
  heavily_reinforced_shield_plug_concrete       darker concrete, a denser steel mesh and a riveted frame
  ultra_reinforced_shield_plug_concrete         near-black concrete, a dense double mesh and a netherite-brown plate frame

Plug lifting (BUILDING-CRANE.md): each tier's blockstate is multipart, with a groove overlay for every face a seam can be on
(`plug_seam_<dir>` models and the `plug_seam` texture), the Plug Seam Marker's item texture and model, and the recipes of the
Crane Anchor and the marker.

Blast resistance 1,800 / 3,600 / 7,200; ratings 5.8 * sqrt(R) = 246.1 / 348.0 / 492.1 psi (see SEVERE-ACCIDENT-UPDATE.md).
"""
import json
import math
import os
import random
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
TEX = os.path.join(RES, "assets/bwr/textures/block")
MODELS = os.path.join(RES, "assets/bwr/models")
STATES = os.path.join(RES, "assets/bwr/blockstates")
LOOT = os.path.join(RES, "data/bwr/loot_table/blocks")
RECIPES = os.path.join(RES, "data/bwr/recipe")
TAGS = os.path.join(RES, "data/bwr/tags/item")
STRENGTH = os.path.join(RES, "data/bwr/bwr_containment")

TIERS = [
    ("shield_plug_concrete", 1800.0),
    ("heavily_reinforced_shield_plug_concrete", 3600.0),
    ("ultra_reinforced_shield_plug_concrete", 7200.0),
]


def noisy(img, seed, amount):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b = px[x, y][:3]
            d = rnd.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), 255)


def tier1():
    img = Image.new("RGBA", (16, 16), (128, 130, 132, 255))
    noisy(img, 11, 9)
    d = ImageDraw.Draw(img)
    rebar = (120, 78, 52, 255)
    shine = (170, 118, 82, 255)
    for k in (3, 11):
        d.line((0, k, 15, k), fill=rebar)
        d.line((k, 0, k, 15), fill=rebar)
    for x in (3, 11):
        for y in (3, 11):
            d.point((x, y), fill=shine)
    d.rectangle((0, 0, 15, 15), outline=(100, 102, 104, 255))
    return img


def tier2():
    img = Image.new("RGBA", (16, 16), (98, 102, 108, 255))
    noisy(img, 22, 8)
    d = ImageDraw.Draw(img)
    rebar = (84, 112, 150, 255)
    shine = (150, 176, 208, 255)
    for k in (1, 5, 9, 13):
        d.line((0, k, 15, k), fill=rebar)
        d.line((k, 0, k, 15), fill=rebar)
    for x in (1, 5, 9, 13):
        for y in (1, 5, 9, 13):
            d.point((x, y), fill=shine)
    d.rectangle((0, 0, 15, 15), outline=(60, 66, 76, 255))
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((x, y), fill=(210, 220, 232, 255))
    return img


def tier3():
    img = Image.new("RGBA", (16, 16), (52, 50, 58, 255))
    noisy(img, 33, 7)
    d = ImageDraw.Draw(img)
    rebar = (96, 70, 96, 255)
    shine = (176, 142, 94, 255)
    for k in range(1, 16, 3):
        d.line((0, k, 15, k), fill=rebar)
        d.line((k, 0, k, 15), fill=rebar)
    for x in range(1, 16, 3):
        for y in range(1, 16, 3):
            d.point((x, y), fill=shine)
    # a netherite-brown plate frame with gold-lit corner studs
    d.rectangle((0, 0, 15, 15), outline=(78, 56, 52, 255))
    d.rectangle((1, 1, 14, 14), outline=(40, 34, 40, 255))
    for x, y in ((0, 0), (15, 0), (0, 15), (15, 15), (7, 0), (8, 15), (0, 8), (15, 7)):
        d.point((x, y), fill=(214, 170, 90, 255))
    return img


DIRS = ("down", "up", "north", "south", "west", "east")
# direction -> (axis index, sign)
DIR_AXIS = {"down": (1, -1), "up": (1, 1), "north": (2, -1), "south": (2, 1), "west": (0, -1), "east": (0, 1)}


def seam_texture():
    """A narrow dark joint: near-black with a faint lighter lip."""
    img = Image.new("RGBA", (16, 16), (22, 24, 26, 255))
    noisy(img, 44, 5)
    d = ImageDraw.Draw(img)
    d.line((0, 0, 15, 0), fill=(54, 56, 60, 255))
    return img


def seam_model(direction):
    """The groove on the four faces round a joint on side `direction`: a one pixel strip along the edge touching that side."""
    axis, sign = DIR_AXIS[direction]
    elements = []
    for g in DIRS:
        gaxis, gsign = DIR_AXIS[g]
        if gaxis == axis:
            continue
        plane = 16.02 if gsign > 0 else -0.02
        lo, hi = [0.0, 0.0, 0.0], [16.0, 16.0, 16.0]
        lo[gaxis] = hi[gaxis] = plane
        if sign > 0:
            lo[axis], hi[axis] = 15.0, 16.0
        else:
            lo[axis], hi[axis] = 0.0, 1.0
        elements.append({"from": lo, "to": hi, "faces": {g: {"uv": [0, 0, 16, 1], "texture": "#seam"}}})
    return {"textures": {"seam": "bwr:block/plug_seam", "particle": "bwr:block/plug_seam"}, "elements": elements}


def marker_texture():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # a flat chisel-ended marker: orange grip, steel ferrule, black tip
    for i in range(9):
        d.point((3 + i, 12 - i), fill=(224, 126, 28, 255))
        d.point((4 + i, 12 - i), fill=(188, 98, 18, 255))
        d.point((3 + i, 13 - i), fill=(240, 150, 48, 255))
    for i in range(2):
        d.point((11 + i, 4 - i), fill=(170, 176, 184, 255))
        d.point((12 + i, 4 - i), fill=(126, 132, 140, 255))
    d.point((13, 3), fill=(20, 20, 22, 255))
    d.point((14, 2), fill=(20, 20, 22, 255))
    d.point((2, 13), fill=(120, 60, 12, 255))
    d.line((1, 15, 5, 15), fill=(30, 32, 34, 255))   # the joint line it draws
    return img


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def main():
    for (name, _), make in zip(TIERS, (tier1, tier2, tier3)):
        os.makedirs(TEX, exist_ok=True)
        make().save(os.path.join(TEX, name + ".png"))
        # a multipart state: the block itself, and a groove on the four faces round every joint marked as a seam
        parts = [{"apply": {"model": "bwr:block/" + name}}]
        for dname in DIRS:
            parts.append({"when": {"seam_" + dname: "true"}, "apply": {"model": "bwr:block/plug_seam_" + dname}})
        write(os.path.join(STATES, name + ".json"), {"multipart": parts})
        write(os.path.join(MODELS, "block", name + ".json"), {"parent": "minecraft:block/cube_all", "textures": {"all": "bwr:block/" + name}})
        write(os.path.join(MODELS, "item", name + ".json"), {"parent": "bwr:block/" + name})
        write(os.path.join(LOOT, name + ".json"), {
            "type": "minecraft:block",
            "pools": [{
                "bonus_rolls": 0.0,
                "conditions": [{"condition": "minecraft:survives_explosion"}],
                "entries": [{"type": "minecraft:item", "name": "bwr:" + name}],
                "rolls": 1.0,
            }],
            "random_sequence": "bwr:blocks/" + name,
        })

    seam_texture().save(os.path.join(TEX, "plug_seam.png"))
    for dname in DIRS:
        write(os.path.join(MODELS, "block", "plug_seam_" + dname + ".json"), seam_model(dname))
    item_tex = os.path.join(RES, "assets/bwr/textures/item")
    os.makedirs(item_tex, exist_ok=True)
    marker_texture().save(os.path.join(item_tex, "plug_seam_marker.png"))
    write(os.path.join(MODELS, "item", "plug_seam_marker.json"),
          {"parent": "minecraft:item/handheld", "textures": {"layer0": "bwr:item/plug_seam_marker"}})
    # The Plug Seam Marker: an iron ingot over a stick. The Crane Anchor: two iron ingots make four lifting eyes.
    write(os.path.join(RECIPES, "plug_seam_marker.json"), {
        "type": "minecraft:crafting_shaped", "category": "equipment",
        "key": {"I": {"item": "minecraft:iron_ingot"}, "S": {"item": "minecraft:stick"}},
        "pattern": ["I", "S"],
        "result": {"count": 1, "id": "bwr:plug_seam_marker"},
    })
    write(os.path.join(RECIPES, "crane_anchor.json"), {
        "type": "minecraft:crafting_shapeless", "category": "building",
        "ingredients": [{"item": "minecraft:iron_ingot"}, {"item": "minecraft:iron_ingot"}],
        "result": {"count": 4, "id": "bwr:crane_anchor"},
    })

    base = {"tag": "bwr:shield_plug_concrete_base"}
    steel = {"tag": "c:ingots/steel"}
    # Tier 1: concrete and steel. Four blocks a craft: 1.25 concrete and 1 steel ingot each.
    write(os.path.join(RECIPES, "shield_plug_concrete.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"C": base, "S": steel},
        "pattern": ["CSC", "SCS", "CSC"],
        "result": {"count": 4, "id": "bwr:shield_plug_concrete"},
    })
    # Tier 2: five tier 1 blocks, two steel ingots and two netherite ingots.
    write(os.path.join(RECIPES, "heavily_reinforced_shield_plug_concrete.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"P": {"item": "bwr:shield_plug_concrete"}, "S": steel, "N": {"tag": "c:ingots/netherite"}},
        "pattern": ["PSP", "NPN", "PSP"],
        "result": {"count": 1, "id": "bwr:heavily_reinforced_shield_plug_concrete"},
    })
    # Tier 3: five tier 2 blocks, two netherite blocks and two netherite ingots.
    write(os.path.join(RECIPES, "ultra_reinforced_shield_plug_concrete.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "key": {"H": {"item": "bwr:heavily_reinforced_shield_plug_concrete"}, "N": {"tag": "c:ingots/netherite"},
                "B": {"tag": "c:storage_blocks/netherite"}},
        "pattern": ["HNH", "BHB", "HNH"],
        "result": {"count": 1, "id": "bwr:ultra_reinforced_shield_plug_concrete"},
    })

    # Any vanilla concrete (c:concretes) or Immersive Engineering's concrete, which is optional.
    write(os.path.join(TAGS, "shield_plug_concrete_base.json"), {
        "replace": False,
        "values": ["#c:concretes", {"id": "immersiveengineering:concrete", "required": False}],
    })

    # The containment wall rule caps one block at 250 psi, which would flatten the two upper tiers: carry the exact
    # ratings, 5.8 * sqrt(blast resistance), in the mod's own data pack override.
    write(os.path.join(STRENGTH, "strength.json"),
          {"bwr:" + name: round(5.8 * math.sqrt(r), 1) for name, r in TIERS})


if __name__ == "__main__":
    main()
