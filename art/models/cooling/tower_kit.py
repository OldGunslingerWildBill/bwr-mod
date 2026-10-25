"""Headless Blender kit for the cooling-tower remodel (alpha.54).

Run with:  blender.exe --background --python art/models/cooling/build_towers_v2.py -- natural|mechanical|fan|lod|all
Geometry is authored in Minecraft coordinates (x, y up, z; one unit is one block), emitted as triangles with
explicit outward winding, turned into Blender mesh objects (Blender Z up, so xyz(p) = (x, -z, y)) and written in
the same JSON schema the exporter (tools-export-cooling.py) already reads.  Nothing here needs the GUI.
"""
import bpy, math, json
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'art/models/cooling'
TAU = math.tau
Y = Vector((0, 1, 0))

# ---- palette: flat sRGB colours, no textures (the models use the white_concrete tile tinted by Kd) --------------
PALETTE = {
    'concrete a': (.700, .710, .690), 'concrete b': (.720, .728, .708), 'concrete c': (.684, .694, .676),
    'concrete d': (.708, .716, .700), 'concrete e': (.692, .702, .684),
    'concrete joint': (.590, .600, .585), 'concrete inner': (.520, .530, .520), 'concrete basin': (.575, .600, .600),
    'concrete beam': (.650, .660, .640), 'plinth': (.500, .520, .520),
    'water': (.170, .485, .556), 'steel': (.650, .680, .690), 'steel dark': (.360, .390, .410),
    'fill dark': (.150, .180, .170), 'fill light': (.235, .275, .255),
    'louver': (.205, .260, .230), 'louver back': (.060, .075, .068),
    'fan deck': (.640, .650, .625), 'distribution a': (.205, .285, .255), 'distribution b': (.245, .335, .295),
    'lamp red': (.860, .090, .070), 'tread': (.220, .250, .250),
    'machined steel': (.722, .767, .792), 'gasket': (.160, .183, .200), 'hot band': (.854, .381, .172),
    'cold band': (.128, .566, .767), 'pipe steel': (.527, .575, .601), 'motor blue': (.143, .381, .566),
    'amber': (.906, .566, .172), 'fan blade': (.560, .600, .610), 'fan hub': (.300, .340, .360),
}
CONCRETES = ['concrete a', 'concrete b', 'concrete c', 'concrete d', 'concrete e']


def lin(c):
    return c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4


def srgb(x):
    return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055


def xyz(p):
    return Vector((p[0], -p[2], p[1]))


