"""Control-room furniture geometry, in plain Python (no Blender needed to run it).

Coordinates are Minecraft block space (x east, y up, z south, 1 unit = 1 block = 1 m) and every model is authored for
facing=north, which means the operator stands at -Z and the back of the piece is at +Z, exactly like the control panel
OBJs. The Blender script (build_furniture.py) turns the face soups made here into Blender meshes, saves the .blend,
exports OBJ + MTL and renders previews.

A "soup" is a list of faces: (material, [(x, y, z)...], [(u, v)...]). Boxes are written face by face so hidden faces
(on the floor, against a neighbour) can be left out, which keeps the face counts low. A Builder can clip every box to a
window, so one authored piece (a 2 high cabinet, a 2 wide supervisor desk, a 3 x 2 wall display) is cut into the
per-block models of a multi-block item without any seam: faces that lie on a cut are interior and are not written.
"""
import math

EPS = 1e-9
PX = 1.0 / 16.0

# name -> (Kd rgb 0..255, texture or None). A texture is textures/block/furniture/<name>.png. Plain colours are drawn with
# minecraft:block/white_concrete tinted by Kd, the way the control panel bodies are (tools-export-panels.py).
MATERIALS = {
    "paint": ((0xA7, 0xBC, 0xAE), None),      # seafoam GE green, the panel face colour
    "bezel": ((0x7D, 0x92, 0x84), None),      # the panel bezel / unpainted edge colour
    "top": ((0xBC, 0xCD, 0xC1), None),        # desk laminate: a lighter seafoam
    "trim": ((0x3A, 0x3F, 0x42), None),       # dark grey trim
    "frame": ((0x3A, 0x3F, 0x42), None),      # the thin housing of mimic bands and wall displays: tint 7 (dark in seafoam)
    "dark": ((0x1F, 0x23, 0x26), None),       # plinths and kick spaces
    "handle": ((0xC3, 0xC8, 0xC9), None),     # brushed aluminium handles
    "casing": ((0x4B, 0x51, 0x55), None),     # monitor and CRT housings
    "louvre": ((255, 255, 255), "louvre"),
    "indicator": ((255, 255, 255), "indicator"),
    "screen_flat": ((255, 255, 255), "screen_flat"),
    "screen_crt": ((255, 255, 255), "screen_crt"),
    "keyboard": ((255, 255, 255), "keyboard"),
    "stand_panel": ((255, 255, 255), "stand_panel"),
    "mimic_cream": ((255, 255, 255), "mimic_cream"),
    "mimic_black": ((255, 255, 255), "mimic_black"),
    "display_2x2": ((255, 255, 255), "display_2x2"),
    "display_3x2": ((255, 255, 255), "display_3x2"),
}

# (x selector, y selector, z selector) of the four corners of each box face; 0 = low end, 1 = high end.
FACE_CORNERS = {
    "n": [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)],
    "s": [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
    "e": [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)],
    "w": [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],
    "u": [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)],
    "d": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
}
ALL_FACES = "nsewud"


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _normal(pts):
    """Newell normal of a polygon."""
    nx = ny = nz = 0.0
    for i, p in enumerate(pts):
        q = pts[(i + 1) % len(pts)]
        nx += (p[1] - q[1]) * (p[2] + q[2])
        ny += (p[2] - q[2]) * (p[0] + q[0])
        nz += (p[0] - q[0]) * (p[1] + q[1])
    return (nx, ny, nz)


def _centroid(pts):
    n = float(len(pts))
    return (sum(p[0] for p in pts) / n, sum(p[1] for p in pts) / n, sum(p[2] for p in pts) / n)


