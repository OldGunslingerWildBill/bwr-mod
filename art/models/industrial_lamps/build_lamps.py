"""Build the industrial lamp models in Blender (run headless) and export the OBJ files.

    blender --background --factory-startup --python art/models/industrial_lamps/build_lamps.py

Twelve OBJ files go to mod/src/main/resources/assets/bwr/models/block/lamp/: a high-bay pendant and a wall pack for each
of the three lamp types (low-pressure sodium, high-pressure sodium, mercury), each lit (`_on`, the lens carries the
emissive texture) and unlit (`_off`). The wall packs are authored facing north (lens at the north, wall to the south); the
blockstate turns them. The shared materials go to lamps_materials.mtl.

The geometry is written here in block units (0 to 1, north at z = 0, up at y = 1, the way the models are read by
Minecraft). Blender is driven with (x, -z, y), and the OBJ exporter writes it back as (x, y, z), so the OBJ coordinates
are the block coordinates again. The script checks the bounds of each file after export.
"""
import math
import os
import re
import sys

sys.dont_write_bytecode = True

import bpy  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
OUT = os.path.join(REPO, "mod", "src", "main", "resources", "assets", "bwr", "models", "block", "lamp")
SEG = 16  # segments round the reflector and the lens


def s(v):
    """Sixteenths to block units."""
    return v / 16.0


def to_blender(p):
    x, y, z = p
    return (x, -z, y)


