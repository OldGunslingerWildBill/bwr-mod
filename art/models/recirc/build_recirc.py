"""Headless Blender build of the reactor recirculation remodel: external recirculation pump, paired jet pump, RIP.

"C:\\Program Files (x86)\\Steam\\steamapps\\common\\Blender\\blender.exe" --background --factory-startup ^
    --python art\\models\\recirc\\build_recirc.py

Saves art/models/recirc/recirc_remodel.blend (one scene per machine, editable objects with UVs on the shared
atlas) and exports art/models/recirc/<id>.json, which tools-export-recirc.py clips into the runtime cell OBJs.

Game coordinates throughout: X east, Y up, Z south (Blender gets (x, -z, y)); one unit is one block. Footprints,
controller cells and port cells are the ones in PUMP-MODELS.md. References are listed in RECIRC-REMODEL.md: the
Columbia FSAR (Fig 5.4-1 loop, Fig 3.9-4 BWR/5 jet pump, section 5.4.1 pump/motor/seal text), the NRC HRTD
recirculation chapter (Figs 2.4-1, 2.4-4, 2.4-6) and the ABWR RIP descriptions in INTERNAL-PUMPS.md.
Generic plates only: no maker's name or logo anywhere.
"""
import bpy, bmesh, json, math
from pathlib import Path
from mathutils import Vector

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ATLAS = ROOT / 'mod/src/main/resources/assets/bwr/textures/block/pumps/recirc_atlas.png'
TILES = json.loads((HERE / 'atlas_tiles.json').read_text())
TEXTURE = 'bwr:block/pumps/recirc_atlas'
TAU = math.tau

for s in list(bpy.data.scenes):
    if s.name.startswith('BWR | recirc'): bpy.data.scenes.remove(s)
IMAGE = bpy.data.images.load(str(ATLAS), check_existing=True)
MATS = {}
def material(tile):
    if tile not in MATS:
        m = bpy.data.materials.new('Recirc | ' + tile); m.use_nodes = True
        nt = m.node_tree; bsdf = nt.nodes['Principled BSDF']
        tex = nt.nodes.new('ShaderNodeTexImage'); tex.image = IMAGE; tex.interpolation = 'Closest'
        nt.links.new(tex.outputs['Color'], bsdf.inputs['Base Color']); nt.links.new(tex.outputs['Alpha'], bsdf.inputs['Alpha'])
        bsdf.inputs['Roughness'].default_value = .7
        x, y, w, h = TILES['tiles'][tile]
        m['tile'] = tile
        MATS[tile] = m
    return MATS[tile]

def tile_uv(tile, s, t):
    """Local (s, t) in [0,1]^2, t up, into atlas UV (Blender/OBJ v-up), inset half a texel from the tile edge."""
    S = TILES['size']; x, y, w, h = TILES['tiles'][tile]
    s = min(1, max(0, s)); t = min(1, max(0, t))
    u = (x + .5 + s * (w - 1)) / S
    v = 1 - (y + .5 + (1 - t) * (h - 1)) / S
    return (u, v)

def V(p): return Vector(p)
def norm(a, b, c):
    n = (V(b) - V(a)).cross(V(c) - V(a))
    return n.normalized() if n.length > 1e-12 else n

class Part:
    """One editable object: game-space vertices, faces with their own per-corner (s, t) and tile."""
    def __init__(self, name, solid=False):
        # solid: the part's bounding box is a collision volume (big closed bodies); other parts collide by their faces
        self.name = name; self.faces = []; self.solid = solid
    def face(self, pts, st, tile, want=None):
        pts = [tuple(p) for p in pts]
        # drop repeated corners (lathe poles)
        keep = [i for i in range(len(pts)) if (V(pts[i]) - V(pts[i - 1])).length > 1e-9]
        pts = [pts[i] for i in keep]; st = [st[i] for i in keep]
        if len(pts) < 3: return
        if want is not None:
            n = norm(*pts[:3])
            if n.length > 0 and n.dot(V(want)) < 0:
                pts = pts[::-1]; st = st[::-1]
        self.faces.append((pts, st, tile))
    def extend(self, other): self.faces += other.faces; return self

def frame(axis):
    d = V(axis).normalized()
    a = d.cross(V((0, 1, 0))) if abs(d.y) < .9 else d.cross(V((1, 0, 0)))
    a.normalize(); b = d.cross(a).normalized()
    return d, a, b

def lathe(part, profile, tile, center=(0, 0, 0), axis=(0, 1, 0), n=24, a0=0.0, arc=None, tiles=None, rep=1, cover=False):
    """Surface of revolution. profile = [(r, h), ...] along the axis from center. The face normal follows the
    profile's left side (going up an outer wall points outward, going inward across a top faces up).
    arc=(start, end) radians builds a partial shell; tiles per band overrides tile; rep = facets per tile width;
    cover maps each band face to the whole tile (for plates/bands)."""
    if isinstance(axis, (int, float)): n, axis = int(axis), (0, 1, 0)
    d, a, b = frame(axis)
    if tuple(axis) == (0, 1, 0):
        d, a, b = V((0, 1, 0)), V((1, 0, 0)), V((0, 0, 1))
    c = V(center)
    lo, hi = (a0, a0 + TAU) if arc is None else arc
    def P(r, h, th): return c + d * h + (a * math.cos(th) + b * math.sin(th)) * r
    for i in range(len(profile) - 1):
        (r0, h0), (r1, h1) = profile[i], profile[i + 1]
        if abs(r0 - r1) < 1e-9 and abs(h0 - h1) < 1e-9: continue
        t2 = (h1 - h0, -(r1 - r0))  # outward normal in (r, h)
        ti = tiles[i] if tiles and i < len(tiles) and tiles[i] else tile
        for j in range(n):
            th0 = lo + (hi - lo) * j / n; th1 = lo + (hi - lo) * (j + 1) / n; tm = (th0 + th1) / 2
            pts = [P(r0, h0, th0), P(r0, h0, th1), P(r1, h1, th1), P(r1, h1, th0)]
            k = j % rep
            s0, s1 = (0, 1) if cover else (k / rep, (k + 1) / rep)
            st = [(s0, 0), (s1, 0), (s1, 1), (s0, 1)]
            want = (a * math.cos(tm) + b * math.sin(tm)) * t2[0] + d * t2[1]
            part.face(pts, st, ti, want)
    return part

