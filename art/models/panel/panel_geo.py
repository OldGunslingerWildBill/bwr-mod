"""Mesh and preview helpers shared by the newer panel model families (fcd, oiltight).

Pure helpers, no model data: a face accumulator (Geo), surfaces of revolution and
rectangular "lathes", prisms, preview-only lamp states, sample legend text and
instancing for tiled previews. Conventions are those of panel_export: inches,
panel face Z = 0, +Z toward the operator, +Y up, footprint top-left at (0, 0).

Every face is wound counter-clockwise seen from outside, so the exported normals
point out of the solid. check_outward() asserts that for the convex pieces.
"""
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import panel_export as px  # noqa: E402


class Geo:
    """Accumulates one object's vertices and faces with per-face material slots."""

    def __init__(self, *mats):
        self.mats = list(mats)
        self.v, self.f, self.fm = [], [], []

    def vert(self, p):
        self.v.append(tuple(float(c) for c in p))
        return len(self.v) - 1

    def face(self, idx, m=0):
        self.f.append(tuple(idx))
        self.fm.append(m)

    def build(self, name, part="body", role="paint", origin=None, **tagkw):
        me = bpy.data.meshes.new(name)
        me.from_pydata(self.v, [], self.f)
        for m in self.mats:
            me.materials.append(m)
        for poly, mi in zip(me.polygons, self.fm):
            poly.material_index = mi
        me.update()
        obj = bpy.data.objects.new(name, me)
        bpy.context.scene.collection.objects.link(obj)
        if origin is not None:
            o = Vector(origin)
            me.transform(Matrix.Translation(-o))
            obj.location = o
        px.tag(obj, part=part, role=role, **tagkw)
        return obj


def lathe(g, cx, cy, profile, segs, mat=0, phase=0.0, rmod=None, ngon_cap=False, cap_mat=None):
    """Surface of revolution about Z through (cx, cy).

    profile: [(r, z), ...] outer wall first, upward, then front faces inward; a final
    r == 0 closes the centre with a triangle fan (or ngon_cap closes the last ring).
    mat: a slot index, or one slot per profile segment. rmod(i, j, r) varies a ring's radius.
    """
    rings = []
    for i, (r, z) in enumerate(profile):
        if r == 0:
            c = g.vert((cx, cy, z))
            rings.append([c] * segs)
            continue
        ring = []
        for j in range(segs):
            th = phase + 2 * math.pi * j / segs
            rr = rmod(i, j, r) if rmod else r
            ring.append(g.vert((cx + rr * math.cos(th), cy + rr * math.sin(th), z)))
        rings.append(ring)
    for i in range(len(profile) - 1):
        a_ring, b_ring = rings[i], rings[i + 1]
        m = mat[i] if isinstance(mat, (list, tuple)) else mat
        for j in range(segs):
            j2 = (j + 1) % segs
            a, b, c, d = a_ring[j], a_ring[j2], b_ring[j2], b_ring[j]
            if c == d:
                g.face((a, b, c), m)
            elif a == b:
                g.face((a, c, d), m)
            else:
                g.face((a, b, c, d), m)
    if ngon_cap:
        m = cap_mat if cap_mat is not None else (mat[-1] if isinstance(mat, (list, tuple)) else mat)
        g.face(tuple(rings[-1]), m)
    return rings


def rlathe(g, cx, cy, profile, mat=0, ngon_cap=False, cap_mat=None):
    """Like lathe() but the rings are axis-aligned rectangles: profile [(half_w, half_h, z), ...].

    Ring corners run counter-clockwise from +Z (bottom-left first), so an upward
    segment is an outward-facing wall and a flat inward segment faces +Z.
    """
    rings = []
    for hw, hh, z in profile:
        rings.append([g.vert((cx - hw, cy - hh, z)), g.vert((cx + hw, cy - hh, z)),
                      g.vert((cx + hw, cy + hh, z)), g.vert((cx - hw, cy + hh, z))])
    for i in range(len(profile) - 1):
        m = mat[i] if isinstance(mat, (list, tuple)) else mat
        for j in range(4):
            j2 = (j + 1) % 4
            g.face((rings[i][j], rings[i][j2], rings[i + 1][j2], rings[i + 1][j]), m)
    if ngon_cap:
        m = cap_mat if cap_mat is not None else (mat[-1] if isinstance(mat, (list, tuple)) else mat)
        g.face(tuple(rings[-1]), m)
    return rings


