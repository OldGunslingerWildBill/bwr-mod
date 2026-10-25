#!/usr/bin/env python3
"""Generates the thorium textures: five 16 x 16 block textures and seven 128 x 128 item icons (the rare earth concentrate, the lead ingot and nugget, the radium capsule among them).

Run from the repository root:  python tools-make-thorium-assets.py
Standard library plus Pillow. Reuses the helpers of tools-make-uranium-assets.py, so the two ores look related but not alike.

What the real minerals look like: monazite is a glassy to resinous reddish-brown, yellow-brown or honey-coloured phosphate,
found as small rounded grains (in a placer it is the red-brown specks in dark heavy-mineral sand); thorite is orange-brown
to black. So the ore is stone with rust-orange and honey grains (never green, never glowing), the sand is pale quartz sand
with a heavy peppering of red-brown grains, and thorium metal is a silvery grey that tarnishes dark.
"""
import importlib.util
import math
import os
import random
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("uranium_assets", os.path.join(ROOT, "tools-make-uranium-assets.py"))
U = importlib.util.module_from_spec(spec)
spec.loader.exec_module(U)

TEX = U.TEX
S = U.S
RUST = [(122, 62, 38), (150, 78, 44), (178, 98, 52), (96, 50, 34)]      # monazite: red-brown, darkest to lightest
HONEY = [(196, 146, 64), (214, 168, 84)]                                   # the glassy yellow-brown grains
GLINT = (232, 205, 150)

# thorium grains are smaller and rounder than the uranium pitch blobs
MASKS = [["##", "##"], [".#.", "###", ".#."], ["###", "###"], ["#", "#"]]


def grain(img, rng, ox, oy, mask):
    px = img.load()
    h, w = len(mask), len(mask[0])
    for dy in range(h):
        for dx in range(w):
            if mask[dy][dx] != "#":
                continue
            x, y = ox + dx, oy + dy
            if 0 <= x < 16 and 0 <= y < 16:
                lower_right = dx == w - 1 or dy == h - 1
                c = RUST[2] if lower_right else (RUST[3] if rng.random() < 0.3 else rng.choice(RUST[:2]))
                if rng.random() < 0.18:
                    c = rng.choice(HONEY)
                px[x, y] = c + (255,)
    for dy in range(h):
        for dx in range(w):
            if mask[dy][dx] == "#" and rng.random() < 0.4:
                x, y = ox + dx, oy + dy
                if 0 <= x < 16 and 0 <= y < 16:
                    px[x, y] = GLINT + (255,)
                    return


def ore_texture(seed, palette, deep, strata=None):
    rng = random.Random(seed)
    img = U.stone_base(rng, palette, strata)
    spots = [(2, 2, 0), (9, 1, 1), (5, 7, 2), (12, 8, 3), (1, 11, 1), (8, 12, 0), (13, 13, 3)] if not deep else \
            [(3, 1, 0), (10, 3, 1), (6, 8, 2), (1, 9, 3), (12, 11, 1), (8, 13, 0)]
    for (x, y, m) in spots:
        grain(img, rng, x, y, MASKS[m])
    return img


