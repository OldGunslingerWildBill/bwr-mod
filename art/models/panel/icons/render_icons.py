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
}

LAMP_LIT = {"white": (0.95, 0.95, 0.85), "red": (0.88, 0.12, 0.12)}


def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def colour(hex_rgb):
    r, g, b = (int(hex_rgb[i:i + 2], 16) / 255.0 for i in (0, 2, 4))
    return srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b)


def clear():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def build(model):
    """The model as one mesh with a face-corner colour attribute; returns its size (w, h) in inches."""
    data = json.load(open(os.path.join(MODELS, model + ".json"), encoding="utf-8"))
    verts, faces, cols = [], [], []
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
