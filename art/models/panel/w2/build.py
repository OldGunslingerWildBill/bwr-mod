"""Westinghouse Type W-2 instrument and control switches (panel model family B).

Builds and exports every W-2 model, then renders previews:

    blender --background --factory-startup --python art/models/panel/w2/build.py
    blender ... --python art/models/panel/w2/build.py -- --only w2_oval --no-render

Models (see README.md next to this file):
    w2_pistol         control_switch "westinghouse": nameplate + target + pistol grip
    w2_pistol_hd      control_switch "westinghouse" variant: heavy-duty pistol grip
    w2_oval           rotary_selector "westinghouse": oval handle
    w2_round_notched  rotary_selector variant: round notched (6-lobe) handle
    w2_mini_slim      rotary_selector variant: mini-slimline "DS" handle
    w2_key            key_switch "westinghouse": removable key handle
    w2_tumbler        key_switch variant: pistol grip with a tumbler key lock above

Every model is drawn around the switch shaft at local (0, 0); handles point UP
(arrow at 12 o'clock) in their modelled pose. Each object's origin is its pivot,
so a pose is just a Z rotation of the object. Before export every pose of every
moving part is checked against the footprint.

Previews go to tmp/panel-models/w2/: <id>_front/_three_quarter/_side (panel_export
.preview), <id>_closeup (from above-right, as a player at a benchboard sees it),
<id>_<part>_cw45 / _ccw45 / ... (each detent; the extremes also in three-quarter),
and <id>_labels_front (sample legends at the label anchors; never exported).
"""
import math
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import panel_export as px  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "w2")
DB = "Westinghouse Descriptive Bulletin 34-251 (April 1987) 'Instrument and Control Switches Type W-2'"

# ---------------------------------------------------------------------------
# Dimension table, inches. "DB p.N" = page of Westinghouse DB 34-251.
# "measured" = scaled off the drawing on that page (no printed dimension).
# ---------------------------------------------------------------------------
PLATE_W = 2.25      # dial plate / nameplate outline width: DB p.19 fig.1, p.2 oval view "2.25"
PLATE_H = 3.00      # outline height: DB p.19 fig.1, p.2 "3"
PLATE_R = 0.14      # outline corner radius: measured, DB p.19 fig.1 / p.13 nameplates
FRONT_PLATE = 0.31  # dial plate + nameplate stand-off from the panel: DB p.19 fig.1 ".31"
DIAL_T = 0.20       # die-cast aluminium dial plate (DB p.2 "Switch Dial"); split of the .31 estimated
NAME_T = 0.08       # white Cycolac ABS nameplate, engraved black (DB p.2 "Switch Dial")
NAME_W = 2.05       # nameplate between the dial plate's side rails: measured, DB p.19 fig.1 inner lines
RAIL_W = 0.07       # side rail width (bright edge either side of the nameplate, DB p.1 photos)
FRONT_STD = 2.06    # oval / pistol / round-notched handle face from the panel: DB p.19 "2.06 (fig. 4, 5, 6)"
FRONT_HD = 3.50     # heavy-duty pistol grip face from the panel: DB p.19 "3.50 (for figure 7 only)"
FRONT_DS = 0.84     # mini-slimline DS: DB p.19 ".84 (for figure 8)"; p.2 ".78"
FRONT_KEY = 0.81    # removable key (finger-tip shape): DB p.19 ".81 (for figure 9)"; p.2 ".75"
PISTOL_L = 1.31     # pistol grip, shaft centre to tip: DB p.19 fig.5 "1.31"
PISTOL_HUB = 0.72   # pistol grip hub width: DB p.2 pistol grip fixed "3/4"
HD_L = 2.50         # heavy-duty pistol grip, shaft centre to tip: DB p.19 fig.7 "2.50", p.2 "2 1/2"
OVAL_L = 2.1875     # oval handle length: DB p.2 "2 3/16"; the shaft is 1.17 from the narrow end (measured)
OVAL_W = 0.75       # oval handle width: DB p.2 "3/4"
KNOB_D = 2.00       # round notched handle across the lobes: DB p.2 "2"; 6 lobes, DB p.19 fig.6
DS_L = 1.00         # mini-slimline DS, shaft centre to tail: DB p.19 fig.8 "1", p.2 "1.0"
KEY_L = 1.88        # removable key, shaft centre to tail: DB p.19 fig.9 "1.88", p.2 finger tip "1.88"
LOCK_D = 1.4375     # tumbler lock cylinder: DB p.17 "1.4375" (drilling and front view)
LOCK_Y = 2.50       # lock centre above the shaft centre: DB p.17 "2.5" / "2.50"
LOCK_PROUD = 0.30   # cylinder face proud of the panel: measured, DB p.17 side view
PLUG_D = 0.58       # eccentric lock plug: measured, DB p.17 front view (and p.1 photo)
PLUG_DROP = 0.22    # plug centre below the cylinder centre: measured, DB p.17 front view
TARGET_Y = 0.66     # target window centre above the shaft: measured, DB p.13 692C707H14, p.14 ex.3, p.1 photo

