# Usage: python render_turbines.py PATH_TO_BWR_MOD
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
BASE = ROOT / "mod/src/main/resources"
MODELS = BASE / "assets/bwr/models/block/turbines"
W, H = 900, 760


def font(size):
    try:
        return ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", size)
    except OSError:
        return ImageFont.load_default()


def load_model(name):
    meta = json.loads(
        (BASE / f"data/bwr/turbine_models/{name}.json").read_text()
    )
    folder = MODELS / name
    palette = {}
    for path in folder.glob("*.mtl"):
        material = None
        for line in path.read_text().splitlines():
            s = line.split()
            if not s:
                continue
            if s[0] == "newmtl":
                material = " ".join(s[1:])
            elif s[0] == "Kd":
                palette[material] = np.array(list(map(float, s[1:4])))

    triangles, colors = [], []
    width, depth = meta["width"], meta["depth"]
    for path in sorted(folder.glob("cell_*.obj")):
        index = int(path.stem.split("_")[-1])
        offset = np.array([
            index % width,
            index // (width * depth),
            (index // width) % depth,
        ])
        vertices = []
        color = np.array([.65, .68, .72])
        for line in path.read_text().splitlines():
            s = line.split()
            if not s:
                continue
            if s[0] == "v":
                vertices.append(np.array(list(map(float, s[1:4]))) + offset)
            elif s[0] == "usemtl":
                color = palette.get(" ".join(s[1:]), np.array([.65, .68, .72]))
            elif s[0] == "f":
                indices = [int(v.split("/")[0]) for v in s[1:]]
                points = [
                    vertices[i - 1 if i > 0 else i] for i in indices
                ]
                for j in range(1, len(points) - 1):
                    triangles.append([points[0], points[j], points[j + 1]])
                    colors.append(color.copy())
    return meta, np.asarray(triangles), np.asarray(colors)


def unit(v):
    v = np.asarray(v, dtype=float)
    return v / np.linalg.norm(v)


def render(meta, triangles, colors, azimuth):
    az, el = np.radians([azimuth, 25])
    toward_eye = np.array([
        math.cos(az) * math.cos(el),
        math.sin(el),
        math.sin(az) * math.cos(el),
    ])
    right = unit(np.cross([0, 1, 0], toward_eye))
    up = unit(np.cross(toward_eye, right))
    camera = np.column_stack((right, up, toward_eye))

    projected = triangles @ camera
    xy = projected[:, :, :2].reshape(-1, 2)
    low, high = xy.min(axis=0), xy.max(axis=0)
    scale = min((W - 110) / (high[0] - low[0]),
                (H - 160) / (high[1] - low[1]))
    middle = (low + high) / 2

    def screen(points):
        q = np.asarray(points) @ camera
        q[..., 0] = (q[..., 0] - middle[0]) * scale + W / 2
        q[..., 1] = -(q[..., 1] - middle[1]) * scale + H / 2 + 18
        return q

    screen_triangles = screen(triangles)
    rgb = np.full((H, W, 3), [237, 242, 246], dtype=np.uint8)
    zbuffer = np.full((H, W), -np.inf)
    light = unit([-.5, 1, .7])
    fill = unit([1, .5, -.7])

    for world, tri, color in zip(triangles, screen_triangles, colors):
        normal = np.cross(world[1] - world[0], world[2] - world[0])
        length = np.linalg.norm(normal)
        if length < 1e-12:
            continue
        normal /= length
        shade = (.38 + .47 * max(0., normal @ light)
                 + .15 * max(0., normal @ fill))
        pixel_color = np.clip(color * shade * 255, 0, 255).astype(np.uint8)

        x0 = max(0, int(np.floor(tri[:, 0].min())))
        x1 = min(W - 1, int(np.ceil(tri[:, 0].max())))
        y0 = max(0, int(np.floor(tri[:, 1].min())))
        y1 = min(H - 1, int(np.ceil(tri[:, 1].max())))
        if x0 > x1 or y0 > y1:
            continue

        a, b, c = tri
        denominator = ((b[1] - c[1]) * (a[0] - c[0])
                       + (c[0] - b[0]) * (a[1] - c[1]))
        if abs(denominator) < 1e-10:
            continue

        yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        xx, yy = xx + .5, yy + .5
        wa = ((b[1] - c[1]) * (xx - c[0])
              + (c[0] - b[0]) * (yy - c[1])) / denominator
        wb = ((c[1] - a[1]) * (xx - c[0])
              + (a[0] - c[0]) * (yy - c[1])) / denominator
        wc = 1 - wa - wb
        z = wa * a[2] + wb * b[2] + wc * c[2]
        local_depth = zbuffer[y0:y1 + 1, x0:x1 + 1]
        visible = ((wa >= -1e-9) & (wb >= -1e-9) & (wc >= -1e-9)
                   & (z > local_depth))
        local_depth[visible] = z[visible]
        rgb[y0:y1 + 1, x0:x1 + 1][visible] = pixel_color

    image = Image.fromarray(rgb)
    draw = ImageDraw.Draw(image)
    draw.text((25, 18), meta["id"].upper(), font=font(27), fill="#172d40")
    draw.text(
        (25, 56),
        f"{meta['width']} × {meta['height']} × {meta['depth']} cells"
        f"  |  azimuth {azimuth}°",
        font=font(17), fill="#506475",
    )

    # Mark only endpoints that are visible from this camera.
    for port in meta.get("ports", []):
        if "face_center" not in port:
            continue
        x, y, z = screen(port["face_center"])
        ix, iy = int(round(x)), int(round(y))
        if 0 <= ix < W and 0 <= iy < H and z >= zbuffer[iy, ix] - .035:
            draw.ellipse((x-4, y-4, x+4, y+4), fill="#ef741b")
            draw.text((x+7, y-10), f"{port['cell']} {port['face']}",
                      font=font(14), fill="#9b3c0a")
    draw.text((25, H-35), "Estimated source scale · 1 block = 1 metre",
              font=font(16), fill="#506475")
    return image


canvas = Image.new("RGB", (W * 2, H * 2), "#edf2f6")
for row, name in enumerate(("rcic_twl", "hpci_turbine")):
    meta, triangles, colors = load_model(name)
    print(f"{name}: {len(triangles):,} triangles", flush=True)
    for col, azimuth in enumerate((45, 225)):
        canvas.paste(render(meta, triangles, colors, azimuth),
                     (col * W, row * H))

output = ROOT / "tmp/rcic-hpci/preview.png"
output.parent.mkdir(parents=True, exist_ok=True)
canvas.save(output)
print(output)
