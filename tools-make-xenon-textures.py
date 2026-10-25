"""Writes the xenon floodlight lens textures (standard library only; no Pillow).

    python tools-make-xenon-textures.py

Writes mod/src/main/resources/assets/bwr/textures/block/:
  xenon_lens.png      the lit lens: cold white, about 6000 K (a warm-neutral centre, a faint blue edge)
  xenon_lens_off.png  the dark lens: the same glass without the light
  xenon_lens_s.png    labPBR specular map for the lit lens: R perceptual smoothness, G metalness (0), B porosity (0),
                      A emission (254 = full; labPBR reads 255 as no emission, so the lit maps use 254); the shaders read it
  xenon_lens_off_s.png the same map for the dark lens, with no emission (A = 0)
  hps_panel_lens.png  the lit flat lens of the high-pressure sodium panel: golden white (#FFCE80, the HPS lamps' colour)
                      behind a prismatic grid
  hps_panel_lens_off.png, hps_panel_lens_s.png, hps_panel_lens_off_s.png  the dark lens and the two labPBR maps
"""
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "mod" / "src" / "main" / "resources" / "assets" / "bwr" / "textures" / "block"
SIZE = 16


def png(path, pixels):
    """pixels: rows of (r, g, b, a) tuples, SIZE x SIZE."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def lens_pixel(t, lit):
    # t: distance from the centre, 0 at the centre, 1 at the rim of the lens disc
    if lit:
        centre = (255, 253, 250)  # about 6000 K, near white: the lit lens is drawn full bright
        edge = (226, 236, 255)
    else:
        centre = (92, 100, 112)
        edge = (70, 78, 90)
    mix = min(max(t, 0.0), 1.0)
    return tuple(int(round(c + (e - c) * mix)) for c, e in zip(centre, edge))


def build(lit):
    image = []
    specular = []
    for y in range(SIZE):
        row = []
        srow = []
        for x in range(SIZE):
            dx = (x + 0.5) / SIZE * 2 - 1
            dy = (y + 0.5) / SIZE * 2 - 1
            t = (dx * dx + dy * dy) ** 0.5
            r, g, b = lens_pixel(t, lit)
            row.append((r, g, b, 255))
            # labPBR: smoothness 235 (glass), metalness 0, porosity 0, emission in alpha
            srow.append((235, 0, 0, 254 if lit else 0))
        image.append(row)
        specular.append(srow)
    return image, specular


def panel(lit):
    """The HPS flat panel's lens: a prismatic grid (a darker line every 4 pixels) over golden white, a little hotter in the
    middle where the arc tube sits."""
    image = []
    specular = []
    for y in range(SIZE):
        row = []
        srow = []
        for x in range(SIZE):
            dx = (x + 0.5) / SIZE * 2 - 1
            dy = (y + 0.5) / SIZE * 2 - 1
            hot = max(0.0, 1.0 - (dx * dx * 0.6 + dy * dy * 1.6))
            grid = x % 4 == 0 or y % 4 == 0
            if lit:
                base = (255, 206, 128)  # #FFCE80
                core = (255, 238, 196)
                c = [int(round(b + (k - b) * hot)) for b, k in zip(base, core)]
                if grid:
                    c = [int(v * 0.86) for v in c]
            else:
                c = [96, 90, 78] if not grid else [78, 73, 64]
            row.append((c[0], c[1], c[2], 255))
            srow.append((220, 0, 0, (254 if not grid else 230) if lit else 0))
        image.append(row)
        specular.append(srow)
    return image, specular


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for lit, name in ((True, "xenon_lens"), (False, "xenon_lens_off")):
        image, specular = build(lit)
        png(OUT / f"{name}.png", image)
        png(OUT / f"{name}_s.png", specular)
        print("wrote", OUT / f"{name}.png", "and", OUT / f"{name}_s.png")
    for lit, name in ((True, "hps_panel_lens"), (False, "hps_panel_lens_off")):
        image, specular = panel(lit)
        png(OUT / f"{name}.png", image)
        png(OUT / f"{name}_s.png", specular)
        print("wrote", OUT / f"{name}.png", "and", OUT / f"{name}_s.png")


if __name__ == "__main__":
    main()
