"""Full-size RHR heat exchanger: a vertical shell-and-tube unit, 3 x 8 x 3 blocks (headless Blender).

Run:  blender.exe --background --factory-startup --python art/models/suppression/build_hx_multiblock.py [-- --preview DIR]

Writes art/models/suppression/rhr_hx_assembly.json, the source mesh that tools-export-hx-multiblock.py cell-splits into
the multiblock's block models, and saves the editable scene to art/models/suppression/rhr_hx_assembly.blend. It never
opens or edits another .blend. With --preview DIR it also renders three review pictures there.

Coordinates are game X/Y/Z (X east, Y up, Z south), one unit per block, machine facing NORTH. The footprint is
x 0..3, y 0..8, z 0..3; the shell axis is the vertical line x = z = 1.5. The controller is the centre of the base.

Reference form (see SUPPRESSION-BASIN.md, "Full-size exchanger", REFERENCES): Columbia's RHR exchangers are vertical
inverted U-tube units, 7,641 ft2 of tube surface each, in a 30 ft tall bay of the reactor building. So: the bolted channel
head (bonnet) is at the BOTTOM, the U-bends at the top under an ellipsoidal head, the shell stands on four lugs that sit on
a braced steel frame. Game service is kept as before: TUBE side = RHR water (red in / amber out, on the channel head),
SHELL side = cooling water (blue in low on the west side / cyan out high on the east side).
"""
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
helper = (ROOT / 'art/models/modern/build_models.py').read_text(encoding='utf-8').split('def build_pump(id):')[0]
helper = helper.replace('bpy.context.window.scene = scene', 'pass')
exec(compile(helper, 'modern/build_models.py', 'exec'))
OUT = ROOT / 'art/models/suppression'          # the helper reset OUT to art/models/modern
condenser = (ROOT / 'art/models/condenser_msiv/build_models.py').read_text(encoding='utf-8')
exec(compile('\n'.join(['import math', condenser[condenser.index('def box('):condenser.index('def scaled_flange(')]]),
             'condenser_msiv/build_models.py', 'exec'))   # box, cylinder, sweep

TAU = math.tau
CX = CZ = 1.5
SIZE = [3, 8, 3]
PORTS = [('TUBE_IN', [1, 1, 0], 'north'), ('TUBE_OUT', [1, 1, 2], 'south'),
         ('SHELL_IN', [0, 2, 1], 'west'), ('SHELL_OUT', [2, 6, 1], 'east')]


def paint(name, rgb, metal=0.0, rough=0.5):
    m = material(name, rgb, metal)
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Roughness'].default_value = rough
    return m


# The alpha.54 palette, so the big unit reads as the same machine family as the compact one.
CLAD = paint('shell aluminium cladding', (.60, .64, .66), .55, .35)
CLAD_BAND = paint('cladding band', (.34, .37, .39), .7, .3)
HEAD = paint('channel head teal enamel', (.035, .17, .21), .35, .4)
WELD = paint('weld bead', (.10, .12, .13), .6, .55)
FLG = paint('forged flange steel', (.30, .33, .35), .7, .35)
SHEET = paint('tubesheet rim', (.42, .40, .36), .75, .35)
FRAME = paint('saddle and skid graphite', (.075, .095, .11), .6, .5)
GROUT = paint('grout pad', (.36, .35, .32), .0, .9)
RED = paint('primary inlet red band', (.50, .055, .028), .25)
AMBER = paint('primary outlet amber band', (.62, .32, .03), .25)
BLUE = paint('cooling inlet blue band', (.025, .20, .48), .25)
CYAN = paint('cooling outlet cyan band', (.025, .42, .48), .25)
PLATE = paint('stainless nameplate', (.58, .60, .60), .9, .25)
PLATE_TEXT = paint('nameplate engraving', (.03, .035, .04), .0, .6)
BORE = paint('nozzle bore', (.012, .015, .018), .0, .9)
VALVE = paint('valve body bronze', (.55, .36, .12), .75, .4)
WHEEL = paint('handwheel red', (.62, .06, .035), .2, .5)
DIAL = paint('gauge dial white', (.86, .86, .82), .0, .5)
NEEDLE = paint('gauge needle black', (.02, .02, .02), .0, .5)
RELIEF = paint('relief valve yellow', (.70, .52, .04), .3, .45)
STEEL_ = STEEL