def disc(part, r, h, tile, center=(0, 0, 0), axis=(0, 1, 0), n=24, up=True, r_in=0.0):
    """Flat disc or annulus facing +axis (up) or -axis, planar-mapped into the tile."""
    prof = [(r_in, h), (r, h)] if not up else [(r, h), (r_in, h)]
    d, a, b = frame(axis)
    if tuple(axis) == (0, 1, 0): d, a, b = V((0, 1, 0)), V((1, 0, 0)), V((0, 0, 1))
    c = V(center) + d * h
    for j in range(n):
        th0, th1 = TAU * j / n, TAU * (j + 1) / n
        pts = [c + (a * math.cos(th0) + b * math.sin(th0)) * r, c + (a * math.cos(th1) + b * math.sin(th1)) * r,
               c + (a * math.cos(th1) + b * math.sin(th1)) * r_in, c + (a * math.cos(th0) + b * math.sin(th0)) * r_in]
        st = [(.5 + .5 * math.cos(th0) * rr, .5 + .5 * math.sin(th0) * rr) for rr in (1,)] + \
             [(.5 + .5 * math.cos(th1), .5 + .5 * math.sin(th1)), (.5 + .5 * math.cos(th1) * r_in / r, .5 + .5 * math.sin(th1) * r_in / r),
              (.5 + .5 * math.cos(th0) * r_in / r, .5 + .5 * math.sin(th0) * r_in / r)]
        part.face(pts, st, tile, d if up else -d)
    return part

def cyl(part, r, h0, h1, tile, center=(0, 0, 0), axis=(0, 1, 0), n=16, caps=(True, True), rep=1, cap_tile=None, cover=False):
    lathe(part, [(r, h0), (r, h1)], tile, center, axis, n, rep=rep, cover=cover)
    if caps[0]: disc(part, r, h0, cap_tile or tile, center, axis, n, up=False)
    if caps[1]: disc(part, r, h1, cap_tile or tile, center, axis, n, up=True)
    return part

def box(part, lo, hi, tile, tiles=None, rot=0.0, pivot=None):
    """Axis-aligned box (optionally turned about Y by rot around pivot); tiles = {face: tile} for '+x','-x',...
    Each face maps the whole tile, so plates and louvers read the right way up."""
    x0, y0, z0 = lo; x1, y1, z1 = hi
    pv = V(pivot) if pivot else V(((x0 + x1) / 2, 0, (z0 + z1) / 2))
    cr, sr = math.cos(rot), math.sin(rot)
    def R(p):
        q = V(p) - pv
        return pv + V((q.x * cr - q.z * sr, q.y, q.x * sr + q.z * cr))
    def Rn(n): return V((n[0] * cr - n[2] * sr, n[1], n[0] * sr + n[2] * cr))
    faces = {
        '+x': ([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1, 0, 0)),
        '-x': ([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0)),
        '+z': ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1)),
        '-z': ([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0, 0, -1)),
        '+y': ([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (0, 1, 0)),
        '-y': ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0)),
    }
    for key, (pts, n) in faces.items():
        t = (tiles or {}).get(key, tile)
        if t is None: continue
        part.face([R(p) for p in pts], [(0, 0), (1, 0), (1, 1), (0, 1)], t, Rn(n))
    return part

def sweep(part, path, r, tile, n=12, caps=(False, False), rep=1):
    """Tube along a polyline with parallel-transported rings (elbows, bends, small-bore lines)."""
    pts = [V(p) for p in path]
    tangents = []
    for i in range(len(pts)):
        t = (pts[min(i + 1, len(pts) - 1)] - pts[max(i - 1, 0)]).normalized(); tangents.append(t)
    d, a, b = frame(tangents[0]); rings = []
    for i, p in enumerate(pts):
        t = tangents[i]
        if i:
            # parallel transport: turn the previous ring's reference by the change of tangent (no twist, no flips)
            a = tangents[i - 1].rotation_difference(t) @ a
            a = (a - t * a.dot(t)).normalized()
        b2 = t.cross(a).normalized()
        rings.append([p + (a * math.cos(TAU * j / n) + b2 * math.sin(TAU * j / n)) * r for j in range(n)])
    for i in range(len(rings) - 1):
        for j in range(n):
            q = [rings[i][j], rings[i][(j + 1) % n], rings[i + 1][(j + 1) % n], rings[i + 1][j]]
            mid = (pts[i] + pts[i + 1]) / 2
            k = j % rep
            part.face(q, [(k / rep, 0), ((k + 1) / rep, 0), ((k + 1) / rep, 1), (k / rep, 1)], tile, (q[0] + q[2]) / 2 - mid)
    for end, idx in ((0, 0), (1, -1)):
        if caps[end]:
            c = pts[idx]; ring = rings[idx]; out = -tangents[0] if end == 0 else tangents[-1]
            for j in range(n):
                part.face([c, ring[j], ring[(j + 1) % n]], [(.5, .5), (.5 + .5 * math.cos(TAU * j / n), .5 + .5 * math.sin(TAU * j / n)),
                          (.5 + .5 * math.cos(TAU * (j + 1) / n), .5 + .5 * math.sin(TAU * (j + 1) / n))], tile, out)
    return part

def arc_path(center, r, start, end, plane_a, plane_b, steps):
    c, A, B = V(center), V(plane_a), V(plane_b)
    return [c + (A * math.cos(start + (end - start) * i / steps) + B * math.sin(start + (end - start) * i / steps)) * r for i in range(steps + 1)]

def bezier(p0, t0, p1, t1, steps, k=.55):
    p0, p1 = V(p0), V(p1); L = (p1 - p0).length * k
    c0, c1 = p0 + V(t0).normalized() * L, p1 - V(t1).normalized() * L
    return [p0 * (1 - u) ** 3 + c0 * 3 * u * (1 - u) ** 2 + c1 * 3 * u * u * (1 - u) + p1 * u ** 3 for u in [i / steps for i in range(steps + 1)]]

def nut(part, center, axis, r, h, tile='nut_zinc'):
    """Hex nut (six sides and an outer face)."""
    d, a, b = frame(axis)
    lathe(part, [(r, 0), (r, h)], tile, center, axis, 6, a0=math.pi / 6, cover=True)
    disc(part, r, h, tile, center, axis, 6, up=True)

