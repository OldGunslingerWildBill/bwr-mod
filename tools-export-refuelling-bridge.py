"""Export the Blender-authored refuelling bridge parts as OBJ models; --check verifies byte-for-byte without writing.

Blender side:  blender --background --python art/models/refuelling_bridge/build_models.py
This tool turns each part's mesh and UV data into assets/bwr/models/block/refuelling_bridge/<part>/body.{obj,json} and
materials.mtl. The renderer (RefuellingBridgeRenderer) draws them from MachineMeshCache.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/refuelling_bridge'

PARTS = ('end_truck', 'girder_plain', 'girder_tile', 'deck_clutter', 'plate', 'trolley', 'reel', 'mast_outer', 'mast_tube',
         'mast_collar', 'grapple_head', 'grapple_jaw', 'assembly')

for name in PARTS:
    s = m.read(name + '.json')
    folder = m.RES / f'assets/bwr/models/block/refuelling_bridge/{name}'
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/refuelling_bridge/{name}/body.obj'))

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale refuelling bridge asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'refuelling bridge assets verified' if '--check' in sys.argv else 'refuelling bridge assets exported')
