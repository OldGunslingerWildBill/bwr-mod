#!/usr/bin/env python3
"""Write the pipe textures (PIPES.md): colour PNGs and labPBR `_s` specular maps.

    python tools-make-pipe-textures.py           write them
    python tools-make-pipe-textures.py --check   fail if a written file differs (read-only)

Into mod/src/main/resources/assets/bwr/textures/block/pipes/, 32 x 32 each, all tiling in both directions:
  painted_steel   the paint coat of a bare pipe. Near white, so the block colour (tint index 0: the service colour or a
                  dye) gives the paint its colour. The water pipe and the (future) oil pipe use it.
  weld            the girth weld at a butt joint: darker unpainted steel with a weld ripple across it.
  flange_steel    machined flange and stud steel, also pipe caps.
  cladding        aluminium cladding over the steam pipe's lagging, mapped one block (one sheet) along its length:
                  rows 15 and 16 are the overlapping lap of the next sheet (a shadow line, then the lit edge), the
                  left columns the longitudinal lap at the underside. (The block boundary carries the service band.)
  service_band    the painted band over each cladding joint, near white so the tint gives it its colour (amber by
                  default). Mapped across the band's width.
Each also gets a labPBR `<name>_s.png`: R perceptual smoothness, G F0 (230 iron, 232 aluminium, small for paint),
B porosity 0, A 255 (no emission). No `_n` maps: the normals stay flat (as the lamps do, LIGHTING.md).
Standard library only; the noise is seeded, so a re-run writes the same bytes.
"""
import math
import os
import random
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr", "textures", "block", "pipes")
N = 32


def png_bytes(pixels):
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def clamp(v):
    return max(0, min(255, int(round(v))))


def noise(seed, cells=4):
    """Tileable value noise in -1..1 on the N x N grid (cells x cells lattice, wrapped)."""
    rnd = random.Random(seed)
    lattice = [[rnd.uniform(-1, 1) for _ in range(cells)] for _ in range(cells)]

    def smooth(t):
        return t * t * (3 - 2 * t)

    out = []
    for y in range(N):
        row = []
        for x in range(N):
            fx, fy = x * cells / N, y * cells / N
            x0, y0 = int(fx), int(fy)
            tx, ty = smooth(fx - x0), smooth(fy - y0)
            a = lattice[y0 % cells][x0 % cells]
            b = lattice[y0 % cells][(x0 + 1) % cells]
            c = lattice[(y0 + 1) % cells][x0 % cells]
            d = lattice[(y0 + 1) % cells][(x0 + 1) % cells]
            row.append((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty)
        out.append(row)
    return out


def grain(seed):
    rnd = random.Random(seed)
    return [[rnd.uniform(-1, 1) for _ in range(N)] for _ in range(N)]


def grey(value, tint=(1.0, 1.0, 1.0)):
    return tuple(clamp(value * t) for t in tint) + (255,)


def painted_steel():
    low, fine = noise(11, 4), grain(12)
    return [[grey(208 + 4 * low[y][x] + 2.5 * fine[y][x]) for x in range(N)] for y in range(N)]


def weld():
    low, fine = noise(21, 8), grain(22)
    px = []
    for y in range(N):
        row = []
        for x in range(N):
            # Weld ripples: overlapping crescents across the bead, every 4 texels around the pipe.
            ripple = 9 * math.cos((x + 0.6 * abs((y % 4) - 1.5)) * math.pi / 2)
            row.append(grey(146 + ripple + 6 * low[y][x] + 4 * fine[y][x], (1.0, 0.97, 0.92)))
        px.append(row)
    return px


def flange_steel():
    low, fine = noise(31, 4), grain(32)
    return [[grey(150 + 6 * low[y][x] + 4 * fine[y][x], (0.98, 1.0, 1.02)) for x in range(N)] for y in range(N)]


def cladding():
    low, fine = noise(41, 2), grain(42)
    px = []
    for y in range(N):
        row = []
        for x in range(N):
            v = 170 + 5 * low[y][x] + 2 * fine[y][x]
            # A soft sheen across each sheet (sheet metal is never quite flat).
            v += 5 * math.cos((y / N) * 2 * math.pi)
            if y == 15:
                v = 132                       # the shadow under the next sheet's lap, mid-block
            elif y == 16:
                v = 196                       # the lit lap edge
            if x == 0:
                v -= 22                       # longitudinal lap, underside
            elif x == 1:
                v += 14
            row.append(grey(v, (0.98, 1.0, 1.02)))
        px.append(row)
    return px


def service_band():
    fine = grain(51)
    px = []
    for y in range(N):
        row = []
        for x in range(N):
            v = 236 + 2.5 * fine[y][x]
            if y in (0, N - 1):
                v = 196                       # the band's edges catch less light
            row.append(grey(v))
        px.append(row)
    return px


# name: (pixels, smoothness, F0)
TEXTURES = {
    "painted_steel": (painted_steel, 120, 20),
    "weld": (weld, 70, 230),
    "flange_steel": (flange_steel, 150, 230),
    "cladding": (cladding, 175, 232),
    "service_band": (service_band, 130, 20),
}


def specular(smooth, f0):
    return [[(smooth, f0, 0, 255) for _ in range(N)] for _ in range(N)]


def outputs():
    out = {}
    for name, (make, smooth, f0) in TEXTURES.items():
        out[os.path.join(OUT, name + ".png")] = png_bytes(make())
        out[os.path.join(OUT, name + "_s.png")] = png_bytes(specular(smooth, f0))
    return out


def main():
    files = outputs()
    if "--check" in sys.argv:
        for path, data in files.items():
            if not os.path.isfile(path) or open(path, "rb").read() != data:
                raise SystemExit("Pipe texture differs: " + path)
        print(len(files), "pipe textures verified")
        return
    os.makedirs(OUT, exist_ok=True)
    for path, data in files.items():
        with open(path, "wb") as fh:
            fh.write(data)
    print(len(files), "pipe textures written to", OUT)


if __name__ == "__main__":
    main()
