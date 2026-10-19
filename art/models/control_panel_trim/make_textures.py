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
  annunciator the window tiles of the header strip: 4 x 2 tiles, 64 x 32 pixels
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


def annunciator():
    """4 x 2 window tiles of 16 pixels each. Pale windows with two lines of engraved legend; one amber, one red."""
    width, height = 64, 32
    px = grid(width, height, (0x25, 0x2C, 0x28))
    tints = {(1, 0): (0xE8, 0xC4, 0x6A), (2, 1): (0xDE, 0x7A, 0x6E)}
    for row in range(2):
        for col in range(4):
            x0, y0 = col * 16, row * 16
            body = tints.get((col, row), (0xE9, 0xE4, 0xCF))
            lit = tuple(min(255, c + 14) for c in body)
            shade = tuple(max(0, c - 34) for c in body)
            rect(px, x0 + 1, y0 + 1, x0 + 14, y0 + 14, (0x15, 0x1A, 0x17))          # the tile's bezel
            rect(px, x0 + 2, y0 + 2, x0 + 13, y0 + 13, body)
            for x in range(x0 + 2, x0 + 14):
                px[y0 + 2][x] = lit
                px[y0 + 13][x] = shade
            for y in range(y0 + 2, y0 + 14):
                px[y][x0 + 13] = shade
            legend = (0x5C, 0x5A, 0x50)
            rect(px, x0 + 4, y0 + 5, x0 + 11, y0 + 5, legend)
            rect(px, x0 + 4, y0 + 8, x0 + 9, y0 + 8, legend)
            rect(px, x0 + 4, y0 + 10, x0 + 11, y0 + 10, legend)
    return width, height, px


TEXTURES = {"face": face, "paint": paint, "bezel": bezel, "back": back, "kick": kick, "cheek": cheek,
            "hood": hood, "apron": apron, "cabinet": cabinet, "annunciator": annunciator}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, make in TEXTURES.items():
        width, height, px = make()
        write_png(os.path.join(OUT, name + ".png"), width, height, px)
    print("wrote", len(TEXTURES), "textures to", OUT)


if __name__ == "__main__":
    main()
