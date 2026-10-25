"""Render review previews of the RWCU models from rwcu.blend. Run headless after build_models.py:

    blender.exe --background --python art/models/rwcu/render_previews.py

Writes preview_<id>.png (north-east view), preview_<id>_back.png (south-west view) and skid-preview.png.
Nothing is saved back to rwcu.blend.
"""
import math
from pathlib import Path

import bpy
from mathutils import Matrix, Vector

OUT = Path(__file__).resolve().parent
bpy.ops.wm.open_mainfile(filepath=str(OUT / 'rwcu.blend'))

IDS = {
    'BWR | RWCU pump': 'rwcu_pump',
    'BWR | RWCU regenerative heat exchanger': 'rwcu_regenerative_heat_exchanger',
    'BWR | RWCU non-regenerative heat exchanger': 'rwcu_nonregenerative_heat_exchanger',
    'BWR | RWCU filter-demineralizer': 'rwcu_filter_demineralizer',
    'BWR | RPV bottom drain port': 'rpv_bottom_drain_port',
}


def flat(name, rgb):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    m.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = (*rgb, 1)
    m.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value = .9
    return m


FLOOR = flat('floor', (.20, .22, .22))
LINE = flat('block outline', (.9, .1, .1))


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


def add_box(scene, lo, hi, mat, name):
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    vs = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    fs = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5)]
    me = bpy.data.meshes.new(name)
    me.from_pydata([xyz(p) for p in vs], [], fs)
    me.update()
    o = bpy.data.objects.new(name, me)
    me.materials.append(mat)
    scene.collection.objects.link(o)
    return o


def block_outline(scene, ox=0, oz=0, t=.004):
    for a in (0, 1):
        for b in (0, 1):
            add_box(scene, (ox + a - t * a, b - t * b, oz), (ox + a + t * (1 - a), b + t * (1 - b), oz + 1), LINE, 'edge')
            add_box(scene, (ox, a - t * a, oz + b - t * b), (ox + 1, a + t * (1 - a), oz + b + t * (1 - b)), LINE, 'edge')
            add_box(scene, (ox + a - t * a, 0, oz + b - t * b), (ox + a + t * (1 - a), 1, oz + b + t * (1 - b)), LINE, 'edge')


def studio(scene, centre, direction, size, outline=True, floor=None):
    scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x = scene.render.resolution_y = 1024
    scene.render.film_transparent = False
    world = bpy.data.worlds.new('studio')
    world.use_nodes = True
    bg = world.node_tree.nodes['Background']
    bg.inputs['Color'].default_value = (.80, .83, .86, 1)
    bg.inputs['Strength'].default_value = .9
    scene.world = world
    scene.view_settings.view_transform = 'Standard'
    if floor:
        add_box(scene, floor[0], floor[1], FLOOR, 'floor')
    c = xyz(centre)
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
    cam.data.type = 'ORTHO'
    cam.data.ortho_scale = size
    cam.data.clip_end = 100
    d = xyz(direction).normalized()
    cam.location = c + d * 30
    cam.rotation_euler = (-d).to_track_quat('-Z', 'Y').to_euler()
    scene.collection.objects.link(cam)
    scene.camera = cam
    for name, off, energy, kind in (('key', (6, 8, 12), 4.0, 'SUN'), ('fill', (-9, 3, 5), 1.6, 'SUN')):
        ld = bpy.data.lights.new(name, kind)
        ld.energy = energy
        lo = bpy.data.objects.new(name, ld)
        lo.location = c + Vector(off)
        lo.rotation_euler = (-Vector(off)).to_track_quat('-Z', 'Y').to_euler()
        scene.collection.objects.link(lo)
    if outline:
        block_outline(scene)


def render(scene, path):
    scene.render.filepath = str(path)
    bpy.context.window.scene = scene
    bpy.ops.render.render(write_still=True, scene=scene.name)
    print('rendered', path.name)


# direction vectors are game-space offsets (x east, y up, z south); camera sits north-east / south-west and above
NE = (1.0, 0.9, -1.2)
SW = (-1.0, 0.9, 1.2)
for scene_name, bid in IDS.items():
    scene = bpy.data.scenes[scene_name]
    studio(scene, (.5, .5, .5), NE, 1.75)
    render(scene, OUT / f'preview_{bid}.png')
    cam = scene.camera
    d = xyz(SW).normalized()
    cam.location = xyz((.5, .5, .5)) + d * 30
    cam.rotation_euler = (-d).to_track_quat('-Z', 'Y').to_euler()
    render(scene, OUT / f'preview_{bid}_back.png')

# ---- skid: pump -> regenerative HX -> cooler -> demineralizer along +X, one block gap between each ----
skid = bpy.data.scenes.new('skid')
# facing east: north flange -> east; facing west: north flange -> west, so the chain flows pump (east) -> units (inlet west)
chain = [('BWR | RWCU pump', 'east', 0), ('BWR | RWCU regenerative heat exchanger', 'west', 2),
         ('BWR | RWCU non-regenerative heat exchanger', 'west', 4), ('BWR | RWCU filter-demineralizer', 'west', 6)]
for scene_name, facing, ox in chain:
    src = bpy.data.scenes[scene_name]
    angle = {'east': -90, 'west': 90, 'north': 0}[facing]
    for o in src.collection.objects:
        if o.type != 'MESH' or o.name.startswith(('edge', 'floor')):
            continue
        c = o.copy()
        skid.collection.objects.link(c)
        centre = Vector((.5, -.5, 0))
        c.matrix_world = Matrix.Translation(Vector((ox, 0, 0))) @ Matrix.Translation(centre) @ Matrix.Rotation(math.radians(angle), 4, 'Z') @ Matrix.Translation(-centre)
PIPE = flat('pipe', (.10, .13, .15))
for x0 in (1, 3, 5):
    bpy.ops.mesh.primitive_cylinder_add(vertices=24, radius=.2, depth=1.0, location=(x0 + .5, -.5, .5), rotation=(0, math.pi / 2, 0))
    pipe = bpy.context.object
    pipe.data.materials.append(PIPE)
    for c in list(pipe.users_collection):
        c.objects.unlink(pipe)
    skid.collection.objects.link(pipe)
studio(skid, (3.5, .4, .5), (.35, 1.0, 1.6), 8.4, outline=False, floor=((-1, -.05, -3), (9, 0, 4)))
skid.render.resolution_x = 1600
skid.render.resolution_y = 900
render(skid, OUT / 'skid-preview.png')
