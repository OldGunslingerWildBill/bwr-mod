"""Generate the corium block textures; --check is read-only.

Standard library only, fully deterministic (own PRNG, own PNG writer), so the
committed PNGs are reproducible byte for byte:

  corium_hot.png (+ .mcmeta)  molten corium: dark crust plates split by glowing
                              orange-yellow cracks, a 16x16 animation strip
  corium_cold.png             solidified slag: grey-black glass, frozen flow
                              lines, a few metallic flecks

Every noise field is periodic on the 16x16 torus, so the textures tile without
seams. Previews (each texture tiled 3x3, scaled 16x, nearest neighbour) go to
tmp/corium/ for looking at; they are not shipped.

  py tools-export-corium.py           write textures and previews
  py tools-export-corium.py --check   fail if a committed texture is stale
"""
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TEX = ROOT / "mod/src/main/resources/assets/bwr/textures/block"
PREVIEW = ROOT / "tmp/corium"
N = 16
HOT_FRAMES = 8
HOT_FRAMETIME = 6


# ---------------------------------------------------------------- utilities

class Rng:
    """xorshift32: tiny, portable, and the same on every Python."""

    def __init__(self, seed):
        self.s = seed & 0xFFFFFFFF or 1

    def next(self):
        s = self.s
        s ^= (s << 13) & 0xFFFFFFFF
        s ^= s >> 17
        s ^= (s << 5) & 0xFFFFFFFF
        self.s = s
        return s / 4294967296.0


def lattice(seed, period):
    r = Rng(seed)
    return [[r.next() for _ in range(period)] for _ in range(period)]


def value_noise(grid, period, x, y):
    """Smooth value noise with `period` cells across the 16-pixel tile."""
    fx, fy = x * period / N, y * period / N
    x0, y0 = int(math.floor(fx)), int(math.floor(fy))
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    def g(i, j):
        return grid[j % period][i % period]
    a = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * tx
    b = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * tx
    return a + (b - a) * ty


def fbm(seed, x, y, octaves=((2, 0.5), (4, 0.3), (8, 0.2))):
    return sum(w * value_noise(lattice(seed + i * 101, p), p, x, y)
               for i, (p, w) in enumerate(octaves))


def wrap_delta(a, b):
    d = (a - b) % N
    return d - N if d > N / 2 else d


def voronoi(points, x, y):
    """Nearest and second-nearest distances on the torus, plus the cell index."""
    best = [(1e9, -1), (1e9, -1)]
    for i, (px, py) in enumerate(points):
        dx, dy = wrap_delta(x, px), wrap_delta(y, py)
        d = math.hypot(dx, dy)
        if d < best[0][0]:
            best = [(d, i), best[0]]
        elif d < best[1][0]:
            best[1] = (d, i)
    return best[0][0], best[1][0], best[0][1]


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def ramp(stops, t):
    t = max(0.0, min(1.0, t))
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0) if t1 > t0 else 0.0)
    return stops[-1][1]


def clamp8(c):
    return tuple(max(0, min(255, int(round(v)))) for v in c)


def png(width, height, rows):
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in rows)
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


# ---------------------------------------------------------------- cold slag

def cold():
    flecks = {(3, 5): 1.0, (12, 2): 0.8, (8, 12): 1.0, (14, 9): 0.65, (5, 14): 0.55, (9, 7): 0.5}
    rows = []
    for y in range(N):
        row = []
        for x in range(N):
            warp = fbm(77, x, y, ((2, 0.6), (4, 0.4)))
            # Frozen flow: bands running diagonally, bent by the warp field.
            # Whole periods across the tile in each direction, so they tile.
            phase = 2 * math.pi * (2 * x + y) / N + 7.0 * warp
            band = 0.5 + 0.5 * math.sin(phase)
            ridge = max(0.0, band - 0.84) / 0.16          # thin glassy crest
            n = fbm(31, x, y)
            trough = max(0.0, 0.16 - band) / 0.16         # shadowed lee of each ripple
            v = 22 + 16 * n + 14 * band
            c = (v * 1.0, v * 0.97, v * 1.0)               # near-neutral black slag
            c = lerp(c, (78, 84, 98), 0.7 * ridge)         # cool glassy sheen on the crests
            c = lerp(c, (12, 11, 12), 0.5 * trough)
            pit = fbm(913, x, y, ((8, 1.0),))
            if pit < 0.22:                                  # gas bubbles frozen in
                c = lerp(c, (10, 10, 13), 0.7)
            f = flecks.get((x, y))
            if f:                                           # metallic inclusions
                c = lerp(c, (196, 190, 176), f)
            elif any(flecks.get(((x + dx) % N, (y + dy) % N)) for dx, dy in ((1, 0), (0, 1))):
                c = lerp(c, (110, 106, 100), 0.35)
            row.append(clamp8(c))
        rows.append(row)
    return rows


# ---------------------------------------------------------------- molten corium

