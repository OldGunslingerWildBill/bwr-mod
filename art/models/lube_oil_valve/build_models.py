"""Blender-authored Lube Oil Valve: a quarter-turn ball valve with a limit switch box on top. Run headless:

    blender.exe --background --factory-startup --python art/models/lube_oil_valve/build_models.py

Writes lube_oil_valve_open.json and lube_oil_valve_closed.json (the mesh snapshots tools-export-lube-oil-valve.py bakes),
lube_oil_valve.blend (one editable scene per state) and the previews preview_open.png, preview_closed.png and
preview_switch_box.png (the close-up of the box, open and shut side by side).

Units: the geometry is written in sixteenths of a block (one texel of a vanilla texture) and divided by 16; game coordinates
are X east, Y up, Z south, the flow runs along Z (the blockstate turns it for east and west), the pipe centre is (8, 8).

What it is (see TURBINE-AUX.md, "Lube oil piping"):
  * two bolted flanges on the block faces, the oil pipe's port flange pair (radius 4.5, 1.5 thick, four studs);
  * a full-bore ball valve body in the pipe's lube-oil brown (the paint is baked in as the material colour; the block has no
    colour handler), an actuator on the stem and, on top, the limit switch box;
  * the box has a lid with a green pad pair on the pipe axis ("open" marks) and a red pad pair across it ("shut" marks), a
    conduit entry on the +X side, and an indicator cap: a green cap with a white bar along the pipe when open, a red cap with
    the bar across the pipe when shut. Those two (the bar and the cap material) are the only difference between the two states.
"""
import json
import math
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector

OUT = Path(__file__).resolve().parent

# ---- materials: key -> (sRGB colour as it goes into the MTL, texture). The texture is multiplied by the colour.
PAINT_TEX = 'bwr:block/pipes/painted_steel'      # the pipe's own paint texture, here with the brown baked in
STEEL_TEX = 'bwr:block/pipes/flange_steel'
FLAT_TEX = 'minecraft:block/white_concrete'
MATERIALS = {
    'body_brown': ((0x6F / 255, 0x4A / 255, 0x2E / 255), PAINT_TEX),   # 0x6F4A2E: the oil pipe's default colour
    'flange_steel': ((0.80, 0.80, 0.82), STEEL_TEX),
    'stud_steel': ((0.62, 0.64, 0.68), STEEL_TEX),
    'actuator': ((0.20, 0.27, 0.31), FLAT_TEX),
    'box_grey': ((0.33, 0.36, 0.39), FLAT_TEX),
    'lid_grey': ((0.50, 0.53, 0.56), FLAT_TEX),
    'open_mark': ((0.16, 0.62, 0.24), FLAT_TEX),
    'shut_mark': ((0.72, 0.13, 0.11), FLAT_TEX),
    'cap_open': ((0.10, 0.78, 0.26), FLAT_TEX),
    'cap_shut': ((0.85, 0.10, 0.09), FLAT_TEX),
    'bar': ((0.97, 0.97, 0.94), FLAT_TEX),
    'gasket': ((0.04, 0.045, 0.05), FLAT_TEX),
}

FACES = []   # {'part', 'mat', 'pts': [(x, y, z)] in blocks}


def P(x, y, z):
    return Vector((x / 16, y / 16, z / 16))


def add(part, mat, pts, outward, uvs=None):
    """One polygon, wound so its normal (right hand rule) points along `outward`. Fans anything over four corners."""
    pts = [Vector(p) for p in pts]
    n = Vector((0, 0, 0))
    for i in range(len(pts)):   # Newell's normal
        a, b = pts[i], pts[(i + 1) % len(pts)]
        n += Vector(((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y)))
    if n.length < 1e-12:
        return
    if n.dot(outward) < 0:
        pts.reverse()
        if uvs:
            uvs = list(reversed(uvs))
    if uvs is None:
        k = max(range(3), key=lambda i: abs(n[i]))
        a, b = [i for i in range(3) if i != k]
        uvs = [(p[a], 1 - p[b]) for p in pts]
    if len(pts) <= 4:
        FACES.append({'part': part, 'mat': mat, 'pts': pts, 'uvs': uvs})
    else:
        for i in range(1, len(pts) - 1):
            FACES.append({'part': part, 'mat': mat, 'pts': [pts[0], pts[i], pts[i + 1]], 'uvs': [uvs[0], uvs[i], uvs[i + 1]]})


def frame(axis):
    d = Vector(axis).normalized()
    a = d.cross(Vector((0, 1, 0)) if abs(d.y) < .9 else Vector((1, 0, 0))).normalized()
    return d, a, d.cross(a).normalized()


