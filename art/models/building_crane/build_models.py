"""Blender-authored reactor building crane (an overhead bridge crane on high runway rails) as parts. Run headless:

    blender.exe --background --python art/models/building_crane/build_models.py

Writes one <part>.json per part (mesh and UV data for tools-export-building-crane.py), building_crane.blend (the editable part
scenes plus a review scene of a 12 m span) and building-crane*.png (Workbench renders of the review scene).

Game coordinates, one unit is one block (one metre): X is the way the crane travels along the runway, Z is the span (from the
first runway rail to the second), Y is up, and y = 0 is the bottom of the runway rail block (the rail head's top is at 0.8125).
Every part is drawn for a crane that travels along X; the renderer turns it for runways along Z.

Parts (the renderer places them; see BuildingCraneRenderer):
  end_carriage    origin on the first runway rail's centre line, built with the girders going to +Z; the other end is the same part
                  turned half a turn. Four flanged wheels on the rail head, the carriage beam, buffers, drive motor, gearbox, cabinet.
  girder_plain    one metre of everything plain and straight: two box girders (centres at x = +-0.65), their trolley rails, flanges;
                  stretched along the span.
  girder_tile     one metre of the detail: diaphragm plates and stiffeners, a cable festoon clip; tiled once per metre.
  cab             the operator's cab hung under the first girder near the first rail, with window, door, seat, pendant console.
  trolley         origin on the hook axis at the middle of the girders: four wheels on the trolley rails, frame, hoist drum with its
                  gear motor, brake, cabinet, rope guide.
  hook_block      origin at the hook's load point (the lowest point of the hook): two sheaves, the block, the hook; the ropes are drawn
                  from the top of this block (y = 1.05) up to the drum by the renderer (a unit rope scaled).
  rope            a unit rope (y 0..1) the renderer scales to the length paid out.
  strongback_hook origin at the hook point: the lifting ring and the pendant chains down to the spreader (0.55 below).
  spreader        the spreader beams of the head strongback, a unit (radius 1, crossed diagonals at 45 and 135 degrees) the renderer
                  scales to the lug ring of the head; slings (unit boxes) are drawn from its four ends to the head's lugs.
  sling           a unit sling (y 0..1) the renderer scales.
  stand_cradle    a unit cradle (radius 1, height 1) under the head's flange on a head stand: ring beam and four legs.
  tensioner_tool  the stud tensioner's tool on a stud, in units of the stud radius (origin on the flange; 5.6 high).
"""
import json
import math
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector

OUT = Path(__file__).resolve().parent
SCENES = []
PARTS = {}


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


def game(p):
    return (p[0], p[2], -p[1])


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def material(name, rgb, metal=0.0, texture=None):
    m = bpy.data.materials.new('Crane | ' + name)
    col = (1.0, 1.0, 1.0) if texture else tuple(lin(c) for c in rgb)
    m.diffuse_color = (*col, 1)
    m.metallic = metal
    m.roughness = 0.45
    if texture:
        m['texture'] = texture
    return m



YEL = material('safety yellow', (247, 176, 18))
YELD = material('yellow shade', (226, 150, 10))
ORG = material('hook orange', (250, 104, 14))
ORGD = material('hook orange shade', (214, 80, 8))
METAL = material('steel', (166, 174, 180), .6)
METALD = material('steel dark', (112, 120, 126), .6)
GALV = material('galvanised', (196, 202, 206), .7)
DARK = material('black steel', (28, 30, 34))
GRAT = material('grating', (64, 68, 72))
CAB = material('cabinet grey', (150, 156, 152))
CABD = material('console grey', (98, 106, 110))
SCREEN = material('screen glass', (14, 44, 50))
GLASSW = material('window glass', (92, 150, 168))
RED = material('stop button', (205, 30, 24))
AMBER = material('beacon', (250, 150, 20))
CABLE = material('cable black', (20, 20, 22))
ROPE = material('wire rope', (70, 72, 76))
SEAT = material('seat', (36, 40, 46))
BLUEP = material('hydraulic blue', (26, 86, 172))



def new_scene(name):
    s = bpy.data.scenes.new(name)
    SCENES.append(s)
    return s


def mesh_object(scene, name, vs, fs, mat, uvs=None, fix=True):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([xyz(p) for p in vs], [], fs)
    mesh.update()
    if fix:
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(mesh)
        bm.free()
    uv = mesh.uv_layers.new(name='Material UV')
    if uvs is None:
        for loop in uv.data:
            loop.uv = (.5, .5)
    else:
        for poly in mesh.polygons:
            for k, li in enumerate(poly.loop_indices):
                uv.data[li].uv = uvs[k % len(uvs)]
    o = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(o)
    mesh.materials.append(mat)
    return o