# ---------------------------------------------------------------- dimensions (metres = blocks)
R_SHELL = .75          # 1.5 m shell (about 59 in): a 7,600 ft2 U-bundle of 3/4 in tubes on ~20 ft legs
R_CLAD = .78           # aluminium lagging over the shell and top head
R_CHAN = .72           # channel barrel and bonnet (painted, not lagged: the channel flanges must stay accessible)
R_FLANGE = .90         # girth flanges and tubesheet rim
Y_BONNET = .62         # lowest point of the bottom bonnet
Y_CHAN0, Y_CHAN1 = .98, 1.74
Y_TS0, Y_TS1 = 1.92, 2.00  # tubesheet rim between the channel flange and the shell flange
Y_SHELL0, Y_SHELL1 = 2.18, 7.18
Y_TOP = Y_SHELL1 + R_CLAD / 2   # 2:1 ellipsoidal head
NOZZLE = dict(flange_r=.33, bore_r=.19, neck_r=.215)


def P(x, y, z):
    return (x, y, z)


def lathe(s, name, profile, mat, n=64, cx=CX, cz=CZ):
    """Solid of revolution about the vertical axis through (cx, cz). profile: (radius, y) from one pole/end to the other.
    A zero radius closes the surface at a pole; open ends are capped with a flat disc so the solid is closed."""
    vs, fs, rings = [], [], []
    for r, y in profile:
        if r < 1e-9:
            rings.append([len(vs)])
            vs.append((cx, y, cz))
        else:
            rings.append(list(range(len(vs), len(vs) + n)))
            vs.extend((cx + r * math.cos(i * TAU / n), y, cz + r * math.sin(i * TAU / n)) for i in range(n))
    for a, b in zip(rings, rings[1:]):
        if len(a) == 1 and len(b) == 1:
            continue
        if len(a) == 1:
            fs.extend((a[0], b[(i + 1) % n], b[i]) for i in range(n))
        elif len(b) == 1:
            fs.extend((a[i], a[(i + 1) % n], b[0]) for i in range(n))
        else:
            fs.extend((a[i], a[(i + 1) % n], b[(i + 1) % n], b[i]) for i in range(n))
    for ring in (rings[0], rings[-1]):
        if len(ring) > 1:
            fs.append(tuple(ring))
    return mesh_object(s, name, vs, fs, mat)


def ellipsoid_profile(r, y_equator, depth, up, steps=10):
    """Quarter ellipse from the equator (r, y_equator) to the pole, rising (up=True) or falling."""
    sign = 1 if up else -1
    return [(r * math.cos(t * math.pi / 2 / steps), y_equator + sign * depth * math.sin(t * math.pi / 2 / steps)) for t in range(steps + 1)]


def ring(s, name, y0, y1, r_out, r_in, mat, n=64):
    sleeve(s, name, (CX, y0, CZ), (CX, y1, CZ), r_out, r_in, mat, n)


def nut(s, name, p, axis, r=.032, h=.045):
    p, d = Vector(p), Vector(axis).normalized()
    cylinder(s, name, tuple(p), tuple(p + d * h), r, FLG, 6)


