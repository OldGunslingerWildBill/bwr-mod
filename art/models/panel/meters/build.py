"""Family C: GE switchboard meters (now sold by Yokogawa under the same model numbers).

Builds and exports, headless:

    blender --background --factory-startup --python art/models/panel/meters/build.py

  ge_ab40                 dial_gauge "ge": AB-40 round-scale meter, 250 deg scale, black pointer
  ge_ab40_red             the same with a Day-glo red pointer
  ge_180_vertical         edgewise_gauge_vertical "ge": Type 180 edgewise, black trim case, white pointer
  ge_180_vertical_red     the same with the Day-glo red pointer option
  ge_180_horizontal       edgewise_gauge_horizontal "ge": the Type 180 cover turned 90 degrees
  ge_180_horizontal_red   the same with the Day-glo red pointer option

Previews (front / three-quarter / side, the needle at min / mid / max, and a
low-resolution "few blocks away" front view) go to tmp/panel-models/meters/.

Source, cited per dimension below: Yokogawa "Switchboard Instruments" catalog
(the former GE switchboard instrument line): p.2 AB/DB general specifications,
p.17 AB/DB-40 options, p.23 AB/DB-40 metal case dimensions, p.25-27 Type 180
specifications, options and dimensions (DWG B3147K22); proportions not given
in the drawings are measured from the catalog's product photographs (front
cover, p.25) and are marked "photo".
"""
import math
import os
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "meters")
CATALOG = "Yokogawa (ex-GE) Switchboard Instruments catalog"

# --------------------------------------------------------------------------- #
# Dimensions, inches                                                          #
# --------------------------------------------------------------------------- #
AB40 = {
    "cover": 4.33,        # F: cover 4.33" square, p.23 short-case table (AB/DB-40)
    "protrusion": 0.86,   # C: front of cover to panel face, p.23
    "corner_r": 0.30,     # photo: cover corner radius
    "roundover": 0.08,    # photo: rounded front edge of the cover
    "window_r": 1.99,     # photo: black cover border ~0.18" at mid-side
    "glass_r": 1.95,      # window glass, set into the cover
    "glass_z": 0.82,
    "dial_r": 1.84,       # photo: visible white dial; dark bezel cone 1.95 -> 1.84
    "dial_z": 0.42,
    "screw_xy": 1.66,     # photo: cover screws, from the dial centre on X and Y
    "screw_r": 0.085,     # photo
    "sweep": 250.0,       # full-scale deflection 250 deg, p.2
    "scale_len": 6.9,     # full scale length 6.9", p.2 -> arc radius 1.58"
    "tick_out": 1.80,     # graduations on the outer scale, p.2; photo for radii
    "tick_in": (1.62, 1.56, 1.50),        # minor, mid, major inner radius
    "tick_w": (0.028, 0.036, 0.055),      # minor, mid, major width
    "divisions": 50,      # 5 numbered intervals x 10 divisions
    "platform_r": 0.55,   # photo: raised white centre platform
    "knob_r": 0.16,       # photo: slotted white zero adjuster at the window centre
    "pointer_z": 0.50,    # pointer plane (top), between dial and platform top
    "numeral_r": 1.24,    # photo: numerals on the inner scale, p.2 ("numerals and legends on the inner scale")
    "numeral_h": 0.20,
}

T180 = {
    "length": 6.040,      # cover 6.040" max, p.27 DWG B3147K22
    "width": 2.294,       # cover 2.294" max, p.27
    "dome_z": 1.065,      # cover projects 1.065" max in front of the panel, p.27
    "window_end_z": 0.84, # dome falls ~0.22" to the ends of the window (spec: bulging ~0.25")
    "rail_w": 0.197,      # photo p.25: side trim rails
    "window_half": 2.40,  # photo p.25: window length 4.80", end caps ~0.62" each
    "cap_foot_z": 0.15,   # end cap rounds down to this height at the case end
    "floor_z": 0.59,      # scale assembly (flattened: the pointer is a linear slider)
    "scale_len": 4.448,   # standard scale length 4.448", p.25
    "divisions": 50,      # 5 numbered intervals x 10 divisions (max 100, p.25)
    # across the window, from the scale side: scale plate | pointer channel | legend strip (p.27 note 4)
    "plate": (-0.89, 0.10),
    "channel": (0.10, 0.36),
    "legend": (0.40, 0.89),
    "tick_len": (0.13, 0.19, 0.27),       # minor, mid, major
    "tick_w": (0.022, 0.030, 0.045),
    "numeral_h": 0.22,    # p.25 numeral height 1/4"; a little smaller to fit four digits
}

