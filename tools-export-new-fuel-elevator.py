"""Export the Blender-authored new fuel elevator parts as OBJ models; --check verifies byte-for-byte without writing.

Blender side:  blender --background --python art/models/new_fuel_elevator/build_models.py
This tool turns each part's mesh and UV data into assets/bwr/models/block/new_fuel_elevator/<part>/body.{obj,json} and materials.mtl,
and writes the block's blockstate (an empty model: the renderer draws it), the held item's model and the loot table.
The renderer (NewFuelElevatorRenderer) draws the parts from MachineMeshCache.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/new_fuel_elevator'

PARTS = ('head', 'guide', 'carriage', 'cable')

for name in PARTS:
    s = m.read(name + '.json')
    folder = m.RES / f'assets/bwr/models/block/new_fuel_elevator/{name}'
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/new_fuel_elevator/{name}/body.obj'))

m.put(m.RES / 'assets/bwr/blockstates/new_fuel_elevator.json', {
    'variants': {f'facing={d}': {'model': 'bwr:block/pumps/empty'} for d in ('north', 'east', 'south', 'west')}})
m.put(m.RES / 'assets/bwr/models/item/new_fuel_elevator.json', {
    'parent': 'bwr:block/new_fuel_elevator/head/body',
    'display': {'gui': {'rotation': [25, 225, 0], 'translation': [0, -1, 0], 'scale': [.42, .42, .42]},
                'ground': {'scale': [.25, .25, .25]},
                'fixed': {'scale': [.4, .4, .4]},
                'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [.2, .2, .2]},
                'firstperson_righthand': {'rotation': [0, 45, 0], 'scale': [.22, .22, .22]}}})
m.put(m.RES / 'data/bwr/loot_table/blocks/new_fuel_elevator.json', {
    'type': 'minecraft:block',
    'pools': [{'bonus_rolls': 0.0, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
               'entries': [{'type': 'minecraft:item', 'name': 'bwr:new_fuel_elevator'}], 'rolls': 1.0}],
    'random_sequence': 'bwr:blocks/new_fuel_elevator'})

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale new fuel elevator asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'new fuel elevator assets verified' if '--check' in sys.argv else 'new fuel elevator assets exported')
