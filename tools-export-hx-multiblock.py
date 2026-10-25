"""Cell-split the full-size RHR heat exchanger (art/models/suppression/rhr_hx_assembly.json) into block models; --check is read-only.

The source mesh comes from art/models/suppression/build_hx_multiblock.py (headless Blender). This reuses the clipping,
OBJ and material writers of tools-export-modern.py, the same pipeline the pump assemblies use, and writes:

  assets/bwr/models/block/suppression/hx_assembly/cell_<i>.{obj,json}, materials.mtl, inventory.{obj,json}
  assets/bwr/blockstates/rhr_heat_exchanger.json   (assembled=false: the alpha.54 single block; assembled=true: one cell)
  assets/bwr/models/item/rhr_heat_exchanger.json   (held item: a scaled copy of the full unit)
  data/bwr/exchanger_models/rhr_heat_exchanger.json (footprint, controller cell, ports and per-cell collision bounds)
  data/bwr/loot_table/blocks/rhr_heat_exchanger.json (one item from a legacy block or the assembly's controller cell)
"""
import importlib.util
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/suppression'
RES = m.RES
EPS = m.EPS
FOLDER = RES / 'assets/bwr/models/block/suppression/hx_assembly'
LOCATION = 'bwr:models/block/suppression/hx_assembly'


def bake():
    source = m.read('rhr_hx_assembly.json')
    w, h, d = source['size']
    count = w * h * d
    assert count <= 256
    mats = m.materials(source, FOLDER)
    faces = source['faces']
    cells, after, occupied = [], 0.0, []
    for i in range(count):
        off = (i % w, i // (w * d), (i // w) % d)
        out, collision = [], []
        for face in faces:
            b = face['bounds']
            n = face['normal']
            if any(b[a + 3] < off[a] - EPS or b[a] > off[a] + 1 + EPS for a in range(3)):
                continue
            # A face lying exactly on a cell boundary belongs to the cell it faces out of, never to both.
            if any(abs(b[a + 3] - b[a]) < EPS and abs(b[a] - round(b[a])) < EPS
                   and off[a] != round(b[a]) - (1 if n[a] > 0 else 0) for a in range(3) if abs(n[a]) > .5):
                continue
            p = face['vertices']
            for axis in range(3):
                p = m.clip(p, axis, off[axis], True) if p else []
                p = m.clip(p, axis, off[axis] + 1, False) if p else []
            if len(p) < 3 or m.area(p) < 1e-12:
                continue
            p = [[max(0, min(1, v[a] - off[a])) for a in range(3)] + v[3:] for v in p]
            polygons = [p] if len(p) <= 4 else [[p[0], p[j], p[j + 1]] for j in range(1, len(p) - 1)]
            for poly in polygons:
                after += m.area(poly)
                out.append(dict(face, vertices=poly))
        for b in source['boxes']:
            low = [max(b[a], off[a]) - off[a] for a in range(3)]
            high = [min(b[a + 3], off[a] + 1) - off[a] for a in range(3)]
            if all(high[a] > low[a] + EPS for a in range(3)):
                collision.extend((low, high))
        for f in out:
            collision.extend(v[:3] for v in f['vertices'])
        cells.append({'index': i, 'offset': list(off), 'bounds': m.bounds(collision), 'triangles': len(out)})
        if out:
            occupied.append(i)
            m.put(FOLDER / f'cell_{i}.obj', m.obj(out, mats))
            model = m.model(f'{LOCATION}/cell_{i}.obj')
            model['textures']['particle'] = 'bwr:block/suppression_pool_wall'
            m.put(FOLDER / f'cell_{i}.json', model)
    before = sum(m.area(f['vertices']) for f in faces)
    assert math.isclose(before, after, rel_tol=2e-6, abs_tol=2e-6), (before, after)
    pts = [p for f in faces for p in f['vertices']]
    b = m.bounds(pts)
    assert all(b[a] >= -EPS and b[a + 3] <= source['size'][a] + EPS for a in range(3)), b
    # Held item: the whole unit scaled into one block.
    scale = .95 / max(b[a + 3] - b[a] for a in range(3))
    small = [dict(f, vertices=[[(p[a] - (b[a] + b[a + 3]) / 2) * scale + .5 for a in range(3)] + p[3:] for p in f['vertices']]) for f in faces]
    m.put(FOLDER / 'inventory.obj', m.obj(small, mats))
    inv = m.model(f'{LOCATION}/inventory.obj')
    inv['textures']['particle'] = 'bwr:block/suppression_pool_wall'
    m.put(FOLDER / 'inventory.json', inv)
    c = source['controller']
    controller = c[0] + w * (c[2] + d * c[1])
    m.put(RES / 'data/bwr/exchanger_models/rhr_heat_exchanger.json',
          {'width': w, 'height': h, 'depth': d, 'controller_cell': c, 'game_ports': source['ports'], 'cells': cells})
    variants = {}
    for facing, angle in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270)):
        for assembled in (False, True):
            for i in range(count):
                key = f'assembled={str(assembled).lower()},cell={i},facing={facing}'
                if not assembled:
                    variants[key] = {'model': 'bwr:block/suppression/heat_exchanger/body', 'y': angle}
                elif i in occupied:
                    variants[key] = {'model': f'bwr:block/suppression/hx_assembly/cell_{i}', 'y': angle}
                else:
                    variants[key] = {'model': 'bwr:block/pumps/empty', 'y': angle}
    m.put(RES / 'assets/bwr/blockstates/rhr_heat_exchanger.json', {'variants': variants})
    m.put(RES / 'assets/bwr/models/item/rhr_heat_exchanger.json', {
        'parent': 'bwr:block/suppression/hx_assembly/inventory',
        'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]},
                    'ground': {'scale': [.5, .5, .5]},
                    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [.375, .375, .375]},
                    'firstperson_righthand': {'rotation': [0, 45, 0], 'scale': [.4, .4, .4]}}})
    # One item: from a single block saved before the multiblock (any cell value, it is never read), or the assembly's
    # controller cell. The other cells drop nothing themselves; breaking one hands out the controller's drop.
    terms = [{'condition': 'minecraft:block_state_property', 'block': 'bwr:rhr_heat_exchanger', 'properties': {'assembled': 'false'}},
             {'condition': 'minecraft:block_state_property', 'block': 'bwr:rhr_heat_exchanger',
              'properties': {'assembled': 'true', 'cell': str(controller)}}]
    m.put(RES / 'data/bwr/loot_table/blocks/rhr_heat_exchanger.json', {'type': 'minecraft:block', 'pools': [{
        'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:rhr_heat_exchanger'}],
        'conditions': [{'condition': 'minecraft:any_of', 'terms': terms}, {'condition': 'minecraft:survives_explosion'}]}]})
    thin = sorted((c['triangles'], c['index']) for c in cells if c['triangles'])[:5]
    print(f'rhr_heat_exchanger assembly: {w}x{h}x{d}, {len(faces)} triangles, {len(occupied)} of {count} cells drawn, '
          f'controller cell {controller}; thinnest cells (triangles, index) {thin}; UVs and surface area preserved')


bake()
for p, data in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.is_file() and p.read_bytes() == data, 'Stale exchanger assembly asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
print(len(m.OUTPUTS), 'exchanger assembly assets verified' if '--check' in sys.argv else 'exchanger assembly assets exported')
