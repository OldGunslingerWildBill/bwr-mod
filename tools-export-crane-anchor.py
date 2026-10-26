"""Export the Blender-authored crane anchor (a lifting eye) as an OBJ block model; --check verifies byte-for-byte without writing.

Blender side:  blender --background --factory-startup --python art/models/crane_anchor/build_models.py
This tool turns eye.json into assets/bwr/models/block/crane_anchor/eye/body.{obj,json} and materials.mtl, and writes the block's
blockstate, the held item's model and the loot table. The anchor is an ordinary block drawn with that OBJ model.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/crane_anchor'

s = m.read('eye.json')
folder = m.RES / 'assets/bwr/models/block/crane_anchor/eye'
mats = m.materials(s, folder)
m.put(folder / 'body.obj', m.obj(s['faces'], mats))
body = m.model('bwr:models/block/crane_anchor/eye/body.obj')
body['render_type'] = 'minecraft:cutout'
body['textures'] = {'particle': 'minecraft:block/iron_block'}
m.put(folder / 'body.json', body)

m.put(m.RES / 'assets/bwr/blockstates/crane_anchor.json', {'variants': {'': {'model': 'bwr:block/crane_anchor/eye/body'}}})
m.put(m.RES / 'assets/bwr/models/item/crane_anchor.json', {
    'parent': 'bwr:block/crane_anchor/eye/body',
    'display': {'gui': {'rotation': [30, 225, 0], 'translation': [0, -2, 0], 'scale': [1.3, 1.3, 1.3]},
                'ground': {'scale': [.6, .6, .6]},
                'fixed': {'scale': [1.0, 1.0, 1.0]},
                'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [.5, .5, .5]},
                'firstperson_righthand': {'rotation': [0, 45, 0], 'scale': [.6, .6, .6]}}})
m.put(m.RES / 'data/bwr/loot_table/blocks/crane_anchor.json', {
    'type': 'minecraft:block',
    'pools': [{'bonus_rolls': 0.0, 'conditions': [{'condition': 'minecraft:survives_explosion'}],
               'entries': [{'type': 'minecraft:item', 'name': 'bwr:crane_anchor'}], 'rolls': 1.0}],
    'random_sequence': 'bwr:blocks/crane_anchor'})

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale crane anchor asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'crane anchor assets verified' if '--check' in sys.argv else 'crane anchor assets exported')