def bolt_ring(part, center, axis, radius, count, h0, h1, r_stud, r_nut, nut_h, a0=0.0, stud=True, under=False):
    d, a, b = frame(axis)
    if tuple(axis) == (0, 1, 0): d, a, b = V((0, 1, 0)), V((1, 0, 0)), V((0, 0, 1))
    for i in range(count):
        th = a0 + TAU * i / count
        c = V(center) + (a * math.cos(th) + b * math.sin(th)) * radius
        if stud: cyl(part, r_stud, h0, h1 + .06, 'dark_steel', c, axis, 6, caps=(False, True))
        nut(part, c + d * h1, axis, r_nut, nut_h)
        if under: nut(part, c + d * h0, -d, r_nut, nut_h)

def place_radial(dst, src, th, centre, rfun):
    """Copy src (built at the origin: x across, y up, z outward) to angle th around the vertical axis at centre.
    x maps to -tangent so the frame stays right-handed and the faces keep their outward winding."""
    cx0, cz0 = centre; c, s = math.cos(th), math.sin(th)
    for pts, st, tt in src.faces:
        q2 = []
        for q in pts:
            q = V(q); rr = rfun(q.y) + q.z
            q2.append(V((cx0 + rr * c + q.x * s, q.y, cz0 + rr * s - q.x * c)))
        dst.faces.append((q2, st, tt))

SCENES = {}
def emit(scene_name, parts):
    scene = bpy.data.scenes.new(scene_name); SCENES[scene_name] = scene
    col = scene.collection
    for p in parts:
        if not p.faces: continue
        verts, faces, uvs, mats = [], [], [], []
        index = {}
        names = sorted(set(t for _, _, t in p.faces))
        for pts, st, t in p.faces:
            ids = []
            for q in pts:
                key = tuple(round(c, 7) for c in q)
                if key not in index: index[key] = len(verts); verts.append(key)
                ids.append(index[key])
            if len(set(ids)) < 3: continue
            faces.append(ids); uvs.append([tile_uv(t, *x) for x in st]); mats.append(names.index(t))
        me = bpy.data.meshes.new(p.name)
        me.from_pydata([(x, -z, y) for x, y, z in verts], [], faces); me.update()
        layer = me.uv_layers.new(name='Atlas')
        for poly, uv, m in zip(me.polygons, uvs, mats):
            poly.material_index = m
            for k, li in enumerate(poly.loop_indices): layer.data[li].uv = uv[k]
        for t in names: me.materials.append(material(t))
        o = bpy.data.objects.new(p.name, me); col.objects.link(o); o['solid'] = p.solid
    return scene

def export(scene, id, size, controller, ports, extra=None):
    faces, boxes = [], []
    for o in scene.collection.objects:
        me = o.data; uv = me.uv_layers['Atlas'].data
        pts_all = []
        for poly in me.polygons:
            vs = []
            for li in poly.loop_indices:
                co = me.vertices[me.loops[li].vertex_index].co
                p = (round(co.x, 7), round(co.z, 7), round(-co.y, 7)); pts_all.append(p)
                vs.append([*p, round(uv[li].uv[0], 7), round(uv[li].uv[1], 7)])
            faces.append({'vertices': vs, 'material': 'atlas'})
        if o.get('solid'): boxes.append([min(p[a] for p in pts_all) for a in range(3)] + [max(p[a] for p in pts_all) for a in range(3)])
    b = [min(f[a] for x in faces for f in x['vertices']) for a in range(3)] + [max(f[a] for x in faces for f in x['vertices']) for a in range(3)]
    assert all(b[a] >= -1e-6 and b[a + 3] <= size[a] + 1e-6 for a in range(3)), (id, b)
    data = {'id': id, 'size': size, 'controller': controller, 'ports': ports, 'texture': TEXTURE,
            'materials': {'atlas': {'color': [1, 1, 1], 'texture': TEXTURE}}, 'boxes': boxes, 'faces': faces}
    if extra: data.update(extra)
    (HERE / f'{id}.json').write_text(json.dumps(data, separators=(',', ':')))
    print(f'{id}: {len(faces)} faces, bounds {[round(v, 3) for v in b]}')

