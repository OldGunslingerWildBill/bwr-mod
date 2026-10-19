"""Render inventory icons for panel component items from their exported panel models.

Run headless (never against an open scene):
    blender --background --factory-startup --python art/models/panel/icons/render_icons.py

Each model in mod/src/main/resources/assets/bwr/models/panel/<model>.json (the files the Blender family scripts
export) is rebuilt as a mesh with its own face colours, rendered head-on with a slight tilt on a transparent
background at 128 px, and shrunk to a 32 x 32 item texture in assets/bwr/textures/item/panel/<item>.png. A flat icon is
the cheapest thing an inventory can draw; the 3D model only appears on the panel itself. Glass windows are left out
so the dial shows; lit lamp lenses are drawn in their colour.
"""
import json
import math
import os
import sys

import bpy

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
MODELS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr", "models", "panel")
OUT = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "bwr", "textures", "item", "panel")
RENDER = 128
ICON = 32

# Item -> the panel model it shows by default (PanelStyles default rows; the recorders by make).
ICONS = {
    "dial_gauge": "ge_ab40",
    "edgewise_gauge_vertical": "ge_180_vertical",
    "edgewise_gauge_horizontal": "ge_180_horizontal",
    "control_switch": "ge_sb_target_pistol",
    "key_switch": "ge_sb_keyed",
    "rotary_selector": "ge_sb_standard_oval",
    "setpoint_knob": "knob_fluted",
    "lamp": "light_round",
    "pushbutton": "pb_flush",
    "paperless_recorder": "yokogawa_dx1000",
    "paperless_recorder_dx2000": "yokogawa_dx2000",
    "full_core_display": "fcd_tile_columbia",
    "rod_select_matrix": "rod_select_button",
    # The owner's CAD hardware (NEW-HARDWARE-SPEC.md): one model each.
    "bar_gauge": "bar_gauge",
    "rps_trip": "rps_trip_assembly",
    "push_turn_switch": "push_turn_switch",
}

LAMP_LIT = {"white": (0.95, 0.95, 0.85), "red": (0.88, 0.12, 0.12)}


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def colour(hex_rgb):
    r, g, b = (int(hex_rgb[i:i + 2], 16) / 255.0 for i in (0, 2, 4))
    return srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b)


def clear():
    bpy.ops.wm.read_factory_settings(use_empty=True)


# The curved bar gauge as BarGaugeFace lays it out (inches, the case model's frame: y down from its top, z out): the
# channel model's corner on the case, the arc's axis behind the panel and the face's radius.
CM = 1.0 / 2.54
BAR_CHANNEL = (2.52 * CM, 2.90 * CM)
BAR_ARC = (11.05 * CM, -14.0 * CM)
BAR_FACE_R = (math.sqrt(10.0 ** 2 + 14.0 ** 2) - 0.2) * CM


def bar_gauge_parts(channel):
    """The whole bar gauge in the case model's frame: the case model, the channel model on it with its pointer turned
    along the arc to 40 % of the narrow range scale, and the printed scale (bands, ticks, the numbers as dark dashes at
    this size) on the curved face, as the game draws them. Returns (parts, w, h)."""
    case = json.load(open(os.path.join(MODELS, "bar_gauge_case.json"), encoding="utf-8"))
    cx, cy = BAR_CHANNEL
    ay, az = BAR_ARC
    needle = next(p for p in channel["parts"] if p["name"] == "needle")
    corners = [v for f in needle["faces"] for v in f["v"]]
    low = (min(v[1] for v in corners) + max(v[1] for v in corners)) / 2 + cy
    travel = needle["travel"]
    tip = min(corners, key=lambda v: v[0])
    tip_r = math.hypot(tip[1] + cy - ay, tip[2] - az)
    rest = math.atan2(ay - (tip[1] + cy), tip[2] - az)

    def height(fraction):          # case y of a scale position, as seen head-on
        return low - travel * fraction

    def angle(y, r):
        return math.asin(max(-1.0, min(1.0, (ay - y) / r)))

    def point(x, a, r):
        return (x, ay - r * math.sin(a), az + r * math.cos(a))

    turn = angle(height(0.4), tip_r) - rest
    c, s = math.cos(turn), math.sin(turn)

    def turned(v):                 # about the arc's axis, positive up the face (y down here)
        dy, dz = v[1] + cy - ay, v[2] - az
        return (v[0] + cx, ay + c * dy - s * dz, az + s * dy + c * dz)

    parts = [dict(p) for p in case["parts"]]
    for p in channel["parts"]:
        move = turned if p["name"] == "needle" else (lambda v: (v[0] + cx, v[1] + cy, v[2]))
        parts.append(dict(p, faces=[dict(f, v=[move(v) for v in f["v"]]) for f in p["faces"]]))
    printed = []

    def strip(x0, x1, f0, f1, lift, rgb, pieces):
        r = BAR_FACE_R + lift
        a0, a1 = angle(height(f0), r), angle(height(f1), r)
        for i in range(pieces):
            b0, b1 = a0 + (a1 - a0) * i / pieces, a0 + (a1 - a0) * (i + 1) / pieces
            printed.append(([point(x0, b0, r), point(x1, b0, r), point(x1, b1, r), point(x0, b1, r)], rgb))

    right = cx
    for lo, hi, rgb in ((0, 10, "A80E0E"), (10, 20, "C4B02A"), (45, 55, "C4B02A"), (55, 60, "A80E0E")):
        strip(1.90 * CM, right, lo / 60.0, hi / 60.0, 0.01, rgb, 4)
    for v in range(0, 61, 5):
        f, d = v / 60.0, 0.06 / 60.0
        strip((1.80 if v % 10 == 0 else 2.12) * CM, right, f - d, f + d, 0.02, "141414", 1)
        if v % 10 == 0:
            strip(0.85 * CM, 1.65 * CM, f - 3 * d, f + 3 * d, 0.02, "3A3A3A", 1)
    parts.append({"role": "paint", "faces": [{"v": v, "c": rgb} for v, rgb in printed]})
    return parts, case["footprint"]["w"], case["footprint"]["h"]


