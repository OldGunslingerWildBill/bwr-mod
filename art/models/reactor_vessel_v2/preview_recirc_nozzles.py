"""Headless Blender review of the recirculation nozzles on a 17 x 17 vessel wall, before and after the remodel.

blender.exe --background --factory-startup --python art/models/reactor_vessel_v2/preview_recirc_nozzles.py -- --out DIR

Places the exported unit parts the way VesselModel does (a Python copy of buildPorts' generic water kit, "before", and of
recircNozzle, "after") for an outlet at the wall's centre column and inlets off centre, with a section of shell behind them.
"""
import bpy, json, math, sys
from pathlib import Path
from mathutils import Vector, Matrix, Quaternion

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
OUT = Path(argv[argv.index('--out') + 1]).resolve(); OUT.mkdir(parents=True, exist_ok=True)
PARTS = HERE / 'parts'; RVU = ROOT / 'art/models/reactor_vessel'

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
meshes, mats = {}, {}
SWAP = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))


def mesh(path):
    if path in meshes: return meshes[path]
    data = json.loads(Path(path).read_text())
    verts, faces, mi, index = [], [], [], {}
    keys = list(data['materials'])
    for f in data['faces']:
        ids = []
        for v in f['vertices']:
            k = tuple(round(c, 6) for c in v[:3])
            if k not in index: index[k] = len(verts); verts.append(k)
            ids.append(index[k])
        if len(set(ids)) < 3: continue
        faces.append(ids); mi.append(keys.index(f['material']))
    me = bpy.data.meshes.new(Path(path).stem)
    me.from_pydata([(x, -z, y) for x, y, z in verts], [], faces); me.update()
    for poly, m in zip(me.polygons, mi): poly.material_index = m
    for k in keys:
        if k not in mats:
            mm = bpy.data.materials.new(k); mm.diffuse_color = (*data['materials'][k]['color'], 1); mats[k] = mm
        me.materials.append(mats[k])
    meshes[path] = me
    return me


def put(path, m_game):
    o = bpy.data.objects.new(Path(path).stem, mesh(path)); scene.collection.objects.link(o)
    o.matrix_world = SWAP @ m_game @ SWAP.inverted()


def T(x, y, z): return Matrix.Translation((x, y, z))
def S(x, y, z): return Matrix.Diagonal((x, y, z, 1))
def rot_to(v): return Vector((0, 0, 1)).rotation_difference(Vector(v).normalized()).to_matrix().to_4x4()


W = 17.0; RX = RZ = .46 * W
WALL = .012 * W


def along(path, a, b, r):
    a, b = Vector(a), Vector(b); d = b - a
    put(path, T(*a) @ rot_to(d) @ S(r, r, d.length))


def before(port_x, y, water=True):
    """buildPorts' generic kit for a north-wall port at x (centre-relative), face at z = -8.5."""
    x, z = port_x, -W / 2 + .5
    r = math.hypot(x / RX, z / RZ)
    sx, sz = x * .98 / r, z * .98 / r
    tip = .5; ex, ez = x, z - tip
    neck = .60
    v = Vector((ex - sx, 0, ez + neck - sz)); L = v.length
    base = T(sx, y, sz) @ rot_to(v)
    put(RVU / 'spool.json', base @ S(1, 1, L))
    rf = min(.45, max(.30, 6 * 1.55 * (2 * .448 * W / 251)))
    lf = min(L * .75, max(.25, rf * 1.1))
    put(PARTS / 'rpv_forging.json', base @ T(0, 0, -.05) @ S(rf, rf, lf + .05))
    if L > lf + .1: put(PARTS / 'rpv_safe_end.json', base @ T(0, 0, lf) @ S(.33, .33, min(.5, L - lf - .02)))
    put(RVU / 'water_nozzle.json', T(ex, y, ez) @ rot_to((0, 0, -1)))


