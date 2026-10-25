"""Export the Blender-authored condensate tank parts; --check verifies byte-for-byte.

The parts are the remodel's (art/models/condensate_tank/build_cst.py -> parts/*.json, textured from the atlas made by
make_cst_atlas.py). The alpha five-part sources (base, wall, roof, port, ladder .json beside build_models.py) are kept only for
the before/after review renders; their part names are reused by the new parts.
"""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/condensate_tank/parts'
NAMES=tuple(sorted(p.stem for p in m.SOURCE.glob('*.json')))
for name in NAMES:
    s=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/condensate_tank'/name
    mats=m.materials(s,folder);m.put(folder/'body.obj',m.obj(s['faces'],mats))
    model=m.model(f'bwr:models/block/condensate_tank/{name}/body.obj')
    # Drawn by the cached block-entity renderer, which already applies directional lighting: do not bake it twice.
    model['shade_quads']=False
    m.put(folder/'body.json',model)
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale tank asset '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'tank assets verified' if '--check' in sys.argv else 'tank assets exported')
