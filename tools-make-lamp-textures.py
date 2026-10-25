#!/usr/bin/env python3
"""Write the lens textures of the industrial lamps: colour PNGs, and labPBR `_s` specular maps for the lit lenses.

    python tools-make-lamp-textures.py

Per lamp type (lps, hps, mercury) it writes into mod/src/main/resources/assets/bwr/textures/block/lamp/:
  <type>_lens_on.png     the lit lens: the colour of the lamp (the same colour the light is meant to have)
  <type>_lens_w1.png     warm-up stage 2: the same lens, a little paler towards red-pink
  <type>_lens_w0.png     warm-up stage 1: the lens pushed further towards red-pink (sodium starts red)
  <type>_lens_<stage>_s.png  labPBR specular maps for the three lit stages: R perceptual smoothness, G F0 (0: not
                         metal), B porosity (0), A emission (on 220, w1 180, w0 120; labPBR reads 255 as no emission and
                         0 to 254 as the level)
  <type>_lens_off.png    the unlit lens: dark glass, no `_s` map, so nothing glows
The lit lens models are fullbright through emissive_ambient (the light level comes from the material's Ka, see
build_lamps.py), so these colours are what the player sees at full light.
Standard library only. Re-run after changing the colours; the lens colours are also written in LIGHTING.md.
"""
import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr", "textures", "block", "lamp")
SIZE = 16

# Lamp colours: LPS deep orange-yellow (589 nm), HPS golden-white, mercury cold bluish-white with a green tint.
COLOURS = {
    "lps": (255, 140, 0),      # deep orange (589 nm)
    "hps": (255, 200, 90),     # golden
    "mercury": (170, 220, 255),  # blue-white
}
RED_PINK = (255, 60, 80)
OFF_GLASS = (46, 52, 58)
EMISSION = {"on": 220, "w1": 180, "w0": 120}
SMOOTHNESS = 230
# The first warm-up stage is pushed towards red-pink, the second is paler towards the lamp colour.
STAGE_MIX = {"on": 0.0, "w1": 0.2, "w0": 0.5}


def png(path, width, height, pixels):
    """pixels: list of rows, each a list of (r, g, b, a) tuples."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n"
    data += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += chunk(b"IEND", b"")
    with open(path, "wb") as fh:
        fh.write(data)


def lens_factor(x, y):
    """Lens shading: brighter at the centre, a fine ring pattern (the prismatic lens), darker frame at the edges."""
    cx = cy = (SIZE - 1) / 2.0
    d = math.hypot(x - cx, y - cy) / (SIZE / 2.0)
    centre = 1.0 - 0.14 * min(d, 1.0)
    rings = 0.96 + 0.04 * math.cos(2 * math.pi * d * 3.0)
    frame = 0.78 if x in (0, SIZE - 1) or y in (0, SIZE - 1) else 1.0
    return centre * rings * frame


def stage_colour(colour, stage):
    mix = STAGE_MIX[stage]
    return tuple(int(round(c * (1 - mix) + r * mix)) for c, r in zip(colour, RED_PINK))


def lit_lens(colour):
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            f = lens_factor(x, y)
            row.append(tuple(min(255, int(round(c * f))) for c in colour) + (255,))
        rows.append(row)
    return rows


def lit_specular(stage):
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            edge = x in (0, SIZE - 1) or y in (0, SIZE - 1)
            row.append((SMOOTHNESS, 0, 0, EMISSION[stage] - (40 if edge else 0)))
        rows.append(row)
    return rows


def unlit_lens():
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            f = lens_factor(x, y)
            row.append(tuple(min(255, int(round(c * f))) for c in OFF_GLASS) + (255,))
        rows.append(row)
    return rows


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, colour in COLOURS.items():
        for stage in ("on", "w1", "w0"):
            png(os.path.join(OUT, "%s_lens_%s.png" % (name, stage)), SIZE, SIZE,
                lit_lens(stage_colour(colour, stage)))
            png(os.path.join(OUT, "%s_lens_%s_s.png" % (name, stage)), SIZE, SIZE, lit_specular(stage))
        png(os.path.join(OUT, "%s_lens_off.png" % name), SIZE, SIZE, unlit_lens())
        print("%s: lens colour %s" % (name, colour))


if __name__ == "__main__":
    main()
