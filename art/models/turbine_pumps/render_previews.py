"""Studio previews of exported pump mesh snapshots (the JSON the exporters bake), headless:

    blender --background --factory-startup --python art/models/turbine_pumps/render_previews.py -- OUT_DIR LABEL a.json [b.json ...]

Renders each JSON from three fixed angles (north-west high, south-east, north low) with flat Minecraft-like
material colours and the real textures, so before/after pictures of the same machine are comparable.
"""
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
TEX = ROOT / 'art/models/modern/textures'
args = sys.argv[sys.argv.index('--') + 1:]
out_dir, label, files = Path(args[0]).resolve(), args[1], [Path(a).resolve() for a in args[2:]]
out_dir.mkdir(parents=True, exist_ok=True)


def lin(c):
    return c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4


def build(source):
    data = json.loads(source.read_text(encoding='utf-8'))
    scene = bpy.data.scenes.new('Preview ' + source.stem)
    mats = {}
    verts, faces, uvs, mat_index = [], [], [], []
    keys = list(data['materials'])
    for key, info in data['materials'].items():
        m = bpy.data.materials.new(key)
        m.use_nodes = True
        bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
        bsdf.inputs['Roughness'].default_value = .6
        tex = info['texture']
        if tex.startswith('bwr:block/modern/'):
            img = bpy.data.images.load(str(TEX / (tex.split('/')[-1] + '.png')), check_existing=True)
            node = m.node_tree.nodes.new('ShaderNodeTexImage')
            node.image = img
            node.interpolation = 'Closest'
            m.node_tree.links.new(node.outputs['Color'], bsdf.inputs['Base Color'])
        else:
            bsdf.inputs['Base Color'].default_value = (*[lin(c) for c in info['color']], 1)
        mats[key] = m
    for f in data['faces']:
        base = len(verts)
        for p in f['vertices']:
            verts.append((p[0], -p[2], p[1]))
            uvs.append((p[3], p[4]))
        faces.append((base, base + 1, base + 2))
        mat_index.append(keys.index(f['material']))
    mesh = bpy.data.meshes.new(source.stem)
    mesh.from_pydata(verts, [], faces)
    layer = mesh.uv_layers.new(name='UV')
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            layer.data[li].uv = uvs[mesh.loops[li].vertex_index]
    for key in keys:
        mesh.materials.append(mats[key])
    mesh.polygons.foreach_set('material_index', mat_index)
    mesh.update()
    obj = bpy.data.objects.new(source.stem, mesh)
    scene.collection.objects.link(obj)
    w, h, d = data['size']
    floor = bpy.data.meshes.new('floor')
    floor.from_pydata([(-6, 6, 0), (w + 6, 6, 0), (w + 6, -d - 6, 0), (-6, -d - 6, 0)], [], [(0, 1, 2, 3)])
    fm = bpy.data.materials.new('floor')
    fm.use_nodes = True
    next(n for n in fm.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled').inputs['Base Color'].default_value = (.32, .33, .33, 1)
    floor.materials.append(fm)
    fo = bpy.data.objects.new('floor', floor)
    scene.collection.objects.link(fo)
    sun = bpy.data.lights.new('sun', 'SUN')
    sun.energy = 3.2
    so = bpy.data.objects.new('sun', sun)
    so.rotation_euler = (math.radians(40), math.radians(10), math.radians(35))
    scene.collection.objects.link(so)
    world = bpy.data.worlds.new('w')
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.55, .62, .70, 1)
    bg.inputs['Strength'].default_value = .9
    scene.world = world
    cam = bpy.data.cameras.new('cam')
    cam.lens = 50
    co = bpy.data.objects.new('cam', cam)
    scene.collection.objects.link(co)
    scene.camera = co
    for engine in ('BLENDER_EEVEE', 'BLENDER_EEVEE_NEXT', 'CYCLES'):
        try:
            scene.render.engine = engine
            break
        except TypeError:
            continue
    if scene.render.engine == 'CYCLES':
        scene.cycles.samples = 32
    scene.render.resolution_x, scene.render.resolution_y = 1600, 1000
    scene.view_settings.view_transform = 'Standard'
    centre = Vector((w / 2, -d / 2, h / 2.4))
    span = max(w, h, d)
    views = {'nw': Vector((-.75, -1.0, .62)), 'se': Vector((.85, 1.0, .5)), 'n': Vector((.25, -1.0, .2))}
    for name, direction in views.items():
        # Minecraft north is Blender +Y here (z -> -y), so 'nw' looks from the north-west.
        dir_b = Vector((direction.x, -direction.y, direction.z)).normalized()
        co.location = centre + dir_b * span * 2.1
        co.rotation_euler = (centre - co.location).to_track_quat('-Z', 'Y').to_euler()
        scene.render.filepath = str(out_dir / f'{source.stem}-{label}-{name}.png')
        bpy.ops.render.render(write_still=True, scene=scene.name)
        print('PREVIEW', scene.render.filepath)


for f in files:
    build(f)
