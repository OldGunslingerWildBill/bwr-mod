"""Export the Blender-authored building crane parts as OBJ models; --check verifies byte-for-byte without writing.

Blender side:  blender --background --python art/models/building_crane/build_models.py
This tool turns each part's mesh and UV data into assets/bwr/models/block/building_crane/<part>/body.{obj,json} and
materials.mtl. The renderer (BuildingCraneRenderer) draws them from MachineMeshCache.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/building_crane'

PARTS = ('end_carriage', 'girder_plain', 'girder_tile', 'cab', 'trolley', 'hook_block', 'rope', 'strongback_hook', 'spreader', 'sling',
         'stand_cradle', 'tensioner_tool')

for name in PARTS:
    s = m.read(name + '.json')
    folder = m.RES / f'assets/bwr/models/block/building_crane/{name}'
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/building_crane/{name}/body.obj'))

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale building crane asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'building crane assets verified' if '--check' in sys.argv else 'building crane assets exported')
