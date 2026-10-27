"""Headless Blender preview of the main turbine train from the exported sources (art/models/power_turbines/*.json).

  blender --background --factory-startup --python art/models/power_turbines/render_previews.py -- OUT_DIR [view ...]

Draws the HP, an LP and the generator on one shaft line (Workbench, flat, back faces culled as in game), plus the LP with
its hood off and the LP blade sections approximated as TurbineHallRenderers draws them, and the hood alone from below.
Review pictures only; nothing here is shipped.
"""
import bpy
import json
import math
import sys
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT/'art/models/power_turbines'
args = sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else []
OUT = Path(args[0]) if args else SRC/'previews'
ONLY = set(args[1:])
OUT.mkdir(parents=True, exist_ok=True)


def lin(c):
    return c/12.92 if c <= .04045 else ((c+.055)/1.055)**2.4


def xyz(p):
    return (p[0], -p[2], p[1])


MATS = {}


def mat(rgb):
    key = tuple(round(c, 4) for c in rgb)
    if key not in MATS:
        m = bpy.data.materials.new('preview %d' % len(MATS))
        m.diffuse_color = (*[lin(c) for c in rgb], 1)
        MATS[key] = m
    return MATS[key]


def add_mesh(scene, name, tris, colors, offset):
    me = bpy.data.meshes.new(name)
    verts, faces, idx = [], [], []
    slots = {}
    for t, c in zip(tris, colors):
        m = mat(c)
        if m.name not in slots:
            slots[m.name] = len(slots)
            me.materials.append(m)
        base = len(verts)
        verts += [xyz((p[0]+offset[0], p[1]+offset[1], p[2]+offset[2])) for p in t]
        faces.append((base, base+1, base+2))
        idx.append(slots[m.name])
    me.from_pydata(verts, [], faces)
    for poly, i in zip(me.polygons, idx):
        poly.material_index = i
    me.update()
    o = bpy.data.objects.new(name, me)
    scene.collection.objects.link(o)
    return o


def module(scene, id, offset, keep=lambda tri: True):
    data = json.loads((SRC/(id+'.json')).read_text())
    tris, colors = [], []
    for f in data['faces']:
        t = [v[:3] for v in f['vertices']]
        if keep(t):
            tris.append(t); colors.append(data['materials'][f['material']]['color'])
    return add_mesh(scene, id, tris, colors, offset)


def section(scene, centre, flipped, damaged=False):
    """TurbineHallRenderers.buildSection, approximately: drum, journals, three stages of discs and blades."""
    tris, colors = [], []
    def cyl(r, z0, z1, c, n=20):
        for i in range(n):
            a0, a1 = math.tau*i/n, math.tau*(i+1)/n
            p = [(r*math.cos(a0), r*math.sin(a0), z0), (r*math.cos(a1), r*math.sin(a1), z0), (r*math.cos(a1), r*math.sin(a1), z1), (r*math.cos(a0), r*math.sin(a0), z1)]
            tris.extend([[p[0], p[1], p[2]], [p[0], p[2], p[3]], [(0, 0, z1), p[3], p[2]], [(0, 0, z0), p[1], p[0]]])
            colors.extend([c]*4)
    def blade(a, r0, r1, w, z, c):
        u = (math.cos(a), math.sin(a), 0); v = (-math.sin(a), math.cos(a), 0)
        k, s = math.cos(math.radians(28)), math.sin(math.radians(28))
        q = [(v[0]*w*k*sx, v[1]*w*k*sx, z+w*s*sx) for sx in (-1, 1)]
        p = [(u[0]*r+q_[0], u[1]*r+q_[1], q_[2]) for r in (r0, r1) for q_ in q]
        tris.extend([[p[0], p[1], p[3]], [p[0], p[3], p[2]], [p[0], p[3], p[1]], [p[0], p[2], p[3]]])
        colors.extend([c]*4)
    drum, disc, bl, tip = ((.45, .30, .20),)*4 if damaged else ((.70, .71, .73), (.77, .78, .80), (.90, .91, .92), (.95, .93, .80))
    cyl(.55, -1.5, 1.5, drum); cyl(.32, -1.95, 1.95, drum, 12)
    for z, d, t, n in ((-0.95, .85, 1.55, 28), (0.05, .85, 1.90, 24), (1.00, .85, 2.30, 20)):
        cyl(d, z-.12, z+.12, disc, 24)
        w = .09+.04*(t-1.5)
        for i in range(n):
            blade(math.tau*i/n, d-.02, d+(t-d)*.82, w, z, bl)
            blade(math.tau*i/n, d+(t-d)*.82, t, w*.9, z, tip)
    sign = -1 if flipped else 1
    tris = [[(p[0]+centre[0], p[1]+centre[1], centre[2]+sign*p[2]) for p in t] for t in tris]
    return add_mesh(scene, 'blade section', tris, colors, (0, 0, 0))


