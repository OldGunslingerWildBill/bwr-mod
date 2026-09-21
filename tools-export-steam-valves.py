"""Export the Blender valve meshes; --check verifies reproducibility without writes."""
import importlib.util,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/steam_valves'
for id in ('steam_stop_valve','turbine_control_valve'):
    s=m.read(id+'.json');folder=m.RES/f'assets/bwr/models/block/steam_valves/{id}'
    points=[p for f in s['faces'] for p in f['vertices']];b=m.bounds(points)
    assert all(b[a]>=-1e-6 and b[a+3]<=1.000001 for a in range(3)),(id,b)
    mats=m.materials(s,folder);m.put(folder/'body.obj',m.obj(s['faces'],mats))
    m.put(folder/'body.json',m.model(f'bwr:models/block/steam_valves/{id}/body.obj'))
    m.put(m.RES/f'assets/bwr/blockstates/{id}.json',{'variants':{f'facing={d}':{'model':f'bwr:block/steam_valves/{id}/body','y':a} for d,a in zip(('north','east','south','west'),(0,90,180,270))}})
    m.put(m.RES/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/steam_valves/{id}/body','display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
    m.put(m.RES/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:'+id}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    m.put(m.RES/f'data/bwr/recipe/{id}.json',{'type':'minecraft:crafting_shaped','pattern':[' R ','IPI',' I '],'key':{'R':{'item':'minecraft:redstone' if id=='steam_stop_valve' else 'minecraft:comparator'},'I':{'item':'minecraft:iron_ingot'},'P':{'item':'bwr:pressurised_tube'}},'result':{'id':'bwr:'+id,'count':1}})
for p,content in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.exists() and p.read_bytes()==content,'Stale valve asset: '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(content)
print(len(m.OUTPUTS),'valve assets verified' if '--check' in sys.argv else 'valve assets exported')
