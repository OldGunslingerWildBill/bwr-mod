"""Blender-authored refuelling platform (the refuelling bridge) as parts. Run headless:

    blender.exe --background --python art/models/refuelling_bridge/build_models.py

Writes one <part>.json per part (mesh and UV data for tools-export-refuelling-bridge.py), refuelling_bridge.blend (the
editable part scenes plus a review scene of a 10 m span) and refuelling-bridge.png (a Workbench render of the review scene).

Game coordinates, one unit is one block (one metre): X is the way the bridge travels along the rails, Z is the span (from
the first rail to the second), Y is up, and y = 0 is the bottom of the rail block (the rail's top is at 0.3125). Every part
is drawn for a bridge that travels along X; the renderer turns it for rails along Z.

Parts (the renderer places them; see RefuellingBridgeRenderer):
  end_truck       origin on the rail centre line, built for the first rail with the girders going to +Z; the other end is the
                  same part turned half a turn. Wheeled carriage, two A-frames with X bracing, end beam, ladder, drive, cabinets.
  girder_tile     one metre of the girders along Z (stiffeners, deck outriggers, handrail post, skirt seam, cable festoon). Tiled
                  once per metre of span.
  girder_plain    one metre of everything plain and straight (two box girders, deck, rails, orange skirt, trolley rails),
                  stretched along Z to the span.
  deck_clutter    the control console, cabinets and a hose coil on the walkway, 0.35 m to 3.5 m from the first rail.
  plate           the generic maker's plate on the girder (textured, no brand), drawn at mid span.
  trolley         origin on the mast axis: bogie, deck, hangers, operator platform with yellow rails and a console, hoists, cabinets.
  reel            one orange cable reel, origin on its axle (axis X); the renderer turns it with the hoist.
  mast_outer      the fixed outer tube of the telescoping mast, from the trolley down to 1.32 m over the rails.
  mast_tube       a unit tube (radius 1, y 0..1) the renderer scales into each inner section.
  mast_collar     a unit ring (radius 1, y -0.04..0.04) at each joint.
  grapple_head    origin at the grapple tip (the top of the carried assembly's handle).
  grapple_jaw     one jaw, hinged at its origin; the renderer swings it open and shut (and turns it half a turn for the other).
  assembly        the fuel assembly hanging from the tip (textured like the old picture).
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
    m = bpy.data.materials.new('Bridge | ' + name)
    col = (1.0, 1.0, 1.0) if texture else tuple(lin(c) for c in rgb)
    m.diffuse_color = (*col, 1)
    m.metallic = metal
    m.roughness = 0.45
    if texture:
        m['texture'] = texture
    return m


YEL = material('safety yellow', (247, 176, 18))
YELD = material('yellow shade', (226, 150, 10))
BLUE = material('trolley blue', (26, 86, 172))
BLUED = material('trolley blue dark', (18, 60, 126))
ORG = material('shroud orange', (250, 104, 14))
ORGD = material('shroud seam', (214, 80, 8))
METAL = material('mast steel', (166, 174, 180), .6)
METALD = material('mast steel dark', (112, 120, 126), .6)
GALV = material('galvanised rail', (196, 202, 206), .7)
DARK = material('black steel', (28, 30, 34))
GRAT = material('deck grating', (64, 68, 72))
CAB = material('cabinet grey', (150, 156, 152))
CABD = material('console grey', (98, 106, 110))
SCREEN = material('screen glass', (14, 44, 50))
RED = material('stop button', (205, 30, 24))
AMBER = material('beacon', (250, 150, 20))
HOSE = material('duct yellow', (240, 200, 30))
CABLE = material('cable black', (20, 20, 22))
PLATE = material('maker plate', (255, 255, 255), texture='bwr:block/refuelling_bridge_plate')
ASM = material('fuel assembly', (255, 255, 255), texture='bwr:block/refuelling_assembly')
PLATEFRAME = material('plate frame', (60, 64, 68))


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
    s = new_scene('BWR | bridge ' + name)
    PARTS[name] = s
    return s


# ------------------------------------------------------------------ end truck
LEG_TOP_Y = 3.5          # girder underside
LEG_BOT_Y = 1.12         # sill top
GIRDER_TOP_Y = 4.7
STATIONS = (-.75, .75)   # the two A-frames of an end, one under each girder


def zhalf(y):
    """Half the width (along Z) of an A-frame at height y: .75 at the sill, .28 at the girders."""
    return .75 - (y - LEG_BOT_Y) / (LEG_TOP_Y - LEG_BOT_Y) * .47


def build_end_truck():
    s = part('end_truck')
    # carriage on two rails per block (the rail model's rails are at +-0.25 from the block centre, top at 0.3125)
    box(s, 'carriage plate', (-1.9, .72, -.42), (1.9, .98, .42), YEL)
    for x in (-1.3, 0, 1.3):
        cyl(s, 'axle', (x, .5125, -.44), (x, .5125, .44), .045, DARK, 8)
        for sz in (-1, 1):
            cyl(s, 'wheel', (x, .5125, sz * .25 - .07), (x, .5125, sz * .25 + .07), .2, DARK, 12)
            cyl(s, 'wheel hub', (x, .5125, sz * .25 - .085), (x, .5125, sz * .25 + .085), .07, METALD, 8)
    for sx in (-1, 1):
        box(s, 'buffer', (sx * 1.9, .76, -.2), (sx * 2.02, .94, .2), DARK)
    box(s, 'sill', (-2.1, .98, -.85), (2.1, LEG_BOT_Y, .85), YEL)
    for sz in (-1, 1):
        box(s, 'sill edge', (-2.1, LEG_BOT_Y, sz * .83), (2.1, LEG_BOT_Y + .06, sz * .85), YELD)
    # A-frames with X bracing
    for x in STATIONS:
        for sz in (-1, 1):
            beam(s, 'a-frame leg', (x, LEG_BOT_Y, sz * .75), (x, LEG_TOP_Y + .05, sz * .27), .30, .22, YEL, side=(1, 0, 0))
        y0, y1 = 1.4, 3.0
        beam(s, 'x brace', (x, y0, -zhalf(y0)), (x, y1, zhalf(y1)), .14, .12, YEL, side=(1, 0, 0))
        beam(s, 'x brace', (x, y0, zhalf(y0)), (x, y1, -zhalf(y1)), .14, .12, YEL, side=(1, 0, 0))
        for y in (y0, y1):
            beam(s, 'frame tie', (x, y, -zhalf(y)), (x, y, zhalf(y)), .16, .14, YEL, side=(1, 0, 0))
        box(s, 'brace gusset', (x - .09, 2.0, -.2), (x + .09, 2.4, .2), YELD)
    # portal bracing between the two A-frames
    for (ya, yb) in ((1.4, 3.0), (3.0, 1.4)):
        beam(s, 'portal brace', (STATIONS[0], ya, 0), (STATIONS[1], yb, 0), .12, .12, YEL, side=(0, 0, 1))
    # the end beam the girders land on
    box(s, 'end beam', (-1.05, LEG_TOP_Y, -.3), (1.05, GIRDER_TOP_Y, .3), YEL)
    box(s, 'end beam cap', (-1.1, GIRDER_TOP_Y, -.34), (1.1, GIRDER_TOP_Y + .05, .34), YELD)
    for x in (-1.1, 1.1):
        box(s, 'end beam bracket', (x - .03, LEG_TOP_Y - .1, -.34), (x + .03, GIRDER_TOP_Y, .34), YELD)
    cyl(s, 'beacon', (0, GIRDER_TOP_Y + .05, 0), (0, GIRDER_TOP_Y + .2, 0), .07, AMBER, 10)
    # ladder up the outer face to the walkway
    for x in (-1.85, -1.45):
        box(s, 'ladder rail', (x - .025, LEG_BOT_Y, -.42), (x + .025, 4.35, -.37), BLUE)
    for k in range(11):
        y = 1.35 + k * .3
        box(s, 'ladder rung', (-1.85, y - .015, -.43), (-1.45, y + .015, -.36), GALV)
    # drive motor, gearbox, cabinets on the sill
    cyl(s, 'drive motor', (-1.45, 1.3, .15), (-1.45, 1.3, .72), .17, DARK, 12)
    box(s, 'gearbox', (-1.68, LEG_BOT_Y, -.12), (-1.22, 1.52, .15), CABD)
    box(s, 'drive cabinet', (1.2, LEG_BOT_Y, -.8), (1.82, 2.15, -.4), CAB)
    box(s, 'cabinet door', (1.2, 1.2, -.402), (1.82, 2.08, -.39), CABD)
    box(s, 'cabinet handle', (1.7, 1.55, -.39), (1.76, 1.72, -.37), DARK)
    box(s, 'junction box', (-1.8, LEG_BOT_Y, .3), (-1.25, 1.85, .78), CAB)
    box(s, 'junction door', (-1.8, 1.2, .78), (-1.25, 1.78, .79), CABD)
    box(s, 'stop button box', (1.95, 1.2, -.3), (2.05, 1.45, -.1), YELD)
    cyl(s, 'stop button', (2.05, 1.32, -.2), (2.09, 1.32, -.2), .035, RED, 8)


# ------------------------------------------------------------------ girders
GA = (-1.0, -.5)   # girder A (walkway side) x range
GB = (.5, 1.0)
DECK_TOP = 3.82
RAIL_X = -2.05     # handrail line of the walkway


def build_girder_plain():
    s = part('girder_plain')
    box(s, 'girder A', (GA[0], 3.5, 0), (GA[1], GIRDER_TOP_Y, 1), YEL)
    box(s, 'girder B', (GB[0], 3.5, 0), (GB[1], GIRDER_TOP_Y, 1), YEL)
    for gx in (GA, GB):
        cx = (gx[0] + gx[1]) / 2
        box(s, 'trolley rail', (cx - .07, GIRDER_TOP_Y, 0), (cx + .07, GIRDER_TOP_Y + .08, 1), DARK)
        box(s, 'girder lower flange', (gx[0] - .03, 3.47, 0), (gx[1] + .03, 3.5, 1), YELD)
        box(s, 'girder upper flange', (gx[0] - .03, GIRDER_TOP_Y, 0), (gx[1] + .03, GIRDER_TOP_Y + .02, 1), YELD)
    box(s, 'walkway deck', (RAIL_X, DECK_TOP - .06, 0), (GA[0], DECK_TOP, 1), GRAT)
    box(s, 'walkway edge beam', (RAIL_X - .04, 3.62, 0), (RAIL_X + .04, DECK_TOP - .06, 1), YEL)
    box(s, 'toe plate', (RAIL_X - .02, DECK_TOP, 0), (RAIL_X + .02, DECK_TOP + .12, 1), YEL)
    for y in (4.87, 4.35):
        cyl(s, 'handrail', (RAIL_X, y, 0), (RAIL_X, y, 1), .022, GALV, 8)
    box(s, 'orange skirt', (RAIL_X - .052, 3.98, 0), (RAIL_X - .027, 4.80, 1), ORG)
    box(s, 'festoon track', (GA[1], 4.52, 0), (GA[1] + .04, 4.57, 1), METALD)
    box(s, 'conduit tray', (GB[1], 4.45, 0), (GB[1] + .18, 4.52, 1), GRAT)


def build_girder_tile():
    s = part('girder_tile')
    for gx in (GA, GB):
        box(s, 'stiffener', (gx[0] - .035, 3.46, 0), (gx[1] + .035, GIRDER_TOP_Y + .03, .05), YELD)
    box(s, 'walkway outrigger', (RAIL_X, 3.52, .45), (GA[0], DECK_TOP - .06, .53), YEL)
    cyl(s, 'handrail post', (RAIL_X, DECK_TOP, .5), (RAIL_X, 4.87, .5), .024, GALV, 8)
    box(s, 'skirt seam', (RAIL_X - .056, 3.98, 0), (RAIL_X - .027, 4.80, .015), ORGD)
    box(s, 'skirt tie', (RAIL_X - .06, 4.7, .485), (RAIL_X - .02, 4.76, .515), DARK)
    box(s, 'festoon clip', (GA[1], 4.49, 0), (GA[1] + .08, 4.57, .03), METALD)
    path = []
    for k in range(8):
        t = k / 7.0
        path.append((GA[1] + .075, 4.5 - .3 * 4 * t * (1 - t), .03 + .94 * t))
    sweep(s, 'festoon cable', path, .016, CABLE, 4)
    box(s, 'conduit clip', (GB[1], 4.43, .48), (GB[1] + .2, 4.54, .52), METALD)


def build_deck_clutter():
    s = part('deck_clutter')
    box(s, 'console desk', (-1.95, DECK_TOP, 1.1), (-1.4, 4.45, 1.9), CABD)
    beam(s, 'console slope', (-1.4, 4.47, 1.5), (-1.95, 4.84, 1.5), .8, .06, CAB, side=(0, 0, 1))
    box(s, 'monitor frame', (-1.93, 4.84, 1.2), (-1.87, 5.24, 1.8), DARK)
    box(s, 'monitor glass', (-1.875, 4.88, 1.24), (-1.865, 5.2, 1.76), SCREEN)
    cyl(s, 'console stop button', (-1.62, 4.62, 1.5), (-1.62, 4.67, 1.5), .04, RED, 8)
    for z in (1.25, 1.75):
        box(s, 'console key', (-1.7, 4.55, z - .05), (-1.62, 4.58, z + .05), GRAT)
    box(s, 'cabinet one', (-1.95, DECK_TOP, .36), (-1.45, 4.82, .96), CAB)
    box(s, 'cabinet one door', (-1.45, 3.9, .4), (-1.44, 4.74, .92), CABD)
    box(s, 'cabinet one handle', (-1.44, 4.25, .82), (-1.42, 4.4, .86), DARK)
    box(s, 'cabinet two', (-1.95, DECK_TOP, 2.1), (-1.5, 4.5, 2.55), CAB)
    box(s, 'cabinet two door', (-1.5, 3.9, 2.14), (-1.49, 4.42, 2.51), CABD)
    for k in range(3):
        ring = [(-1.5 + .26 * math.cos(i * math.tau / 12), DECK_TOP + .05 + k * .09, 3.2 + .26 * math.sin(i * math.tau / 12)) for i in range(13)]
        sweep(s, 'hose coil', ring, .04, HOSE, 4)


def build_plate():
    s = part('plate')
    hw, y0, y1, x = .8, 4.3, 4.7, 1.0
    box(s, 'plate backing', (x, y0 - .03, -hw - .03), (x + .004, y1 + .03, hw + .03), PLATEFRAME)
    # looking at it from +X: u grows towards -Z, v is up
    mesh_object(s, 'plate face', [(x + .006, y0, hw), (x + .006, y0, -hw), (x + .006, y1, -hw), (x + .006, y1, hw)], [(0, 1, 2, 3)], PLATE,
                uvs=[(0, 0), (1, 0), (1, 1), (0, 1)], fix=False)


# ------------------------------------------------------------------ trolley
DECK_Y = 5.16
PLATFORM_Y = 3.2


def build_trolley():
    s = part('trolley')
    for gx in (GA, GB):
        cx = (gx[0] + gx[1]) / 2
        box(s, 'bogie beam', (cx - .18, 4.84, -1.0), (cx + .18, 5.08, 1.0), BLUE)
        for sz in (-1, 1):
            for side in (-1, 1):
                x = cx + side * .21
                cyl(s, 'bogie wheel', (x - .03, 4.88, sz * .8), (x + .03, 4.88, sz * .8), .1, DARK, 10)
            cyl(s, 'bogie axle', (cx - .23, 4.88, sz * .8), (cx + .23, 4.88, sz * .8), .03, METALD, 6)
    box(s, 'trolley deck', (-1.15, 5.08, -1.05), (2.25, DECK_Y, 1.05), BLUE)
    box(s, 'deck edge', (-1.17, 5.0, -1.07), (2.27, 5.08, -1.03), BLUED)
    box(s, 'deck edge', (-1.17, 5.0, 1.03), (2.27, 5.08, 1.07), BLUED)
    # hangers and bracing for the operator platform
    for x in (1.15, 2.15):
        for sz in (-1, 1):
            box(s, 'hanger', (x - .06, PLATFORM_Y, sz * .9 - .06), (x + .06, 5.08, sz * .9 + .06), BLUE)
    for sz in (-1, 1):
        beam(s, 'hanger brace', (1.15, 3.4, sz * .9), (2.15, 4.8, sz * .9), .06, .06, BLUED, side=(0, 0, 1))
        beam(s, 'hanger brace', (2.15, 3.4, sz * .9), (1.15, 4.8, sz * .9), .06, .06, BLUED, side=(0, 0, 1))
    # the platform the operator stands on, and the arm that carries the mast guide
    box(s, 'platform floor', (1.05, PLATFORM_Y - .08, -.95), (2.2, PLATFORM_Y, .95), BLUED)
    box(s, 'platform grating', (1.07, PLATFORM_Y, -.93), (2.18, PLATFORM_Y + .015, .93), GRAT)
    box(s, 'guide arm', (.25, PLATFORM_Y - .08, -.3), (1.05, PLATFORM_Y, .3), BLUED)
    sleeve(s, 'mast guide ring', (0, 3.0, 0), (0, 3.3, 0), .3, .2, BLUE, 16)
    for sz in (-1, 1):
        box(s, 'platform toe board', (1.05, PLATFORM_Y, sz * .95 - .02), (2.2, PLATFORM_Y + .12, sz * .95 + .02), YEL)
    box(s, 'platform toe board', (2.18, PLATFORM_Y, -.95), (2.22, PLATFORM_Y + .12, .95), YEL)
    top, mid = PLATFORM_Y + 1.1, PLATFORM_Y + .55
    for y in (top, mid):
        for sz in (-1, 1):
            cyl(s, 'platform rail', (1.05, y, sz * .95), (2.2, y, sz * .95), .024, YEL, 6)
        cyl(s, 'platform rail', (2.2, y, -.95), (2.2, y, .95), .024, YEL, 6)
    for (px, pz) in ((1.05, -.95), (1.05, .95), (1.6, -.95), (1.6, .95), (2.2, -.95), (2.2, .95), (2.2, 0)):
        cyl(s, 'platform post', (px, PLATFORM_Y, pz), (px, top, pz), .024, YEL, 6)
    # operator console
    box(s, 'op console', (1.78, PLATFORM_Y, -.36), (2.1, 3.95, .36), CABD)
    beam(s, 'op console slope', (1.78, 3.97, 0), (2.08, 4.25, 0), .72, .05, CAB, side=(0, 0, 1))
    box(s, 'op screen frame', (2.02, 4.25, -.3), (2.08, 4.6, .3), DARK)
    box(s, 'op screen', (2.0, 4.28, -.26), (2.02, 4.57, .26), SCREEN)
    cyl(s, 'op stop button', (1.9, 4.08, .2), (1.9, 4.12, .2), .035, RED, 8)
    # reel stands and axles (the reels are their own part)
    for sz in (-1, 1):
        zc = sz * .55
        for x in (.78, 1.18):
            box(s, 'reel stand', (x, DECK_Y, zc - .14), (x + .04, DECK_Y + .75, zc + .14), BLUED)
        cyl(s, 'reel axle', (.78, DECK_Y + .55, zc), (1.22, DECK_Y + .55, zc), .045, METALD, 8)
    # hoists, mast head, cabinets
    box(s, 'aux hoist', (-1.05, DECK_Y, -.4), (-.45, DECK_Y + .62, .4), YEL)
    cyl(s, 'aux hoist drum', (-.75, DECK_Y + .8, -.36), (-.75, DECK_Y + .8, .36), .2, METAL, 12)
    box(s, 'aux hoist yoke', (-.85, DECK_Y + .62, -.3), (-.65, DECK_Y + .8, .3), YELD)
    box(s, 'mast head', (-.3, DECK_Y, -.3), (.3, DECK_Y + .42, .3), CABD)
    cyl(s, 'mast head motor', (-.3, DECK_Y + .22, 0), (-.62, DECK_Y + .22, 0), .13, DARK, 10)
    box(s, 'deck cabinet', (-1.1, DECK_Y, .5), (-.65, DECK_Y + .8, .95), CAB)
    box(s, 'deck cabinet door', (-.65, DECK_Y + .08, .54), (-.64, DECK_Y + .72, .91), CABD)
    box(s, 'deck cabinet', (-1.1, DECK_Y, -.95), (-.65, DECK_Y + .65, -.5), CAB)
    # hoist ropes from the reels to the mast head (fixed lines)
    for sz in (-1, 1):
        cyl(s, 'hoist rope', (.84, DECK_Y + 1.05, sz * .55), (.04, DECK_Y + .42, sz * .12), .014, CABLE, 5)
    # handrails round the trolley deck
    rtop, rmid = DECK_Y + 1.05, DECK_Y + .55
    xs, zs = (-1.1, .55, 2.2), (-1.0, 1.0)
    posts = [(x, z) for x in xs for z in zs] + [(-1.1, 0), (2.2, 0)]
    for (px, pz) in posts:
        cyl(s, 'deck post', (px, DECK_Y, pz), (px, rtop, pz), .022, GALV, 6)
    for y in (rtop, rmid):
        for z in zs:
            cyl(s, 'deck rail', (-1.1, y, z), (2.2, y, z), .02, GALV, 6)
        for x in (-1.1, 2.2):
            cyl(s, 'deck rail', (x, y, -1.0), (x, y, 1.0), .02, GALV, 6)


def build_reel():
    s = part('reel')
    n = 16
    for sx in (-1, 1):
        cyl(s, 'reel flange', (sx * .14 - .015, 0, 0), (sx * .14 + .015, 0, 0), .5, ORG, n)
    cyl(s, 'reel core', (-.14, 0, 0), (.14, 0, 0), .26, ORGD, 12)
    cyl(s, 'reel winding', (-.12, 0, 0), (.12, 0, 0), .4, CABLE, n)
    for k in range(6):
        a = k * math.tau / 6
        c = (0, .44 * math.cos(a), .44 * math.sin(a))
        for sx in (-1, 1):
            box(s, 'reel lug', (sx * .14 + (.015 if sx > 0 else -.035), c[1] - .035, c[2] - .035), (sx * .14 + (.035 if sx > 0 else -.015), c[1] + .035, c[2] + .035), DARK)
    cyl(s, 'reel hub cap', (-.17, 0, 0), (.17, 0, 0), .06, METALD, 8)


# ------------------------------------------------------------------ mast and grapple
MAST_TOP = 5.35
MAST_BOTTOM = 1.32   # rail top + 1.0 (the retracted grapple tip) + 0.32 (the grapple head)


def build_mast():
    s = part('mast_outer')
    n = 16
    cyl(s, 'outer tube', (0, MAST_BOTTOM, 0), (0, MAST_TOP, 0), .2, METAL, n)
    cyl(s, 'outer lower flange', (0, MAST_BOTTOM, 0), (0, MAST_BOTTOM + .08, 0), .245, DARK, n)
    cyl(s, 'outer upper flange', (0, MAST_TOP - .08, 0), (0, MAST_TOP, 0), .245, DARK, n)
    for y in (2.4, 3.3, 4.2):
        cyl(s, 'depth band', (0, y, 0), (0, y + .05, 0), .206, YEL, n)
    box(s, 'mast head clamp', (-.26, MAST_TOP, -.26), (.26, MAST_TOP + .1, .26), METALD)
    t = part('mast_tube')
    cyl(t, 'unit tube', (0, 0, 0), (0, 1, 0), 1.0, METAL, n)
    c = part('mast_collar')
    cyl(c, 'unit collar', (0, -.04, 0), (0, .04, 0), 1.0, METALD, n)


def build_grapple():
    s = part('grapple_head')
    n = 16
    cyl(s, 'grapple body', (0, 0, 0), (0, .32, 0), .21, METALD, n)
    cyl(s, 'grapple lower ring', (0, 0, 0), (0, .035, 0), .225, YEL, n)
    cyl(s, 'grapple neck', (0, .32, 0), (0, .4, 0), .13, METAL, n)
    for sx in (-1, 1):
        box(s, 'hinge block', (sx * .09 - .05, -.05, -.13), (sx * .09 + .05, .06, .13), YEL)
    box(s, 'grapple lamp', (-.05, .1, .2), (.05, .2, .26), AMBER)
    j = part('grapple_jaw')
    box(j, 'jaw plate', (.06, -.34, -.2), (.1, 0, .2), DARK)
    box(j, 'jaw lip', (.0, -.34, -.2), (.1, -.29, .2), DARK)
    cyl(j, 'jaw hinge', (.08, 0, -.15), (.08, 0, .15), .035, METALD, 8)
    f = part('assembly')
    face = [(0, 0), (1, 0), (1, 1), (0, 1)]
    box(f, 'handle bale', (-.06, -.34, -.06), (.06, 0, .06), ASM, face)
    box(f, 'top plate', (-.2, -.42, -.2), (.2, -.34, .2), ASM, face)
    box(f, 'bundle', (-.18, -4.4, -.18), (.18, -.42, .18), ASM, face)
    for y in (-1.2, -2.4, -3.6):
        box(f, 'spacer grid', (-.2, y, -.2), (.2, y + .06, .2), ASM, face)
    box(f, 'lower nozzle', (-.12, -4.5, -.12), (.12, -4.4, .12), ASM, face)


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
def review(span=10, lowered=5.0):
    """The parts assembled for a 10 m span, trolley 3.2 m from the first rail, mast lowered a little, assembly on the grapple."""
    r = new_scene('BWR | bridge review')

    def place(name, loc=(0, 0, 0), scale=(1, 1, 1), rot=0.0):
        for src in PARTS[name].objects:
            o = src.copy()
            o.data = src.data
            r.collection.objects.link(o)
            o.scale = (scale[0], scale[2], scale[1])
            loc_b = xyz(loc)
            o.location = loc_b
            o.rotation_euler = (0, 0, math.radians(rot))
            if rot:
                # rotate about the part origin: scale first, then rotate, then move
                pass
            yield o

    list(place('end_truck', (0, 0, 0)))
    list(place('end_truck', (0, 0, span), rot=180))
    list(place('girder_plain', (0, 0, 0), (1, 1, span)))
    for k in range(span):
        list(place('girder_tile', (0, 0, k)))
    list(place('deck_clutter'))
    list(place('plate', (0, 0, span / 2)))
    zt = 3.2
    list(place('trolley', (0, 0, zt)))
    for sz in (-1, 1):
        list(place('reel', (1.0, DECK_Y + .55, zt + sz * .55)))
    list(place('mast_outer', (0, 0, zt)))
    tip = 1.0 - lowered
    ext = 1.0 - tip
    radii = (.17, .14, .11)
    for k, rad in enumerate(radii):
        top = MAST_BOTTOM - ext * k / 3.0 + .02
        bottom = MAST_BOTTOM - ext * (k + 1) / 3.0
        list(place('mast_tube', (0, bottom, zt), (rad, top - bottom, rad)))
        list(place('mast_collar', (0, bottom, zt), (rad + .025, 1, rad + .025)))
    list(place('grapple_head', (0, tip, zt)))
    list(place('grapple_jaw', (0, tip, zt)))
    list(place('grapple_jaw', (0, tip, zt), rot=180))
    list(place('assembly', (0, tip, zt)))
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
    scene.render.filepath = str(OUT / ('refuelling-bridge' + name + '.png'))
    bpy.ops.render.render(write_still=True, scene=scene.name)


def main():
    build_end_truck()
    build_girder_plain()
    build_girder_tile()
    build_deck_clutter()
    build_plate()
    build_trolley()
    build_reel()
    build_mast()
    build_grapple()
    total = 0
    for name, scene in PARTS.items():
        n = export_scene(scene, name + '.json')
        total += n
        print('%-14s %5d triangles' % (name, n))
    print('all parts %d triangles' % total)
    r = review()
    if '--no-render' not in sys.argv:
        render_review(r, '', (-9.5, 8.0, -6.5), (.4, 2.9, 4.6))
        render_review(r, '-platform', (11.0, 7.5, -4.0), (1.0, 3.5, 4.0))
        render_review(r, '-end', (0.0, 5.5, -13.0), (0.0, 2.8, 3.0), 28)
    bpy.data.libraries.write(str(OUT / 'refuelling_bridge.blend'), set(SCENES), fake_user=True)
    print('refuelling bridge parts written')


main()
