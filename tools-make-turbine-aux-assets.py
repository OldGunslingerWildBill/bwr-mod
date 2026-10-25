#!/usr/bin/env python3
"""Generates the two consumable items of the turbine auxiliaries: textures, item models and recipes.

Run from the repository root:  python tools-make-turbine-aux-assets.py          (add --check to verify without writing)
Standard library plus Pillow. Everything is drawn procedurally; no external art.

  turbine_oil_drum    an amber steel drum of lubricating oil (refills the Turbine Main Oil Tank)
  hydrogen_cylinder   a pale grey-blue gas cylinder with a valve cap (refills the Generator Cooling Skid)
"""
import io
import json
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources")
TEX = os.path.join(RES, "assets/bwr/textures/item")
MODELS = os.path.join(RES, "assets/bwr/models/item")
RECIPES = os.path.join(RES, "data/bwr/recipe")

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


def item(id, image, recipe):
    put_png(os.path.join(TEX, id + ".png"), image)
    put_json(os.path.join(MODELS, id + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "bwr:item/" + id}})
    put_json(os.path.join(RECIPES, id + ".json"),
             {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": recipe[0],
              "key": {k: {"item": v} for k, v in recipe[1].items()}, "result": {"id": "bwr:" + id, "count": 1}})


item("turbine_oil_drum", drum(), ([" C ", "CIC", " C "], {"C": "minecraft:coal", "I": "minecraft:iron_ingot"}))
item("hydrogen_cylinder", cylinder(), ([" I ", "IWI", " I "], {"I": "minecraft:iron_ingot", "W": "minecraft:water_bucket"}))

for p, data in OUTPUTS.items():
    if "--check" in sys.argv:
        assert os.path.isfile(p) and open(p, "rb").read() == data, "Stale turbine aux asset: " + p
    else:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)
print(len(OUTPUTS), "turbine aux item assets verified" if "--check" in sys.argv else "turbine aux item assets written")
