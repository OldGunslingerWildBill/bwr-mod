"""Build the pipe models in headless Blender and snapshot them for tools-export-pipes.py (PIPES.md).

    "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Blender\\blender.exe" --background --factory-startup ^
        --python art/models/pipes/build_pipes.py

One parameter set per pipe (PIPE_SETS below): bore, lagging, paint or cladding, joint style, port flange. Each set gets
  * connection_<mask>  64 meshes, one per pattern of connected faces (bit i = Direction.values()[i]: down, up, north,
                       south, west, east), as the blockstates have always used. Straight runs are open prisms with no
                       faces on the block boundary, so neighbours join with no seam and nothing to cull.
  * port_<direction>   6 meshes: the bolted flange drawn where a connected face meets a machine rather than another pipe
                       (the client adds it; piping/client/PipeJointModels.java). Its outer face lies on the block face and
                       culls against full blocks.
  * item               a spool: the north-south straight with a port flange at each end.
Writes art/models/pipes/<set>/mesh.json (polygons with block-unit positions, UVs and material names; quads and
triangles only, since the NeoForge OBJ loader reads at most four corners) and art/models/pipes/pipes.blend (one scene
per set, every part laid out on a grid). Never touches any scene it did not create.

Geometry is in block units (0 to 1; north at z = 0, up at y = 1). Blender is driven with (x, -z, y).
"""
import json
import math
import os
import sys

sys.dont_write_bytecode = True

import bpy  # noqa: E402
from mathutils import Matrix, Vector  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------------------------------------------------
# Parameter sets. Lengths in sixteenths of a block (one texel of a vanilla texture), converted below.
#   radius         the outside of what is seen: bare pipe, or the cladding over the lagging (apothem of the prism)
#   sides          prism sides (a multiple of 4 so the rings of every axis match at the block faces)
#   body           material of the pipe surface: 'paint' (tinted by the block colour) or 'cladding' (plain metal)
#   joint          what marks each block boundary:
#                    'ring'   a narrow ring of joint_mat straddling the boundary, joint_len wide on each side and standing
#                             joint_raise proud of the pipe: an unpainted girth weld ('weld'), or a painted service band
#                             over the cladding's joint ('band', tinted)
#                    'flange' a flange pair: one flange joint_len thick and joint_flange_radius across on each side
#   cap            material of the cap that closes a dead end or the run of a capped tee
#   port_*         the flange drawn against a machine: radius, thickness, stud count
#   elbow_segments rings in a 90 degree bend (for cladding these are the gores of the elbow jacket)
#   ship           write the models into the mod's assets (False: kept in art/models/pipes/<set>/ only)
#   preview_tint   the block colour in Blender previews only (the game takes it from PipeColors or the dye)
# ---------------------------------------------------------------------------------------------------------------------
PIPE_SETS = {
    # Painted carbon-steel high-pressure water: 8 px across as before, butt-welded every block, flanged to machines.
    'water': dict(radius=4, sides=12, body='paint', joint='ring', joint_mat='weld', joint_len=1, joint_raise=.5, cap='steel',
                  port_radius=6.5, port_len=2, port_studs=8, elbow_segments=6, ship=True, preview_tint=(0x5A, 0x9F, 0xCC)),
    # Main steam: thick lagging under aluminium cladding (12 px across); the amber service band sits on each sheet joint.
    'steam': dict(radius=6, sides=12, body='cladding', joint='ring', joint_mat='band', joint_len=1, joint_raise=0, cap='cladding',
                  port_radius=7.25, port_len=2, port_studs=8, elbow_segments=4, ship=True, preview_tint=(0xE8, 0xA4, 0x3B)),
    # Lubricating oil (the Lube Oil Pipe, bwr:lube_oil_pipe): small bore, painted brown, flanged at every joint.
    'oil': dict(radius=2.5, sides=8, body='paint', joint='flange', joint_len=1, joint_flange_radius=4, cap='steel',
                port_radius=4.5, port_len=1.5, port_studs=4, elbow_segments=4, ship=True, preview_tint=(0x6F, 0x4A, 0x2E)),
}
# Material name: (texture, tint index). The tinted ones take the block colour (service colour or dye).
MATERIALS = {
    'paint': ('bwr:block/pipes/painted_steel', 0),
    'weld': ('bwr:block/pipes/weld', -1),
    'steel': ('bwr:block/pipes/flange_steel', -1),
    'cladding': ('bwr:block/pipes/cladding', -1),
    'band': ('bwr:block/pipes/service_band', 0),
}

