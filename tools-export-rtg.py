"""Export the Blender-authored radioisotope thermoelectric generator (RTG); --check verifies without writes.

Blender side:  blender --background --python art/models/rtg/build_models.py
This tool turns the exported mesh into the OBJ model, blockstate, item model and loot table. The recipe is hand-written.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/rtg'


def block(id):
    s = m.read(id + '.json')
    folder = m.RES / f'assets/bwr/models/block/rtg/{id}'
    points = [p for f in s['faces'] for p in f['vertices']]
    b = m.bounds(points)
    assert all(b[a] >= -1e-6 and b[a + 3] <= 1.000001 for a in range(3)), (id, b)
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/rtg/{id}/body.obj'))
    model = f'bwr:block/rtg/{id}/body'
    # Rotationally symmetric: a single variant, no rotation.
    m.put(m.RES / f'assets/bwr/blockstates/{id}.json', {'variants': {'': {'model': model}}})
    m.put(m.RES / f'assets/bwr/models/item/{id}.json',
          {'parent': model, 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
    m.put(m.RES / f'data/bwr/loot_table/blocks/{id}.json',
          {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:' + id}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})


block('rtg')

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale RTG asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'RTG assets verified' if '--check' in sys.argv else 'RTG assets exported')
