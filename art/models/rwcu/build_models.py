"""Blender-authored Reactor Water Cleanup (RWCU) equipment. Run headless:

    blender.exe --background --python art/models/rwcu/build_models.py

Writes rwcu_pump.json, rwcu_regenerative_heat_exchanger.json, rwcu_nonregenerative_heat_exchanger.json,
rwcu_filter_demineralizer.json and rpv_bottom_drain_port.json (mesh and UV data for tools-export-rwcu.py) and
rwcu.blend (the editable scenes). One unit is one block; game coordinates are X right, Y up, Z south, and the
default (facing=north) orientation puts the "front" flange on the north face (z = 0).
Every pipe flange is the round High-Pressure Water Pipe flange of the water valves: centre (0.5, 0.5) of its face.

Flange map (facing=north):
  rwcu_pump                              north = discharge,        south = suction
  rwcu_regenerative_heat_exchanger       north = hot in,           south = hot out,     west = cold in,    east = cold out
  rwcu_nonregenerative_heat_exchanger    north = process in,       south = process out, west = cooling in, east = cooling out
  rwcu_filter_demineralizer              north = inlet,            south = outlet     (the stainless steel nanofilter)
  rpv_bottom_drain_port                  north = drain flange (faces OUT along facing)
"""
import json
import math
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector

bpy.ops.wm.read_factory_settings(use_empty=True)

OUT = Path(__file__).resolve().parent
SCENES = []


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


def game(p):
    return (p[0], p[2], -p[1])


def material(name, rgb, metal=0.0):
    m = bpy.data.materials.new('RWCU | ' + name)
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
NAVY = material('terminal box', (.01, .15, .32))
TEAL = material('water band', (.025, .45, .32))
COPPER = material('heat recovery copper', (.62, .30, .13), .7)
COOL_IN = material('cooling water in', (.02, .16, .62))
COOL_OUT = material('cooling water out', (.03, .60, .68))
GREEN = material('resin identification green', (.06, .52, .16))
AMBER = material('drain amber', (.86, .55, .03))
GLASS = material('sight glass', (.45, .72, .78))
SHELL = material('vessel wall', (.30, .34, .37), .6)
VESSEL = material('resin vessel steel', (.36, .42, .46), .55)
BASE = material('painted base', (.09, .11, .12), .4)
STAINLESS = material('brushed stainless steel', (.66, .70, .72), .85)
BANDBLUE = material('nanofilter identification blue', (.10, .36, .62))
DIAL = material('gauge dial', (.92, .93, .90))
NEEDLE = material('gauge needle', (.80, .05, .04))
PLATE = material('nameplate', (.80, .82, .84), .9)


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


def lathe(scene, name, start, axis, profile, mat, segments=24):
    """Closed solid of revolution; profile is a list of (distance along axis, radius), radius > 0."""
    s = Vector(start)
    d, u, v = basis(axis)
    vs = [tuple(s + d * t + r * (math.cos(i * math.tau / segments) * u + math.sin(i * math.tau / segments) * v))
          for t, r in profile for i in range(segments)]
    fs = [tuple(range(segments - 1, -1, -1)), tuple(range((len(profile) - 1) * segments, len(profile) * segments))]
    for k in range(len(profile) - 1):
        for i in range(segments):
            j = (i + 1) % segments
            fs.append((k * segments + i, k * segments + j, (k + 1) * segments + j, (k + 1) * segments + i))
    return mesh_object(scene, name, vs, fs, mat)


