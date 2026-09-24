"""Bake Blender alpha hardware with reproducible port/collision manifests. --check is read-only."""
import importlib.util,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/alpha'
for id in ('slc_pump','slc_boron_tank'):
    source=m.bake_assembly_cells(id,'');w,h,d=source['size'];c=source['controller'];index=c[0]+w*(c[2]+d*c[1]);variants={}
    for face,angle in zip(('north','east','south','west'),(0,90,180,270)):
        for full in (False,True):
            for cell in range(256):
                model=f'pumps/{id}/cell_{cell}' if full and cell<w*h*d else 'pumps/empty' if full else f'pumps/{id}/compact'
                variants[f'assembled={str(full).lower()},cell={cell},facing={face}']={'model':'bwr:block/'+model,'y':angle}
    m.put(m.RES/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
    m.put(m.RES/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/pumps/{id}/compact','display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
    m.put(m.RES/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:'+id}],
      'conditions':[{'condition':'minecraft:block_state_property','block':'bwr:'+id,'properties':{'cell':str(index)}},{'condition':'minecraft:survives_explosion'}]}]})
for id in ('ads_controller','ads_relief_valve','water_discharge_port','borate_charge'):
    source=m.read(id+'.json');folder=m.RES/f'assets/bwr/models/block/alpha/{id}'
    mats=m.materials(source,folder);m.put(folder/'body.obj',m.obj(source['faces'],mats));m.put(folder/'body.json',m.model(f'bwr:models/block/alpha/{id}/body.obj'))
    variants={}
    for facing,angle in zip(('north','east','south','west'),(0,90,180,270)):
        if id=='ads_relief_valve':
            for opened in (False,True):variants[f'facing={facing},open={str(opened).lower()}']={'model':f'bwr:block/alpha/{id}/body','y':angle}
        else:variants['facing='+facing]={'model':f'bwr:block/alpha/{id}/body','y':angle}
    if id!='borate_charge':m.put(m.RES/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
    m.put(m.RES/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/alpha/{id}/body','display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
    if id!='borate_charge':m.put(m.RES/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:'+id}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
bad=[]
for path,data in m.OUTPUTS.items():
    if '--check' in sys.argv:
        if not path.exists() or path.read_bytes()!=data:bad.append(str(path.relative_to(ROOT)))
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print('Alpha model files:',len(m.OUTPUTS),'stale:',len(bad))
if bad:print('\n'.join(bad));sys.exit(1)
