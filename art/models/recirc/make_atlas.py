"""Procedural texture atlas for the recirculation pump, jet pump and RIP remodel (Pillow, seeded, no external art).

python art/models/recirc/make_atlas.py
Writes mod/src/main/resources/assets/bwr/textures/block/pumps/recirc_atlas.png and art/models/recirc/atlas_tiles.json
(tile name -> pixel rect x, y, w, h). build_recirc.py maps every face into one tile. Nameplates carry generic text only;
no maker's name or logo.
"""
import json, random
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'mod/src/main/resources/assets/bwr/textures/block/pumps/recirc_atlas.png'
TILES = Path(__file__).resolve().parent / 'atlas_tiles.json'
S = 256
img = Image.new('RGBA', (S, S), (0, 0, 0, 255))
px = img.load()
tiles = {}
cursor = [0, 0, 0]  # x, y, row height


def place(name, w, h):
    if cursor[0] + w > S:
        cursor[0], cursor[1], cursor[2] = 0, cursor[1] + cursor[2], 0
    x, y = cursor[0], cursor[1]
    assert y + h <= S, 'atlas full'
    tiles[name] = [x, y, w, h]
    cursor[0] += w
    cursor[2] = max(cursor[2], h)
    return x, y


def clamp(v):
    return max(0, min(255, int(round(v))))


def fill(name, w, h, rgb, noise=6, seed=1, streak=0.0, vertical=True, mottle=0):
    x0, y0 = place(name, w, h)
    rnd = random.Random(seed)
    cols = [rnd.uniform(-1, 1) for _ in range(max(w, h))]
    blobs = [(rnd.uniform(0, w), rnd.uniform(0, h), rnd.uniform(2, 6), rnd.uniform(-1, 1)) for _ in range(mottle)]
    for y in range(h):
        for x in range(w):
            d = rnd.uniform(-noise, noise)
            if streak:
                d += cols[x if vertical else y] * streak
            for bx, by, br, bs in blobs:
                if (x - bx) ** 2 + (y - by) ** 2 < br * br:
                    d += bs * 7
            px[x0 + x, y0 + y] = tuple(clamp(c + d) for c in rgb) + (255,)
    return x0, y0


def rect(x0, y0, x1, y1, rgb, a=255):
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = tuple(rgb) + (a,)


def edge_shade(name, top=14, bottom=-18, left=0, right=0):
    x0, y0, w, h = tiles[name]
    for x in range(w):
        for y, k in ((0, top), (h - 1, bottom)):
            r, g, b, a = px[x0 + x, y0 + y]
            px[x0 + x, y0 + y] = (clamp(r + k), clamp(g + k), clamp(b + k), a)
    for y in range(h):
        for x, k in ((0, left), (w - 1, right)):
            r, g, b, a = px[x0 + x, y0 + y]
            px[x0 + x, y0 + y] = (clamp(r + k), clamp(g + k), clamp(b + k), a)


# 3 x 5 pixel font, generic plate text only.
FONT = {
    'A': '010101111101101', 'B': '110101110101110', 'C': '011100100100011', 'D': '110101101101110', 'E': '111100110100111',
    'F': '111100110100100', 'G': '011100101101011', 'H': '101101111101101', 'I': '111010010010111', 'K': '101101110101101',
    'L': '100100100100111', 'M': '101111111101101', 'N': '110101101101101', 'O': '010101101101010', 'P': '110101110100100',
    'R': '110101110101101', 'S': '011100010001110', 'T': '111010010010010', 'U': '101101101101111', 'V': '101101101101010',
    'W': '101101111111101', 'X': '101101010101101', 'Y': '101101010010010', 'Z': '111001010100111', '0': '010101101101010',
    '1': '010110010010111', '2': '110001010100111', '3': '110001010001110', '4': '101101111001001', '5': '111100110001110',
    '6': '011100110101010', '7': '111001010010010', '8': '010101010101010', '9': '010101011001110', '-': '000000111000000',
    '.': '000000000000010', '/': '001001010100100', ' ': '000000000000000', ':': '000010000010000', 'J': '001001001101010',
}