# Palette (sRGB). Nameplate colour per DB p.2; the rest from PANEL-MODELS-SPEC.md.
ALU = "B8BCC0"       # die-cast aluminium dial plate (bright metal trim)
WHITE = "EDEBE3"     # white ABS nameplate
PHENOLIC = "1A1A1A"  # moulded phenolic handles
ARROW = "F0F0E0"     # light fill in the moulded arrow
SCREW = "3C3C3C"     # handle screw in its counterbore
WINDOW = "141414"    # target window surround
TARGET = "C0261E"    # target flag (role "flag": recoloured red/green by the game)
CHROME = "C8CCD0"    # lock cylinder face
BRASS = "B89A58"     # lock plug
SLOT = "101010"      # keyway

INLAY = 0.012        # decal offset above a face (keeps clear of z-fighting at game scale)

# ---------------------------------------------------------------------------
# Geometry helpers (all polygons counter-clockwise seen from the front, +Z).
# ---------------------------------------------------------------------------


def _unit(x, y):
    n = math.hypot(x, y)
    return (x / n, y / n) if n > 1e-12 else (0.0, 0.0)


def rounded(corners, seg=4):
    """Polygon from [(x, y, r)] corners, each filleted with radius r (0 = sharp).

    Keep every fillet radius at least the total inset later applied by prism()
    (chamfer + pocket rim), or 0: offsetting an arc by more than its radius folds it.
    """
    out = []
    n = len(corners)
    for i, (x, y, r) in enumerate(corners):
        if r <= 0:
            out.append((x, y))
            continue
        ax, ay = corners[i - 1][:2]
        bx, by = corners[(i + 1) % n][:2]
        ux, uy = _unit(ax - x, ay - y)
        vx, vy = _unit(bx - x, by - y)
        ang = math.acos(max(-1.0, min(1.0, ux * vx + uy * vy)))
        half = ang / 2
        if half < 1e-3 or math.pi - ang < 1e-3:
            out.append((x, y))
            continue
        t = min(r / math.tan(half), 0.45 * math.hypot(ax - x, ay - y), 0.45 * math.hypot(bx - x, by - y))
        r_eff = t * math.tan(half)
        mx, my = _unit(ux + vx, uy + vy)
        d = r_eff / math.sin(half)
        cx, cy = x + mx * d, y + my * d
        a1 = math.atan2(y + uy * t - cy, x + ux * t - cx)
        a2 = math.atan2(y + vy * t - cy, x + vx * t - cx)
        da = (a2 - a1 + math.pi) % (2 * math.pi) - math.pi
        for k in range(seg + 1):
            a = a1 + da * k / seg
            out.append((cx + r_eff * math.cos(a), cy + r_eff * math.sin(a)))
    return out


def circle(r, n, cx=0.0, cy=0.0, start=90.0):
    return [(cx + r * math.cos(math.radians(start + 360.0 * k / n)),
             cy + r * math.sin(math.radians(start + 360.0 * k / n))) for k in range(n)]


