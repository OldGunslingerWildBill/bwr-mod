"""Main steam valves, rebuilt from the Columbia FSAR figures: the MSIV and the safety relief valve.

Headless only:
    python art/models/main_steam_valves/make_textures.py
    blender --background --factory-startup --python art/models/main_steam_valves/build_valves.py

Writes the mesh/UV snapshots that the existing exporters bake:
    art/models/condenser_msiv/msiv.json, msiv_open.json      -> python tools-export-msiv.py
    art/models/alpha/ads_relief_valve.json, _open.json        -> python tools-export-alpha.py
and the editable source art/models/main_steam_valves/main_steam_valves.blend.

Each valve is written twice, shut and open; only the moving parts differ (stem, spring seat member and springs,
position pointer, relief valve lever, spindle and actuator rod). Coordinates are Minecraft X/Y/Z for a north-facing
valve; one unit is one block. Footprints, controller cell and the steam ports (cell, face, flange centre) are
unchanged and are checked against the shipped data before anything is written. Original geometry; no vendor CAD.

Do not regenerate these with art/models/condenser_msiv/build_models.py (build_msiv) or art/models/alpha/build_models.py:
those scripts would restore the old models.
"""
import json
import math
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/turbine_pumps/build_models.py'
exec(compile(helper.read_text(encoding='utf8').split('# ================================================================ reactor feed pump turbine')[0],
             str(helper), 'exec'))
HERE = ROOT / 'art/models/main_steam_valves'
SCENES = []


def new_scene(name):
    s = bpy.data.scenes.new(name)
    SCENES.append(s)
    return s


def mpaint(name, srgb, metal=0.0, rough=.5):
    m = material('MSV ' + name, tuple(lin(c) for c in srgb), metal)
    next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled').inputs['Roughness'].default_value = rough
    return m


def mtex(name, tex):
    m = tex_mat('MSV ' + name, tex)
    return m


# ---------------------------------------------------------------- materials (sRGB as seen in game)
FORGED = mpaint('forged valve body', (.38, .40, .41), .4, .45)
CASTD = mpaint('cast body darker', (.31, .33, .34), .4, .45)
ACTP = mpaint('actuator paint grey', (.60, .63, .63), .2, .45)
ACTD = mpaint('actuator heads darker grey', (.46, .49, .50), .3, .4)
SPRG = mpaint('closing spring', (.14, .15, .16), .5, .35)
SHAFTM = mpaint('machined stem and shafts', (.76, .78, .79), .85, .2)
NUT = mpaint('dark fasteners', (.20, .21, .22), .6, .4)
GASK = mpaint('gasket', (.06, .065, .07))
ACCUM = mpaint('accumulator', (.52, .58, .55), .2)
SOLB = mpaint('solenoid coil black', (.11, .115, .12), .1)
SWB = mpaint('limit switch enclosure', (.33, .42, .38), .2)
TUBE = mpaint('stainless tubing', (.70, .72, .72), .8, .25)
CONDU = mpaint('conduit', (.48, .50, .50), .6)
YEL = mpaint('pointer yellow', (.92, .70, .10))
REDH = mpaint('red handle', (.72, .12, .08))
BRASS = mpaint('brass fittings', (.70, .55, .25), .7, .3)
PIPEG = mpaint('discharge pipe grey', (.42, .45, .46), .4)
PLATE_MSIV = mtex('MSIV nameplate', 'msv_plate_msiv')
PLATE_SRV = mtex('relief valve nameplate', 'msv_plate_srv')
SCALE = mtex('stem position scale', 'msv_scale')
AIRL = mtex('air supply label', 'msv_label_air')


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


def helix(s, name, a, axis, coil_r, wire_r, length, turns, mat, steps=10, sides=6):
    a = V(a)
    d, u, v = basis(axis)
    total = int(turns * steps)
    pts = [a + d * (length * j / total) + coil_r * (math.cos(j * math.tau / steps) * u + math.sin(j * math.tau / steps) * v)
           for j in range(total + 1)]
    return sweep(s, name, pts, wire_r, mat, sides)


def nut(s, c, axis, r=.03, h=.03, mat=None):
    c = V(c)
    d = V(axis).normalized()
    hexbolt(s, c - d * h / 2, c + d * h / 2, r, mat or NUT)