def text(x, y, s, rgb):
    for ch in s:
        g = FONT[ch]
        for i, bit in enumerate(g):
            if bit == '1':
                px[x + i % 3, y + i // 3] = tuple(rgb) + (255,)
        x += 4


# ---- paints and metals ---------------------------------------------------------------------------------------------
MOTOR = (78, 102, 94)          # pale machinery green-grey
STAND = (60, 78, 74)
fill('paint_motor', 32, 32, MOTOR, noise=4, seed=11, streak=2)
fill('paint_stand', 32, 32, STAND, noise=4, seed=12, streak=1.5)
fill('paint_band', 16, 16, (102, 126, 118), noise=3, seed=13)
edge_shade('paint_band', 18, -22)
# Motor frame cooling ribs: a lit edge, a face and a shadowed edge across the tile; one facet = one rib bay.
x0, y0 = fill('motor_fins', 32, 32, MOTOR, noise=3, seed=14, streak=1)
for x in range(32):
    k = 0
    if x % 8 in (0,):
        k = 30
    elif x % 8 in (1,):
        k = 14
    elif x % 8 in (6, 7):
        k = -26
    for y in range(32):
        r, g, b, a = px[x0 + x, y0 + y]
        px[x0 + x, y0 + y] = (clamp(r + k), clamp(g + k), clamp(b + k), 255)
# Air-water cooler face: a framed panel of horizontal louvers over the finned tube bundle.
x0, y0 = fill('cooler_louver', 32, 32, (72, 92, 86), noise=3, seed=15)
for y in range(3, 29):
    if (y - 3) % 4 in (2, 3):
        rect(x0 + 3, y0 + y, x0 + 29, y0 + y + 1, (30, 38, 38) if (y - 3) % 4 == 3 else (52, 64, 62))
rect(x0, y0, x0 + 32, y0 + 2, (104, 128, 120)); rect(x0, y0 + 30, x0 + 32, y0 + 32, (48, 60, 56))
rect(x0, y0, x0 + 2, y0 + 32, (98, 122, 114)); rect(x0 + 30, y0, x0 + 32, y0 + 32, (50, 62, 58))
for bx in (4, 27):
    for by in (4, 27):
        rect(x0 + bx, y0 + by, x0 + bx + 1, y0 + by + 1, (170, 176, 170))
fill('stainless_cast', 32, 32, (150, 152, 148), noise=7, seed=21, mottle=10)
fill('stainless_brushed', 32, 32, (162, 166, 166), noise=3, seed=22, streak=7)
fill('machined', 16, 16, (184, 188, 188), noise=4, seed=23, streak=4)
fill('dark_steel', 16, 16, (58, 62, 66), noise=4, seed=24)
fill('nut_zinc', 16, 16, (150, 152, 146), noise=8, seed=25)
fill('gasket', 16, 16, (36, 34, 32), noise=3, seed=26)
fill('interior', 16, 16, (40, 46, 46), noise=3, seed=27)
fill('safety_yellow', 16, 16, (226, 184, 32), noise=5, seed=28)
edge_shade('safety_yellow', 16, -30)
fill('suction_blue', 16, 16, (24, 104, 178), noise=4, seed=29)
fill('discharge_teal', 16, 16, (26, 156, 124), noise=4, seed=30)
fill('cw_green', 16, 16, (58, 128, 66), noise=4, seed=31, streak=2, vertical=False)
fill('copper', 16, 16, (186, 112, 60), noise=6, seed=32, streak=4, vertical=False)
fill('chrome', 16, 16, (222, 228, 232), noise=3, seed=33, streak=6)
fill('black_cable', 16, 16, (26, 26, 28), noise=3, seed=34)
fill('jet_stainless', 32, 32, (154, 156, 154), noise=5, seed=41, streak=4, mottle=4)
x0, y0 = fill('jet_weld', 16, 16, (128, 124, 114), noise=6, seed=42)
for y in range(6, 10):
    rect(x0, y0 + y, x0 + 16, y0 + y + 1, (96, 88, 74) if y in (7, 8) else (112, 104, 92))
fill('rip_casing', 32, 32, (138, 142, 142), noise=5, seed=43, streak=3)
# Coupling guard: yellow expanded metal, transparent holes (render_type cutout).
x0, y0 = place('guard_mesh', 32, 32)
for y in range(32):
    for x in range(32):
        cx, cy = (x + (4 if (y // 4) % 2 else 0)) % 8, y % 4
        hole = 2 <= cx <= 6 and 1 <= cy <= 2
        px[x0 + x, y0 + y] = (0, 0, 0, 0) if hole else (clamp(226 - 20 * (cy == 3)), clamp(184 - 20 * (cy == 3)), 36, 255)
for x in range(32):
    for y in (0, 31):
        px[x0 + x, y0 + y] = (196, 156, 24, 255)
for y in range(32):
    for x in (0, 31):
        px[x0 + x, y0 + y] = (196, 156, 24, 255)
# Hazard stripes for the guard frame and the lifting points.
x0, y0 = place('hazard', 16, 16)
for y in range(16):
    for x in range(16):
        px[x0 + x, y0 + y] = (230, 186, 30, 255) if ((x + y) // 4) % 2 == 0 else (30, 30, 30, 255)
# Gauge face: white dial, black ticks, red needle.
x0, y0 = place('gauge', 16, 16)
for y in range(16):
    for x in range(16):
        d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** .5
        px[x0 + x, y0 + y] = (40, 40, 40, 255) if d > 7 else (236, 236, 228, 255) if d < 6.4 else (20, 20, 20, 255)
for a, b in ((4, 10), (3, 7), (5, 4), (8, 3), (11, 4), (12, 7), (11, 10)):
    px[x0 + a, y0 + b] = (20, 20, 20, 255)
for t in range(5):
    px[x0 + 8 + t // 2, y0 + 8 - t] = (200, 30, 20, 255)
# Oil sight glass.
x0, y0 = fill('sight_glass', 16, 16, (196, 132, 30), noise=5, seed=44)
rect(x0, y0, x0 + 16, y0 + 6, (60, 50, 34))
# Nameplates: generic engraved aluminium plates.
x0, y0 = fill('plate_pump', 64, 32, (200, 204, 196), noise=3, seed=51)
rect(x0, y0, x0 + 64, y0 + 1, (120, 120, 116)); rect(x0, y0 + 31, x0 + 64, y0 + 32, (100, 100, 96))
rect(x0, y0, x0 + 1, y0 + 32, (120, 120, 116)); rect(x0 + 63, y0, x0 + 64, y0 + 32, (100, 100, 96))
for sx, sy in ((2, 2), (60, 2), (2, 28), (60, 28)):
    rect(x0 + sx, y0 + sy, x0 + sx + 2, y0 + sy + 2, (90, 90, 88))
rect(x0 + 4, y0 + 4, x0 + 60, y0 + 11, (34, 54, 92))
text(x0 + 11, y0 + 5, 'RECIRC PUMP', (230, 230, 220))
text(x0 + 6, y0 + 13, 'RRC-P-1A', (30, 30, 30))
text(x0 + 6, y0 + 19, '1 STAGE VERT', (60, 60, 60))
text(x0 + 6, y0 + 25, '47200 GPM', (60, 60, 60))
x0, y0 = fill('plate_motor', 64, 32, (196, 198, 188), noise=3, seed=52)
rect(x0, y0, x0 + 64, y0 + 1, (120, 120, 116)); rect(x0, y0 + 31, x0 + 64, y0 + 32, (100, 100, 96))
rect(x0, y0, x0 + 1, y0 + 32, (120, 120, 116)); rect(x0 + 63, y0, x0 + 64, y0 + 32, (100, 100, 96))
text(x0 + 3, y0 + 3, 'INDUCTION MOTOR', (30, 30, 30))
rect(x0 + 3, y0 + 9, x0 + 61, y0 + 10, (70, 70, 66))
text(x0 + 4, y0 + 12, '8900 HP 60 HZ', (50, 50, 50))
text(x0 + 4, y0 + 18, '6600 V 3 PH', (50, 50, 50))
text(x0 + 4, y0 + 24, 'TEWAC 1780 RPM', (50, 50, 50))
x0, y0 = fill('plate_jet', 32, 16, (190, 192, 186), noise=3, seed=53)
text(x0 + 2, y0 + 2, 'JET', (30, 30, 30)); text(x0 + 2, y0 + 9, 'PUMPS', (60, 60, 60))
x0, y0 = fill('plate_rip', 32, 16, (190, 192, 186), noise=3, seed=54)
text(x0 + 2, y0 + 2, 'RIP', (30, 30, 30)); text(x0 + 2, y0 + 9, 'WET MTR', (60, 60, 60))
x0, y0 = fill('label_hv', 16, 16, (230, 186, 30), noise=3, seed=55)
rect(x0, y0, x0 + 16, y0 + 1, (20, 20, 20)); rect(x0, y0 + 15, x0 + 16, y0 + 16, (20, 20, 20))
for i, (a, b) in enumerate(((9, 2), (8, 3), (7, 4), (6, 5), (6, 6), (7, 7), (8, 7), (9, 7), (8, 8), (7, 9), (6, 10), (5, 11), (5, 12))):
    px[x0 + a, y0 + b] = (20, 20, 20, 255)
# Rotating-shaft arrow plate (red on white).
x0, y0 = fill('arrow', 16, 16, (230, 230, 224), noise=2, seed=56)
for y in range(4, 12):
    for x in range(2, 14):
        if (x < 9 and 6 <= y <= 9) or (x >= 9 and abs(y - 7.5) <= 13 - x):
            px[x0 + x, y0 + y] = (196, 30, 24, 255)

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT, optimize=True)
TILES.write_text(json.dumps({'size': S, 'tiles': tiles}, indent=1) + '\n')
print('atlas', OUT, len(tiles), 'tiles, rows used to y =', cursor[1] + cursor[2])
