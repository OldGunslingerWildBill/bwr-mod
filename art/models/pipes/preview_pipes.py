"""Render a preview of each pipe set in headless Blender (PIPES.md): a short line laid against two machine stand-ins, with
a straight run, a rising bend, a tee and the port flanges, coloured with the set's default paint or band colour.

    "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Blender\\blender.exe" --background --factory-startup ^
        --python art/models/pipes/preview_pipes.py -- [set ...]

Writes art/models/pipes/<set>_preview.png (all sets when none is named). Builds its own scene from build_pipes.py's
parameter sets; it does not open or change pipes.blend or any other file.
"""
import math
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import bpy  # noqa: E402
from mathutils import Vector  # noqa: E402
import build_pipes as bp  # noqa: E402

# Cells of the little layout (block coordinates): two machines and the pipe between them.
MACHINES = [(0, 0, 0), (3, 0, 4)]
PIPES = [(1, 0, 0), (2, 0, 0), (3, 0, 0), (4, 0, 0), (5, 0, 0), (5, 1, 0), (5, 2, 0), (3, 0, 1), (3, 0, 2), (3, 0, 3)]


def layout():
    pipes, machines = set(PIPES), set(MACHINES)
    for cell in PIPES:
        mask, ports = 0, []
        for i, (name, d) in enumerate(bp.DIRS):
            n = tuple(cell[k] + d[k] for k in range(3))
            if n in pipes or n in machines:
                mask |= 1 << i
                if n in machines:
                    ports.append(d)
        yield cell, mask, ports


def scene_for(set_name):
    spec = bp.PIPE_SETS[set_name]
    builder = bp.Builder(set_name, spec)
    scene = bpy.data.scenes.new('Pipe preview ' + set_name)
    for cell, mask, ports in layout():
        part = builder.connection(mask)
        for d in ports:
            part.polys += builder.port(d).polys
        mesh = bp.build_mesh(set_name, '%s preview %s' % (set_name, cell), part)
        obj = bpy.data.objects.new(mesh.name, mesh)
        obj.location = bp.to_blender(cell)
        scene.collection.objects.link(obj)
    grey = bpy.data.materials.new('machine stand-in')
    grey.diffuse_color = (.20, .26, .22, 1)
    bsdf = next(n for n in grey.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value = (.08, .14, .10, 1)
    bsdf.inputs['Roughness'].default_value = .7
    for cell in MACHINES:
        me = bpy.data.meshes.new('machine')
        x, y, z = cell
        v = [(x + a, y + b, z + c) for a in (0, 1) for b in (0, 1) for c in (0, 1)]
        f = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
        me.from_pydata([bp.to_blender(p) for p in v], [], f)
        me.materials.append(grey)
        o = bpy.data.objects.new('machine', me)
        scene.collection.objects.link(o)
    floor = bpy.data.meshes.new('floor')
    floor.from_pydata([(-20, -20, -.01), (20, -20, -.01), (20, 20, -.01), (-20, 20, -.01)], [], [(0, 1, 2, 3)])
    fm = bpy.data.materials.new('floor')
    next(n for n in fm.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled').inputs['Base Color'].default_value = (.32, .33, .34, 1)
    floor.materials.append(fm)
    scene.collection.objects.link(bpy.data.objects.new('floor', floor))
    # Camera from the south-east, above, looking at the middle of the line.
    target = Vector(bp.to_blender((3.2, .8, 1.6)))
    cam = bpy.data.objects.new('camera', bpy.data.cameras.new('camera'))
    cam.location = target + Vector((4.6, -6.2, 4.4))
    cam.rotation_euler = (target - cam.location).to_track_quat('-Z', 'Y').to_euler()
    cam.data.lens = 40
    scene.collection.objects.link(cam)
    scene.camera = cam
    sun = bpy.data.objects.new('sun', bpy.data.lights.new('sun', 'SUN'))
    sun.data.energy = 4.5
    sun.rotation_euler = (math.radians(40), math.radians(10), math.radians(35))
    scene.collection.objects.link(sun)
    scene.world = bpy.data.worlds.new('sky')
    bg = next(n for n in scene.world.node_tree.nodes if n.bl_idname == 'ShaderNodeBackground')
    bg.inputs[0].default_value = (.55, .68, .9, 1)
    bg.inputs[1].default_value = .45
    return scene


def render(scene, path):
    for engine in ('BLENDER_EEVEE', 'BLENDER_EEVEE_NEXT', 'CYCLES'):
        try:
            scene.render.engine = engine
            break
        except TypeError:
            continue
    if scene.render.engine == 'CYCLES':
        scene.cycles.samples = 48
    scene.view_settings.view_transform = 'Standard'
    scene.render.resolution_x, scene.render.resolution_y = 1280, 720
    scene.render.image_settings.file_format = 'PNG'
    scene.render.filepath = path
    with bpy.context.temp_override(scene=scene):
        bpy.ops.render.render(write_still=True, scene=scene.name)
    print('PIPE PREVIEW', scene.render.engine, path)


def main():
    args = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    for name in args or list(bp.PIPE_SETS):
        render(scene_for(name), os.path.join(HERE, name + '_preview.png'))


if __name__ == '__main__':
    main()