def rot_x(deg, pivot):
    """Tilt about an axis parallel to X through pivot (y, z). Positive tilts the top toward +z (backwards for a screen)."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)

    def f(p):
        y, z = p[1] - pivot[0], p[2] - pivot[1]
        return (p[0], pivot[0] + y * c - z * s, pivot[1] + y * s + z * c)
    return f


def rot_y(deg, pivot):
    """Turn about a vertical axis through pivot (x, z); 45 turns a north-facing front toward north-west."""
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)

    def f(p):
        dx, dz = p[0] - pivot[0], p[2] - pivot[1]
        return (pivot[0] + dx * c + dz * s, p[1], pivot[1] - dx * s + dz * c)
    return f


class Builder:
    """Accumulates faces. clip=(x0, y0, z0, x1, y1, z1) keeps only the part of every box inside that window and moves the
    window's low corner to the origin, so the result is the model of one block of a multi-block piece."""

    def __init__(self, name, clip=None):
        self.name = name
        self.clip = clip
        self.faces = []

    # ---- low level

    def _emit(self, mat, pts, uvs, inside):
        """Add a face wound counter-clockwise seen from outside (inside = any point in the solid's interior)."""
        n = _normal(pts)
        if _dot(n, _sub(_centroid(pts), inside)) < 0:
            pts = list(reversed(pts))
            uvs = list(reversed(uvs))
        self.faces.append((mat, pts, uvs))

    def box(self, lo, hi, mats, skip="", xform=None):
        """An axis aligned box. mats is a material name or {face: material}; faces named in skip are not written."""
        lo, hi = list(lo), list(hi)
        for i in range(3):
            assert hi[i] > lo[i], (self.name, lo, hi)
        skipped = set(skip)
        olo, ohi = list(lo), list(hi)
        if self.clip is not None:
            c = self.clip
            nlo = [max(lo[i], c[i]) for i in range(3)]
            nhi = [min(hi[i], c[3 + i]) for i in range(3)]
            if any(nhi[i] - nlo[i] <= 1e-7 for i in range(3)):
                return
            for i, (low_face, high_face) in enumerate((("w", "e"), ("d", "u"), ("n", "s"))):
                if nlo[i] > lo[i] + 1e-7:
                    skipped.add(low_face)
                if nhi[i] < hi[i] - 1e-7:
                    skipped.add(high_face)
            lo, hi = nlo, nhi
        size = [ohi[i] - olo[i] for i in range(3)]
        center = tuple((lo[i] + hi[i]) / 2.0 for i in range(3))
        if xform:
            center = xform(center)
        for face in ALL_FACES:
            if face in skipped:
                continue
            mat = mats if isinstance(mats, str) else mats.get(face)
            if mat is None:
                continue
            pts, uvs = [], []
            for sel in FACE_CORNERS[face]:
                p = (hi[0] if sel[0] else lo[0], hi[1] if sel[1] else lo[1], hi[2] if sel[2] else lo[2])
                u, v = self._box_uv(face, p, olo, size)
                pts.append(xform(p) if xform else p)
                uvs.append((u, v))
            self._emit(mat, pts, uvs, center)

    @staticmethod
    def _box_uv(face, p, olo, size):
        fx = (p[0] - olo[0]) / size[0]
        fy = (p[1] - olo[1]) / size[1]
        fz = (p[2] - olo[2]) / size[2]
        if face == "n":
            return (1.0 - fx, fy)
        if face == "s":
            return (fx, fy)
        if face == "e":
            return (1.0 - fz, fy)
        if face == "w":
            return (fz, fy)
        if face == "u":
            # read from the front (-z): the image's top is the far edge and its right is -x, so keys and buttons are upright
            return (1.0 - fx, fz)
        return (fx, fz)

    def quad(self, mat, pts, uvs, outward):
        """A single flat face; outward is a vector the face looks along."""
        if self.clip is not None:
            raise ValueError("quad cannot be clipped")
        n = _normal(pts)
        if _dot(n, outward) < 0:
            pts = list(reversed(pts))
            uvs = list(reversed(uvs))
        self.faces.append((mat, list(pts), list(uvs)))

    def prism(self, poly, y0, y1, mats, skip="", xform=None):
        """A convex polygon (x, z points) extruded from y0 to y1. mats keys: top, bottom, side. skip: 't', 'b'."""
        assert self.clip is None
        n = len(poly)
        cx = sum(p[0] for p in poly) / n
        cz = sum(p[1] for p in poly) / n
        center = (cx, (y0 + y1) / 2.0, cz)
        if xform:
            center = xform(center)

        def mk(p):
            return xform(p) if xform else p
        side = mats if isinstance(mats, str) else mats.get("side")
        for i in range(n):
            a, b = poly[i], poly[(i + 1) % n]
            pts = [mk((a[0], y0, a[1])), mk((b[0], y0, b[1])), mk((b[0], y1, b[1])), mk((a[0], y1, a[1]))]
            self._emit(side, pts, [(0, 0), (1, 0), (1, 1), (0, 1)], center)
        for key, y, flag in (("top", y1, "t"), ("bottom", y0, "b")):
            if flag in skip:
                continue
            mat = mats if isinstance(mats, str) else mats.get(key)
            for i in range(1, n - 1):
                pts = [mk((poly[0][0], y, poly[0][1])), mk((poly[i][0], y, poly[i][1])),
                       mk((poly[i + 1][0], y, poly[i + 1][1]))]
                self._emit(mat, pts, [(0, 0), (1, 0), (1, 1)], center)

    # ---- results

    def finish(self, recentre=False, single_block=False):
        """Return the face list, shifted so a clip window's low corner is the origin (or the bbox centre is 0.5)."""
        shift = (0.0, 0.0, 0.0)
        if self.clip is not None:
            shift = (-self.clip[0], -self.clip[1], -self.clip[2])
        faces = [(m, [(p[0] + shift[0], p[1] + shift[1], p[2] + shift[2]) for p in pts], uvs)
                 for m, pts, uvs in self.faces]
        if recentre:
            xs = [p[0] for _, pts, _ in faces for p in pts]
            ys = [p[1] for _, pts, _ in faces for p in pts]
            zs = [p[2] for _, pts, _ in faces for p in pts]
            move = (0.5 - (min(xs) + max(xs)) / 2.0, 0.5 - (min(ys) + max(ys)) / 2.0, 0.5 - (min(zs) + max(zs)) / 2.0)
            faces = [(m, [(p[0] + move[0], p[1] + move[1], p[2] + move[2]) for p in pts], uvs) for m, pts, uvs in faces]
        if single_block:
            for _, pts, _ in faces:
                for p in pts:
                    assert -1e-6 <= p[0] <= 1.0 + 1e-6 and -1e-6 <= p[2] <= 1.0 + 1e-6, (self.name, p)
                    assert -1e-6 <= p[1] <= 1.5, (self.name, p)
        return faces


