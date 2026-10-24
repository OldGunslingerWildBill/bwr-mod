#!/usr/bin/env python3
"""Generates the reactor building crane's block textures, block models, block states, item models, loot tables and recipes.

Run from the repository root:  python tools-make-crane-assets.py
Standard library plus Pillow. Everything is procedural (a seeded noise, no external art). Names:

  crane_runway_rail          an I-section runway beam with the crane rail on its top; axis x or z
  building_crane_console     the operator's pendant desk, safety yellow, with a screen
  head_stand                 a steel plinth marking the middle of the head's lay-down place
  stud_tensioner             the hydraulic power pack of the stud tensioner

The crane itself, its hook, the strongback and the stud tensioner's tool are Blender parts (art/models/building_crane, exported by
tools-export-building-crane.py) drawn by block entity renderers.
"""
import json
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

STEEL = (146, 160, 170)
STEEL_DARK = (92, 104, 114)
STEEL_LIGHT = (176, 190, 198)
YELLOW = (240, 178, 22)
YELLOW_DARK = (196, 140, 12)
DARK = (28, 34, 42)
ORANGE = (232, 96, 20)
GLASS = (22, 60, 66)
CYAN = (72, 214, 224)
AMBER = (240, 168, 48)
RED = (205, 40, 30)


def noisy(img, seed, amount=6):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            d = rnd.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), a)


def save(name, img):
    os.makedirs(TEX, exist_ok=True)
    img.save(os.path.join(TEX, name + ".png"))


def beam_image():
    img = Image.new("RGBA", (16, 16), STEEL + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 1), fill=STEEL_LIGHT + (255,))
    d.rectangle((0, 14, 15, 15), fill=STEEL_DARK + (255,))
    for x in (2, 6, 10, 14):
        d.point((x, 4), fill=STEEL_DARK + (255,))
        d.point((x, 11), fill=STEEL_DARK + (255,))
    noisy(img, 31, 4)
    return img


def rail_head_image():
    img = Image.new("RGBA", (16, 16), (70, 78, 86, 255))
    d = ImageDraw.Draw(img)
    d.line((0, 0, 15, 0), fill=(150, 160, 168, 255))
    noisy(img, 32, 4)
    return img


def console_side():
    img = Image.new("RGBA", (16, 16), YELLOW + (255,))
    d = ImageDraw.Draw(img)
    for i in range(-16, 16, 6):
        d.line((i, 15, i + 15, 0), fill=DARK + (255,), width=1)
    d.rectangle((0, 0, 15, 1), fill=YELLOW_DARK + (255,))
    d.rectangle((0, 14, 15, 15), fill=YELLOW_DARK + (255,))
    noisy(img, 33, 5)
    return img