def raw_block(seed=9):
    """Lumps of red-brown monazite concentrate with dark crevices."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), (54, 34, 28, 255))
    px = img.load()
    centres = [(3, 3), (10, 2), (7, 7), (13, 8), (2, 10), (9, 13), (14, 14), (4, 14)]
    for y in range(16):
        for x in range(16):
            d = sorted(((x - cx) ** 2 + (y - cy) ** 2, i) for i, (cx, cy) in enumerate(centres))
            (d0, i0), (d1, _) = d[0], d[1]
            if d1 - d0 < 4.5:
                px[x, y] = (54, 34, 28, 255)
                continue
            cx, cy = centres[i0]
            light = (cx - x) * 0.5 + (cy - y) * 0.5
            base = (118, 66, 42) if light < 1.2 else (146, 84, 50)
            px[x, y] = U.shade(base, rng.randrange(-6, 7)) + (255,)
            if light < -1.2 and rng.random() < 0.5:
                px[x, y] = U.shade((190, 130, 70), rng.randrange(-6, 8)) + (255,)
    for _ in range(4):
        x, y = rng.randrange(16), rng.randrange(16)
        px[x, y] = rng.choice(HONEY) + (255,)
    return img


def metal_block(seed=12):
    """Silvery thorium metal in four bevelled plates, a little lighter and cooler than the uranium block."""
    rng = random.Random(seed)
    base = (150, 152, 156)
    img = Image.new("RGBA", (16, 16), base + (255,))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = U.shade(base, rng.randrange(-5, 6)) + (255,)
    hi, lo, groove = (206, 208, 214, 255), (88, 90, 96, 255), (66, 68, 74, 255)
    for i in range(16):
        px[i, 0] = hi
        px[0, i] = hi
        px[i, 15] = lo
        px[15, i] = lo
    for i in range(1, 15):
        px[i, 7] = groove
        px[7, i] = groove
        px[i, 8] = U.shade(base, 14) + (255,)
        px[8, i] = U.shade(base, 14) + (255,)
    for ox, oy in ((1, 1), (9, 1), (1, 9), (9, 9)):
        for i in range(0, 6):
            px[ox + i, oy] = U.shade(base, 22) + (255,)
            px[ox, oy + i] = U.shade(base, 22) + (255,)
            px[ox + i, oy + 5] = U.shade(base, -22) + (255,)
            px[ox + 5, oy + i] = U.shade(base, -22) + (255,)
        px[ox + 1, oy + 1] = (232, 232, 236, 255)
    return img


def monazite_sand(seed=15):
    """Pale quartz sand peppered with dark red-brown monazite and honey grains: a heavy-mineral placer."""
    rng = random.Random(seed)
    sand = [(214, 196, 150), (220, 202, 158), (206, 188, 142), (224, 208, 166), (200, 180, 136)]
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = rng.choice(sand)
            if x and rng.random() < 0.25:
                c = px[x - 1, y][:3]
            px[x, y] = c + (255,)
    for _ in range(62):
        x, y = rng.randrange(16), rng.randrange(16)
        r = rng.random()
        px[x, y] = (rng.choice(RUST) if r < 0.7 else rng.choice(HONEY) if r < 0.85 else (60, 44, 40)) + (255,)
    return img


def poly(d, pts, fill, outline=None):
    d.polygon([(x * S, y * S) for x, y in pts], fill=fill, outline=outline)


def thorium_ingot():
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    U.slab(d, (158, 160, 166, 255), (96, 98, 104, 255), (176, 92, 44, 255))
    return img.resize((128, 128), Image.LANCZOS)


def thorium_nugget():
    """A small rounded lump of the same silvery metal."""
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy = 64, 70
    d.ellipse(((cx - 26) * S, (cy + 14) * S, (cx + 30) * S, (cy + 28) * S), fill=(0, 0, 0, 60))
    pts = [(cx + 24 * math.cos(2 * math.pi * i / 9 + 0.3), cy + 19 * math.sin(2 * math.pi * i / 9 + 0.3)) for i in range(9)]
    poly(d, pts, (120, 122, 128, 255))
    hub = (cx - 6, cy - 6)
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        mx, my = (a[0] + b[0]) / 2 - hub[0], (a[1] + b[1]) / 2 - hub[1]
        light = (-mx - my) / 40.0
        poly(d, [hub, a, b], U.shade((150, 152, 158), int(max(-30, min(60, light * 60)))) + (255,))
    d.polygon([(x * S, y * S) for x, y in pts], outline=(60, 62, 68, 255))
    d.ellipse(((cx - 10) * S, (cy - 12) * S, (cx - 3) * S, (cy - 8) * S), fill=(236, 238, 242, 255))
    return img.resize((128, 128), Image.LANCZOS)


def raw_thorium():
    """A lump of monazite concentrate: a faceted red-brown rock with honey glints."""
    rng = random.Random(23)
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy = 62, 66
    d.ellipse(((cx - 40) * S, (cy + 26) * S, (cx + 44) * S, (cy + 42) * S), fill=(0, 0, 0, 60))
    pts = []
    for i in range(11):
        a = 2 * math.pi * i / 11 + 0.2
        r = 36 + rng.uniform(-7, 7)
        pts.append((cx + r * math.cos(a), cy + 0.78 * r * math.sin(a)))
    poly(d, pts, RUST[0] + (255,))
    hub = (cx - 6, cy - 6)
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        mx, my = (a[0] + b[0]) / 2 - hub[0], (a[1] + b[1]) / 2 - hub[1]
        light = (-mx - my) / 60.0
        col = U.shade((138, 74, 44), int(max(-26, min(60, light * 60)) + rng.uniform(-4, 4)))
        poly(d, [hub, a, b], col + (255,))
    for i in range(0, len(pts), 2):
        d.line([(hub[0] * S, hub[1] * S), (pts[i][0] * S, pts[i][1] * S)], fill=(60, 34, 26, 255), width=S)
    d.polygon([(x * S, y * S) for x, y in pts], outline=(40, 22, 18, 255))
    for _ in range(6):
        x, y = cx - 26 + rng.uniform(0, 30), cy - 24 + rng.uniform(0, 28)
        d.ellipse(((x - 2.2) * S, (y - 1.6) * S, (x + 2.2) * S, (y + 1.6) * S), fill=GLINT + (255,))
    for _ in range(7):
        a = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(8, 30)
        x, y = cx + r * math.cos(a), cy + 0.78 * r * math.sin(a)
        d.ellipse(((x - 2.6) * S, (y - 2.0) * S, (x + 2.6) * S, (y + 2.0) * S), fill=rng.choice(HONEY) + (255,))
    return img.resize((128, 128), Image.LANCZOS)


def rare_earth_concentrate():
    """A little heap of pale pinkish-grey rare-earth phosphate powder with a few darker grains, on a soft shadow."""
    rng = random.Random(41)
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy = 64, 74
    d.ellipse(((cx - 44) * S, (cy + 14) * S, (cx + 48) * S, (cy + 34) * S), fill=(0, 0, 0, 60))
    # a mound: stacked ellipses from a wide dark base to a light top, then grains scattered over it
    tones = [(150, 120, 118), (170, 140, 136), (188, 160, 152), (204, 178, 168), (218, 196, 186)]
    for i, t in enumerate(tones):
        rx, ry = 40 - i * 7, 22 - i * 4
        oy = -i * 6
        d.ellipse(((cx - rx) * S, (cy + oy - ry) * S, (cx + rx) * S, (cy + oy + ry) * S), fill=t + (255,))
    for _ in range(70):
        a = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(0, 1) ** 0.6
        x = cx + 34 * r * math.cos(a)
        y = cy - 10 + 16 * r * math.sin(a) - 10 * (1 - r)
        c = rng.choice([(110, 84, 82), (236, 218, 204), (176, 126, 96), (96, 72, 70)])
        d.ellipse(((x - 1.6) * S, (y - 1.2) * S, (x + 1.6) * S, (y + 1.2) * S), fill=c + (255,))
    return img.resize((128, 128), Image.LANCZOS)


def lead_ingot():
    """A soft blue-grey bar, duller and darker than the thorium and uranium ones."""
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    U.slab(d, (104, 112, 128, 255), (62, 68, 82, 255), (148, 158, 178, 255))
    return img.resize((128, 128), Image.LANCZOS)


def lead_nugget():
    """A small rounded lump of the same blue-grey metal."""
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy = 64, 70
    d.ellipse(((cx - 26) * S, (cy + 14) * S, (cx + 30) * S, (cy + 28) * S), fill=(0, 0, 0, 60))
    pts = [(cx + 24 * math.cos(2 * math.pi * i / 9 + 0.5), cy + 19 * math.sin(2 * math.pi * i / 9 + 0.5)) for i in range(9)]
    poly(d, pts, (84, 92, 108, 255))
    hub = (cx - 6, cy - 6)
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        mx, my = (a[0] + b[0]) / 2 - hub[0], (a[1] + b[1]) / 2 - hub[1]
        light = (-mx - my) / 40.0
        poly(d, [hub, a, b], U.shade((112, 120, 138), int(max(-26, min(50, light * 50)))) + (255,))
    d.polygon([(x * S, y * S) for x, y in pts], outline=(44, 50, 62, 255))
    d.ellipse(((cx - 10) * S, (cy - 12) * S, (cx - 3) * S, (cy - 8) * S), fill=(196, 204, 220, 255))
    return img.resize((128, 128), Image.LANCZOS)


def radium():
    """A small sealed glass capsule of pale blue-white salt that glows, in a dark metal cap, with the radiation trefoil's yellow on the cap."""
    img = Image.new("RGBA", (128 * S, 128 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img, "RGBA")
    cx = 64
    d.ellipse(((cx - 34) * S, 98 * S, (cx + 38) * S, 112 * S), fill=(0, 0, 0, 60))
    # the glow: a few soft halos behind the capsule
    for r, a in ((46, 26), (38, 40), (30, 58)):
        d.ellipse(((cx - r) * S, (66 - r) * S, (cx + r) * S, (66 + r) * S), fill=(120, 230, 255, a))
    # the glass body
    d.rounded_rectangle(((cx - 17) * S, 34 * S, (cx + 17) * S, 100 * S), radius=14 * S, fill=(176, 214, 226, 235), outline=(70, 96, 112, 255), width=3)
    # the salt inside, glowing
    d.rounded_rectangle(((cx - 12) * S, 56 * S, (cx + 12) * S, 96 * S), radius=10 * S, fill=(214, 250, 255, 255))
    d.rounded_rectangle(((cx - 12) * S, 56 * S, (cx - 3) * S, 94 * S), radius=8 * S, fill=(246, 255, 255, 255))
    # the cap, with a yellow band
    d.rounded_rectangle(((cx - 19) * S, 24 * S, (cx + 19) * S, 40 * S), radius=5 * S, fill=(58, 62, 72, 255), outline=(28, 30, 36, 255), width=3)
    d.rectangle(((cx - 17) * S, 30 * S, (cx + 17) * S, 34 * S), fill=(236, 196, 40, 255))
    # glass highlight
    d.line([((cx - 13) * S, 46 * S), ((cx - 13) * S, 90 * S)], fill=(255, 255, 255, 190), width=2 * S)
    return img.resize((128, 128), Image.LANCZOS)


def main():
    stone = [(116, 116, 116), (124, 124, 124), (131, 131, 131), (110, 110, 112), (137, 136, 136)]
    slate = [(70, 70, 75), (78, 78, 83), (64, 64, 69), (86, 86, 91)]
    block = os.path.join(TEX, "block")
    ore_texture(31, stone, False).save(os.path.join(block, "thorium_ore.png"))
    ore_texture(37, slate, True, strata=[(3, -8), (8, -10), (13, -8)]).save(os.path.join(block, "deepslate_thorium_ore.png"))
    raw_block().save(os.path.join(block, "raw_thorium_block.png"))
    metal_block().save(os.path.join(block, "thorium_block.png"))
    monazite_sand().save(os.path.join(block, "monazite_sand.png"))
    item = os.path.join(TEX, "item/fuel")
    thorium_ingot().save(os.path.join(item, "thorium_ingot.png"))
    thorium_nugget().save(os.path.join(item, "thorium_nugget.png"))
    raw_thorium().save(os.path.join(item, "raw_thorium.png"))
    rare_earth_concentrate().save(os.path.join(item, "rare_earth_concentrate.png"))
    lead_ingot().save(os.path.join(item, "lead_ingot.png"))
    lead_nugget().save(os.path.join(item, "lead_nugget.png"))
    radium().save(os.path.join(item, "radium.png"))
    print("wrote 5 block textures and 7 item icons")


if __name__ == "__main__":
    main()