class Mesh:
    """A face soup: polygons with a material name and UVs, in block units."""

    def __init__(self):
        self.polys = []  # (points, material, uvs)

    def quad(self, pts, material, uvs=None, hint=None):
        """A polygon. hint is the outward direction in block units; the winding is set to match it."""
        if uvs is None:
            uvs = [(0, 0), (1, 0), (1, 1), (0, 1)]
        pts = list(pts)
        if hint is not None:
            p0, p1, p2 = pts[0], pts[1], pts[2]
            n = [(p1[1]-p0[1])*(p2[2]-p0[2])-(p1[2]-p0[2])*(p2[1]-p0[1]),
                 (p1[2]-p0[2])*(p2[0]-p0[0])-(p1[0]-p0[0])*(p2[2]-p0[2]),
                 (p1[0]-p0[0])*(p2[1]-p0[1])-(p1[1]-p0[1])*(p2[0]-p0[0])]
            if sum(a * b for a, b in zip(n, hint)) < 0:
                pts.reverse()
                uvs = list(reversed(uvs))
        self.polys.append((pts, material, uvs))

    def box(self, x0, y0, z0, x1, y1, z1, default="housing", lens=()):
        """An axis-aligned box in sixteenths; faces named in `lens` ('-Z', '-Y', ...) take the lens material."""
        x0, y0, z0, x1, y1, z1 = (s(v) for v in (x0, y0, z0, x1, y1, z1))
        faces = {
            "-Z": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
            "+Z": [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)],
            "-X": [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
            "+X": [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
            "-Y": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
            "+Y": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        }
        hints = {"-Z": (0, 0, -1), "+Z": (0, 0, 1), "-X": (-1, 0, 0), "+X": (1, 0, 0), "-Y": (0, -1, 0), "+Y": (0, 1, 0)}
        for name, pts in faces.items():
            self.quad(pts, self.lens_name if name in lens else default, hint=hints[name])

    def lathe(self, cx, cz, profile, material):
        """Side rings of a surface of revolution. profile is [(radius, y)] in sixteenths, top first."""
        for (r0, y0), (r1, y1) in zip(profile, profile[1:]):
            for i in range(SEG):
                a0 = 2 * math.pi * i / SEG
                a1 = 2 * math.pi * (i + 1) / SEG
                pts = [
                    (cx + s(r0) * math.cos(a0), s(y0), cz + s(r0) * math.sin(a0)),
                    (cx + s(r0) * math.cos(a1), s(y0), cz + s(r0) * math.sin(a1)),
                    (cx + s(r1) * math.cos(a1), s(y1), cz + s(r1) * math.sin(a1)),
                    (cx + s(r1) * math.cos(a0), s(y1), cz + s(r1) * math.sin(a0)),
                ]
                am = 2 * math.pi * (i + 0.5) / SEG
                self.quad(pts, material, hint=(math.cos(am), 0, math.sin(am)))

    def disk_down(self, cx, y, cz, radius, material):
        """A flat round lens facing down (the bottom of a high bay)."""
        for i in range(SEG):
            a0 = 2 * math.pi * i / SEG
            a1 = 2 * math.pi * (i + 1) / SEG
            pts = [
                (cx, s(y), cz),
                (cx + s(radius) * math.cos(a1), s(y), cz + s(radius) * math.sin(a1)),
                (cx + s(radius) * math.cos(a0), s(y), cz + s(radius) * math.sin(a0)),
            ]
            uvs = [(0.5, 0.5), (0.5 + 0.5 * math.cos(a1), 0.5 + 0.5 * math.sin(a1)),
                   (0.5 + 0.5 * math.cos(a0), 0.5 + 0.5 * math.sin(a0))]
            self.quad(pts, material, uvs=uvs, hint=(0, -1, 0))


def high_bay(lens_name):
    """Ceiling pendant: canopy, a dome reflector of revolution and a flat lens underneath, centred on the block."""
    m = Mesh()
    m.lens_name = lens_name
    m.box(6, 12, 6, 10, 16, 10)                       # canopy against the ceiling
    m.lathe(0.5, 0.5, [(4.0, 12.0), (5.0, 10.0), (5.0, 6.0), (4.5, 4.0)], "housing")
    m.lathe(0.5, 0.5, [(4.5, 4.0), (4.0, 2.0)], "housing")  # rim down to the lens
    m.disk_down(0.5, 2.0, 0.5, 4.0, lens_name)
    return m


def wall_pack(lens_name):
    """Wall box facing north with the lens angled down at the front. The mounting plate is at the south (the wall)."""
    m = Mesh()
    m.lens_name = lens_name
    m.box(3, 6, 15, 13, 12, 16)                       # mounting plate on the wall
    m.box(2, 5, 8, 14, 13, 15)                        # housing
    m.box(3, 3, 4, 13, 6, 8, lens=("-Z", "-Y"))       # lens, front and underside
    return m


def build_mesh(name, m):
    verts = []
    index = {}
    faces = []
    uvs = []
    mats = []
    for pts, material, uv in m.polys:
        idx = []
        for p in pts:
            key = tuple(round(c, 6) for c in p)
            if key not in index:
                index[key] = len(verts)
                verts.append(to_blender(p))
            idx.append(index[key])
        faces.append(idx)
        uvs.append(uv)
        mats.append(material)
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    uv_layer = mesh.uv_layers.new(name="UVMap")
    for poly, uv in zip(mesh.polygons, uvs):
        for k, loop_index in enumerate(poly.loop_indices):
            uv_layer.data[loop_index].uv = uv[k]
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    for mat_name in sorted(set(mats)):
        mesh.materials.append(bpy.data.materials.get(mat_name) or bpy.data.materials.new(mat_name))
    slot = {mat_name: i for i, mat_name in enumerate(sorted(set(mats)))}
    for poly, mat_name in zip(mesh.polygons, mats):
        poly.material_index = slot[mat_name]
    return obj


def export(obj, path):
    for o in bpy.data.objects:
        o.select_set(False)
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.wm.obj_export(filepath=path, check_existing=False, export_selected_objects=True,
                          apply_modifiers=False, export_uv=True, export_normals=True, export_materials=True,
                          export_triangulated_mesh=False, forward_axis="NEGATIVE_Z", up_axis="Y")


def check_bounds(path):
    lo = [9.0, 9.0, 9.0]
    hi = [-9.0, -9.0, -9.0]
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("v "):
                xyz = [float(v) for v in line.split()[1:4]]
                for k in range(3):
                    lo[k] = min(lo[k], xyz[k])
                    hi[k] = max(hi[k], xyz[k])
    for k in range(3):
        if lo[k] < -0.001 or hi[k] > 1.001:
            raise SystemExit("%s: bounds outside the block: %s .. %s" % (path, lo, hi))
    return lo, hi


def write_materials():
    types = {"lps": (255, 160, 30), "hps": (255, 206, 128), "mercury": (190, 236, 226)}
    lines = [
        "# Generated by art/models/industrial_lamps/build_lamps.py",
        "newmtl housing", "Kd 0.560000 0.588000 0.612000", "map_Kd minecraft:block/white_concrete", "",
    ]
    for t in types:
        lines += ["newmtl lens_%s_on" % t, "Kd 1.000000 1.000000 1.000000",
                  "map_Kd bwr:block/lamp/%s_lens_on" % t, ""]
        lines += ["newmtl lens_%s_off" % t, "Kd 1.000000 1.000000 1.000000",
                  "map_Kd bwr:block/lamp/%s_lens_off" % t, ""]
    with open(os.path.join(OUT, "lamps_materials.mtl"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    for t in ("lps", "hps", "mercury"):
        for state in ("on", "off"):
            lens = "lens_%s_%s" % (t, state)
            for fixture, factory in (("high_bay", high_bay), ("wall_pack", wall_pack)):
                name = "%s_%s_%s" % (t, fixture, state)
                obj = build_mesh(name, factory(lens))
                path = os.path.join(OUT, name + ".obj")
                export(obj, path)
                with open(path, encoding="utf-8") as fh:
                    text = fh.read()
                text = re.sub(r"^mtllib .*$", "mtllib lamps_materials.mtl", text, flags=re.M)
                with open(path, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(text)
                lo, hi = check_bounds(path)
                print("%s: bounds %s .. %s, faces %d" % (name, [round(v, 3) for v in lo],
                                                       [round(v, 3) for v in hi], text.count("\nf ")))
                bpy.data.objects.remove(obj)
                # The exporter also writes its own .mtl next to the OBJ; the shared one replaces it.
                generated = os.path.join(OUT, name + ".mtl")
                if os.path.exists(generated):
                    os.remove(generated)
    write_materials()
    print("lamp models written to", OUT)


if __name__ == "__main__":
    main()
