"""Blender-authored crane anchor (a steel lifting eye) for the shield plug lift. Run headless:

    blender.exe --background --python art/models/crane_anchor/build_models.py

Writes eye.json (mesh and UV data for tools-export-crane-anchor.py), crane_anchor.blend and a Workbench review render.

Game coordinates, one unit is one block. The anchor block stands on the top face of a shield plug block (y = 0 is the top of the
plug): a square base plate with four bolt heads, a collar and a ring (the eye) standing in the x-y plane, x 0.33..0.67 - small enough
for four to sit on one block face row, wide enough to read from across the refuel floor.
"""
import json
import math
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector

OUT = Path(__file__).resolve().parent


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


def game(p):
    return (p[0], p[2], -p[1])


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def material(name, rgb, metal=0.0):
    m = bpy.data.materials.new('Anchor | ' + name)
    m.diffuse_color = (*(lin(c) for c in rgb), 1)
    m.metallic = metal
    m.roughness = 0.45
    return m


scene = bpy.data.scenes.new('BWR | crane anchor')
bpy.context.window.scene = scene if bpy.context.window else bpy.context.window


def mesh_object(name, vs, fs, mat):
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


BOX_FACES = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]


def box(name, lo, hi, mat):
    x0, x1 = sorted((lo[0], hi[0]))
    y0, y1 = sorted((lo[1], hi[1]))
    z0, z1 = sorted((lo[2], hi[2]))
    vs = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    return mesh_object(name, vs, BOX_FACES, mat)


def cyl_y(name, cx, cz, y0, y1, r, mat, n=12):
    vs = [(cx + r * math.cos(i * math.tau / n), y, cz + r * math.sin(i * math.tau / n)) for y in (y0, y1) for i in range(n)]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))] + [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(name, vs, fs, mat)


def ring(name, cx, cy, cz, major, minor, mat, nu=20, nv=8):
    """A torus standing in the x-y plane (axis along z)."""
    vs = []
    for i in range(nu):
        a = i * math.tau / nu
        for j in range(nv):
            b = j * math.tau / nv
            r = major + minor * math.cos(b)
            vs.append((cx + r * math.cos(a), cy + r * math.sin(a), cz + minor * math.sin(b)))
    fs = [(i * nv + j, ((i + 1) % nu) * nv + j, ((i + 1) % nu) * nv + (j + 1) % nv, i * nv + (j + 1) % nv) for i in range(nu) for j in range(nv)]
    return mesh_object(name, vs, fs, mat)


STEEL = material('forged steel', (120, 128, 138), .8)
PLATE = material('base plate', (92, 98, 106), .7)
BOLT = material('bolt heads', (52, 56, 62), .6)
PAINT = material('safety yellow ring', (230, 176, 28))

box('plate', (.28, 0, .28), (.72, .07, .72), PLATE)
for sx in (.34, .66):
    for sz in (.34, .66):
        cyl_y('bolt', sx, sz, .07, .10, .035, BOLT, 8)
cyl_y('collar', .5, .5, .07, .20, .075, STEEL, 12)
ring('eye', .5, .38, .5, .15, .04, STEEL)
box('pin', (.42, .20, .465), (.58, .24, .535), STEEL)


def export_scene(sc, filename):
    faces, boxes, mats = [], [], {}
    for o in sc.objects:
        if o.type != 'MESH':
            continue
        me = o.data
        me.calc_loop_triangles()
        points = [game(o.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in points) for a in range(3)] + [max(p[a] for p in points) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            key = m.name
            if key not in mats:
                mats[key] = {'color': [srgb(x) for x in m.diffuse_color[:3]], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(points[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3]) - Vector(vs[0][:3])).cross(Vector(vs[2][:3]) - Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
    (OUT / filename).write_text(json.dumps({'faces': faces, 'boxes': boxes, 'materials': mats}, separators=(',', ':')), encoding='utf-8')
    return len(faces)


n = export_scene(scene, 'eye.json')
print('crane anchor eye: %d triangles' % n)

# review render (Workbench)
try:
    cam_data = bpy.data.cameras.new('cam')
    cam = bpy.data.objects.new('cam', cam_data)
    scene.collection.objects.link(cam)
    cam.location = xyz((1.5, 1.1, 1.5))
    direction = xyz((.5, .3, .5)) - cam.location
    cam.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    scene.camera = cam
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.render.resolution_x = 512
    scene.render.resolution_y = 512
    scene.render.filepath = str(OUT / 'crane-anchor-review.png')
    scene.display.shading.color_type = 'MATERIAL'
    bpy.ops.render.render(write_still=True, scene=scene.name)
except Exception as e:  # the review picture is a convenience
    print('review render skipped:', e)
bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'crane_anchor.blend'))
