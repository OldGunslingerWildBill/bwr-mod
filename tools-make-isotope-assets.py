#!/usr/bin/env python3
"""Generates the isotope capsule icons and the stripped waste icon, and their item models.

Run from the repository root:  python tools-make-isotope-assets.py
Standard library plus Pillow. Same 128 x 128 hand-drawn style as tools-make-reprocessing-assets.py: a sealed steel
capsule with a coloured band per isotope (strontium red, caesium blue, americium green) and a hazard trefoil dot;
the stripped waste is the waste canister without its hazard band.

It also writes, for each isotope (strontium_90, caesium_137, americium_241, cobalt_60), a hand-drawn metal ingot icon
with a colour band and trefoil dot, plus a 16 x 16 storage block texture (3 x 3 ingot tiles framed by dark steel with
hazard-yellow corner rivets) with its block model, blockstate, item model and loot table.
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


ISOTOPE_COLOURS = {
    "strontium_90": (214, 66, 52, 255),
    "caesium_137": (70, 120, 224, 255),
    "americium_241": (84, 178, 92, 255),
    "cobalt_60": (112, 92, 200, 255),
}
DATA = os.path.join(ROOT, "mod/src/main/resources/data/bwr")


def shade(c, k):
    return tuple(max(0, min(255, int(round(v * k)))) for v in c[:3]) + (255,)


def fill_poly(img, pts, base, grad=0.0, x0=0, x1=128):
    """Fill a polygon with a flat colour, optionally lighter on the left (grad > 0) than on the right."""
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    px, mp = img.load(), mask.load()
    for y in range(img.height):
        for x in range(img.width):
            if mp[x, y]:
                px[x, y] = shade(base, 1 + grad * (0.5 - (x - x0) / (x1 - x0)))


def trefoil_dot(d, cx, cy, r, band):
    dark = (24, 24, 24, 255)
    d.ellipse((cx - r * 0.5, cy - r * 0.5, cx + r * 0.5, cy + r * 0.5), fill=dark)
    box = (cx - r, cy - r, cx + r, cy + r)
    for a in (30, 150, 270):
        d.pieslice(box, a, a + 60, fill=dark)
    d.ellipse((cx - r * 0.28, cy - r * 0.28, cx + r * 0.28, cy + r * 0.28), fill=band)


def ingot(img, band):
    """A bevelled bar seen from front-right-above: trapezoid front, top and right faces, lit from the left, with a
    coloured band wrapped round the middle and a small trefoil on the front."""
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse((6, 96, 122, 120), fill=(0, 0, 0, 60))
    steel = (150, 158, 168, 255)
    edge = (62, 70, 82, 255)
    ft_l, ft_r, fb_l, fb_r = (20, 72), (92, 72), (10, 100), (100, 100)
    off = (16, -14)
    bt_l, bt_r = (ft_l[0] + off[0], ft_l[1] + off[1]), (ft_r[0] + off[0], ft_r[1] + off[1])
    bb_r = (fb_r[0] + off[0], fb_r[1] + off[1])
    front = [fb_l, fb_r, ft_r, ft_l]
    top = [ft_l, ft_r, bt_r, bt_l]
    right = [fb_r, bb_r, bt_r, ft_r]
    fill_poly(img, right, shade(steel, 0.62))
    fill_poly(img, top, shade(steel, 1.22), 0.35, 10, 108)
    fill_poly(img, front, shade(steel, 0.98), 0.45, 10, 100)
    # the colour band across the front, over the top and (darker) down the right end
    bx0, bx1 = 40, 70
    fill_poly(img, [(bx0, 100), (bx1, 100), (bx1, 72), (bx0, 72)], band, 0.45, 10, 100)
    fill_poly(img, [(bx0, 72), (bx1, 72), (bx1 + off[0], 72 + off[1]), (bx0 + off[0], 72 + off[1])],
              shade(band, 1.18), 0.3, 10, 108)
    # the recessed stamp on the top face either side of the band
    for a, b in ((26, 36), (74, 84)):
        d.polygon([(a, 69), (b, 69), (b + 10, 61), (a + 10, 61)], fill=shade(steel, 0.9))
    # bevel highlights on the near top edge and the left end, shadow along the bottom
    d.line([ft_l, ft_r], fill=shade(steel, 1.5), width=2)
    d.line([fb_l, ft_l], fill=shade(steel, 1.45), width=2)
    d.line([bt_l, bt_r], fill=shade(steel, 1.35), width=1)
    d.line([fb_l, fb_r], fill=(40, 46, 56, 255), width=2)
    d.line([fb_r, bb_r], fill=(40, 46, 56, 255), width=2)
    # outline
    d.line([fb_l, fb_r, bb_r, bt_r, bt_l, ft_l, fb_l], fill=edge, width=2, joint="curve")
    d.line([ft_r, bt_r], fill=edge, width=1)
    d.line([ft_l, ft_r], fill=edge, width=1)
    d.line([fb_r, ft_r], fill=edge, width=2)
    trefoil_dot(d, 55, 86, 10, band)


def block_tile(px, x0, y0, base, seed):
    """One 4 x 4 ingot-like tile: lit top and left rows, shaded bottom and right, a bright specular pixel."""
    for dy in range(4):
        for dx in range(4):
            k = 1.0
            if dy == 0 or dx == 0:
                k = 1.22
            if dy == 3 or dx == 3:
                k = 0.72
            if (dx, dy) in ((0, 3), (3, 0)):
                k = 0.95
            if dx and dy and dx < 3 and dy < 3 and (dx * 7 + dy * 3 + seed) % 5 == 0:
                k = 0.9
            px[x0 + dx, y0 + dy] = shade(base, k)
    px[x0, y0] = shade(base, 1.6)


def isotope_block(band):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    steel, steel_hi, steel_lo = (44, 50, 58, 255), (84, 92, 104, 255), (26, 30, 36, 255)
    for y in range(16):
        for x in range(16):
            px[x, y] = steel
    for i in range(16):
        px[i, 0] = steel_hi
        px[0, i] = steel_hi
        px[i, 15] = steel_lo
        px[15, i] = steel_lo
    base = shade(band, 0.86)
    for j in range(3):
        for i in range(3):
            block_tile(px, 1 + i * 5, 1 + j * 5, base, i + 3 * j)
    # hazard-yellow rivets in the four corners, a black trefoil hub on the middle tile
    yellow = (236, 178, 24, 255)
    for x, y in ((0, 0), (15, 0), (0, 15), (15, 15)):
        px[x, y] = yellow
    dark = (18, 18, 20, 255)
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):
        px[x, y] = dark
    for x, y in ((7, 6), (6, 8), (9, 8)):
        px[x, y] = yellow
    return img


NL = chr(10)


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline=NL) as f:
        json.dump(data, f, indent=2)
        f.write(NL)


def isotope_ingots_and_blocks():
    for iso, band in ISOTOPE_COLOURS.items():
        img = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
        ingot(img, band)
        img.save(os.path.join(TEX, iso + "_ingot.png"))
        write_json(os.path.join(MODELS, iso + "_ingot.json"),
                   {"parent": "minecraft:item/generated", "textures": {"layer0": "bwr:item/fuel/" + iso + "_ingot"}})
        name = iso + "_block"
        isotope_block(band).save(os.path.join(RES, "textures/block", name + ".png"))
        write_json(os.path.join(RES, "models/block", name + ".json"),
                   {"parent": "minecraft:block/cube_all", "textures": {"all": "bwr:block/" + name}})
        write_json(os.path.join(RES, "blockstates", name + ".json"), {"variants": {"": {"model": "bwr:block/" + name}}})
        write_json(os.path.join(MODELS, name + ".json"), {"parent": "bwr:block/" + name})
        write_json(os.path.join(DATA, "loot_table/blocks", name + ".json"), {
            "type": "minecraft:block",
            "pools": [{"bonus_rolls": 0.0, "conditions": [{"condition": "minecraft:survives_explosion"}],
                       "entries": [{"type": "minecraft:item", "name": "bwr:" + name}], "rolls": 1.0}],
            "random_sequence": "bwr:blocks/" + name})


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
    isotope_ingots_and_blocks()
    print("written")