BOX_FACES = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]


def box(s, name, lo, hi, mat, uvs=None):
    x0, x1 = sorted((lo[0], hi[0]))
    y0, y1 = sorted((lo[1], hi[1]))
    z0, z1 = sorted((lo[2], hi[2]))
    vs = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    return mesh_object(s, name, vs, BOX_FACES, mat, uvs)


def beam(s, name, a, b, w, d, mat, side=None):
    """A square-ended member from a to b, w wide along `side` (default: horizontal, across the member), d deep the other way."""
    a, b = Vector(a), Vector(b)
    dirv = (b - a).normalized()
    sv = Vector(side) if side is not None else dirv.cross(Vector((0, 1, 0)))
    if sv.length < 1e-6:
        sv = Vector((1, 0, 0))
    sv = (sv - dirv * sv.dot(dirv)).normalized()
    tv = dirv.cross(sv).normalized()
    vs = []
    for p in (a, b):
        for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            vs.append(tuple(p + sv * (sx * w / 2) + tv * (sy * d / 2)))
    return mesh_object(s, name, vs, BOX_FACES, mat)


def basis(axis):
    d = Vector(axis).normalized()
    a = d.cross(Vector((0, 1, 0)) if abs(d.y) < .9 else Vector((1, 0, 0))).normalized()
    return d, a, d.cross(a).normalized()


def cyl(s, name, a, b, r, mat, n=12):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    vs = [tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for p in (a, b) for i in range(n)]
    fs = [tuple(range(n - 1, -1, -1)), tuple(range(n, 2 * n))] + [(i, (i + 1) % n, (i + 1) % n + n, i + n) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def sleeve(s, name, a, b, outer, inner, mat, n=16):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b - a)
    rings = [(a, outer), (b, outer), (b, inner), (a, inner)]
    vs = [tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for p, r in rings for i in range(n)]
    fs = [(r * n + i, r * n + (i + 1) % n, ((r + 1) % 4) * n + (i + 1) % n, ((r + 1) % 4) * n + i) for r in range(4) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)


def sweep(s, name, path, r, mat, n=4):
    pts = [Vector(p) for p in path]
    vs = []
    u = None
    for j, p in enumerate(pts):
        d = (pts[min(len(pts) - 1, j + 1)] - pts[max(0, j - 1)]).normalized()
        if u is None:
            u = basis(d)[1]
        else:
            u = (u - d * u.dot(d)).normalized()
        v = d.cross(u).normalized()
        vs.extend(tuple(p + r * (math.cos(i * math.tau / n) * u + math.sin(i * math.tau / n) * v)) for i in range(n))
    fs = [(j * n + i, j * n + (i + 1) % n, (j + 1) * n + (i + 1) % n, (j + 1) * n + i) for j in range(len(pts) - 1) for i in range(n)]
    fs += [tuple(range(n - 1, -1, -1)), tuple(range((len(pts) - 1) * n, len(pts) * n))]
    return mesh_object(s, name, vs, fs, mat)


def part(name):
    s = new_scene('BWR | crane ' + name)
    PARTS[name] = s
    return s



# ------------------------------------------------------------------ dimensions
RAIL_TOP = 0.8125      # the runway rail head, above the rail block's floor
WHEEL_R = 0.24
AXLE_Y = RAIL_TOP + WHEEL_R
CARRIAGE_TOP = 1.30    # the girders sit on the end carriages
GIRDER_TOP = 2.60
GX = 0.65              # girder centre lines at x = +-GX
GHALF = 0.225          # half a girder's width
TROLLEY_RAIL_TOP = 2.68
ROPE_TOP = 3.10        # where the ropes leave the drum (the renderer's constant)


