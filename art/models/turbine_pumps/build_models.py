"""Turbine-driven pumps, rebuilt from reference photographs: RFPT + barrel feed pump, RCIC and HPCI Terry skids.

Headless only:
    blender --background --factory-startup --python art/models/turbine_pumps/build_models.py
First run `python art/models/turbine_pumps/make_textures.py` (gauge, nameplate and panel textures).

Writes the mesh/UV snapshots that the existing exporters bake into block cells:
    art/models/modern/turbine_feed_pump.json   -> python tools-export-modern.py
    art/models/terry/rcic_twl.json             -> python tools-export-terry.py
    art/models/terry/hpci_turbine.json
and the editable source art/models/turbine_pumps/turbine_pumps.blend.

Coordinates are Minecraft X/Y/Z for a north-facing machine; one unit is one block (one metre).
Footprints, controller cells and the four process ports (cell, face, flange centre) are unchanged and are
checked against the shipped manifests before anything is written. Original geometry; no vendor CAD.
"""
import json
import math
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf8').split('def build_msiv():')[0], str(helper), 'exec'))
HERE = ROOT / 'art/models/turbine_pumps'
TEXDIR = ROOT / 'art/models/modern/textures'
MANIFESTS = ROOT / 'mod/src/main/resources/data/bwr/pump_models'
SCENES = []
DIRV = {'down': (0, -1, 0), 'up': (0, 1, 0), 'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0)}


def new_scene(name):
    # Background Blender has no window; scenes are only containers here.
    s = bpy.data.scenes.new(name)
    SCENES.append(s)
    return s


def lin(c):
    return c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4


def paint(name, srgb, metal=0.0, rough=.5):
    m = material('TDP ' + name, tuple(lin(c) for c in srgb), metal)
    next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled').inputs['Roughness'].default_value = rough
    return m


def tex_mat(name, tex):
    m = bpy.data.materials.new('TDP | ' + name)
    m.use_nodes = True
    nodes = m.node_tree.nodes
    bsdf = next(n for n in nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    node = nodes.new('ShaderNodeTexImage')
    node.image = bpy.data.images.load(str(TEXDIR / (tex + '.png')), check_existing=True)
    node.interpolation = 'Closest'
    m.node_tree.links.new(node.outputs['Color'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = .55
    m['tdp_texture'] = tex
    return m


# ---------------------------------------------------------------- materials (sRGB as seen in game)
BASEP = paint('skid dark grey', (.24, .26, .27))
BOLTM = paint('dark fastener', (.19, .20, .21), .6, .4)
JACKET = paint('aluminium insulation jacket', (.80, .82, .83), .5, .35)
STRAP = paint('jacket band', (.56, .58, .60), .6, .35)
SHAFT = paint('machined shaft', (.72, .74, .75), .8, .25)
GUARD = paint('coupling guard yellow', (.90, .68, .10))
HAND = paint('valve handwheel red', (.72, .12, .08))
OIL = paint('lube oil line ochre', (.72, .54, .16))
ACT = paint('actuator black', (.13, .14, .15), .2)
HYD = paint('hydraulic cylinder grey', (.44, .47, .49), .5, .3)
VALVE = paint('cast valve body', (.36, .39, .40), .3)
PANEL = paint('local panel enclosure', (.55, .58, .57))
TURB = paint('feed turbine casing grey', (.60, .64, .66))
TURBD = paint('feed turbine pedestal grey', (.43, .47, .49))
FPUMP = paint('feed pump barrel blue grey', (.31, .41, .51))
TERRY = paint('terry casing grey', (.55, .58, .57))
EGREEN = paint('ECCS pump green', (.20, .38, .29))
SKIDG = paint('ECCS skid green', (.16, .27, .21))
WPIPE = paint('water pipe grey green', (.44, .52, .48), .2)
GAUGE_T = tex_mat('pressure gauge dial', 'tdp_gauge')
PLATE = {k: tex_mat(k + ' nameplate', 'tdp_plate_' + k) for k in ('rfpt', 'feedpump', 'rcic', 'hpci', 'eccspump')}
GUARD_L = tex_mat('guard warning label', 'tdp_label_guard')
PANEL_T = {k: tex_mat(k + ' local panel face', 'tdp_panel_' + k) for k in ('rcic', 'hpci')}


# ---------------------------------------------------------------- primitives
def V(p):
    return Vector(p)


def frustum(s, name, a, b, r0, r1, mat, n=24):
    a, b = V(a), V(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for p, r in ((a, r0), (b, r1)) for i in range(n)]
    return mesh_object(s, name, vs, [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))] +
                       [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)], mat)


def prism(s, name, axis, t0, t1, profile, mat):
    def P(t, u, w):
        return {'x': (t, w, u), 'y': (u, t, w), 'z': (u, w, t)}[axis]
    n = len(profile)
    vs = [P(t0, u, w) for u, w in profile] + [P(t1, u, w) for u, w in profile]
    fs = [tuple(range(n)), tuple(range(2 * n - 1, n - 1, -1))] + [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def arc(cu, cw, r, a0, a1, n):
    return [(cu + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cw + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def obox(s, name, a, b, w, h, up, mat):
    """Oriented beam from a to b; h along `up`, w across."""
    a, b = V(a), V(b)
    d = (b - a).normalized()
    u = V(up) - d * d.dot(V(up))
    u = u.normalized() if u.length > 1e-6 else basis(d)[1]
    v = d.cross(u).normalized()
    vs = [tuple(p + u * h / 2 * su + v * w / 2 * sv) for p in (a, b) for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    return mesh_object(s, name, vs, [(0, 1, 2, 3), (7, 6, 5, 4), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)], mat)


def ellipsoid(s, name, c, rx, ry, rz, mat, n=16, rows=8, lat0=-90, lat1=90):
    c = V(c)
    vs = []
    for j in range(rows + 1):
        lat = math.radians(lat0 + (lat1 - lat0) * j / rows)
        for i in range(n):
            lon = i * math.tau / n
            vs.append((c.x + rx * math.cos(lat) * math.cos(lon), c.y + ry * math.sin(lat), c.z + rz * math.cos(lat) * math.sin(lon)))
    fs = [(j * n + i, j * n + (i + 1) % n, (j + 1) * n + (i + 1) % n, (j + 1) * n + i) for j in range(rows) for i in range(n)]
    if lat0 > -89:
        fs.append(tuple(range(n - 1, -1, -1)))
    if lat1 < 89:
        fs.append(tuple(range(rows * n, rows * n + n)))
    return mesh_object(s, name, vs, fs, mat)


def loft(s, name, ra, rb, mat):
    n = len(ra)
    vs = [tuple(p) for p in ra] + [tuple(p) for p in rb]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))] + [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def hexbolt(s, a, b, r=.026, mat=None):
    """Hex stud/nut: six sides and the exposed end only (the seated end is never visible)."""
    a, b = V(a), V(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + r * (math.cos(i * math.tau / 6) * u + math.sin(i * math.tau / 6) * v)) for p in (a, b) for i in range(6)]
    return mesh_object(s, 'fastener', vs, [tuple(range(6, 12))] + [(i, (i + 1) % 6, (i + 1) % 6 + 6, i + 6) for i in range(6)], mat or BOLTM)


def bolt_circle(s, c, axis, radius, count, length=.05, r=.026, mat=None, phase=0.0):
    c = V(c)
    d, u, v = basis(axis)
    for i in range(count):
        t = phase + i * math.tau / count
        q = c + radius * (math.cos(t) * u + math.sin(t) * v)
        hexbolt(s, q - d * .004, q + d * length, r, mat)


def flange_ring(s, name, c, axis, r_out, r_in, width, mat, bolts=8, bolt_r=None):
    c = V(c)
    d = V(axis).normalized()
    sleeve(s, name, c - d * width / 2, c + d * width / 2, r_out, r_in, mat, 18)
    if bolts:
        bolt_circle(s, c - d * (width / 2 + .02), d, (bolt_r or (r_out * .82)), bolts, width + .04, .022)


def pipe(s, name, points, r=.10, mat=STEEL, n=16):
    pts = [V(p) for p in points]
    clean = [pts[0]]
    for p in pts[1:]:
        if (p - clean[-1]).length > 1e-4:
            clean.append(p)
    pts = clean
    smooth = [pts[0]]
    for a, b, c in zip(pts, pts[1:], pts[2:]):
        length = min((b - a).length * .45, (c - b).length * .45, r * 1.8)
        start = b + (a - b).normalized() * length
        end = b + (c - b).normalized() * length
        smooth.extend((1 - t) ** 2 * start + 2 * (1 - t) * t * b + t * t * end for t in (i / 6 for i in range(7)))
    smooth.append(pts[-1])
    return sweep(s, name, smooth, r, mat, n)


def insulated(s, name, points, r, spacing=.55, mat=None):
    pipe(s, name, points, r, mat or JACKET, 20)
    pts = [V(p) for p in points]
    for a, b in zip(pts, pts[1:]):
        L = (b - a).length
        if L < 1e-6:
            continue
        d = (b - a) / L
        k = int(L / spacing)
        for j in range(1, k + 1):
            t = j * L / (k + 1)
            if t < r * 2.2 or L - t < r * 2.2:
                continue
            q = a + d * t
            sleeve(s, name + ' band', q - d * .012, q + d * .012, r + .009, r - .02, STRAP, 16)


def raw_mesh(s, name, vs, fs, uvs, mat):
    """Single-sided textured faces; winding is chosen by the caller (CCW seen from the front)."""
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([xyz(p) for p in vs], [], fs)
    mesh.update()
    layer = mesh.uv_layers.new(name='Material UV')
    k = 0
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            layer.data[li].uv = uvs[k]
            k += 1
    o = bpy.data.objects.new(name, mesh)
    s.collection.objects.link(o)
    mesh.materials.append(mat)
    return o


def frame(normal, up):
    n = V(normal).normalized()
    u = V(up) - n * n.dot(V(up))
    u.normalize()
    return n, u, u.cross(n)


def plate(s, name, c, normal, up, w, h, mat, back=None, depth=.014):
    c = V(c)
    n, u, r = frame(normal, up)
    obox(s, name + ' backing', c - n * depth, c, w + .024, h + .024, u, back or BOLTM)
    f = c + n * .0025
    vs = [f - r * w / 2 - u * h / 2, f + r * w / 2 - u * h / 2, f + r * w / 2 + u * h / 2, f - r * w / 2 + u * h / 2]
    raw_mesh(s, name, [tuple(p) for p in vs], [(0, 1, 2, 3)], [(0, 0), (1, 0), (1, 1), (0, 1)], mat)


def gauge(s, c, normal, r=.075, up=(0, 1, 0), stem=None):
    c = V(c)
    n, u, rt = frame(normal, up)
    cylinder(s, 'gauge case', c - n * .05, c, r, ACT, 12)
    sleeve(s, 'gauge bezel', c - n * .006, c + n * .014, r * 1.06, r * .86, STEEL, 12)
    k = 14
    ring = [c + n * .004 + r * .88 * (math.cos(i * math.tau / k) * rt + math.sin(i * math.tau / k) * u) for i in range(k)]
    vs = [tuple(c + n * .004)] + [tuple(p) for p in ring]
    fs = [(0, 1 + i, 1 + (i + 1) % k) for i in range(k)]
    uvs = []
    for i in range(k):
        for j in (None, i, (i + 1) % k):
            uvs.append((.5, .5) if j is None else (.5 + .5 * math.cos(j * math.tau / k), .5 + .5 * math.sin(j * math.tau / k)))
    raw_mesh(s, 'gauge dial', vs, fs, uvs, GAUGE_T)
    if stem is not None:
        pipe(s, 'gauge siphon', [c - n * .05, V(stem)], .012, STEEL, 8)


def handwheel(s, c, axis, r, mat=None):
    c = V(c)
    d, u, v = basis(axis)
    pts = [c + r * (math.cos(i * math.tau / 24) * u + math.sin(i * math.tau / 24) * v) for i in range(25)]
    sweep(s, 'handwheel rim', pts, max(.012, r * .1), mat or HAND, 8)
    for k in range(4):
        t = k * math.pi / 2 + math.pi / 4
        cylinder(s, 'handwheel spoke', c, c + r * .95 * (math.cos(t) * u + math.sin(t) * v), max(.008, r * .055), mat or HAND, 6)
    cylinder(s, 'handwheel hub', c - d * .03, c + d * .03, r * .2, mat or HAND, 10)


def helix(s, name, base, axis, radius, height, turns, wire, mat=None):
    base = V(base)
    d, u, v = basis(axis)
    steps = int(turns * 10)
    pts = [base + d * height * i / steps + radius * (math.cos(i * math.tau / 10) * u + math.sin(i * math.tau / 10) * v) for i in range(steps + 1)]
    sweep(s, name, pts, wire, mat or STEEL, 4)


def motor(s, a, b, r, mat, foot_y=None):
    """Totally enclosed fan-cooled motor: ribbed frame, end bells, fan cowl, terminal box, feet."""
    a, b = V(a), V(b)
    L = (b - a).length
    d, u, v = basis(b - a)
    cylinder(s, 'motor frame', a + d * L * .1, b - d * L * .18, r, mat, 20)
    for i in range(8):
        t = i * math.tau / 8
        q = math.cos(t) * u + math.sin(t) * v
        obox(s, 'motor cooling rib', a + d * L * .12 + q * r, b - d * L * .2 + q * r, .014, r * .22, q, mat)
    cylinder(s, 'motor drive end bell', a, a + d * L * .1, r * .86, mat, 20)
    cylinder(s, 'motor fan cowl', b - d * L * .18, b, r * .95, mat, 20)
    sleeve(s, 'fan cowl grille', b - d * .004, b + d * .004, r * .8, r * .25, DARK, 12)
    cylinder(s, 'motor shaft', a - d * .06, a, r * .18, SHAFT, 10)
    upv = V((0, 1, 0))
    mid = (a + b) / 2
    obox(s, 'motor terminal box', mid + upv * r * .9, mid + upv * r * 1.32, r * .7, r * .8, d, mat)
    if foot_y is not None:
        for t in (.25, .7):
            p = a + d * L * t
            hx, hz = (.06, r * .75) if abs(d.x) > .5 else (r * .75, .06)
            box(s, 'motor foot', (p.x - hx, foot_y, p.z - hz), (p.x + hx, p.y - r * .7, p.z + hz), mat)


def lifting_lug(s, c, along='x'):
    c = V(c)
    if along == 'x':
        box(s, 'lifting lug', (c.x - .07, c.y - .02, c.z - .018), (c.x + .07, c.y + .09, c.z + .018), BOLTM)
    else:
        box(s, 'lifting lug', (c.x - .018, c.y - .02, c.z - .07), (c.x + .018, c.y + .09, c.z + .07), BOLTM)


def baseplate(s, x0, x1, z0, z1, top, mat):
    t = .14
    box(s, 'baseplate side channel', (x0, 0, z0), (x1, top, z0 + t), mat)
    box(s, 'baseplate side channel', (x0, 0, z1 - t), (x1, top, z1), mat)
    box(s, 'baseplate end channel', (x0, 0, z0 + t), (x0 + t, top, z1 - t), mat)
    box(s, 'baseplate end channel', (x1 - t, 0, z0 + t), (x1, top, z1 - t), mat)
    box(s, 'baseplate deck plate', (x0 + t, top - .06, z0 + t), (x1 - t, top - .03, z1 - t), mat)
    box(s, 'baseplate bottom flange', (x0 - .02, 0, z0 - .02), (x1 + .02, .03, z0 + .02), mat)
    box(s, 'baseplate bottom flange', (x0 - .02, 0, z1 - .02), (x1 + .02, .03, z1 + .02), mat)
    n = max(2, int((x1 - x0) / .75))
    for i in range(n + 1):
        x = x0 + .2 + (x1 - x0 - .4) * i / n
        for z in (z0 + t / 2, z1 - t / 2):
            hexbolt(s, (x, top - .005, z), (x, top + .045, z), .03)
    for x in (x0 + .03, x1 - .03):
        for z in (z0 + .45, z1 - .45):
            hexbolt(s, (x, top - .005, z), (x, top + .045, z), .03)


def joint_studs(s, x0, x1, y, z, step=.2):
    n = max(1, int((x1 - x0) / step))
    for i in range(n + 1):
        x = x0 + (x1 - x0) * i / n
        hexbolt(s, (x, y - .005, z), (x, y + .06, z), .026)


def centreline_feet(s, x, ay, az, r, top, mat, span=.26):
    for side in (-1, 1):
        z_in, z_out = az + side * (r - .04), az + side * (r + span)
        lo, hi = min(z_in, z_out), max(z_in, z_out)
        box(s, 'centreline support lug', (x - .13, ay - .09, lo), (x + .13, ay + .05, hi), mat)
        plo, phi = min(az + side * (r + .02), az + side * (r + span + .02)), max(az + side * (r + .02), az + side * (r + span + .02))
        box(s, 'support pedestal', (x - .16, top - .03, plo), (x + .16, ay - .09, phi), mat)
        hexbolt(s, (x, ay + .045, (lo + hi) / 2 + side * .06), (x, ay + .11, (lo + hi) / 2 + side * .06), .03)


def coupling(s, x0, x1, ay, az, r, top):
    cylinder(s, 'coupling hub', (x0 + .04, ay, az), (x0 + .16, ay, az), r * .55, STEEL, 16)
    cylinder(s, 'coupling hub', (x1 - .16, ay, az), (x1 - .04, ay, az), r * .55, STEEL, 16)
    cylinder(s, 'coupling spacer', (x0 + .16, ay, az), (x1 - .16, ay, az), r * .27, SHAFT, 12)
    base = top + .22
    profile = [(az + r, base)] + arc(az, ay, r, 0, 180, 16) + [(az - r, base)]
    prism(s, 'coupling guard', 'x', x0, x1, profile, GUARD)
    box(s, 'guard mounting flange', (x0, base - .04, az - r - .04), (x1, base, az + r + .04), GUARD)
    for z in (az - r - .02, az + r + .02):
        for x in (x0 + .06, x1 - .06):
            box(s, 'guard stand', (x - .03, top - .03, z - .03), (x + .03, base - .04, z + .03), GUARD)
    # Expanded-metal inspection windows (dark) on the curved top, then the warning label on the flat side.
    plate(s, 'guard warning label', ((x0 + x1) / 2, (base + ay) / 2, az - r - .002), (0, 0, -1), (0, 1, 0),
          min(.3, (x1 - x0) * .55), min(.15, (ay - base) * .8), GUARD_L, GUARD, .004)
    plate(s, 'guard warning label', ((x0 + x1) / 2, (base + ay) / 2, az + r + .002), (0, 0, 1), (0, 1, 0),
          min(.3, (x1 - x0) * .55), min(.15, (ay - base) * .8), GUARD_L, GUARD, .004)


def modern_port(s, role, cell, face, start):
    d = V(DIRV[face])
    p = V(cell) + V((.5, .5, .5)) + d * .5
    sleeve(s, role + ' neck', V(start), p - d * .08, .25, .205, BODY)
    flange(s, role, p, d)
    band = AMBER if role.startswith('steam') else BLUE if role == 'water_suction' else TEAL
    sleeve(s, role + ' service band', p - d * .30, p - d * .20, .257, .249, band)
    marker(s, role.upper(), p, role=role.upper(), cell=cell, face=face)
    return p


def terry_port(s, role, cell, face, route, mat):
    d = V(DIRV[face])
    p = V(cell) + V((.5, .5, .5)) + d * .5
    inner = p - d * .32
    pipe(s, role + ' nozzle', [V(q) for q in route] + [inner], .245, mat, 20)
    sleeve(s, role + ' open terminal bore', inner, p, .245, .205, STEEL, 24)
    steam_flange(s, role + ' block face flange', p, d)
    band = {'STEAM_INLET': AMBER, 'STEAM_EXHAUST': JACKET, 'WATER_SUCTION': BLUE, 'WATER_DISCHARGE': TEAL}[role]
    sleeve(s, role + ' identification band', p - d * .27, p - d * .18, .254, .244, band, 24)
    marker(s, role, p, role=role, cell=cell, face=face)
    return p


def globe_valve(s, name, c, flow, r, body=None, stem_len=.5, top='wheel', wheel_r=.16):
    """Flanged globe/stop valve with bonnet, yoke, stem and handwheel or actuator cap. Returns stem top."""
    c = V(c)
    f = V(flow).normalized()
    up = V((0, 1, 0))
    ellipsoid(s, name + ' body', c, r if abs(f.x) < .5 else r * 1.15, r * .95, r if abs(f.z) < .5 else r * 1.15, body or VALVE, 16, 8)
    for sgn in (-1, 1):
        q = c + f * sgn * r * 1.15
        flange_ring(s, name + ' end flange', q, f, r * 1.2, r * .45, .06, body or VALVE, 8, r * 1.0)
    b0 = c + up * r * .7
    frustum(s, name + ' bonnet', b0, b0 + up * r * 1.0, r * .62, r * .5, body or VALVE, 16)
    flange_ring(s, name + ' bonnet flange', b0 + up * .03, up, r * .78, r * .2, .05, body or VALVE, 6, r * .66)
    y0 = b0 + up * r * 1.0
    y1 = y0 + up * stem_len
    side = f.cross(up).normalized()
    for sg in (-1, 1):
        obox(s, name + ' yoke arm', y0 + side * sg * r * .38, y1 + side * sg * r * .3, .035, .035, f, body or VALVE)
    obox(s, name + ' yoke head', y1 - up * .025 + side * r * .42, y1 - up * .025 - side * r * .42, .07, .05, up, body or VALVE)
    cylinder(s, name + ' stem', y0, y1 + up * .1, .022, SHAFT, 8)
    if top == 'wheel':
        handwheel(s, y1 + up * .1, up, wheel_r)
    return y1 + up * .1


def check_wired(id, ports, size, controller):
    shipped = json.loads((MANIFESTS / (id + '.json')).read_text(encoding='utf-8'))
    got = [{'role': r.upper(), 'cell': c, 'face': f} for r, c, f in ports]
    assert shipped['game_ports'] == got, (id, shipped['game_ports'], got)
    assert [shipped['width'], shipped['height'], shipped['depth']] == size, (id, size)
    assert shipped['controller_cell'] == controller, (id, controller)


def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def export(s, path, meta):
    faces, boxes, mats = [], [], {}
    lo, hi = [9e9] * 3, [-9e9] * 3
    for o in s.objects:
        if o.type != 'MESH':
            continue
        me = o.data
        me.calc_loop_triangles()
        pts = [game(o.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in pts) for a in range(3)] + [max(p[a] for p in pts) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            key = m.name
            if key not in mats:
                tex = m.get('tdp_texture')
                bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
                rgb = list(bsdf.inputs['Base Color'].default_value[:3])
                mats[key] = {'color': [1, 1, 1] if tex else [srgb(x) for x in rgb],
                             'texture': ('bwr:block/modern/' + tex) if tex else 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(pts[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (V(vs[1][:3]) - V(vs[0][:3])).cross(V(vs[2][:3]) - V(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
                for p in vs:
                    for a in range(3):
                        lo[a] = min(lo[a], p[a])
                        hi[a] = max(hi[a], p[a])
    size = meta['size']
    assert all(lo[a] >= -1e-6 and hi[a] <= size[a] + 1e-6 for a in range(3)), ('outside footprint', path.name, lo, hi)
    path.write_text(json.dumps(dict(meta, faces=faces, boxes=boxes, materials=mats), separators=(',', ':')), encoding='utf-8')
    print('TDP EXPORT', path.name, len(faces), 'triangles', len(mats), 'materials', 'bounds', [round(x, 3) for x in lo + hi])


# ================================================================ reactor feed pump turbine + barrel pump (9 x 3 x 3)
TFP_PORTS = [('water_suction', [1, 2, 1], 'up'), ('water_discharge', [3, 2, 1], 'up'),
             ('steam_inlet', [6, 2, 1], 'up'), ('steam_exhaust', [7, 1, 2], 'south')]


def build_tfp():
    s = new_scene('BWR | turbine pumps | turbine_feed_pump')
    AY, AZ, TOP = 1.2, 1.5, .30
    baseplate(s, .08, 8.92, .12, 2.88, TOP, BASEP)
    # ---- barrel feed pump (outer barrel, bolted end covers, centreline feet, top nozzles)
    R = .56
    cylinder(s, 'feed pump outer barrel', (.80, AY, AZ), (3.90, AY, AZ), R, FPUMP, 32)
    for x in (.95, 3.75):
        sleeve(s, 'barrel girth band', (x - .03, AY, AZ), (x + .03, AY, AZ), R + .018, R - .02, FPUMP, 24)
    cylinder(s, 'discharge end cover', (.62, AY, AZ), (.80, AY, AZ), .64, FPUMP, 32)
    cylinder(s, 'end cover boss', (.45, AY, AZ), (.62, AY, AZ), .44, FPUMP, 28)
    cylinder(s, 'non drive end bearing housing', (.22, AY, AZ), (.45, AY, AZ), .21, FPUMP, 20)
    cylinder(s, 'bearing end cap', (.16, AY, AZ), (.22, AY, AZ), .14, STEEL, 16)
    box(s, 'bearing cap split line', (.22, AY - .012, AZ - .23), (.45, AY + .012, AZ + .23), BOLTM)
    for side in (-1, 1):
        hexbolt(s, (.28, AY + .012, AZ + side * .2), (.28, AY + .06, AZ + side * .2), .022)
        hexbolt(s, (.39, AY + .012, AZ + side * .2), (.39, AY + .06, AZ + side * .2), .022)
    for i in range(16):
        t = i * math.tau / 16 + math.tau / 32
        q = V((0, AY + .55 * math.sin(t), AZ + .55 * math.cos(t)))
        cylinder(s, 'cover stud', (.50, q.y, q.z), (.84, q.y, q.z), .03, BOLTM, 6)
        cylinder(s, 'cover stud nut', (.50, q.y, q.z), (.585, q.y, q.z), .062, BOLTM, 6)
    cylinder(s, 'drive end cover', (3.90, AY, AZ), (4.02, AY, AZ), .62, FPUMP, 32)
    for i in range(16):
        t = i * math.tau / 16 + math.tau / 32
        q = V((0, AY + .55 * math.sin(t), AZ + .55 * math.cos(t)))
        cylinder(s, 'cover stud nut', (4.02, q.y, q.z), (4.08, q.y, q.z), .058, BOLTM, 6)
    cylinder(s, 'mechanical seal housing', (4.02, AY, AZ), (4.22, AY, AZ), .27, FPUMP, 24)
    cylinder(s, 'drive end bearing housing', (4.22, AY, AZ), (4.44, AY, AZ), .21, FPUMP, 20)
    box(s, 'bearing cap split line', (4.22, AY - .012, AZ - .23), (4.44, AY + .012, AZ + .23), BOLTM)
    cylinder(s, 'pump shaft', (4.44, AY, AZ), (4.56, AY, AZ), .065, SHAFT, 12)
    for x in (1.05, 3.62):
        centreline_feet(s, x, AY, AZ, R, TOP, FPUMP)
    # Conical nozzles rise to the existing flanges.
    for x in (1.5, 3.5):
        frustum(s, 'barrel nozzle', (x, AY + .3, AZ), (x, 2.48, AZ), .36, .27, FPUMP, 24)
        sleeve(s, 'nozzle weld ring', (x, 2.40, AZ), (x, 2.48, AZ), .30, .2, FPUMP, 16)
    modern_port(s, 'water_suction', [1, 2, 1], 'up', (1.5, 2.46, AZ))
    modern_port(s, 'water_discharge', [3, 2, 1], 'up', (3.5, 2.46, AZ))
    gauge(s, (1.5, 2.22, AZ - .5), (0, 0, -1), .07, stem=(1.5, 2.22, AZ - .3))
    gauge(s, (3.5, 2.22, AZ - .5), (0, 0, -1), .07, stem=(3.5, 2.22, AZ - .3))
    # Vent on top, balance leak-off and seal flush lines, nameplate.
    cylinder(s, 'barrel vent nipple', (2.5, AY + R - .02, AZ), (2.5, AY + R + .16, AZ), .025, STEEL, 8)
    handwheel(s, (2.5, AY + R + .17, AZ), (0, 1, 0), .055)
    pipe(s, 'balance leak-off line', [(4.12, AY - .1, AZ + .22), (4.12, AY - .1, AZ + .48), (4.12, TOP + .05, AZ + .48)], .035, STEEL, 10)
    pipe(s, 'seal flush line', [(4.08, AY + .25, AZ - .12), (4.08, AY + .45, AZ - .12), (3.0, AY + .45, AZ - .12), (3.0, AY + R - .03, AZ - .12)], .018, STEEL, 8)
    plate(s, 'feed pump nameplate', (2.45, AY, AZ - R - .018), (0, 0, -1), (0, 1, 0), .36, .18, PLATE['feedpump'])
    # ---- coupling and guard
    coupling(s, 4.50, 5.12, AY, AZ, .34, TOP)
    # ---- turbine: exhaust-end pedestal, casing with bolted horizontal joint, HP end, front standard
    box(s, 'exhaust end bearing pedestal', (5.14, TOP - .03, AZ - .42), (5.50, AY - .2, AZ + .42), TURBD)
    cylinder(s, 'exhaust end bearing housing', (5.16, AY, AZ), (5.48, AY, AZ), .29, TURBD, 24)
    box(s, 'bearing housing joint', (5.14, AY - .03, AZ - .38), (5.50, AY + .03, AZ + .38), TURBD)
    for z in (AZ - .34, AZ + .34):
        joint_studs(s, 5.2, 5.44, AY + .03, z, .24)
    cylinder(s, 'turbine shaft', (5.0, AY, AZ), (5.62, AY, AZ), .065, SHAFT, 12)
    cylinder(s, 'exhaust end gland housing', (5.48, AY, AZ), (5.62, AY, AZ), .2, STEEL, 20)
    HW = .86
    box(s, 'exhaust hood lower casing', (5.62, TOP - .03, AZ - HW), (8.05, AY, AZ + HW), TURB)
    prism(s, 'turbine upper casing', 'x', 5.62, 8.05, arc(AZ, AY, .92, 0, 180, 28), TURB)
    for x0, x1 in ((5.58, 5.64), (8.03, 8.09)):
        prism(s, 'upper casing end rim', 'x', x0, x1, arc(AZ, AY, .95, 0, 180, 28), TURBD)
    box(s, 'casing horizontal joint flange', (5.58, AY - .04, AZ - 1.0), (8.09, AY + .04, AZ + 1.0), TURBD)
    for z in (AZ - .96, AZ + .96):
        joint_studs(s, 5.66, 8.0, AY + .04, z, .22)
    for x in (5.9, 6.4, 6.9, 7.4, 7.85):
        box(s, 'hood stiffener rib', (x - .03, TOP, AZ - HW - .05), (x + .03, AY - .06, AZ - HW), TURBD)
    for x in (5.9, 6.4, 6.9):
        box(s, 'hood stiffener rib', (x - .03, TOP, AZ + HW), (x + .03, AY - .06, AZ + HW + .05), TURBD)
    for x in (6.0, 7.7):
        lifting_lug(s, (x, AY + .92, AZ), 'x')
    # Inspection cover on the upper casing (north side).
    sleeve(s, 'casing inspection cover', (6.55, AY + .62, AZ - .62), (6.55, AY + .66, AZ - .68), .17, .0, TURBD, 20)
    bolt_circle(s, (6.55, AY + .66, AZ - .68), (0, .7, -.7), .14, 8, .03, .016)
    frustum(s, 'HP casing', (8.05, AY, AZ), (8.60, AY, AZ), .70, .52, TURB, 28)
    box(s, 'HP casing joint flange', (8.05, AY - .04, AZ - .80), (8.62, AY + .04, AZ + .80), TURBD)
    for z in (AZ - .76, AZ + .76):
        joint_studs(s, 8.1, 8.56, AY + .04, z, .23)
    box(s, 'front standard', (8.60, TOP - .03, AZ - .45), (8.94, AY + .05, AZ + .45), TURBD)
    prism(s, 'front standard cover', 'x', 8.60, 8.94, arc(AZ, AY + .05, .45, 0, 180, 16), TURBD)
    box(s, 'front standard cover joint', (8.58, AY + .03, AZ - .48), (8.96, AY + .07, AZ + .48), TURBD)
    cylinder(s, 'hand trip knob', (8.94, AY + .28, AZ + .2), (8.985, AY + .28, AZ + .2), .045, HAND, 12)
    cylinder(s, 'speed pickup', (8.94, AY + .1, AZ - .2), (8.98, AY + .1, AZ - .2), .03, ACT, 8)
    gauge(s, (8.77, AY - .05, AZ - .456), (0, 0, -1), .06)
    plate(s, 'turbine nameplate', (6.85, .78, AZ - HW - .003), (0, 0, -1), (0, 1, 0), .38, .19, PLATE['rfpt'])
    # ---- steam admission: inlet elbow, main stop valve with hydraulic actuator, control valve chest + bar lift
    SY = 2.42
    insulated(s, 'steam inlet elbow', [(6.5, 2.7, AZ), (6.5, SY, AZ), (6.98, SY, AZ)], .22)
    modern_port(s, 'steam_inlet', [6, 2, 1], 'up', (6.5, 2.6, AZ))
    ellipsoid(s, 'main stop valve body', (7.22, SY, AZ), .27, .25, .25, VALVE, 20, 8)
    for x in (6.98, 7.46):
        flange_ring(s, 'stop valve flange', (x, SY, AZ), (1, 0, 0), .29, .1, .06, VALVE, 8, .24)
    box(s, 'stop valve saddle', (7.08, AY + .86, AZ - .14), (7.36, SY - .2, AZ + .14), TURBD)
    cylinder(s, 'stop valve actuator', (7.22, SY, AZ - .22), (7.22, SY, AZ - .68), .13, HYD, 20)
    cylinder(s, 'stop valve spring can', (7.22, SY, AZ - .68), (7.22, SY, AZ - 1.02), .16, ACT, 20)
    cylinder(s, 'actuator end cap', (7.22, SY, AZ - 1.02), (7.22, SY, AZ - 1.06), .12, HYD, 16)
    box(s, 'limit switch box', (7.34, SY + .08, AZ - .62), (7.46, SY + .2, AZ - .44), ACT)
    pipe(s, 'actuator oil tubing', [(7.22, SY - .13, AZ - .5), (7.22, AY + .95, AZ - .5), (7.8, AY + .95, AZ - .5)], .014, OIL, 8)
    insulated(s, 'stop to control valve lead', [(7.46, SY, AZ), (7.78, SY + .03, AZ)], .17)
    cylinder(s, 'control valve steam chest', (7.76, SY + .03, AZ), (8.76, SY + .03, AZ), .22, TURBD, 24)
    for x in (7.76, 8.76):
        flange_ring(s, 'steam chest cover', (x, SY + .03, AZ), (1, 0, 0), .26, .0, .04, TURBD, 8, .2)
    for x in (8.12, 8.48):
        cylinder(s, 'steam lead to nozzle box', (x, SY - .15, AZ), (x, AY + .55, AZ), .1, JACKET, 16)
    for x in (7.86, 8.66):
        cylinder(s, 'bar lift post', (x, SY + .2, AZ), (x, 2.92, AZ), .03, SHAFT, 8)
    box(s, 'bar lift crossbar', (7.82, 2.86, AZ - .05), (8.70, 2.93, AZ + .05), STEEL)
    for x in (8.06, 8.26, 8.46):
        cylinder(s, 'control valve stem', (x, SY + .22, AZ), (x, 2.86, AZ), .02, SHAFT, 8)
    box(s, 'servomotor bracket', (8.74, AY + .3, AZ + .26), (8.9, AY + .72, AZ + .44), TURBD)
    cylinder(s, 'control valve servomotor', (8.82, AY + .72, AZ + .35), (8.82, 2.62, AZ + .35), .09, HYD, 16)
    cylinder(s, 'servomotor head', (8.82, 2.62, AZ + .35), (8.82, 2.7, AZ + .35), .11, ACT, 16)
    obox(s, 'servomotor lever', (8.82, 2.78, AZ + .35), (8.68, 2.9, AZ + .02), .03, .03, (0, 1, 0), STEEL)
    cylinder(s, 'servomotor rod', (8.82, 2.7, AZ + .35), (8.82, 2.78, AZ + .35), .025, SHAFT, 8)
    # ---- side exhaust to the condenser connection: rectangular hood outlet tapering to the round flange
    cylinder(s, 'exhaust nozzle', (7.5, 1.5, 2.05), (7.5, 1.5, 2.6), .31, TURB, 24)
    box(s, 'exhaust nozzle reinforcing pad', (7.08, AY - .25, AZ + HW), (7.92, 1.5, AZ + HW + .03), TURBD)
    for x in (7.12, 7.88):
        obox(s, 'nozzle gusset', (x, AY - .2, AZ + HW + .02), (x, 1.5, 2.5), .03, .1, (1, 0, 0), TURBD)
    flange_ring(s, 'exhaust joint flange', (7.5, 1.5, 2.6), (0, 0, 1), .34, .2, .05, TURBD, 12, .31)
    modern_port(s, 'steam_exhaust', [7, 1, 2], 'south', (7.5, 1.5, 2.6))
    # ---- lube oil console (south side): reservoir, two motor-driven pumps, duplex filter, oil cooler
    box(s, 'lube oil reservoir', (4.30, TOP - .03, 2.42), (5.92, .86, 2.84), TURBD)
    box(s, 'reservoir cover', (4.28, .86, 2.40), (5.94, .89, 2.86), TURBD)
    for x0 in (4.40, 5.02):
        motor(s, (x0 + .2, 1.03, 2.63), (x0 + .55, 1.03, 2.63), .13, TURBD, .89)
        cylinder(s, 'oil pump', (x0 + .05, 1.0, 2.63), (x0 + .2, 1.0, 2.63), .08, VALVE, 12)
    for x in (5.66, 5.82):
        cylinder(s, 'duplex oil filter can', (x, .89, 2.63), (x, 1.24, 2.63), .065, STEEL, 14)
        cylinder(s, 'filter cap', (x, 1.24, 2.63), (x, 1.28, 2.63), .075, BOLTM, 14)
    obox(s, 'filter transfer handle', (5.66, 1.32, 2.63), (5.82, 1.32, 2.63), .02, .02, (0, 1, 0), HAND)
    cylinder(s, 'lube oil cooler shell', (6.05, .55, 2.64), (6.95, .55, 2.64), .12, STEEL, 16)
    for x in (6.05, 6.95):
        flange_ring(s, 'cooler bonnet', (x, .55, 2.64), (1, 0, 0), .15, .0, .04, STEEL, 6, .13)
    for x in (6.25, 6.75):
        box(s, 'cooler saddle', (x - .04, TOP - .03, 2.54), (x + .04, .48, 2.74), TURBD)
    gauge(s, (5.1, .66, 2.415), (0, 0, -1), .055)
    # Oil supply header along the north side with risers to the four bearings.
    pipe(s, 'oil supply from console', [(4.36, .45, 2.40), (4.36, .40, 2.40), (4.36, .40, .52)], .028, OIL, 8)
    pipe(s, 'oil supply header', [(.33, .40, .52), (8.77, .40, .52)], .028, OIL, 8)
    for x in (.33, 4.33, 5.31, 8.77):
        pipe(s, 'bearing oil riser', [(x, .40, .52), (x, AY - .08, .52), (x, AY - .08, AZ - .12)], .022, OIL, 8)
    # Local gauge board for the pump and turbine pressures.
    box(s, 'gauge board post', (4.77, TOP, .2), (4.83, 1.28, .26), TURBD)
    box(s, 'gauge board', (4.42, 1.26, .18), (5.18, 1.66, .26), TURBD)
    for x in (4.56, 4.80, 5.04):
        gauge(s, (x, 1.48, .176), (0, 0, -1), .075)
    for x in (4.56, 4.80, 5.04):
        pipe(s, 'gauge impulse tubing', [(x, 1.26, .22), (x, TOP + .02, .22)], .008, STEEL, 6)
    meta = {'id': 'turbine_feed_pump', 'size': [9, 3, 3], 'controller': [4, 0, 1], 'scale': 1.0,
            'ports': [{'role': r.upper(), 'cell': c, 'face': f} for r, c, f in TFP_PORTS]}
    check_wired('turbine_feed_pump_modern', TFP_PORTS, meta['size'], meta['controller'])
    export(s, ROOT / 'art/models/modern/turbine_feed_pump.json', meta)
    return s


# ================================================================ Terry skids
def terry_turbine(s, tx, ay, az, R, half, top, casing_mat, insulated_casing, pedestal_mat, skid_mat, plate_mat):
    """Single-wheel Terry turbine: horizontally split drum casing with bolted side covers, bearing housings."""
    x0, x1 = tx - half, tx + half
    if insulated_casing:
        prism(s, 'turbine casing lower half', 'x', x0, x1, arc(az, ay, R, 180, 360, 28), JACKET)
        prism(s, 'turbine casing upper half', 'x', x0, x1, arc(az, ay, R, 0, 180, 28), JACKET)
        for x in [x0 + (x1 - x0) * k / 4 for k in (1, 2, 3)]:
            sleeve(s, 'insulation band', (x - .012, ay, az), (x + .012, ay, az), R + .01, R - .03, STRAP, 24)
        cover_mat = pedestal_mat
    else:
        prism(s, 'turbine casing lower half', 'x', x0, x1, arc(az, ay, R, 180, 360, 28), casing_mat)
        prism(s, 'turbine casing upper half', 'x', x0, x1, arc(az, ay, R, 0, 180, 28), casing_mat)
        cover_mat = casing_mat
    for xa, xb, sgn in ((x0 - .035, x0, -1), (x1, x1 + .035, 1)):
        cylinder(s, 'casing side cover', (xa, ay, az), (xb, ay, az), R * .94, cover_mat, 32)
        xf = xa if sgn < 0 else xb
        bolt_circle(s, (xf, ay, az), (sgn, 0, 0), R * .86, 16, .035, .024)
        cylinder(s, 'bearing boss', (xf, ay, az), (xf + sgn * .08, ay, az), R * .4, cover_mat, 24)
    box(s, 'casing horizontal joint', (x0 - .04, ay - .035, az - R - .13), (x1 + .04, ay + .035, az + R + .13), cover_mat)
    for z in (az - R - .08, az + R + .08):
        joint_studs(s, x0, x1, ay + .035, z, .17)
    lifting_lug(s, (tx, ay + R, az), 'x')
    box(s, 'casing support pedestal', (x0 + .04, top - .03, az - R * .7), (x1 - .04, ay - R * .75, az + R * .7), pedestal_mat)
    for z in (az - R * .7 - .14, az + R * .7):
        box(s, 'pedestal foot flange', (x0 + .04, top - .03, z), (x1 - .04, top + .06, z + .14), pedestal_mat)
        for x in (x0 + .12, x1 - .12):
            hexbolt(s, (x, top + .055, z + .07), (x, top + .11, z + .07), .028)
    plate(s, 'turbine nameplate', (tx, (top + ay - R * .75) / 2, az - R * .7 - .003), (0, 0, -1), (0, 1, 0),
          min(.32, half * 1.7), min(.16, (ay - R * .75 - top) * .85), plate_mat)


def bearing_housing(s, xa, xb, ay, az, r, mat):
    cylinder(s, 'bearing housing', (xa, ay, az), (xb, ay, az), r, mat, 20)
    box(s, 'bearing cap joint', (xa, ay - .015, az - r - .03), (xb, ay + .015, az + r + .03), mat)
    for z in (az - r - .01, az + r + .01):
        hexbolt(s, ((xa + xb) / 2, ay + .015, z), ((xa + xb) / 2, ay + .07, z), .022)
    cylinder(s, 'oil level sight glass', ((xa + xb) / 2, ay - r * .45, az - r * .9), ((xa + xb) / 2, ay - r * .45, az - r - .03), .03, ACT, 10)
    cylinder(s, 'bearing temperature element', ((xa + xb) / 2, ay + r - .02, az), ((xa + xb) / 2, ay + r + .07, az), .018, STEEL, 8)


def build_terry(id):
    hp = id == 'hpci_turbine'
    size = [7, 5, 5] if hp else [5, 4, 5]
    ports = ([('steam_inlet', [0, 3, 2], 'west'), ('steam_exhaust', [2, 1, 0], 'north'),
              ('water_suction', [6, 1, 2], 'east'), ('water_discharge', [5, 1, 4], 'south')] if hp else
             [('steam_inlet', [0, 2, 2], 'west'), ('steam_exhaust', [1, 1, 0], 'north'),
              ('water_suction', [4, 1, 2], 'east'), ('water_discharge', [3, 1, 4], 'south')])
    check_wired(id + '_terry', ports, size, [0, 0, 0])
    s = new_scene('BWR | turbine pumps | ' + id)
    AZ = 2.5
    if hp:
        AY, TOP, R, TX, HALF = 1.05, .25, .80, 2.45, .45
    else:
        AY, TOP, R, TX, HALF = 1.10, .28, .70, 1.45, .30
    W = size[0]
    baseplate(s, .12, W - .12, .18, 4.82, TOP, SKIDG)
    plate_mat = PLATE['hpci' if hp else 'rcic']
    terry_turbine(s, TX, AY, AZ, R, HALF, TOP, TERRY, hp, TURBD if hp else TERRY, SKIDG, plate_mat)
    bw = .38 if hp else .32
    bx0 = TX - HALF - .12 - bw
    bearing_housing(s, bx0, TX - HALF - .12, AY, AZ, .22 if hp else .2, TURBD if hp else TERRY)
    bearing_housing(s, TX + HALF + .12, TX + HALF + .12 + bw, AY, AZ, .22 if hp else .2, TURBD if hp else TERRY)
    cylinder(s, 'turbine shaft', (bx0 - .3, AY, AZ), (TX + HALF + .7, AY, AZ), .06, SHAFT, 12)
    # Governor end: mechanical overspeed trip, governor drive and the hydraulic governor actuator on top.
    gx1 = bx0
    gx0 = gx1 - (.26 if hp else .22)
    cylinder(s, 'governor drive housing', (gx0, AY, AZ), (gx1, AY, AZ), .17, TURBD if hp else TERRY, 18)
    obox(s, 'overspeed trip tappet lever', (gx0 + .05, AY + .12, AZ - .2), (gx0 + .05, AY + .34, AZ - .26), .03, .03, (1, 0, 0), HAND)
    cylinder(s, 'trip reset knob', (gx0 + .05, AY + .34, AZ - .26), (gx0 + .05, AY + .34, AZ - .32), .03, HAND, 10)
    ex0, ex1 = gx0 - .02, gx1 + .08
    box(s, 'governor actuator', (ex0, AY + .2, AZ - .17), (ex1, AY + .68, AZ + .17), ACT)
    cylinder(s, 'governor actuator cover', ((ex0 + ex1) / 2, AY + .68, AZ), ((ex0 + ex1) / 2, AY + .76, AZ), .14, ACT, 16)
    box(s, 'governor nameplate band', (ex0 - .004, AY + .45, AZ - .1), (ex0, AY + .55, AZ + .1), STEEL)
    pipe(s, 'governor oil tubing', [((ex0 + ex1) / 2, AY + .2, AZ + .17), ((ex0 + ex1) / 2, AY + .1, AZ + .3), (TX - HALF, AY + .1, AZ + .3)], .014, OIL, 8)
    # Steam admission: trip-throttle valve with handwheel, governor valve with spring and linkage, chest.
    SY = 3.5 if hp else 2.5
    vr = .22 if hp else .18
    ttx = .62 if hp else .56
    terry_port(s, 'STEAM_INLET', ports[0][1], 'west', [(ttx - vr * 1.2, SY, AZ)], JACKET)
    if hp:
        st = globe_valve(s, 'turbine stop valve', (ttx, SY, AZ), (1, 0, 0), vr, VALVE, .3, 'none')
        cylinder(s, 'stop valve hydraulic cylinder', st, st + V((0, .42, 0)), .13, HYD, 18)
        cylinder(s, 'stop valve spring housing', st + V((0, .42, 0)), st + V((0, .5, 0)), .16, ACT, 18)
        box(s, 'stop valve limit switch', (ttx + .14, st.y + .05, AZ - .06), (ttx + .26, st.y + .2, AZ + .06), ACT)
        pipe(s, 'stop valve oil tubing', [st + V((0, .2, -.13)), (ttx, st.y + .2, AZ - .3), (ttx, SY + .1, AZ - .3), (ttx + vr * 2.5 - .2, SY + .1, AZ - .3)], .014, OIL, 8)
    else:
        globe_valve(s, 'trip throttle valve', (ttx, SY, AZ), (1, 0, 0), vr, VALVE, .3, 'wheel', .17)
    if not hp:
        obox(s, 'trip latch lever', (ttx, SY + vr * 1.9, AZ + .1), (ttx + .18, SY + vr * 2.2, AZ + .22), .025, .025, (0, 1, 0), HAND)
    gvx = ttx + vr * 2.5 + .1
    # Pipe support stanchion and saddle under the inlet valve train.
    # Wide-flange (H) stanchion: web along x, two flanges across z.
    sh, top_y = (.09 if hp else .07), SY - vr * 1.05
    box(s, 'stanchion web', (ttx - sh, TOP - .03, AZ - .012), (ttx + sh, top_y, AZ + .012), BASEP)
    for sz in (-1, 1):
        box(s, 'stanchion flange', (ttx - sh, TOP - .03, AZ + sz * sh - .012), (ttx + sh, top_y, AZ + sz * sh + .012), BASEP)
    box(s, 'stanchion base plate', (ttx - .16, TOP - .03, AZ - .16), (ttx + .16, TOP + .03, AZ + .16), BASEP)
    for dx in (-.11, .11):
        for dz in (-.11, .11):
            hexbolt(s, (ttx + dx, TOP + .025, AZ + dz), (ttx + dx, TOP + .07, AZ + dz), .022)
    box(s, 'valve support saddle', (ttx - .13, top_y - .03, AZ - .12), (ttx + .13, SY - vr * .9, AZ + .12), BASEP)
    spool = (ttx + vr * 1.2 + .03, gvx - vr * 1.0 - .03)
    cylinder(s, 'valve spool', (spool[0], SY, AZ), (spool[1], SY, AZ), vr * .55, STEEL, 16)
    gtop = globe_valve(s, 'governor valve', (gvx, SY, AZ), (1, 0, 0), vr * .85, VALVE, .22, 'none')
    helix(s, 'governor valve opening spring', (gvx, SY + vr * 1.45, AZ), (0, 1, 0), .055, .2, 4, .008)
    lz = AZ - .3
    obox(s, 'governor valve lever', gtop, (gvx - .05, gtop.y, lz), .03, .03, (0, 1, 0), STEEL)
    rx = (ex0 + ex1) / 2
    pipe(s, 'governor linkage rod', [(gvx - .05, gtop.y, lz), (rx, gtop.y - .1, lz), (rx, AY + .62, lz)], .014, SHAFT, 6)
    obox(s, 'actuator output lever', (rx, AY + .58, AZ - .17), (rx, AY + .62, lz), .03, .03, (0, 1, 0), STEEL)
    out_x = gvx + vr * .85 * 1.2 + .03
    chest_x = TX - HALF * .35
    chest_top = AY + R + .12
    insulated(s, 'governor valve to steam chest', [(out_x - .03, SY, AZ), (chest_x, SY, AZ), (chest_x, chest_top, AZ)], vr * .62, .5)
    box(s, 'steam chest', (chest_x - .2, AY + R - .12, AZ - .24), (chest_x + .22, chest_top, AZ + .24), cover_mat_for(hp))
    box(s, 'steam chest cover', (chest_x - .22, chest_top, AZ - .26), (chest_x + .24, chest_top + .04, AZ + .26), STEEL)
    for dx in (-.16, .18):
        for dz in (-.2, .2):
            hexbolt(s, (chest_x + dx, chest_top + .035, AZ + dz), (chest_x + dx, chest_top + .08, AZ + dz), .022)
    gauge(s, (chest_x + .28, chest_top - .1, AZ - .245), (0, 0, -1), .06)
    # Exhaust: insulated line north, swing check valve, rupture diaphragm branch, reducer to the port.
    ex_x = 2.5 if hp else 1.5
    er = .33 if hp else .29
    zc = AZ - math.sqrt(max(R * R - (1.5 - AY) ** 2, 0)) + .12
    insulated(s, 'turbine exhaust line', [(ex_x, 1.5, zc), (ex_x, 1.5, .66)], er, .5)
    frustum(s, 'exhaust reducer', (ex_x, 1.5, .66), (ex_x, 1.5, .46), er, .245, JACKET, 24)
    terry_port(s, 'STEAM_EXHAUST', ports[1][1], 'north', [(ex_x, 1.5, .5)], JACKET)
    ckz = (zc + .66) / 2 + .1
    cylinder(s, 'exhaust check valve body', (ex_x, 1.5, ckz - .14), (ex_x, 1.5, ckz + .14), er + .06, VALVE, 24)
    for z in (ckz - .14, ckz + .14):
        flange_ring(s, 'check valve flange', (ex_x, 1.5, z), (0, 0, 1), er + .1, er - .05, .05, VALVE, 10, er + .06)
    cylinder(s, 'check valve hinge cover', (ex_x, 1.5 + er, ckz), (ex_x, 1.5 + er + .12, ckz), .14, VALVE, 16)
    bolt_circle(s, (ex_x, 1.5 + er + .12, ckz), (0, 1, 0), .11, 6, .03, .016)
    rz = (ckz - .14 + .66) / 2
    cylinder(s, 'rupture diaphragm branch', (ex_x, 1.5 + er - .03, rz), (ex_x, 2.05 if not hp else 2.25, rz), .09, STEEL, 14)
    for y in ((1.95, 2.0) if not hp else (2.15, 2.2)):
        flange_ring(s, 'rupture disc holder', (ex_x, y, rz), (0, 1, 0), .15, .07, .05, STEEL, 6, .12)
    cylinder(s, 'diaphragm vent cap', (ex_x, 2.05 if not hp else 2.25, rz), (ex_x, (2.05 if not hp else 2.25) + .06, rz), .11, STEEL, 14)
    pipe(s, 'exhaust drain', [(ex_x, 1.5 - er + .02, rz), (ex_x, TOP + .1, rz), (ex_x + .4, TOP + .1, rz)], .025, STEEL, 8)
    # Coupling and guard to the pump.
    cx0 = TX + HALF + .12 + bw
    cx1 = cx0 + (.65 if hp else .6)
    coupling(s, cx0, cx1, AY, AZ, .3 if hp else .28, TOP)
    # Multistage pump with tangential discharge (south) and an end-entry suction boss (east).
    pr = .48 if hp else .42
    if hp:
        x_de = cx1 + .02
        # Stage section first, then the discharge casing at x 5.5, then a larger booster/suction casing.
        bearing_housing(s, x_de, x_de + .2, AY, AZ, .18, EGREEN)
        cylinder(s, 'mechanical seal housing', (x_de + .2, AY, AZ), (4.4, AY, AZ), .24, EGREEN, 20)
        n = 5
        for k in range(n):
            a = 4.4 + (5.2 - 4.4) * k / n
            b = 4.4 + (5.2 - 4.4) * (k + 1) / n
            cylinder(s, 'stage casing ring', (a, AY, AZ), (b, AY, AZ), pr * (.92 if k % 2 else .9), EGREEN, 24)
            sleeve(s, 'stage joint', (b - .012, AY, AZ), (b + .012, AY, AZ), pr * .95, pr * .8, BOLTM, 18)
        cylinder(s, 'discharge casing', (5.2, AY, AZ), (5.8, AY, AZ), pr + .02, EGREEN, 28)
        cylinder(s, 'booster suction casing', (5.8, AY, AZ), (6.32, AY, AZ), pr + .05, EGREEN, 28)
        for x in (4.4, 5.2, 5.8, 6.32):
            sleeve(s, 'casing flange', (x - .02, AY, AZ), (x + .02, AY, AZ), pr + .08, pr * .7, EGREEN, 20)
            bolt_circle(s, (x - .025, AY, AZ), (1, 0, 0), pr + .05, 12, .05, .02)
        for i in range(6):
            t = i * math.tau / 6 + math.pi / 6
            q = (AY + (pr + .07) * math.sin(t), AZ + (pr + .07) * math.cos(t))
            cylinder(s, 'tie rod', (4.34, q[0], q[1]), (5.24, q[0], q[1]), .022, BOLTM, 6)
        for x in (4.8, 6.06):
            centreline_feet(s, x, AY, AZ, pr + .03, TOP, EGREEN, .22)
        bearing_housing(s, 6.32, 6.52, AY, AZ, .135, EGREEN)
        cylinder(s, 'bearing end cap', (6.52, AY, AZ), (6.58, AY, AZ), .1, STEEL, 12)
        dis_x, suc_x0, suc_x1 = 5.5, 5.86, 6.30
        stage_mid = 4.8
    else:
        bearing_housing(s, cx1 + .02, cx1 + .22, AY, AZ, .17, EGREEN)
        cylinder(s, 'mechanical seal housing', (cx1 + .22, AY, AZ), (3.24, AY, AZ), .22, EGREEN, 20)
        cylinder(s, 'discharge casing', (3.24, AY, AZ), (3.76, AY, AZ), pr + .02, EGREEN, 28)
        n = 4
        for k in range(n):
            a = 3.76 + (4.24 - 3.76) * k / n
            b = 3.76 + (4.24 - 3.76) * (k + 1) / n
            cylinder(s, 'stage casing ring', (a, AY, AZ), (b, AY, AZ), pr * (.92 if k % 2 else .9), EGREEN, 24)
            sleeve(s, 'stage joint', (b - .012, AY, AZ), (b + .012, AY, AZ), pr * .95, pr * .8, BOLTM, 18)
        cylinder(s, 'suction casing', (4.24, AY, AZ), (4.58, AY, AZ), pr + .02, EGREEN, 28)
        for x in (3.24, 3.76, 4.24, 4.58):
            sleeve(s, 'casing flange', (x - .02, AY, AZ), (x + .02, AY, AZ), pr + .07, pr * .7, EGREEN, 20)
            bolt_circle(s, (x - .025, AY, AZ), (1, 0, 0), pr + .045, 10, .05, .02)
        for i in range(6):
            t = i * math.tau / 6 + math.pi / 6
            q = (AY + (pr + .07) * math.sin(t), AZ + (pr + .07) * math.cos(t))
            cylinder(s, 'tie rod', (3.70, q[0], q[1]), (4.30, q[0], q[1]), .02, BOLTM, 6)
        for x in (3.5, 4.41):
            centreline_feet(s, x, AY, AZ, pr + .03, TOP, EGREEN, .2)
        bearing_housing(s, 4.58, 4.76, AY, AZ, .13, EGREEN)
        cylinder(s, 'bearing end cap', (4.76, AY, AZ), (4.82, AY, AZ), .09, STEEL, 12)
        dis_x, suc_x0, suc_x1 = 3.5, 4.28, 4.56
        stage_mid = 4.0
    plate(s, 'pump nameplate', (stage_mid, AY, AZ - pr * .92 - .02), (0, 0, -1), (0, 1, 0), .3 if hp else .26, .15 if hp else .13, PLATE['eccspump'])
    # Discharge: tangential nozzle, orifice flange pair, gauge branch, minimum-flow bypass.
    terry_port(s, 'WATER_DISCHARGE', ports[3][1], 'south', [(dis_x, 1.5, AZ)], WPIPE)
    for z in (3.62, 3.68):
        flange_ring(s, 'flow orifice flange', (dis_x, 1.5, z), (0, 0, 1), .33, .2, .05, STEEL, 8, .29)
    pipe(s, 'discharge gauge tap', [(dis_x, 1.72, 3.3), (dis_x, 2.0, 3.3)], .02, STEEL, 8)
    gauge(s, (dis_x + .1, 2.06, 3.3), (1, 0, 0), .07)
    pipe(s, 'gauge stem', [(dis_x, 2.0, 3.3), (dis_x + .05, 2.06, 3.3)], .015, STEEL, 6)
    pipe(s, 'minimum flow bypass', [(dis_x, 1.28, 4.05), (dis_x, TOP + .14, 4.05), (dis_x + .55 if not hp else dis_x + .6, TOP + .14, 4.05)], .05, STEEL, 10)
    cylinder(s, 'minimum flow valve', (dis_x, .62, 3.92), (dis_x, .62, 4.18), .09, VALVE, 14)
    box(s, 'minimum flow valve operator', (dis_x - .08, .7, 3.97), (dis_x + .08, .86, 4.13), ACT)
    # Suction: boss on the suction casing end face, nozzle at the old flange height.
    sb_hi = 1.76
    box(s, 'suction inlet boss', (suc_x0, AY + .1, AZ - .27), (suc_x1, sb_hi, AZ + .27), EGREEN)
    terry_port(s, 'WATER_SUCTION', ports[2][1], 'east', [(suc_x0 + .1, 1.5, AZ)], WPIPE)
    gauge(s, (W - .55, 1.5, AZ - .33), (0, 0, -1), .065, stem=(W - .55, 1.5, AZ - .23))
    if hp:
        hpci_auxiliaries(s, AY, AZ, TOP, TX, HALF, R)
    else:
        rcic_auxiliaries(s, AY, AZ, TOP, TX, HALF, R)
    meta = {'size': size, 'controller': [0, 0, 0], 'ports': [{'role': r.upper(), 'cell': c, 'face': f} for r, c, f in ports]}
    export(s, ROOT / 'art/models/terry' / (id + '.json'), meta)
    return s


def cover_mat_for(hp):
    return TURBD if hp else TERRY


def local_panel(s, x0, x1, kind, top):
    y0, y1, z0, z1 = 2.05, 2.88, .1, .38
    box(s, 'local control panel', (x0, y0, z0), (x1, y1, z1), PANEL)
    box(s, 'panel rain hood', (x0 - .03, y1, z0 - .05), (x1 + .03, y1 + .04, z1 + .02), PANEL)
    plate(s, 'local panel face', ((x0 + x1) / 2, (y0 + y1) / 2, z0 - .002), (0, 0, -1), (0, 1, 0), x1 - x0 - .08, y1 - y0 - .08, PANEL_T[kind], PANEL, .004)
    for x in (x0 + .05, x1 - .05):
        box(s, 'panel support leg', (x - .03, top - .03, .2), (x + .03, y0, .28), PANEL)
    box(s, 'gauge board', (x0 + .02, 1.5, .14), (x1 - .02, 1.92, .2), PANEL)
    for k in range(3):
        gauge(s, (x0 + (x1 - x0) * (k + .5) / 3, 1.71, .136), (0, 0, -1), .07)
    pipe(s, 'panel cable conduit', [((x0 + x1) / 2, y0, .3), ((x0 + x1) / 2, top + .02, .3)], .025, STEEL, 8)


def rcic_auxiliaries(s, AY, AZ, TOP, TX, HALF, R):
    # Lube oil cooler on the south side, cooled from pump discharge, with oil lines to both bearings.
    cylinder(s, 'lube oil cooler shell', (.48, .74, 3.95), (2.05, .74, 3.95), .15, STEEL, 18)
    for x in (.48, 2.05):
        flange_ring(s, 'cooler channel cover', (x, .74, 3.95), (1, 0, 0), .19, .0, .05, STEEL, 8, .16)
    for x in (.75, 1.8):
        box(s, 'cooler saddle', (x - .05, TOP - .03, 3.82), (x + .05, .64, 4.08), SKIDG)
    pipe(s, 'cooling water supply', [(2.05, .74, 3.95), (2.6, .74, 3.95), (2.6, 1.5, 3.95), (3.28, 1.5, 3.95)], .035, STEEL, 10)
    pipe(s, 'oil to governor end bearing', [(.9, .87, 3.95), (.9, .87, 2.85), (.9, 1.0, 2.62)], .022, OIL, 8)
    pipe(s, 'oil to pump end bearing', [(1.92, .87, 3.95), (1.92, .87, 2.85), (1.92, 1.0, 2.62)], .022, OIL, 8)
    cylinder(s, 'oil filter', (2.32, TOP, 3.55), (2.32, .78, 3.55), .08, STEEL, 14)
    cylinder(s, 'oil filter cap', (2.32, .78, 3.55), (2.32, .82, 3.55), .09, BOLTM, 14)
    # Barometric condenser with vacuum pump and condensate pump (north-east).
    bx, bz = 3.95, .95
    cylinder(s, 'barometric condenser shell', (bx, .62, bz), (bx, 1.72, bz), .31, EGREEN, 24)
    ellipsoid(s, 'condenser top head', (bx, 1.72, bz), .31, .13, .31, EGREEN, 24, 4, 0, 90)
    ellipsoid(s, 'condenser bottom head', (bx, .62, bz), .31, .13, .31, EGREEN, 24, 4, -90, 0)
    for k in range(3):
        t = k * math.tau / 3 + .5
        lx, lz = bx + .24 * math.cos(t), bz + .24 * math.sin(t)
        box(s, 'condenser leg', (lx - .03, TOP - .03, lz - .03), (lx + .03, .58, lz + .03), SKIDG)
    gauge(s, (bx, 1.3, bz - .36), (0, 0, -1), .06, stem=(bx, 1.3, bz - .3))
    cylinder(s, 'condenser level glass', (bx + .33, .8, bz), (bx + .33, 1.5, bz), .025, STEEL, 8)
    motor(s, (3.05, .56, .55), (2.55, .56, .55), .15, EGREEN, TOP)
    cylinder(s, 'vacuum pump', (3.05, .54, .55), (3.32, .54, .55), .13, VALVE, 16)
    pipe(s, 'vacuum pump suction', [(3.32, .54, .55), (3.55, .54, .55), (3.68, .9, .85)], .04, STEEL, 10)
    motor(s, (2.95, .5, 1.25), (2.55, .5, 1.25), .12, EGREEN, TOP)
    cylinder(s, 'condensate pump', (2.95, .48, 1.25), (3.16, .48, 1.25), .1, VALVE, 14)
    pipe(s, 'condensate pump suction', [(3.16, .48, 1.25), (3.6, .48, 1.25), (3.8, .62, 1.1)], .035, STEEL, 10)
    pipe(s, 'gland leak-off to condenser', [(TX + HALF + .2, AY + .05, AZ - .12), (TX + HALF + .2, .46, AZ - .12),
                                            (TX + HALF + .2, .46, 1.55), (3.7, .46, 1.55), (3.75, .7, 1.2)], .035, STEEL, 10)
    pipe(s, 'vacuum pump discharge to exhaust', [(2.55, .56, .55), (2.2, .56, .55), (2.2, 1.0, .55), (1.6, 1.35, .6)], .03, STEEL, 8)
    local_panel(s, 3.08, 3.92, 'rcic', TOP)


def hpci_auxiliaries(s, AY, AZ, TOP, TX, HALF, R):
    # Gland seal condenser and gland exhauster (south-west), fed from both turbine glands.
    cylinder(s, 'gland seal condenser shell', (.45, .72, 4.15), (1.45, .72, 4.15), .22, EGREEN, 20)
    ellipsoid(s, 'gland condenser head', (.45, .72, 4.15), .1, .22, .22, EGREEN, 20, 4, -90, 90)
    flange_ring(s, 'gland condenser channel flange', (1.47, .72, 4.15), (1, 0, 0), .27, .0, .05, EGREEN, 10, .24)
    cylinder(s, 'gland condenser channel', (1.5, .72, 4.15), (1.66, .72, 4.15), .2, EGREEN, 20)
    for x in (.7, 1.3):
        box(s, 'condenser saddle', (x - .05, TOP - .03, 3.98), (x + .05, .55, 4.32), SKIDG)
    cylinder(s, 'gland exhauster blower', (1.95, .78, 4.0), (1.95, .78, 4.3), .2, EGREEN, 20)
    motor(s, (2.15, .78, 4.15), (2.6, .78, 4.15), .14, EGREEN, TOP)
    pipe(s, 'blower inlet', [(1.66, .72, 4.15), (1.78, .72, 4.15)], .07, STEEL, 12)
    pipe(s, 'blower vent', [(1.95, .98, 4.15), (1.95, 1.6, 4.15), (1.95, 1.6, 4.7)], .05, STEEL, 10)
    for x in (TX - HALF - .2, TX + HALF + .2):
        pipe(s, 'gland seal leak-off', [(x, AY - .05, AZ + .15), (x, .5, AZ + .15), (x, .5, 3.65), (1.0, .5, 3.65), (1.0, .6, 3.98)], .035, STEEL, 10)
    # Lube oil console (north-east): reservoir, cooler, filters, DC auxiliary oil pump, risers to bearings.
    box(s, 'lube oil reservoir', (3.55, TOP - .03, .38), (5.35, .86, 1.28), TURBD)
    box(s, 'reservoir cover', (3.53, .86, .36), (5.37, .89, 1.3), TURBD)
    cylinder(s, 'lube oil cooler shell', (3.75, 1.06, .88), (5.05, 1.06, .88), .15, STEEL, 18)
    for x in (3.75, 5.05):
        flange_ring(s, 'cooler channel cover', (x, 1.06, .88), (1, 0, 0), .19, .0, .05, STEEL, 8, .16)
    for x in (4.0, 4.8):
        box(s, 'cooler saddle', (x - .05, .89, .78), (x + .05, .96, .98), TURBD)
    motor(s, (4.75, 1.02, .52), (5.25, 1.02, .52), .13, TURBD, .89)
    cylinder(s, 'auxiliary oil pump', (4.55, 1.0, .52), (4.75, 1.0, .52), .09, VALVE, 12)
    for x in (3.75, 3.95):
        cylinder(s, 'duplex oil filter can', (x, .89, .5), (x, 1.24, .5), .07, STEEL, 14)
        cylinder(s, 'filter cap', (x, 1.24, .5), (x, 1.28, .5), .08, BOLTM, 14)
    obox(s, 'filter transfer handle', (3.75, 1.33, .5), (3.95, 1.33, .5), .02, .02, (0, 1, 0), HAND)
    gauge(s, (4.3, .66, .375), (0, 0, -1), .055)
    pipe(s, 'oil supply header', [(3.6, .45, .78), (1.62, .45, .78)], .03, OIL, 8)
    for x in (1.62, 3.07):
        pipe(s, 'bearing oil riser', [(x, .45, .78), (x, .45, 2.2), (x, AY - .12, 2.36)], .022, OIL, 8)
    pipe(s, 'pump bearing oil', [(4.17, .45, 1.28), (4.17, .45, 2.2), (4.17, AY - .12, 2.36)], .022, OIL, 8)
    pipe(s, 'pump outboard oil', [(5.35, .45, .8), (6.42, .45, .8), (6.42, .45, 2.2), (6.42, AY - .1, 2.4)], .022, OIL, 8)
    # Exhaust drain pot under the exhaust line.
    cylinder(s, 'exhaust drain pot', (2.5, .5, 1.12), (2.5, 1.2, 1.12), .1, STEEL, 14)
    pipe(s, 'drain pot drain', [(2.5, .5, 1.12), (2.5, TOP + .06, 1.12), (2.9, TOP + .06, 1.12)], .025, STEEL, 8)
    # Valve access platform over the gland condenser, with handrails and a ladder.
    PY = 2.45
    box(s, 'platform frame', (.18, PY - .1, 3.42), (1.95, PY, 3.48), STEEL)
    box(s, 'platform frame', (.18, PY - .1, 4.76), (1.95, PY, 4.82), STEEL)
    box(s, 'platform frame', (.18, PY - .1, 3.48), (.24, PY, 4.76), STEEL)
    box(s, 'platform frame', (1.89, PY - .1, 3.48), (1.95, PY, 4.76), STEEL)
    for i in range(20):
        x = .3 + i * .08
        box(s, 'platform grating bar', (x - .012, PY - .06, 3.48), (x + .012, PY - .005, 4.76), STEEL)
    for z in (3.8, 4.1, 4.4):
        box(s, 'grating cross bar', (.24, PY - .05, z - .008), (1.89, PY - .02, z + .008), STEEL)
    for x, z in ((.21, 3.45), (.21, 4.79), (1.92, 4.79), (1.92, 3.45)):
        box(s, 'platform post', (x - .03, TOP - .03, z - .03), (x + .03, PY - .1, z + .03), STEEL)
    for x, z in ((.21, 4.79), (1.92, 4.79), (.21, 3.45)):
        cylinder(s, 'handrail upright', (x, PY, z), (x, PY + 1.05, z), .022, STEEL, 8)
    for y in (PY + .55, PY + 1.05):
        pipe(s, 'handrail', [(1.92, y, 4.79), (.21, y, 4.79), (.21, y, 3.45)], .022, STEEL, 8)
    box(s, 'toe plate', (.18, PY, 4.79), (1.95, PY + .1, 4.82), STEEL)
    box(s, 'toe plate', (.18, PY, 3.45), (.21, PY + .1, 4.82), STEEL)
    for x in (1.95, 2.35):
        box(s, 'ladder stile', (x - .02, TOP - .03, 4.86), (x + .02, PY + .9, 4.9), STEEL)
    for i in range(8):
        y = TOP + .25 + i * .28
        cylinder(s, 'ladder rung', (1.95, y, 4.88), (2.35, y, 4.88), .016, STEEL, 6)
    local_panel(s, 4.08, 4.92, 'hpci', TOP)


if __name__ == '__main__':
    tfp = build_tfp()
    rcic = build_terry('rcic_twl')
    hpci = build_terry('hpci_turbine')
    for sc in SCENES:
        sc['description'] = 'Original reference-based model; one Blender unit is one Minecraft block. Ports unchanged.'
    # Keep only the three machine scenes, then save the whole file (partial library writes crash headless 5.2).
    for sc in list(bpy.data.scenes):
        if sc not in SCENES:
            bpy.data.scenes.remove(sc)
    bpy.ops.wm.save_as_mainfile(filepath=str(HERE / 'turbine_pumps.blend'), compress=True)
    print('TDP BUILT', [(sc.name, len(sc.objects)) for sc in SCENES])
