"""Headless Blender build of the condensate storage tank remodel (CONDENSATE-TANK.md, "Remodel" section).

"C:\\Program Files (x86)\\Steam\\steamapps\\common\\Blender\\blender.exe" --background --factory-startup ^
    --python art\\models\\condensate_tank\\build_cst.py [-- --preview DIR]

Writes art/models/condensate_tank/parts/<part>.json (the mod's Blender source format, UVs on the atlas made by
make_cst_atlas.py) and saves cst_remodel.blend (one scene per part, plus assembled review scenes). Then
`python tools-export-condensate-tank.py` bakes the parts into assets/bwr/models/block/condensate_tank/<part>/.

The renderer (CondensateTankRenderer) places the parts for a tank of diameter d and height h exactly as layout() below does;
the two must agree (the preview scenes use layout()). Conventions, game coordinates (x east, y up, z south), one unit one block:
 * "scaled" parts are fractions of the diameter in x and z (the renderer scales them by d) and either one unit or a real height in y;
 * "unit" parts are full size and are placed on the shell, turned so that their local -z points outward.
The reference plant is Columbia (BWR/5): two 400,000 gal carbon-steel tanks with a phenolic lining, electric heaters and
heat-traced piping, inside a concrete dike on a 2 ft 4 in mat (FSAR 9.2.6 and 3.8.5.1.5). The shell courses, top angle, cone
roof with centre vent and roof manway, perimeter handrail, caged ladder with roof landing, shell manway, float-and-tape gauge
board, anchor chairs and reinforced nozzles follow ordinary welded water-tank practice (references in CONDENSATE-TANK.md).
Generic text only: no maker's name or logo.
"""
import bpy, json, math, sys
from pathlib import Path
from mathutils import Vector

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ATLAS = ROOT / 'mod/src/main/resources/assets/bwr/textures/block/condensate_tank/cst_atlas.png'
TILES = json.loads((HERE / 'atlas_tiles.json').read_text())
TEXTURE = 'bwr:block/condensate_tank/cst_atlas'
OUT = HERE / 'parts'
TAU = math.tau
ALIAS = {'dark_steel': 'steel_dark', 'nut_zinc': 'galv'}

IMAGE = bpy.data.images.load(str(ATLAS), check_existing=True)
MATS = {}


def material(tile):
    tile = ALIAS.get(tile, tile)
    if tile not in MATS:
        m = bpy.data.materials.new('CST | ' + tile); m.use_nodes = True
        nt = m.node_tree; bsdf = nt.nodes['Principled BSDF']
        tex = nt.nodes.new('ShaderNodeTexImage'); tex.image = IMAGE; tex.interpolation = 'Closest'
        nt.links.new(tex.outputs['Color'], bsdf.inputs['Base Color'])
        bsdf.inputs['Roughness'].default_value = .7
        MATS[tile] = m
    return MATS[tile]


def tile_uv(tile, s, t):
    tile = ALIAS.get(tile, tile)
    S = TILES['size']; x, y, w, h = TILES['tiles'][tile]
    s = min(1, max(0, s)); t = min(1, max(0, t))
    return ((x + .5 + s * (w - 1)) / S, 1 - (y + .5 + (1 - t) * (h - 1)) / S)


# The recirculation remodel's mesh helpers (Part, lathe, disc, cyl, box, sweep, arc_path, nut, bolt_ring, place_radial).
_src = (HERE.parent / 'recirc' / 'build_recirc.py').read_text(encoding='utf-8')
exec(compile(_src[_src.index('def V(p)'):_src.index('SCENES = {}')], 'build_recirc.py helpers', 'exec'))

PARTS = {}


def part(name):
    p = Part(name); PARTS.setdefault(name, []).append(p); return p


def ring_pts(c, r, n, y, a0=0.0):
    return [V((c[0] + r * math.cos(a0 + TAU * i / n), y, c[2] + r * math.sin(a0 + TAU * i / n))) for i in range(n + 1)]


