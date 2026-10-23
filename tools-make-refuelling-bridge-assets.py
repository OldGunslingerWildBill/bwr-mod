#!/usr/bin/env python3
"""Generates the refuelling bridge's textures, block models, block states, item models, loot tables and recipes.

Run from the repository root:  python tools-make-refuelling-bridge-assets.py
Standard library plus Pillow. Everything is procedural (a seeded noise, no external art). Names:

  refuelling_rail            two steel rails on a concrete sleeper; axis x or z
  transfer_canal             a lined trough (the cattle chute), dry or with a water body; axis x or z
  canal_gate                 a trough with a plate across it, raised (closed) or sunk (open), dry or wet
  refuelling_bridge_console  the operator's desk with a screen
  refuelling_bridge_steel / _mast   the old box-model textures (now only the refuelling_bridge_parts preview model uses them)
  refuelling_assembly, refuelling_bridge_plate   textures of the carried assembly and the girder's maker plate (the bridge's
                             own models are Blender parts, art/models/refuelling_bridge, exported by tools-export-refuelling-bridge.py)
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
CONCRETE_LIGHT = (128, 132, 136)
YELLOW = (232, 184, 36)
YELLOW_DARK = (176, 132, 20)
DARK = (28, 34, 42)
BLUE = (64, 140, 232)
CYAN = (72, 214, 224)
AMBER = (240, 168, 48)
GREEN = (110, 220, 110)


def noisy(img, seed, amount=6):
    rnd = random.Random(seed)
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            d = rnd.randint(-amount, amount)
            px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)), a)


def solid(colour, seed, amount=5):
    img = Image.new("RGBA", (16, 16), colour + (255,))
    noisy(img, seed, amount)
    return img


def rail_image():
    img = Image.new("RGBA", (16, 16), STEEL + (255,))
    d = ImageDraw.Draw(img)
    d.line((0, 0, 15, 0), fill=STEEL_LIGHT + (255,))
    d.line((0, 15, 15, 15), fill=STEEL_DARK + (255,))
    for x in (3, 11):
        d.point((x, 7), fill=STEEL_DARK + (255,))
    noisy(img, 21, 4)
    return img


def tie_image():
    img = Image.new("RGBA", (16, 16), CONCRETE + (255,))
    d = ImageDraw.Draw(img)
    for x in range(0, 16, 4):
        d.line((x, 0, x, 15), fill=CONCRETE_LIGHT + (255,))
    noisy(img, 22, 5)
    return img


def canal_image():
    img = Image.new("RGBA", (16, 16), (96, 112, 118, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=(76, 90, 96, 255))
    d.line((0, 8, 15, 8), fill=(110, 126, 132, 255))
    noisy(img, 23, 5)
    return img


def water_image():
    img = Image.new("RGBA", (16, 16), (44, 120, 214, 150))
    px = img.load()
    rnd = random.Random(24)
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            d = rnd.randint(-10, 10)
            px[x, y] = (max(0, r + d // 2), max(0, min(255, g + d)), max(0, min(255, b + d)), 150)
    return img


def gate_image():
    img = Image.new("RGBA", (16, 16), STEEL_DARK + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=YELLOW_DARK + (255,))
    for y in (3, 8, 13):
        d.line((1, y, 14, y), fill=STEEL + (255,))
    for p in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point(p, fill=YELLOW + (255,))
    noisy(img, 25, 4)
    return img


def console_side_image():
    img = Image.new("RGBA", (16, 16), (70, 80, 90, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=DARK + (255,))
    d.rectangle((3, 3, 12, 12), outline=(54, 62, 70, 255))
    d.line((0, 0, 15, 0), fill=YELLOW + (255,))
    noisy(img, 26, 4)
    return img


def console_top_image():
    img = Image.new("RGBA", (16, 16), (60, 68, 78, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=DARK + (255,))
    for i, c in enumerate((AMBER, GREEN, BLUE, CYAN)):
        d.rectangle((2 + i * 3, 11, 3 + i * 3, 12), fill=c + (255,))
    d.rectangle((2, 3, 13, 8), fill=(44, 52, 60, 255), outline=STEEL_DARK + (255,))
    noisy(img, 27, 3)
    return img


def console_screen_image():
    img = Image.new("RGBA", (16, 16), DARK + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=STEEL_DARK + (255,))
    d.rectangle((2, 2, 13, 13), fill=(14, 26, 30, 255))
    for i, h in enumerate((4, 7, 5, 9, 3, 6)):
        d.line((3 + i * 2, 12, 3 + i * 2, 12 - h), fill=CYAN + (255,))
    d.line((3, 4, 12, 4), fill=GREEN + (255,))
    return img


def bridge_steel_image():
    img = Image.new("RGBA", (16, 16), YELLOW + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=YELLOW_DARK + (255,))
    for x in range(-16, 16, 6):
        d.line((x, 15, x + 15, 0), fill=DARK + (255,), width=1)
    noisy(img, 28, 5)
    return img


def bridge_mast_image():
    img = Image.new("RGBA", (16, 16), STEEL + (255,))
    d = ImageDraw.Draw(img)
    for y in (0, 5, 10):
        d.line((0, y, 15, y), fill=STEEL_DARK + (255,))
    d.line((0, 1, 15, 1), fill=STEEL_LIGHT + (255,))
    noisy(img, 29, 5)
    return img


def assembly_image():
    img = Image.new("RGBA", (16, 16), (150, 154, 140, 255))
    d = ImageDraw.Draw(img)
    for x in range(1, 16, 3):
        d.line((x, 0, x, 15), fill=(112, 116, 104, 255))
    for y in (2, 7, 12):
        d.line((0, y, 15, y), fill=(186, 190, 176, 255))
    noisy(img, 30, 4)
    return img


def plate_image():
    """The maker's plate on the girder: generic, no brand. 160 x 40 (the quad is 1.6 m by 0.4 m)."""
    from PIL import ImageFont
    img = Image.new("RGBA", (160, 40), (226, 228, 224, 255))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 159, 39), outline=(70, 76, 82, 255))
    d.rectangle((2, 2, 157, 37), outline=(150, 156, 160, 255))
    lines = ("REFUELING PLATFORM", "MAIN HOIST  /  AUX HOIST")
    font = None
    for size in (14, 13, 12, 11, 10, 9):
        for name in ("arialbd.ttf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf"):
            try:
                candidate = ImageFont.truetype(name, size)
            except OSError:
                continue
            if max(d.textlength(t, font=candidate) for t in lines) <= 148:
                font = candidate
                break
        if font:
            break
    if font is None:
        font = ImageFont.load_default()
    for text, y in zip(lines, (6, 22)):
        w = d.textlength(text, font=font)
        d.text(((160 - w) / 2, y), text, fill=(30, 36, 44, 255), font=font)
    return img


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def textures():
    os.makedirs(TEX, exist_ok=True)
    for name, image in (("refuelling_rail", rail_image()), ("refuelling_rail_tie", tie_image()),
                        ("transfer_canal", canal_image()), ("transfer_canal_water", water_image()),
                        ("canal_gate", gate_image()), ("refuelling_bridge_console_side", console_side_image()),
                        ("refuelling_bridge_console_top", console_top_image()),
                        ("refuelling_bridge_console_screen", console_screen_image()),
                        ("refuelling_bridge_steel", bridge_steel_image()), ("refuelling_bridge_mast", bridge_mast_image()),
                        ("refuelling_assembly", assembly_image()),
                        ("refuelling_bridge_plate", plate_image())):
        image.save(os.path.join(TEX, name + ".png"))