def ring_bolts(s, name, center, axis, radius, count, length, r, mat=None, nuts=False):
    center = Vector(center)
    d, u, v = basis(axis)
    for i in range(count):
        q = center + radius * (math.cos((i + .5) * TAU / count) * u + math.sin((i + .5) * TAU / count) * v)
        o = bolt(s, name + ' ' + str(i), q - d * length / 2, q + d * length / 2, r)
        if mat is not None:
            o.data.materials.clear()
            o.data.materials.append(mat)
        if nuts:
            nut(s, name + ' nut a ' + str(i), q - d * (length / 2 - .02), -d)
            nut(s, name + ' nut b ' + str(i), q + d * (length / 2 - .02), d)


def nozzle(s, name, origin, outward, band, neck_from, flange_r=.33, bore_r=.19, neck_r=.215, count=12, pad_r=None):
    """A flanged nozzle: 'origin' is the centre of the outer flange face, 'outward' points away from the vessel and
    'neck_from' is how far back along -outward the neck starts (inside the shell or channel wall)."""
    p = Vector(origin)
    d = Vector(outward).normalized()
    sleeve(s, name + ' flange', p - d * .075, p, flange_r, bore_r, FLG, 40)
    cylinder(s, name + ' bore dark', tuple(p - d * .07), tuple(p - d * .062), bore_r, BORE, 32)
    sleeve(s, name + ' raised face', p - d * .09, p - d * .075, bore_r + .05, bore_r, STEEL, 32)
    sleeve(s, name + ' weld neck hub', p - d * .17, p - d * .075, neck_r + .035, bore_r, FLG, 32, end_outer=neck_r + .06)
    cylinder(s, name + ' neck', tuple(p - d * neck_from), tuple(p - d * .17), neck_r, FLG, 32)
    sleeve(s, name + ' identification band', p - d * .30, p - d * .20, neck_r + .014, neck_r - .01, band, 32)
    ring_bolts(s, name + ' stud', p - d * .08, d, flange_r - .05, count, .14, .016)
    if pad_r:
        return pad_r


def dial(s, name, base, stem_dir, face_dir, r=.075, stem=.16, mat_ring=None):
    """A dial gauge or bimetal thermometer on a stem: base on the pipe, stem along stem_dir, dial facing face_dir."""
    b = Vector(base)
    sd = Vector(stem_dir).normalized()
    fd = Vector(face_dir).normalized()
    top = b + sd * stem
    cylinder(s, name + ' stem', tuple(b), tuple(top), .014, STEEL, 10)
    cylinder(s, name + ' socket', tuple(b), tuple(b + sd * .04), .028, FLG, 6)
    c = top + sd * r * .9
    cylinder(s, name + ' case', tuple(c - fd * .03), tuple(c + fd * .02), r, mat_ring or FLG, 24)
    cylinder(s, name + ' dial', tuple(c + fd * .02), tuple(c + fd * .024), r * .86, DIAL, 24)
    _, u, v = basis(fd)
    tip = c + fd * .026 + (u * .6 + v * .5).normalized() * r * .7
    box_from_to(s, name + ' needle', c + fd * .025, tip, .006, NEEDLE)


