"""The v2 vessel's recirculation nozzles: unit parts for VesselModel's recirculation outlet and inlet kits (RECIRC-NOZZLES in
RECIRC-REMODEL.md), and the unformed port blocks' models. Headless only (never the GUI, never the scene "anim_prev"):

  blender.exe --background --factory-startup --python art/models/reactor_vessel_v2/build_recirc_nozzles.py

Writes only the new rpv_recirc_* / rpv_thermal_sleeve parts into parts/ (the other parts stay exactly as build_parts.py made them)
and art/models/reactor_vessel_v2/port_blocks/recirculation_{inlet,outlet}.json. Then `python tools-export-reactor-vessel.py`.

Shapes, from the Columbia FSAR (Amendment 67, ML23346A215): Fig 5.3-5 (the outlet nozzle is a big, short, rounded forging low on
the shell; the inlet nozzles are slimmer), Fig 5.3-6 (the nozzle forging's fillet and taper, the Inconel overlay where the
stainless safe end is welded on, the thermal sleeve welded inside, the safe end tapering to the pipe) and 5.3.3.1.4.5 (the
recirculation inlet nozzles have thermal sleeves; stainless safe ends welded on after heat treatment). The outlet carries a
full structural weld overlay over its safe-end weld, the usual IGSCC repair on BWR recirculation nozzles. Unit sizes: each part is
drawn at unit radius along +z (the renderer scales x, y by the radius and z by the length); flat colours like the other v2 parts.
"""
import os, sys, json, math
HERE = os.path.dirname(os.path.abspath(__file__))
_src = open(os.path.join(HERE, 'build_parts.py'), encoding='utf-8').read()
_src = _src[:_src.rindex('build_all()')]   # the helpers and build_all's definition, without running it
exec(compile(_src, 'build_parts.py', 'exec'))

OUT_BLOCKS = os.path.join(HERE, 'port_blocks')


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    assert 'anim_prev' not in bpy.data.scenes
    M = materials()
    for key, rgb in (('forging', (.50, .55, .58)), ('seam', (.26, .29, .31)), ('bolt', (.13, .14, .15)), ('ss', (.78, .80, .82)), ('ss_dark', (.55, .58, .61))):
        M[key].diffuse_color = (*rgb, 1)
    # the nozzle forgings are low-alloy steel like the shell: a shade under the shell's colour, because their faces turn up
    # to the light more than the shell's do, so in game they read as the same metal
    nozzle = mat('RPV recirc nozzle forging', (.44, .49, .52))
    inconel = mat('RPV Inconel butter and overlay', (.70, .67, .60))
    overlay = mat('RPV weld overlay', (.62, .63, .64))
    safe = mat('RPV recirc safe end', (.80, .82, .83))
    pipe = mat('RPV recirc pipe', (.74, .76, .77))
    blue = mat('RPV recirc suction band', (.05, .30, .62))
    teal = mat('RPV recirc discharge band', (.04, .46, .36))
    gasket = mat('RPV recirc gasket', (.04, .045, .05))
    flange = mat('RPV recirc flange', (.60, .64, .67))
    n = 0

    # Outlet forging: a broad set-in boss blending into the shell with a big radius (Fig 5.3-5), barrel, chamfer to the safe end.
    pf = new('outlet nozzle forging', nozzle)
    prof = [(0, -.30), (1.80, -.30), (1.80, 0)]
    prof += [(1.0 + .80 * math.cos(i * math.pi / 2 / 8), .55 * math.sin(i * math.pi / 2 / 8)) for i in range(1, 9)]
    prof += [(1.0, .86), (.80, 1.0), (0, 1.0)]
    zlathe(pf, prof, 28)
    n += export('rpv_recirc_outlet_boss', [pf])
    # Inlet forging: the Fig 5.3-6 profile, a fillet off the shell, a conical taper, the nozzle end, chamfer to the safe end.
    pf = new('inlet nozzle forging', nozzle)
    prof = [(0, -.30), (1.60, -.30), (1.60, 0)]
    prof += [(1.60 - .30 * math.sin(i * math.pi / 2 / 5), .22 * (1 - math.cos(i * math.pi / 2 / 5))) for i in range(1, 6)]
    prof += [(1.0, .62), (1.0, .88), (.80, 1.0), (0, 1.0)]
    zlathe(pf, prof, 24)
    n += export('rpv_recirc_inlet_boss', [pf])
    # Weld bands: Inconel buttering (nozzle to safe end) and the overlay (safe end to pipe), weld passes as ripples.
    for name, m, r0 in (('rpv_recirc_butter', inconel, 1.0), ('rpv_recirc_overlay', overlay, 1.0)):
        p = new(name.replace('_', ' '), m)
        prof = [(0, 0), (r0, 0)]
        for k in range(1, 8):
            prof.append((r0 + (.06 if k % 2 else .025), k / 8))
        prof += [(r0, 1.0), (0, 1.0)]
        zlathe(p, prof, 24)
        n += export(name, [p])
    # Stainless safe end: straight, then the taper down to the pipe (Fig 5.3-6, item 2).
    p = new('recirc safe end', safe)
    zlathe(p, [(0, 0), (1.0, 0), (1.0, .40), (.78, .85), (.78, 1.0), (0, 1.0)], 24)
    n += export('rpv_recirc_safe_end', [p])
    p = new('recirc pipe', pipe)
    gcyl(p, (0, 0, 0), (0, 0, 1), 1, 16)
    n += export('rpv_recirc_pipe', [p])
    # Thermal sleeve inside the inlet nozzle: the sleeve tube standing in from the inner wall with its welded collar (Fig 5.3-6,
    # items 3 and 4); the jet-pump riser is welded to it inside the vessel (FSAR 3.9.5).
    ps = new('thermal sleeve', M['ss'])
    gcyl(ps, (0, 0, 0), (0, 0, 1), 1, 16, hollow=.80)
    pc = new('thermal sleeve collar', M['ss_dark'])
    gcyl(pc, (0, 0, -.05), (0, 0, .12), 1.15, 16, hollow=.75)
    n += export('rpv_thermal_sleeve', [ps, pc])
    # The pipe end at the port face (local z -0.30 .. 0, face at z 0, as the old water nozzle's face): weld-neck hub, raised-face
    # flange, gasket, twelve studs with nuts, and a band in the pump's port colour (outlet to the suction: blue; inlet from the
    # discharge: teal). The renderer draws the outlet at 1.15 and the inlet at 0.85.
    for name, band in (('rpv_recirc_flange_outlet', blue), ('rpv_recirc_flange_inlet', teal)):
        ph = new('flange hub', flange)
        zlathe(ph, [(0, -.42), (.30, -.42), (.30, -.24), (.38, -.12), (.44, -.12), (.44, -.03), (.40, -.03), (.40, 0), (0, 0)], 24)
        pg = new('gasket', gasket)
        gcyl(pg, (0, 0, -.035), (0, 0, -.025), .445, 24)
        pb = new('flange studs', M['bolt'])
        for q in range(12):
            a = (q + .5) * TAU / 12
            x, y = .385 * math.cos(a), .385 * math.sin(a)
            gcyl(pb, (x, y, -.155), (x, y, -.115), .036, 6)
        pband = new('identification band', band)
        gcyl(pband, (0, 0, -.40), (0, 0, -.30), .315, 24)
        pr = new('raised face and bore', gasket)
        gcyl(pr, (0, 0, -.01), (0, 0, .004), .29, 24)
        n += export(name, [ph, pg, pb, pband, pr])
    print('RECIRC NOZZLE PARTS', n, 'triangles')
    port_blocks(M, inconel, safe, flange, gasket, blue, teal)