PALETTE = {
    "case": "1C1C1C",       # meter cases black (spec palette); Type 180 black trim case option 21, p.27
    "mask": "141414",
    "dial": "F2F0E8",       # meter dials white
    "ink": "1A1A1A",        # black scale
    "pointer_black": "141414",
    "pointer_white": "F2F0E8",  # Type 180 standard pointer colour white, p.25
    "dayglo": "E8402A",     # Day-glo red pointer, Type 180 option 19, p.27
    "channel": "2B2E30",
    "screw": "4A4D50",
    "slot": "0A0A0A",
    "knob": "E4E2DA",
    "glass": "E6EEF0",
}


# --------------------------------------------------------------------------- #
# Mesh helpers                                                                #
# --------------------------------------------------------------------------- #
def newell(pts):
    nx = ny = nz = 0.0
    for i, (x1, y1, z1) in enumerate(pts):
        x2, y2, z2 = pts[(i + 1) % len(pts)]
        nx += (y1 - y2) * (z1 + z2)
        ny += (z1 - z2) * (x1 + x2)
        nz += (x1 - x2) * (y1 + y2)
    return nx, ny, nz


class MB:
    """Collects flat-shaded faces (each with its own vertices) and builds one object."""

    def __init__(self):
        self.verts, self.faces, self.fmats, self.mats = [], [], [], []

    def face(self, pts, mat, out):
        """Adds a polygon; `out` is any vector on the side the face should look to."""
        pts = [tuple(float(c) for c in p) for p in pts]
        n = newell(pts)
        if n[0] * out[0] + n[1] * out[1] + n[2] * out[2] < 0:
            pts.reverse()
        if mat not in self.mats:
            self.mats.append(mat)
        base = len(self.verts)
        self.verts.extend(pts)
        self.faces.append(tuple(range(base, base + len(pts))))
        self.fmats.append(self.mats.index(mat))

    def count(self):
        return sum(1 if len(f) <= 4 else len(f) - 2 for f in self.faces)

    def build(self, name, origin=(0.0, 0.0, 0.0)):
        ox, oy, oz = origin
        me = bpy.data.meshes.new(name)
        me.from_pydata([(x - ox, y - oy, z - oz) for x, y, z in self.verts], [], self.faces)
        for m in self.mats:
            me.materials.append(m)
        for poly, mi in zip(me.polygons, self.fmats):
            poly.material_index = mi
        me.update()
        obj = bpy.data.objects.new(name, me)
        obj.location = origin
        bpy.context.scene.collection.objects.link(obj)
        return obj


def circle(cx, cy, r, n, phase=0.0):
    return [(cx + r * math.cos(phase + 2 * math.pi * k / n), cy + r * math.sin(phase + 2 * math.pi * k / n))
            for k in range(n)]


def cylinder(mb, cx, cy, r, z0, z1, n, side_mat, top_mat, phase=0.0):
    ring = circle(cx, cy, r, n, phase)
    for k in range(n):
        (x0, y0), (x1, y1) = ring[k], ring[(k + 1) % n]
        mb.face([(x0, y0, z0), (x1, y1, z0), (x1, y1, z1), (x0, y0, z1)], side_mat,
                ((x0 + x1) / 2 - cx, (y0 + y1) / 2 - cy, 0))
    mb.face([(x, y, z1) for x, y in ring], top_mat, (0, 0, 1))


