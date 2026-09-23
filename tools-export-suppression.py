"""Export Blender-authored concrete basin panels and RHR hardware; --check is read-only."""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/suppression'
for name in ('wall','rim','suction','return','heat_exchanger','water_surface','spray_rail','spray_riser'):
    s=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/suppression'/name
    mats=m.materials(s,folder);m.put(folder/'body.obj',m.obj(s['faces'],mats))
    model=m.model(f'bwr:models/block/suppression/{name}/body.obj')
    model['textures']['particle']='bwr:block/suppression_pool_wall'
    model['automatic_culling']=name in ('wall','rim','suction','return')
    m.put(folder/'body.json',model)
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale suppression asset: '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'suppression assets verified' if '--check' in sys.argv else 'suppression assets exported')
