#!/usr/bin/env python3
"""Draws the two 16 x 16 item textures of the RWCU stainless steel nanofilter element (item ids demineralizer_resin and
spent_resin, the resin's ids kept so recipes and worlds load): a sintered stainless-fibre cartridge, bright when new and
fouled brown with a radiation mark when spent. Run from anywhere; writes into mod/src/main/resources/assets/bwr/textures/item."""
from pathlib import Path
from PIL import Image

OUT = Path(__file__).resolve().parent / "mod/src/main/resources/assets/bwr/textures/item"


def cartridge(body, light, dark, cap, cap_dark, fibre):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    # a vertical cartridge: x 4..11, y 1..14, end caps at the top and bottom
    for y in range(1, 15):
        for x in range(4, 12):
            shade = light if x in (5, 6) else dark if x in (10, 11) else body
            if (y + (x // 2)) % 4 == 0 and 2 < y < 13:
                shade = fibre  # the sintered fibre mat
            px[x, y] = shade + (255,)
    for y in (1, 2, 13, 14):
        for x in range(3 if y in (2, 13) else 4, 13 if y in (2, 13) else 12):
            px[x, y] = (cap if x < 9 else cap_dark) + (255,)
    for x in range(5, 11):  # the cap handle / end plate
        px[x, 0] = cap_dark + (255,)
        px[x, 15] = cap_dark + (255,)
    for y in range(1, 15):  # outline
        for x in (3, 12):
            if px[x, y][3] == 0:
                px[x, y] = (40, 46, 50, 255)
    return im


new = cartridge((176, 186, 192), (222, 230, 234), (118, 128, 136), (96, 108, 116), (58, 68, 76), (150, 160, 168))
new.save(OUT / "demineralizer_resin.png")

spent = cartridge((92, 80, 66), (128, 112, 90), (58, 50, 42), (70, 70, 68), (44, 44, 44), (74, 64, 52))
px = spent.load()
for (x, y) in ((7, 6), (8, 6), (6, 7), (9, 7), (7, 8), (8, 8), (6, 9), (9, 9), (7, 10), (8, 10)):
    px[x, y] = (236, 196, 36, 255)  # the radiation mark: a yellow trefoil, roughly
px[7, 7] = px[8, 7] = (30, 30, 30, 255)
spent.save(OUT / "spent_resin.png")
print("wrote the nanofilter element textures")
