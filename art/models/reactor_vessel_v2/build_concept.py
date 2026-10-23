"""Phase-1 CONCEPT: a parametric BWR/5-6 reactor pressure vessel with its internals.

Not used by the game. It is a proposal for owner review; see RPV-REFERENCE.md for every dimension and its source.

Headless only (never the Blender GUI, never a scene called "anim_prev"; it starts from an empty factory file and
never opens or saves any other .blend):

  blender.exe --background --factory-startup --python build_concept.py -- --size 17 17 27 --views all --out concept

  --size W D H   construction envelope in blocks: footprint W x D (7..23), height H (rows from the vessel floor to the
                 cap; the CRD layer below is not counted, so the 17x17x28 reference build is --size 17 17 27).
  --views ...    any of: exterior cutaway section headoff dryerout sepout   ("all" = every one)
  --tag NAME     file name prefix (default WxDxH)
  --stats PATH   write per-part triangle counts (full, uncut build) as JSON
  --blend PATH   also save the built (uncut) scene, for inspection

Frame: Blender X = game X, Blender Y = game Z, Blender Z = game Y (up). One unit = one block. The origin is the centre
of the footprint at the bottom of the envelope. Rectangular footprints give elliptical sections (Y scaled by D/W), as
the current renderer does.

How the real vessel is fitted into the block envelope (all from the game's own rules, so the concept lines up with
the existing fuel, jet pumps, spargers and CRD layer):
  * shell outer radius 0.46 W, inner 0.448 W (unchanged, so the jet-pump and sparger fits stay valid);
  * bottom of active fuel at y = 2 and top at 2 + coreHeight(W, D, H) (VesselInternalsGeometry);
  * jet-pump diffusers stand on the shroud support plate at y = 1.72 (VesselJetGeometry);
  * everything above the core keeps real Columbia proportions with ONE vertical scale s_up (blocks per inch),
    chosen so the real inside height (875 in) ends at the top of the envelope; if the envelope is taller than real
    proportions need, the standpipes get longer (the real design variable) instead of stretching anything else;
  * horizontal sizes use s_h = 2 x 0.448 W / 251 in (the real 251 in inside diameter).
"""
import bpy, bmesh, math, sys, json, os
from mathutils import Vector, Matrix

TAU = math.tau

# ---------------------------------------------------------------- real data, inches above vessel zero (RPV-REFERENCE.md)
REAL = dict(
    vessel_id=251.0,          # Columbia FSAR Table 5.3-9 / Table 1.3-1 (20 ft 11 in)
    inside_height=875.0,      # Columbia FSAR Table 1.3-1 (72 ft 11 in)
    baf=213.0, taf=363.0,     # the mod's own constants (PhysicalConstants); Columbia 216.31 / 366.31 (Fig 5.3-3)
    core_plate=205.0,         # estimate: just under BAF
    top_guide=380.0,          # estimate: top-guide beams just above TAF
    shroud_flange=440.0,      # estimate: below FW sparger (493) with room for core-spray headers
    shroud_dome=465.0,        # estimate: ~25 in shallow dome (Fig 5.3-5 / BWR6 Fig 2-1)
    lpci=420.0, core_spray=470.0,   # estimates; order LPCI < core spray < feedwater from GE BWR/6 Fig 2-1
    feedwater=493.25,         # Columbia Fig 5.3-3
    skirt_bottom=527.5,       # Columbia Fig 5.2-5 elevation chart: bottom of dryer skirt = instrument zero
    sep_body=540.0,           # 465 + 74.8 in standpipe lower section (ORNL MELCOR Peach Bottom table)
    sep_top_section=613.0,    # + 72.8 in separator section
    sep_top=632.0,            # + 19.7 in top section
    water=563.55,             # Columbia Fig 5.3-3 normal water level
    steam=648.0,              # Columbia Fig 5.3-3 / Fig 5.2-5 nozzle N3
    dryer_ring=662.0,         # estimate: support ring just above the steam nozzle bore
    dryer_top=745.0,          # ring + ~2.2 m (87 in) vane height (ORNL MELCOR table)
    flange=755.0,             # estimate: hemispherical head of ~125 in radius above it
    seams=(230.0, 405.0, 585.0),   # 230 / 405 Columbia Fig 5.3-2; 585 estimate (upper course)
    recirc_out=172.5, recirc_in=181.0, jp_instr=152.0,
)
SIZES_IN = dict(steam=26, feedwater=12, recirc_out=24, recirc_in=12, core_spray=10, lpci=12, instrument=2,
                stud=6.0, separator_od=12.75, standpipe_od=6.63, crd_housing=6.0, incore=2.0, fw_header=6.0)

# ---------------------------------------------------------------- arguments
argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
def arg(name, n=1, default=None):
    if name in argv:
        i = argv.index(name)
        return argv[i + 1:i + 1 + n] if n > 1 else argv[i + 1]
    return default
W, D, H = (int(v) for v in arg('--size', 3, ['17', '17', '27']))
VIEWS = []
if '--views' in argv:
    for v in argv[argv.index('--views') + 1:]:
        if v.startswith('--'):
            break
        VIEWS.append(v)
if 'all' in VIEWS:
    VIEWS = ['exterior', 'cutaway', 'section', 'headoff', 'dryerout', 'sepout']
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, arg('--out', default='concept'))
TAG = arg('--tag', default=f'{W}x{D}x{H}')
os.makedirs(OUT, exist_ok=True)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


# ---------------------------------------------------------------- geometry frame
class G:
    pass


def frame(W, D, H):
    g = G()
    g.W, g.D, g.H = W, D, H
    g.ey = D / W
    g.Ro, g.Ri = .46 * W, .448 * W
    g.t = g.Ro - g.Ri
    m = min(W, D)
    desired = clamp(3.5 + .55 * (m - 7), 3.5, 9)
    rim = H - min(H * .22, m * .18) - .08
    g.core_h = min(desired, max(.5, rim - 2 - .7))
    g.baf, g.taf = 2.0, 2.0 + g.core_h
    g.s_h = 2 * g.Ri / REAL['vessel_id']
    g.top_in = H - g.t                      # inner apex of the top head
    g.s_up = min((g.top_in - g.taf) / (REAL['inside_height'] - REAL['taf']), g.s_h)
    # bottom head: outer apex 0.15 above the envelope floor; the inner dome must already be 0.43 W wide at the
    # jet-pump seat (y = 1.72), otherwise the annulus has no floor for the diffusers.
    g.y0o = .15
    g.y0i = g.y0o + g.t
    k = .43 / .448
    g.Hb_i = min((1.72 - g.y0i) / (1 - math.sqrt(1 - k * k)), .5 * g.Ri)
    g.Hb_o = g.Hb_i + g.t
    g.zk = g.y0o + g.Hb_o                   # knuckle: bottom head meets the cylinder
    g.z_f = up(g, REAL['flange'])
    g.fl_h = max(.28, 26 * g.s_up)
    g.fl_w = .025 * W
    g.Hh_i = g.top_in - g.z_f               # head dome rise (inner)
    g.Hh_o = g.Hh_i + g.t
    g.r_cs = .375 * W                       # core shroud (ReactorCoreGeometry)
    g.r_us = .418 * W                       # upper shroud: real 220 / 198 in larger than the core shroud
    g.r_sp = .40 * W                        # core-spray rings (CoreSpargerGeometry)
    g.r_jp = (.375 + .448) / 2 * W          # jet pumps (VesselJetGeometry)
    g.y_jet0 = 1.72
    g.y_jet1 = 2 + .72 * g.core_h
    return g


