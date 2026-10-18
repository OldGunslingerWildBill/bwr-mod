"""Imports the owner's CAD models (OBJ, centimetres) into panel component models.

    blender --background --factory-startup --python art/models/panel/imported/build.py
    blender ... --python art/models/panel/imported/build.py -- --only button_1 --no-preview

Source files are in art/models/panel/imported/obj/ (exported from Autodesk Fusion: +X right, +Y out of the panel,
+Z up, centimetres; one OBJ group per part, moving parts in their own group). There are no .mtl files, so colours
come from the material names. Each model is turned into the panel_export convention (inches, +Z out of the face,
+Y up, footprint top-left at the origin), geometry behind the panel is dropped, the dense CAD meshes are reduced to
the face budget (planar dissolve first, then collapse), parts are tagged with their roles, pivots and travel, legend
anchors are added, and the model is exported to mod/src/main/resources/assets/bwr/models/panel/<id>.json.
"""
import math
import os
import sys

import bmesh
import bpy

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import panel_export as px  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OBJ_DIR = os.path.join(HERE, "obj")
PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "imported")
CM = 1.0 / 2.54

# Material name -> sRGB colour (the CAD library names say what they are).
COLOURS = [
    ("Translucent_Glossy_(Green)", "3FCF6A"),
    ("Glossy_(Green)", "1E9E44"),
    ("Glossy_(Red)", "D42020"),
    ("Matte_(Red)", "B01818"),
    ("Matte_(Black)", "1A1A1A"),
    ("Matte_Black", "1A1A1A"),
    ("Enamel_Glossy_(Black)", "141414"),
    ("Anodized_Rough_(Grey)", "6E7278"),
    ("Bead_Blasted", "B8BCC0"),
    ("Steel_-_Satin", "C8CCD0"),
    ("Metal_White", "E8E8E4"),
    ("Metal_Gray_Rough", "7A7E82"),
    ("Plastic_Glossy_Matte", "202020"),
    ("Tough_2000", "2A2A2A"),
]


def colour_for(name):
    for key, hex_rgb in COLOURS:
        if key.lower() in name.lower():
            return hex_rgb
    print("WARNING: no colour for material", name)
    return "808080"


def import_obj(path):
    """Imports one OBJ split by group; returns {group name: object} in panel_export space (inches, +Z out, +Y up)."""
    before = set(bpy.data.objects)
    bpy.ops.wm.obj_import(filepath=path, forward_axis="NEGATIVE_Z", up_axis="Y",
                          use_split_objects=True, use_split_groups=True)
    objs = [o for o in bpy.data.objects if o not in before and o.type == "MESH"]
    out = {}
    for o in objs:
        # Default import gives Blender (x, -z_obj, y_obj); we want (x, z_obj, y_obj) in inches.
        mesh = o.data
        for v in mesh.vertices:
            x, y, z = o.matrix_world @ v.co
            v.co = (x * CM, -y * CM, z * CM)
        o.matrix_world.identity()
        # The axis swap above is a reflection, which turns every face inside out (the exported normals pointed into
        # the panel and the game lit the models from behind): turn them back to face outward.
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.reverse_faces(bm, faces=bm.faces[:])
        bm.to_mesh(mesh)
        bm.free()
        for slot in o.material_slots:
            if slot.material is not None:
                hex_rgb = colour_for(slot.material.name)
                mat = px.material("mat_" + hex_rgb, hex_rgb)
                slot.material = mat
        name = o.name.split(".")[0]
        for prefix in ("bar-gauge_", "button-1_", "RPS-Trip-Assembly_", "rot-swt-open-close_", "rot-swt-open-auto-close_",
                       "WL-2 B_", "WL-2_B_"):
            if name.startswith(prefix):
                name = name[len(prefix):]
        out[name] = o
    return out


def bounds(objs):
    xs, ys, zs = [], [], []
    for o in objs:
        for v in o.data.vertices:
            xs.append(v.co.x)
            ys.append(v.co.y)
            zs.append(v.co.z)
    return min(xs), max(xs), min(ys), max(ys), min(zs), max(zs)


def shift(objs, dx, dy, dz):
    for o in objs:
        for v in o.data.vertices:
            v.co.x += dx
            v.co.y += dy
            v.co.z += dz