def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def inset(pts, d, max_miter=2.5):
    """Offsets a CCW polygon inward by d (mitred corners, miter length clamped)."""
    n = len(pts)
    out = []
    for i in range(n):
        (x0, y0), (x1, y1), (x2, y2) = pts[i - 1], pts[i], pts[(i + 1) % n]
        e1 = _unit(x1 - x0, y1 - y0)
        e2 = _unit(x2 - x1, y2 - y1)
        n1 = (-e1[1], e1[0])
        n2 = (-e2[1], e2[0])
        m = _unit(n1[0] + n2[0], n1[1] + n2[1])
        c = max(m[0] * n1[0] + m[1] * n1[1], 1.0 / max_miter)
        out.append((x1 + m[0] * d / c, y1 + m[1] * d / c))
    for i in range(n):
        (ax, ay), (bx, by) = pts[i], pts[(i + 1) % n]
        (cx, cy), (ex, ey) = out[i], out[(i + 1) % n]
        if (bx - ax) * (ex - cx) + (by - ay) * (ey - cy) < 0:
            raise ValueError(f"inset by {d} folds the outline near {pts[i]}: raise that fillet radius")
    return out


def prism(pts, z0, z1, chamfer=0.0, pocket=None):
    """Extrudes a CCW outline from z0 to z1 with a 45-degree chamfer on the front edge.

    pocket=(rim, depth) sinks the face inside a raised rim of width `rim`.
    No back face: it sits against the plate or panel and is never seen.
    Returns (verts, faces).
    """
    n = len(pts)
    verts = [(x, y, z0) for x, y in pts]
    faces = []
    rings = [0]

    def ring(poly, z):
        rings.append(len(verts))
        verts.extend((x, y, z) for x, y in poly)

    def band(a, b, down=False):
        for i in range(n):
            j = (i + 1) % n
            if down:   # pocket wall, facing into the pocket
                faces.append((a + j, b + j, b + i, a + i))
            else:
                faces.append((a + i, a + j, b + j, b + i))

    ring(pts, z1 - chamfer if chamfer > 0 else z1)
    band(rings[0], rings[1])
    top = pts
    if chamfer > 0:
        top = inset(pts, chamfer)
        ring(top, z1)
        band(rings[-2], rings[-1])
    if pocket:
        rim, depth = pocket
        inner = inset(top, rim)
        ring(inner, z1)
        band(rings[-2], rings[-1])
        ring(inner, z1 - depth)
        band(rings[-2], rings[-1], down=True)
    faces.append(tuple(range(rings[-1], rings[-1] + n)))
    return verts, faces


def tube(r0, r1, z0, z1, n=14):
    """Open frustum along Z (a handle shank)."""
    verts = [(x, y, z0) for x, y in circle(r0, n)] + [(x, y, z1) for x, y in circle(r1, n)]
    faces = [(i, (i + 1) % n, n + (i + 1) % n, n + i) for i in range(n)]
    return verts, faces


def flat(pts, z):
    return [(x, y, z) for x, y in pts], [tuple(range(len(pts)))]


def material(hexcol):
    mat = px.material("w2_" + hexcol, hexcol, roughness=0.6)
    mat.use_backface_culling = True   # like the game: a wrongly wound face vanishes in the preview
    return mat


def add(name, geom, colour, loc=(0.0, 0.0, 0.0), part="body", role="paint", warp=None, **tag):
    """Creates one mesh object from (verts, faces); its origin `loc` is the part's pivot."""
    verts, faces = geom
    if warp:
        verts = [warp(*v) for v in verts]
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.validate()
    me.update()
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.location = loc
    ob.visible_shadow = False   # the game draws no shadows; keep previews comparable
    ob.data.materials.append(material(colour))
    px.tag(ob, part, role, **tag)
    return ob


# ---------------------------------------------------------------------------
# Common nameplate assembly (DB p.5 exploded view: dial plate behind, nameplate in front).
# ---------------------------------------------------------------------------