def box_from_to(s, name, a, b, half, mat):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + half * (x * u + y * v)) for p in (a, b) for x, y in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    fs = [(3, 2, 1, 0), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    return mesh_object(s, name, vs, fs, mat)


def gate_valve(s, name, center, axis, stem_dir, r=.05, wheel_r=.09):
    """A small globe/gate valve: body on the pipe axis, bonnet and handwheel along stem_dir."""
    c, a, sd = Vector(center), Vector(axis).normalized(), Vector(stem_dir).normalized()
    cylinder(s, name + ' body', tuple(c - a * r * 1.3), tuple(c + a * r * 1.3), r, VALVE, 16)
    for k in (-1, 1):
        cylinder(s, name + ' end flange', tuple(c + a * k * r * 1.3), tuple(c + a * k * (r * 1.3 + .02)), r * 1.25, FLG, 16)
    cylinder(s, name + ' bonnet', tuple(c), tuple(c + sd * r * 2.4), r * .6, VALVE, 12)
    cylinder(s, name + ' stem', tuple(c + sd * r * 2.4), tuple(c + sd * (r * 2.4 + .07)), .01, STEEL, 8)
    w = c + sd * (r * 2.4 + .07)
    cylinder(s, name + ' handwheel rim', tuple(w), tuple(w + sd * .016), wheel_r, WHEEL, 20)
    cylinder(s, name + ' handwheel hub', tuple(w + sd * .016), tuple(w + sd * .035), .016, STEEL, 8)


def weld_seam(s, name, y, r, n=64):
    ring(s, name, y - .009, y + .009, r + .008, r - .02, WELD, n)


# ---------------------------------------------------------------- the machine
def foundation(s):
    """Four braced column legs on grouted base plates; lugs on the shell bear on the column caps."""
    legs = [(.40, .40), (2.60, .40), (.40, 2.60), (2.60, 2.60)]
    for x, z in legs:
        box(s, 'grout pad', (x - .26, 0, z - .26), (x + .26, .035, z + .26), GROUT)
        box(s, 'column base plate', (x - .21, .035, z - .21), (x + .21, .07, z + .21), FRAME)
        for dx in (-.15, .15):
            for dz in (-.15, .15):
                cylinder(s, 'anchor bolt', (x + dx, .07, z + dz), (x + dx, .115, z + dz), .016, STEEL, 6)
                cylinder(s, 'anchor nut', (x + dx, .07, z + dz), (x + dx, .095, z + dz), .028, FLG, 6)
        # Wide-flange column: two flanges and a web, oriented along the diagonal by its square symmetry.
        y0, y1 = .07, 2.36
        box(s, 'column flange', (x - .11, y0, z - .11), (x + .11, y1, z - .085), FRAME)
        box(s, 'column flange', (x - .11, y0, z + .085), (x + .11, y1, z + .11), FRAME)
        box(s, 'column web', (x - .012, y0, z - .085), (x + .012, y1, z + .085), FRAME)
        box(s, 'column cap plate', (x - .15, y1, z - .15), (x + .15, y1 + .035, z + .15), FRAME)
    # Bracing: tie beams low on all four sides, under the nozzles; top girts under the column caps; X-bracing on the west
    # and east sides (north and south carry the channel nozzles at y 1.5, so those sides are braced by the girts only).
    for z in (.40, 2.60):
        box(s, 'tie beam', (.51, .30, z - .07), (2.49, .44, z + .07), FRAME)
        box(s, 'top girt', (.51, 2.22, z - .06), (2.49, 2.34, z + .06), FRAME)
    for x in (.40, 2.60):
        box(s, 'tie beam', (x - .07, .30, .51), (x + .07, .44, 2.49), FRAME)
        box(s, 'top girt', (x - .06, 2.22, .51), (x + .06, 2.34, 2.49), FRAME)
        box_from_to(s, 'x brace', (x, .46, .52), (x, 2.20, 2.48), .03, FRAME)
        box_from_to(s, 'x brace', (x, .46, 2.48), (x, 2.20, .52), .03, FRAME)
        for z in (.52, 2.48):
            for y in (.46, 2.20):
                box(s, 'brace gusset plate', (x - .045, y - .07, z - .07 if z < 1 else z - .03), (x + .045, y + .07, z + .03 if z < 1 else z + .07), FRAME)


def bottom_bonnet(s):
    # Ellipsoidal bonnet, channel barrel and its girth weld, all painted.
    depth = Y_CHAN0 - Y_BONNET
    prof = list(reversed(ellipsoid_profile(R_CHAN, Y_CHAN0, depth, up=False))) + [(R_CHAN, Y_CHAN1)]
    lathe(s, 'channel head and bonnet', prof, HEAD)
    weld_seam(s, 'bonnet girth weld', Y_CHAN0, R_CHAN)
    weld_seam(s, 'channel flange weld', Y_CHAN1 - .03, R_CHAN)
    # Bolted joint: channel flange, tubesheet rim, shell flange, through-studs with nuts both ends.
    ring(s, 'channel flange', Y_CHAN1, Y_TS0, R_FLANGE, R_CHAN - .05, FLG)
    ring(s, 'tubesheet', Y_TS0, Y_TS1, R_FLANGE - .015, R_CHAN - .1, SHEET)
    ring(s, 'shell flange', Y_TS1, Y_SHELL0, R_FLANGE, R_SHELL - .05, FLG)
    ring_bolts(s, 'girth flange stud', (CX, (Y_CHAN1 + Y_SHELL0) / 2, CZ), (0, 1, 0), R_FLANGE - .055, 32,
               (Y_SHELL0 - Y_CHAN1) + .12, .016, nuts=True)
    # Drain on the bottom of the bonnet (inside the controller cell), with a valve and a blind flange.
    cylinder(s, 'bonnet drain nozzle', (CX, Y_BONNET + .02, CZ), (CX, .42, CZ), .045, FLG, 16)
    gate_valve(s, 'bonnet drain valve', (CX, .33, CZ), (0, 1, 0), (1, 0, 0), r=.045, wheel_r=.075)
    cylinder(s, 'drain blind flange', (CX, .245, CZ), (CX, .22, CZ), .07, FLG, 16)
    ring_bolts(s, 'drain stud', (CX, .232, CZ), (0, 1, 0), .052, 4, .04, .008)
    # Bonnet lifting eye (for pulling the channel head) and a small vent plug on the barrel.
    box(s, 'bonnet lifting eye', (CX - .015, Y_CHAN0 - .02, CZ + R_CHAN - .02), (CX + .015, Y_CHAN0 + .14, CZ + R_CHAN + .06), FLG)
    cylinder(s, 'channel vent plug', (CX + R_CHAN - .01, Y_CHAN1 - .12, CZ), (CX + R_CHAN + .05, Y_CHAN1 - .12, CZ), .025, FLG, 8)
    # Channel (tube-side) nozzles: red RHR in (north), amber RHR out (south), through the pass partition halves.
    nozzle(s, 'tube inlet nozzle', (CX, 1.5, 0), (0, 0, -1), RED, CZ - .3)
    nozzle(s, 'tube outlet nozzle', (CX, 1.5, 3), (0, 0, 1), AMBER, CZ - .3)
    for zf, sgn in ((0, -1), (3, 1)):
        # Thermowell with a bimetal dial thermometer on top of each tube nozzle neck.
        dial(s, 'tube nozzle thermometer', (CX, 1.5 + .21, zf - sgn * .42), (0, 1, 0), (0, 0, sgn), r=.07, stem=.12)
        # Pressure gauge on a siphon at the side of the neck.
        dial(s, 'tube nozzle pressure gauge', (CX + .21, 1.5, zf - sgn * .52), (1, 0, 0), (0, 0, sgn), r=.06, stem=.12)


def shell(s):
    cylinder(s, 'shell', (CX, Y_SHELL0, CZ), (CX, Y_SHELL1, CZ), R_CLAD, CLAD, 64)
    lathe(s, 'top head lagging', [(R_CLAD, Y_SHELL1 - .001)] + ellipsoid_profile(R_CLAD, Y_SHELL1, R_CLAD / 2, up=True)[1:], CLAD)
    # Cladding: lap joints and retaining bands; a vertical lap seam on the back (south-east) quarter.
    for y in (2.95, 3.95, 4.95, 5.95, 6.85):
        ring(s, 'cladding band', y, y + .035, R_CLAD + .012, R_CLAD - .02, CLAD_BAND)
    for y in (3.45, 4.45, 5.45, 6.45):
        ring(s, 'cladding lap joint', y, y + .012, R_CLAD + .006, R_CLAD - .02, CLAD)
    a = math.radians(35)
    box_from_to(s, 'cladding vertical lap seam', (CX + R_CLAD * math.cos(a), Y_SHELL0 + .05, CZ + R_CLAD * math.sin(a)),
                (CX + R_CLAD * math.cos(a), Y_SHELL1 - .02, CZ + R_CLAD * math.sin(a)), .012, CLAD_BAND)
    # The lagging stops short of the shell flange so the girth joint can be inspected: a bare painted band with its weld.
    lathe(s, 'shell bottom course', [(R_SHELL, Y_SHELL0 - .001), (R_SHELL, Y_SHELL0 + .1)], HEAD)
    weld_seam(s, 'shell flange weld', Y_SHELL0 + .05, R_SHELL)
    ring(s, 'lagging end cap', Y_SHELL0 + .1, Y_SHELL0 + .12, R_CLAD + .01, R_SHELL - .01, CLAD_BAND)
    # Shell-side nozzles: blue cooling water in low (west), cyan out high (east), each on a reinforcing pad.
    nozzle(s, 'shell inlet nozzle', (0, 2.5, CZ), (-1, 0, 0), BLUE, CX - .45)
    nozzle(s, 'shell outlet nozzle', (3, 6.5, CZ), (1, 0, 0), CYAN, CX - .45)
    for x0, x1, y in ((CX - R_CLAD - .03, CX - R_CLAD + .03, 2.5), (CX + R_CLAD - .03, CX + R_CLAD + .03, 6.5)):
        cylinder(s, 'nozzle reinforcing pad', (x0, y, CZ), (x1, y, CZ), .32, CLAD_BAND, 40)
    dial(s, 'shell inlet thermometer', (.45, 2.5 + .21, CZ), (0, 1, 0), (-1, 0, 0), r=.07, stem=.12)
    dial(s, 'shell inlet pressure gauge', (.32, 2.5, CZ - .21), (0, 0, -1), (-1, 0, 0), r=.06, stem=.12)
    dial(s, 'shell outlet thermometer', (2.55, 6.5 + .21, CZ), (0, 1, 0), (1, 0, 0), r=.07, stem=.12)
    dial(s, 'shell outlet pressure gauge', (2.68, 6.5, CZ + .21), (0, 0, 1), (1, 0, 0), r=.06, stem=.12)
    # Four support lugs on the diagonals, bearing on the column caps, with two gussets each.
    for k in range(4):
        ang = math.radians(45 + 90 * k)
        d = Vector((math.cos(ang), 0, math.sin(ang)))
        t = Vector((-d.z, 0, d.x))
        c0 = Vector((CX, 0, CZ)) + d * (R_CLAD - .02)
        c1 = Vector((CX, 0, CZ)) + d * 1.50
        y0, y1 = 2.395, 2.44
        lug = [c0 + t * .17, c1 + t * .17, c1 - t * .17, c0 - t * .17]
        mesh_object(s, 'support lug base plate', [(p.x, y0, p.z) for p in lug] + [(p.x, y1, p.z) for p in lug],
                    [(3, 2, 1, 0), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)], FRAME)
        for side in (-1, 1):
            g0 = c0 + t * side * .12
            g1 = c1 + t * side * .12 - d * .08
            prof = [(g0, y1), (g1, y1), (g0, y1 + .42)]
            off = t * .012
            vs = [(p.x - off.x, y, p.z - off.z) for p, y in prof] + [(p.x + off.x, y, p.z + off.z) for p, y in prof]
            mesh_object(s, 'support lug gusset', vs, [(2, 1, 0), (3, 4, 5), (0, 1, 4, 3), (1, 2, 5, 4), (2, 0, 3, 5)], FRAME)
        cap = c1 - d * .09
        for side in (-1, 1):
            q = cap + t * side * .09
            cylinder(s, 'lug anchor bolt', (q.x, y0 - .03, q.z), (q.x, y1 + .05, q.z), .015, STEEL, 6)
            cylinder(s, 'lug anchor nut', (q.x, y1, q.z), (q.x, y1 + .03, q.z), .028, FLG, 6)
    # Shell drain just above the shell flange (south-east), with a valve.
    a = math.radians(60)
    p0 = Vector((CX + R_CLAD * math.cos(a), 2.32, CZ + R_CLAD * math.sin(a)))
    out = Vector((math.cos(a), 0, math.sin(a)))
    cylinder(s, 'shell drain pipe', tuple(p0 - out * .05), tuple(p0 + out * .24), .03, STEEL, 12)
    gate_valve(s, 'shell drain valve', tuple(p0 + out * .16), tuple(out), (0, 1, 0), r=.04, wheel_r=.07)