# ------------------------------------------------------------------ end carriage
def build_end_carriage():
    s = part('end_carriage')
    # a carriage beam along the runway on four wheels on the rail head
    box(s, 'carriage beam', (-1.35, 0.98, -0.42), (1.35, CARRIAGE_TOP, 0.42), YEL)
    box(s, 'carriage beam web', (-1.30, CARRIAGE_TOP, -0.22), (1.30, CARRIAGE_TOP + 0.10, 0.22), YELD)
    for x in (-0.95, 0.95):
        # wheel housing, axle across the runway, two flanged wheels either side of the rail head
        box(s, 'wheel housing', (x - 0.32, AXLE_Y - 0.06, -0.40), (x + 0.32, 0.98, 0.40), YELD)
        cyl(s, 'axle', (x, AXLE_Y, -0.46), (x, AXLE_Y, 0.46), 0.05, DARK, 8)
        cyl(s, 'wheel', (x, AXLE_Y, -0.09), (x, AXLE_Y, 0.09), WHEEL_R, DARK, 14)
        cyl(s, 'wheel flange a', (x, AXLE_Y, -0.13), (x, AXLE_Y, -0.09), WHEEL_R + 0.05, DARK, 14)
        cyl(s, 'wheel flange b', (x, AXLE_Y, 0.09), (x, AXLE_Y, 0.13), WHEEL_R + 0.05, DARK, 14)
        cyl(s, 'wheel hub', (x, AXLE_Y, -0.17), (x, AXLE_Y, 0.17), 0.08, METALD, 8)
    for sx in (-1, 1):
        box(s, 'buffer', (sx * 1.35, 1.02, -0.18), (sx * 1.47, 1.26, 0.18), DARK)
        box(s, 'buffer plate', (sx * 1.47, 0.98, -0.24), (sx * 1.50, 1.30, 0.24), YELD)
    # girder seats: the girders land on the carriage beam and are bolted to its ends
    for gx in (-GX, GX):
        box(s, 'girder seat', (gx - 0.30, CARRIAGE_TOP + 0.10, -0.46), (gx + 0.30, CARRIAGE_TOP + 0.18, 0.46), YELD)
        for sz in (-1, 1):
            box(s, 'seat bracket', (gx - 0.28, CARRIAGE_TOP + 0.10, sz * 0.46), (gx + 0.28, CARRIAGE_TOP + 0.55, sz * 0.46 + sz * 0.04), YELD)
    # drive: a motor and a gearbox on the outer side of the carriage, a cabinet, a junction box, the stop button
    cyl(s, 'drive motor', (-1.05, 1.15, 0.40), (-1.05, 1.15, 0.92), 0.17, DARK, 12)
    box(s, 'gearbox', (-1.27, 0.98, 0.12), (-0.83, 1.40, 0.40), CABD)
    box(s, 'drive cabinet', (0.62, 0.98, 0.42), (1.30, 1.90, 0.80), CAB)
    box(s, 'cabinet door', (0.64, 1.04, 0.80), (1.28, 1.84, 0.815), CABD)
    box(s, 'cabinet handle', (1.14, 1.35, 0.815), (1.20, 1.55, 0.835), DARK)
    box(s, 'junction box', (-0.55, 0.98, 0.42), (-0.15, 1.46, 0.72), CAB)
    box(s, 'stop button box', (-0.10, 1.00, 0.42), (0.12, 1.20, 0.52), YELD)
    cyl(s, 'stop button', (0.0, 1.10, 0.52), (0.0, 1.10, 0.57), 0.035, RED, 8)
    cyl(s, 'beacon', (0.0, CARRIAGE_TOP + 0.10, -0.30), (0.0, CARRIAGE_TOP + 0.24, -0.30), 0.07, AMBER, 10)
    # a rail sweep plate in front of each pair of wheels
    for x in (-1.22, 1.22):
        box(s, 'rail sweep', (x - 0.02, RAIL_TOP, -0.12), (x + 0.02, RAIL_TOP + 0.20, 0.12), DARK)


