"""Export the Blender Lube Oil Valve meshes (open and closed); --check verifies without writes.

Blender side:  blender --background --factory-startup --python art/models/lube_oil_valve/build_models.py
This tool turns the two mesh snapshots into the OBJ models (block/lube_oil_valve/open and /closed, which share one
materials.mtl), the blockstate (facing x open), the item model and the loot table. Recipes, lang and Java are not here.
Standard library only (it loads tools-export-modern.py for the shared OBJ/MTL writers).
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/lube_oil_valve'

ID = 'lube_oil_valve'
FOLDER = m.RES / f'assets/bwr/models/block/{ID}'
STATES = ('open', 'closed')

snapshots = {state: m.read(f'{ID}_{state}.json') for state in STATES}
# One material table for both states, so one materials.mtl serves both OBJ files (the unused ones are simply not referenced).
assert snapshots['open']['materials'] == snapshots['closed']['materials'], 'the two states must share their material table'
mats = m.materials(snapshots['open'], FOLDER)
for state, s in snapshots.items():
    points = [p for f in s['faces'] for p in f['vertices']]
    b = m.bounds(points)
    assert all(b[a] >= -1e-6 and b[a + 3] <= 1.000001 for a in range(3)), (state, b)
    m.put(FOLDER / f'{state}.obj', m.obj(s['faces'], mats))
    model = m.model(f'bwr:models/block/{ID}/{state}.obj')
    model['textures'] = {'particle': 'bwr:block/pipes/flange_steel'}
    m.put(FOLDER / f'{state}.json', model)

HORIZONTAL = tuple(zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270)))
variants = {}
for d, y in HORIZONTAL:
    for is_open in (True, False):
        v = {'model': f'bwr:block/{ID}/{"open" if is_open else "closed"}'}
        if y:
            v['y'] = y
        variants[f'facing={d},open={str(is_open).lower()}'] = v
m.put(m.RES / f'assets/bwr/blockstates/{ID}.json', {'variants': variants})
m.put(m.RES / f'assets/bwr/models/item/{ID}.json',
      {'parent': f'bwr:block/{ID}/open', 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
m.put(m.RES / f'data/bwr/loot_table/blocks/{ID}.json',
      {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:' + ID}],
                                              'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale lube oil valve asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'lube oil valve assets verified' if '--check' in sys.argv else 'lube oil valve assets exported')