# =====================================================================================================================
# External recirculation pump, 5 x 10 x 5. Axis at (2.5, 2.5). Rear suction flange centred (2.5, 0.5, 5); front
# discharge flange centred (2.5, 2.5, 0). Vertical single-stage casing with a big-stud cover closure, a seal cartridge,
# an open motor stand with a three-piece spacer coupling behind mesh guards, and a vertical air-water-cooled motor.
# =====================================================================================================================
def recirc_pump():
    C = (2.5, 0, 2.5); cx, cz = 2.5, 2.5
    P = []
    # ---- suction elbow: horizontal from the rear flange, a long-radius 90 degree bend up into the casing inlet
    suction = Part('suction elbow and nozzle'); R = .9; r = .333
    path = [V((cx, .5, 4.86)), V((cx, .5, cz + R))]
    path += arc_path((cx, .5 + R, cz + R), R, math.radians(180), math.radians(270), (0, 1, 0), (0, 0, 1), 12)[1:]
    path += [V((cx, 1.5, cz))]
    sweep(suction, path, r, 'stainless_cast', n=20)
    lathe(suction, [(r + .015, 0), (r + .015, .2)], 'suction_blue', (cx, .5, 4.5), (0, 0, 1), 20, cover=True)
    lathe(suction, [(.333, -.05), (.36, 0), (.42, .06), (.5, .06), (.5, .14), (.33, .14)], 'machined', (cx, .5, 4.86), (0, 0, 1), 20,
          tiles=[None, None, None, None, 'machined'])
    disc(suction, .33, .14, 'gasket', (cx, .5, 4.86), (0, 0, 1), 20, up=True, r_in=.25)
    disc(suction, .25, .135, 'interior', (cx, .5, 4.86), (0, 0, 1), 12, up=True)
    bolt_ring(suction, (cx, .5, 4.86), (0, 0, -1), .43, 8, 0, .0, .02, .04, .05, a0=math.pi / 8, stud=False)
    P.append(suction)
    # suction-line elbow flow taps (the loop flow element is an elbow tap set): two root valves and tubing
    taps = Part('elbow flow taps')
    for sx in (-1, 1):
        a = math.radians(225); y0 = .5 + R + R * math.sin(a); z0 = cz + R + R * math.cos(a)
        base = V((cx + sx * r, y0, z0))
        sweep(taps, [base, base + V((sx * .22, 0, 0)), base + V((sx * .26, -.04, 0)), V((cx + sx * .62, .12, z0))], .025, 'stainless_brushed', 6)
        box(taps, (cx + sx * .55 - .06, .62, z0 - .06), (cx + sx * .55 + .06, .74, z0 + .06), 'dark_steel')
        cyl(taps, .035, .74, .82, 'safety_yellow', (cx + sx * .55, 0, z0), n=6)
    P.append(taps)
    # ---- casing (bowl), cover closure studs, discharge nozzle
    casing = Part('pump casing', solid=True)
    lathe(casing, [(.36, 1.42), (.38, 1.55), (.62, 1.62), (.95, 1.78), (1.2, 2.02), (1.31, 2.3), (1.31, 2.68), (1.24, 2.88),
                   (1.38, 2.92), (1.38, 3.1)], 'stainless_cast', C, n=32,
          tiles=[None, 'machined', None, None, None, None, None, 'stainless_cast', 'stainless_brushed'])
    lathe(casing, [(1.38, 3.1), (1.37, 3.1), (1.37, 3.135)], 'gasket', C, n=32)
    lathe(casing, [(1.37, 3.135), (1.38, 3.135), (1.38, 3.32), (1.02, 3.32), (1.02, 3.52)], 'stainless_brushed', C, n=32,
          tiles=[None, None, 'stainless_cast', 'machined'])
    P.append(casing)
    closure = Part('cover closure studs')
    bolt_ring(closure, C, (0, 1, 0), 1.235, 20, 2.84, 3.32, .042, .082, .1, a0=math.pi / 20, under=False)
    for i in range(20):
        th = math.pi / 20 + TAU * i / 20
        nut(closure, (cx + 1.235 * math.cos(th), 2.92, cz + 1.235 * math.sin(th)), (0, -1, 0), .082, .09)
    P.append(closure)
    dis = Part('discharge nozzle')
    lathe(dis, [(.47, -.2), (.47, .9), (.45, 1.05), (.45, 2.1), (.5, 2.25), (.6, 2.25), (.6, 2.4), (.36, 2.4)], 'stainless_cast',
          (cx, 2.5, 2.4), (0, 0, -1), 24, tiles=[None, None, None, 'machined', 'machined', 'machined', 'machined'])
    lathe(dis, [(.47, 0), (.47, .13)], 'discharge_teal', (cx, 2.5, .62), (0, 0, -1), 24, cover=True)
    disc(dis, .36, 2.4, 'gasket', (cx, 2.5, 2.4), (0, 0, -1), 24, up=True, r_in=.3)
    disc(dis, .3, 2.395, 'interior', (cx, 2.5, 2.4), (0, 0, -1), 12, up=True)
    bolt_ring(dis, (cx, 2.5, .15), (0, 0, 1), .54, 12, 0, 0, .02, .045, .06, a0=math.pi / 12, stud=False)
    P.append(dis)
    # ---- supports: four cast lugs, columns and base plates; two lateral snubbers at the casing bottom
    sup = Part('casing supports')
    for k in range(4):
        th = math.pi / 4 + k * math.pi / 2; ux, uz = math.cos(th), math.sin(th)
        lug_c = V((cx + ux * 1.42, 0, cz + uz * 1.42))
        box(sup, (lug_c.x - .14, 2.06, lug_c.z - .14), (lug_c.x + .14, 2.3, lug_c.z + .14), 'stainless_cast', rot=-th, pivot=lug_c)
        col = V((cx + ux * 1.56, 0, cz + uz * 1.56))
        box(sup, (col.x - .1, .1, col.z - .1), (col.x + .1, 2.06, col.z + .1), 'paint_stand', tiles={'+y': None})
        box(sup, (col.x - .24, 0, col.z - .24), (col.x + .24, .1, col.z + .24), 'paint_stand', tiles={'-y': None})
        for dx in (-.16, .16):
            for dz in (-.16, .16): nut(sup, (col.x + dx, .1, col.z + dz), (0, 1, 0), .035, .05)
    P.append(sup)
    snub = Part('casing snubbers')
    for sx in (-1, 1):
        a = V((cx + sx * .78, 1.72, cz)); b = V((cx + sx * 2.25, .1, cz))
        d = (b - a).normalized(); m = a + (b - a) * .5
        cyl(snub, .075, 0, (m - a).length + .1, 'paint_stand', a, d, 10)
        cyl(snub, .035, (m - a).length + .1, (b - a).length - .06, 'chrome', a, d, 8, caps=(False, True))
        box(snub, (b.x - .2, 0, b.z - .2), (b.x + .2, .08, b.z + .2), 'dark_steel', tiles={'-y': None})
        box(snub, (b.x - .04, .08, b.z - .1), (b.x + .04, .2, b.z + .1), 'dark_steel')
        box(snub, (a.x - .05 * sx - .05, a.y - .1, a.z - .12), (a.x - .05 * sx + .05, a.y + .1, a.z + .12), 'stainless_cast')
    P.append(snub)
    # ---- seal cartridge housing, shaft and coupling
    seal = Part('seal cartridge housing', solid=True)
    lathe(seal, [(1.02, 3.52), (.62, 3.56), (.55, 3.6), (.55, 4.04), (.63, 4.04), (.63, 4.14), (.4, 4.14), (.4, 4.2), (.16, 4.2)],
          'stainless_brushed', C, 24, tiles=[None, None, None, 'machined', 'machined', 'machined', 'machined', 'machined'])
    bolt_ring(seal, C, (0, 1, 0), .53, 8, 4.0, 4.14, .02, .04, .05, stud=False)
    P.append(seal)
    sealtubes = Part('seal staging and leak-off tubing')
    # two cavity-pressure lines per side leave through the window, drop past the casing and run to a local rack
    ports = [(math.radians(-62), 3.96, 0), (math.radians(-70), 3.84, 1), (math.radians(-118), 3.96, 0), (math.radians(-110), 3.84, 1)]
    for i, (th, y, k) in enumerate(ports):
        east = math.cos(th) > 0
        p0 = V((cx + .55 * math.cos(th), y, cz + .55 * math.sin(th)))
        p1 = V((cx + 1.3 * math.cos(th), y, cz + 1.3 * math.sin(th)))
        p2 = V((cx + 1.62 * math.cos(th), y - .25, cz + 1.62 * math.sin(th)))
        p3 = V((p2.x, 1.05 + .1 * k, p2.z))
        rack_x = 4.45 if east else .55
        p4 = V((p2.x, p3.y, .8 + .08 * k))
        p5 = V((rack_x + (-.12 if east else .12), p3.y, .8 + .08 * k))
        sweep(sealtubes, [p0, p1, p2, p3, p4, p5], .024, 'stainless_brushed', 6)
        cyl(sealtubes, .05, y - .05, y + .05, 'machined', (p0.x, 0, p0.z), n=6)
    # local seal instrument racks with two gauges each (seal cavity pressure, stage 1 and 2)
    for rack_x in (.55, 4.45):
        box(sealtubes, (rack_x - .05, 0, .65), (rack_x + .05, 1.55, .73), 'paint_stand')
        box(sealtubes, (rack_x - .22, 0, .55), (rack_x + .22, .05, .85), 'dark_steel', tiles={'-y': None})
        box(sealtubes, (rack_x - .2, 1.05, .6), (rack_x + .2, 1.5, .66), 'dark_steel')
        for k, gx in enumerate((-.1, .1)):
            cyl(sealtubes, .085, 0, .05, 'dark_steel', (rack_x + gx, 1.3, .6), (0, 0, -1), 12, caps=(False, False))
            disc(sealtubes, .085, .05, 'gauge', (rack_x + gx, 1.3, .6), (0, 0, -1), 12, up=True)
    P.append(sealtubes)
    shaft = Part('shaft and spacer coupling')
    cyl(shaft, .13, 4.2, 6.1, 'machined', C, n=12, caps=(False, False))
    lathe(shaft, [(.13, 4.45), (.27, 4.45), (.27, 4.7), (.32, 4.7), (.32, 4.78), (.17, 4.78)], 'machined', C, 16)
    cyl(shaft, .17, 4.78, 5.17, 'safety_yellow', C, n=12, caps=(False, False), cover=True)
    lathe(shaft, [(.17, 5.17), (.32, 5.17), (.32, 5.25), (.27, 5.25), (.27, 5.5), (.13, 5.5)], 'machined', C, 16)
    for yy in (4.78, 5.17):
        bolt_ring(shaft, (cx, yy, cz), (0, 1, 0) if yy < 5 else (0, -1, 0), .25, 6, 0, 0, .015, .03, .04, stud=False)
    P.append(shaft)
    # ---- motor stand: tapered fabricated shell with front and back access windows behind mesh coupling guards
    stand = Part('motor stand'); guard = Part('coupling guards')
    N = 32; y_bot, y_w0, y_w1, y_top = 3.64, 3.86, 5.6, 5.86
    def rad(y, inner=False):
        r = 1.1 + (1.26 - 1.1) * (y - y_bot) / (y_top - y_bot)
        return r - (.08 if inner else 0)
    win = lambda th: min(abs(((th - (-math.pi / 2) + math.pi) % TAU) - math.pi), abs(((th - (math.pi / 2) + math.pi) % TAU) - math.pi)) < math.radians(40)
    def ring_pt(r, y, th): return V((cx + r * math.cos(th), y, cz + r * math.sin(th)))
    for j in range(N):
        th0, th1 = TAU * j / N, TAU * (j + 1) / N; tm = (th0 + th1) / 2; out = V((math.cos(tm), 0, math.sin(tm)))
        bands = [(y_bot, y_w0), (y_w1, y_top)] if win(tm) else [(y_bot, y_top)]
        for ya, yb in bands:
            stand.face([ring_pt(rad(ya), ya, th0), ring_pt(rad(ya), ya, th1), ring_pt(rad(yb), yb, th1), ring_pt(rad(yb), yb, th0)],
                       [(0, 0), (1, 0), (1, 1), (0, 1)], 'paint_stand', out)
            stand.face([ring_pt(rad(ya, 1), ya, th0), ring_pt(rad(ya, 1), ya, th1), ring_pt(rad(yb, 1), yb, th1), ring_pt(rad(yb, 1), yb, th0)],
                       [(0, 0), (1, 0), (1, 1), (0, 1)], 'interior', -out)
        if win(tm):
            for yy, up in ((y_w0, True), (y_w1, False)):
                stand.face([ring_pt(rad(yy), yy, th0), ring_pt(rad(yy), yy, th1), ring_pt(rad(yy, 1), yy, th1), ring_pt(rad(yy, 1), yy, th0)],
                           [(0, 0), (1, 0), (1, 1), (0, 1)], 'paint_stand', V((0, 1 if up else -1, 0)))
            # curved mesh guard just outside the opening
            g = .012
            guard.face([ring_pt(rad(y_w0) + g, y_w0, th0), ring_pt(rad(y_w0) + g, y_w0, th1), ring_pt(rad(y_w1) + g, y_w1, th1), ring_pt(rad(y_w1) + g, y_w1, th0)],
                       [(0, 0), (1, 0), (1, 1), (0, 1)], 'guard_mesh', out)
        # window jambs
        nxt = (th1 + th1 - tm)
        if win(tm) != win(nxt):
            edge = th1; tang = V((-math.sin(edge), 0, math.cos(edge))) * (1 if win(tm) else -1)
            stand.face([ring_pt(rad(y_w0), y_w0, edge), ring_pt(rad(y_w1), y_w1, edge), ring_pt(rad(y_w1, 1), y_w1, edge), ring_pt(rad(y_w0, 1), y_w0, edge)],
                       [(0, 0), (0, 1), (1, 1), (1, 0)], 'paint_stand', -tang)
            # guard frame strip on the jamb
            box(guard, (-.035, y_w0, -.035), (.035, y_w1, .035), 'safety_yellow')
            gf = guard.faces[-6:]; del guard.faces[-6:]
            pc = ring_pt(rad((y_w0 + y_w1) / 2) + .03, 0, edge)
            for pts, st, t in gf:
                guard.faces.append(([V((pc.x + q[0], q[1], pc.z + q[2])) for q in pts], st, t))
    for yy in (y_w0, y_w1):
        for side in (-1, 1):
            lathe(guard, [(rad(yy) + .045, yy - .03), (rad(yy) + .045, yy + .03)], 'safety_yellow', C, 8,
                  arc=(side * math.pi / 2 - math.radians(40), side * math.pi / 2 + math.radians(40)), cover=True)
    lathe(stand, [(1.0, 3.52), (1.2, 3.52), (1.2, y_bot), (1.1, y_bot)], 'paint_band', C, N, cover=True)
    lathe(stand, [(rad(y_top), y_top), (1.44, y_top), (1.44, 6.0)], 'paint_band', C, N, cover=True)
    bolt_ring(stand, C, (0, 1, 0), 1.155, 16, 3.52, y_bot, .015, .032, .04, a0=math.pi / 16, stud=False)
    # side gussets on the solid east/west panels
    for th in (0, math.pi):
        for off in (-.3, .3):
            ln = Part('gusset')
            box(ln, (-.035, y_bot, 0), (.035, y_top, .12), 'paint_stand')
            place_radial(stand, ln, th + off, (cx, cz), rad)
    P += [stand, guard]
    # ---- motor: lower bracket, ribbed frame, air-water coolers, upper bracket and thrust-bearing oil pot
    motor = Part('motor frame', solid=True); M0, M1 = 6.0, 9.0; RM = 1.36; NM = 20
    lathe(motor, [(1.44, M0), (1.48, M0 + .04), (1.48, M0 + .14), (RM, M0 + .14)], 'paint_band', C, NM, cover=True, a0=math.pi / NM)
    lathe(motor, [(RM, M0 + .14), (RM, M1 - .12)], 'motor_fins', C, NM, a0=math.pi / NM)
    lathe(motor, [(RM, M1 - .12), (1.48, M1 - .12), (1.48, M1), (1.22, M1 + .22), (1.0, M1 + .3), (.98, M1 + .3)], 'paint_band', C, NM,
          a0=math.pi / NM, tiles=[None, None, 'paint_motor', 'paint_motor', None])
    lathe(motor, [(1.4, 7.5), (1.42, 7.53), (1.42, 7.6), (1.4, 7.63)], 'paint_band', C, NM, a0=math.pi / NM, cover=True)
    lathe(motor, [(.98, M1 + .3), (.98, 9.58), (1.03, 9.58), (1.03, 9.64), (.62, 9.72), (.14, 9.74)], 'paint_band', C, 20,
          tiles=['paint_motor', None, None, 'paint_motor', 'paint_motor'])
    cyl(motor, .14, 9.74, 9.86, 'dark_steel', C, n=8, caps=(False, True))
    # cooling ribs (fins) on the frame, clear of the coolers (east/west) and the front plate pad
    for k in range(24):
        th = TAU * (k + .5) / 24
        if abs(math.cos(th)) > .82 or (math.sin(th) < -.86): continue
        rib = Part('rib'); box(rib, (-.03, M0 + .16, 0), (.03, M1 - .14, .14), 'paint_motor', tiles={'-y': None, '+y': 'paint_band'})
        place_radial(motor, rib, th, (cx, cz), lambda y: RM - .02)
    P.append(motor)
    cool = Part('air-water coolers', solid=True); cw = Part('cooling water piping')
    for sx in (-1, 1):
        xa, xb = (cx + 1.12, cx + 1.92) if sx > 0 else (cx - 1.92, cx - 1.12)
        outer = '+x' if sx > 0 else '-x'; inner = '-x' if sx > 0 else '+x'
        box(cool, (xa, 6.62, 1.86), (xb, 8.5, 3.14), 'paint_motor', tiles={outer: 'cooler_louver', inner: None})
        box(cool, (xa, 6.54, 1.78), (xb, 6.64, 3.22), 'paint_band', tiles={inner: None})
        box(cool, (xa, 8.48, 1.78), (xb, 8.58, 3.22), 'paint_band', tiles={inner: None})
        xm = (xa + xb) / 2
        for zz in (1.72, 3.28):
            zl, zh = (zz - .1, zz + .04) if zz < 2.5 else (zz - .04, zz + .1)
            box(cool, (xm - .24, 6.7, zl), (xm + .24, 8.42, zh), 'paint_band')
        # supply and return: down from the water boxes, then out through the side face to the plant cooling water
        for zz in (1.6, 3.4):
            top = V((xm + sx * .1, 6.72, zz)); edge = cx + sx * 2.44
            pth = [top, V((top.x, 6.2, zz)), V((top.x + sx * .12, 6.04, zz)), V((edge, 6.04, zz))]
            sweep(cw, pth, .07, 'cw_green', 10, caps=(False, False))
            lathe(cw, [(.12, 0), (.12, .06), (.07, .06)], 'machined', (edge, 6.04, zz), (sx, 0, 0), 10, cover=True)
            lathe(cw, [(.07, -.02), (.12, -.02), (.12, .0)], 'machined', (edge, 6.04, zz), (sx, 0, 0), 10, cover=True)
            disc(cw, .07, .06, 'interior', (edge, 6.04, zz), (sx, 0, 0), 8, up=True)
            lathe(cw, [(.1, 0), (.1, .08)], 'paint_band', (top.x, 6.62, zz), (0, 1, 0), 8, cover=True)
    P += [cool, cw]
    trim = Part('motor trim')
    # terminal box on the back with high-voltage label and a conduit to the floor
    box(trim, (2.08, 7.1, cz + 1.4), (2.92, 8.0, cz + 1.86), 'paint_motor', tiles={'+z': 'paint_band'})
    box(trim, (2.22, 7.25, cz + 1.86), (2.4, 7.43, cz + 1.87), 'label_hv')
    bolt_ring(trim, (2.5, 7.55, cz + 1.86), (0, 0, 1), .33, 6, 0, 0, .01, .022, .02, stud=False)
    sweep(trim, [V((2.5, 7.1, cz + 1.63)), V((2.5, 6.55, cz + 1.63)), V((2.5, 6.4, cz + 1.75)), V((2.5, .1, cz + 1.75))], .075, 'black_cable', 8)
    box(trim, (2.36, 0, cz + 1.61), (2.64, .12, cz + 1.89), 'dark_steel', tiles={'-y': None})
    # nameplates on the front of the motor frame
    zf = cz - RM - .015
    box(trim, (2.12, 6.42, zf - .02), (2.88, 6.8, zf), 'paint_band', tiles={'-z': 'plate_pump'})
    box(trim, (2.12, 7.8, zf - .02), (2.88, 8.18, zf), 'paint_band', tiles={'-z': 'plate_motor'})
    # rotation arrow on the upper bracket, vibration probe and oil sight glass on the bearing pot
    box(trim, (2.36, 9.36, cz - 1.03), (2.64, 9.5, cz - .99), 'paint_band', tiles={'-z': 'arrow'})
    box(trim, (cx + .6, 9.38, cz - .82), (cx + .78, 9.52, cz - .66), 'dark_steel', rot=math.radians(-40), pivot=(cx + .69, 0, cz - .74))
    sweep(trim, [V((cx + .76, 9.45, cz - .72)), V((cx + 1.0, 9.45, cz - .4)), V((cx + 1.18, 9.05, cz - .3))], .025, 'black_cable', 6)
    box(trim, (cx - .78, 9.36, cz - .76), (cx - .58, 9.56, cz - .7), 'dark_steel', tiles={'-z': 'sight_glass'}, rot=math.radians(30), pivot=(cx - .68, 0, cz - .73))
    # lifting lugs on the upper bracket
    for k in range(4):
        th = math.pi / 4 + k * math.pi / 2
        lug = Part('lug'); box(lug, (-.04, M1 - .04, -.02), (.04, M1 + .28, .3), 'paint_band', tiles={'+x': 'hazard', '-x': 'hazard'})
        place_radial(trim, lug, th, (cx, cz), lambda y: 1.12)
    P.append(trim)
    scene = emit('BWR | recirc | recirculation pump 5x10x5', P)
    export(scene, 'recirculation_pump', [5, 10, 5], [2, 0, 2],
           [{'role': 'WATER_SUCTION', 'cell': [2, 0, 4], 'face': 'south'}, {'role': 'WATER_DISCHARGE', 'cell': [2, 2, 0], 'face': 'north'}])

