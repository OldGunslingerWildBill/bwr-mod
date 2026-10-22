#!/usr/bin/env python3
"""Generates the uranium ore textures: four 16 x 16 block textures and two 128 x 128 item icons.

Run from the repository root:  python tools-make-uranium-assets.py
Standard library plus Pillow. Everything is procedural and seeded, so the files can be regenerated and tweaked.

Real uraninite (pitchblende) is black, dense and pitch-like with a greasy lustre; the only colour it shows is a dull
yellow or orange crust where it has weathered (the "uranium yellows"). So the ore is black blobs in plain stone, never a
glowing green; a few dull ochre specks are the weathering. The metal is a dark warm grey. The item icons follow the other
fuel-cycle icons (a bevelled slab with a coloured label, a soft drop shadow).
"""
import os
import random
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(ROOT, "mod/src/main/resources/assets/bwr/textures")

PITCH = [(14, 14, 17), (24, 24, 28), (36, 35, 40), (52, 51, 56)]   # darkest to lightest pitch
GLINT = (92, 92, 100)
OCHRE = [(146, 122, 44), (168, 138, 50), (120, 100, 40)]


def shade(c, d):
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + ((c[3],) if len(c) > 3 else ())


def stone_base(rng, palette, strata=None):
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = rng.choice(palette)
            # clump a little: half the time copy the pixel to the left or above
            if x and rng.random() < 0.30:
                c = px[x - 1, y]
            elif y and rng.random() < 0.30:
                c = px[x, y - 1]
            px[x, y] = c + (255,) if len(c) == 3 else c
    if strata:
        for y, d in strata:
            for x in range(16):
                if rng.random() < 0.85:
                    px[x, y] = shade(px[x, y], d)
    return img


# Pixel masks of the ore blobs ('#' = pitch). Each is placed at a position; the lighter pitch is on the lower right.
BLOBS = [
    [".##.",
     "####",
     "####",
     ".##."],
    ["##.",
     "###",
     ".##"],
    [".###.",
     "#####",
     ".###."],
    ["##",
     "##"],
    ["###",
     "###",
     ".#."],
]


def put_blob(img, rng, mask, ox, oy, deep):
    px = img.load()
    h, w = len(mask), len(mask[0])
    for dy in range(h):
        for dx in range(w):
            if mask[dy][dx] != "#":
                continue
            x, y = ox + dx, oy + dy
            if not (0 <= x < 16 and 0 <= y < 16):
                continue
            # darker in the middle, a little lighter at the lower right edge
            edge = (dx == w - 1 or dy == h - 1 or mask[dy][min(w - 1, dx + 1)] != "#" or (dy + 1 < h and mask[dy + 1][dx] != "#"))
            c = PITCH[2] if edge else rng.choice(PITCH[:2])
            px[x, y] = c + (255,)
    # one glint on the upper left of the blob (the greasy lustre) and, sometimes, a weathering speck beside it
    for dy in range(h):
        for dx in range(w):
            if mask[dy][dx] == "#":
                x, y = ox + dx, oy + dy
                if 0 <= x < 16 and 0 <= y < 16 and rng.random() < 0.5:
                    px[x, y] = GLINT + (255,)
                    return


