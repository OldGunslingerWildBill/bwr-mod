"""Export modular Blender pump parts; --check validates deterministic game assets."""
import importlib.util,sys,math
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/reactor_vessel/installed_jet'
for name in ('lower','straight','upper','brace'):
    source=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/installed_jet'/name
    points=[p for f in source['faces'] for p in f['vertices']]
    assert all(abs(p[0])<=.61 and abs(p[2])<=.48 for p in points),(name,m.bounds(points))
    low,high={'lower':(-.001,1.651),'straight':(-.001,1.001),'upper':(-.031,1.001),'brace':(-.06,.06)}[name]
    assert all(low<=p[1]<=high for p in points),(name,m.bounds(points))
    mats=m.materials(source,folder)
    m.put(folder/'body.obj',m.obj(source['faces'],mats))
    model=m.model(f'bwr:models/block/installed_jet/{name}/body.obj');model['shade_quads']=False
    m.put(folder/'body.json',model)
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert path.exists() and path.read_bytes()==data,'Stale installed jet asset: '+str(path)
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(len(m.OUTPUTS),'installed jet assets verified' if '--check' in sys.argv else 'installed jet assets exported')
