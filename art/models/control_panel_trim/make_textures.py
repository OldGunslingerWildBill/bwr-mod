#!/usr/bin/env python3
"""Pixel textures for the control panel bodies and the trim blocks (standard library only).

    python art/models/control_panel_trim/make_textures.py

Writes mod/src/main/resources/assets/bwr/textures/block/control_panel/*.png. The colours are the existing seafoam
control-board palette (tools-export-panels.py): benchboard paint A7BCAE and bezel 7D9284. The detail is in the
textures, not in extra geometry, because these blocks tile across whole rooms:

  face        the paint of a mounting face, with a light line on the top and left edge of every block and a dark one
              on the bottom and right edge: a run reads as separate modules, not one slab
  paint       the same paint with no seam, for the edges of end caps
  bezel       the unpainted edges, with the same light and dark edge lines
  back        the rear of a panel: a service door outline and a handle
  kick        the dark toe-kick
  cheek       the outer face of an end cap, a flat panel with a stepped frame
  hood        the dark top hood of the annunciator canopy
  apron       the foot plate, an anti-slip diagonal rib pattern
  cabinet     the front of the plinth base, a pair of doors
  annunciator the annunciator panel: a recessed box of 4 x 3 big windows with labels and a caption, 128 x 128 pixels
  annunciator_lit  the bright (glowing) windows laid over some of those, 91 x 17
  vent        a louvre grille panel, 128 x 128

Finishes: the painted textures (face, paint, bezel, back, cheek, cabinet) are written in GREY, not seafoam: every
pixel is the seafoam picture's pixel divided by the seafoam colour of its kind (paint or bezel), times the grey level
NEUTRAL (= PanelFinish.PANEL_NEUTRAL, 230). The models give those materials a tint index (0 paint, 1 bezel) and
PanelFinish colours them, so one texture serves every finish and seafoam renders as before. annunciator and vent keep
their seafoam border and come in one copy per finish (finish_variants()), picked by the blockstate.
"""
import os
import struct
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr", "textures", "block", "control_panel")

PAINT = (0xA7, 0xBC, 0xAE)
PAINT_LIGHT = (0xB6, 0xCA, 0xBD)
PAINT_DARK = (0x93, 0xA8, 0x9A)
BEZEL = (0x7D, 0x92, 0x84)
BEZEL_LIGHT = (0x8D, 0xA2, 0x94)
BEZEL_DARK = (0x6B, 0x7F, 0x72)


def write_png(path, width, height, pixels):
    """pixels[y][x] = (r, g, b), y = 0 at the top."""
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as fh:
        fh.write(png)


def grid(width, height, colour):
    return [[colour for _ in range(width)] for _ in range(height)]


def edges(px, light, dark):
    """Light line on the top and left edge, dark line on the bottom and right edge."""
    h, w = len(px), len(px[0])
    for x in range(w):
        px[0][x] = light
        px[h - 1][x] = dark
    for y in range(h):
        px[y][0] = light
        px[y][w - 1] = dark
    px[0][w - 1] = px[h - 1][0] = (light[0] + dark[0]) // 2, (light[1] + dark[1]) // 2, (light[2] + dark[2]) // 2


def rect(px, x0, y0, x1, y1, colour):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[y][x] = colour


def frame(px, x0, y0, x1, y1, colour):
    for x in range(x0, x1 + 1):
        px[y0][x] = px[y1][x] = colour
    for y in range(y0, y1 + 1):
        px[y][x0] = px[y][x1] = colour


# Which painted textures are written grey, and the seafoam colour that becomes the tint colour (paint -> tint 0, bezel -> 1).
NEUTRAL = 230
GREY_OF = {"face": PAINT, "paint": PAINT, "cheek": PAINT, "cabinet": PAINT, "bezel": BEZEL, "back": BEZEL}

# The paint colours of the finishes other than seafoam (PanelFinish.java): paint, light edge, dark edge ratios as seafoam's.
FINISH_PAINT = {"cream": (0xDD, 0xD6, 0xBB), "tan": (0xC2, 0xAE, 0x8A), "grey": (0xB4, 0xB9, 0xBC), "light_blue": (0x9C, 0xC4, 0xD6)}


def to_grey(px, ref):
    """The same picture in grey: each pixel is NEUTRAL times its mean channel ratio to the colour `ref`."""
    out = []
    for row in px:
        out.append([(min(255, round(NEUTRAL * sum(c / r for c, r in zip(p, ref)) / 3.0)),) * 3 for p in row])
    return out


def face():
    px = grid(16, 16, PAINT)
    edges(px, PAINT_LIGHT, PAINT_DARK)
    return 16, 16, px


def paint():
    return 16, 16, grid(16, 16, PAINT)


def bezel():
    px = grid(16, 16, BEZEL)
    edges(px, BEZEL_LIGHT, BEZEL_DARK)
    return 16, 16, px


