"""Blender-authored motor-operated water valve and cavity drain port. Run headless:

    blender.exe --background --python art/models/water_valves/build_models.py

Writes motor_operated_water_valve.json and cavity_drain_port.json (mesh and UV data for tools-export-water-valves.py)
and water_valves.blend (the editable scenes). One unit is one block; game coordinates are X right, Y up, Z south.
The pipe flanges sit on the block faces and match the round High-Pressure Water Pipe exactly (centre 0.5, 0.5).
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
    m = bpy.data.materials.new('Water valve | ' + name)
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
CONCRETE = material('wall frame', (.42, .43, .44))
GRATE = material('strainer', (.12, .14, .15), .5)


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


def flange(scene, name, endpoint, outward, radius=.31, bolts=8):
    p = Vector(endpoint)
    d, u, v = basis(outward)
    sleeve(scene, name + ' flange', p - d * .12, p - d * .025, radius, .205, STEEL)
    sleeve(scene, name + ' gasket', p - d * .025, p, radius * .93, .205, DARK)
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


# ---- the motor-operated water valve: pipe axis along Z, actuator on top ----
scene = new_scene('BWR | motor-operated water valve')
sleeve(scene, 'water bore', (0.5, .5, 0), (0.5, .5, 1), .225, .19, BODY)
sleeve(scene, 'cast valve chest', (0.5, .5, .23), (0.5, .5, .77), .295, .19, BODY)
for z, d in ((0, (0, 0, -1)), (1, (0, 0, 1))):
    flange(scene, 'water connection', (0.5, .5, z), d, radius=.31, bolts=8)
sleeve(scene, 'bonnet', (0.5, .68, .5), (0.5, .78, .5), .16, .06, STEEL)
sleeve(scene, 'bonnet gasket', (0.5, .775, .5), (0.5, .795, .5), .18, .06, DARK)
for x in (.38, .62):
    for z in (.38, .62):
        bolt(scene, 'bonnet stud', (x, .78, z), (x, .84, z), .019)
sleeve(scene, 'gearbox', (0.5, .79, .5), (0.5, .93, .5), .14, 0, BLUE)
sleeve(scene, 'gearbox water band', (0.5, .835, .5), (0.5, .865, .5), .145, 0, TEAL)
sleeve(scene, 'motor', (0.28, .88, .5), (0.72, .88, .5), .085, 0, BLUE)
sleeve(scene, 'motor end cap', (0.72, .88, .5), (0.76, .88, .5), .07, 0, STEEL)
sleeve(scene, 'motor fan cover', (0.24, .88, .5), (0.28, .88, .5), .075, 0, STEEL)
sleeve(scene, 'handwheel', (0.5, .93, .5), (0.5, .955, .5), .17, .145, STEEL)
for a in range(4):
    angle = a * math.tau / 4
    bolt(scene, 'handwheel spoke', (0.5, .94, .5), (.5 + .155 * math.cos(angle), .94, .5 + .155 * math.sin(angle)), .014)
bolt(scene, 'handwheel hub', (0.5, .93, .5), (0.5, .985, .5), .035)
export_scene(scene, 'motor_operated_water_valve.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})

# ---- the cavity drain port: flange to the north (-Z), the wet face on the south side ----
scene = new_scene('BWR | cavity drain port')
box(scene, 'wall frame', (0.0, 0.0, .5), (1.0, 1.0, .93), CONCRETE)
sleeve(scene, 'drain bore', (0.5, .5, 0), (0.5, .5, .52), .225, .19, BODY)
flange(scene, 'drain connection', (0.5, .5, 0), (0, 0, -1), radius=.31, bolts=8)
sleeve(scene, 'inner dish', (0.5, .5, .93), (0.5, .5, .945), .27, 0, DARK)
sleeve(scene, 'strainer ring', (0.5, .5, .93), (0.5, .5, .99), .36, .27, STEEL)
for k in range(-2, 3):
    box(scene, 'strainer bar', (0.5 + k * .09 - .012, .5 - .27, .945), (0.5 + k * .09 + .012, .5 + .27, .975), GRATE)
for k in range(-2, 3):
    box(scene, 'strainer cross bar', (0.5 - .27, .5 + k * .09 - .012, .945), (0.5 + .27, .5 + k * .09 + .012, .96), GRATE)
export_scene(scene, 'cavity_drain_port.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})

bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'water_valves.blend'))
print('Saved the editable Blender scenes and exported their meshes.')
