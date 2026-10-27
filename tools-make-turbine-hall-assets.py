#!/usr/bin/env python3
"""Generates the turbine hall's items and stands (TURBINE-MAINTENANCE.md): textures, models, blockstates, loot tables and recipes.

Run from the repository root:  python tools-make-turbine-hall-assets.py          (add --check to verify without writing)
Standard library plus Pillow. Everything is drawn procedurally; no external art, no brand.

  lp_blade_section          a bladed rotor section seen end-on: grey disc, pale blades with a stellite tip band
  damaged_lp_blade_section  the same, rust-dark, blades missing and broken short
  lp_turbine_hood           (recipe: five iron blocks, a spare hood) the upper casing (exhaust hood) of an LP turbine, green-grey, with lifting lugs
  wrecked_lp_turbine_hood   the same hood torn open, scorched and rust-streaked (scrapped alone for three iron blocks)
  turbine_rebuild_kit       a steel crate of bearing shells and coupling bolts (recipe: iron blocks, copper, an anvil)
  lp_blade_stand            a dark steel base with a yellow saddle (the two saddles are drawn by the block entity)
  casing_laydown_stand      a low grey plinth with two yellow cradle beams
  turbine_shaft_wreck       no texture of its own: the block entity draws the rotor half; its model is only the break particle
"""
import io
import json
import math
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
A = os.path.join(RES, "assets/bwr")
D = os.path.join(RES, "data/bwr")
OUTPUTS = {}


def put(path, data):
    OUTPUTS[path] = data


def put_json(path, data):
    put(path, (json.dumps(data, indent=2) + "\n").encode())


def put_png(path, image):
    buf = io.BytesIO()
    image.save(buf, "PNG", optimize=False)
    put(path, buf.getvalue())


def section_icon(damaged):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    cx = cy = 7.5
    blade = (122, 76, 50, 255) if damaged else (200, 205, 212, 255)
    tip = (110, 70, 45, 255) if damaged else (226, 214, 150, 255)
    disc = (98, 66, 46, 255) if damaged else (128, 133, 140, 255)
    hub = (70, 72, 76, 255)
    for y in range(16):
        for x in range(16):
            dx, dy = x - cx, y - cy
            r = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            if r <= 1.6:
                px[x, y] = hub
            elif r <= 3.6:
                px[x, y] = disc
            elif r <= 7.6:
                k = (a / (2 * math.pi) * 14) % 1.0
                n = int(((a + math.pi) / (2 * math.pi)) * 14) % 14
                if damaged and (n % 4 == 0 and r > 4.8 or n % 7 == 3):
                    continue
                if k < 0.55:
                    px[x, y] = tip if r > 6.4 else blade
    # a dark rim on the blade tips' shadow side
    for y in range(16):
        for x in range(16):
            if px[x, y][3] and (x + 1 < 16 and y + 1 < 16) and px[x + 1, y + 1][3] == 0 and math.hypot(x - cx, y - cy) > 3.6:
                r, g, b, al = px[x, y]
                px[x, y] = (int(r * .7), int(g * .7), int(b * .7), al)
    return img


def hood_icon():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    body = (104, 122, 112, 255)
    dark = (70, 84, 76, 255)
    light = (150, 168, 156, 255)
    lug = (230, 180, 40, 255)
    rib = (124, 132, 134, 255)
    for y in range(2, 14):
        for x in range(1, 15):
            # a fabricated exhaust hood seen from the side: vertical walls, sloped shoulders, a flat roof, the joint flange along the bottom
            shoulder = 7 - min(x - 1, 14 - x)
            if y >= max(3, shoulder):
                px[x, y] = light if y < 7 else body
    for x in range(0, 16):
        px[x, 13] = dark
        px[x, 14] = dark
    for x in (5, 10):
        for y in range(4, 13):
            px[x, y] = rib
    for x in (6, 7, 8, 9):
        px[x, 2] = dark
    for x in (3, 12):
        px[x, 4] = lug
        px[x, 3] = lug
    for x in (2, 6, 10, 14):
        px[x, 12] = dark
    return img