def box(g, x0, y0, z0, x1, y1, z1, m=0, back=False, sides=True):
    """Axis-aligned box from corner to corner; the back (-Z) face is skipped unless back=True."""
    v = {}
    for xi, x in enumerate((x0, x1)):
        for yi, y in enumerate((y0, y1)):
            for zi, z in enumerate((z0, z1)):
                v[xi, yi, zi] = g.vert((x, y, z))
    g.face((v[0, 0, 1], v[1, 0, 1], v[1, 1, 1], v[0, 1, 1]), m)          # front +Z
    if sides:
        g.face((v[1, 0, 0], v[1, 1, 0], v[1, 1, 1], v[1, 0, 1]), m)      # +X
        g.face((v[0, 0, 0], v[0, 0, 1], v[0, 1, 1], v[0, 1, 0]), m)      # -X
        g.face((v[0, 1, 0], v[0, 1, 1], v[1, 1, 1], v[1, 1, 0]), m)      # +Y
        g.face((v[0, 0, 0], v[1, 0, 0], v[1, 0, 1], v[0, 0, 1]), m)      # -Y
    if back:
        g.face((v[0, 0, 0], v[0, 1, 0], v[1, 1, 0], v[1, 0, 0]), m)


def prism(g, pts, z0, z1, m=0, sides=True, top=True):
    """Extrusion of a convex polygon (counter-clockwise from +Z) from z0 to z1."""
    b = [g.vert((x, y, z0)) for x, y in pts]
    t = [g.vert((x, y, z1)) for x, y in pts]
    n = len(pts)
    if sides:
        for i in range(n):
            j = (i + 1) % n
            g.face((b[i], b[j], t[j], t[i]), m)
    if top:
        g.face(tuple(t), m)


def rect_pts(cx, cy, hw, hh):
    return [(cx - hw, cy - hh), (cx + hw, cy - hh), (cx + hw, cy + hh), (cx - hw, cy + hh)]


def check_outward(obj, name=None, tol=1e-6):
    """Assert every face of a CONVEX-ish object faces away from its centroid (winding sanity).

    Concave rings (bezels) are checked loosely: only the count of inward faces is reported.
    """
    me = obj.data
    c = sum((v.co for v in me.vertices), Vector()) / max(1, len(me.vertices))
    bad = 0
    for p in me.polygons:
        if (p.center - c).dot(p.normal) < -tol and p.normal.z < 0.5:
            bad += 1
    return bad


# --------------------------------------------------------------------------
# Preview helpers (never exported)
# --------------------------------------------------------------------------
def lamp_materials(objs=None):
    mats = set()
    for o in (objs or bpy.context.scene.objects):
        if o.type == "MESH" and o.get("bwr_role") == "lamp":
            mats.update(s.material for s in o.material_slots if s.material)
    return mats


def set_lamp_mat(mat, state, strength=1.4, dim=0.3, colour=None):
    """Preview only. lit: full-bright (emission, no shading) as the game draws a lit
    lamp; off: dimmed as the game dims an unlit lamp; authored: as exported."""
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    base = bsdf.inputs["Base Color"]
    if "authored_base" not in mat:
        mat["authored_base"] = list(base.default_value)
    col = list(colour) if colour else list(mat["authored_base"])
    bsdf.inputs["Emission Strength"].default_value = 0.0
    if state == "lit":
        bsdf.inputs["Emission Color"].default_value = col
        bsdf.inputs["Emission Strength"].default_value = strength
        base.default_value = (0.0, 0.0, 0.0, 1.0)
    elif state == "off":
        base.default_value = (col[0] * dim, col[1] * dim, col[2] * dim, 1.0)
    else:
        base.default_value = list(mat["authored_base"])


