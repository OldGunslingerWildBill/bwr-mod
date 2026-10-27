"""Export Blender power modules using the shared area-preserving cell clipper.

Sources come from art/models/power_turbines/build_models.py (headless Blender). --check is read-only."""
import importlib.util
import json
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/power_turbines'
# PowerModuleBlock.HOOD_FROM_Y: LP cells at or above this height are the crane-lifted hood.
HOOD_FROM_Y=3
for id in ('hp_turbine','lp_turbine','nuclear_generator'):
    m.bake_assembly_cells(id, suffix='')
    data=json.loads((m.SOURCE/(id+'.json')).read_text());w,h,d=data['size'];c=data['controller'];ctrl=c[0]+w*(c[2]+d*c[1])
    variants={}
    for f,angle in zip(('north','east','south','west'),(0,90,180,270)):
        for full in (False,True):
            for i in range(256):
                model=f'pumps/{id}/cell_{i}' if full and i<w*h*d else 'pumps/empty' if full else f'pumps/{id}/compact'
                key=f'assembled={str(full).lower()},cell={i},facing={f}'
                if full and id=='lp_turbine' and i>=HOOD_FROM_Y*w*d:
                    # PowerModuleBlock.OPEN: with the hood off (crane-lifted) the hood cells draw nothing.
                    variants[key+',open=false']={'model':'bwr:block/'+model,'y':angle}
                    variants[key+',open=true']={'model':'bwr:block/pumps/empty','y':angle}
                else:
                    variants[key]={'model':'bwr:block/'+model,'y':angle}
    res=m.RES
    m.put(res/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
    m.put(res/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/pumps/{id}/compact','display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
    m.put(res/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:'+id}],
          'conditions':[{'condition':'minecraft:block_state_property','block':'bwr:'+id,'properties':{'cell':str(ctrl)}},{'condition':'minecraft:survives_explosion'}]}]})
    center={'hp_turbine':'piston','lp_turbine':'cauldron','nuclear_generator':'gold_block'}[id]
    m.put(res/f'data/bwr/recipe/{id}.json',{'type':'minecraft:crafting_shaped','pattern':['ISI','SCS','IRI'],'key':{'I':{'item':'minecraft:iron_block'},'S':{'item':'minecraft:iron_ingot'},'C':{'item':'minecraft:'+center},'R':{'item':'minecraft:redstone_block'}},'result':{'id':'bwr:'+id,'count':1}})
    print(id, data['size'], 'exported')
for path,content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert path.exists() and path.read_bytes()==content, 'Stale asset: '+str(path)
    else:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(content)
print(len(m.OUTPUTS),'assets verified' if '--check' in sys.argv else 'assets written')