def nameplate(c, target=False, key_bushing=False):
    """Dial plate, side rails and white nameplate centred on the shaft c = (x, y)."""
    hw, hh = PLATE_W / 2, PLATE_H / 2
    loc = (c[0], c[1], 0.0)
    outline = rounded([(-hw, -hh, PLATE_R), (hw, -hh, PLATE_R), (hw, hh, PLATE_R), (-hw, hh, PLATE_R)])
    add("dial_plate", prism(outline, 0.0, DIAL_T, chamfer=0.03), ALU, loc)
    nw, nh = NAME_W / 2, hh - 0.05
    plate = rounded([(-nw, -nh, 0.08), (nw, -nh, 0.08), (nw, nh, 0.08), (-nw, nh, 0.08)], seg=3)
    add("nameplate", prism(plate, DIAL_T, DIAL_T + NAME_T, chamfer=0.02), WHITE, loc)
    ry = hh - PLATE_R
    for side in (-1, 1):
        x0, x1 = sorted((side * nw, side * (nw + RAIL_W)))
        add(f"rail_{side}", prism(rect(x0, -ry, x1, ry), DIAL_T, FRONT_PLATE), ALU, loc)
    face = DIAL_T + NAME_T
    if target:
        # Trapezoid target window (wide side up) above the shaft, DB p.13 692C707H14.
        def trap(top_w, bot_w, y0, y1):
            return [(-bot_w / 2, y0), (bot_w / 2, y0), (top_w / 2, y1), (-top_w / 2, y1)]
        add("target_window", flat(trap(0.48, 0.32, TARGET_Y - 0.18, TARGET_Y + 0.18), face + 0.006), WINDOW, loc)
        add("target_flag", flat(trap(0.40, 0.24, TARGET_Y - 0.14, TARGET_Y + 0.14), face + INLAY),
            TARGET, loc, part="target", role="flag")
    if key_bushing:
        # Removable-key nameplates have the larger shaft opening (DB p.13 "Removable Key"); a bright
        # bushing ring shows round the key's hub.
        add("key_bushing", prism(circle(0.40, 20), face, face + 0.05, chamfer=0.015), ALU, loc)
    return face


def common_labels(c, face):
    z = face + 0.01
    px.label("caption", c[0], c[1] + 1.24, z, 0.10)       # DB p.14 table 3 lines 15/16
    px.label("maker", c[0], c[1] - 1.31, z, 0.065)        # "Westingapartment" on every DB p.13 nameplate (placeholder anchor; the game draws no maker legend)


def switch_labels(c, face, pull_legend=True):
    """Control/key switch legends, DB p.14 example 3 (STOP / AUTO / START, PULL TO LOCKOUT)."""
    z = face + 0.01
    common_labels(c, face)
    px.label("top", c[0], c[1] + 0.98, z, 0.08)
    px.label("left", c[0] - 0.46, c[1] + 0.40, z, 0.08, align="right")
    px.label("right", c[0] + 0.46, c[1] + 0.40, z, 0.08, align="left")
    if pull_legend:
        px.label("legend", c[0] - 0.38, c[1] - 0.30, z, 0.06, align="right")


def selector_labels(c, face, radius=0.55):
    """pos1..pos8 at 45-degree steps clockwise from 7:30 (pos4 at 12 o'clock, pos8 at 6 o'clock)."""
    z = face + 0.01
    common_labels(c, face)
    for k in range(8):
        a = math.radians(-135.0 + 45.0 * k)
        s = math.sin(a)
        align = "left" if s > 0.3 else "right" if s < -0.3 else "center"
        px.label(f"pos{k + 1}", c[0] + radius * s, c[1] + radius * math.cos(a), z, 0.065, align=align)


# ---------------------------------------------------------------------------
# Handles. Local frame: shaft at (0, 0), pointer (arrow) toward +Y.
# ---------------------------------------------------------------------------


def screw(name, z, loc, r=0.085, warp=None, y=0.0):
    add(name, flat(circle(r, 8, cy=y), z), SCREW, loc, part="handle", warp=warp)


def arrow(name, tip, base_y, half_w, z, loc, warp=None):
    pts = [(0.0, tip), (-half_w, base_y), (half_w, base_y)]
    add(name, flat(pts, z), ARROW, loc, part="handle", warp=warp)


