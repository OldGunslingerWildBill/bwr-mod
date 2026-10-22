#!/usr/bin/env python3
"""Generates the reprocessing plant's block textures and the three new fuel-cycle item icons.

Run from the repository root:  python tools-make-reprocessing-assets.py
Standard library plus Pillow. The block textures are the fuel fabricator's with the green swapped for a cold cyan and
the FAB lettering replaced; the item icons are small hand-drawn 128 x 128 pictures in the style of the depleted
uranium ingot (a dark slab with a coloured label), kept procedural so they can be regenerated and tweaked.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(ROOT, "mod/src/main/resources/assets/bwr/textures")

GREEN_BRIGHT = (126, 208, 74, 255)
GREEN_DARK = (62, 112, 36, 255)
CYAN_BRIGHT = (96, 206, 224, 255)
CYAN_DARK = (30, 96, 116, 255)
BAND = (58, 63, 69, 255)

FONT = {  # 3 x 5 pixel letters
    "R": ["###", "#.#", "##.", "#.#", "#.#"],
    "E": ["###", "#..", "##.", "#..", "###"],
    "P": ["###", "#.#", "###", "#..", "#.."],
}


def recolor(img):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            if px[x, y] == GREEN_BRIGHT:
                px[x, y] = CYAN_BRIGHT
            elif px[x, y] == GREEN_DARK:
                px[x, y] = CYAN_DARK


def block_textures():
    top = Image.open(os.path.join(TEX, "block/fuel_fabricator_top.png")).convert("RGBA")
    recolor(top)
    top.save(os.path.join(TEX, "block/reprocessing_plant_top.png"))

    side = Image.open(os.path.join(TEX, "block/fuel_fabricator_side.png")).convert("RGBA")
    px = side.load()
    # clear the FAB lettering (the lower band), then write REP
    for y in range(8, 15):
        for x in range(1, 15):
            if px[x, y] in (GREEN_BRIGHT, GREEN_DARK):
                px[x, y] = BAND
    recolor(side)
    for i, ch in enumerate("REP"):
        for dy, row in enumerate(FONT[ch]):
            for dx, c in enumerate(row):
                if c == "#":
                    px[2 + i * 4 + dx, 9 + dy] = CYAN_BRIGHT
    side.save(os.path.join(TEX, "block/reprocessing_plant_side.png"))


def slab(d, body, edge, label, size=128):
    """A dark ingot like the depleted uranium icon: a bevelled slab with a coloured label."""
    cx, cy = size // 2, size // 2 + 6
    top = [(cx - 34, cy - 18), (cx + 22, cy - 28), (cx + 40, cy - 12), (cx - 18, cy - 4)]
    front = [(cx - 34, cy - 18), (cx - 18, cy - 4), (cx - 18, cy + 18), (cx - 34, cy + 6)]
    side = [(cx - 18, cy - 4), (cx + 40, cy - 12), (cx + 40, cy + 10), (cx - 18, cy + 18)]
    d.polygon(front, fill=edge)
    d.polygon(side, fill=body)
    d.polygon(top, fill=tuple(min(255, c + 28) for c in body))
    d.polygon([(cx - 8, cy + 2), (cx + 30, cy - 4), (cx + 30, cy + 4), (cx - 8, cy + 10)], fill=label)
    d.ellipse((cx - 44, cy + 24, cx + 52, cy + 40), fill=(0, 0, 0, 60))


def icon(name, painter):
    img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    painter(ImageDraw.Draw(img, "RGBA"))
    img.save(os.path.join(TEX, "item/fuel", name + ".png"))


def plutonium_ingot(d):
    slab(d, (118, 132, 150, 255), (74, 86, 102, 255), (214, 70, 60, 255))


def plutonium_nugget(d):
    cx, cy = 64, 70
    d.ellipse((cx - 30, cy + 14, cx + 30, cy + 28), fill=(0, 0, 0, 60))
    d.polygon([(cx - 24, cy + 4), (cx - 12, cy - 22), (cx + 14, cy - 24), (cx + 28, cy - 2), (cx + 16, cy + 18), (cx - 10, cy + 20)],
              fill=(118, 132, 150, 255))
    d.polygon([(cx - 12, cy - 22), (cx + 14, cy - 24), (cx + 28, cy - 2), (cx + 4, cy - 6)], fill=(150, 164, 182, 255))
    d.polygon([(cx - 24, cy + 4), (cx - 10, cy + 20), (cx + 16, cy + 18), (cx + 2, cy + 6)], fill=(74, 86, 102, 255))
    d.ellipse((cx - 4, cy - 6, cx + 10, cy + 8), fill=(214, 70, 60, 255))


def waste(d):
    # a dark canister with a hazard band
    d.ellipse((24, 100, 104, 118), fill=(0, 0, 0, 60))
    d.rectangle((40, 30, 88, 108), fill=(52, 58, 52, 255))
    d.ellipse((40, 22, 88, 40), fill=(82, 90, 82, 255))
    d.ellipse((40, 98, 88, 116), fill=(40, 44, 40, 255))
    d.rectangle((40, 52, 88, 84), fill=(224, 190, 40, 255))
    for i in range(6):
        x = 40 + i * 10
        d.polygon([(x, 84), (x + 8, 52), (x + 14, 52), (x + 6, 84)], fill=(36, 38, 36, 255))
    d.rectangle((40, 52, 41, 84), fill=(52, 58, 52, 255))
    d.rectangle((87, 52, 88, 84), fill=(52, 58, 52, 255))
    d.ellipse((40, 22, 88, 40), outline=(40, 46, 40, 255))


if __name__ == "__main__":
    block_textures()
    icon("plutonium_ingot", plutonium_ingot)
    icon("plutonium_nugget", plutonium_nugget)
    icon("fission_product_waste", waste)
    print("written")
