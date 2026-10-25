#!/usr/bin/env python3
"""Generates the isotope capsule icons and the stripped waste icon, and their item models.

Run from the repository root:  python tools-make-isotope-assets.py
Standard library plus Pillow. Same 128 x 128 hand-drawn style as tools-make-reprocessing-assets.py: a sealed steel
capsule with a coloured band per isotope (strontium red, caesium blue, americium green) and a hazard trefoil dot;
the stripped waste is the waste canister without its hazard band.
"""
import json
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "mod/src/main/resources/assets/bwr")
TEX = os.path.join(RES, "textures/item/fuel")
MODELS = os.path.join(RES, "models/item")


def capsule(d, band):
    d.ellipse((30, 104, 98, 120), fill=(0, 0, 0, 60))
    # the steel body, slightly lit on the left
    d.rectangle((44, 26, 84, 108), fill=(150, 158, 168, 255))
    d.rectangle((44, 26, 56, 108), fill=(186, 194, 204, 255))
    d.rectangle((74, 26, 84, 108), fill=(104, 112, 124, 255))
    d.ellipse((44, 16, 84, 36), fill=(178, 186, 196, 255))
    d.ellipse((44, 98, 84, 118), fill=(92, 100, 112, 255))
    # the colour band
    d.rectangle((44, 56, 84, 80), fill=band)
    d.rectangle((44, 56, 56, 80), fill=tuple(min(255, c + 36) for c in band[:3]) + (255,))
    d.rectangle((74, 56, 84, 80), fill=tuple(max(0, c - 40) for c in band[:3]) + (255,))
    # the trefoil: a dot with three wedges
    d.ellipse((58, 62, 70, 74), fill=(24, 24, 24, 255))
    d.pieslice((52, 56, 76, 80), 30, 90, fill=(24, 24, 24, 255))
    d.pieslice((52, 56, 76, 80), 150, 210, fill=(24, 24, 24, 255))
    d.pieslice((52, 56, 76, 80), 270, 330, fill=(24, 24, 24, 255))
    d.ellipse((60, 64, 68, 72), fill=band)
    d.rectangle((44, 22, 84, 30), outline=(70, 78, 90, 255))


def stripped(d):
    d.ellipse((24, 100, 104, 118), fill=(0, 0, 0, 60))
    d.rectangle((40, 30, 88, 108), fill=(62, 66, 62, 255))
    d.ellipse((40, 22, 88, 40), fill=(88, 94, 88, 255))
    d.ellipse((40, 98, 88, 116), fill=(46, 50, 46, 255))
    d.rectangle((40, 60, 88, 76), fill=(96, 100, 94, 255))
    d.rectangle((40, 60, 41, 76), fill=(62, 66, 62, 255))
    d.rectangle((87, 60, 88, 76), fill=(62, 66, 62, 255))
    d.ellipse((40, 22, 88, 40), outline=(46, 52, 46, 255))


CYAN_BRIGHT = (96, 206, 224, 255)
CYAN_DARK = (30, 96, 116, 255)
AMBER_BRIGHT = (236, 170, 50, 255)
AMBER_DARK = (124, 80, 22, 255)
BAND = (58, 63, 69, 255)
FONT = {  # 3 x 5 pixel letters
    "I": ["###", ".#.", ".#.", ".#.", "###"],
    "S": ["###", "#..", "###", "..#", "###"],
    "O": ["###", "#.#", "#.#", "#.#", "###"],
}


def block_textures():
    """The reprocessing plant's textures with the cyan swapped for amber and the REP lettering for ISO."""
    tex = os.path.join(RES, "textures/block")
    for name in ("top", "side"):
        img = Image.open(os.path.join(tex, "reprocessing_plant_%s.png" % name)).convert("RGBA")
        px = img.load()
        for y in range(img.height):
            for x in range(img.width):
                if name == "side" and 8 <= y < 15 and 1 <= x < 15 and px[x, y] in (CYAN_BRIGHT, CYAN_DARK):
                    px[x, y] = BAND
                elif px[x, y] == CYAN_BRIGHT:
                    px[x, y] = AMBER_BRIGHT
                elif px[x, y] == CYAN_DARK:
                    px[x, y] = AMBER_DARK
        if name == "side":
            for i, ch in enumerate("ISO"):
                for dy, row in enumerate(FONT[ch]):
                    for dx, c in enumerate(row):
                        if c == "#":
                            px[2 + i * 4 + dx, 9 + dy] = AMBER_BRIGHT
        img.save(os.path.join(tex, "isotope_separator_%s.png" % name))


ICONS = {
    "strontium_90_capsule": lambda d: capsule(d, (214, 66, 52, 255)),
    "caesium_137_capsule": lambda d: capsule(d, (70, 120, 224, 255)),
    "americium_241_capsule": lambda d: capsule(d, (84, 178, 92, 255)),
    "stripped_waste": stripped,
}

if __name__ == "__main__":
    block_textures()
    for name, painter in ICONS.items():
        img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
        painter(ImageDraw.Draw(img, "RGBA"))
        img.save(os.path.join(TEX, name + ".png"))
        with open(os.path.join(MODELS, name + ".json"), "w", newline="\n") as f:
            json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "bwr:item/fuel/" + name}}, f, indent=2)
            f.write("\n")
    print("written")