def pistol(c, heavy=False):
    """Pistol-grip handle (DB p.19 fig.5) or heavy-duty pistol grip (fig.7, p.2 'large pistol grip')."""
    loc = (c[0], c[1], 0.0)
    pv = (c[0], c[1], 0.0)
    if heavy:
        # Round hub (CCW from 3 o'clock over the top), down the left side, slanted tip, up the right.
        hub_r = 0.38
        hub = [(hub_r * math.cos(math.radians(a)), 0.02 + hub_r * math.sin(math.radians(a)), 0.0)
               for a in range(0, 181, 20)]
        outline = rounded(hub + [
            (-0.30, -0.75, 0.4), (-0.31, -2.10, 0.0), (-0.27, -HD_L, 0.11),
            (0.27, -HD_L + 0.10, 0.11), (0.31, -2.00, 0.0), (0.30, -0.75, 0.4)])
        front, depth, chamfer, lean, shank = FRONT_HD, 0.80, 0.10, 0.45, (0.36, 0.32)
        arrow_tip, arrow_base, arrow_w, screw_y = 0.27, 0.12, 0.12, -0.06
        length = HD_L
    else:
        # Chamfered hub carrying the arrow, straight taper to a slanted tip (DB p.19 fig.5).
        hw = PISTOL_HUB / 2
        outline = rounded([
            (-0.25, -PISTOL_L, 0.09), (0.25, -PISTOL_L + 0.06, 0.09), (hw, 0.02, 0.0), (hw, 0.16, 0.09),
            (0.14, 0.44, 0.09), (-0.14, 0.44, 0.09), (-hw, 0.16, 0.09), (-hw, 0.02, 0.0)])
        front, depth, chamfer, lean, shank = FRONT_STD, 0.62, 0.08, 0.12, (0.31, 0.28)
        arrow_tip, arrow_base, arrow_w, screw_y = 0.33, 0.18, 0.10, -0.04
        length = PISTOL_L

    def warp(x, y, z):
        # The grip leans out toward the operator (DB p.2 large pistol grip side view).
        return (x, y, z + lean * (-y) / length)

    add("handle_grip", prism(outline, front - depth, front, chamfer=chamfer), PHENOLIC, loc,
        part="handle", warp=warp, pivot=pv)
    add("handle_shank", tube(shank[0], shank[1], FRONT_PLATE - 0.02, front - depth + 0.12), PHENOLIC, loc,
        part="handle")
    arrow("handle_arrow", arrow_tip, arrow_base, arrow_w, front + INLAY, loc, warp=warp)
    screw("handle_screw", front + INLAY, loc, warp=warp, y=screw_y)


def oval(c):
    """Oval handle (DB p.19 fig.4, p.2 'Oval Fixed'): tapered bar on a round shank, arrow toward the narrow end."""
    loc = (c[0], c[1], 0.0)
    hw = OVAL_W / 2 + 0.01
    top = OVAL_L - 1.02           # shaft sits 1.02 above the wide end, 1.17 below the narrow end (measured)
    outline = rounded([
        (-0.24, -1.02, 0.09), (0.24, -1.02, 0.09), (0.33, -0.78, 0.10), (hw, -0.08, 0.14),
        (0.17, top, 0.12), (-0.17, top, 0.12), (-hw, -0.08, 0.14), (-0.33, -0.78, 0.10)])
    depth = 0.55
    add("handle_head", prism(outline, FRONT_STD - depth, FRONT_STD, chamfer=0.08), PHENOLIC, loc,
        part="handle", pivot=(c[0], c[1], 0.0))
    add("handle_shank", tube(0.27, 0.24, FRONT_PLATE - 0.02, FRONT_STD - depth + 0.1), PHENOLIC, loc, part="handle")
    arrow("handle_arrow", 0.98, 0.30, 0.12, FRONT_STD + INLAY, loc)
    screw("handle_screw", FRONT_STD + INLAY, loc, r=0.09, y=0.0)


def round_notched(c):
    """Round notched handle (DB p.19 fig.6, p.2 'Round Notched Fixed'): six-lobe knob, raised centre."""
    loc = (c[0], c[1], 0.0)
    ro, ri = KNOB_D / 2, 0.70
    pts = []
    for k in range(48):
        th = math.radians(90.0 + 7.5 * k)
        f = (0.5 + 0.5 * math.cos(6 * (th - math.radians(90.0)))) ** 0.75
        r = ri + (ro - ri) * f
        pts.append((r * math.cos(th), r * math.sin(th)))
    depth = 0.45
    add("handle_knob", prism(pts, FRONT_STD - depth, FRONT_STD, chamfer=0.07), PHENOLIC, loc,
        part="handle", pivot=(c[0], c[1], 0.0))
    add("handle_boss", prism(circle(0.55, 24), FRONT_STD - 0.01, FRONT_STD + 0.05, chamfer=0.035), PHENOLIC, loc,
        part="handle")
    add("handle_shank", tube(0.30, 0.27, FRONT_PLATE - 0.02, FRONT_STD - depth + 0.1), PHENOLIC, loc, part="handle")
    arrow("handle_arrow", 0.88, 0.64, 0.12, FRONT_STD + INLAY, loc)
    screw("handle_screw", FRONT_STD + 0.05 + INLAY, loc, r=0.09)