def rod(p, a, b, r, tile='galv', n=6, caps=(True, True)):
    """A straight round bar from a to b."""
    a, b = V(a), V(b); d = b - a
    cyl(p, r, 0, d.length, tile, a, tuple(d.normalized()), n, caps=caps)


# =====================================================================================================================
# Scaled parts (x, z are fractions of the diameter)
# =====================================================================================================================
def build_scaled():
    # Foundation: concrete ringwall under the shell with a chamfered top, the projecting bottom plate and its fillet weld.
    p = part('base')
    lathe(p, [(0, 0), (.492, 0), (.492, .10), (.484, .14), (0, .14)], 'concrete', n=64, rep=4, tiles=[None, None, 'concrete_top', 'concrete_top'])
    lathe(p, [(.40, .14), (.4795, .14), (.4795, .158), (.40, .158)], 'steel_dark', n=64, rep=4)
    lathe(p, [(.4715, .158), (.4740, .158), (.4705, .175)], 'weld', n=64, rep=8)
    # The concrete mat of the dike: a slab over the square footprint (the curb walls are unit parts placed per side).
    p = part('mat')
    box(p, (-.5, 0, -.5), (.5, .045, .5), 'concrete_top', tiles={'-y': None})
    # Shell: one unit high, the coated plate; the course seams are separate rings so they stay at real spacing.
    p = part('wall')
    lathe(p, [(.47, 0), (.47, 1)], 'shell', n=64, rep=2)
    p = part('seam')  # a girth weld bead, real height .035 block (the renderer scales y by 1)
    lathe(p, [(.4699, 0), (.4712, .006), (.4712, .029), (.4699, .035)], 'weld', n=64, rep=8)
    # Top angle (curb angle) at the eave: the horizontal leg sticks out, the vertical leg laps the top course.
    p = part('top_angle')
    lathe(p, [(.4695, -.07), (.4735, -.07), (.4735, -.012), (.4795, -.012), (.4795, .0), (.4695, .0)], 'shell', n=64, rep=4)
    # Cone roof: one unit rise (the renderer scales y by the rise), lapped at the eave, apex ring for the centre vent.
    p = part('roof')
    lathe(p, [(.4800, -.012), (.4800, .0), (.04, 1.0), (.0, 1.0)], 'roof', n=64, rep=2)
    p = part('roof_seams')  # twelve radial plate laps, raised a hair above the cone
    for i in range(12):
        th = TAU * (i + .5) / 12
        for k in range(10):
            r0, r1 = .47 - .043 * k, .47 - .043 * (k + 1)
            y0, y1 = (.48 - r0) / .44, (.48 - r1) / .44
            u = V((math.cos(th), 0, math.sin(th))); w = V((-math.sin(th), 0, math.cos(th)))
            hw = .0016
            a0 = u * r0 + V((0, y0 + .0015, 0)); a1 = u * r1 + V((0, y1 + .0015, 0))
            p.face([a0 - w * hw, a0 + w * hw, a1 + w * hw, a1 - w * hw], [(0, 0), (1, 0), (1, 1), (0, 1)], 'weld', (0, 1, 0))
    # The name band, a curved decal 50 degrees wide and one unit high, a hair proud of the shell.
    p = part('stencil')
    n = 12
    for j in range(n):
        a0 = math.radians(-25 + 50 * j / n); a1 = math.radians(-25 + 50 * (j + 1) / n)
        r = .4706
        pts = [V((r * math.cos(a), y, r * math.sin(a))) for a, y in ((a0, 0), (a1, 0), (a1, 1), (a0, 1))]
        # the decal faces outward at angle 0 (+x); reading left to right as seen from outside means s grows with -angle
        st = [(1 - j / n, 0), (1 - (j + 1) / n, 0), (1 - (j + 1) / n, 1), (1 - j / n, 1)]
        p.face(pts, st, 'stencil', (math.cos((a0 + a1) / 2), 0, math.sin((a0 + a1) / 2)))


