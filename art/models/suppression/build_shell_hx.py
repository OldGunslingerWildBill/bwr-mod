"""Shell-and-tube RHR heat exchanger and the water outlet chute (headless Blender).

Run:  blender.exe --background --python art/models/suppression/build_shell_hx.py

Writes art/models/suppression/heat_exchanger.json and water_outlet_chute.json (the files tools-export-suppression.py
bakes into OBJ resources), plus two reference renders and the editable .blend. Nothing here opens a GUI or touches any
other .blend. Coordinates are game X/Y/Z, one unit per block, default facing NORTH.

Shell-and-tube exchanger, one block. The tube axis runs along Z. TUBE side (the hot process / RHR water) enters and
leaves through the two axial nozzles of the channel heads (north face red, south face amber). The SHELL side (cooling
water) uses the two radial shell nozzles on the west (blue, in) and east (cyan, out) faces. Port faces and centres are
the same as the plate exchanger this replaces, so every placed exchanger keeps its connections.
"""
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'art/models/suppression'

# The shared Blender helpers (materials, mesh_object, sleeve, bolt, export_scene). They were written for the GUI session
# and assign bpy.context.window.scene, which does not exist in a background run, so those lines are dropped: every scene
# is simply the one default scene, emptied between models.
helper = (ROOT / 'art/models/modern/build_models.py').read_text(encoding='utf-8').split('def build_pump(id):')[0]
helper = helper.replace('bpy.context.window.scene = scene', 'pass')
exec(compile(helper, 'modern/build_models.py', 'exec'))
OUT = ROOT / 'art/models/suppression'          # the helper reset OUT to art/models/modern
condenser = (ROOT / 'art/models/condenser_msiv/build_models.py').read_text(encoding='utf-8')
exec(compile('\n'.join(['import math', condenser[condenser.index('def box('):condenser.index('def scaled_flange(')]]),
             'condenser_msiv/build_models.py', 'exec'))   # box, cylinder, sweep