def mini_slim(c):
    """Mini-slimline 'DS' handle (DB p.19 fig.8, p.2 'Mini-Slim DS'): low pointer with a raised rim."""
    loc = (c[0], c[1], 0.0)
    outline = rounded([
        (-0.15, -DS_L, 0.12), (0.15, -DS_L, 0.12), (0.255, 0.15, 0.0), (0.0, 0.44, 0.0), (-0.255, 0.15, 0.0)])
    add("handle_body", prism(outline, FRONT_PLATE - 0.02, FRONT_DS, chamfer=0.05, pocket=(0.05, 0.03)),
        PHENOLIC, loc, part="handle", pivot=(c[0], c[1], 0.0))
    floor = FRONT_DS - 0.03
    add("handle_hub", prism(circle(0.13, 12), floor - 0.01, FRONT_DS, chamfer=0.02), PHENOLIC, loc, part="handle")
    arrow("handle_arrow", 0.31, 0.17, 0.09, floor + INLAY, loc)
    screw("handle_screw", FRONT_DS + INLAY, loc, r=0.065)


def removable_key(c):
    """Removable key handle (DB p.19 fig.9 / p.2 'Finger Tip'): pointed hub, waisted shank, raised rim."""
    loc = (c[0], c[1], 0.0)
    outline = rounded([
        (-0.28, -KEY_L, 0.12), (0.28, -KEY_L, 0.12), (0.30, -1.60, 0.25), (0.17, -0.58, 0.35),
        (0.31, -0.08, 0.22), (0.22, 0.28, 0.14), (0.0, 0.50, 0.0), (-0.22, 0.28, 0.14),
        (-0.31, -0.08, 0.22), (-0.17, -0.58, 0.35), (-0.30, -1.60, 0.25)], seg=4)
    add("handle_body", prism(outline, FRONT_PLATE - 0.02, FRONT_KEY, chamfer=0.05, pocket=(0.055, 0.03)),
        PHENOLIC, loc, part="handle", pivot=(c[0], c[1], 0.0))
    floor = FRONT_KEY - 0.03
    add("handle_hub", prism(circle(0.17, 14), floor - 0.01, FRONT_KEY + 0.01, chamfer=0.025), PHENOLIC, loc,
        part="handle")
    arrow("handle_arrow", 0.40, 0.25, 0.08, floor + INLAY, loc)
    screw("handle_screw", FRONT_KEY + 0.01 + INLAY, loc, r=0.075)


def tumbler_lock(c):
    """Tumbler pin key lock cylinder above the handle (DB p.17; p.1 'Type W-2 Switch Lock' photo)."""
    lc = (c[0], c[1] + LOCK_Y, 0.0)
    add("lock_cylinder", prism(circle(LOCK_D / 2, 28), 0.0, LOCK_PROUD, chamfer=0.07), CHROME, lc)
    pc = (c[0], c[1] + LOCK_Y - PLUG_DROP, 0.0)
    add("lock_plug", prism(circle(PLUG_D / 2, 16), LOCK_PROUD - 0.02, LOCK_PROUD + 0.02, chamfer=0.015), BRASS, pc,
        part="plug", pivot=pc)
    add("lock_keyway", flat(rect(-0.035, -0.19, 0.035, 0.19), LOCK_PROUD + 0.02 + INLAY), SLOT, pc, part="plug")


# ---------------------------------------------------------------------------
# Models: footprint (w, h) in inches, shaft position, moving parts and their poses.
# Poses are degrees CLOCKWISE as seen by the operator.
# ---------------------------------------------------------------------------
SELECTOR_DETENTS = [-135, -90, -45, 0, 45, 90, 135, 180]


def model_pistol(heavy):
    w, h = (3.9, 5.85) if heavy else (2.4, 3.6)      # 2:3 (control_switch 8 x 12 cells)
    c = (w / 2, -h / 2)
    face = nameplate(c, target=True)
    pistol(c, heavy)
    switch_labels(c, face)
    return w, h, {"handle": [-45, 0, 45]}