# =====================================================================================================================
# Unit parts (full size; local -z points outward from the shell, origin on the shell surface unless noted)
# =====================================================================================================================
def build_units():
    # Dike curb: one side of the square curb, one unit long in x (scaled by the renderer to the footprint), at z 0..-.14.
    p = part('curb')
    box(p, (-.5, 0, -.14), (.5, .32, 0), 'concrete', tiles={'+y': 'concrete_top', '-y': None})
    # Water nozzle on the shell (flange face at z 0, centre 1.5 above the base, as before): weld-neck flange with gasket and
    # bolting, hub taper, schedule pipe neck through the reinforcing pad into the shell, blue identification band.
    p = part('port')
    ax = (0, 0, 1)
    lathe(p, [(.205, 0), (.40, 0), (.40, .085), (.30, .10), (.235, .19), (.235, .20)], 'flange', (0, 0, 0), ax, 24)
    disc(p, .40, 0, 'flange', (0, 0, 0), ax, 24, up=False, r_in=.205)
    lathe(p, [(.215, 0), (.215, -.012)], 'gasket', (0, 0, 0), ax, 24)
    lathe(p, [(.205, .20), (.205, .95)], 'flange', (0, 0, 0), ax, 20)
    lathe(p, [(.212, .34), (.212, .48)], 'blue', (0, 0, 0), ax, 20, cover=True)
    disc(p, .205, .02, 'coating_inner', (0, 0, 0), ax, 20, up=False)
    for i in range(12):
        th = TAU * (i + .5) / 12
        c = V((.335 * math.cos(th), .335 * math.sin(th), 0))
        cyl(p, .022, -.06, .13, 'steel_dark', c, ax, 6)
        nut(p, c + V((0, 0, .085)), ax, .036, .04)
        nut(p, c + V((0, 0, -.012)), (0, 0, -1), .036, .04)
    # Reinforcing pad around a nozzle: a plate slightly bigger than the flange, its centre sunk into the shell, telltale hole.
    p = part('repad')
    cyl(p, .40, -.02, .045, 'shell', (0, 0, 0), (0, 0, -1), 28, caps=(False, True))
    lathe(p, [(.40, .0), (.405, .045)], 'weld', (0, 0, 0), (0, 0, -1), 28)
    cyl(p, .012, .045, .05, 'steel_dark', (.30, -.12, 0), (0, 0, -1), 6, caps=(False, True))
    # 24 in shell manway: neck, bolted cover with 20 bolts, hinge davit and handle.
    p = part('manway')
    out = (0, 0, -1)
    cyl(p, .30, -.03, .17, 'shell', (0, 0, 0), out, 24, caps=(False, False))
    cyl(p, .40, .17, .23, 'shell', (0, 0, 0), out, 28)
    cyl(p, .40, .23, .29, 'shell', (0, 0, 0), out, 28)
    lathe(p, [(.40, .229), (.395, .231)], 'gasket', (0, 0, 0), out, 28)
    bolt_ring(p, (0, 0, -.17), out, .35, 20, 0, .12, .018, .03, .03)
    box(p, (.38, -.05, -.25), (.46, .05, -.20), 'shell')
    rod(p, (.46, -.18, -.24), (.46, .18, -.24), .025, 'steel_dark', 8)
    sweep(p, [V((-.14, 0, -.29)), V((-.14, 0, -.37)), V((.14, 0, -.37)), V((.14, 0, -.29))], .016, 'galv', 6)
    # Heater: flanged immersion heater nozzle with its terminal box and a conduit down to the base.
    p = part('heater')
    cyl(p, .14, -.03, .16, 'shell', (0, 0, 0), out, 16, caps=(False, False))
    cyl(p, .21, .16, .22, 'flange', (0, 0, 0), out, 16)
    bolt_ring(p, (0, 0, -.16), out, .18, 8, 0, .07, .012, .02, .02)
    box(p, (-.17, -.16, -.50), (.17, .17, -.22), 'heater_box', tiles={'+x': 'steel_dark', '-x': 'steel_dark', '+y': 'steel_dark', '-y': 'steel_dark', '+z': 'steel_dark'})
    sweep(p, [V((0, -.16, -.36)), V((0, -.32, -.36)), V((0, -.40, -.30)), V((0, -.50, -.24))], .028, 'steel_dark', 6)
    # Anchor bolt chair: two gussets, a top plate, the anchor bolt and nut; origin at the shell foot (bottom plate top).
    p = part('anchor_chair')
    for x in (-.075, .075):
        box(p, (x - .012, 0, -.20), (x + .012, .34, 0), 'shell')
    box(p, (-.10, .34, -.22), (.10, .37, 0), 'shell')
    cyl(p, .022, -.02, .45, 'anchor', (0, 0, -.12), (0, 1, 0), 6)
    nut(p, (0, .37, -.12), (0, 1, 0), .040, .045)
    # Ladder: one block of a caged ladder, rails at z 0 (the renderer stands it .20 off the shell); brackets back to the shell.
    p = part('ladder')
    for x in (-.22, .22):
        box(p, (x - .012, 0, -.03), (x + .012, 1.0, .0), 'galv')
    for y in (.17, .50, .83):
        rod(p, (-.21, y, -.015), (.21, y, -.015), .016, 'galv', 6)
    for x in (-.22, .22):
        box(p, (x - .01, .45, .0), (x + .01, .52, .21), 'galv')
    # Cage: one block of hoop and vertical bars, a U from rail to rail around the outer side (centre .30 out).
    p = part('cage')
    hoop = [V((.36 * math.cos(a), .5, -.30 + .36 * math.sin(a))) for a in [math.pi * (i / 12) - math.pi for i in range(13)]]
    hoop = [V((-.24, .5, -.02))] + hoop[1:-1] + [V((.24, .5, -.02))]
    sweep(p, hoop, .012, 'galv', 5)
    for a in (-.85, -.5, -.15, .15, .5, .85):
        th = -math.pi / 2 + a * math.pi / 2
        x, z = .36 * math.cos(th), -.30 + .36 * math.sin(th)
        box(p, (x - .01, 0, z - .01), (x + .01, 1.0, z + .01), 'galv')
    # Roof landing at the top of the ladder: grating platform, side rails, toe plates, the cage extended above the eave.
    p = part('landing')
    box(p, (-.42, -.04, .02), (.42, 0, .66), 'grating', tiles={'-y': 'galv', '+x': 'galv', '-x': 'galv', '+z': 'galv', '-z': 'galv'})
    for x in (-.42, .42):
        box(p, (x - .01, 0, .02), (x + .01, .10, .66), 'yellow')
        for z in (.04, .64):
            rod(p, (x, 0, z), (x, 1.05, z), .02, 'galv', 6)
        rod(p, (x, 1.05, .04), (x, 1.05, .64), .02, 'galv', 6)
        rod(p, (x, .55, .04), (x, .55, .64), .016, 'galv', 6)
    box(p, (-.42, 0, .65), (.42, .10, .67), 'yellow')
    # walk-through: the ladder rails carry on 1.05 above the landing and bend over to the side rails
    for x in (-.22, .22):
        box(p, (x - .012, -.30, -.03), (x + .012, 1.05, .0), 'galv')
        rod(p, (x, 1.05, -.015), (x * 1.9, 1.05, .04), .02, 'galv', 6)
    # Handrail post (1.05 high with a toe plate stub) and the generic rail bar (unit length along +z, unit radius).
    p = part('post')
    rod(p, (0, 0, 0), (0, 1.05, 0), .022, 'galv', 6)
    box(p, (-.03, 0, -.03), (.03, .02, .03), 'galv')
    p = part('rail')
    cyl(p, 1, 0, 1, 'galv', (0, 0, 0), (0, 0, 1), 6, caps=(False, False))
    # Roof centre vent: flanged neck and a gooseneck turned down with its bird screen. Origin at the apex.
    p = part('roof_vent')
    cyl(p, .19, -.05, .05, 'roof', (0, 0, 0), (0, 1, 0), 20)
    cyl(p, .12, .05, .42, 'roof', (0, 0, 0), (0, 1, 0), 16, caps=(False, False))
    cyl(p, .17, .30, .35, 'flange', (0, 0, 0), (0, 1, 0), 16)
    bend = arc_path((0, .42, .18), .18, math.pi, 0, (0, 0, 1), (0, 1, 0), 10)
    sweep(p, bend, .12, 'roof', 16)
    cyl(p, .12, .26, .42, 'roof', (0, 0, .36), (0, 1, 0), 16, caps=(False, False))
    cyl(p, .135, .20, .26, 'grating', (0, 0, .36), (0, 1, 0), 16, caps=(True, False))
    # Roof manway: square curb, hinged cover, handle; sits on the cone near the landing. Origin on the roof surface.
    p = part('roof_hatch')
    box(p, (-.30, -.06, -.30), (.30, .10, .30), 'roof')
    box(p, (-.34, .10, -.34), (.34, .14, .34), 'roof')
    rod(p, (-.12, .17, .0), (.12, .17, .0), .016, 'yellow', 6)
    for x in (-.12, .12):
        rod(p, (x, .14, .0), (x, .17, .0), .014, 'yellow', 6)
    for x in (-.22, .22):
        box(p, (x - .04, .08, .34), (x + .04, .16, .38), 'steel_dark')
    # Float-and-tape level gauge: one block of the gauge board (16 cm board in a channel, on standoffs) ...
    p = part('gauge_board')
    box(p, (-.10, 0, -.10), (.10, 1.0, -.07), 'gauge_scale', tiles={'+x': 'galv', '-x': 'galv', '+y': None, '-y': None, '+z': 'galv'})
    for x in (-.115, .115):
        box(p, (x - .015, 0, -.12), (x + .015, 1.0, -.05), 'galv')
    for y in (.25, .75):
        box(p, (-.13, y - .02, -.05), (.13, y + .02, .0), 'galv')
    # ... its foot: the head plate and the reading housing; and its top: the tape conduit elbow under the roof sheave box.
    p = part('gauge_head')
    box(p, (-.13, 0, -.16), (.13, .34, -.05), 'gauge_head', tiles={'+x': 'galv', '-x': 'galv', '+y': 'galv', '-y': 'galv', '+z': 'galv'})
    box(p, (-.07, -.22, -.13), (.07, 0, -.06), 'steel_dark')
    p = part('gauge_top')
    box(p, (-.12, -.05, -.42), (.12, .20, -.02), 'steel_dark')
    sweep(p, [V((0, .0, -.30)), V((0, -.25, -.30)), V((0, -.45, -.18))], .035, 'steel_dark', 6)
    # Overflow: a flanged nozzle under the eave turning down (origin on the shell); the downcomer is the 'pipe' bar; foot elbow.
    p = part('overflow_top')
    cyl(p, .10, -.02, .30, 'shell', (0, 0, 0), (0, 0, -1), 12, caps=(False, False))
    cyl(p, .17, .18, .24, 'flange', (0, 0, 0), (0, 0, -1), 12)
    sweep(p, arc_path((0, -.22, -.30), .22, 0, math.pi / 2, (0, 1, 0), (0, 0, -1), 6), .10, 'shell', 12)
    p = part('overflow_foot')
    sweep(p, arc_path((0, .22, -.74), .22, 0, math.pi / 2, (0, 0, 1), (0, -1, 0), 6), .10, 'shell', 12)
    box(p, (-.16, -.22, -.90), (.16, -.12, -.60), 'concrete')
    p = part('pipe')
    cyl(p, 1, 0, 1, 'shell', (0, 0, 0), (0, 0, 1), 12, caps=(False, False))
    p = part('pipe_clip')
    box(p, (-.03, -.03, -.42), (.03, .03, 0), 'galv')
    sweep(p, arc_path((0, 0, -.52), .12, 0, math.pi, (1, 0, 0), (0, 0, -1), 6), .015, 'galv', 5)
    # Flat plates on the shell: the radiological posting and the tank plate (local -z outward, faces -z).
    p = part('rad_sign')
    box(p, (-.22, -.22, -.025), (.22, .22, 0), 'galv', tiles={'-z': 'rad_sign'})
    p = part('plate')
    box(p, (-.30, -.15, -.02), (.30, .15, 0), 'galv', tiles={'-z': 'plate'})


