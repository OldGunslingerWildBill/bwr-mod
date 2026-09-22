"""Deterministically export Blender cooling plant meshes and sparse assembly layouts."""
import importlib.util,json,math,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/cooling'
IDS=('natural_draft_tower','mechanical_draft_tower','circulating_water_pump','makeup_water_pump','screened_water_intake')
for id in IDS:
    s=m.read(id+'.json');w,h,d=s['size'];folder=m.RES/'assets/bwr/models/block/cooling'/id
    mats=m.materials(s,folder);m.put(folder/'body.obj',m.obj(s['faces'],mats));m.put(folder/'body.json',m.model(f'bwr:models/block/cooling/{id}/body.obj'))
    b=m.bounds([p for f in s['faces'] for p in f['vertices']]);assert all(b[a]>=-1e-6 and b[a+3]<=s['size'][a]+1e-6 for a in range(3)),(id,b)
    scale=.9/max(b[a+3]-b[a] for a in range(3))
    compact=[dict(f,vertices=[[(p[a]-(b[a]+b[a+3])/2)*scale+.5 for a in range(3)]+p[3:] for p in f['vertices']]) for f in s['faces']]
    m.put(folder/'compact.obj',m.obj(compact,mats));m.put(folder/'compact.json',m.model(f'bwr:models/block/cooling/{id}/compact.obj'))
    cells={}
    for box in s['boxes']:
        lo=[max(0,math.floor(box[a]+1e-7)) for a in range(3)];hi=[min(s['size'][a]-1,math.ceil(box[a+3]-1e-7)-1) for a in range(3)]
        for x in range(lo[0],hi[0]+1):
            for y in range(lo[1],hi[1]+1):
                for z in range(lo[2],hi[2]+1):
                    p=(x,y,z);bounds=[max(0,box[a]-p[a]) for a in range(3)]+[min(1,box[a+3]-p[a]) for a in range(3)]
                    if any(bounds[a+3]-bounds[a]<1e-7 for a in range(3)):continue
                    i=x+w*(z+d*y)
                    if i in cells:
                        old=cells[i]['bounds'];bounds=[min(old[a],bounds[a]) for a in range(3)]+[max(old[a+3],bounds[a+3]) for a in range(3)]
                    cells[i]={'index':i,'cell':list(p),'bounds':bounds,'role':'NONE','face':'north'}
    for p in s['ports']:
        x,y,z=p['cell'];i=x+w*(z+d*y);assert i in cells and cells[i]['role']=='NONE',(id,p)
        cells[i].update(role=p['role'],face=p['face'])
        axis,edge={'north':(2,0),'south':(2,1),'east':(0,1),'west':(0,0)}[p['face']]
        assert abs(cells[i]['bounds'][axis+3*edge]-edge)<1e-6,(id,p)
    ctrl=s['controller'];assert ctrl[0]+w*(ctrl[2]+d*ctrl[1]) in cells,id
    m.put(m.RES/f'data/bwr/cooling/{id}.json',dict(size=s['size'],controller=ctrl,ports=s['ports'],rotors=s.get('rotors',[]),cells=[cells[i] for i in sorted(cells)]))
    m.put(m.RES/f'assets/bwr/models/block/{id}.json',{'textures':{'particle':'minecraft:block/iron_block'},'elements':[]})
    m.put(m.RES/f'assets/bwr/blockstates/{id}.json',{'variants':{'':{'model':f'bwr:block/{id}'}}})
    m.put(m.RES/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/cooling/{id}/compact','display':{'gui':{'rotation':[20,225,0],'scale':[1,1,1]}}})
    m.put(m.RES/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:'+id}],'conditions':[{'condition':'minecraft:block_state_property','block':'bwr:'+id,'properties':{'controller':'true'}},{'condition':'minecraft:survives_explosion'}]}]})
    m.put(m.RES/f'data/bwr/recipe/{id}.json',{'type':'minecraft:crafting_shaped','pattern':['IPI','ICI','III'],'key':{'I':{'item':'minecraft:iron_block' if 'tower' not in id else 'minecraft:smooth_stone'},'P':{'item':'bwr:high_pressure_water_pipe'},'C':{'item':'minecraft:comparator' if id!='screened_water_intake' else 'minecraft:iron_bars'}},'result':{'id':'bwr:'+id,'count':1}})
    print(id,len(s['faces']),'triangles',len(cells),'cells',len(s['ports']),'ports')
s=m.read('cooling_fan.json');folder=m.RES/'assets/bwr/models/block/cooling/fan';mats=m.materials(s,folder)
m.put(folder/'body.obj',m.obj(s['faces'],mats));m.put(folder/'body.json',m.model('bwr:models/block/cooling/fan/body.obj'))
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale cooling asset: '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'cooling assets verified' if '--check' in sys.argv else 'cooling assets exported')