DIRS = [('down', (0, -1, 0)), ('up', (0, 1, 0)), ('north', (0, 0, -1)),
        ('south', (0, 0, 1)), ('west', (-1, 0, 0)), ('east', (1, 0, 0))]
C = Vector((.5, .5, .5))
# The run of a junction prefers a horizontal axis.
RUN_AXES = (0, 2, 1)


def frame(axis):
    """The ring basis of an axis (0 x, 1 y, 2 z): e2 is up for horizontal pipes, so ring angle -90 deg is the underside."""
    if axis == 0:
        return Vector((0, 0, 1)), Vector((0, 1, 0))
    if axis == 2:
        return Vector((1, 0, 0)), Vector((0, 1, 0))
    return Vector((1, 0, 0)), Vector((0, 0, 1))


def axis_of(d):
    return next(i for i in range(3) if d[i])


def coord(p, axis):
    """Texture v along a straight: the block coordinate along the axis (y runs top to bottom), so runs tile."""
    return 1 - p[1] if axis == 1 else p[axis]


class Part:
    """A face soup in block units: (points, uvs, material)."""

    def __init__(self):
        self.polys = []

    def add(self, pts, uvs, mat, hint):
        n = (pts[1] - pts[0]).cross(pts[2] - pts[0])
        if len(pts) == 4 and n.length < 1e-12:
            n = (pts[2] - pts[1]).cross(pts[3] - pts[1])
        if n.length < 1e-12:
            return
        if n.dot(hint) < 0:
            pts, uvs = pts[::-1], uvs[::-1]
        self.polys.append(([p.copy() for p in pts], list(uvs), mat))


class Ring:
    """A ring of n corners (plus the closing corner again, for the texture seam) at centre p."""

    def __init__(self, p, e1, e2, apothem, n, v):
        self.p, self.v = p, v
        rv = apothem / math.cos(math.pi / n)
        start = -math.pi / 2 + math.pi / n
        self.pts = [p + rv * (math.cos(start + 2 * math.pi * k / n) * e1 + math.sin(start + 2 * math.pi * k / n) * e2)
                    for k in range(n + 1)]
        self.e1, self.e2, self.n = e1, e2, n

    def moved(self, matrix, v):
        r = Ring.__new__(Ring)
        r.p, r.v, r.n = matrix @ self.p, v, self.n
        r.pts = [matrix @ q for q in self.pts]
        r.e1, r.e2 = (matrix.to_3x3() @ self.e1), (matrix.to_3x3() @ self.e2)
        return r


def band(part, a, b, mat, local_v=False):
    """The quads between two rings; v from the rings (or 0..1 across the band when local_v)."""
    va, vb = (0.0, 1.0) if local_v else (a.v, b.v)
    for k in range(a.n):
        pts = [a.pts[k], a.pts[k + 1], b.pts[k + 1], b.pts[k]]
        uvs = [(k / a.n, va), ((k + 1) / a.n, va), ((k + 1) / a.n, vb), (k / a.n, vb)]
        centre = (a.p + b.p) / 2
        part.add(pts, uvs, mat, sum(pts, Vector()) / 4 - centre)


