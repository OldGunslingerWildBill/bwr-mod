"""Blender-authored new fuel elevator as parts. Run headless:

    blender.exe --background --python art/models/new_fuel_elevator/build_models.py

Writes one <part>.json per part (mesh and UV data for tools-export-new-fuel-elevator.py), new_fuel_elevator.blend (the editable part
scenes plus a review scene of an elevator on a 7 m pool wall) and review renders (Workbench).

Game coordinates, one unit is one block (one metre). The elevator block stands on the rim of a pool wall: the block is x 0..1, z 0..1, y 0..1
over the wall top (y = 0 is the top of the wall block), and the pool lies towards +X (the renderer turns every part so that +X is "into the
pool"). The wall's inside face is x = 1.0; the guide frame hangs against it; the guide axis (the carried assembly) is at x = 1.45, z = 0.5.

Parts (the renderer places them; see NewFuelElevatorRenderer):
  head      the stand on the rim: a steel frame with a deck level with the hatch (y = 1.0), a hatch hole over the guide column, a handrail,
            a gantry over the hatch with the hoist motor, gearbox and cable drum, and a small control cabinet with a screen and a beacon.
  guide     one metre of the guide frame (two rails with lips, cross ties and a diagonal), x 1.0 to 1.14, tiled down the inside face of the
            wall to the pool floor.
  carriage  origin at its hook, on the assembly axis: a blue frame with rollers on the two rails, a yoke and jaws round the handle of the
            assembly, a sheave and an eye for the cable at y = +0.55.
  cable     a unit rope (y 0 to 1) the renderer stretches from the carriage's eye up to the drum.
"""
import json
import math
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector

OUT = Path(__file__).resolve().parent
SCENES = []
PARTS = {}


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


def game(p):
    return (p[0], p[2], -p[1])


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def material(name, rgb, metal=0.0, texture=None):
    m = bpy.data.materials.new('Elevator | ' + name)
    col = (1.0, 1.0, 1.0) if texture else tuple(lin(c) for c in rgb)
    m.diffuse_color = (*col, 1)
    m.metallic = metal
    m.roughness = 0.45
    if texture:
        m['texture'] = texture
    return m



def new_scene(name):
    s = bpy.data.scenes.new(name)
    SCENES.append(s)
    return s


def mesh_object(scene, name, vs, fs, mat, uvs=None, fix=True):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([xyz(p) for p in vs], [], fs)
    mesh.update()
    if fix:
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(mesh)
        bm.free()
    uv = mesh.uv_layers.new(name='Material UV')
    if uvs is None:
        for loop in uv.data:
            loop.uv = (.5, .5)
    else:
        for poly in mesh.polygons:
            for k, li in enumerate(poly.loop_indices):
                uv.data[li].uv = uvs[k % len(uvs)]
    o = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(o)
    mesh.materials.append(mat)
    return o


BOX_FACES = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]


def box(s, name, lo, hi, mat, uvs=None):
    x0, x1 = sorted((lo[0], hi[0]))
    y0, y1 = sorted((lo[1], hi[1]))
    z0, z1 = sorted((lo[2], hi[2]))
    vs = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    return mesh_object(s, name, vs, BOX_FACES, mat, uvs)


def beam(s, name, a, b, w, d, mat, side=None):
    """A square-ended member from a to b, w wide along `side` (default: horizontal, across the member), d deep the other way."""
    a, b = Vector(a), Vector(b)
    dirv = (b - a).normalized()
    sv = Vector(side) if side is not None else dirv.cross(Vector((0, 1, 0)))
    if sv.length < 1e-6:
        sv = Vector((1, 0, 0))
    sv = (sv - dirv * sv.dot(dirv)).normalized()
    tv = dirv.cross(sv).normalized()
    vs = []
    for p in (a, b):
        for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            vs.append(tuple(p + sv * (sx * w / 2) + tv * (sy * d / 2)))
    return mesh_object(s, name, vs, BOX_FACES, mat)


def basis(axis):
    d = Vector(axis).normalized()
    a = d.cross(Vector((0, 1, 0)) if abs(d.y) < .9 else Vector((1, 0, 0))).normalized()
    return d, a, d.cross(a).normalized()