class Kit:
    def __init__(self, name):
        self.name = name
        self.tris = {}            # (group, material) -> [(a, b, c)]

    # ------------------------------------------------------------------ primitives
    def emit(self, g, m, a, b, c, out):
        """One triangle, wound so its normal faces the direction `out`."""
        a, b, c = Vector(a), Vector(b), Vector(c)
        n = (b - a).cross(c - a)
        if n.length < 1e-9:
            return
        if n.dot(out) < 0:
            b, c = c, b
        self.tris.setdefault((g, m), []).append((a[:], b[:], c[:]))

    def quad(self, g, m, a, b, c, d, out):
        self.emit(g, m, a, b, c, out)
        self.emit(g, m, a, c, d, out)

    def quad2(self, g, m, a, b, c, d):
        """Thin plate seen from both sides."""
        n = (Vector(b) - Vector(a)).cross(Vector(c) - Vector(a))
        self.quad(g, m, a, b, c, d, n)
        self.quad(g, m, a, b, c, d, -n)

    def box(self, g, m, lo, hi):
        (x, y, z), (X, Y_, Z) = lo, hi
        P = lambda i, j, k: (X if i else x, Y_ if j else y, Z if k else z)
        self.quad(g, m, P(0, 0, 0), P(0, 1, 0), P(0, 1, 1), P(0, 0, 1), (-1, 0, 0))
        self.quad(g, m, P(1, 0, 0), P(1, 1, 0), P(1, 1, 1), P(1, 0, 1), (1, 0, 0))
        self.quad(g, m, P(0, 0, 0), P(1, 0, 0), P(1, 0, 1), P(0, 0, 1), (0, -1, 0))
        self.quad(g, m, P(0, 1, 0), P(1, 1, 0), P(1, 1, 1), P(0, 1, 1), (0, 1, 0))
        self.quad(g, m, P(0, 0, 0), P(1, 0, 0), P(1, 1, 0), P(0, 1, 0), (0, 0, -1))
        self.quad(g, m, P(0, 0, 1), P(1, 0, 1), P(1, 1, 1), P(0, 1, 1), (0, 0, 1))

    def obox(self, g, m, c, ax, ay, az, hx, hy, hz):
        """Oriented box: centre, three unit axes and half sizes."""
        c, ax, ay, az = Vector(c), Vector(ax), Vector(ay), Vector(az)
        C = lambda i, j, k: c + ax * (hx * i) + ay * (hy * j) + az * (hz * k)
        for s in (-1, 1):
            self.quad(g, m, C(s, -1, -1), C(s, 1, -1), C(s, 1, 1), C(s, -1, 1), ax * s)
            self.quad(g, m, C(-1, s, -1), C(1, s, -1), C(1, s, 1), C(-1, s, 1), ay * s)
            self.quad(g, m, C(-1, -1, s), C(1, -1, s), C(1, 1, s), C(-1, 1, s), az * s)

    def beam(self, g, m, p0, p1, w, h=None, up=Y, caps=True):
        """Square-section bar from p0 to p1 (w across, h along `up`-ish)."""
        p0, p1 = Vector(p0), Vector(p1)
        d = p1 - p0
        if d.length < 1e-9:
            return
        d.normalize()
        side = d.cross(Vector(up))
        if side.length < 1e-6:
            side = d.cross(Vector((1, 0, 0)))
        side.normalize()
        third = d.cross(side).normalized()
        hw, hh = w / 2, (h if h else w) / 2
        C = lambda p, i, j: p + side * (hw * i) + third * (hh * j)
        for (i0, j0, i1, j1) in ((-1, -1, 1, -1), (1, -1, 1, 1), (1, 1, -1, 1), (-1, 1, -1, -1)):
            out = side * (i0 + i1) / 2 + third * (j0 + j1) / 2
            self.quad(g, m, C(p0, i0, j0), C(p1, i0, j0), C(p1, i1, j1), C(p0, i1, j1), out)
        if caps:
            for p, s in ((p0, -d), (p1, d)):
                self.quad(g, m, C(p, -1, -1), C(p, 1, -1), C(p, 1, 1), C(p, -1, 1), s)

    def basis(self, d):
        d = Vector(d).normalized()
        a = d.cross(Y if abs(d.y) < .9 else Vector((1, 0, 0))).normalized()
        return d, a, d.cross(a).normalized()

    def tube(self, g, m, p0, p1, r, n=8, r1=None, caps=True):
        """Solid cylinder / frustum along p0 -> p1."""
        p0, p1 = Vector(p0), Vector(p1)
        d, u, v = self.basis(p1 - p0)
        r1 = r if r1 is None else r1
        ring = lambda p, rr, i: p + (u * math.cos(i * TAU / n) + v * math.sin(i * TAU / n)) * rr
        for i in range(n):
            a, b = i * 1.0, (i + 1) % n
            mid = (u * math.cos((i + .5) * TAU / n) + v * math.sin((i + .5) * TAU / n))
            self.quad(g, m, ring(p0, r, i), ring(p0, r, b), ring(p1, r1, b), ring(p1, r1, i), mid)
            if caps:
                self.emit(g, m, p0, ring(p0, r, i), ring(p0, r, b), -d)
                self.emit(g, m, p1, ring(p1, r1, i), ring(p1, r1, b), d)

    def sleeve(self, g, m, p0, p1, outer, inner, n=20):
        """Hollow spool with annular ends (open bore)."""
        p0, p1 = Vector(p0), Vector(p1)
        d, u, v = self.basis(p1 - p0)
        ring = lambda p, rr, i: p + (u * math.cos(i * TAU / n) + v * math.sin(i * TAU / n)) * rr
        for i in range(n):
            b = (i + 1) % n
            mid = (u * math.cos((i + .5) * TAU / n) + v * math.sin((i + .5) * TAU / n))
            self.quad(g, m, ring(p0, outer, i), ring(p0, outer, b), ring(p1, outer, b), ring(p1, outer, i), mid)
            self.quad(g, m, ring(p0, inner, i), ring(p0, inner, b), ring(p1, inner, b), ring(p1, inner, i), -mid)
            self.quad(g, m, ring(p0, inner, i), ring(p0, outer, i), ring(p0, outer, b), ring(p0, inner, b), -d)
            self.quad(g, m, ring(p1, inner, i), ring(p1, outer, i), ring(p1, outer, b), ring(p1, inner, b), d)

    def bolt(self, g, m, p0, p1, r):
        self.tube(g, m, p0, p1, r, 6)

    # ------------------------------------------------------------------ surfaces of revolution about a vertical axis
    def revolve(self, g, mats, profile, cx, cz, n, side=1, theta0=0.0, skip=None):
        """Loft the (r, y) polyline around the vertical axis through (cx, cz).
        side=+1: the surface faces to the right of the profile when it is walked upward (outer skin when r grows
        outward); side=-1 faces the other way (inner skin).  `mats` is a name or a callable (band, segment)."""
        for k in range(len(profile) - 1):
            (r0, y0), (r1, y1) = profile[k], profile[k + 1]
            dr, dy = r1 - r0, y1 - y0
            nr, ny = dy * side, -dr * side
            for i in range(n):
                if skip and skip(i):
                    continue
                t0, t1 = theta0 + i * TAU / n, theta0 + (i + 1) * TAU / n
                tm = (t0 + t1) / 2
                P = lambda r, y, t: (cx + r * math.cos(t), y, cz + r * math.sin(t))
                out = (nr * math.cos(tm), ny, nr * math.sin(tm))
                m = mats(k, i) if callable(mats) else mats
                self.quad(g, m, P(r0, y0, t0), P(r0, y0, t1), P(r1, y1, t1), P(r1, y1, t0), out)

    def disc(self, g, m, cx, cz, y, r0, r1, n, up=True, theta0=0.0, theta1=None, mats=None, skip=None):
        """Flat annulus (or full disc when r0 == 0)."""
        theta1 = theta0 + TAU if theta1 is None else theta1
        for i in range(n):
            if skip and skip(i):
                continue
            t0 = theta0 + (theta1 - theta0) * i / n
            t1 = theta0 + (theta1 - theta0) * (i + 1) / n
            P = lambda r, t: (cx + r * math.cos(t), y, cz + r * math.sin(t))
            mm = mats(i) if mats else m
            out = (0, 1 if up else -1, 0)
            if r0 <= 1e-9:
                self.emit(g, mm, (cx, y, cz), P(r1, t0), P(r1, t1), out)
            else:
                self.quad(g, mm, P(r0, t0), P(r0, t1), P(r1, t1), P(r1, t0), out)

    def ring_box(self, g, m, cx, cz, rin, rout, y0, y1, n, mats=None, skip=None):
        dm = (lambda i: mats(0, i)) if mats else None
        self.revolve(g, mats or m, [(rout, y0), (rout, y1)], cx, cz, n, 1, skip=skip)
        self.revolve(g, mats or m, [(rin, y0), (rin, y1)], cx, cz, n, -1, skip=skip)
        self.disc(g, m, cx, cz, y1, rin, rout, n, True, mats=dm, skip=skip)
        self.disc(g, m, cx, cz, y0, rin, rout, n, False, mats=dm, skip=skip)

    def polygon(self, g, m, y, outer, holes=(), up=True):
        """Horizontal polygon with holes (points are (x, z)), triangulated by Blender's tessellator."""
        import mathutils.geometry as mg
        loops = [[Vector((p[0], p[1], 0)) for p in outer]] + [[Vector((p[0], p[1], 0)) for p in h] for h in holes]
        flat = [p for loop in loops for p in loop]
        for t in mg.tessellate_polygon(loops):
            a, b, c = (flat[i] for i in t)
            self.emit(g, m, (a.x, y, a.y), (b.x, y, b.y), (c.x, y, c.y), (0, 1 if up else -1, 0))

    # ------------------------------------------------------------------ output
    def triangle_count(self):
        return sum(len(v) for v in self.tris.values())

    def materials_used(self):
        return sorted({m for (_, m) in self.tris})

    def faces_json(self):
        faces = []
        for (g, m), tris in self.tris.items():
            for t in tris:
                faces.append({'vertices': [[round(c, 5) for c in p] + [.5, .5] for p in t], 'material': m, 'part': g})
        return faces

    def materials_json(self):
        used = self.materials_used()
        return {m: {'color': [round(c, 6) for c in PALETTE[m]], 'texture': 'minecraft:block/white_concrete', 'tint': -1}
                for m in used}

    def bounds(self):
        pts = [p for tris in self.tris.values() for t in tris for p in t]
        return [min(p[a] for p in pts) for a in range(3)] + [max(p[a] for p in pts) for a in range(3)]

    def write_json(self, path, meta):
        data = dict(meta, faces=self.faces_json(), boxes=meta.pop('boxes', []), materials=self.materials_json())
        Path(path).write_text(json.dumps(data, separators=(',', ':')), encoding='utf-8')
        print(Path(path).name, self.triangle_count(), 'triangles; bounds', [round(v, 2) for v in self.bounds()])

    # ------------------------------------------------------------------ Blender scene
    def blender_scene(self, extra_objects=()):
        """A new Blender scene holding one mesh object per (group, material)."""
        scene = bpy.data.scenes.new('BWR | ' + self.name)
        mats = {}
        for m in self.materials_used():
            bm = bpy.data.materials.new('BWR | ' + m)
            rgb = PALETTE[m]
            bm.diffuse_color = (*rgb, 1)
            bm.use_nodes = True
            bsdf = next(n for n in bm.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
            bsdf.inputs['Base Color'].default_value = (*[lin(c) for c in rgb], 1)
            bsdf.inputs['Roughness'].default_value = .85
            mats[m] = bm
        for (g, m), tris in self.tris.items():
            verts, faces = [], []
            for t in tris:
                base = len(verts)
                verts.extend(xyz(p) for p in t)
                faces.append((base, base + 1, base + 2))
            mesh = bpy.data.meshes.new(g)
            mesh.from_pydata(verts, [], faces)
            mesh.update()
            mesh.materials.append(mats[m])
            o = bpy.data.objects.new(g + ' | ' + m, mesh)
            scene.collection.objects.link(o)
        return scene


def studio(scene, target, eye, lens=35, size=(1400, 1200), sun_dir=(-0.5, -0.7, -0.6)):
    """Camera, sun, sky and a render setup that works with --background."""
    cam = bpy.data.cameras.new(scene.name + ' camera')
    cam.lens = lens
    cam.clip_end = 1000
    co = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(co)
    co.location = xyz(eye)
    co.rotation_euler = (xyz(target) - co.location).to_track_quat('-Z', 'Y').to_euler()
    scene.camera = co
    sun = bpy.data.lights.new(scene.name + ' sun', 'SUN')
    sun.energy = 3.2
    so = bpy.data.objects.new(sun.name, sun)
    scene.collection.objects.link(so)
    so.rotation_euler = Vector((sun_dir[0], -sun_dir[2], sun_dir[1])).to_track_quat('-Z', 'Y').to_euler()
    world = bpy.data.worlds.new(scene.name + ' sky')
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (.46, .55, .68, 1)
    bg.inputs['Strength'].default_value = 1.1
    scene.world = world
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'


def render(scene, path, samples=24):
    scene.render.filepath = str(path)
    try:
        scene.render.engine = 'CYCLES'
        scene.cycles.samples = samples
        scene.cycles.use_denoising = False
        scene.cycles.device = 'CPU'
        bpy.ops.render.render(write_still=True, scene=scene.name)
    except Exception as exc:  # fall back to the always-available Workbench engine
        print('Cycles failed, using Workbench:', exc)
        scene.render.engine = 'BLENDER_WORKBENCH'
        scene.display.shading.light = 'STUDIO'
        scene.display.shading.color_type = 'MATERIAL'
        bpy.ops.render.render(write_still=True, scene=scene.name)