def wrecked_hood_icon():
    img = hood_icon()
    px = img.load()
    hole = (28, 26, 24, 255)
    scorch = (58, 52, 46, 255)
    rust = (132, 78, 44, 255)
    # a ragged tear through the roof and the left shoulder, scorched round its edge, plates bent up out of it
    for y in range(2, 13):
        for x in range(1, 15):
            if not px[x, y][3]:
                continue
            d = math.hypot((x - 6.5) * 0.9, (y - 5.5) * 1.2)
            if d < 2.6 + 0.8 * math.sin(3 * x + y):
                px[x, y] = hole
            elif d < 4.2:
                px[x, y] = scorch
            elif (x * 7 + y * 3) % 11 == 0:
                px[x, y] = rust
    for x, y in ((4, 1), (5, 2), (9, 1), (8, 2), (3, 3)):
        px[x, y] = (150, 160, 152, 255)
    return img


def rebuild_kit_icon():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    crate = (96, 104, 112, 255)
    edge = (60, 66, 72, 255)
    copper = (190, 110, 60, 255)
    bolt = (200, 204, 210, 255)
    for y in range(5, 15):
        for x in range(1, 15):
            px[x, y] = edge if x in (1, 14) or y in (5, 14) else crate
    for x in range(5, 11):
        px[x, 3] = edge
    for y in (3, 4):
        px[5, y] = edge
        px[10, y] = edge
    # two bearing half shells (copper) and a row of coupling bolts
    for x in range(3, 8):
        px[x, 8] = copper
        px[x, 9] = copper if x in (3, 7) else crate
    for x in range(8, 13):
        px[x, 8] = copper
        px[x, 9] = copper if x in (8, 12) else crate
    for x in (3, 5, 7, 9, 11):
        px[x, 12] = bolt
    return img


def cuboid(f, t, tex):
    return {"from": f, "to": t, "faces": {d: {"texture": tex} for d in ("north", "south", "east", "west", "up", "down")}}


