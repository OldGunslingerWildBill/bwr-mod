"""Motor-driven pump remodel: LPCS, RHR/LPCI, HPCS, motor feedwater and SLC (pump and boron tank).

Headless: blender.exe --background --factory-startup --python art/models/modern/build_motor_pumps.py [-- --preview DIR]
or, with the bpy module from PyPI and no Blender binary: python3 art/models/modern/build_motor_pumps.py -- [--preview DIR]
(previews then render with Cycles on the CPU; --cycles forces that under Blender too).
Writes the source meshes art/models/modern/<id>.json (alpha/ for SLC) read by tools-export-modern.py /
tools-export-alpha.py, and saves the editable scenes to art/models/modern/motor_pumps.blend. It never opens or edits
modern_pumps_and_pipes.blend (the turbine feed pump and the round pipe fittings stay there).

Minecraft coordinates throughout: X east, Y up, Z south, one unit = one block, machine facing north.
Port flanges keep the exact position and shape of the earlier modern exports (flange() from build_models.py), so placed
pipes meet them where they did. The compact single-block models that old saves use are frozen: each source JSON lists
that model's original materials first (legacy_compact_materials.json), so materials.mtl keeps mat_0.. as compact.obj
expects, and 'frozen_compact' tells the exporters not to regenerate compact.*.

Reference forms (see MOTOR-PUMP-REMODEL.md, REFERENCES): BWR/5 RHR, LPCS and HPCS pumps are vertical can-type pumps
with a mechanical seal and cyclone separator and an open 4160 V vertical motor on the discharge head (HPCS 3000 hp, LPCS
about 1500 hp, RHR about 800 hp); the can hangs below the floor. Each has a minimum-flow line; HPCS and LPCS have a
water-leg pump. Motor feedwater: single-stage double-suction reactor feed pump, speed-increasing gear, large horizontal
motor. SLC: horizontal triplex plunger pump driven through a triple-reduction gear reducer, heat-traced suction.
"""
import ast
import bpy
import bmesh
import json
import math
import sys
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/modern/build_models.py'
exec(compile(helper.read_text(encoding='utf-8').split('def build_pump(id):')[0], str(helper), 'exec'))
MODERN = ROOT / 'art/models/modern'
ALPHA = ROOT / 'art/models/alpha'
TEXTURE = MODERN / 'textures/motor_pump_details.png'
_tex_src = (MODERN / 'make_motor_pump_texture.py').read_text(encoding='utf-8')
REGIONS = ast.literal_eval(_tex_src.split('REGIONS = ', 1)[1].split('\n}\n', 1)[0] + '\n}')
LEGACY = json.loads((MODERN / 'legacy_compact_materials.json').read_text(encoding='utf-8'))
TAU = math.tau
CYCLES_PREVIEW = '--cycles' in sys.argv or bool(getattr(bpy.app, 'module', False)) or not bpy.app.binary_path


def new_scene(name):
    # Background Blender has no window: create the scene without switching to it.
    scene = bpy.data.scenes.new(name)
    SCENES.append(scene)
    return scene