def slot(mb, cx, cy, z, length, width, angle_deg, mat):
    a = math.radians(angle_deg)
    ux, uy = math.cos(a) * length / 2, math.sin(a) * length / 2
    vx, vy = -math.sin(a) * width / 2, math.cos(a) * width / 2
    mb.face([(cx - ux - vx, cy - uy - vy, z), (cx + ux - vx, cy + uy - vy, z),
             (cx + ux + vx, cy + uy + vy, z), (cx - ux + vx, cy - uy + vy, z)], mat, (0, 0, 1))


def rounded_square(a, rc, n_edge, n_corner):
    """CCW outline of a square of half-size a with corner radius rc, about the origin.

    Straight-edge points sit at uniform angles from the centre, so a circle sampled
    at the same angles bridges to it with clean quads."""
    tc = math.atan2(a - rc, a)
    local = [(a, a * math.tan(-tc + 2 * tc * i / n_edge)) for i in range(n_edge)]
    local += [(a - rc + rc * math.cos(math.pi / 2 * j / n_corner), a - rc + rc * math.sin(math.pi / 2 * j / n_corner))
              for j in range(n_corner)]
    pts = []
    for side in range(4):
        c, s = math.cos(side * math.pi / 2), math.sin(side * math.pi / 2)
        pts += [(x * c - y * s, x * s + y * c) for x, y in local]
    return pts


def bridge(mb, loop_a, loop_b, mat, out_fn):
    n = len(loop_a)
    for i in range(n):
        j = (i + 1) % n
        q = [loop_a[i], loop_a[j], loop_b[j], loop_b[i]]
        mb.face(q, mat, out_fn(q))


def mats(pointer_hex):
    m = {k: px.material(k, v, 0.55) for k, v in PALETTE.items() if k != "glass"}
    m["case"] = px.material("case", PALETTE["case"], 0.45)
    m["screw"] = px.material("screw", PALETTE["screw"], 0.3, 0.6)
    m["pointer"] = px.material("pointer_" + pointer_hex, pointer_hex, 0.5)
    glass = px.material("glass", PALETTE["glass"], 0.05)
    bsdf = glass.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Alpha"].default_value = 0.05
    if hasattr(glass, "surface_render_method"):
        glass.surface_render_method = "BLENDED"
    m["glass"] = glass
    return m


