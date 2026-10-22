"""Inventory icons of the control hardware drawn in code (no Blender model): the controller station, the synchroscope,
the two pen recorder, the joystick switch, the LED rod display and the plant computer.

    python art/models/panel/icons/hardware_icons.py

render_icons.py renders icons from baked Blender models; these items are drawn procedurally in game, so their icons are
drawn procedurally here: flat 32 x 32 textures, painted at 8 times the size and averaged down, like the rendered ones.
"""
import math
import os

from PIL import Image, ImageDraw

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
OUT = os.path.join(REPO, "mod", "src", "main", "resources", "assets", "bwr", "textures", "item", "panel")
S = 8  # supersampling
N = 32

HOUSING = (0x23, 0x27, 0x2C)
PLATE = (0x2E, 0x34, 0x39)
RECESS = (0x14, 0x17, 0x1A)
CARD = (0xE9, 0xE5, 0xD4)
INK = (0x20, 0x20, 0x20)
RED = (0xD0, 0x30, 0x10)
GREEN = (0x30, 0xD0, 0x60)
DARK_GREEN = (0x1E, 0x8A, 0x3A)
AMBER = (0xFF, 0xB0, 0x00)
CAP = (0xC9, 0xCD, 0xD1)
STEEL = (0xC4, 0xC9, 0xCE)


def canvas():
    img = Image.new("RGBA", (N * S, N * S), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def rect(d, x0, y0, x1, y1, fill):
    d.rectangle([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], fill=fill)


def poly(d, points, fill):
    d.polygon([(x * S, y * S) for x, y in points], fill=fill)


def disc(d, cx, cy, r, fill):
    d.ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S - 1, (cy + r) * S - 1], fill=fill)


def save(img, name):
    small = img.resize((N, N), Image.LANCZOS)
    os.makedirs(OUT, exist_ok=True)
    small.save(os.path.join(OUT, name + ".png"))


def controller_station():
    img, d = canvas()
    rect(d, 8, 1, 24, 31, HOUSING)
    rect(d, 9, 2, 23, 30, PLATE)
    rect(d, 10, 3, 22, 17, CARD)
    rect(d, 14.5, 4.5, 15.5, 16, INK)
    poly(d, [(14.4, 8), (11, 6.8), (11, 9.2)], DARK_GREEN)
    poly(d, [(15.6, 11.5), (19, 10.3), (19, 12.7)], RED)
    rect(d, 19.5, 5, 21.5, 16, (0x2A, 0x2E, 0x32))
    rect(d, 19.5, 11, 21.5, 16, AMBER)
    rect(d, 10, 18.5, 15.5, 21.5, GREEN)
    rect(d, 16.5, 18.5, 22, 21.5, (0x6B, 0x4C, 0x00))
    rect(d, 10, 24, 15.5, 29, CAP)
    rect(d, 16.5, 24, 22, 29, CAP)
    poly(d, [(12.75, 24.8), (11.6, 26.6), (13.9, 26.6)], RECESS)
    poly(d, [(19.25, 26.6), (18.1, 24.8), (20.4, 24.8)], RECESS)
    save(img, "controller_station")


def synchroscope():
    img, d = canvas()
    rect(d, 2, 1, 30, 29, HOUSING)
    disc(d, 16, 14, 12.5, RECESS)
    disc(d, 16, 14, 11.2, CARD)
    for i in range(12):
        a = math.radians(i * 30)
        x0, y0 = 16 + math.sin(a) * 9.0, 14 - math.cos(a) * 9.0
        x1, y1 = 16 + math.sin(a) * 10.6, 14 - math.cos(a) * 10.6
        d.line([(x0 * S, y0 * S), (x1 * S, y1 * S)], fill=INK, width=int(0.9 * S))
    a = math.radians(40)
    d.line([(16 * S, 14 * S), ((16 + math.sin(a) * 9) * S, (14 - math.cos(a) * 9) * S)], fill=RED, width=int(1.6 * S))
    disc(d, 16, 14, 1.5, INK)
    disc(d, 7, 27, 2.0, AMBER)
    disc(d, 25, 27, 2.0, (0x6B, 0x4C, 0x00))
    save(img, "synchroscope")