def disc(part, ring, mat, outward):
    """A flat cap across a ring: the polygon fanned into quads (and a last triangle when needed)."""
    n = ring.n
    pts = ring.pts[:n]
    rv = (pts[0] - ring.p).length

    def uv(q):
        d = q - ring.p
        return (.5 + .45 * d.dot(ring.e1) / rv, .5 + .45 * d.dot(ring.e2) / rv)
    k = 1
    while k + 1 < n:
        idx = [0, k, k + 1] + ([k + 2] if k + 2 < n else [])
        part.add([pts[i] for i in idx], [uv(pts[i]) for i in idx], mat, outward)
        k += 2


def annulus(part, inner, outer, mat, outward):
    n = inner.n
    for k in range(n):
        pts = [inner.pts[k], inner.pts[k + 1], outer.pts[k + 1], outer.pts[k]]
        uvs = [(k / n, .45), ((k + 1) / n, .45), ((k + 1) / n, .55), (k / n, .55)]
        part.add(pts, uvs, mat, outward)


def box(part, centre, half, axes, mat, skip=None):
    """A small box (a stud nut): half sizes along the three given unit axes; skip (axis, sign) leaves out a hidden face."""
    for i in range(3):
        for s in (-1, 1):
            if skip == (i, s):
                continue
            n = axes[i] * s
            u, w = axes[(i + 1) % 3], axes[(i + 2) % 3]
            hu, hw = half[(i + 1) % 3], half[(i + 2) % 3]
            f = centre + n * half[i]
            pts = [f - u * hu - w * hw, f + u * hu - w * hw, f + u * hu + w * hw, f - u * hu + w * hw]
            part.add(pts, [(0, 0), (1, 0), (1, 1), (0, 1)], mat, n)