def top_fittings(s):
    # Vent on the crown of the top head: nozzle, valve, handwheel.
    cylinder(s, 'top vent nozzle', (CX, Y_TOP - .03, CZ), (CX, Y_TOP + .09, CZ), .045, FLG, 16)
    cylinder(s, 'top vent flange', (CX, Y_TOP + .09, CZ), (CX, Y_TOP + .12, CZ), .075, FLG, 16)
    gate_valve(s, 'top vent valve', (CX, Y_TOP + .19, CZ), (0, 1, 0), (1, 0, 0), r=.045, wheel_r=.075)
    cylinder(s, 'vent outlet cap', (CX, Y_TOP + .26, CZ), (CX, Y_TOP + .30, CZ), .05, FLG, 12)
    # Two lifting padeyes on the top head, one each side of the vent (axis along X).
    for x in (CX - .40, CX + .40):
        prof = [(x - .14, 7.33), (x + .14, 7.33), (x + .07, 7.64), (x - .07, 7.64)]
        mesh_object(s, 'lifting padeye', [(px, py, CZ - .025) for px, py in prof] + [(px, py, CZ + .025) for px, py in prof],
                    [(3, 2, 1, 0), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)], FLG)
        sleeve(s, 'padeye cheek ring', (x, 7.54, CZ - .04), (x, 7.54, CZ + .04), .06, .032, FLG, 16)
        cylinder(s, 'padeye hole', (x, 7.54, CZ - .041), (x, 7.54, CZ + .041), .03, BORE, 16)
    # Shell-side thermal relief valve (cooling water side), high on the north face: branch, elbow up, spring bonnet,
    # discharge elbow to the side.
    b0 = Vector((CX + .25, 6.95, CZ - R_CLAD + .05))
    cylinder(s, 'relief branch', tuple(b0), (b0.x, b0.y, .62), .05, STEEL, 14)
    cylinder(s, 'relief branch flange', (b0.x, b0.y, .66), (b0.x, b0.y, .62), .085, FLG, 16)
    sweep(s, 'relief inlet elbow', [(b0.x, b0.y, .62), (b0.x, b0.y, .52), (b0.x, b0.y + .04, .47), (b0.x, b0.y + .12, .45)], .05, STEEL, 12)
    vb = Vector((b0.x, b0.y + .12, .45))
    cylinder(s, 'relief inlet flange', tuple(vb), (vb.x, vb.y + .03, vb.z), .085, FLG, 16)
    cylinder(s, 'relief valve body', (vb.x, vb.y + .03, vb.z), (vb.x, vb.y + .17, vb.z), .07, RELIEF, 16)
    cylinder(s, 'relief spring bonnet', (vb.x, vb.y + .17, vb.z), (vb.x, vb.y + .38, vb.z), .045, RELIEF, 14)
    cylinder(s, 'relief cap', (vb.x, vb.y + .38, vb.z), (vb.x, vb.y + .43, vb.z), .03, FLG, 10)
    box(s, 'relief test lever', (vb.x - .008, vb.y + .40, vb.z - .01), (vb.x + .008, vb.y + .41, vb.z - .14), FLG)
    sweep(s, 'relief discharge', [(vb.x + .06, vb.y + .10, vb.z), (vb.x + .18, vb.y + .10, vb.z), (vb.x + .23, vb.y + .05, vb.z),
                                   (vb.x + .25, vb.y - .10, vb.z)], .035, STEEL, 10)
    # Nameplate on a standoff bracket on the front (north) face of the lagging, generic engraving only.
    zf = CZ - R_CLAD
    box(s, 'nameplate bracket', (CX - .22, 4.42, zf - .035), (CX + .22, 4.74, zf + .02), FLG)
    box(s, 'nameplate', (CX - .2, 4.44, zf - .042), (CX + .2, 4.72, zf - .035), PLATE)
    for w, y in ((.30, 4.66), (.22, 4.62), (.26, 4.58), (.18, 4.54), (.28, 4.50)):
        box(s, 'nameplate engraving', (CX - .17, y, zf - .0435), (CX - .17 + w, y + .016, zf - .042), PLATE_TEXT)
    for x in (CX - .185, CX + .185):
        for y in (4.455, 4.705):
            cylinder(s, 'nameplate rivet', (x, y, zf - .042), (x, y, zf - .046), .007, STEEL, 6)
    # Equipment tag band (a plain painted stripe) above the nameplate.
    ring(s, 'equipment tag band', 5.10, 5.16, R_CLAD + .01, R_CLAD - .02, HEAD)