def main():
    check = "--check" in sys.argv
    put_png(os.path.join(A, "textures/item/lp_blade_section.png"), section_icon(False))
    put_png(os.path.join(A, "textures/item/damaged_lp_blade_section.png"), section_icon(True))
    put_png(os.path.join(A, "textures/item/lp_turbine_hood.png"), hood_icon())
    put_png(os.path.join(A, "textures/item/wrecked_lp_turbine_hood.png"), wrecked_hood_icon())
    put_png(os.path.join(A, "textures/item/turbine_rebuild_kit.png"), rebuild_kit_icon())
    for item in ("lp_blade_section", "damaged_lp_blade_section", "lp_turbine_hood", "wrecked_lp_turbine_hood", "turbine_rebuild_kit"):
        put_json(os.path.join(A, f"models/item/{item}.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"bwr:item/{item}"}})
    put_json(os.path.join(A, "models/block/lp_blade_stand.json"), {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/iron_block", "base": "minecraft:block/gray_concrete", "steel": "minecraft:block/iron_block",
                     "saddle": "minecraft:block/yellow_concrete"},
        "elements": [cuboid([0, 0, 0], [16, 3, 16], "#base"), cuboid([2, 3, 6], [14, 6, 10], "#saddle"), cuboid([6, 3, 2], [10, 5, 14], "#steel")]})
    put_json(os.path.join(A, "models/block/casing_laydown_stand.json"), {
        "parent": "minecraft:block/block",
        "textures": {"particle": "minecraft:block/iron_block", "base": "minecraft:block/gray_concrete", "beam": "minecraft:block/yellow_concrete"},
        "elements": [cuboid([0, 0, 0], [16, 4, 16], "#base"), cuboid([1, 4, 0], [4, 8, 16], "#beam"), cuboid([12, 4, 0], [15, 8, 16], "#beam")]})
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    for block in ("lp_blade_stand", "casing_laydown_stand"):
        put_json(os.path.join(A, f"blockstates/{block}.json"),
                 {"variants": {f"facing={d}": ({"model": f"bwr:block/{block}", "y": r} if r else {"model": f"bwr:block/{block}"}) for d, r in rot.items()}})
        put_json(os.path.join(A, f"models/item/{block}.json"), {"parent": f"bwr:block/{block}"})
        put_json(os.path.join(D, f"loot_table/blocks/{block}.json"), {
            "type": "minecraft:block",
            "pools": [{"bonus_rolls": 0.0, "conditions": [{"condition": "minecraft:survives_explosion"}],
                       "entries": [{"type": "minecraft:item", "name": f"bwr:{block}"}], "rolls": 1.0}],
            "random_sequence": f"bwr:blocks/{block}"})
    # The rotor half a shaft excursion leaves on the floor: drawn by its block entity; broken up, it is a damaged section.
    put_json(os.path.join(A, "models/block/turbine_shaft_wreck.json"), {"textures": {"particle": "minecraft:block/iron_block"}})
    put_json(os.path.join(A, "models/item/turbine_shaft_wreck.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "bwr:item/damaged_lp_blade_section"}})
    put_json(os.path.join(A, "blockstates/turbine_shaft_wreck.json"),
             {"variants": {f"axis={a}": {"model": "bwr:block/turbine_shaft_wreck"} for a in ("x", "z")}})
    put_json(os.path.join(D, "loot_table/blocks/turbine_shaft_wreck.json"), {
        "type": "minecraft:block",
        "pools": [{"bonus_rolls": 0.0, "conditions": [{"condition": "minecraft:survives_explosion"}],
                   "entries": [{"type": "minecraft:item", "name": "bwr:damaged_lp_blade_section"}], "rolls": 1.0}],
        "random_sequence": "bwr:blocks/turbine_shaft_wreck"})
    # A rebuild kit: casing plate (iron blocks), bearing shells (copper), coupling bolts (iron), machined true on an anvil.
    put_json(os.path.join(D, "recipe/turbine_rebuild_kit.json"), {
        "type": "minecraft:crafting_shaped", "category": "misc",
        "pattern": ["BCB", "IAI", "BCB"],
        "key": {"B": {"item": "minecraft:iron_block"}, "C": {"item": "minecraft:copper_ingot"}, "I": {"item": "minecraft:iron_ingot"},
                "A": {"item": "minecraft:anvil"}},
        "result": {"id": "bwr:turbine_rebuild_kit", "count": 1}})
    put_json(os.path.join(D, "recipe/wrecked_lp_turbine_hood_scrap.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": "bwr:wrecked_lp_turbine_hood"}],
        "result": {"id": "minecraft:iron_block", "count": 3}})
    # An LP blade section: steel discs and blades (iron), the stellite erosion shields on the last-stage tips (a diamond).
    put_json(os.path.join(D, "recipe/lp_blade_section.json"), {
        "type": "minecraft:crafting_shaped", "category": "misc",
        "pattern": ["BIB", "IDI", "BIB"],
        "key": {"B": {"item": "minecraft:iron_block"}, "I": {"item": "minecraft:iron_ingot"}, "D": {"item": "minecraft:diamond"}},
        "result": {"id": "bwr:lp_blade_section", "count": 1}})
    put_json(os.path.join(D, "recipe/damaged_lp_blade_section_scrap.json"), {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": [{"item": "bwr:damaged_lp_blade_section"}],
        "result": {"id": "minecraft:iron_block", "count": 2}})
    # A replacement hood (the upper outer and inner casing halves): a half-shell of iron blocks.
    put_json(os.path.join(D, "recipe/lp_turbine_hood.json"), {
        "type": "minecraft:crafting_shaped", "category": "misc",
        "pattern": ["BBB", "B B"],
        "key": {"B": {"item": "minecraft:iron_block"}},
        "result": {"id": "bwr:lp_turbine_hood", "count": 1}})
    put_json(os.path.join(D, "recipe/lp_blade_stand.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "pattern": ["I I", "IBI", "SSS"],
        "key": {"I": {"item": "minecraft:iron_ingot"}, "B": {"item": "minecraft:iron_block"}, "S": {"item": "minecraft:smooth_stone_slab"}},
        "result": {"id": "bwr:lp_blade_stand", "count": 1}})
    put_json(os.path.join(D, "recipe/casing_laydown_stand.json"), {
        "type": "minecraft:crafting_shaped", "category": "building",
        "pattern": ["B B", "SSS"],
        "key": {"B": {"item": "minecraft:iron_block"}, "S": {"item": "minecraft:smooth_stone"}},
        "result": {"id": "bwr:casing_laydown_stand", "count": 1}})
    for tag in ("data/minecraft/tags/block/mineable/pickaxe.json", "data/minecraft/tags/block/needs_iron_tool.json"):
        path = os.path.join(RES, tag)
        data = json.load(open(path, encoding="utf-8"))
        for block in ("bwr:lp_blade_stand", "bwr:casing_laydown_stand", "bwr:turbine_shaft_wreck"):
            if block not in data["values"]:
                data["values"].append(block)
        put_json(path, data)
    bad = 0
    for path, data in sorted(OUTPUTS.items()):
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old == data:
            continue
        if check:
            print("differs:", os.path.relpath(path, ROOT))
            bad += 1
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "wb").write(data)
            print("wrote", os.path.relpath(path, ROOT))
    if check:
        print("turbine hall assets: %s" % ("OK" if not bad else "%d files differ" % bad))
        sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
