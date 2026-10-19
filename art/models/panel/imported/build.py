"""Imports the owner's CAD models (OBJ, centimetres) into panel component models.

    blender --background --factory-startup --python art/models/panel/imported/build.py
    blender ... --python art/models/panel/imported/build.py -- --only button_1 --no-preview

Source files are in art/models/panel/imported/obj/ (exported from Autodesk Fusion: +X right, +Y out of the panel,
+Z up, centimetres; one OBJ group per part, moving parts in their own group). There are no .mtl files, so colours
come from the material names. Each model is turned into the panel_export convention (inches, +Z out of the face,
+Y up, footprint top-left at the origin), geometry behind the panel is dropped, the dense CAD meshes are reduced to
the face budget (planar dissolve first, then collapse), parts are tagged with their roles, pivots and travel, legend
anchors are added, and the model is exported to mod/src/main/resources/assets/bwr/models/panel/<id>.json.

The SRV switches, the RPS trip assembly and the bar gauge are not decimated: they are rebuilt as clean solids from
the dimensions measured on their OBJ files (see "rebuilt solids" below); the bar gauge, two models (its curved case,
and the channel with the pointer), is laid out like the owner's simulator picture.
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


# ---------------------------------------------------------------- rebuilt solids
#
# The bar gauge, the SRV switches and the RPS trip assembly are rebuilt as clean low-poly solids from dimensions
# measured on the owner's OBJ files instead of being imported and decimated: the planar dissolve merged the CAD's
# finely faceted cylinders into twisted n-gons (the SRV knobs came out as grey mushrooms, the RPS cup guards
# collapsed) and the engraved text decimated into noise (the game prints legends at the label anchors instead).
#
# Orientation: Fusion writes these files Y-up with the part lying on its back, so the panel's up is -Z of the OBJ:
# the engraved text reads right way round with right = +X, up = -Z, out = +Y, a proper rotation. import_obj() maps
# (x, z, y), a mirror image, which turned these models upside down (the SRV legend tab under the knob, the RPS label
# pads under the guards, the text upside down). The dimensions here are in the correct frame: centimetres, x right,
# y up, z out of the panel, origin at the OBJ origin (the handle's shaft).


def obj_bounds(file_name):
    """Per-group bounds of an OBJ in that frame: {group: ((x0, x1), (y0, y1), (z0, z1))}, centimetres."""
    verts, groups, cur = [], {}, None
    with open(os.path.join(OBJ_DIR, file_name), encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("v "):
                x, y, z = (float(t) for t in line.split()[1:4])
                verts.append((x, -z, y))
            elif line.startswith("g "):
                cur = line[2:].strip()
            elif line.startswith("f "):
                groups.setdefault(cur, set()).update(int(t.split("/")[0]) - 1 for t in line.split()[1:])
    return {g: tuple((min(verts[i][k] for i in idx), max(verts[i][k] for i in idx)) for k in range(3))
            for g, idx in groups.items()}


def expect(bounds, group, want, file_name, tol=0.02):
    """Stops the build when the OBJ no longer has the dimensions its rebuild was measured from."""
    got = bounds[group]
    for k, axis in enumerate("xyz"):
        if abs(got[k][0] - want[k][0]) > tol or abs(got[k][1] - want[k][1]) > tol:
            raise ValueError("%s %s: %s %s is not the measured %s; re-measure the rebuild"
                             % (file_name, group, axis, tuple(round(v, 3) for v in got[k]), want[k]))


def newell(pts):
    nx = ny = nz = 0.0
    for i, (x0, y0, z0) in enumerate(pts):
        x1, y1, z1 = pts[(i + 1) % len(pts)]
        nx += (y0 - y1) * (z0 + z1)
        ny += (z0 - z1) * (x0 + x1)
        nz += (x0 - x1) * (y0 + y1)
    return nx, ny, nz


class Solid:
    """The faces of one part, authored in the rebuild frame."""

    def __init__(self):
        self.faces = []

    def face(self, pts, colour, outward=None):
        """One planar face (3 or more corners); wound so its normal agrees with the direction outward."""
        if outward is not None:
            n = newell(pts)
            if n[0] * outward[0] + n[1] * outward[1] + n[2] * outward[2] < 0:
                pts = pts[::-1]
        self.faces.append((list(pts), colour))


def lathe(solid, cx, cy, profile, segments, colours):
    """A surface of revolution about the panel normal through (cx, cy). profile: (r, z) points walked with the solid
    on the left (up an outer wall, inward over a top, down a bore); colours: one per profile segment, or one."""
    if isinstance(colours, str):
        colours = [colours] * (len(profile) - 1)
    for i in range(len(profile) - 1):
        (ra, za), (rb, zb) = profile[i], profile[i + 1]
        nr, nz = zb - za, ra - rb
        for k in range(segments):
            t0, t1 = 2 * math.pi * k / segments, 2 * math.pi * (k + 1) / segments
            tm = (t0 + t1) / 2
            pts = []
            for r, z, t in ((ra, za, t0), (ra, za, t1), (rb, zb, t1), (rb, zb, t0)):
                p = (cx + r * math.cos(t), cy + r * math.sin(t), z)
                if not pts or max(abs(p[j] - pts[-1][j]) for j in range(3)) > 1e-7:
                    pts.append(p)
            if len(pts) > 3 and max(abs(pts[0][j] - pts[-1][j]) for j in range(3)) < 1e-7:
                pts.pop()
            if len(pts) >= 3:
                solid.face(pts, colours[i], (nr * math.cos(tm), nr * math.sin(tm), nz))


def prism(solid, outline, z0, z1, top, side):
    """An upright prism over a counter-clockwise outline [(x, y)]; no top face when top is None."""
    if top is not None:
        solid.face([(x, y, z1) for x, y in outline], top, (0, 0, 1))
    for i in range(len(outline)):
        (xa, ya), (xb, yb) = outline[i], outline[(i + 1) % len(outline)]
        solid.face([(xa, ya, z0), (xb, yb, z0), (xb, yb, z1), (xa, ya, z1)], side, (yb - ya, xa - xb, 0))


def flat(solid, outline, z, colour):
    solid.face([(x, y, z) for x, y in outline], colour, (0, 0, 1))


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def arc(cx, cy, r, a0, a1, steps):
    """Points on a circle from a0 to a1 degrees (counter-clockwise when a1 > a0), both ends included."""
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]


class Frame:
    """The rebuild frame of one model mapped onto the panel_export convention: the footprint's top-left corner at the
    origin, +Y up, inches. (x0, y1) is the footprint's top-left corner in the rebuild frame, w x h its size; unit
    converts rebuild units to inches (centimetres by default) and margin (inches) is kept clear all round."""

    def __init__(self, x0, y1, w, h, unit=CM, margin=0.04):
        self.x0, self.y1, self.unit, self.margin = x0, y1, unit, margin
        self.w = round(w * unit + 2 * margin, 4)
        self.h = round(h * unit + 2 * margin, 4)

    def x(self, x):
        return (x - self.x0) * self.unit + self.margin

    def y(self, y):
        return (y - self.y1) * self.unit - self.margin

    def z(self, z):
        return z * self.unit


def make_object(name, solid, frame):
    """A mesh object of a Solid's faces in panel_export space, one material per colour."""
    mesh = bpy.data.meshes.new(name)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    bm = bmesh.new()
    slots = {}
    for pts, colour in solid.faces:
        if colour not in slots:
            slots[colour] = len(slots)
            mesh.materials.append(px.material("mat_" + colour, colour))
        face = bm.faces.new([bm.verts.new((frame.x(x), frame.y(y), frame.z(z))) for x, y, z in pts])
        face.material_index = slots[colour]
    bm.to_mesh(mesh)
    bm.free()
    return obj


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
    """The owner's SRV control switch (references/gauges/owner-srv-switch-render.png), rebuilt from the OBJ's
    dimensions: a round grey base with a 45 degree chamfered rim, a legend tab above it engraved "Close | Open" (or
    "Close | Auto | Open"), and the handle that turns in the base: a white disc with a straight bar across it, flared
    at both ends, with a slot line at its pointer end. The handle points up at the tab as modelled, so it needs no
    turning over, and the position anchors sit on the engraved legends either side of the separators: the game turns
    the handle to the bearing of each (about 17 degrees either side, 20 for the three position plate)."""
    b = obj_bounds(file_name)
    expect(b, "Body", ((-1.8, 1.8), (-1.8, 2.5), (0.0, 1.3)), file_name)
    expect(b, "Handle", ((-1.5, 1.5), (-1.5, 1.5), (0.0, 2.15)), file_name)
    px.clear_scene()
    frame = Frame(-1.8, 2.5, 3.6, 4.3)

    body = Solid()
    # Base: r 1.8 to 1.0 high, chamfered in to the bore (r 1.5) at 1.3; the bore wall down to below the disc.
    lathe(body, 0.0, 0.0, [(1.8, 0.0), (1.8, 1.0), (1.5, 1.3), (1.5, 1.2)], 32, ["9A9EA2", "B0B4B8", "5E6266"])
    # Legend tab: 2.7 wide, up to 2.5 above the shaft, 0.8 high, rounded top corners (its foot is inside the base).
    tab = [(-1.35, 0.9), (1.35, 0.9)] + arc(1.05, 2.2, 0.3, 0.0, 90.0, 4) + arc(-1.05, 2.2, 0.3, 90.0, 180.0, 4)
    prism(body, tab, 0.0, 0.8, "CDD0D3", "9A9EA2")
    legends, separators, cap = {2: ([-0.68, 0.68], [0.0], 0.085),
                                3: ([-0.80, 0.0, 0.80], [-0.35, 0.35], 0.062)}[positions]
    for x in separators:
        flat(body, rect(x - 0.05, 1.9, x + 0.05, 2.4), 0.82, "4E5155")
    make_object("body", body, frame)

    handle = Solid()
    lathe(handle, 0.0, 0.0, [(1.48, 1.0), (1.48, 1.25), (0.0, 1.25)], 32, ["C4C4C0", "FAFAF7"])
    # The bar: 3 long, 0.30 wide at the middle flaring to 0.58 at the ends, from the disc (1.25) to 2.15 with a small
    # chamfer round its top.
    widths = [(0.0, 0.15), (0.4, 0.16), (0.8, 0.20), (1.1, 0.235), (1.3, 0.265), (1.48, 0.29)]
    ts = [-t for t, _ in reversed(widths[1:])] + [t for t, _ in widths]
    half = dict(widths)
    outer = [(half[abs(t)], t) for t in ts] + [(-half[abs(t)], t) for t in reversed(ts)]
    inner = [(half[abs(t)] - 0.04, max(-1.44, min(1.44, t))) for t in ts] + \
            [(-half[abs(t)] + 0.04, max(-1.44, min(1.44, t))) for t in reversed(ts)]
    prism(handle, outer, 1.25, 2.10, None, "C2C5C8")
    for i in range(len(outer)):
        j = (i + 1) % len(outer)
        (xa, ya), (xb, yb) = outer[i], outer[j]
        handle.face([(xa, ya, 2.10), (xb, yb, 2.10), (inner[j][0], inner[j][1], 2.15), (inner[i][0], inner[i][1], 2.15)],
                    "D2D5D8", (yb - ya, xa - xb, 0.06))
    n = len(ts)
    for i in range(n - 1):
        right_a, right_b = inner[i], inner[i + 1]
        left_a, left_b = inner[2 * n - 1 - i], inner[2 * n - 2 - i]
        handle.face([(left_a[0], left_a[1], 2.15), (right_a[0], right_a[1], 2.15), (right_b[0], right_b[1], 2.15),
                     (left_b[0], left_b[1], 2.15)], "E2E4E6", (0, 0, 1))
    flat(handle, rect(-0.06, 0.74, 0.06, 1.36), 2.17, "3A3C40")
    pivot = (frame.x(0.0), frame.y(0.0), 0.0)
    px.tag(make_object("handle", handle, frame), part="handle", pivot=pivot, axis=(0, 0, 1))
    for i, x in enumerate(legends):
        px.label("pos%d" % (i + 1), frame.x(x), frame.y(2.15), frame.z(0.8) + 0.01, cap)
    return model_id, frame.w, frame.h, "Owner's CAD model %s (Fusion), rebuilt from its dimensions" % file_name, 700


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


