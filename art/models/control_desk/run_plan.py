"""The plan of the modular 45 degree runs (CONTROL-PANELS-SPEC section 14), pure Python, no Blender.

A run is bend A + n diagonal units + bend B (n >= 0), in two families (the desk, and the tall panel: a benchboard bench, two
vertical boards and a header strip, 4 blocks high) and two kinds (inside: the console wraps around the operator; outside:
it wraps away). AngledRunGeometry.java has the same numbers and is the authority; build_angled_runs.py draws them,
tools-export-angled-runs.py writes the JSON, and this module writes angled_runs.json (read by the game tests).

THE FRAME. x to the operator's right, f from the back toward the operator, y up, in blocks; cube (bx, bf, by) is the block
whose low corner is (bx, bf, by). Unmirrored. The straight run A arrives from +x (its cubes are bx >= 1, bf = 0 for the
desk and the tall panel alike); bend A is the column bx = 0, unit k (1..n) is the column bx = -k, bend B is the column
bx = -n - 1 down to where the straight run B (back at x = -n - 1 inside, x = -n outside, walking toward +f inside and -f
outside) starts. The diagonal advances (-1, +1) per unit inside and (-1, -1) outside, so a unit is the part of the console
between two planes x = const. The console is a band of constant depth t (mitred bends), the profile of the family a function
of t, back at t = 0.

    inside : t = min(f, (x + f - PHI) / sqrt 2, x - xwall)                     xwall = -n - 1
    outside: t = max(f, (PHI + tan(22.5) W - (x - f)) / sqrt 2, xwall - x)     xwall = -n
"""
import json
import math
import os

SQ2 = math.sqrt(2.0)
T22 = math.tan(math.radians(22.5))
TB = math.tan(math.radians(5.0))          # the header strip leans back 5 degrees
HERE = os.path.dirname(os.path.abspath(__file__))

# family: W the depth the mitre geometry is made for, layers (by, depth of the solid in that block layer)
FAMILIES = {
    "desk": dict(W=0.75, layers=[(0, 0.75)]),
    "tall": dict(W=1.0, layers=[(0, 1.0), (1, 0.25), (2, 0.25), (3, 0.25)]),
}
KINDS = ("inside", "outside")
ROLES = ("a", "unit", "b")
# where the mitre starts along the run (the x of its leftmost point), in sixteenths of a block
PHI = {("desk", "inside"): 6 / 16.0, ("desk", "outside"): 5 / 16.0,
       ("tall", "inside"): 8 / 16.0, ("tall", "outside"): 1 / 16.0}
CHAIN_F = {"inside": 1, "outside": -1}    # the diagonal advances (-1, CHAIN_F) per unit


def depth(kind, fam, x, f, n):
    phi, w = PHI[(fam, kind)], FAMILIES[fam]["W"]
    if kind == "inside":
        return min(f, (x + f - phi) / SQ2, x - (-n - 1))
    return max(f, (phi + T22 * w - (x - f)) / SQ2, (-n) - x)


def terms(kind, fam, x, f, n):
    phi, w = PHI[(fam, kind)], FAMILIES[fam]["W"]
    if kind == "inside":
        return [f, (x + f - phi) / SQ2, x - (-n - 1)]
    return [f, (phi + T22 * w - (x - f)) / SQ2, (-n) - x]


def layer_depth(fam, by):
    return dict(FAMILIES[fam]["layers"]).get(by)


def cube_content(kind, fam, n, bx, bf, by, grid=16):
    """(has content, pure straight run B) for a cube: sampled on a grid of cell centres."""
    dep = layer_depth(fam, by)
    if dep is None:
        return False, False
    any_hit, pure = False, True
    for i in range(grid):
        for j in range(grid):
            x, f = bx + (i + 0.5) / grid, bf + (j + 0.5) / grid
            t = depth(kind, fam, x, f, n)
            if 1e-9 < t < dep - 1e-9:
                any_hit = True
                ts = terms(kind, fam, x, f, n)
                if abs(t - ts[2]) > 1e-9:
                    pure = False
    return any_hit, any_hit and pure


