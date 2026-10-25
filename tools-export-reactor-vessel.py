"""Export Blender-authored RPV pieces; --check verifies deterministic assets.

The vessel itself (shell, heads, closure, nozzles, internals, the failed head) is the v2 model: unit parts from
art/models/reactor_vessel_v2/build_parts.py. The port end pieces and the controller panel are the alpha.21 RVU parts.
"""
import importlib.util, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
RVU=('spool','steam_nozzle','water_nozzle','controller_panel')
V2=tuple(sorted(p.stem for p in (ROOT/'art/models/reactor_vessel_v2/parts').glob('*.json')))
for folder_name,names in (('art/models/reactor_vessel',RVU),('art/models/reactor_vessel_v2/parts',V2)):
    m.SOURCE=ROOT/folder_name
    for name in names:
        source=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/reactor_vessel'/name
        mats=m.materials(source,folder)
        m.put(folder/'body.obj',m.obj(source['faces'],mats))
        model=m.model(f'bwr:models/block/reactor_vessel/{name}/body.obj')
        # Entity rendering already applies directional lighting; do not bake it twice.
        model['shade_quads']=False
        m.put(folder/'body.json',model)
# The unformed recirculation port blocks (art/models/reactor_vessel_v2/build_recirc_nozzles.py): the shell cube with its nozzle.
m.SOURCE=ROOT/'art/models/reactor_vessel_v2/port_blocks'
for name in ('recirculation_inlet','recirculation_outlet'):
    source=m.read(name+'.json');folder=m.RES/'assets/bwr/models/block/recirculation_port'/name.split('_')[1]
    mats=m.materials(source,folder)
    m.put(folder/'body.obj',m.obj(source['faces'],mats))
    model={'parent':'minecraft:block/block',**m.model(f'bwr:models/block/recirculation_port/{name.split("_")[1]}/body.obj')}
    model['textures']={'particle':'bwr:block/reactor_vessel'}
    m.put(m.RES/'assets/bwr/models/block'/(name+'.json'),model)
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert path.is_file() and path.read_bytes()==data,'Stale RPV asset '+str(path)
    else:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(len(m.OUTPUTS),'RPV assets verified' if '--check' in sys.argv else 'RPV assets exported')