def ore_texture(seed, palette, deep, strata=None):
    rng = random.Random(seed)
    img = stone_base(rng, palette, strata)
    spots = [(1, 2, 0), (9, 1, 2), (5, 7, 1), (11, 9, 4), (2, 11, 3), (8, 12, 1)] if not deep else \
            [(2, 1, 1), (10, 2, 3), (6, 6, 2), (1, 9, 4), (11, 10, 0), (7, 12, 3)]
    for (x, y, m) in spots:
        put_blob(img, rng, BLOBS[m], x, y, deep)
    px = img.load()
    for _ in range(4):   # weathering specks next to the pitch
        x, y = rng.randrange(16), rng.randrange(16)
        if px[x, y][:3] not in PITCH and any(
                0 <= x + dx < 16 and 0 <= y + dy < 16 and px[x + dx, y + dy][:3] in PITCH
                for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
            px[x, y] = rng.choice(OCHRE) + (255,)
    return img


def raw_block(seed=7):
    """Heavy lumps of pitch with dark crevices between them, a few ochre crusts."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), PITCH[0] + (255,))
    px = img.load()
    centres = [(3, 3), (10, 2), (7, 7), (13, 8), (2, 10), (9, 13), (14, 14), (4, 14)]
    # nearest-centre (Voronoi) lumps with the crevice where two lumps meet
    for y in range(16):
        for x in range(16):
            d = sorted(((x - cx) ** 2 + (y - cy) ** 2, i) for i, (cx, cy) in enumerate(centres))
            (d0, i0), (d1, _) = d[0], d[1]
            if d1 - d0 < 4.5:
                px[x, y] = PITCH[0] + (255,)
                continue
            cx, cy = centres[i0]
            light = (cx - x) * 0.5 + (cy - y) * 0.5          # lit from the upper left
            base = (36, 36, 42) if light < 1.2 else (54, 54, 62)
            px[x, y] = shade(base, rng.randrange(-5, 6)) + (255,)
            if light < -1.2 and rng.random() < 0.5:
                px[x, y] = shade((84, 84, 92), rng.randrange(-6, 8)) + (255,)
    for _ in range(5):
        x, y = rng.randrange(16), rng.randrange(16)
        if px[x, y][:3] != PITCH[0]:
            px[x, y] = rng.choice(OCHRE) + (255,)
    for _ in range(3):
        x, y = rng.randrange(15), rng.randrange(15)
        px[x, y] = GLINT + (255,)
    return img


def metal_block(seed=11):
    """A dark warm-grey metal block in panels, like the machine casing's bevel and rivets."""
    rng = random.Random(seed)
    base = (96, 96, 90)
    img = Image.new("RGBA", (16, 16), base + (255,))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = shade(base, rng.randrange(-5, 6)) + (255,)
    hi, lo, groove = (150, 150, 140, 255), (52, 52, 50, 255), (40, 40, 38, 255)
    # outer bevel
    for i in range(16):
        px[i, 0] = hi
        px[0, i] = hi
        px[i, 15] = lo
        px[15, i] = lo
    # a cross groove splits the face into four plates, each with its own bevel
    for i in range(1, 15):
        px[i, 7] = groove
        px[7, i] = groove
        px[i, 8] = shade(base, 14) + (255,)
        px[8, i] = shade(base, 14) + (255,)
    for ox, oy in ((1, 1), (9, 1), (1, 9), (9, 9)):
        for i in range(0, 6):
            px[ox + i, oy] = shade(base, 22) + (255,)
            px[ox, oy + i] = shade(base, 22) + (255,)
            px[ox + i, oy + 5] = shade(base, -22) + (255,)
            px[ox + 5, oy + i] = shade(base, -22) + (255,)
        px[ox + 1, oy + 1] = (178, 176, 166, 255)   # rivet
    return img


# ---------------------------------------------------------------------------------------------------------------------
# 128 x 128 item icons, drawn at 4x and reduced for smooth edges.

S = 4


def poly(d, pts, fill, outline=None):
    d.polygon([(x * S, y * S) for x, y in pts], fill=fill, outline=outline)


def slab(d, body, edge, label, size=128):
    """The same bevelled slab as the depleted uranium / plutonium icons: top, front and side faces, a label, a shadow."""
    cx, cy = size // 2, size // 2 + 6
    top = [(cx - 34, cy - 18), (cx + 22, cy - 28), (cx + 40, cy - 12), (cx - 18, cy - 4)]
    front = [(cx - 34, cy - 18), (cx - 18, cy - 4), (cx - 18, cy + 18), (cx - 34, cy + 6)]
    side = [(cx - 18, cy - 4), (cx + 40, cy - 12), (cx + 40, cy + 10), (cx - 18, cy + 18)]
    d.ellipse((( cx - 44) * S, (cy + 24) * S, (cx + 52) * S, (cy + 40) * S), fill=(0, 0, 0, 60))
    poly(d, front, edge)
    poly(d, side, body)
    poly(d, top, shade(body, 28))
    poly(d, [(cx - 8, cy + 2), (cx + 30, cy - 4), (cx + 30, cy + 4), (cx - 8, cy + 10)], label)


def uranium_ingot():
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    slab(d, (84, 83, 78, 255), (50, 50, 47, 255), (186, 124, 44, 255))
    return img.resize((128, 128), Image.LANCZOS)


def raw_uranium():
    """A lump of pitchblende: an irregular faceted rock, black, with a lit upper left and a few ochre crusts."""
    rng = random.Random(21)
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy = 62, 66
    d.ellipse(((cx - 40) * S, (cy + 26) * S, (cx + 44) * S, (cy + 42) * S), fill=(0, 0, 0, 60))
    import math
    # outline: 11 points around, radius wobbling
    pts = []
    for i in range(11):
        a = 2 * math.pi * i / 11 + 0.2
        r = 36 + rng.uniform(-7, 7)
        pts.append((cx + r * math.cos(a), cy + 0.78 * r * math.sin(a)))
    poly(d, pts, PITCH[1] + (255,))
    # facets: triangles from a point left of centre to each outline edge, lit by their normal
    hub = (cx - 6, cy - 6)
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        mx, my = (a[0] + b[0]) / 2 - hub[0], (a[1] + b[1]) / 2 - hub[1]
        light = (-mx - my) / 60.0
        base = PITCH[1]
        col = shade(base, int(max(-12, min(46, light * 50)) + rng.uniform(-4, 4)))
        poly(d, [hub, a, b], col + (255,))
    # crevice lines
    for i in range(0, len(pts), 2):
        d.line([(hub[0] * S, hub[1] * S), (pts[i][0] * S, pts[i][1] * S)], fill=PITCH[0] + (255,), width=2 * S // 2)
    d.polygon([(x * S, y * S) for x, y in pts], outline=(8, 8, 10, 255))
    # glints on the lit facets and a few weathering crusts
    for _ in range(6):
        x, y = cx - 26 + rng.uniform(0, 30), cy - 24 + rng.uniform(0, 28)
        d.ellipse(((x - 2.2) * S, (y - 1.6) * S, (x + 2.2) * S, (y + 1.6) * S), fill=GLINT + (255,))
    for _ in range(7):
        a = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(8, 30)
        x, y = cx + r * math.cos(a), cy + 0.78 * r * math.sin(a)
        d.ellipse(((x - 2.6) * S, (y - 2.0) * S, (x + 2.6) * S, (y + 2.0) * S), fill=rng.choice(OCHRE) + (255,))
    return img.resize((128, 128), Image.LANCZOS)


def main():
    stone = [(116, 116, 116), (124, 124, 124), (131, 131, 131), (110, 110, 112), (137, 136, 136)]
    slate = [(70, 70, 75), (78, 78, 83), (64, 64, 69), (86, 86, 91)]
    block = os.path.join(TEX, "block")
    ore_texture(3, stone, False).save(os.path.join(block, "uranium_ore.png"))
    ore_texture(5, slate, True, strata=[(3, -8), (8, -10), (13, -8)]).save(os.path.join(block, "deepslate_uranium_ore.png"))
    raw_block().save(os.path.join(block, "raw_uranium_block.png"))
    metal_block().save(os.path.join(block, "uranium_block.png"))
    item = os.path.join(TEX, "item/fuel")
    uranium_ingot().save(os.path.join(item, "uranium_ingot.png"))
    raw_uranium().save(os.path.join(item, "raw_uranium.png"))
    print("wrote 4 block textures and 2 item icons")


if __name__ == "__main__":
    main()
