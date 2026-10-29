"""Blender-authored turbine auxiliaries: main oil tank, generator cooling skid, shaft oil pump, the duplex oil filter housing (four
states) and the lube oil conditioner.
Run headless:

    blender.exe --background --python art/models/turbine_aux/build_models.py

Writes <id>.json for each block (mesh and UV data for tools-export-turbine-aux.py), turbine_aux.blend (the editable
scenes) and preview PNGs. One unit is one block; game coordinates are X right, Y up, Z south; every model is drawn facing
NORTH (its front is -Z). Pipe flanges sit on the block faces and match the round High-Pressure pipes (centre 0.5, 0.5).
"""
import json
import math
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector

OUT = Path(__file__).resolve().parent
SCENES = []


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


def game(p):
    return (p[0], p[2], -p[1])


def material(name, rgb, metal=0.0):
    m = bpy.data.materials.new('Turbine aux | ' + name)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value = (*rgb, 1)
    bsdf.inputs['Metallic'].default_value = metal
    bsdf.inputs['Roughness'].default_value = 0.32
    return m


STEEL = material('machined steel', (.48, .55, .59), .65)
BODY = material('pipe steel', (.24, .29, .32), .6)
DARK = material('gasket', (.022, .028, .033))
BLUE = material('motor blue', (.015, .28, .55))
TEAL = material('water band', (.025, .45, .32))
PALE = material('gas cylinder', (.62, .70, .76), .35)
AMBER = material('oil amber', (.62, .40, .08))
GLASS = material('sight glass', (.10, .25, .30))
YELLOW = material('guard yellow', (.80, .60, .05))
GREY = material('frame grey', (.30, .33, .35), .5)
STEAMBAND = material('steam band', (.10, .11, .12), .5)
RED = material('valve red', (.55, .06, .04))
BROWN = material('lube oil pipe brown', (.30, .16, .055))


def mesh_object(scene, name, vs, fs, mat):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([xyz(p) for p in vs], [], fs)
    mesh.update()
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(mesh)
    bm.free()
    uv = mesh.uv_layers.new(name='Material UV')
    for loop in uv.data:
        loop.uv = (.5, .5)
    o = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(o)
    mesh.materials.append(mat)
    return o


def basis(axis):
    d = Vector(axis).normalized()
    a = d.cross(Vector((0, 1, 0)) if abs(d.y) < .9 else Vector((1, 0, 0))).normalized()
    return d, a, d.cross(a).normalized()


def sleeve(scene, name, a, b, outer, inner, mat, segments=20):
    """Hollow spool with annular end faces; no plug in its bore. inner = 0 makes it a solid cylinder."""
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    if inner <= 0:
        vs = [tuple(p + outer * (math.cos(t * math.tau / segments) * u + math.sin(t * math.tau / segments) * v))
              for p in (a, b) for t in range(segments)]
        fs = [tuple(range(segments - 1, -1, -1)), tuple(range(segments, 2 * segments))]
        fs += [(i, (i + 1) % segments, (i + 1) % segments + segments, i + segments) for i in range(segments)]
        return mesh_object(scene, name, vs, fs, mat)
    rings = [(a, outer), (b, outer), (b, inner), (a, inner)]
    vs = [tuple(p + radius * (math.cos(t * math.tau / segments) * u + math.sin(t * math.tau / segments) * v))
          for p, radius in rings for t in range(segments)]
    fs = [(r * segments + t, r * segments + (t + 1) % segments, ((r + 1) % 4) * segments + (t + 1) % segments, ((r + 1) % 4) * segments + t)
          for r in range(4) for t in range(segments)]
    return mesh_object(scene, name, vs, fs, mat)


def bolt(scene, name, a, b, radius=.025):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    n = 6
    vs = [tuple(p + radius * (math.cos(t * math.tau / n) * u + math.sin(t * math.tau / n) * v)) for p in (a, b) for t in range(n)]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))]
    fs += [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(scene, name, vs, fs, STEEL)


def flange(scene, name, endpoint, outward, radius=.31, bolts=8, band=None):
    p = Vector(endpoint)
    d, u, v = basis(outward)
    sleeve(scene, name + ' flange', p - d * .12, p - d * .025, radius, .205, STEEL)
    sleeve(scene, name + ' gasket', p - d * .025, p, radius * .93, .205, DARK)
    if band is not None:
        sleeve(scene, name + ' band', p - d * .105, p - d * .06, radius + .006, radius - .03, band)
    for i in range(bolts):
        c = p + radius * .81 * (math.cos(i * math.tau / bolts) * u + math.sin(i * math.tau / bolts) * v)
        bolt(scene, name + ' stud ' + str(i), c - d * .14, c - d * .018, .026)


def box(scene, name, lo, hi, mat):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    vs = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    fs = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5)]
    return mesh_object(scene, name, vs, fs, mat)