def mirror_x(faces):
    """Mirror a face list across the block's x centre (winding reversed to keep faces outward)."""
    out = []
    for m, pts, uvs in faces:
        out.append((m, [(1.0 - p[0], p[1], p[2]) for p in reversed(pts)], list(reversed(uvs))))
    return out


# ---------------------------------------------------------------------------------------------------------------------
# Relay cabinet: 2 blocks tall. Standard: double doors, louvres, indicator window at the front and a service door at the
# back. Plain back: the back is flat at the block edge so rows stand back to back.
# ---------------------------------------------------------------------------------------------------------------------

def cabinet(clip=None, back_door=True):
    b = Builder("cabinet", clip)
    front = 0.25                        # carcass front; the doors stand 0.05 proud of it
    back = 0.92 if back_door else 1.0   # carcass back
    # plinth, recessed 0.10 so a technician can stand close (the kick space of the research notes)
    b.box((0.0, 0.0, front + 0.10), (1.0, 0.10, back), "dark", skip="d")
    b.box((0.0, 0.10, front), (1.0, 1.94, back), "bezel", skip="du")
    b.box((0.0, 1.94, front - 0.03), (1.0, 2.0, back), "trim")           # top cap, overhanging the doors
    for x0, x1 in ((0.03, 0.485), (0.515, 0.97)):                        # the two door leaves, a seam between them
        b.box((x0, 0.14, 0.20), (x1, 1.90, front), {"n": "paint", "e": "bezel", "w": "bezel", "u": "bezel", "d": "bezel"})
        b.box((x0 + 0.04, 0.20, 0.19), (x1 - 0.04, 1.84, 0.20), {"n": "paint", "e": "bezel", "w": "bezel", "u": "bezel", "d": "bezel"})
        b.box((x0 + 0.07, 1.62, 0.178), (x1 - 0.07, 1.80, 0.19), {"n": "louvre", "e": "trim", "w": "trim", "u": "trim", "d": "trim"})
        b.box((x0 + 0.07, 0.26, 0.178), (x1 - 0.07, 0.42, 0.19), {"n": "louvre", "e": "trim", "w": "trim", "u": "trim", "d": "trim"})
    for x0 in (0.452, 0.528):                                            # bar handles either side of the seam
        b.box((x0, 0.86, 0.165), (x0 + 0.02, 1.20, 0.20), "handle", skip="s")
    # small indicator window in the right leaf at eye height, in a dark frame
    b.box((0.585, 1.37, 0.18), (0.895, 1.57, 0.19), "trim", skip="s")
    b.box((0.605, 1.395, 0.172), (0.875, 1.545, 0.18), {"n": "indicator", "e": "trim", "w": "trim", "u": "trim", "d": "trim"})
    if back_door:
        b.box((0.03, 0.14, back), (0.97, 1.90, 0.97), {"s": "paint", "e": "bezel", "w": "bezel", "u": "bezel", "d": "bezel"})
        b.box((0.20, 1.62, 0.97), (0.80, 1.80, 1.0), {"s": "louvre", "e": "trim", "w": "trim", "u": "trim", "d": "trim"})
        b.box((0.20, 0.26, 0.97), (0.80, 0.42, 1.0), {"s": "louvre", "e": "trim", "w": "trim", "u": "trim", "d": "trim"})
        b.box((0.86, 0.86, 0.97), (0.89, 1.20, 0.995), "handle", skip="n")
    return b


