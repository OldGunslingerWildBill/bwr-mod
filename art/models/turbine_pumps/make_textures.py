"""Small generic detail textures for the turbine-driven pump models (gauges, nameplates, local panels).

Run with ordinary Python + Pillow: python art/models/turbine_pumps/make_textures.py
Writes art/models/modern/textures/tdp_*.png; tools-export-modern.py copies them to
assets/bwr/textures/block/modern/. Text is generic: no vendor names or logos.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import random

OUT = Path(__file__).resolve().parents[1] / 'modern' / 'textures'


def font(size):
    return ImageFont.load_default(size=size)


def brushed(w, h, base, seed):
    rnd = random.Random(seed)
    im = Image.new('RGB', (w, h), base)
    px = im.load()
    for y in range(h):
        row = rnd.randint(-7, 7)
        for x in range(w):
            n = row + rnd.randint(-3, 3)
            px[x, y] = tuple(max(0, min(255, c + n)) for c in base)
    return im


def gauge():
    s = 64
    im = Image.new('RGB', (s, s), (24, 26, 28))
    d = ImageDraw.Draw(im)
    d.ellipse((1, 1, s - 2, s - 2), fill=(40, 42, 44))
    d.ellipse((5, 5, s - 6, s - 6), fill=(238, 236, 226))
    import math
    c = s / 2
    for i in range(41):
        a = math.radians(225 - i * 270 / 40)
        r0 = 25 if i % 5 == 0 else 27
        d.line((c + r0 * math.cos(a), c - r0 * math.sin(a), c + 29 * math.cos(a), c - 29 * math.sin(a)),
               fill=(20, 20, 20), width=2 if i % 5 == 0 else 1)
    # Green normal band and a red high band on the outer arc.
    d.arc((7, 7, s - 8, s - 8), start=135 + 270 * .45, end=135 + 270 * .75, fill=(40, 140, 60), width=3)
    d.arc((7, 7, s - 8, s - 8), start=135 + 270 * .88, end=405, fill=(190, 30, 25), width=3)
    a = math.radians(225 - 270 * .6)
    d.line((c - 6 * math.cos(a), c + 6 * math.sin(a), c + 24 * math.cos(a), c - 24 * math.sin(a)), fill=(170, 20, 15), width=2)
    d.ellipse((c - 3, c - 3, c + 3, c + 3), fill=(25, 25, 25))
    d.text((c, c + 13), 'kPa', fill=(30, 30, 30), font=font(8), anchor='mm')
    return im


def nameplate(lines, seed):
    w, h = 128, 64
    im = brushed(w, h, (176, 180, 178), seed)
    d = ImageDraw.Draw(im)
    d.rectangle((1, 1, w - 2, h - 2), outline=(60, 62, 62), width=2)
    for x, y in ((5, 5), (w - 6, 5), (5, h - 6), (w - 6, h - 6)):
        d.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(90, 92, 92))
    d.rectangle((10, 6, w - 11, 19), fill=(28, 30, 32))
    d.text((w / 2, 13), lines[0], fill=(225, 225, 215), font=font(10), anchor='mm')
    y = 25
    for line in lines[1:]:
        d.text((11, y), line, fill=(25, 27, 28), font=font(8))
        y += 9
    return im


def label(text, bg, fg, seed):
    w, h = 64, 32
    im = Image.new('RGB', (w, h), bg)
    d = ImageDraw.Draw(im)
    d.rectangle((1, 1, w - 2, h - 2), outline=fg, width=2)
    words = text.split('\n')
    for i, t in enumerate(words):
        d.text((w / 2, h / 2 + (i - (len(words) - 1) / 2) * 11), t, fill=fg, font=font(10), anchor='mm')
    return im


def panel(title, seed):
    """Local turbine control station face: mimic, lamps, selector switches and push buttons."""
    w, h = 128, 128
    im = brushed(w, h, (150, 158, 154), seed)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, h - 1), outline=(70, 74, 72), width=3)
    d.rectangle((8, 6, w - 9, 20), fill=(28, 30, 32))
    d.text((w / 2, 13), title, fill=(230, 230, 220), font=font(9), anchor='mm')
    # Mimic of steam inlet, turbine and pump.
    d.rectangle((10, 26, w - 11, 62), fill=(214, 216, 208))
    d.line((14, 36, 52, 36), fill=(200, 120, 20), width=3)
    d.polygon(((52, 30), (66, 26), (66, 52), (52, 48)), outline=(30, 30, 30), fill=(160, 166, 160))
    d.line((66, 39, 82, 39), fill=(30, 30, 30), width=2)
    d.ellipse((82, 31, 98, 47), outline=(30, 30, 30), fill=(70, 120, 80), width=2)
    d.line((98, 39, 114, 39), fill=(40, 80, 170), width=3)
    d.line((90, 47, 90, 58), fill=(40, 80, 170), width=3)
    d.line((59, 52, 59, 58), fill=(140, 140, 140), width=3)
    # Lamp row: red open / green closed pairs.
    for i in range(5):
        x = 18 + i * 23
        d.ellipse((x - 5, 69, x + 5, 79), fill=(200, 40, 30), outline=(30, 30, 30))
        d.ellipse((x + 3, 69, x + 13, 79), fill=(40, 160, 70), outline=(30, 30, 30))
        d.rectangle((x - 5, 82, x + 13, 86), fill=(235, 235, 225))
    # Selector switches and push buttons.
    for i in range(4):
        x = 20 + i * 29
        d.ellipse((x - 9, 92, x + 9, 110), fill=(35, 36, 38))
        d.line((x, 94, x + 5, 101), fill=(230, 230, 230), width=3)
        d.rectangle((x - 9, 113, x + 9, 118), fill=(235, 235, 225))
    return im


def run():
    OUT.mkdir(parents=True, exist_ok=True)
    images = {
        'tdp_gauge': gauge(),
        'tdp_plate_rfpt': nameplate(['FEED PUMP TURBINE', 'MULTISTAGE CONDENSING', 'RATED 5300 RPM  7 MW', 'SER 1A  BWR PLANT USE'], 11),
        'tdp_plate_feedpump': nameplate(['REACTOR FEED PUMP', 'BARREL MULTISTAGE', 'RATED 1300 M HEAD', 'SER 1A  SEE MANUAL'], 12),
        'tdp_plate_rcic': nameplate(['RCIC TURBINE', 'SINGLE WHEEL IMPULSE', 'RATED 4500 RPM', 'TRIP 125 PCT SPEED'], 13),
        'tdp_plate_hpci': nameplate(['HPCI TURBINE', 'TWO ROW IMPULSE', 'RATED 4000 RPM', 'TRIP 125 PCT SPEED'], 14),
        'tdp_plate_eccspump': nameplate(['INJECTION PUMP', 'HORIZONTAL MULTISTAGE', 'DRIVEN BY TURBINE', 'SEE SYSTEM MANUAL'], 15),
        'tdp_label_guard': label('DANGER\nROTATING', (232, 182, 20), (20, 20, 20), 16),
        'tdp_panel_rcic': panel('RCIC LOCAL PANEL', 17),
        'tdp_panel_hpci': panel('HPCI LOCAL PANEL', 18),
    }
    for name, im in images.items():
        im.save(OUT / (name + '.png'), optimize=True)
        print('wrote', name, im.size)


if __name__ == '__main__':
    run()
