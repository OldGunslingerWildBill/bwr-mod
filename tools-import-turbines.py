#!/usr/bin/env python3
"""Import the two supplied, estimated-scale STEP box assemblies without CAD dependencies.

Python 3.10+; conversion/validation use only the standard library.
  python tools-import-turbines.py --source-dir C:/Users/14238/Downloads
  python tools-import-turbines.py --check --preview

--check compares every generated text asset without writing it. --preview requires
Pillow and NumPy, and writes tmp/rcic-hpci/preview.png.
Coordinates are absolute source millimetres / 1000, NOT rebased to the lowest
vertex (the 6.25 mm skid clearance is intentional). X east, Y up, Z south.
Only the supplied planar rectangular-prism BREP subset is supported: unexpected
topology, placement, units, missing colors, or source dimensions fail loudly.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import hashlib
import itertools
import json
import math
from pathlib import Path
import re

EPS = 1e-9
TEXTURE = "minecraft:block/white_concrete"
CONFIG = {
    "rcic_twl": {
        "file": "RCIC_TWL_Exterior.step", "size": (3, 3, 2), "parts": 405,
        "source_max_mm": (3000, 2345.9375, 2000),
        "ports": {"Steam_inlet_flange": "UP", "Pump_discharge_flange": "UP",
                  "Pump_suction_flange": "EAST", "Turbine_exhaust_flange": "SOUTH"},
    },
    "hpci_turbine": {
        "file": "HPCI_Turbine_Exterior.step", "size": (4, 5, 3), "parts": 763,
        "source_max_mm": (4000, 4162.5, 3000),
        "ports": {"Steam_inlet_flange": "UP", "Exhaust_front_flange": "WEST"},
        "water_adapters": [("Associated_pump_suction", [3, 0, 0]),
                           ("Associated_pump_discharge", [3, 0, 2])],
    },
}
DIRECTIONS = {"UP": (1, 1), "DOWN": (1, -1), "EAST": (0, 1),
              "WEST": (0, -1), "SOUTH": (2, 1), "NORTH": (2, -1)}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def normal(poly):
    n = (0., 0., 0.)
    for a, b in zip(poly, poly[1:] + poly[:1]):
        c = cross(a, b)
        n = tuple(x + y for x, y in zip(n, c))
    return n


def bounds(points):
    points = list(points)
    return [min(p[a] for p in points) for a in range(3)] + [
        max(p[a] for p in points) for a in range(3)] if points else []


def center(box):
    return tuple((box[a] + box[a + 3]) / 2 for a in range(3))


def clean(values):
    return [round(v, 9) for v in values]


@dataclass(frozen=True)
class Ref:
    id: int


def split_args(text):
    """Split STEP parameters, respecting nested aggregates and escaped strings."""
    depth, quoted, start, i = 0, False, 0, 0
    result = []
    while i < len(text):
        char = text[i]
        if char == "'":
            if quoted and i + 1 < len(text) and text[i + 1] == "'":
                i += 2
                continue
            quoted = not quoted
        elif not quoted:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            elif char == "," and depth == 0:
                result.append(text[start:i].strip())
                start = i + 1
        i += 1
    require(not quoted and depth == 0, "Unbalanced STEP parameter")
    result.append(text[start:].strip())
    return result


def value(text):
    if text.startswith("#"):
        return Ref(int(text[1:]))
    if text.startswith("'"):
        return text[1:-1].replace("''", "'")
    if text.startswith("("):
        return tuple(value(s) for s in split_args(text[1:-1]))
    if text in (".T.", ".F."):
        return text == ".T."
    if text in ("$", "*") or text.startswith(".") and text.endswith("."):
        return text
    try:
        return float(text.replace("D", "E"))
    except ValueError:
        return text


@dataclass
class Face:
    points: list
    normal: tuple
    color: tuple
    part: str


@dataclass
class Solid:
    name: str
    entity: int
    product: int
    faces: list
    box: list
    color: tuple


class Step:
    def __init__(self, path):
        self.raw = path.read_bytes()
        text = self.raw.decode("utf-8")
        self.entities = {
            int(i): body.strip() for i, body in re.findall(
                r"#(\d+)\s*=\s*((?:'[^']*(?:''[^']*)*'|[^;'])+);", text, re.S)
        }
        self.cache = {}
        self.kinds = defaultdict(list)
        for i, body in self.entities.items():
            self.kinds[body.split("(", 1)[0].strip()].append(i)
        require("SI_UNIT(.MILLI.,.METRE.)" in text, "Expected millimetres")
        require("CONVERSION_BASED_UNIT(" not in text, "Unexpected mixed units")
        self.root_name = self.get(self.kinds["PRODUCT"][0])[0]
        require("ESTIMATED" in self.root_name.upper(), "Missing estimated-scale provenance")
        # The supplied BREP vertices are absolute. Verify placement pairs agree,
        # instead of silently ignoring transformations on future replacement files.
        for i in self.kinds["ITEM_DEFINED_TRANSFORMATION"]:
            args = self.get(i)
            require(self.placement(args[2]) == self.placement(args[3]),
                    f"Nonidentity placement #{i}: this importer expects absolute vertices")
        self.color_map = {}
        for i in self.kinds["STYLED_ITEM"]:
            args = self.get(i)
            colors = set()
            for style in args[1]:
                colors.update(self.find_colors(style, set()))
            require(len(colors) == 1, f"Ambiguous/missing style color #{i}")
            self.color_map[args[2].id] = next(iter(colors))
        product_defs = {}
        for i in self.kinds["PRODUCT_DEFINITION"]:
            product = self.get(self.get(i)[2])[2]
            product_defs[i] = (product.id, self.get(product)[0])
        representations = {}
        for i in self.kinds["SHAPE_DEFINITION_REPRESENTATION"]:
            shape, representation = self.get(i)
            definition = self.get(shape)[2]
            if definition.id in product_defs:
                representations[representation.id] = product_defs[definition.id]
        self.solid_names = {}
        for rep, product in representations.items():
            if self.kind(rep) == "ADVANCED_BREP_SHAPE_REPRESENTATION":
                for ref in self.get(rep)[1]:
                    if self.kind(ref) == "MANIFOLD_SOLID_BREP":
                        require(ref.id not in self.solid_names, "Repeated solid instances unsupported")
                        self.solid_names[ref.id] = product

    def kind(self, ref):
        i = ref.id if isinstance(ref, Ref) else ref
        return self.entities[i].split("(", 1)[0].strip()

    def get(self, ref, expected=None):
        i = ref.id if isinstance(ref, Ref) else ref
        if expected:
            require(self.kind(i) == expected, f"#{i}: expected {expected}, got {self.kind(i)}")
        if i not in self.cache:
            body = self.entities[i]
            self.cache[i] = tuple(value(s) for s in split_args(body[body.index("(")+1:-1]))
        return self.cache[i]

    def placement(self, ref):
        args = self.get(ref, "AXIS2_PLACEMENT_3D")
        return tuple(self.get(r)[1] for r in args[1:])

    def find_colors(self, ref, seen):
        if ref.id in seen:
            return set()
        seen.add(ref.id)
        args = self.get(ref)
        if self.kind(ref) == "COLOUR_RGB":
            return {tuple(args[1:4])}
        found = set()
        def visit(v):
            if isinstance(v, Ref):
                found.update(self.find_colors(v, seen))
            elif isinstance(v, tuple):
                for x in v:
                    visit(x)
        for arg in args:
            visit(arg)
        return found

    def vertex(self, ref):
        point = self.get(ref, "VERTEX_POINT")[1]
        p = self.get(point, "CARTESIAN_POINT")[1]
        require(len(p) == 3, "Expected 3D vertex")
        return tuple(x / 1000 for x in p)

    def face(self, ref, fallback_color, name):
        args = self.get(ref, "ADVANCED_FACE")
        require(len(args[1]) == 1, f"{name}: holes/multiple bounds unsupported")
        plane = self.get(args[2], "PLANE")
        placement = self.get(plane[1], "AXIS2_PLACEMENT_3D")
        n = self.get(placement[2], "DIRECTION")[1]
        n = tuple(x * (1 if args[3] else -1) for x in n)
        bound = self.get(args[1][0])
        require(self.kind(args[1][0]) in ("FACE_BOUND", "FACE_OUTER_BOUND"), "Unsupported bound")
        loop = self.get(bound[1], "EDGE_LOOP")
        edges = []
        for oriented in loop[1]:
            edge = self.get(oriented, "ORIENTED_EDGE")
            curve = self.get(edge[3], "EDGE_CURVE")
            geometry = curve[3]
            if self.kind(geometry) in ("SURFACE_CURVE", "SEAM_CURVE"):
                geometry = self.get(geometry)[1]
            self.get(geometry, "LINE")
            a, b = self.vertex(curve[1]), self.vertex(curve[2])
            edges.append((a, b) if edge[4] else (b, a))
        require(all(a[1] == b[0] for a, b in zip(edges, edges[1:]+edges[:1])),
                f"{name}: disconnected oriented edge loop")
        poly = [e[0] for e in edges]
        if not bound[2]:
            poly.reverse()
        # STEP face sense determines the outward surface normal. Bound orientation
        # expresses topological traversal, not an OBJ front-face convention.
        if dot(normal(poly), n) < 0:
            poly.reverse()
        require(len(poly) == 4, f"{name}: expected a rectangular face")
        color = self.color_map.get(ref.id, fallback_color)
        return Face(poly, n, color, name)

    def solids(self):
        result = []
        for i in self.kinds["MANIFOLD_SOLID_BREP"]:
            require(i in self.solid_names, f"Unnamed solid #{i}")
            product, name = self.solid_names[i]
            require(i in self.color_map, f"Missing source solid color #{i}")
            color = self.color_map[i]
            shell = self.get(self.get(i)[1], "CLOSED_SHELL")[1]
            faces = [self.face(ref, color, name) for ref in shell]
            box = bounds(p for f in faces for p in f.points)
            require(len(faces) == 6, f"{name}: expected six box faces")
            corners = {p for f in faces for p in f.points}
            require(len(corners) == 8, f"{name}: expected eight prism corners")
            c = center(box)
            volume = 0
            edge_counts = Counter()
            for face in faces:
                validate_face(face)
                require(dot(face.normal, sub(center(bounds(face.points)), c)) > EPS,
                        f"{name}: inward source face")
                for a, b in zip(face.points, face.points[1:] + face.points[:1]):
                    edge_counts[tuple(sorted((a, b)))] += 1
                for j in range(1, len(face.points)-1):
                    volume += dot(face.points[0], cross(face.points[j], face.points[j+1])) / 6
            corner = min(corners)
            edges = [sub(b if a == corner else a, corner)
                     for a, b in edge_counts if a == corner or b == corner]
            require(len(edges) == 3 and all(abs(dot(a,b)) < 1e-8
                    for a,b in itertools.combinations(edges,2)), f"{name}: nonorthogonal prism")
            expected = abs(dot(edges[0], cross(edges[1], edges[2])))
            require(len(edge_counts) == 12 and set(edge_counts.values()) == {2},
                    f"{name}: nonmanifold box")
            require(math.isclose(volume, expected, rel_tol=1e-6, abs_tol=1e-12),
                    f"{name}: signed volume mismatch")
            result.append(Solid(name, i, product, faces, box, color))
        return result


def validate_face(face):
    p = face.points
    require(len(p) in (3, 4), f"{face.part}: unsupported OBJ polygon size")
    require(all(math.isfinite(v) for point in p for v in point), "Nonfinite vertex")
    require(len(set(p)) == len(p), f"{face.part}: duplicate face vertex")
    require(math.isclose(dot(face.normal, face.normal), 1., abs_tol=EPS), "Nonunit normal")
    for j in range(1, len(p)-1):
        require(dot(cross(sub(p[j], p[0]), sub(p[j+1], p[0])), face.normal) > 1e-13,
                f"{face.part}: degenerate or incorrectly wound triangle")
    require(all(abs(dot(sub(v, p[0]), face.normal)) < EPS for v in p), "Nonplanar face")


def box_faces(box, color, name):
    result = []
    for a in range(3):
        u, v = [i for i in range(3) if i != a]
        for side in (0, 1):
            poly = []
            for du, dv in ((0, 0), (1, 0), (1, 1), (0, 1)):
                p = [0., 0., 0.]
                p[a], p[u], p[v] = box[a+3*side], box[u+3*du], box[v+3*dv]
                poly.append(tuple(p))
            n = tuple((2*side-1) if i == a else 0 for i in range(3))
            if dot(normal(poly), n) < 0:
                poly.reverse()
            result.append(Face(poly, n, color, name))
    return result


def adapters(solids, config):
    faces, boxes, ports = [], [], []
    size = config["size"]
    for prefix, direction in config["ports"].items():
        matching = [s for s in solids if s.name.startswith(prefix + "_")]
        require(len(matching) == 8, f"{prefix}: expected eight flange segments")
        box = bounds(p for s in matching for f in s.faces for p in f.points)
        c = center(box)
        axis, sign = DIRECTIONS[direction]
        cell = [max(0, min(size[a]-1, math.floor(round(c[a], 9)))) for a in range(3)]
        cell[axis] = size[axis]-1 if sign > 0 else 0
        # Auto-connecting BWR tubes on adjacent upward ports would immediately
        # join steam and water. Leave one empty grid column between these risers.
        if prefix == "Pump_discharge_flange":
            cell[0] = 2
        end = [p + .5 for p in cell]
        end[axis] = size[axis] if sign > 0 else 0.
        surface = list(c)
        surface[axis] = box[axis+3] if sign > 0 else box[axis]
        # A square tube leaves the source flange normally, then shifts to the
        # nearest cell-face center. Joints overlap by one tube radius.
        mid = (surface[axis] + end[axis]) / 2
        route = [list(c)]
        p = list(c)
        p[axis] = mid
        route.append(p.copy())
        for a in range(3):
            if a != axis and abs(p[a]-end[a]) > EPS:
                p[a] = end[a]
                route.append(p.copy())
        route.append(end.copy())
        color = matching[0].color
        for j, (start, stop) in enumerate(zip(route, route[1:])):
            if max(abs(x-y) for x, y in zip(start, stop)) < EPS:
                continue
            tube = [max(0., min(start[a], stop[a])-.09) for a in range(3)] + [
                min(size[a], max(start[a], stop[a])+.09) for a in range(3)]
            # End inside the connector collar, avoiding overlapping exterior caps.
            if j == len(route)-2:
                tube[axis+3 if sign > 0 else axis] = end[axis]-sign*.065
            require(all(tube[a+3] > tube[a] for a in range(3)), "Invalid adapter")
            boxes.append(tube)
            faces.extend(box_faces(tube, color, f"adapter_{prefix}_{j}"))
        collar = [v-.16 for v in end] + [v+.16 for v in end]
        collar[axis], collar[axis+3] = sorted((end[axis], end[axis]-sign*.065))
        boxes.append(collar)
        faces.extend(box_faces(collar, (.64, .68, .72), f"adapter_{prefix}_connector"))
        index = cell[0] + size[0]*(cell[2] + size[2]*cell[1])
        ports.append({
            "name": prefix,
            "cell": index,
            "offset": cell,
            "face": direction,
            "source_parts": [s.name for s in matching],
            "source_center_mm": clean(v*1000 for v in c),
            "source_surface_center_mm": clean(v*1000 for v in surface),
            "face_center": clean(end),
            "cell_local_face_center": clean(end[a]-cell[a] for a in range(3)),
            "adapter_centerline": [clean(v) for v in route],
            "adapter_width": .18,
            "connector_width": .32,
            "closed_source_cover_removed": prefix == "Exhaust_front_flange",
            "note": (
                "Source exhaust blanking cover omitted to expose the connected port."
                if prefix == "Exhaust_front_flange"
                else "Adapter added for centered Minecraft tube connection."
            ),
        })
    # These are gameplay connections for the associated HPCI pump, explicitly
    # separate from the turbine-only STEP's real steam nozzles.
    for name, cell in config.get("water_adapters", []):
        x, y, z = cell
        pipe = [x+.12, y+.34, z+.34, x+.94, y+.66, z+.66]
        collar = [x+.94, y+.25, z+.25, x+1., y+.75, z+.75]
        boxes.extend([pipe, collar])
        faces.extend(box_faces(pipe, (.64, .68, .72), name + "_pipe"))
        faces.extend(box_faces(collar, (.08, .48, .70), name + "_collar"))
        end = [x+1., y+.5, z+.5]
        ports.append({"name": name, "cell": x+size[0]*(z+size[2]*y),
                      "offset": cell, "face": "EAST", "source_parts": [],
                      "face_center": end, "cell_local_face_center": [1., .5, .5],
                      "adapter_centerline": [[x+.12, y+.5, z+.5], end],
                      "adapter_width": .32, "connector_width": .50,
                      "closed_source_cover_removed": False,
                      "note": "Gameplay adapter for the associated pump; not a nozzle from the turbine-only STEP."})
    return faces, boxes, ports


def clip_plane(poly, axis, plane, keep_above):
    if not poly:
        return []
    result = []
    for start, end in zip(poly[-1:]+poly[:-1], poly):
        ds, de = start[axis]-plane, end[axis]-plane
        inside_s = ds >= -EPS if keep_above else ds <= EPS
        inside_e = de >= -EPS if keep_above else de <= EPS
        if inside_s != inside_e:
            t = ds / (ds-de)
            p = [start[a] + t*(end[a]-start[a]) for a in range(3)]
            p[axis] = plane
            result.append(tuple(p))
        if inside_e:
            result.append(end)
    unique = []
    for p in result:
        if not unique or max(abs(x-y) for x, y in zip(p, unique[-1])) > EPS:
            unique.append(p)
    if len(unique) > 1 and max(
        abs(x-y) for x, y in zip(unique[0], unique[-1])
    ) <= EPS:
        unique.pop()
    return unique


def cell_offset(index, size):
    width, height, depth = size
    return (index % width, index // (width*depth), (index//width) % depth)


def clip_cells(faces, boxes, size):
    cells = []
    for index in range(math.prod(size)):
        offset = cell_offset(index, size)
        clipped = []
        for face in faces:
            fb = bounds(face.points)
            if any(
                fb[a+3] < offset[a]-EPS or fb[a] > offset[a]+1+EPS
                for a in range(3)
            ):
                continue
            # A face on a grid plane belongs to the solid-interior side.
            # Assign it once; never duplicate it into both adjacent cells.
            if any(
                abs(fb[a+3]-fb[a]) < EPS
                and abs(fb[a]-round(fb[a])) < EPS
                and offset[a] != int(round(fb[a]))-(1 if face.normal[a] > 0 else 0)
                for a in range(3) if abs(face.normal[a]) > .5
            ):
                continue
            poly = face.points
            for a in range(3):
                poly = clip_plane(poly, a, offset[a], True)
                poly = clip_plane(poly, a, offset[a]+1, False)
            if len(poly) < 3 or dot(normal(poly), face.normal) <= 1e-13:
                continue
            local = [
                tuple(max(0., min(1., p[a]-offset[a])) for a in range(3))
                for p in poly
            ]
            # NeoForge's OBJ baker accepts triangles/quads, not general ngons.
            polygons = [local] if len(local) <= 4 else [
                [local[0], local[j], local[j+1]]
                for j in range(1, len(local)-1)
            ]
            for polygon in polygons:
                f = Face(polygon, face.normal, face.color, face.part)
                validate_face(f)
                clipped.append(f)

        # Include solid interiors in collision even without a visible face.
        intersections = []
        for box in boxes:
            low = [max(box[a], offset[a]) for a in range(3)]
            high = [min(box[a+3], offset[a]+1) for a in range(3)]
            if all(high[a]-low[a] > EPS for a in range(3)):
                intersections += [sub(low, offset), sub(high, offset)]
        cells.append({
            "index": index,
            "offset": list(offset),
            "bounds": clean(bounds(intersections)),
            "faces": clipped,
        })

    before = sum(
        math.sqrt(dot(normal(f.points), normal(f.points))) / 2 for f in faces
    )
    after = sum(
        math.sqrt(dot(normal(f.points), normal(f.points))) / 2
        for cell in cells for f in cell["faces"]
    )
    require(
        math.isclose(before, after, rel_tol=1e-8, abs_tol=1e-8),
        f"Clipping lost/duplicated surface area: {before} -> {after}",
    )
    return cells, before


def obj_text(faces, materials, mtl):
    lines = [
        "# Generated by tools-import-turbines.py; estimated STEP scale.",
        f"mtllib {mtl}",
        "s off",
    ]
    vertices, indices = [], {}

    def vertex(p):
        key = tuple(round(v, 9) for v in p)
        if key not in indices:
            vertices.append(key)
            indices[key] = len(vertices)
        return indices[key]

    normals = sorted(set(f.normal for f in faces))
    normal_ids = {n: i+1 for i, n in enumerate(normals)}
    commands, current = [], None
    for f in sorted(faces, key=lambda f: (materials[f.color], f.part)):
        validate_face(f)
        mat = materials[f.color]
        if current != mat:
            commands.append(f"usemtl {mat}")
            current = mat
        commands.append("f " + " ".join(
            f"{vertex(p)}/{j+1}/{normal_ids[f.normal]}"
            for j, p in enumerate(f.points)
        ))
    lines += ["v " + " ".join(f"{v:.9f}" for v in p) for p in vertices]
    lines += ["vt 0 0", "vt 1 0", "vt 1 1", "vt 0 1"]
    lines += ["vn " + " ".join(f"{v:.9f}" for v in n) for n in normals]
    return "\n".join(lines + commands) + "\n"


def obj_model(location):
    return {
        "loader": "neoforge:obj",
        "model": location,
        "automatic_culling": False,
        "shade_quads": True,
        "flip_v": True,
        "emissive_ambient": False,
        "ambientocclusion": False,
        "textures": {"particle": TEXTURE},
        "render_type": "minecraft:solid",
    }


def json_text(data):
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def verify_obj(text, materials, unit_box=False):
    vertices, normals, uv_count, count = [], [], 0, 0
    for line in text.splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] == "v":
            p = tuple(map(float, fields[1:]))
            require(
                not unit_box or all(-EPS <= v <= 1+EPS for v in p),
                "OBJ outside unit cell",
            )
            vertices.append(p)
        elif fields[0] == "vn":
            normals.append(tuple(map(float, fields[1:])))
        elif fields[0] == "vt":
            uv_count += 1
        elif fields[0] == "usemtl":
            require(fields[1] in materials.values(), "Unresolved material")
        elif fields[0] == "f":
            refs = [tuple(map(int, item.split("/"))) for item in fields[1:]]
            require(
                all(
                    1 <= v <= len(vertices)
                    and 1 <= t <= uv_count
                    and 1 <= n <= len(normals)
                    for v, t, n in refs
                ),
                "Bad OBJ index",
            )
            require(len({r[2] for r in refs}) == 1, "Mixed face normals")
            validate_face(Face(
                [vertices[v-1] for v, t, n in refs],
                normals[refs[0][2]-1],
                (1, 1, 1),
                "serialized OBJ",
            ))
            count += 1
    return count


def generate(key, config, source_dir):
    source = source_dir / config["file"]
    step = Step(source)
    solids = step.solids()
    require(len(solids) == config["parts"], f"{key}: unexpected part count")
    source_box = bounds(
        p for solid in solids for face in solid.faces for p in face.points
    )
    expected = [0., .00625, 0.] + [
        v/1000 for v in config["source_max_mm"]
    ]
    require(
        all(abs(a-b) < EPS for a, b in zip(source_box, expected)),
        "Unexpected source bounds",
    )
    extra_faces, extra_boxes, ports = adapters(solids, config)
    omitted = [s for s in solids if key == "hpci_turbine" and s.name.startswith("Exhaust_closed_cover_")]
    runtime_solids = [s for s in solids if s not in omitted]
    faces = [f for s in runtime_solids for f in s.faces] + extra_faces
    for f in faces:
        validate_face(f)
    cells, area = clip_cells(
        faces, [s.box for s in runtime_solids] + extra_boxes, config["size"]
    )
    colors = sorted(set(f.color for f in faces) | {s.color for s in solids})
    materials = {color: f"step_color_{i:03d}" for i, color in enumerate(colors)}
    assets = Path("mod/src/main/resources/assets/bwr")
    folder = assets / "models/block/turbines" / key
    location = f"bwr:models/block/turbines/{key}/"
    outputs = {}

    mtl = ["# Source STEP COLOUR_RGB -> MTL Kd; neutral metal adapter collars."]
    for color, name in materials.items():
        mtl += [
            f"newmtl {name}",
            "Kd " + " ".join(f"{v:.9f}" for v in color),
            "Ka 0 0 0",
            "d 1",
            f"map_Kd {TEXTURE}",
            "",
        ]
    outputs[folder/"materials.mtl"] = "\n".join(mtl).rstrip() + "\n"
    outputs[folder/"empty.json"] = json_text({
        "textures": {"particle": TEXTURE}, "elements": [],
    })

    face_count = 0
    for cell in cells:
        name = f"cell_{cell['index']}"
        obj = obj_text(cell["faces"], materials, "materials.mtl")
        face_count += verify_obj(obj, materials, True)
        outputs[folder/(name+".obj")] = obj
        outputs[folder/(name+".json")] = json_text(
            obj_model(location+name+".obj")
        )

    for index in range(len(cells), 60):
        cells.append({
            "index": index, "offset": None, "bounds": [], "faces": [],
        })
    variants = {}
    for cell in cells:
        suffix = (
            f"cell_{cell['index']}"
            if cell["index"] < math.prod(config["size"]) else "empty"
        )
        model = f"bwr:block/turbines/{key}/{suffix}"
        for facing, rotation in (
            ("north", 0), ("east", 90), ("south", 180), ("west", 270)
        ):
            variants[f"cell={cell['index']},facing={facing}"] = {
                "model": model, "y": rotation,
            }
    outputs[assets/f"blockstates/{key}.json"] = json_text({"variants": variants})
    outputs[assets/f"models/block/{key}.json"] = json_text({
        "parent": f"bwr:block/turbines/{key}/cell_0",
    })

    assembly = obj_text(faces, materials, "materials.mtl")
    verify_obj(assembly, materials)
    outputs[folder/"assembly.obj"] = assembly

    # Uniformly normalize the full assembly, including adapters, into an icon.
    full_box = bounds(p for f in faces for p in f.points)
    middle = center(full_box)
    icon_scale = .9 / max(full_box[a+3]-full_box[a] for a in range(3))
    icon = [
        Face(
            [
                tuple((p[a]-middle[a])*icon_scale+.5 for a in range(3))
                for p in f.points
            ],
            f.normal, f.color, f.part,
        )
        for f in faces
    ]
    icon_obj = obj_text(icon, materials, "materials.mtl")
    verify_obj(icon_obj, materials, True)
    outputs[folder/"item.obj"] = icon_obj
    item = obj_model(location+"item.obj")
    item["gui_light"] = "front"
    item["display"] = {
        "gui": {
            "rotation": [25, 225, 0],
            "translation": [0, 0, 0],
            "scale": [1, 1, 1],
        },
        "ground": {
            "rotation": [0, 0, 0],
            "translation": [0, 3, 0],
            "scale": [.5, .5, .5],
        },
        "fixed": {
            "rotation": [0, 180, 0],
            "translation": [0, 0, 0],
            "scale": [.8, .8, .8],
        },
        "thirdperson_righthand": {
            "rotation": [75, 45, 0],
            "translation": [0, 2.5, 0],
            "scale": [.5, .5, .5],
        },
        "firstperson_righthand": {
            "rotation": [0, 45, 0],
            "translation": [0, 0, 0],
            "scale": [.65, .65, .65],
        },
        "thirdperson_lefthand": {
            "rotation": [75, -45, 0],
            "translation": [0, 2.5, 0],
            "scale": [.5, .5, .5],
        },
        "firstperson_lefthand": {
            "rotation": [0, -45, 0],
            "translation": [0, 0, 0],
            "scale": [.65, .65, .65],
        },
    }
    outputs[assets/f"models/item/{key}.json"] = json_text(item)

    metadata = {
        "schema_version": 1,
        "id": key,
        "width": config["size"][0],
        "height": config["size"][1],
        "depth": config["size"][2],
        "root": [0, 0, 0],
        "cell_count": math.prod(config["size"]),
        "state_cell_count": 60,
        "index_formula": "x + width * (z + depth * y)",
        "coordinates": {
            "units": "blocks (1 block = 1000 mm)",
            "x": "EAST", "y": "UP", "z": "SOUTH",
            "source_origin_mm": [0, 0, 0],
            "north_rotation": 0,
            "east_rotation": 90,
            "south_rotation": 180,
            "west_rotation": 270,
            "east_offset_transform": "(-z, y, x)",
        },
        "source": {
            "file": source.name,
            "sha256": hashlib.sha256(step.raw).hexdigest(),
            "product": step.root_name,
            "parts": len(solids),
            "units": "mm",
            "bounds_mm": clean(v*1000 for v in source_box),
            "estimated_scale": True,
            "scale_provenance": (
                "Supplied STEP product is explicitly ESTIMATED; dimensions "
                "are visualization estimates, not verified engineering measurements. "
                "Absolute millimetres converted at 1000 mm per block without rescaling."
            ),
            "transform": (
                "identity; absolute BREP vertices, including 6.25 mm bottom clearance"
            ),
        },
        "port_selection": (
            "Nearest projected exterior face center, clamped to envelope; RCIC discharge "
            "uses x=2 to leave a gap between auto-connecting water and steam risers."
        ),
        "ports": ports,
        "omitted_source_parts": [s.name for s in omitted],
        "omission_reason": "Remove the HPCI exhaust blanking cover for its connected in-game configuration.",
        "cells": [
            {k: v for k, v in cell.items() if k != "faces"}
            for cell in cells
        ],
        "parts": [
            {
                "name": s.name,
                "brep_entity": s.entity,
                "product_entity": s.product,
                "bounds_mm": clean(v*1000 for v in s.box),
                "color": list(s.color),
                "material": materials[s.color],
            }
            for s in solids
        ],
        "materials": [
            {"name": name, "kd": list(color), "texture": TEXTURE}
            for color, name in materials.items()
        ],
        "item": {
            "scale": icon_scale,
            "source_center_blocks": clean(middle),
            "normalized_bounds": clean(bounds(
                p for f in icon for p in f.points
            )),
        },
        "validation": {
            "source_faces": len(solids)*6,
            "adapter_faces": len(extra_faces),
            "clipped_faces": face_count,
            "surface_area_blocks2": round(area, 9),
            "winding": "outward; source signed volumes and serialized normals checked",
            "degenerate_faces": 0,
            "cell_bbox": "all OBJ vertices in [0,1]^3",
            "collision": "envelope of source solid and adapter intersections per cell",
            "blockstate_variants": len(variants),
            "source_unit_conversion": .001,
            "runtime_bake_check": "./gradlew.bat :mod:runTurbineModelCheck",
        },
    }
    verify_metadata(metadata)
    outputs[Path(
        f"mod/src/main/resources/data/bwr/turbine_models/{key}.json"
    )] = json_text(metadata)
    return outputs, metadata, cells, materials


def verify_metadata(meta):
    require(len(meta["cells"]) == 60, "Missing CELL state metadata")
    size = (meta["width"], meta["height"], meta["depth"])
    for cell in meta["cells"]:
        b = cell["bounds"]
        require(
            not b or (
                len(b) == 6 and all(0 <= v <= 1 for v in b)
                and all(b[a] < b[a+3] for a in range(3))
            ),
            "Invalid collision bounds",
        )
        if cell["index"] >= meta["cell_count"]:
            require(not b, "Unreachable cell has collision")
    for port in meta["ports"]:
        offset = port["offset"]
        require(
            tuple(offset) == cell_offset(port["cell"], size),
            "Port index/offset mismatch",
        )
        a, sign = DIRECTIONS[port["face"]]
        require(
            offset[a] == (size[a]-1 if sign > 0 else 0),
            "Interior port",
        )
        end = port["cell_local_face_center"]
        require(
            end[a] == (1 if sign > 0 else 0)
            and all(end[i] == .5 for i in range(3) if i != a),
            "Off-center port",
        )
        b = meta["cells"][port["cell"]]["bounds"]
        require(
            b and all(b[i]-EPS <= end[i] <= b[i+3]+EPS for i in range(3)),
            "Connector missing from collision envelope",
        )


def self_check():
    """Check cell-boundary ownership and crossing-face clipping."""
    cube = box_faces([0, 0, 0, 2, 2, 2], (.5, .5, .5), "boundary cube")
    cells, area = clip_cells(cube, [[0, 0, 0, 2, 2, 2]], (2, 2, 2))
    require(
        area == 24
        and all(c["bounds"] == [0, 0, 0, 1, 1, 1] for c in cells),
        "Cube clipping regression",
    )
    require(
        sum(len(c["faces"]) for c in cells) == 24,
        "Duplicate boundary faces",
    )
    slab = box_faces(
        [.8, .3, .7, 1.2, 1.8, 1.3], (.5, .5, .5), "crossing slab"
    )
    clip_cells(slab, [[.8, .3, .7, 1.2, 1.8, 1.3]], (2, 2, 2))


def preview(root, generated):
    """Render the saved assets with the bundled Pillow/NumPy renderer."""
    import subprocess
    import sys
    subprocess.run([sys.executable, str(root / "tools-render-turbines.py"), str(root)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir", type=Path, default=Path.home()/"Downloads"
    )
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parent
    )
    parser.add_argument(
        "--check", action="store_true",
        help="Compare generated text assets without writing",
    )
    parser.add_argument(
        "--preview", action="store_true",
        help="Render saved OBJ files using matplotlib",
    )
    args = parser.parse_args()
    self_check()
    outputs, generated = {}, []
    for key, config in CONFIG.items():
        files, metadata, cells, materials = generate(
            key, config, args.source_dir
        )
        outputs.update(files)
        generated.append((metadata, cells, materials))
        print(
            f"{key}: {metadata['source']['parts']} parts, "
            f"{metadata['cell_count']} cells, "
            f"{metadata['validation']['clipped_faces']} clipped faces, 240 variants"
        )
        for p in metadata["ports"]:
            print(
                f"  {p['name']}: cell {p['cell']} offset {p['offset']} "
                f"{p['face']} -> {p['face_center']}"
            )

    mismatches = []
    for relative, content in outputs.items():
        path = args.root / relative
        encoded = content.encode("utf-8")
        if args.check:
            if not path.is_file() or path.read_bytes() != encoded:
                mismatches.append(str(relative))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(encoded)
    require(
        not mismatches,
        "Generated assets differ:\n" + "\n".join(mismatches),
    )
    print(
        f"{'Verified' if args.check else 'Wrote'} {len(outputs)} deterministic text assets; "
        "source topology, winding, volumes, clipping area, serialized OBJ bounds, "
        "ports and metadata passed."
    )
    if args.preview:
        preview(args.root, generated)


if __name__ == "__main__":
    main()