# ---------------------------------------------------------------- the curved bar gauge
#
# Two models built to the owner's CAD part bar-gauge.obj and laid out like the owner's simulator picture
# (references/gauges/owner-bar-gauge-target-look.png):
#
#   bar_gauge_case  the 5 x 20 cm case: the white face curved along its length on the CAD's arc, raised side rails
#                   on the CAD rim's arc, grey top and bottom caps (a dark round dot on the bottom one) and the tag
#                   plate above. The face is left open over the channel.
#   bar_gauge       the narrow black channel that fills that slot, following the arc, and the grey pointer in it
#                   pointing left at the scale.
#
# Measured on the OBJ (centimetres, in the rebuild frame: x right, y up, z out; y = 0 is the middle of the length):
# the satin rim is a circular arc about an axis parallel to x through y = 0, z = -14, of radius sqrt(10^2 + 14^2) =
# 17.205 (it meets the panel at y = +-10 and stands 3.205 at the middle); the enamel face is the same arc 0.2 lower
# (radius 17.005). obj_arc() refits both every build and stops it if the file changes.
#
# The game prints the scale, the bands, the numbers and the units on the curved face (BarGaugeFace), and places the
# two models by the same numbers, written there in inches (CASE_W, CHANNEL_X/Y, ARC_Y/Z, FACE_R ...): keep the two in
# step. CurvedBarGaugeTests checks the shipped files against BarGaugeFace.
#
# The pointer is modelled at the scale minimum, lying on the arc, with its tip at the middle of its own height.
# Its travel (15.2 cm) is the rise of the pointer from the scale minimum to the maximum as seen head-on; the game moves
# it along the arc, turning it about the arc's axis, so that seen head-on its height is linear in the reading.