# --------------------------------------------------------------------------- #
# AB-40 round-scale meter (dial_gauge)                                        #
# --------------------------------------------------------------------------- #
def build_ab40(pointer_hex):
    """Builds the AB-40 in the current (empty) scene. Returns (needle object, pivot)."""
    D = AB40
    a = D["cover"] / 2
    cx, cy = a, -a
    M = mats(pointer_hex)
    body = MB()
    zf = D["protrusion"]
    ro = D["roundover"]

    # Cover front: flat ring between the round-over and the window chamfer.
    inset = rounded_square(a - ro, D["corner_r"] - ro, 8, 3)
    front_out = [(cx + x, cy + y, zf) for x, y in inset]
    chamfer = [(cx + D["window_r"] * math.cos(math.atan2(y, x)), cy + D["window_r"] * math.sin(math.atan2(y, x)), zf)
               for x, y in inset]
    bridge(body, front_out, chamfer, M["case"], lambda q: (0, 0, 1))

    def radial_out(sign, up):
        def fn(q):
            mx = sum(p[0] for p in q) / 4 - cx
            my = sum(p[1] for p in q) / 4 - cy
            return (sign * mx, sign * my, up)
        return fn

    # Round-over of the front edge (two facets) and the straight side down to the panel.
    loops = []
    for ang in (0.0, 45.0, 90.0):
        t = math.radians(ang)
        off = ro - ro * math.sin(t)
        z = zf - ro + ro * math.cos(t)
        loops.append([(cx + x, cy + y, z) for x, y in rounded_square(a - off, D["corner_r"] - off, 1, 3)])
    loops.append([(x, y, 0.0) for x, y, _ in loops[-1]])
    for la, lb in zip(loops, loops[1:]):
        bridge(body, la, lb, M["case"], radial_out(1, 0.3))

    # Window chamfer and the dark bezel cone down to the dial (reads as the black ring round the dial).
    glass_ring = [(cx + D["glass_r"] * math.cos(math.atan2(y - cy, x - cx)),
                   cy + D["glass_r"] * math.sin(math.atan2(y - cy, x - cx)), D["glass_z"]) for x, y, _ in chamfer]
    bridge(body, chamfer, glass_ring, M["case"], radial_out(-1, 1))
    dial_ring = [(cx + D["dial_r"] * math.cos(math.atan2(y - cy, x - cx)),
                  cy + D["dial_r"] * math.sin(math.atan2(y - cy, x - cx)), D["dial_z"]) for x, y, _ in glass_ring]
    bridge(body, glass_ring, dial_ring, M["mask"], radial_out(-1, 0.5))
    body.face(dial_ring, M["dial"], (0, 0, 1))

    # Cover screws.
    for sx in (-1, 1):
        for sy in (-1, 1):
            x, y = cx + sx * D["screw_xy"], cy + sy * D["screw_xy"]
            cylinder(body, x, y, D["screw_r"], zf - 0.01, zf + 0.035, 8, M["screw"], M["screw"], math.pi / 8)
            slot(body, x, y, zf + 0.039, D["screw_r"] * 1.7, 0.022, 35 * sx * sy, M["slot"])

    # Scale: graduations on the outer arc, 250 deg, zero at -125 deg (clockwise from 12 o'clock).
    zt = D["dial_z"] + 0.012
    n = D["divisions"]
    for k in range(n + 1):
        kind = 2 if k % 10 == 0 else 1 if k % 5 == 0 else 0
        w = D["tick_w"][kind] * (1.3 if k in (0, n) else 1.0)
        th = math.radians(-D["sweep"] / 2 + D["sweep"] * k / n)
        ux, uy = math.sin(th), math.cos(th)
        vx, vy = math.cos(th) * w / 2, -math.sin(th) * w / 2
        r0, r1 = D["tick_in"][kind], D["tick_out"]
        body.face([(cx + ux * r0 - vx, cy + uy * r0 - vy, zt), (cx + ux * r1 - vx, cy + uy * r1 - vy, zt),
                   (cx + ux * r1 + vx, cy + uy * r1 + vy, zt), (cx + ux * r0 + vx, cy + uy * r0 + vy, zt)],
                  M["ink"], (0, 0, 1))

    # Raised centre platform (inner scale plate boss) and the slotted zero adjuster in the window.
    cylinder(body, cx, cy, D["platform_r"], D["dial_z"], D["pointer_z"] + 0.06, 24, M["dial"], M["dial"])
    cylinder(body, cx, cy, D["knob_r"], D["glass_z"] - 0.01, D["glass_z"] + 0.03, 12, M["knob"], M["knob"])
    slot(body, cx, cy, D["glass_z"] + 0.034, D["knob_r"] * 1.6, 0.035, 0, M["slot"])
    body.build("ab40_body")

    # Window glass.
    glass = MB()
    glass.face([(x, y, D["glass_z"] + 0.004) for x, y, _ in glass_ring], M["glass"], (0, 0, 1))
    px.tag(glass.build("ab40_window"), part="window", role="glass")

    # Pointer: a black wedge from under the platform tapering to a knife-edge tip,
    # authored pointing straight up (mid-scale) about the dial centre.
    needle = MB()
    zt1, zt0 = D["pointer_z"], D["pointer_z"] - 0.02
    outline = [(-0.10, 0.30), (0.10, 0.30), (0.045, 1.18), (0.016, 1.32), (0.016, 1.68),
               (0.0, 1.76), (-0.016, 1.68), (-0.016, 1.32), (-0.045, 1.18)]
    P = [(cx + x, cy + y) for x, y in outline]
    for idx in ((0, 1, 2, 8), (8, 2, 3, 7), (7, 3, 4, 6), (6, 4, 5)):
        needle.face([(P[i][0], P[i][1], zt1) for i in idx], M["pointer"], (0, 0, 1))
    for i in range(len(P)):
        (x0, y0), (x1, y1) = P[i], P[(i + 1) % len(P)]
        needle.face([(x0, y0, zt0), (x1, y1, zt0), (x1, y1, zt1), (x0, y0, zt1)], M["pointer"],
                    (y1 - y0, -(x1 - x0), 0))
    pivot = (cx, cy, D["dial_z"])
    nobj = needle.build("ab40_needle", origin=pivot)
    px.tag(nobj, part="needle", role="needle", pivot=pivot, axis=(0.0, 0.0, 1.0))

    # Label anchors: numerals at the five numbered intervals, units and caption in the bottom gap.
    zl = D["dial_z"] + 0.02
    for k in range(6):
        th = math.radians(-D["sweep"] / 2 + D["sweep"] * k / 5)
        px.label(f"scale{k}", cx + D["numeral_r"] * math.sin(th), cy + D["numeral_r"] * math.cos(th), zl,
                 D["numeral_h"])
    px.label("units", cx, cy - 0.80, zl, 0.15)
    px.label("caption", cx, cy - 1.18, zl, 0.13)
    return nobj, pivot


