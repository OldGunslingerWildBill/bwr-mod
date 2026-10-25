"""Blender-authored Large RTG: a 2 x 3 x 2 block assembly (2 wide in x, 3 high, 2 deep in z), authored facing north.
Run headless:

    blender.exe --background --python art/models/rtg/build_large.py

Writes rtg_large.json (mesh and UV data for tools-export-large-rtg.py), rtg_large.blend and preview PNGs. Game
coordinates: X east, Y up, Z south; the front (the face with the loading hatch, placards and nameplate) is north (-Z).
A heavy eight-sided shield jacket on a plinth, a square-finned radiator stack and lifting cap above it, copper FE
terminals low on the east and west faces, a hinged loading hatch with an intake funnel, hazard placards and a plain
(blank) nameplate. Everything lies inside [0,2] x [0,3] x [0,2].
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
    m = bpy.data.materials.new('LargeRTG | ' + name)
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
JACKET = material('shield jacket', (.33, .37, .40), .6)
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


def cyl(scene, name, y0, y1, r, mat, segments=24, cx=1.0, cz=1.0):
    """Vertical cylinder about (cx, cz) from y0 to y1."""
    vs = [(cx + r * math.cos(t * math.tau / segments), y, cz + r * math.sin(t * math.tau / segments)) for y in (y0, y1) for t in range(segments)]
    fs = [tuple(range(segments)), tuple(range(2 * segments - 1, segments - 1, -1))]
    fs += [(t, (t + 1) % segments, (t + 1) % segments + segments, t + segments) for t in range(segments)]
    return mesh_object(scene, name, vs, fs, mat)


def prism_poly(scene, name, sides, apothem, y0, y1, mat, cx=1.0, cz=1.0):
    """Regular prism with flat faces on the four axes (phase half a step); apothem is the distance to each flat face."""
    r = apothem / math.cos(math.pi / sides)
    ph = math.pi / sides
    vs = [(cx + r * math.cos(ph + t * math.tau / sides), y, cz + r * math.sin(ph + t * math.tau / sides)) for y in (y0, y1) for t in range(sides)]
    fs = [tuple(range(sides)), tuple(range(2 * sides - 1, sides - 1, -1))]
    fs += [(t, (t + 1) % sides, (t + 1) % sides + sides, t + sides) for t in range(sides)]
    return mesh_object(scene, name, vs, fs, mat)


def extrude_z(scene, name, outline, z0, dz, thick, mat):
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


def hazard_plate(scene, x0, x1, y0, y1, zface, dz):
    """Yellow placard with a black border and trefoil; its outward face is at zface (dz -1 faces north, +1 faces south)."""
    t = .012
    lo, hi = (zface - t, zface) if dz < 0 else (zface, zface + t)
    if dz < 0:
        box(scene, 'placard plate', (x0, y0, zface), (x1, y1, zface + .05), YELLOW)
    else:
        box(scene, 'placard plate', (x0, y0, zface - .05), (x1, y1, zface), YELLOW)
    b = .018
    box(scene, 'placard border top', (x0, y1 - b, lo), (x1, y1, hi), BLACK)
    box(scene, 'placard border bottom', (x0, y0, lo), (x1, y0 + b, hi), BLACK)
    box(scene, 'placard border left', (x0, y0 + b, lo), (x0 + b, y1 - b, hi), BLACK)
    box(scene, 'placard border right', (x1 - b, y0 + b, lo), (x1, y1 - b, hi), BLACK)
    cx, cy, r1 = (x0 + x1) / 2, (y0 + y1) / 2, min(x1 - x0, y1 - y0) * .34
    for k in range(3):
        a0 = math.radians(60 + 120 * k)
        extrude_z(scene, 'trefoil blade', sector(cx, cy, r1 * .36, r1, a0, a0 + math.radians(60)), zface, dz, t, BLACK)
    extrude_z(scene, 'trefoil hub', disc(cx, cy, r1 * .25), zface, dz, t, BLACK)


def terminal(scene, side):
    """Copper FE terminal on the plinth, +X for side = +1 (x = 2), mirrored to -X (x = 0) for side = -1."""
    def bx(name, lo, hi, mat):
        a, b = (lo[0], hi[0]) if side > 0 else (2 - hi[0], 2 - lo[0])
        box(scene, name, (a, lo[1], lo[2]), (b, hi[1], hi[2]), mat)
    bx('terminal housing', (1.82, .20, .78), (1.97, .44, 1.22), BODY)
    bx('terminal collar', (1.95, .22, .82), (1.99, .42, 1.18), STEEL)
    bx('terminal face', (1.99, .25, .86), (2.0, .39, 1.14), COPPER)
    bx('terminal socket left', (1.99, .29, .89), (2.0, .35, .98), DARK)
    bx('terminal socket right', (1.99, .29, 1.02), (2.0, .35, 1.11), DARK)
    bx('terminal cable clamp', (1.84, .44, .86), (1.92, .50, 1.14), BLUE)


scene = bpy.data.scenes.new('BWR | rtg large')

# ---- base: slab on four feet, then the plinth ----
box(scene, 'base slab', (.06, .08, .06), (1.94, .20, 1.94), GREY)
for x in (.10, 1.74):
    for z in (.10, 1.74):
        box(scene, 'foot', (x, 0, z), (x + .16, .08, z + .16), BODY)
box(scene, 'plinth', (.14, .20, .14), (1.86, .46, 1.86), BODY)
for x in (.22, 1.78):
    for z in (.22, 1.78):
        cyl(scene, 'plinth bolt', .46, .49, .035, STEEL, 6, x, z)

# ---- the shield jacket: eight flat faces, bands, hazard stripe ----
prism_poly(scene, 'shield jacket', 8, .90, .46, 2.00, JACKET)
prism_poly(scene, 'jacket foot ring', 8, .94, .46, .62, BODY)
prism_poly(scene, 'hazard band', 8, .925, .66, .84, YELLOW)
prism_poly(scene, 'hazard band stripe', 8, .935, .72, .78, BLACK)
for i, y in enumerate((1.00, 1.38, 1.76)):
    prism_poly(scene, 'shield band %d' % i, 8, .935, y, y + .07, BODY)
prism_poly(scene, 'jacket crown', 8, .96, 1.94, 2.02, BODY)

# ---- shield plug and the radiator stack ----
cyl(scene, 'shield plug', 2.02, 2.12, .70, STEEL, 32)
for k in range(12):
    a = k * math.tau / 12
    cyl(scene, 'plug bolt', 2.12, 2.145, .022, BODY, 6, 1 + .62 * math.cos(a), 1 + .62 * math.sin(a))
for i in range(14):
    y0 = 2.16 + i * .055
    box(scene, 'radiator fin %d' % i, (.16, y0, .16), (1.84, y0 + .028, 1.84), FIN)
cyl(scene, 'core pipe', 2.12, 2.92, .17, STEEL, 24)
for x in (.24, 1.76):
    for z in (.24, 1.76):
        cyl(scene, 'fin tie rod', 2.12, 2.94, .022, STEEL, 6, x, z)
box(scene, 'cap plate', (.22, 2.92, .22), (1.78, 2.97, 1.78), BODY)
for x in (.7, 1.3):
    box(scene, 'lifting lug', (x - .03, 2.97, .8), (x + .03, 3.0, 1.2), STEEL)

# ---- four corner posts carry the radiator stack off the crown ----
for x in (.18, 1.74):
    for z in (.18, 1.74):
        box(scene, 'stack post', (x, 2.02, z), (x + .08, 2.16, z + .08), BODY)

# ---- FE terminals ----
terminal(scene, +1)
terminal(scene, -1)

# ---- the front (north, -Z): hatch, funnel, placard, nameplate ----
Z = .10   # the jacket's north face is at z = 1 - .90 = .10
box(scene, 'hatch frame', (.56, .90, Z - .045), (1.44, 1.62, Z), BODY)
box(scene, 'hatch door', (.62, .96, Z - .07), (1.38, 1.56, Z - .045), STEEL)
for y in (1.04, 1.40):
    box(scene, 'hatch hinge', (.575, y, Z - .085), (.655, y + .1, Z - .045), BODY)
box(scene, 'hatch handle post a', (1.18, 1.19, Z - .095), (1.22, 1.23, Z - .07), STEEL)
box(scene, 'hatch handle post b', (1.18, 1.29, Z - .095), (1.22, 1.33, Z - .07), STEEL)
box(scene, 'hatch handle bar', (1.14, 1.19, Z - .10), (1.26, 1.33, Z - .095), STEEL)
box(scene, 'hatch seam', (.62, 1.255, Z - .073), (1.38, 1.265, Z - .07), DARK)
box(scene, 'intake funnel throat', (1.02, 1.62, Z - .06), (1.30, 1.70, Z + .05), BODY)
box(scene, 'intake funnel mouth', (.96, 1.70, Z - .10), (1.36, 1.78, Z + .09), STEEL)
box(scene, 'intake funnel lip', (.94, 1.78, Z - .10), (1.38, 1.80, Z + .10), BODY)
ZN = .058   # placards stand proud of the shield bands (their faces are at .065)
hazard_plate(scene, .20, .50, 1.00, 1.34, ZN, -1)
hazard_plate(scene, .62, .90, 1.66, 1.94, ZN, -1)
ZP = .07
box(scene, 'nameplate', (.60, .50, ZP - .02), (1.40, .72, ZP + .03), GREY)
for x in (.66, .76, .86, .96, 1.06, 1.16):
    box(scene, 'nameplate bar', (x, .57, ZP - .03), (x + .05, .65, ZP - .02), STEEL)
box(scene, 'nameplate rivet a', (.62, .52, ZP - .03), (.65, .55, ZP - .02), STEEL)
box(scene, 'nameplate rivet b', (1.35, .67, ZP - .03), (1.38, .70, ZP - .02), STEEL)

# ---- the back (south, +Z): a placard and a service ladder ----
ZS = 2.0 - .058
hazard_plate(scene, .62, 1.38, 1.15, 1.75, ZS, +1)
for x in (.70, 1.30):
    box(scene, 'service ladder rail', (x - .015, .50, ZS), (x + .015, 1.00, ZS + .04), STEEL)
for y in (.58, .70, .82, .94):
    box(scene, 'service ladder rung', (.70, y, ZS + .02), (1.30, y + .02, ZS + .04), STEEL)


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


export_scene(scene, 'rtg_large.json', {'size': [2, 3, 2], 'controller': [0, 0, 0], 'ports': []})
bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'rtg_large.blend'))
print('Saved the editable Blender scene and exported its mesh.')


def preview(sc, filename, size=(1000, 1200), eye=(1.6, -2.1, 1.5), scale=1.15):
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
    preview(scene, 'rtg_large.png', eye=(-1.4, 2.2, 1.3))
    preview(scene, 'rtg_large_back.png', eye=(1.6, -2.2, 1.3))