def port_blocks(M, inconel, safe, flange, gasket, blue, teal):
    """The unformed port blocks: a shell cube (the vessel texture) with the nozzle set into its north face, flush with it, so the
    pipe joins exactly as before. Written in the tools-export-modern.py source format with the cube faces UV-mapped."""
    os.makedirs(OUT_BLOCKS, exist_ok=True)
    for kind, r, band, studs in (('outlet', .40, blue, 12), ('inlet', .32, teal, 8)):
        faces, mats = [], {}

        def add(p_parts, texture=None):
            for p in p_parts:
                bm = p.bm
                bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=1e-7)
                bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
                bmesh.ops.triangulate(bm, faces=list(bm.faces))
                key = p.mat.name
                mats.setdefault(key, {'color': [round(srgb(c), 6) for c in p.mat.diffuse_color[:3]], 'texture': 'minecraft:block/white_concrete', 'tint': -1})
                for f in bm.faces:
                    vs = [list(round(c, 7) for c in game(v.co)) + [.5, .5] for v in f.verts]
                    faces.append({'vertices': vs, 'material': key})
                bm.free()
        def tri(vs, want, material):
            a, b, c3 = (Vector(v[:3]) for v in vs)
            if (b - a).cross(c3 - a).dot(Vector(want)) < 0: vs = [vs[0], vs[2], vs[1]]
            faces.append({'vertices': vs, 'material': material})
        # block space: x, y in 0..1, the face at z 0 (north), the shell cube behind it with a counterbore round the nozzle
        shell = {'color': [1, 1, 1], 'texture': 'bwr:block/reactor_vessel', 'tint': -1}
        mats['shell'] = shell
        c = (.5, .5)
        depth = .19
        rb = r + .07                      # counterbore radius
        N = 32
        # five full cube faces (south, east, west, up, down) with the texture's usual mapping
        cube = {
            'south': [(1, 0, 1), (0, 0, 1), (0, 1, 1), (1, 1, 1)], 'east': [(1, 0, 0), (1, 0, 1), (1, 1, 1), (1, 1, 0)],
            'west': [(0, 0, 1), (0, 0, 0), (0, 1, 0), (0, 1, 1)], 'up': [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
            'down': [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)]}
        for side, q in cube.items():
            uv = {'south': lambda p: (1 - p[0], p[1]), 'east': lambda p: (1 - p[2], p[1]), 'west': lambda p: (p[2], p[1]),
                  'up': lambda p: (p[0], 1 - p[2]), 'down': lambda p: (p[0], p[2])}[side]
            vs = [[*map(float, p), *uv(p)] for p in q]
            want = {'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}[side]
            tri([vs[0], vs[1], vs[2]], want, 'shell')
            tri([vs[0], vs[2], vs[3]], want, 'shell')
        # the north face: the square minus the counterbore circle (fan from the square corners), facing -z
        ring = [(c[0] + rb * math.cos(TAU * i / N), c[1] + rb * math.sin(TAU * i / N)) for i in range(N)]
        corners = [(1, 0), (0, 0), (0, 1), (1, 1)]   # clockwise seen from -z (outside)
        def nv(x, y, z=0.0): return [x, y, z, 1 - x, y]
        for i in range(N):
            a, b = ring[i], ring[(i + 1) % N]
            # nearest corner to the segment midpoint closes each sliver
            mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
            k = min(range(4), key=lambda j: (corners[j][0] - mx) ** 2 + (corners[j][1] - my) ** 2)
            tri([nv(*corners[k]), nv(*b), nv(*a)], (0, 0, -1), 'shell')
        for j in range(4):
            # the triangle between two corners and the ring point closest to their edge midpoint
            p0, p1 = corners[j], corners[(j + 1) % 4]
            mx, my = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
            i = min(range(N), key=lambda i: (ring[i][0] - mx) ** 2 + (ring[i][1] - my) ** 2)
            tri([nv(*p1), nv(*ring[i]), nv(*p0)], (0, 0, -1), 'shell')
        # counterbore wall and floor (dark forging), facing inward / outward
        fmat = M['forging'].name
        mats.setdefault(fmat, {'color': [round(srgb(x), 6) for x in M['forging'].diffuse_color[:3]], 'texture': 'minecraft:block/white_concrete', 'tint': -1})
        for i in range(N):
            a, b = ring[i], ring[(i + 1) % N]
            inward = (c[0] - (a[0] + b[0]) / 2, c[1] - (a[1] + b[1]) / 2, 0)
            tri([[a[0], a[1], 0, .5, .5], [b[0], b[1], 0, .5, .5], [b[0], b[1], depth, .5, .5]], inward, fmat)
            tri([[a[0], a[1], 0, .5, .5], [b[0], b[1], depth, .5, .5], [a[0], a[1], depth, .5, .5]], inward, fmat)
            tri([[c[0], c[1], depth, .5, .5], [a[0], a[1], depth, .5, .5], [b[0], b[1], depth, .5, .5]], (0, 0, -1), fmat)
        # the nozzle in the counterbore: forging lip, Inconel butter, safe end, flange flush with the face, studs, band
        parts = []
        def tube(name, m, rr, z0, z1, segs=24):
            p = new(name, m)
            # game z grows into the block; gpt maps game (x, y, z) to Blender, so build in game space directly
            gcyl(p, (c[0], c[1], z0), (c[0], c[1], z1), rr, segs)
            parts.append(p)
        tube('nozzle forging', M['forging'], r * 1.12, .10, depth + .01)
        tube('butter', inconel, r * .98, .07, .10)
        tube('safe end', safe, r * .90, .03, .07)
        tube('flange', flange, r, .0005, .03)
        tube('gasket ring', gasket, r * .62, .0002, .0006)
        pb = new('studs', M['bolt'])
        for q in range(studs):
            a = (q + .5) * TAU / studs
            x, y = c[0] + r * .82 * math.cos(a), c[1] + r * .82 * math.sin(a)
            gcyl(pb, (x, y, .0), (x, y, .028), .028, 6)
        parts.append(pb)
        pband = new('band', band)
        gcyl(pband, (c[0], c[1], .12), (c[0], c[1], .15), r * 1.14, 24)
        parts.append(pband)
        add(parts)
        data = {'id': 'recirculation_' + kind, 'faces': faces, 'materials': mats, 'boxes': []}
        with open(os.path.join(OUT_BLOCKS, f'recirculation_{kind}.json'), 'w', encoding='utf-8') as f:
            json.dump(data, f, separators=(',', ':'))
        print('PORT BLOCK', kind, len(faces), 'triangles')


main()
