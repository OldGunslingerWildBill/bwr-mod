"""Blender-authored radioisotope thermoelectric generator (RTG): finned cylindrical housing on a base plate.
Run headless:

    blender.exe --background --python art/models/rtg/build_models.py

Writes rtg.json (mesh and UV data for tools-export-rtg.py), rtg.blend (the editable scene) and preview PNGs.
One unit is one block; game coordinates are X right, Y up, Z south. The model is rotationally symmetric (no blockstate
rotation): FE power ports on the +X and -X sides of the base, hazard warning signs on the -Z and +Z sides.
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


def material(name, rgb, metal=0.0):
    m = bpy.data.materials.new('RTG | ' + name)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value = (*rgb, 1)
    bsdf.inputs['Metallic'].default_value = metal
    bsdf.inputs['Roughness'].default_value = 0.35
    return m


STEEL = material('stainless steel', (.52, .58, .62), .7)
FIN = material('radiator fin', (.40, .45, .49), .6)
BODY = material('housing steel', (.24, .28, .31), .6)
DARK = material('socket dark', (.02, .025, .03))
GREY = material('base grey', (.30, .33, .35), .5)
YELLOW = material('hazard yellow', (.85, .64, .04))
BLACK = material('hazard black', (.012, .012, .014))
COPPER = material('terminal copper', (.72, .38, .16), .8)
BLUE = material('power blue', (.015, .28, .55))


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


def box(scene, name, lo, hi, mat):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    vs = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    fs = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5)]
    return mesh_object(scene, name, vs, fs, mat)


def lathe(scene, name, profile, mat, cx=.5, cz=.5, segments=24):
    """Solid of revolution about the vertical axis through (cx, cz). profile = [(radius, y), ...] from bottom to top;
    a radius of 0 at either end closes the solid on the axis, otherwise flat caps are added."""
    vs, rings = [], []
    for r, y in profile:
        if r <= 1e-9:
            rings.append(len(vs))
            vs.append((cx, y, cz))
        else:
            rings.append(list(range(len(vs), len(vs) + segments)))
            vs += [(cx + r * math.cos(t * math.tau / segments), y, cz + r * math.sin(t * math.tau / segments)) for t in range(segments)]
    fs = []
    for a, b in zip(rings[:-1], rings[1:]):
        for t in range(segments):
            t2 = (t + 1) % segments
            if isinstance(a, int):
                fs.append((a, b[t2], b[t]))
            elif isinstance(b, int):
                fs.append((a[t], a[t2], b))
            else:
                fs.append((a[t], a[t2], b[t2], b[t]))
    for ring in (rings[0], rings[-1]):
        if not isinstance(ring, int):
            fs.append(tuple(ring))
    return mesh_object(scene, name, vs, fs, mat)


def cyl(scene, name, y0, y1, r, mat, segments=24, cx=.5, cz=.5):
    return lathe(scene, name, [(r, y0), (r, y1)], mat, cx, cz, segments)


def prism(scene, name, outline, z0, dz, thick, mat):
    """Extrude a flat 2D outline [(x, y), ...] (game x/y) from plane z0 along dz (+1 or -1) by thick."""
    n = len(outline)
    vs = [(x, y, z0) for x, y in outline] + [(x, y, z0 + dz * thick) for x, y in outline]
    fs = [tuple(range(n)), tuple(range(2 * n - 1, n - 1, -1))]
    fs += [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(scene, name, vs, fs, mat)


def sector(cx, cy, r0, r1, a0, a1, steps=8):
    outer = [(cx + r1 * math.cos(a0 + (a1 - a0) * i / steps), cy + r1 * math.sin(a0 + (a1 - a0) * i / steps)) for i in range(steps + 1)]
    inner = [(cx + r0 * math.cos(a1 - (a1 - a0) * i / steps), cy + r0 * math.sin(a1 - (a1 - a0) * i / steps)) for i in range(steps + 1)]
    return outer + inner


def disc(cx, cy, r, steps=16):
    return [(cx + r * math.cos(i * math.tau / steps), cy + r * math.sin(i * math.tau / steps)) for i in range(steps)]


def hazard_sign(scene, dz):
    """Yellow radiation warning plate standing on the base at the -Z (dz = -1) or +Z (dz = +1) edge, with a raised
    black border and trefoil on its outer face."""
    zs = (.07, .105) if dz < 0 else (.895, .93)
    out_face = zs[0] if dz < 0 else zs[1]
    t = .006
    lo, hi = (out_face - t, out_face) if dz < 0 else (out_face, out_face + t)
    cx, cy = .5, .215
    box(scene, 'sign plate', (.30, .12, zs[0]), (.70, .31, zs[1]), YELLOW)
    for x in (.33, .67):
        box(scene, 'sign standoff', (x - .02, .10, zs[0] + .005), (x + .02, .12, zs[1] - .005), GREY)
    box(scene, 'sign border top', (.30, .295, lo), (.70, .31, hi), BLACK)
    box(scene, 'sign border bottom', (.30, .12, lo), (.70, .135, hi), BLACK)
    box(scene, 'sign border left', (.30, .135, lo), (.315, .295, hi), BLACK)
    box(scene, 'sign border right', (.685, .135, lo), (.70, .295, hi), BLACK)
    for k in range(3):
        a0 = math.radians(60 + 120 * k)
        prism(scene, 'trefoil blade', sector(cx, cy, .032, .088, a0, a0 + math.radians(60)), out_face, dz, t, BLACK)
    prism(scene, 'trefoil hub', disc(cx, cy, .022), out_face, dz, t, BLACK)


def power_port(scene, side):
    """FE connector nub on the base plate: +X for side = +1, mirrored to -X for side = -1."""
    def bx(name, lo, hi, mat):
        a, b = (lo[0], hi[0]) if side > 0 else (1 - hi[0], 1 - lo[0])
        box(scene, name, (a, lo[1], lo[2]), (b, hi[1], hi[2]), mat)
    bx('port housing', (.80, .10, .39), (.95, .25, .61), BODY)
    bx('port collar', (.93, .11, .41), (.97, .24, .59), STEEL)
    bx('port face', (.97, .13, .43), (.985, .22, .57), COPPER)
    bx('port socket', (.985, .15, .46), (.995, .20, .54), DARK)
    bx('port cable clamp', (.84, .25, .45), (.90, .28, .55), BLUE)


scene = bpy.data.scenes.new('BWR | rtg')

# ---- base: plate on four small feet ----
box(scene, 'base plate', (.05, .05, .05), (.95, .10, .95), GREY)
for x in (.08, .84):
    for z in (.08, .84):
        box(scene, 'foot', (x, 0, z), (x + .08, .05, z + .08), BODY)
for x in (.12, .88):
    for z in (.12, .88):
        cyl(scene, 'anchor bolt', .10, .115, .022, STEEL, 6, x, z)

# ---- core housing ----
cyl(scene, 'housing lower collar', .10, .14, .34, BODY)
cyl(scene, 'housing', .14, .36, .30, STEEL)
cyl(scene, 'housing under fins', .36, .84, .30, BODY)
cyl(scene, 'hazard band', .15, .24, .312, YELLOW)
cyl(scene, 'hazard band stripe', .185, .205, .316, BLACK)
cyl(scene, 'fin collar low', .30, .36, .34, BODY)

# ---- radiator fins: 10 thin stacked plates ----
for i in range(10):
    y0 = .37 + i * .047
    cyl(scene, 'radiator fin %d' % i, y0, y0 + .014, .43, FIN, 32)

# ---- four vertical tie rods through the fin stack ----
for a in range(4):
    ang = math.radians(45 + 90 * a)
    cyl(scene, 'fin tie rod', .34, .84, .014, STEEL, 6, .5 + .37 * math.cos(ang), .5 + .37 * math.sin(ang))

# ---- top: flange, seam ring and dome cap ----
cyl(scene, 'top flange', .84, .88, .35, BODY)
cyl(scene, 'dome seam ring', .88, .90, .27, BODY, 32)
lathe(scene, 'dome cap', [(0, .88), (.17, .88), (.24, .89), (.265, .92), (.255, .95), (.2, .975), (.1, .993), (0, 1.0)], STEEL, segments=32)

# ---- hazard signs (N and S) and FE ports (E and W) ----
hazard_sign(scene, -1)
hazard_sign(scene, +1)
power_port(scene, +1)
power_port(scene, -1)


def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def export_scene(scene, filename, meta):
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
                bsdf = next((n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled'), None) if m.node_tree else None
                rgb = list(bsdf.inputs['Base Color'].default_value[:3]) if bsdf else list(m.diffuse_color[:3])
                mats[key] = {'color': [srgb(x) for x in rgb], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(points[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3]) - Vector(vs[0][:3])).cross(Vector(vs[2][:3]) - Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
    (OUT / filename).write_text(json.dumps(dict(meta, faces=faces, boxes=boxes, materials=mats), separators=(',', ':')), encoding='utf-8')
    print(filename, len(faces), 'triangles')


export_scene(scene, 'rtg.json', {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': []})
bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'rtg.blend'))
print('Saved the editable Blender scene and exported its mesh.')


def preview(sc, filename, size=(1000, 800), eye=(1.6, -2.1, 1.5), scale=1.15):
    pv = bpy.data.scenes.new('Preview | ' + filename)
    for o in sc.objects:
        pv.collection.objects.link(o.copy())
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
    cam_data.ortho_scale = max(w, h * size[0] / size[1]) * scale
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
    for engine in ('BLENDER_EEVEE_NEXT', 'BLENDER_EEVEE', 'BLENDER_WORKBENCH'):
        try:
            pv.render.engine = engine
            break
        except TypeError:
            continue
    pv.render.resolution_x, pv.render.resolution_y = size
    pv.render.film_transparent = False
    pv.render.filepath = str(OUT / filename)
    bpy.ops.render.render(write_still=True, scene=pv.name)


if '--no-preview' not in sys.argv:
    # Blender: game x -> X, game z -> -Y, so an eye at +Y looks at the north (-Z) face.
    preview(scene, 'rtg.png', eye=(-1.4, 2.2, 1.3))
    preview(scene, 'rtg_back.png', eye=(1.6, -2.2, 1.3))