# --------------------------------------------------------------------------- #
# Type 180 edgewise meter                                                     #
# --------------------------------------------------------------------------- #
def t180_profile():
    """(s, z) samples of the case top along the meter: window arc plus rounded end caps."""
    D = T180
    h = D["window_half"]
    sag = D["dome_z"] - D["window_end_z"]
    R = (h * h + sag * sag) / (2 * sag)

    def arc(s):
        return D["dome_z"] - (R - math.sqrt(R * R - s * s))

    window = [(-h + 2 * h * i / 12, arc(-h + 2 * h * i / 12)) for i in range(13)]
    cap_len = D["length"] / 2 - h
    cap = [(h + cap_len * math.sin(math.radians(p)), D["cap_foot_z"] + (D["window_end_z"] - D["cap_foot_z"])
            * math.cos(math.radians(p))) for p in (30, 55, 75, 90)]
    return [(-s, z) for s, z in reversed(cap)] + window + cap, arc


def build_t180(vertical, pointer_hex, fw, fh):
    """Builds a Type 180 in the current scene. Returns (needle object, travel axis)."""
    D = T180
    M = mats(pointer_hex)
    cx, cy = fw / 2, -fh / 2

    # Canonical frame: s along the scale (low -> high), t across (scale side negative), z out.
    if vertical:
        def P(s, t, z):
            return (cx + t, cy + s, z)

        def V(ds, dt, dz):
            return (dt, ds, dz)
    else:
        def P(s, t, z):
            return (cx + s, cy - t, z)

        def V(ds, dt, dz):
            return (ds, -dt, dz)

    body = MB()

    def quad(pts, mat, out):
        body.face([P(*p) for p in pts], mat, V(*out))

    prof, arc = t180_profile()
    h = D["window_half"]
    half = D["width"] / 2
    ti = half - D["rail_w"]           # rail inner edge = window edge
    tt = half - 0.077                 # rail flat top outer edge, then a 45 deg chamfer
    ch = 0.077
    zf = D["floor_z"]
    for (s0, z0), (s1, z1) in zip(prof, prof[1:]):
        ds, dz = s1 - s0, z1 - z0
        up = (-dz, 0, ds)             # outward normal of the profile (s, z) segment
        for sg in (-1, 1):
            quad([(s0, sg * ti, z0), (s1, sg * ti, z1), (s1, sg * tt, z1), (s0, sg * tt, z0)], M["case"], up)
            quad([(s0, sg * tt, z0), (s1, sg * tt, z1), (s1, sg * half, z1 - ch), (s0, sg * half, z0 - ch)],
                 M["case"], (up[0], sg, up[2]))
            quad([(s0, sg * half, z0 - ch), (s1, sg * half, z1 - ch), (s1, sg * half, 0), (s0, sg * half, 0)],
                 M["case"], (0, sg, 0))
            if abs(s0) < h - 1e-6 or abs(s1) < h - 1e-6:
                # inner wall of the window well
                quad([(s0, sg * ti, z0), (s1, sg * ti, z1), (s1, sg * ti, zf), (s0, sg * ti, zf)], M["mask"],
                     (0, -sg, 0))
        if abs(s0) >= h - 1e-6 and abs(s1) >= h - 1e-6:
            # end cap between the rails
            quad([(s0, -ti, z0), (s1, -ti, z1), (s1, ti, z1), (s0, ti, z0)], M["case"], up)
    for sg in (-1, 1):
        s_end, z_end = prof[-1] if sg > 0 else prof[0]
        body.face([P(s_end, t, z) for t, z in ((-half, 0), (-half, z_end - ch), (-tt, z_end), (tt, z_end),
                                               (half, z_end - ch), (half, 0))], M["case"], V(sg, 0, 0))
        zw = arc(h)
        quad([(sg * h, -ti, zw), (sg * h, ti, zw), (sg * h, ti, zf), (sg * h, -ti, zf)], M["mask"], (-sg, 0, 0))
    # Floor of the well (the dark mask round the scale), the scale plate, pointer channel and legend strip.
    quad([(-h, -ti, zf), (h, -ti, zf), (h, ti, zf), (-h, ti, zf)], M["mask"], (0, 0, 1))
    se = h - 0.036
    zp = zf + 0.01
    for (t0, t1), mat in ((D["plate"], M["dial"]), (D["channel"], M["channel"]), (D["legend"], M["dial"])):
        quad([(-se, t0, zp), (se, t0, zp), (se, t1, zp), (-se, t1, zp)], mat, (0, 0, 1))

    # Graduations: from the channel edge of the scale plate toward the numerals.
    s_zero = -D["scale_len"] / 2
    edge = D["plate"][1]
    zt = zp + 0.012
    n = D["divisions"]
    for k in range(n + 1):
        kind = 2 if k % 10 == 0 else 1 if k % 5 == 0 else 0
        s = s_zero + D["scale_len"] * k / n
        w = D["tick_w"][kind] / 2
        quad([(s - w, edge - D["tick_len"][kind], zt), (s - w, edge, zt), (s + w, edge, zt),
              (s + w, edge - D["tick_len"][kind], zt)], M["ink"], (0, 0, 1))

    # Zero adjuster: slotted screw in the low-end cap.
    s_adj = -(h + 0.28)
    ring = circle(0, 0, 0.085, 10)
    zc = 0.84
    for k in range(10):
        (a0, b0), (a1, b1) = ring[k], ring[(k + 1) % 10]
        quad([(s_adj + a0, b0, 0.55), (s_adj + a1, b1, 0.55), (s_adj + a1, b1, zc), (s_adj + a0, b0, zc)],
             M["knob"], ((a0 + a1) / 2, (b0 + b1) / 2, 0))
    body.face([P(s_adj + a, b, zc) for a, b in ring], M["knob"], V(0, 0, 1))
    x, y, _ = P(s_adj, 0, 0)
    slot(body, x, y, zc + 0.004, 0.14, 0.03, 90 if vertical else 0, M["slot"])
    body.build("t180_body")

    # Domed window glass between the rails.
    glass = MB()
    for (s0, z0), (s1, z1) in zip(prof, prof[1:]):
        if abs(s0) <= h + 1e-6 and abs(s1) <= h + 1e-6:
            glass.face([P(s0, -ti, z0 - 0.025), P(s1, -ti, z1 - 0.025), P(s1, ti, z1 - 0.025),
                        P(s0, ti, z0 - 0.025)], M["glass"], V(-(z1 - z0), 0, s1 - s0))
    px.tag(glass.build("t180_window"), part="window", role="glass")

    # Pointer: arrowhead riding in the channel, tip at the graduations, authored at ZERO.
    needle = MB()
    c0, c1 = D["channel"]
    outline = [(0.0, c0), (0.11, c0 + 0.11), (0.11, c1 - 0.02), (-0.11, c1 - 0.02), (-0.11, c0 + 0.11)]
    z1n, z0n = zp + 0.045, zp + 0.025
    pts = [(s_zero + ds, t) for ds, t in outline]
    needle.face([P(s, t, z1n) for s, t in (pts[0], pts[1], pts[4])], M["pointer"], V(0, 0, 1))
    needle.face([P(s, t, z1n) for s, t in (pts[1], pts[2], pts[3], pts[4])], M["pointer"], V(0, 0, 1))
    for i in range(5):
        (s0, t0), (s1, t1) = pts[i], pts[(i + 1) % 5]
        mid = (sum(p[0] for p in pts) / 5, sum(p[1] for p in pts) / 5)
        needle.face([P(s0, t0, z0n), P(s1, t1, z0n), P(s1, t1, z1n), P(s0, t0, z1n)], M["pointer"],
                    V((s0 + s1) / 2 - mid[0], (t0 + t1) / 2 - mid[1], 0))
    nobj = needle.build("t180_needle", origin=P(s_zero, c0, zp))
    axis = V(1.0, 0.0, 0.0)
    px.tag(nobj, part="needle", role="needle", axis=axis, travel=D["scale_len"])

    # Label anchors.
    zl = zp + 0.02
    for k in range(6):
        s = s_zero + D["scale_len"] * k / 5
        if vertical:
            x, y, _ = P(s, edge - D["tick_len"][2] - 0.06, 0)
            px.label(f"scale{k}", x, y, zl, D["numeral_h"], align="right")
        else:
            # centred over their ticks, except the end numerals, which are pulled in so a
            # three-digit full-scale value stays on the plate (as on the catalog photo)
            align = "left" if k == 0 else "right" if k == 5 else "center"
            ds = -0.06 if k == 0 else 0.06 if k == 5 else 0.0
            x, y, _ = P(s + ds, edge - D["tick_len"][2] - 0.06 - D["numeral_h"] / 2, 0)
            px.label(f"scale{k}", x, y, zl, D["numeral_h"], align=align)
    lt = sum(D["legend"]) / 2
    if vertical:
        x, y, _ = P(0, lt, 0)
        px.label("units", x, y, zl, 0.20, rot_deg=-90)    # reads top to bottom, as on the catalog photo
        x, y, _ = P(h + 0.15, 0, 0)
        px.label("caption", x, y, arc(h) + 0.02, 0.13)    # on the top end cap
    else:
        x, y, _ = P(-se + 0.12, lt, 0)
        px.label("caption", x, y, zl, 0.18, align="left")
        x, y, _ = P(se - 0.12, lt, 0)
        px.label("units", x, y, zl, 0.18, align="right")
    return nobj, axis


