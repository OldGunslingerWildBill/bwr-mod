#!/usr/bin/env python3
"""Write the pipe models from the Blender snapshots (PIPES.md). --check is read-only and fails on any difference.

    python tools-export-pipes.py
    python tools-export-pipes.py --check

Reads art/models/pipes/<set>/mesh.json, written by art/models/pipes/build_pipes.py in headless Blender, and writes
for each set: models/block/pipes/<set>/connection_<mask>.obj/.json (the 64 connection patterns), port_<direction>
(the flange the client adds against a machine), item, and materials.mtl. A set with `ship` writes into the mod's
assets together with its blockstate and item model; the others (the oil pipe, which has no block yet) go to
art/models/pipes/<set>/resources/, laid out as mod/src/main/resources so they can be moved in as they are.
Polygons are written as they come from Blender (quads and triangles), with one flat normal per face.
Standard library only.
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(ROOT, "art", "models", "pipes")
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
DIRS = ("down", "up", "north", "south", "west", "east")
# Which block each shipped set draws.
BLOCKS = {"water": "high_pressure_water_pipe", "steam": "pressurised_tube"}
PARTICLE = {"water": "bwr:block/pipes/painted_steel", "steam": "bwr:block/pipes/cladding", "oil": "bwr:block/pipes/painted_steel"}
OUTPUTS = {}


def put(path, data):
    if isinstance(data, dict):
        data = json.dumps(data, indent=2) + "\n"
    OUTPUTS[path] = data.encode("utf-8")


def normal(points):
    a, b, c = points[0], points[1], points[2]
    u = [b[i] - a[i] for i in range(3)]
    v = [c[i] - a[i] for i in range(3)]
    n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]
    if len(points) == 4 and math.sqrt(sum(x * x for x in n)) < 1e-12:
        a, b, c = points[1], points[2], points[3]
        u = [b[i] - a[i] for i in range(3)]
        v = [c[i] - a[i] for i in range(3)]
        n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]
    size = math.sqrt(sum(x * x for x in n))
    return tuple(x / size for x in n)


def obj(set_name, part, faces, mats):
    verts, uvs, normals, commands, current = {}, {}, {}, [], None

    def index(table, key):
        key = tuple(round(v, 6) for v in key)
        if key not in table:
            table[key] = len(table) + 1
        return table[key]
    for f in sorted(faces, key=lambda f: f["m"]):
        if f["m"] != current:
            commands.append("usemtl " + f["m"])
            current = f["m"]
        pts = [p[:3] for p in f["v"]]
        ni = index(normals, normal(pts))
        # flip_v is set in the model JSON: write v up, the way OBJ counts it.
        commands.append("f " + " ".join("%d/%d/%d" % (index(verts, p[:3]), index(uvs, (p[3], 1 - p[4])), ni) for p in f["v"]))
    lines = ["# Pipe %s %s: built by art/models/pipes/build_pipes.py, written by tools-export-pipes.py" % (set_name, part),
             "mtllib materials.mtl", "s off"]
    for prefix, table in (("v", verts), ("vt", uvs), ("vn", normals)):
        lines += [prefix + " " + " ".join("%.6f" % v for v in key) for key in table]
    return "\n".join(lines + commands) + "\n"


def model(set_name, part):
    return {"loader": "neoforge:obj", "model": "bwr:models/block/pipes/%s/%s.obj" % (set_name, part),
            # Faces on the block boundary (only the port flange's outer face) cull against a full neighbour.
            "automatic_culling": True, "shade_quads": True, "flip_v": True, "emissive_ambient": False,
            "ambientocclusion": False, "textures": {"particle": PARTICLE[set_name]}, "render_type": "minecraft:solid"}


def materials(mats):
    lines = []
    for name, m in sorted(mats.items()):
        lines += ["newmtl " + name, "Kd 1 1 1", "map_Kd " + m["texture"]]
        if m["tint"] >= 0:
            lines.append("neoforge_TintIndex %d" % m["tint"])
        lines.append("")
    return "\n".join(lines)


def export(set_name):
    data = json.load(open(os.path.join(ART, set_name, "mesh.json"), encoding="utf-8"))
    ship = data["spec"]["ship"]
    base = RES if ship else os.path.join(ART, set_name, "resources")
    folder = os.path.join(base, "assets", "bwr", "models", "block", "pipes", set_name)
    put(os.path.join(folder, "materials.mtl"), materials(data["materials"]))
    for part, faces in data["parts"].items():
        for f in faces:
            assert 3 <= len(f["v"]) <= 4, (set_name, part)
            for p in f["v"]:
                assert all(-1e-6 <= c <= 1 + 1e-6 for c in p[:3]), (set_name, part, p)
        put(os.path.join(folder, part + ".obj"), obj(set_name, part, faces, data["materials"]))
        put(os.path.join(folder, part + ".json"), model(set_name, part))
    # Every connection pattern needs both the tinted paint and plain metal (PumpModelCheck asserts it in game).
    for mask in range(64):
        tints = {data["materials"][f["m"]]["tint"] for f in data["parts"]["connection_%d" % mask]}
        assert 0 in tints and -1 in tints, (set_name, mask, tints)
    variants = {}
    for mask in range(64):
        key = ",".join("%s=%s" % (name, str(bool(mask >> i & 1)).lower()) for i, name in enumerate(DIRS))
        variants[key] = {"model": "bwr:block/pipes/%s/connection_%d" % (set_name, mask)}
    block = BLOCKS.get(set_name, set_name + "_pipe")
    put(os.path.join(base, "assets", "bwr", "blockstates", block + ".json"), {"variants": variants})
    put(os.path.join(base, "assets", "bwr", "models", "item", block + ".json"),
        {"parent": "bwr:block/pipes/%s/item" % set_name,
         "display": {"gui": {"rotation": [25, 225, 0], "scale": [.85, .85, .85]}, "ground": {"scale": [.5, .5, .5]}}})
    counts = {p: len(f) for p, f in data["parts"].items()}
    print("%s: %d parts, straight %d, elbow %d, tee %d, six-way %d, port flange %d polygons -> %s" % (
        set_name, len(counts), counts["connection_12"], counts["connection_36"], counts["connection_44"],
        counts["connection_63"], counts["port_north"], os.path.relpath(folder, ROOT)))


def main():
    for set_name in sorted(os.listdir(ART)):
        if os.path.isfile(os.path.join(ART, set_name, "mesh.json")):
            export(set_name)
    if "--check" in sys.argv:
        for path, data in OUTPUTS.items():
            if not os.path.isfile(path) or open(path, "rb").read() != data:
                raise SystemExit("Generated pipe asset differs: " + path)
        print(len(OUTPUTS), "pipe assets verified")
        return
    for path, data in OUTPUTS.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write(data)
    print(len(OUTPUTS), "pipe assets written")


if __name__ == "__main__":
    main()