def face(texture, uv=None, cull=None):
    f = {"texture": "#" + texture}
    if uv:
        f["uv"] = uv
    if cull:
        f["cullface"] = cull
    return f


def element(lo, hi, texture, faces=("north", "south", "east", "west", "up", "down")):
    return {"from": lo, "to": hi, "faces": {d: {"texture": "#" + texture} for d in faces}}


def rail_models():
    elements = [element([0, 0, 0], [16, 2, 16], "tie"),
                element([0, 2, 3], [16, 5, 5], "rail"), element([0, 2, 11], [16, 5, 13], "rail")]
    write(os.path.join(MODELS, "block", "refuelling_rail.json"), {
        "textures": {"tie": "bwr:block/refuelling_rail_tie", "rail": "bwr:block/refuelling_rail",
                     "particle": "bwr:block/refuelling_rail_tie"}, "elements": elements})
    write(os.path.join(STATES, "refuelling_rail.json"), {"variants": {
        "axis=x": {"model": "bwr:block/refuelling_rail"},
        "axis=z": {"model": "bwr:block/refuelling_rail", "y": 90}}})
    write(os.path.join(MODELS, "item", "refuelling_rail.json"), {"parent": "bwr:block/refuelling_rail"})


def trough(floor, top, water, plate=None):
    """A trench block for flow along x: two long walls, the floor if nothing is below, a water body (to the surface in the top
    block, to the full height in a block with another above it) and, in a gate, a plate across it."""
    y0 = 0 if not floor else 2
    elements = [element([0, 0, 0], [16, 16, 2], "canal"), element([0, 0, 14], [16, 16, 16], "canal")]
    if floor:
        elements.append(element([0, 0, 0], [16, 2, 16], "canal"))
    if water:
        # only the surface (in the top block) and the sides against the walls: no end faces between neighbours and no floor, so
        # translucent layers never stack up and a view down the trench or from inside it stays clear
        elements.append(element([0, y0, 2], [16, 13 if top else 16, 14], "water", ("north", "south", "up") if top else ("north", "south")))
    if plate:
        elements.append(element(plate[0], plate[1], "plate"))
    return elements