def cylinder(part, mat, a, b, r, n=12, caps=(True, True), r_b=None):
    """A solid (optionally tapered) cylinder from point a to point b (blocks), n sides."""
    a, b = Vector(a), Vector(b)
    d, u, v = frame(b - a)
    r_b = r if r_b is None else r_b
    ring = lambda c, rr: [c + rr * (math.cos(t * math.tau / n) * u + math.sin(t * math.tau / n) * v) for t in range(n)]
    ra, rb = ring(a, r / 16), ring(b, r_b / 16)
    length = (b - a).length
    for t in range(n):
        s = (t + 1) % n
        mid = (ra[t] + ra[s]) / 2 - a
        mid -= d * mid.dot(d)
        add(part, mat, [ra[t], ra[s], rb[s], rb[t]], mid, [(t / n, 0), ((t + 1) / n, 0), ((t + 1) / n, length), (t / n, length)])
    if caps[0]:
        add(part, mat, ra, -d, [(.5 + .5 * math.cos(t * math.tau / n), .5 + .5 * math.sin(t * math.tau / n)) for t in range(n)])
    if caps[1]:
        add(part, mat, rb, d, [(.5 + .5 * math.cos(t * math.tau / n), .5 + .5 * math.sin(t * math.tau / n)) for t in range(n)])


def box(part, mat, lo, hi, faces='xXyYzZ'):
    """An axis aligned box from lo to hi (sixteenths). faces: x/X = -x/+x, y/Y, z/Z to draw."""
    x0, y0, z0 = [c / 16 for c in lo]
    x1, y1, z1 = [c / 16 for c in hi]
    v = lambda x, y, z: Vector((x, y, z))
    table = {
        'x': ([v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0)], (-1, 0, 0)),
        'X': ([v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), v(x1, y0, z1)], (1, 0, 0)),
        'y': ([v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1), v(x0, y0, z1)], (0, -1, 0)),
        'Y': ([v(x0, y1, z0), v(x0, y1, z1), v(x1, y1, z1), v(x1, y1, z0)], (0, 1, 0)),
        'z': ([v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0), v(x1, y0, z0)], (0, 0, -1)),
        'Z': ([v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1)], (0, 0, 1)),
    }
    for f in faces:
        pts, n = table[f]
        add(part, mat, pts, Vector(n))


def sphere(part, mat, c, r, rings=8, segs=14):
    c = Vector(c)
    rr = r / 16
    grid = []
    for i in range(rings + 1):
        phi = math.pi * i / rings
        grid.append([c + rr * Vector((math.sin(phi) * math.cos(t * math.tau / segs), math.cos(phi), math.sin(phi) * math.sin(t * math.tau / segs)))
                     for t in range(segs)])
    for i in range(rings):
        for t in range(segs):
            s = (t + 1) % segs
            quad = [grid[i][t], grid[i][s], grid[i + 1][s], grid[i + 1][t]]
            uv = [(t / segs, i / rings), ((t + 1) / segs, i / rings), ((t + 1) / segs, (i + 1) / rings), (t / segs, (i + 1) / rings)]
            pts = []
            uvs = []
            for p, q in zip(quad, uv):   # drop the repeated pole point
                if not any((p - o).length < 1e-9 for o in pts):
                    pts.append(p)
                    uvs.append(q)
            add(part, mat, pts, sum(pts, Vector()) / len(pts) - c, uvs)


def flange(part_prefix, z_face, inward):
    """The bolted flange on a block face: the port flange of the oil pipe, 4.5 radius, 1.5 thick, four stud nuts behind it.
    z_face is 0 or 16; inward is +1 for the z=0 end and -1 for the z=16 end."""
    za, zb = z_face, z_face + inward * 1.5
    cylinder(part_prefix + ' flange', 'flange_steel', P(8, 8, za), P(8, 8, zb), 4.5, n=16)
    cylinder(part_prefix + ' gasket', 'gasket', P(8, 8, zb), P(8, 8, zb + inward * .3), 3.1, n=12, caps=(False, True))
    for i in range(4):
        ang = (i + .5) * math.tau / 4
        cx, cy = 8 + 3.5 * math.cos(ang), 8 + 3.5 * math.sin(ang)
        lo = min(zb, zb + inward * 1.0)
        box(part_prefix + ' stud nut', 'stud_steel', (cx - .55, cy - .55, lo), (cx + .55, cy + .55, lo + 1.0))