def reduce(obj, target):
    """Planar dissolve (CAD meshes are mostly flat runs of thin triangles), then collapse down to target faces."""
    bpy.context.view_layer.objects.active = obj
    if len(obj.data.polygons) > target:
        m = obj.modifiers.new("dissolve", "DECIMATE")
        m.decimate_type = "DISSOLVE"
        m.angle_limit = math.radians(4.0)
        bpy.ops.object.modifier_apply(modifier=m.name)
    # The game takes triangles and quads; split anything bigger first so the count is what ships, then collapse
    # (a few rounds, since collapsing works on triangles and lands near, not on, the target).
    for _ in range(4):
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        bmesh.ops.triangulate(bm, faces=[f for f in bm.faces if len(f.verts) > 4])
        bm.to_mesh(obj.data)
        bm.free()
        if len(obj.data.polygons) <= target:
            break
        m = obj.modifiers.new("collapse", "DECIMATE")
        m.decimate_type = "COLLAPSE"
        m.ratio = max(0.02, 0.95 * target / len(obj.data.polygons))
        bpy.ops.object.modifier_apply(modifier=m.name)
    return len(obj.data.polygons)


def drop_behind(obj):
    """Deletes faces that are wholly behind the panel face and clamps the rest to it (the game never sees them)."""
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    gone = [f for f in bm.faces if all(v.co.z < 0.0 for v in f.verts)]
    bmesh.ops.delete(bm, geom=gone, context="FACES")
    for v in bm.verts:
        if v.co.z < -0.04:
            v.co.z = -0.04
    bm.to_mesh(obj.data)
    bm.free()


def turn_half(obj, pivot):
    """Turns a part 180 degrees about the panel normal through pivot: the game turns handles from an upright pose."""
    for v in obj.data.vertices:
        v.co.x = 2.0 * pivot[0] - v.co.x
        v.co.y = 2.0 * pivot[1] - v.co.y


def surface_z(obj, x, y, r=0.12):
    """The height of a part's front around (x, y), so a legend anchor sits on the plate, not under it."""
    from mathutils import Vector
    bpy.context.view_layer.update()
    hit, where, _, _ = obj.ray_cast(Vector((x, y, 20.0)), Vector((0.0, 0.0, -1.0)))
    if hit:
        return round(where.z + 0.01, 4)
    near = [v.co.z for v in obj.data.vertices if (v.co.x - x) ** 2 + (v.co.y - y) ** 2 <= r * r]
    return round(max(near) + 0.01, 4) if near else 0.0


def merge(objs, name):
    """Joins objects into one (same part)."""
    objs = [o for o in objs if o is not None]
    if len(objs) == 1:
        objs[0].name = name
        return objs[0]
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    objs[0].name = name
    return objs[0]


# ---------------------------------------------------------------- models

def prepare(file_name, margin=0.04):
    """Imports a file and moves its footprint's top-left corner to the origin.

    Returns (parts, w, h, ox, oy): (ox, oy) is where the OBJ's own origin landed, the turning centre of a handle.
    """
    px.clear_scene()
    parts = import_obj(os.path.join(OBJ_DIR, file_name))
    for o in parts.values():
        drop_behind(o)
    parts = {k: o for k, o in parts.items() if len(o.data.polygons) > 0}
    x0, x1, y0, y1, z0, _ = bounds(parts.values())
    shift(parts.values(), -x0 + margin, -y1 - margin, max(0.0, -z0))
    w = round(x1 - x0 + 2 * margin, 4)
    h = round(y1 - y0 + 2 * margin, 4)
    return parts, w, h, margin - x0, -y1 - margin


def build_button_1():
    """The owner's pushbutton: a bead-blasted bezel, a green cap (Movable) with a translucent lens on top."""
    parts, w, h, ox, oy = prepare("button-1.obj")
    reduce(parts["Body"], 260)
    reduce(parts["Body3"], 60)
    body = merge([parts["Body"], parts["Body3"]], "body")
    px.tag(body, part="body")
    cap = merge([parts["Movable"], parts.get("Body4")], "cap")
    reduce(cap, 220)
    # Recoloured by the component's colour like every pushbutton cap (default green, as modelled).
    px.tag(cap, part="cap", tint="green", travel=0.15, axis=(0, 0, -1))
    # On the bezel's top band, inside the footprint, like the other pushbuttons' legends.
    px.label("legend", w / 2, -0.22, surface_z(body, w / 2, -0.22), 0.15)
    return "button_1", w, h, "Owner's CAD model button-1.obj (Fusion), 5 cm bezel", 700


