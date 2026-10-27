"""Blender 5: the wrecked-turbine parts (TURBINE-MAINTENANCE.md), metres = blocks, in the modules' own coordinates.

Headless:  blender --background --factory-startup --python art/models/power_turbines/build_wreck.py
then       python tools-export-turbine-wreck.py   (bakes each part into an OBJ model the renderer draws)

Writes art/models/power_turbines/wreck/*.json and turbine_wreck.blend. Generic and brand-free; game scale.

The parts, drawn by TurbineHallRenderers over (or in place of) the baked module:
  * lp_hood_burst   an LP's hood (everything at or above the joint, y >= 3) burst open: ragged end and side walls, roof plates
                    torn off and flapped up, scorched edges. The hood cells are open (drawn empty) while the casing is burst.
  * lp_end_blown_a  the lower casing where a shaft excursion's rotor half left at flow A's end (towards z = 0): a ragged hole
                    through the right side wall and the end corner with its plates bent out, a knocked bearing cap and the
                    sheared coupling stub. lp_end_blown_b is the same at flow B's end (mirrored in z).
  * tear_petal      a hole a thrown blade tore, in its own frame: the hole in the z = 0 plane, outward +z, petals bent out.
  * hp_upper_burst  an HP's casing above y = 3 torn away (its upper cells open while burst): jagged remains of the upper half,
                    the inlet chamber knocked over, a dark lining inside, and the bent, stripped rotor seen through it.
Coordinates as build_models.py: x right, y up, z along the shaft; an LP is 7 x 5 x 7, an HP 5 x 5 x 9; the shaft at y = 2.5.
"""
from pathlib import Path
import math
ROOT = Path(__file__).resolve().parents[3]
# The modules' own primitives and materials (everything before the modules are built), so the wreck matches them.
src = (ROOT / 'art/models/power_turbines/build_models.py').read_text()
exec(compile(src.split('MODULES=[build_hp()')[0], str(ROOT / 'art/models/power_turbines/build_models.py'), 'exec'))
WRECK = OUT / 'wreck'
WRECK.mkdir(parents=True, exist_ok=True)
SCORCH = material('scorched steel', (.075, .068, .062), .2)
RUST = material('heat-rusted steel', (.34, .20, .12), .3)
HOLE = material('torn opening', (.012, .012, .014))


def obox(s, name, centre, size, rot, mat):
    """A box of the given size about its centre, turned by the Euler angles (degrees, about x then y then z)."""
    hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
    R = (Matrix.Rotation(math.radians(rot[2]), 3, 'Z') @ Matrix.Rotation(math.radians(rot[1]), 3, 'Y') @ Matrix.Rotation(math.radians(rot[0]), 3, 'X'))
    c = Vector(centre)
    vs = [tuple(c + R @ Vector((x, y, z))) for z in (-hz, hz) for y in (-hy, hy) for x in (-hx, hx)]
    # corners in the order box() uses
    order = [vs[0], vs[1], vs[3], vs[2], vs[4], vs[5], vs[7], vs[6]]
    return mesh_object(s, name, order, [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (3, 7, 6, 2), (0, 4, 7, 3), (1, 2, 6, 5)], mat)


def hinged(s, name, hinge, length, width, thick, axis, angle, along, mat, outward):
    """A plate hinged on a line through {@code hinge} along {@code axis}: before it is bent it runs along {@code along}; it is
    turned {@code angle} degrees about the hinge, whichever way takes it towards {@code outward}. Its width runs along the hinge."""
    a, t = Vector(along).normalized(), Vector(axis).normalized()
    d = (Matrix.Rotation(math.radians(angle), 3, t) @ a).normalized()
    if d.dot(Vector(outward)) < 0:
        d = (Matrix.Rotation(math.radians(-angle), 3, t) @ a).normalized()
    w = t * (width / 2)
    n = d.cross(t).normalized() * (thick / 2)
    h = Vector(hinge)
    p = [h - w - n, h + w - n, h + w + n, h - w + n]
    q = [x + d * length for x in p]
    return mesh_object(s, name, [tuple(v) for v in p + q], [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)], mat)


def jag(i, lo, hi):
    """A repeatable ragged height between lo and hi for the i-th strip."""
    k = (math.sin(i * 12.9898 + 4.1) * 43758.5453) % 1.0
    return lo + (hi - lo) * k


def export(scene, name):
    meta = {'id': name, 'authoring': 'Blender 5 headless; wrecked-turbine parts, generic; game scale, not a CAD replica'}
    export_scene(scene, 'wreck/' + name + '.json', meta)