def build(open_):
    FACES.clear()
    # flanges at both block faces and the bore collars to the ball
    flange('inlet', 0.0, +1)
    flange('outlet', 16.0, -1)
    cylinder('inlet collar', 'body_brown', P(8, 8, 1.8), P(8, 8, 5.2), 2.7, caps=(False, False))
    cylinder('outlet collar', 'body_brown', P(8, 8, 10.8), P(8, 8, 14.2), 2.7, caps=(False, False))
    # the ball valve body: a sphere (the rounded chest) split by a body joint ring
    sphere('valve body', 'body_brown', P(8, 8, 8), 3.7)
    cylinder('body joint', 'body_brown', P(8, 8, 6.5), P(8, 8, 7.0), 3.55, caps=(True, True))
    cylinder('body joint', 'body_brown', P(8, 8, 9.0), P(8, 8, 9.5), 3.55, caps=(True, True))
    # the stem neck and the quarter-turn actuator
    cylinder('stem', 'stud_steel', P(8, 11.0, 8), P(8, 12.0, 8), 1.4, n=8)
    cylinder('actuator base', 'actuator', P(8, 11.4, 8), P(8, 12.2, 8), 3.0, n=12)
    cylinder('actuator housing', 'actuator', P(8, 12.2, 8), P(8, 13.2, 8), 2.5, n=12)
    for sx in (-1, 1):
        for sz in (-1, 1):
            box('actuator stud', 'stud_steel', (8 + sx * 2.4 - .35, 12.0, 8 + sz * 2.4 - .35), (8 + sx * 2.4 + .35, 12.5, 8 + sz * 2.4 + .35))
    # the limit switch box: 7 x 7 x 1.3, lid 0.3 thick, conduit entry on +X
    box('switch box', 'box_grey', (4.5, 13.2, 4.5), (11.5, 14.5, 11.5), faces='xXyzZ')
    box('switch box', 'box_grey', (4.5, 13.2, 4.5), (11.5, 14.5, 11.5), faces='Y')
    box('switch box lid', 'lid_grey', (4.8, 14.5, 4.8), (11.2, 14.8, 11.2))
    cylinder('conduit entry', 'box_grey', P(11.5, 13.85, 8), P(12.7, 13.85, 8), .6, n=8, caps=(False, True))
    cylinder('conduit gland', 'stud_steel', P(12.3, 13.85, 8), P(13.0, 13.85, 8), .85, n=6, caps=(True, True))
    for sx in (-1, 1):
        for sz in (-1, 1):
            box('lid screw', 'stud_steel', (8 + sx * 3.1 - .3, 14.8, 8 + sz * 3.1 - .3), (8 + sx * 3.1 + .3, 15.0, 8 + sz * 3.1 + .3))
    # position marks on the lid: green on the pipe axis (open), red across it (shut)
    for dz in (-1, 1):
        zc = 8 + dz * 2.95
        box('open mark', 'open_mark', (7.35, 14.8, zc - .45), (8.65, 14.95, zc + .45), faces='xXyYzZ')
    for dx in (-1, 1):
        xc = 8 + dx * 2.95
        box('shut mark', 'shut_mark', (xc - .45, 14.8, 7.35), (xc + .45, 14.95, 8.65), faces='xXyYzZ')
    # the indicator cap, in two steps like a dome, and the bar across it
    cap = 'cap_open' if open_ else 'cap_shut'
    cylinder('indicator cap', cap, P(8, 14.8, 8), P(8, 15.25, 8), 2.2, n=12, caps=(False, True))
    cylinder('indicator cap', cap, P(8, 15.25, 8), P(8, 15.6, 8), 1.55, n=12, caps=(True, True))
    if open_:   # along the pipe (Z)
        box('indicator bar', 'bar', (7.45, 15.6, 5.7), (8.55, 16.0, 10.3))
    else:       # across the pipe (X)
        box('indicator bar', 'bar', (5.7, 15.6, 7.45), (10.3, 16.0, 8.55))


def snapshot(filename):
    faces = []
    used = []
    for f in FACES:
        if f['mat'] not in used:
            used.append(f['mat'])
        faces.append({'vertices': [[round(c, 7) for c in p] + [round(u, 7) for u in uv] for p, uv in zip(f['pts'], f['uvs'])],
                      'material': f['mat'], 'part': f['part']})
    pts = [p for f in FACES for p in f['pts']]
    boxes = [[min(p[a] for p in pts) for a in range(3)] + [max(p[a] for p in pts) for a in range(3)]]
    mats = {k: {'color': list(v[0]), 'texture': v[1], 'tint': -1} for k, v in MATERIALS.items()}
    data = {'size': [1, 1, 1], 'controller': [0, 0, 0], 'ports': [], 'faces': faces, 'boxes': boxes, 'materials': mats}
    (OUT / filename).write_text(json.dumps(data, separators=(',', ':')), encoding='utf-8', newline='\n')
    quads = sum(1 for f in FACES if len(f['pts']) == 4)
    print(filename, len(FACES), 'polygons,', quads, 'quads, bounds', [round(min(p[a] for p in pts) * 16, 2) for a in range(3)],
          [round(max(p[a] for p in pts) * 16, 2) for a in range(3)])