def cyl(s, name, a, b, r, mat, n=12):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for p in (a, b) for i in range(n)]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))] + [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def sleeve(s, name, a, b, outer, inner, mat, n=16):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    rings = [(a, outer), (b, outer), (b, inner), (a, inner)]
    vs = [tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for p, r in rings for i in range(n)]
    fs = [(r * n + i, r * n + (i + 1) % n, ((r + 1) % 4) * n + (i + 1) % n, ((r + 1) % 4) * n + i) for r in range(4) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def sweep(s, name, path, r, mat, n=4):
    pts = [Vector(p) for p in path]
    vs = []
    u = None
    for j, p in enumerate(pts):
        d = (pts[min(len(pts) - 1, j + 1)] - pts[max(0, j - 1)]).normalized()
        if u is None:
            u = basis(d)[1]
        else:
            u = (u - d * u.dot(d)).normalized()
        v = d.cross(u).normalized()
        vs.extend(tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for i in range(n))
    fs = [(j * n + i, j * n + (i + 1) % n, (j + 1) * n + (i + 1) % n, (j + 1) * n + i) for j in range(len(pts) - 1) for i in range(n)]
    fs += [tuple(range(n - 1, -1, -1)), tuple(range((len(pts) - 1) * n, len(pts) * n))]
    return mesh_object(s, name, vs, fs, mat)


def part(name):
    s = new_scene('BWR | elevator ' + name)
    PARTS[name] = s
    return s




YEL = material('safety yellow', (247, 176, 18))
YELD = material('yellow shade', (226, 150, 10))
BLUE = material('hoist blue', (26, 86, 172))
BLUED = material('hoist blue dark', (18, 60, 126))
GALV = material('galvanised', (196, 202, 206), .7)
GALVD = material('galvanised dark', (150, 156, 162), .6)
DARK = material('black steel', (28, 30, 34))
GRAT = material('deck grating', (64, 68, 72))
CAB = material('cabinet grey', (150, 156, 152))
SCREEN = material('screen glass', (14, 44, 50))
RED = material('stop button', (205, 30, 24))
AMBER = material('beacon', (250, 150, 20))
ORG = material('drum orange', (250, 104, 14))
CABLE = material('cable black', (20, 20, 22))
WALL = material('pool wall', (120, 124, 128))
WATER = material('review water', (60, 120, 190))

GX = 1.45
GZ = 0.5
DECK = 1.0
BEAM_Y = 2.25


def build_head():
    s = part('head')
    box(s, 'base plate', (0.02, 0, 0.04), (0.98, 0.05, 0.96), DARK)
    posts = [(0.1, 0.12), (0.1, 0.88), (0.9, 0.12), (0.9, 0.88)]
    for x, z in posts:
        box(s, 'stand post', (x - .045, .05, z - .045), (x + .045, DECK - .03, z + .045), YEL)
    for z in (0.12, 0.88):
        beam(s, 'stand brace', (.1, .12, z), (.9, .9, z), .05, .04, YELD, side=(0, 0, 1))
        beam(s, 'stand brace', (.9, .12, z), (.1, .9, z), .05, .04, YELD, side=(0, 0, 1))
        box(s, 'stand rail', (.055, .88, z - .03), (.945, .95, z + .03), YEL)
    for x in (0.1, 0.9):
        box(s, 'stand tie', (x - .03, .88, .075), (x + .03, .95, .925), YEL)
    # deck: the hatch hole is x 1.12..1.7, z 0.28..0.72 over the guide column
    box(s, 'deck', (0.06, DECK - .03, 0.08), (1.12, DECK, 0.92), GRAT)
    box(s, 'deck', (1.12, DECK - .03, 0.08), (1.8, DECK, 0.28), GRAT)
    box(s, 'deck', (1.12, DECK - .03, 0.72), (1.8, DECK, 0.92), GRAT)
    box(s, 'deck', (1.7, DECK - .03, 0.28), (1.8, DECK, 0.72), GRAT)
    for z in (0.2, 0.8):
        box(s, 'deck beam', (0.9, DECK - .1, z - .03), (1.8, DECK - .03, z + .03), YELD)
        beam(s, 'cantilever strut', (0.9, .5, z), (1.75, DECK - .08, z), .05, .04, YEL, side=(0, 0, 1))
    # hatch coaming
    for lo, hi in (((1.09, DECK, .25), (1.12, DECK + .1, .75)), ((1.7, DECK, .25), (1.73, DECK + .1, .75)),
                   ((1.12, DECK, .25), (1.7, DECK + .1, .28)), ((1.12, DECK, .72), (1.7, DECK + .1, .75))):
        box(s, 'coaming', lo, hi, YEL)
    # handrail: posts, top and mid rails and a kick plate round the deck
    runs = [((0.06, 0.08), (0.06, 0.92)), ((0.06, 0.08), (1.78, 0.08)), ((0.06, 0.92), (1.78, 0.92)), ((1.78, 0.08), (1.78, 0.92))]
    for (ax, az), (bx, bz) in runs:
        n = max(1, int(round(math.hypot(bx - ax, bz - az) / 0.6)))
        for k in range(n + 1):
            px, pz = ax + (bx - ax) * k / n, az + (bz - az) * k / n
            box(s, 'rail post', (px - .02, DECK, pz - .02), (px + .02, DECK + .98, pz + .02), GALV)
        for h in (.98, .52):
            beam(s, 'rail', (ax, DECK + h, az), (bx, DECK + h, bz), .035, .035, GALV)
        beam(s, 'kick plate', (ax, DECK + .06, az), (bx, DECK + .06, bz), .012, .12, GALVD)
    # the guide rails go on up past the deck to the gantry, so the carriage can ride up to the hatch
    for c in (GZ - .17, GZ + .17):
        box(s, 'guide rail', (1.0, 0, c - .03), (1.1, BEAM_Y, c + .03), GALV)
        box(s, 'guide lip', (1.1, 0, c - .06), (1.14, BEAM_Y, c + .06), GALVD)
    # gantry over the hatch, hoist motor, gearbox and drum
    for z in (0.16, 0.84):
        box(s, 'gantry post', (GX - .05, DECK, z - .05), (GX + .05, BEAM_Y, z + .05), YEL)
    box(s, 'gantry beam', (GX - .09, BEAM_Y, .1), (GX + .09, BEAM_Y + .1, .9), YEL)
    box(s, 'hoist motor', (GX - .22, BEAM_Y + .1, .5 - .2), (GX + .22, BEAM_Y + .42, .5 + .2), BLUE)
    box(s, 'motor cap', (GX - .22, BEAM_Y + .1, .5 - .26), (GX + .22, BEAM_Y + .42, .5 - .2), BLUED)
    box(s, 'gearbox', (GX - .16, BEAM_Y + .1, .5 + .2), (GX + .16, BEAM_Y + .36, .5 + .36), BLUED)
    cyl(s, 'cable drum', (GX, BEAM_Y - .1, .5 - .22), (GX, BEAM_Y - .1, .5 + .22), .13, ORG, 16)
    cyl(s, 'cable drum flange', (GX, BEAM_Y - .1, .5 - .24), (GX, BEAM_Y - .1, .5 - .22), .17, DARK, 16)
    cyl(s, 'cable drum flange', (GX, BEAM_Y - .1, .5 + .22), (GX, BEAM_Y - .1, .5 + .24), .17, DARK, 16)
    cyl(s, 'cable wraps', (GX, BEAM_Y - .1, .5 - .2), (GX, BEAM_Y - .1, .5 + .2), .135, CABLE, 16)
    # control cabinet with a screen, a stop button and a beacon
    box(s, 'cabinet', (0.2, DECK, 0.14), (0.5, DECK + .62, 0.4), CAB)
    box(s, 'cabinet screen', (0.158, DECK + .34, 0.19), (0.2, DECK + .54, 0.35), SCREEN)
    box(s, 'cabinet button', (0.17, DECK + .22, 0.25), (0.2, DECK + .29, 0.3), RED)
    box(s, 'beacon', (0.31, DECK + .62, 0.23), (0.39, DECK + .74, 0.31), AMBER)


def build_guide():
    s = part('guide')
    for c in (GZ - .17, GZ + .17):
        box(s, 'guide rail', (1.0, 0, c - .03), (1.1, 1.0, c + .03), GALV)
        box(s, 'guide lip', (1.1, 0, c - .06), (1.14, 1.0, c + .06), GALVD)
    for y in (0.0, 0.5):
        box(s, 'guide tie', (1.0, y, GZ - .17), (1.06, y + .06, GZ + .17), YELD)
    beam(s, 'guide diagonal', (1.03, .06, GZ - .15), (1.03, .5, GZ + .15), .04, .03, YELD, side=(1, 0, 0))
    beam(s, 'guide diagonal', (1.03, .56, GZ + .15), (1.03, 1.0, GZ - .15), .04, .03, YELD, side=(1, 0, 0))


def build_carriage():
    s = part('carriage')
    wall = 1.0 - GX            # the wall's inside face, relative to the assembly axis
    box(s, 'carriage plate', (wall + .16, -.05, -.24), (wall + .26, .45, .24), BLUE)
    for c in (-.17, .17):
        box(s, 'roller carrier', (wall + .1, -.05, c - .1), (wall + .16, .45, c + .1), BLUED)
    for z in (-.22, .22):
        box(s, 'yoke arm', (wall + .26, -.05, z - .02), (.22, .24, z + .02), BLUE)
    box(s, 'yoke bridge', (-.2, .2, -.24), (.22, .26, .24), BLUE)
    for c in (-.17, .17):
        for y in (.02, .38):
            for side in (-1, 1):
                box(s, 'roller', (wall + .11, y - .045, c + side * .08 - .02), (wall + .21, y + .045, c + side * .08 + .02), DARK)
    for z in (-.1, .1):
        box(s, 'jaw', (-.12, -.22, z - .02), (.12, -.1, z + .02), YEL)
    box(s, 'hook plate', (-.2, -.1, -.1), (.2, -.04, .1), YEL)
    cyl(s, 'sheave', (0, .55, -.1), (0, .55, .1), .09, GALVD, 12)
    box(s, 'cable eye', (-.02, .26, -.02), (.02, .5, .02), GALV)
    box(s, 'carriage lamp', (wall + .1, .45, -.04), (wall + .18, .52, .04), AMBER)


def build_cable():
    s = part('cable')
    box(s, 'rope', (-.012, 0, -.012), (.012, 1.0, .012), CABLE)


# ------------------------------------------------------------------ export
# ------------------------------------------------------------------ export
def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def export_scene(scene, filename):
    faces, boxes, mats = [], [], {}
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
            key = m.name
            if key not in mats:
                if 'texture' in m:
                    mats[key] = {'color': [1, 1, 1], 'texture': m['texture'], 'tint': -1}
                else:
                    mats[key] = {'color': [srgb(x) for x in m.diffuse_color[:3]], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(points[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3]) - Vector(vs[0][:3])).cross(Vector(vs[2][:3]) - Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
    (OUT / filename).write_text(json.dumps({'faces': faces, 'boxes': boxes, 'materials': mats}, separators=(',', ':')), encoding='utf-8')
    return len(faces)



# ------------------------------------------------------------------ review scene
def review(depth=7):
    """An elevator on a 7 m pool wall: the wall, the water, the guide tiled to the floor, the carriage part way down with an assembly."""
    r = new_scene('BWR | elevator review')

    def place(name, loc=(0, 0, 0), scale=(1, 1, 1)):
        for src in PARTS[name].objects:
            o = src.copy()
            o.data = src.data
            r.collection.objects.link(o)
            o.scale = (scale[0], scale[2], scale[1])
            o.location = xyz(loc)

    box(r, 'wall', (-1.0, -depth, -2.0), (1.0, 0.0, 3.0), WALL)
    box(r, 'pool floor', (1.0, -depth - .2, -2.0), (7.0, -depth, 3.0), WALL)
    place('head')
    for k in range(depth):
        place('guide', (0, -depth + k, 0))
    ycar = -3.0
    place('carriage', (GX, ycar, GZ))
    top = BEAM_Y - .1
    place('cable', (GX, ycar + .55, GZ), (1, top - (ycar + .55), 1))
    return r


def render_review(scene, name, eye, target_game, lens=30):
    cam = bpy.data.cameras.new('review camera ' + name)
    co = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(co)
    co.location = xyz(eye)
    target = xyz(target_game)
    co.rotation_euler = (target - co.location).to_track_quat('-Z', 'Y').to_euler()
    cam.lens = lens
    cam.clip_end = 200
    scene.camera = co
    if not scene.world:
        scene.world = bpy.data.worlds.new('review world')
        scene.world.color = (.42, .5, .58)
        sun = bpy.data.lights.new('review sun', 'SUN')
        so = bpy.data.objects.new('review sun', sun)
        scene.collection.objects.link(so)
        so.rotation_euler = (math.radians(55), 0, math.radians(35))
        scene.render.engine = 'BLENDER_WORKBENCH'
        scene.display.shading.light = 'STUDIO'
        scene.display.shading.color_type = 'MATERIAL'
        scene.render.resolution_x = 1600
        scene.render.resolution_y = 1000
        scene.render.image_settings.file_format = 'PNG'
    scene.render.filepath = str(OUT / ('new-fuel-elevator' + name + '.png'))
    bpy.ops.render.render(write_still=True, scene=scene.name)


def main():
    build_head()
    build_guide()
    build_carriage()
    build_cable()
    total = 0
    for name, scene in PARTS.items():
        n = export_scene(scene, name + '.json')
        total += n
        print('%-10s %5d triangles' % (name, n))
    print('all parts %d triangles' % total)
    r = review()
    if '--no-render' not in sys.argv:
        render_review(r, '-review', (6.5, -0.5, -4.5), (1.3, -2.8, 0.5), 38)
        render_review(r, '-top', (-3.0, 3.0, 3.8), (1.3, 1.0, 0.5), 38)
    bpy.data.libraries.write(str(OUT / 'new_fuel_elevator.blend'), set(SCENES), fake_user=True)
    print('new fuel elevator parts written')


main()