def up(g, inch):
    """Real elevation (in) -> game y. Below BAF: compressed lower plenum; above TAF: one scale s_up, anchored at
    TAF for the lower group (<= 500 in) and at the top head for the upper group (>= 520 in)."""
    if inch <= REAL['baf']:
        return g.y0i + inch / REAL['baf'] * (g.baf - g.y0i)
    if inch <= REAL['taf']:
        return g.baf + (inch - REAL['baf']) / (REAL['taf'] - REAL['baf']) * g.core_h
    if inch <= 500:
        return g.taf + (inch - REAL['taf']) * g.s_up
    return g.top_in - (REAL['inside_height'] - inch) * g.s_up


def sz(g, inch, lo=0.0):
    return max(lo, inch * g.s_h)


# ---------------------------------------------------------------- mesh builder
MATS = {}
def mat(name, rgb, alpha=1.0):
    if name in MATS:
        return MATS[name]
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*rgb, alpha)
    m.roughness = .55
    if alpha < 1:
        try:
            m.surface_render_method = 'BLENDED'
        except Exception:
            m.blend_method = 'BLEND'
    MATS[name] = m
    return m


class Part:
    def __init__(self, name, material, group):
        self.name, self.mat, self.group = name, material, group
        self.bm = bmesh.new()


PARTS = {}
CUT = None          # (a0, a1): azimuth wedge removed for cutaways


def part(name, material, group):
    if name not in PARTS:
        PARTS[name] = Part(name, material, group)
    return PARTS[name]


def face(bm, vs):
    try:
        return bm.faces.new(vs)
    except ValueError:
        return None


def in_cut(g, x, y):
    if CUT is None:
        return False
    if math.hypot(x, y / g.ey) < 1e-6:
        return False
    a = math.atan2(y / g.ey, x) % TAU
    a0, a1 = CUT[0] % TAU, CUT[1] % TAU
    return (a0 <= a <= a1) if a0 <= a1 else (a >= a0 or a <= a1)


def lathe(p, g, prof, segs=96, closed=True, ey=None, a0=None, a1=None):
    """Revolve a (r, z) profile. Closed profiles are solid walls and get end caps where a cutaway opens them."""
    ey = g.ey if ey is None else ey
    bm = p.bm
    if a0 is None and CUT is not None:
        a0, a1 = CUT[1], CUT[0] + TAU
    full = a0 is None
    if full:
        angles = [i * TAU / segs for i in range(segs)]
    else:
        n = max(2, int(round(segs * (a1 - a0) / TAU)))
        angles = [a0 + (a1 - a0) * i / n for i in range(n + 1)]
    rings = [[bm.verts.new((r * math.cos(a), r * math.sin(a) * ey, z)) for r, z in prof] for a in angles]
    m = len(prof)
    edges = m if closed else m - 1
    nr = len(rings)
    for i in range(nr if full else nr - 1):
        A, B = rings[i], rings[(i + 1) % nr]
        for j in range(edges):
            j2 = (j + 1) % m
            face(bm, [A[j], B[j], B[j2], A[j2]])
    if not full and closed:
        face(bm, rings[0][::-1])
        face(bm, rings[-1])


def basis(d):
    d = d.normalized()
    u = d.cross(Vector((0, 0, 1)) if abs(d.z) < .9 else Vector((1, 0, 0))).normalized()
    return d, u, d.cross(u).normalized()


def cyl(p, a, b, r, n=12, caps=True, r2=None, hollow=None):
    """Cylinder/cone from a to b. hollow=inner radius makes a sleeve with annular ends."""
    a, b = Vector(a), Vector(b)
    if (b - a).length < 1e-6:
        return
    d, u, v = basis(b - a)
    r2 = r if r2 is None else r2
    bm = p.bm
    ring = lambda c, rr: [bm.verts.new(c + rr * (math.cos(i * TAU / n) * u + math.sin(i * TAU / n) * v)) for i in range(n)]
    A, B = ring(a, r), ring(b, r2)
    for i in range(n):
        face(bm, [A[i], A[(i + 1) % n], B[(i + 1) % n], B[i]])
    if hollow:
        Ai, Bi = ring(a, hollow), ring(b, hollow * r2 / r)
        for i in range(n):
            face(bm, [Ai[(i + 1) % n], Ai[i], Bi[i], Bi[(i + 1) % n]])
            face(bm, [A[(i + 1) % n], A[i], Ai[i], Ai[(i + 1) % n]])
            face(bm, [B[i], B[(i + 1) % n], Bi[(i + 1) % n], Bi[i]])
    elif caps:
        face(bm, A[::-1])
        face(bm, B)


def box(p, c, s, rot=0.0):
    bm = p.bm
    c = Vector(c)
    R = Matrix.Rotation(rot, 3, 'Z')
    vs = []
    for dx, dy, dz in [(-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1), (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]:
        vs.append(bm.verts.new(c + R @ Vector((dx * s[0] / 2, dy * s[1] / 2, dz * s[2] / 2))))
    for f in [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5)]:
        face(bm, [vs[i] for i in f])


def quad(p, pts):
    bm = p.bm
    face(bm, [bm.verts.new(Vector(q)) for q in pts])


def annulus(p, c, ro, ri, n):
    bm = p.bm
    c = Vector(c)
    A = [bm.verts.new(c + Vector((ro * math.cos(i * TAU / n), ro * math.sin(i * TAU / n), 0))) for i in range(n)]
    B = [bm.verts.new(c + Vector((ri * math.cos(i * TAU / n), ri * math.sin(i * TAU / n), 0))) for i in range(n)]
    for i in range(n):
        face(bm, [A[i], A[(i + 1) % n], B[(i + 1) % n], B[i]])


def torus(p, c, axis, R, r, n=16, m=6):
    c = Vector(c)
    d, u, v = basis(Vector(axis))
    bm = p.bm
    rings = []
    for i in range(n):
        a = i * TAU / n
        radial = math.cos(a) * u + math.sin(a) * v
        ctr = c + R * radial
        rings.append([bm.verts.new(ctr + r * (math.cos(j * TAU / m) * radial + math.sin(j * TAU / m) * d)) for j in range(m)])
    for i in range(n):
        A, B = rings[i], rings[(i + 1) % n]
        for j in range(m):
            face(bm, [A[j], B[j], B[(j + 1) % m], A[(j + 1) % m]])


def ellipse_pt(g, r, a, z):
    return Vector((r * math.cos(a), r * math.sin(a) * g.ey, z))


