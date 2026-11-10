"""Generator circuit breaker (GCB) for the 1,300 MW unit: SF6 breaker of the ABB HEC 7/8 class, closed and open.

Headless only:
    python art/models/generator_breaker/make_textures.py
    blender --background --factory-startup --python art/models/generator_breaker/build_gcb.py [-- --preview DIR]

Writes the editable source art/models/generator_breaker/generator_breaker.blend (scenes 'BWR | GCB closed' and
'BWR | GCB open'), art/models/generator_breaker/gcb_connections.json (the six IPB connection points for the CEE bus),
the mesh snapshots generator_breaker.json / generator_breaker_open.json that tools-export-gcb.py bakes, and, with
--preview, studio pictures of both states. Coordinates are Minecraft X/Y/Z, one unit is one block (1 m); footprint 6 (x,
across the phases) x 3 (y) x 4 (z, along the bus). Generator end is z = 0, transformer end z = 4 (the flange tips); the
control cubicle is on the west (x = 0) side of the frame near the generator end ("front").

SOURCED (ABB brochure 1HC0070730 E02, HEC 7/8 dimension drawings pp. 46-47, checked by the CEE session):
  - phase distance N 1,650-2,100 mm (HEC 7), 1,850-2,100 mm (HEC 8): 2.0 m pitch, poles at x = 1.0, 3.0, 5.0;
  - pole enclosure length along the bus 3,260 mm (4,000 mm with erection allowance): 3.3 m between the flange zones
    (z 0.35 to 3.65), flange tips at z = 0 and z = 4;
  - bus centreline height 1,600-1,750 mm: pole axis 1.7 m above the floor;
  - overall height at least 2,900 mm (HEC 7): the pressure relief hoods on the chambers reach 2.9 m;
  - pole-end diameter 1,250 mm: the interrupting chamber (and the CT) are 1.25 m across; the IPB flange stays 1.3 m;
  - the control cubicle sits on the side of the frame.

Real reference for the arrangement (ABB Review special report "Pushing the limits of technology", HEC 9 single-line diagram and
text; the HEC 7/8 class is used at nuclear units up to ~1,600 MW, e.g. V.C. Summer): three-phase system in single-phase
enclosures, three separate parallel pole enclosures, one per phase, each in line with its IPB. HEC 7/8 poles sit on a common
base frame with ONE HMB-8 hydromechanical spring mechanism (HEC 9 gives each pole its own frame and mechanism). Real order along
each pole, generator end -> transformer end: connection zone -> voltage transformer -> surge capacitor -> earthing switch ->
current transformer -> interrupting chamber (SF6, fat central part) -> series disconnector -> current transformer -> earthing
switch -> surge capacitor -> surge arrester -> voltage transformer -> connection zone. Plus a local control cubicle. Connection
to the plant: CEE isolated-phase bus IPB-45 FA (enclosure 1,150 mm, conductor tube 600 mm).

COMPRESSED to fit 4 m (not sourced, reasoned): the voltage transformers, surge capacitors and surge arrester are grounded bolted
metal compartments hanging under each pole, two side by side at each end (a metal-enclosed GCB has no exposed insulators); the
earthing switch drives are small metal boxes on the +x side. Section lengths, enclosure diameter 0.9 m (flange rings 1.06 m), the
spring-stack/shaft layout of the HMB-8, cubicle 0.6 x 0.4 x 1.8 m, and every colour (light grey RAL 7035-ish enclosures, darker
grey frame, grey cubicle) are estimates; real units differ in arrangement. Original geometry; no vendor CAD, no vendor names or
logos.

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
PL_AUX = {k: mtex('compartment nameplate ' + k, 'gcb_plate_' + k) for k in ('vt', 'cap', 'arr')}
PL_HMB = mtex('mechanism nameplate', 'gcb_plate_hmb')
HAZ = mtex('warning label', 'gcb_hazard')
SF6L = mtex('SF6 gas label', 'gcb_label_sf6')
MPI = {True: mtex('position indicator closed', 'gcb_mpi_closed'), False: mtex('position indicator open', 'gcb_mpi_open')}

_cylinder = cylinder


def cylinder(s, name, a, b, r, mat, n=None):
    """Capped cylinder; the segment count follows the radius unless given (keeps small parts cheap)."""
    return _cylinder(s, name, a, b, r, mat, n if n and n >= 16 else (n or (8 if r < .06 else 12 if r < .15 else 16)))


# ---------------------------------------------------------------- tunables
AY = 1.7                     # pole axis height (brochure: bus centreline 1,600-1,750 mm)
POLES = {'A': 1.0, 'B': 3.0, 'C': 5.0}   # 2.0 m phase pitch
R_TUBE, R_FL, R_CH = .45, .53, .625
Z_GEN, Z_XF = 0.0, 4.0
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
def compartment(s, xc, zc, length, kind, tag, side=-1):
    """Grounded bolted metal compartment hanging under the pole (VT, surge capacitor, surge arrester): box with flanged cover on the
    side (-1 west, +1 east) that faces away from its neighbour."""
    z0, z1 = zc - length / 2, zc + length / 2
    box(s, 'compartment ' + tag, (xc - .30, .85, z0), (xc + .30, 1.30, z1), ALUD)
    fx = xc + side * .30
    box(s, 'compartment cover flange ' + tag, (min(fx, fx + side * .025), .87, z0 - .02), (max(fx, fx + side * .025), 1.20, z1 + .02), ALUF)
    for zz in (z0 + .04, z1 - .04):
        for y in (.91, 1.16):
            stud(s, (fx + side * .02, y, zz), (fx + side * .055, y, zz), .022)
    plate(s, 'compartment nameplate ' + tag, (fx + side * .025, 1.035, zc), (side, 0, 0), (0, 1, 0), .20, .10, PL_AUX[kind], FRAMEM, .014)


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
    sleeve(s, 'conductor tube stub ' + end, (xp, AY, z), (xp, AY, z + dz * .35), .30, .27, BRIGHT, 16)


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
    zm = (z0 + z1) / 2 + .2
    cylinder(s, 'density monitor stub', (xp - R_CH + .02, AY + .15, zm), (xp - R_CH - .12, AY + .15, zm), .04, ALUD, 10)
    gauge(s, (xp - R_CH - .15, AY + .15, zm), (-1, 0, 0), .07)
    plate(s, 'SF6 label', (xp - R_CH + .005, AY - .15, zm), (-1, 0, 0), (0, 1, 0), .20, .10, SF6L, FRAMEM, .014)


CH0, CH1 = 1.05, 2.40        # interrupting chamber
DS0, DS1 = 2.40, 3.00        # series disconnector


def pole(s, phase, xp, opened):
    for end in ('gen', 'xfmr'):
        connection_zone(s, xp, end)
    # enclosure between the flange zones: z 0.35 to 3.65 = 3.3 m (brochure 3,260 mm)
    cylinder(s, 'enclosure generator side', (xp, AY, .33), (xp, AY, CH0), R_TUBE, ALU, 20)
    cylinder(s, 'enclosure transformer side', (xp, AY, DS1), (xp, AY, Z_XF - .33), R_TUBE, ALU, 20)
    interrupter(s, xp, CH0, CH1)
    disconnector(s, xp, DS0, DS1, opened, phase)
    for z in (.55, .85, CH0, CH1, 3.0, 3.3):
        if z in (CH0, CH1):
            bolted_ring(s, 'section flange', xp, z, R_FL, .07, ALUF, NBOLT, 16, side=1 if z == CH1 else -1)
        else:
            bolted_ring(s, 'section flange', xp, z, R_FL, .07, ALUF, NBOLT, 16)
    # generator side (z .35 -> 1.05): VT and surge capacitor side by side under the tube, CT, earthing switch
    compartment(s, xp, .5, .4, 'vt', 'VT ' + phase + 'g')
    compartment(s, xp + .62, .5, .4, 'cap', 'capacitor ' + phase + 'g', 1)
    current_transformer(s, xp, .55, .85, phase + 'g')
    side_earthing_switch(s, xp, .95, phase + 'g')
    # transformer side (z 3.0 -> 3.65)
    current_transformer(s, xp, 3.0, 3.3, phase + 'x')
    side_earthing_switch(s, xp, 3.47, phase + 'x')
    compartment(s, xp, 3.2, .4, 'cap', 'capacitor ' + phase + 'x')
    compartment(s, xp + .62, 3.2, .4, 'arr', 'arrester ' + phase + 'x', 1)
    compartment(s, xp, 3.68, .4, 'vt', 'VT ' + phase + 'x')
    for z in (.95, 2.55):
        box(s, 'lifting lug', (xp - .02, AY + R_TUBE - .01, z - .06), (xp + .02, AY + R_TUBE + .13, z + .06), FRAMEM)
    cylinder(s, 'SF6 fill valve', (xp, AY + R_TUBE - .01, 2.7), (xp, AY + R_TUBE + .09, 2.7), .03, ALUD, 8)
    cylinder(s, 'SF6 fill valve cap', (xp, AY + R_TUBE + .09, 2.7), (xp, AY + R_TUBE + .13, 2.7), .04, BTN_R, 8)
    zm = (CH0 + CH1) / 2
    plate(s, 'pole warning label', (xp - R_CH + .003, AY + .25, zm - .1), (-1, 0, 0), (0, 1, 0), .24, .12, HAZ, FRAMEM, .014)
    # pressure relief device on top of the chamber: the highest part, 2.9 m above the floor (brochure: overall height at least 2,900 mm)
    zr = CH1 - .4
    cylinder(s, 'pressure relief flange', (xp, AY + R_CH - .04, zr), (xp, AY + R_CH + .03, zr), .17, ALUF, 16)
    cylinder(s, 'pressure relief stack', (xp, AY + R_CH, zr), (xp, 2.82, zr), .09, ALUD, 12)
    cylinder(s, 'pressure relief hood', (xp, 2.78, zr), (xp, 2.9, zr), .14, ALUF, 14)
    # crank housing under the chamber, driven from the operating shaft
    box(s, 'crank housing', (xp - .18, .85, zm - .22), (xp + .38, 1.12, zm + .22), ALUD)
    cylinder(s, 'crank housing end cover', (xp + .38, .985, zm), (xp + .43, .985, zm), .12, ALUF, 14)


# ---------------------------------------------------------------- frame, mechanism, cubicle
CROSS = [.55, 1.15, 2.5, 3.4]
LEGX = [.6, 2.0, 4.0, 5.4]
FY = .745                    # cross beam centre height; the frame top is at .85 (saddles, compartments)


def saddle(s, xp, z, r):
    c = r * math.cos(math.radians(30))
    prof = [(xp + c, .85)] + [(xp + r * math.cos(math.radians(a)), AY + r * math.sin(math.radians(a))) for a in range(-30, -151, -30)] + [(xp - c, .85)]
    prism(s, 'pole saddle', 'z', z - .04, z + .04, prof, FRAMEM)


def base_frame(s):
    for ph, xp in POLES.items():
        beam_z(s, 'frame longitudinal beam ' + ph, xp, FY + .03, .5, 3.5)
    for z in CROSS:
        beam_x(s, 'frame cross beam', z, FY, .4, 5.6)
        for x in LEGX:
            leg(s, 'frame leg', x, z, 0, FY - .075)
    for ph, xp in POLES.items():
        for z, r in ((.75, .45), (1.25, .60), (2.2, .60), (2.7, .45), (3.4, .45)):
            saddle(s, xp, z, r)
    # earthing bar along the last cross beam with a drop on the front leg, lug at the floor
    zb = CROSS[-1] + .105
    obox(s, 'earthing bar', (.4, .70, zb), (5.6, .70, zb), .02, .04, (0, 1, 0), EARTH)
    obox(s, 'earthing bar drop', (.65, .70, zb - .025), (.65, .08, zb - .025), .02, .04, (0, 0, 1), EARTH)
    box(s, 'earthing lug', (.58, .05, zb - .035), (.72, .11, zb - .005), EARTH)


def mechanism(s, opened):
    """HMB-8 style hydromechanical spring mechanism under the middle pole, with the operating shaft across all poles."""
    xb = POLES['B']
    box(s, 'mechanism cabinet', (xb - .5, .052, 1.35), (xb + .5, .598, 2.05), MECHM)
    box(s, 'mechanism cabinet top', (xb - .52, .60, 1.33), (xb + .52, .62, 2.07), ALUD)
    box(s, 'mechanism plinth', (xb - .52, .013, 1.33), (xb + .52, .052, 2.07), FRAMEM)
    # spring stack: housing and exposed coil, running toward the transformer end
    cylinder(s, 'spring stack housing', (xb, .325, 2.05), (xb, .325, 2.55), .2, SPRM, 18)
    for z in (2.2, 2.38, 2.52):
        cylinder(s, 'spring stack band', (xb, .325, z - .012), (xb, .325, z + .012), .215, ALUD, 18)
    helix(s, 'closing spring', (xb, .325, 2.55), (0, 0, 1), .16, .026, .22, 4, SPRM, 10, 5)
    cylinder(s, 'spring stack end plate', (xb, .325, 2.77), (xb, .325, 2.81), .21, ALUD, 18)
    cylinder(s, 'spring guide rod', (xb, .325, 2.8), (xb, .325, 2.9), .03, SHAFTM2, 8)
    plate(s, 'mechanism nameplate', (xb - .5, .40, 1.70), (-1, 0, 0), (0, 1, 0), .30, .12, PL_HMB, FRAMEM, .014)
    gauge(s, (xb - .5, .215, 1.88), (-1, 0, 0), .06)
    plate(s, 'mechanism position indicator', (xb - .5, .215, 1.55), (-1, 0, 0), (0, 1, 0), .11, .11, MPI[not opened], FRAMEM, .014)
    cylinder(s, 'manual charge handle', (xb - .5, .52, 1.90), (xb - .6, .52, 1.90), .015, HANDLEM, 8)
    cylinder(s, 'manual charge handle grip', (xb - .6, .52, 1.90), (xb - .6, .52, 2.0), .02, BTN_R, 8)
    # operating shaft across under all three poles, bearing blocks, cranks and link rods up to each pole's crank housing
    zs = 1.70
    cylinder(s, 'operating shaft', (.6, .40, zs), (5.4, .40, zs), .035, SHAFTM2, 10)
    for xp in POLES.values():
        dx = -.3
        box(s, 'shaft bearing block', (xp + dx - .07, .013, zs - .07), (xp + dx + .07, .338, zs + .07), FRAMEM)
        cylinder(s, 'shaft bearing', (xp + dx - .05, .40, zs), (xp + dx + .05, .40, zs), .07, ALUD, 10)
        obox(s, 'crank arm', (xp + .25, .40, zs), (xp + .25, .40, zs + .12), .05, .06, (0, 1, 0), SHAFTM2)
        cylinder(s, 'crank link rod', (xp + .25, .40, zs + .12), (xp + .25, .98, zs + .12), .02, SHAFTM2, 8)
        cylinder(s, 'crank collar', (xp + .20, .40, zs), (xp + .30, .40, zs), .05, SHAFTM2, 8)
    for x in (.65, 5.35):
        box(s, 'shaft end bearing block', (x - .07, .013, zs - .07), (x + .07, .338, zs + .07), FRAMEM)
        cylinder(s, 'shaft end bearing', (x - .05, .40, zs), (x + .05, .40, zs), .07, ALUD, 10)
    pipe(s, 'control cable trunk', [(.30, .12, .70), (1.5, .12, .70), (1.5, .12, 1.55), (xb - .45, .12, 1.55)], .03, FRAMEM, 8)


def cubicle(s, opened):
    """Local control cubicle on the west side of the frame near the generator end: 0.6 wide (z) x 0.4 deep x 1.8 tall."""
    z0, z1, zc = .40, 1.00, .70
    box(s, 'cubicle plinth', (.0, .0, z0 - .02), (.42, .10, z1 + .02), FRAMEM)
    box(s, 'cubicle body', (.05, .10, z0), (.40, 1.90, z1), CUBM)
    box(s, 'cubicle roof', (.0, 1.90, z0 - .02), (.42, 1.95, z1 + .02), ALUD)
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
    # floor: 1 m checker (one block per square) plus the 6 x 4 footprint outline
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
    for a, b in (((0, 0), (6, 0)), ((6, 0), (6, 4)), ((6, 4), (0, 4)), ((0, 4), (0, 0))):
        obox(s, 'footprint outline', (a[0], .004, a[1]), (b[0], .004, b[1]), .04, .01, (0, 1, 0), outline)
    figure(s, -.55, 1.6)
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
        lo.rotation_euler = (xyz((3.0, 0, 2.0)) - xyz(loc)).to_track_quat('-Z', 'Y').to_euler()
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
    '1-iso-front': ((-4.5, 3.4, -2.8), (2.8, 1.1, 1.8), 30, (1600, 1000), True),
    '2-iso-rear': ((10.5, 3.8, 7.5), (3.0, 1.1, 2.0), 32, (1600, 1000), False),
    '3-side-along-bus': ((-10.0, 2.0, 2.0), (3.0, 1.3, 2.0), 38, (1800, 800), False),
    '4-end-flanges': ((3.0, 2.2, -9.0), (3.0, 1.5, 0.0), 55, (1600, 1000), False),
    '5-cubicle-indicator': ((-1.7, 1.55, 0.2), (0.0, 1.55, 0.7), 32, (1200, 1000), False),
    '6-disconnector-window': ((-2.2, 2.0, 3.8), (0.6, 1.7, 2.7), 40, (1400, 1000), False),
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
        'description': 'IPB-45 FA connection points of the generator breaker, in block units of the 6 x 3 x 4 box '
                       '(x across the phases, y up, z along the bus; generator end z = 0, transformer end z = 4).',
        'box': [6, 3, 4], 'connections': connections()}, indent=2) + '\n', encoding='utf-8')
    for sc in SCENES:
        sc['description'] = ('Original reference-based model (ABB/Hitachi HEC 7/8 class SF6 generator breaker); '
                             'one Blender unit is one block. Sizes and colours are estimates.')
        lo, hi = check_bounds(sc)
        print('GCB', sc.name, triangles(sc), 'triangles', 'objects', len(sc.objects), 'bounds', [round(x, 3) for x in lo + hi])
        assert all(lo[a] >= -1e-6 for a in range(3)) and hi[0] <= 6 + 1e-6 and hi[1] <= 3 + 1e-6 and hi[2] <= 4 + 1e-6, 'outside 6x3x4'
    # Mesh/UV snapshots that tools-export-gcb.py bakes into per-cell models (the MSIV's route, tools-export-msiv.py). The size is
    # width x height x depth in cells; the controller is the control cubicle's cell (x 0, y 0, z 0), where the block entity lives.
    meta = {'id': 'generator_breaker', 'size': [6, 3, 4], 'controller': [0, 0, 0], 'ports': []}
    export(SCENES[0], HERE / 'generator_breaker.json', meta)
    export(SCENES[1], HERE / 'generator_breaker_open.json', dict(meta, id='generator_breaker_open'))
    for sc in list(bpy.data.scenes):
        if sc not in SCENES:
            bpy.data.scenes.remove(sc)
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(HERE / 'generator_breaker.blend'), compress=True)
    print('GCB BUILT', [(sc.name, len(sc.objects)) for sc in SCENES])
    if '--preview' in argv:
        only = argv[argv.index('--only') + 1].split(',') if '--only' in argv else None
        render_previews(Path(argv[argv.index('--preview') + 1]).resolve(), only)