# ---------------------------------------------------------------- materials
def lin(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    return tuple(x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in c)


def paint(name, hexc, metal=0.0, rough=.55):
    m = bpy.data.materials.new('Motor pump | ' + name)
    rgb = lin(hexc)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value = (*rgb, 1)
    bsdf.inputs['Metallic'].default_value = metal
    bsdf.inputs['Roughness'].default_value = rough
    return m


IMAGE = bpy.data.images.load(str(TEXTURE))
IMAGE.name = 'motor_pump_details'


def textured(name):
    m = bpy.data.materials.new('Motor pump | ' + name)
    m.use_nodes = True
    nt = m.node_tree
    bsdf = next(n for n in nt.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    node = nt.nodes.new('ShaderNodeTexImage')
    node.image = IMAGE
    node.interpolation = 'Closest'
    nt.links.new(node.outputs['Color'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = .5
    m['texture'] = 'bwr:block/modern/motor_pump_details'
    return m


DETAIL = textured('detail sheet')
GALV = paint('galvanized conduit', '#a9b1b5', .5, .45)
FLEX = paint('liquidtight flex conduit', '#30353a', .0, .6)
JBOX = paint('junction box grey', '#7c858c', .1, .5)
CAST = paint('cast discharge head', '#5d666d', .2, .55)
BASE = paint('base plate', '#3b4147', .3, .55)
GROUT = paint('grout pad', '#8d8c86', .0, .9)
GUARD = paint('guard yellow', '#d8a11d', .0, .5)
NUT = paint('zinc fasteners', '#8e979c', .7, .35)
SS = paint('stainless', '#bcc3c7', .8, .3)
WHEEL = paint('handwheel red', '#a3271f', .1, .45)
BLACK = paint('black enamel', '#24282c', .1, .5)
BRASS = paint('gauge brass', '#b08d3e', .8, .35)
OIL = paint('oil line copper', '#b06f45', .8, .35)
POWER = paint('power frame cast iron', '#545c62', .25, .55)
FLUID = paint('fluid end stainless', '#9aa3a8', .7, .35)
COLORS = {'lpcs': '#567d4f', 'rhr': '#2e6b75', 'hpcs': '#8c3b2c', 'rfw': '#3a5f87', 'slc': '#4b7055'}
MOTOR = {k: paint(k + ' motor enamel', v, .1, .5) for k, v in COLORS.items()}


# ---------------------------------------------------------------- primitives (Minecraft coordinates)
def V(*p):
    return Vector(p[0] if len(p) == 1 else p)


def box(s, name, low, high, mat):
    x, y, z = low
    X, Y, Z = high
    return mesh_object(s, name, [(x, y, z), (X, y, z), (X, Y, z), (x, Y, z), (x, y, Z), (X, y, Z), (X, Y, Z), (x, Y, Z)],
                       [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5)], mat)


def obox(s, name, c, u, v, w, mat):
    c, u, v, w = V(c), V(u), V(v), V(w)
    vs = [tuple(c + sx * u + sy * v + sz * w) for sz in (-1, 1) for sy in (-1, 1) for sx in (-1, 1)]
    return mesh_object(s, name, vs, [(0, 2, 3, 1), (4, 5, 7, 6), (0, 1, 5, 4), (2, 6, 7, 3), (0, 4, 6, 2), (1, 3, 7, 5)], mat)


def lathe(s, name, a, axis, profile, mat, n=24, cap=True):
    """Surface of revolution; profile is [(distance along axis, radius)]. Closed with caps where radius > 0."""
    a = V(a)
    d, u, w = basis(axis)
    vs, fs = [], []
    for t, r in profile:
        vs += [tuple(a + d * t + r * (math.cos(i * TAU / n) * u + math.sin(i * TAU / n) * w)) for i in range(n)]
    rows = len(profile)
    fs += [(j * n + i, j * n + (i + 1) % n, (j + 1) * n + (i + 1) % n, (j + 1) * n + i) for j in range(rows - 1) for i in range(n)]
    if cap and profile[0][1] > 0:
        fs.append(tuple(range(n - 1, -1, -1)))
    if cap and profile[-1][1] > 0:
        fs.append(tuple(range((rows - 1) * n, rows * n)))
    return mesh_object(s, name, vs, fs, mat)


def cyl(s, name, a, b, r, mat, n=24):
    a, b = V(a), V(b)
    return lathe(s, name, a, b - a, [(0, r), ((b - a).length, r)], mat, n)


def cone(s, name, a, b, r0, r1, mat, n=24):
    a, b = V(a), V(b)
    return lathe(s, name, a, b - a, [(0, r0), ((b - a).length, r1)], mat, n)


def band(s, name, a, b, outer, inner, mat, n=32):
    """Annular band whose bore face is hidden against a body: outer face plus both end rings."""
    a, b = V(a), V(b)
    h = (b - a).length
    return lathe(s, name, a, b - a, [(0, inner), (0, outer), (h, outer), (h, inner)], mat, n, cap=False)


def tube(s, name, a, b, outer, inner, mat, n=24):
    return sleeve(s, name, tuple(a), tuple(b), outer, inner, mat, n)


def sweep(s, name, path, r, mat, n=12, caps=True):
    pts = [V(p) for p in path]
    vs, u = [], None
    for j, p in enumerate(pts):
        d = (pts[min(len(pts) - 1, j + 1)] - pts[max(0, j - 1)]).normalized()
        u = basis(d)[1] if u is None else (u - d * u.dot(d)).normalized()
        v = d.cross(u).normalized()
        vs.extend(tuple(p + r * (math.cos(i * TAU / n) * u + math.sin(i * TAU / n) * v)) for i in range(n))
    fs = [(j * n + i, j * n + (i + 1) % n, (j + 1) * n + (i + 1) % n, (j + 1) * n + i) for j in range(len(pts) - 1) for i in range(n)]
    if caps:
        fs += [tuple(range(n - 1, -1, -1)), tuple(range((len(pts) - 1) * n, len(pts) * n))]
    return mesh_object(s, name, vs, fs, mat)


def fillet(points, bend, steps=8):
    """Polyline with every interior corner replaced by a circular arc of radius `bend`."""
    pts = [V(p) for p in points]
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        p, d0, d1 = pts[i], (pts[i] - pts[i - 1]).normalized(), (pts[i + 1] - pts[i]).normalized()
        cos = max(-1, min(1, d0.dot(d1)))
        theta = math.acos(cos)
        if theta < 1e-4:
            out.append(p)
            continue
        t = bend * math.tan(theta / 2)
        nrm = (d1 - d0 * cos).normalized()
        c = p - d0 * t + nrm * bend
        for k in range(steps + 1):
            phi = theta * k / steps
            out.append(c + bend * (-nrm * math.cos(phi) + d0 * math.sin(phi)))
    out.append(pts[-1])
    return out


def pipe(s, name, points, r, mat=None, bend=.3, n=16, steps=8):
    return sweep(s, name, fillet(points, bend, steps), r, mat or BODY, n)


def torus(s, name, c, normal, R, r, mat, nu=10, nv=5):
    c = V(c)
    d, u, w = basis(normal)
    path = [c + R * (math.cos(i * TAU / nu) * u + math.sin(i * TAU / nu) * w) for i in range(nu + 1)]
    return sweep(s, name, path, r, mat, nv, caps=False)


def nut(s, name, p, axis, r=.026, h=.05):
    p, d = V(p), V(axis).normalized()
    return bolt(s, name, p, p + d * h, r)


def bolt_ring(s, name, c, axis, radius, count, r=.024, h=.05, mat_offset=0.0, phase=.5):
    c = V(c)
    d, u, w = basis(axis)
    for i in range(count):
        a = (i + phase) * TAU / count + mat_offset
        q = c + radius * (math.cos(a) * u + math.sin(a) * w)
        nut(s, name, q, d, r, h)


def joint(s, name, p, axis, r=.25, fr=None, count=8):
    """Bolted flange pair in a pipe run (two raised-face flanges, gasket, through studs and nuts)."""
    p, d = V(p), V(axis).normalized()
    fr = fr or r * 1.55
    cyl(s, name + ' flange A', p - d * .065, p - d * .008, fr, STEEL, 24)
    cyl(s, name + ' gasket', p - d * .008, p + d * .008, fr * .93, DARK, 24)
    cyl(s, name + ' flange B', p + d * .008, p + d * .065, fr, STEEL, 24)
    _, u, w = basis(d)
    for i in range(count):
        a = (i + .5) * TAU / count
        q = p + fr * .8 * (math.cos(a) * u + math.sin(a) * w)
        nut(s, name + ' nut', q - d * .065, -d, .027, .035)
        nut(s, name + ' nut', q + d * .065, d, .027, .035)


def quad(s, name, corners, uvs, mat):
    """One textured quad, wound counter-clockwise as seen from its front (no normal recalculation)."""
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([xyz(p) for p in corners], [], [(0, 1, 2, 3)])
    mesh.update()
    uv = mesh.uv_layers.new(name='Material UV')
    for loop, coord in zip(uv.data, uvs):
        loop.uv = coord
    o = bpy.data.objects.new(name, mesh)
    s.collection.objects.link(o)
    mesh.materials.append(mat)
    return o


def region_uv(key):
    x0, y0, x1, y1 = REGIONS[key]
    inset = .35
    u0, u1 = (x0 + inset) / 256, (x1 - inset) / 256
    vb, vt = 1 - (y1 - inset) / 256, 1 - (y0 + inset) / 256
    return [(u0, vb), (u1, vb), (u1, vt), (u0, vt)]


def decal(s, name, c, normal, up, w, h, key, back=None, depth=.012):
    """A plate: optional backing slab plus a textured face toward `normal`."""
    c, n, up = V(c), V(normal).normalized(), V(up).normalized()
    right = up.cross(n).normalized()
    if back is not None:
        obox(s, name + ' backing', c - n * depth / 2, right * (w / 2 + .01), up * (h / 2 + .01), n * depth / 2, back)
    f = c + n * .002
    corners = [f - right * w / 2 - up * h / 2, f + right * w / 2 - up * h / 2, f + right * w / 2 + up * h / 2, f - right * w / 2 + up * h / 2]
    return quad(s, name, corners, region_uv(key), DETAIL)


def port(s, role, cell, face, inner):
    """Round neck, bolted flange and service band at a block-face centre (identical to the earlier modern ports)."""
    axis = Vector(DIRECTIONS[face])
    endpoint = Vector(cell) + Vector((.5, .5, .5)) + axis * .5
    k = next(i for i in range(3) if axis[i])
    start = endpoint.copy()
    start[k] = inner
    sleeve(s, role + ' neck', start, endpoint - axis * .08, .25, .205, BODY)
    flange(s, role, endpoint, axis)
    band = BLUE if role == 'water_suction' else TEAL
    sleeve(s, role + ' service band', endpoint - axis * .30, endpoint - axis * .20, .257, .249, band)
    return endpoint


def gauge(s, name, p, stem_dir, face_dir, length=.22):
    """Instrument tap: root valve with red handwheel, siphon/stem and a pressure gauge."""
    p, d, f = V(p), V(stem_dir).normalized(), V(face_dir).normalized()
    cyl(s, name + ' tap boss', p - d * .02, p + d * .05, .035, STEEL, 10)
    cyl(s, name + ' stem', p, p + d * length, .014, SS, 8)
    vb = p + d * .08
    obox(s, name + ' root valve', vb, d * .035, basis(d)[1] * .03, basis(d)[2] * .03, BRASS)
    torus(s, name + ' handwheel', vb + basis(d)[1] * .06, basis(d)[1], .035, .007, WHEEL, 10, 4)
    g = p + d * (length + .07)
    cyl(s, name + ' case', g - f * .035, g + f * .02, .075, BLACK, 12)
    tube(s, name + ' bezel', g + f * .02, g + f * .03, .076, .066, SS, 12)
    up = d if abs(d.dot(f)) < .5 else basis(f)[1]
    decal(s, name + ' dial', g + f * .022, f, up, .118, .118, 'gauge')


def conduit(s, name, points, bend=.12):
    pipe(s, name, points, .032, GALV, bend, 8, 5)


# ---------------------------------------------------------------- assemblies
def vertical_motor(s, X, Z, y0, h, r, mat, jbox_angle, plate_angle, lug_count=3, cool_angle=-math.pi / 2 - .35, cool_floor=.03):
    """Open (weather-protected) vertical induction motor, as on the ECCS pumps: screened air intakes in the lower
    bracket, a smooth stator frame, a louvred air-discharge housing, the upper thrust-bearing oil reservoir with its
    sight glass and cooling-water tubes, lifting eyes and the 4160 V terminal box."""
    def at(a, rr, y):
        return V(X + rr * math.cos(a), y, Z + rr * math.sin(a))

    def radial(a):
        return V(math.cos(a), 0, math.sin(a))

    def tangent(a):
        return V(-math.sin(a), 0, math.cos(a))

    def near(a, *others):
        return min(abs((a - k + math.pi) % TAU - math.pi) for k in others)

    def panel(name, a0, a1, rr, ylo, yhi, key, k=4):
        # A textured panel wrapped round the frame in k flat strips, so it stays proud of the faceted cylinder.
        # Seen from outside, "right" is toward decreasing angle, so a1 is the left edge.
        (u0, vb), (u1, _), (_, vt), _ = region_uv(key)
        for i in range(k):
            al, ar = a1 - (a1 - a0) * i / k, a1 - (a1 - a0) * (i + 1) / k
            ul, ur = u0 + (u1 - u0) * i / k, u0 + (u1 - u0) * (i + 1) / k
            quad(s, name, [at(al, rr, ylo), at(ar, rr, ylo), at(ar, rr, yhi), at(al, rr, yhi)], [(ul, vb), (ur, vb), (ur, vt), (ul, vt)], DETAIL)

    up = V(0, 1, 0)
    c = lambda y: (X, y, Z)
    lathe(s, 'motor base flange', c(y0), up, [(0, r * 1.04), (.07, r * 1.04), (.07, r * .9)], mat, 28)
    bolt_ring(s, 'motor hold-down nut', c(y0 + .07), up, r * .98, 8, .03, .045)
    y1 = y0 + .07
    ya, yt = y1 + h * .16, y0 + h * .64
    # Lower end bracket: cooling air enters through screens all round.
    cyl(s, 'motor lower bracket', c(y1), c(ya), r * .86, mat, 28)
    panels = 8
    for i in range(panels):
        a0, a1 = (i + .1) * TAU / panels, (i + .9) * TAU / panels
        rr = r * .872
        panel('motor air intake screen', a0, a1, rr, y1 + .03, ya - .03, 'screen')
    cyl(s, 'motor stator frame', c(ya), c(yt), r * .93, mat, 32)
    band(s, 'motor frame lower ring', c(ya), c(ya + .06), r * 1.0, r * .84, mat, 28)
    ribs = 8
    for i in range(ribs):
        a = (i + .5) * TAU / ribs
        if near(a, jbox_angle, plate_angle, jbox_angle + .55) < .3:
            continue
        obox(s, 'motor frame rib', at(a, r * .955, (ya + yt) / 2), radial(a) * r * .035, tangent(a) * .022, up * ((yt - ya) / 2 - .06), mat)
    # Upper air housing: warm air leaves through louvres under the top cover.
    yh = yt + h * .14
    cyl(s, 'motor air housing', c(yt), c(yh), r * 1.0, mat, 32)
    panels = 12
    for i in range(panels):
        a0, a1 = (i + .12) * TAU / panels, (i + .88) * TAU / panels
        rr = r * 1.008
        panel('motor air opening', a0, a1, rr, yt + .05, yh - .05, 'louvre')
    band(s, 'motor hat rim', c(yh - .02), c(yh + .02), r * 1.03, r * .9, mat, 28)
    lathe(s, 'motor top cover', c(yh), up, [(0, r * .99), (h * .03, r * .9), (h * .06, r * .52)], mat, 28, cap=False)
    yc = yh + h * .06
    # Upper (thrust) bearing oil reservoir.
    cyl(s, 'upper bearing oil reservoir', c(yc - .01), c(yc + h * .1), r * .5, mat, 24)
    band(s, 'oil reservoir cover rim', c(yc + h * .085), c(yc + h * .1), r * .54, r * .46, mat, 24)
    cyl(s, 'motor thrust bearing vent', c(yc + h * .1), c(yc + h * .1 + .04), r * .1, BLACK, 12)
    sg = plate_angle + .9
    decal(s, 'bearing oil sight glass', at(sg, r * .5 + .012, yc + h * .045), radial(sg), up, .05, .1, 'sight_glass', back=SS)
    fill = at(sg + .7, r * .36, yc + h * .1)
    cyl(s, 'bearing oil fill cap', fill, fill + up * .045, .035, WHEEL, 10)
    # Bearing oil cooler: cooling-water supply and return down the frame to the floor.
    for da in (-.09, .09):
        a = cool_angle + da
        top = at(a, r * .5, yc + h * .03)
        run = [top, at(a, r * 1.09, yc + h * .03), at(a, r * 1.09, yt - .2), at(a, r * 1.09, cool_floor)]
        sweep(s, 'bearing cooling water tube', fillet(run, .06, 4), .017, SS, 6)
        cyl(s, 'cooling water union', at(a, r * 1.09, yt - .1), at(a, r * 1.09, yt - .04), .028, BRASS, 8)
    for i in range(lug_count):
        a = (i + .25) * TAU / lug_count
        base = at(a, r * .74, yh + h * .042)
        obox(s, 'motor lifting lug base', base, radial(a) * .06, tangent(a) * .02, up * .03, mat)
        torus(s, 'motor lifting eye', base + up * .075, tangent(a), .05, .016, mat, 10, 5)
    # Main (4160 V) terminal box with conduit to the floor.
    ym = (ya + yt) / 2
    jb = at(jbox_angle, r * .93 + .15, ym)
    hb = min(.26, (yt - ya) / 2 - .08)
    obox(s, 'motor terminal box', jb, radial(jbox_angle) * .15, tangent(jbox_angle) * .2, up * hb, JBOX)
    obox(s, 'motor terminal box lid', jb + radial(jbox_angle) * .155, radial(jbox_angle) * .01, tangent(jbox_angle) * .18, up * (hb - .02), JBOX)
    for sx in (-1, 1):
        for sy in (-1, 1):
            nut(s, 'terminal box screw', jb + radial(jbox_angle) * .165 + tangent(jbox_angle) * .16 * sx + up * (hb - .04) * sy, radial(jbox_angle), .012, .012)
    bottom = jb - up * hb
    cyl(s, 'terminal box hub', bottom, bottom - up * .05, .05, GALV, 10)
    flr = at(jbox_angle, r * .93 + .29, .0)
    conduit(s, 'motor power conduit', [bottom - up * .05, bottom - up * .25, (flr.x, bottom.y - .45, flr.z), (flr.x, .02, flr.z)])
    cyl(s, 'conduit floor seal', (flr.x, 0, flr.z), (flr.x, .05, flr.z), .06, GALV, 10)
    decal(s, 'conduit voltage tag', (bottom - up * .32 + radial(jbox_angle) * .035), radial(jbox_angle), up, .1, .02, 'conduit_tag')
    # Space heater / RTD box with flexible conduit.
    sb = at(jbox_angle + .55, r * .93 + .07, ya + .2)
    obox(s, 'motor auxiliary box', sb, radial(jbox_angle + .55) * .07, tangent(jbox_angle + .55) * .08, up * .08, JBOX)
    sweep(s, 'auxiliary flex conduit', fillet([sb - up * .08, sb - up * .17, jb + tangent(jbox_angle) * .2 - up * (hb - .06)], .05, 4), .02, FLEX, 6, True)
    pn = at(plate_angle, r * .93 + .004, ym + h * .05)
    decal(s, 'motor nameplate', pn, radial(plate_angle), up, .3, .15, 'motor_plate', back=SS)
    return yc + h * .1 + .04


def motor_stand(s, X, Z, y0, y1, r, mat, coupling=True):
    """Discharge-head motor stand: four posts, top ring, shaft, spacer coupling, mechanical seal with its cyclone
    separator, and bar guards."""
    up = V(0, 1, 0)
    c = lambda y: (X, y, Z)
    for i in range(4):
        a = (i + .5) * TAU / 4
        rad, tan = V(math.cos(a), 0, math.sin(a)), V(-math.sin(a), 0, math.cos(a))
        obox(s, 'motor stand post', V(X, (y0 + y1) / 2, Z) + rad * r * .88, rad * .06, tan * .085, up * (y1 - y0) / 2, mat)
    band(s, 'motor stand top ring', c(y1 - .07), c(y1), r * 1.0, r * .55, mat, 28)
    band(s, 'motor stand base ring', c(y0), c(y0 + .05), r * .98, r * .5, mat, 28)
    cyl(s, 'pump shaft', c(y0 - .05), c(y1), .05, STEEL, 12)
    hs = y0 + .05
    cyl(s, 'mechanical seal housing', c(hs), c(hs + .12), .17, CAST, 20)
    cyl(s, 'seal gland plate', c(hs + .12), c(hs + .16), .14, STEEL, 20)
    bolt_ring(s, 'gland nut', c(hs + .16), up, .115, 4, .016, .025)
    if coupling:
        span = y1 - hs - .16
        ya = hs + .16 + span * .25
        cyl(s, 'coupling pump hub', c(ya), c(ya + .08), .11, STEEL, 16)
        cyl(s, 'coupling spacer', c(ya + .08), c(ya + span * .4), .075, STEEL, 16)
        cyl(s, 'coupling motor hub', c(ya + span * .4), c(ya + span * .4 + .08), .11, STEEL, 16)
    # Expanded-metal style guard: frame and bars across each window, coupling visible behind.
    d = r * .76
    for i in range(4):
        a = i * TAU / 4
        rad, tan = V(math.cos(a), 0, math.sin(a)), V(-math.sin(a), 0, math.cos(a))
        cc = V(X, (y0 + y1) / 2, Z) + rad * d
        half_w, half_h = r * .58, (y1 - y0 - .14) / 2
        for sy in (-1, 1):
            obox(s, 'guard rail', cc + up * sy * half_h, rad * .01, tan * half_w, up * .012, GUARD)
        for k in range(-2, 3):
            obox(s, 'guard bar', cc + tan * k * half_w / 2.6, rad * .006, tan * .006, up * half_h, GUARD)
        for k in (-1, 0, 1):
            obox(s, 'guard bar', cc + up * k * half_h / 2, rad * .006, tan * half_w, up * .006, GUARD)
    # Seal flush: pump discharge water is cleaned in a cyclone separator on the stand; clean water goes to the
    # seal gland, the dirty underflow returns to the suction side.
    cx, cz = X + r * .98, Z - .2
    cy = hs + .2
    lathe(s, 'cyclone separator', (cx, cy - .2, cz), up, [(0, .016), (.12, .05), (.2, .05), (.215, .022)], SS, 10)
    obox(s, 'separator bracket', (cx - .04, cy - .03, cz + .06), (.045, 0, 0), (0, .012, 0), (0, 0, .03), mat)
    sweep(s, 'seal flush tube', fillet([(cx, cy + .015, cz), (cx, cy + .09, cz), (X + .1, cy + .09, Z - .1), (X + .1, hs + .08, Z - .1)], .04, 3), .012, SS, 6)
    sweep(s, 'separator inlet tube', fillet([(cx + .05, cy - .03, cz), (cx + .11, cy - .03, cz), (cx + .11, y0 - .16, cz), (cx - .1, y0 - .16, cz)], .04, 3), .012, SS, 6)
    sweep(s, 'separator underflow tube', fillet([(cx, cy - .2, cz), (cx, y0 - .3, cz), (cx - .16, y0 - .3, cz)], .04, 3), .012, SS, 6)


def sole_plate(s, X, Z, half, y=.07):
    box(s, 'grout pad', (X - half - .06, 0, Z - half - .06), (X + half + .06, .015, Z + half + .06), GROUT)
    box(s, 'pump sole plate', (X - half, .015, Z - half), (X + half, y, Z + half), BASE)
    e = half - .07
    for dx, dz in ((e, e), (e, -e), (-e, e), (-e, -e), (e, 0), (-e, 0), (0, e), (0, -e)):
        p = V(X + dx, y, Z + dz)
        cyl(s, 'anchor bolt', p, p + V(0, .1, 0), .018, NUT, 6)
        nut(s, 'anchor nut', p, (0, 1, 0), .04, .045)


def pipe_support(s, name, x, z, y_pipe, r, along='x'):
    """Floor stanchion with a saddle and U-bolt under a horizontal pipe."""
    top = y_pipe - r - .02
    box(s, name + ' base plate', (x - .12, 0, z - .12), (x + .12, .025, z + .12), BASE)
    cyl(s, name + ' stanchion', (x, .025, z), (x, top - .03, z), .045, BASE, 12)
    if along == 'x':
        box(s, name + ' saddle', (x - .06, top - .03, z - r * .85), (x + .06, top + .02, z + r * .85), BASE)
        sweep(s, name + ' U-bolt', [(x, top + .02 + (r + .02) * (1 - math.cos(t)), z + (r + .02) * math.sin(t)) for t in [math.pi * k / 10 - math.pi / 2 for k in range(11)]], .011, NUT, 6)
    else:
        box(s, name + ' saddle', (x - r * .85, top - .03, z - .06), (x + r * .85, top + .02, z + .06), BASE)
        sweep(s, name + ' U-bolt', [(x + (r + .02) * math.sin(t), top + .02 + (r + .02) * (1 - math.cos(t)), z) for t in [math.pi * k / 10 - math.pi / 2 for k in range(11)]], .011, NUT, 6)
    for k in (-1, 1):
        for sx in (-1, 1):
            nut(s, name + ' anchor', (x + sx * .085, .025, z + k * .085), (0, 1, 0), .018, .02)


def nozzle(s, name, p0, axis, length, r, fr, mat, studs=8):
    """Cast nozzle boss and flange on a casing, bolted to the mating pipe flange; returns the pipe-side face."""
    p0, d = V(p0), V(axis).normalized()
    cyl(s, name + ' boss', p0, p0 + d * length, r, mat, 24)
    end = p0 + d * length
    cyl(s, name + ' flange', end - d * .06, end, fr, mat, 24)
    cyl(s, name + ' gasket', end, end + d * .012, fr * .93, DARK, 24)
    cyl(s, name + ' pipe flange', end + d * .012, end + d * .07, fr, STEEL, 24)
    _, u, w = basis(d)
    for i in range(studs):
        a = (i + .5) * TAU / studs
        q = end + fr * .8 * (math.cos(a) * u + math.sin(a) * w)
        nut(s, name + ' nut', q - d * .06, -d, .027, .035)
        nut(s, name + ' nut', q + d * .07, d, .027, .035)
    return end + d * .07


def check_valve(s, name, x, y, z, r=.25):
    """Flanged swing check valve body with a bolted top cover and hinge-pin boss."""
    cyl(s, name + ' body', (x - .26, y, z), (x + .26, y, z), r * 1.25, CAST, 24)
    lathe(s, name + ' bonnet', (x, y, z), (0, 1, 0), [(r * .9, r * .95), (r * 1.45, r * .95), (r * 1.45, r * .7)], CAST, 20)
    cyl(s, name + ' cover', (x, y + r * 1.45, z), (x, y + r * 1.45 + .06, z), r * 1.05, CAST, 24)
    bolt_ring(s, name + ' cover nut', (x, y + r * 1.45 + .06, z), (0, 1, 0), r * .92, 8, .022, .035)
    cyl(s, name + ' hinge boss', (x - .04, y + r * .5, z + r * 1.1), (x - .04, y + r * .5, z + r * 1.45), .06, CAST, 12)
    obox(s, name + ' flow arrow', (x, y + r * .4, z + r * 1.25), (.12, 0, 0), (0, .02, 0), (0, 0, .006), GUARD)
    for sx in (-1, 1):
        joint(s, name + ' end flange', (x + sx * .32, y, z), (1, 0, 0), r)


def seal_cooler(s, x0, x1, y, z, link_from):
    """Small shell-and-tube seal water cooler on saddles, with tubing to the pump seal."""
    cyl(s, 'seal cooler shell', (x0, y, z), (x1, y, z), .12, CAST, 20)
    for x in (x0, x1):
        cyl(s, 'seal cooler head', (x - .03 if x == x0 else x, y, z), (x if x == x0 else x + .03, y, z), .14, CAST, 20)
    for x in (x0 + .15, x1 - .15):
        box(s, 'seal cooler saddle', (x - .05, .0, z - .1), (x + .05, y - .08, z + .1), BASE)
    for x in (x0 + .25, x1 - .25):
        cyl(s, 'cooling water nozzle', (x, y, z), (x, y + .2, z), .03, STEEL, 8)
        cyl(s, 'cooling water union', (x, y + .2, z), (x, y + .24, z), .045, BRASS, 8)
    sweep(s, 'seal water tube', fillet([(x0 - .03, y, z), (x0 - .15, y, z)] + link_from, .07, 4), .014, SS, 6)


def instrument_rack(s, x0, x1, z0, z1, top=1.6):
    """Local instrument rack: strut frame, two pressure transmitters with impulse tubing and a local control station."""
    for x in (x0 + .05, x1 - .05):
        box(s, 'rack upright', (x - .025, 0, z0), (x + .025, top, z0 + .05), BASE)
        box(s, 'rack foot', (x - .08, 0, z0 - .05), (x + .08, .02, z0 + .15), BASE)
    for y in (.55, 1.1, top - .05):
        box(s, 'rack rail', (x0, y - .025, z0), (x1, y + .025, z0 + .05), BASE)
    for i, x in enumerate((x0 + .25, x1 - .25)):
        c = V(x, .82, z0 + .14)
        cyl(s, 'transmitter body', c - V(0, .1, 0), c + V(0, .02, 0), .07, JBOX, 16)
        cyl(s, 'transmitter head', c + V(0, .02, 0), c + V(0, .16, 0), .065, BLUE, 16)
        box(s, 'transmitter flange', (x - .1, .66, z0 + .1), (x + .1, .72, z0 + .19), STEEL)
        sweep(s, 'impulse tube', fillet([(x - .06, .66, z0 + .14), (x - .06, .3, z0 + .14), (x - .06, .3, z0 - .25), (x - .06, .02, z0 - .25)], .05, 3), .009, SS, 6)
        sweep(s, 'transmitter conduit', fillet([(x, .98, z0 + .14), (x, 1.1, z0 + .14), (x0 + .1, 1.1, z0 + .14)], .04, 3), .014, FLEX, 6)
    cs = V((x0 + x1) / 2, 1.33, z0 + .12)
    box(s, 'local control station', cs - V(.13, .17, .07), cs + V(.13, .17, .07), JBOX)
    for k, m in ((-1, GREEN_LAMP), (1, RED_LAMP)):
        cyl(s, 'pushbutton', cs + V(k * .06, .03, .07), cs + V(k * .06, .03, .1), .028, m, 12)
    decal(s, 'station label', cs + V(0, -.09, .071), (0, 0, 1), (0, 1, 0), .18, .03, 'conduit_tag')


def min_flow_line(s, bx, y, Z=1.5, zv=.45):
    """Minimum-flow bypass off the pump discharge: tee, restricting orifice, motor-operated valve, then down through
    the floor toward the suppression pool."""
    cyl(s, 'minimum flow tee', (bx, y, Z), (bx, y, Z - .32), .11, BODY, 16)
    zo = Z - .55
    for k in (-1, 1):
        cyl(s, 'restricting orifice flange', (bx, y, zo + k * .008), (bx, y, zo + k * .05), .16, STEEL, 12)
    cyl(s, 'restricting orifice plate', (bx, y, zo - .008), (bx, y, zo + .008), .15, SS, 12)
    obox(s, 'orifice plate tab', (bx, y + .2, zo), (.03, 0, 0), (0, .05, 0), (0, 0, .006), SS)
    if y >= .9:
        pipe(s, 'minimum flow line', [(bx, y, Z - .3), (bx, y, zv), (bx, .0, zv)], .085, BODY, .2, 12)
        cyl(s, 'min flow valve body', (bx, .62, zv), (bx, .38, zv), .13, CAST, 16)
        cyl(s, 'min flow valve bonnet', (bx, .5, zv), (bx, .5, zv - .16), .08, CAST, 12)
        for k in (-1, 1):
            box(s, 'min flow yoke arm', (bx + k * .07 - .015, .44, zv - .28), (bx + k * .07 + .015, .56, zv - .14), CAST)
        box(s, 'min flow valve actuator', (bx - .14, .34, zv - .5), (bx + .14, .66, zv - .28), JBOX)
        cyl(s, 'actuator motor', (bx, .66, zv - .39), (bx, .86, zv - .39), .075, JBOX, 12)
        torus(s, 'actuator handwheel', (bx + .17, .5, zv - .39), (1, 0, 0), .1, .012, WHEEL, 14, 4)
    else:
        # Low discharge line: valve in the horizontal leg with the actuator above it.
        zb = zv + .1
        pipe(s, 'minimum flow line', [(bx, y, Z - .3), (bx, y, zb - .28), (bx, .0, zb - .28)], .085, BODY, .16, 12)
        cyl(s, 'min flow valve body', (bx, y, zb + .13), (bx, y, zb - .11), .13, CAST, 16)
        cyl(s, 'min flow valve bonnet', (bx, y, zb), (bx, y + .2, zb), .08, CAST, 12)
        for k in (-1, 1):
            box(s, 'min flow yoke arm', (bx + k * .07 - .015, y + .2, zb - .03), (bx + k * .07 + .015, y + .36, zb + .03), CAST)
        box(s, 'min flow valve actuator', (bx - .14, y + .36, zb - .12), (bx + .14, y + .62, zb + .12), JBOX)
        cyl(s, 'actuator motor', (bx - .14, y + .5, zb), (bx - .34, y + .5, zb), .075, JBOX, 12)
        torus(s, 'actuator handwheel', (bx, y + .5, zb + .15), (0, 0, 1), .1, .012, WHEEL, 14, 4)


def water_leg_pump(s, x, z, suction_from, discharge_to, mat):
    """Water-leg (jockey) pump: a small horizontal single-stage centrifugal pump that keeps the discharge line full.
    Suction from the main pump suction line, discharge through a check valve into the discharge line."""
    y = .22
    box(s, 'water leg pump base', (x - .36, 0, z - .13), (x + .3, .05, z + .13), BASE)
    cyl(s, 'water leg pump motor', (x - .33, y, z), (x - .05, y, z), .095, mat, 14)
    cyl(s, 'water leg motor fan cover', (x - .36, y, z), (x - .33, y, z), .085, BLACK, 14)
    box(s, 'water leg motor foot', (x - .28, .05, z - .08), (x - .1, y - .07, z + .08), mat)
    box(s, 'water leg motor box', (x - .25, y + .08, z - .05), (x - .13, y + .15, z + .05), JBOX)
    cyl(s, 'water leg pump frame', (x - .05, y, z), (x + .08, y, z), .05, CAST, 10)
    cyl(s, 'water leg pump casing', (x + .08, y, z), (x + .17, y, z), .12, CAST, 16)
    box(s, 'water leg casing foot', (x + .09, .05, z - .06), (x + .16, y - .09, z + .06), CAST)
    cyl(s, 'water leg suction nozzle', (x + .17, y, z), (x + .27, y, z), .05, CAST, 10)
    cyl(s, 'water leg discharge nozzle', (x + .125, y + .08, z), (x + .125, y + .2, z), .042, CAST, 10)
    sweep(s, 'water leg suction line', fillet([V(p) for p in suction_from] + [V(x + .27, y, z)], .06, 3), .034, BODY, 6)
    pts = [V(x + .125, y + .2, z)] + [V(p) for p in discharge_to]
    sweep(s, 'water leg discharge line', fillet(pts, .06, 3), .03, BODY, 6)
    cyl(s, 'water leg check valve', (x + .125, y + .3, z), (x + .125, y + .42, z), .055, BRASS, 10)
    decal(s, 'water leg tag', (x - .03, .025, z + .131), (0, 0, 1), (0, 1, 0), .16, .04, 'tag_wlp')


GREEN_LAMP = paint('green pushbutton', '#2e8b3d', .0, .4)
RED_LAMP = paint('red pushbutton', '#b3261e', .0, .4)


# ---------------------------------------------------------------- machines
def eccs_vertical(s, kind, X, can, head, stand_top, motor_r, suction_lane, discharge_y, riser_x, top_y):
    """RHR and HPCS: suction drops to the can nozzle near the floor; discharge leaves the head and rises."""
    Z = 1.5
    up = V(0, 1, 0)
    rc, yc = can
    sole_plate(s, X, Z, .78)
    cyl(s, 'pump can top', (X, .07, Z), (X, yc, Z), rc, CAST, 32)
    band(s, 'can floor ring', (X, .07, Z), (X, .13, Z), rc + .08, rc - .02, CAST, 32)
    cyl(s, 'can head flange', (X, yc, Z), (X, yc + .08, Z), rc + .16, CAST, 40)
    bolt_ring(s, 'can flange nut', (X, yc + .08, Z), up, rc + .1, 12, .028, .04)
    rh, yh = head
    lathe(s, 'discharge head casting', (X, yc + .08, Z), up,
          [(0, rh + .02), (.05, rh), (yh - yc - .2, rh * .92), (yh - yc - .12, rh * .62), (yh - yc - .08, rh * .62)], CAST, 32)
    tube(s, 'discharge head top flange', (X, yh - .08, Z), (X, yh, Z), rh * .8, rh * .3, CAST, 32)
    for i in range(6):
        a = i * TAU / 6 + .3
        rad = V(math.cos(a), 0, math.sin(a))
        obox(s, 'head stiffening rib', V(X, (yc + yh) / 2, Z) + rad * rh * .93, rad * .04, V(-math.sin(a), 0, math.cos(a)) * .015, up * (yh - yc - .25) / 2, CAST)
    # Suction nozzle on the can, discharge nozzle on the head (both toward the west).
    sn = nozzle(s, 'suction nozzle', (X, suction_lane, Z), (-1, 0, 0), rc + .14, .28, .38, CAST)
    dn = nozzle(s, 'discharge nozzle', (X, discharge_y, Z), (-1, 0, 0), rh + .14, .27, .38, CAST)
    motor_stand(s, X, Z, yh, stand_top, motor_r * .95, CAST)
    vertical_motor(s, X, Z, stand_top, top_y - stand_top - .02, motor_r, MOTOR[kind], 0.0, math.pi / 2 + .45)
    # Suction line: port at the west face, down to the floor lane and into the can.
    port(s, 'water_suction', (0, 1, 1), 'west', .3)
    pipe(s, 'suction line', [(.29, 1.5, Z), (.72, 1.5, Z), (.72, suction_lane, Z), (sn.x - .07, suction_lane, Z)], .25, BODY, .32)
    pipe_support(s, 'suction support', (.72 + sn.x) / 2 + .2, Z, suction_lane, .25)
    # Discharge: check valve then up to the port.
    port(s, 'water_discharge', (riser_x, top_y - 1, 1), 'up', top_y - .55)
    cvx = (riser_x + .55 + dn.x) / 2
    pipe(s, 'discharge line to check valve', [(dn.x - .07, discharge_y, Z), (cvx + .33, discharge_y, Z)], .25, BODY)
    check_valve(s, 'discharge check valve', cvx, discharge_y, Z)
    pipe(s, 'discharge riser', [(cvx - .33, discharge_y, Z), (riser_x + .5, discharge_y, Z), (riser_x + .5, top_y - .5, Z)], .25, BODY, .5)
    gauge(s, 'discharge pressure gauge', (riser_x + .5, top_y - 1.0, Z + .25), (0, 0, 1), (0, 0, 1), .14)
    gauge(s, 'suction pressure gauge', (.72, 1.12, Z + .25), (0, 0, 1), (0, 0, 1), .12)
    # Minimum-flow bypass between the pump and the check valve.
    min_flow_line(s, (cvx + .33 + dn.x) / 2 + .02, discharge_y, Z, .6)
    # Vent on the head, equipment tag and caution label.
    cyl(s, 'head vent', (X + rh * .5, yh, Z - rh * .3), (X + rh * .5, yh + .12, Z - rh * .3), .022, SS, 8)
    tag = {'rhr': 'tag_rhr', 'hpcs': 'tag_hpcs'}[kind]
    decal(s, 'equipment tag', (X, yc + .16, Z + rh * .96), (0, 0, 1), up, .5, .062, tag, back=SS)
    decal(s, 'caution label', (X - .2, (yh + stand_top) / 2, Z + motor_r * .95 * .76 + .02), (0, 0, 1), up, .14, .07, 'caution')
    decal(s, 'pump nameplate', (X + rh * .7, (yc + yh) / 2 + .05, Z + rh * .7), (.7071, 0, .7071), up, .26, .13, 'pump_plate', back=SS)
    return sn, dn


def build_rhr():
    """RHR / LPCI: the smallest of the three motors (about 800 hp)."""
    s = new_scene('BWR | motor pump rhr_pump')
    X = 4.3
    eccs_vertical(s, 'rhr', X, (.46, .62), (.55, 1.34), 1.76, .62, .42, 1.04, 1, 3)
    seal_cooler(s, 5.55, 6.55, .5, .55, [(5.2, .5, .55), (5.2, 1.0, .55), (X + .2, 1.0, .9), (X + .2, 1.45, 1.1)])
    instrument_rack(s, 5.95, 6.95, 2.25, 2.7, 1.62)
    return s


def build_hpcs():
    """HPCS: the largest motor (3000 hp), with its water-leg (jockey) pump."""
    s = new_scene('BWR | motor pump hpcs_pump')
    X = 5.75
    eccs_vertical(s, 'hpcs', X, (.52, .64), (.62, 1.5), 2.06, .9, .42, 1.1, 2, 4)
    water_leg_pump(s, 3.3, 2.45, [(3.95, .42, 1.72), (3.95, .42, 2.45), (3.95, .22, 2.45)],
                   [(3.425, 1.75, 2.45), (2.5, 1.75, 2.45), (2.5, 1.75, 1.74)], MOTOR['hpcs'])
    seal_cooler(s, 7.0, 8.0, .5, .55, [(6.75, .5, .55), (6.75, 1.1, .55), (X + .2, 1.1, .82), (X + .2, 1.6, 1.0)])
    instrument_rack(s, 7.65, 8.9, 2.25, 2.72, 1.9)
    return s


def build_lpcs():
    """LPCS: in-line nozzles on the discharge head, a mid-sized motor (about 1500 hp) and a water-leg pump."""
    s = new_scene('BWR | motor pump lpcs_pump')
    X, Z = 2.5, 1.5
    up = V(0, 1, 0)
    sole_plate(s, X, Z, .9)
    rh = .66
    lathe(s, 'discharge head casting', (X, .07, Z), up,
          [(0, rh + .14), (.08, rh + .14), (.08, rh), (.75, rh * .94), (.88, rh * .65), (.95, rh * .65)], CAST, 40)
    bolt_ring(s, 'head hold-down nut', (X, .15, Z), up, rh + .08, 12, .03, .045)
    tube(s, 'discharge head top flange', (X, .95, Z), (X, 1.04, Z), rh * .82, rh * .3, CAST, 32)
    for i in range(4):
        a = i * TAU / 4 + math.pi / 4
        rad = V(math.cos(a), 0, math.sin(a))
        obox(s, 'head stiffening rib', V(X, .52, Z) + rad * rh * .93, rad * .05, V(-math.sin(a), 0, math.cos(a)) * .018, up * .36, CAST)
    sn = nozzle(s, 'suction nozzle', (X, .5, Z), (-1, 0, 0), rh + .18, .3, .42, CAST, 10)
    dn = nozzle(s, 'discharge nozzle', (X, .5, Z), (1, 0, 0), rh + .18, .29, .42, CAST, 10)
    motor_stand(s, X, Z, 1.04, 1.9, .68, CAST)
    vertical_motor(s, X, Z, 1.9, 2.45, .7, MOTOR['lpcs'], math.pi / 2, -math.pi / 2 + .5, 4, cool_floor=.16)
    port(s, 'water_suction', (0, 0, 1), 'west', .3)
    port(s, 'water_discharge', (4, 0, 1), 'east', 4.7)
    pipe(s, 'suction spool', [(.29, .5, Z), (sn.x - .07, .5, Z)], .25, BODY)
    cone(s, 'suction reducer', (sn.x - .3, .5, Z), (sn.x - .07, .5, Z), .25, .3, BODY, 24)
    pipe(s, 'discharge spool', [(dn.x + .07, .5, Z), (4.71, .5, Z)], .25, BODY)
    cone(s, 'discharge reducer', (dn.x + .07, .5, Z), (dn.x + .3, .5, Z), .29, .25, BODY, 24)
    gauge(s, 'discharge pressure gauge', (4.2, .75, Z), (0, 1, 0), (0, 0, 1), .25)
    gauge(s, 'suction pressure gauge', (.85, .75, Z), (0, 1, 0), (0, 0, 1), .25)
    cyl(s, 'discharge drain', (4.45, .25, Z), (4.45, .1, Z), .03, SS, 8)
    cyl(s, 'head vent', (X + .3, 1.04, Z - .3), (X + .3, 1.16, Z - .3), .022, SS, 8)
    min_flow_line(s, 3.9, .5, Z, .6)
    water_leg_pump(s, .62, 2.5, [(1.1, .5, 1.72), (1.1, .5, 2.5), (1.1, .22, 2.5)],
                   [(.745, .8, 2.5), (.745, .8, 2.8), (.745, .1, 2.8), (4.3, .1, 2.8), (4.3, .1, 2.0), (4.3, .5, 2.0), (4.3, .5, 1.74)], MOTOR['lpcs'])
    decal(s, 'equipment tag', (X, .32, Z + rh * .99 + .01), (0, 0, 1), up, .5, .062, 'tag_lpcs', back=SS)
    decal(s, 'pump nameplate', (X - rh * .68, .6, Z - rh * .68), (-.7071, 0, -.7071), up, .26, .13, 'pump_plate', back=SS)
    decal(s, 'caution label', (X - .2, 1.47, Z + .68 * .76 + .02), (0, 0, 1), up, .14, .07, 'caution')
    return s


def build_mfp():
    """Motor-driven reactor feed pump: single-stage double-suction pump (bottom suction, top discharge), speed-increasing
    gear and a large horizontal weather-protected motor, with a lube oil console and a minimum-flow line."""
    s = new_scene('BWR | motor pump motor_feed_pump')
    Z, A = 1.5, 1.45
    ax = V(1, 0, 0)
    up = V(0, 1, 0)
    mm = MOTOR['rfw']
    # Fabricated baseplate on a grout pad, with a drip rim and anchor bolts.
    box(s, 'grout pad', (.06, 0, .3), (6.94, .03, 2.7), GROUT)
    box(s, 'baseplate deck', (.12, .03, .38), (6.88, .17, 2.62), BASE)
    for z in (.38, 2.56):
        box(s, 'baseplate drip rim', (.12, .17, z), (6.88, .21, z + .06), BASE)
    for x in (.12, 6.82):
        box(s, 'baseplate drip rim', (x, .17, .38), (x + .06, .21, 2.62), BASE)
    for x in (.4, 1.6, 2.8, 4.0, 5.2, 6.5):
        for z in (.5, 2.5):
            nut(s, 'baseplate anchor nut', (x, .17, z), (0, 1, 0), .035, .04)
    # Pump: short forged casing between two bolted end covers, seal and bearing housings on each side.
    P, rb = 3.5, .56
    cyl(s, 'pump casing', (P - .34, A, Z), (P + .34, A, Z), rb, CAST, 40)
    for sg in (-1, 1):
        d = ax * sg
        e = V(P, A, Z) + d * .34
        cyl(s, 'casing end cover', e, e + d * .12, rb - .07, CAST, 40)
        bolt_ring(s, 'cover stud nut', e + d * .12, d, rb - .15, 10, .034, .06)
        cyl(s, 'seal housing', e + d * .12, e + d * .3, .2, CAST, 24)
        cyl(s, 'bearing housing', e + d * .3, e + d * .58, .19, CAST, 24)
        xb = (e + d * .44).x
        box(s, 'bearing bracket saddle', (xb - .1, A - .24, Z - .37), (xb + .1, A - .12, Z + .37), CAST)
        for sz in (-1, 1):
            box(s, 'bearing bracket leg', (xb - .07, .17, Z + sz * .31 - .05), (xb + .07, A - .24, Z + sz * .31 + .05), CAST)
        pipe(s, 'bearing oil supply', [(xb, A + .18, Z - .08), (xb, A + .3, Z - .08), (xb, A + .3, Z - .9), (xb, .21, Z - .9)], .016, OIL, .06, 6, 3)
        xi = (e + d * .21).x
        sweep(s, 'seal injection tube', fillet([(xi, A + .19, Z + .05), (xi, A + .34, Z + .05), (xi, A + .34, Z + .45)], .05, 3), .013, SS, 6)
    cyl(s, 'thrust bearing cap', (P - .92, A, Z), (P - .98, A, Z), .14, CAST, 20)
    # Centreline feet on pedestals.
    for sz in (-1, 1):
        za, zb = sorted((Z + sz * (rb - .06), Z + sz * (rb + .22)))
        box(s, 'centreline support foot', (P - .2, A - .08, za), (P + .2, A + .02, zb), CAST)
        zc = Z + sz * (rb + .12)
        box(s, 'support pedestal', (P - .16, .17, zc - .1), (P + .16, A - .08, zc + .1), BASE)
        for x in (P - .12, P + .12):
            nut(s, 'foot hold-down nut', (x, A + .02, zc + sz * .02), (0, 1, 0), .03, .04)
    # Discharge nozzle on top, straight up to its port.
    cyl(s, 'discharge casing nozzle', (P, A + rb - .1, Z), (P, 2.1, Z), .29, CAST, 24)
    joint(s, 'discharge nozzle joint', (P, 2.16, Z), (0, 1, 0), .27, .42, 8)
    port(s, 'water_discharge', (3, 2, 1), 'up', 2.2)
    gauge(s, 'water_discharge gauge', (P, 2.55, Z + .25), (0, 0, 1), (0, 0, 1), .12)
    # Suction nozzle underneath; the suction line runs below the outboard bearing and rises to its port.
    cyl(s, 'suction casing nozzle', (P, A - rb + .1, Z), (P, .86, Z), .3, CAST, 24)
    joint(s, 'suction nozzle joint', (P, .8, Z), (0, 1, 0), .27, .42, 8)
    pipe(s, 'suction line', [(P, .75, Z), (P, .46, Z), (1.5, .46, Z), (1.5, 2.31, Z)], .25, BODY, .26)
    port(s, 'water_suction', (1, 2, 1), 'up', 2.3)
    gauge(s, 'water_suction gauge', (1.5, 1.9, Z + .25), (0, 0, 1), (0, 0, 1), .12)
    # Minimum-flow (recirculation) line with an air-operated control valve.
    mz = .45
    pipe(s, 'minimum flow line', [(P, 1.98, Z - .2), (P, 1.98, mz), (P, .19, mz)], .07, BODY, .14, 12)
    cyl(s, 'min flow valve body', (P, 1.3, mz), (P, 1.06, mz), .12, CAST, 14)
    cyl(s, 'min flow valve bonnet', (P, 1.18, mz), (P + .22, 1.18, mz), .05, CAST, 10)
    lathe(s, 'min flow valve diaphragm actuator', (P + .22, 1.18, mz), (1, 0, 0), [(0, .05), (.03, .15), (.07, .15), (.1, .06), (.1, 0)], JBOX, 16)
    cyl(s, 'casing drain', (P + .2, A - rb + .02, Z + .2), (P + .2, .3, Z + .2), .025, SS, 8)
    cyl(s, 'drain valve', (P + .2, .45, Z + .2), (P + .2, .35, Z + .2), .045, BRASS, 8)
    # Pump shaft and coupling guard to the speed increaser.
    gx0, gx1 = 4.76, 5.3
    cyl(s, 'pump shaft end', (P + .92, A, Z), (gx0, A, Z), .06, STEEL, 12)
    lathe(s, 'coupling guard', (P + .94, A, Z), ax, [(0, .0), (0, .21), (gx0 - P - .96, .21), (gx0 - P - .96, .0)], GUARD, 20)
    decal(s, 'caution label', ((P + .94 + gx0) / 2, A + .02, Z + .215), (0, 0, 1), up, .13, .065, 'caution')
    # Speed-increasing gear: parallel shafts side by side, split housing, bearing caps where the shafts leave.
    Zm = Z + .18
    gz0, gz1 = Z - .36, Zm + .42
    box(s, 'gear case lower half', (gx0, .17, gz0), (gx1, A, gz1), CAST)
    box(s, 'gear case base flange', (gx0 - .04, .17, gz0 - .04), (gx1 + .04, .23, gz1 + .04), CAST)
    box(s, 'gear case split flange', (gx0 - .03, A - .03, gz0 - .03), (gx1 + .03, A + .03, gz1 + .03), CAST)
    box(s, 'gear case cover', (gx0, A + .03, gz0), (gx1, A + .42, gz1), CAST)
    box(s, 'gear inspection cover', (gx0 + .1, A + .42, Z - .2), (gx1 - .1, A + .45, Zm + .26), STEEL)
    for x in (gx0 + .05, gx1 - .05):
        for z in (gz0 + .06, (gz0 + gz1) / 2, gz1 - .06):
            nut(s, 'gear case flange bolt', (x, A + .03, z), (0, 1, 0), .022, .03)
    for x, d, z, r in ((gx0, -1, Z, .11), (gx1, 1, Z, .11), (gx0, -1, Zm + .1, .15), (gx1, 1, Zm + .1, .15)):
        cyl(s, 'gear bearing cap', (x, A, z), (x + d * .05, A, z), r, CAST, 20)
        bolt_ring(s, 'bearing cap screw', (x + d * .05, A, z), (d, 0, 0), r * .78, 6, .014, .015)
    cyl(s, 'gear case breather', (gx0 + .15, A + .45, Zm + .1), (gx0 + .15, A + .55, Zm + .1), .035, BLACK, 8)
    decal(s, 'gear oil sight glass', (gx1 - .12, .6, gz1 + .002), (0, 0, 1), up, .06, .12, 'sight_glass')
    pipe(s, 'gear oil supply', [((gx0 + gx1) / 2, A + .45, gz0 + .1), ((gx0 + gx1) / 2, A + .55, gz0 + .1), ((gx0 + gx1) / 2, A + .55, Z - .9), ((gx0 + gx1) / 2, .21, Z - .9)], .02, OIL, .06, 6, 3)
    # Motor shaft (offset from the pump shaft by the gear centre distance) and its guard.
    mx0, mx1 = 5.58, 6.82
    Zs = Zm + .1
    cyl(s, 'motor shaft end', (gx1 + .05, A, Zs), (mx0, A, Zs), .075, STEEL, 12)
    lathe(s, 'motor coupling guard', (gx1 + .06, A, Zs), ax, [(0, .0), (0, .23), (mx0 - gx1 - .1, .23), (mx0 - gx1 - .1, .0)], GUARD, 20)
    # Large horizontal weather-protected motor: box frame, end brackets with bearing housings, top air housing.
    hw = .6
    box(s, 'motor sole rail', (mx0 + .05, .17, Zs - hw - .04), (mx1 - .05, .27, Zs + hw + .04), BASE)
    box(s, 'motor frame', (mx0 + .1, .27, Zs - hw), (mx1 - .1, A + .5, Zs + hw), mm)
    for x, d in ((mx0 + .1, -1), (mx1 - .1, 1)):
        cyl(s, 'motor end bracket', (x, A, Zs), (x + d * .06, A, Zs), .5, mm, 32)
        cyl(s, 'motor bearing housing', (x + d * .06, A, Zs), (x + d * .16, A, Zs), .2, mm, 20)
        bolt_ring(s, 'end bracket bolt', (x + d * .06, A, Zs), (d, 0, 0), .44, 8, .02, .02)
    cyl(s, 'motor bearing end cap', (mx1 + .06, A, Zs), (mx1 + .09, A, Zs), .13, mm, 16)
    for z in (Zs - hw - .02, Zs + hw - .12):
        for x in (mx0 + .2, mx1 - .2):
            nut(s, 'motor foot bolt', (x, .27, z + .07), (0, 1, 0), .03, .04)
    yt = A + .5
    box(s, 'motor air housing', (mx0 + .04, yt, Zs - hw - .04), (mx1 - .04, 2.82, Zs + hw + .04), mm)
    box(s, 'air housing rain cap', (mx0, 2.82, Zs - hw - .08), (mx1, 2.86, Zs + hw + .08), mm)
    for sz in (-1, 1):
        for k in (0, 1):
            xa = mx0 + .12 + k * .52
            n = (0, 0, sz)
            decal(s, 'motor air louvre', (xa + .24, (yt + 2.82) / 2, Zs + sz * (hw + .042)), n, up, .46, 2.82 - yt - .16, 'louvre')
    for sx, x in ((-1, mx0 + .04), (1, mx1 - .04)):
        decal(s, 'motor air intake screen', (x + sx * .002, (yt + 2.82) / 2, Zs), (sx, 0, 0), up, .9, 2.82 - yt - .2, 'screen')
    for x in (mx0 + .15, mx1 - .15):
        for sz in (-1, 1):
            torus(s, 'motor lifting eye', (x, 2.92, Zs + sz * .45), (1, 0, 0), .05, .016, mm, 10, 5)
    tb = V((mx0 + mx1) / 2, A + .05, Zs + hw + .1)
    box(s, 'motor terminal box', tb - V(.22, .3, .1), tb + V(.22, .3, .1), JBOX)
    box(s, 'terminal box lid', tb + V(-.2, -.28, .1), tb + V(.2, .28, .115), JBOX)
    conduit(s, 'motor power conduit', [tb - V(0, .3, 0), tb - V(0, .5, 0), (tb.x, .2, tb.z)], .08)
    decal(s, 'conduit voltage tag', tb + V(0, .2, .117), (0, 0, 1), up, .1, .02, 'conduit_tag')
    decal(s, 'motor nameplate', ((mx0 + mx1) / 2, A + .05, Zs - hw - .004), (0, 0, -1), up, .3, .15, 'motor_plate', back=SS)
    # Lube oil console at the outboard end: reservoir, motor-driven oil pump, cooler and filter.
    box(s, 'lube oil reservoir', (.3, .17, .8), (1.05, .72, 2.2), CAST)
    box(s, 'reservoir cover', (.28, .72, .78), (1.07, .75, 2.22), CAST)
    cyl(s, 'oil pump motor', (.55, .75, 1.1), (.55, 1.12, 1.1), .1, mm, 14)
    cyl(s, 'oil pump mount', (.55, .75, 1.1), (.55, .8, 1.1), .13, CAST, 14)
    cyl(s, 'oil filter', (.8, .75, 1.55), (.8, 1.0, 1.55), .07, SS, 12)
    cyl(s, 'reservoir breather', (.5, .75, 1.95), (.5, .86, 1.95), .04, BLACK, 8)
    cyl(s, 'lube oil cooler', (.42, .93, 2.0), (.95, .93, 2.0), .09, CAST, 16)
    for x in (.5, .87):
        box(s, 'oil cooler saddle', (x - .03, .75, 1.93), (x + .03, .86, 2.07), BASE)
    decal(s, 'reservoir sight glass', (.9, .45, 2.202), (0, 0, 1), up, .06, .12, 'sight_glass')
    pipe(s, 'lube oil header', [(.8, 1.0, 1.55), (.8, 1.08, 1.55), (.8, 1.08, Z - .9), (1.2, .21, Z - .9), (6.2, .21, Z - .9)], .022, OIL, .06, 6, 3)
    gauge(s, 'lube oil pressure gauge', (.8, 1.08, 1.25), (0, 1, 0), (0, 0, 1), .1)
    decal(s, 'equipment tag', (P, .62, Z + rb + .222), (0, 0, 1), up, .3, .037, 'tag_rfw', back=SS)
    decal(s, 'pump nameplate', (P, .95, Z + rb + .222), (0, 0, 1), up, .28, .14, 'feed_plate', back=SS)
    return s


def build_slc():
    """Horizontal triplex plunger pump: liquid end with suction manifold below and discharge manifold above, power end,
    triple-reduction gear reducer and motor; heat-traced suction piping; pulsation dampener and relief valve."""
    s = new_scene('BWR | motor pump slc_pump')
    up = V(0, 1, 0)
    sm = MOTOR['slc']
    box(s, 'skid deck', (.08, 0, .2), (2.92, .1, 2.8), BASE)
    for z in (.2, 2.72):
        box(s, 'skid channel', (.05, 0, z), (2.95, .14, z + .08), BASE)
    for x in (.25, 1.5, 2.75):
        for z in (.3, 2.7):
            nut(s, 'skid anchor', (x, .14, z), (0, 1, 0), .028, .03)
    # Power end (crankcase) along X: the frame top slopes down toward the crossheads, with a vent on top.
    px0, px1, pz0, pz1 = .62, 1.95, 1.0, 1.72
    box(s, 'power frame', (px0, .1, pz0), (px1, .7, pz1), POWER)
    box(s, 'power frame base flange', (px0 - .04, .1, pz0 - .04), (px1 + .04, .16, pz1 + .04), POWER)
    mesh_object(s, 'power frame sloped top', [(px0, .7, pz0), (px1, .7, pz0), (px1, .7, pz1), (px0, .7, pz1),
                                              (px0, .78, pz0), (px1, .78, pz0), (px1, .92, pz1 - .3), (px0, .92, pz1 - .3),
                                              (px1, .92, pz1), (px0, .92, pz1)],
                [(0, 3, 2, 1), (0, 1, 5, 4), (4, 5, 6, 7), (7, 6, 8, 9), (3, 9, 8, 2), (0, 4, 7, 9, 3), (1, 2, 8, 6, 5)], POWER)
    box(s, 'crankcase rear cover', (px0 + .1, .2, pz1), (px1 - .1, .84, pz1 + .025), POWER)
    for x in (px0 + .15, (px0 + px1) / 2, px1 - .15):
        for y in (.25, .79):
            nut(s, 'rear cover screw', (x, y, pz1 + .025), (0, 0, 1), .015, .012)
    for x, sgn in ((px0, -1), (px1, 1)):
        cyl(s, 'main bearing cap', (x, .5, 1.36), (x + sgn * .06, .5, 1.36), .17, POWER, 20)
        bolt_ring(s, 'bearing cap screw', (x + sgn * .06, .5, 1.36), (sgn, 0, 0), .13, 6, .014, .015)
    cyl(s, 'crankcase vent', (1.25, .92, 1.55), (1.25, 1.03, 1.55), .05, BLACK, 12)
    cyl(s, 'oil fill cap', (.85, .92, 1.6), (.85, .98, 1.6), .035, WHEEL, 10)
    decal(s, 'oil sight glass', (1.72, .45, pz1 + .027), (0, 0, 1), up, .06, .12, 'sight_glass', back=SS)
    # Crosshead extension with three exposed plungers and stuffing boxes.
    box(s, 'crosshead extension frame', (px0 + .05, .1, .7), (px1 - .05, .3, pz0), POWER)
    for x in (px0 + .02, px1 - .07):
        box(s, 'extension side plate', (x, .1, .7), (x + .05, .76, pz0), POWER)
    xs = (.8, 1.25, 1.7)
    yp = .5
    for x in xs:
        cyl(s, 'crosshead guide', (x, yp, pz0), (x, yp, pz0 - .1), .1, POWER, 16)
        cyl(s, 'plunger', (x, yp, pz0), (x, yp, .7), .045, STEEL, 12)
        cyl(s, 'stuffing box gland', (x, yp, .76), (x, yp, .66), .085, SS, 16)
        for k in (-1, 1):
            nut(s, 'gland nut', (x + k * .07, yp, .76), (0, 0, 1), .016, .02)
    # Liquid end: cylinder blocks, discharge valve covers on top, suction valves below.
    box(s, 'liquid end base block', (.62, .3, .36), (1.95, .42, .66), FLUID)
    for x in xs:
        box(s, 'liquid end cylinder', (x - .2, .42, .32), (x + .2, .8, .66), FLUID)
        for sx in (-1, 1):
            for y in (.48, .74):
                nut(s, 'liquid end tie bolt nut', (x + sx * .15, y, .32), (0, 0, -1), .018, .02)
        cyl(s, 'discharge valve cover', (x, .8, .48), (x, .88, .48), .09, SS, 16)
        bolt_ring(s, 'valve cover nut', (x, .88, .48), up, .065, 4, .014, .02)
        cyl(s, 'cylinder head', (x, .5, .32), (x, .5, .25), .09, SS, 16)
        bolt_ring(s, 'cylinder head nut', (x, .5, .25), (0, 0, -1), .065, 4, .014, .02)
    # Suction: west port, reducer, strainer and the round suction manifold under the liquid end, all heat traced.
    port(s, 'water_suction', (0, 0, 1), 'west', .28)
    cone(s, 'suction reducer', (.26, .5, 1.5), (.36, .5, 1.5), .25, .11, BODY, 20)
    run = [(.34, .5, 1.5), (.46, .5, 1.5), (.46, .5, .45), (.46, .19, .45), (2.0, .19, .45)]
    pipe(s, 'suction header and manifold', run, .09, SS, .1, 10, 5)
    for x in xs:
        cyl(s, 'manifold riser', (x, .19, .45), (x, .31, .45), .055, SS, 12)
    cyl(s, 'Y-strainer body', (.46, .5, .92), (.46, .5, 1.18), .13, CAST, 20)
    cyl(s, 'Y-strainer screen leg', (.46, .46, 1.0), (.46, .27, .81), .085, CAST, 16)
    cyl(s, 'Y-strainer cap', (.46, .27, .81), (.46, .24, .78), .1, CAST, 16)
    # Electric heat tracing spiralled along the suction manifold, with its power connection box.
    turns, n = 9, 36
    helix = [(.56 + 1.4 * i / n, .19 + .1 * math.cos(turns * TAU * i / n), .45 + .1 * math.sin(turns * TAU * i / n)) for i in range(n + 1)]
    sweep(s, 'suction heat trace cable', helix, .012, OIL, 3, False)
    box(s, 'heat trace power box', (2.02, .14, .3), (2.2, .34, .44), JBOX)
    sweep(s, 'heat trace lead', fillet([(1.96, .19, .55), (2.1, .19, .55), (2.1, .3, .44)], .03, 3), .011, OIL, 4)
    decal(s, 'heat trace tag', (2.11, .24, .298), (0, 0, -1), up, .16, .04, 'tag_trace')
    # Discharge: manifold on top, pulsation dampener, relief valve and line to the east port.
    pipe(s, 'discharge manifold', [(.8, .97, .48), (2.6, .97, .48), (2.6, .97, 1.5), (2.6, .5, 1.5), (2.79, .5, 1.5)], .075, SS, .14, 12, 5)
    for x in xs:
        cyl(s, 'discharge riser', (x, .88, .48), (x, .97, .48), .05, SS, 12)
    cyl(s, 'dampener stem', (2.2, .97, .48), (2.2, 1.12, .48), .05, SS, 12)
    lathe(s, 'pulsation dampener', (2.2, 1.1, .48), up, [(0, .0), (0, .1), (.05, .17), (.55, .17), (.65, .1), (.66, 0)], SS, 24)
    cyl(s, 'dampener charging valve', (2.2, 1.76, .48), (2.2, 1.83, .48), .025, BRASS, 8)
    gauge(s, 'discharge gauge', (2.6, 1.04, .95), (0, 1, 0), (1, 0, 0), .2)
    cyl(s, 'relief valve body', (2.6, .97, 1.2), (2.42, .97, 1.2), .07, BRASS, 12)
    lathe(s, 'relief valve bonnet', (2.42, .97, 1.2), up, [(0, .05), (.32, .04), (.32, 0)], BRASS, 12)
    cyl(s, 'relief cap', (2.42, 1.29, 1.2), (2.42, 1.35, 1.2), .03, WHEEL, 8)
    cone(s, 'discharge expander', (2.72, .5, 1.5), (2.8, .5, 1.5), .08, .25, BODY, 20)
    port(s, 'water_discharge', (2, 0, 1), 'east', 2.78)
    # Triple-reduction gear reducer between the motor and the crankshaft: four parallel shafts in a split housing,
    # each with its bearing cap on the outboard face, stepping up in size from the motor pinion to the output gear.
    gx0, gx1 = 1.98, 2.42
    mz, my, mr = 2.12, .46, .3
    box(s, 'gear reducer lower case', (gx0, .1, pz0 + .05), (gx1, .5, 2.48), CAST)
    box(s, 'reducer split flange', (gx0 - .015, .48, pz0 + .035), (gx1 + .015, .52, 2.495), CAST)
    mesh_object(s, 'gear reducer cover', [(gx0, .52, pz0 + .05), (gx1, .52, pz0 + .05), (gx1, .52, 2.48), (gx0, .52, 2.48),
                                          (gx0, .9, pz0 + .05), (gx1, .9, pz0 + .05), (gx1, .9, 1.75), (gx0, .9, 1.75),
                                          (gx1, .72, 2.48), (gx0, .72, 2.48)],
                [(0, 3, 2, 1), (0, 1, 5, 4), (4, 5, 6, 7), (7, 6, 8, 9), (3, 9, 8, 2), (0, 4, 7, 9, 3), (1, 2, 8, 6, 5)], CAST)
    for z in (pz0 + .12, 1.45, 1.8, 2.15, 2.42):
        for x in (gx0 - .005, gx1 + .005):
            nut(s, 'reducer flange bolt', (x, .52, z), (0, 1, 0), .014, .02)
    for z, r in ((mz, .075), (1.88, .095), (1.63, .12), (1.36, .16)):
        cyl(s, 'reducer bearing cap', (gx1, .5, z), (gx1 + .035, .5, z), r, CAST, 16)
        nut(s, 'bearing cap plug', (gx1 + .035, .5, z), (1, 0, 0), r * .3, .012)
    cyl(s, 'reducer breather', (2.2, .9, 1.4), (2.2, 1.0, 1.4), .03, BLACK, 8)
    box(s, 'reducer inspection cover', (gx0 + .08, .9, 1.15), (gx1 - .08, .915, 1.65), STEEL)
    cyl(s, 'crankshaft input', (px1, .5, 1.36), (gx0, .5, 1.36), .06, STEEL, 12)
    cyl(s, 'motor drive end', (1.72, my, mz), (1.8, my, mz), mr * .9, sm, 24)
    cyl(s, 'motor frame', (.58, my, mz), (1.72, my, mz), mr, sm, 24)
    for i in range(20):
        a = i * TAU / 20
        if math.sin(a) < -.6:
            continue
        rad = V(0, math.sin(a), math.cos(a))
        obox(s, 'motor rib', V(1.15, my, mz) + rad * mr * 1.03, (.54, 0, 0), rad * .03, rad.cross(V(1, 0, 0)) * .009, sm)
    lathe(s, 'motor fan cowl', (.58, my, mz), (-1, 0, 0), [(0, mr * .9), (.03, mr * 1.05), (.2, mr * 1.05), (.22, mr * .9), (.22, 0)], sm, 24)
    decal(s, 'fan grille', (.36 - .001, my, mz), (-1, 0, 0), up, .5, .5, 'fan_grille')
    for x in (.7, 1.6):
        box(s, 'motor foot', (x - .08, .1, mz - .26), (x + .08, my - .2, mz + .26), sm)
    lathe(s, 'coupling guard', (1.8, my, mz), (1, 0, 0), [(0, 0), (0, .15), (.18, .15), (.18, 0)], GUARD, 16)
    box(s, 'motor terminal box', (1.0, my + mr - .02, mz - .14), (1.3, my + mr + .2, mz + .14), JBOX)
    conduit(s, 'motor conduit', [(1.15, my + mr + .2, mz + .14), (1.15, my + mr + .2, mz + .3), (1.15, .5, 2.65), (1.15, .14, 2.65)], .1)
    decal(s, 'motor nameplate', (1.1, my, mz + mr + .002), (0, 0, 1), up, .24, .12, 'motor_plate', back=SS)
    decal(s, 'equipment tag', (1.5, .075, .2 - .002), (0, 0, -1), up, .44, .055, 'tag_slc', back=SS)
    return s


def build_tank():
    """SLC storage tank: insulated vertical tank with heater, air sparger, mixer drive, level gauge and outlet."""
    s = new_scene('BWR | motor pump slc_boron_tank')
    up = V(0, 1, 0)
    X, Z = 1.5, 1.5
    TANK = paint('slc tank stainless jacket', '#c9cfd2', .6, .4)
    box(s, 'foundation', (.25, 0, .25), (2.75, .2, 2.75), GROUT)
    for a in range(4):
        ang = (a + .5) * TAU / 4
        p = V(X + .82 * math.cos(ang), .2, Z + .82 * math.sin(ang))
        cyl(s, 'tank leg', p, p + V(0, .3, 0), .07, BASE, 12)
        box(s, 'leg base plate', p - V(.12, 0, .12), p + V(.12, .03, .12), BASE)
        for k in (-1, 1):
            nut(s, 'leg anchor', p + V(k * .08, .03, k * .08), up, .02, .02)
    lathe(s, 'tank shell', (X, .45, Z), up, [(0, .0), (0, .85), (.05, 1.0), (3.0, 1.0), (3.05, .97), (3.18, .7), (3.22, 0)], TANK, 48)
    for y in (.9, 1.6, 2.3, 3.0):
        tube(s, 'insulation band', (X, y, Z), (X, y + .03, Z), 1.012, .99, SS, 48)
    # Outlet (north) and fill (top) ports at their original positions.
    port(s, 'water_discharge', (1, 0, 0), 'north', .55)
    pipe(s, 'outlet line', [(X, .5, .55), (X, .5, .62)], .25, BODY)
    tube(s, 'outlet heat trace', (X, .5, .3), (X, .5, .55), .26, .25, OIL, 16)
    port(s, 'water_suction', (1, 3, 1), 'up', 3.6)
    # Manway, mixer drive, sparger air line, heater flange and level gauge.
    cyl(s, 'manway neck', (2.05, 3.5, 1.9), (2.05, 3.72, 1.9), .2, TANK, 24)
    cyl(s, 'manway cover', (2.05, 3.72, 1.9), (2.05, 3.78, 1.9), .25, SS, 24)
    bolt_ring(s, 'manway nut', (2.05, 3.78, 1.9), up, .21, 10, .018, .025)
    cyl(s, 'mixer mount', (.95, 3.55, 1.1), (.95, 3.65, 1.1), .16, SS, 20)
    cyl(s, 'mixer gearbox', (.95, 3.65, 1.1), (.95, 3.78, 1.1), .14, sm_tank, 20)
    cyl(s, 'mixer motor', (.95, 3.78, 1.1), (.95, 3.98, 1.1), .11, sm_tank, 20)
    pipe(s, 'sparger air line', [(2.62, .55, 1.0), (2.62, 3.25, 1.0), (2.3, 3.6, 1.0), (2.1, 3.6, 1.0)], .03, SS, .15, 8, 4)
    cyl(s, 'heater flange', (2.33, .95, 2.0), (2.45, .95, 2.0), .2, SS, 20)
    box(s, 'heater terminal box', (2.45, .8, 1.85), (2.68, 1.1, 2.15), JBOX)
    conduit(s, 'heater conduit', [(2.56, .8, 2.0), (2.56, .5, 2.0), (2.56, .2, 2.0)], .08)
    for y in (.75, 3.15):
        cyl(s, 'level tap', (2.48, y, 1.5), (2.62, y, 1.5), .04, SS, 8)
    cyl(s, 'level gauge glass', (2.62, .75, 1.5), (2.62, 3.15, 1.5), .045, DARK, 10)
    for x in (1.0, 2.0):
        cyl(s, 'ladder stile', (x, .2, 2.68), (x, 3.75, 2.68), .025, GALV, 8)
    for i in range(13):
        cyl(s, 'ladder rung', (1.0, .45 + i * .26, 2.68), (2.0, .45 + i * .26, 2.68), .02, GALV, 8)
    decal(s, 'borate placard', (X, 2.1, Z - 1.0 - .003), (0, 0, -1), up, .9, .225, 'boron_placard', back=SS)
    decal(s, 'equipment tag', (X, 1.2, Z - 1.0 - .003), (0, 0, -1), up, .5, .062, 'tag_tank', back=SS)
    return s


sm_tank = paint('slc mixer enamel', COLORS['slc'], .1, .5)


# ---------------------------------------------------------------- export
CONFIG = {
    'lpcs_pump': (build_lpcs, MODERN, [5, 5, 3], 1.0, [('WATER_SUCTION', [0, 0, 1], 'west'), ('WATER_DISCHARGE', [4, 0, 1], 'east')]),
    'hpcs_pump': (build_hpcs, MODERN, [9, 4, 3], 1.0, [('WATER_SUCTION', [0, 1, 1], 'west'), ('WATER_DISCHARGE', [2, 3, 1], 'up')]),
    'rhr_pump': (build_rhr, MODERN, [7, 3, 3], 1.15, [('WATER_SUCTION', [0, 1, 1], 'west'), ('WATER_DISCHARGE', [1, 2, 1], 'up')]),
    'motor_feed_pump': (build_mfp, MODERN, [7, 3, 3], 1.25, [('WATER_SUCTION', [1, 2, 1], 'up'), ('WATER_DISCHARGE', [3, 2, 1], 'up')]),
    'slc_pump': (build_slc, ALPHA, [3, 2, 3], None, [('WATER_SUCTION', [0, 0, 1], 'west'), ('WATER_DISCHARGE', [2, 0, 1], 'east')]),
    'slc_boron_tank': (build_tank, ALPHA, [3, 4, 3], None, [('WATER_SUCTION', [1, 3, 1], 'up'), ('WATER_DISCHARGE', [1, 0, 0], 'north')]),
}


def export(scene, id):
    _, folder, size, scale, ports = CONFIG[id]
    faces, boxes = [], []
    mats = dict(LEGACY[id])
    for o in scene.objects:
        if o.type != 'MESH':
            continue
        me = o.data
        me.calc_loop_triangles()
        points = [game(o.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in points) for a in range(3)] + [max(p[a] for p in points) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            if m.name not in mats:
                tex = m.get('texture')
                bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
                rgb = [1, 1, 1] if tex else [srgb(x) for x in bsdf.inputs['Base Color'].default_value[:3]]
                mats[m.name] = {'color': rgb, 'texture': tex or 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(points[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3]) - Vector(vs[0][:3])).cross(Vector(vs[2][:3]) - Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': m.name, 'part': o.name})
    meta = {'id': id, 'size': size, 'controller': [size[0] // 2, 0, size[2] // 2]}
    if scale is not None:
        meta['scale'] = scale
    meta['ports'] = [{'role': r, 'cell': c, 'face': f} for r, c, f in ports]
    meta['frozen_compact'] = True
    data = dict(meta, faces=faces, boxes=boxes, materials=mats)
    (folder / (id + '.json')).write_text(json.dumps(data, separators=(',', ':')), encoding='utf-8')
    bmin = [min(p[a] for f in faces for p in f['vertices']) for a in range(3)]
    bmax = [max(p[a] for f in faces for p in f['vertices']) for a in range(3)]
    print(f'MOTOR PUMP {id}: {len(faces)} triangles, bounds {[round(v, 3) for v in bmin]} .. {[round(v, 3) for v in bmax]}', flush=True)
    assert all(math.isfinite(x) for f in faces for p in f['vertices'] for x in p), id + ': non-finite vertex'
    assert all(bmin[a] >= -1e-6 and bmax[a] <= size[a] + 1e-6 for a in range(3)), (id, bmin, bmax)


def preview(scene, id, out_dir, tag='after'):
    _, _, size, _, _ = CONFIG[id]
    w, h, d = size
    target = V(w / 2, h * .45, d / 2)
    span = max(w, h, d)
    cam = bpy.data.cameras.new(scene.name + ' camera')
    co = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(co)
    scene.camera = co
    cam.lens = 50
    world = bpy.data.worlds.new(scene.name + ' world')
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.32, .35, .39, 1)
    bg.inputs['Strength'].default_value = .9
    scene.world = world
    sun = bpy.data.lights.new(scene.name + ' sun', 'SUN')
    sun.energy = 3.2
    so = bpy.data.objects.new(sun.name, sun)
    scene.collection.objects.link(so)
    so.rotation_euler = (math.radians(50), 0, math.radians(35))
    floor = box(scene, '_preview floor', (-6, -.02, -6), (w + 6, 0, d + 6), GROUT)
    if CYCLES_PREVIEW:
        # No GPU/display (bpy as a Python module, or --cycles): render on the CPU.
        scene.render.engine = 'CYCLES'
        scene.cycles.device = 'CPU'
        scene.cycles.samples = 20
        scene.cycles.use_denoising = False
        scene.cycles.max_bounces = 2
    else:
        scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x, scene.render.resolution_y = 1600, 1000
    scene.render.image_settings.file_format = 'PNG'
    scene.view_settings.view_transform = 'Standard'
    views = {'a': (-.9, .75, 1.3), 'b': (1.25, .55, 1.1), 'c': (.4, .5, -1.4)}
    for k, (dx, dy, dz) in views.items():
        eye = target + V(dx, dy, dz).normalized() * (1.35 * math.sqrt(w * w + h * h + d * d) + 2.2)
        co.location = xyz(eye)
        co.rotation_euler = (xyz(target) - xyz(eye)).to_track_quat('-Z', 'Y').to_euler()
        scene.render.filepath = str(Path(out_dir) / f'{id}_{tag}_{k}.png')
        bpy.ops.render.render(write_still=True, scene=scene.name)
    bpy.data.objects.remove(floor, do_unlink=True)
    for o in (co, so):
        bpy.data.objects.remove(o, do_unlink=True)


def json_scene(path, id):
    """Rebuild an exported source JSON (e.g. the pre-remodel mesh) as flat-coloured geometry for comparison renders."""
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    s = new_scene('BWR | before ' + id)
    names = list(data['materials'])
    mats = []
    for k in names:
        info = data['materials'][k]
        m = bpy.data.materials.new('before | ' + k)
        m.use_nodes = True
        rgb = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in info['color']]
        if info['texture'] != 'minecraft:block/white_concrete':
            rgb = [.35, .37, .38]
        next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled').inputs['Base Color'].default_value = (*rgb, 1)
        mats.append(m)
    verts, faces, index = [], [], []
    for f in data['faces']:
        faces.append(tuple(range(len(verts), len(verts) + 3)))
        verts += [xyz(v[:3]) for v in f['vertices']]
        index.append(names.index(f['material']))
    mesh = bpy.data.meshes.new('before ' + id)
    mesh.from_pydata(verts, [], faces)
    for m in mats:
        mesh.materials.append(m)
    for poly, i in zip(mesh.polygons, index):
        poly.material_index = i
    o = bpy.data.objects.new(mesh.name, mesh)
    s.collection.objects.link(o)
    return s


def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    out = argv[argv.index('--preview') + 1] if '--preview' in argv else None
    only = argv[argv.index('--only') + 1].split(',') if '--only' in argv else list(CONFIG)
    if '--before' in argv:
        src = Path(argv[argv.index('--before') + 1])
        for id in only:
            preview(json_scene(src / (id + '.json'), id), id, out, 'before')
        return
    scenes = []
    for id in only:
        scene = CONFIG[id][0]()
        export(scene, id)
        scenes.append(scene)
        if out:
            preview(scene, id, out)
    if '--no-save' not in argv:
        bpy.data.libraries.write(str(MODERN / 'motor_pumps.blend'), set(scenes), fake_user=True, compress=True, path_remap='RELATIVE_ALL')


if __name__ == '__main__':
    main()
