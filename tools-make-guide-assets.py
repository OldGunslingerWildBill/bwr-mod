#!/usr/bin/env python3
"""Draws the textures of the operator's guide book (original pixel art, no outside source): the 16x16 item, and the
open-book screen sheet (the parchment spread, the page-turn arrows and the contents ribbon).

Run from anywhere with Pillow installed:  python tools-make-guide-assets.py
Writes mod/src/main/resources/assets/bwr/textures/item/guide_book.png and textures/gui/guide_book.png.

Layout of the screen sheet (512 x 256), which GuideScreen blits from; keep the two in step:
  (0, 0)    316 x 232  the open book: cover, page edges, two parchment pages and the spine crease
  (320, 0)  23 x 13    page forward arrow          (344, 0)  page backward arrow
  (320, 16) 23 x 13    ... highlighted             (344, 16) ... highlighted
  (368, 0)  13 x 30    contents ribbon             (384, 0)  ... highlighted
"""
import os
import random

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(ROOT, "mod/src/main/resources/assets/bwr/textures")


def guide_book():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([2, 1, 13, 14], fill=(32, 78, 132, 255), outline=(14, 36, 66, 255))      # the cover
    d.rectangle([2, 1, 3, 14], fill=(22, 56, 98, 255))                                   # the spine
    d.rectangle([13, 3, 13, 12], fill=(236, 230, 210, 255))                              # page edges
    d.rectangle([5, 3, 11, 5], fill=(226, 232, 238, 255))                                # a title plate
    yellow = (250, 205, 30, 255)
    d.pieslice([5, 6, 11, 12], 60, 120, fill=yellow)                                     # a trefoil: three sectors
    d.pieslice([5, 6, 11, 12], 180, 240, fill=yellow)
    d.pieslice([5, 6, 11, 12], 300, 360, fill=yellow)
    d.rectangle([7, 8, 9, 10], fill=(32, 78, 132, 255))
    img.putpixel((8, 9), yellow)
    return img


BW, BH = 316, 232          # the open book, in texture pixels
PAGE_TOP, PAGE_BOTTOM = 4, 222
LEFT_PAGE = (8, 155)       # x range of each parchment page
RIGHT_PAGE = (161, 308)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def parchment(img, x0, x1, seed, spine_on_right):
    """One page: warm paper with fibres and faint stains, shaded towards the spine and a little at the outer edge."""
    rnd = random.Random(seed)
    px = img.load()
    base = (228, 214, 178)
    for y in range(PAGE_TOP, PAGE_BOTTOM):
        for x in range(x0, x1):
            n = rnd.randint(-4, 4)
            c = (base[0] + n, base[1] + n, base[2] + n)
            d = (x1 - 1 - x) if spine_on_right else (x - x0)      # distance to the spine
            if d < 22:
                c = mix(c, (150, 128, 90), (1 - d / 22.0) ** 2 * 0.85)
            o = (x - x0) if spine_on_right else (x1 - 1 - x)      # distance to the outer edge
            if o < 3:
                c = mix(c, (190, 168, 128), (3 - o) / 3.0 * 0.5)
            e = min(y - PAGE_TOP, PAGE_BOTTOM - 1 - y)
            if e < 3:
                c = mix(c, (200, 180, 140), (3 - e) / 3.0 * 0.4)
            px[x, y] = c + (255,)
    # fibres: short strokes a shade darker or lighter
    for _ in range(420):
        x = rnd.randint(x0 + 2, x1 - 3)
        y = rnd.randint(PAGE_TOP + 2, PAGE_BOTTOM - 3)
        shade = rnd.choice((-9, -7, 6, 8))
        horizontal = rnd.random() < 0.7
        for k in range(rnd.randint(2, 5)):
            xx, yy = (x + k, y) if horizontal else (x, y + k)
            if x0 <= xx < x1 and PAGE_TOP <= yy < PAGE_BOTTOM:
                r, g, b, _a = px[xx, yy]
                px[xx, yy] = (max(0, min(255, r + shade)), max(0, min(255, g + shade)), max(0, min(255, b + shade - 1)), 255)
    # a few faint age stains
    for _ in range(5):
        cx = rnd.randint(x0 + 14, x1 - 14)
        cy = rnd.randint(PAGE_TOP + 14, PAGE_BOTTOM - 14)
        rad = rnd.randint(5, 9)
        for y in range(cy - rad, cy + rad + 1):
            for x in range(cx - rad, cx + rad + 1):
                dist = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if dist < rad and rnd.random() < 0.35 * (1 - dist / rad):
                    r, g, b, _a = px[x, y]
                    px[x, y] = (r - 7, g - 9, b - 13, 255)


def stack(d, x0, y0, x1, y1, horizontal):
    """The edge of the page block: fine alternating lines."""
    for i in range((y1 - y0) if horizontal else (x1 - x0)):
        c = (238, 228, 198, 255) if i % 2 == 0 else (206, 190, 152, 255)
        if horizontal:
            d.line([(x0, y0 + i), (x1 - 1, y0 + i)], fill=c)
        else:
            d.line([(x0 + i, y0), (x0 + i, y1 - 1)], fill=c)