def flange_pair(s, name, c, axis, r, bore, thick, count, stud_r=.016, mat=None):
    """Two bolted flanges meeting at c (a mid-line joint), studs with a nut each side."""
    c = V(c)
    d, u, v = basis(axis)
    sleeve(s, name + ' flange a', c - d * thick, c - d * .004, r, bore, mat or CASTD, 20)
    sleeve(s, name + ' gasket', c - d * .004, c + d * .004, r * .9, r * .8, GASK, 20)
    sleeve(s, name + ' flange b', c + d * .004, c + d * thick, r, bore, mat or CASTD, 20)
    for i in range(count):
        q = c + r * .8 * (math.cos((i + .5) * math.tau / count) * u + math.sin((i + .5) * math.tau / count) * v)
        hexbolt(s, q - d * (thick + .022), q - d * (thick - .002), stud_r * 1.7)
        hexbolt(s, q + d * (thick - .002), q + d * (thick + .022), stud_r * 1.7)


def port_flange(s, name, p, outward, r=.33, count=8):
    """Steam port flange whose sealing face lies exactly on the block face at p."""
    p = V(p)
    d, u, v = basis(outward)
    sleeve(s, name + ' flange', p - d * .11, p - d * .02, r, .205, CASTD, 20)
    sleeve(s, name + ' raised face', p - d * .02, p, r * .74, .205, FORGED, 20)
    for i in range(count):
        q = p + r * .82 * (math.cos((i + .5) * math.tau / count) * u + math.sin((i + .5) * math.tau / count) * v)
        hexbolt(s, q - d * .135, q - d * .03, .024)


def manifest(name):
    return json.loads((ROOT / 'mod/src/main/resources/data/bwr/pump_models' / name).read_text(encoding='utf-8'))


# ================================================================ MSIV (1 x 3 x 1, steam ports on the base cell)
# Columbia FSAR 5.4.5.2 and Fig 5.4-9: 26 in Y-pattern globe valve; air cylinder (closing piston inside) and hydraulic
# dashpot on top, carried on the bonnet by the actuator support and spring guide shafts; helical closing springs on the
# guide shafts push the spring seat member (fixed to the stem) down; the control unit with its solenoid pilot valves is
# on the cylinder; an accumulator and check valve supply backup air. The stem leans toward the inlet (north) as much as
# a one-block-deep footprint allows (7.4 degrees; the real valve's seat is at 45 degrees inside the body).
MS_STROKE = .24