# --------------------------------------------------------------------------- #
# Export and previews                                                         #
# --------------------------------------------------------------------------- #
def preview_base(name):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    return os.path.join(PREVIEW_DIR, name)


def label_preview(base, w, h, texts, light=()):
    """Front render with sample legends at every label anchor (checks that the anchors
    read as a real scale). Text objects are not meshes, so they never reach the export."""
    ink = px.material("label_ink", PALETTE["ink"])
    white = px.material("label_white", "F0F0F0")
    made = []
    for e in [o for o in bpy.context.scene.objects if o.type == "EMPTY" and "bwr_label" in o]:
        cu = bpy.data.curves.new("lbl_" + e["bwr_label"], "FONT")
        cu.body = texts.get(e["bwr_label"], e["bwr_label"])
        cu.size = float(e["bwr_text_h"]) / 0.72        # cap height is ~0.72 em in Blender's font
        cu.align_x = e.get("bwr_align", "center").upper()
        cu.align_y = "CENTER"
        cu.materials.append(white if e["bwr_label"] in light else ink)
        ob = bpy.data.objects.new(cu.name, cu)
        ob.location = e.location
        ob.rotation_euler = e.rotation_euler
        bpy.context.scene.collection.objects.link(ob)
        made.append(ob)
    px.preview(base + "_labels", w, h, views=("front",))
    for ob in made:
        cu = ob.data
        bpy.data.objects.remove(ob)
        bpy.data.curves.remove(cu)