def back():
    px = grid(16, 16, BEZEL_DARK)
    edges(px, BEZEL, (0x55, 0x66, 0x5B))
    frame(px, 2, 2, 13, 13, (0x55, 0x66, 0x5B))
    frame(px, 3, 3, 12, 12, BEZEL)
    rect(px, 10, 7, 11, 9, (0x9A, 0xAE, 0xA0))     # handle
    rect(px, 3, 3, 3, 3, (0x9A, 0xAE, 0xA0))       # corner screws
    rect(px, 12, 3, 12, 3, (0x9A, 0xAE, 0xA0))
    rect(px, 3, 12, 3, 12, (0x9A, 0xAE, 0xA0))
    rect(px, 12, 12, 12, 12, (0x9A, 0xAE, 0xA0))
    return 16, 16, px


def kick():
    px = grid(16, 16, (0x2F, 0x3A, 0x34))
    for x in range(16):
        px[0][x] = (0x42, 0x50, 0x48)
        px[15][x] = (0x24, 0x2D, 0x28)
    return 16, 16, px


def cheek():
    px = grid(16, 16, PAINT)
    frame(px, 0, 0, 15, 15, BEZEL)
    frame(px, 1, 1, 14, 14, PAINT_DARK)
    frame(px, 3, 3, 12, 12, PAINT_LIGHT)
    return 16, 16, px


def hood():
    px = grid(16, 16, (0x4A, 0x5A, 0x50))
    for x in range(16):
        px[0][x] = (0x5E, 0x70, 0x65)
        px[15][x] = (0x3A, 0x48, 0x40)
    return 16, 16, px


def apron():
    base, rib = (0x3A, 0x45, 0x3F), (0x50, 0x5D, 0x55)
    px = grid(16, 16, base)
    for y in range(16):
        for x in range(16):
            if (x + y) % 4 in (0, 1):
                px[y][x] = rib
    frame(px, 0, 0, 15, 15, (0x2A, 0x33, 0x2E))
    return 16, 16, px


def cabinet():
    px = grid(16, 16, PAINT)
    edges(px, PAINT_LIGHT, PAINT_DARK)
    for y in range(1, 15):                                   # the seam between the two doors
        px[y][7] = PAINT_DARK
        px[y][8] = PAINT_LIGHT
    frame(px, 2, 2, 13, 13, PAINT_DARK)
    rect(px, 5, 6, 5, 9, BEZEL)                              # handles
    rect(px, 10, 6, 10, 9, BEZEL)
    return 16, 16, px


FONT = {
    "1": (".#.", "##.", ".#.", ".#.", "###"), "2": ("##.", "..#", ".#.", "#..", "###"),
    "3": ("##.", "..#", ".#.", "..#", "##."), "4": ("#.#", "#.#", "###", "..#", "..#"),
    "A": (".#.", "#.#", "###", "#.#", "#.#"), "B": ("##.", "#.#", "##.", "#.#", "##."),
    "C": (".##", "#..", "#..", "#..", ".##")}

# The annunciator picture, 128 x 128 pixels over one block of the leaning panel (y = 0 at the top). Layout, in pixels:
# the box is x 6..121, y 15..98; windows are 22 x 17 at x = 19 + 25 * column, y = 27 + 20 * row (columns 1-4, rows A-C);
# the numbers sit at y 20..24, the letters at x 11..13, the caption at y 87..92 and the nameplate at y 104..114.
# build_trim.py lights windows B1+B2 (19..65, 47..63), C3 (69..90, 67..83) and A4 (94..115, 27..43) with annunciator_lit.
WIN_W, WIN_H = 22, 17
WINDOWS = {  # (row, column): (body, legend)
    (0, 0): "red", (0, 1): "red", (0, 2): "white", (0, 3): "red",
    (1, 0): "white", (1, 1): "white", (1, 2): "red", (1, 3): "red",
    (2, 0): "white", (2, 1): "amber", (2, 2): "amber", (2, 3): "red"}
TILE = {"red": ((0xA8, 0x2A, 0x24), (0xE6, 0xA2, 0x5A)), "white": ((0xC9, 0xC3, 0xAC), (0x4A, 0x46, 0x3A)),
        "amber": ((0xB8, 0x96, 0x2E), (0x3F, 0x32, 0x10))}
LIT = {"white": ((0xFF, 0xF6, 0xD8), (0x6A, 0x5C, 0x30)), "amber": ((0xFF, 0xDC, 0x50), (0x5A, 0x40, 0x10)),
       "red": ((0xFF, 0x4A, 0x32), (0xFF, 0xD8, 0xA0))}


def glyph(px, ch, x, y, colour):
    for dy, row in enumerate(FONT[ch]):
        for dx, c in enumerate(row):
            if c == "#":
                px[y + dy][x + dx] = colour


def window(px, x0, y0, body, legend):
    lit = tuple(min(255, c + 22) for c in body)
    shade = tuple(max(0, c - 40) for c in body)
    rect(px, x0, y0, x0 + WIN_W - 1, y0 + WIN_H - 1, body)
    for x in range(x0, x0 + WIN_W):
        px[y0][x] = lit
        px[y0 + WIN_H - 1][x] = shade
    for y in range(y0, y0 + WIN_H):
        px[y][x0] = lit
        px[y][x0 + WIN_W - 1] = shade
    rect(px, x0 + 4, y0 + 6, x0 + WIN_W - 5, y0 + 6, legend)         # two lines of legend
    rect(px, x0 + 6, y0 + 10, x0 + WIN_W - 7, y0 + 10, legend)