# ---------------------------------------------------------------------------------------------------------------------
# Desks, 0.75 m high with an open knee and kick space (at least 100 mm) underneath.
# ---------------------------------------------------------------------------------------------------------------------

TOP_Y0, TOP_Y1 = 0.70, 0.75


def _top_mats():
    return {"u": "top", "n": "trim", "s": "trim", "e": "trim", "w": "trim", "d": "trim"}


def desk_straight(clip=None):
    b = Builder("desk", clip)
    b.box((0.0, TOP_Y0, 0.10), (1.0, TOP_Y1, 0.90), _top_mats())
    for x0, x1 in ((0.0, 0.05), (0.95, 1.0)):                            # end panels reach the floor
        b.box((x0, 0.0, 0.14), (x1, TOP_Y0, 0.86), "bezel", skip="ud")
    b.box((0.05, 0.18, 0.78), (0.95, TOP_Y0, 0.82), "bezel", skip="u")   # modesty panel, kick space in front of it
    b.box((0.05, 0.0, 0.74), (0.95, 0.10, 0.82), "dark", skip="d")       # toe board at the back
    b.box((0.28, 0.60, 0.12), (0.72, TOP_Y0, 0.52), {"n": "paint", "e": "bezel", "w": "bezel", "s": "bezel", "d": "bezel"})
    b.box((0.45, 0.645, 0.10), (0.55, 0.665, 0.12), "handle", skip="s")
    return b