ARC_Z = -14.0                       # the arc's axis, behind the panel
RIM_R = math.sqrt(10.0 ** 2 + 14.0 ** 2)
FACE_R = RIM_R - 0.2                # the face, 0.2 under the rim as in the CAD
CHANNEL_R = FACE_R - 0.12           # the channel floor, a shallow slot in the face
CASE_W, HALF_LEN = 5.0, 10.0        # the case, 5 x 20
CAP = 1.85                          # cap length at each end (so the face shows between y = +-8.15)
FACE_HALF = HALF_LEN - CAP
CAP_Z, CHAMFER = 1.5, 0.15          # the caps are flat-topped blocks 1.5 high with chamfered long edges
RAIL = 0.4                          # side rails x 0..0.4 and 4.6..5, as the CAD rim
CHANNEL_X0, CHANNEL_X1 = 2.52, 3.60  # the channel, a little right of the middle (0.50 to 0.72 of the width)
TAG_GAP, TAG_H, TAG_X0, TAG_X1, TAG_Z = 0.25, 0.8, 0.35, 4.65, 0.15
TOP = HALF_LEN + TAG_GAP + TAG_H    # the footprint's top edge
SCALE_HALF = FACE_HALF - 0.55       # the scale's ends, 0.55 in from the caps (the picture's margin)
SEGMENTS = 24                       # facets along the arc
POINTER_TIP, POINTER_BASE, POINTER_HALF = 0.05, 1.03, 0.4  # across the channel from its left edge; half its height
CASE_GREY, CAP_GREY, FACE_WHITE, CHANNEL_BLACK = "C3C6CB", "A4A8B2", "F6F5EE", "0E0E0E"