# Three materials on the one atlas, by surface: coatings, bare and galvanised metal (a cool cast), concrete (a warm cast).
FAMILIES = {'paint': [1.0, 1.0, 1.0], 'metal': [.95, .975, 1.0], 'concrete': [1.0, .975, .94]}
FAMILY = {t: 'metal' for t in ('galv', 'steel_dark', 'flange', 'gasket', 'anchor', 'grating')}
FAMILY.update({t: 'concrete' for t in ('concrete', 'concrete_top')})


def export_all():
    OUT.mkdir(parents=True, exist_ok=True)
    total = {}
    for name, ps in PARTS.items():
        faces = []
        for p in ps:
            for pts, st, tile in p.faces:
                vs = [[round(q.x, 7), round(q.y, 7), round(q.z, 7)] + [round(c, 7) for c in tile_uv(tile, *x)] for q, x in zip(map(V, pts), st)]
                # fan-triangulate n-gons (lathe poles give triangles already)
                fam = FAMILY.get(ALIAS.get(tile, tile), 'paint')
                for k in range(1, len(vs) - 1):
                    faces.append({'vertices': [vs[0], vs[k], vs[k + 1]], 'material': fam})
        used = [f for f in FAMILIES if any(x['material'] == f for x in faces)]
        data = {'id': name, 'texture': TEXTURE, 'materials': {f: {'color': FAMILIES[f], 'texture': TEXTURE, 'tint': -1} for f in used}, 'faces': faces}
        (OUT / f'{name}.json').write_text(json.dumps(data, separators=(',', ':')))
        total[name] = len(faces)
    print('CST PARTS', json.dumps(total))
    return total