class Builder:
    def __init__(self, name, spec):
        self.name, self.s = name, spec
        self.r = spec['radius'] / 16
        self.n = spec['sides']
        self.jl = spec.get('joint_len', 0) / 16

    def ring(self, p, axis, v, radius=None):
        e1, e2 = frame(axis)
        return Ring(p, e1, e2, self.r if radius is None else radius, self.n, v)

    # -- straight pieces ----------------------------------------------------------------------------------------------
    def straight(self, part, axis, a, b, face_a, face_b):
        """A straight along axis from coordinate a to b on the centre line. An end on a block face gets the joint."""
        def point(t):
            p = C.copy()
            p[axis] = t
            return p
        if a > b:
            a, b, face_a, face_b = b, a, face_b, face_a
        jl = self.jl
        # The joint (a ring, or a flange the pipe stops under) takes the last joint_len before each face.
        body_a, body_b = a + (jl if face_a else 0), b - (jl if face_b else 0)
        band(part, self.ring(point(body_a), axis, coord(point(body_a), axis)),
             self.ring(point(body_b), axis, coord(point(body_b), axis)), self.s['body'])
        for at_face, t0, t1, sign in ((face_a, a, body_a, -1), (face_b, body_b, b, 1)):
            if not at_face:
                continue
            if self.s['joint'] == 'flange':
                self.joint_flange(part, axis, t0 if sign < 0 else t1, sign)
            else:
                self.joint_ring(part, axis, point(t0), point(t1), sign)

    def joint_ring(self, part, axis, p0, p1, sign):
        """Half of a joint ring (the other half is the neighbour's), raised by joint_raise with a step facing inward.
        sign is the direction of the face the ring sits against."""
        mat = self.s['joint_mat']
        rr = self.r + self.s['joint_raise'] / 16
        band(part, self.ring(p0, axis, 0, rr), self.ring(p1, axis, 1, rr), mat, local_v=True)
        if rr > self.r:
            inner = p0 if sign > 0 else p1
            d = Vector((0, 0, 0))
            d[axis] = -sign
            annulus(part, self.ring(inner, axis, 0), self.ring(inner, axis, 0, rr), mat, d)

    def joint_flange(self, part, axis, face_t, sign):
        """One flange of a flange-pair joint, its outer face on the block boundary (left out: the neighbour's covers it)."""
        rf = self.s['joint_flange_radius'] / 16
        d = Vector((0, 0, 0))
        d[axis] = sign
        p_face = C.copy()
        p_face[axis] = face_t
        p_back = p_face - d * self.jl
        back_in, back_out = self.ring(p_back, axis, 0, self.r * .9), self.ring(p_back, axis, 0, rf)
        annulus(part, back_in, back_out, 'steel', -d)
        band(part, back_out, self.ring(p_face, axis, 1, rf), 'steel', local_v=True)

    # -- the bend -----------------------------------------------------------------------------------------------------
    def elbow(self, part, a, b):
        """A swept 90 degree bend of centre-line radius 1/2 from the centre of face a to the centre of face b. Each end
        has the joint; the stretch between is divided into elbow_segments (gores, for cladding)."""
        a, b = Vector(a), Vector(b)
        origin = C + (a + b) * .5
        k = b.cross(a)
        R = .5
        segs = self.s['elbow_segments']
        j = self.jl / R

        def turned(phi, v, radius=None):
            m = Matrix.Translation(origin) @ Matrix.Rotation(phi, 4, k) @ Matrix.Translation(-origin)
            return self.ring(C + a * .5, axis_of(a), 0, radius).moved(m, v)
        gore = (math.pi / 2 - 2 * j) / segs
        for i in range(segs):
            p0, p1 = j + i * gore, j + (i + 1) * gore
            # For cladding each gore is its own sheet: its texture starts at the sheet's mid-row lap.
            v0 = .5 if self.s['body'] == 'cladding' else p0 * R
            band(part, turned(p0, v0), turned(p1, v0 + gore * R), self.s['body'])
        for face, phi0, phi1, inner_phi in ((a, 0.0, j, j), (b, math.pi / 2 - j, math.pi / 2, math.pi / 2 - j)):
            ax = axis_of(face)
            if self.s['joint'] == 'flange':
                self.joint_flange(part, ax, 1.0 if face[ax] > 0 else 0.0, 1 if face[ax] > 0 else -1)
                continue
            rr = self.r + self.s['joint_raise'] / 16
            band(part, turned(phi0, 0, rr), turned(phi1, 1, rr), self.s['joint_mat'], local_v=True)
            if rr > self.r:
                # The step faces along the bend, away from this end's face.
                tangent = b * math.sin(inner_phi) - a * math.cos(inner_phi)
                d = tangent if face is a else -tangent
                annulus(part, turned(inner_phi, 0), turned(inner_phi, 0, rr), self.s['joint_mat'], d)

    # -- one connection pattern ---------------------------------------------------------------------------------------
    def connection(self, mask):
        part = Part()
        active = [Vector(v) for i, (_, v) in enumerate(DIRS) if mask >> i & 1]

        def face_t(d):
            ax = axis_of(d)
            return 1.0 if d[ax] > 0 else 0.0

        if len(active) == 2 and abs(active[0].dot(active[1])) < .5:
            self.elbow(part, active[0], active[1])
            return part
        pairs = [ax for ax in RUN_AXES if any(d[ax] > 0 for d in active) and any(d[ax] < 0 for d in active)]
        if pairs:
            # A run straight through the block; any other arm is a branch welded onto it.
            ax = pairs[0]
            self.straight(part, ax, 0.0, 1.0, True, True)
            branches = [d for d in active if not d[ax]]
        elif active:
            # No straight run: the first arm runs on past the centre to a cap (a capped tee, or a dead end).
            run = sorted(active, key=lambda d: (RUN_AXES.index(axis_of(d)), -sum(d)))[0]
            ax = axis_of(run)
            inner = .5 - run[ax] * self.r
            self.straight(part, ax, inner, face_t(run), False, True)
            self.cap(part, ax, inner, -run)
            branches = [d for d in active if d is not run]
        else:
            # A lone segment: a short upright spool, capped, with a joint at each end.
            lo, hi = .5 - self.r - self.jl, .5 + self.r + self.jl
            self.straight(part, 1, lo, hi, True, True)
            self.cap(part, 1, lo, Vector((0, -1, 0)), self.s['joint'] == 'ring' and self.s['joint_raise'])
            self.cap(part, 1, hi, Vector((0, 1, 0)), self.s['joint'] == 'ring' and self.s['joint_raise'])
            return part
        for d in branches:
            ax = axis_of(d)
            # From just inside the run's centre line out to the face.
            self.straight(part, ax, .5 - d[ax] * 1e-3, face_t(d), False, True)
        return part

    def cap(self, part, axis, t, outward, raised=False):
        p = C.copy()
        p[axis] = t
        radius = self.r + self.s.get('joint_raise', 0) / 16 if raised else self.r
        disc(part, self.ring(p, axis, 0, radius), self.s['cap'], Vector(outward))

    # -- machine flange and item --------------------------------------------------------------------------------------
    def port(self, d):
        d = Vector(d)
        ax = axis_of(d)
        part = Part()
        rf = self.s['port_radius'] / 16
        thick = self.s['port_len'] / 16
        face = C + d * .5
        back = face - d * thick
        outer_face, outer_back = self.ring(face, ax, 1, rf), self.ring(back, ax, 0, rf)
        band(part, outer_back, outer_face, 'steel', local_v=True)
        annulus(part, self.ring(back, ax, 0, self.r * .9), outer_back, 'steel', -d)
        disc(part, outer_face, 'steel', d)
        # Studs and nuts on the back of the flange, between the pipe and the rim.
        e1, e2 = frame(ax)
        studs = self.s['port_studs']
        rs = (self.r + rf) / 2
        nut = min(.03, (rf - self.r) * .33)
        for i in range(studs):
            ang = (i + .5) * 2 * math.pi / studs
            radial = math.cos(ang) * e1 + math.sin(ang) * e2
            tangent = d.cross(radial).normalized()
            centre = back + radial * rs - d * nut
            box(part, centre, (nut, nut, nut), (d.copy(), radial, tangent), 'steel', skip=(0, 1))
        return part

    def item(self):
        part = self.connection(0b1100)
        for i, (name, v) in enumerate(DIRS):
            if name in ('north', 'south'):
                part.polys += self.port(v).polys
        return part


