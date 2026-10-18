"""Builds the ADS push-to-turn switch (the owner's reference photo ads-manual-activation-push-turn.png).

    blender --background --factory-startup --python art/models/panel/imported/push_turn.py
    blender ... --python art/models/panel/imported/push_turn.py -- --no-preview

There is no CAD file for this one, so it is modelled here from the photo and exported through the same
panel_export module as the imported models (build.py): inches, +Z out of the face, footprint top-left at the origin,
tagged parts, legend anchors. A dark plate carries a grey bezel ring with a black well, and a round red knob sits in
the well with a black arrow on its top.

The knob is one part that does two things. It turns about the middle of the well (pivot, axis +Z): the game turns it to
the AUTO legend at the left (-45 degrees) or the ARM legend at the right (+45 degrees) from the modelled pose, which
has the arrow pointing straight up. And it slides along the same axis: it is modelled at rest, standing out of the
bezel, and the game pushes it in by its (negative) travel while the switch is held, which is the only time it may be
turned. Legends are not geometry: pos1 (AUTO) and pos2 (ARM) mark the top corners, caption sits under the bezel.
"""
import math
import os
import sys

import bmesh  # noqa: F401  (kept for parity with build.py: the export goes through bmesh)
import bpy

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import panel_export as px  # noqa: E402

PREVIEW_DIR = os.path.join(px.REPO, "tmp", "panel-models", "imported")

W, H = 2.3, 2.2          # footprint, inches
CX, CY = W / 2, -1.15    # centre of the knob and the bezel (Blender y is up, the footprint hangs down from 0)
SEGMENTS = 24

PLATE = "2B2D2F"
BEZEL_TOP = "A4A9AD"
BEZEL_WALL = "7A7F83"
WELL = "0E0E0E"
KNOB_SIDE = "A81414"
KNOB_TOP = "D82222"
ARROW = "0A0A0A"

PLATE_Z = 0.08
BEZEL_Z = 0.22
BEZEL_R_IN, BEZEL_R_OUT = 0.58, 0.74
KNOB_R, KNOB_TOP_R = 0.50, 0.42
KNOB_Z, KNOB_TOP_Z = 0.30, 0.36   # the knob's side wall ends and its top, at rest
TRAVEL = -0.12                    # pushed in: 0.12 inch toward the panel (the knob is modelled at rest)


class Mesh:
    """Accumulates faces with a material per face, then becomes one object."""

    def __init__(self):
        self.verts = []
        self.faces = []
        self.mats = []
        self.names = []

    def mat(self, hex_rgb):
        name = "mat_" + hex_rgb
        if name not in self.names:
            self.names.append(name)
        return self.names.index(name)

    def face(self, points, hex_rgb):
        base = len(self.verts)
        self.verts.extend(points)
        self.faces.append(tuple(range(base, base + len(points))))
        self.mats.append(self.mat(hex_rgb))

    def to_object(self, name):
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata(self.verts, [], self.faces)
        for hex_rgb in (n[4:] for n in self.names):
            mesh.materials.append(px.material("mat_" + hex_rgb, hex_rgb))
        mesh.update()
        for poly, index in zip(mesh.polygons, self.mats):
            poly.material_index = index
        obj = bpy.data.objects.new(name, mesh)
        bpy.context.scene.collection.objects.link(obj)
        return obj


def ring_wall(m, r, z0, z1, hex_rgb, outward=True):
    """A cylinder wall of radius r from z0 to z1, facing out (or in)."""
    for i in range(SEGMENTS):
        a0 = 2 * math.pi * i / SEGMENTS
        a1 = 2 * math.pi * (i + 1) / SEGMENTS
        quad = [(CX + r * math.cos(a0), CY + r * math.sin(a0), z0), (CX + r * math.cos(a1), CY + r * math.sin(a1), z0),
                (CX + r * math.cos(a1), CY + r * math.sin(a1), z1), (CX + r * math.cos(a0), CY + r * math.sin(a0), z1)]
        m.face(quad if outward else quad[::-1], hex_rgb)


def annulus(m, r0, r1, z, hex_rgb):
    """A flat ring facing +Z."""
    for i in range(SEGMENTS):
        a0 = 2 * math.pi * i / SEGMENTS
        a1 = 2 * math.pi * (i + 1) / SEGMENTS
        m.face([(CX + r0 * math.cos(a0), CY + r0 * math.sin(a0), z), (CX + r1 * math.cos(a0), CY + r1 * math.sin(a0), z),
                (CX + r1 * math.cos(a1), CY + r1 * math.sin(a1), z), (CX + r0 * math.cos(a1), CY + r0 * math.sin(a1), z)],
               hex_rgb)