def model_selector(handle):
    w, h = 2.4, 3.0                                   # 4:5 (rotary_selector 8 x 10 cells)
    c = (w / 2, -h / 2)
    face = nameplate(c)
    handle(c)
    selector_labels(c, face)
    return w, h, {"handle": SELECTOR_DETENTS}


def model_key():
    w, h = 3.0, 4.0                                   # 3:4 (key_switch 6 x 8 cells)
    c = (w / 2, -h / 2)
    face = nameplate(c, key_bushing=True)
    removable_key(c)
    switch_labels(c, face, pull_legend=False)
    return w, h, {"handle": [-45, 0, 45]}


def model_tumbler():
    w, h = 3.6, 4.8                                   # 3:4 (key_switch 6 x 8 cells)
    span_top, span_bot = LOCK_Y + LOCK_D / 2, -PLATE_H / 2
    c = (w / 2, -(h - (span_top - span_bot)) / 2 - span_top)
    face = nameplate(c)
    pistol(c, heavy=False)
    tumbler_lock(c)
    switch_labels(c, face, pull_legend=False)
    return w, h, {"handle": [-45, 0, 45], "plug": [0, 90]}


MODELS = {
    "w2_pistol": lambda: model_pistol(False),
    "w2_pistol_hd": lambda: model_pistol(True),
    "w2_oval": lambda: model_selector(oval),
    "w2_round_notched": lambda: model_selector(round_notched),
    "w2_mini_slim": lambda: model_selector(mini_slim),
    "w2_key": model_key,
    "w2_tumbler": model_tumbler,
}

SOURCES = {
    "w2_pistol": "p.19 fig.1 and fig.5 pistol grip, p.2 pistol grip fixed, p.13 nameplate 692C707H14 (target)",
    "w2_pistol_hd": "p.19 fig.1 and fig.7 heavy duty pistol grip, p.2 large pistol grip, p.13 692C707H14",
    "w2_oval": "p.19 fig.1 and fig.4 oval, p.2 oval fixed, p.14 table 3 engraving locations",
    "w2_round_notched": "p.19 fig.1 and fig.6 round notched, p.2 round notched fixed, p.14 table 3",
    "w2_mini_slim": "p.19 fig.1 and fig.8 mini-slimline DS, p.2 mini-slim DS, p.14 table 3",
    "w2_key": "p.19 fig.1 and fig.9 removable key, p.2 finger tip, p.13 removable-key nameplates",
    "w2_tumbler": "p.17 tumbler pin key lock, p.19 fig.1 and fig.5, p.1 switch lock photo",
}


# ---------------------------------------------------------------------------
# Posing, checks, export and previews.
# ---------------------------------------------------------------------------


def part_objects(part):
    return [o for o in bpy.context.scene.objects if o.type == "MESH" and o.get("bwr_part", "body") == part]


def pose(part, cw_degrees):
    for o in part_objects(part):
        o.rotation_euler = (0.0, 0.0, math.radians(-cw_degrees))
    bpy.context.view_layer.update()


def mesh_points():
    for o in bpy.context.scene.objects:
        if o.type == "MESH":
            for v in o.data.vertices:
                yield o, o.matrix_world @ v.co


def check_inside(name, w, h, eps=1e-4):
    for o, (x, y, z) in mesh_points():
        if x < -eps or x > w + eps or y > eps or y < -h - eps or z < -0.05 - eps:
            raise ValueError(f"{name}: {o.name} at {math.degrees(-o.rotation_euler[2]):.0f} deg cw puts "
                             f"{(round(x, 3), round(y, 3), round(z, 3))} outside {w} x {h}")


