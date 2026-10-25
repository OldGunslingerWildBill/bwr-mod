"""Build the industrial lamp models in Blender (run headless) and export the OBJ files.

    blender --background --factory-startup --python art/models/industrial_lamps/build_lamps.py

Each lamp is split into parts, so that the lens can be fullbright without lighting the housing:
  * <fixture>_housing.obj              the body, the canopy or the wall box (world light)
  * <type>_<fixture>_lens_<stage>.obj  the lens alone, one file per stage: off (dark glass, world light),
                                       w0 and w1 (warm-up, dimmer) and on (full). The lit stages set emissive_ambient,
                                       which the OBJ loader turns into a light level from the material's ambient colour Ka.
  * <type>_<fixture>_item.obj          the whole lamp, for the inventory icon.
Types: lps, hps, mercury. Fixtures: high_bay (ceiling pendant) and wall_pack (authored facing north, the lens at the
north, the wall to the south; the blockstate turns it). The shared materials go to lamps_materials.mtl.

The geometry is written in block units (0 to 1, north at z = 0, up at y = 1). Blender is driven with (x, -z, y), and the
OBJ exporter writes it back as (x, y, z), so the OBJ coordinates are the block coordinates again. The bounds of each file
are checked after export.
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


STAGES = ("off", "w0", "w1", "on")
# Lit lens colours, saturated. The first warm-up stage is dimmer (the light level falls); the texture for it is pushed
# towards red-pink, as a sodium lamp starts red.
TYPES = {"lps": (255, 140, 0), "hps": (255, 200, 90), "mercury": (170, 220, 255)}
FIXTURES = (("high_bay", high_bay), ("wall_pack", wall_pack))
# Ambient colour per lit stage. The OBJ loader's light level is mean(Ka) * 15, so Ka = (level + 0.2) / 15.
KA = {"w0": 4.2 / 15.0, "w1": 8.2 / 15.0, "on": 1.0}


def retarget(polys, keep):
    """The polygons whose material keep() maps to a new name (None drops the polygon)."""
    out = []
    for pts, material, uv in polys:
        new = keep(material)
        if new is not None:
            out.append((pts, new, uv))
    return out


def housing_only(material):
    return material if material == "housing" else None


def lens_as(state, t):
    return lambda material: ("lens_%s_%s" % (t, state)) if material.startswith("lens") else None


def item_of(t):
    return lambda material: "housing" if material == "housing" else "lens_%s_on" % t


def build_mesh(name, polys):
    verts = []
    index = {}
    faces = []
    uvs = []
    mats = []
    for pts, material, uv in polys:
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
    names = sorted(set(mats))
    for mat_name in names:
        mesh.materials.append(bpy.data.materials.get(mat_name) or bpy.data.materials.new(mat_name))
    slot = {mat_name: i for i, mat_name in enumerate(names)}
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


def write_obj(name, polys):
    obj = build_mesh(name, polys)
    path = os.path.join(OUT, name + ".obj")
    export(obj, path)
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    text = re.sub(r"^mtllib .*$", "mtllib lamps_materials.mtl", text, flags=re.M)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    lo, hi = check_bounds(path)
    print("%s: bounds %s .. %s, polygons %d" % (name, [round(v, 3) for v in lo], [round(v, 3) for v in hi],
                                              text.count("\nf ")))
    bpy.data.objects.remove(obj)
    # The exporter also writes its own .mtl next to the OBJ; the shared one replaces it.
    generated = os.path.join(OUT, name + ".mtl")
    if os.path.exists(generated):
        os.remove(generated)


def write_materials():
    lines = [
        "# Generated by art/models/industrial_lamps/build_lamps.py",
        "newmtl housing", "Kd 0.560000 0.588000 0.612000", "map_Kd minecraft:block/white_concrete", "",
    ]
    for t in TYPES:
        for state in STAGES:
            ka = KA.get(state, 0.0)
            lines += ["newmtl lens_%s_%s" % (t, state), "Kd 1.000000 1.000000 1.000000",
                      "Ka %.6f %.6f %.6f" % (ka, ka, ka),
                      "map_Kd bwr:block/lamp/%s_lens_%s" % (t, state), ""]
    with open(os.path.join(OUT, "lamps_materials.mtl"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    os.makedirs(OUT, exist_ok=True)
    for fixture, factory in FIXTURES:
        base = factory("lens_lps_on").polys
        write_obj("%s_housing" % fixture, retarget(base, housing_only))
        for t in TYPES:
            polys = factory("lens_%s_on" % t).polys
            for state in STAGES:
                write_obj("%s_%s_lens_%s" % (t, fixture, state), retarget(polys, lens_as(state, t)))
            write_obj("%s_%s_item" % (t, fixture), retarget(polys, item_of(t)))
    write_materials()
    print("lamp models written to", OUT)


if __name__ == "__main__":
    main()