def disc(m, r, z, hex_rgb):
    """A flat disc facing +Z."""
    for i in range(SEGMENTS):
        a0 = 2 * math.pi * i / SEGMENTS
        a1 = 2 * math.pi * (i + 1) / SEGMENTS
        m.face([(CX, CY, z), (CX + r * math.cos(a0), CY + r * math.sin(a0), z),
                (CX + r * math.cos(a1), CY + r * math.sin(a1), z)], hex_rgb)


def build_body():
    m = Mesh()
    # The plate: a flat slab with its top and four sides (the back is never seen).
    x0, x1, y0, y1 = 0.0, W, -H, 0.0
    m.face([(x0, y0, PLATE_Z), (x1, y0, PLATE_Z), (x1, y1, PLATE_Z), (x0, y1, PLATE_Z)], PLATE)
    m.face([(x0, y0, 0.0), (x1, y0, 0.0), (x1, y0, PLATE_Z), (x0, y0, PLATE_Z)], PLATE)
    m.face([(x1, y0, 0.0), (x1, y1, 0.0), (x1, y1, PLATE_Z), (x1, y0, PLATE_Z)], PLATE)
    m.face([(x1, y1, 0.0), (x0, y1, 0.0), (x0, y1, PLATE_Z), (x1, y1, PLATE_Z)], PLATE)
    m.face([(x0, y1, 0.0), (x0, y0, 0.0), (x0, y0, PLATE_Z), (x0, y1, PLATE_Z)], PLATE)
    # The bezel ring and the black well inside it.
    ring_wall(m, BEZEL_R_OUT, PLATE_Z, BEZEL_Z, BEZEL_WALL)
    annulus(m, BEZEL_R_IN, BEZEL_R_OUT, BEZEL_Z, BEZEL_TOP)
    ring_wall(m, BEZEL_R_IN, PLATE_Z + 0.01, BEZEL_Z, BEZEL_WALL, outward=False)
    disc(m, BEZEL_R_IN, PLATE_Z + 0.01, WELL)
    return m.to_object("body")


def build_knob():
    m = Mesh()
    ring_wall(m, KNOB_R, PLATE_Z + 0.02, KNOB_Z, KNOB_SIDE)
    # The shoulder from the side wall in to the flat top, then the top.
    for i in range(SEGMENTS):
        a0 = 2 * math.pi * i / SEGMENTS
        a1 = 2 * math.pi * (i + 1) / SEGMENTS
        m.face([(CX + KNOB_R * math.cos(a0), CY + KNOB_R * math.sin(a0), KNOB_Z),
                (CX + KNOB_R * math.cos(a1), CY + KNOB_R * math.sin(a1), KNOB_Z),
                (CX + KNOB_TOP_R * math.cos(a1), CY + KNOB_TOP_R * math.sin(a1), KNOB_TOP_Z),
                (CX + KNOB_TOP_R * math.cos(a0), CY + KNOB_TOP_R * math.sin(a0), KNOB_TOP_Z)], KNOB_SIDE)
    disc(m, KNOB_TOP_R, KNOB_TOP_Z, KNOB_TOP)
    # The black arrow on top, pointing up at the modelled pose (12 o'clock).
    z = KNOB_TOP_Z + 0.004
    m.face([(CX, CY + 0.36, z), (CX - 0.17, CY + 0.15, z), (CX + 0.17, CY + 0.15, z)], ARROW)
    m.face([(CX - 0.055, CY - 0.26, z), (CX + 0.055, CY - 0.26, z), (CX + 0.055, CY + 0.17, z), (CX - 0.055, CY + 0.17, z)], ARROW)
    return m.to_object("knob")


def build():
    px.clear_scene()
    body = build_body()
    px.tag(body, part="body")
    knob = build_knob()
    px.tag(knob, part="knob", pivot=(CX, CY, 0.0), axis=(0, 0, 1), travel=TRAVEL)
    # AUTO and ARM at the top corners, where the arrow points at them from -45 and +45 degrees; the caption below.
    px.label("pos1", 0.5, -0.3, PLATE_Z + 0.005, 0.15)
    px.label("pos2", W - 0.5, -0.3, PLATE_Z + 0.005, 0.15)
    px.label("caption", W / 2, -H + 0.2, PLATE_Z + 0.005, 0.13)
    return "push_turn_switch", W, H, "Modelled from the owner's photo ads-manual-activation-push-turn.png (red knob, black arrow, bezel)", 400


def main():
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    model_id, w, h, source, budget = build()
    px.export(model_id, w, h, source, max_faces=budget)
    if "--no-preview" not in args:
        os.makedirs(PREVIEW_DIR, exist_ok=True)
        px.preview(os.path.join(PREVIEW_DIR, model_id), w, h)


main()