# ------------------------------------------------------------------ girders
def build_girder_plain():
    s = part('girder_plain')
    for gx in (-GX, GX):
        box(s, 'girder', (gx - GHALF, CARRIAGE_TOP + 0.18, 0), (gx + GHALF, GIRDER_TOP, 1), YEL)
        box(s, 'girder lower flange', (gx - GHALF - 0.04, CARRIAGE_TOP + 0.15, 0), (gx + GHALF + 0.04, CARRIAGE_TOP + 0.18, 1), YELD)
        box(s, 'girder upper flange', (gx - GHALF - 0.04, GIRDER_TOP, 0), (gx + GHALF + 0.04, GIRDER_TOP + 0.03, 1), YELD)
        box(s, 'trolley rail', (gx - 0.06, GIRDER_TOP + 0.03, 0), (gx + 0.06, TROLLEY_RAIL_TOP, 1), DARK)
    # the walkway grating along the outer side of the first girder, with a toe plate and a rail
    box(s, 'walkway', (-GX - GHALF - 0.55, GIRDER_TOP - 0.28, 0), (-GX - GHALF, GIRDER_TOP - 0.22, 1), GRAT)
    box(s, 'toe plate', (-GX - GHALF - 0.57, GIRDER_TOP - 0.22, 0), (-GX - GHALF - 0.55, GIRDER_TOP - 0.10, 1), YEL)
    cyl(s, 'handrail', (-GX - GHALF - 0.56, GIRDER_TOP + 0.80, 0), (-GX - GHALF - 0.56, GIRDER_TOP + 0.80, 1), 0.022, GALV, 8)
    cyl(s, 'midrail', (-GX - GHALF - 0.56, GIRDER_TOP + 0.40, 0), (-GX - GHALF - 0.56, GIRDER_TOP + 0.40, 1), 0.018, GALV, 8)
    box(s, 'festoon track', (GX + GHALF, GIRDER_TOP - 0.16, 0), (GX + GHALF + 0.04, GIRDER_TOP - 0.11, 1), METALD)


def build_girder_tile():
    s = part('girder_tile')
    for gx in (-GX, GX):
        box(s, 'diaphragm', (gx - GHALF - 0.03, CARRIAGE_TOP + 0.14, 0.0), (gx + GHALF + 0.03, GIRDER_TOP + 0.03, 0.05), YELD)
        box(s, 'stiffener', (gx - GHALF - 0.03, CARRIAGE_TOP + 0.18, 0.5), (gx - GHALF, GIRDER_TOP, 0.56), YELD)
        box(s, 'stiffener', (gx + GHALF, CARRIAGE_TOP + 0.18, 0.5), (gx + GHALF + 0.03, GIRDER_TOP, 0.56), YELD)
    cyl(s, 'handrail post', (-GX - GHALF - 0.56, GIRDER_TOP - 0.22, 0.5), (-GX - GHALF - 0.56, GIRDER_TOP + 0.80, 0.5), 0.024, GALV, 8)
    box(s, 'walkway outrigger', (-GX - GHALF - 0.55, GIRDER_TOP - 0.34, 0.45), (-GX - GHALF, GIRDER_TOP - 0.28, 0.53), YEL)
    box(s, 'festoon clip', (GX + GHALF, GIRDER_TOP - 0.19, 0.0), (GX + GHALF + 0.08, GIRDER_TOP - 0.10, 0.03), METALD)
    path = []
    for k in range(8):
        t = k / 7.0
        path.append((GX + GHALF + 0.075, GIRDER_TOP - 0.17 - 0.30 * 4 * t * (1 - t), 0.03 + 0.94 * t))
    sweep(s, 'festoon cable', path, 0.016, CABLE, 4)


