"""Headless Blender review renders of whole condensate tanks, before and after the remodel.

blender.exe --background --factory-startup --python art/models/condensate_tank/preview_cst.py -- --mode after --out DIR
blender.exe --background --factory-startup --python art/models/condensate_tank/preview_cst.py -- --mode before --src OLD --out DIR

after:  the parts in parts/*.json placed by build_cst.layout() (the same placement as CondensateTankRenderer);
before: the alpha five-part sources (base, wall, roof, port, ladder JSON from git, folder OLD) placed as the old renderer did.
Three sizes (3 x 3, 7 x 8, 15 x 24) side by side plus close views. Workbench, flat lighting, textures from the atlas.
"""
import bpy, json, math, sys
from pathlib import Path
from mathutils import Vector, Matrix

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RES = ROOT / 'mod/src/main/resources'
argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []


def arg(name, default=None):
    return argv[argv.index(name) + 1] if name in argv else default


MODE = arg('--mode', 'after'); OUTDIR = Path(arg('--out')).resolve(); SRC = arg('--src')
OUTDIR.mkdir(parents=True, exist_ok=True)
src = (HERE / 'build_cst.py').read_text(encoding='utf-8')
# layout() and its constants only (no Blender build)
ns = {}
exec(compile('import math\nTAU = math.tau\n' + src[src.index('EAVE_DROP ='):src.index('# =====================================================================================================================\ndef emit(')], 'layout', 'exec'), ns)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
images, mats, meshes = {}, {}, {}


def material(key, m):
    if key in mats: return mats[key]
    mat = bpy.data.materials.new(key); mat.use_nodes = True
    nt = mat.node_tree; bsdf = nt.nodes['Principled BSDF']
    tex = m['texture']
    if tex.startswith('bwr:'):
        if tex not in images: images[tex] = bpy.data.images.load(str(RES / 'assets/bwr/textures' / (tex[4:] + '.png')))
        t = nt.nodes.new('ShaderNodeTexImage'); t.image = images[tex]; t.interpolation = 'Closest'
        nt.links.new(t.outputs['Color'], bsdf.inputs['Base Color'])
    else:
        bsdf.inputs['Base Color'].default_value = (*m['color'], 1)
    mats[key] = mat
    return mat


def mesh(path):
    if path in meshes: return meshes[path]
    data = json.loads(Path(path).read_text())
    verts, faces, uvs, mi, index = [], [], [], [], {}
    keys = list(data['materials'])
    for f in data['faces']:
        ids = []
        for v in f['vertices']:
            k = tuple(round(c, 6) for c in v[:3])
            if k not in index: index[k] = len(verts); verts.append(k)
            ids.append(index[k])
        if len(set(ids)) < 3: continue
        faces.append(ids); uvs.append([v[3:5] for v in f['vertices']]); mi.append(keys.index(f['material']))
    me = bpy.data.meshes.new(Path(path).stem)
    me.from_pydata([(x, -z, y) for x, y, z in verts], [], faces); me.update()
    layer = me.uv_layers.new(name='uv')
    for poly, uv, m in zip(me.polygons, uvs, mi):
        poly.material_index = m
        for k, li in enumerate(poly.loop_indices): layer.data[li].uv = uv[k]
    for k in keys: me.materials.append(material(k, data['materials'][k]))
    meshes[path] = me
    return me


def G(x, y, z):  # game -> Blender
    return Vector((x, -z, y))


def place(path, pos, yaw, scale, origin):
    o = bpy.data.objects.new(Path(path).stem, mesh(path)); scene.collection.objects.link(o)
    # game: translate(pos) * rotY(yaw) * scale; Blender Z is game Y, Blender Y is game -Z
    o.matrix_world = Matrix.Translation(G(*(Vector(pos) + Vector(origin)))) @ Matrix.Rotation(math.radians(yaw), 4, 'Z') @ Matrix.Diagonal((scale[0], scale[2], scale[1], 1))


def rail(path, a, b, r, origin):
    a, b = Vector(a), Vector(b); d = b - a
    o = bpy.data.objects.new('rail', mesh(path)); scene.collection.objects.link(o)
    q = Vector((0, 0, 1)).rotation_difference(Vector((d.x, d.y, d.z)))  # in game space
    m_game = Matrix.Translation(a + Vector(origin)) @ q.to_matrix().to_4x4() @ Matrix.Diagonal((r, r, d.length, 1))
    swap = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))
    o.matrix_world = swap @ m_game @ swap.inverted()


def tank(d, h, origin):
    if MODE == 'after':
        for e in ns['layout'](d, h):
            if len(e) == 4 and e[0] in ('rail', 'pipe'): rail(HERE / 'parts' / (e[0] + '.json'), e[1], e[2], e[3], origin)
            else: place(HERE / 'parts' / (e[0] + '.json'), e[1], e[2], e[3], origin)
    else:
        S = Path(SRC)
        place(S / 'base.json', (0, 0, 0), 0, (d, 1, d), origin)
        place(S / 'wall.json', (0, .18, 0), 0, (d, h - 1.48, d), origin)
        place(S / 'roof.json', (0, h - 1.3, 0), 0, (d, 1, d), origin)
        for i in range(4):
            t = math.radians(90 * i)
            place(S / 'port.json', (-math.sin(t) * d / 2, 1.5, -math.cos(t) * d / 2), 90 * i, (1, 1, 1), origin)
        z = -math.sqrt((.47 * d) ** 2 - .49) - .05
        for y in range(h - 1): place(S / 'ladder.json', (.7, y + .2, z), 0, (1, 1, 1), origin)


# ground plane
bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, -.001))
g = bpy.context.object; gm = bpy.data.materials.new('ground'); gm.diffuse_color = (.52, .52, .50, 1); g.data.materials.append(gm)
tank(3, 3, (-10, 0, 0)); tank(7, 8, (0, 0, 0)); tank(15, 24, (16, 0, 0))
scene.render.engine = 'BLENDER_WORKBENCH'
sh = scene.display.shading
sh.light = 'STUDIO'; sh.color_type = 'TEXTURE'; sh.show_shadows = True; sh.show_cavity = False
scene.render.resolution_x, scene.render.resolution_y = 1920, 1080
scene.view_settings.view_transform = 'Standard'
world = bpy.data.worlds.new('w'); scene.world = world; world.color = (.62, .74, .92)
cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); scene.collection.objects.link(cam); scene.camera = cam
cam.data.lens = 35


def shot(name, eye, target):
    cam.location = G(*eye)
    cam.rotation_euler = (G(*target) - G(*eye)).to_track_quat('-Z', 'Y').to_euler()
    scene.render.filepath = str(OUTDIR / f'{MODE}-{name}.png')
    bpy.ops.render.render(write_still=True)
    print('PREVIEW', scene.render.filepath)


shot('sizes', (2, 16, -34), (3, 6, 0))
shot('mid-7x8', (-8, 6, -9), (0, 3.2, 0))
shot('large-15x24-roof', (30, 34, -14), (16, 21, 0))
shot('large-15x24-base', (8, 2.6, -14), (16, 1.6, -5))
