#!/usr/bin/env python3
"""Draws the 16x16 textures of the survey meter and the area radiation monitor (original pixel art, no outside source).

Run from anywhere with Pillow installed:  python tools-make-radiation-assets.py
Writes mod/src/main/resources/assets/bwr/textures/block/area_radiation_monitor_*.png and item/survey_meter.png.
"""
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(ROOT, "mod/src/main/resources/assets/bwr/textures")


def save(img, rel):
    path = os.path.join(TEX, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", rel)


def body():
    img = Image.new("RGBA", (16, 16), (138, 144, 150, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=(92, 98, 104, 255))
    d.rectangle([1, 1, 14, 14], outline=(160, 166, 172, 255))
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        img.putpixel((x, y), (60, 64, 68, 255))
    for x in range(2, 14):  # a hazard stripe along the bottom
        colour = (240, 200, 30, 255) if (x // 2) % 2 == 0 else (30, 30, 30, 255)
        img.putpixel((x, 12), colour)
        img.putpixel((x, 11), colour if (x // 2) % 2 == 1 else (240, 200, 30, 255))
    return img


def front():
    img = Image.new("RGBA", (16, 16), (28, 34, 42, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=(112, 118, 124, 255))
    d.rectangle([1, 1, 14, 14], outline=(58, 64, 72, 255))
    cx, cy = 7.5, 6.5
    yellow = (250, 205, 30, 255)
    # a radiation trefoil: three 60 degree sectors, a hub and a ring
    for k in range(3):
        start = 90 + k * 120 - 30
        d.pieslice([cx - 5, cy - 5, cx + 5, cy + 5], start, start + 60, fill=yellow)
    d.ellipse([cx - 1.4, cy - 1.4, cx + 1.4, cy + 1.4], fill=(28, 34, 42, 255))
    d.ellipse([cx - 0.7, cy - 0.7, cx + 0.7, cy + 0.7], fill=yellow)
    for x in range(2, 14):  # a scale along the bottom: ticks, longer every fourth
        img.putpixel((x, 13), (120, 230, 140, 255))
        if (x - 2) % 4 == 0:
            img.putpixel((x, 12), (120, 230, 140, 255))
    return img


def lamp(core, rim):
    img = Image.new("RGBA", (16, 16), rim)
    d = ImageDraw.Draw(img)
    d.rectangle([3, 3, 12, 12], fill=core)
    d.rectangle([5, 5, 7, 7], fill=tuple(min(255, c + 70) for c in core[:3]) + (255,))
    return img


def survey_meter():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([7, 0, 8, 3], fill=(150, 156, 162, 255))      # the probe
    d.rectangle([6, 3, 9, 4], fill=(100, 106, 112, 255))
    d.rectangle([3, 4, 12, 15], fill=(236, 168, 24, 255), outline=(120, 80, 10, 255))   # the case
    d.rectangle([4, 5, 11, 9], fill=(24, 34, 30, 255), outline=(70, 76, 70, 255))       # the screen
    for x, h in ((5, 1), (6, 2), (7, 3), (8, 2), (9, 3), (10, 1)):                      # a bargraph
        d.line([x, 8, x, 8 - h], fill=(110, 240, 130, 255))
    d.ellipse([5, 11, 7, 13], fill=(40, 40, 40, 255))                                    # a knob
    d.rectangle([9, 11, 11, 13], fill=(200, 40, 30, 255), outline=(90, 20, 15, 255))     # a button
    return img


if __name__ == "__main__":
    save(body(), "block/area_radiation_monitor_body.png")
    save(front(), "block/area_radiation_monitor_front.png")
    save(lamp((70, 36, 36, 255), (40, 40, 44, 255)), "block/area_radiation_monitor_lamp_off.png")
    save(lamp((255, 176, 0, 255), (60, 48, 20, 255)), "block/area_radiation_monitor_lamp_alert.png")
    save(lamp((255, 40, 30, 255), (70, 20, 18, 255)), "block/area_radiation_monitor_lamp_alarm.png")
    save(survey_meter(), "item/survey_meter.png")