def obj_arc(file_name, x, material):
    """(centre y, centre z, radius) of the circle through the OBJ's vertices at x of faces in material, rebuild frame."""
    verts, faces, cur = [], [], None
    with open(os.path.join(OBJ_DIR, file_name), encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("v "):
                vx, vy, vz = (float(t) for t in line.split()[1:4])
                verts.append((vx, -vz, vy))
            elif line.startswith("usemtl"):
                cur = line.split(None, 1)[1].strip()
            elif line.startswith("f ") and material in (cur or ""):
                faces.append([int(t.split("/")[0]) - 1 for t in line.split()[1:]])
    pts = sorted({(round(verts[i][1], 6), round(verts[i][2], 6)) for f in faces for i in f
                  if abs(verts[i][0] - x) < 1e-4 and verts[i][2] > 0.05})
    (y1, z1), (y2, z2), (y3, z3) = pts[0], pts[len(pts) // 2], pts[-1]
    d = 2 * (y1 * (z2 - z3) + y2 * (z3 - z1) + y3 * (z1 - z2))
    cy = ((y1 * y1 + z1 * z1) * (z2 - z3) + (y2 * y2 + z2 * z2) * (z3 - z1) + (y3 * y3 + z3 * z3) * (z1 - z2)) / d
    cz = ((y1 * y1 + z1 * z1) * (y3 - y2) + (y2 * y2 + z2 * z2) * (y1 - y3) + (y3 * y3 + z3 * z3) * (y2 - y1)) / d
    r = math.hypot(y1 - cy, z1 - cz)
    worst = max(abs(math.hypot(py - cy, pz - cz) - r) for py, pz in pts)
    if worst > 1e-3:
        raise ValueError("%s: the vertices at x %.2f (%s) are not on one circle (off by %.4f)" % (file_name, x, material, worst))
    return cy, cz, r


def check_bar_gauge_obj():
    """Stops the build when bar-gauge.obj no longer has the dimensions the rebuild below was measured from."""
    b = obj_bounds("bar-gauge.obj")
    expect(b, "body", ((0.0, 5.0), (-10.0, 10.0), (-0.2, 3.185)), "bar-gauge.obj")
    expect(b, "pointer", ((0.398, 1.406), (-0.527, 0.574), (3.022, 3.209)), "bar-gauge.obj")
    for x, material, radius in ((0.0, "Steel", RIM_R), (0.4, "Enamel", FACE_R)):
        cy, cz, r = obj_arc("bar-gauge.obj", x, material)
        if abs(cy) > 2e-3 or abs(cz - ARC_Z) > 2e-3 or abs(r - radius) > 2e-3:
            raise ValueError("bar-gauge.obj %s arc: centre (%.4f, %.4f) radius %.4f is not the measured (0, %.1f) %.4f"
                             % (material, cy, cz, r, ARC_Z, radius))


def arc_angles(radius, half=FACE_HALF, n=SEGMENTS):
    """The facet angles (radians, up positive) of an arc of this radius that ends at y = +-half."""
    a = math.asin(half / radius)
    return [-a + 2 * a * i / n for i in range(n + 1)]


def on_arc(radius, angle):
    """(y, z) of the point at this angle on the arc of this radius."""
    return radius * math.sin(angle), ARC_Z + radius * math.cos(angle)


def arc_strip(solid, x0, x1, radius, colour):
    """A strip of the curved surface between x0 and x1."""
    angles = arc_angles(radius)
    for a, b in zip(angles, angles[1:]):
        (ya, za), (yb, zb) = on_arc(radius, a), on_arc(radius, b)
        m = (a + b) / 2
        solid.face([(x0, ya, za), (x1, ya, za), (x1, yb, zb), (x0, yb, zb)], colour, (0, math.sin(m), math.cos(m)))


def arc_wall(solid, x, low, high, outward_x, colour):
    """A wall in the plane x between two arcs (high a radius, or None for the panel itself)."""
    lows = arc_angles(low) if low is not None else None
    highs = arc_angles(high)
    for i in range(SEGMENTS):
        ya, za = on_arc(high, highs[i])
        yb, zb = on_arc(high, highs[i + 1])
        if lows is None:
            bottom = [(x, yb, 0.0), (x, ya, 0.0)]
        else:
            (yc, zc), (yd, zd) = on_arc(low, lows[i + 1]), on_arc(low, lows[i])
            bottom = [(x, yc, zc), (x, yd, zd)]
        solid.face([(x, ya, za), (x, yb, zb)] + bottom, colour, (outward_x, 0, 0))


def cap_block(solid, y_inner, y_outer, colour):
    """A cap across the case from y_inner (against the face) to y_outer, flat-topped with both long edges chamfered."""
    s = 1.0 if y_outer > y_inner else -1.0
    profile = [(y_inner, 0.0), (y_inner, CAP_Z - CHAMFER), (y_inner + s * CHAMFER, CAP_Z),
               (y_outer - s * CHAMFER, CAP_Z), (y_outer, CAP_Z - CHAMFER), (y_outer, 0.0)]
    for (ya, za), (yb, zb) in zip(profile, profile[1:]):
        # outward: away from the cap's middle line in the y-z plane
        ny, nz = (zb - za) * s, (ya - yb) * s
        solid.face([(0.0, ya, za), (CASE_W, ya, za), (CASE_W, yb, zb), (0.0, yb, zb)], colour, (0, -ny, -nz))
    for x, nx in ((0.0, -1.0), (CASE_W, 1.0)):
        solid.face([(x, y, z) for y, z in profile], colour, (nx, 0, 0))


def build_bar_gauge_case():
    """The bar gauge's case (see the notes above): face, rails, caps with the dot, tag plate."""
    check_bar_gauge_obj()
    px.clear_scene()
    frame = Frame(0.0, TOP, CASE_W, TOP + HALF_LEN, margin=0.0)
    make_object("case", case_solid(), frame)
    return "bar_gauge_case", frame.w, frame.h, \
        "Owner's CAD model bar-gauge.obj (Fusion), curved face and rim on its arc; owner's simulator layout", 600


def case_solid():
    body = Solid()
    # the white face either side of the channel slot, and the rails on the rim's arc
    arc_strip(body, RAIL, CHANNEL_X0, FACE_R, FACE_WHITE)
    arc_strip(body, CHANNEL_X1, CASE_W - RAIL, FACE_R, FACE_WHITE)
    arc_strip(body, 0.0, RAIL, RIM_R, CASE_GREY)
    arc_strip(body, CASE_W - RAIL, CASE_W, RIM_R, CASE_GREY)
    # the rails' inner steps down to the face, and the case's sides down to the panel
    arc_wall(body, RAIL, FACE_R, RIM_R, 1.0, CASE_GREY)
    arc_wall(body, CASE_W - RAIL, FACE_R, RIM_R, -1.0, CASE_GREY)
    arc_wall(body, 0.0, None, RIM_R, -1.0, CASE_GREY)
    arc_wall(body, CASE_W, None, RIM_R, 1.0, CASE_GREY)
    cap_block(body, FACE_HALF, HALF_LEN, CAP_GREY)
    cap_block(body, -FACE_HALF, -HALF_LEN, CAP_GREY)
    # the round dark dot in the middle of the bottom cap
    lathe(body, CASE_W / 2, -(FACE_HALF + HALF_LEN) / 2, [(0.28, CAP_Z), (0.28, CAP_Z + 0.04), (0.0, CAP_Z + 0.04)], 16,
          "2B2C2E")
    prism(body, rect(TAG_X0, HALF_LEN + TAG_GAP, TAG_X1, TOP), 0.0, TAG_Z, "E6E6E1", "C9C9C4")
    return body


def pointer_solid():
    """The pointer at the scale minimum: a low two-faceted wedge pointing left, its tip a single point at the middle of
    its height (the reading point), turned about the arc's axis down to where it reads the scale minimum."""
    w_bot, w_edge, w_ridge = CHANNEL_R + 0.005, FACE_R + 0.02, FACE_R + 0.07
    # With the tip half way between the pointer's lowest and highest faces, its height is the middle of the pointer's
    # own extent at any turn below the middle (the extremes are a base corner on each face).
    w_tip = (w_bot + w_edge) / 2
    turn = math.asin(-SCALE_HALF / w_tip)
    tip_x, base_x, half = CHANNEL_X0 + POINTER_TIP, CHANNEL_X0 + POINTER_BASE, POINTER_HALF

    def placed(x, v, w):
        return x, v * math.cos(turn) + w * math.sin(turn), ARC_Z - v * math.sin(turn) + w * math.cos(turn)

    tip = placed(tip_x, 0.0, w_tip)
    upper, lower, ridge = placed(base_x, half, w_edge), placed(base_x, -half, w_edge), placed(base_x, 0.0, w_ridge)
    upper_bot, lower_bot = placed(base_x, half, w_bot), placed(base_x, -half, w_bot)
    up = (0.0, math.cos(turn), -math.sin(turn))     # the arc's up direction at the pointer
    out = (0.0, math.sin(turn), math.cos(turn))     # out of the face there
    s = Solid()
    s.face([tip, ridge, upper], "D0D3D6", tuple(o + 0.3 * u for o, u in zip(out, up)))
    s.face([tip, lower, ridge], "9A9EA2", tuple(o - 0.3 * u for o, u in zip(out, up)))
    s.face([upper_bot, lower_bot, lower, ridge, upper], "8E9296", (1, 0, 0))
    s.face([tip, upper, upper_bot], "B8BCC0", up)
    s.face([tip, lower_bot, lower], "8E9296", tuple(-u for u in up))
    return s


def build_bar_gauge():
    """The bar gauge's channel and pointer (see the notes above): the model the game places the scale against."""
    check_bar_gauge_obj()
    px.clear_scene()
    frame = Frame(CHANNEL_X0, FACE_HALF, CHANNEL_X1 - CHANNEL_X0, 2 * FACE_HALF, margin=0.0)
    make_object("body", channel_solid(), frame)
    px.tag(make_object("pointer", pointer_solid(), frame), part="needle", role="needle",
           travel=round(2 * SCALE_HALF * CM, 5), axis=(0, 1, 0))
    return "bar_gauge", frame.w, frame.h, \
        "Owner's CAD model bar-gauge.obj (Fusion) and simulator layout: the curved channel and its pointer", 400


def channel_solid():
    channel = Solid()
    arc_strip(channel, CHANNEL_X0, CHANNEL_X1, CHANNEL_R, CHANNEL_BLACK)
    arc_wall(channel, CHANNEL_X0, CHANNEL_R, FACE_R, 1.0, CHANNEL_BLACK)
    arc_wall(channel, CHANNEL_X1, CHANNEL_R, FACE_R, -1.0, CHANNEL_BLACK)
    return channel


def preview_bar_gauge():
    """Both models together in the case's frame, with the pointer at the minimum, middle and maximum of the scale."""
    frame = Frame(0.0, TOP, CASE_W, TOP + HALF_LEN, margin=0.0)
    w, h = frame.w, frame.h
    for name, fraction in (("min", 0.0), ("mid", 0.5), ("max", 1.0)):
        px.clear_scene()
        make_object("case", case_solid(), frame)
        make_object("channel", channel_solid(), frame)
        pointer = make_object("pointer", pointer_solid(), frame)
        # turned about the arc's axis so the tip rises linearly as seen head-on, as the game does
        w_tip = (CHANNEL_R + 0.005 + FACE_R + 0.02) / 2
        rest = math.asin(-SCALE_HALF / w_tip)
        goal = math.asin((-SCALE_HALF + 2 * SCALE_HALF * fraction) / w_tip)
        from mathutils import Matrix
        pivot = (0.0, frame.y(0.0), frame.z(ARC_Z))
        turn = Matrix.Translation(pivot) @ Matrix.Rotation(-(goal - rest), 4, "X") @ Matrix.Translation(
            tuple(-c for c in pivot))
        pointer.data.transform(turn)
        os.makedirs(PREVIEW_DIR, exist_ok=True)
        views = ("front", "three_quarter", "side") if name == "mid" else ("front",)
        px.preview(os.path.join(PREVIEW_DIR, "bar_gauge_assembled_" + name), w, h, views=views)


def build_rps_trip():
    """The owner's RPS trip assembly (references/gauges/owner-rps-trip-render.png), rebuilt from the OBJ's
    dimensions: a red 12 x 6 plate with 1 cm chamfered corners; round of each button a raised flange with a label pad
    above it (where the CAD engraves "RPS A Manual Trip"; the game prints the editable legend there instead); a tall
    cup guard on the flange; and inside the cup the recessed red button with three concentric ripples, which slides
    0.15 in while it is held."""
    b = obj_bounds("RPS-Trip-Assembly.obj")
    expect(b, "Body", ((-6.0, 6.0), (-3.0, 3.0), (0.0, 2.3)), "RPS-Trip-Assembly.obj")
    for key, cx in (("RPS A Movable", -3.0), ("RPS B Movable", 3.0)):
        expect(b, key, ((cx - 1.15, cx + 1.15), (-1.15, 1.15), (0.2, 1.9)), "RPS-Trip-Assembly.obj")
    px.clear_scene()
    frame = Frame(-6.0, 3.0, 12.0, 6.0)
    body = Solid()
    plate = [(-5.0, -3.0), (5.0, -3.0), (6.0, -2.0), (6.0, 2.0), (5.0, 3.0), (-5.0, 3.0), (-6.0, 2.0), (-6.0, -2.0)]
    prism(body, plate, 0.0, 0.2, "B01818", "8E1414")
    # The flange (r 1.85) and its label pad (3 wide, up to 2.6) are one raised plateau 0.2 thick; above it the cup:
    # r 1.45 to 1.3 high, tapering to 1.35 at the rim (2.3), bore 1.25, its inside in shadow as in the render.
    seam = math.degrees(math.atan2(math.sqrt(1.85 ** 2 - 1.5 ** 2), 1.5))
    for cx in (-3.0, 3.0):
        pad = arc(cx, 0.0, 1.85, 180.0 - seam, 360.0 + seam, 22) + arc(cx + 1.25, 2.35, 0.25, 0.0, 90.0, 3) \
            + arc(cx - 1.25, 2.35, 0.25, 90.0, 180.0, 3)
        prism(body, pad, 0.2, 0.4, "B41A1A", "8E1414")
        lathe(body, cx, 0.0, [(1.45, 0.4), (1.45, 1.3), (1.35, 2.3), (1.25, 2.3), (1.25, 0.4)], 32,
              ["A81616", "B01818", "C42828", "6E0E0E"])
    make_object("body", body, frame)
    # The button: r 1.15, its top 1.8 at the edge rising to 1.9, three grooves (r 0.37, 0.57, 0.77) between ridges.
    ripples = [(1.15, 0.45), (1.15, 1.80), (1.08, 1.88), (0.88, 1.89), (0.77, 1.81), (0.67, 1.89), (0.57, 1.81),
               (0.47, 1.89), (0.37, 1.81), (0.27, 1.90), (0.0, 1.90)]
    for side, cx in (("a", -3.0), ("b", 3.0)):
        cap = Solid()
        lathe(cap, cx, 0.0, ripples, 28, ["B81A1A"] + ["D42020"] * (len(ripples) - 2))
        px.tag(make_object("cap_" + side, cap, frame), part="cap_" + side, travel=0.15, axis=(0, 0, -1))
        # On the label pad above the guard, where the CAD engraves its legend: high on the pad, so the tall guard
        # does not hide it from a player looking up at the assembly.
        px.label("legend_" + side, frame.x(cx), frame.y(2.27), frame.z(0.4) + 0.01, 0.115)
    return "rps_trip_assembly", frame.w, frame.h, \
        "Owner's CAD model RPS-Trip-Assembly.obj (Fusion), rebuilt from its dimensions", 1100


BUILDERS = {
    "button_1": build_button_1,
    "srv_open_close": lambda: build_rot_switch("rot-swt-open-close.obj", "srv_open_close", 2),
    "srv_open_auto_close": lambda: build_rot_switch("rot-swt-open-auto-close.obj", "srv_open_auto_close", 3),
    "wl2_selector": build_wl2,
    "bar_gauge": build_bar_gauge,
    "bar_gauge_case": build_bar_gauge_case,
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
    if "--no-preview" not in args and ("bar_gauge" in only or "bar_gauge_case" in only):
        preview_bar_gauge()


main()
