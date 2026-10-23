"""Export the Blender water valve meshes (motor-operated water valve, cavity drain port); --check verifies without writes.

Blender side:  blender --background --python art/models/water_valves/build_models.py
This tool turns the exported meshes into the OBJ models, blockstates, item models, loot tables and recipes.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/water_valves'

HORIZONTAL = {f'facing={d}': {'model': None, 'y': a} for d, a in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270))}


def block(id, variants, recipe):
    s = m.read(id + '.json')
    folder = m.RES / f'assets/bwr/models/block/water_valves/{id}'
    points = [p for f in s['faces'] for p in f['vertices']]
    b = m.bounds(points)
    assert all(b[a] >= -1e-6 and b[a + 3] <= 1.000001 for a in range(3)), (id, b)
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/water_valves/{id}/body.obj'))
    model = f'bwr:block/water_valves/{id}/body'
    m.put(m.RES / f'assets/bwr/blockstates/{id}.json', {'variants': {k: dict(v, model=model) if v else {'model': model} for k, v in variants.items()}})
    m.put(m.RES / f'assets/bwr/models/item/{id}.json',
          {'parent': model, 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
    m.put(m.RES / f'data/bwr/loot_table/blocks/{id}.json',
          {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:' + id}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    m.put(m.RES / f'data/bwr/recipe/{id}.json', dict(recipe, result={'id': 'bwr:' + id, 'count': 1}))


block('motor_operated_water_valve', {k: dict(v) for k, v in HORIZONTAL.items()},
      {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': [' R ', 'IPI', 'CIC'],
       'key': {'R': {'item': 'minecraft:redstone'}, 'I': {'item': 'minecraft:iron_ingot'}, 'C': {'item': 'minecraft:copper_ingot'},
               'P': {'item': 'bwr:high_pressure_water_pipe'}}})
block('cavity_drain_port',
      {'facing=north': {}, 'facing=east': {'y': 90}, 'facing=south': {'y': 180}, 'facing=west': {'y': 270},
       'facing=up': {'x': 270}, 'facing=down': {'x': 90}},
      {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': ['III', 'SPS', 'III'],
       'key': {'I': {'item': 'minecraft:iron_ingot'}, 'S': {'item': 'minecraft:iron_bars'}, 'P': {'item': 'bwr:high_pressure_water_pipe'}}})

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale water valve asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'water valve assets verified' if '--check' in sys.argv else 'water valve assets exported')
