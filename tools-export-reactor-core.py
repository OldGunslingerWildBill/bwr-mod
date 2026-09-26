"""Export original Blender core pieces; --check verifies reproducibility."""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/reactor_core'
for name in ('channel','bundle_top','insert_top','guide_cell','support_cell','guide_tube','blade','shroud','shroud_rim'):
    source=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/reactor_core'/name
    mats=m.materials(source,folder);m.put(folder/'body.obj',m.obj(source['faces'],mats))
    model=m.model(f'bwr:models/block/reactor_core/{name}/body.obj');model['shade_quads']=False
    m.put(folder/'body.json',model)
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert path.is_file() and path.read_bytes()==data,'Stale core asset '+str(path)
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(len(m.OUTPUTS),'core assets verified' if '--check' in sys.argv else 'core assets exported')
