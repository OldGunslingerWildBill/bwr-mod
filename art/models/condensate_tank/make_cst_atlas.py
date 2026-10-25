"""Procedural texture atlas for the condensate storage tank remodel (Pillow, seeded, no external art).

python art/models/condensate_tank/make_cst_atlas.py
Writes mod/src/main/resources/assets/bwr/textures/block/condensate_tank/cst_atlas.png and
art/models/condensate_tank/atlas_tiles.json (tile name -> pixel rect x, y, w, h). build_cst.py maps every face into one
tile. The helpers (tile packing, noise fills, 3 x 5 plate font) are the recirculation remodel's (art/models/recirc/make_atlas.py).
Generic text only: no maker's name or logo.
"""
from pathlib import Path

HERE = Path(__file__).resolve().parent
_helper = HERE.parent / 'recirc' / 'make_atlas.py'
exec(compile(_helper.read_text(encoding='utf-8').split('# ---- paints and metals')[0], str(_helper), 'exec'))
OUT = ROOT / 'mod/src/main/resources/assets/bwr/textures/block/condensate_tank/cst_atlas.png'
TILES = HERE / 'atlas_tiles.json'

PAINT = (226, 228, 222)       # off-white exterior coating
ROOFC = (200, 203, 199)       # roof, weathered a shade darker
CONC = (168, 165, 156)
GALV = (172, 178, 180)


def text2(x, y, s, rgb, k=2):
    """The plate font at k x k pixels per dot (stencilled tank lettering)."""
    for ch in s:
        g = FONT[ch]
        for i, bit in enumerate(g):
            if bit == '1':
                for dx in range(k):
                    for dy in range(k):
                        px[x + (i % 3) * k + dx, y + (i // 3) * k + dy] = tuple(rgb) + (255,)
        x += 4 * k


# ---- coatings and metals --------------------------------------------------------------------------------------------
fill('shell', 32, 32, PAINT, noise=2, seed=1, mottle=2)
x0, y0 = fill('weld', 16, 16, (204, 206, 199), noise=3, seed=2)
for y in range(0, 16, 3):
    rect(x0, y0 + y, x0 + 16, y0 + y + 1, (186, 188, 181))
fill('roof', 32, 32, ROOFC, noise=3, seed=3, mottle=3)
fill('concrete', 32, 32, CONC, noise=9, seed=4, mottle=6)
fill('concrete_top', 32, 32, (184, 181, 172), noise=8, seed=5, mottle=5)
fill('galv', 16, 16, GALV, noise=6, seed=6, mottle=2)
fill('yellow', 16, 16, (222, 182, 34), noise=4, seed=7)
fill('steel_dark', 16, 16, (62, 66, 68), noise=4, seed=8)
fill('flange', 16, 16, (150, 156, 160), noise=4, seed=9, streak=3)
fill('gasket', 16, 16, (28, 28, 30), noise=3, seed=10)
fill('blue', 16, 16, (36, 92, 168), noise=3, seed=11)
fill('coating_inner', 16, 16, (120, 98, 70), noise=4, seed=12)   # phenolic lining seen in an open bore
fill('anchor', 16, 16, (98, 104, 108), noise=5, seed=13)
fill('red', 16, 16, (196, 40, 30), noise=3, seed=14)
# open-grid roof platform grating (opaque: dark gaps, galvanised bars)
x0, y0 = fill('grating', 32, 32, (40, 42, 44), noise=3, seed=15)
for i in range(0, 32, 4):
    rect(x0 + i, y0, x0 + i + 1, y0 + 32, GALV)
for j in range(0, 32, 8):
    rect(x0, y0 + j, x0 + 32, y0 + j + 1, (150, 156, 158))
# stencilled name band on the shell
x0, y0 = fill('stencil', 192, 16, PAINT, noise=3, seed=16)
text2(x0 + 4, y0 + 3, 'CONDENSATE STORAGE TANK', (32, 34, 36))
# radiological posting at the ladder (generic trefoil, black/magenta on yellow)
x0, y0 = fill('rad_sign', 32, 32, (238, 202, 40), noise=2, seed=17)
rect(x0, y0, x0 + 32, y0 + 1, (20, 20, 20)); rect(x0, y0 + 31, x0 + 32, y0 + 32, (20, 20, 20))
rect(x0, y0, x0 + 1, y0 + 32, (20, 20, 20)); rect(x0 + 31, y0, x0 + 32, y0 + 32, (20, 20, 20))
text(x0 + 2, y0 + 3, 'CAUTION', (20, 20, 20))
import math as _m
for y in range(10, 30):
    for x in range(6, 26):
        dx, dy = x - 15.5, y - 19.5
        r = _m.hypot(dx, dy)
        a = (_m.degrees(_m.atan2(dy, dx)) + 90) % 120
        if r < 2.2 or (3.4 < r < 9 and a < 60):
            px[x0 + x, y0 + y] = (150, 30, 110, 255)
# float-and-tape level gauge board: one tile per block of height (minor ticks every 1/8, a major tick at the top)
x0, y0 = fill('gauge_scale', 16, 64, (236, 236, 230), noise=2, seed=18)
rect(x0, y0, x0 + 1, y0 + 64, (40, 40, 40)); rect(x0 + 15, y0, x0 + 16, y0 + 64, (40, 40, 40))
for k in range(8):
    y = y0 + k * 8
    rect(x0 + 1, y, x0 + (12 if k == 0 else 6), y + 1, (20, 20, 20))
rect(x0 + 13, y0, x0 + 15, y0 + 64, (196, 40, 30))   # the target slot
x0, y0 = fill('gauge_head', 32, 32, (226, 226, 220), noise=2, seed=19)
rect(x0, y0, x0 + 32, y0 + 1, (40, 40, 40)); rect(x0, y0 + 31, x0 + 32, y0 + 32, (40, 40, 40))
text(x0 + 6, y0 + 4, 'LEVEL', (20, 20, 20))
text(x0 + 2, y0 + 12, 'FLOAT', (60, 60, 60))
text(x0 + 2, y0 + 19, 'GAUGE', (60, 60, 60))
rect(x0 + 2, y0 + 26, x0 + 30, y0 + 28, (196, 40, 30))
# the heater terminal box and the tank plate
x0, y0 = fill('heater_box', 32, 32, (150, 156, 150), noise=3, seed=20)
rect(x0 + 4, y0 + 4, x0 + 28, y0 + 13, (230, 230, 222))
text(x0 + 5, y0 + 6, 'HEATER', (20, 20, 20))
rect(x0 + 6, y0 + 18, x0 + 26, y0 + 20, (60, 60, 60))
x0, y0 = fill('plate', 64, 32, (196, 198, 188), noise=3, seed=21)
rect(x0, y0, x0 + 64, y0 + 1, (120, 120, 116)); rect(x0, y0 + 31, x0 + 64, y0 + 32, (100, 100, 96))
rect(x0 + 2, y0 + 3, x0 + 62, y0 + 10, (34, 54, 92))
text(x0 + 5, y0 + 4, 'COND STORAGE', (230, 230, 220))
text(x0 + 4, y0 + 13, '400000 GAL', (30, 30, 30))
text(x0 + 4, y0 + 19, 'ATM PLUS HEAD', (60, 60, 60))
text(x0 + 4, y0 + 25, 'DESIGN 140 F', (60, 60, 60))

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT, optimize=True)
TILES.write_text(json.dumps({'size': S, 'tiles': tiles}, indent=1) + '\n')
print('atlas', OUT, len(tiles), 'tiles, rows used to y =', cursor[1] + cursor[2])