def console_top():
    img = Image.new("RGBA", (16, 16), YELLOW_DARK + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((2, 3, 13, 12), outline=DARK + (255,))
    d.rectangle((3, 4, 7, 6), fill=RED + (255,))
    d.rectangle((9, 4, 12, 6), fill=GLASS + (255,))
    for x in (4, 7, 10):
        d.point((x, 9), fill=DARK + (255,))
        d.point((x, 11), fill=DARK + (255,))
    noisy(img, 34, 4)
    return img


def console_screen():
    img = Image.new("RGBA", (16, 16), GLASS + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=DARK + (255,))
    for y in (3, 6, 9):
        d.line((2, y, 13, y), fill=CYAN + (255,))
    d.rectangle((2, 11, 6, 13), fill=AMBER + (255,))
    noisy(img, 35, 3)
    return img


def stand_top():
    img = Image.new("RGBA", (16, 16), STEEL_DARK + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((1, 1, 14, 14), outline=YELLOW + (255,))
    d.line((4, 4, 11, 11), fill=YELLOW + (255,))
    d.line((11, 4, 4, 11), fill=YELLOW + (255,))
    noisy(img, 36, 4)
    return img


def tensioner_side():
    img = Image.new("RGBA", (16, 16), ORANGE + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=(150, 60, 10, 255))
    d.rectangle((3, 3, 12, 12), outline=DARK + (255,))
    d.rectangle((5, 5, 10, 10), fill=(60, 60, 66, 255))
    d.point((7, 7), fill=AMBER + (255,))
    noisy(img, 37, 4)
    return img


def tensioner_top():
    img = Image.new("RGBA", (16, 16), (84, 90, 98, 255))
    d = ImageDraw.Draw(img)
    d.ellipse((3, 3, 12, 12), outline=DARK + (255,))
    d.ellipse((6, 6, 9, 9), fill=ORANGE + (255,))
    d.line((0, 15, 15, 15), fill=(60, 66, 72, 255))
    noisy(img, 38, 4)
    return img


def box(frm, to, tex, up="#top", faces=None):
    f = {}
    for side in ("north", "south", "east", "west", "down"):
        f[side] = {"texture": tex}
    f["up"] = {"texture": up}
    return {"from": frm, "to": to, "faces": f}


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def main():
    save("crane_runway_beam", beam_image())
    save("crane_runway_head", rail_head_image())
    save("building_crane_console_side", console_side())
    save("building_crane_console_top", console_top())
    save("building_crane_console_screen", console_screen())
    save("head_stand_top", stand_top())
    save("stud_tensioner_side", tensioner_side())
    save("stud_tensioner_top", tensioner_top())

    # runway rail: an I-section along x (axis x), turned for z
    rail = {"textures": {"beam": "bwr:block/crane_runway_beam", "head": "bwr:block/crane_runway_head", "particle": "bwr:block/crane_runway_beam"},
            "elements": [box([0, 0, 3], [16, 2, 13], "#beam", "#beam"), box([0, 2, 6], [16, 10, 10], "#beam", "#beam"),
                         box([0, 10, 3], [16, 12, 13], "#beam", "#beam"), box([0, 12, 6], [16, 13, 10], "#head", "#head")]}
    write(os.path.join(MODELS, "block/crane_runway_rail.json"), rail)
    write(os.path.join(MODELS, "item/crane_runway_rail.json"), {"parent": "bwr:block/crane_runway_rail"})
    write(os.path.join(STATES, "crane_runway_rail.json"), {"variants": {"axis=x": {"model": "bwr:block/crane_runway_rail"},
                                                                       "axis=z": {"model": "bwr:block/crane_runway_rail", "y": 90}}})

    # pendant console
    screen_face = {"north": {"texture": "#screen"}, "south": {"texture": "#side"}, "east": {"texture": "#side"}, "west": {"texture": "#side"},
                   "up": {"texture": "#side"}, "down": {"texture": "#side"}}
    console = {"textures": {"side": "bwr:block/building_crane_console_side", "top": "bwr:block/building_crane_console_top",
                            "screen": "bwr:block/building_crane_console_screen", "particle": "bwr:block/building_crane_console_side"},
               "elements": [box([1, 0, 1], [15, 10, 15], "#side", "#top"), box([0, 10, 0], [16, 12, 16], "#side", "#top"),
                            {"from": [2, 12, 10], "to": [14, 16, 12], "rotation": {"angle": -22.5, "axis": "x", "origin": [8, 12, 11]}, "faces": screen_face}]}
    write(os.path.join(MODELS, "block/building_crane_console.json"), console)
    write(os.path.join(MODELS, "item/building_crane_console.json"), {"parent": "bwr:block/building_crane_console"})
    write(os.path.join(STATES, "building_crane_console.json"), {"variants": {
        "facing=north": {"model": "bwr:block/building_crane_console"},
        "facing=east": {"model": "bwr:block/building_crane_console", "y": 90},
        "facing=south": {"model": "bwr:block/building_crane_console", "y": 180},
        "facing=west": {"model": "bwr:block/building_crane_console", "y": 270}}})

    # head stand
    stand = {"parent": "minecraft:block/block", "textures": {"particle": "minecraft:block/iron_block", "top": "bwr:block/head_stand_top",
                                                              "side": "minecraft:block/gray_concrete", "post": "minecraft:block/iron_block"},
             "elements": [box([0, 0, 0], [16, 3, 16], "#side", "#top"), box([5, 3, 5], [11, 4, 11], "#post", "#post")]}
    write(os.path.join(MODELS, "block/head_stand.json"), stand)
    write(os.path.join(MODELS, "item/head_stand.json"), {"parent": "bwr:block/head_stand"})
    write(os.path.join(STATES, "head_stand.json"), {"variants": {"": {"model": "bwr:block/head_stand"}}})

    # stud tensioner power pack
    tensioner = {"textures": {"side": "bwr:block/stud_tensioner_side", "top": "bwr:block/stud_tensioner_top", "particle": "bwr:block/stud_tensioner_side"},
                 "elements": [box([1, 0, 1], [15, 9, 15], "#side", "#top"), box([2, 9, 2], [8, 12, 8], "#side", "#top"),
                              box([9, 9, 9], [14, 11, 14], "#top", "#top")]}
    write(os.path.join(MODELS, "block/stud_tensioner.json"), tensioner)
    write(os.path.join(MODELS, "item/stud_tensioner.json"), {"parent": "bwr:block/stud_tensioner"})
    write(os.path.join(STATES, "stud_tensioner.json"), {"variants": {
        "facing=north": {"model": "bwr:block/stud_tensioner"},
        "facing=east": {"model": "bwr:block/stud_tensioner", "y": 90},
        "facing=south": {"model": "bwr:block/stud_tensioner", "y": 180},
        "facing=west": {"model": "bwr:block/stud_tensioner", "y": 270}}})

    for name in ("crane_runway_rail", "building_crane_console", "head_stand", "stud_tensioner"):
        write(os.path.join(LOOT, name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"bonus_rolls": 0.0, "conditions": [{"condition": "minecraft:survives_explosion"}],
                       "entries": [{"type": "minecraft:item", "name": "bwr:" + name}], "rolls": 1.0}],
            "random_sequence": "bwr:blocks/" + name})

    iron = {"tag": "c:ingots/iron"}
    gray = {"item": "minecraft:gray_concrete"}
    recipes = {
        "crane_runway_rail": ({"I": iron, "G": gray}, ["III", " G ", "III"], 4),
        "building_crane_console": ({"I": iron, "P": {"item": "minecraft:glass_pane"}, "C": {"item": "minecraft:comparator"},
                                    "Y": {"item": "minecraft:yellow_dye"}}, ["PPP", "ICI", "IYI"], 1),
        "head_stand": ({"I": iron, "G": gray}, ["GGG", "I I", "III"], 1),
        "stud_tensioner": ({"I": iron, "P": {"item": "minecraft:piston"}, "R": {"item": "minecraft:redstone"}}, ["III", "IPI", "IRI"], 1),
    }
    for name, (key, pattern, count) in recipes.items():
        write(os.path.join(RECIPES, name + ".json"), {"type": "minecraft:crafting_shaped", "category": "building", "key": key,
                                                      "pattern": pattern, "result": {"count": count, "id": "bwr:" + name}})
    print("crane assets written")


main()