# ------------------------------------------------------------------ cab
def build_cab():
    s = part('cab')
    # hung under the first girder (x = -GX), near the first rail: floor 0.0, roof just under the girder
    x0, x1 = -1.75, -0.45
    z0, z1 = 0.62, 2.12
    y0, y1 = 0.05, 1.22
    box(s, 'cab floor', (x0, y0, z0), (x1, y0 + 0.08, z1), YELD)
    box(s, 'cab roof', (x0 - 0.04, y1, z0 - 0.04), (x1 + 0.04, y1 + 0.07, z1 + 0.04), YEL)
    for (a, b) in (((x0, z0), (x0 + 0.06, z0 + 0.06)), ((x1 - 0.06, z0), (x1, z0 + 0.06)), ((x0, z1 - 0.06), (x0 + 0.06, z1)), ((x1 - 0.06, z1 - 0.06), (x1, z1))):
        box(s, 'cab post', (a[0], y0 + 0.08, a[1]), (b[0], y1, b[1]), YEL)
    # side walls: solid below the sill, glass above
    box(s, 'cab wall outer', (x0, y0 + 0.08, z0), (x0 + 0.04, y0 + 0.50, z1), YEL)
    box(s, 'cab glass outer', (x0 + 0.005, y0 + 0.50, z0 + 0.06), (x0 + 0.02, y1, z1 - 0.06), GLASSW)
    box(s, 'cab wall inner', (x1 - 0.04, y0 + 0.08, z0), (x1, y0 + 0.50, z1), YEL)
    box(s, 'cab glass inner', (x1 - 0.02, y0 + 0.50, z0 + 0.06), (x1 - 0.005, y1, z1 - 0.06), GLASSW)
    box(s, 'cab wall back', (x0, y0 + 0.08, z0), (x1, y0 + 0.50, z0 + 0.04), YEL)
    box(s, 'cab glass back', (x0 + 0.06, y0 + 0.50, z0 + 0.005), (x1 - 0.06, y1, z0 + 0.02), GLASSW)
    box(s, 'cab wall front', (x0, y0 + 0.08, z1 - 0.04), (x1, y0 + 0.50, z1), YEL)
    box(s, 'cab glass front', (x0 + 0.06, y0 + 0.50, z1 - 0.02), (x1 - 0.06, y1, z1 - 0.005), GLASSW)
    # seat, console, hangers up to the girder
    box(s, 'seat base', (x0 + 0.25, y0 + 0.08, z0 + 0.35), (x0 + 0.65, y0 + 0.40, z0 + 0.75), SEAT)
    box(s, 'seat back', (x0 + 0.20, y0 + 0.40, z0 + 0.35), (x0 + 0.27, y0 + 0.80, z0 + 0.75), SEAT)
    box(s, 'console', (x0 + 0.70, y0 + 0.08, z0 + 0.30), (x0 + 1.12, y0 + 0.62, z0 + 0.80), CABD)
    box(s, 'console screen', (x0 + 0.72, y0 + 0.62, z0 + 0.34), (x0 + 0.76, y0 + 0.82, z0 + 0.76), SCREEN)
    for z in (z0 + 0.45, z0 + 0.65):
        box(s, 'console lever', (x0 + 0.85, y0 + 0.62, z - 0.02), (x0 + 0.89, y0 + 0.72, z + 0.02), DARK)
    for z in (z0 + 0.15, z1 - 0.15):
        box(s, 'cab hanger', (-GX - GHALF - 0.02, y1 + 0.07, z - 0.04), (x1 + 0.02, CARRIAGE_TOP + 0.35, z + 0.04), YELD)
        box(s, 'cab hanger bar', (x0, y1 + 0.07, z - 0.04), (-GX - GHALF, y1 + 0.13, z + 0.04), YELD)
    cyl(s, 'cab beacon', (x0 + 0.65, y1 + 0.07, z0 + 0.75), (x0 + 0.65, y1 + 0.21, z0 + 0.75), 0.06, AMBER, 10)
    box(s, 'ladder rung a', (x0 - 0.02, y0 + 0.2, z1 - 0.02), (x0 + 0.02, y0 + 0.24, z1 + 0.30), GALV)


# ------------------------------------------------------------------ trolley
def build_trolley():
    s = part('trolley')
    wz = 0.55
    for gx in (-GX, GX):
        for z in (-wz, wz):
            cyl(s, 'trolley wheel', (gx, TROLLEY_RAIL_TOP + 0.11, z - 0.07), (gx, TROLLEY_RAIL_TOP + 0.11, z + 0.07), 0.11, DARK, 12)
            box(s, 'wheel bracket', (gx - 0.14, TROLLEY_RAIL_TOP + 0.05, z - 0.11), (gx + 0.14, TROLLEY_RAIL_TOP + 0.26, z + 0.11), YELD)
        # side beam over the girder, along the span
        box(s, 'side beam', (gx - 0.17, 2.94, -0.80), (gx + 0.17, 3.12, 0.80), YEL)
    for z in (-wz, wz):
        box(s, 'cross beam', (-GX - 0.17, 2.94, z - 0.13), (GX + 0.17, 3.12, z + 0.13), YEL)
    # bearing blocks and the hoist drum between them (axis across the span's direction: along X), ropes leave at x = +-0.2
    for gx in (-GX, GX):
        box(s, 'bearing block', (gx - 0.14, 3.12, -0.16), (gx + 0.14, 3.42, 0.16), YELD)
    cyl(s, 'drum', (-0.56, 3.40, 0), (0.56, 3.40, 0), 0.30, METALD, 18)
    for k in range(-5, 6):
        cyl(s, 'drum groove', (k * 0.10 - 0.02, 3.40, 0), (k * 0.10 + 0.02, 3.40, 0), 0.315, DARK, 18)
    cyl(s, 'drum flange a', (-0.60, 3.40, 0), (-0.56, 3.40, 0), 0.38, YELD, 18)
    cyl(s, 'drum flange b', (0.56, 3.40, 0), (0.60, 3.40, 0), 0.38, YELD, 18)
    # gear motor and brake on one end
    box(s, 'gearbox', (0.80, 3.12, -0.22), (1.12, 3.68, 0.22), CABD)
    cyl(s, 'hoist motor', (1.12, 3.40, 0), (1.80, 3.40, 0), 0.22, DARK, 14)
    cyl(s, 'brake drum', (1.80, 3.40, 0), (1.92, 3.40, 0), 0.26, METALD, 14)
    box(s, 'brake caliper', (1.74, 3.58, -0.08), (1.98, 3.80, 0.08), RED)
    for z in (-0.18, 0.18):
        box(s, 'motor foot', (1.20, 3.12, z - 0.05), (1.70, 3.20, z + 0.05), YELD)
    # control cabinet and a limit switch on the frame
    box(s, 'control cabinet', (-1.12, 3.12, -0.46), (-0.80, 4.00, 0.26), CAB)
    box(s, 'control cabinet door', (-1.12, 3.20, -0.46), (-1.115, 3.94, 0.20), CABD)
    box(s, 'limit switch', (-0.30, 3.12, 0.60), (-0.10, 3.40, 0.74), AMBER)
    cyl(s, 'trolley beacon', (0.70, 3.12, -0.60), (0.70, 3.30, -0.60), 0.06, AMBER, 10)
    # rope guide: a pair of clamps on the cross beam where the ropes pass
    for x in (-0.20, 0.20):
        box(s, 'rope clamp', (x - 0.05, 2.97, -0.06), (x + 0.05, 3.10, 0.06), METALD)
    # handrail round the machinery deck on the cab side
    for z in (-0.78, 0.78):
        cyl(s, 'deck rail', (-1.15, 3.55, z), (1.0, 3.55, z), 0.02, GALV, 8)


