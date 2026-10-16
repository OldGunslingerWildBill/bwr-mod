"""Family F: Yokogawa DX1000 and DX2000 paperless recorder bezels (`paperless_recorder` component).

Builds, checks, exports and previews, headless, from the repo root:

    blender --background --factory-startup --python art/models/panel/recorders/build.py
    blender ... --python art/models/panel/recorders/build.py -- --only yokogawa_dx1000 --no-preview

  yokogawa_dx1000   style `yokogawa_dx1000`: 144 mm square front, 5.7" 4:3 LCD, one key strip
  yokogawa_dx2000   style `yokogawa_dx2000`: 288 mm square front, 10.4" 4:3 LCD, key strip under a
                    hinged key-panel cover (part `key_cover`, opens about 100 degrees)

The part names, roles and footprints are the "Model / code contract" of DX1000-RECORDER-SPEC.md
section 7. Previews go to tmp/panel-models/recorders/ (git-ignored).

AUTHORING. Everything below is authored in MILLIMETRES, x right and y DOWN from the face's top-left
corner, z out of the panel face (z = 0 is the panel the recorder is mounted in), exactly the
exported model space but in mm; P() converts to the Blender scene (inches, y up) that
panel_export.export() reads. Faces are built with an explicit "outward" vector (fq), so winding and
the exported normals are right without relying on vertex order.

SOURCES (full list with pages in README.md). Layout fractions are measured from the Yokogawa
drawings: DX2000 Operation Guide IM 04L42B01-02E p.10 (Names of Parts, front view) and p.14 (Panel
Keys), p.51 (External Dimensions); DX1000 User's Manual IM 04L41B01-01E p.4-2 (key drawing, 4.1);
the closed key-panel cover from the Yokogawa FX1000 bulletin 04L21B01-01EN cover photograph (same
front family). Where a drawing is silent a value is marked [ASM] (assumed).
"""
import math
import os
import sys

import bpy
import bmesh
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import panel_export as px  # noqa: E402
import panel_geo as pg  # noqa: E402
from panel_geo import Geo  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "recorders")
MM = 1.0 / 25.4

# ---------------------------------------------------------------------------------------------
# Palette (sRGB). Bezel is "charcoal gray light" Munsell 10B3.6/0.3 ~ 4A5157 ([UM] 13.6 / spec 7).
# ---------------------------------------------------------------------------------------------
C = {
    "bezel": "4A5157",
    "frustum": "3A4046",     # sloped LCD frame, a shade darker than the face
    "plate": "202528",       # key plate (pocket floor)
    "cap": "30363B",         # key caps
    "skirt": "8A939A",       # key outline ring
    "ring": "3B4248",        # DISP/ENTER ring plate
    "screen": "101418",      # contract: near-black
    "cover": "4C535A",
    "cover_back": "2A2F33",
    "label": "DCDCD2",       # paper label strip on the key-panel cover
    "tab": "383E44",
    "mark": "C9CED2",        # arrow triangles
    "led": "E02020",
    "sensor": "14171A",
    "indicator": "2A2F33",
}


def mats():
    return {k: px.material("rec_" + k, v, 0.55 if k not in ("led",) else 0.4) for k, v in C.items()}


# ---------------------------------------------------------------------------------------------
# Specs (mm). [DWG] = read off a Yokogawa drawing, [PH] = Yokogawa photo, [ASM] = assumed.
# ---------------------------------------------------------------------------------------------
def stadium_spec(cx, cy, w, h):
    return (cx, cy, w, h)


