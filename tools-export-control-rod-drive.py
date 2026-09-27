"""Export the Blender control rod drive into a static, chunk-baked model."""
import importlib.util,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/control_rod_drive'
source=m.read('control_rod_drive.json');folder=m.RES/'assets/bwr/models/block/control_rod_drive'
mats=m.materials(source,folder);m.put(folder/'body.obj',m.obj(source['faces'],mats))
m.put(m.RES/'assets/bwr/models/block/control_rod_drive.json',m.model('bwr:models/block/control_rod_drive/body.obj'))
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert path.is_file() and path.read_bytes()==data,'Stale CRD asset '+str(path)
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(len(m.OUTPUTS),'CRD assets verified' if '--check' in sys.argv else 'CRD assets exported')
