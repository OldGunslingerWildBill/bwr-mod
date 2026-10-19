#!/usr/bin/env python3
"""Generate the control-room furniture textures (mod/src/main/resources/assets/bwr/textures/block/furniture/*.png).

Standard library only. Re-run to regenerate; the textures are small hand-drawn style panels: cabinet louvres, an
indicator window, desk monitor and CRT screens, a keyboard, two mimic (synoptic) band finishes and the overview display
pictures. The mimic tiles are drawn so their lines meet the tile edges at the same heights, so a run of bands joins up.
The display pictures cover a whole 2 x 2 or 3 x 2 display (32 px per block); models map one block's share of each.
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "mod/src/main/resources/assets/bwr/textures/block/furniture")


class Canvas:
    def __init__(self, w, h, fill):
        self.w, self.h = w, h
        self.px = [[fill] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        """Filled rectangle, x1/y1 exclusive."""
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.set(x, y, c)

    def outline(self, x0, y0, x1, y1, c):
        for x in range(x0, x1):
            self.set(x, y0, c)
            self.set(x, y1 - 1, c)
        for y in range(y0, y1):
            self.set(x0, y, c)
            self.set(x1 - 1, y, c)

    def hline(self, x0, x1, y, c, t=1):
        self.rect(x0, y, x1, y + t, c)

    def vline(self, x, y0, y1, c, t=1):
        self.rect(x, y0, x + t, y1, c)

    def line(self, x0, y0, x1, y1, c):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.set(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def circle(self, cx, cy, r, c, fill=None):
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                d = (x - cx) ** 2 + (y - cy) ** 2
                if d <= r * r + r * 0.6:
                    if fill is not None and d < (r - 1) ** 2 + (r - 1) * 0.6:
                        self.set(x, y, fill)
                    elif d >= (r - 1) ** 2:
                        self.set(x, y, c)

    def save(self, name):
        raw = b"".join(b"\x00" + bytes(v for p in row for v in p) for row in self.px)

        def chunk(tag, data):
            body = tag + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)
        png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, name + ".png"), "wb") as fh:
            fh.write(png)


def louvre():
    c = Canvas(16, 16, (30, 34, 37))
    for y in range(16):
        c.hline(0, 16, y, [(88, 95, 100), (62, 68, 72), (22, 25, 27), (18, 20, 22)][y % 4])
    return c


def indicator():
    c = Canvas(16, 8, (12, 16, 18))
    c.outline(0, 0, 16, 8, (44, 49, 52))
    for i, col in enumerate(((214, 48, 40), (232, 170, 34), (70, 200, 96), (96, 64, 28))):
        x = 2 + i * 3
        c.rect(x, 2, x + 2, 4, col)
        c.rect(x, 5, x + 2, 6, tuple(v // 3 for v in col))
    c.line(1, 6, 5, 1, (36, 44, 48))
    return c


def screen_flat():
    c = Canvas(16, 16, (9, 17, 27))
    c.outline(0, 0, 16, 16, (22, 36, 52))
    c.hline(1, 15, 1, (28, 62, 104), 2)
    for g in (5, 9, 13):
        c.hline(1, 15, g, (15, 28, 42))
        c.vline(g - 1, 3, 15, (15, 28, 42))
    for x in range(1, 15):
        c.set(x, 10 - int(3.0 * (1 if x % 5 < 3 else 0) + (x % 3 == 0)), (64, 226, 146))
        c.set(x, 12 - (x // 4) % 3, (240, 176, 48))
    return c


def screen_crt():
    c = Canvas(16, 16, (5, 15, 9))
    for y in range(1, 16, 2):
        c.hline(0, 16, y, (3, 11, 7))
    for y, n in ((3, 9), (5, 6), (7, 11), (9, 4), (11, 8)):
        c.hline(2, 2 + n, y, (80, 230, 118))
    c.rect(2, 13, 4, 14, (120, 255, 150))
    c.outline(0, 0, 16, 16, (10, 26, 16))
    return c


def keyboard():
    c = Canvas(16, 8, (30, 34, 36))
    for row in range(3):
        for k in range(7):
            c.rect(1 + k * 2, 1 + row * 2, 2 + k * 2, 2 + row * 2, (104, 110, 114))
    c.rect(3, 6, 12, 7, (104, 110, 114))
    return c


def stand_panel():
    c = Canvas(16, 16, (38, 43, 47))
    c.outline(0, 0, 16, 16, (20, 23, 25))
    for i, col in enumerate(((214, 48, 40), (232, 170, 34), (70, 200, 96))):
        c.rect(2 + i * 4, 2, 4 + i * 4, 4, col)
    c.rect(2, 6, 7, 9, (196, 52, 44))
    c.rect(9, 6, 14, 9, (60, 170, 92))
    c.circle(5, 12, 2, (210, 214, 216), fill=(70, 76, 80))
    c.rect(10, 11, 14, 14, (22, 25, 27))
    c.rect(11, 12, 13, 13, (190, 194, 196))
    return c


def mimic(cream):
    """32 x 32 tile; the bus lines run through both side edges at rows 10 and 22."""
    if cream:
        bg, ink = (238, 231, 208), (36, 38, 40)
        line_a, line_b, line_c = (36, 84, 170), (196, 48, 40), (46, 140, 76)
    else:
        bg, ink = (22, 24, 27), (226, 226, 220)
        line_a, line_b, line_c = (232, 232, 226), (232, 196, 48), (80, 214, 224)
    c = Canvas(32, 32, bg)
    c.hline(0, 32, 10, line_a, 2)
    c.hline(0, 32, 22, line_b, 2)
    c.vline(16, 12, 22, line_c, 2)
    # a valve (bow tie) on the upper line, a pump (circle with a tail) on the lower one, a tank outline
    c.rect(6, 7, 11, 14, bg)
    c.line(6, 8, 10, 13, ink)
    c.line(6, 13, 10, 8, ink)
    c.vline(6, 8, 14, ink)
    c.vline(10, 8, 14, ink)
    c.circle(24, 22, 4, ink, fill=bg)
    c.hline(24, 29, 19, ink)
    c.line(21, 25, 28, 25, ink)
    c.outline(12, 3, 21, 8, ink)
    c.rect(14, 5, 15, 6, (210, 50, 40))
    c.rect(17, 5, 18, 6, (60, 190, 90))
    c.rect(2, 26, 5, 29, (210, 50, 40))
    c.rect(7, 26, 10, 29, (60, 190, 90))
    return c


def display(width):
    """Overview picture for a display `width` blocks wide and 2 tall (32 px per block)."""
    w, h = 32 * width, 64
    bg = (8, 13, 20)
    c = Canvas(w, h, bg)
    grid = (15, 24, 36)
    for x in range(0, w, 32):
        c.vline(x, 0, h, grid)
    for y in range(0, h, 32):
        c.hline(0, w, y, grid)
    cyan, white, amber, red, green, blue = (86, 196, 226), (226, 230, 232), (236, 170, 40), (214, 56, 46), (66, 200, 100), (60, 110, 190)
    # alarm tile row along the top
    tiles = [(34, 48, 64)] * 7 + [amber, red, green, (34, 48, 64)]
    for i in range(w // 9):
        col = tiles[(i * 5 + 3) % len(tiles)]
        c.rect(3 + i * 9, 3, 3 + i * 9 + 7, 8, col)
    # plant mimic: vessel -> steam line -> turbine -> generator -> grid; condenser and feed loop below
    ox = 2
    c.outline(ox + 6, 16, ox + 20, 44, cyan)
    c.hline(ox + 7, ox + 19, 30, blue, 2)
    c.vline(ox + 8, 20, 30, (80, 130, 190))
    c.vline(ox + 13, 24, 30, (80, 130, 190))
    c.vline(ox + 20, 18, 22, cyan)
    c.hline(ox + 20, ox + 38, 18, white)
    t0 = ox + 38 if width == 3 else ox + 26
    c.line(t0, 14, t0 + 12, 17, white)
    c.line(t0, 28, t0 + 12, 25, white)
    c.vline(t0, 14, 29, white)
    c.vline(t0 + 12, 17, 26, white)
    c.hline(t0 + 12, t0 + 18, 21, white)
    gx = t0 + 24 if width == 3 else t0 + 22
    c.circle(gx, 21, 6, white, fill=bg)
    c.hline(gx - 3, gx + 3, 21, amber)
    c.hline(gx + 6, w - 3, 21, amber)
    c.rect(w - 6, 18, w - 3, 24, green)
    c.outline(t0 - 2, 36, t0 + 18, 46, blue)
    c.vline(t0 + 6, 28, 36, blue)
    c.hline(t0 - 2, t0 - 14, 41, blue)
    c.circle(t0 - 14, 41, 3, cyan, fill=bg)
    c.hline(ox + 20, t0 - 17, 41, blue)
    c.vline(ox + 20, 41, 44, blue)
    # trend charts and bars along the bottom
    tw = 34 if width == 3 else 28
    c.outline(4, 50, 4 + tw, 61, (30, 52, 78))
    for x in range(5, 3 + tw):
        c.set(x, 56 - int(3.2 * (1 if (x // 4) % 2 else 0) + (x % 7) * 0.3), green)
        c.set(x, 58 - (x // 5) % 3, amber)
    for i in range(6 if width == 3 else 3):
        bx = 42 + i * 8 if width == 3 else 36 + i * 8
        c.outline(bx, 49, bx + 6, 62, (30, 52, 78))
        height = [9, 6, 11, 4, 8, 7][i]
        c.rect(bx + 1, 61 - height, bx + 5, 61, [green, cyan, green, amber, green, cyan][i])
    return c


def main():
    louvre().save("louvre")
    indicator().save("indicator")
    screen_flat().save("screen_flat")
    screen_crt().save("screen_crt")
    keyboard().save("keyboard")
    stand_panel().save("stand_panel")
    mimic(True).save("mimic_cream")
    mimic(False).save("mimic_black")
    display(2).save("display_2x2")
    display(3).save("display_3x2")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
