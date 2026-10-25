"""Cooling-tower remodel (alpha.54): hyperbolic natural-draft tower and Columbia-style round induced-draft tower.

    blender.exe --background --python art/models/cooling/build_towers_v2.py -- natural|mechanical|fan|lod|all

References are listed in COOLING-TOWERS-REFERENCE.md.  The occupancy (collision cells) of both blocks is NOT changed:
the `boxes` of alpha.53 are kept in occupancy_alpha53.json so placed towers, ports and saves are untouched.
Dimensions: natural 25 x 36 x 25, mechanical 17 x 8 x 17, ports and rotor pivots as before.
"""
import sys
from pathlib import Path
exec(compile((Path(__file__).resolve().parent / 'tower_kit.py').read_text(), 'tower_kit.py', 'exec'))

OCC = json.loads((OUT / 'occupancy_alpha53.json').read_text())
DIRS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0)}


def pol(cx, cz, r, t, y):
    return (cx + r * math.cos(t), y, cz + r * math.sin(t))


# ------------------------------------------------------------------------------------------------ shared ports
def water_port(k, role, cell, face, hot):
    """Flange, spool and identification band on a full-block flange centre (same geometry as alpha.53)."""
    d = Vector(DIRS[face])
    p = Vector(cell) + Vector((.5, .5, .5)) + d * .5
    g = role.lower() + ' port'
    k.sleeve(g, 'machined steel', p - d * .85, p - d * .12, .25, .205, 20)
    k.sleeve(g, 'hot band' if hot else 'cold band', p - d * .42, p - d * .31, .265, .245, 20)
    k.sleeve(g, 'machined steel', p - d * .12, p - d * .025, .40, .205, 24)
    k.sleeve(g, 'gasket', p - d * .025, p, .27, .205, 24)
    _, u, v = k.basis(d)
    for i in range(12):
        q = p + (u * math.cos(i * TAU / 12) + v * math.sin(i * TAU / 12)) * .324
        k.bolt(g, 'machined steel', q - d * .14, q - d * .006, .026)


def tower_pipes(k, w, d):
    c = w // 2
    g = 'tower piping'
    k.sleeve(g, 'machined steel', (c + .5, 2.5, .84), (c + .5, 2.5, d / 2), .25, .205, 20)
    k.sleeve(g, 'machined steel', (c + .5, 2.5, d / 2), (c + .5, 4.2, d / 2), .25, .205, 20)
    k.sleeve(g, 'machined steel', (c + .5, 1.5, d / 2), (c + .5, 1.5, d - .84), .25, .205, 20)
    k.sleeve(g, 'machined steel', (c + .5, .35, d / 2), (c + .5, 1.5, d / 2), .25, .205, 20)
    k.sleeve(g, 'machined steel', (.84, 1.5, d / 2), (w / 2, 1.5, d / 2), .25, .205, 20)