def screw(px, x, y):
    rect(px, x, y, x + 2, y + 2, (0x9A, 0xA4, 0x9E))
    px[y + 1][x + 1] = (0x3A, 0x40, 0x3C)


def annunciator():
    px = grid(128, 128, PAINT)
    edges(px, PAINT_LIGHT, PAINT_DARK)
    for x, y in ((2, 7), (123, 7), (2, 102), (123, 102)):
        screw(px, x, y)
    rect(px, 6, 15, 121, 98, (0x17, 0x19, 0x1A))                       # the box: a dark, nearly black bezel
    frame(px, 6, 15, 121, 98, (0x3A, 0x41, 0x3E))
    frame(px, 7, 16, 120, 97, (0x0C, 0x0D, 0x0E))
    for column, ch in enumerate("1234"):
        glyph(px, ch, 19 + 25 * column + 9, 20, (0xB8, 0xBC, 0xB4))
    for row, ch in enumerate("ABC"):
        glyph(px, ch, 11, 27 + 20 * row + 6, (0xB8, 0xBC, 0xB4))
    for (row, column), tint in WINDOWS.items():
        body, legend = TILE[tint]
        window(px, 19 + 25 * column, 27 + 20 * row, body, legend)
    rect(px, 40, 87, 88, 92, (0x1F, 0x23, 0x22))                       # the caption strip
    for x in range(44, 85, 3):
        px[89][x] = px[89][x + 1] = (0x7C, 0x82, 0x7E)
    rect(px, 34, 104, 94, 114, (0x12, 0x14, 0x15))                     # the nameplate below
    frame(px, 34, 104, 94, 114, (0x4A, 0x52, 0x4E))
    for x in range(40, 89, 4):
        px[107][x] = px[107][x + 1] = px[107][x + 2] = (0xC8, 0xCC, 0xC4)
        px[111][x + 1] = px[111][x + 2] = (0x9C, 0xA2, 0x9E)
    return 128, 128, px


def annunciator_lit():
    """Bright windows: white pair (47 wide, the dark gap between them kept), amber, red; 91 x 17 pixels."""
    px = grid(91, WIN_H, (0x17, 0x19, 0x1A))
    window(px, 0, 0, *LIT["white"])
    window(px, 25, 0, *LIT["white"])
    window(px, 47, 0, *LIT["amber"])
    window(px, 69, 0, *LIT["red"])
    return 91, WIN_H, px


def vent():
    """A vent louvre panel: board paint with a dark recessed grille of vertical slats."""
    px = grid(128, 128, PAINT)
    edges(px, PAINT_LIGHT, PAINT_DARK)
    for x, y in ((2, 4), (123, 4), (2, 120), (123, 120)):
        screw(px, x, y)
    rect(px, 36, 10, 91, 110, (0x14, 0x17, 0x16))
    frame(px, 36, 10, 91, 110, (0x3A, 0x41, 0x3E))
    for x in range(40, 88, 4):
        rect(px, x, 14, x + 1, 106, (0x5E, 0x68, 0x63))
        rect(px, x + 2, 14, x + 2, 106, (0x0A, 0x0C, 0x0B))
    return 128, 128, px


def recolour(px, src, dst):
    """The picture with the seafoam paint family replaced by the same family of another finish: each pixel's channel
    ratio to `src` (seafoam paint) times `dst`. Dark and coloured pixels (the annunciator box) scale with it, so only
    the paint border should hold pixels near `src`: pixels far from the paint family are kept."""
    out = []
    for row in px:
        new = []
        for p in row:
            ratios = [c / r for c, r in zip(p, src)]
            if 0.75 <= min(ratios) and max(ratios) <= 1.25 and max(ratios) - min(ratios) < 0.12:
                new.append(tuple(min(255, round(d * sum(ratios) / 3.0)) for d in dst))
            else:
                new.append(p)
        out.append(new)
    return out


TEXTURES = {"face": face, "paint": paint, "bezel": bezel, "back": back, "kick": kick, "cheek": cheek,
            "hood": hood, "apron": apron, "cabinet": cabinet, "annunciator": annunciator,
            "annunciator_lit": annunciator_lit, "vent": vent}


FINISH_VARIANT_TEXTURES = ("annunciator", "vent")


def main():
    os.makedirs(OUT, exist_ok=True)
    count = 0
    for name, make in TEXTURES.items():
        width, height, px = make()
        if name in GREY_OF:
            px = to_grey(px, GREY_OF[name])
        write_png(os.path.join(OUT, name + ".png"), width, height, px)
        count += 1
    for name in FINISH_VARIANT_TEXTURES:                    # annunciator and vent: one copy per finish
        width, height, px = TEXTURES[name]()
        for finish, paint in FINISH_PAINT.items():
            write_png(os.path.join(OUT, "%s_%s.png" % (name, finish)), width, height, recolour(px, PAINT, paint))
            count += 1
    print("wrote", count, "textures to", OUT)


if __name__ == "__main__":
    main()