def dome_z(g, r_frac, top):
    """Height of the outer bottom (top=False) or top (top=True) head surface at r_frac of the outer radius."""
    s = math.sqrt(max(0.0, 1 - r_frac * r_frac))
    return g.z_f + g.Hh_o * s if top else g.zk - g.Hb_o * s


# ---------------------------------------------------------------- materials (flat colours, mod style)
def materials():
    return dict(
        shell=mat('RPV low-alloy steel', (.34, .38, .41)),
        seam=mat('RPV weld bead', (.20, .22, .24)),
        skirt=mat('RPV support skirt', (.12, .15, .17)),
        forging=mat('RPV nozzle forging', (.40, .44, .47)),
        safe=mat('stainless safe end', (.74, .76, .77)),
        bolt=mat('closure bolting', (.10, .11, .12)),
        ss=mat('stainless internals', (.70, .73, .75)),
        ss_dark=mat('stainless shadowed', (.48, .52, .55)),
        vane=mat('dryer vane bank', (.58, .61, .60)),
        hood=mat('dryer hood plate', (.84, .86, .86)),
        fuel=mat('fuel channel zircaloy', (.47, .43, .34)),
        handle=mat('bundle handle', (.62, .60, .55)),
        crd=mat('CRD housing', (.36, .40, .43)),
        water=mat('vessel water', (.20, .45, .80), .28),
        flash=mat('label yellow', (.85, .68, .12)),
    )