def new_scene(name):
    scene = bpy.data.scenes.new(name)
    SCENES.append(scene)
    return scene


def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def export_scene(scene, filename, meta):
    faces, boxes, mats = [], [], {}
    for o in scene.objects:
        if o.type != 'MESH':
            continue
        me = o.data  # plain meshes at the origin: nothing to evaluate
        me.calc_loop_triangles()
        points = [game(o.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in points) for a in range(3)] + [max(p[a] for p in points) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            key = m.name
            if key not in mats:
                bsdf = next((n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled'), None) if m.node_tree else None
                rgb = list(bsdf.inputs['Base Color'].default_value[:3]) if bsdf else list(m.diffuse_color[:3])
                mats[key] = {'color': [srgb(x) for x in rgb], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(points[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3]) - Vector(vs[0][:3])).cross(Vector(vs[2][:3]) - Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
    (OUT / filename).write_text(json.dumps(dict(meta, faces=faces, boxes=boxes, materials=mats), separators=(',', ':')), encoding='utf-8')
    print(filename, len(faces), 'triangles')




def cyl(scene, name, a, b, radius, mat, segments=20):
    """Solid cylinder between two points (game coordinates)."""
    return sleeve(scene, name, a, b, radius, 0, mat, segments)


def water_flange(scene, x, outward):
    flange(scene, 'water connection', (x, .5, .5), outward, radius=.30, bolts=8, band=TEAL)


def cooler(scene, y=.5, z=.5, radius=.15, x0=.12, x1=.88):
    """Shell-and-tube cooler along X: shell, tube sheets, two water boxes and saddles. Water bore reaches both faces."""
    cyl(scene, 'cooler shell', (x0 + .06, y, z), (x1 - .06, y, z), radius, STEEL)
    for x in (x0 + .06, x1 - .06):
        sleeve(scene, 'cooler shell ring', (x - .02, y, z), (x + .02, y, z), radius + .018, radius - .01, BODY)
    for x in (x0, x1):
        cyl(scene, 'water box', (x, y, z), (x + (.07 if x < .5 else -.07), y, z), radius + .02, BLUE)
    sleeve(scene, 'water bore west', (0, y, z), (x0, y, z), .225, .19, BODY)
    sleeve(scene, 'water bore east', (x1, y, z), (1, y, z), .225, .19, BODY)
    for x in (.3, .7):
        box(scene, 'cooler saddle', (x - .035, y - radius - .045, z - radius * .75), (x + .035, y - radius * .55, z + radius * .75), GREY)
    for x in (.4, .6):
        sleeve(scene, 'cooler tube nozzle', (x, y + radius - .01, z), (x, y + radius + .04, z), .028, .012, DARK)


# ---- main oil tank: reservoir drum under the cooler, pumps on a shelf, DC box on the top face ----
scene = new_scene('BWR | main oil tank')
box(scene, 'base frame', (.05, 0, .06), (.95, .045, .94), GREY)
for x in (.08, .92):
    for z in (.09, .91):
        box(scene, 'frame post', (x - .02, .045, z - .02), (x + .02, .66, z + .02), GREY)
cyl(scene, 'oil reservoir', (.12, .2, .5), (.88, .2, .5), .165, BODY)
for x in (.12, .88):
    cyl(scene, 'reservoir dished head', (x - .02, .2, .5), (x + .02, .2, .5), .17, STEEL)
for x in (.3, .7):
    box(scene, 'reservoir band', (x - .012, .02, .32), (x + .012, .38, .68), STEEL)
box(scene, 'sight glass frame', (.42, .12, .335), (.58, .30, .352), STEEL)
box(scene, 'sight glass', (.45, .14, .325), (.55, .28, .338), GLASS)
box(scene, 'oil in sight glass', (.45, .14, .322), (.55, .21, .327), AMBER)
sleeve(scene, 'filler neck', (.26, .35, .5), (.26, .395, .5), .035, .02, STEEL)
cooler(scene, y=.52, z=.5, radius=.13)
box(scene, 'pump shelf', (.1, .66, .56), (.9, .69, .94), GREY)
box(scene, 'AC pump plinth', (.14, .69, .62), (.52, .72, .9), STEEL)
cyl(scene, 'AC pump motor', (.33, .72, .76), (.33, .86, .76), .12, BLUE)
cyl(scene, 'AC motor cooling fins', (.33, .76, .76), (.33, .80, .76), .128, STEEL)
cyl(scene, 'AC motor fan cover', (.33, .86, .76), (.33, .92, .76), .105, STEEL)
bolt(scene, 'AC motor lifting eye', (.33, .92, .76), (.33, .96, .76), .018)
cyl(scene, 'AC pump casing', (.33, .66, .76), (.33, .72, .76), .08, BODY)
box(scene, 'DC pump plinth', (.58, .69, .62), (.9, .72, .9), STEEL)
cyl(scene, 'DC pump motor', (.68, .72, .76), (.68, .86, .76), .075, TEAL)
cyl(scene, 'DC pump motor cap', (.68, .86, .76), (.68, .89, .76), .06, STEEL)
box(scene, 'DC junction box', (.76, .72, .66), (.88, .86, .80), BLUE)
box(scene, 'DC junction lid', (.755, .86, .655), (.885, .885, .805), STEEL)
sleeve(scene, 'DC conduit', (.82, .885, .73), (.82, .975, .73), .022, .01, DARK)
box(scene, 'DC label plate', (.77, .74, .655), (.87, .80, .66), YELLOW)
box(scene, 'AC junction box', (.40, .36, .93), (.62, .56, .995), BLUE)
box(scene, 'AC junction lid', (.395, .555, .925), (.625, .575, .995), STEEL)
sleeve(scene, 'AC conduit', (.51, .5, .93), (.51, .5, 1.0), .02, .01, DARK)
water_flange(scene, 0.0, (-1, 0, 0))
water_flange(scene, 1.0, (1, 0, 0))
export_scene(scene, 'main_oil_tank.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})

# ---- generator cooling skid: pumps below, cooler, stator water tank, hydrogen rack on top ----
scene = new_scene('BWR | generator cooling skid')
box(scene, 'base frame', (.05, 0, .06), (.95, .045, .94), GREY)
for x in (.08, .92):
    for z in (.09, .91):
        box(scene, 'frame post', (x - .02, .045, z - .02), (x + .02, .64, z + .02), GREY)
cooler(scene, y=.5, z=.5, radius=.13)
box(scene, 'stator water tank', (.12, .06, .56), (.62, .36, .92), BLUE)
box(scene, 'tank cover', (.11, .36, .55), (.63, .385, .93), STEEL)
sleeve(scene, 'tank vent', (.5, .385, .8), (.5, .45, .8), .03, .015, DARK)
for i, (x, z) in enumerate(((.16, .16), (.40, .36))):
    cyl(scene, 'stator pump motor', (x, .17, z), (x + .24, .17, z), .095, BLUE)
    cyl(scene, 'stator motor fan cover', (x + .24, .17, z), (x + .30, .17, z), .08, STEEL)
    cyl(scene, 'stator pump casing', (x - .1, .17, z), (x, .17, z), .105, BODY)
    sleeve(scene, 'stator pump discharge', (x - .05, .17, z), (x - .05, .17, z + .2), .04, .02, STEEL)
    box(scene, 'pump skid foot', (x - .1, .045, z - .08), (x + .3, .075, z + .08), GREY)
box(scene, 'rack shelf', (.1, .64, .54), (.9, .67, .94), GREY)
for i in range(3):
    for j in range(2):
        cx, cz = .26 + i * .24, .66 + j * .2
        cyl(scene, 'hydrogen cylinder', (cx, .67, cz), (cx, .90, cz), .075, PALE)
        sleeve(scene, 'cylinder shoulder', (cx, .90, cz), (cx, .93, cz), .05, .02, PALE)
        cyl(scene, 'cylinder valve', (cx, .93, cz), (cx, .965, cz), .028, STEAMBAND)
for y in (.72, .84):
    box(scene, 'cylinder strap', (.15, y, .575), (.85, y + .02, .59), STEEL)
box(scene, 'electrical junction box', (.62, .22, .93), (.88, .46, .995), BLUE)
box(scene, 'junction lid', (.615, .455, .925), (.885, .475, .995), STEEL)
sleeve(scene, 'cable conduit', (.75, .35, .93), (.75, .35, 1.0), .02, .01, DARK)
water_flange(scene, 0.0, (-1, 0, 0))
water_flange(scene, 1.0, (1, 0, 0))
export_scene(scene, 'generator_cooling_skid.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})

# ---- shaft-driven main oil pump: shaft stub and coupling at the back (+Z), gear-driven volute pump ----
scene = new_scene('BWR | shaft oil pump')
box(scene, 'base plate', (.04, 0, .04), (.96, .06, .96), GREY)
for x in (.1, .9):
    for z in (.1, .9):
        bolt(scene, 'anchor bolt', (x, .06, z), (x, .1, z), .025)
cyl(scene, 'shaft stub', (.5, .5, .62), (.5, .5, 1.0), .07, STEEL)
sleeve(scene, 'coupling guard disc', (.5, .5, .86), (.5, .5, .95), .28, .1, YELLOW)
sleeve(scene, 'coupling guard rim', (.5, .5, .95), (.5, .5, .99), .30, .26, STEEL)
for a in range(8):
    ang = a * math.tau / 8
    bolt(scene, 'coupling bolt', (.5 + .2 * math.cos(ang), .5 + .2 * math.sin(ang), .95), (.5 + .2 * math.cos(ang), .5 + .2 * math.sin(ang), .99), .016)
box(scene, 'bearing housing', (.36, .36, .46), (.64, .64, .70), BODY)
box(scene, 'bearing cap', (.34, .62, .44), (.66, .68, .72), STEEL)
cyl(scene, 'gearbox', (.5, .5, .30), (.5, .5, .46), .2, STEEL)
sleeve(scene, 'gearbox flange', (.5, .5, .44), (.5, .5, .48), .22, .08, BODY)
cyl(scene, 'pump volute', (.5, .5, .08), (.5, .5, .30), .24, BLUE)
cyl(scene, 'pump volute rim', (.5, .5, .08), (.5, .5, .11), .26, STEEL)
sleeve(scene, 'pump suction eye', (.5, .5, .045), (.5, .5, .08), .1, .06, DARK)
sleeve(scene, 'pump discharge neck', (.74, .5, .19), (.9, .5, .19), .07, .045, STEEL)
sleeve(scene, 'suction stub pipe', (.5, .26, .2), (.5, .1, .2), .06, .04, STEEL)
sleeve(scene, 'discharge stub pipe', (.9, .5, .19), (.9, .62, .19), .06, .04, STEEL)
sleeve(scene, 'discharge stub flange', (.9, .62, .19), (.9, .66, .19), .08, .04, TEAL)
box(scene, 'name plate', (.345, .44, .52), (.36, .58, .64), YELLOW)
box(scene, 'electrical junction box', (.62, .68, .40), (.84, .85, .58), BLUE)
box(scene, 'junction lid', (.615, .845, .395), (.845, .865, .585), STEEL)
sleeve(scene, 'cable conduit', (.73, .865, .49), (.73, .96, .49), .022, .01, DARK)
export_scene(scene, 'shaft_oil_pump.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})

# ---- oil filter housing: a duplex full-flow filter, two vertical canisters (A on the left seen from the FRONT, that is at x .75, and B
# at x .25) and a changeover (transfer) valve between them whose lever points at the canister that is in service. The oil flow runs
# along Z through the middle: it comes in on the back (+Z) and leaves on the front (-Z), a round flange on each face at the pipe centre
# (0.5, 0.5), so a lube oil pipe can be run in-line. The in-service canister has a green band and lamp, the off-service one an amber
# band and lamp; with its cartridge slot empty ("open for a change") the off-service canister's lid stands open on its hinge.
# Four baked models: side A or B in service, off-service lid closed or open.
def oil_flange(scene, name, z, out):
    p, d = Vector((.5, .5, z)), Vector((0, 0, out))
    _, u, v = basis(d)
    sleeve(scene, name + ' flange', p - d * .10, p - d * .025, .15, .06, STEEL)
    sleeve(scene, name + ' gasket', p - d * .025, p, .14, .06, DARK)
    sleeve(scene, name + ' band', p - d * .085, p - d * .045, .157, .14, BROWN)
    for i in range(6):
        c = p + .112 * (math.cos(i * math.tau / 6) * u + math.sin(i * math.tau / 6) * v)
        bolt(scene, name + ' stud ' + str(i), c - d * .12, c - d * .018, .014)


GREENBAND = material('in service band', (.04, .42, .14))
GREENLAMP = material('in service lamp', (.10, .90, .30))
AMBERLAMP = material('off service lamp', (.95, .52, .04))
LETTER = material('letter white', (.92, .93, .90))

# Block letters on a 5 x 7 grid, as rectangles (col0, row0, col1, row1), rows from the top, end exclusive.
FONT = {
    'A': [(1, 0, 4, 1), (0, 1, 1, 7), (4, 1, 5, 7), (1, 3, 4, 4)],
    'B': [(0, 0, 1, 7), (1, 0, 4, 1), (1, 3, 4, 4), (1, 6, 4, 7), (4, 1, 5, 3), (4, 4, 5, 6)],
}


def letter_front(scene, ch, cx, cy, z, cell, depth=.011):
    """A letter standing out of a face that looks to -Z (the front), read from the front: its left is +X."""
    for c0, r0, c1, r1 in FONT[ch]:
        box(scene, 'letter ' + ch, (cx + (2.5 - c1) * cell, cy + (3.5 - r1) * cell, z - depth), (cx + (2.5 - c0) * cell, cy + (3.5 - r0) * cell, z), LETTER)


def letter_top(scene, ch, cx, cz, y, cell, depth=.011):
    """A letter lying on a face that looks up, read from the front: its top points to +Z and its left is +X."""
    for c0, r0, c1, r1 in FONT[ch]:
        box(scene, 'letter ' + ch, (cx + (2.5 - c1) * cell, y, cz + (3.5 - r1) * cell), (cx + (2.5 - c0) * cell, y + depth, cz + (3.5 - r0) * cell), LETTER)


def yaw_box(scene, name, centre, half, yaw_deg, mat):
    """A box turned about the vertical axis through its centre (game coordinates)."""
    cx, cy, cz = centre
    hx, hy, hz = half
    c, s = math.cos(math.radians(yaw_deg)), math.sin(math.radians(yaw_deg))
    pts = []
    for dy in (-hy, hy):
        for dx, dz in ((-hx, -hz), (hx, -hz), (hx, hz), (-hx, hz)):
            pts.append((cx + dx * c - dz * s, cy + dy, cz + dx * s + dz * c))
    fs = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    return mesh_object(scene, name, pts, fs, mat)


def filter_housing(label, side_a, off_open):
    scene = new_scene('BWR | oil filter housing ' + label)
    box(scene, 'base frame', (.05, 0, .06), (.95, .045, .94), GREY)
    for x in (.25, .75):
        in_service = (x == .75) == side_a
        ch = 'A' if x == .75 else 'B'
        box(scene, 'canister saddle', (x - .17, .045, .32), (x + .17, .075, .68), GREY)
        cyl(scene, 'filter canister', (x, .075, .5), (x, .70, .5), .15, BODY)
        sleeve(scene, 'canister band', (x, .16, .5), (x, .185, .5), .158, .14, STEEL)
        sleeve(scene, 'service band', (x, .56, .5), (x, .595, .5), .162, .14, GREENBAND if in_service else AMBER)
        cyl(scene, 'canister drain plug', (x, .045, .5), (x, .075, .5), .03, STEEL)
        # the service lamp and the sight glass on the front
        cyl(scene, 'service lamp', (x, .635, .36), (x, .635, .322), .024, GREENLAMP if in_service else AMBERLAMP)
        box(scene, 'cartridge sight glass', (x - .05, .21, .345), (x + .05, .33, .355), GLASS)
        box(scene, 'cartridge sight frame', (x - .065, .195, .35), (x + .065, .345, .36), STEEL)
        # the letter plate: A or B, standing out of the front, white on black
        box(scene, 'letter plate', (x - .08, .382, .338), (x + .08, .552, .352), DARK)
        letter_front(scene, ch, x, .4675, .338, .0215)
        if off_open and not in_service:
            # open for a change: the lid stands up on its back hinge, the rim and the dark empty bore show
            sleeve(scene, 'canister rim', (x, .70, .5), (x, .725, .5), .165, .125, STEEL)
            cyl(scene, 'empty bore', (x, .70, .5), (x, .706, .5), .127, DARK)
            box(scene, 'lid hinge', (x - .05, .70, .665), (x + .05, .735, .695), STEEL)
            phi = math.radians(45)
            hinge = Vector((x, .7175, .67))
            centre = hinge + .17 * Vector((0, math.sin(phi), -math.cos(phi)))
            normal = Vector((0, math.cos(phi), math.sin(phi)))
            cyl(scene, 'open lid', tuple(centre - normal * .0175), tuple(centre + normal * .0175), .17, STEEL)
            for i in range(8):
                a = i * math.tau / 8
                bolt(scene, 'rim stud', (x + .145 * math.cos(a), .725, .5 + .145 * math.sin(a)), (x + .145 * math.cos(a), .755, .5 + .145 * math.sin(a)), .014)
        else:
            cyl(scene, 'canister lid', (x, .70, .5), (x, .735, .5), .17, STEEL)
            for i in range(8):
                a = i * math.tau / 8
                bolt(scene, 'lid bolt', (x + .145 * math.cos(a), .735, .5 + .145 * math.sin(a)), (x + .145 * math.cos(a), .76, .5 + .145 * math.sin(a)), .016)
            box(scene, 'lid letter plate', (x - .085, .735, .395), (x + .085, .75, .605), DARK)
            letter_top(scene, ch, x, .5, .75, .02)
            cyl(scene, 'vent plug', (x + .106, .735, .5 + .044), (x + .106, .78, .5 + .044), .02, STEEL)
    cyl(scene, 'inlet pipe', (.5, .5, .5), (.5, .5, .985), .06, BODY)
    cyl(scene, 'outlet pipe', (.5, .5, .015), (.5, .5, .5), .06, BODY)
    cyl(scene, 'cross connection', (.40, .5, .5), (.60, .5, .5), .04, STEEL)
    # the transfer valve: body, stem, a quadrant plate with a stop at each end, and the lever pointing at the canister in service
    sleeve(scene, 'changeover valve body', (.5, .5, .5), (.5, .66, .5), .05, 0, RED)
    cyl(scene, 'quadrant plate', (.5, .66, .5), (.5, .675, .5), .09, STEEL)
    for yaw in (45, 135):
        a = math.radians(yaw)
        bolt(scene, 'quadrant stop', (.5 + .075 * math.cos(a), .675, .5 - .075 * math.sin(a)), (.5 + .075 * math.cos(a), .72, .5 - .075 * math.sin(a)), .014)
    cyl(scene, 'valve stem', (.5, .675, .5), (.5, .775, .5), .018, STEEL)
    cyl(scene, 'lever boss', (.5, .745, .5), (.5, .795, .5), .034, STEEL)
    # A is at +X (left from the front): the lever leans forward and toward it; B is at -X. The two positions are 90 degrees apart.
    sign = 1 if side_a else -1
    tip = (.5 + sign * .107, .78, .5 - .107)
    yaw_box(scene, 'transfer lever', ((.5 + tip[0]) / 2, .775, (.5 + tip[2]) / 2), (.092, .014, .016), -sign * 45, RED)
    cyl(scene, 'lever knob', (tip[0], .76, tip[2]), (tip[0], .835, tip[2]), .03, YELLOW)
    box(scene, 'differential pressure gauge case', (.40, .60, .045), (.60, .80, .17), YELLOW)
    cyl(scene, 'gauge dial glass', (.5, .70, .045), (.5, .70, .02), .075, GLASS)
    cyl(scene, 'gauge impulse tube', (.5, .62, .17), (.5, .62, .46), .014, DARK)
    box(scene, 'name plate', (.44, .12, .045), (.56, .20, .052), YELLOW)
    oil_flange(scene, 'inlet', 1.0, 1)
    oil_flange(scene, 'outlet', 0.0, -1)
    export_scene(scene, 'oil_filter_housing_' + label + '.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})
    return scene


HOUSING = {label: filter_housing(label, side_a, off_open)
           for label, side_a, off_open in (('a_closed', True, False), ('a_open', True, True), ('b_closed', False, False), ('b_open', False, True))}


# ---- lube oil conditioner: a side-stream oil cleanup skid. Oil comes in on the back (+Z) into a horizontal disc-stack centrifuge
# (coalescer) that a motor drives, goes up and over into a vertical vacuum dehydrator column (sight glass, vacuum line to a vacuum pump
# and a cold trap) and leaves on the front (-Z). A flange with a brown band on each of those two faces at the pipe centre (0.5, 0.5);
# a control cabinet with the FE terminal stands in the back left corner.
scene = new_scene('BWR | lube oil conditioner')
box(scene, 'base frame', (.05, 0, .06), (.95, .045, .94), GREY)
for x in (.08, .92):
    for z in (.09, .91):
        box(scene, 'frame post', (x - .02, .045, z - .02), (x + .02, .30, z + .02), GREY)
# centrifuge / coalescer, along X at y .5, z .68
for x in (.34, .56):
    box(scene, 'centrifuge saddle', (x - .03, .045, .56), (x + .03, .40, .80), GREY)
cyl(scene, 'centrifuge bowl housing', (.24, .5, .68), (.66, .5, .68), .125, STEEL)
sleeve(scene, 'bowl housing flange', (.24, .5, .68), (.275, .5, .68), .14, .07, BODY)
cyl(scene, 'inspection cover', (.215, .5, .68), (.24, .5, .68), .115, BODY)
cyl(scene, 'inspection cover boss', (.20, .5, .68), (.215, .5, .68), .045, STEEL)
for i in range(8):
    a = i * math.tau / 8
    bolt(scene, 'cover bolt', (.24, .5 + .095 * math.cos(a), .68 + .095 * math.sin(a)), (.20, .5 + .095 * math.cos(a), .68 + .095 * math.sin(a)), .013)
for x in (.34, .46, .58):
    sleeve(scene, 'bowl housing band', (x, .5, .68), (x + .02, .5, .68), .133, .12, BODY)
cyl(scene, 'drive gearbox', (.66, .5, .68), (.72, .5, .68), .10, BODY)
cyl(scene, 'drive motor', (.72, .5, .68), (.90, .5, .68), .095, BLUE)
for x in (.76, .79, .82):
    sleeve(scene, 'motor cooling fin', (x, .5, .68), (x + .015, .5, .68), .103, .09, STEEL)
cyl(scene, 'motor fan cover', (.90, .5, .68), (.94, .5, .68), .08, STEEL)
box(scene, 'motor foot', (.70, .38, .60), (.88, .405, .76), GREY)
box(scene, 'motor stand', (.70, .045, .60), (.88, .405, .62), GREY)
box(scene, 'motor stand', (.70, .045, .74), (.88, .405, .76), GREY)
# the inlet from the back flange into the bowl housing, and a drain at its low point
cyl(scene, 'inlet pipe', (.5, .5, .80), (.5, .5, .985), .06, BODY)
cyl(scene, 'bowl drain', (.42, .30, .68), (.42, .38, .68), .02, STEEL)
sleeve(scene, 'sludge discharge', (.30, .375, .68), (.30, .30, .68), .03, .015, DARK)
# transfer line: up out of the centrifuge, forward over the skid and down into the dehydrator head
cyl(scene, 'transfer riser', (.5, .60, .68), (.5, .86, .68), .035, BODY)
cyl(scene, 'transfer line', (.5, .86, .68), (.5, .86, .30), .035, BODY)
cyl(scene, 'transfer drop', (.5, .86, .30), (.5, .78, .30), .035, BODY)
sleeve(scene, 'transfer elbow', (.5, .86, .685), (.5, .86, .655), .042, 0, STEEL)
# vacuum dehydrator column
cyl(scene, 'column skirt', (.5, .045, .30), (.5, .10, .30), .125, GREY)
cyl(scene, 'dehydrator column', (.5, .10, .30), (.5, .72, .30), .115, STEEL)
cyl(scene, 'column dished head', (.5, .72, .30), (.5, .78, .30), .09, STEEL)
for y in (.20, .44, .66):
    sleeve(scene, 'column band', (.5, y, .30), (.5, y + .022, .30), .124, .11, BODY)
sleeve(scene, 'column head flange', (.5, .715, .30), (.5, .735, .30), .13, .09, BODY)
box(scene, 'sight glass frame', (.455, .24, .176), (.545, .62, .192), STEEL)
box(scene, 'sight glass', (.47, .26, .168), (.53, .60, .178), GLASS)
box(scene, 'oil in sight glass', (.47, .26, .165), (.53, .43, .170), AMBER)
cyl(scene, 'outlet pipe', (.5, .5, .015), (.5, .5, .20), .06, BODY)
cyl(scene, 'level transmitter', (.40, .38, .20), (.40, .38, .15), .022, STEEL)
# vacuum line to the pump (right) and a cold trap (left)
cyl(scene, 'vacuum header', (.5, .68, .30), (.84, .68, .30), .022, BODY)
cyl(scene, 'vacuum header left', (.5, .68, .30), (.22, .68, .30), .022, BODY)
cyl(scene, 'trap line', (.22, .68, .30), (.22, .44, .30), .022, BODY)
cyl(scene, 'cold trap', (.22, .08, .30), (.22, .44, .30), .065, PALE)
sleeve(scene, 'cold trap head', (.22, .44, .30), (.22, .48, .30), .07, .03, STEEL)
cyl(scene, 'cold trap drain valve', (.22, .045, .30), (.22, .08, .30), .02, STEAMBAND)
box(scene, 'cold trap sight glass', (.20, .18, .228), (.24, .34, .236), GLASS)
cyl(scene, 'vacuum pump casing', (.74, .20, .30), (.80, .20, .30), .075, BODY)
cyl(scene, 'vacuum pump motor', (.80, .20, .30), (.92, .20, .30), .06, BLUE)
cyl(scene, 'vacuum pump fan cover', (.92, .20, .30), (.945, .20, .30), .05, STEEL)
cyl(scene, 'vacuum pump line', (.84, .68, .30), (.84, .275, .30), .022, BODY)
sleeve(scene, 'vacuum pump discharge', (.74, .20, .30), (.74, .20, .22), .03, .015, DARK)
box(scene, 'pump skid foot', (.68, .045, .22), (.94, .075, .38), GREY)
# the control cabinet and the FE terminal, back left
box(scene, 'control cabinet', (.07, .045, .62), (.19, .62, .93), BLUE)
box(scene, 'cabinet door', (.065, .09, .66), (.07, .56, .89), STEEL)
box(scene, 'cabinet handle', (.055, .30, .85), (.065, .38, .865), YELLOW)
box(scene, 'cabinet roof', (.065, .62, .615), (.195, .64, .935), STEEL)
sleeve(scene, 'FE conduit', (.13, .64, .78), (.13, .72, .78), .022, .01, DARK)
box(scene, 'FE terminal plate', (.07, .66, .62), (.19, .70, .66), YELLOW)
box(scene, 'status lamp box', (.09, .48, .60), (.17, .58, .62), GREY)
cyl(scene, 'running lamp', (.13, .53, .60), (.13, .53, .575), .022, GREENLAMP)
oil_flange(scene, 'inlet', 1.0, 1)
oil_flange(scene, 'outlet', 0.0, -1)
export_scene(scene, 'lube_oil_conditioner.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})

bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'turbine_aux.blend'))
print('Saved the editable Blender scenes and exported their meshes.')


# ---- headless preview renders (one per block, one combined), written after the .blend is saved ----
def preview(scene_list, filename, offsets, size=(1280, 720), eye=(1.6, -2.1, 1.5)):
    pv = bpy.data.scenes.new('Preview | ' + filename)
    for sc, off in zip(scene_list, offsets):
        for o in sc.objects:
            c = o.copy()
            c.location = Vector(xyz(off))
            pv.collection.objects.link(c)
    pts = [c.location + v.co for c in pv.objects for v in c.data.vertices]
    lo = Vector([min(p[a] for p in pts) for a in range(3)])
    hi = Vector([max(p[a] for p in pts) for a in range(3)])
    centre, span = (lo + hi) / 2, max(hi - lo)
    cam_data = bpy.data.cameras.new('cam')
    cam = bpy.data.objects.new('cam', cam_data)
    pv.collection.objects.link(cam)
    pv.camera = cam
    cam_data.type = 'ORTHO'
    cam.location = centre + Vector(eye).normalized() * span * 3
    cam.rotation_euler = (centre - cam.location).to_track_quat('-Z', 'Y').to_euler()
    inv = cam.rotation_euler.to_matrix().transposed()
    proj = [inv @ (p - centre) for p in pts]
    w = max(p[0] for p in proj) - min(p[0] for p in proj)
    h = max(p[1] for p in proj) - min(p[1] for p in proj)
    cam_data.ortho_scale = max(w, h * size[0] / size[1]) * 1.12
    for n, offs, power in (('key', (-2, -3, 4), 700), ('fill', (3, -1, 2), 350), ('rim', (0, 3, 3), 500)):
        lamp = bpy.data.lights.new(n, 'AREA')
        lamp.energy = power * span * span
        lamp.size = 3 * span
        light = bpy.data.objects.new(n, lamp)
        pv.collection.objects.link(light)
        light.location = centre + Vector(offs) * span
        light.rotation_euler = (centre - light.location).to_track_quat('-Z', 'Y').to_euler()
    pv.world = bpy.data.worlds.new('w')
    pv.world.color = (.55, .6, .65)
    try:
        pv.render.engine = 'BLENDER_EEVEE'
    except TypeError:
        pass
    pv.render.resolution_x, pv.render.resolution_y = size
    pv.render.film_transparent = False
    pv.render.filepath = str(OUT / filename)
    bpy.ops.render.render(write_still=True, scene=pv.name)


def named(label):
    return next(sc for sc in SCENES if sc.name == 'BWR | ' + label)


if '--no-preview' not in __import__('sys').argv:
    # Blender coordinates: game x -> X, game z -> -Y, so "south" game offsets use xyz(). The camera looks from the front (north, -Z game = +Y blender).
    for name, scene_name in (('main_oil_tank', 'main oil tank'), ('generator_cooling_skid', 'generator cooling skid'), ('shaft_oil_pump', 'shaft oil pump'),
                             ('oil_filter_housing', 'oil filter housing a_closed'), ('lube_oil_conditioner', 'lube oil conditioner')):
        preview([named(scene_name)], name + '.png', [(0, 0, 0)], eye=(-1.5, 2.2, 1.4))
        preview([named(scene_name)], name + '_back.png', [(0, 0, 0)], eye=(1.6, -2.2, 1.4))
    states = [named('oil filter housing ' + n) for n in ('a_closed', 'a_open', 'b_closed', 'b_open')]
    preview(states, 'oil_filter_housing_states.png', [(0, 0, 0), (-1.3, 0, 0), (-2.6, 0, 0), (-3.9, 0, 0)], size=(1900, 560), eye=(-0.9, 2.2, 1.5))
    preview(states[:2] + states[2:3], 'oil_filter_housing_levers.png', [(0, 0, 0), (-1.3, 0, 0), (-2.6, 0, 0)], size=(1900, 700), eye=(-0.1, 1.2, 2.6))
    preview([named(n) for n in ('main oil tank', 'generator cooling skid', 'shaft oil pump', 'oil filter housing a_closed', 'lube oil conditioner')],
            'turbine_aux_all.png', [(0, 0, 0), (-1.3, 0, 0), (-2.6, 0, 0), (-3.9, 0, 0), (-5.2, 0, 0)], size=(2300, 560), eye=(-0.4, 2.6, 1.3))