# ------------------------------------------------------------------ hook block, rope
def build_hook_block():
    s = part('hook_block')
    # the hook itself: shank, bowl, point; origin at the lowest point of the bowl (the load point)
    box(s, 'hook point', (-0.06, 0.0, 0.10), (0.06, 0.20, 0.19), DARK)
    box(s, 'hook bowl', (-0.06, 0.0, -0.10), (0.06, 0.11, 0.19), DARK)
    box(s, 'hook back', (-0.06, 0.11, -0.10), (0.06, 0.40, -0.01), DARK)
    box(s, 'hook shank', (-0.055, 0.40, -0.075), (0.055, 0.62, 0.025), METALD)
    cyl(s, 'hook nut', (0, 0.60, -0.025), (0, 0.66, -0.025), 0.10, METAL, 10)
    box(s, 'hook safety latch', (-0.02, 0.17, 0.19), (0.02, 0.23, 0.08), AMBER)
    # block: cheek plates and two sheaves for the two falls of rope
    for x in (-0.30, 0.26):
        box(s, 'cheek plate', (x, 0.60, -0.17), (x + 0.04, 1.03, 0.17), ORG)
    box(s, 'block centre plate', (-0.28, 0.62, -0.06), (0.28, 0.94, 0.06), ORGD)
    for x in (-0.2, 0.2):
        cyl(s, 'sheave', (x - 0.07, 0.88, 0), (x + 0.07, 0.88, 0), 0.17, METALD, 16)
        cyl(s, 'sheave hub', (x - 0.12, 0.88, 0), (x + 0.12, 0.88, 0), 0.05, METAL, 8)
    box(s, 'block cap', (-0.30, 0.96, -0.17), (0.30, 1.05, 0.17), ORG)
    box(s, 'block weight', (-0.30, 0.58, -0.17), (0.30, 0.64, 0.17), ORG)
    for x in (-0.2, 0.2):
        cyl(s, 'rope socket', (x, 1.03, 0), (x, 1.10, 0), 0.04, METAL, 8)
    r = part('rope')
    box(r, 'rope', (-0.02, 0, -0.02), (0.02, 1, 0.02), ROPE)