def msiv(opened):
    s = new_scene('BWR | MSIV ' + ('open' if opened else 'shut'))
    # Run of the body: weld-neck hubs to the existing flange centres (.5, .5, 0) and (.5, .5, 1).
    for z, dz, nm in ((0, -1, 'north inlet'), (1, 1, 'south outlet')):
        steam_flange(s, nm, (.5, .5, z), (0, 0, dz))
    lathe(s, 'forged body run', (.5, .5, .10), (0, 0, 1),
          [(0, .255), (.07, .265), (.16, .31), (.26, .355), (.40, .37), (.52, .36), (.62, .33), (.72, .285), (.80, .255)], FORGED, 28)
    # Inclined bonnet neck of the Y body, on the stem axis.
    A = V((.5, .52, .60))
    D = V((0, 1, -.13)).normalized()
    X = V((1, 0, 0))
    W = D.cross(X).normalized()          # across the stem, in the y-z plane (points north-down)

    def at(t, x=0.0, w=0.0):
        return A + D * t + X * x + W * w

    frustum(s, 'Y body bonnet neck', at(.0), at(.34), .30, .275, FORGED, 28)
    # Drain boss under the body and the flow arrow cast on the east side.
    cylinder(s, 'body drain boss', (.5, .17, .50), (.5, .10, .50), .055, FORGED, 10)
    hexbolt(s, (.5, .10, .5), (.5, .075, .5), .04)
    prism(s, 'cast flow arrow', 'x', .86, .875, [(.38, .40), (.38, .44), (.56, .44), (.56, .47), (.64, .42), (.56, .37), (.56, .40)], CASTD)
    # Bonnet joint: body flange, gasket, bonnet flange and 12 through-bolts.
    cylinder(s, 'body bonnet flange', at(.33), at(.385), .33, FORGED, 32)
    cylinder(s, 'bonnet gasket', at(.385), at(.392), .31, GASK, 32)
    cylinder(s, 'bonnet flange', at(.392), at(.447), .33, FORGED, 32)
    for i in range(12):
        a = (i + .5) * math.tau / 12
        q = (math.cos(a) * .285, math.sin(a) * .285)
        hexbolt(s, at(.447, *q), at(.482, *q), .028)
        hexbolt(s, at(.33, *q), at(.30, *q), .028)
    frustum(s, 'bonnet', at(.447), at(.60), .25, .15, FORGED, 28)
    # Stuffing box, gland follower and the plugged leak-off connection.
    cylinder(s, 'stuffing box', at(.58), at(.69), .105, FORGED, 20)
    obox(s, 'gland follower', at(.69), at(.725), .30, .13, W, FORGED)
    for x in (-.115, .115):
        cylinder(s, 'gland stud', at(.66, x), at(.75, x), .013, SHAFTM, 6)
        nut(s, at(.74, x), D, .022, .02)
    pipe(s, 'packing leak-off nipple', [at(.63, .1), at(.63, .19), at(.55, .19)], .018, SHAFTM, 8)
    hexbolt(s, at(.55, .19), at(.52, .19), .026)
    # Actuator support: lower plate on two legs from the bonnet.
    for x in (-.15, .15):
        obox(s, 'actuator support leg', at(.50, x), at(.75, x), .05, .14, W, ACTD)
    obox(s, 'lower support plate', at(.75), at(.80), .62, .58, W, ACTD)
    # Spring guide shafts from the lower plate up to the upper (cylinder) plate.
    T_TOP = 1.72
    tc = .88 + (MS_STROKE if opened else 0.0)             # spring seat member, fixed to the stem
    corners = [(x, w) for x in (-.225, .225) for w in (-.205, .205)]
    for x, w in corners:
        cylinder(s, 'spring guide shaft', at(.75, x, w), at(T_TOP + .06, x, w), .025, SHAFTM, 8)
        nut(s, at(.735, x, w), D, .04, .03)
        sleeve(s, 'spring seat bushing', at(tc - .015, x, w), at(tc + .085, x, w), .045, .026, ACTD, 8)
        helix(s, 'closing spring', at(tc + .085, x, w), D, .088, .019, T_TOP - tc - .095, 7.0, SPRG, 10, 6)
        sleeve(s, 'spring cup', at(T_TOP - .02, x, w), at(T_TOP, x, w), .11, .03, ACTD, 8)
    obox(s, 'spring seat member', at(tc), at(tc + .07), .64, .56, W, ACTP)
    # Stem, split coupling and piston rod.
    cylinder(s, 'valve stem', at(.60), at(tc), .042, SHAFTM, 12)
    cylinder(s, 'stem coupling', at(tc - .11), at(tc), .07, ACTD, 12)
    for w in (-.05, .05):
        hexbolt(s, at(tc - .055, -.09, w), at(tc - .055, .09, w), .016)
    cylinder(s, 'piston rod', at(tc + .07), at(T_TOP - .2), .036, SHAFTM, 12)
    # Hydraulic dashpot hanging under the upper plate, with the speed control valves on its oil bypass.
    lathe(s, 'hydraulic dashpot', at(T_TOP - .30), D, [(0, .10), (.03, .125), (.27, .125), (.30, .135)], ACTD, 20)
    pipe(s, 'dashpot oil bypass', [at(T_TOP - .27, .12), at(T_TOP - .27, .20), at(T_TOP - .05, .20), at(T_TOP - .05, .12)], .014, TUBE, 8)
    for t in (T_TOP - .21, T_TOP - .11):
        obox(s, 'speed control valve body', at(t - .025, .20), at(t + .025, .20), .05, .05, X, BRASS)
        cylinder(s, 'speed control valve bonnet', at(t, .20), at(t, .26), .015, BRASS, 8)
        cylinder(s, 'speed control valve knob', at(t, .26), at(t, .285), .026, REDH, 8)
    # Upper spring plate and the air cylinder (square heads, round barrel, four tie rods).
    obox(s, 'upper spring plate', at(T_TOP), at(T_TOP + .06), .62, .58, W, ACTD)
    C0, C1 = T_TOP + .06, T_TOP + .52
    obox(s, 'cylinder lower head', at(C0), at(C0 + .06), .50, .50, W, ACTD)
    cylinder(s, 'air cylinder barrel', at(C0 + .06), at(C1 - .06), .215, ACTP, 32)
    obox(s, 'cylinder upper head', at(C1 - .06), at(C1), .50, .50, W, ACTD)
    for x, w in ((-.2, -.2), (.2, -.2), (.2, .2), (-.2, .2)):
        cylinder(s, 'cylinder tie rod', at(C0, x, w), at(C1 + .03, x, w), .017, SHAFTM, 6)
        nut(s, at(C1 + .02, x, w), D, .028, .025)
    for x in (-.17, .17):
        hexbolt(s, at(C1, x), at(C1 + .05, x), .022)
        path = [at(C1 + .05, x) + .05 * (math.cos(k * math.tau / 16) * X + math.sin(k * math.tau / 16) * D) + D * .05 for k in range(17)]
        sweep(s, 'lifting eye', path[::2], .012, SHAFTM, 5)
    # Accumulator on top of the cylinder (FSAR Fig 5.4-9), on two saddles, with gauge and check valve.
    acc_c = at(C1 + .17)
    for x in (-.16, .16):
        obox(s, 'accumulator saddle', at(C1, x), at(C1 + .10, x), .04, .16, W, ACTD)
    cylinder(s, 'accumulator shell', acc_c + X * -.24, acc_c + X * .24, .085, ACCUM, 20)
    for sg in (-1, 1):
        lathe(s, 'accumulator dished head', acc_c + X * .24 * sg, X * sg, [(0, .085), (.03, .075), (.05, .05), (.06, .0)], ACCUM, 20, caps=False)
    gauge(s, acc_c + X * .1 + W * -.088, -W, .032, D)
    pipe(s, 'accumulator outlet', [acc_c + X * -.20 + W * .07, at(C1 + .12, -.20, .17), at(C1 - .22, -.28, .17)], .013, TUBE, 8)
    obox(s, 'accumulator check valve', at(C1 + .03, -.215, .17), at(C1 + .09, -.215, .17), .035, .035, X, BRASS)
    # Control unit on the west side of the cylinder: manifold, three solenoid pilot valves, junction box.
    cu = at((C0 + C1) / 2, -.31, .05)
    obox(s, 'control unit manifold', cu - D * .14, cu + D * .14, .08, .26, W, ACTD)
    for k, w in enumerate((-.08, 0.0, .08)):
        p = cu + W * w + D * .14
        cylinder(s, 'solenoid pilot valve body', p, p + D * .03, .028, BRASS, 8)
        cylinder(s, 'solenoid coil', p + D * .03, p + D * .10, .033, SOLB, 10)
        cylinder(s, 'solenoid cap', p + D * .10, p + D * .115, .02, SOLB, 8)
    obox(s, 'pilot valve junction box', cu - D * .14, cu - D * .26, .09, .20, W, SOLB)
    pipe(s, 'pilot valve conduit', [cu - D * .26, at(C0 - .3, -.31, .05), at(.85, -.31, .2), at(.40, -.31, .3)], .016, CONDU, 8)
    pipe(s, 'cylinder upper air line', [cu + D * .05 + X * .02, at(C1 - .03, -.24, .05)], .012, TUBE, 6)
    pipe(s, 'cylinder lower air line', [cu - D * .10 + X * .02, at(C0 + .03, -.24, .05)], .012, TUBE, 6)
    plate(s, 'air supply label', cu - D * .02 + X * -.045 + W * -.06, -X, D, .09, .045, AIRL, ACTD, .006)
    # Position indication: a pointer on the spring seat member against a fixed scale (west side) ...
    lv = tc + .035
    sc_t0 = .88 + .035 - MS_STROKE * .1
    sc_h = MS_STROKE * 1.2
    obox(s, 'scale bracket tab', at(.775, -.30, .24), at(.775, -.36, .24), .04, .05, D, ACTD)
    obox(s, 'scale bracket', at(.75, -.345, .24), at(sc_t0 + sc_h + .02, -.345, .24), .02, .03, X, ACTD)
    plate(s, 'stem position scale', at(sc_t0 + sc_h / 2, -.37, .24), -X, D, .07, sc_h, SCALE, ACTD, .012)
    obox(s, 'position pointer arm', at(lv, -.30, .295), at(lv, -.39, .295), .02, .015, D, YEL)
    obox(s, 'position pointer', at(lv, -.385, .30), at(lv, -.385, .20), .012, .014, D, YEL)
    # ... and two position (limit) switches on the east side, struck by a cam on the spring seat member.
    obox(s, 'limit switch bracket tab', at(.775, .30, .0), at(.775, .45, .0), .06, .05, D, ACTD)
    obox(s, 'limit switch bracket', at(.75, .445, .0), at(.84 + MS_STROKE + .17, .445, .0), .06, .025, X, ACTD)
    for t in (.84, .84 + MS_STROKE + .04):
        obox(s, 'position switch enclosure', at(t, .40, -.01), at(t + .11, .40, -.01), .08, .07, X, SWB)
        cylinder(s, 'switch cover screw', at(t + .03, .365, -.01), at(t + .08, .365, -.01), .012, NUT, 6)
        cylinder(s, 'switch roller lever', at(t + .055, .365, -.05), at(t - .01, .345, -.05), .008, SHAFTM, 6)
    obox(s, 'switch cam', at(lv - .015, .335, -.05), at(lv + .015, .335, -.05), .05, .05, X, SHAFTM)
    pipe(s, 'switch conduit', [at(.84, .40, -.06), at(.78, .40, -.16), at(.50, .34, -.26)], .014, CONDU, 8)
    # Nameplate on the body (west side).
    plate(s, 'valve nameplate', (.135, .55, .34), (-1, 0, 0), (0, 1, 0), .22, .11, PLATE_MSIV, CASTD, .03)
    return s