def column(kind, fam, n, bx, frange=range(-14, 15)):
    """{by: [bf]} for the cubes with content in a column."""
    out = {}
    for by, _ in FAMILIES[fam]["layers"]:
        out[by] = [bf for bf in frange if cube_content(kind, fam, n, bx, bf, by)[0]]
    return out


# ---------------------------------------------------------------------------------------------------------------
# the faces of one unit (k = 1, slab x = -1..0); layer j of the family

def face_specs(fam):
    """Per face: layer by, the profile's depth t at row 0 (top) and at the last row, the height of row 0 and of the last
    row, the grid rows. Cell row r is at depth t0 + (t1 - t0) r / rows and height y0 + (y1 - y0) r / rows."""
    if fam == "desk":
        return [dict(by=0, kind="desk", t0=0.125, t1=0.75, y0=0.75, y1=0.75, rows=10)]
    return [dict(by=0, kind="bench", t0=0.0, t1=1.0, y0=1.0, y1=0.0, rows=16),
            dict(by=1, kind="vertical", t0=0.25, t1=0.25, y0=2.0, y1=1.0, rows=16),
            dict(by=2, kind="vertical", t0=0.25, t1=0.25, y0=3.0, y1=2.0, rows=16),
            dict(by=3, kind="header", t0=0.25 - TB, t1=0.25, y0=4.0, y1=3.0, rows=16)]


def plan_dirs(kind):
    """Unit steps in (x, f): along the columns (to the operator's right), and toward the operator (the face normal)."""
    r = math.sqrt(0.5)
    return ((r, -r), (r, r)) if kind == "inside" else ((r, r), (-r, r))


def face_centre(kind, fam, spec, k=1):
    """Plan centre (x, f) and height y of the middle of a face of unit k: the middle column, the middle row. The faces of
    the layers of a unit are stacked on one line across the diagonal (the same column positions in every layer, as the rows of
    a straight panel are): the line through the middle of the first face at the middle of the unit's slab x = -k .. -k + 1."""
    r = math.sqrt(0.5)
    first = face_specs(fam)[0]
    t_ref = (first["t0"] + first["t1"]) / 2.0
    t = (spec["t0"] + spec["t1"]) / 2.0
    y = (spec["y0"] + spec["y1"]) / 2.0
    xc = -k + 0.5
    phi, w = PHI[(fam, kind)], FAMILIES[fam]["W"]
    if kind == "inside":
        xb = xc - r * t_ref                  # the back line x + f = phi at the foot of that line
        fb = phi - xb
        return xb + r * t, fb + r * t, y
    xb = xc + r * t_ref                      # the back line x - f = phi + tan(22.5) W
    fb = xb - (phi + T22 * w)
    return xb - r * t, fb + r * t, y


def unit_face_cores(kind, fam):
    out = []
    for spec in face_specs(fam):
        xc, fc, y = face_centre(kind, fam, spec)
        out.append(dict(spec, centre=[xc, fc, y], core=[math.floor(xc), math.floor(fc), spec["by"]]))
    return out


# ---------------------------------------------------------------------------------------------------------------
# pieces