def tower_ports(k, w, d):
    c = w // 2
    water_port(k, 'INLET', (c, 2, 0), 'north', True)
    water_port(k, 'OUTLET', (c, 1, d - 1), 'south', False)
    water_port(k, 'MAKEUP', (0, 1, d // 2), 'west', False)


def cage_ladder(k, g, path, tang, width=.5, rung_dy=.38, cage_from=None, hoop_step=1.5, hoop_r=.38):
    """Vertical caged ladder. `path(y)` gives the (x, z) of the ladder centre line, `tang(y)` its horizontal unit
    tangent; radial (outward) is derived from it."""
    ys = []
    y = path['y0']
    while y <= path['y1'] + 1e-6:
        ys.append(y)
        y += rung_dy
    pts = []
    for y in ys:
        x, z = path['fn'](y)
        t = Vector(path['tangent'](y))
        pts.append((Vector((x, y, z)), t))
    for (p, t), (q, s) in zip(pts, pts[1:]):
        for sgn in (-1, 1):
            k.beam(g, 'steel', p + t * (width / 2 * sgn), q + s * (width / 2 * sgn), .045, .045)
    for p, t in pts:
        k.beam(g, 'steel', p - t * (width / 2), p + t * (width / 2), .03, .03)
    if cage_from is not None:
        prev = None
        for i, (p, t) in enumerate(pts):
            if p.y < cage_from or i % max(1, round(hoop_step / rung_dy)):
                continue
            out = Y.cross(t)          # radial direction (outwards)
            ring = [p + out * (hoop_r * math.sin(a * math.pi / 8)) + t * (hoop_r * math.cos(a * math.pi / 8)) for a in range(9)]
            for a, b in zip(ring, ring[1:]):
                k.beam(g, 'steel dark', a, b, .035, .035)
            mid = ring[4]
            if prev is not None:
                k.beam(g, 'steel dark', prev, mid, .04, .04)
            prev = mid


# ================================================================================================ natural draft tower
def natural_profile(y):
    """Outer shell radius: the alpha.53 curve, so the footprint and collision cells stay valid."""
    s = (y - 4) / 31.5
    return 6.1 + 4.7 * ((s - .68) / .68) ** 2


def build_natural():
    k = Kit('natural_draft_tower')
    c = 12.5
    N = 96
    TOP, BASE = 35.0, 4.0
    levels = [BASE + (TOP - BASE) * j / 28 for j in range(29)]

    def inner_t(y):
        return .22 + .33 * max(0.0, 1 - (y - BASE) / 12.0)

    # --- cast-in-place shell: 28 pour lifts, alternate lifts stepped 3.5 cm for visible construction joints
    for j in range(28):
        y0, y1 = levels[j], levels[j + 1]
        off = .035 if j % 2 == 0 else 0.0
        k.revolve('hyperbolic shell lift %02d' % j, lambda _, i, j=j: CONCRETES[(j * 2 + (i // 4) * 3) % 5],
                  [(natural_profile(y0) + off, y0), (natural_profile(y1) + off, y1)], c, c, N, 1)
        if j < 27:
            o_next = .035 if (j + 1) % 2 == 0 else 0.0
            a, b = natural_profile(y1) + off, natural_profile(y1) + o_next
            if abs(a - b) > 1e-6:
                k.disc('pour joint ledge', 'concrete joint', c, c, y1, min(a, b), max(a, b), N, up=(o_next > off))
    k.revolve('shell inner skin', 'concrete inner', [(natural_profile(y) - inner_t(y), y) for y in levels], c, c, N, -1)
    # top coping, rim handrail and aviation lights
    rin = natural_profile(TOP) - inner_t(TOP) - .02
    rout = natural_profile(TOP) + .12
    k.ring_box('top coping', 'concrete beam', c, c, rin, rout, TOP - .1, TOP + .32, N)
    rr = rout - .06
    for i in range(48):
        a = i * TAU / 48
        k.beam('rim handrail', 'steel', pol(c, c, rr, a, TOP + .32), pol(c, c, rr, a, TOP + .92), .06, .06)
        b = (i + 1) * TAU / 48
        for h in (.62, .90):
            k.beam('rim handrail', 'steel', pol(c, c, rr, a, TOP + h), pol(c, c, rr, b, TOP + h), .045, .045, caps=False)
    for i in range(4):
        a = i * TAU / 4 + TAU / 8
        p = Vector(pol(c, c, rr, a, TOP + .92))
        k.box('aviation light', 'lamp red', (p.x - .13, p.y - .05, p.z - .13), (p.x + .13, p.y + .06, p.z + .13))
    # mid-height obstruction lights on the shell
    for i in range(3):
        a = i * TAU / 3 + TAU / 6
        y = 18.0
        r = natural_profile(y)
        p = Vector(pol(c, c, r + .1, a, y))
        out = Vector((math.cos(a), 0, math.sin(a)))
        k.beam('aviation light bracket', 'steel dark', Vector(pol(c, c, r - .05, a, y)), p, .09, .09)
        k.obox('aviation light', 'lamp red', p + out * .08, (-out.z, 0, out.x), Y, out, .12, .12, .12)

    # --- ring beam and diagonal X legs
    k.ring_box('ring beam', 'concrete beam', c, c, 10.1, 11.15, 3.55, 4.3, N)
    NL = 40
    for i in range(NL):
        a0 = i * TAU / NL
        for sgn in (-1, 1):
            a1 = a0 + sgn * TAU / NL
            p0 = Vector(pol(c, c, 11.7, a0, .85))
            p1 = Vector(pol(c, c, 10.65, a1, 3.72))
            radial = Vector((math.cos((a0 + a1) / 2), 0, math.sin((a0 + a1) / 2)))
            k.beam('X leg', 'concrete beam', p0, p1, .38, .5, up=radial)
        # base pedestals and head corbels so the legs read as cast columns
        k.obox('leg pedestal', 'concrete basin', pol(c, c, 11.72, a0, .95), (-math.sin(a0), 0, math.cos(a0)), Y,
               (math.cos(a0), 0, math.sin(a0)), .30, .13, .36)
        k.obox('leg corbel', 'concrete beam', pol(c, c, 10.7, a0, 3.62), (-math.sin(a0), 0, math.cos(a0)), Y,
               (math.cos(a0), 0, math.sin(a0)), .30, .13, .38)

    # --- cold-water basin, guard rail
    k.disc('basin floor', 'concrete basin', c, c, .25, 0, 11.9, 96, True)
    k.revolve('basin wall', 'concrete basin', [(11.95, .05), (11.95, .85)], c, c, N, 1)
    k.revolve('basin wall', 'concrete basin', [(11.45, .25), (11.45, .85)], c, c, N, -1)
    k.disc('basin coping', 'concrete basin', c, c, .85, 11.45, 11.95, N, True)
    k.disc('basin water', 'water', c, c, .32, 0, 11.4, 96, True)
    ladder_az = TAU * 0.0125   # 4.5 degrees: between two leg nodes
    near = lambda a, b, w: abs((a - b + math.pi) % TAU - math.pi) < w
    skip_az = [math.radians(a) for a in (0, 90, 180, 270)]
    posts = []
    for i in range(48):
        a = i * TAU / 48 + TAU / 96
        if any(near(a, s, math.radians(14)) for s in skip_az):
            continue
        posts.append(i)
        k.beam('basin guard rail', 'steel', pol(c, c, 11.78, a, .85), pol(c, c, 11.78, a, 1.95), .07, .07)
    for i in posts:
        if (i + 1) in posts:
            for h in (1.45, 1.92):
                k.beam('basin guard rail', 'steel', pol(c, c, 11.78, i * TAU / 48 + TAU / 96, h), pol(c, c, 11.78, (i + 1) * TAU / 48 + TAU / 96, h), .05, .05, caps=False)

    # --- rain zone fill packs, water distribution and drift eliminators (visible through the legs)
    north = math.radians(270)
    corridor = lambda i: near((i + .5) * TAU / 48, north, math.radians(8.5))
    for n_layer, (y0, y1) in enumerate(((2.20, 2.52), (2.58, 2.90), (2.96, 3.28))):
        tone = lambda _, i, n_layer=n_layer: 'fill dark' if (i + n_layer) % 2 == 0 else 'fill light'
        k.revolve('fill pack', tone, [(10.0, y0), (10.0, y1)], c, c, 48, 1, skip=corridor)
        k.revolve('fill pack', tone, [(2.4, y0), (2.4, y1)], c, c, 48, -1, skip=corridor)
        k.disc('fill pack', 'fill dark', c, c, y1, 2.4, 10.0, 48, True, skip=corridor)
        k.disc('fill pack', 'fill dark', c, c, y0, 2.4, 10.0, 48, False, skip=corridor)
    for r in (3.4, 6.4, 9.2):
        for i in range(48):
            a, b = i * TAU / 48, (i + 1) * TAU / 48
            if corridor(i) and r > 5:
                continue
            k.tube('distribution ring main', 'pipe steel', pol(c, c, r, a, 3.75), pol(c, c, r, b, 3.75), .09, 6, caps=False)
    for i in range(12):
        a = i * TAU / 12
        k.tube('distribution lateral', 'pipe steel', pol(c, c, .3, a, 3.75), pol(c, c, 9.8, a, 3.75), .08, 6)
    for i in range(72):
        a = i * TAU / 72
        t = Vector((-math.sin(a), 0, math.cos(a)))
        for r0, r1 in ((2.5, 6.2), (6.3, 9.9)):
            p0, p1 = Vector(pol(c, c, r0, a, 3.93)), Vector(pol(c, c, r1, a, 3.93))
            lift = Vector((0, .20, 0))
            k.quad2('drift eliminator', 'steel dark', p0 - t * .2, p1 - t * .2, p1 + t * .2 + lift, p0 + t * .2 + lift)
    tower_pipes(k, 25, 25)
    tower_ports(k, 25, 25)

    # --- access ladder up the shell with cage, landings, hoops
    def lad_r(y):
        r_shell = natural_profile(y) + .16
        r_low = 11.62
        u = min(1.0, max(0.0, (y - 4.3) / 1.7))
        u = u * u * (3 - 2 * u)
        return r_low * (1 - u) + r_shell * u if y < 6.0 else r_shell

    def lad_pos(y):
        r = lad_r(y)
        return (c + r * math.cos(ladder_az), c + r * math.sin(ladder_az))

    cage_ladder(k, 'shell ladder', {'y0': .85, 'y1': TOP + .2, 'fn': lad_pos,
                                    'tangent': lambda y: (-math.sin(ladder_az), 0, math.cos(ladder_az))},
                None, width=.5, cage_from=3.2, hoop_step=1.52)
    for y in (12.0, 22.0, 29.5):
        p = Vector((lad_pos(y)[0], y, lad_pos(y)[1]))
        out = Vector((math.cos(ladder_az), 0, math.sin(ladder_az)))
        t = Vector((-math.sin(ladder_az), 0, math.cos(ladder_az)))
        k.obox('ladder landing', 'tread', p + out * .45 - Vector((0, .1, 0)), t, Y, out, .55, .05, .5)
        for s in (-1, 1):
            k.beam('landing rail', 'steel', p + out * .9 + t * (.55 * s) - Vector((0, .05, 0)), p + out * .9 + t * (.55 * s) + Vector((0, 1.0, 0)), .05, .05)
        k.beam('landing rail', 'steel', p + out * .9 - t * .55 + Vector((0, .95, 0)), p + out * .9 + t * .55 + Vector((0, .95, 0)), .05, .05)
        for s in (-1, 1):
            k.beam('landing rail', 'steel', p + out * .9 + t * (.55 * s) + Vector((0, .95, 0)), p + out * .1 + t * (.55 * s) + Vector((0, .95, 0)), .05, .05)
    return k


def natural_meta():
    return {'size': [25, 36, 25], 'controller': [12, 0, 12],
            'ports': [{'role': 'INLET', 'cell': [12, 2, 0], 'face': 'north'},
                      {'role': 'OUTLET', 'cell': [12, 1, 24], 'face': 'south'},
                      {'role': 'MAKEUP', 'cell': [0, 1, 12], 'face': 'west'}],
            'id': 'natural_draft_tower', 'boxes': OCC['natural_draft_tower']['boxes']}


def build_natural_lod():
    """Distant version of the shell: 64 sides, 14 rings, no ports, ladder, fill or fine detail."""
    k = Kit('natural_draft_tower_lod')
    c = 12.5
    N = 64
    TOP, BASE = 35.0, 4.0
    levels = [BASE + (TOP - BASE) * j / 14 for j in range(15)]
    k.revolve('shell', lambda _, i: CONCRETES[(i // 2) % 5], [(natural_profile(y) + .02, y) for y in levels], c, c, N, 1)
    inner = lambda y: .22 + .33 * max(0.0, 1 - (y - BASE) / 12.0)
    k.revolve('shell inner', 'concrete inner', [(natural_profile(y) - inner(y), y) for y in levels], c, c, N, -1)
    k.ring_box('coping', 'concrete beam', c, c, natural_profile(TOP) - inner(TOP) - .02, natural_profile(TOP) + .12, TOP - .1, TOP + .32, N)
    k.ring_box('ring beam', 'concrete beam', c, c, 10.1, 11.15, 3.55, 4.3, N)
    for i in range(20):
        a0 = i * TAU / 20
        for sgn in (-1, 1):
            a1 = a0 + sgn * TAU / 20
            radial = Vector((math.cos((a0 + a1) / 2), 0, math.sin((a0 + a1) / 2)))
            k.beam('X leg', 'concrete beam', pol(c, c, 11.7, a0, .85), pol(c, c, 10.65, a1, 3.72), .42, .5, up=radial, caps=False)
    k.disc('basin floor', 'concrete basin', c, c, .25, 0, 11.9, 48, True)
    k.revolve('basin wall', 'concrete basin', [(11.95, .05), (11.95, .85)], c, c, 48, 1)
    k.disc('basin coping', 'concrete basin', c, c, .85, 11.45, 11.95, 48, True)
    k.disc('basin water', 'water', c, c, .32, 0, 11.4, 48, True)
    k.revolve('fill pack', 'fill dark', [(10.0, 2.2), (10.0, 3.28)], c, c, 24, 1)
    return k


# ================================================================================================ mechanical draft tower
ROTORS = [[8.5 + 4.9 * math.cos(i * TAU / 6), 6.55, 8.5 + 4.9 * math.sin(i * TAU / 6)] for i in range(6)]
DECK_Y, STACK_TOP = 6.05, 7.34


def build_mechanical():
    k = Kit('mechanical_draft_tower')
    c = 8.5
    # plinth, cold-water basin curb
    k.revolve('plinth', 'plinth', [(8.25, .05), (8.25, .35)], c, c, 64, 1)
    k.disc('plinth', 'plinth', c, c, .35, 7.99, 8.25, 64, True)
    k.disc('basin water', 'water', c, c, .36, 0, 7.5, 64, True)
    k.ring_box('basin curb', 'concrete basin', c, c, 7.35, 8.0, .35, .65, 96)
    # precast columns (offset half a bay so every flange lands mid-bay), beams, louvers
    NB = 48
    for y in (1.75, 3.15, 4.55):
        k.ring_box('louver tier beam', 'concrete beam', c, c, 7.3, 7.95, y, y + .12, 96)
    k.ring_box('top beam', 'concrete beam', c, c, 7.0, 8.0, 5.45, 5.75, 96)
    for i in range(NB):
        a = (i + .5) * TAU / NB
        t = (-math.sin(a), 0, math.cos(a))
        u = (math.cos(a), 0, math.sin(a))
        k.obox('precast column', 'concrete beam', pol(c, c, 7.75, a, 3.05), t, Y, u, .09, 2.7, .21)
    for i in range(NB):
        a = i * TAU / NB
        t = Vector((-math.sin(a), 0, math.cos(a)))
        for (y0, y1) in ((.65, 1.75), (1.95, 3.15), (3.35, 4.55), (4.75, 5.45)):
            n = int((y1 - y0) / .15)
            for s in range(n):
                y = y0 + .06 + s * (y1 - y0 - .06) / n
                pi_ = Vector(pol(c, c, 7.46, a, y))
                po = Vector(pol(c, c, 7.90, a, y + .17))
                k.quad2('air inlet louver', 'louver', pi_ - t * .36, pi_ + t * .36, po + t * .36, po - t * .36)
    k.revolve('louver backing', lambda _, i: 'fill dark' if (i // 2) % 2 else 'louver back', [(7.40, .65), (7.40, 5.45)], c, c, 96, 1)
    # outer coping, hot-water distribution deck (annulus), fan deck drum
    k.ring_box('coping wall', 'concrete a', c, c, 7.45, 8.0, 5.75, 6.4, 96)
    k.disc('distribution deck', 'distribution a', c, c, 5.85, 6.55, 7.45, 48, True,
           mats=lambda i: 'distribution a' if i % 2 else 'distribution b')
    for i in range(48):
        a = (i + .5) * TAU / 48
        t = (-math.sin(a), 0, math.cos(a))
        u = (math.cos(a), 0, math.sin(a))
        k.obox('distribution box seam', 'distribution b', pol(c, c, 7.0, a, 5.9), t, Y, u, .035, .05, .42)
    k.revolve('fan deck drum', 'concrete a', [(6.55, 5.75), (6.55, DECK_Y)], c, c, 96, 1)
    outer = [(c + 6.55 * math.cos(i * TAU / 96), c + 6.55 * math.sin(i * TAU / 96)) for i in range(96)]
    holes = []
    for x, _, z in ROTORS:
        holes.append([(x + 1.35 * math.cos(-i * TAU / 32), z + 1.35 * math.sin(-i * TAU / 32)) for i in range(32)])
    k.polygon('fan deck', 'fan deck', DECK_Y, outer, holes, True)
    area = sum(abs((Vector(q) - Vector(p)).cross(Vector(r) - Vector(p)).y) / 2 for p, q, r in k.tris[('fan deck', 'fan deck')])
    assert abs(area - (math.pi * 6.55 ** 2 - 6 * math.pi * 1.35 ** 2)) < 2.0, 'fan deck tessellation lost area: %.2f' % area
    # central riser hatch
    k.tube('central riser housing', 'concrete beam', (c, DECK_Y, c), (c, DECK_Y + .42, c), .85, 24)
    k.tube('central riser cap', 'steel', (c, DECK_Y + .42, c), (c, DECK_Y + .5, c), .62, 24)
    # six fan stacks (velocity-recovery cones), gearboxes, drive shafts and motors
    for n_, (x, _, z) in enumerate(ROTORS):
        a = math.atan2(z - c, x - c)
        u = Vector((math.cos(a), 0, math.sin(a)))
        t = Vector((-math.sin(a), 0, math.cos(a)))
        o = Vector((x, 0, z))
        k.revolve('fan stack', 'steel dark', [(1.24, DECK_Y), (1.24, 6.85), (1.55, STACK_TOP)], x, z, 32, 1)
        k.revolve('fan stack', 'louver back', [(1.12, DECK_Y), (1.12, 6.85), (1.42, STACK_TOP)], x, z, 32, -1)
        k.disc('fan stack lip', 'steel', x, z, STACK_TOP, 1.42, 1.55, 32, True)
        k.ring_box('fan stack base curb', 'concrete beam', x, z, 1.12, 1.62, DECK_Y, DECK_Y + .12, 32)
        # support beams across the throat, gearbox, hub shaft
        for ang in (0, math.pi / 2):
            d_ = Vector((math.cos(a + ang), 0, math.sin(a + ang)))
            k.beam('fan support beam', 'steel dark', o + d_ * -1.2 + Vector((0, 6.14, 0)), o + d_ * 1.2 + Vector((0, 6.14, 0)), .11, .14)
        k.obox('gearbox', 'motor blue', o + Vector((0, 6.27, 0)), t, Y, u, .27, .14, .27)
        k.tube('gearbox stem', 'steel dark', o + Vector((0, 6.14, 0)), o + Vector((0, 6.4, 0)), .1, 10)
        # motor inboard of the stack, drive shaft through a guard collar in the stack wall
        mo = o - u * 2.25
        k.obox('fan motor', 'motor blue', mo + Vector((0, DECK_Y + .30, 0)), t, Y, u, .30, .30, .50)
        k.obox('motor base', 'concrete beam', mo + Vector((0, DECK_Y + .04, 0)), t, Y, u, .38, .04, .58)
        k.tube('motor cooling cap', 'steel', mo - u * .5 + Vector((0, DECK_Y + .30, 0)), mo - u * .66 + Vector((0, DECK_Y + .30, 0)), .26, 14)
        k.tube('drive shaft', 'steel', mo + u * .5 + Vector((0, DECK_Y + .22, 0)), o + Vector((0, DECK_Y + .22, 0)), .07, 8)
        k.obox('shaft guard collar', 'steel dark', o - u * 1.2 + Vector((0, DECK_Y + .22, 0)), t, Y, u, .20, .20, .12)
    # outer handrail and stair tower with bridge
    stair = math.radians(45)
    def near_az(a, b, w):
        return abs((a - b + math.pi) % TAU - math.pi) < w

    for i in range(NB):
        a = (i + .5) * TAU / NB
        if near_az(a, stair, math.radians(11)):
            continue
        k.beam('coping handrail', 'steel', pol(c, c, 7.86, a, 6.4), pol(c, c, 7.86, a, 7.42), .07, .07)
        b = a + TAU / NB
        if not near_az(b, stair, math.radians(11)):
            for h in (6.95, 7.40):
                k.beam('coping handrail', 'steel', pol(c, c, 7.86, a, h), pol(c, c, 7.86, b, h), .05, .05, caps=False)
    for i in range(24):
        a = (i + .5) * TAU / 24
        if near_az(a, stair, math.radians(14)):
            continue
        k.beam('fan deck rail', 'steel', pol(c, c, 6.40, a, DECK_Y), pol(c, c, 6.40, a, DECK_Y + 1.0), .07, .07)
        b = a + TAU / 24
        if not near_az(b, stair, math.radians(14)):
            for h in (DECK_Y + .5, DECK_Y + .97):
                k.beam('fan deck rail', 'steel', pol(c, c, 6.40, a, h), pol(c, c, 6.40, b, h), .05, .05, caps=False)
    ut = Vector((-math.sin(stair), 0, math.cos(stair)))
    uu = Vector((math.cos(stair), 0, math.sin(stair)))
    sc = Vector(pol(c, c, 7.95, stair, 0))
    k.obox('stair tower', 'concrete a', sc + Vector((0, 3.38, 0)), ut, Y, uu, .65, 3.03, .45)
    k.obox('stair tower cap', 'concrete beam', sc + Vector((0, 6.45, 0)), ut, Y, uu, .72, .05, .52)
    bc = Vector(pol(c, c, 7.15, stair, 0)) + Vector((0, 6.34, 0))
    k.obox('stair bridge', 'tread', bc, ut, Y, uu, .5, .06, .85)
    for s in (-1, 1):
        for r0, r1 in ((6.55, 7.75),):
            k.beam('bridge rail', 'steel', Vector(pol(c, c, r0, stair, 0)) + ut * (.5 * s) + Vector((0, 6.40, 0)), Vector(pol(c, c, r0, stair, 0)) + ut * (.5 * s) + Vector((0, 7.40, 0)), .06, .06)
            k.beam('bridge rail', 'steel', Vector(pol(c, c, r1, stair, 0)) + ut * (.5 * s) + Vector((0, 6.40, 0)), Vector(pol(c, c, r1, stair, 0)) + ut * (.5 * s) + Vector((0, 7.40, 0)), .06, .06)
            for h in (6.9, 7.38):
                k.beam('bridge rail', 'steel', Vector(pol(c, c, r0, stair, 0)) + ut * (.5 * s) + Vector((0, h, 0)), Vector(pol(c, c, r1, stair, 0)) + ut * (.5 * s) + Vector((0, h, 0)), .05, .05)
    ladder_r = 8.5
    cage_ladder(k, 'access ladder', {'y0': .5, 'y1': 6.3, 'fn': lambda y: (c + ladder_r * uu.x, c + ladder_r * uu.z),
                                     'tangent': lambda y: tuple(ut)}, None, width=.46, cage_from=2.4, hoop_step=1.14, hoop_r=.33)
    tower_pipes(k, 17, 17)
    tower_ports(k, 17, 17)
    # power: electrical connection box on the east face, with a conduit riser to the fan deck
    k.box('electrical connection', 'pipe steel', (16, 1.13, 8.13), (17, 1.87, 8.87))
    k.box('electrical terminal', 'amber', (16.95, 1.25, 8.25), (17.0, 1.75, 8.75))
    k.tube('electrical conduit', 'steel dark', (16.12, 1.87, 8.5), (16.12, 5.9, 8.5), .05, 8)
    return k


def mechanical_meta():
    return {'size': [17, 8, 17], 'controller': [8, 0, 8],
            'ports': [{'role': 'INLET', 'cell': [8, 2, 0], 'face': 'north'},
                      {'role': 'OUTLET', 'cell': [8, 1, 16], 'face': 'south'},
                      {'role': 'MAKEUP', 'cell': [0, 1, 8], 'face': 'west'},
                      {'role': 'POWER', 'cell': [16, 1, 8], 'face': 'east'}],
            'rotors': ROTORS, 'id': 'mechanical_draft_tower', 'boxes': OCC['mechanical_draft_tower']['boxes']}


def build_fan():
    """Eight twisted blades on a conical hub. Rotation is +Y in model space (points move toward decreasing atan2(z, x)),
    so each blade's leading edge is the lower one: it pitches the air upward, as an induced-draft fan must."""
    k = Kit('cooling_fan')
    k.tube('fan hub', 'fan hub', (0, -.14, 0), (0, .14, 0), .19, 16)
    k.tube('fan hub cone', 'fan hub', (0, .14, 0), (0, .30, 0), .19, 16, r1=.025, caps=False)
    NBL = 8
    stations = [(.18, .27, 30), (.42, .27, 24), (.68, .24, 19), (.88, .20, 15), (1.02, .15, 12)]
    for b in range(NBL):
        a0 = b * TAU / NBL
        prev = None
        for r, chord, pitch in stations:
            half = chord / 2 / r
            tp = math.tan(math.radians(pitch)) * chord / 2
            lead = (r * math.cos(a0 - half), -tp + .05, r * math.sin(a0 - half))
            trail = (r * math.cos(a0 + half), tp + .05, r * math.sin(a0 + half))
            if prev is not None:
                k.quad2('fan blade', 'fan blade', prev[0], prev[1], trail, lead)
            prev = (lead, trail)
    return k


FAN_META = {'id': 'cooling_fan', 'size': [3, 1, 3], 'controller': [0, 0, 0], 'ports': []}


# ================================================================================================ driver
def write_scene(kit, blend_name, png_name, eye, target, lens, extras=None, size=(1400, 1200)):
    scene = kit.blender_scene()
    if extras:
        extras(scene)
    studio(scene, target, eye, lens, size)
    render(scene, OUT / png_name)
    return scene


def main():
    what = (sys.argv[sys.argv.index('--') + 1:] or ['all'])[0]
    outs = []
    if what in ('fan', 'all', 'mechanical'):
        fan = build_fan()
        fan.write_json(OUT / 'cooling_fan.json', dict(FAN_META))
    if what in ('natural', 'all'):
        k = build_natural()
        k.write_json(OUT / 'natural_draft_tower.json', natural_meta())
        s = write_scene(k, 'natural_draft_tower', 'natural_draft_tower.png', (62, 26, -55), (12.5, 16, 12.5), 50)
        bpy.data.libraries.write(str(OUT / 'natural_draft_tower.blend'), {s}, fake_user=True)
        # base close-up for the doc
        s2 = bpy.data.scenes.new('close');
    if what in ('lod', 'all'):
        k = build_natural_lod()
        k.write_json(OUT / 'natural_draft_tower_lod.json', {'id': 'natural_draft_tower_lod', 'size': [25, 36, 25]})
    if what in ('mechanical', 'all'):
        k = build_mechanical()
        meta = mechanical_meta()
        k.write_json(OUT / 'mechanical_draft_tower.json', meta)
        fan = build_fan()
        # the studio copy shows the animated fans at their pivots; the exported tower does not contain them
        for x, y, z in ROTORS:
            for key, tris in fan.tris.items():
                k.tris.setdefault(('render fan', key[1]), []).extend(
                    [tuple((p[0] + x, p[1] + y, p[2] + z) for p in t) for t in tris])
        s = write_scene(k, 'mechanical_draft_tower', 'mechanical_draft_tower.png', (27, 17, -17), (8.5, 4.2, 8.5), 50)
        bpy.data.libraries.write(str(OUT / 'mechanical_draft_tower.blend'), {s}, fake_user=True)


main()