HOT_RAMP = [(0.00, (34, 18, 14)), (0.30, (70, 24, 12)), (0.50, (168, 44, 8)),
            (0.68, (236, 104, 16)), (0.84, (255, 176, 40)), (1.00, (255, 240, 150))]


def crack_map():
    """Plate id per pixel, and each pixel's torus distance (in steps) to a crack.

    Cracks are the one-pixel boundary between Voronoi plates, so they are always
    connected lines, never the broken dots a thresholded distance field gives at
    this resolution.
    """
    r = Rng(4242)
    points = [(r.next() * N, r.next() * N) for _ in range(6)]
    cell = [[0] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            # A mild warp so plate edges wander instead of running ruler-straight.
            wx = x + 0.5 + 0.7 * (fbm(71, x, y, ((4, 1.0),)) - 0.5)
            wy = y + 0.5 + 0.7 * (fbm(72, x, y, ((4, 1.0),)) - 0.5)
            cell[y][x] = voronoi(points, wx, wy)[2]
    dist = [[99] * N for _ in range(N)]
    frontier = []
    for y in range(N):
        for x in range(N):
            if cell[y][x] != cell[y][(x + 1) % N] or cell[y][x] != cell[(y + 1) % N][x]:
                dist[y][x] = 0
                frontier.append((x, y))
    while frontier:
        nxt = []
        for x, y in frontier:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                u, v = (x + dx) % N, (y + dy) % N
                if dist[v][u] > dist[y][x] + 1:
                    dist[v][u] = dist[y][x] + 1
                    nxt.append((u, v))
        frontier = nxt
    return cell, dist


EMBERS = lattice(8080, N)


def hot_frame(frame, cells=crack_map()):
    cell, dist = cells
    t = 2 * math.pi * frame / HOT_FRAMES
    rows = []
    for y in range(N):
        row = []
        for x in range(N):
            d = dist[y][x]
            # Some crack segments run white-hot, others have skinned over red.
            vein = 0.62 + 0.5 * fbm(301, x, y, ((4, 0.7), (8, 0.3)))
            # Glow breathes along the cracks: a periodic field drifting round a
            # circle in its own phase space, so the last frame leads back into
            # the first.
            pulse = fbm(555, x + 3 * math.cos(t), y + 3 * math.sin(t), ((2, 0.6), (4, 0.4)))
            glow = vein * (0.82 + 0.3 * pulse)
            # Crust: dark, rough, each plate a slightly different shade.
            crust = 0.04 + fbm(19, x, y, ((4, 0.5), (8, 0.5))) * 0.20 + 0.05 * ((cell[y][x] * 37) % 5) / 4
            if d == 0:
                level = glow
            elif d == 1:
                level = max(crust, 0.25 + 0.10 * glow)     # plate rim lit from beneath
            elif EMBERS[y][x] < 0.05:
                level = 0.40 + 0.08 * pulse                 # pinhole through the crust
            else:
                level = crust
            c = ramp(HOT_RAMP, level)
            row.append(clamp8(c))
        rows.append(row)
    return rows


# ---------------------------------------------------------------- output

def tiled_preview(rows, tiles=3, scale=16):
    out = []
    for ty in range(tiles):
        for y in range(len(rows)):
            line = []
            for tx in range(tiles):
                for px in rows[y]:
                    line.extend([px] * scale)
            out.extend([line] * scale)
    return out


def main():
    check = "--check" in sys.argv
    cold_rows = cold()
    frames = [hot_frame(f) for f in range(HOT_FRAMES)]
    strip = [row for fr in frames for row in fr]
    mcmeta = ('{\n  "animation": {\n    "frametime": %d,\n    "interpolate": true\n  }\n}\n'
              % HOT_FRAMETIME).encode()
    outputs = {
        TEX / "corium_cold.png": png(N, N, cold_rows),
        TEX / "corium_hot.png": png(N, N * HOT_FRAMES, strip),
        TEX / "corium_hot.png.mcmeta": mcmeta,
    }
    for path, data in outputs.items():
        if check:
            assert path.is_file() and path.read_bytes() == data, "Stale corium texture: " + str(path)
        else:
            path.write_bytes(data)
    if check:
        print(len(outputs), "corium assets verified")
        return
    PREVIEW.mkdir(parents=True, exist_ok=True)
    for name, rows in (("cold_3x3", cold_rows), ("hot_3x3", frames[0])):
        p = tiled_preview(rows)
        (PREVIEW / (name + ".png")).write_bytes(png(len(p[0]), len(p), p))
    # Every animation frame side by side, 8x, to check the loop.
    strip_rows = [[px for fr in frames for px in fr[y] for _ in range(8)] for y in range(N) for _ in range(8)]
    (PREVIEW / "hot_frames.png").write_bytes(png(len(strip_rows[0]), len(strip_rows), strip_rows))
    print(len(outputs), "corium assets exported; previews in", PREVIEW)


if __name__ == "__main__":
    main()