def pieces(kind, fam):
    """The three pieces of a run in the reference chain (unit 1 at bx = -1, bend B for n = 0 at bx = -1), with the cubes of
    each relative to the piece's origin: {role: dict(origin=[bx, bf, by], parts=[[ix, if, iy]...], ...)}."""
    n_far = 3                                           # a run long enough that the units see no bend B
    a_cubes = [(0, bf, by) for by, bfs in column(kind, fam, n_far, 0).items() for bf in bfs]
    u_cubes = [(-1, bf, by) for by, bfs in column(kind, fam, n_far, -1).items() for bf in bfs]
    b_cubes_all = {}
    nonpure = []
    for by, _ in FAMILIES[fam]["layers"]:
        for bf in range(-14, 15):
            has, pure = cube_content(kind, fam, 0, -1, bf, by)
            if has:
                b_cubes_all[(-1, bf, by)] = pure
                if not pure:
                    nonpure.append(bf)
    if kind == "inside":
        fb = max(nonpure)                                # B owns bf <= fb
        b_cubes = [c for c in b_cubes_all if c[1] <= fb]
        straight_bf = fb + 1
    else:
        fb = min(nonpure)                                # B owns bf >= fb
        b_cubes = [c for c in b_cubes_all if c[1] >= fb]
        straight_bf = fb - 1
    faces = unit_face_cores(kind, fam)
    out = {}
    for role, cubes_, origin in (
            ("a", a_cubes, (0, 0, 0)),
            ("unit", u_cubes, tuple(faces[0]["core"])),
            ("b", b_cubes, (-1, fb, 0))):
        assert origin in cubes_, (kind, fam, role, origin, sorted(cubes_))
        rel = [[c[0] - origin[0], c[1] - origin[1], c[2] - origin[2]] for c in cubes_]
        rel.sort(key=lambda p: (p != [0, 0, 0], p[2], p[1], p[0]))
        out[role] = dict(origin=list(origin), parts=rel)
    out["a"]["straight"] = [1, 0, 0]                     # first cube of the straight run, relative to the origin
    out["b"]["straight"] = [0, straight_bf - fb, 0]
    uo = out["unit"]["origin"]
    out["unit"]["faces"] = [dict(kind=fc["kind"], rows=fc["rows"], by=fc["by"], t0=fc["t0"], t1=fc["t1"], y0=fc["y0"], y1=fc["y1"],
                                 centre=fc["centre"],
                                 core=[fc["core"][0] - uo[0], fc["core"][1] - uo[1], fc["core"][2] - uo[2]])
                            for fc in faces]
    sf = CHAIN_F[kind]
    # origins along the chain, relative to A's origin (0, 0): unit k at uo + (k - 1) step; B for n units at b0 + n step
    chain = dict(step=[-1, sf], unit1=[uo[0], uo[1]], b0=[-1, fb])
    out["chain"] = chain
    return out


def check_run(kind, fam, n_units=3):
    """Cubes of a run of n units split by pieces: no overlap, nothing missing."""
    pc = pieces(kind, fam)
    sf = CHAIN_F[kind]
    owned = {}

    def put(piece, o, tag):
        for p in piece["parts"]:
            c = (o[0] + p[0], o[1] + p[1], o[2] + p[2])
            assert c not in owned, ("overlap", kind, fam, c, tag, owned[c])
            owned[c] = tag
    put(pc["a"], (0, 0, 0), "A")
    ua = pc["unit"]["origin"]
    for k in range(1, n_units + 1):
        put(pc["unit"], (ua[0] - (k - 1), ua[1] + (k - 1) * sf, ua[2]), "U%d" % k)
    n = n_units
    bo = pc["b"]["origin"]
    put(pc["b"], (bo[0] - n, bo[1] + n * sf, 0), "B")
    missing = []
    for bx in range(-n - 1, 1):
        for by, _ in FAMILIES[fam]["layers"]:
            for bf in range(-n - 6, n + 7):
                has, pure = cube_content(kind, fam, n, bx, bf, by)
                if has and (bx, bf, by) not in owned:
                    if bx == -n - 1 and pure:
                        continue
                    missing.append((bx, bf, by))
    return owned, missing


if __name__ == "__main__":
    out = {}
    for fam in FAMILIES:
        for kind in KINDS:
            owned, missing = check_run(kind, fam)
            pc = pieces(kind, fam)
            print(fam, kind, "A", len(pc["a"]["parts"]), "U", len(pc["unit"]["parts"]), "B", len(pc["b"]["parts"]),
                  "missing", missing, "chain", pc["chain"])
            out["%s_%s" % (fam, kind)] = pc
    with open(os.path.join(HERE, "angled_runs.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=1)