def build_rot_switch(file_name, model_id, positions):
    """The owner's SRV control switch: a grey plate and a turning handle; OPEN/CLOSE or OPEN/AUTO/CLOSE."""
    parts, w, h, ox, oy = prepare(file_name)
    reduce(parts["Body"], 320)
    px.tag(parts["Body"], part="body")
    handle = parts["Handle"]
    reduce(handle, 300)
    # The handle turns about its own centre: the OBJ origin, which prepare() moved to (ox, oy). It is modelled
    # pointing down at the legends engraved on the plate; the game turns handles from upright, so it is turned over
    # and the position anchors sit on the engraved legends: the handle swings down to point at each.
    pivot = (ox, oy, 0.0)
    turn_half(handle, pivot)
    px.tag(handle, part="handle", pivot=pivot, axis=(0, 0, 1))
    legend_y = pivot[1] - 2.0 * CM
    xs = {2: [-1.15, 1.15], 3: [-1.25, 0.0, 1.25]}[positions]
    for i, dx in enumerate(xs):
        x = pivot[0] + dx * CM
        px.label("pos%d" % (i + 1), x, legend_y, surface_z(parts["Body"], x, legend_y), 0.11)
    return model_id, w, h, "Owner's CAD model %s (Fusion)" % file_name, 700


def build_wl2():
    """The owner's WL-2 selector: white plate, a bar handle with a pointer cap; channel or mode selection."""
    parts, w, h, ox, oy = prepare("WL-2 B.obj")
    reduce(parts["Body"], 120)
    px.tag(parts["Body"], part="body")
    handle = merge([parts["Handle"], parts.get("Body5")], "handle")
    reduce(handle, 420)
    pivot = (ox, oy, 0.0)
    turn_half(handle, pivot)  # its pointer tip is modelled at the bottom; the game turns handles from upright
    px.tag(handle, part="handle", pivot=pivot, axis=(0, 0, 1))
    radius = 2.6 * CM
    for i, a in enumerate([-90.0, -45.0, 0.0, 45.0, 90.0]):
        t = math.radians(a)
        x, y = pivot[0] + radius * math.sin(t), pivot[1] + radius * math.cos(t)
        px.label("pos%d" % (i + 1), x, y, surface_z(parts["Body"], x, y), 0.13)
    px.label("caption", w / 2, -h + 0.16, surface_z(parts["Body"], w / 2, -h + 0.16), 0.13)
    return "wl2_selector", w, h, "Owner's CAD model WL-2 B.obj (Fusion)", 700


def build_bar_gauge():
    """The owner's edgewise bar gauge: a black case with a satin bezel and a pointer that slides along the scale."""
    parts, w, h, ox, oy = prepare("bar-gauge.obj")
    px.tag(parts["body"], part="body")
    pointer = parts["pointer"]
    # Modelled at mid-scale; park it at the bottom of the scale and let it travel the scale length upward.
    travel = 16.0 * CM
    shift([pointer], 0.0, -travel / 2, 0.0)
    px.tag(pointer, part="needle", role="needle", travel=travel, axis=(0, 1, 0))
    return "bar_gauge", w, h, "Owner's CAD model bar-gauge.obj (Fusion), 5 x 20 cm", 400


def build_rps_trip():
    """The owner's RPS trip assembly: a black body with red guards and two red trip buttons, A and B."""
    parts, w, h, ox, oy = prepare("RPS-Trip-Assembly.obj")
    reduce(parts["Body"], 600)
    px.tag(parts["Body"], part="body")
    for side, key in (("a", "RPS A Movable"), ("b", "RPS B Movable")):
        cap = parts[key]
        reduce(cap, 200)
        px.tag(cap, part="cap_" + side, travel=0.15, axis=(0, 0, -1))
    for side, dx in (("a", -3.0), ("b", 3.0)):
        x = ox + dx * CM
        # Above the button on the plain top band: below it the CAD has small raised plates with its own engraved text.
        px.label("legend_" + side, x, -0.42, surface_z(parts["Body"], x, -0.42), 0.18)
    return "rps_trip_assembly", w, h, "Owner's CAD model RPS-Trip-Assembly.obj (Fusion)", 1100


BUILDERS = {
    "button_1": build_button_1,
    "srv_open_close": lambda: build_rot_switch("rot-swt-open-close.obj", "srv_open_close", 2),
    "srv_open_auto_close": lambda: build_rot_switch("rot-swt-open-auto-close.obj", "srv_open_auto_close", 3),
    "wl2_selector": build_wl2,
    "bar_gauge": build_bar_gauge,
    "rps_trip_assembly": build_rps_trip,
}


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = args[args.index("--only") + 1].split(",") if "--only" in args else list(BUILDERS)
    for key in only:
        model_id, w, h, source, budget = BUILDERS[key]()
        px.export(model_id, w, h, source, max_faces=budget)
        if "--no-preview" not in args:
            os.makedirs(PREVIEW_DIR, exist_ok=True)
            px.preview(os.path.join(PREVIEW_DIR, model_id), w, h)


main()