# ------------------------------------------------------------------ an LP hood burst open
def torn_wall(s, name, axis, u0, u1, w0, w1, top, mat, seed, step=.3):
    """A wall plate standing on the joint (y = 3) from u0 to u1 in the plane across {@code axis}, w0 to w1 thick, its top edge torn:
    a zigzag under {@code top(u)}, with a scorched band along it."""
    n = max(2, int(round((u1 - u0) / step)))
    edge = []
    for k in range(n + 1):
        u = u0 + (u1 - u0) * k / n
        dip = .04 if k % 2 == 0 else .12 + .3 * jag(k + seed, 0, 1)
        edge.append((u, max(3.1, top(u) - dip)))
    extrude(s, name, [(u0, 3.0), (u1, 3.0)] + edge[::-1], axis, w0, w1, mat)
    band = edge[::-1] + [(u, max(3.05, v - .07)) for u, v in edge]
    extrude(s, name + ' scorch', band, axis, w0 - .012, w1 + .012, SCORCH)
    return edge


def build_lp_hood_burst():
    s = new_scene('BWR wreck | lp_hood_burst')
    w, depth = 7, 7
    E0, E1 = .56, depth - .56
    # the upper joint flanges stay bolted down, one side lifted and buckled
    box(s, 'upper joint flange', (.08, 3.0, .44), (.40, 3.20, depth - .44), STEEL)
    for i, (z0, z1) in enumerate(((.44, 2.2), (2.2, 4.4), (4.4, depth - .44))):
        obox(s, 'buckled joint flange', (w - .24, 3.12 + .06 * i, (z0 + z1) / 2), (.32, .2, z1 - z0), (0, 0, -6 - 5 * i), STEEL)
    # the end walls: whole up the corners where the frame held, torn down towards the middle where the burst went through
    def end_top(seed):
        return lambda x: 3.25 + (lp_hood_top(min(max(x, .3), w - .3)) - 3.25) * min(1, (abs(x - 3.5) / 2.6) ** 1.6 + .25 * jag(int(x * 7) + seed, 0, 1))
    for zw0, zw1, seed in ((E0, .70, 3), (depth - .70, E1, 17)):
        torn_wall(s, 'torn end wall', 'z', .27, w - .27, zw0, zw1, end_top(seed), HOOD, seed)
    # the side walls: torn along their length, standing tallest by the end walls
    def side_top(seed):
        return lambda z: 3.35 + .8 * min(1, (abs(z - 3.5) / 2.9) ** 2 + .35 * jag(int(z * 5) + seed, 0, 1))
    for xw, seed in ((.30, 5), (w - .38, 29)):
        edge = torn_wall(s, 'torn side wall', 'x', .62, depth - .62, xw, xw + .08, side_top(seed), HOOD, seed)
        # two plates of it peeled outwards off the torn edge
        side = -1 if xw < 1 else 1
        for zc, ang in ((2.3, 42), (4.6, 55)):
            y = min(v for u, v in edge if abs(u - zc) < .4)
            hinged(s, 'peeled side plate', (xw + (.08 if side > 0 else 0), y - .02, zc), .62, .7, .07, (0, 0, 1), ang, (0, 1, 0), HOOD, (side, 0, 0))
    # a roof plate still hanging from the far end wall, folded back over it, and a shoulder plate curled out at the near end
    hinged(s, 'folded roof plate', (3.5, end_top(17)(3.5) - .05, E1 - .02), 1.4, 1.9, .08, (1, 0, 0), 115, (0, 0, -1), HOOD, (0, .3, 1))
    hinged(s, 'curled shoulder plate', (1.0, end_top(3)(1.0) - .05, .9), .8, .9, .07, (0, 0, 1), 65, (1, .5, 0), RUST, (-1, 1, 0))
    # fragments of the inner casing's upper half over the rotor
    up = math.asin(.52 / 2.0)
    lathe(s, 'inner casing fragment', [(1.30, 2.13), (1.30, 2.25), (2.40, 2.25), (2.40, 2.13)], CASING, (3.5, 2.5), 6, up, up + .7)
    lathe(s, 'inner casing fragment', [(4.6, 2.13), (4.6, 2.25), (5.70, 2.25), (5.70, 2.13)], CASING, (3.5, 2.5), 6, math.pi - up - .55, math.pi - up)
    # a torn stub of the crossover inlet pipe, hanging from what is left of its pad
    cylinder(s, 'torn crossover inlet stub', (3.5, 3.55, 3.5), (3.5, 4.1, 3.5), .36, SCORCH, 14)
    obox(s, 'crossover pipe fragment', (2.9, 3.4, 3.1), (.5, .14, .9), (0, 25, 18), CASING)
    export(s, 'lp_hood_burst')
    return s


