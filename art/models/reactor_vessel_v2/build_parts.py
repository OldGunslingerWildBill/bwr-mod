"""The in-game reactor vessel (v2): unit parts the renderer scales and places, exported as the mod's Blender source JSON.

Headless only (never the GUI, never the scene "anim_prev"; starts from an empty factory file):

  blender.exe --background --factory-startup --python art/models/reactor_vessel_v2/build_parts.py

then `python tools-export-reactor-vessel.py` bakes them into assets/bwr/models/block/reactor_vessel/<part>/.

The shapes are the approved concept's (build_concept.py, RPV-REFERENCE.md); the concept's geometry helpers are reused. Each part is
drawn at a unit size and the renderer (ReactorVesselRenderer, VesselShellGeometry, VesselInternalsModel) stretches it to the vessel:
revolved parts are fractions of the footprint width (x, z) and one unit high (scaled to the real height of that part), small parts
are unit radius and placed with a uniform scale. Game coordinates: x, y up, z. Flat colours (a white tile tinted per material).
"""
import os, sys, json, math
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'build_concept.py'), encoding='utf-8').read().split('def main():')[0]
sys.argv = [a for a in sys.argv if a != '--']  # the concept's argument parser sees no options
exec(compile(src, 'build_concept.py', 'exec'))

OUT_PARTS = os.path.join(HERE, 'parts')
os.makedirs(OUT_PARTS, exist_ok=True)


class Unit:
    """A frame for parts drawn at unit size: circular, no cutaway."""
    ey = 1.0
    W = D = 1.0


U = Unit()


def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def game(v):
    return (v[0], v[2], -v[1])


def gpt(x, y, z):
    """A game-coordinate point (x, y up, z) as a Blender vector."""
    return Vector((x, -z, y))