# ------------------------------------------------------------------ strongback
def build_strongback():
    s = part('strongback_hook')
    # lifting ring on the hook, two pendant chains down to the middle of the spreader (0.55 below)
    path = [(0.16 * math.cos(i * math.tau / 10), -0.20 + 0.16 * math.sin(i * math.tau / 10), 0.0) for i in range(11)]
    sweep(s, 'lifting ring', path, 0.032, METAL, 6)
    for sx in (-1, 1):
        beam(s, 'pendant', (sx * 0.05, -0.34, 0), (sx * 0.20, -0.52, 0), 0.05, 0.03, DARK, side=(0, 0, 1))
    box(s, 'pendant plate', (-0.26, -0.56, -0.07), (0.26, -0.50, 0.07), YELD)
    box(s, 'hook bow', (-0.05, -0.05, -0.05), (0.05, 0.0, 0.05), DARK)
    sp = part('spreader')
    k = math.sqrt(0.5)
    for ang in (45.0, 135.0):
        a = math.radians(ang)
        d = (math.cos(a), math.sin(a))
        beam(sp, 'spreader beam', (-d[0], -0.62, -d[1]), (d[0], -0.62, d[1]), 0.045, 0.14, YEL, side=(-d[1], 0, d[0]))
        for sgn in (-1, 1):
            box(sp, 'spreader shackle', (sgn * d[0] - 0.03, -0.74, sgn * d[1] - 0.03), (sgn * d[0] + 0.03, -0.62, sgn * d[1] + 0.03), METALD)
    box(sp, 'spreader boss', (-0.05, -0.64, -0.05), (0.05, -0.50, 0.05), YELD)
    sl = part('sling')
    box(sl, 'sling', (-0.5, 0, -0.5), (0.5, 1, 0.5), CABLE)


# ------------------------------------------------------------------ head stand cradle
def build_cradle():
    s = part('stand_cradle')
    n = 24
    ro, ri = 1.0, 0.86
    y0, y1 = 0.78, 1.0
    vs = []
    for y in (y0, y1):
        for r in (ro, ri):
            for i in range(n):
                a = i * math.tau / n
                vs.append((r * math.cos(a), y, r * math.sin(a)))
    # rings: 0 = bottom outer, 1 = bottom inner, 2 = top outer, 3 = top inner
    def ix(ring, i):
        return ring * n + i % n
    fs = []
    for i in range(n):
        fs.append((ix(0, i), ix(0, i + 1), ix(2, i + 1), ix(2, i)))        # outer wall
        fs.append((ix(1, i + 1), ix(1, i), ix(3, i), ix(3, i + 1)))        # inner wall
        fs.append((ix(2, i), ix(2, i + 1), ix(3, i + 1), ix(3, i)))        # top
        fs.append((ix(0, i + 1), ix(0, i), ix(1, i), ix(1, i + 1)))        # bottom
    mesh_object(s, 'cradle ring', vs, fs, YELD)
    for k in range(4):
        a = math.radians(45 + 90 * k)
        c, sn = math.cos(a), math.sin(a)
        t = (-sn, c)
        beam(s, 'cradle leg', (0.935 * c, 0.0, 0.935 * sn), (0.935 * c, y0 + 0.05, 0.935 * sn), 0.07, 0.07, YEL, side=(t[0], 0, t[1]))
        box(s, 'cradle foot', (0.935 * c - 0.09, 0.0, 0.935 * sn - 0.09), (0.935 * c + 0.09, 0.03, 0.935 * sn + 0.09), DARK)


# ------------------------------------------------------------------ stud tensioner tool
def build_tool():
    s = part('tensioner_tool')
    cyl(s, 'tool skirt', (0, 0, 0), (0, 0.5, 0), 2.6, DARK, 16)
    cyl(s, 'tool body', (0, 0.5, 0), (0, 3.2, 0), 2.2, ORG, 16)
    cyl(s, 'tool band', (0, 1.6, 0), (0, 1.9, 0), 2.28, ORGD, 16)
    cyl(s, 'puller', (0, 3.2, 0), (0, 4.6, 0), 1.7, YEL, 16)
    cyl(s, 'puller cap', (0, 4.6, 0), (0, 5.0, 0), 1.45, METALD, 16)
    cyl(s, 'tool fitting', (0, 5.0, 0), (0, 5.6, 0), 0.8, METAL, 10)
    box(s, 'tool drive', (1.8, 0.9, -0.9), (3.6, 2.8, 0.9), CABD)
    cyl(s, 'drive wheel', (2.2, 0.55, -1.05), (2.2, 0.55, 1.05), 0.55, DARK, 10)
    cyl(s, 'hose fitting', (3.6, 1.8, 0), (4.1, 1.8, 0), 0.4, BLUEP, 8)
    box(s, 'tool handle', (-3.4, 3.6, -0.25), (-1.2, 4.0, 0.25), DARK)
    box(s, 'tool handle post', (-1.4, 3.0, -0.25), (-1.0, 4.0, 0.25), DARK)