def label_overlay():
    """Temporary sample legends at the label anchors (preview only; never exported)."""
    sample = {"caption": "CIRCUIT BREAKER", "maker": "Westingapartment", "top": "NORMAL", "left": "TRIP",
              "right": "CLOSE", "legend": "PULL TO LOCK"}
    made = []
    mat = material("101010")
    for e in list(bpy.context.scene.objects):
        if e.type != "EMPTY" or "bwr_label" not in e:
            continue
        lid = e["bwr_label"]
        cu = bpy.data.curves.new("lbl_" + lid, "FONT")
        cu.body = sample.get(lid, lid.upper().replace("POS", "P"))
        cu.size = e["bwr_text_h"] / 0.7
        cu.align_x = {"left": "LEFT", "right": "RIGHT"}.get(e.get("bwr_align", "center"), "CENTER")
        cu.align_y = "CENTER"
        ob = bpy.data.objects.new("lbl_" + lid, cu)
        ob.location = e.location
        ob.rotation_euler = e.rotation_euler
        ob.visible_shadow = False
        ob.data.materials.append(mat)
        bpy.context.scene.collection.objects.link(ob)
        made.append(ob)
    return made


def closeup(path, w, h, res=640):
    """Close perspective view from above-right, as a player standing at a benchboard sees it."""
    import mathutils
    scene = bpy.context.scene
    sun = bpy.data.objects.new("cu_sun", bpy.data.lights.new("cu_sun", "SUN"))
    sun.data.energy = 3.5
    sun.rotation_euler = (math.radians(-40), math.radians(-25), math.radians(20))   # upper left, as preview()
    scene.collection.objects.link(sun)
    cam = bpy.data.objects.new("cu_cam", bpy.data.cameras.new("cu_cam"))
    scene.collection.objects.link(cam)
    scene.camera = cam
    cx, cy = w / 2, -h / 2
    d = max(w, h) * 2.2
    cam.data.lens = 50
    eye = mathutils.Vector((cx + d * 0.40, cy + d * 0.45, d * 0.80))
    fwd = (mathutils.Vector((cx, cy, 0.6)) - eye).normalized()
    right = fwd.cross(mathutils.Vector((0.0, 1.0, 0.0))).normalized()   # panel +Y is "up" in the image
    up = right.cross(fwd)
    cam.matrix_world = mathutils.Matrix((
        (right.x, up.x, -fwd.x, eye.x), (right.y, up.y, -fwd.y, eye.y),
        (right.z, up.z, -fwd.z, eye.z), (0.0, 0.0, 0.0, 1.0)))
    bpy.ops.mesh.primitive_plane_add(size=max(w, h) * 6, location=(cx, cy, -0.06))
    bd = bpy.context.active_object
    bd.data.materials.append(px.material("backdrop", "A7BCAE", 0.8))
    scene.render.resolution_x = scene.render.resolution_y = res
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    for o in (bd, sun, cam):
        bpy.data.objects.remove(o)


def build(name, render=True, res=640):
    px.clear_scene()
    bpy.context.scene.view_settings.view_transform = "Standard"
    w, h, poses = MODELS[name]()
    bpy.context.view_layer.update()
    lo_x, hi_x, lo_y, hi_y = w, 0.0, 0.0, -h
    for part, angles in poses.items():
        for a in angles:
            pose(part, a)
            check_inside(name, w, h)
            for _, p in mesh_points():
                lo_x, hi_x, lo_y, hi_y = min(lo_x, p.x), max(hi_x, p.x), min(lo_y, p.y), max(hi_y, p.y)
        pose(part, 0)
    print(f"{name}: footprint {w} x {h}; swept extent x {lo_x:.3f}..{hi_x:.3f}, y {lo_y:.3f}..{hi_y:.3f}")
    px.export(name, w, h, f"{DB}: {SOURCES[name]}")
    if not render:
        return
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    base = os.path.join(PREVIEW_DIR, name)
    px.preview(base, w, h, res=res)
    closeup(base + "_closeup.png", w, h, res=res)
    for part, angles in poses.items():
        for a in angles:
            if a == 0:
                continue
            pose(part, a)
            tag = f"{part}_{'cw' if a > 0 else 'ccw'}{abs(a)}"
            extreme = a in (min(angles), max(angles))
            views = ("front", "three_quarter") if extreme else ("front",)
            px.preview(f"{base}_{tag}", w, h, views=views, res=res if extreme else res // 2)
        pose(part, 0)
    made = label_overlay()
    px.preview(base + "_labels", w, h, views=("front",), res=res)
    for ob in made:
        bpy.data.objects.remove(ob)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = argv[argv.index("--only") + 1].split(",") if "--only" in argv else None
    render = "--no-render" not in argv
    for name in MODELS:
        if only is None or name in only:
            build(name, render=render)


main()
