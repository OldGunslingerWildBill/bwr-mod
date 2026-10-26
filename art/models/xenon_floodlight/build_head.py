"""Xenon floodlight head: the reflector, the lens and the dark trunnion, housing and cooling fins, as OBJ parts.

Run headless (never with the GUI tools):

    blender.exe --background --factory-startup --python art/models/xenon_floodlight/build_head.py

Writes mod/src/main/resources/assets/bwr/models/block/xenon_floodlight/head_on/body.{obj,mtl} (lens lit) and head_off (lens
dark). The block's base, yoke arms and ballast box are JSON element models, not here. Three hand-written NeoForge OBJ
wrappers next to the folders show the parts: head_body.json (the head without the lens, lit by the world), and
head_lens_on.json / head_lens_off.json (the lens alone, drawn full bright while lit), chosen with "visibility".

Game coordinates, one unit is one block: the model is authored for a north-facing lamp, so the front is -Z; the trunnion
(the pitch pivot) is at the origin; +Y is up. The renderer (XenonFloodlightRenderer) turns it to the aim.

Parts (object names are the material names):
  dish    the reflector, a 60-degree cap of a 0.42-block sphere, open to the front; apex 0.21 behind the trunnion.
  lens    a flat lens across the mouth of the dish, planar UVs; lens_on is emissive in the labPBR _s alpha.
  dark    the trunnion axle and bosses, the cooling housing behind the dish and six fins on it.

Blender is only the exporter. Every face is written both ways round, so the parts show from either side.
"""
import math
import sys
from pathlib import Path

import bpy

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "mod" / "src" / "main" / "resources" / "assets" / "bwr" / "models" / "block" / "xenon_floodlight"

MATERIALS = {
    "dish": ("minecraft:block/iron_block", (1.0, 1.0, 1.0)),
    "dark": ("minecraft:block/gray_concrete", (0.55, 0.56, 0.58)),
}
LENS_TEXTURES = {"head_on": "bwr:block/xenon_lens", "head_off": "bwr:block/xenon_lens_off"}

R = 0.42          # dish radius
ZC = -0.21        # sphere centre: the apex is at +0.21 (behind), the mouth at z = 0 (front)
THETA_MAX = math.radians(60.0)
RINGS = 8
SEGMENTS = 24
LENS_Z = -0.012
LENS_R = 0.35


def mc_to_blender(p):
    """Game (x, y, z) to Blender, so that the default OBJ export (forward -Z, up Y) writes game coordinates back."""
    x, y, z = p
    return (x, -z, y)


