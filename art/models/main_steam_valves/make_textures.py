"""Small generic detail textures for the main steam valve models (MSIV and safety relief valve).

Run with ordinary Python + Pillow: python art/models/main_steam_valves/make_textures.py
Writes art/models/modern/textures/msv_*.png; tools-export-modern.py copies them to
assets/bwr/textures/block/modern/. Text is generic: no vendor names or logos.
"""
import importlib.util
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('tdp_textures', ROOT / 'art/models/turbine_pumps/make_textures.py')
tdp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tdp)
OUT = tdp.OUT


def scale():
    """Local stem position scale: OPEN at the top, SHUT at the bottom, with ticks."""
    w, h = 32, 128
    im = tdp.brushed(w, h, (226, 224, 214), 31)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w - 1, h - 1), outline=(40, 42, 42), width=2)
    d.rectangle((3, 3, w - 4, 18), fill=(190, 36, 28))
    d.text((w / 2, 11), 'OPEN', fill=(250, 248, 240), font=tdp.font(9), anchor='mm')
    d.rectangle((3, h - 19, w - 4, h - 4), fill=(36, 140, 62))
    d.text((w / 2, h - 11), 'SHUT', fill=(250, 248, 240), font=tdp.font(9), anchor='mm')
    for i in range(11):
        y = 24 + i * (h - 48) / 10
        d.line((4 if i % 5 == 0 else 9, y, w - 5, y), fill=(30, 30, 30), width=2 if i % 5 == 0 else 1)
    return im


def run():
    OUT.mkdir(parents=True, exist_ok=True)
    images = {
        'msv_plate_msiv': tdp.nameplate(['ISOLATION VALVE', 'MAIN STEAM 26 IN Y-GLOBE', 'AIR OPEN  SPRING SHUT', 'STROKE 3-5 S  BWR USE'], 41),
        'msv_plate_srv': tdp.nameplate(['SAFETY RELIEF', 'DIRECT SPRING  6 X 10 IN', 'SET 1165 PSIG  AIR LIFT', 'DISCHARGE TO POOL'], 42),
        'msv_scale': scale(),
        'msv_label_air': tdp.label('INST AIR\nN2 ONLY', (232, 182, 20), (20, 20, 20), 43),
    }
    for name, im in images.items():
        im.save(OUT / (name + '.png'), optimize=True)
        print('wrote', name, im.size)


if __name__ == '__main__':
    run()