# =====================================================================================================================
# Layout: where the renderer puts each part for a d x h tank (mirrored in CondensateTankRenderer.build)
# =====================================================================================================================
EAVE_DROP = 1.3      # the eave sits 1.3 below the top of the box (as before)
WALL_FOOT = .158     # top of the bottom plate
PORT_Y = 1.5         # flange centres, one block above the base (unchanged)


def rise(d):
    return min(.95, .05 * d + .12)


def layout(d, h):
    """[(part, (x, y, z), yaw_degrees, (sx, sy, sz))] or ('rail', a, b, r). The renderer translates to (x, y, z), turns by
    yaw about +Y (PoseStack Axis.YP: local -z then points to (-sin yaw, -cos yaw) in x, z) and scales."""
    out = []
    R = .47 * d
    eave = h - EAVE_DROP
    rr = rise(d)
    zl = -math.sqrt(R * R - .49) - .20               # ladder rails, .20 off the shell on the existing ladder column (x .7)
    ladder_a = math.degrees(math.atan2(zl + .20, .7))

    def on_shell(name, a_deg, y, sx=1, sy=1, sz=1):
        a = math.radians(a_deg)
        out.append((name, (R * math.cos(a), y, R * math.sin(a)), -(a_deg + 90), (sx, sy, sz)))

    def roof_y(r):
        return eave + rr * max(0.0, min(1.0, (.48 * d - r) / (.44 * d)))

    def clear(a_deg, others, arc):
        return all(abs((a_deg - o + 180) % 360 - 180) * math.pi / 180 * R > arc for o in others)

    out.append(('mat', (0, 0, 0), 0, (d, 1, d)))
    for k in range(4):
        t = math.radians(90 * k)
        out.append(('curb', (-math.sin(t) * (d / 2 - .14), 0, -math.cos(t) * (d / 2 - .14)), 90 * k, (d, 1, 1)))
    out.append(('base', (0, 0, 0), 0, (d, 1, d)))
    out.append(('wall', (0, WALL_FOOT, 0), 0, (d, eave - WALL_FOOT, d)))
    courses = max(1, round((eave - WALL_FOOT) / 2.6))
    ch = (eave - WALL_FOOT) / courses
    plates = max(2, round(math.pi * d / 9))
    for c in range(courses):
        y0 = WALL_FOOT + c * ch
        if c:
            out.append(('seam', (0, y0 - .0175, 0), 0, (d, 1, d)))
        for j in range(plates):
            a = 360 * (j + .5 * (c % 2)) / plates + 17
            if clear(a, [ladder_a], .30):
                on_shell('seam_v', a, y0, 1, ch, 1)
    out.append(('top_angle', (0, eave, 0), 0, (d, 1, d)))
    out.append(('roof', (0, eave, 0), 0, (d, rr, d)))
    out.append(('roof_seams', (0, eave, 0), 0, (d, rr, d)))
    out.append(('roof_vent', (0, eave + rr, 0), 0, (1, 1, 1)))
    # nozzles, as before: four flanges one block above the base, faces on the box sides; a pad where each meets the shell
    for k in range(4):
        t = math.radians(90 * k)
        out.append(('port', (-math.sin(t) * d / 2, PORT_Y, -math.cos(t) * d / 2), 90 * k, (1, 1, 1)))
        out.append(('repad', (-math.sin(t) * R, PORT_Y, -math.cos(t) * R), 90 * k, (1, 1, 1)))
    # shell fittings
    on_shell('manway', 135, .62)
    on_shell('heater', 45, .55)
    on_shell('gauge_head', -150, .55)
    y = .9
    while y + 1 <= eave - .35 + 1e-6:
        on_shell('gauge_board', -150, y)
        y += 1
    on_shell('gauge_top', -150, eave + .02)
    if d >= 5:
        on_shell('plate', 112, 1.25)
        on_shell('rad_sign', -118, min(2.1, eave - .45))
        if eave - WALL_FOOT >= 3.5:  # the stencilled name, a quarter of the way down from the eave, 50 degrees wide at -120
            band = .41 * d / 12 * 1.5
            out.append(('stencil', (0, eave - .28 * (eave - WALL_FOOT) - band / 2, 0), 120, (d, band, d)))
    # overflow downcomer at 165 degrees: nozzle under the eave, pipe .52 off the shell on clips, seal elbow at the foot
    if d >= 5:
        a = math.radians(165)
        top_y = eave - .45
        on_shell('overflow_top', 165, top_y)
        on_shell('overflow_foot', 165, .45)
        px, pz = (R + .52) * math.cos(a), (R + .52) * math.sin(a)
        out.append(('pipe', (px, .67, pz), (px, top_y - .22, pz), .10))
        yc = 1.6
        while yc < top_y - .6:
            on_shell('pipe_clip', 165, yc)
            yc += 2.0
    # anchor chairs round the foot, clear of the fittings at the base
    chairs = max(8, 4 * round(math.pi * d / 1.7 / 4))
    for j in range(chairs):
        a = 360 * (j + .5) / chairs
        if clear(a, [135, 45, -150, 165, ladder_a], .42):
            on_shell('anchor_chair', a, WALL_FOOT)
    # caged ladder (cage from 2.2 up) and the roof landing reaching in over the eave
    land = roof_y(R - .75) + .03
    for y in range(int(h - 1)):
        if .2 + y + 1 > land + .02:
            break
        out.append(('ladder', (.7, .2 + y, zl), 0, (1, 1, 1)))
        if .2 + y >= 2.2:
            out.append(('cage', (.7, .2 + y, zl), 0, (1, 1, 1)))
    out.append(('cage', (.7, land, zl), 0, (1, 1, 1)))
    out.append(('landing', (.7, land, zl), 0, (1, 1, 1)))
    # roof hatch beside the landing
    if d >= 5:
        ha = math.radians(ladder_a - math.degrees(1.3 / R))
        hr = R - 1.1
        out.append(('roof_hatch', (hr * math.cos(ha), roof_y(hr), hr * math.sin(ha)), -(math.degrees(ha) + 90), (1, 1, 1)))
    # perimeter handrail on the roof edge: posts about every 1.9 blocks, top and mid rails as chords, open at the landing
    rp = R - .07
    posts = max(6, round(TAU * rp / 1.9))
    yp = roof_y(rp)
    angles = [360 * (j + .5) / posts - 90 for j in range(posts)]
    keep = [clear(a, [ladder_a], .62) for a in angles]
    for a, k in zip(angles, keep):
        if k:
            ar = math.radians(a)
            out.append(('post', (rp * math.cos(ar), yp, rp * math.sin(ar)), 0, (1, 1, 1)))
    for j in range(posts):
        n = (j + 1) % posts
        if not (keep[j] and keep[n]):
            continue
        a0, a1 = math.radians(angles[j]), math.radians(angles[n])
        for yy, r in ((1.05, .02), (.55, .016)):
            out.append(('rail', (rp * math.cos(a0), yp + yy, rp * math.sin(a0)), (rp * math.cos(a1), yp + yy, rp * math.sin(a1)), r))
    return out