# ================================================================ Safety relief valve (1 block; inlet west, discharge down)
# Columbia FSAR 5.2.2.4 and Fig 5.2-8: dual-function spring-loaded direct-acting safety/relief valve with an auxiliary
# actuating device: bonnet and spring, spindle, set pressure verification head and adjusting screw on top, a lever and
# piston-type pneumatic actuator, solenoid/air control valve assemblies A, B and C, an LVDT for valve position
# indication, its own accumulator and inlet check valve, and a 6 in inlet with a 10 in side discharge led to the pool.
SR_LIFT = .035


def srv(opened):
    s = new_scene('BWR | safety relief valve ' + ('open' if opened else 'shut'))
    lift = SR_LIFT if opened else 0.0
    # Inlet: port flange on the west face (centre 0, .5, .5) and a short contour nozzle into the body.
    port_flange(s, 'steam inlet', (0, .5, .5), (-1, 0, 0))
    lathe(s, 'inlet contour nozzle', (.11, .5, .5), (1, 0, 0), [(0, .19), (.04, .175), (.10, .17)], FORGED, 24, caps=False)
    B = V((.27, .55, .50))
    ellipsoid(s, 'valve body', B, .16, .17, .19, CASTD, 20, 8)
    # Discharge: the outlet leaves the body angled 45 degrees down to the east, a bolted 10 in joint, then the
    # tailpipe bends down to the bottom port flange (centre .5, 0, .5), toward the suppression pool.
    dd = V((1, -1, 0)).normalized()
    frustum(s, 'outlet nozzle', B + dd * .08, B + dd * .205, .13, .14, CASTD, 24)
    flange_pair(s, 'discharge', B + dd * .24, dd, .19, .12, .032, 8)
    t0 = B + dd * .275
    pipe(s, 'discharge tailpipe', [t0, V((.50, .32, .5)), V((.51, .24, .5)), V((.5, .13, .5)), V((.5, .10, .5))], .13, PIPEG, 16)
    port_flange(s, 'discharge', (0.5, 0, .5), (0, -1, 0), .31, 8)
    # Tailpipe thermocouple (temperature indication).
    cylinder(s, 'tailpipe thermocouple well', (.52, .22, .62), (.52, .22, .67), .016, SHAFTM, 6)
    obox(s, 'thermocouple head', (.52, .19, .69), (.52, .25, .69), .045, .045, (1, 0, 0), NUT)
    # Bonnet: the spring and spindle are enclosed in a tall cylindrical bonnet bolted to the body (FSAR Fig 5.2-8).
    cylinder(s, 'bonnet flange', (B.x, .63, .5), (B.x, .665, .5), .15, FORGED, 20)
    for i in range(8):
        a = (i + .5) * math.tau / 8
        hexbolt(s, (B.x + .125 * math.cos(a), .665, .5 + .125 * math.sin(a)), (B.x + .125 * math.cos(a), .69, .5 + .125 * math.sin(a)), .017)
    lathe(s, 'spring bonnet', (B.x, .665, .5), (0, 1, 0), [(0, .115), (.03, .10), (.20, .092), (.225, .092)], ACTP, 20, caps=False)
    cylinder(s, 'bonnet top flange', (B.x, .885, .5), (B.x, .905, .5), .105, ACTP, 20)
    for i in range(6):
        a = (i + .5) * math.tau / 6
        hexbolt(s, (B.x + .088 * math.cos(a), .905, .5 + .088 * math.sin(a)), (B.x + .088 * math.cos(a), .918, .5 + .088 * math.sin(a)), .012)
    # Setpoint adjusting screw and locknut, then the set pressure verification head with its lifting eyes, and the
    # valve position indication (LVDT) on top.
    hexbolt(s, (B.x, .905, .5), (B.x, .925, .5), .04)
    cylinder(s, 'adjusting screw', (B.x, .925, .5), (B.x, .945, .5), .022, SHAFTM, 10)
    lathe(s, 'verification head', (B.x, .945, .5), (0, 1, 0), [(0, .05), (.008, .068), (.03, .068), (.038, .05)], ACTD, 16)
    for z in (.455, .545):
        path = [V((B.x + .016 * math.cos(k * math.pi / 6), .975 + .016 * math.sin(k * math.pi / 6), z)) for k in range(7)]
        sweep(s, 'lifting eye', path, .006, SHAFTM, 5)
    cylinder(s, 'LVDT', (B.x, .983, .5), (B.x, .998, .5), .016, SOLB, 10)
    # Lever and piston-type pneumatic actuator (south): the lever pivots on a bracket off the bonnet top (north),
    # passes the lifting fork at the spindle and is lifted by the actuator rod. Open: rod out and lever raised.
    LX = B.x + .085
    rise = SR_LIFT * .62 / .31 if opened else 0.0
    pivot = V((LX, .945, .19))
    rod_top = V((LX, .915 + rise, .81))
    obox(s, 'lever pivot bracket', (LX, .905, .43), (LX, .945, .19), .022, .03, (0, 1, 0), ACTD)
    cylinder(s, 'lever pivot pin', pivot + V((-.03, 0, 0)), pivot + V((.03, 0, 0)), .011, SHAFTM, 6)
    obox(s, 'lifting lever', pivot, rod_top, .022, .03, (0, 1, 0), SHAFTM)
    fork = pivot + (rod_top - pivot) * ((.5 - pivot.z) / (rod_top.z - pivot.z))
    obox(s, 'lifting fork', fork, V((B.x + .035, fork.y, .5)), .03, .022, (0, 1, 0), SHAFTM)
    ax, az = LX, .81
    obox(s, 'actuator bracket', (B.x, .60, .64), (ax, .49, az - .07), .04, .045, (1, 0, 0), ACTD)
    obox(s, 'actuator mounting plate', (ax, .475, az), (ax, .50, az), .17, .17, (1, 0, 0), ACTD)
    lathe(s, 'pneumatic actuator cylinder', (ax, .50, az), (0, 1, 0), [(0, .075), (.02, .078), (.27, .078), (.29, .07)], ACTP, 16)
    cylinder(s, 'actuator piston rod', (ax, .79, az), rod_top, .014, SHAFTM, 8)
    obox(s, 'rod clevis', rod_top + V((0, -.035, 0)), rod_top + V((0, .012, 0)), .045, .03, (0, 0, 1), ACTD)
    for x, z in ((ax - .065, az - .065), (ax + .065, az - .065), (ax + .065, az + .065), (ax - .065, az + .065)):
        cylinder(s, 'actuator tie rod', (x, .50, z), (x, .795, z), .008, SHAFTM, 6)
    # Solenoid/air control valve assemblies A, B and C, stacked on a plate on the actuator bracket.
    obox(s, 'solenoid valve plate', (.47, .45, .81), (.47, .74, .81), .02, .12, (0, 0, 1), ACTD)
    obox(s, 'solenoid plate arm', (ax + .078, .50, az), (.47, .50, .81), .03, .02, (0, 1, 0), ACTD)
    for y in (.50, .595, .69):
        obox(s, 'air control valve body', (.48, y, .81), (.53, y, .81), .06, .05, (0, 1, 0), BRASS)
        cylinder(s, 'solenoid coil', (.53, y, .81), (.61, y, .81), .028, SOLB, 10)
        cylinder(s, 'solenoid cap', (.61, y, .81), (.625, y, .81), .016, SOLB, 8)
    pipe(s, 'solenoid air header', [(.505, .47, .845), (.505, .72, .845)], .01, TUBE, 5)
    pipe(s, 'actuator air line', [(.505, .72, .845), (ax + .02, .76, .845), (ax + .02, .78, az + .03)], .01, TUBE, 5)
    # Accumulator and inlet check valve (south-east corner).
    cylinder(s, 'accumulator shell', (.86, .06, .80), (.86, .40, .80), .085, ACCUM, 18)
    lathe(s, 'accumulator top head', (.86, .40, .80), (0, 1, 0), [(0, .085), (.03, .072), (.05, .045), (.06, 0)], ACCUM, 18, caps=False)
    for x, z in ((.80, .74), (.92, .86), (.80, .86), (.92, .74)):
        obox(s, 'accumulator foot', (x, .0, z), (x, .07, z), .025, .025, (1, 0, 0), ACTD)
    gauge(s, (.86, .30, .715), (0, 0, -1), .03)
    obox(s, 'inlet check valve', (.86, .46, .80), (.86, .51, .80), .04, .04, (1, 0, 0), BRASS)
    pipe(s, 'accumulator supply', [(.86, .51, .80), (.86, .54, .87), (.505, .44, .87), (.505, .47, .845)], .01, TUBE, 5)
    plate(s, 'relief valve nameplate', (B.x, .55, .309), (0, 0, -1), (0, 1, 0), .14, .07, PLATE_SRV, CASTD, .02)
    return s