def make_ab40(name, pointer_hex, note):
    px.clear_scene()
    needle, pivot = build_ab40(pointer_hex)
    w = h = AB40["cover"]
    px.export(name, w, h, f"{CATALOG}: {note}")
    base = preview_base(name)

    def at(frac):
        # clockwise from 12 o'clock as seen by the operator; Blender's +Z turn is counter-clockwise
        needle.rotation_euler = (0, 0, -math.radians(-AB40["sweep"] / 2 + AB40["sweep"] * frac))

    px.preview(base, w, h)
    for tag, frac in (("min", 0.0), ("mid", 0.5), ("max", 1.0)):
        at(frac)
        px.preview(f"{base}_{tag}", w, h, views=("front",))
    px.preview(f"{base}_max", w, h, views=("three_quarter",))
    at(0.66)
    px.preview(f"{base}_far", w, h, views=("front", "three_quarter"), res=112)
    texts = {f"scale{k}": str(100 * k) for k in range(6)}
    texts.update(units="AMPS", caption="RECIRC PUMP A")
    label_preview(base, w, h, texts)


def make_t180(name, vertical, pointer_hex, note):
    px.clear_scene()
    if vertical:
        w, h = T180["width"], round(T180["width"] * 8 / 3, 4)
    else:
        w, h = round(T180["width"] * 8 / 3, 4), T180["width"]
    needle, axis = build_t180(vertical, pointer_hex, w, h)
    px.export(name, w, h, f"{CATALOG}: {note}")
    base = preview_base(name)
    rest = tuple(needle.location)

    def at(frac):
        needle.location = tuple(r + a * T180["scale_len"] * frac for r, a in zip(rest, axis))

    at(0.62)
    px.preview(base, w, h)
    for tag, frac in (("min", 0.0), ("mid", 0.5), ("max", 1.0)):
        at(frac)
        px.preview(f"{base}_{tag}", w, h, views=("front",))
    px.preview(f"{base}_max", w, h, views=("three_quarter",))
    at(0.62)
    px.preview(f"{base}_far", w, h, views=("front", "three_quarter"), res=112)
    texts = {f"scale{k}": str(20 * k) for k in range(6)}
    texts.update(units="PSIG", caption="RX PRESSURE" if vertical else "FEEDWATER FLOW")
    label_preview(base, w, h, texts, light=("caption",) if vertical else ())