# ------------------------------------------------------------------ the hole a rotor half broke out through, flow A's end
def build_lp_end_blown():
    s = new_scene('BWR wreck | lp_end_blown_a')
    w = 7
    # the ragged opening in the right side wall, from the end corner back past the section's seat, just proud of the walers
    hole = [(.60, .55), (.95, .40), (1.6, .62), (2.2, .45), (2.9, .70), (3.55, 1.1), (3.4, 1.8), (3.6, 2.45), (2.8, 2.78),
            (2.1, 2.62), (1.4, 2.80), (.85, 2.55), (.62, 2.0), (.70, 1.2)]
    extrude(s, 'torn opening in the side wall', hole, 'x', w - .20, w - .17, HOLE)
    # and round the corner into the end wall
    corner = [(4.55, .6), (5.4, .45), (6.3, .7), (6.74, 1.1), (6.74, 2.6), (6.0, 2.82), (5.2, 2.55), (4.7, 2.0), (4.4, 1.3)]
    extrude(s, 'torn opening in the end wall', corner, 'z', .43, .46, HOLE)
    # plates round the openings' edges, bent out of the wall: hinged on the edge, first lying in the wall pointing away from the middle
    for i, (a, b) in enumerate(zip(hole, hole[1:] + hole[:1])):
        if i % 2:
            continue
        mz, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        away = Vector((0, my - 1.6, mz - 2.1)).normalized()
        edge = Vector((0, b[1] - a[1], b[0] - a[0])).normalized()
        hinged(s, 'side plate bent out', (w - .18, my, mz), .45 + .1 * (i % 3), .42, .06, tuple(edge), 55, tuple(away), HOOD, (1, 0, 0))
    for i, (a, b) in enumerate(zip(corner, corner[1:] + corner[:1])):
        if i % 2:
            continue
        mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        away = Vector((mx - 5.5, my - 1.6, 0)).normalized()
        edge = Vector((b[0] - a[0], b[1] - a[1], 0)).normalized()
        hinged(s, 'end plate bent out', (mx, my, .44), .4, .4, .06, tuple(edge), 50, tuple(away), HOOD, (0, 0, -1))
    # the bearing at this end knocked off its pedestal, its cap split
    obox(s, 'knocked bearing cap', (3.75, 2.62, .43), (1.0, .26, .3), (8, 0, -22), RIB)
    obox(s, 'split bearing cap half', (4.55, 2.15, .30), (.55, .2, .26), (0, 30, 48), RUST)
    # the sheared coupling: a stub with a ragged face, and its bolts snapped
    cylinder(s, 'sheared coupling stub', (3.5, 2.5, .30), (3.5, 2.5, .08), .34, STEEL, 16)
    for i in range(7):
        a = i * math.tau / 7
        r = .1 + .2 * ((i * 5) % 3) / 2
        cylinder(s, 'ragged shear face', (3.5 + r * math.cos(a), 2.5 + r * math.sin(a), .08), (3.5 + r * math.cos(a), 2.5 + r * math.sin(a), .02 + .04 * (i % 3)),
                 .09, SCORCH, 6)
    for z0, z1 in ((.62, 1.2), (1.6, 2.1)):
        obox(s, 'scorch streak', (w - .165, 2.9, (z0 + z1) / 2), (.02, .5, z1 - z0), (0, 0, 0), SCORCH)
    export(s, 'lp_end_blown_a')
    # flow B's end: the same mirrored in z
    b = new_scene('BWR wreck | lp_end_blown_b')
    for o in s.objects:
        me = o.data.copy()
        for v in me.vertices:
            v.co.y = -7 - v.co.y  # blender y is -z: z -> 7 - z
        bm = bmesh.new()
        bm.from_mesh(me)
        bmesh.ops.reverse_faces(bm, faces=bm.faces)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(me)
        bm.free()
        c = bpy.data.objects.new(o.name, me)
        b.collection.objects.link(c)
    export(b, 'lp_end_blown_b')
    return s


# ------------------------------------------------------------------ the hole a blade tore, in its own frame
def build_tear_petal():
    s = new_scene('BWR wreck | tear_petal')
    cylinder(s, 'torn hole', (0, 0, -.12), (0, 0, .025), .30, HOLE, 12)
    for i in range(7):
        a = i * math.tau / 7 + .2
        rim = Vector((math.cos(a) * .27, math.sin(a) * .27, .02))
        tangent = Vector((-math.sin(a), math.cos(a), 0))
        hinged(s, 'petal bent out', tuple(rim), .2 + .07 * (i % 3), .16, .04, tuple(tangent), 48 + 9 * (i % 2), (math.cos(a), math.sin(a), 0), HOOD, (0, 0, 1))
    cylinder(s, 'scorched rim', (0, 0, .004), (0, 0, .012), .46, SCORCH, 14)
    export(s, 'tear_petal')
    return s