def bolt(scene, name, a, b, radius=.025, mat=None):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    n = 6
    vs = [tuple(p + radius * (math.cos(t * math.tau / n) * u + math.sin(t * math.tau / n) * v)) for p in (a, b) for t in range(n)]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))]
    fs += [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(scene, name, vs, fs, mat or STEEL)


def flange(scene, name, endpoint, outward, radius=.31, bolts=8):
    """The High-Pressure Water Pipe flange of the water valves, ending on the block face at `endpoint`."""
    p = Vector(endpoint)
    d, u, v = basis(outward)
    sleeve(scene, name + ' flange', p - d * .12, p - d * .025, radius, .205, STEEL)
    sleeve(scene, name + ' gasket', p - d * .025, p, radius * .93, .205, DARK)
    for i in range(bolts):
        c = p + radius * .81 * (math.cos(i * math.tau / bolts) * u + math.sin(i * math.tau / bolts) * v)
        bolt(scene, name + ' stud ' + str(i), c - d * .14, c - d * .018, .026)


def bolt_ring(scene, name, centre, axis, ring, count, a_off, b_off, radius=.02, mat=None, phase=0.0):
    c0 = Vector(centre)
    d, u, v = basis(axis)
    for i in range(count):
        ang = phase + i * math.tau / count
        c = c0 + ring * (math.cos(ang) * u + math.sin(ang) * v)
        bolt(scene, name + ' ' + str(i), c + d * a_off, c + d * b_off, radius, mat)


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


META = {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []}
N, S, W, E = (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)


def face_point(direction):
    return {N: (.5, .5, 0), S: (.5, .5, 1), W: (0, .5, .5), E: (1, .5, .5)}[direction]


def saddle(scene, z0, z1, x0=.33, x1=.67, top=.28):
    box(scene, 'saddle', (x0, .06, z0), (x1, top, z1), BASE)


# ---- 1. RWCU pump: inline horizontal centrifugal pump, FE motor on top. Discharge north, suction south ----
scene = new_scene('BWR | RWCU pump')
sleeve(scene, 'pump water bore', (.5, .5, 0), (.5, .5, 1), .225, .19, BODY)
flange(scene, 'discharge', face_point(N), N)
flange(scene, 'suction', face_point(S), S)
lathe(scene, 'volute casing', (.5, .5, 0), (0, 0, 1),
      [(.27, .21), (.29, .245), (.33, .26), (.67, .26), (.71, .245), (.73, .21)], BODY, 28)
sleeve(scene, 'casing water band', (.5, .5, .46), (.5, .5, .54), .266, 0, TEAL, 28)
for z in (.36, .64):
    sleeve(scene, 'casing joint ring', (.5, .5, z - .012), (.5, .5, z + .012), .285, 0, STEEL, 28)
    bolt_ring(scene, 'casing joint bolt', (.5, .5, z), (0, 0, 1), .272, 10, -.02, .02, .014)
box(scene, 'base plate', (.14, 0, .17), (.86, .05, .83), BASE)
for z in (.35, .61):
    box(scene, 'pump pedestal', (.30, .05, z), (.70, .25, z + .04), BASE)
box(scene, 'motor adapter', (.38, .71, .38), (.62, .77, .62), STEEL)
sleeve(scene, 'motor frame', (.5, .84, .17), (.5, .84, .80), .12, 0, BLUE)
for i in range(9):
    z = .22 + i * .065
    sleeve(scene, 'cooling fin', (.5, .84, z), (.5, .84, z + .016), .137, 0, BLUE)
sleeve(scene, 'motor fan cover', (.5, .84, .80), (.5, .84, .845), .11, 0, STEEL)
sleeve(scene, 'motor shaft end', (.5, .84, .14), (.5, .84, .17), .045, 0, STEEL)
sleeve(scene, 'motor drive-end bell', (.5, .84, .17), (.5, .84, .22), .128, 0, STEEL)
box(scene, 'junction box', (.62, .78, .40), (.745, .90, .60), NAVY)
box(scene, 'junction box lid', (.615, .90, .39), (.75, .915, .61), STEEL)
sleeve(scene, 'cable gland', (.745, .84, .5), (.78, .84, .5), .024, 0, DARK)
bolt(scene, 'power cable out', (.78, .84, .5), (.86, .84, .5), .018, DARK)
bolt(scene, 'power cable down', (.86, .84, .5), (.86, .56, .5), .018, DARK)
for x in (.66, .70):
    for z in (.42, .58):
        bolt(scene, 'lid screw', (x, .915, z), (x, .93, z), .01)
export_scene(scene, 'rwcu_pump.json', META)

# ---- 2. regenerative heat exchanger: fat shell, domed heads, copper bands ----
scene = new_scene('BWR | RWCU regenerative heat exchanger')
for d in (N, S):
    p = Vector(face_point(d))
    inward = Vector((0, 0, 1 if d == N else -1))
    # head dome grows from the flange back plane (.12 in from the face) toward the shell
    dome = [(.12, .20), (.14, .235), (.165, .262), (.19, .278), (.23, .29)]
    lathe(scene, 'hot head', tuple(p), tuple(inward), dome, BODY, 28)
    sleeve(scene, 'hot head copper band', p + inward * .17, p + inward * .20, .286, 0, COPPER, 28)
    sleeve(scene, 'hot bore dark', p + inward * .115, p + inward * .122, .2, 0, DARK)
    flange(scene, 'hot ' + ('in' if d == N else 'out'), tuple(p), d)
    z = .245 if d == N else .755
    sleeve(scene, 'channel joint ring', (.5, .5, z - .015), (.5, .5, z + .015), .335, 0, STEEL, 28)
    bolt_ring(scene, 'channel joint bolt', (.5, .5, z), (0, 0, 1), .31, 12, -.03, .03, .017)
sleeve(scene, 'exchanger shell', (.5, .5, .26), (.5, .5, .74), .29, 0, BODY, 28)
for z in (.285, .715):
    sleeve(scene, 'shell copper band', (.5, .5, z - .018), (.5, .5, z + .018), .296, 0, COPPER, 28)
sleeve(scene, 'shell teal band', (.5, .5, .465), (.5, .5, .535), .292, 0, TEAL, 28)
for d in (W, E):
    p = Vector(face_point(d))
    inward = Vector((1 if d == W else -1, 0, 0))
    sleeve(scene, 'cold nozzle neck', p + inward * .12, p + inward * .36, .235, .205, BODY)
    sleeve(scene, 'cold nozzle teal band', p + inward * .17, p + inward * .22, .243, .205, TEAL)
    sleeve(scene, 'cold nozzle dark bore', p + inward * .115, p + inward * .122, .2, 0, DARK)
    flange(scene, 'cold ' + ('in' if d == W else 'out'), tuple(p), d)
for z0 in (.30, .63):
    saddle(scene, z0, z0 + .07)
box(scene, 'skid rail', (.20, 0, .27), (.80, .06, .73), BASE)
sleeve(scene, 'vent stub', (.5, .78, .5), (.5, .86, .5), .03, 0, STEEL)
sleeve(scene, 'vent cap', (.5, .86, .5), (.5, .885, .5), .042, 0, DARK)
for z in (.36, .64):
    box(scene, 'lifting lug', (.47, .78, z - .012), (.53, .83, z + .012), STEEL)
export_scene(scene, 'rwcu_regenerative_heat_exchanger.json', META)

# ---- 3. non-regenerative heat exchanger (RWCU cooler): longer, slimmer, blue / cyan cooling-water nozzles ----
scene = new_scene('BWR | RWCU non-regenerative heat exchanger')
for d in (N, S):
    p = Vector(face_point(d))
    inward = Vector((0, 0, 1 if d == N else -1))
    sleeve(scene, 'process head', p + inward * .12, p + inward * .19, .27, 0, BODY, 28)
    sleeve(scene, 'process bore dark', p + inward * .115, p + inward * .122, .2, 0, DARK)
    sleeve(scene, 'process tubesheet flange', p + inward * .19, p + inward * .22, .315, 0, STEEL, 28)
    bolt_ring(scene, 'tubesheet bolt', p + inward * .205, (0, 0, 1), .285, 14, -.03, .03, .016)
    sleeve(scene, 'process head band', p + inward * .135, p + inward * .165, .277, 0, TEAL, 28)
    flange(scene, 'process ' + ('in' if d == N else 'out'), tuple(p), d)
sleeve(scene, 'cooler shell', (.5, .5, .22), (.5, .5, .78), .25, 0, BODY, 28)
sleeve(scene, 'cooling water band', (.5, .5, .455), (.5, .5, .545), .258, 0, COOL_IN, 28)
for z in (.30, .70):
    sleeve(scene, 'cyan band', (.5, .5, z - .015), (.5, .5, z + .015), .256, 0, COOL_OUT, 28)
for k in range(4):  # expansion bellows
    z = .59 + k * .035
    sleeve(scene, 'bellows ring', (.5, .5, z), (.5, .5, z + .018), .27, 0, STEEL, 28)
for d in (W, E):
    p = Vector(face_point(d))
    inward = Vector((1 if d == W else -1, 0, 0))
    col = COOL_IN if d == W else COOL_OUT
    sleeve(scene, 'cooling nozzle neck', p + inward * .12, p + inward * .32, .235, .205, col)
    sleeve(scene, 'cooling nozzle collar', p + inward * .15, p + inward * .19, .25, .205, STEEL)
    sleeve(scene, 'cooling nozzle dark bore', p + inward * .115, p + inward * .122, .2, 0, DARK)
    flange(scene, 'cooling ' + ('in' if d == W else 'out'), tuple(p), d)
for z0 in (.27, .66):
    saddle(scene, z0, z0 + .07, .33, .67, .27)
box(scene, 'skid rail', (.22, 0, .24), (.78, .06, .76), BASE)
sleeve(scene, 'vent stub', (.5, .73, .38), (.5, .80, .38), .026, 0, STEEL)
sleeve(scene, 'vent cap', (.5, .80, .38), (.5, .82, .38), .036, 0, DARK)
sleeve(scene, 'drain stub', (.5, .06, .62), (.5, .26, .62), .024, 0, STEEL)
export_scene(scene, 'rwcu_nonregenerative_heat_exchanger.json', META)

# ---- 4. stainless steel nanofilter (id rwcu_filter_demineralizer): vertical filter housing, bolted top closure, inlet north, outlet south ----
scene = new_scene('BWR | RWCU filter-demineralizer')
lathe(scene, 'bottom head', (.5, 0, .5), (0, 1, 0),
      [(.12, .13), (.14, .19), (.17, .225), (.20, .24)], STAINLESS, 28)
sleeve(scene, 'vessel shell', (.5, .20, .5), (.5, .73, .5), .24, 0, STAINLESS, 32)
sleeve(scene, 'shell weld ring', (.5, .192, .5), (.5, .208, .5), .246, 0, STEEL, 32)
sleeve(scene, 'nanofilter identification band', (.5, .27, .5), (.5, .35, .5), .246, 0, BANDBLUE, 32)
sleeve(scene, 'element guide band', (.5, .62, .5), (.5, .64, .5), .244, 0, STEEL, 32)
# bolted top closure: shell flange, gasket, cover flange, twelve studs with nuts, dished cover, lifting eye and vent
sleeve(scene, 'shell closure flange', (.5, .73, .5), (.5, .765, .5), .325, 0, STEEL, 36)
sleeve(scene, 'closure gasket', (.5, .765, .5), (.5, .775, .5), .30, 0, DARK, 36)
sleeve(scene, 'cover flange', (.5, .775, .5), (.5, .81, .5), .325, 0, STEEL, 36)
lathe(scene, 'closure dome', (.5, 0, .5), (0, 1, 0),
      [(.81, .26), (.835, .245), (.86, .21), (.88, .16), (.895, .10), (.90, .05)], STAINLESS, 32)
for i in range(12):
    ang = i * math.tau / 12
    cx, cz = .5 + .285 * math.cos(ang), .5 + .285 * math.sin(ang)
    bolt(scene, 'closure stud', (cx, .725, cz), (cx, .835, cz), .016, STEEL)
    sleeve(scene, 'closure nut', (cx, .812, cz), (cx, .835, cz), .028, 0, DARK, 6)
box(scene, 'lifting eye', (.46, .90, .485), (.54, .93, .515), STEEL)
sleeve(scene, 'lifting eye hole', (.5, .90, .47), (.5, .935, .53), .008, 0, DARK, 8)
sleeve(scene, 'vent stub', (.62, .87, .5), (.62, .93, .5), .02, 0, STEEL)
sleeve(scene, 'vent cap', (.62, .93, .5), (.62, .95, .5), .03, 0, DARK)
for sx in (-1, 1):
    for sz in (-1, 1):
        x, z = .5 + sx * .185, .5 + sz * .185
        box(scene, 'support leg', (x - .03, .02, z - .03), (x + .03, .19, z + .03), BASE)
        box(scene, 'foot plate', (x - .06, 0, z - .06), (x + .06, .02, z + .06), BASE)
box(scene, 'leg brace west', (.29, .10, .31), (.32, .125, .69), BASE)
box(scene, 'leg brace east', (.68, .10, .31), (.71, .125, .69), BASE)
for d, role in ((N, 'inlet'), (S, 'outlet')):
    p = Vector(face_point(d))
    inward = Vector((0, 0, 1 if d == N else -1))
    sleeve(scene, role + ' neck', p + inward * .12, p + inward * .29, .225, .205, STAINLESS)
    sleeve(scene, role + ' band', p + inward * .16, p + inward * .20, .233, .205, TEAL)
    sleeve(scene, role + ' dark bore', p + inward * .115, p + inward * .122, .2, 0, DARK)
    flange(scene, role, tuple(p), d)
# differential-pressure gauge, west side: two impulse lines from taps above and below the element to a manifold and a dial
sleeve(scene, 'upper tap', (.30, .66, .5), (.19, .66, .5), .013, 0, STEEL, 10)
sleeve(scene, 'lower tap', (.30, .34, .5), (.19, .34, .5), .013, 0, STEEL, 10)
sleeve(scene, 'upper impulse line', (.19, .52, .5), (.19, .66, .5), .013, 0, STEEL, 10)
sleeve(scene, 'lower impulse line', (.19, .34, .5), (.19, .52, .5), .013, 0, STEEL, 10)
box(scene, 'gauge manifold', (.16, .46, .465), (.22, .58, .535), STEEL)
sleeve(scene, 'gauge stem', (.10, .52, .5), (.16, .52, .5), .022, 0, STEEL, 12)
sleeve(scene, 'gauge case', (.05, .52, .5), (.10, .52, .5), .075, 0, STEEL, 24)
sleeve(scene, 'gauge dial', (.045, .52, .5), (.05, .52, .5), .062, 0, DIAL, 24)
bolt(scene, 'gauge needle', (.042, .52, .5), (.042, .565, .53), .006, NEEDLE)
sleeve(scene, 'gauge hub', (.04, .52, .5), (.046, .52, .5), .01, 0, DARK, 8)
for k in range(7):  # dial ticks
    a = math.radians(-120 + k * 40)
    bolt(scene, 'dial tick', (.043, .52 + .05 * math.cos(a), .5 + .05 * math.sin(a)), (.043, .52 + .058 * math.cos(a), .5 + .058 * math.sin(a)), .004, DARK)
# nameplate, east side: bracket, plate, engraved lines, rivets
box(scene, 'nameplate bracket', (.715, .38, .40), (.76, .60, .60), STEEL)
box(scene, 'nameplate', (.76, .41, .42), (.775, .57, .58), PLATE)
for k, y in enumerate((.54, .505, .47, .435)):
    box(scene, 'nameplate line', (.775, y, .44 if k else .45), (.779, y + .014, .56 if k else .55), DARK)
for y in (.425, .555):
    for z in (.435, .565):
        sleeve(scene, 'nameplate rivet', (.775, y, z), (.782, y, z), .008, 0, STEEL, 8)
# capped drain and backwash connection at the bottom
sleeve(scene, 'drain nozzle', (.5, .13, .5), (.5, .045, .5), .035, 0, STEEL, 16)
sleeve(scene, 'drain blind flange', (.5, .045, .5), (.5, .02, .5), .06, 0, DARK, 16)
sleeve(scene, 'drain flange', (.5, .08, .5), (.5, .06, .5), .055, 0, STEEL, 16)
export_scene(scene, 'rwcu_filter_demineralizer.json', META)

# ---- 5. RPV bottom-head drain port: vessel-wall block, flange north, little manual gate-valve handwheel ----
scene = new_scene('BWR | RPV bottom drain port')
box(scene, 'vessel wall', (0, 0, .4), (1, 1, 1), SHELL)
sleeve(scene, 'wall reinforcement plate', (.5, .5, .36), (.5, .5, .4), .38, .1, STEEL, 28)
bolt_ring(scene, 'reinforcement stud', (.5, .5, .36), (0, 0, 1), .335, 12, -.03, 0, .022)
sleeve(scene, 'drain neck', (.5, .5, .12), (.5, .5, .36), .21, .1, BODY, 24)
sleeve(scene, 'isolation valve collar', (.5, .5, .15), (.5, .5, .34), .25, .1, BODY, 24)
sleeve(scene, 'drain identification band', (.5, .5, .21), (.5, .5, .27), .262, .1, AMBER, 24)
sleeve(scene, 'drain bore end', (.5, .5, .395), (.5, .5, .4), .1, 0, DARK)
flange(scene, 'drain connection', (.5, .5, 0), N)
sleeve(scene, 'gate valve bonnet', (.5, .70, .245), (.5, .76, .245), .07, 0, STEEL)
sleeve(scene, 'bonnet gland', (.5, .76, .245), (.5, .785, .245), .045, 0, DARK)
sleeve(scene, 'valve stem', (.5, .785, .245), (.5, .86, .245), .014, 0, DARK)
sleeve(scene, 'handwheel rim', (.5, .86, .245), (.5, .875, .245), .1, .082, AMBER, 24)
for a in range(4):
    angle = a * math.tau / 4 + math.pi / 4
    bolt(scene, 'handwheel spoke', (.5, .867, .245), (.5 + .09 * math.cos(angle), .867, .245 + .09 * math.sin(angle)), .008, AMBER)
sleeve(scene, 'handwheel hub', (.5, .855, .245), (.5, .89, .245), .022, 0, STEEL)
sleeve(scene, 'vessel side bore ring', (.5, .5, .985), (.5, .5, 1), .17, .12, STEEL, 24)
sleeve(scene, 'vessel side bore', (.5, .5, .995), (.5, .5, 1), .12, 0, DARK, 24)
export_scene(scene, 'rpv_bottom_drain_port.json', META)

bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'rwcu.blend'))
print('Saved the editable Blender scenes and exported their meshes.')