# =====================================================================================================================
# Paired jet pump, 1 x 6 x 1 (facing north: the riser elbow and the brace point south, at the vessel wall).
# Two diffusers with slip joints, two inlet mixers, the central riser with its transition piece, elbows down into the
# five-hole drive nozzles, the hold-down beam, restrainer brackets and the riser brace, after FSAR Fig 3.9-4.
# =====================================================================================================================
def jet_pump():
    P = []; jz = .42; rz = .6; xs = (.27, .73)
    jets = Part('diffusers and inlet mixers')
    for x in xs:
        c = (x, 0, jz)
        lathe(jets, [(0, 0), (.17, 0), (.17, .14), (.152, .17), (.112, 1.86), (.124, 1.88), (.124, 2.12), (.114, 2.14), (.114, 2.2),
                     (.084, 2.22), (.084, 4.9), (.11, 4.92), (.11, 5.0), (.122, 5.0), (.146, 5.13), (.134, 5.15), (.07, 5.15)],
              'jet_stainless', c, n=14,
              tiles=['jet_stainless', 'jet_weld', 'jet_stainless', None, 'jet_weld', 'jet_weld', 'jet_weld', 'jet_weld', 'jet_weld',
                     'jet_stainless', None, 'jet_weld', 'jet_weld', None, 'jet_stainless', 'interior'])
        lathe(jets, [(.084, 3.5), (.092, 3.52), (.092, 3.56), (.084, 3.58)], 'jet_weld', c, n=14, cover=True)
        # slip-joint clamp blocks
        for side in (-1, 1):
            box(jets, (x + side * .12 - .025, 1.9, jz - .05), (x + side * .12 + .025, 2.1, jz + .05), 'jet_weld')
        # drive nozzle body inside the suction bell
        cyl(jets, .062, 5.08, 5.34, 'jet_stainless', c, n=10, caps=(False, False))
        # restrainer bracket wedge on the outer side of each mixer
        side = -1 if x < .5 else 1
        box(jets, (x + side * .084 - .02, 2.5, jz - .045), (x + side * .084 + .03 * side + .02, 2.72, jz + .045), 'jet_weld')
        # diffuser instrument line to the wall
        sweep(jets, [V((x, .5, jz + .15)), V((x, .5, .82)), V((x, .44, .97))], .016, 'stainless_brushed', 5)
    P.append(jets)
    riser = Part('riser, elbow and thermal sleeve')
    R = .2; y0 = .78
    path = [V((.5, y0, .985)), V((.5, y0, rz + R))] + arc_path((.5, y0 + R, rz + R), R, math.radians(180), math.radians(270), (0, 1, 0), (0, 0, 1), 8)[1:]
    path += [V((.5, 5.42, rz))]
    sweep(riser, path, .082, 'jet_stainless', 12)
    lathe(riser, [(.082, 0), (.105, .0), (.105, .06), (.082, .06)], 'jet_weld', (.5, y0, .9), (0, 0, 1), 12, cover=True)
    disc(riser, .082, .085, 'interior', (.5, y0, .9), (0, 0, 1), 10, up=True)
    for yy in (1.6, 4.6):
        lathe(riser, [(.082, yy), (.09, yy + .01), (.09, yy + .04), (.082, yy + .05)], 'jet_weld', (.5, 0, rz), n=12, cover=True)
    # transition piece and elbows into the nozzles
    box(riser, (.36, 5.4, rz - .1), (.64, 5.66, rz + .09), 'jet_stainless')
    for x in xs:
        sx = -1 if x < .5 else 1
        sweep(riser, bezier((.5 + sx * .12, 5.53, rz - .02), (sx, 0, 0), (x, 5.33, jz), (0, -1, 0), 8), .06, 'jet_stainless', 10)
    # hold-down beam with its bolt and the lifting ears
    box(riser, (.12, 5.66, rz - .13), (.88, 5.76, rz + .03), 'jet_weld')
    cyl(riser, .035, 5.76, 5.95, 'machined', (.5, 0, rz - .05), n=6, caps=(False, True))
    nut(riser, (.5, 5.76, rz - .05), (0, 1, 0), .06, .06)
    for x in (.16, .84):
        ear = arc_path((x, 5.84, rz - .05), .07, 0, TAU, (1, 0, 0), (0, 1, 0), 10)
        sweep(riser, ear, .018, 'jet_weld', 5)
    P.append(riser)
    braces = Part('restrainer brackets and riser brace')
    lathe(braces, [(.082, 2.5), (.11, 2.5), (.11, 2.72), (.082, 2.72)], 'jet_weld', (.5, 0, rz), n=12, cover=True)
    for x in xs:
        x0, x1 = sorted((x + (.09 if x < .5 else -.09), .5 + (-.1 if x < .5 else .1)))
        box(braces, (x0, 2.55, jz - .03), (x1, 2.67, rz + .03), 'jet_weld')
    lathe(braces, [(.082, 3.62), (.11, 3.62), (.11, 3.8), (.082, 3.8)], 'jet_weld', (.5, 0, rz), n=12, cover=True)
    for dx in (-.07, .07):
        box(braces, (.5 + dx - .015, 3.64, rz + .08), (.5 + dx + .015, 3.78, .995), 'jet_weld')
    box(braces, (.38, 3.6, .94), (.62, 3.82, .995), 'jet_stainless')
    P.append(braces)
    scene = emit('BWR | recirc | jet pump pair 1x6x1', P)
    export(scene, 'jet_pump', [1, 6, 1], [0, 0, 0], [])

