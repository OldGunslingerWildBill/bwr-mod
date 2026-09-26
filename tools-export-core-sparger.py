"""Bake Blender core-spray parts and construction segments. --check is read-only."""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/core_sparger'
for name in ('header','nozzle_lpcs','nozzle_hpcs','segment_lpcs','segment_hpcs'):
    source=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/core_sparger'/name
    mats=m.materials(source,folder);m.put(folder/'body.obj',m.obj(source['faces'],mats))
    model=m.model(f'bwr:models/block/core_sparger/{name}/body.obj')
    model['shade_quads']=name.startswith('segment_')
    m.put(folder/'body.json',model)
variants={}
for loop in ('lpcs','hpcs'):
    m.put(m.RES/f'assets/bwr/models/block/core_spray_sparger_{loop}.json',{'parent':f'bwr:block/core_sparger/segment_{loop}/body'})
    for facing,angle in zip(('north','east','south','west'),(0,90,180,270)):
        variants[f'facing={facing},loop={loop}']={'model':f'bwr:block/core_spray_sparger_{loop}','y':angle}
m.put(m.RES/'assets/bwr/blockstates/core_spray_sparger.json',{'variants':variants})
m.put(m.RES/'assets/bwr/models/item/core_spray_sparger.json',{'parent':'bwr:block/core_spray_sparger_lpcs','display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert path.is_file() and path.read_bytes()==data,'Stale sparger asset '+str(path)
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(len(m.OUTPUTS),'sparger assets verified' if '--check' in sys.argv else 'sparger assets exported')
