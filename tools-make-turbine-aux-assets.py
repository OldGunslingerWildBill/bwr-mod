#!/usr/bin/env python3
"""Generates the consumable items of the turbine auxiliaries: textures, item models and recipes.

Run from the repository root:  python tools-make-turbine-aux-assets.py          (add --check to verify without writing)
Standard library plus Pillow. Everything is drawn procedurally; no external art.

  turbine_oil_drum    an amber steel drum of lubricating oil (refills the Turbine Main Oil Tank)
  hydrogen_cylinder   a pale grey-blue gas cylinder with a valve cap (refills the Generator Cooling Skid)
  oil_filter_cartridge a pleated filter element between two steel end caps (the element of the Oil Filter Housing)
  oil_filter_cartridge_coarse / _fine  the same with a green (coarse, high flow) or blue (fine, high filtration) grade band
  turbine_oil_can     a small amber oil can with a spout (250 L of synthetic oil from a Mekanism reaction chamber; four fill a drum
                      when Mekanism is installed; MEKANISM-RECIPES.md). It has no crafting recipe, so none is written for it here.

The drum's recipe is the vanilla one, loaded only without Mekanism (the condition below); the Mekanism recipe beside it,
turbine_oil_drum_mekanism.json, is written by tools-generate-mekanism-recipes.py. Run that tool after this one.
"""
import io
import json
import math
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
TEX = os.path.join(RES, "assets/bwr/textures/item")
MODELS = os.path.join(RES, "assets/bwr/models/item")
RECIPES = os.path.join(RES, "data/bwr/recipe")
BLOCK_TEX = os.path.join(RES, "assets/bwr/textures/block/turbine_aux")

OUTPUTS = {}


def put(path, data):
    OUTPUTS[path] = data


def put_json(path, data):
    put(path, (json.dumps(data, indent=2) + "\n").encode())


def put_png(path, image):
    buf = io.BytesIO()
    image.save(buf, "PNG", optimize=False)
    put(path, buf.getvalue())


def rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            img.putpixel((x, y), color + (255,))


def drum():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    body, light, dark, band, lid = (176, 112, 28), (222, 154, 52), (110, 64, 14), (70, 70, 74), (150, 150, 156)
    rect(img, 3, 3, 12, 14, body)
    rect(img, 3, 3, 4, 14, light)          # lit side
    rect(img, 11, 3, 12, 14, dark)         # shaded side
    rect(img, 3, 5, 12, 5, band)           # rolling hoops
    rect(img, 3, 9, 12, 9, band)
    rect(img, 3, 13, 12, 13, band)
    rect(img, 3, 2, 12, 2, lid)            # top rim
    rect(img, 4, 1, 11, 1, (96, 96, 102))
    rect(img, 9, 2, 10, 2, (40, 40, 44))   # bung
    rect(img, 6, 7, 9, 7, (252, 214, 120))  # label stripe
    rect(img, 3, 15, 12, 15, (60, 36, 10))
    for x, y in ((3, 2), (12, 2), (3, 14), (12, 14)):
        img.putpixel((x, y), (0, 0, 0, 0))
    return img


def cylinder():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    body, light, dark, band, steel = (150, 172, 190), (206, 224, 236), (96, 116, 134), (60, 90, 120), (88, 92, 98)
    rect(img, 5, 5, 10, 14, body)
    rect(img, 5, 5, 6, 14, light)
    rect(img, 9, 5, 10, 14, dark)
    rect(img, 6, 4, 9, 4, body)            # rounded shoulder
    rect(img, 7, 3, 8, 3, body)
    rect(img, 7, 1, 8, 2, steel)           # valve stem
    rect(img, 6, 1, 9, 1, steel)           # valve cap
    rect(img, 5, 8, 10, 9, band)           # gas colour band
    rect(img, 5, 15, 10, 15, (70, 76, 82))
    return img