# ---------------------------------------------------------------- the build
def build(g, M, show):
    PARTS.clear()
    ext, head = 'exterior', 'head'

    # --- shell: bottom head + cylinder + vessel flange forging, one closed revolved wall
    seg = 96
    outer, inner = [], []
    for i in range(17):
        th = -math.pi / 2 + i * (math.pi / 2) / 16
        outer.append((g.Ro * math.cos(th), g.zk + g.Hb_o * math.sin(th)))
        inner.append((g.Ri * math.cos(th), g.zk + g.Hb_i * math.sin(th)))
    taper = .45 * g.fl_h
    prof = outer + [(g.Ro, g.z_f - g.fl_h - taper), (g.Ro + g.fl_w, g.z_f - g.fl_h), (g.Ro + g.fl_w, g.z_f),
                    (g.Ri, g.z_f)] + inner[::-1]
    lathe(part('shell', M['shell'], ext), g, prof, seg)

    # weld seams: head-to-shell knuckle, Columbia course seams, flange-to-shell; 3 vertical seams per course
    p = part('weld seams', M['seam'], ext)
    seam_z = [g.zk] + [up(g, s) for s in REAL['seams']] + [g.z_f - g.fl_h - taper - .04]
    seam_z = sorted(z for z in seam_z if g.zk - .01 <= z <= g.z_f - g.fl_h - taper)
    bead = max(.035, .012 * g.W)
    for z in seam_z:
        lathe(p, g, [(g.Ro - .01, z - bead), (g.Ro + bead * .6, z - bead * .4), (g.Ro + bead * .6, z + bead * .4),
                     (g.Ro - .01, z + bead)], seg)
    for k in range(len(seam_z) - 1):
        for j in range(3):
            a = (j * TAU / 3) + (k % 2) * TAU / 6 + .3
            q0 = ellipse_pt(g, g.Ro + bead * .3, a, seam_z[k] + bead)
            if in_cut(g, q0.x, q0.y):
                continue
            q1 = ellipse_pt(g, g.Ro + bead * .3, a, seam_z[k + 1] - bead)
            cyl(p, q0, q1, bead * .7, 6, caps=False)

    # --- support skirt with base ring, anchor nuts and two access openings
    p = part('support skirt', M['skirt'], ext)
    r_top, r_bot = .80 * g.Ro, .86 * g.Ro
    z_att = dome_z(g, r_top / g.Ro, False)
    ts = max(.06, 1.5 * g.s_h)
    lathe(p, g, [(r_bot, .12), (r_top, z_att + .05), (r_top - ts, z_att + .05), (r_bot - ts, .12)], 96)
    lathe(p, g, [(r_bot + .18, 0), (r_bot + .18, .12), (r_bot - ts - .12, .12), (r_bot - ts - .12, 0)], 96)
    pb = part('skirt anchor nuts', M['bolt'], ext)
    n_anchor = int(clamp(round(TAU * r_bot / .55), 16, 64))
    for i in range(n_anchor):
        a = (i + .5) * TAU / n_anchor
        c = ellipse_pt(g, r_bot + .08, a, .12)
        if not in_cut(g, c.x, c.y):
            cyl(pb, c, c + Vector((0, 0, .09)), .055, 6)
    pd = part('skirt openings', M['bolt'], ext)
    for a in (math.radians(100), math.radians(280)):
        c = ellipse_pt(g, (r_bot + r_top) / 2 + .01, a, .12 + (z_att - .12) * .45)
        if not in_cut(g, c.x, c.y):
            box(pd, c, (.03, min(.9, .09 * g.W), (z_att - .12) * .5), a)

    # --- bottom head penetrations: CRD stub tubes / housings and in-core housings down to the CRD layer
    pc = part('CRD housings', M['crd'], ext)
    pitch = .35 * g.W / (g.W - 2) * 2           # one control cell = 2 x 2 bundles (ReactorCoreGeometry pitch)
    r_fuel = .362 * g.W
    n = int(r_fuel / pitch) + 1
    rh = min(.42 * pitch, sz(g, SIZES_IN['crd_housing'] / 2 * 1.3, .05))
    crd_n = incore_n = 0
    for i in range(-n, n + 1):
        for j in range(-n, n + 1):
            x, y = i * pitch, j * pitch * g.ey
            if math.hypot(i * pitch, j * pitch) > r_fuel - .5 * pitch or in_cut(g, x, y):
                continue
            z = dome_z(g, math.hypot(i * pitch, j * pitch) / g.Ro, False)
            cyl(pc, (x, y, .0), (x, y, z + .02), rh, 6, caps=False)
            crd_n += 1
    pi_ = part('in-core housings', M['ss_dark'], ext)
    k = 0
    for i in range(-n, n):
        for j in range(-n, n):
            if (i + j) % 3:
                continue
            x, y = (i + .5) * pitch, (j + .5) * pitch * g.ey
            if math.hypot((i + .5) * pitch, (j + .5) * pitch) > r_fuel - pitch or in_cut(g, x, y):
                continue
            z = dome_z(g, math.hypot((i + .5) * pitch, (j + .5) * pitch) / g.Ro, False)
            cyl(pi_, (x, y, 0), (x, y, z + .02), rh * .45, 5, caps=False)
            incore_n += 1
    # bottom drain
    c = ellipse_pt(g, .3 * g.Ro, math.radians(225), 0)
    if not in_cut(g, c.x, c.y):
        z = dome_z(g, .3, False)
        cyl(pc, (c.x, c.y, .05), (c.x, c.y, z + .02), rh * .8, 8)

    # --- vessel nozzles with forgings and stainless safe ends
    pf = part('nozzle forgings', M['forging'], ext)
    ps = part('nozzle safe ends', M['safe'], ext)
    pw = part('safe-end welds', M['seam'], ext)

    def reach(a):
        # envelope boundary along azimuth a (the square/rectangle), less a small margin
        c, s = math.cos(a), math.sin(a)
        return min((g.W / 2 - .04) / max(abs(c), 1e-6), (g.D / 2 - .04) / max(abs(s), 1e-6))

    def nozzle(a, z, bore_in, minr=.07, inner_pipe=None):
        rb = sz(g, bore_in / 2, minr)
        rf = rb * 1.55 + .03
        rs = rb * 1.12
        d = Vector((math.cos(a), math.sin(a) * g.ey, 0)).normalized()
        base = ellipse_pt(g, g.Ri + .02, a, z)
        if in_cut(g, base.x, base.y):
            return
        L = reach(a)
        shell_r = Vector((g.Ro * math.cos(a), g.Ro * math.sin(a) * g.ey, 0)).length
        lf = min(shell_r + max(.12, .55 * rb), L - .1)
        cyl(pf, base, Vector((d.x * lf, d.y * lf, z)), rf, 16, caps=False)
        e1 = Vector((d.x * lf, d.y * lf, z))
        e2 = Vector((d.x * (lf + .1 + .35 * rb), d.y * (lf + .1 + .35 * rb), z))
        cyl(pf, e1, e2, rf, 16, caps=False, r2=rs * 1.02)
        e3 = Vector((d.x * L, d.y * L, z))
        if (e3 - e2).length > .03:
            cyl(ps, e2, e3, rs, 16, hollow=rb * .9)
            m = e2 + (e3 - e2) * .15
            cyl(pw, m - d * .025, m + d * .025, rs * 1.04, 16, caps=False)
        if inner_pipe:
            inner_pipe(a, z, rb)

    deg = math.radians
    sched = []
    for a in (-50, -17, 17, 50):
        sched.append(('main steam', a, up(g, REAL['steam']), SIZES_IN['steam']))
    for a in (45, 90, 135, 225, 270, 315):
        sched.append(('feedwater', a, up(g, REAL['feedwater']), SIZES_IN['feedwater']))
    for a in (0, 180):
        sched.append(('core spray', a, up(g, REAL['core_spray']), SIZES_IN['core_spray']))
    for a in (75, 195, 315):
        sched.append(('LPCI', a + 15, up(g, REAL['lpci']), SIZES_IN['lpci']))
    z_ro = max(up(g, REAL['recirc_out']), g.zk + sz(g, 12, .2) * 1.2)
    for a in (0, 180):
        sched.append(('recirculation outlet', a, z_ro, SIZES_IN['recirc_out']))
    z_ri = max(up(g, REAL['recirc_in']), g.zk + sz(g, 6, .1) * 1.6)
    inlet_az = [30, 60, 90, 120, 150, 210, 240, 270, 300, 330]
    for a in inlet_az:
        sched.append(('recirculation inlet', a, z_ri, SIZES_IN['recirc_in']))
    for a, e in ((10, 'jp_instr'), (190, 'jp_instr'), (100, 'taf'), (280, 'taf'), (160, 'skirt_bottom'), (340, 'skirt_bottom'), (110, 'sep_top_section'), (250, 'sep_top_section')):
        sched.append(('instrument', a, up(g, REAL[e] if e != 'jp_instr' else REAL['jp_instr']), 2))
    for kind, a, z, bore in sched:
        nozzle(deg(a), z, bore, .05 if kind == 'instrument' else .07)

    # --- closure: vessel flange studs (they stay in with the head off), nuts and washers belong to the head
    n_studs = int(clamp(round(TAU * .472 * g.W / (11.2 * g.s_h)), 24, 92))
    r_stud = sz(g, SIZES_IN['stud'] / 2, .05)
    pst = part('closure studs', M['bolt'], 'studs')
    pnut = part('closure nuts and washers', M['bolt'], head)
    pwas = part('closure washers', M['safe'], head)
    z_top_head_flange = g.z_f + g.fl_h
    for i in range(n_studs):
        a = (i + .5) * TAU / n_studs
        c = ellipse_pt(g, .472 * g.W, a, 0)
        if in_cut(g, c.x, c.y):
            continue
        cyl(pst, (c.x, c.y, g.z_f - .55 * g.fl_h), (c.x, c.y, z_top_head_flange + 2.6 * r_stud), r_stud, 8)
        cyl(pwas, (c.x, c.y, z_top_head_flange), (c.x, c.y, z_top_head_flange + .35 * r_stud), r_stud * 1.75, 10)
        cyl(pnut, (c.x, c.y, z_top_head_flange + .35 * r_stud), (c.x, c.y, z_top_head_flange + 1.9 * r_stud),
            r_stud * 1.6, 6)

    # --- top head: flange forging + dome (one closed wall), nozzles and lifting lugs
    ph = part('top head', M['shell'], head)
    hp_o, hp_i = [], []
    for i in range(17):
        th = i * (math.pi / 2) / 16
        hp_o.append((g.Ro * math.cos(th), g.z_f + g.Hh_o * math.sin(th)))
        hp_i.append((g.Ri * math.cos(th), g.z_f + g.Hh_i * math.sin(th)))
    lathe(ph, g, [(g.Ri, g.z_f + .005), (g.Ro + g.fl_w, g.z_f + .005), (g.Ro + g.fl_w, z_top_head_flange),
                  (g.Ro, z_top_head_flange + taper)] + hp_o[1:] + hp_i[::-1], seg)
    phs = part('head seams', M['seam'], head)
    zs = z_top_head_flange + taper + .05
    rr = g.Ro * math.cos(math.asin(min(1, (zs - g.z_f) / g.Hh_o)))
    lathe(phs, g, [(rr - .01, zs - bead), (rr + bead * .6, zs), (rr - .01, zs + bead)], seg)
    # head vent + head spray + spare nozzle at the crown, flanged
    pn = part('head nozzles', M['forging'], head)
    pnb = part('head nozzle bolting', M['bolt'], head)
    for (dx, dy), bore, hgt in (((0, 0), 6, 1.0), ((.09, .05), 4, .8), ((-.08, .07), 4, .7)):
        x, y = dx * g.W, dy * g.D
        rf = dome_z(g, math.hypot(x / g.Ro, y / (g.Ro * g.ey)), True)
        rb = sz(g, bore / 2, .06) * 1.6
        top = rf + max(.25, hgt * .5 * g.Hh_o / 3)
        cyl(pn, (x, y, rf - .2), (x, y, top), rb, 14)
        cyl(pn, (x, y, top), (x, y, top + .07), rb * 1.9, 14)
        cyl(pn, (x, y, top + .07), (x, y, top + .13), rb * 1.9, 14)
        for q in range(8):
            a = q * TAU / 8
            cyl(pnb, (x + rb * 1.55 * math.cos(a), y + rb * 1.55 * math.sin(a), top - .02),
                (x + rb * 1.55 * math.cos(a), y + rb * 1.55 * math.sin(a), top + .17), rb * .16, 6)
    # four head lifting lugs on the dome
    pl = part('head lifting lugs', M['forging'], head)
    for i in range(4):
        a = math.radians(45 + 90 * i)
        rf = .55
        c = ellipse_pt(g, rf * g.Ro, a, dome_z(g, rf, True))
        if in_cut(g, c.x, c.y):
            continue
        hgt = max(.35, .09 * g.W)
        box(pl, c + Vector((0, 0, hgt * .4)), (max(.12, .02 * g.W), max(.4, .07 * g.W), hgt), a)
        dv = Vector((math.cos(a), math.sin(a), 0))
        cyl(pnb, c + Vector((0, 0, hgt * .62)) - dv * .09 * g.W / 17 * 2, c + Vector((0, 0, hgt * .62)) + dv * .09 * g.W / 17 * 2,
            max(.06, .012 * g.W), 12)

    # ================================================================ internals (drawn only with the head off)
    I = 'internals'
    # shroud support plate (annulus floor; jet-pump diffusers stand on it)
    y_sp = g.y_jet0
    th = math.asin(clamp((y_sp - g.zk) / g.Hb_i, -1, 1))
    r_wall_sp = g.Ri * math.cos(th)
    p = part('shroud support plate', M['ss_dark'], I)
    lathe(p, g, [(g.r_cs - .02, y_sp - .1), (r_wall_sp + .02, y_sp - .1), (r_wall_sp + .02, y_sp), (g.r_cs - .02, y_sp)], 96)
    # core shroud (lower + central), step out to the upper shroud at the top guide, shroud flange
    ts = max(.05, 2.0 * g.s_h)
    y_tg = up(g, REAL['top_guide'])
    y_shf = up(g, REAL['shroud_flange'])
    p = part('core shroud', M['ss'], I)
    lathe(p, g, [(g.r_cs, y_sp - .45), (g.r_cs, y_tg), (g.r_us, y_tg + .12), (g.r_us, y_shf - .1), (g.r_us + .12, y_shf - .1),
                 (g.r_us + .12, y_shf), (g.r_us - ts, y_shf), (g.r_us - ts, y_tg + .12 + ts), (g.r_cs - ts, y_tg + ts),
                 (g.r_cs - ts, y_sp - .45)], 96)
    # core plate and top guide
    p = part('core plate', M['ss_dark'], I)
    lathe(p, g, [(0, 1.76), (g.r_cs - ts, 1.76), (g.r_cs - ts, 1.9), (0, 1.9)], 96)
    p = part('top guide', M['ss'], I)
    lathe(p, g, [(g.r_cs - ts - .14, g.taf + .02), (g.r_cs - ts, g.taf + .02), (g.r_cs - ts, y_tg), (g.r_cs - ts - .14, y_tg)], 96)
    rr = g.r_cs - ts - .07
    bw = max(.035, .4 * g.s_h)
    for i in range(-n, n + 1):
        c = (i + .5) * pitch
        if abs(c) >= rr:
            continue
        half = math.sqrt(rr * rr - c * c)
        for k_ in range(2):
            a_ = Vector((c, -half, 0)) if k_ == 0 else Vector((-half, c, 0))
            b_ = Vector((c, half, 0)) if k_ == 0 else Vector((half, c, 0))
            steps = 6 if CUT else 1
            for s_ in range(steps):
                q0 = a_.lerp(b_, s_ / steps)
                q1 = a_.lerp(b_, (s_ + 1) / steps)
                mid = (q0 + q1) / 2
                if in_cut(g, mid.x, mid.y * g.ey):
                    continue
                ln = (q1 - q0).length
                if k_ == 0:
                    box(p, (mid.x, mid.y * g.ey, (g.taf + y_tg) / 2), (bw, ln * g.ey, y_tg - g.taf - .02))
                else:
                    box(p, (mid.x, mid.y * g.ey, (g.taf + y_tg) / 2), (ln, bw * g.ey, y_tg - g.taf - .02))
    # simplified core: bundles in the existing fuel radius (the real game keeps its own core geometry)
    pfu = part('fuel (existing core, simplified)', M['fuel'], 'core')
    pha = part('bundle handles', M['handle'], 'core')
    bp = pitch / 2
    nb = int(r_fuel / bp) + 1
    bundles = 0
    for i in range(-nb, nb):
        for j in range(-nb, nb):
            x, y = (i + .5) * bp, (j + .5) * bp
            if math.hypot(x, y) > r_fuel or in_cut(g, x, y * g.ey):
                continue
            box(pfu, (x, y * g.ey, (g.baf + g.taf) / 2), (bp * .9, bp * .9 * g.ey, g.core_h))
            box(pha, (x, y * g.ey, g.taf + .05), (bp * .15, bp * .7 * g.ey, .1))
            bundles += 1

    # jet pumps: ten risers, each feeding two jet pumps (BWR/3-6 external-loop layout)
    pj = part('jet pumps', M['ss'], I)
    pjd = part('jet pump restrainers', M['ss_dark'], I)
    rj = sz(g, 3.2, .05)            # inlet-mixer / throat  (~6.4 in, Table 5.4-2)
    rd = sz(g, 9.5, .09)            # diffuser exit (~19 in)
    rr_ = sz(g, 5.0, .07)           # riser
    for a_deg in inlet_az:
        a = deg(a_deg)
        c0 = ellipse_pt(g, g.r_jp, a, 0)
        if in_cut(g, c0.x, c0.y):
            continue
        top = g.y_jet1
        riser = ellipse_pt(g, g.r_jp, a, z_ri)
        cyl(pj, ellipse_pt(g, g.Ri, a, z_ri), riser, rr_, 10)
        cyl(pj, riser, ellipse_pt(g, g.r_jp, a, top), rr_, 10)
        da = (rd * 2.6) / g.r_jp
        for sgn in (-1, 1):
            b = a + sgn * da
            ybot, ydif = y_sp, y_sp + .38 * (top - y_sp)
            cyl(pj, ellipse_pt(g, g.r_jp, b, ybot), ellipse_pt(g, g.r_jp, b, ydif), rd, 10, r2=rj * 1.3)
            cyl(pj, ellipse_pt(g, g.r_jp, b, ydif), ellipse_pt(g, g.r_jp, b, top - .25), rj, 10)
            cyl(pjd, ellipse_pt(g, g.r_jp, b, top - .25), ellipse_pt(g, g.r_jp, b, top + .05), rj * 1.7, 10)
            cyl(pj, ellipse_pt(g, g.r_jp, a, top + .12), ellipse_pt(g, g.r_jp, b, top + .05), rj, 8)
            cyl(pjd, ellipse_pt(g, g.r_jp, b, ydif + .35 * (top - ydif)) , ellipse_pt(g, g.r_jp, a, ydif + .35 * (top - ydif)), rj * .5, 6)
        cyl(pjd, ellipse_pt(g, g.r_jp, a, top + .05), ellipse_pt(g, g.r_jp, a, top + .28), rr_ * 1.2, 10)

    # core spray: semicircular rings inside the upper shroud (LPCS lower, HPCS upper), lines from the two nozzles
    pcs = part('core spray spargers', M['ss'], I)
    pcl = part('core spray lines', M['ss_dark'], I)
    r_pipe = sz(g, 2.5, .045)
    y_rings = [g.taf + .35 * (y_shf - g.taf), g.taf + .62 * (y_shf - g.taf)]
    for y_r in y_rings:
        prof = [(g.r_sp + r_pipe * math.cos(j * TAU / 6), y_r + r_pipe * math.sin(j * TAU / 6)) for j in range(6)]
        lathe(pcs, g, prof, 96)
    y_cs = up(g, REAL['core_spray'])
    for a0 in (0, math.pi):
        for sgn in (-1, 1):
            a1 = a0 + sgn * math.radians(70)
            lo, hi = min(a0, a1), max(a0, a1)
            if CUT is None:
                prof = [(.433 * g.W + r_pipe * math.cos(j * TAU / 6), y_cs + r_pipe * math.sin(j * TAU / 6)) for j in range(6)]
                lathe(pcl, g, prof, 96, a0=lo, a1=hi)
            c = ellipse_pt(g, .433 * g.W, a1, y_cs)
            if not in_cut(g, c.x, c.y):
                cyl(pcl, c, ellipse_pt(g, .433 * g.W, a1, y_shf + .2), r_pipe, 8)
                cyl(pcl, ellipse_pt(g, .433 * g.W, a1, y_shf + .2), ellipse_pt(g, g.r_us, a1, y_shf - .25), r_pipe, 8)
                cyl(pcl, ellipse_pt(g, g.r_us, a1, y_shf - .25), ellipse_pt(g, g.r_sp, a1, y_rings[1 if a0 == 0 else 0]), r_pipe, 8)
    # spray nozzles on the rings (inward/downward stubs)
    for y_r in y_rings:
        for i in range(int(clamp(round(TAU * g.r_sp / .45), 24, 96))):
            a = i * TAU / max(24, int(clamp(round(TAU * g.r_sp / .45), 24, 96)))
            c = ellipse_pt(g, g.r_sp, a, y_r)
            if in_cut(g, c.x, c.y):
                continue
            cyl(pcs, c, ellipse_pt(g, g.r_sp - r_pipe * 2.4, a, y_r - r_pipe * 1.6), r_pipe * .45, 5)

    # LPCI couplings: nozzle to the upper shroud
    for a in (90, 210, 330):
        c = ellipse_pt(g, g.Ri, deg(a), up(g, REAL['lpci']))
        if not in_cut(g, c.x, c.y):
            cyl(pcl, c, ellipse_pt(g, g.r_us, deg(a), up(g, REAL['lpci'])), sz(g, 6, .06), 10)

    # feedwater spargers: one 6 in header arc per nozzle in the top of the downcomer annulus
    pfw = part('feedwater spargers', M['ss'], I)
    y_fw = up(g, REAL['feedwater'])
    rf = sz(g, SIZES_IN['fw_header'] / 2, .05)
    r_fw = (g.r_us + .12 + g.Ri) / 2
    for a_deg in (45, 90, 135, 225, 270, 315):
        a = deg(a_deg)
        lo, hi = a - math.radians(26), a + math.radians(26)
        if CUT is not None:
            lo2, hi2 = CUT[1], CUT[0] + TAU
            # clip the arc to the visible range
            segs = []
            for t_ in range(24):
                u0 = lo + (hi - lo) * t_ / 24
                u1 = lo + (hi - lo) * (t_ + 1) / 24
                c = ellipse_pt(g, r_fw, (u0 + u1) / 2, y_fw)
                if not in_cut(g, c.x, c.y):
                    cyl(pfw, ellipse_pt(g, r_fw, u0, y_fw), ellipse_pt(g, r_fw, u1, y_fw), rf, 8)
        else:
            prof = [(r_fw + rf * math.cos(j * TAU / 8), y_fw + rf * math.sin(j * TAU / 8)) for j in range(8)]
            lathe(pfw, g, prof, 96, a0=lo, a1=hi)
        c = ellipse_pt(g, r_fw, a, y_fw)
        if not in_cut(g, c.x, c.y):
            cyl(pfw, ellipse_pt(g, g.Ri, a, y_fw), c, rf * 1.3, 10)
            for e in (lo, hi):
                q = ellipse_pt(g, r_fw, e, y_fw)
                box(pfw, q, (rf * 2.4, rf * 2.4, rf * 3.2), e)

    # guide rods (dryer and shroud-head alignment) and dryer support brackets on the vessel wall
    pg = part('guide rods and brackets', M['ss_dark'], I)
    y_ring = up(g, REAL['dryer_ring'])
    for a in (deg(5), deg(185)):
        c0 = ellipse_pt(g, g.Ri - .12, a, y_shf)
        if not in_cut(g, c0.x, c0.y):
            cyl(pg, c0, ellipse_pt(g, g.Ri - .12, a, g.z_f - .15), max(.04, .01 * g.W), 8)
    for i in range(4):
        a = deg(22.5 + 90 * i)
        c = ellipse_pt(g, g.Ri - .1, a, y_ring - .12)
        if not in_cut(g, c.x, c.y):
            box(pg, c, (.2, max(.3, .04 * g.W), .2), a)

    # --- shroud head + standpipes + steam separators (one removable assembly)
    S = 'separators'
    psh = part('shroud head', M['ss'], S)
    y_dome = up(g, REAL['shroud_dome'])
    rise = y_dome - y_shf - .1
    prof_o = [(g.r_us + .12, y_shf), (g.r_us + .12, y_shf + .12)]
    prof_i = []
    for i in range(13):
        th = i * (math.pi / 2) / 12
        prof_o.append(((g.r_us) * math.cos(th), y_shf + .12 + rise * math.sin(th)))
    prof_i = [((g.r_us - ts) * math.cos(th), y_shf + .12 - ts + (rise - 0) * math.sin(th)) for th in
              [i * (math.pi / 2) / 12 for i in range(12, -1, -1)]]
    lathe(psh, g, prof_o + prof_i + [(g.r_us - ts, y_shf)], 96)

    def shroud_head_z(x, y):
        rr = math.hypot(x, y / g.ey) / g.r_us
        return y_shf + .12 + rise * math.sqrt(max(0, 1 - rr * rr))

    # separator grid: hexagonal, real pitch (separator OD + gap), real count capped at 225 (Fermi 2 / 251-764 vessel)
    r_arr = g.r_us - .2
    p_sep = max(13.4 * g.s_h, .5)
    while True:
        pts = []
        rows = int(r_arr / (p_sep * .866)) + 1
        for j in range(-rows, rows + 1):
            off = (j % 2) * p_sep / 2
            yy = j * p_sep * .866
            for i in range(-int(r_arr / p_sep) - 2, int(r_arr / p_sep) + 3):
                xx = i * p_sep + off
                if math.hypot(xx, yy) <= r_arr - p_sep * .5:
                    pts.append((xx, yy * g.ey))
        if len(pts) <= 225:
            break
        p_sep *= 1.01
    sep_count = len(pts)
    r_sep = min(.47 * p_sep, max(sz(g, SIZES_IN['separator_od'] / 2), .45 * p_sep))
    r_std = .52 * r_sep
    y_body, y_top_sec, y_top = up(g, REAL['sep_body']), up(g, REAL['sep_top_section']), up(g, REAL['sep_top'])
    pstd = part('standpipes', M['ss_dark'], S)
    psep = part('steam separators', M['ss'], S)
    psepc = part('separator caps', M['ss_dark'], S)
    nseg = 8
    for x, y in pts:
        if in_cut(g, x, y):
            continue
        z0 = shroud_head_z(x, y) - .05
        cyl(pstd, (x, y, z0), (x, y, y_body - .25), r_std, nseg, caps=False)
        cyl(psep, (x, y, y_body - .25), (x, y, y_body), r_std, nseg, caps=False, r2=r_sep)         # turning-vane hub cone
        cyl(psep, (x, y, y_body), (x, y, y_top_sec), r_sep, nseg, caps=False)                       # separator barrel
        cyl(psepc, (x, y, y_top_sec - .06), (x, y, y_top_sec + .03), r_sep * 1.08, nseg, caps=False)  # stage ring
        cyl(psep, (x, y, y_top_sec + .03), (x, y, y_top), r_sep * .8, nseg, caps=False)             # top section
        annulus(psepc, (x, y, y_top), r_sep * .8, r_sep * .5, nseg)                                 # steam outlet lip
    # shroud-head hold-down bolts reaching above the separators, and four lifting lugs
    pbolt = part('shroud head bolts and lugs', M['bolt'], S)
    nb_ = int(clamp(round(TAU * g.r_us / 1.6), 12, 48))
    for i in range(nb_):
        a = (i + .5) * TAU / nb_
        c = ellipse_pt(g, g.r_us + .07, a, y_shf)
        if not in_cut(g, c.x, c.y):
            cyl(pbolt, (c.x, c.y, y_shf - .05), (c.x, c.y, y_top + .3), max(.03, .006 * g.W), 6)
            cyl(pbolt, (c.x, c.y, y_top + .3), (c.x, c.y, y_top + .42), max(.06, .011 * g.W), 6)
    for i in range(4):
        a = deg(45 + 90 * i)
        c = ellipse_pt(g, .55 * g.r_us, a, 0)
        x, y = c.x, c.y
        if in_cut(g, x, y):
            continue
        # replace the separator at that spot by a lifting post
        cyl(pbolt, (x, y, shroud_head_z(x, y)), (x, y, y_top + .45), max(.05, .01 * g.W), 8)
        torus(pbolt, (x, y, y_top + .62), (math.cos(a), math.sin(a), 0), max(.12, .022 * g.W), max(.035, .006 * g.W), 12, 6)

    # --- steam dryer: support ring, skirt (to below water level), six vane banks, hoods, troughs, rods and eyes
    DR = 'dryer'
    y_skirt = up(g, REAL['skirt_bottom'])
    y_dtop = up(g, REAL['dryer_top'])
    r_ring_o, r_ring_i = g.Ri - .05, g.r_us + .05
    r_skirt = g.r_us + .1
    pr = part('dryer support ring', M['ss_dark'], DR)
    lathe(pr, g, [(r_ring_i - .1, y_ring - .14), (r_ring_o, y_ring - .14), (r_ring_o, y_ring + .04), (r_ring_i - .1, y_ring + .04)], 96)
    psk = part('dryer skirt', M['ss'], DR)
    tsk = max(.03, .8 * g.s_h)
    lathe(psk, g, [(r_skirt, y_skirt), (r_skirt, y_ring - .14), (r_skirt - tsk, y_ring - .14), (r_skirt - tsk, y_skirt)], 96)
    lathe(psk, g, [(r_skirt - .02, y_skirt - .02), (r_skirt + .07, y_skirt - .02), (r_skirt + .07, y_skirt + .1), (r_skirt - .02, y_skirt + .1)], 96)
    # vertical drain channels on the skirt
    for i in range(8):
        a = (i + .5) * TAU / 8
        c = ellipse_pt(g, r_skirt + .04, a, 0)
        if not in_cut(g, c.x, c.y):
            box(psk, (c.x, c.y, (y_skirt + y_ring) / 2), (.08, max(.12, .02 * g.W), y_ring - y_skirt - .2), a)
    # vane banks: six banks along X; 7 plenums alternate inlet (covered by hoods) / outlet (open to the dome)
    pv = part('dryer vane banks', M['vane'], DR)
    pho = part('dryer hoods and covers', M['hood'], DR)
    pt = part('dryer troughs and end plates', M['ss_dark'], DR)
    r_bank = r_ring_i + .1
    span = 2 * r_bank
    gap = span / (6 * 1.2 + 7)
    bank = 1.2 * gap
    y0 = -span / 2
    hb = y_dtop - y_ring - .25
    z0 = y_ring + .05
    for k_ in range(13):
        w_ = bank if k_ % 2 else gap
        yc = y0 + w_ / 2
        y0 += w_
        lim = abs(yc) + w_ / 2
        if lim >= r_bank:
            lim = abs(yc)
        half = math.sqrt(max(0, r_bank * r_bank - lim * lim)) - .05
        if half <= .2:
            continue
        nsplit = 8 if CUT else 1
        for s_ in range(nsplit):
            xa = -half + 2 * half * s_ / nsplit
            xb = -half + 2 * half * (s_ + 1) / nsplit
            xm = (xa + xb) / 2
            if in_cut(g, xm, yc * g.ey):
                continue
            L = xb - xa
            if k_ % 2:                                   # a vane bank, with vertical vane slats on both faces
                box(pv, (xm, yc * g.ey, z0 + hb / 2), (L, bank * g.ey, hb))
                box(pt, (xm, yc * g.ey, z0 - .06), (L, (bank + .1) * g.ey, .12))           # collecting trough
                box(pt, (xm, yc * g.ey, z0 + hb + .03), (L, (bank + .02) * g.ey, .06))    # bank top plate
                nsl = max(2, int(L / .3))
                for q in range(nsl):
                    xs = xa + (q + .5) * L / nsl
                    for sgn in (-1, 1):
                        box(pt, (xs, (yc + sgn * bank / 2) * g.ey, z0 + hb / 2), (.025, .03, hb * .92))
            elif k_ in (0, 4, 8, 12):                    # inlet plenum: slanted hood roof over it
                yA, yB = (yc - w_ / 2) * g.ey, (yc + w_ / 2) * g.ey
                ridge = z0 + hb + min(.5, .35 * w_)
                if k_ == 0:
                    quad(pho, [(xa, yA, z0 + hb), (xb, yA, z0 + hb), (xb, yB, ridge), (xa, yB, ridge)])
                elif k_ == 12:
                    quad(pho, [(xa, yA, ridge), (xb, yA, ridge), (xb, yB, z0 + hb), (xa, yB, z0 + hb)])
                else:
                    ym = (yA + yB) / 2
                    quad(pho, [(xa, yA, z0 + hb), (xb, yA, z0 + hb), (xb, ym, ridge), (xa, ym, ridge)])
                    quad(pho, [(xa, ym, ridge), (xb, ym, ridge), (xb, yB, z0 + hb), (xa, yB, z0 + hb)])
            else:                                        # outlet plenum: closed bottom, open top
                box(pt, (xm, yc * g.ey, z0 + .02), (L, w_ * g.ey, .04))
        for sgn in (-1, 1):                              # end plates on the chord ends
            if not in_cut(g, sgn * half, yc * g.ey):
                box(pt, (sgn * half, yc * g.ey, z0 + hb / 2 + .05), (.06, w_ * g.ey, hb + .1))
    # top perimeter band and four lifting rods with eyes reaching up into the head
    lathe(pt, g, [(r_bank - .05, z0), (r_bank + .05, z0), (r_bank + .05, z0 + hb + .1), (r_bank - .05, z0 + hb + .1)], 96)
    for i in range(4):
        a = deg(45 + 90 * i)
        c = ellipse_pt(g, r_bank - .15, a, 0)
        if in_cut(g, c.x, c.y):
            continue
        eye_z = min(g.z_f + .55 * g.Hh_i, y_dtop + .9)
        cyl(pr, (c.x, c.y, y_ring - .14), (c.x, c.y, eye_z), max(.05, .009 * g.W), 8)
        torus(pr, (c.x, c.y, eye_z + max(.13, .02 * g.W)), (-math.sin(a), math.cos(a), 0), max(.13, .02 * g.W), max(.04, .006 * g.W), 12, 6)

    # --- normal water level (translucent), for the cutaways
    pwat = part('normal water level', M['water'], 'water')
    yw = up(g, REAL['water'])
    lathe(pwat, g, [(0, 1.0), (g.Ri - .03, 1.0), (g.Ri - .03, yw), (0, yw)], 96)

    stats = dict(crd_housings=crd_n, incore_housings=incore_n, bundles=bundles, separators=sep_count,
                 studs=n_studs, separator_pitch=round(p_sep, 3))
    return stats


