"""Generator circuit breaker (GCB) for the 1,300 MW unit: SF6 breaker of the ABB/Hitachi HEC 7/8 class, closed and open.

Headless only:
    python art/models/generator_breaker/make_textures.py
    blender --background --factory-startup --python art/models/generator_breaker/build_gcb.py [-- --preview DIR]

Writes the editable source art/models/generator_breaker/generator_breaker.blend (scenes 'BWR | GCB closed' and
'BWR | GCB open'), art/models/generator_breaker/gcb_connections.json (the six IPB connection points for the CEE bus
later) and, with --preview, studio pictures of both states. It does not write anything into mod/ and is not wired
into the game yet. Coordinates are Minecraft X/Y/Z, one unit is one block (1 m); footprint 5 (x, across the phases)
x 3 (y) x 7 (z, along the bus). Generator end is z = 0, transformer end z = 7; the control cubicle is on the west
(x = 0) long side near the generator end ("front").

Real reference (ABB Review special report "Pushing the limits of technology", HEC 9 single-line diagram and text; the
HEC 7/8 class is used at nuclear units up to ~1,600 MW, e.g. V.C. Summer): three-phase system in single-phase
enclosures, three separate parallel pole enclosures, one per phase, each in line with its IPB. HEC 7/8 poles sit on
a common base frame with ONE HMB-8 hydromechanical spring mechanism (HEC 9 gives each pole its own frame and
mechanism). Along each pole, generator end -> transformer end: connection zone -> voltage transformer -> surge
capacitor -> earthing switch -> current transformer -> interrupting chamber (SF6, fat central part) -> series
disconnector -> current transformer -> earthing switch -> surge capacitor -> surge arrester -> voltage transformer
-> connection zone. Plus a local control cubicle. Connection to the plant: CEE isolated-phase bus IPB-45 FA
(enclosure 1,150 mm, conductor tube 600 mm).

NOT found in sources, reasoned estimates (the owner may change them): overall footprint 5 x 7 x 3 m, phase pitch 1.5 m
(poles at x = 1.0, 2.5, 4.0), pole axis 2.0 m above the floor, enclosure diameter 0.9 m (chamber 1.25 m, IPB flange
1.3 m), section lengths, the spring-stack/shaft layout of the HMB-8, cubicle 0.8 x 0.5 x 1.8 m, every colour (light
grey RAL 7035-ish enclosures, darker grey frame, grey cubicle, brown porcelain arresters). The VT/capacitor/arrester
are top-mounted and the earthing switches side-mounted on the +x side to fit the 3 m height; real units differ in
arrangement. Original geometry; no vendor CAD, no vendor names or logos.

The two states differ only in the mechanical position indicator (CLOSED red "I", OPEN green "O"; US practice) and the
disconnector blade seen through each pole's viewing window (blade bridging the contacts / swung away).
"""
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/turbine_pumps/build_models.py'
exec(compile(helper.read_text(encoding='utf8').split('# ================================================================ reactor feed pump turbine')[0],
             str(helper), 'exec'))
HERE = ROOT / 'art/models/generator_breaker'
TEXD = HERE / 'textures'
SCENES = []


def new_scene(name):
    s = bpy.data.scenes.new(name)
    SCENES.append(s)
    return s


def mpaint(name, srgb, metal=0.0, rough=.5, alpha=1.0):
    m = material('GCB ' + name, tuple(lin(c) for c in srgb), metal)
    b = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    b.inputs['Roughness'].default_value = rough
    if alpha < 1:
        b.inputs['Alpha'].default_value = alpha
        try:
            m.blend_method = 'BLEND'
        except Exception:
            pass
    return m