def after(port_x, y, outlet):
    x, z = port_x, -W / 2 + .5
    r = math.hypot(x / RX, z / RZ)
    sx, sz = x / r, z / r
    rf = .56 if outlet else .42; rs = .8 * rf; rp = .78 * rs
    n = Vector((sx / RX ** 2, 0, sz / RZ ** 2)).normalized(); f = Vector((0, 0, -1))
    tx, tz = x, z - .5; hub = .42
    fx, fz = tx - f.x * hub, tz - f.z * hub
    dist = max(.2, (fx - sx) * n.x + (fz - sz) * n.z)
    lb, lbu, ls, lov = max(.24, rf * .95), .06, max(.20, rf * .62), (.09 if outlet else .05)
    k = min(1, max(.35, dist) * .85 / (lb + lbu + ls + lov)); lb *= k; lbu *= k; ls *= k; lov *= k
    base = T(sx, y, sz) @ rot_to(n)
    z0 = -.06
    put(PARTS / ('rpv_recirc_outlet_boss.json' if outlet else 'rpv_recirc_inlet_boss.json'), base @ T(0, 0, z0) @ S(rf, rf, lb))
    zz = z0 + lb
    put(PARTS / 'rpv_recirc_butter.json', base @ T(0, 0, zz - .005) @ S(rs, rs, lbu + .01)); zz += lbu
    put(PARTS / 'rpv_recirc_safe_end.json', base @ T(0, 0, zz) @ S(rs, rs, ls)); zz += ls
    ro = rp * (1.10 if outlet else 1.0)
    put(PARTS / 'rpv_recirc_overlay.json', base @ T(0, 0, zz - .005) @ S(ro, ro, lov + .01)); zz += lov
    if not outlet: put(PARTS / 'rpv_thermal_sleeve.json', base @ T(0, 0, -(WALL + .02) - .45) @ S(rp * .92, rp * .92, .45))
    ex, ez = sx + n.x * zz, sz + n.z * zz
    L = math.hypot(fx - ex, fz - ez)
    if L > .02:
        c0 = (ex + n.x * L * .5, ez + n.z * L * .5); c1 = (fx - f.x * L * .5, fz - f.z * L * .5)
        segs = 1 if n.dot(f) > .999 else 8
        lx, lz = ex, ez
        for i in range(1, segs + 1):
            t = i / segs; u = 1 - t
            qx = u ** 3 * ex + 3 * u * u * t * c0[0] + 3 * u * t * t * c1[0] + t ** 3 * fx
            qz = u ** 3 * ez + 3 * u * u * t * c0[1] + 3 * u * t * t * c1[1] + t ** 3 * fz
            dx, dz = qx - lx, qz - lz; l = math.hypot(dx, dz); over = .01 if segs == 1 else rp * .18
            if l > 1e-4: along(PARTS / 'rpv_recirc_pipe.json', (lx - dx / l * over, y, lz - dz / l * over), (qx + dx / l * over, y, qz + dz / l * over), rp)
            lx, lz = qx, qz
    sc = 1.15 if outlet else .85
    put(PARTS / ('rpv_recirc_flange_outlet.json' if outlet else 'rpv_recirc_flange_inlet.json'), T(tx, y, tz) @ rot_to(f) @ S(sc, sc, 1))


def shell():
    put(PARTS / 'rpv_barrel.json', T(0, 0, 0) @ S(W, 6, W))


def render(name):
    scene.render.engine = 'BLENDER_WORKBENCH'
    sh = scene.display.shading; sh.light = 'STUDIO'; sh.color_type = 'MATERIAL'; sh.show_shadows = True
    scene.render.resolution_x, scene.render.resolution_y = 1920, 1080
    world = bpy.data.worlds.new('w'); scene.world = world; world.color = (.62, .74, .92)
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam')); scene.collection.objects.link(cam); scene.camera = cam
    cam.data.lens = 40
    for view, eye, tgt in (('nw', (-4.5, 4.6, -14.5), (0, 2.2, -8.2)), ('low', (5.5, 1.2, -12.5), (0, 2.2, -8.2))):
        cam.location = SWAP.to_3x3() @ Vector(eye)
        cam.rotation_euler = (SWAP.to_3x3() @ (Vector(tgt) - Vector(eye))).to_track_quat('-Z', 'Y').to_euler()
        scene.render.filepath = str(OUT / f'{name}-{view}.png'); bpy.ops.render.render(write_still=True)
        print('PREVIEW', scene.render.filepath)


def clear():
    for o in list(scene.collection.objects): bpy.data.objects.remove(o, do_unlink=True)


# outlet at the centre column (low), inlets beside it, one high outlet: the arrangement of the in-game shots
shell()
for xo in (-4, -2, 2, 4): before(xo, 1.5)
before(0, 2.5)
render('before')
clear(); shell()
for xo in (-4, -2, 2, 4): after(xo, 1.5, False)
after(0, 2.5, True)
render('after')
