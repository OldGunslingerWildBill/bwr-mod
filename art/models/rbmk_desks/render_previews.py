"""Preview renders of art/models/rbmk_desks/rbmk_desks.blend (build it first with build_rbmk_desks.py).

Run headless from the repo root:
    python3 art/models/rbmk_desks/render_previews.py
Writes PNGs into art/models/rbmk_desks/preview/. The .blend is opened read-only: cameras, lights and a floor are
added in memory only and never saved back.

Per desk <D> in A, P, T:
    <D>_front34.png   perspective, from the operator side, 3/4
    <D>_rear34.png    perspective, from behind, 3/4
    <D>_side.png      orthographic end view of the profile (looking at the left end cheek along +X)
    <D>_top.png       orthographic top view
All desks:
    all_top.png, all_persp.png
Camera views roughly imitating the reference images (reference/NN_*.png):
    ref02_P_front.png, ref05_A_left_end.png, ref06_A_front.png, ref07_T_front.png, ref09_rear_doors.png
"""
import math
import os
import sys

import bpy
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
BLEND = os.path.join(HERE, "rbmk_desks.blend")
OUT = os.path.join(HERE, "preview")
WIDTH = 1600
SAMPLES = 24


def desk_info(key):
    coll = bpy.data.collections["Desk_" + key]
    obs = list(coll.objects)
    pts = [ob.matrix_world @ v.co for ob in obs for v in ob.data.vertices]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return obs[0].location.copy(), lo, hi


def setup_scene():
    scene = bpy.context.scene
    # EEVEE needs a GPU context (EGL/Vulkan), which the headless pip bpy module cannot open here: Cycles on CPU.
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = True
    engine = "CYCLES"
    scene.render.film_transparent = False
    scene.view_settings.view_transform = "Standard"
    scene.render.image_settings.file_format = "PNG"

    world = bpy.data.worlds.new("preview_world")
    bg = world.node_tree.nodes["Background"]
    bg.inputs["Color"].default_value = (0.55, 0.57, 0.6, 1.0)
    bg.inputs["Strength"].default_value = 0.55
    scene.world = world

    # floor (in memory only)
    me = bpy.data.meshes.new("preview_floor")
    s = 60.0
    me.from_pydata([(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0)], [], [(0, 1, 2, 3)])
    floor = bpy.data.objects.new("preview_floor", me)
    m = bpy.data.materials.new("preview_floor")
    m.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.36, 0.30, 0.22, 1.0)
    m.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.8
    me.materials.append(m)
    scene.collection.objects.link(floor)

    def light(name, kind, energy, rot, loc=(0, 0, 10), size=None):
        ld = bpy.data.lights.new(name, kind)
        ld.energy = energy
        if size is not None:
            ld.size = size
        ob = bpy.data.objects.new(name, ld)
        ob.rotation_euler = [math.radians(a) for a in rot]
        ob.location = loc
        scene.collection.objects.link(ob)
        return ob

    light("key", "SUN", 3.2, (50, 0, -30))       # from the operator side, upper left
    light("fill", "SUN", 1.0, (60, 0, 160))       # from behind, upper right
    light("top", "SUN", 0.8, (5, 0, 0))
    return scene, engine


def camera(scene, name, loc, target, lens=35.0, ortho=None, aspect=(16, 9)):
    cd = bpy.data.cameras.new(name)
    cd.clip_start = 0.05
    cd.clip_end = 200.0
    if ortho is not None:
        cd.type = "ORTHO"
        cd.ortho_scale = ortho
    else:
        cd.lens = lens
    ob = bpy.data.objects.new(name, cd)
    scene.collection.objects.link(ob)
    ob.location = Vector(loc)
    d = Vector(target) - Vector(loc)
    ob.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    scene.camera = ob
    scene.render.resolution_x = WIDTH
    scene.render.resolution_y = int(WIDTH * aspect[1] / aspect[0])
    return ob


def shoot(scene, fname):
    scene.render.filepath = os.path.join(OUT, fname)
    bpy.ops.render.render(write_still=True)
    print("wrote", scene.render.filepath)