def canal_models():
    tex = {"canal": "bwr:block/transfer_canal", "water": "bwr:block/transfer_canal_water",
           "plate": "bwr:block/canal_gate", "particle": "bwr:block/transfer_canal"}
    for floor in (False, True):
        for top in (False, True):
            for wet in (False, True):
                suffix = "%s_%s_%s" % ("floor" if floor else "nofloor", "top" if top else "mid", "wet" if wet else "dry")
                model = {"textures": tex, "elements": trough(floor, top, wet)}
                if wet:
                    model["render_type"] = "minecraft:translucent"
                write(os.path.join(MODELS, "block", "transfer_canal_%s.json" % suffix), model)
                for openness, plate in (("closed", ([7, 2 if floor else 0, 2], [9, 20 if top else 16, 14])), ("open", ([7, 2 if floor else 0, 2], [9, 3 if floor else 1, 14]))):
                    gate = {"textures": tex, "elements": trough(floor, top, wet, plate)}
                    if wet:
                        gate["render_type"] = "minecraft:translucent"
                    write(os.path.join(MODELS, "block", "canal_gate_%s_%s.json" % (openness, suffix)), gate)
    variants = {}
    gate_variants = {}
    for axis, rot in (("x", 0), ("z", 90)):
        for floor in ("false", "true"):
            for top in ("false", "true"):
                for water in ("false", "true"):
                    suffix = "%s_%s_%s" % ("floor" if floor == "true" else "nofloor", "top" if top == "true" else "mid", "wet" if water == "true" else "dry")
                    v = {"model": "bwr:block/transfer_canal_" + suffix}
                    if rot:
                        v["y"] = rot
                    variants["axis=%s,floor=%s,top=%s,water=%s" % (axis, floor, top, water)] = v
                    for op in ("false", "true"):
                        g = {"model": "bwr:block/canal_gate_%s_%s" % ("open" if op == "true" else "closed", suffix)}
                        if rot:
                            g["y"] = rot
                        gate_variants["axis=%s,floor=%s,open=%s,top=%s,water=%s" % (axis, floor, op, top, water)] = g
    write(os.path.join(STATES, "transfer_canal.json"), {"variants": variants})
    write(os.path.join(STATES, "canal_gate.json"), {"variants": gate_variants})
    write(os.path.join(MODELS, "item", "transfer_canal.json"), {"parent": "bwr:block/transfer_canal_floor_top_wet"})
    write(os.path.join(MODELS, "item", "canal_gate.json"), {"parent": "bwr:block/canal_gate_closed_floor_top_wet"})


