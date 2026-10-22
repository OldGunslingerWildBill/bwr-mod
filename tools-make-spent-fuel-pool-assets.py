#!/usr/bin/env python3
"""Generates the spent fuel pool's textures, block models, block states, item models, loot tables and recipes.

Run from the repository root:  python tools-make-spent-fuel-pool-assets.py
Standard library plus Pillow. Everything is procedural (a seeded noise, no external art), so the files can be
regenerated and tweaked. Texture and model names:

  spent_fuel_pool_wall            stainless-lined concrete (floor and walls)
  spent_fuel_pool_controller      the wall with a status panel
  spent_fuel_pool_port_*          the wall with a coloured flange (amber makeup, blue cooling in, cyan cooling out)
  spent_fuel_rack(_top)           stainless tubes on a base plate, four cells; spent_fuel_rack_head_N is the head of
                                  the assembly standing in cell N (shown when the rack block's cell_a..cell_d is true)
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
CONCRETE = (104, 108, 112)
AMBER = (240, 168, 48)
BLUE = (64, 128, 232)
CYAN = (72, 214, 224)
DARK = (28, 34, 42)


def noisy(img, seed, amount=6):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b = px[x, y][:3]
            d = rnd.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), 255)


def wall_image(seed=1):
    img = Image.new("RGBA", (16, 16), STEEL + (255,))
    d = ImageDraw.Draw(img)
    # four stainless plates with seams and rivets over a concrete border
    d.rectangle((0, 0, 15, 15), outline=CONCRETE + (255,))
    for (x0, y0) in ((1, 1), (9, 1), (1, 9), (9, 9)):
        d.rectangle((x0, y0, x0 + 5, y0 + 5), fill=STEEL + (255,), outline=STEEL_DARK + (255,))
        d.point((x0 + 1, y0 + 1), fill=STEEL_LIGHT + (255,))
        d.point((x0 + 4, y0 + 4), fill=STEEL_LIGHT + (255,))
    d.line((7, 1, 7, 14), fill=CONCRETE + (255,))
    d.line((8, 1, 8, 14), fill=CONCRETE + (255,))
    d.line((1, 7, 14, 7), fill=CONCRETE + (255,))
    d.line((1, 8, 14, 8), fill=CONCRETE + (255,))
    noisy(img, seed, 5)
    return img


def controller_image():
    img = wall_image(2)
    d = ImageDraw.Draw(img)
    d.rectangle((2, 3, 13, 12), fill=DARK + (255,), outline=STEEL_LIGHT + (255,))
    # a bar graph of rack heat and two lamps
    for i, h in enumerate((3, 5, 4, 6, 2, 4)):
        d.line((3 + i * 2, 11, 3 + i * 2, 11 - h), fill=CYAN + (255,))
    d.point((12, 4), fill=AMBER + (255,))
    d.point((12, 6), fill=(110, 220, 110, 255))
    return img


def port_image(colour, seed):
    img = wall_image(seed)
    d = ImageDraw.Draw(img)
    d.ellipse((2, 2, 13, 13), fill=STEEL_DARK + (255,), outline=colour + (255,), width=2)
    d.ellipse((5, 5, 10, 10), fill=DARK + (255,))
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((x, y), fill=STEEL_LIGHT + (255,))
    return img


def rack_image():
    img = Image.new("RGBA", (16, 16), STEEL + (255,))
    d = ImageDraw.Draw(img)
    for x in range(0, 16, 4):
        d.line((x, 0, x, 15), fill=STEEL_DARK + (255,))
    d.line((0, 0, 15, 0), fill=STEEL_LIGHT + (255,))
    d.line((0, 15, 15, 15), fill=STEEL_DARK + (255,))
    noisy(img, 3, 6)
    return img


def rack_top_image():
    img = Image.new("RGBA", (16, 16), STEEL + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=STEEL_DARK + (255,))
    d.rectangle((3, 3, 12, 12), fill=DARK + (255,), outline=STEEL_LIGHT + (255,))
    noisy(img, 4, 5)
    return img


def head_image():
    img = Image.new("RGBA", (16, 16), (186, 190, 176, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=(120, 124, 112, 255))
    d.rectangle((5, 5, 10, 10), fill=(70, 74, 66, 255))
    for p in ((3, 3), (12, 3), (3, 12), (12, 12)):
        d.point(p, fill=(230, 230, 210, 255))
    noisy(img, 5, 5)
    return img


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def textures():
    os.makedirs(TEX, exist_ok=True)
    wall_image().save(os.path.join(TEX, "spent_fuel_pool_wall.png"))
    controller_image().save(os.path.join(TEX, "spent_fuel_pool_controller.png"))
    port_image(AMBER, 11).save(os.path.join(TEX, "spent_fuel_pool_port_makeup.png"))
    port_image(BLUE, 12).save(os.path.join(TEX, "spent_fuel_pool_port_cooling_in.png"))
    port_image(CYAN, 13).save(os.path.join(TEX, "spent_fuel_pool_port_cooling_out.png"))
    rack_image().save(os.path.join(TEX, "spent_fuel_rack.png"))
    rack_top_image().save(os.path.join(TEX, "spent_fuel_rack_top.png"))
    head_image().save(os.path.join(TEX, "spent_fuel_assembly_head.png"))


def cube_all(name, texture):
    write(os.path.join(MODELS, "block", name + ".json"),
          {"parent": "minecraft:block/cube_all", "textures": {"all": "bwr:block/" + texture}})
    write(os.path.join(STATES, name + ".json"), {"variants": {"": {"model": "bwr:block/" + name}}})
    write(os.path.join(MODELS, "item", name + ".json"), {"parent": "bwr:block/" + name})


def port(name, texture):
    write(os.path.join(MODELS, "block", name + ".json"), {
        "parent": "minecraft:block/orientable",
        "textures": {"top": "bwr:block/spent_fuel_pool_wall", "side": "bwr:block/spent_fuel_pool_wall",
                     "front": "bwr:block/" + texture}})
    write(os.path.join(STATES, name + ".json"), {"variants": {
        "facing=north": {"model": "bwr:block/" + name},
        "facing=east": {"model": "bwr:block/" + name, "y": 90},
        "facing=south": {"model": "bwr:block/" + name, "y": 180},
        "facing=west": {"model": "bwr:block/" + name, "y": 270}}})
    write(os.path.join(MODELS, "item", name + ".json"), {"parent": "bwr:block/" + name})


def cell_box(k, y0, y1, inset=1):
    x0 = inset + 8 * (k & 1)
    z0 = inset + 8 * (k >> 1)
    return [x0, y0, z0], [x0 + 6, y1, z0 + 6]


def rack():
    """One block of a rack column: four tubes the full height of the block. The top block of the column (state top=true)
    uses the shorter tubes and shows the assembly heads. A rack is four of these blocks, never more."""
    def tubes(height):
        elements = []
        for k in range(4):
            lo, hi = cell_box(k, 0, height)
            elements.append({"from": lo, "to": hi, "faces": {
                "north": {"uv": [0, 0, 6, height], "texture": "#rack"}, "south": {"uv": [0, 0, 6, height], "texture": "#rack"},
                "east": {"uv": [0, 0, 6, height], "texture": "#rack"}, "west": {"uv": [0, 0, 6, height], "texture": "#rack"},
                "up": {"uv": [4, 4, 12, 12], "texture": "#top"}}})
        return elements
    textures = {"rack": "bwr:block/spent_fuel_rack", "top": "bwr:block/spent_fuel_rack_top",
                "particle": "bwr:block/spent_fuel_rack"}
    write(os.path.join(MODELS, "block", "spent_fuel_rack.json"), {"textures": textures, "elements": tubes(16)})
    write(os.path.join(MODELS, "block", "spent_fuel_rack_top.json"), {"textures": textures, "elements": tubes(14)})
    for k in range(4):
        lo, hi = cell_box(k, 14, 16, inset=2)
        lo[0], hi[0] = lo[0], lo[0] + 4
        lo[2], hi[2] = lo[2], lo[2] + 4
        write(os.path.join(MODELS, "block", "spent_fuel_rack_head_%d.json" % k), {
            "textures": {"head": "bwr:block/spent_fuel_assembly_head", "particle": "bwr:block/spent_fuel_rack"},
            "elements": [{"from": lo, "to": hi, "faces": {
                "north": {"uv": [4, 4, 12, 8], "texture": "#head"}, "south": {"uv": [4, 4, 12, 8], "texture": "#head"},
                "east": {"uv": [4, 4, 12, 8], "texture": "#head"}, "west": {"uv": [4, 4, 12, 8], "texture": "#head"},
                "up": {"uv": [4, 4, 12, 12], "texture": "#head"}}}]})
    multipart = [{"when": {"top": "false"}, "apply": {"model": "bwr:block/spent_fuel_rack"}},
                 {"when": {"top": "true"}, "apply": {"model": "bwr:block/spent_fuel_rack_top"}}]
    for k in range(4):
        multipart.append({"when": {"top": "true", "cell_" + "abcd"[k]: "true"},
                          "apply": {"model": "bwr:block/spent_fuel_rack_head_%d" % k}})
    write(os.path.join(STATES, "spent_fuel_rack.json"), {"multipart": multipart})
    write(os.path.join(MODELS, "item", "spent_fuel_rack.json"), {"parent": "bwr:block/spent_fuel_rack"})


def loot(name):
    write(os.path.join(LOOT, name + ".json"), {
        "type": "minecraft:block",
        "pools": [{"bonus_rolls": 0.0, "conditions": [{"condition": "minecraft:survives_explosion"}],
                   "entries": [{"type": "minecraft:item", "name": "bwr:" + name}], "rolls": 1.0}],
        "random_sequence": "bwr:blocks/" + name})


def shaped(name, key, pattern, count=1):
    write(os.path.join(RECIPES, name + ".json"), {
        "type": "minecraft:crafting_shaped", "category": "building", "key": key, "pattern": pattern,
        "result": {"count": count, "id": "bwr:" + name}})


def shapeless(name, ingredients):
    write(os.path.join(RECIPES, name + ".json"), {
        "type": "minecraft:crafting_shapeless", "category": "building", "ingredients": ingredients,
        "result": {"count": 1, "id": "bwr:" + name}})


def recipes():
    iron = {"tag": "c:ingots/iron"}
    shaped("spent_fuel_pool_wall", {"G": {"item": "minecraft:gray_concrete"}, "I": iron},
           ["GGG", "III", "GGG"], 8)
    shaped("spent_fuel_rack", {"B": {"item": "minecraft:iron_bars"}, "I": iron},
           ["BIB", "BIB", "III"], 4)
    shaped("spent_fuel_pool_controller", {"W": {"item": "bwr:spent_fuel_pool_wall"}, "I": iron,
                                          "C": {"item": "minecraft:comparator"}, "R": {"item": "minecraft:redstone_block"}},
           ["WIW", "ICI", "WRW"])
    for name, dye in (("spent_fuel_pool_makeup_port", "orange_dye"), ("spent_fuel_pool_cooling_in", "blue_dye"),
                      ("spent_fuel_pool_cooling_out", "cyan_dye")):
        shapeless(name, [{"item": "bwr:spent_fuel_pool_wall"}, {"item": "minecraft:hopper"}, {"item": "minecraft:" + dye}])


def tags():
    names = ["bwr:spent_fuel_pool_wall", "bwr:spent_fuel_pool_controller", "bwr:spent_fuel_rack",
             "bwr:spent_fuel_pool_makeup_port", "bwr:spent_fuel_pool_cooling_in", "bwr:spent_fuel_pool_cooling_out"]
    for rel in ("data/minecraft/tags/block/mineable/pickaxe.json", "data/minecraft/tags/block/needs_iron_tool.json"):
        path = os.path.join(RES, rel)
        raw = open(path, encoding="utf-8", newline="").read()
        nl = "\r\n" if "\r\n" in raw else "\n"
        data = json.loads(raw)
        for n in names:
            if n not in data["values"]:
                data["values"].append(n)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(json.dumps(data, indent=2).replace("\n", nl) + nl)


def main():
    textures()
    cube_all("spent_fuel_pool_wall", "spent_fuel_pool_wall")
    cube_all("spent_fuel_pool_controller", "spent_fuel_pool_controller")
    port("spent_fuel_pool_makeup_port", "spent_fuel_pool_port_makeup")
    port("spent_fuel_pool_cooling_in", "spent_fuel_pool_port_cooling_in")
    port("spent_fuel_pool_cooling_out", "spent_fuel_pool_port_cooling_out")
    rack()
    for n in ("spent_fuel_pool_wall", "spent_fuel_pool_controller", "spent_fuel_rack", "spent_fuel_pool_makeup_port",
              "spent_fuel_pool_cooling_in", "spent_fuel_pool_cooling_out"):
        loot(n)
    recipes()
    tags()
    print("spent fuel pool assets written")


if __name__ == "__main__":
    main()
