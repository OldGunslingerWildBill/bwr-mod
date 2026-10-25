"""Cell-split the Large RTG (art/models/rtg/rtg_large.json, 2 x 3 x 2) into block models; --check is read-only.

Blender side:  blender --background --python art/models/rtg/build_large.py
This tool reuses the clipping, OBJ and material writers of tools-export-modern.py (the pipeline the pump and heat exchanger
assemblies use) and writes:

  assets/bwr/models/block/rtg/large/cell_<i>.{obj,json}, materials.mtl, inventory.{obj,json}
  assets/bwr/blockstates/large_rtg.json     (one variant per cell and facing: key cell=<i>,facing=<f>)
  assets/bwr/models/item/large_rtg.json     (held item: the whole unit scaled into one block)
  data/bwr/loot_table/blocks/large_rtg.json (one item, from the controller cell, cell 0)

Cell index = x + 2 * (z + 2 * y), x east, z south, y up, in the north-facing frame; the controller is cell 0. The recipe, the
language file and the collision (a full cube per cell) are not made here.
"""
import importlib.util
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/rtg'
RES = m.RES
EPS = m.EPS
FOLDER = RES / 'assets/bwr/models/block/rtg/large'
LOCATION = 'bwr:models/block/rtg/large'


def bake():
    source = m.read('rtg_large.json')
    w, h, d = source['size']
    count = w * h * d
    mats = m.materials(source, FOLDER)
    faces = source['faces']
    after, occupied = 0.0, []
    for i in range(count):
        off = (i % w, i // (w * d), (i // w) % d)
        out = []
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
        if out:
            occupied.append(i)
            m.put(FOLDER / f'cell_{i}.obj', m.obj(out, mats))
            model = m.model(f'{LOCATION}/cell_{i}.obj')
            model['textures']['particle'] = 'minecraft:block/iron_block'
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
    inv['textures']['particle'] = 'minecraft:block/iron_block'
    m.put(FOLDER / 'inventory.json', inv)
    variants = {}
    for facing, angle in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270)):
        for i in range(count):
            key = f'cell={i},facing={facing}'
            if i in occupied:
                variants[key] = {'model': f'bwr:block/rtg/large/cell_{i}', 'y': angle}
            else:
                variants[key] = {'model': 'bwr:block/pumps/empty', 'y': angle}
    m.put(RES / 'assets/bwr/blockstates/large_rtg.json', {'variants': variants})
    m.put(RES / 'assets/bwr/models/item/large_rtg.json', {
        'parent': 'bwr:block/rtg/large/inventory',
        'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]},
                    'ground': {'scale': [.5, .5, .5]},
                    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [.375, .375, .375]},
                    'firstperson_righthand': {'rotation': [0, 45, 0], 'scale': [.4, .4, .4]}}})
    # One item, from the controller cell only: the other cells drop nothing themselves (breaking one hands out the controller's drop).
    m.put(RES / 'data/bwr/loot_table/blocks/large_rtg.json', {'type': 'minecraft:block', 'pools': [{
        'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:large_rtg'}],
        'conditions': [{'condition': 'minecraft:block_state_property', 'block': 'bwr:large_rtg', 'properties': {'cell': '0'}},
                       {'condition': 'minecraft:survives_explosion'}]}]})
    print(f'large_rtg assembly: {w}x{h}x{d}, {len(faces)} triangles, {len(occupied)} of {count} cells drawn: {occupied}; UVs and surface area preserved')


bake()
for p, data in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.is_file() and p.read_bytes() == data, 'Stale Large RTG asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
print(len(m.OUTPUTS), 'Large RTG assets verified' if '--check' in sys.argv else 'Large RTG assets exported')
