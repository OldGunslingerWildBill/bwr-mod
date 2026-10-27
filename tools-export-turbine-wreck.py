"""Export the wrecked-turbine parts (TURBINE-MAINTENANCE.md); --check verifies byte-for-byte.

Sources: art/models/power_turbines/build_wreck.py (headless Blender) -> art/models/power_turbines/wreck/*.json. Each part becomes
assets/bwr/models/block/turbine_wreck/<part>/body.{obj,json}, drawn by TurbineHallRenderers in the module's own coordinates.
"""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/power_turbines/wreck'
NAMES=tuple(sorted(p.stem for p in m.SOURCE.glob('*.json')))
for name in NAMES:
    s=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/turbine_wreck'/name
    mats=m.materials(s,folder);m.put(folder/'body.obj',m.obj(s['faces'],mats))
    model=m.model(f'bwr:models/block/turbine_wreck/{name}/body.obj')
    # Drawn by the cached block-entity renderer, which already applies directional lighting: do not bake it twice.
    model['shade_quads']=False
    m.put(folder/'body.json',model)
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale wreck asset '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'wreck assets verified' if '--check' in sys.argv else 'wreck assets exported')