def check_msiv():
    m = manifest('msiv.json')
    assert m['game_ports'] == [{'role': 'STEAM_INLET', 'cell': [0, 0, 0], 'face': 'north'},
                               {'role': 'STEAM_EXHAUST', 'cell': [0, 0, 0], 'face': 'south'}], m['game_ports']
    assert [m['width'], m['height'], m['depth']] == [1, 3, 1] and m['controller_cell'] == [0, 0, 0]


if __name__ == '__main__':
    check_msiv()
    meta = {'id': 'msiv', 'size': [1, 3, 1], 'controller': [0, 0, 0], 'stroke_seconds': 4,
            'ports': [{'role': 'STEAM_INLET', 'cell': [0, 0, 0], 'face': 'north'}, {'role': 'STEAM_EXHAUST', 'cell': [0, 0, 0], 'face': 'south'}]}
    export(msiv(False), ROOT / 'art/models/condenser_msiv/msiv.json', meta)
    export(msiv(True), ROOT / 'art/models/condenser_msiv/msiv_open.json', dict(meta, id='msiv_open'))
    small = {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []}
    export(srv(False), ROOT / 'art/models/alpha/ads_relief_valve.json', small)
    export(srv(True), ROOT / 'art/models/alpha/ads_relief_valve_open.json', small)
    for sc in SCENES:
        sc['description'] = 'Original reference-based model (Columbia FSAR Fig 5.4-9 / 5.2-8); one Blender unit is one block.'
    for sc in list(bpy.data.scenes):
        if sc not in SCENES:
            bpy.data.scenes.remove(sc)
    bpy.context.preferences.filepaths.save_version = 0
    bpy.ops.wm.save_as_mainfile(filepath=str(HERE / 'main_steam_valves.blend'), compress=True)
    print('MSV BUILT', [(sc.name, len(sc.objects)) for sc in SCENES])