def main():
    bpy.ops.wm.open_mainfile(filepath=BLEND)
    os.makedirs(OUT, exist_ok=True)
    scene, engine = setup_scene()
    only = set(sys.argv[sys.argv.index("--") + 1:]) if "--" in sys.argv else None

    def want(name):
        return only is None or name in only

    info = {k: desk_info(k) for k in ("A", "P", "T")}
    for key, (org, lo, hi) in info.items():
        cx, cy = 0.5 * (lo.x + hi.x), 0.5 * (lo.y + hi.y)
        w = hi.x - lo.x
        dist = w * 0.9 + 2.5
        if want(key + "_front34"):
            camera(scene, "cam", (cx - 0.45 * dist, cy - dist, 2.6), (cx, cy, 0.6), lens=35)
            shoot(scene, key + "_front34.png")
        if want(key + "_rear34"):
            camera(scene, "cam", (cx + 0.45 * dist, cy + dist, 2.8), (cx, cy, 0.6), lens=35)
            shoot(scene, key + "_rear34.png")
        if want(key + "_side"):
            # look straight at the left end cheek, along the end section's own direction
            ch = bpy.data.objects[key + "_cheek_L"]
            nrm = Vector((0, 0, 0))
            for poly in ch.data.polygons:
                if abs(poly.normal.z) < 0.5 and poly.area > 0.05:
                    nrm += poly.normal * poly.area
            nrm.z = 0
            nrm.normalize()
            if nrm.x > 0:
                nrm = -nrm
            pts = [ch.matrix_world @ v.co for v in ch.data.vertices]
            cc = sum(pts, Vector()) / len(pts)
            cc.z = 0.62
            camera(scene, "cam", cc + nrm * 10.0, cc, ortho=1.75, aspect=(4, 3))
            shoot(scene, key + "_side.png")
        if want(key + "_top"):
            camera(scene, "cam", (cx, cy, 20.0), (cx, cy + 1e-4, 0.0), ortho=w * 1.1, aspect=(16, 9))
            shoot(scene, key + "_top.png")

    alo = Vector((min(i[1].x for i in info.values()), min(i[1].y for i in info.values()), 0))
    ahi = Vector((max(i[2].x for i in info.values()), max(i[2].y for i in info.values()), 1.2))
    c = 0.5 * (alo + ahi)
    if want("all_top"):
        camera(scene, "cam", (c.x, c.y, 30.0), (c.x, c.y + 1e-4, 0), ortho=(ahi.x - alo.x) * 1.05, aspect=(16, 5))
        shoot(scene, "all_top.png")
    if want("all_persp"):
        camera(scene, "cam", (c.x - 6.0, c.y - 17.0, 6.0), (c.x, c.y, 0.3), lens=35, aspect=(16, 7))
        shoot(scene, "all_persp.png")

    # --- imitations of the reference photos ---
    def front_of(key, back, height, side=0.0, look_z=0.55, lens=35, aspect=(16, 9)):
        org, lo, hi = info[key]
        cx = 0.5 * (lo.x + hi.x)
        return camera(scene, "cam", (cx + side, lo.y - back, height), (cx + side * 0.3, lo.y + 0.7, look_z),
                      lens=lens, aspect=aspect)

    if want("ref02_P_front"):
        front_of("P", 3.6, 2.3, side=0.1, look_z=0.45, lens=26, aspect=(1752, 601))
        shoot(scene, "ref02_P_front.png")
    if want("ref06_A_front"):
        front_of("A", 4.3, 2.0, side=0.6, look_z=0.55, lens=30, aspect=(962, 460))
        shoot(scene, "ref06_A_front.png")
    if want("ref07_T_front"):
        front_of("T", 4.6, 2.3, side=-0.2, look_z=0.45, lens=24, aspect=(2206, 707))
        shoot(scene, "ref07_T_front.png")
    if want("ref05_A_left_end"):
        org, lo, hi = info["A"]
        camera(scene, "cam", (lo.x - 1.6, lo.y - 1.1, 1.45), (lo.x + 1.3, lo.y + 0.9, 0.62), lens=24,
               aspect=(1123, 785))
        shoot(scene, "ref05_A_left_end.png")
    if want("ref09_rear_doors"):
        org, lo, hi = info["P"]
        camera(scene, "cam", (lo.x + 0.2, hi.y + 1.5, 2.1), (lo.x + 2.0, hi.y - 0.2, 0.6), lens=22,
               aspect=(1632, 1143))
        shoot(scene, "ref09_rear_doors.png")
    print("engine:", engine)


if __name__ == "__main__":
    main()