def camera(scene, name, pos, target, lens=30):
    cam = bpy.data.objects.new(name, bpy.data.cameras.new(name))
    cam.data.lens = lens
    cam.data.clip_end = 500
    scene.collection.objects.link(cam)
    cam.location = xyz(pos)
    d = Vector(xyz(target))-cam.location
    cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    return cam


def render(scene, cam, name):
    if ONLY and name not in ONLY:
        return
    scene.camera = cam
    scene.render.filepath = str(OUT/(name+'.png'))
    bpy.ops.render.render(write_still=True, scene=scene.name)
    print('preview', name)


scene = bpy.data.scenes['Scene']
for o in list(scene.objects):
    bpy.data.objects.remove(o)
scene.render.engine = 'BLENDER_WORKBENCH'
scene.render.resolution_x, scene.render.resolution_y = 1600, 900
scene.display.shading.light = 'STUDIO'
scene.display.shading.color_type = 'MATERIAL'
scene.display.shading.show_backface_culling = True
scene.display.shading.show_cavity = True
scene.display.shading.cavity_type = 'WORLD'
scene.display.shading.show_object_outline = False
scene.world = bpy.data.worlds.new('preview world') if scene.world is None else scene.world
scene.world.color = (.55, .65, .8)
scene.view_settings.view_transform = 'Standard'

# The train: HP (z 0..9), LP (z 9..16), generator (z 16..25); shafts on x = 3.5.
floor = add_mesh(scene, 'floor', [[(-6, 0, -6), (13, 0, 31), (13, 0, -6)], [(-6, 0, -6), (-6, 0, 31), (13, 0, 31)]], [(.62, .62, .6)]*2, (0, -.001, 0))
hp = module(scene, 'hp_turbine', (1, 0, 0))
lp = module(scene, 'lp_turbine', (0, 0, 9))
gen = module(scene, 'nuclear_generator', (1, 0, 16))
views = {
    'train-front-left': ((-7, 7.5, -6), (3.5, 2, 9)),
    'train-left': ((-11, 4, 12.5), (3.5, 2.2, 12.5)),
    'train-right-rear': ((15, 8, 22), (3.5, 2, 10)),
    'hp-left': ((-5.5, 4.5, 1.0), (2.5, 2.2, 4.5)),
    'hp-right-high': ((9.5, 9, 9.5), (3.5, 2.5, 4.5)),
    'hp-front-low': ((6, 2.0, -6.5), (3.5, 2.4, 3.0)),
    'lp-left': ((-6.5, 5.5, 8.5), (3.5, 2.5, 12.5)),
    'lp-right-high': ((12, 9, 18.5), (3.5, 2.5, 12.5)),
}
for name, (pos, target) in views.items():
    render(scene, camera(scene, name, pos, target, 26 if name.startswith('train') else 30), name)

# The LP with its hood off and both blade sections in, then the hood alone, from below as it hangs.
bpy.data.objects.remove(lp)
lp_low = module(scene, 'lp_turbine', (0, 0, 9), lambda t: sum(p[1] for p in t)/3 < 3)
blades = [section(scene, (3.5, 2.5, 12.5+1.5), False, False), section(scene, (3.5, 2.5, 12.5-1.5), True, True)]
render(scene, camera(scene, 'lp-open-high', (9.5, 9.5, 7.5), (3.5, 1.8, 12.5)), 'lp-open-high')
render(scene, camera(scene, 'lp-open-left', (-4.5, 7.5, 12.5), (3.5, 1.8, 12.5)), 'lp-open-left')
for b in blades:
    bpy.data.objects.remove(b)
render(scene, camera(scene, 'lp-open-empty', (9.5, 9.5, 7.5), (3.5, 1.8, 12.5)), 'lp-open-empty')
for o in (hp, gen, lp_low):
    bpy.data.objects.remove(o)
module(scene, 'lp_turbine', (0, 4, 9), lambda t: sum(p[1] for p in t)/3 >= 3)
render(scene, camera(scene, 'hood-from-below', (10, 2.5, 5), (3.5, 7.2, 12.5)), 'hood-from-below')