# ------------------------------------------------------------------ an HP casing torn open, with its bent rotor
def build_hp_upper_burst():
    s = new_scene('BWR wreck | hp_upper_burst')
    cx, cy = 2.5, 2.5
    pts = HP_OUTLINE[2:-2]
    # a dark lining inside the lower half, so the open casing reads as a hollow, not as a cut
    low = math.asin(.5 / 1.3)
    lining = [(z, r - .1) for z, r in pts] + [(z, r - .17) for z, r in reversed(pts)]
    lathe(s, 'casing lining', lining, HOLE, (cx, cy), 28, math.pi - low, 2 * math.pi + low)
    # the jagged remains of the upper half: shell bands standing to torn angles on both sides of the joint
    for i, ((za, ra), (zb, rb)) in enumerate(zip(pts, pts[1:])):
        if zb <= za:
            continue
        for side in (0, 1):
            reach = .45 + .5 * ((i * 7 + side * 3) % 5) / 4  # radians above the joint plane
            base = math.asin(min(.99, .5 / max(ra, rb)))
            t0, t1 = (base, reach) if side == 0 else (math.pi - reach, math.pi - base)
            if t1 <= t0:
                continue
            shell_band = [(za, ra - .02), (za, ra + .06), (zb, rb + .06), (zb, rb - .02)]
            lathe(s, 'torn upper casing', shell_band, CASING if i % 3 else RUST, (cx, cy), 8, t0, t1)
            edge = t1 if side == 0 else t0
            zm, rm = (za + zb) / 2, (ra + rb) / 2
            obox(s, 'scorched tear edge', (cx + rm * math.cos(edge), cy + rm * math.sin(edge), zm), (.1, .1, max(.1, zb - za - .1)), (0, 0, math.degrees(edge)), SCORCH)
    # plates peeled back off the top
    for z, side, ang in ((2.4, -1, 55), (4.1, 1, 62), (5.9, -1, 48), (6.9, 1, 40)):
        x = cx + side * 1.15
        hinged(s, 'peeled casing plate', (x, 3.45, z), .9, .7, .07, (0, 0, 1), ang, (-side * .3, 1, 0), CASING, (side, .4, 0))
    # the inlet chamber knocked over onto the casing's shoulder, its steam leads torn off
    obox(s, 'knocked inlet chamber', (1.35, 3.55, 4.5), (1.6, .62, 1.3), (0, 8, 32), CASING)
    obox(s, 'inlet chamber cover', (1.05, 3.98, 4.5), (1.7, .1, 1.4), (0, 8, 32), STEEL)
    for x, z in ((3.3, 4.1), (3.4, 4.9), (1.7, 3.6)):
        cylinder(s, 'torn steam lead', (x, 3.0, z), (x + .1, 3.45, z + .05), .16, SCORCH, 8)
    # the rotor, bent and stripped: two drum lengths with a kink at the middle, discs with their blades sheared to stubs
    kink = Vector((cx, cy + .14, 4.5))
    for a in (Vector((cx, cy, 1.1)), Vector((cx, cy, 7.9))):
        cylinder(s, 'bent shaft', tuple(a), tuple(kink), .2, STEEL, 12)
        cylinder(s, 'rotor drum', tuple(a.lerp(kink, .2)), tuple(a.lerp(kink, .92)), .55, RUST, 16)
        for k, f in enumerate((.3, .48, .66, .84)):
            p = a.lerp(kink, f)
            q = p + (kink - a).normalized() * .1
            cylinder(s, 'stripped disc', tuple(p), tuple(q), 1.05 - .08 * k, STEEL if k % 2 else RUST, 18)
            for j in range(0, 12, 3 if k % 2 else 4):
                ang = j * math.tau / 12 + k
                r0 = 1.05 - .08 * k
                obox(s, 'blade stub', (p.x + (r0 + .1) * math.cos(ang), p.y + (r0 + .1) * math.sin(ang), p.z + .05), (.06, .24, .14),
                     (0, 0, math.degrees(ang) - 90), STEEL)
    export(s, 'hp_upper_burst')
    return s


PARTS = [build_lp_hood_burst(), build_lp_end_blown(), build_tear_petal(), build_hp_upper_burst()]
for o in list(bpy.data.objects):
    if o.users_scene and all(sc.name == 'Scene' for sc in o.users_scene):
        bpy.data.objects.remove(o)
bpy.ops.wm.save_as_mainfile(filepath=str(OUT / 'turbine_wreck.blend'), copy=True, compress=True)
print('Built the wrecked-turbine parts.')