def desk_round(clip=None, mirrored=False):
    """Straight desk with a semicircular (bullnose) east end; mirrored puts the round end on the west."""
    b = Builder("desk_round", clip)
    cx, cz, r = 0.60, 0.50, 0.40
    steps = 8

    def arc(radius, i):
        phi = -math.pi / 2 + math.pi * i / steps
        return (cx + radius * math.cos(phi), cz + radius * math.sin(phi))
    poly = [(0.0, 0.10)] + [arc(r, i) for i in range(0, steps + 1)] + [(0.0, 0.90)]
    b.prism(poly, TOP_Y0, TOP_Y1, {"top": "top", "bottom": "trim", "side": "trim"})
    b.box((0.0, 0.0, 0.14), (0.05, TOP_Y0, 0.86), "bezel", skip="ud")
    b.box((0.05, 0.18, 0.78), (0.60, TOP_Y0, 0.82), "bezel", skip="u")
    b.box((0.05, 0.0, 0.74), (0.60, 0.10, 0.82), "dark", skip="d")
    # a half drum under the rounded end
    r2 = 0.32
    drum = [arc(r2, i) for i in range(0, steps + 1)]
    b.prism(drum, 0.08, TOP_Y0, {"top": "bezel", "bottom": "bezel", "side": "bezel"}, skip="tb")
    plinth = [arc(r2 - 0.04, i) for i in range(0, steps + 1)]
    b.prism(plinth, 0.0, 0.08, {"top": "dark", "bottom": "dark", "side": "dark"}, skip="tb")
    faces = b.finish(single_block=True)
    return mirror_x(faces) if mirrored else faces


def desk_corner(clip=None):
    """L shaped desk: arms along the back (south) and east edges, the open crook toward the north-west."""
    b = Builder("desk_corner", clip)
    t = _top_mats()
    b.box((0.0, TOP_Y0, 0.32), (0.32, TOP_Y1, 0.92), t)
    b.box((0.32, TOP_Y0, 0.08), (0.92, TOP_Y1, 0.92), t)
    b.box((0.0, 0.0, 0.36), (0.04, TOP_Y0, 0.88), "bezel", skip="ud")          # west end panel
    b.box((0.36, 0.0, 0.08), (0.88, TOP_Y0, 0.12), "bezel", skip="ud")          # north end panel
    b.box((0.04, 0.18, 0.84), (0.88, TOP_Y0, 0.88), "bezel", skip="u")          # modesty panel along the back
    b.box((0.84, 0.18, 0.12), (0.88, TOP_Y0, 0.84), "bezel", skip="u")          # and along the east side
    b.box((0.04, 0.0, 0.80), (0.88, 0.10, 0.88), "dark", skip="d")
    b.box((0.80, 0.0, 0.12), (0.88, 0.10, 0.80), "dark", skip="d")
    return b


# ---- monitors, kept as separate models so a desk shows one or none --------------------------------------------------

def monitor_flat(cx=0.5, cz=0.45, turn=0.0):
    b = Builder("monitor_flat")
    xf = rot_y(turn, (cx, cz)) if turn else None
    y = TOP_Y1

    def xs(x0, x1, z0, z1):
        return (cx + x0, cz + z0), (cx + x1, cz + z1)
    # keyboard on the desk in front of the screen
    (a, c) = xs(-0.20, 0.20, -0.31, -0.19)
    b.box((a[0], y, a[1]), (c[0], y + 0.02, c[1]), {"u": "keyboard", "n": "casing", "s": "casing", "e": "casing", "w": "casing"}, xform=xf)
    # foot and neck
    (a, c) = xs(-0.10, 0.10, -0.05, 0.11)
    b.box((a[0], y, a[1]), (c[0], y + 0.015, c[1]), "casing", skip="d", xform=xf)
    (a, c) = xs(-0.03, 0.03, 0.0, 0.04)
    b.box((a[0], y + 0.015, a[1]), (c[0], y + 0.13, c[1]), "casing", skip="ud", xform=xf)
    # panel, tilted back 10 degrees about the top of the neck
    tilt = rot_x(10.0, (y + 0.10, cz + 0.04))
    xt = (lambda p: xf(tilt(p))) if xf else tilt
    (a, c) = xs(-0.24, 0.24, 0.0, 0.04)
    b.box((a[0], y + 0.10, a[1]), (c[0], y + 0.40, c[1]), "casing", xform=xt)
    (a, c) = xs(-0.22, 0.22, -0.012, 0.0)
    b.box((a[0], y + 0.12, a[1]), (c[0], y + 0.38, c[1]),
          {"n": "screen_flat", "e": "casing", "w": "casing", "u": "casing", "d": "casing"}, xform=xt)
    return b.finish(single_block=True)


