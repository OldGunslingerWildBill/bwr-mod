"""Bake the recirculation remodel (art/models/recirc, built by build_recirc.py in headless Blender) into NeoForge OBJ
cells, preserving atlas UVs. --check is read-only.

Writes, for recirculation_pump, jet_pump and rip_pump:
  assets/bwr/models/block/pumps/<id>/cell_N.obj|json   one per occupied cell, clipped to its own block
  assets/bwr/models/block/pumps/<id>/recirc.mtl        one material on bwr:block/pumps/recirc_atlas
  assets/bwr/models/block/pumps/<id>/inventory.obj|json and models/item/<id>.json
  data/bwr/pump_models/<id>.json                        footprint, controller, ports (unchanged) and new cell bounds

Old saves keep their own models: compact.obj, assembled.obj and materials.mtl in these folders, and the
recirculation_pump_legacy / jet_pump_legacy folders, are not touched (tools-export-dvss.py and tools-narrow-jet.py
still own and verify them). Footprints, controller cells, port cells and faces are asserted against the manifests.
"""
import json, math, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'art/models/recirc'
RES = ROOT / 'mod/src/main/resources'
EPS = 1e-7
OUTPUTS = {}
# Baked-face budgets: the replaced models' sizes (recirculation pump 13,612, jet pair 14,242, RIP 4,120 baked faces).
BUDGET = {'recirculation_pump': 13612, 'jet_pump': 14242, 'rip_pump': 4120}
# The coupling guards are expanded metal with real holes, so the external pump's cells use cutout.
RENDER = {'recirculation_pump': 'minecraft:cutout', 'jet_pump': 'minecraft:solid', 'rip_pump': 'minecraft:solid'}
ITEM_DISPLAY = {
    'recirculation_pump': {'gui': {'rotation': [20, 225, 0], 'scale': [.9, .9, .9]}, 'ground': {'scale': [.3, .3, .3]}, 'fixed': {'scale': [.7, .7, .7]}},
    'jet_pump': {'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.8, .8, .8]}, 'ground': {'scale': [.3, .3, .3]}, 'fixed': {'scale': [.7, .7, .7]}},
    'rip_pump': {'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.8, .8, .8]}, 'ground': {'scale': [.3, .3, .3]}, 'fixed': {'scale': [.7, .7, .7]}},
}


def put(path, data):
    OUTPUTS[path] = (json.dumps(data, indent=2) + '\n') if isinstance(data, dict) else data


def sub(a, b): return tuple(a[i] - b[i] for i in range(3))
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def length(a): return math.sqrt(sum(x * x for x in a))
def area(p): return sum(length(cross(sub(p[i], p[0]), sub(p[i + 1], p[0]))) / 2 for i in range(1, len(p) - 1))
def normal(p):
    best = (0, 0, 0)
    for i in range(1, len(p) - 1):
        n = cross(sub(p[i], p[0]), sub(p[i + 1], p[0]))
        if length(n) > length(best): best = n
    size = length(best)
    return tuple(x / size for x in best) if size > 1e-12 else (0, 0, 0)
def bounds(points):
    return [min(p[i] for p in points) for i in range(3)] + [max(p[i] for p in points) for i in range(3)] if points else []


def clip(poly, axis, plane, above):
    """Sutherland-Hodgman against one grid plane; interpolates position and UV together."""
    out = []
    for a, b in zip(poly[-1:] + poly[:-1], poly):
        da, db = a[axis] - plane, b[axis] - plane
        ia = da >= -EPS if above else da <= EPS
        ib = db >= -EPS if above else db <= EPS
        if ia != ib:
            t = da / (da - db)
            p = [a[k] + t * (b[k] - a[k]) for k in range(5)]
            p[axis] = plane
            out.append(p)
        if ib: out.append(b)
    result = []
    for p in out:
        if not result or length(sub(p, result[-1])) > EPS: result.append(p)
    if len(result) > 1 and length(sub(result[0], result[-1])) <= EPS: result.pop()
    return result


def obj(faces, mtl):
    verts, uvs, normals, commands = {}, {}, {}, ['usemtl recirc_atlas']
    def index(table, key):
        key = tuple(round(v, 9) for v in key)
        if key not in table: table[key] = len(table) + 1
        return table[key]
    for f in faces:
        n = normal([v[:3] for v in f])
        if n == (0, 0, 0): continue
        ni = index(normals, n)
        commands.append('f ' + ' '.join(f'{index(verts, p[:3])}/{index(uvs, p[3:])}/{ni}' for p in f))
    lines = ['# Built by art/models/recirc/build_recirc.py (headless Blender); baked by tools-export-recirc.py', f'mtllib {mtl}', 's off']
    for prefix, table in (('v', verts), ('vt', uvs), ('vn', normals)):
        lines += [prefix + ' ' + ' '.join(f'{v:.9f}' for v in key) for key in table]
    return '\n'.join(lines + commands) + '\n'


def model(id, name):
    return {'loader': 'neoforge:obj', 'model': f'bwr:models/block/pumps/{id}/{name}.obj', 'automatic_culling': False,
            'shade_quads': True, 'flip_v': True, 'emissive_ambient': False, 'ambientocclusion': False,
            'textures': {'particle': 'minecraft:block/iron_block'}, 'render_type': RENDER[id]}


def bake(id):
    source = json.loads((SOURCE / f'{id}.json').read_text())
    w, h, d = source['size']
    manifest_path = RES / f'data/bwr/pump_models/{id}.json'
    manifest = json.loads(manifest_path.read_text())
    # The remodel keeps every gameplay contract: footprint, controller, ports.
    assert [manifest['width'], manifest['height'], manifest['depth']] == [w, h, d], id
    assert manifest['controller_cell'] == source['controller'], id
    assert manifest['game_ports'] == source['ports'], (id, manifest['game_ports'], source['ports'])
    folder = RES / f'assets/bwr/models/block/pumps/{id}'
    put(folder / 'recirc.mtl', 'newmtl recirc_atlas\nKd 1 1 1\nKa 0 0 0\nd 1\nmap_Kd ' + source['texture'] + '\n')
    faces = []
    for f in source['faces']:
        v = [list(p) for p in f['vertices']]
        for p in v:
            for i in range(3):
                if abs(p[i] - round(p[i])) < 1e-6: p[i] = float(round(p[i]))
        if area([p[:3] for p in v]) < 1e-12: continue
        # bent tube sections can leave a quad slightly warped; split those so every polygon is planar
        n = normal([p[:3] for p in v[:3]])
        warped = len(v) == 4 and (n == (0, 0, 0) or abs(sum((v[3][a] - v[0][a]) * n[a] for a in range(3))) > 1e-6
                                  or sum(x * y for x, y in zip(normal([p[:3] for p in (v[0], v[2], v[3])]), n)) < .999)
        for poly in ([v[:3], [v[0], v[2], v[3]]] if warped else [v]):
            if area([p[:3] for p in poly]) < 1e-12: continue
            faces.append({'v': poly, 'n': normal([p[:3] for p in poly]), 'b': bounds([p[:3] for p in poly])})
    cells, baked, after = [], 0, 0.0
    for i in range(w * h * d):
        off = (i % w, i // (w * d), (i // w) % d)
        out, collision = [], []
        for f in faces:
            b, n = f['b'], f['n']
            if any(b[a + 3] < off[a] - EPS or b[a] > off[a] + 1 + EPS for a in range(3)): continue
            # a face lying on a grid plane belongs to the cell on its back side, exactly once
            if any(abs(b[a + 3] - b[a]) < EPS and abs(b[a] - round(b[a])) < EPS
                   and off[a] != round(b[a]) - (1 if n[a] > 0 else 0) for a in range(3) if abs(n[a]) > .5): continue
            p = f['v']
            for axis in range(3):
                p = clip(p, axis, off[axis], True) if p else []
                p = clip(p, axis, off[axis] + 1, False) if p else []
            if len(p) < 3 or area([q[:3] for q in p]) < 1e-12: continue
            p = [[max(0., min(1., q[a] - off[a])) for a in range(3)] + q[3:] for q in p]
            polys = [p] if len(p) <= 4 else [[p[0], p[j], p[j + 1]] for j in range(1, len(p) - 1)]
            for poly in polys:
                after += area([q[:3] for q in poly]); out.append(poly)
        for b in source['boxes']:
            low = [max(b[a], off[a]) - off[a] for a in range(3)]
            high = [min(b[a + 3], off[a] + 1) - off[a] for a in range(3)]
            if all(high[a] > low[a] + EPS for a in range(3)): collision.extend((low, high))
        for f in out: collision.extend(q[:3] for q in f)
        cb = [round(v, 7) for v in bounds(collision)]
        cell = {'index': i, 'offset': list(off), 'bounds': cb}
        if 'triangles' in manifest['cells'][0]: cell['triangles'] = len(out)
        cells.append(cell)
        baked += len(out)
        if out:
            put(folder / f'cell_{i}.obj', obj(out, 'recirc.mtl'))
            put(folder / f'cell_{i}.json', model(id, f'cell_{i}'))
        else:
            # an empty cell keeps a valid (empty) model so the blockstate never points at a missing file
            put(folder / f'cell_{i}.obj', obj([], 'recirc.mtl'))
            put(folder / f'cell_{i}.json', model(id, f'cell_{i}'))
    before = sum(area([p[:3] for p in f['v']]) for f in faces)
    assert math.isclose(before, after, rel_tol=2e-6, abs_tol=2e-6), (id, before, after)
    assert baked <= BUDGET[id], f'{id}: {baked} baked faces exceeds the {BUDGET[id]} budget'
    # every port cell carries geometry reaching its outward face (the flange the pipe meets)
    for port in manifest['game_ports']:
        x, y, z = port['cell']; idx = x + w * (z + d * y); b = cells[idx]['bounds']
        axis, edge = {'north': (2, 0), 'south': (2, 1), 'west': (0, 0), 'east': (0, 1), 'up': (1, 1), 'down': (1, 0)}[port['face']]
        reach = b[axis] if edge == 0 else b[axis + 3]
        assert abs(reach - edge) < 1e-6, (id, port, b)
    # inventory model: the whole machine scaled into one block, like the compact model
    all_points = [p for f in faces for p in f['v']]; bb = bounds([p[:3] for p in all_points])
    scale = .9 / max(bb[a + 3] - bb[a] for a in range(3))
    inv = [[[(p[a] - (bb[a] + bb[a + 3]) / 2) * scale + .5 for a in range(3)] + p[3:] for p in f['v']] for f in faces]
    put(folder / 'inventory.obj', obj(inv, 'recirc.mtl'))
    put(folder / 'inventory.json', model(id, 'inventory'))
    put(RES / f'assets/bwr/models/item/{id}.json', {'parent': f'bwr:block/pumps/{id}/inventory', 'display': ITEM_DISPLAY[id]})
    manifest = dict(manifest)
    manifest['cells'] = cells
    if 'validation' in manifest:
        manifest['validation'] = {'source_triangles': len(faces), 'clipped_triangles': baked, 'source_surface_area_m2': round(before, 6),
                                  'clipped_surface_area_m2': round(after, 6), 'cell_bounds_pass': True}
    manifest['model_source'] = 'art/models/recirc/build_recirc.py (headless Blender); tools-export-recirc.py'
    put(manifest_path, manifest)
    print(f'{id}: {len(faces)} source faces -> {baked} baked faces in {sum(1 for c in cells if c["bounds"])} cells (budget {BUDGET[id]})')


for id in ('recirculation_pump', 'jet_pump', 'rip_pump'):
    bake(id)
check = '--check' in sys.argv
for p, text in OUTPUTS.items():
    if check:
        if not p.exists() or p.read_text(encoding='utf-8') != text: raise SystemExit('Asset differs: ' + str(p))
    else:
        p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text, encoding='utf-8')
print(f'Recirculation remodel: {len(OUTPUTS)} assets ' + ('verified' if check else 'written'))