def dx2000_spec():
    S = dict(
        name="yokogawa_dx2000", W=288.0, H=288.0,            # [DWG] OG p.51: 288 mm front
        zb=27.3,                                             # [DWG] OG p.51: 27.3 mm bezel projection
        chamfer=2.5,                                         # [ASM] rounded front edge
        opening=(5.6, 27.4, 282.4, 207.6),                   # [DWG] OG p.10: LCD frame outer edge
        screen=(37.3, 38.5, 250.7, 198.5),                   # [DWG] OG p.10: 213.3 x 160 = 4:3 (10.4" = 211 x 158)
        screen_depth=15.0,                                   # [ASM] LCD deep-set (OG p.13 3/4 view)
        pocket=(32.5, 211.0, 219.0, 280.5),                  # [DWG] OG p.14 key plate
        pocket_depth=10.5, key_h=4.0, key_inset=1.1,         # [ASM]
        cover=(33.5, 213.0, 218.0, 278.3), cover_t=3.5,      # [DWG] OG p.10/14 cover; [ASM] thickness
        hinge_y=278.3,
        tab=(127.0, 204.0, 161.0, 220.0),                    # [DWG] OG p.10 tab, centred, straddles the frame
        label_strip=(41.0, 222.0, 210.0, 262.0),             # [DWG] OG p.10 "label (front of key panel cover)"
        ring=(247.5, 245.0, 27.5, 14.3),                     # [DWG] OG p.14: DISP/ENTER ring 55 mm, disc 28.6 mm
        led=(21.5, 233.0, 1.9), sensor=(17.5, 240.0, 25.5, 249.0),
        indicator=(13.2, 225.7, 29.7, 251.6),                # [DWG] OG p.10: power indicator / remote sensor box
        text_big=5.2, text_key=2.7, text_pad=3.3,
    )
    pitch = 25.9
    soft = [(50.7 + k * pitch, 230.7, 17.0, 8.6) for k in range(7)]
    keys = {
        "key_start": ("stadium", 53.2, 249.5, 17.3, 8.2),
        "key_stop": ("round", 52.2, 264.2, 11.0, 11.0),
        "key_esc": ("stadium", 79.5, 249.5, 16.8, 8.2),
        "key_menu": ("stadium", 100.1, 249.5, 16.8, 8.2),
        "key_user": ("stadium", 79.5, 269.9, 16.8, 8.2),
        "key_func": ("stadium", 100.1, 269.9, 16.8, 8.2),
        "key_fav": ("stadium", 208.5, 268.5, 17.0, 12.5),
    }
    pad = [(x, y, 15.4, 7.6) for y in (245.0, 254.4, 264.0, 274.4) for x in (138.9, 160.4, 182.0)]
    S.update(soft=soft, keys=keys, pad=pad, has_cover=True,
             maker=(14.0, 13.7, "left"), model=(274.0, 10.5, "right"), series=(274.0, 19.5, "right"))
    return S


def dx1000_spec():
    k = 0.38      # mm per drawing px of DX1000 UM p.4-2: strip + ring = 323 px -> 123 mm of the 144 mm front
    ox, oy = 10.6, 112.0

    def X(px_):
        return ox + (px_ - 355) * k

    def Y(py_):
        return oy + (py_ - 233) * k

    S = dict(
        name="yokogawa_dx1000", W=144.0, H=144.0,            # spec 7: 144 mm square front
        zb=25.0,                                             # [ASM] bezel projection (no DX1000 drawing on disk)
        chamfer=1.6,
        opening=(5.0, 12.0, 139.0, 107.5),                   # [ASM] from the DX2000 proportions, scaled
        screen=(14.1, 17.0, 129.9, 103.9),                   # 5.7" active 115.8 x 86.9 = 4:3
        screen_depth=8.0,
        pocket=(ox, oy, X(598), Y(310)),                     # [DWG] UM p.4-2 key strip 243 x 77 px
        pocket_depth=1.2, key_h=2.2, key_inset=0.5,
        led=(X(362), Y(243), 1.15),                          # the circle at the strip's top left, UM p.4-2
        ring=(X(640), Y(265), 14.4, 7.2),                    # [DWG] UM p.4-2 ring 76 px dia
        has_cover=False, text_big=3.4, text_key=1.5, text_pad=0.0,
    )
    soft = [(X(px_), Y(254), 14.4, 6.1) for px_ in (388, 434, 480, 526, 571)]
    keys = {
        "key_start": ("stadium", X(386), Y(287), 11.4, 5.3),
        "key_stop": ("round", X(421), Y(287), 6.2, 6.2),
        "key_user": ("stadium", X(453), Y(287), 9.6, 5.3),
        "key_func": ("stadium", X(483), Y(287), 9.6, 5.3),
        "key_esc": ("stadium", X(520), Y(287), 9.6, 5.3),
        "key_menu": ("stadium", X(551), Y(287), 9.6, 5.3),
        "key_fav": ("stadium", X(588), Y(287), 10.6, 8.2),
    }
    S.update(soft=soft, keys=keys, pad=[],
             maker=(9.0, 6.0, "left"), model=(135.0, 6.0, "right"), series=None)
    return S


# ---------------------------------------------------------------------------------------------
# Geometry helpers (mm, y down)
# ---------------------------------------------------------------------------------------------
def P(x, y, z):
    return (x * MM, -y * MM, z * MM)


def newell(pts):
    nx = ny = nz = 0.0
    for i, (x1, y1, z1) in enumerate(pts):
        x2, y2, z2 = pts[(i + 1) % len(pts)]
        nx += (y1 - y2) * (z1 + z2)
        ny += (z1 - z2) * (x1 + x2)
        nz += (x1 - x2) * (y1 + y2)
    return nx, ny, nz


def fq(g, pts, out, m=0):
    """One face from mm points (y down); `out` (mm space) is any vector on the side it must face."""
    bp = [P(*p) for p in pts]
    bo = (out[0], -out[1], out[2])
    n = newell(bp)
    if n[0] * bo[0] + n[1] * bo[1] + n[2] * bo[2] < 0:
        bp.reverse()
    g.face([g.vert(p) for p in bp], m)