def mtex(name, tex):
    m = bpy.data.materials.new('GCB | ' + name)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    node = m.node_tree.nodes.new('ShaderNodeTexImage')
    node.image = bpy.data.images.load(str(TEXD / (tex + '.png')), check_existing=True)
    node.image.pack()
    node.interpolation = 'Closest'
    m.node_tree.links.new(node.outputs['Color'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = .5
    m['tdp_texture'] = tex  # same custom property the modern exporter reads
    return m


# ---------------------------------------------------------------- materials (sRGB as seen in game)
ALU = mpaint('enclosure light grey RAL7035', (.79, .81, .80), .3, .45)
ALUF = mpaint('flange rings grey', (.62, .64, .64), .4, .4)
ALUD = mpaint('cast housing darker grey', (.52, .55, .55), .4, .45)
CTB = mpaint('current transformer housing', (.25, .27, .28), .3, .5)
FRAMEM = mpaint('base frame dark grey', (.26, .28, .29), .3, .55)
CUBM = mpaint('cubicle grey', (.58, .60, .60), .2, .5)
DOORM = mpaint('cubicle door grey', (.64, .66, .65), .2, .45)
PORC = mpaint('arrester porcelain brown', (.38, .21, .12), .0, .15)
CAPP = mpaint('capacitor porcelain light', (.68, .62, .50), .0, .2)
BRIGHT = mpaint('bright aluminium conductor', (.82, .84, .86), .85, .25)
BRASSM = mpaint('contact brass', (.72, .56, .25), .8, .3)
SILV = mpaint('disconnector blade silver', (.90, .92, .93), .9, .2)
RUBB = mpaint('bellows rubber black', (.045, .045, .05), .0, .85)
LINER = mpaint('enclosure liner dark', (.08, .09, .10), .1, .7)
GLASS = mpaint('viewing glass', (.62, .78, .84), .0, .05, alpha=.18)
EARTH = mpaint('earthing green yellow', (.60, .70, .10), .0, .4)
MECHM = mpaint('mechanism cabinet blue grey', (.40, .48, .55), .3, .45)
SPRM = mpaint('spring stack steel', (.30, .33, .36), .6, .35)
SHAFTM2 = mpaint('operating shaft steel', (.72, .74, .75), .85, .25)
BTN_R = mpaint('pushbutton red', (.75, .10, .08), .0, .3)
BTN_G = mpaint('pushbutton green', (.10, .55, .20), .0, .3)
HANDLEM = mpaint('door handle steel', (.55, .57, .58), .8, .3)
NUTM = mpaint('dark fasteners', (.20, .21, .22), .6, .4)
YELM = mpaint('pointer yellow', (.92, .70, .10), .0, .4)
PL_PLATE = mtex('breaker nameplate', 'gcb_plate')
PL_HMB = mtex('mechanism nameplate', 'gcb_plate_hmb')
HAZ = mtex('warning label', 'gcb_hazard')
SF6L = mtex('SF6 gas label', 'gcb_label_sf6')
MPI = {True: mtex('position indicator closed', 'gcb_mpi_closed'), False: mtex('position indicator open', 'gcb_mpi_open')}

_cylinder = cylinder


def cylinder(s, name, a, b, r, mat, n=None):
    """Capped cylinder; the segment count follows the radius unless given (keeps small parts cheap)."""
    return _cylinder(s, name, a, b, r, mat, n if n and n >= 16 else (n or (8 if r < .06 else 12 if r < .15 else 16)))


# ---------------------------------------------------------------- tunables
AY = 2.0                     # pole axis height
POLES = {'A': 1.0, 'B': 2.5, 'C': 4.0}
R_TUBE, R_FL, R_CH = .45, .53, .625
Z_GEN, Z_XF = 0.0, 7.0
SEG = 24                     # radial segments of the big round parts
NBOLT = 4                    # flange bolts drawn per section flange


# ---------------------------------------------------------------- primitives
def lathe(s, name, a, axis, profile, mat, n=24, caps=True):
    """Body of revolution: profile is [(t, r)] along axis from a."""
    a = V(a)
    d, u, v = basis(axis)
    vs = []
    for t, r in profile:
        vs.extend(tuple(a + d * t + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for i in range(n))
    k = len(profile)
    fs = [(j * n + i, j * n + (i + 1) % n, (j + 1) * n + (i + 1) % n, (j + 1) * n + i) for j in range(k - 1) for i in range(n)]
    if caps:
        fs += [tuple(range(n - 1, -1, -1)), tuple(range((k - 1) * n, k * n))]
    return mesh_object(s, name, vs, fs, mat)


def helix(s, name, a, axis, coil_r, wire_r, length, turns, mat, steps=8, sides=5):
    a = V(a)
    d, u, v = basis(axis)
    total = int(turns * steps)
    pts = [a + d * (length * j / total) + coil_r * (math.cos(j * math.tau / steps) * u + math.sin(j * math.tau / steps) * v)
           for j in range(total + 1)]
    return sweep(s, name, pts, wire_r, mat, sides)


def arc_strip(s, name, xp, z0, z1, r, a0, a1, mat, n=28, y=AY):
    """Open strip of a cylinder around the z axis through (xp, y), from angle a0 to a1 (degrees, 0 = +x, 90 = up)."""
    k = max(2, int(abs(a1 - a0) / 360 * n))
    vs = []
    for z in (z0, z1):
        for i in range(k + 1):
            t = math.radians(a0 + (a1 - a0) * i / k)
            vs.append((xp + r * math.cos(t), y + r * math.sin(t), z))
    fs = [(i, i + 1, k + 1 + i + 1, k + 1 + i) for i in range(k)]
    return mesh_object(s, name, vs, fs, mat)


def ibeam_profile(cu, cw, bf, hh, tf, tw):
    """I section centred at (cu, cw): flange width bf, depth hh, flange thickness tf, web tw."""
    a, b = bf / 2, hh / 2
    pts = [(-a, -b), (a, -b), (a, -b + tf), (tw / 2, -b + tf), (tw / 2, b - tf), (a, b - tf), (a, b), (-a, b), (-a, b - tf),
           (-tw / 2, b - tf), (-tw / 2, -b + tf), (-a, -b + tf)]
    return [(cu + p, cw + q) for p, q in pts]


def beam_z(s, name, x, y, z0, z1, bf=.3, hh=.15):
    return prism(s, name, 'z', z0, z1, ibeam_profile(x, y, bf, hh, .025, .02), FRAMEM)


def beam_x(s, name, z, y, x0, x1, bf=.2, hh=.15):
    return prism(s, name, 'x', x0, x1, ibeam_profile(z, y, bf, hh, .025, .02), FRAMEM)


def leg(s, name, x, z, y0, y1, bf=.14, hh=.14):
    return prism(s, name, 'y', y0, y1, ibeam_profile(x, z, bf, hh, .02, .02), FRAMEM)


def stud(s, a, b, r, mat=None):
    """Low-poly square bolt head: four sides and the exposed end only."""
    a, b = V(a), V(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + r * (math.cos(i * math.tau / 4 + .785) * u + math.sin(i * math.tau / 4 + .785) * v)) for p in (a, b) for i in range(4)]
    return mesh_object(s, 'fastener', vs, [(4, 5, 6, 7)] + [(i, (i + 1) % 4, (i + 1) % 4 + 4, i + 4) for i in range(4)], mat or NUTM)


def bolted_ring(s, name, xp, z, r_out, width, mat, bolts=NBOLT, n=20, bolt_r=None, side=1):
    """Round flange ring around the pole axis at z; hex bolt heads on the +z (side=1) or -z face."""
    cylinder(s, name, (xp, AY, z - width / 2), (xp, AY, z + width / 2), r_out, mat, n)
    br = bolt_r if bolt_r else r_out - .045
    for i in range(bolts):
        t = (i + .5) * math.tau / bolts
        q = (xp + br * math.cos(t), AY + br * math.sin(t))
        z0 = z + side * width / 2
        stud(s, (q[0], q[1], z0 - side * .004), (q[0], q[1], z0 + side * .03), .024)


# ---------------------------------------------------------------- pole
def top_voltage_transformer(s, xp, zc, tag):
    lathe(s, 'voltage transformer ' + tag, (xp, AY + .40, zc), (0, 1, 0),
          [(0, .26), (.04, .26), (.04, .20), (.44, .20), (.50, .15), (.55, .09), (.56, .05)], ALUD, 10)
    cylinder(s, 'VT terminal ' + tag, (xp, AY + .95, zc), (xp, AY + .985, zc), .045, BRIGHT, 8)


def top_capacitor(s, xp, zc, tag):
    prof = [(0, .20), (.04, .20), (.04, .12)]
    for k in range(2):
        t = .08 + k * .2
        prof += [(t, .12), (t + .01, .165), (t + .17, .165), (t + .19, .12)]
    prof += [(.5, .12), (.5, .08)]
    lathe(s, 'surge capacitor ' + tag, (xp, AY + .40, zc), (0, 1, 0), prof, CAPP, 8)
    cylinder(s, 'capacitor cap ' + tag, (xp, AY + .90, zc), (xp, AY + .96, zc), .09, BRIGHT, 12)


def top_arrester(s, xp, zc):
    prof = [(0, .20), (.04, .20), (.04, .10)]
    for k in range(5):
        t = .06 + k * .09
        prof += [(t, .10), (t + .008, .17), (t + .085, .12)]
    prof += [(.51, .10), (.51, .06)]
    lathe(s, 'surge arrester', (xp, AY + .40, zc), (0, 1, 0), prof, PORC, 8)
    cylinder(s, 'arrester cap', (xp, AY + .91, zc), (xp, AY + .96, zc), .10, BRIGHT, 12)
    cylinder(s, 'arrester terminal', (xp, AY + .96, zc), (xp, AY + .985, zc), .035, BRIGHT, 8)


def side_earthing_switch(s, xp, zc, tag):
    box(s, 'earthing switch housing ' + tag, (xp + .38, AY - .2, zc - .2), (xp + .72, AY + .2, zc + .2), ALUD)
    box(s, 'earthing switch collar ' + tag, (xp + .30, AY - .13, zc - .13), (xp + .38, AY + .13, zc + .13), ALUF)
    cylinder(s, 'earthing switch drive ' + tag, (xp + .72, AY, zc), (xp + .80, AY, zc), .06, CTB, 12)
    box(s, 'earthing switch pointer ' + tag, (xp + .80, AY - .012, zc - .06), (xp + .83, AY + .012, zc + .06), YELM)
    box(s, 'earthing terminal ' + tag, (xp + .50, AY - .26, zc - .05), (xp + .56, AY - .2, zc + .05), EARTH)


def current_transformer(s, xp, z0, z1, tag):
    cylinder(s, 'current transformer ' + tag, (xp, AY, z0 + .04), (xp, AY, z1 - .04), .535, CTB, 20)
    box(s, 'CT terminal box ' + tag, (xp + .30, AY + .46, (z0 + z1) / 2 - .09), (xp + .50, AY + .60, (z0 + z1) / 2 + .09), ALUD)


def connection_zone(s, xp, end):
    """IPB connection point: bellows ring, pole flange with bolt circle, insulator, bright conductor tube stub."""
    z = Z_GEN if end == 'gen' else Z_XF
    dz = 1 if end == 'gen' else -1
    a = (xp, AY, z)
    ax = (0, 0, dz)
    prof = [(0, .44), (0, .50)]
    for k in range(3):
        prof += [(.02 + k * .04, .57), (.04 + k * .04, .50)]
    prof += [(.12, .50), (.12, .44), (0, .44)]
    lathe(s, 'IPB bellows ' + end, a, ax, prof, RUBB, 14, caps=False)
    sleeve(s, 'IPB pole flange ' + end, (xp, AY, z + dz * .12), (xp, AY, z + dz * .20), .66, .40, ALUF, 16)
    for i in range(8):
        t = (i + .5) * math.tau / 8
        q = (xp + .60 * math.cos(t), AY + .60 * math.sin(t))
        stud(s, (q[0], q[1], z + dz * .125), (q[0], q[1], z + dz * .06), .026)
    cylinder(s, 'epoxy insulator ' + end, (xp, AY, z + dz * .15), (xp, AY, z + dz * .19), .40, LINER, 16)
    sleeve(s, 'conductor tube stub ' + end, (xp, AY, z), (xp, AY, z + dz * .28), .30, .27, BRIGHT, 16)


def disconnector(s, xp, z0, z1, opened, tag):
    """Series disconnector section with a viewing window on the -x (front) side; blade visible through it."""
    half = 24
    arc_strip(s, 'disconnector enclosure', xp, z0 + .035, z1 - .035, R_TUBE, 180 + half, 540 - half, ALU, 28)
    arc_strip(s, 'disconnector liner', xp, z0 + .035, z1 - .035, R_TUBE - .02, 180 + half, 540 - half, LINER, 28)
    arc_strip(s, 'viewing glass', xp, z0 + .06, z1 - .06, R_TUBE + .018, 180 - half + 3, 180 + half - 3, GLASS, 8)
    for a in (180 - half, 180 + half):
        t = math.radians(a)
        p = (xp + (R_TUBE + .012) * math.cos(t), AY + (R_TUBE + .012) * math.sin(t))
        obox(s, 'window frame ' + tag, (p[0], p[1], z0 + .035), (p[0], p[1], z1 - .035), .03, .035, (math.cos(t), math.sin(t), 0), ALUF)
    pts = [(xp + (R_TUBE + .012) * math.cos(math.radians(a)), AY + (R_TUBE + .012) * math.sin(math.radians(a))) for a in (180 - half, 180 + half)]
    for z in (z0 + .05, z1 - .05):
        obox(s, 'window frame end ' + tag, (pts[0][0], pts[0][1], z), (pts[1][0], pts[1][1], z), .03, .03, (-1, 0, 0), ALUF)
    # contacts on the axis inside: fixed contact at the generator side, hinged blade on the transformer side
    za, zb = z0 + .16, z1 - .16
    cylinder(s, 'fixed contact ' + tag, (xp, AY, z0 + .05), (xp, AY, za), .07, BRASSM, 14)
    cylinder(s, 'contact tulip ' + tag, (xp, AY, za), (xp, AY, za + .05), .05, BRASSM, 12)
    cylinder(s, 'hinge terminal ' + tag, (xp, AY, zb), (xp, AY, z1 - .05), .075, BRASSM, 14)
    cylinder(s, 'hinge pin ' + tag, (xp - .07, AY, zb), (xp + .07, AY, zb), .035, BRASSM, 10)
    if not opened:
        obox(s, 'disconnector blade ' + tag, (xp, AY, za + .02), (xp, AY, zb), .05, .075, (0, 1, 0), SILV)
    else:
        L = zb - za - .02
        ang = math.radians(42)
        tip = (xp, AY + L * math.sin(ang), zb - L * math.cos(ang))
        obox(s, 'disconnector blade ' + tag, tip, (xp, AY, zb), .05, .075, (0, 1, 0), SILV)
    cylinder(s, 'disconnector drive ' + tag, (xp + R_TUBE, AY, (z0 + z1) / 2), (xp + R_TUBE + .10, AY, (z0 + z1) / 2), .05, CTB, 12)


def interrupter(s, xp, z0, z1):
    """SF6 interrupting chamber: the fat central part, with bell ends and bolted unit joints."""
    prof = [(z0, R_TUBE), (z0 + .06, R_TUBE + .05), (z0 + .17, R_CH - .01), (z0 + .35, R_CH), (z1 - .35, R_CH),
            (z1 - .17, R_CH - .01), (z1 - .06, R_TUBE + .05), (z1, R_TUBE)]
    lathe(s, 'interrupting chamber', (xp, AY, 0), (0, 0, 1), prof, ALU, 20)
    for z in (z0 + .24, (z0 + z1) / 2, z1 - .24):
        bolted_ring(s, 'chamber joint', xp, z, R_CH + .07, .09, ALUF, 8, 16, R_CH + .035)
    # SF6 density monitor on the front (-x) side, with its gas label
    cylinder(s, 'density monitor stub', (xp - R_CH + .02, AY + .15, 3.35), (xp - R_CH - .12, AY + .15, 3.35), .04, ALUD, 10)
    gauge(s, (xp - R_CH - .15, AY + .15, 3.35), (-1, 0, 0), .07)
    plate(s, 'SF6 label', (xp - R_CH + .005, AY - .15, 3.35), (-1, 0, 0), (0, 1, 0), .20, .10, SF6L, FRAMEM, .014)


def pole(s, phase, xp, opened):
    for end in ('gen', 'xfmr'):
        connection_zone(s, xp, end)
    cylinder(s, 'enclosure generator side', (xp, AY, .2), (xp, AY, 2.15), R_TUBE, ALU, 20)
    cylinder(s, 'enclosure transformer side', (xp, AY, 4.45), (xp, AY, 6.8), R_TUBE, ALU, 20)
    interrupter(s, xp, 2.15, 3.75)
    disconnector(s, xp, 3.75, 4.45, opened, phase)
    for z in (.8, 1.25, 1.75, 2.15, 3.75, 4.45, 4.85, 5.35, 5.75, 6.25):
        if z in (2.15, 3.75):
            bolted_ring(s, 'section flange', xp, z, R_FL, .07, ALUF, NBOLT, 16, side=1 if z == 3.75 else -1)
        else:
            bolted_ring(s, 'section flange', xp, z, R_FL, .07, ALUF, NBOLT, 16)
    # generator side (z .2 -> 2.15)
    top_voltage_transformer(s, xp, .5, phase + 'g')
    top_capacitor(s, xp, 1.025, phase + 'g')
    side_earthing_switch(s, xp, 1.5, phase + 'g')
    current_transformer(s, xp, 1.75, 2.15, phase + 'g')
    # transformer side (z 4.45 -> 6.8)
    current_transformer(s, xp, 4.45, 4.85, phase + 'x')
    side_earthing_switch(s, xp, 5.1, phase + 'x')
    top_capacitor(s, xp, 5.55, phase + 'x')
    top_arrester(s, xp, 6.0)
    top_voltage_transformer(s, xp, 6.525, phase + 'x')
    plate(s, 'pole warning label', (xp - R_CH + .003, AY + .25, 2.75), (-1, 0, 0), (0, 1, 0), .24, .12, HAZ, FRAMEM, .014)
    # crank housing under the chamber, driven from the operating shaft
    box(s, 'crank housing', (xp - .18, 1.15, 2.88), (xp + .38, 1.42, 3.32), ALUD)
    cylinder(s, 'crank housing end cover', (xp + .38, 1.285, 3.1), (xp + .43, 1.285, 3.1), .12, ALUF, 14)


# ---------------------------------------------------------------- frame, mechanism, cubicle
CROSS = [1.0, 2.3, 3.6, 5.0, 6.3]
LEGX = [.65, 1.75, 3.25, 4.35]


def saddle(s, xp, z, r):
    c = r * math.cos(math.radians(30))
    prof = [(xp + c, 1.15)] + [(xp + r * math.cos(math.radians(a)), AY + r * math.sin(math.radians(a))) for a in range(-30, -151, -30)] + [(xp - c, 1.15)]
    prism(s, 'pole saddle', 'z', z - .04, z + .04, prof, FRAMEM)


def base_frame(s):
    for ph, xp in POLES.items():
        beam_z(s, 'frame longitudinal beam ' + ph, xp, 1.075, .6, 6.7)
    for z in CROSS:
        beam_x(s, 'frame cross beam', z, 1.045, .55, 4.45)
        for x in LEGX:
            leg(s, 'frame leg', x, z, 0, 1.0)
    for ph, xp in POLES.items():
        for z, r in ((1.0, .45), (2.3, .60), (3.6, .60), (5.0, .45), (6.3, .45)):
            saddle(s, xp, z, r)
    # earthing bar along the last cross beam with a drop on the front leg, lug at the floor
    obox(s, 'earthing bar', (.55, 1.0, 6.3 + .105), (4.45, 1.0, 6.3 + .105), .02, .04, (0, 1, 0), EARTH)
    obox(s, 'earthing bar drop', (.65, 1.0, 6.3 + .08), (.65, .08, 6.3 + .08), .02, .04, (0, 0, 1), EARTH)
    box(s, 'earthing lug', (.58, .05, 6.3 + .07), (.72, .11, 6.3 + .10), EARTH)


def mechanism(s, opened):
    """HMB-8 style hydromechanical spring mechanism under the middle pole, with the operating shaft across all poles."""
    xb = POLES['B']
    box(s, 'mechanism cabinet', (xb - .5, .08, 2.75), (xb + .5, .92, 3.45), MECHM)
    box(s, 'mechanism cabinet top', (xb - .52, .92, 2.73), (xb + .52, .95, 3.47), ALUD)
    box(s, 'mechanism plinth', (xb - .52, .02, 2.73), (xb + .52, .08, 3.47), FRAMEM)
    # spring stack: housing and exposed coil, running toward the transformer end
    cylinder(s, 'spring stack housing', (xb, .5, 3.45), (xb, .5, 3.95), .21, SPRM, 18)
    for z in (3.6, 3.78, 3.92):
        cylinder(s, 'spring stack band', (xb, .5, z - .012), (xb, .5, z + .012), .225, ALUD, 18)
    helix(s, 'closing spring', (xb, .5, 3.95), (0, 0, 1), .17, .028, .22, 4, SPRM, 10, 5)
    cylinder(s, 'spring stack end plate', (xb, .5, 4.17), (xb, .5, 4.21), .22, ALUD, 18)
    cylinder(s, 'spring guide rod', (xb, .5, 4.2), (xb, .5, 4.3), .03, SHAFTM2, 8)
    plate(s, 'mechanism nameplate', (xb - .5, .62, 3.10), (-1, 0, 0), (0, 1, 0), .30, .15, PL_HMB, FRAMEM, .014)
    gauge(s, (xb - .5, .33, 3.28), (-1, 0, 0), .06)
    plate(s, 'mechanism position indicator', (xb - .5, .33, 2.95), (-1, 0, 0), (0, 1, 0), .11, .11, MPI[not opened], FRAMEM, .014)
    cylinder(s, 'manual charge handle', (xb - .5, .8, 3.30), (xb - .6, .8, 3.30), .015, HANDLEM, 8)
    cylinder(s, 'manual charge handle grip', (xb - .6, .8, 3.30), (xb - .6, .8, 3.40), .02, BTN_R, 8)
    # operating shaft across under all three poles, bearing blocks, cranks and link rods up to each pole's crank housing
    cylinder(s, 'operating shaft', (.55, .62, 3.1), (4.45, .62, 3.1), .035, SHAFTM2, 10)
    for xp in POLES.values():
        dx = -.3
        box(s, 'shaft bearing block', (xp + dx - .07, .02, 3.03), (xp + dx + .07, .52, 3.17), FRAMEM)
        cylinder(s, 'shaft bearing', (xp + dx - .05, .62, 3.1), (xp + dx + .05, .62, 3.1), .07, ALUD, 10)
        obox(s, 'crank arm', (xp + .25, .62, 3.1), (xp + .25, .62, 3.22), .05, .06, (0, 1, 0), SHAFTM2)
        cylinder(s, 'crank link rod', (xp + .25, .62, 3.22), (xp + .25, 1.16, 3.22), .02, SHAFTM2, 8)
        cylinder(s, 'crank collar', (xp + .20, .62, 3.1), (xp + .30, .62, 3.1), .05, SHAFTM2, 8)
    for x in (.62, 4.38):
        box(s, 'shaft end bearing block', (x - .07, .02, 3.03), (x + .07, .52, 3.17), FRAMEM)
        cylinder(s, 'shaft end bearing', (x - .05, .62, 3.1), (x + .05, .62, 3.1), .07, ALUD, 10)
    pipe(s, 'control cable trunk', [(.30, .12, 1.25), (1.9, .12, 1.25), (1.9, .12, 2.9), (xb - .45, .12, 2.9)], .03, FRAMEM, 8)


def cubicle(s, opened):
    """Local control cubicle on the west long side near the generator end: 0.8 wide (z) x 0.5 deep x 1.8 tall."""
    z0, z1, zc = .85, 1.65, 1.25
    box(s, 'cubicle plinth', (.0, .0, z0 - .02), (.52, .10, z1 + .02), FRAMEM)
    box(s, 'cubicle body', (.05, .10, z0), (.50, 1.90, z1), CUBM)
    box(s, 'cubicle roof', (.0, 1.90, z0 - .02), (.52, 1.95, z1 + .02), ALUD)
    box(s, 'cubicle door', (.02, .16, z0 + .03), (.05, 1.84, z1 - .03), DOORM)
    for y in (.30, 1.20, 1.70):
        cylinder(s, 'door hinge', (.03, y, z0 + .035), (.03, y + .12, z0 + .035), .018, FRAMEM, 8)
    box(s, 'door handle base', (.0, 1.01, z1 - .10), (.02, 1.35, z1 - .08), HANDLEM)
    plate(s, 'cubicle nameplate', (.02, 1.70, zc), (-1, 0, 0), (0, 1, 0), .30, .15, PL_PLATE, FRAMEM, .014)
    # mechanical position indicator window
    cy = 1.42
    for lo, hi in (((.0, cy + .10, zc - .13), (.02, cy + .13, zc + .13)), ((.0, cy - .13, zc - .13), (.02, cy - .10, zc + .13)),
                   ((.0, cy - .10, zc - .13), (.02, cy + .10, zc - .10)), ((.0, cy - .10, zc + .10), (.02, cy + .10, zc + .13))):
        box(s, 'MPI bezel', lo, hi, FRAMEM)
    plate(s, 'mechanical position indicator', (.02, cy, zc), (-1, 0, 0), (0, 1, 0), .20, .20, MPI[not opened], FRAMEM, .004)
    box(s, 'MPI glass', (.0, cy - .10, zc - .10), (.005, cy + .10, zc + .10), GLASS)
    # control switch and pushbuttons
    for z, m in ((zc - .12, BTN_G), (zc + .12, BTN_R)):
        cylinder(s, 'pushbutton', (.02, 1.12, z), (.0, 1.12, z), .03, m, 12)
    cylinder(s, 'selector switch', (.02, 1.12, zc), (.0, 1.12, zc), .035, FRAMEM, 12)
    plate(s, 'cubicle warning label', (.02, .86, zc), (-1, 0, 0), (0, 1, 0), .24, .12, HAZ, FRAMEM, .014)
    for k in range(5):
        y = .30 + k * .045
        box(s, 'vent louvre', (.0, y, z0 + .12), (.02, y + .02, z1 - .12), FRAMEM)


def gcb(opened):
    s = new_scene('BWR | GCB ' + ('open' if opened else 'closed'))
    for ph, xp in POLES.items():
        pole(s, ph, xp, opened)
    base_frame(s)
    mechanism(s, opened)
    cubicle(s, opened)
    return s


def connections():
    return [{'phase': ph, 'end': end, 'x': xp, 'y': AY, 'z': z}
            for ph, xp in POLES.items() for end, z in (('gen', Z_GEN), ('xfmr', Z_XF))]


def triangles(s):
    return sum(len(p.vertices) - 2 for o in s.objects if o.type == 'MESH' for p in o.data.polygons)


def check_bounds(s):
    lo, hi = [9e9] * 3, [-9e9] * 3
    for o in s.objects:
        if o.type == 'MESH':
            for v in o.data.vertices:
                g = game(o.matrix_world @ v.co)
                for a in range(3):
                    lo[a], hi[a] = min(lo[a], g[a]), max(hi[a], g[a])
    return lo, hi


# ---------------------------------------------------------------- preview rig (added after the .blend is saved)
def figure(s, x, z):
    """Simple 1.8 m tall human-scale reference figure."""
    skin = mpaint('figure skin', (.80, .62, .50), 0, .6)
    vest = mpaint('figure vest', (.95, .45, .08), 0, .6)
    pants = mpaint('figure trousers', (.15, .20, .32), 0, .7)
    hat = mpaint('figure hard hat', (.95, .95, .92), 0, .4)
    boots = mpaint('figure boots', (.08, .08, .08), 0, .7)
    for dz in (-.09, .09):
        box(s, 'fig leg', (x - .07, .08, z + dz - .07), (x + .07, .86, z + dz + .07), pants)
        box(s, 'fig boot', (x - .07, 0, z + dz - .07), (x + .15, .08, z + dz + .07), boots)
    box(s, 'fig torso', (x - .12, .86, z - .25), (x + .12, 1.50, z + .25), vest)
    for dz in (-.31, .31):
        box(s, 'fig arm', (x - .06, .88, z + dz - .06), (x + .06, 1.46, z + dz + .06), vest)
        ellipsoid(s, 'fig hand', (x, .84, z + dz), .06, .06, .06, skin, 8, 4)
    cylinder(s, 'fig neck', (x, 1.50, z), (x, 1.58, z), .06, skin, 8)
    ellipsoid(s, 'fig head', (x, 1.68, z), .10, .12, .10, skin, 12, 6)
    ellipsoid(s, 'fig hard hat', (x, 1.74, z), .115, .09, .115, hat, 12, 5, 0, 90)


def smooth_all(s, angle=38):
    """Preview only: smooth shading with sharp edges above `angle` degrees (the .blend stays flat like the game mesh)."""
    import bmesh
    for o in s.objects:
        if o.type != 'MESH' or o.name.startswith(('floor', 'footprint', 'conductor tube', 'IPB', 'epoxy')):
            continue
        bm = bmesh.new()
        bm.from_mesh(o.data)
        for e in bm.edges:
            e.smooth = not (e.is_boundary or (len(e.link_faces) == 2 and e.calc_face_angle(0) > math.radians(angle)))
        for f in bm.faces:
            f.smooth = True
        bm.to_mesh(o.data)
        bm.free()


def rig(s, tag):
    # floor: 1 m checker (one block per square) plus the 5 x 7 footprint outline
    fm = bpy.data.materials.new('floor checker')
    fm.use_nodes = True
    nt = fm.node_tree
    bsdf = next(n for n in nt.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    chk = nt.nodes.new('ShaderNodeTexChecker')
    chk.inputs['Scale'].default_value = 1.0
    chk.inputs['Color1'].default_value = (.46, .47, .48, 1)
    chk.inputs['Color2'].default_value = (.40, .41, .42, 1)
    tc = nt.nodes.new('ShaderNodeTexCoord')
    nt.links.new(tc.outputs['Object'], chk.inputs['Vector'])
    nt.links.new(chk.outputs['Color'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = .9
    mesh = bpy.data.meshes.new('floor')
    mesh.from_pydata([xyz(p) for p in [(-9, -.01, -9), (14, -.01, -9), (14, -.01, 16), (-9, -.01, 16)]], [], [(0, 3, 2, 1)])
    mesh.materials.append(fm)
    s.collection.objects.link(bpy.data.objects.new('floor', mesh))
    outline = mpaint('footprint outline', (.80, .15, .10), 0, .8)
    for a, b in (((0, 0), (5, 0)), ((5, 0), (5, 7)), ((5, 7), (0, 7)), ((0, 7), (0, 0))):
        obox(s, 'footprint outline', (a[0], .004, a[1]), (b[0], .004, b[1]), .04, .01, (0, 1, 0), outline)
    figure(s, -.55, 2.4)
    world = bpy.data.worlds.new('studio ' + tag)
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.60, .62, .64, 1)
    bg.inputs['Strength'].default_value = .85
    s.world = world
    for nm, loc, energy, ang in (('key', (-6, 8, -9), 3.0, 8), ('fill', (12, 10, 14), 1.2, 25)):
        ld = bpy.data.lights.new(nm, 'SUN')
        ld.energy = energy
        ld.angle = math.radians(ang)
        lo = bpy.data.objects.new(nm, ld)
        s.collection.objects.link(lo)
        lo.rotation_euler = (xyz((2.5, 0, 3.5)) - xyz(loc)).to_track_quat('-Z', 'Y').to_euler()
        lo.location = xyz(loc)


def set_engine(s):
    for engine in ('BLENDER_EEVEE', 'BLENDER_EEVEE_NEXT', 'CYCLES'):
        try:
            s.render.engine = engine
            break
        except TypeError:
            continue
    if s.render.engine == 'CYCLES':
        s.cycles.samples = 48
        s.cycles.use_denoising = True
    else:
        try:
            s.eevee.taa_render_samples = 48
        except Exception:
            pass
    s.view_settings.view_transform = 'Standard'
    s.render.image_settings.file_format = 'PNG'
    s.render.film_transparent = False


VIEWS = {
    # name: (eye, target, lens, (w, h), figure?)  all in Minecraft X/Y/Z
    '1-iso-front': ((-5.0, 3.4, -2.0), (2.4, .9, 3.0), 30, (1600, 1000), True),
    '2-iso-rear': ((9.2, 3.8, 10.0), (2.5, .9, 3.5), 32, (1600, 1000), False),
    '3-side-along-bus': ((-9.5, 2.2, 3.5), (2.5, 1.45, 3.5), 38, (1800, 800), False),
    '4-end-flanges': ((2.5, 2.2, -9.5), (2.5, 1.5, 0.0), 55, (1600, 1000), False),
    '5-cubicle-indicator': ((-1.7, 1.55, 0.55), (0.0, 1.55, 1.25), 32, (1200, 1000), False),
    '6-disconnector-window': ((-2.4, 2.1, 3.9), (0.4, 2.0, 4.1), 40, (1400, 1000), False),
}


def render_previews(out_dir, only=None):
    out_dir.mkdir(parents=True, exist_ok=True)
    for s in SCENES:
        state = 'open' if s.name.endswith('open') else 'closed'
        smooth_all(s)
        rig(s, state)
        fig = [o for o in s.objects if o.name.startswith('fig ')]
        cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
        s.collection.objects.link(cam)
        s.camera = cam
        set_engine(s)
        for name, (eye, target, lens, res, show_fig) in VIEWS.items():
            if only and not any(name.startswith(k) for k in only):
                continue
            for o in fig:
                o.hide_render = not show_fig
            cam.data.lens = lens
            cam.data.clip_end = 100
            cam.location = xyz(eye)
            cam.rotation_euler = (xyz(target) - xyz(eye)).to_track_quat('-Z', 'Y').to_euler()
            s.render.resolution_x, s.render.resolution_y = res
            s.render.resolution_percentage = 100
            s.render.filepath = str(out_dir / f'gcb-{state}-{name}.png')
            bpy.ops.render.render(write_still=True, scene=s.name)
            print('PREVIEW', s.render.filepath)


if __name__ == '__main__':
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    gcb(False)
    gcb(True)
    (HERE / 'gcb_connections.json').write_text(json.dumps({
        'description': 'IPB-45 FA connection points of the generator breaker, in block units of the 5 x 7 x 3 box '
                       '(x across the phases, y up, z along the bus; generator end z = 0, transformer end z = 7).',
        'box': [5, 3, 7], 'connections': connections()}, indent=2) + '\n', encoding='utf-8')
    for sc in SCENES:
        sc['description'] = ('Original reference-based model (ABB/Hitachi HEC 7/8 class SF6 generator breaker); '
                             'one Blender unit is one block. Sizes and colours are estimates.')
        lo, hi = check_bounds(sc)
        print('GCB', sc.name, triangles(sc), 'triangles', 'objects', len(sc.objects), 'bounds', [round(x, 3) for x in lo + hi])
        assert all(lo[a] >= -1e-6 for a in range(3)) and hi[0] <= 5 + 1e-6 and hi[1] <= 3 + 1e-6 and hi[2] <= 7 + 1e-6, 'outside 5x3x7'
    for sc in list(bpy.data.scenes):
        if sc not in SCENES:
            bpy.data.scenes.remove(sc)
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(HERE / 'generator_breaker.blend'), compress=True)
    print('GCB BUILT', [(sc.name, len(sc.objects)) for sc in SCENES])
    if '--preview' in argv:
        only = argv[argv.index('--only') + 1].split(',') if '--only' in argv else None
        render_previews(Path(argv[argv.index('--preview') + 1]).resolve(), only)
