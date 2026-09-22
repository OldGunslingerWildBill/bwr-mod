"""Export Blender-authored scalable tank components; --check verifies byte-for-byte."""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/condensate_tank'
for name in ('base','wall','roof','port','ladder'):
    s=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/condensate_tank'/name
    mats=m.materials(s,folder);m.put(folder/'body.obj',m.obj(s['faces'],mats));m.put(folder/'body.json',m.model(f'bwr:models/block/condensate_tank/{name}/body.obj'))
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale tank asset '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'tank assets verified' if '--check' in sys.argv else 'tank assets exported')