def two_pen_recorder():
    img, d = canvas()
    rect(d, 2, 3, 30, 29, HOUSING)
    rect(d, 4, 5, 28, 27, (0xF2, 0xEF, 0xE2))
    for i in range(1, 5):
        rect(d, 4 + i * 4.8, 5, 4.4 + i * 4.8, 27, (0xCF, 0xC9, 0xB4))
        rect(d, 4, 5 + i * 4.4, 28, 5.4 + i * 4.4, (0xCF, 0xC9, 0xB4))
    d.line([(s * S, y * S) for s, y in [(4, 20), (9, 20), (9, 12), (15, 12), (15, 17), (22, 17), (22, 9), (28, 9)]],
           fill=(0xD0, 0x20, 0x20), width=int(1.4 * S))
    d.line([(s * S, y * S) for s, y in [(4, 24), (11, 24), (11, 19), (18, 19), (18, 23), (28, 23)]],
           fill=(0x10, 0x80, 0x10), width=int(1.4 * S))
    save(img, "two_pen_recorder")


def joystick_switch():
    img, d = canvas()
    rect(d, 4, 4, 28, 28, HOUSING)
    rect(d, 5, 5, 27, 27, PLATE)
    for dx, dy, r in [(0, -8.5, 1.3), (0, 8.5, 1.3), (-8.5, 0, 1.3), (8.5, 0, 1.3)]:
        disc(d, 16 + dx, 16 + dy, r, STEEL)
    disc(d, 16, 16, 5.5, RECESS)
    d.line([(16 * S, 16 * S), (21 * S, 9 * S)], fill=(0x10, 0x10, 0x10), width=int(3.2 * S))
    disc(d, 21.5, 8.5, 3.0, (0x20, 0x20, 0x20))
    disc(d, 20.8, 7.8, 1.0, (0x60, 0x60, 0x60))
    save(img, "joystick_switch")


def led_rod_display():
    img, d = canvas()
    rect(d, 2, 2, 30, 30, HOUSING)
    rect(d, 3, 3, 29, 29, RECESS)
    lit = {(2, 3): "a", (3, 3): "a", (4, 3): "r", (5, 2): "a", (3, 4): "r", (4, 4): "a", (2, 5): "a", (5, 5): "r"}
    for gy in range(7):
        for gx in range(7):
            x = 5.5 + gx * 3.5
            y = 5.5 + gy * 3.5
            if (gx - 3) ** 2 + (gy - 3) ** 2 > 12:
                continue
            kind = lit.get((gx, gy))
            colour = AMBER if kind == "a" else (0xFF, 0x30, 0x20) if kind == "r" else (0x3A, 0x2A, 0x10)
            disc(d, x, y, 1.15, colour)
    save(img, "led_rod_display")


def plant_computer():
    img, d = canvas()
    rect(d, 1, 3, 31, 29, HOUSING)
    rect(d, 2, 4, 30, 28, RECESS)
    # a green / amber / red tile grid on the left, a trend line and a bar on the right
    tiles = [(3, 5, GREEN), (9, 5, GREEN), (3, 11, AMBER), (9, 11, GREEN), (3, 17, GREEN), (9, 17, RED)]
    for x, y, colour in tiles:
        rect(d, x, y, x + 5, y + 5, tuple(c // 3 for c in colour))
        rect(d, x + 0.5, y + 0.5, x + 4.5, y + 4.5, colour)
    rect(d, 16, 5, 29, 14, (0x0A, 0x0B, 0x0C))
    pts = [(17, 12), (19, 10.5), (21, 11.5), (23, 8.5), (25, 9), (27, 6.5), (28.5, 7)]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        steps = 12
        for i in range(steps + 1):
            t = i / steps
            disc(d, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, 0.55, (0x30, 0xD8, 0xE8))
    rect(d, 16, 17, 29, 19.5, (0x1A, 0x1D, 0x20))
    rect(d, 16, 17, 25, 19.5, (0x30, 0xD8, 0xE8))
    rect(d, 3, 24, 29, 27, (0x1A, 0x1D, 0x20))
    for i in range(3):
        rect(d, 4 + i * 8.5, 24.5, 10.5 + i * 8.5, 26.5, (0x60, 0x66, 0x6C))
    save(img, "plant_computer")


if __name__ == "__main__":
    controller_station()
    synchroscope()
    two_pen_recorder()
    joystick_switch()
    led_rod_display()
    plant_computer()
    print("wrote 6 icons to", OUT)