def export(name, parts):
    """Writes one source JSON (tools-export-modern.py format) from the bmeshes of the given parts."""
    faces, mats = [], {}
    for p in parts:
        bm = p.bm
        bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=1e-7)
        bmesh.ops.dissolve_degenerate(bm, edges=list(bm.edges), dist=1e-9)
        bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
        bmesh.ops.triangulate(bm, faces=list(bm.faces))
        m = p.mat
        key = m.name
        if key not in mats:
            mats[key] = {'color': [round(srgb(c), 6) for c in m.diffuse_color[:3]], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
        for f in bm.faces:
            vs = [list(round(c, 7) for c in game(v.co)) + [.5, .5] for v in f.verts]
            a, b, c = (Vector(vv[:3]) for vv in vs)
            if (b - a).cross(c - a).length > 1e-12:
                faces.append({'vertices': vs, 'material': key, 'part': p.name})
        bm.free()
    data = {'id': name, 'faces': faces, 'materials': mats, 'boxes': []}
    with open(os.path.join(OUT_PARTS, name + '.json'), 'w', encoding='utf-8') as f:
        json.dump(data, f, separators=(',', ':'))
    print('PART', name, len(faces), 'triangles')
    return len(faces)


def new(name, material):
    return Part(name, material, 'unit')


def ring(name, material, prof, segs=96):
    p = new(name, material)
    lathe(p, U, prof, segs)
    return p


def ellipsoid(name, material, r_out, r_in, rise_in, segs=96, upper=True, steps=16):
    """A closed shell: the outer half-ellipsoid of radius r_out and rise 1, the inner of r_in and rise rise_in, from the equator (y 0)."""
    sgn = 1 if upper else -1
    outer = [(r_out * math.cos(i * math.pi / 2 / steps), sgn * math.sin(i * math.pi / 2 / steps)) for i in range(steps + 1)]
    inner = [(r_in * math.cos(i * math.pi / 2 / steps), sgn * rise_in * math.sin(i * math.pi / 2 / steps)) for i in range(steps + 1)]
    p = new(name, material)
    lathe(p, U, outer + inner[::-1], segs)
    return p


def gcyl(p, a, b, r, n=12, caps=True, r2=None, hollow=None):
    cyl(p, gpt(*a), gpt(*b), r, n, caps, r2, hollow)


def gbox(p, c, s):
    box(p, gpt(*c), (s[0], s[2], s[1]))


def zlathe(p, prof, segs):
    """Revolves an (r, z) profile round the game z axis (a nozzle pointing along +z)."""
    bm = p.bm
    rings = [[bm.verts.new(gpt(r * math.cos(i * TAU / segs), r * math.sin(i * TAU / segs), z)) for r, z in prof] for i in range(segs)]
    m = len(prof)
    for i in range(segs):
        A, B = rings[i], rings[(i + 1) % segs]
        for j in range(m):
            face(bm, [A[j], B[j], B[(j + 1) % m], A[(j + 1) % m]])


def build_all():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    assert 'anim_prev' not in bpy.data.scenes
    M = materials()
    # In game the entity lighting darkens a flat tint more than Workbench does: the same hues, lifted to the old vessel's brightness.
    for key, rgb in (('shell', (.55, .60, .63)), ('seam', (.26, .29, .31)), ('forging', (.50, .55, .58)), ('skirt', (.20, .23, .26)),
                     ('bolt', (.13, .14, .15)), ('ss', (.78, .80, .82)), ('ss_dark', (.55, .58, .61)), ('vane', (.66, .69, .68)), ('hood', (.88, .90, .90))):
        M[key].diffuse_color = (*rgb, 1)
    n = 0
    # --- exterior (fractions of the footprint width; one unit high) --------------------------------
    p = new('rpv_bottom_head', M['shell'])
    outer = [(.46 * math.cos(-math.pi / 2 + i * math.pi / 32), 1 + math.sin(-math.pi / 2 + i * math.pi / 32)) for i in range(17)]
    inner = [(.448 * math.cos(-math.pi / 2 + i * math.pi / 32), 1 + .93 * math.sin(-math.pi / 2 + i * math.pi / 32)) for i in range(17)]
    lathe(p, U, outer + inner[::-1], 96)
    n += export('rpv_bottom_head', [p])
    n += export('rpv_barrel', [ring('barrel', M['shell'], [(.448, 0), (.46, 0), (.46, 1), (.448, 1)])])
    n += export('rpv_vessel_flange', [ring('vessel flange', M['shell'], [(.448, -.45), (.46, -.45), (.485, 0), (.485, 1), (.448, 1)])])
    n += export('rpv_head_flange', [ring('head flange', M['shell'], [(.448, 0), (.485, 0), (.485, 1), (.46, 1.45), (.448, 1.45)])])
    n += export('rpv_head_dome', [ellipsoid('head dome', M['shell'], .46, .448, .94)])
    n += export('rpv_seam', [ring('girth seam', M['seam'], [(.459, 0), (.4665, .3), (.4665, .7), (.459, 1)])])
    p = new('vertical seam', M['seam'])
    gcyl(p, (0, 0, 0), (0, 1, 0), 1, 6)
    n += export('rpv_seam_v', [p])
    n += export('rpv_skirt', [ring('support skirt', M['skirt'], [(.3836, 0), (.3956, 0), (.368, 1), (.356, 1)])])
    n += export('rpv_skirt_base', [ring('skirt base ring', M['skirt'], [(.375, 0), (.41, 0), (.41, 1), (.375, 1)])])
    p = new('anchor nut', M['bolt'])
    gcyl(p, (0, 0, 0), (0, 1, 0), 1, 6)
    n += export('rpv_anchor_nut', [p])
    # nozzle kit, along +z: forging boss then the stainless safe end with its weld
    pf = new('nozzle forging', M['forging'])
    zlathe(pf, [(0, 0), (1, 0), (1, .6), (.55, 1), (0, 1)], 16)
    n += export('rpv_forging', [pf])
    ps = new('safe end', M['safe'])
    gcyl(ps, (0, 0, 0), (0, 0, 1), 1, 16, hollow=.82)
    pw = new('safe end weld', M['seam'])
    gcyl(pw, (0, 0, .12), (0, 0, .2), 1.05, 16)
    n += export('rpv_safe_end', [ps, pw])
    pi_ = new('instrument nozzle', M['forging'])
    gcyl(pi_, (0, 0, 0), (0, 0, 1), 1, 10)
    pp = new('instrument line', M['safe'])
    gcyl(pp, (0, 0, 1), (0, 0, 2.2), .5, 8)
    n += export('rpv_instrument', [pi_, pp])
    p = new('closure stud', M['bolt'])
    gcyl(p, (0, 0, 0), (0, 1, 0), 1, 8)
    n += export('rpv_stud', [p])
    pw = new('washer', M['safe'])
    gcyl(pw, (0, 0, 0), (0, .18, 0), 1.09, 10)
    pn = new('nut', M['bolt'])
    gcyl(pn, (0, .18, 0), (0, 1, 0), 1, 6)
    n += export('rpv_nut', [pw, pn])
    p = new('crown nozzle neck', M['forging'])
    gcyl(p, (0, 0, 0), (0, 1, 0), 1, 14)
    n += export('rpv_crown_neck', [p])
    pf = new('crown flange', M['forging'])
    gcyl(pf, (0, 0, 0), (0, .13, 0), 1.9, 14)
    pb = new('crown flange bolts', M['bolt'])
    for q in range(8):
        a = q * TAU / 8
        gcyl(pb, (1.55 * math.cos(a), -.02, 1.55 * math.sin(a)), (1.55 * math.cos(a), .19, 1.55 * math.sin(a)), .16, 6)
    n += export('rpv_crown_flange', [pf, pb])
    pl = new('head lifting lug', M['forging'])
    gbox(pl, (0, .5, 0), (.3, 1, 1.1))
    pp = new('lug pin', M['bolt'])
    gcyl(pp, (-.3, .62, 0), (.3, .62, 0), .17, 10)
    n += export('rpv_lug', [pl, pp])
    # --- internals --------------------------------------------------------------------------------
    n += export('rpv_core_shroud', [ring('core shroud', M['ss'], [(.368, 0), (.375, 0), (.375, 1), (.368, 1)])])
    n += export('rpv_shroud_step', [ring('shroud step', M['ss'], [(.368, 0), (.375, 0), (.418, 1), (.411, 1)])])
    n += export('rpv_upper_shroud', [ring('upper shroud', M['ss'], [(.411, 0), (.418, 0), (.418, 1), (.411, 1)])])
    n += export('rpv_shroud_flange', [ring('shroud flange', M['ss'], [(.405, 0), (.425, 0), (.425, 1), (.405, 1)])])
    n += export('rpv_support_plate', [ring('shroud support plate', M['ss_dark'], [(.368, 0), (.43, 0), (.43, 1), (.368, 1)])])
    n += export('rpv_top_guide_rim', [ring('top guide rim', M['ss'], [(.355, 0), (.368, 0), (.368, 1), (.355, 1)])])
    n += export('rpv_core_plate', [ring('core plate', M['ss_dark'], [(0, 0), (.366, 0), (.366, 1), (0, 1)])])
    n += export('rpv_shroud_head', [ellipsoid('shroud head dome', M['ss'], .418, .411, .9, steps=12)])
    p = new('standpipe', M['ss_dark'])
    gcyl(p, (0, 0, 0), (0, 1, 0), 1, 8)
    n += export('rpv_standpipe', [p])
    ps = new('separator', M['ss'])
    lathe(ps, U, [(0, 0), (.52, 0), (1, .081), (1, .81), (.8, .81), (.8, 1), (0, 1)], 8)
    pd = new('separator stage ring and lip', M['ss_dark'])
    gcyl(pd, (0, .79, 0), (0, .84, 0), 1.08, 8)
    gcyl(pd, (0, 1, 0), (0, 1.012, 0), .8, 8, hollow=.5)
    n += export('rpv_separator', [ps, pd])
    p = new('lifting eye', M['ss_dark'])
    torus(p, gpt(0, 0, 0), gpt(0, 0, 1) - gpt(0, 0, 0), 1, .27, 12, 6)
    n += export('rpv_eye', [p])
    p = new('rod', M['ss_dark'])
    gcyl(p, (0, 0, 0), (0, 1, 0), 1, 8)
    n += export('rpv_rod', [p])
    p = new('pipe', M['ss'])
    gcyl(p, (0, 0, 0), (0, 0, 1), 1, 8)
    n += export('rpv_pipe', [p])
    n += export('rpv_dryer_ring', [ring('dryer support ring', M['ss_dark'], [(.42, 0), (.443, 0), (.443, 1), (.42, 1)])])
    n += export('rpv_dryer_skirt', [ring('dryer skirt', M['ss'], [(.425, 0), (.428, 0), (.428, 1), (.425, 1)])])
    n += export('rpv_dryer_skirt_ring', [ring('dryer lower ring', M['ss'], [(.424, 0), (.432, 0), (.432, 1), (.424, 1)])])
    n += export('rpv_dryer_band', [ring('dryer perimeter band', M['ss_dark'], [(.424, 0), (.430, 0), (.430, 1), (.424, 1)])])
    pv = new('vane bank', M['vane'])
    gbox(pv, (0, .5, 0), (1, 1, 1))
    pt = new('bank top, trough and slats', M['ss_dark'])
    gbox(pt, (0, 1.01, 0), (1, .04, 1.02))
    gbox(pt, (0, -.04, 0), (1, .08, 1.1))
    for q in range(12):
        x = -.5 + (q + .5) / 12
        for sgn in (-1, 1):
            gbox(pt, (x, .5, sgn * .5), (.012, .92, .03))
    n += export('rpv_dryer_bank', [pv, pt])
    ph = new('dryer hood', M['hood'])
    bm = ph.bm
    v = [bm.verts.new(gpt(*q)) for q in [(-.5, 0, -.5), (-.5, 0, .5), (-.5, 1, 0), (.5, 0, -.5), (.5, 0, .5), (.5, 1, 0)]]
    for f in [(0, 1, 2), (3, 5, 4), (0, 3, 4, 1), (0, 2, 5, 3), (1, 4, 5, 2)]:
        face(bm, [v[i] for i in f])
    n += export('rpv_dryer_hood', [ph])
    for name, key in (('cube_dark', 'ss_dark'), ('cube_ss', 'ss'), ('cube_bolt', 'bolt'), ('cube_skirt', 'skirt')):
        p = new(name, M[key])
        gbox(p, (0, 0, 0), (1, 1, 1))
        n += export('rpv_' + name, [p])
    # --- the failed head: the dome torn off at the crown, a peeled segment, sheared studs (unit rise, from the flange face) ---
    steel = mat('Accident oxidized steel', (.27, .22, .19))
    edge = mat('Accident exposed fracture', (.52, .51, .49))
    pr = new('torn rim', steel)
    bm = pr.bm
    nseg = 96
    rows = []
    for row in range(4):
        verts = []
        for i in range(nseg):
            a = i * TAU / nseg
            top = .30 + .12 * math.sin(i * 2.4) + (.55 if i % 13 == 0 else 0)
            r = .46 if row in (0, 1) else .448
            y = 0 if row in (0, 3) else top
            if row in (1, 2):  # the torn edge follows the dome's own curve
                r = r * math.sqrt(max(.05, 1 - top * top)) + .006 * math.sin(i * 1.3)
            verts.append(bm.verts.new(gpt(r * math.cos(a), y, r * math.sin(a))))
        rows.append(verts)
    for row in range(4):
        for i in range(nseg):
            A, B = rows[row], rows[(row + 1) % 4]
            face(bm, [A[i], A[(i + 1) % nseg], B[(i + 1) % nseg], B[i]])
    pe = new('peeled head segment', edge)
    for i in range(9):
        a0 = (i / 9 * .68 + .1) * math.pi
        a1 = ((i + 1) / 9 * .68 + .1) * math.pi
        prof = [(.46, .02), (.44, .45), (.38, .75), (.30, .93), (.29, .90), (.37, .72), (.43, .44), (.448, .02)]
        lathe(pe, U, prof, 9, a0=a0, a1=a1)
    pst = new('sheared studs', M['bolt'])
    for i in range(24):
        a = i * TAU / 24
        r = .472
        gcyl(pst, (r * math.cos(a), -.2, r * math.sin(a)), ((r + .006 * math.sin(i)) * math.cos(a), .12 + (i % 3) * .05, (r + .006) * math.sin(a)), .0045, 8)
    n += export('failed_head', [pr, pe, pst])
    print('TOTAL', n)


build_all()