# ---------------------------------------------------------------------------------------------------------------------
# Blender side: meshes, the .blend, the snapshot
# ---------------------------------------------------------------------------------------------------------------------
def to_blender(p):
    return (p[0], -p[2], p[1])


def from_blender(p):
    return (p[0], p[2], -p[1])


def material(set_name, mat):
    name = 'pipe %s | %s' % (set_name, mat)
    m = bpy.data.materials.get(name)
    if m:
        return m
    m = bpy.data.materials.new(name)
    texture, tint = MATERIALS[mat]
    path = os.path.join(HERE, '..', '..', '..', 'mod', 'src', 'main', 'resources', 'assets', 'bwr', 'textures', 'block',
                        texture.split('/', 1)[1] + '.png')
    nodes, links = m.node_tree.nodes, m.node_tree.links
    bsdf = next(n for n in nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    img = nodes.new('ShaderNodeTexImage')
    img.image = bpy.data.images.load(os.path.abspath(path), check_existing=True)
    img.interpolation = 'Closest'
    colour = img.outputs['Color']
    if tint >= 0:
        mix = nodes.new('ShaderNodeMix')
        mix.data_type = 'RGBA'
        mix.blend_type = 'MULTIPLY'
        mix.inputs['Factor'].default_value = 1.0
        rgb = PIPE_SETS[set_name]['preview_tint']
        mix.inputs['B'].default_value = tuple((c / 255) ** 2.2 for c in rgb) + (1,)
        links.new(colour, mix.inputs['A'])
        colour = mix.outputs['Result']
    links.new(colour, bsdf.inputs['Base Color'])
    bsdf.inputs['Metallic'].default_value = .8 if mat in ('steel', 'cladding', 'weld') else 0
    bsdf.inputs['Roughness'].default_value = .45 if mat in ('steel', 'cladding') else .6
    m['bwr_material'] = mat
    m['bwr_texture'] = texture
    m['bwr_tint'] = tint
    return m


def build_mesh(set_name, name, part):
    verts, index, faces, uvs, mats = [], {}, [], [], []
    for pts, uv, mat in part.polys:
        idx = []
        for p in pts:
            key = tuple(round(c, 7) for c in p)
            if key not in index:
                index[key] = len(verts)
                verts.append(to_blender(p))
            idx.append(index[key])
        faces.append(idx)
        uvs.append(uv)
        mats.append(mat)
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    layer = mesh.uv_layers.new(name='UVMap')
    for poly, uv in zip(mesh.polygons, uvs):
        for k, li in enumerate(poly.loop_indices):
            # Blender's v points up; the texture rows count down (the model JSON sets flip_v).
            layer.data[li].uv = (uv[k][0], 1 - uv[k][1])
    names = sorted(set(mats))
    for m in names:
        mesh.materials.append(material(set_name, m))
    for poly, m in zip(mesh.polygons, mats):
        poly.material_index = names.index(m)
    return mesh


def snapshot(mesh):
    """The mesh read back from Blender: polygons in block units with their UVs (as written, v down) and material."""
    layer = mesh.uv_layers['UVMap']
    out = []
    for poly in mesh.polygons:
        if len(poly.vertices) > 4:
            raise SystemExit('%s: a %d-sided polygon (the OBJ loader reads four corners)' % (mesh.name, len(poly.vertices)))
        pts = []
        for li in poly.loop_indices:
            co = mesh.vertices[mesh.loops[li].vertex_index].co
            u, v = layer.data[li].uv
            pts.append([round(c, 6) for c in from_blender(co)] + [round(u, 6), round(1 - v, 6)])
        out.append({'v': pts, 'm': mesh.materials[poly.material_index]['bwr_material']})
    for f in out:
        for p in f['v']:
            if min(p[:3]) < -1e-6 or max(p[:3]) > 1 + 1e-6:
                raise SystemExit('%s: outside the block: %s' % (mesh.name, p))
    return out


def build_set(set_name, spec):
    builder = Builder(set_name, spec)
    scene = bpy.data.scenes.new('BWR | pipes ' + set_name)
    parts = {'connection_%d' % m: builder.connection(m) for m in range(64)}
    parts.update({'port_' + name: builder.port(v) for name, v in DIRS})
    parts['item'] = builder.item()
    data = {'set': set_name, 'spec': spec, 'materials': {m: {'texture': MATERIALS[m][0], 'tint': MATERIALS[m][1]}
                                                          for m in sorted({p[2] for part in parts.values() for p in part.polys})},
            'parts': {}}
    for i, (name, part) in enumerate(parts.items()):
        mesh = build_mesh(set_name, '%s %s' % (set_name, name), part)
        obj = bpy.data.objects.new('%s %s' % (set_name, name), mesh)
        obj.location = ((i % 8) * 1.6, -(i // 8) * 1.6, 0)
        scene.collection.objects.link(obj)
        data['parts'][name] = snapshot(mesh)
    out = os.path.join(HERE, set_name)
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, 'mesh.json'), 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(data, fh, separators=(',', ':'))
        fh.write('\n')
    counts = {k: len(v) for k, v in data['parts'].items()}
    print('PIPES %s: straight %d, elbow %d, tee %d, six-way %d, dead end %d, port flange %d, item %d polygons' % (
        set_name, counts['connection_12'], counts['connection_36'], counts['connection_44'], counts['connection_63'],
        counts['connection_4'], counts['port_north'], counts['item']))
    return scene


def main():
    for name, spec in PIPE_SETS.items():
        build_set(name, spec)
    # A factory-startup session: drop its default cube, light and camera, then save the whole file.
    default = bpy.data.scenes.get('Scene')
    if default is not None:
        for obj in list(default.objects):
            bpy.data.objects.remove(obj)
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(HERE, 'pipes.blend'), compress=True)
    print('PIPES written: %s' % ', '.join(PIPE_SETS))


if __name__ == '__main__':
    main()