def box(g, x0, y0, z0, x1, y1, z1, m=0, back=False, mback=None, sides=True):
    fq(g, [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1), m)
    if sides:
        fq(g, [(x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1)], (1, 0, 0), m)
        fq(g, [(x0, y0, z0), (x0, y1, z0), (x0, y1, z1), (x0, y0, z1)], (-1, 0, 0), m)
        fq(g, [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), m)
        fq(g, [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0), m)
    if back:
        fq(g, [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)], (0, 0, -1), m if mback is None else mback)


def stadium_pts(cx, cy, w, h, n=4):
    r = h / 2
    d = max(0.0, w / 2 - r)
    pts = []
    for i in range(n + 1):                       # right end, -90 .. +90
        a = -math.pi / 2 + math.pi * i / n
        pts.append((cx + d + r * math.cos(a), cy + r * math.sin(a)))
    for i in range(n + 1):                       # left end, +90 .. +270
        a = math.pi / 2 + math.pi * i / n
        pts.append((cx - d + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def circle_pts(cx, cy, r, n=12):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def key_body(g, kind, cx, cy, w, h, z0, z1, inset, m_skirt, m_cap):
    """A key: sloped skirt in the outline colour, flat cap inset all round, as the drawings' double outline."""
    if kind == "round":
        outer, inner = circle_pts(cx, cy, w / 2), circle_pts(cx, cy, w / 2 - inset)
    else:
        outer = stadium_pts(cx, cy, w, h)
        inner = stadium_pts(cx, cy, w - 2 * inset, h - 2 * inset)
    n = len(outer)
    for i in range(n):
        j = (i + 1) % n
        mid = ((outer[i][0] + outer[j][0]) / 2 - cx, (outer[i][1] + outer[j][1]) / 2 - cy)
        fq(g, [(*outer[i], z0), (*outer[j], z0), (*inner[j], z1), (*inner[i], z1)], (mid[0], mid[1], 0.5), m_skirt)
    fq(g, [(*p, z1) for p in inner], (0, 0, 1), m_cap)


def sector(g, cx, cy, r0, r1, a0, a1, z0, z1, m_wall, m_top, n=4):
    """Annular sector (angles in the y-down frame, radians), walls and flat top."""
    angs = [a0 + (a1 - a0) * i / n for i in range(n + 1)]
    for i in range(n):
        p = lambda r, a, z: (cx + r * math.cos(a), cy + r * math.sin(a), z)
        fq(g, [p(r0, angs[i], z1), p(r1, angs[i], z1), p(r1, angs[i + 1], z1), p(r0, angs[i + 1], z1)], (0, 0, 1), m_top)
        am = (angs[i] + angs[i + 1]) / 2
        fq(g, [p(r1, angs[i], z0), p(r1, angs[i + 1], z0), p(r1, angs[i + 1], z1), p(r1, angs[i], z1)],
           (math.cos(am), math.sin(am), 0), m_wall)
        fq(g, [p(r0, angs[i], z0), p(r0, angs[i + 1], z0), p(r0, angs[i + 1], z1), p(r0, angs[i], z1)],
           (-math.cos(am), -math.sin(am), 0), m_wall)
    for a, s in ((angs[0], -1), (angs[-1], 1)):
        p = lambda r, z: (cx + r * math.cos(a), cy + r * math.sin(a), z)
        fq(g, [p(r0, z0), p(r1, z0), p(r1, z1), p(r0, z1)], (-s * math.sin(a), s * math.cos(a), 0), m_wall)


def disc(g, cx, cy, r, z0, z1, m_wall, m_top, n=16):
    ring = circle_pts(cx, cy, r, n)
    for i in range(n):
        j = (i + 1) % n
        mid = ((ring[i][0] + ring[j][0]) / 2 - cx, (ring[i][1] + ring[j][1]) / 2 - cy, 0)
        fq(g, [(*ring[i], z0), (*ring[j], z0), (*ring[j], z1), (*ring[i], z1)], mid, m_wall)
    fq(g, [(*p, z1) for p in ring], (0, 0, 1), m_top)


def face_with_holes(g, region, holes, z, m):
    """Front face rectangle `region` minus axis-aligned `holes` (disjoint), as quads at height z."""
    x0, y0, x1, y1 = region
    ys = sorted({y0, y1} | {h[1] for h in holes} | {h[3] for h in holes})
    for ya, yb in zip(ys, ys[1:]):
        cuts = sorted((h[0], h[2]) for h in holes if h[1] <= ya + 1e-9 and h[3] >= yb - 1e-9)
        x = x0
        for cx0, cx1 in cuts + [(x1, x1)]:
            if cx0 > x + 1e-9:
                fq(g, [(x, ya, z), (cx0, ya, z), (cx0, yb, z), (x, yb, z)], (0, 0, 1), m)
            x = max(x, cx1)


def hole_walls(g, hole, z_top, z_floor, m):
    x0, y0, x1, y1 = hole
    fq(g, [(x0, y0, z_floor), (x1, y0, z_floor), (x1, y0, z_top), (x0, y0, z_top)], (0, 1, 0), m)
    fq(g, [(x0, y1, z_floor), (x1, y1, z_floor), (x1, y1, z_top), (x0, y1, z_top)], (0, -1, 0), m)
    fq(g, [(x0, y0, z_floor), (x0, y1, z_floor), (x0, y1, z_top), (x0, y0, z_top)], (1, 0, 0), m)
    fq(g, [(x1, y0, z_floor), (x1, y1, z_floor), (x1, y1, z_top), (x1, y0, z_top)], (-1, 0, 0), m)


def lab(label_id, x, y, z, h, align="center", rot=0.0):
    px.label(label_id, x * MM, -y * MM, z * MM, h * MM, align, rot)


# ---------------------------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------------------------
def build(S):
    px.clear_scene()
    M = mats()
    W, H, zb, c = S["W"], S["H"], S["zb"], S["chamfer"]
    mi = {k: i for i, k in enumerate(C)}
    opening, screen, pocket = S["opening"], S["screen"], S["pocket"]
    zs = zb - S["screen_depth"]
    zf = zb - S["pocket_depth"]
    sx0, sy0, sx1, sy1 = screen
    ox0, oy0, ox1, oy1 = opening
    out = {}

    # ---- bezel: shell, chamfer, front face with the LCD and key-strip openings, frustum, pocket walls
    g = Geo(*[M[k] for k in C])
    B, FR, PL = mi["bezel"], mi["frustum"], mi["plate"]
    for (a, b, o) in (((0, 0), (W, 0), (0, -1, 0)), ((W, 0), (W, H), (1, 0, 0)),
                      ((W, H), (0, H), (0, 1, 0)), ((0, H), (0, 0), (-1, 0, 0))):
        fq(g, [(*a, 0), (*b, 0), (*b, zb - c), (*a, zb - c)], o, B)
    cor = [(0, 0), (W, 0), (W, H), (0, H)]
    inn = [(c, c), (W - c, c), (W - c, H - c), (c, H - c)]
    for i in range(4):
        j = (i + 1) % 4
        mid = ((cor[i][0] + cor[j][0]) / 2 - W / 2, (cor[i][1] + cor[j][1]) / 2 - H / 2, 0.6)
        fq(g, [(*cor[i], zb - c), (*cor[j], zb - c), (*inn[j], zb), (*inn[i], zb)], mid, B)
    face_with_holes(g, (c, c, W - c, H - c), [opening, pocket], zb, B)
    fq(g, [(ox0, oy0, zb), (ox1, oy0, zb), (sx1, sy0, zs), (sx0, sy0, zs)], (0, 1, 1), FR)
    fq(g, [(ox0, oy1, zb), (ox1, oy1, zb), (sx1, sy1, zs), (sx0, sy1, zs)], (0, -1, 1), FR)
    fq(g, [(ox0, oy0, zb), (ox0, oy1, zb), (sx0, sy1, zs), (sx0, sy0, zs)], (1, 0, 1), FR)
    fq(g, [(ox1, oy0, zb), (ox1, oy1, zb), (sx1, sy1, zs), (sx1, sy0, zs)], (-1, 0, 1), FR)
    hole_walls(g, pocket, zb, zf, mi["bezel"] if S["has_cover"] else PL)
    if S["has_cover"]:
        ix0, iy0, ix1, iy1 = S["indicator"]
        box(g, ix0, iy0, zb, ix1, iy1, zb + 0.8, mi["indicator"])
        sx_0, sy_0, sx_1, sy_1 = S["sensor"]
        fq(g, [(sx_0, sy_0, zb + 0.85), (sx_1, sy_0, zb + 0.85), (sx_1, sy_1, zb + 0.85), (sx_0, sy_1, zb + 0.85)],
           (0, 0, 1), mi["sensor"])
    g.build("bezel", part="bezel")

    # ---- key plate (pocket floor)
    g = Geo(*[M[k] for k in C])
    px0, py0, px1, py1 = pocket
    fq(g, [(px0, py0, zf), (px1, py0, zf), (px1, py1, zf), (px0, py1, zf)], (0, 0, 1), PL)
    g.build("strip_plate", part="strip_plate")

    # ---- screen: one flat rectangle at the bottom of the frustum
    g = Geo(*[M[k] for k in C])
    fq(g, [(sx0, sy0, zs), (sx1, sy0, zs), (sx1, sy1, zs), (sx0, sy1, zs)], (0, 0, 1), mi["screen"])
    g.build("screen", part="screen")

    # ---- keys
    z_top = zf + S["key_h"]

    def one_key(part, kind, cx, cy, w, h):
        gg = Geo(*[M[k] for k in C])
        key_body(gg, kind, cx, cy, w, h, zf, z_top, S["key_inset"], mi["skirt"], mi["cap"])
        gg.build(part, part=part)

    for i, (cx, cy, w, h) in enumerate(S["soft"]):
        one_key(f"key_soft{i + 1}", "stadium", cx, cy, w, h)
    for part, (kind, cx, cy, w, h) in S["keys"].items():
        one_key(part, kind, cx, cy, w, h)
    if S["pad"]:
        gg = Geo(*[M[k] for k in C])
        for cx, cy, w, h in S["pad"]:
            key_body(gg, "stadium", cx, cy, w, h, zf, z_top, S["key_inset"] * 0.8, mi["skirt"], mi["cap"])
        gg.build("keypad", part="keypad")

    # ---- DISP/ENTER ring: base plate, centre key, four arrow sectors (each with a marker triangle)
    rcx, rcy, R, r_in = S["ring"]
    z_ring = zb + (1.5 if S["has_cover"] else 0.8)
    gg = Geo(*[M[k] for k in C])
    disc(gg, rcx, rcy, R, zb, z_ring, mi["ring"], mi["ring"], n=24)
    gg.build("ring_base", part="ring_base")
    z_k = z_ring + (2.2 if S["has_cover"] else 1.2)
    gg = Geo(*[M[k] for k in C])
    disc(gg, rcx, rcy, r_in, z_ring, z_k, mi["skirt"], mi["cap"], n=16)
    gg.build("key_disp", part="key_disp")
    half = math.radians(32.0)        # sector half-angle: bounding boxes of the four arrows stay disjoint (tan a <= r0/r1)
    r1 = R * 0.955
    r0 = max(r1 * 0.66, r_in * 1.2)
    z_a = z_ring + (1.8 if S["has_cover"] else 1.0)
    dirs = {"key_up": -math.pi / 2, "key_right": 0.0, "key_down": math.pi / 2, "key_left": math.pi}
    for part, a in dirs.items():
        gg = Geo(*[M[k] for k in C])
        sector(gg, rcx, rcy, r0, r1, a - half, a + half, z_ring, z_a, mi["skirt"], mi["cap"])
        rm = (r0 + r1) / 2
        tx, ty = rcx + rm * math.cos(a), rcy + rm * math.sin(a)
        s = (r1 - r0) * 0.17
        ux, uy = math.cos(a), math.sin(a)          # triangle points outward
        vx, vy = -uy, ux
        tri = [(tx + ux * s * 1.1, ty + uy * s * 1.1), (tx - ux * s * 0.8 + vx * s, ty - uy * s * 0.8 + vy * s),
               (tx - ux * s * 0.8 - vx * s, ty - uy * s * 0.8 - vy * s)]
        fq(gg, [(*p, z_a + 0.12) for p in tri], (0, 0, 1), mi["mark"])
        gg.build(part, part=part)

    # ---- power LED
    lx, ly, lr = S["led"]
    gg = Geo(*[M[k] for k in C])
    disc(gg, lx, ly, lr, zf if not S["has_cover"] else zb + 0.8, (zf + 1.2) if not S["has_cover"] else zb + 1.7,
         mi["led"], mi["led"], n=10)
    gg.build("power_led", part="power_led", role="lamp", tint="red")

    # ---- key-panel cover (DX2000): hinged along its bottom edge, opens forward and down
    if S["has_cover"]:
        cx0, cy0, cx1, cy1 = S["cover"]
        t = S["cover_t"]
        z1 = zb - 0.5
        z0 = z1 - t
        hy = S["hinge_y"]
        hinge = (S["W"] / 2, hy, z1)      # hinge line along the front-bottom edge of the cover
        gg = Geo(*[M[k] for k in C])
        box(gg, cx0, cy0, z0, cx1, cy1, z1, mi["cover"], back=True, mback=mi["cover_back"])
        lx0, ly0, lx1, ly1 = S["label_strip"]
        fq(gg, [(lx0, ly0, z1 + 0.25), (lx1, ly0, z1 + 0.25), (lx1, ly1, z1 + 0.25), (lx0, ly1, z1 + 0.25)],
           (0, 0, 1), mi["label"])
        # label strip frame: a thin raised border
        for (a0, b0, a1, b1) in ((lx0 - 1, ly0 - 1, lx1 + 1, ly0), (lx0 - 1, ly1, lx1 + 1, ly1 + 1),
                                 (lx0 - 1, ly0, lx0, ly1), (lx1, ly0, lx1 + 1, ly1)):
            box(gg, a0, b0, z1, a1, b1, z1 + 0.25, mi["cover"], sides=False)
        hp = P(*hinge)
        gg.build("key_cover", part="key_cover", role="paint", origin=hp, pivot=hp, axis=(-1, 0, 0))
        out["cover"] = bpy.data.objects["key_cover"]
        # tab: straddles the join between the LCD frame and the cover, a click target
        tx0, ty0, tx1, ty1 = S["tab"]
        gg = Geo(*[M[k] for k in C])
        zt0, zt1 = zb - 4.0, zb + 2.2
        box(gg, tx0, ty0, zt0, tx1, ty1, zt1, mi["tab"])
        cxm = (tx0 + tx1) / 2
        fq(gg, [(cxm - 4.5, ty0 + 5.0, zt1 + 0.12), (cxm + 4.5, ty0 + 5.0, zt1 + 0.12), (cxm, ty0 + 11.5, zt1 + 0.12)],
           (0, 0, 1), mi["mark"])
        gg.build("cover_tab", part="cover_tab")
        out["tab"] = bpy.data.objects["cover_tab"]

    # ---- label anchors (legends are drawn by the game)
    zk = z_top + 0.06
    th = S["text_key"]
    mk, md, sr = S["maker"], S["model"], S["series"]
    lab("maker", mk[0], mk[1], zb + 0.05, S["text_big"], mk[2])
    lab("model", md[0], md[1], zb + 0.05, S["text_big"] * 0.95, md[2])
    if sr:
        lab("series", sr[0], sr[1], zb + 0.05, S["text_big"] * 0.5, sr[2])
    for part in ("key_start", "key_stop", "key_user", "key_func", "key_esc", "key_menu"):
        _, cx, cy, w, h = S["keys"][part]
        lab(part[4:], cx, cy, zk, th)
    _, cx, cy, w, h = S["keys"]["key_fav"]
    lab("fav", cx, cy, zk, th)
    lab("disp", rcx, rcy - r_in * 0.2, z_k + 0.06, th * 0.95)
    lab("enter", rcx, rcy + r_in * 0.28, z_k + 0.06, th * 0.7)
    if S["pad"]:
        for i, (cx, cy, w, h) in enumerate(S["pad"]):
            lab(f"pad{i + 1}", cx, cy, zk, S["text_pad"])
    if S["has_cover"]:
        lx0, ly0, lx1, ly1 = S["label_strip"]
        lab("cover_label", (lx0 + lx1) / 2, (ly0 + ly1) / 2, z1 + 0.3, 5.0)
    return out


# ---------------------------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------------------------
def part_verts():
    """{part: [(x, y, z) in exported model space, inches]} from the scene's tagged meshes."""
    deps = bpy.context.evaluated_depsgraph_get()
    res = {}
    for o in bpy.context.scene.objects:
        if o.type != "MESH":
            continue
        ev = o.evaluated_get(deps)
        me = ev.to_mesh()
        mw = o.matrix_world
        res.setdefault(o["bwr_part"], []).extend(((mw @ v.co).x, -(mw @ v.co).y, (mw @ v.co).z) for v in me.vertices)
        ev.to_mesh_clear()
    return res


def bounds(verts):
    xs, ys, zs = zip(*verts)
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def check(S, out):
    W_in, H_in = S["W"] * MM, S["H"] * MM
    pv = part_verts()
    bb = {k: bounds(v) for k, v in pv.items()}
    errs = []
    need = ["screen", "key_start", "key_stop", "key_user", "key_func", "key_esc", "key_menu", "key_fav",
            "key_disp", "key_up", "key_down", "key_left", "key_right", "power_led"]
    nsoft = len(S["soft"])
    need += [f"key_soft{i}" for i in range(1, nsoft + 1)]
    if S["has_cover"]:
        need += ["keypad", "key_cover", "cover_tab"]
    for n in need:
        if n not in bb:
            errs.append("missing part " + n)
    # screen: single quad, 4:3 within 1 %, recessed behind the bezel face, inside the face
    sv = pv["screen"]
    (x0, y0, z0), (x1, y1, z1) = bb["screen"]
    aspect = (x1 - x0) / (y1 - y0)
    if len(sv) != 4 or abs(aspect / (4 / 3) - 1) > 0.01:
        errs.append(f"screen is not a 4:3 quad ({len(sv)} verts, aspect {aspect:.4f})")
    zface = S["zb"] * MM
    if z1 > zface - 0.1:
        errs.append("screen is not recessed behind the bezel face")
    for n in need:
        if n == "screen" or n not in bb:
            continue
        (a0, b0, c0), (a1, b1, c1) = bb[n]
        if a0 < -1e-4 or b0 < -1e-4 or a1 > W_in + 1e-4 or b1 > H_in + 1e-4:
            errs.append(f"{n} outside the footprint")
    # keys are paint parts that must not overlap each other in the face plane
    keys = [n for n in bb if n.startswith("key_") and n != "key_cover"] + (["keypad"] if "keypad" in bb else [])
    for i, a in enumerate(keys):
        for b in keys[i + 1:]:
            (a0, b0, _), (a1, b1, _) = bb[a]
            (c0, d0, _), (c1, d1, _) = bb[b]
            if a0 < c1 - 1e-4 and c0 < a1 - 1e-4 and b0 < d1 - 1e-4 and d0 < b1 - 1e-4:
                errs.append(f"key hit boxes overlap: {a} / {b}")
    # keys sit on the plate, below the closed cover's back
    if S["has_cover"]:
        cover_back = (S["zb"] - 0.5 - S["cover_t"]) * MM
        for n in keys:
            if bb[n][1][2] > cover_back and n not in ("key_disp", "key_up", "key_down", "key_left", "key_right"):
                errs.append(f"{n} reaches into the closed cover")
        # open pose: rotate the cover by +100 deg about the hinge; must stay in the footprint
        hp = out["cover"]["bwr_pivot"]
        px_, py_, pz_ = hp[0], -hp[1], hp[2]                 # exported-space pivot
        th = math.radians(100.0)
        for n in ("key_cover",):
            for (x, y, z) in pv[n]:
                dy, dz = y - py_, z - pz_
                # Rodrigues about a = (-1, 0, 0) in the y-down frame: a x v = (0, v.z, -v.y)
                ny = dy * math.cos(th) + dz * math.sin(th)
                nz = dz * math.cos(th) - dy * math.sin(th)
                Y, Z = py_ + ny, pz_ + nz
                if Y > H_in + 0.08 or Y < -1e-4 or Z > 8.0 or Z < -0.05:
                    errs.append(f"open {n} leaves the footprint at ({x:.2f}, {Y:.2f}, {Z:.2f})")
                    break
    return bb, errs


def open_cover_extent(out, S):
    """Exported-space (max y, max z) of the cover when open 100 deg, for the report."""
    pv = part_verts()
    hp = out["cover"]["bwr_pivot"]
    py_, pz_ = -hp[1], hp[2]
    th = math.radians(100.0)
    mx = [-1e9, -1e9, 1e9]
    for (x, y, z) in pv["key_cover"]:
        dy, dz = y - py_, z - pz_
        Y = py_ + dy * math.cos(th) + dz * math.sin(th)
        Z = pz_ + dz * math.cos(th) - dy * math.sin(th)
        mx = [max(mx[0], Y), max(mx[1], Z), min(mx[2], Y)]
    return mx


def outward_check():
    """Every front-facing piece of the bezel should report a +Z normal; counts faces by normal sign."""
    bad = 0
    for o in bpy.context.scene.objects:
        if o.type == "MESH" and o["bwr_part"] in ("screen", "strip_plate"):
            for p in o.data.polygons:
                if p.normal.z < 0.99:
                    bad += 1
    return bad


# ---------------------------------------------------------------------------------------------
# Previews (never exported)
# ---------------------------------------------------------------------------------------------
SAMPLE_TXT = {
    "maker": ("YOKOGAWA", "E8E8E8", 0), "model": ("DX2000", "E8E8E8", 0), "series": ("DAQSTATION", "BFC4C8", 0),
    "start": ("START", "F0F0F0", 0), "stop": ("STOP", "F0F0F0", 0), "user": ("USER", "F0F0F0", 0),
    "func": ("FUNC", "F0F0F0", 0), "esc": ("ESC", "F0F0F0", 0), "menu": ("MENU", "F0F0F0", 0),
    "fav": ("FAV", "F0F0F0", 0), "disp": ("DISP/", "F0F0F0", 0), "enter": ("ENTER", "F0F0F0", 0),
    "cover_label": ("CHANNEL LABELS", "202020", 0),
}
for _i, _t in enumerate("123456789-0."):
    SAMPLE_TXT[f"pad{_i + 1}"] = (_t, "F0F0F0", 0)


def sample_legends(samples):
    """Preview-only: draw sample text at each label anchor, honouring its alignment."""
    out = []
    for lid, e in pg.label_empties().items():
        if lid in samples:
            txt, col, emit = samples[lid]
            out.append(pg.add_text(txt, e.location.x, e.location.y, e.location.z + 0.003, float(e["bwr_text_h"]),
                                   col, emit, align=e.get("bwr_align", "center").upper(), name="sample_" + lid))
    return out


def preview_screen(S, spec_name):
    """Preview-only: a sample trend picture on the screen so the render reads as a recorder."""
    scr = bpy.data.objects["screen"]
    img_path = os.path.join(HERE, "..", "..", "..", "..", "tmp", "panel-models", "recorders", "_screen.png")
    if not os.path.exists(img_path):
        return
    mat = bpy.data.materials.new("screen_prev")
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(os.path.abspath(img_path))
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 0.9
    bsdf.inputs["Base Color"].default_value = (0, 0, 0, 1)
    uv = scr.data.uv_layers.new(name="uv") if not scr.data.uv_layers else scr.data.uv_layers[0]
    # screen quad: map its corners (bounds) to 0..1
    xs = [v.co.x for v in scr.data.vertices]
    ys = [v.co.y for v in scr.data.vertices]
    for loop in scr.data.loops:
        v = scr.data.vertices[loop.vertex_index].co
        uv.data[loop.index].uv = ((v.x - min(xs)) / (max(xs) - min(xs)), (v.y - min(ys)) / (max(ys) - min(ys)))
    scr.data.materials.clear()
    scr.data.materials.append(mat)


def render_all(S, out, only_open=False):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    name = S["name"]
    W, H = S["W"] * MM, S["H"] * MM
    base = os.path.join(PREVIEW_DIR, name)
    outs = []
    preview_screen(S, name)
    if not only_open:
        outs += px.preview(base, W, H, res=1000)
        # legends at the anchors, preview-only
        samples = dict(SAMPLE_TXT)
        samples["model"] = ("DX2000" if S["has_cover"] else "DX1000", "E8E8E8", 0)
        made = sample_legends(samples)
        outs += px.preview(base + "_legends", W, H, views=("front",), res=1400)
        for o in made:
            bpy.data.objects.remove(o)
        outs += px.preview(base + "_far", W, H, views=("front",), res=160)
    if S["has_cover"]:
        # cover open: rotate cover + tab about the hinge (Blender +X by +100 deg opens forward and down)
        for key in ("cover",):
            out[key].rotation_euler = (math.radians(100.0), 0, 0)
        bpy.context.view_layer.update()
        outs += px.preview(base + "_open", W, H, res=1000)
        made = sample_legends({k: v for k, v in SAMPLE_TXT.items() if k != "cover_label"})
        outs += px.preview(base + "_open_legends", W, H, views=("front",), res=1400)
        for o in made:
            bpy.data.objects.remove(o)
        for key in ("cover",):
            out[key].rotation_euler = (0, 0, 0)
        bpy.context.view_layer.update()
    return outs


# ---------------------------------------------------------------------------------------------
SOURCES = {
    "yokogawa_dx1000": ("Yokogawa DX1000/DX1000N User's Manual IM 04L41B01-01E p.4-2 (4.1, front key drawing; "
                        "strip and DISP/ENTER ring proportions), 12.2 (5.7 in 320x240 LCD); DX2000 Operation Guide "
                        "IM 04L42B01-02E p.10 and p.14 for the shared front proportions"),
    "yokogawa_dx2000": ("Yokogawa DX2000 Operation Guide IM 04L42B01-02E p.10 (Names of Parts, front view), p.14 "
                        "(Panel Keys), p.51 (External Dimensions); DX2000 User's Manual IM 04L42B01-01E 13.2 "
                        "(10.4 in 640x480 LCD), 13.6 (charcoal gray light bezel); FX1000 bulletin 04L21B01-01EN "
                        "(closed key-panel cover photo)"),
}
MODELS = {"yokogawa_dx1000": dx1000_spec, "yokogawa_dx2000": dx2000_spec}


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    only = set(argv[argv.index("--only") + 1].split(",")) if "--only" in argv else None
    previews = "--no-preview" not in argv
    failed = False
    for name, spec_fn in MODELS.items():
        if only and name not in only:
            continue
        S = spec_fn()
        out = build(S)
        bb, errs = check(S, out)
        bad = outward_check()
        if bad:
            errs.append(f"{bad} screen/plate faces not facing +Z")
        for e in errs:
            print("CHECK FAILED:", name, e)
        failed |= bool(errs)
        px.export(name, round(S["W"] * MM, 5), round(S["H"] * MM, 5), SOURCES[name], max_faces=1500)
        print(f"-- {name} part bounds (inches, x right, y down, z out): min -> max")
        for n in sorted(bb):
            lo, hi = bb[n]
            print(f"   {n:14s} x {lo[0]:.4f}..{hi[0]:.4f}  y {lo[1]:.4f}..{hi[1]:.4f}  z {lo[2]:.4f}..{hi[2]:.4f}")
        if S["has_cover"]:
            print("   open cover (100 deg) max y / max z / min y:", [round(v, 4) for v in open_cover_extent(out, S)])
        if previews:
            for p in render_all(S, out):
                print("preview", p)
    if failed:
        raise SystemExit(1)


main()