def console_model():
    tex = {"side": "bwr:block/refuelling_bridge_console_side", "top": "bwr:block/refuelling_bridge_console_top",
           "screen": "bwr:block/refuelling_bridge_console_screen", "particle": "bwr:block/refuelling_bridge_console_side"}
    cabinet = {"from": [1, 0, 1], "to": [15, 10, 15], "faces": {d: {"texture": "#side"} for d in ("north", "south", "east", "west", "down")}}
    cabinet["faces"]["up"] = {"texture": "#top"}
    desk = {"from": [0, 10, 0], "to": [16, 12, 16], "faces": {d: {"texture": "#side"} for d in ("north", "south", "east", "west", "down")}}
    desk["faces"]["up"] = {"texture": "#top"}
    screen = {"from": [2, 12, 10], "to": [14, 16, 12], "rotation": {"angle": -22.5, "axis": "x", "origin": [8, 12, 11]},
              "faces": {"north": {"texture": "#screen"}, "south": {"texture": "#side"}, "east": {"texture": "#side"},
                        "west": {"texture": "#side"}, "up": {"texture": "#side"}, "down": {"texture": "#side"}}}
    write(os.path.join(MODELS, "block", "refuelling_bridge_console.json"), {"textures": tex, "elements": [cabinet, desk, screen]})
    write(os.path.join(STATES, "refuelling_bridge_console.json"), {"variants": {
        "facing=north": {"model": "bwr:block/refuelling_bridge_console"},
        "facing=east": {"model": "bwr:block/refuelling_bridge_console", "y": 90},
        "facing=south": {"model": "bwr:block/refuelling_bridge_console", "y": 180},
        "facing=west": {"model": "bwr:block/refuelling_bridge_console", "y": 270}}})
    write(os.path.join(MODELS, "item", "refuelling_bridge_console.json"), {"parent": "bwr:block/refuelling_bridge_console"})


def parts_model():
    """The three textures the bridge's block entity renderer draws (girder and trolley, mast and grapple, the carried assembly), shown
    as a small cube of each so the asset audit and a model viewer can see what the renderer uses."""
    tex = {"steel": "bwr:block/refuelling_bridge_steel", "mast": "bwr:block/refuelling_bridge_mast",
           "assembly": "bwr:block/refuelling_assembly", "particle": "bwr:block/refuelling_bridge_steel"}
    write(os.path.join(MODELS, "block", "refuelling_bridge_parts.json"), {"textures": tex, "elements": [
        element([0, 0, 0], [5, 5, 5], "steel"), element([6, 0, 0], [11, 5, 5], "mast"), element([0, 6, 0], [3, 16, 3], "assembly")]})


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


def recipes():
    iron = {"tag": "c:ingots/iron"}
    shaped("refuelling_rail", {"I": iron, "G": {"item": "minecraft:gray_concrete"}}, ["I I", "GGG", "I I"], 4)
    shaped("transfer_canal", {"G": {"item": "minecraft:gray_concrete"}, "I": iron}, ["G G", "GIG"], 4)
    shaped("canal_gate", {"I": iron, "C": {"item": "bwr:transfer_canal"}}, ["III", "ICI"])
    shaped("refuelling_bridge_console", {"I": iron, "P": {"item": "minecraft:glass_pane"}, "C": {"item": "minecraft:comparator"}},
           ["PPP", "ICI", "III"])


def tags():
    names = ["bwr:refuelling_rail", "bwr:transfer_canal", "bwr:canal_gate", "bwr:refuelling_bridge_console"]
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
    rail_models()
    canal_models()
    console_model()
    parts_model()
    for n in ("refuelling_rail", "transfer_canal", "canal_gate", "refuelling_bridge_console"):
        loot(n)
    recipes()
    tags()
    print("refuelling bridge assets written")


if __name__ == "__main__":
    main()
