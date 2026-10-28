"""Export the Blender turbine auxiliary meshes (main oil tank, generator cooling skid, shaft oil pump, oil filter housing); --check verifies without writes.

Blender side:  blender --background --python art/models/turbine_aux/build_models.py
This tool turns the exported meshes into the OBJ models, blockstates, item models, loot tables and recipes.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/turbine_aux'

HORIZONTAL_VARIANTS = {f'facing={d}': {'model': None, 'y': a} for d, a in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270))}


def block(id, variants, recipe):
    s = m.read(id + '.json')
    folder = m.RES / f'assets/bwr/models/block/turbine_aux/{id}'
    points = [p for f in s['faces'] for p in f['vertices']]
    b = m.bounds(points)
    assert all(b[a] >= -1e-6 and b[a + 3] <= 1.000001 for a in range(3)), (id, b)
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/turbine_aux/{id}/body.obj'))
    model = f'bwr:block/turbine_aux/{id}/body'
    m.put(m.RES / f'assets/bwr/blockstates/{id}.json', {'variants': {k: dict(v, model=model) if v else {'model': model} for k, v in variants.items()}})
    m.put(m.RES / f'assets/bwr/models/item/{id}.json',
          {'parent': model, 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
    m.put(m.RES / f'data/bwr/loot_table/blocks/{id}.json',
          {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:' + id}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    m.put(m.RES / f'data/bwr/recipe/{id}.json', dict(recipe, result={'id': 'bwr:' + id, 'count': 1}))



def shaped(pattern, key):
    return {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pattern,
            'key': {k: {'item': v} for k, v in key.items()}}


IRON, COPPER = 'minecraft:iron_ingot', 'minecraft:copper_ingot'
block('main_oil_tank', dict(HORIZONTAL_VARIANTS),
      shaped(['ITI', 'PBP', 'III'], {'I': IRON, 'T': 'minecraft:cauldron', 'P': 'bwr:high_pressure_water_pipe', 'B': 'minecraft:bucket'}))
block('generator_cooling_skid', dict(HORIZONTAL_VARIANTS),
      shaped(['ICI', 'PTP', 'III'], {'I': IRON, 'C': COPPER, 'P': 'bwr:high_pressure_water_pipe', 'T': 'minecraft:cauldron'}))
block('oil_filter_housing', dict(HORIZONTAL_VARIANTS),
      shaped(['ILI', 'BPB', 'III'], {'I': IRON, 'L': 'minecraft:lever', 'B': 'minecraft:bucket', 'P': 'bwr:high_pressure_water_pipe'}))
block('shaft_oil_pump', dict(HORIZONTAL_VARIANTS),
      shaped(['ICI', 'RPR', 'III'], {'I': IRON, 'C': COPPER, 'R': 'minecraft:redstone', 'P': 'minecraft:piston'}))

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale turbine aux asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'turbine aux assets verified' if '--check' in sys.argv else 'turbine aux assets exported')
