"""Headless Blender review of a pump exactly as the game baked it: every cell_N.obj, offset by its cell.

blender.exe --background --factory-startup --python art/models/recirc/preview_cells.py -- \
    --pump recirculation_pump --manifest recirculation_pump --out shot.png --view front [--res 1600 1200]

Loads the runtime OBJ cells (not the Blender source), so before/after renders compare what Minecraft draws.
Textures named bwr:... are read from the mod resources; vanilla textures render as their Kd colour.
Flat shading with one sun, matching the game's per-face quad shading.
"""
import bpy, json, math, sys
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / 'mod/src/main/resources'
argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
def arg(name, default=None, n=1):
    if name not in argv: return default
    i = argv.index(name)
    return argv[i + 1] if n == 1 else argv[i + 1:i + 1 + n]
pump = arg('--pump'); manifest = arg('--manifest', pump); out = str(Path(arg('--out')).resolve()); views = arg('--view', 'front').split(',')
res = [int(v) for v in arg('--res', ['1600', '1200'], 2)]
folder = RES / 'assets/bwr/models/block/pumps' / pump
info = json.loads((RES / f'data/bwr/pump_models/{manifest}.json').read_text())
W, H, D = info['width'], info['height'], info['depth']

scene = bpy.context.scene
for o in list(bpy.data.objects): bpy.data.objects.remove(o, do_unlink=True)
images = {}
def image(ref):
    ns, path = ref.split(':', 1)
    if ns != 'bwr': return None
    if ref not in images:
        images[ref] = bpy.data.images.load(str(RES / 'assets/bwr/textures' / (path + '.png')))
    return images[ref]
def materials(lib):
    mats, cur = {}, None
    for line in (folder / lib).read_text().splitlines():
        f = line.split()
        if not f: continue
        if f[0] == 'newmtl': cur = mats.setdefault(f[1], {'kd': (1, 1, 1), 'map': None})
        elif f[0] == 'Kd': cur['kd'] = tuple(map(float, f[1:4]))
        elif f[0] == 'map_Kd': cur['map'] = f[1]
    out = {}
    for name, m in mats.items():
        mat = bpy.data.materials.new(pump + ' ' + name); mat.use_nodes = True
        nt = mat.node_tree; bsdf = nt.nodes['Principled BSDF']
        bsdf.inputs['Roughness'].default_value = .8
        img = image(m['map']) if m['map'] else None
        if img:
            tex = nt.nodes.new('ShaderNodeTexImage'); tex.image = img; tex.interpolation = 'Closest'
            mix = nt.nodes.new('ShaderNodeMix'); mix.data_type = 'RGBA'; mix.blend_type = 'MULTIPLY'
            mix.inputs['Factor'].default_value = 1; mix.inputs[7].default_value = (*m['kd'], 1)
            nt.links.new(tex.outputs['Color'], mix.inputs[6]); nt.links.new(mix.outputs[2], bsdf.inputs['Base Color'])
        else:
            bsdf.inputs['Base Color'].default_value = (*m['kd'], 1)
        mat.diffuse_color = (*m['kd'], 1)
        out[name] = mat
    return out
libs = {}
for cell in info['cells']:
    path = folder / f"cell_{cell['index']}.obj"
    if not path.exists(): continue
    ox, oy, oz = cell['offset']
    vs, vts, faces, mats_used = [], [], [], []
    cur = None; lib = None
    for line in path.read_text().splitlines():
        f = line.split()
        if not f: continue
        if f[0] == 'mtllib': lib = f[1]
        elif f[0] == 'v': x, y, z = map(float, f[1:4]); vs.append((x + ox, -(z + oz), y + oy))
        elif f[0] == 'vt': vts.append(tuple(map(float, f[1:3])))
        elif f[0] == 'usemtl': cur = f[1]
        elif f[0] == 'f': faces.append(([int(p.split('/')[0]) - 1 for p in f[1:]], [int(p.split('/')[1]) - 1 for p in f[1:]], cur))
    if not faces: continue
    if lib not in libs: libs[lib] = materials(lib)
    names = sorted(set(m for _, _, m in faces))
    me = bpy.data.meshes.new(f'cell_{cell["index"]}')
    me.from_pydata(vs, [], [fv for fv, _, _ in faces]); me.update()
    uv = me.uv_layers.new()
    li = 0
    for poly, (_, ft, m) in zip(me.polygons, faces):
        poly.material_index = names.index(m)
        for k, loop in enumerate(poly.loop_indices):
            u, v = vts[ft[k]]
            uv.data[loop].uv = (u, v)  # OBJ and Blender share v-up; flip_v only converts to Minecraft's v-down
    for n in names: me.materials.append(libs[lib][n])
    o = bpy.data.objects.new(me.name, me); scene.collection.objects.link(o)

# floor grid for scale
bpy.ops.mesh.primitive_plane_add(size=1, location=(W / 2, -D / 2, -.001)); floor = bpy.context.object
floor.scale = (W + 6, D + 6, 1)
fm = bpy.data.materials.new('floor'); fm.diffuse_color = (.42, .42, .40, 1); fm.use_nodes = True
fm.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value = (.42, .42, .40, 1); floor.data.materials.append(fm)

scene.render.engine = 'BLENDER_WORKBENCH'
sh = scene.display.shading
sh.light = 'STUDIO'; sh.color_type = 'TEXTURE'; sh.show_shadows = True; sh.show_cavity = False
sh.show_object_outline = False
scene.display.shading.studio_light = 'Default'
scene.render.resolution_x, scene.render.resolution_y = res
scene.render.film_transparent = False
scene.world = bpy.data.worlds.new('w'); scene.world.color = (.55, .62, .70)
sh.background_type = 'WORLD'
cam = bpy.data.cameras.new('cam'); cam.lens = 50
co = bpy.data.objects.new('cam', cam); scene.collection.objects.link(co); scene.camera = co
target = Vector((W / 2, -D / 2, H * .45))
dist = max(W, H, D) * 2.3
presets = {'front': (-35, 18), 'back': (145, 18), 'side': (55, 12), 'low': (-20, -2), 'top': (-35, 55), 'east': (90, 10)}
for view in views:
    yaw, pitch = presets[view]
    a, p = math.radians(yaw), math.radians(pitch)
    # front = looking at the north face (game -z, Blender +y) from the north-west
    co.location = target + Vector((math.sin(a) * math.cos(p), math.cos(a) * math.cos(p) * 1, math.sin(p))) * dist
    co.rotation_euler = (target - co.location).to_track_quat('-Z', 'Y').to_euler()
    scene.render.filepath = out.replace('.png', f'-{view}.png') if len(views) > 1 else out
    bpy.ops.render.render(write_still=True)
    print('wrote', scene.render.filepath)