def monitor_crt(cx=0.5, cz=0.45, turn=0.0):
    b = Builder("monitor_crt")
    xf = rot_y(turn, (cx, cz)) if turn else None
    y = TOP_Y1

    def at(x0, x1, z0, z1):
        return (cx + x0, cz + z0), (cx + x1, cz + z1)
    (a, c) = at(-0.20, 0.20, -0.31, -0.19)
    b.box((a[0], y, a[1]), (c[0], y + 0.02, c[1]), {"u": "keyboard", "n": "casing", "s": "casing", "e": "casing", "w": "casing"}, xform=xf)
    (a, c) = at(-0.22, 0.22, -0.16, 0.00)
    b.box((a[0], y, a[1]), (c[0], y + 0.32, c[1]), "casing", skip="d", xform=xf)          # front housing
    (a, c) = at(-0.17, 0.17, -0.175, -0.16)
    b.box((a[0], y + 0.04, a[1]), (c[0], y + 0.28, c[1]),
          {"n": "screen_crt", "e": "trim", "w": "trim", "u": "trim", "d": "trim"}, xform=xf)   # screen, proud of the bezel
    (a, c) = at(-0.17, 0.17, 0.00, 0.14)
    b.box((a[0], y + 0.03, a[1]), (c[0], y + 0.27, c[1]), "casing", skip="nd", xform=xf)   # tube housing
    (a, c) = at(-0.11, 0.11, 0.14, 0.26)
    b.box((a[0], y + 0.07, a[1]), (c[0], y + 0.23, c[1]), "casing", skip="nd", xform=xf)   # neck of the tube
    return b.finish(single_block=True)


# ---------------------------------------------------------------------------------------------------------------------
# Supervisor desk, 2 blocks wide, drawer pedestal on the left, modesty panel and end panel on the right.
# ---------------------------------------------------------------------------------------------------------------------

def supervisor(clip=None):
    b = Builder("supervisor_desk", clip)
    b.box((0.0, 0.70, 0.05), (2.0, 0.76, 0.95), _top_mats())
    b.box((0.0, 0.0, 0.10), (0.05, 0.70, 0.90), "bezel", skip="ud")                       # left end panel
    b.box((1.95, 0.0, 0.10), (2.0, 0.70, 0.90), "bezel", skip="ud")                       # right end panel
    b.box((0.05, 0.08, 0.10), (0.60, 0.70, 0.90), {"n": "bezel", "s": "bezel", "e": "bezel", "w": "bezel"})   # pedestal
    b.box((0.10, 0.0, 0.14), (0.55, 0.08, 0.86), "dark", skip="d")
    for i in range(3):                                                                    # three drawer fronts
        y0 = 0.11 + i * 0.195
        b.box((0.08, y0, 0.07), (0.57, y0 + 0.175, 0.10), {"n": "paint", "e": "bezel", "w": "bezel", "u": "bezel", "d": "bezel"})
        b.box((0.255, y0 + 0.075, 0.045), (0.395, y0 + 0.095, 0.07), "handle", skip="s")
    b.box((0.60, 0.18, 0.84), (1.95, 0.70, 0.88), "bezel", skip="u")                      # modesty panel
    b.box((0.60, 0.0, 0.80), (1.95, 0.10, 0.88), "dark", skip="d")
    b.box((1.35, 0.60, 0.12), (1.80, 0.70, 0.50), {"n": "paint", "e": "bezel", "w": "bezel", "s": "bezel", "d": "bezel"})  # pencil drawer
    b.box((1.52, 0.645, 0.10), (1.63, 0.665, 0.12), "handle", skip="s")
    return b