# ------------------------------------------------------------------ export
def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def export_scene(scene, filename):
    faces, boxes, mats = [], [], {}
    for o in scene.objects:
        if o.type != 'MESH':
            continue
        me = o.data
        me.calc_loop_triangles()
        points = [game(o.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in points) for a in range(3)] + [max(p[a] for p in points) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            key = m.name
            if key not in mats:
                if 'texture' in m:
                    mats[key] = {'color': [1, 1, 1], 'texture': m['texture'], 'tint': -1}
                else:
                    mats[key] = {'color': [srgb(x) for x in m.diffuse_color[:3]], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
            vs = [list(points[i]) + list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3]) - Vector(vs[0][:3])).cross(Vector(vs[2][:3]) - Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
    (OUT / filename).write_text(json.dumps({'faces': faces, 'boxes': boxes, 'materials': mats}, separators=(',', ':')), encoding='utf-8')
    return len(faces)




# ------------------------------------------------------------------ review scene
def review(span=12, lowered=4.0):
    """The parts assembled for a 12 m span, the trolley 4 m from the first rail, the hook lowered a little with the head's strongback on it."""
    r = new_scene('BWR | crane review')

    def place(name, loc=(0, 0, 0), scale=(1, 1, 1), rot=0.0):
        for src in PARTS[name].objects:
            o = src.copy()
            o.data = src.data
            r.collection.objects.link(o)
            o.scale = (scale[0], scale[2], scale[1])
            o.location = xyz(loc)
            o.rotation_euler = (0, 0, math.radians(rot))
            yield o

    # the runway: two rail blocks' worth of beam under each end carriage
    for z in (0, span):
        box(r, 'runway beam', (-6, 0, z - 0.19), (6, 0.81, z + 0.19), METAL)
    list(place('end_carriage', (0, 0, 0)))
    list(place('end_carriage', (0, 0, span), rot=180))
    list(place('girder_plain', (0, 0, 0), (1, 1, span)))
    for k in range(span):
        list(place('girder_tile', (0, 0, k)))
    list(place('cab'))
    zt = 5.0
    list(place('trolley', (0, 0, zt)))
    tip = 1.0 - lowered
    ropes_top = ROPE_TOP
    for x in (-0.2, 0.2):
        list(place('rope', (x, tip + 1.05, zt), (1, ropes_top - (tip + 1.05), 1)))
    list(place('hook_block', (0, tip, zt)))
    list(place('strongback_hook', (0, tip, zt)))
    return r


def render_review(scene, name, eye, target_game, lens=30):
    cam = bpy.data.cameras.new('review camera ' + name)
    co = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(co)
    co.location = xyz(eye)
    target = xyz(target_game)
    co.rotation_euler = (target - co.location).to_track_quat('-Z', 'Y').to_euler()
    cam.lens = lens
    cam.clip_end = 200
    scene.camera = co
    if not scene.world:
        scene.world = bpy.data.worlds.new('review world')
        scene.world.color = (.42, .5, .58)
        sun = bpy.data.lights.new('review sun', 'SUN')
        so = bpy.data.objects.new('review sun', sun)
        scene.collection.objects.link(so)
        so.rotation_euler = (math.radians(55), 0, math.radians(35))
        scene.render.engine = 'BLENDER_WORKBENCH'
        scene.display.shading.light = 'STUDIO'
        scene.display.shading.color_type = 'MATERIAL'
        scene.render.resolution_x = 1600
        scene.render.resolution_y = 1000
        scene.render.image_settings.file_format = 'PNG'
    scene.render.filepath = str(OUT / ('building-crane' + name + '.png'))
    bpy.ops.render.render(write_still=True, scene=scene.name)


def main():
    build_end_carriage()
    build_girder_plain()
    build_girder_tile()
    build_cab()
    build_trolley()
    build_hook_block()
    build_strongback()
    build_cradle()
    build_tool()
    total = 0
    for name, scene in PARTS.items():
        n = export_scene(scene, name + '.json')
        total += n
        print('%-16s %5d triangles' % (name, n))
    print('all parts %d triangles' % total)
    r = review()
    if '--no-render' not in sys.argv:
        render_review(r, '', (-11.0, 9.0, -7.0), (0.0, 2.2, 6.0))
        render_review(r, '-end', (0.0, 5.0, -13.0), (0.0, 2.4, 5.0), 28)
        render_review(r, '-close', (5.5, 4.2, 1.8), (0.0, 1.8, 5.0), 40)
    bpy.data.libraries.write(str(OUT / 'building_crane.blend'), set(SCENES), fake_user=True)
    print('building crane parts written')


main()