# ---------------------------------------------------------------- scene, objects, camera, render
def realise(show_groups):
    scene = bpy.context.scene
    for o in list(scene.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    tris = {}
    for name, p in PARTS.items():
        bmesh.ops.remove_doubles(p.bm, verts=list(p.bm.verts), dist=1e-5)
        bmesh.ops.dissolve_degenerate(p.bm, edges=list(p.bm.edges), dist=1e-6)
        me = bpy.data.meshes.new(name)
        p.bm.to_mesh(me)
        p.bm.free()
        me.materials.append(p.mat)
        ob = bpy.data.objects.new(name, me)
        ob['group'] = p.group
        scene.collection.objects.link(ob)
        ob.hide_render = p.group not in show_groups
        tris[name] = (p.group, sum(len(f.vertices) - 2 for f in me.polygons))
    return tris


def setup_render():
    scene = bpy.context.scene
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.render.resolution_x, scene.render.resolution_y = 1920, 1080
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    sh = scene.display.shading
    sh.light = 'STUDIO'
    sh.color_type = 'MATERIAL'
    sh.show_cavity = True
    sh.cavity_type = 'WORLD'
    sh.show_object_outline = True
    sh.object_outline_color = (.05, .06, .07)
    sh.show_specular_highlight = True
    try:
        scene.display.render_aa = '16'
    except Exception:
        pass
    if scene.world is None:
        scene.world = bpy.data.worlds.new('concept world')
    scene.world.color = (.80, .83, .86)
    scene.view_settings.view_transform = 'Standard'


def camera(target, az_deg, el_deg, dist, lens=40, ortho=None):
    scene = bpy.context.scene
    cam = scene.camera
    if cam is None:
        cam = bpy.data.objects.new('concept camera', bpy.data.cameras.new('concept camera'))
        scene.collection.objects.link(cam)
        scene.camera = cam
    az, el = math.radians(az_deg), math.radians(el_deg)
    t = Vector(target)
    cam.location = t + dist * Vector((math.cos(el) * math.cos(az), math.cos(el) * math.sin(az), math.sin(el)))
    cam.rotation_euler = (t - cam.location).to_track_quat('-Z', 'Y').to_euler()
    cam.data.lens = lens
    cam.data.clip_end = 1000
    if ortho:
        cam.data.type = 'ORTHO'
        cam.data.ortho_scale = ortho
    else:
        cam.data.type = 'PERSP'


def render(name):
    path = os.path.join(OUT, f'{TAG}-{name}.png')
    bpy.context.scene.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print('RENDERED', path)
    return path


VIEW_SPEC = {
    # name: (cut wedge or None, groups shown, camera az, el, distance factor, target height factor)
    'exterior': (None, {'exterior', 'head', 'studs'}, -55, 9, 2.75, .46),
    'cutaway': ((math.radians(250), math.radians(340)), {'exterior', 'head', 'studs', 'internals', 'separators', 'dryer', 'core', 'water'}, -65, 16, 2.75, .48),
    'section': ((math.radians(180), math.radians(360)), {'exterior', 'head', 'studs', 'internals', 'separators', 'dryer', 'core', 'water'}, -90, 5, 2.35, .47),
    'headoff': (None, {'exterior', 'studs', 'internals', 'separators', 'dryer', 'core'}, -60, 62, 1.75, .74),
    'dryerout': (None, {'exterior', 'studs', 'internals', 'separators', 'core'}, -60, 55, 1.8, .66),
    'sepout': (None, {'exterior', 'studs', 'internals', 'core'}, -60, 58, 1.8, .56),
}


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    assert 'anim_prev' not in bpy.data.scenes
    setup_render()
    M = materials()
    g = frame(W, D, H)
    global CUT
    report = dict(size=[W, D, H], frame={k: round(v, 3) for k, v in vars(g).items() if isinstance(v, float)})
    stats_path = arg('--stats')
    if stats_path or arg('--blend'):
        CUT = None
        report['counts'] = build(g, M, None)
        tris = realise({'exterior', 'head', 'studs', 'internals', 'separators', 'dryer', 'core', 'water'})
        report['triangles'] = {k: dict(group=v[0], tris=v[1]) for k, v in tris.items()}
        groups = {}
        for k, v in tris.items():
            groups[v[0]] = groups.get(v[0], 0) + v[1]
        report['triangles_by_group'] = groups
        if arg('--blend'):
            bpy.ops.wm.save_as_mainfile(filepath=os.path.join(HERE, arg('--blend')), check_existing=False)
        if stats_path:
            with open(os.path.join(HERE, stats_path), 'w') as f:
                json.dump(report, f, indent=1)
    for view in VIEWS:
        cut, groups, az, el, dist, th = VIEW_SPEC[view]
        CUT = cut
        build(g, M, groups)
        realise(groups)
        size = max(H, 1.15 * max(W, D))
        camera((0, 0, H * th), az, el, dist * size, 40)
        render(view)
    CUT = None


main()