def build(model):
    """The model as one mesh with a face-corner colour attribute; returns its size (w, h) in inches."""
    data = json.load(open(os.path.join(MODELS, model + ".json"), encoding="utf-8"))
    verts, faces, cols = [], [], []
    if model == "bar_gauge":
        parts, fw, fh = bar_gauge_parts(data)
        data["footprint"] = {"w": fw, "h": fh}
        data["parts"] = parts
    for part in data["parts"]:
        if part["role"] == "glass":
            continue
        for face in part["faces"]:
            base = len(verts)
            for x, y, z in face["v"]:
                # Model space: x right, y down, z out of the panel. Blender: X right, Z up, -Y towards the camera.
                verts.append((x, -z, -y))
            faces.append(tuple(range(base, base + len(face["v"]))))
            rgb = colour(face["c"])
            if part["role"] == "lamp" and part.get("tint") in LAMP_LIT:
                rgb = tuple(srgb_to_linear(c) for c in LAMP_LIT[part["tint"]])
            cols.append(rgb)
    mesh = bpy.data.meshes.new(model)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    attr = mesh.color_attributes.new("Col", "FLOAT_COLOR", "CORNER")
    for poly, rgb in zip(mesh.polygons, cols):
        for loop in poly.loop_indices:
            attr.data[loop].color = (*rgb, 1.0)
    obj = bpy.data.objects.new(model, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return data["footprint"]["w"], data["footprint"]["h"]


def render(model, path):
    clear()
    w, h = build(model)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    shading = scene.display.shading
    shading.light = "STUDIO"
    shading.color_type = "VERTEX"
    shading.show_cavity = False
    scene.render.film_transparent = True
    scene.render.resolution_x = RENDER
    scene.render.resolution_y = RENDER
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "Standard"
    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = max(w, h) * 1.08
    cam = bpy.data.objects.new("cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    # Head-on with a small tilt from above and the right, so knobs and bezels read as raised.
    cx, cz = w / 2.0, -h / 2.0
    tilt, turn = math.radians(12.0), math.radians(10.0)
    dist = 50.0
    cam.location = (cx + dist * math.sin(turn), -dist * math.cos(turn) * math.cos(tilt), cz + dist * math.sin(tilt))
    direction = (cx - cam.location[0], 0.0 - cam.location[1], cz - cam.location[2])
    from mathutils import Vector
    cam.rotation_euler = Vector(direction).to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def shrink(src, dst):
    """128 px render -> 32 px icon, averaging 4 x 4 blocks with alpha weighting (no colour fringes)."""
    img = bpy.data.images.load(src)
    w, h = img.size
    px = list(img.pixels)
    f = w // ICON
    out = [0.0] * (ICON * ICON * 4)
    for oy in range(ICON):
        for ox in range(ICON):
            r = g = b = a = 0.0
            for dy in range(f):
                for dx in range(f):
                    i = ((oy * f + dy) * w + (ox * f + dx)) * 4
                    al = px[i + 3]
                    r += px[i] * al
                    g += px[i + 1] * al
                    b += px[i + 2] * al
                    a += al
            o = (oy * ICON + ox) * 4
            n = f * f
            if a > 0:
                out[o:o + 4] = [r / a, g / a, b / a, a / n]
    small = bpy.data.images.new("icon", ICON, ICON, alpha=True)
    small.pixels = out
    small.filepath_raw = dst
    small.file_format = "PNG"
    small.save()


def main():
    only = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    os.makedirs(OUT, exist_ok=True)
    tmp = os.path.join(ROOT, "tmp", "panel-icons")
    os.makedirs(tmp, exist_ok=True)
    for item, model in ICONS.items():
        if only and item not in only:
            continue
        big = os.path.join(tmp, item + "_128.png")
        render(model, big)
        shrink(big, os.path.join(OUT, item + ".png"))
        print("ICON", item, "<-", model)


main()