# =====================================================================================================================
# Reactor internal pump, 2 x 5 x 2. Axis (1, 1). Bottom-head mounting flange in layer 3 (anchor y = 3), wet end with
# diffuser and impeller above, wet-motor casing hanging below with its bolted motor cover, cooling-water nozzles to the
# RIP heat exchanger, purge line and cable connector.
# =====================================================================================================================
def rip_pump():
    P = []; C = (1, 0, 1); cx = cz = 1
    wet = Part('wet end: diffuser, impeller and nozzle stub', solid=True)
    # stretch-tube nozzle stub through the bottom head, the diffuser barrel with external vane ribs, the inlet bell
    lathe(wet, [(.4, 3.24), (.4, 3.66), (.5, 3.7), (.5, 4.42), (.44, 4.5), (.38, 4.7), (.4, 4.76), (.4, 4.95), (.32, 4.95), (.32, 4.62)],
          'stainless_brushed', C, 20, tiles=[None, 'jet_weld', 'stainless_cast', 'jet_weld', 'stainless_cast', 'machined', 'machined', 'machined', 'interior'])
    lathe(wet, [(.32, 4.62), (.12, 4.62), (.05, 4.84), (0, 4.86)], 'machined', C, 12)
    for k in range(7):
        th = TAU * k / 7
        v = Part('vane'); box(v, (-.012, 4.62, .1), (.012, 4.8, .3), 'machined')
        place_radial(wet, v, th, (cx, cz), lambda y: 0)
    for k in range(8):
        th = TAU * (k + .5) / 8
        rib = Part('diffuser rib'); box(rib, (-.025, 3.74, 0), (.025, 4.38, .06), 'stainless_cast', tiles={'-y': None})
        place_radial(wet, rib, th, (cx, cz), lambda y: .49)
    P.append(wet)
    flange = Part('mounting flange and motor casing', solid=True)
    lathe(flange, [(.4, 3.0), (.9, 3.0), (.92, 3.04), (.92, 3.2), (.88, 3.24), (.4, 3.24)], 'stainless_cast', C, 24,
          tiles=[None, 'machined', 'machined', 'machined', None])
    bolt_ring(flange, C, (0, 1, 0), .78, 16, 2.88, 3.24, .03, .055, .07, a0=math.pi / 16)
    for i in range(16):
        th = math.pi / 16 + TAU * i / 16
        nut(flange, (cx + .78 * math.cos(th), 3.0, cz + .78 * math.sin(th)), (0, -1, 0), .055, .07)
    lathe(flange, [(.14, .27), (.4, .32), (.5, .44), (.62, .46), (.62, .66), (.47, .66), (.47, 2.74), (.64, 2.76), (.64, 2.98), (.4, 3.0)],
          'rip_casing', C, 20, tiles=['stainless_cast', 'stainless_cast', None, 'machined', 'machined', None, None, 'machined', 'machined'])
    for yy in (1.3, 2.1):
        lathe(flange, [(.47, yy), (.5, yy + .02), (.5, yy + .08), (.47, yy + .1)], 'jet_weld', C, 20, cover=True)
    bolt_ring(flange, C, (0, -1, 0), .55, 12, -.46, -.46 + .0, .028, .055, .07, a0=math.pi / 12, stud=False)
    cyl(flange, .12, .1, .27, 'dark_steel', C, n=10, caps=(True, False))
    P.append(flange)
    cable = Part('power cable and plate')
    sweep(cable, [V((cx, .14, cz)), V((cx, .1, cz + .18)), V((cx, .1, 1.98))], .055, 'black_cable', 8)
    box(cable, (cx - .2, 1.6, cz - .49), (cx + .2, 1.8, cz - .47), 'paint_band', tiles={'-z': 'plate_rip'})
    P.append(cable)
    pipes = Part('cooling water and purge lines')
    for yy in (.95, 2.35):
        sweep(pipes, [V((cx + .3, yy, cz - .3)), V((cx + .62, yy, cz - .62)), V((cx + .7, yy, .02))], .075, 'cw_green', 10)
        lathe(pipes, [(.075, 0), (.12, 0), (.12, .06), (.06, .06)], 'machined', (cx + .7, yy, .08), (0, 0, -1), 10, cover=True)
        disc(pipes, .06, .06, 'interior', (cx + .7, yy, .08), (0, 0, -1), 8, up=True)
    sweep(pipes, [V((cx - .45, 2.5, cz + .1)), V((cx - .8, 2.5, cz + .1)), V((cx - .8, 1.2, cz + .1)), V((cx - .8, 1.2, 1.98))], .025, 'copper', 6)
    P.append(pipes)
    scene = emit('BWR | recirc | reactor internal pump 2x5x2', P)
    export(scene, 'rip_pump', [2, 5, 2], [0, 3, 0], [])

recirc_pump(); jet_pump(); rip_pump()
for s in SCENES.values(): s.render.engine = 'BLENDER_WORKBENCH'
default = bpy.data.scenes.get('Scene')
if default is not None:
    for o in list(default.collection.objects): bpy.data.objects.remove(o, do_unlink=True)
    bpy.context.window.scene = next(iter(SCENES.values())) if bpy.context.window else None
    bpy.data.scenes.remove(default)
bpy.ops.wm.save_as_mainfile(filepath=str(HERE / 'recirc_remodel.blend'), compress=True)
print('saved', HERE / 'recirc_remodel.blend')
