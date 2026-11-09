"""Small generic detail textures for the generator circuit breaker model (nameplates, position indicator, labels).

Run with ordinary Python + Pillow: python art/models/generator_breaker/make_textures.py
Writes art/models/generator_breaker/textures/gcb_*.png. Text is generic: no vendor names or logos.
"""
import importlib.util
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location('tdp_textures', ROOT / 'art/models/turbine_pumps/make_textures.py')
tdp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tdp)
OUT = HERE / 'textures'


def indicator(closed):
    """Mechanical position indicator window: US practice, red I = closed, green O = open."""
    s = 64
    bg, ch, word = ((190, 36, 28), 'I', 'CLOSED') if closed else ((36, 140, 62), 'O', 'OPEN')
    im = Image.new('RGB', (s, s), (22, 24, 26))
    d = ImageDraw.Draw(im)
    d.rectangle((3, 3, s - 4, s - 4), fill=bg)
    d.text((s / 2, 26), ch, fill=(250, 248, 240), font=tdp.font(40), anchor='mm')
    d.text((s / 2, 53), word, fill=(250, 248, 240), font=tdp.font(10), anchor='mm')
    return im


def hazard():
    w, h = 64, 32
    im = Image.new('RGB', (w, h), (236, 190, 20))
    d = ImageDraw.Draw(im)
    for x in range(-h, w, 12):
        d.polygon([(x, 0), (x + 6, 0), (x + 6 + 8, 8), (x + 8, 8)], fill=(20, 20, 20))
    d.text((w / 2, 17), 'DANGER', fill=(20, 20, 20), font=tdp.font(11), anchor='mm')
    d.text((w / 2, 27), 'HIGH VOLTAGE', fill=(20, 20, 20), font=tdp.font(8), anchor='mm')
    d.rectangle((0, 0, w - 1, h - 1), outline=(20, 20, 20), width=1)
    return im


def run():
    OUT.mkdir(parents=True, exist_ok=True)
    images = {
        'gcb_plate': tdp.nameplate(['GENERATOR BREAKER', 'SF6  3 POLE', '1300 MW  28.9 KA', 'IPB-45 FA  1150 MM'], 61),
        'gcb_plate_vt': tdp.nameplate(['VOLTAGE XFMR', 'METAL ENCLOSED', 'GROUNDED TANK', 'GEN TERMINALS'], 64),
        'gcb_plate_cap': tdp.nameplate(['SURGE CAP', 'METAL ENCLOSED', 'GROUNDED TANK', 'GEN TERMINALS'], 65),
        'gcb_plate_arr': tdp.nameplate(['SURGE ARRESTER', 'METAL ENCLOSED', 'GROUNDED TANK', 'GEN TERMINALS'], 66),
        'gcb_plate_hmb': tdp.nameplate(['SPRING DRIVE', 'HYDROMECHANICAL', 'MECH TYPE HMB-8', 'FOR HEC 7/8 CLASS'], 62),
        'gcb_mpi_closed': indicator(True),
        'gcb_mpi_open': indicator(False),
        'gcb_hazard': hazard(),
        'gcb_label_sf6': tdp.label('SF6 GAS\nFILL 6 BAR', (232, 182, 20), (20, 20, 20), 63),
    }
    for name, im in images.items():
        im.save(OUT / (name + '.png'), optimize=True)
        print('wrote', name, im.size)


if __name__ == '__main__':
    run()