AB40_NOTE = ("AB/DB-40 metal case, p.23 (cover 4.33 in square, 0.86 in in front of panel); "
             "p.2 (250 deg, 6.9 in scale, graduations on the outer scale); front-cover photographs")
T180_NOTE = ("Type 180 edgewise, p.27 DWG B3147K22 (cover 6.040 x 2.294 in, 1.065 in domed), "
             "p.25 (4.448 in scale), p.27 options 19 Day-glo red pointer and 21 black trim case")

if __name__ == "__main__":
    only = os.environ.get("BWR_ONLY", "")
    jobs = [
        ("ge_ab40", lambda n: make_ab40(n, PALETTE["pointer_black"], AB40_NOTE)),
        ("ge_ab40_red", lambda n: make_ab40(n, PALETTE["dayglo"], AB40_NOTE + "; Day-glo red pointer")),
        ("ge_180_vertical", lambda n: make_t180(n, True, PALETTE["pointer_white"], T180_NOTE)),
        ("ge_180_vertical_red", lambda n: make_t180(n, True, PALETTE["dayglo"], T180_NOTE)),
        ("ge_180_horizontal", lambda n: make_t180(n, False, PALETTE["pointer_white"], T180_NOTE)),
        ("ge_180_horizontal_red", lambda n: make_t180(n, False, PALETTE["dayglo"], T180_NOTE)),
    ]
    for name, job in jobs:
        if not only or name in only.split(","):
            job(name)