def set_lamps(state, **kw):
    for mat in lamp_materials():
        set_lamp_mat(mat, state, **kw)


def hex_linear(h):
    r, g_, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    f = lambda c: c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return (f(r), f(g_), f(b), 1.0)


def text_material(hex_colour, emit=0.0):
    name = "txt_%s_%s" % (hex_colour, emit)
    if name in bpy.data.materials:
        return bpy.data.materials[name]
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    col = hex_linear(hex_colour)
    if emit > 0:
        bsdf.inputs["Base Color"].default_value = (0, 0, 0, 1)
        bsdf.inputs["Emission Color"].default_value = col
        bsdf.inputs["Emission Strength"].default_value = emit
    else:
        bsdf.inputs["Base Color"].default_value = col
    bsdf.inputs["Roughness"].default_value = 0.6
    return mat


def add_text(txt, x, y, z, h, colour="141414", emit=0.0, align="CENTER", rot_deg=0.0, name="txt", width=None):
    """Preview-only legend at an anchor; h is the cap height in inches."""
    cu = bpy.data.curves.new(name, "FONT")
    cu.body = txt
    cu.size = h / 0.72
    cu.align_x = align
    cu.align_y = "CENTER"
    cu.extrude = 0.0
    if width:
        cu.space_character = 1.0
    ob = bpy.data.objects.new(name, cu)
    ob.location = (x, y, z)
    ob.rotation_euler = (0, 0, math.radians(rot_deg))
    ob.data.materials.append(text_material(colour, emit))
    bpy.context.scene.collection.objects.link(ob)
    if width:
        # squeeze long legends to fit a window
        ob.scale.x = 1.0
        bpy.context.view_layer.update()
        w = ob.dimensions.x
        if w > width:
            ob.scale.x = width / w
    return ob


def label_empties():
    return {e["bwr_label"]: e for e in bpy.context.scene.objects if e.type == "EMPTY" and "bwr_label" in e}


def sample_legends(samples):
    """samples: {label id: (text, colour hex, emit)}; draws each at its anchor."""
    out = []
    for lid, e in label_empties().items():
        if lid in samples:
            txt, col, emit = samples[lid]
            out.append(add_text(txt, e.location.x, e.location.y, e.location.z + 0.003,
                                float(e["bwr_text_h"]), col, emit, rot_deg=math.degrees(e.rotation_euler[2]),
                                name="sample_" + lid))
    return out


def clone(src, dx, dy, dz=0.0, material=None, name=None):
    """Linked duplicate of a mesh object (shares the mesh), moved by (dx, dy, dz); optional
    per-object material for slot 0."""
    inst = bpy.data.objects.new(name or src.name + "_i", src.data)
    inst.location = (src.location.x + dx, src.location.y + dy, src.location.z + dz)
    inst.rotation_euler = src.rotation_euler
    for k, v in src.items():
        inst[k] = v
    bpy.context.scene.collection.objects.link(inst)
    if material is not None:
        inst.material_slots[0].link = "OBJECT"
        inst.material_slots[0].material = material
    return inst


def plane(x0, y0, x1, y1, z, mat, name="plane"):
    g = Geo(mat)
    a = g.vert((x0, y0, z))
    b = g.vert((x1, y0, z))
    c = g.vert((x1, y1, z))
    d = g.vert((x0, y1, z))
    g.face((a, b, c, d), 0)
    me = bpy.data.meshes.new(name)
    me.from_pydata(g.v, [], g.f)
    me.materials.append(mat)
    me.update()
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    return ob


def ghost_glass(alpha=0.22):
    """Preview only: draw role-glass parts translucent, as the game does."""
    for o in bpy.context.scene.objects:
        if o.type == "MESH" and o.get("bwr_role") == "glass":
            for s in o.material_slots:
                m = s.material
                if m is None or not m.use_nodes:
                    continue
                m.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value = alpha
                try:
                    m.surface_render_method = "BLENDED"
                except Exception:
                    pass
