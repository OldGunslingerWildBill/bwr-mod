"""Draws the 256 x 256 detail sheet used by the motor-driven pump remodel (plates, louvres, guard mesh, gauge dial).

Run with plain Python (Pillow): python art/models/modern/make_motor_pump_texture.py
Generic text only: no maker names or logos. REGIONS (pixel rectangles, top-left origin) are read by build_motor_pumps.py.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent / 'textures' / 'motor_pump_details.png'
S = 256
REGIONS = {
    'motor_plate': (0, 0, 128, 64), 'pump_plate': (128, 0, 256, 64),
    'tag_lpcs': (0, 64, 128, 80), 'tag_rhr': (0, 80, 128, 96), 'tag_hpcs': (0, 96, 128, 112),
    'tag_rfw': (0, 112, 128, 128), 'tag_slc': (0, 128, 128, 144), 'tag_tank': (0, 144, 128, 160),
    'louvre': (128, 64, 192, 128), 'mesh': (192, 64, 256, 128), 'gauge': (128, 128, 192, 192),
    'caution': (192, 128, 256, 160), 'fan_grille': (192, 160, 256, 224), 'sight_glass': (0, 160, 32, 224),
    'conduit_tag': (32, 160, 128, 176), 'boron_placard': (0, 224, 128, 256),
}


def font(size):
    return ImageFont.load_default(size=size)


def plate(d, box, lines, fill=(176, 182, 186), ink=(24, 27, 30)):
    x0, y0, x1, y1 = box
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=fill)
    d.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), outline=(110, 116, 120))
    for cx, cy in ((x0 + 4, y0 + 4), (x1 - 5, y0 + 4), (x0 + 4, y1 - 5), (x1 - 5, y1 - 5)):
        d.ellipse((cx - 2, cy - 2, cx + 2, cy + 2), fill=(90, 96, 100))
    y = y0 + 5
    for size, text in lines:
        f = font(size)
        w = d.textlength(text, font=f)
        d.text((x0 + (x1 - x0 - w) / 2, y), text, font=f, fill=ink)
        y += size + 2


def tag(d, box, text):
    x0, y0, x1, y1 = box
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(236, 236, 230))
    d.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), outline=(20, 20, 20))
    f = font(11)
    w = d.textlength(text, font=f)
    d.text((x0 + (x1 - x0 - w) / 2, y0 + 2), text, font=f, fill=(15, 15, 15))


def main():
    img = Image.new('RGB', (S, S), (40, 44, 48))
    d = ImageDraw.Draw(img)
    plate(d, REGIONS['motor_plate'], [(9, '3-PHASE INDUCTION MOTOR'), (8, '4160 V   60 HZ   1780 RPM'),
                                      (8, 'INS CL F   SF 1.15   3 PH'), (8, 'ENCL WP-II   DUTY CONT'),
                                      (8, 'SERIAL 0000-000   1E CLASS')])
    plate(d, REGIONS['pump_plate'], [(9, 'PUMP'), (8, 'MULTISTAGE CENTRIFUGAL'),
                                     (8, 'FLOW / HEAD: SEE DATA'), (8, 'DESIGN PRESS.: SEE DATA'),
                                     (8, 'SAFETY RELATED  CL 2')])
    for key, text in (('tag_lpcs', 'LPCS PUMP P-1'), ('tag_rhr', 'RHR PUMP P-2A'), ('tag_hpcs', 'HPCS PUMP P-1'),
                      ('tag_rfw', 'MOTOR FEED PUMP'), ('tag_slc', 'SLC PUMP P-1A'), ('tag_tank', 'SLC TANK TK-1')):
        tag(d, REGIONS[key], text)
    # Cooling-air openings: dark slots with a lit upper lip on each louvre blade.
    x0, y0, x1, y1 = REGIONS['louvre']
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(12, 14, 16))
    for y in range(y0 + 2, y1 - 2, 6):
        d.rectangle((x0 + 2, y, x1 - 3, y + 1), fill=(150, 156, 160))
        d.rectangle((x0 + 2, y + 2, x1 - 3, y + 2), fill=(70, 74, 78))
    d.rectangle((x0, y0, x1 - 1, y1 - 1), outline=(95, 100, 104))
    # Expanded-metal guard in safety yellow with a dark interior behind it.
    x0, y0, x1, y1 = REGIONS['mesh']
    tile = Image.new('RGB', (x1 - x0, y1 - y0), (18, 18, 16))
    t = ImageDraw.Draw(tile)
    for i in range(-64, 72, 8):
        t.line((i, 0, i + 64, 64), fill=(214, 160, 28), width=2)
        t.line((i + 64, 0, i, 64), fill=(214, 160, 28), width=2)
    t.rectangle((0, 0, x1 - x0 - 1, y1 - y0 - 1), outline=(214, 160, 28), width=3)
    img.paste(tile, (x0, y0))
    # Pressure gauge dial.
    x0, y0, x1, y1 = REGIONS['gauge']
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(30, 32, 34))
    d.ellipse((x0 + 2, y0 + 2, x1 - 3, y1 - 3), fill=(232, 232, 226), outline=(20, 20, 20), width=2)
    import math
    cx, cy, r = (x0 + x1) / 2, (y0 + y1) / 2, 26
    for k in range(11):
        a = math.radians(225 - 27 * k)
        d.line((cx + r * math.cos(a), cy - r * math.sin(a), cx + (r - 5) * math.cos(a), cy - (r - 5) * math.sin(a)), fill=(20, 20, 20), width=1)
    d.arc((cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1), -45 - 50, -45, fill=(190, 30, 25), width=3)
    a = math.radians(120)
    d.line((cx, cy, cx + 21 * math.cos(a), cy - 21 * math.sin(a)), fill=(180, 20, 20), width=2)
    d.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), fill=(20, 20, 20))
    # Caution label.
    x0, y0, x1, y1 = REGIONS['caution']
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(236, 180, 20))
    d.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), outline=(20, 20, 20))
    for text, yy in (('CAUTION', y0 + 3), ('ROTATING', y0 + 13), ('EQUIPMENT', y0 + 21)):
        f = font(9 if text == 'CAUTION' else 7)
        d.text((x0 + (64 - d.textlength(text, font=f)) / 2, yy), text, font=f, fill=(15, 15, 15))
    # Fan cowl grille for the end of a horizontal motor.
    x0, y0, x1, y1 = REGIONS['fan_grille']
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(22, 24, 26))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    for r in range(6, 32, 4):
        d.ellipse((cx - r, cy - r, cx + r, cy + r), outline=(120, 140, 170), width=1)
    for k in range(8):
        a = k * math.pi / 4
        d.line((cx, cy, cx + 31 * math.cos(a), cy + 31 * math.sin(a)), fill=(120, 140, 170), width=1)
    d.ellipse((cx - 5, cy - 5, cx + 5, cy + 5), fill=(80, 100, 128))
    # Oil / liquid sight glass.
    x0, y0, x1, y1 = REGIONS['sight_glass']
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(150, 156, 160))
    d.rectangle((x0 + 8, y0 + 4, x1 - 9, y1 - 5), fill=(30, 34, 38))
    d.rectangle((x0 + 9, y0 + 34, x1 - 10, y1 - 6), fill=(196, 150, 40))
    d.line((x0 + 6, y0 + 32, x1 - 7, y0 + 32), fill=(200, 30, 30), width=1)
    tag(d, REGIONS['conduit_tag'], '4160 VAC')
    x0, y0, x1, y1 = REGIONS['boron_placard']
    d.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(236, 180, 20))
    d.rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), outline=(20, 20, 20), width=2)
    for text, yy, size in (('SODIUM PENTABORATE', y0 + 5, 10), ('SOLUTION - KEEP HEATED', y0 + 18, 8)):
        f = font(size)
        d.text((x0 + (128 - d.textlength(text, font=f)) / 2, yy), text, font=f, fill=(15, 15, 15))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, optimize=True)
    print('wrote', OUT)


if __name__ == '__main__':
    main()