def cartridge(band=None):
    """band: None for the standard cartridge (as it always was), or a colour for the grade: green coarse, blue fine."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    cap, cap_dark, media, fold, core = (150, 150, 156), (96, 96, 102), (232, 222, 190), (186, 172, 128), (70, 70, 74)
    rect(img, 4, 1, 11, 2, cap)             # top end cap
    rect(img, 4, 2, 11, 2, cap_dark)
    rect(img, 4, 13, 11, 14, cap)           # bottom end cap
    rect(img, 4, 14, 11, 14, cap_dark)
    rect(img, 4, 3, 11, 12, media)          # the pleated media
    for x in (5, 7, 9, 11):
        rect(img, x, 3, x, 12, fold)        # pleats
    rect(img, 4, 3, 4, 12, (255, 248, 222))  # lit edge
    rect(img, 7, 0, 8, 0, core)             # centre tube showing at the top
    rect(img, 7, 15, 8, 15, core)
    if band is not None:
        rect(img, 4, 6, 11, 8, band)        # the grade band across the media
        rect(img, 4, 6, 11, 6, tuple(min(255, c + 40) for c in band))
        rect(img, 4, 8, 11, 8, tuple(int(c * 0.65) for c in band))
    for x, y in ((4, 1), (11, 1), (4, 14), (11, 14)):
        img.putpixel((x, y), (0, 0, 0, 0))
    return img


GRADE_GREEN, GRADE_BLUE = (52, 168, 76), (52, 104, 214)
NOT_MEKANISM = [{"type": "neoforge:not", "value": {"type": "neoforge:mod_loaded", "modid": "mekanism"}}]


def can():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    body, light, dark, cap, spout = (176, 112, 28), (222, 154, 52), (110, 64, 14), (70, 70, 74), (150, 150, 156)
    rect(img, 3, 6, 11, 14, body)
    rect(img, 3, 6, 4, 14, light)
    rect(img, 10, 6, 11, 14, dark)
    rect(img, 3, 6, 11, 6, light)
    rect(img, 3, 14, 11, 14, (60, 36, 10))
    rect(img, 5, 9, 9, 11, (252, 214, 120))   # label
    rect(img, 6, 10, 8, 10, (110, 64, 14))
    rect(img, 4, 3, 7, 5, cap)               # handle
    rect(img, 5, 4, 6, 4, (0, 0, 0))
    img.putpixel((5, 4), (0, 0, 0, 0))
    img.putpixel((6, 4), (0, 0, 0, 0))
    rect(img, 11, 7, 13, 7, spout)           # spout
    rect(img, 13, 5, 13, 7, spout)
    rect(img, 12, 4, 14, 4, cap)
    return img


def item(id, image, recipe=None, gated=False):
    put_png(os.path.join(TEX, id + ".png"), image)
    put_json(os.path.join(MODELS, id + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "bwr:item/" + id}})
    if recipe is None:
        return
    shaped = {"type": "minecraft:crafting_shaped"}
    if gated:
        shaped["neoforge:conditions"] = NOT_MEKANISM
    shaped.update({"category": "misc", "pattern": recipe[0],
                   "key": {k: {"item": v} for k, v in recipe[1].items()}, "result": {"id": "bwr:" + id, "count": 1}})
    put_json(os.path.join(RECIPES, id + ".json"), shaped)


item("turbine_oil_drum", drum(), ([" C ", "CIC", " C "], {"C": "minecraft:coal", "I": "minecraft:iron_ingot"}), gated=True)
item("turbine_oil_can", can())
item("oil_filter_cartridge", cartridge(), (["IPI", "PWP", "IPI"], {"I": "minecraft:iron_ingot", "P": "minecraft:paper", "W": "minecraft:white_wool"}), gated=True)
item("oil_filter_cartridge_coarse", cartridge(GRADE_GREEN), (["IPI", "PBP", "IPI"], {"I": "minecraft:iron_ingot", "P": "minecraft:paper", "B": "minecraft:iron_bars"}), gated=True)
item("oil_filter_cartridge_fine", cartridge(GRADE_BLUE), (["IPI", "WGW", "IPI"], {"I": "minecraft:iron_ingot", "P": "minecraft:paper", "W": "minecraft:white_wool", "G": "minecraft:glass_pane"}), gated=True)
item("hydrogen_cylinder", cylinder(), ([" I ", "IWI", " I "], {"I": "minecraft:iron_ingot", "W": "minecraft:water_bucket"}), gated=True)

# ---- the Lube Oil Conditioner's running lamp (TURBINE-AUX.md): the lens textures, as the industrial lamps' lenses are made (LIGHTING.md):
# a bright green lit lens with a labPBR specular map (R smoothness 230, G F0 0, B porosity 0, A emission: 220 across the face and 180 on the
# edge ring, as the lamp lenses; labPBR reads 255 as no emission) and a dark green glass lens with no map, so nothing glows while it is stopped.
LIT_GREEN, DARK_GREEN = (70, 255, 110), (10, 42, 22)


def lens_factor(x, y):
    c = 7.5
    d = math.hypot(x - c, y - c) / 8.0
    return (1.0 - 0.18 * min(d, 1.0)) * (0.97 + 0.03 * math.cos(2 * math.pi * d * 2.0))


def lens_image(colour):
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            f = lens_factor(x, y)
            img.putpixel((x, y), tuple(min(255, int(round(c * f))) for c in colour) + (255,))
    return img


def lens_specular():
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            img.putpixel((x, y), (230, 0, 0, 220 - (40 if edge else 0)))
    return img


put_png(os.path.join(BLOCK_TEX, "conditioner_lamp_on.png"), lens_image(LIT_GREEN))
put_png(os.path.join(BLOCK_TEX, "conditioner_lamp_on_s.png"), lens_specular())
put_png(os.path.join(BLOCK_TEX, "conditioner_lamp_off.png"), lens_image(DARK_GREEN))

for p, data in OUTPUTS.items():
    if "--check" in sys.argv:
        assert os.path.isfile(p) and open(p, "rb").read() == data, "Stale turbine aux asset: " + p
    else:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)
print(len(OUTPUTS), "turbine aux item assets verified" if "--check" in sys.argv else "turbine aux item assets written")