class Part:
    def __init__(self):
        self.verts = []
        self.faces = []
        self.uvs = []  # one (u, v) per face corner, in face order

    def add_vert(self, p):
        self.verts.append(p)
        return len(self.verts) - 1

    def add_face(self, idx, uv=None):
        # both windings, so the part is visible from either side
        # untextured parts sample the middle of their sprite (a corner texel bleeds the next atlas sprite in)
        corner_uv = uv if uv is not None else [(0.5, 0.5)] * len(idx)
        self.faces.append(list(idx))
        self.uvs.append(list(corner_uv))
        self.faces.append(list(reversed(idx)))
        self.uvs.append(list(reversed(corner_uv)))

    def box(self, x0, x1, y0, y1, z0, z1):
        c = [
            (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
            (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
        ]
        ids = [self.add_vert(p) for p in c]
        for quad in ((0, 1, 2, 3), (4, 7, 6, 5), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)):
            self.add_face([ids[i] for i in quad])


def dish(part):
    ids = []
    for i in range(RINGS + 1):
        theta = THETA_MAX * i / RINGS
        row = []
        for j in range(SEGMENTS):
            phi = 2.0 * math.pi * j / SEGMENTS
            p = (R * math.sin(theta) * math.cos(phi), R * math.sin(theta) * math.sin(phi), ZC + R * math.cos(theta))
            row.append(part.add_vert(p))
        ids.append(row)
    for i in range(RINGS):
        for j in range(SEGMENTS):
            k = (j + 1) % SEGMENTS
            part.add_face([ids[i][j], ids[i][k], ids[i + 1][k], ids[i + 1][j]])


def lens(part):
    centre = part.add_vert((0.0, 0.0, LENS_Z))
    ring = []
    for j in range(SEGMENTS):
        phi = 2.0 * math.pi * j / SEGMENTS
        ring.append(part.add_vert((LENS_R * math.cos(phi), LENS_R * math.sin(phi), LENS_Z)))
    for j in range(SEGMENTS):
        k = (j + 1) % SEGMENTS
        pts = [(0.5, 0.5), (0.5 + math.cos(2 * math.pi * j / SEGMENTS) * 0.5, 0.5 + math.sin(2 * math.pi * j / SEGMENTS) * 0.5),
               (0.5 + math.cos(2 * math.pi * k / SEGMENTS) * 0.5, 0.5 + math.sin(2 * math.pi * k / SEGMENTS) * 0.5)]
        part.add_face([centre, ring[j], ring[k]], uv=pts)


def dark(part):
    part.box(-0.46, 0.46, -0.025, 0.025, -0.025, 0.025)      # trunnion axle
    part.box(0.36, 0.42, -0.05, 0.05, -0.05, 0.05)            # trunnion boss, right (on the dish rim)
    part.box(-0.42, -0.36, -0.05, 0.05, -0.05, 0.05)          # trunnion boss, left
    part.box(-0.20, 0.20, -0.20, 0.20, 0.19, 0.45)            # cooling housing behind the dish
    for k in range(6):
        y = -0.17 + 0.068 * k
        part.box(-0.26, 0.26, y - 0.008, y + 0.008, 0.20, 0.50)  # cooling fin


def build(variant):
    parts = {"dish": Part(), "lens": Part(), "dark": Part()}
    dish(parts["dish"])
    lens(parts["lens"])
    dark(parts["dark"])

    bpy.ops.wm.read_factory_settings(use_empty=True)
    for name, part in parts.items():
        me = bpy.data.meshes.new(name)
        me.from_pydata([mc_to_blender(v) for v in part.verts], [], part.faces)
        me.update()
        uv_layer = me.uv_layers.new(name="UVMap")
        loop = 0
        for face, uv in zip(part.faces, part.uvs):
            for k in range(len(face)):
                uv_layer.data[loop].uv = uv[k]
                loop += 1
        ob = bpy.data.objects.new(name, me)
        bpy.context.collection.objects.link(ob)

    OUT.joinpath(variant).mkdir(parents=True, exist_ok=True)
    obj_path = OUT / variant / "body.obj"
    bpy.ops.wm.obj_export(filepath=str(obj_path), forward_axis="NEGATIVE_Z", up_axis="Y", export_materials=False,
                          export_uv=True, export_normals=False, export_selected_objects=False)
    tidy(obj_path, variant)


def tidy(obj_path, variant):
    """Adds the material library and a usemtl per object (the exporter's own material output is not used)."""
    lines = obj_path.read_text(encoding="utf-8").splitlines()
    out = ["mtllib materials.mtl"]
    for line in lines:
        if line.startswith("mtllib"):
            continue
        out.append(line)
        if line.startswith("o "):
            name = line[2:].strip()
            mat = "lens" if name == "lens" else name
            out.append(f"usemtl {mat}")
    obj_path.write_text("\n".join(out) + "\n", encoding="utf-8")

    mtl = []
    for name, (tex, kd) in MATERIALS.items():
        mtl += [f"newmtl {name}", f"Kd {kd[0]:.4f} {kd[1]:.4f} {kd[2]:.4f}", f"map_Kd {tex}", ""]
    mtl += ["newmtl lens", "Kd 1.0000 1.0000 1.0000", f"map_Kd {LENS_TEXTURES[variant]}", ""]
    (obj_path.parent / "materials.mtl").write_text("\n".join(mtl) + "\n", encoding="utf-8")


if __name__ == "__main__":
    for variant in ("head_on", "head_off"):
        build(variant)
        print("wrote", OUT / variant)
    sys.stdout.flush()