def new_scene(name):
    scene = bpy.context.scene
    for o in list(scene.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    scene.name = name
    return scene


def paint(name, rgb, metal=0.0, rough=0.5):
    m = material(name, rgb, metal)
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Roughness'].default_value = rough
    return m


CLAD = paint('shell aluminium cladding', (.60, .64, .66), .55, .35)
CLAD_BAND = paint('cladding band', (.34, .37, .39), .7, .3)
HEAD = paint('channel head teal enamel', (.035, .17, .21), .35, .4)
FLG = paint('forged flange steel', (.30, .33, .35), .7, .35)
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
CEMENT = paint('warm cast concrete', (.48, .46, .42), .0, .9)
COPING = paint('precast coping', (.64, .62, .57), .0, .9)
JOINT = paint('concrete expansion joint', (.19, .19, .17), .0, .9)
TEAL = paint('discharge band', (.025, .45, .32), .25)
STEEL_ = STEEL


def prism(s, name, profile, axis, lo, hi, mat):
    """Extrude a flat profile (list of 2D points, counter-clockwise) between lo and hi along axis 'x', 'y' or 'z'.
    'x': profile is (z, y); 'z': profile is (x, y)."""
    n = len(profile)

    def pt(p, t):
        if axis == 'z':
            return (p[0], p[1], t)
        if axis == 'x':
            return (t, p[1], p[0])
        return (p[0], t, p[1])
    vs = [pt(p, lo) for p in profile] + [pt(p, hi) for p in profile]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))]
    fs += [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def frustum(s, name, a, b, ra, rb, mat, n=32):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for p, r in ((a, ra), (b, rb)) for i in range(n)]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))] + [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def ring_bolts(s, name, center, axis, radius, count, length, r, mat=None):
    center = Vector(center)
    d, u, v = basis(axis)
    for i in range(count):
        q = center + radius * (math.cos(i * math.tau / count) * u + math.sin(i * math.tau / count) * v)
        o = bolt(s, name + ' ' + str(i), q - d * length / 2, q + d * length / 2, r)
        if mat is not None:
            o.data.materials.clear()
            o.data.materials.append(mat)


def nozzle(s, name, origin, outward, band, flange_r=.20, bore_r=.13, neck_r=.145, neck_len=.07, count=8):
    """A flanged nozzle: 'origin' is the centre of the outer flange face, 'outward' points away from the vessel."""
    p = Vector(origin)
    d = Vector(outward).normalized()
    sleeve(s, name + ' flange', p - d * .05, p, flange_r, bore_r, FLG, 32)
    cylinder(s, name + ' bore dark', p - d * .048, p - d * .043, bore_r, BORE, 32)  # in front of the neck's end cap, which lies at .05
    sleeve(s, name + ' raised face', p - d * .060, p - d * .05, bore_r + .035, bore_r, STEEL, 32)
    cylinder(s, name + ' neck', p - d * (.05 + neck_len), p - d * .05, neck_r, FLG, 32)
    sleeve(s, name + ' identification band', p - d * .105, p - d * .06, neck_r + .012, neck_r - .01, band, 32)
    ring_bolts(s, name + ' stud', p, d, flange_r - .033, count, .09, .012)


def z_of(t, south):
    return 1 - t if south else t


SHELL_R = .22


def channel_head(s, south):
    """North (south=False) or south (south=True) channel head, measured by distance t from its end face."""
    band = AMBER if south else RED
    nm = 'south ' if south else 'north '
    cx, cy = .5, .5

    def P(t, x=cx, y=cy):
        return (x, y, z_of(t, south))
    nozzle(s, nm + 'tube nozzle', P(0), (0, 0, 1 if south else -1), band)
    frustum(s, nm + 'dished head', P(.10), P(.15), .145, .215, HEAD, 40)
    frustum(s, nm + 'knuckle', P(.15), P(.165), .215, .225, HEAD, 40)
    cylinder(s, nm + 'bonnet barrel', P(.165), P(.19), .225, HEAD, 48)
    # Bolted channel flange pair against the shell flange.
    cylinder(s, nm + 'channel flange', P(.19), P(.22), .27, FLG, 48)
    cylinder(s, nm + 'shell flange', P(.22), P(.25), .27, FLG, 48)
    ring_bolts(s, nm + 'flange bolt', P(.22), (0, 0, 1), .248, 20, .085, .011)
    for k in (-1, 1):  # small bonnet vent / drain plugs
        cylinder(s, nm + 'bonnet plug', P(.178, cx, cy + k * .22), P(.178, cx, cy + k * .255), .018, FLG, 12)
    box(s, nm + 'head lifting eye', (cx - .012, cy + .215, z_of(.14, south) - .03), (cx + .012, cy + .275, z_of(.14, south) + .03), FLG)


def saddle(s, z0, w=.06):
    R, y0 = SHELL_R + .012, .075
    a_max = math.radians(58)
    arc = [(.5 + R * math.sin(a), .5 - R * math.cos(a)) for a in [(-a_max) + 2 * a_max * i / 10 for i in range(11)]]
    prof = [(.315, y0), (.685, y0)] + list(reversed(arc))
    prism(s, 'shell saddle', prof, 'z', z0, z0 + w, FRAME)
    box(s, 'saddle base plate', (.28, .06, z0 - .012), (.72, y0 + .012, z0 + w + .012), FRAME)
    for x in (.31, .69):
        for z in (z0 + .01, z0 + w - .01):
            cylinder(s, 'anchor bolt', (x, .07, z), (x, .105, z), .011, STEEL, 8)


def build_exchanger():
    s = new_scene('BWR | shell and tube RHR heat exchanger')
    # Foundation: grout pad and a longitudinal skid under the saddles
    box(s, 'grouted foundation pad', (.19, 0, .05), (.81, .035, .95), GROUT)
    for x in (.24, .70):
        box(s, 'skid beam', (x, .035, .08), (x + .06, .06, .92), FRAME)
    for z in (.31, .63):
        saddle(s, z)
    # Shell
    cylinder(s, 'shell', (.5, .5, .25), (.5, .5, .75), SHELL_R, CLAD, 64)
    for z in (.385, .60):
        cylinder(s, 'cladding band', (.5, .5, z), (.5, .5, z + .014), SHELL_R + .008, CLAD_BAND, 64)
    channel_head(s, False)
    channel_head(s, True)
    # Shell-side nozzles: west cooling water in (blue), east cooling water out (cyan). Radial, horizontal.
    nozzle(s, 'shell inlet nozzle', (0, .5, .5), (-1, 0, 0), BLUE, neck_len=.24)
    nozzle(s, 'shell outlet nozzle', (1, .5, .5), (1, 0, 0), CYAN, neck_len=.24)
    for x0, x1 in ((.262, .30), (.70, .738)):
        cylinder(s, 'nozzle reinforcing pad', (x0, .5, .5), (x1, .5, .5), .175, CLAD_BAND, 32)
    # Vent (top) with a small gate valve and handwheel; drain (bottom centre) with a blind flange.
    cylinder(s, 'vent stem pipe', (.5, .70, .565), (.5, .80, .565), .026, STEEL, 12)
    box(s, 'vent valve body', (.465, .76, .54), (.535, .80, .59), VALVE)
    cylinder(s, 'vent valve stem', (.5, .80, .565), (.5, .845, .565), .011, STEEL, 8)
    cylinder(s, 'vent handwheel', (.5, .845, .565), (.5, .857, .565), .05, WHEEL, 20)
    cylinder(s, 'vent hub', (.5, .857, .565), (.5, .875, .565), .015, STEEL, 8)
    cylinder(s, 'drain pipe', (.5, .285, .5), (.5, .105, .5), .026, STEEL, 12)
    cylinder(s, 'drain blind flange', (.5, .105, .5), (.5, .085, .5), .05, FLG, 16)
    ring_bolts(s, 'drain stud', (.5, .095, .5), (0, 1, 0), .038, 4, .03, .008)
    # Lifting lugs and nameplate
    for z in (.31, .69):
        lug = [(z - .04, .70), (z + .04, .70), (z + .014, .775), (z - .014, .775)]
        prism(s, 'lifting lug', lug, 'x', .488, .512, FLG)
    box(s, 'nameplate pad', (.41, .706, .385), (.59, .72, .475), FLG)
    box(s, 'nameplate', (.42, .72, .392), (.58, .724, .468), PLATE)
    for w, z in ((.12, .415), (.10, .430), (.12, .445)):
        box(s, 'nameplate engraving', (.44, .724, z), (.44 + w, .7245, z + .007), PLATE_TEXT)
    for x in (.43, .57):
        for z in (.40, .46):
            cylinder(s, 'nameplate rivet', (x, .724, z), (x, .7255, z), .005, STEEL, 6)
    export_scene(s, 'heat_exchanger.json', {'id': 'heat_exchanger'})
    return s


def build_chute():
    s = new_scene('BWR | water outlet chute')
    # Apron slab, wider than the headwall so the discharge never undercuts it
    box(s, 'outfall apron', (0, 0, 0), (1, .10, 1), CEMENT)
    box(s, 'apron expansion joint', (0.02, .10, .49), (.98, .103, .505), JOINT)
    # Headwall (receives the pipe) and its coping
    box(s, 'headwall', (.06, .10, .52), (.94, .76, 1.0), CEMENT)
    box(s, 'headwall coping', (.04, .76, .50), (.96, .82, 1.0), COPING)
    for x in (.18, .5, .82):
        box(s, 'form seam', (x - .004, .12, .519), (x + .004, .74, .522), JOINT)
    for y in (.30, .48):
        box(s, 'horizontal form seam', (.08, y, .519), (.92, y + .008, .522), JOINT)
    # Inlet flange on the back face (water arrives from the plant line)
    nozzle(s, 'chute inlet', (.5, .5, 1.0), (0, 0, 1), TEAL, flange_r=.25, bore_r=.15, neck_r=.17, neck_len=.12)
    # Discharge stub on the front face
    sleeve(s, 'discharge nozzle', (.5, .5, .43), (.5, .5, .525), .21, .15, FLG, 32)
    sleeve(s, 'discharge nozzle collar', (.5, .5, .50), (.5, .5, .535), .25, .21, STEEL, 32)
    cylinder(s, 'discharge bore dark', (.5, .5, .507), (.5, .5, .514), .15, BORE, 32)
    # Trough: floor and side walls, sloping to the lip
    prism(s, 'chute floor', [(.52, .10), (.52, .28), (.035, .145), (.035, .10)], 'x', .16, .84, CEMENT)
    wall = [(.52, .10), (.52, .46), (.28, .36), (.0, .27), (.0, .10)]
    prism(s, 'chute side wall west', wall, 'x', .06, .16, CEMENT)
    prism(s, 'chute side wall east', wall, 'x', .84, .94, CEMENT)
    for x in (.04, .84):
        prism(s, 'side wall coping', [(.52, .46), (.28, .36), (.0, .27), (.0, .30), (.28, .39), (.52, .50)], 'x', x, x + .12, COPING)
    # Energy-dissipating baffle blocks near the lip, and a splash sill
    for x in (.24, .44, .64):
        box(s, 'baffle block', (x, .17, .10), (x + .12, .26, .20), COPING)
    box(s, 'lip sill', (.16, .10, .0), (.84, .165, .035), COPING)
    # Guard rail along the top of the headwall
    for x in (.08, .92):
        for z in (.54, .96):
            cylinder(s, 'rail post', (x, .82, z), (x, .94, z), .014, STEEL, 8)
        cylinder(s, 'guard rail', (x, .94, .54), (x, .94, .96), .011, STEEL, 8)
        cylinder(s, 'mid rail', (x, .88, .54), (x, .88, .96), .008, STEEL, 8)
    # Identification plate on the headwall front, above the nozzle
    box(s, 'identification plate', (.38, .64, .513), (.62, .70, .52), TEAL)
    export_scene(s, 'water_outlet_chute.json', {'id': 'water_outlet_chute'})
    return s


def preview(builder, name, eye, target, size):
    s = builder()
    cam = bpy.data.cameras.new(name + ' camera')
    o = bpy.data.objects.new(cam.name, cam)
    s.collection.objects.link(o)
    o.location = xyz(eye)
    o.rotation_euler = (xyz(target) - o.location).to_track_quat('-Z', 'Y').to_euler()
    cam.type = 'ORTHO'
    cam.ortho_scale = size
    s.camera = o
    for label, p, power in (('key', (-2, 4, -2), 700), ('fill', (4, 2.5, 3), 400), ('rim', (1, 4, 4), 450)):
        data = bpy.data.lights.new(name + label, type='AREA')
        data.energy = power
        data.size = 3
        light = bpy.data.objects.new(data.name, data)
        s.collection.objects.link(light)
        light.location = xyz(p)
        light.rotation_euler = (xyz(target) - light.location).to_track_quat('-Z', 'Y').to_euler()
    world = bpy.data.worlds.new(name + ' world')
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.20, .23, .27, 1)
    bg.inputs['Strength'].default_value = .6
    s.world = world
    s.render.engine = 'CYCLES'
    s.cycles.samples = 48
    s.cycles.device = 'CPU'
    s.render.resolution_x, s.render.resolution_y = 1200, 900
    s.render.image_settings.file_format = 'PNG'
    s.render.filepath = str(OUT / (name + '.png'))
    bpy.ops.render.render(write_still=True)


if __name__ == '__main__':
    preview(build_exchanger, 'shell-tube-heat-exchanger', (2.6, 1.9, -1.9), (.5, .45, .5), 2.0)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'shell_tube_heat_exchanger.blend'))
    preview(build_chute, 'water-outlet-chute', (2.6, 1.9, -1.9), (.5, .4, .5), 2.0)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'water_outlet_chute.blend'))
    print('BWR SHELL HX BUILD DONE')