def build():
    scene = bpy.context.scene
    for o in list(scene.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    scene.name = 'BWR | full-size RHR heat exchanger'
    foundation(scene)
    bottom_bonnet(scene)
    shell(scene)
    top_fittings(scene)
    meta = {'id': 'rhr_hx_assembly', 'size': SIZE, 'controller': [1, 0, 1],
            'ports': [{'role': r, 'cell': c, 'face': f} for r, c, f in PORTS]}
    export_scene(scene, 'rhr_hx_assembly.json', meta)
    return scene


def preview(scene, out_dir):
    cam = bpy.data.cameras.new('preview camera')
    co = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(co)
    scene.camera = co
    cam.lens = 50
    world = bpy.data.worlds.new('preview world')
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.32, .35, .39, 1)
    bg.inputs['Strength'].default_value = .9
    scene.world = world
    sun = bpy.data.lights.new('preview sun', 'SUN')
    sun.energy = 3.2
    so = bpy.data.objects.new(sun.name, sun)
    scene.collection.objects.link(so)
    so.rotation_euler = (math.radians(50), 0, math.radians(35))
    floor = box(scene, '_preview floor', (-6, -.02, -6), (9, 0, 9), GROUT)
    scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x, scene.render.resolution_y = 1200, 1500
    scene.render.image_settings.file_format = 'PNG'
    scene.view_settings.view_transform = 'Standard'
    views = {'a': ((-6.5, 9.0, -9.0), (1.5, 3.8, 1.5), 35), 'b': ((9.5, 6.5, -5.0), (1.5, 3.8, 1.5), 35),
             'c': ((-1.2, 2.4, -3.6), (1.5, 1.6, 1.0), 35), 'd': ((3.6, 9.6, -2.8), (1.5, 7.0, 1.4), 35)}
    for k, (eye, look, lens) in views.items():
        e, t = xyz(eye), xyz(look)
        cam.lens = lens
        co.location = e
        co.rotation_euler = (t - e).to_track_quat('-Z', 'Y').to_euler()
        scene.render.filepath = str(Path(out_dir) / f'rhr_hx_assembly_{k}.png')
        bpy.ops.render.render(write_still=True)
    bpy.data.objects.remove(floor, do_unlink=True)
    for o in (co, so):
        bpy.data.objects.remove(o, do_unlink=True)


if __name__ == '__main__':
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    s = build()
    if '--preview' in argv:
        preview(s, argv[argv.index('--preview') + 1])
    if '--no-save' not in argv:
        bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'rhr_hx_assembly.blend'), compress=True)
    print('BWR HX MULTIBLOCK BUILD DONE')