# ---------------------------------------------------------------------------------------------------------------------
# Mimic / synoptic band: a flat board 3/16 deep against the wall, in two finishes.
# ---------------------------------------------------------------------------------------------------------------------

def mimic_band(finish):
    b = Builder("mimic_band_" + finish)
    mat = "mimic_" + finish
    b.box((0.0, 0.05, 0.875), (1.0, 0.95, 1.0), {"n": mat, "e": "frame", "w": "frame"}, skip="sud")
    b.box((0.0, 0.0, 0.84), (1.0, 0.05, 1.0), "frame", skip="s")
    b.box((0.0, 0.95, 0.84), (1.0, 1.0, 1.0), "frame", skip="s")
    return b.finish(single_block=True)


# ---------------------------------------------------------------------------------------------------------------------
# Wall display: a dark screen in a dark grey frame, 2 or 3 blocks wide, 2 tall. Built as one piece and cut per block.
# ---------------------------------------------------------------------------------------------------------------------

def wall_display(width, height=2, clip=None):
    b = Builder("wall_display_%dx%d" % (width, height), clip)
    mat = "display_%dx%d" % (width, height)
    frame = 0.06
    screen_z = 0.90
    # the screen is one quad per block so it can be cut anywhere; the UVs run over the whole display
    for cx in range(width):
        for cy in range(height):
            if clip is not None and not (clip[0] <= cx < clip[3] and clip[1] <= cy < clip[4]):
                continue
            x0, x1, y0, y1 = cx, cx + 1, cy, cy + 1
            uv = lambda x, y: (x / width, y / height)
            # seen from the front (-z) the right hand is -x, so u runs against x: mirror it to keep the picture upright
            pts = [(x1, y0, screen_z), (x0, y0, screen_z), (x0, y1, screen_z), (x1, y1, screen_z)]
            uvs = [(1.0 - uv(p[0], p[1])[0], uv(p[0], p[1])[1]) for p in pts]
            b.faces.append((mat, pts, uvs))   # already wound counter-clockwise seen from -z
    ztop = 1.0
    zf = 0.84
    b.box((0.0, height - frame, zf), (width, height, ztop), "frame", skip="s")        # top bar
    b.box((0.0, 0.0, zf), (width, frame, ztop), "frame", skip="s")                    # bottom bar
    b.box((0.0, frame, zf), (frame, height - frame, ztop), "frame", skip="s")         # left bar
    b.box((width - frame, frame, zf), (width, height - frame, ztop), "frame", skip="s")  # right bar
    # a thin dark backing closes the sides of the screen so it does not look like a hole from an angle
    b.box((0.0, 0.0, screen_z), (width, height, ztop), "frame", skip="nsud")
    return b


# ---------------------------------------------------------------------------------------------------------------------
# Control stand: a narrow pedestal column with a small tilted top carrying a lamp and button panel.
# ---------------------------------------------------------------------------------------------------------------------

def control_stand():
    b = Builder("control_stand")
    b.box((0.18, 0.0, 0.18), (0.82, 0.06, 0.82), "dark", skip="d")
    b.box((0.34, 0.06, 0.34), (0.66, 0.80, 0.66), {"n": "paint", "e": "bezel", "w": "bezel", "s": "bezel"})
    tilt = rot_x(-18.0, (0.85, 0.5))                       # the back edge rises toward the operator's eye line
    b.box((0.16, 0.80, 0.22), (0.84, 0.90, 0.78), {"u": "stand_panel", "n": "trim", "s": "trim", "e": "trim", "w": "trim", "d": "trim"},
          xform=tilt)
    return b.finish(single_block=True)