def spread():
    img = Image.new("RGBA", (BW, BH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cover, cover_dark, cover_light = (28, 62, 108, 255), (12, 28, 52, 255), (52, 92, 146, 255)
    amber = (196, 150, 40, 255)
    # the cover: a board slightly larger than the pages, with cut corners and a shadow edge
    d.rectangle([0, 0, BW - 1, BH - 1], fill=cover, outline=cover_dark)
    d.line([(1, 1), (BW - 2, 1)], fill=cover_light)
    d.line([(1, 1), (1, BH - 2)], fill=cover_light)
    d.line([(2, BH - 2), (BW - 2, BH - 2)], fill=(18, 40, 74, 255))
    for corner in ((0, 0), (BW - 1, 0), (0, BH - 1), (BW - 1, BH - 1)):
        img.putpixel(corner, (0, 0, 0, 0))
    # a stitched amber line near the cover edge, on the strips that show
    for x in range(6, BW - 6, 4):
        d.point((x, 2), fill=amber)
        d.point((x, BH - 4), fill=amber)
    for y in range(6, BH - 6, 4):
        d.point((2, y), fill=amber)
        d.point((BW - 3, y), fill=amber)
    # the page blocks: edges on both sides and along the bottom
    stack(d, 4, PAGE_TOP + 1, 8, PAGE_BOTTOM + 6, False)
    stack(d, 308, PAGE_TOP + 1, 312, PAGE_BOTTOM + 6, False)
    stack(d, 8, PAGE_BOTTOM, 155, PAGE_BOTTOM + 6, True)
    stack(d, 161, PAGE_BOTTOM, 308, PAGE_BOTTOM + 6, True)
    parchment(img, LEFT_PAGE[0], LEFT_PAGE[1], 11, True)
    parchment(img, RIGHT_PAGE[0], RIGHT_PAGE[1], 23, False)
    # the spine: a dark crease with a bound-in thread
    d.rectangle([155, PAGE_TOP, 160, PAGE_BOTTOM + 5], fill=(92, 74, 48, 255))
    d.line([(157, PAGE_TOP), (157, PAGE_BOTTOM + 5)], fill=(66, 52, 32, 255))
    d.line([(158, PAGE_TOP), (158, PAGE_BOTTOM + 5)], fill=(66, 52, 32, 255))
    d.line([(155, PAGE_TOP), (155, PAGE_BOTTOM - 1)], fill=(124, 102, 70, 255))
    d.line([(160, PAGE_TOP), (160, PAGE_BOTTOM - 1)], fill=(124, 102, 70, 255))
    d.line([(8, PAGE_TOP - 1), (308, PAGE_TOP - 1)], fill=cover_dark)
    return img


def arrow(highlight, forward):
    """A 23 x 13 page-turn arrow, ink on a light rim so it reads on any corner of the page."""
    ink = (96, 28, 22, 255) if highlight else (58, 40, 22, 255)
    rim = (240, 230, 200, 255)
    img = Image.new("RGBA", (23, 13), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([2, 4, 13, 8], fill=ink)                                   # the shaft
    for i in range(7):                                                      # the head
        d.line([(12 + i, i), (12 + i, 12 - i)], fill=ink)
    px = img.load()
    solid = {(x, y) for x in range(23) for y in range(13) if px[x, y][3]}
    for (x, y) in solid:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < 23 and 0 <= n[1] < 13 and n not in solid:
                px[n] = rim
    return img if forward else img.transpose(Image.FLIP_LEFT_RIGHT)


def ribbon(highlight):
    """The contents ribbon: a swallow-tailed strip of red cloth that hangs from the top of the page."""
    red = (186, 48, 42, 255) if highlight else (140, 34, 34, 255)
    dark = (84, 18, 22, 255)
    light = (226, 96, 80, 255) if highlight else (180, 64, 58, 255)
    img = Image.new("RGBA", (13, 30), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([1, 0, 11, 29], fill=red)
    d.line([(1, 0), (1, 29)], fill=light)
    d.line([(11, 0), (11, 29)], fill=dark)
    d.line([(2, 0), (10, 0)], fill=dark)
    px = img.load()
    for i in range(6):                                                      # cut a V out of the bottom edge
        half = (i + 1) // 2
        for x in range(6 - half, 7 + half):
            px[x, 24 + i] = (0, 0, 0, 0)
    return img


def sheet():
    img = Image.new("RGBA", (512, 256), (0, 0, 0, 0))
    img.paste(spread(), (0, 0))
    img.paste(arrow(False, True), (320, 0))
    img.paste(arrow(False, False), (344, 0))
    img.paste(arrow(True, True), (320, 16))
    img.paste(arrow(True, False), (344, 16))
    img.paste(ribbon(False), (368, 0))
    img.paste(ribbon(True), (384, 0))
    return img


if __name__ == "__main__":
    for name, make in (("item/guide_book.png", guide_book), ("gui/guide_book.png", sheet)):
        path = os.path.join(TEX, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        make().save(path)
        print("wrote", name)