# =====================================================================================================================
def emit(name, ps, scene):
    for p in ps:
        if not p.faces: continue
        verts, faces, uvs, mats, index = [], [], [], [], {}
        names = sorted(set(ALIAS.get(t, t) for _, _, t in p.faces))
        for pts, st, t in p.faces:
            ids = []
            for q in pts:
                key = tuple(round(c, 7) for c in q)
                if key not in index: index[key] = len(verts); verts.append(key)
                ids.append(index[key])
            if len(set(ids)) < 3: continue
            faces.append(ids); uvs.append([tile_uv(t, *x) for x in st]); mats.append(names.index(ALIAS.get(t, t)))
        me = bpy.data.meshes.new(name + ' | ' + p.name)
        me.from_pydata([(x, -z, y) for x, y, z in verts], [], faces); me.update()
        layer = me.uv_layers.new(name='Atlas')
        for poly, uv, m in zip(me.polygons, uvs, mats):
            poly.material_index = m
            for k, li in enumerate(poly.loop_indices): layer.data[li].uv = uv[k]
        for t in names: me.materials.append(material(t))
        o = bpy.data.objects.new(name, me); scene.collection.objects.link(o)


def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    bpy.ops.wm.read_factory_settings(use_empty=True)
    assert 'anim_prev' not in bpy.data.scenes
    global IMAGE
    IMAGE = bpy.data.images.load(str(ATLAS), check_existing=True)
    MATS.clear()
    build_scaled(); build_units()
    # seam_v: a thin vertical bead strip one unit high (scaled to the course height)
    p = part('seam_v')
    box(p, (-.016, 0, -.007), (.016, 1, .002), 'weld', tiles={'+y': None, '-y': None})
    export_all()
    for name, ps in PARTS.items():
        s = bpy.data.scenes.new('BWR | CST ' + name)
        emit(name, ps, s)
    bpy.ops.wm.save_as_mainfile(filepath=str(HERE / 'cst_remodel.blend'), compress=True)
    print('saved', HERE / 'cst_remodel.blend')


if __name__ == '__main__':
    main()