# ---- Blender scenes and previews --------------------------------------------------------------------------------------
def lin(x):
    return x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4


def blender_material(key):
    m = bpy.data.materials.new('Lube oil valve | ' + key)
    rgb = [lin(c) for c in MATERIALS[key][0]]
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    b = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    b.inputs['Base Color'].default_value = (*rgb, 1)
    return m


def to_scene(name):
    scene = bpy.data.scenes.new(name)
    mats = {}
    groups = {}
    for f in FACES:
        groups.setdefault((f['part'], f['mat']), []).append(f)
    for (part, mat), faces in groups.items():
        verts, polys = [], []
        for f in faces:
            idx = []
            for p in f['pts']:
                verts.append(Vector((p.x, -p.z, p.y)))
                idx.append(len(verts) - 1)
            polys.append(tuple(idx))
        me = bpy.data.meshes.new(part)
        me.from_pydata(verts, [], polys)
        me.update()
        if mat not in mats:
            mats[mat] = blender_material(mat)
        me.materials.append(mats[mat])
        scene.collection.objects.link(bpy.data.objects.new(part, me))
    return scene


def render(scene, path, location, target, lens=50, size=(900, 900), ortho=None):
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.display.shading.light = 'STUDIO'
    scene.display.shading.color_type = 'MATERIAL'
    scene.display.shading.show_cavity = False
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.image_settings.file_format = 'PNG'
    scene.render.film_transparent = False
    world = bpy.data.worlds.new('preview ' + scene.name)
    world.color = (.72, .75, .78)
    scene.world = world
    cam = bpy.data.cameras.new('cam')
    cam.lens = lens
    if ortho:
        cam.type = 'ORTHO'
        cam.ortho_scale = ortho
    co = bpy.data.objects.new('cam', cam)
    scene.collection.objects.link(co)
    co.location = Vector(location)
    co.rotation_euler = (Vector(target) - Vector(location)).to_track_quat('-Z', 'Y').to_euler()
    scene.camera = co
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True, scene=scene.name)


def g2b(x, y, z):   # game blocks -> Blender coordinates
    return (x, -z, y)


scenes = {}
for state, open_ in (('open', True), ('closed', False)):
    build(open_)
    snapshot(f'lube_oil_valve_{state}.json')
    scenes[state] = to_scene(f'BWR | lube oil valve ({state})')

# overview: from the south-east, above, as the game shows a block; the pipe runs left-right so flow is along the view's X
for state, scene in scenes.items():
    render(scene, OUT / f'preview_{state}.png', g2b(.5 + 2.2, .5 + 1.9, .5 + 2.6), g2b(.5, .5, .5), lens=50, ortho=1.45)
    # a top-down-ish close-up of the switch box with the pipe running up the picture
for state, scene in scenes.items():
    render(scene, OUT / f'_close_{state}.png', g2b(.5 + 1.1, .98 + .85, .5 + 1.5), g2b(.5, .92, .5), lens=50, size=(700, 700), ortho=.62)

# the two close-ups side by side (open left, shut right) as preview_switch_box.png
import numpy as np
imgs = [bpy.data.images.load(str(OUT / f'_close_{s}.png')) for s in ('open', 'closed')]
w, h = imgs[0].size
both = bpy.data.images.new('switch box', 2 * w, h, alpha=False)
buf = np.zeros((h, 2 * w, 4), dtype=np.float32)
for i, im in enumerate(imgs):
    a = np.empty(w * h * 4, dtype=np.float32)
    im.pixels.foreach_get(a)
    buf[:, i * w:(i + 1) * w, :] = a.reshape(h, w, 4)
both.pixels.foreach_set(buf.ravel())
both.filepath_raw = str(OUT / 'preview_switch_box.png')
both.file_format = 'PNG'
both.save()
for s in ('open', 'closed'):
    (OUT / f'_close_{s}.png').unlink()
for obj in list(bpy.data.scenes['Scene'].objects):
    bpy.data.objects.remove(obj)
bpy.data.scenes.remove(bpy.data.scenes['Scene'])
bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'lube_oil_valve.blend'))
print('Saved the editable Blender scenes and exported their meshes.')
