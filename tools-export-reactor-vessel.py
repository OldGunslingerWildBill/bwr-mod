"""Export Blender-authored RPV pieces; --check verifies deterministic assets."""
import importlib.util, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/reactor_vessel'
for name in ('barrel','bottom','flange','head','weld','stud','spool','collar'):
    source=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/reactor_vessel'/name
    mats=m.materials(source,folder)
    m.put(folder/'body.obj',m.obj(source['faces'],mats))
    model=m.model(f'bwr:models/block/reactor_vessel/{name}/body.obj')
    # Entity rendering already applies directional lighting; do not bake it twice.
    model['shade_quads']=False
    m.put(folder/'body.json',model)
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert path.is_file() and path.read_bytes()==data,'Stale RPV asset '+str(path)
    else:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(len(m.OUTPUTS),'RPV assets verified' if '--check' in sys.argv else 'RPV assets exported')
