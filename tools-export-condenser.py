"""Export the enclosed Blender condenser, sparse collision cells and bypass valve.

Large condenser geometry is drawn once by its controller, not copied into each
occupied block. Ports and collision cells are physical, persistent world blocks.
"""
import importlib.util,json,math,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/condenser_ports'
s=m.read('arabelle_condenser.json');w,h,d=s['size'];ctrl=s['controller']
folder=m.RES/'assets/bwr/models/block/condenser'
mats=m.materials(s,folder)
m.put(folder/'body.obj',m.obj(s['faces'],mats));m.put(folder/'body.json',m.model('bwr:models/block/condenser/body.obj'))
b=m.bounds([v for f in s['faces'] for v in f['vertices']])
assert all(b[a]>=-1e-6 and b[a+3]<=s['size'][a]+1e-6 for a in range(3)),b
# Makeup nozzles are authored at final scale, not anisotropically stretched.
for p in (p for p in s['ports'] if p['role']=='MAKEUP'):
    cy,cz=p['cell'][1]+.5,p['cell'][2]+.5
    neck=[v for f in s['faces'] if f['part'].startswith('hotwell makeup '+str(cz)+' neck') for v in f['vertices']]
    assert neck,('missing makeup neck',p)
    assert all(min(abs(math.hypot(v[1]-cy,v[2]-cz)-r) for r in (.205,.25))<1e-5 for v in neck),('oval makeup pipe',p)
scale=.9/max(b[a+3]-b[a] for a in range(3))
compact=[dict(f,vertices=[[(p[a]-(b[a]+b[a+3])/2)*scale+.5 for a in range(3)]+p[3:] for p in f['vertices']]) for f in s['faces']]
m.put(folder/'compact.obj',m.obj(compact,mats));m.put(folder/'compact.json',m.model('bwr:models/block/condenser/compact.obj'))
def export_layout(s,path):
    w,h,d=s['size'];ctrl=s['controller']
    cells={}
    for box in s['boxes']:
        low=[max(0,math.floor(box[a]+1e-7)) for a in range(3)]
        high=[min(s['size'][a]-1,math.ceil(box[a+3]-1e-7)-1) for a in range(3)]
        for x in range(low[0],high[0]+1):
            for y in range(low[1],high[1]+1):
                for z in range(low[2],high[2]+1):
                    pos=(x,y,z);clip=[max(0,box[a]-pos[a]) for a in range(3)]+[min(1,box[a+3]-pos[a]) for a in range(3)]
                    if any(clip[a+3]-clip[a]<1e-7 for a in range(3)):continue
                    idx=x+w*(z+d*y)
                    if idx in cells:
                        old=cells[idx]['bounds'];clip=[min(old[a],clip[a]) for a in range(3)]+[max(old[a+3],clip[a+3]) for a in range(3)]
                    cells[idx]={'index':idx,'cell':list(pos),'bounds':clip,'role':'NONE'}
    for p in s['ports']:
        x,y,z=p['cell'];idx=x+w*(z+d*y)
        assert idx in cells,('port is not modeled',p)
        assert cells[idx]['role']=='NONE',('multiple roles in one cell',p)
        cells[idx]['role']=p['role']
        # All sockets meet the face centre, making the selected face reachable.
        face={'down':(1,0),'up':(1,1),'north':(2,0),'south':(2,1),'west':(0,0),'east':(0,1)}[p['face']]
        assert abs(cells[idx]['bounds'][face[0]+(3 if face[1] else 0)]-face[1])<1e-6,p
    root_idx=ctrl[0]+w*(ctrl[2]+d*ctrl[1]);assert root_idx in cells
    m.put(path,{'size':s['size'],'controller':ctrl,'ports':s['ports'],'cells':[cells[i] for i in sorted(cells)]})
    return cells
cells=export_layout(s,m.RES/'data/bwr/condenser/layout.json')
occupied={tuple(c['cell']) for c in cells.values()}
for p in (p for p in s['ports'] if p['role']=='MAKEUP'):
    x,y,z=p['cell'];assert (x-1,y,z) not in occupied,('blocked makeup socket',p)
# Preserve the alpha.3 facing and footprint in existing saved structures.
wide_v3=m.read('arabelle_condenser_wide_v3.json')
v3_folder=m.RES/'assets/bwr/models/block/condenser/wide_v3'
v3_mats=m.materials(wide_v3,v3_folder)
m.put(v3_folder/'body.obj',m.obj(wide_v3['faces'],v3_mats))
m.put(v3_folder/'body.json',m.model('bwr:models/block/condenser/wide_v3/body.obj'))
export_layout(wide_v3,m.RES/'data/bwr/condenser/layout_wide_v3.json')
# Version 2 occupied seven columns. Retain its mesh and cell indices in saved worlds.
compact_v2=m.read('arabelle_condenser_compact_v2.json')
v2_folder=m.RES/'assets/bwr/models/block/condenser/compact_v2'
v2_mats=m.materials(compact_v2,v2_folder)
m.put(v2_folder/'body.obj',m.obj(compact_v2['faces'],v2_mats))
m.put(v2_folder/'body.json',m.model('bwr:models/block/condenser/compact_v2/body.obj'))
export_layout(compact_v2,m.RES/'data/bwr/condenser/layout_compact_v2.json')
# Keep old placed structures addressable until the player breaks and replaces them.
legacy=m.read('arabelle_condenser_legacy.json')
legacy_folder=m.RES/'assets/bwr/models/block/condenser/legacy'
legacy_mats=m.materials(legacy,legacy_folder)
m.put(legacy_folder/'body.obj',m.obj(legacy['faces'],legacy_mats))
m.put(legacy_folder/'body.json',m.model('bwr:models/block/condenser/legacy/body.obj'))
export_layout(legacy,m.RES/'data/bwr/condenser/layout_legacy.json')
empty={'textures':{'particle':'minecraft:block/iron_block'},'elements':[]}
m.put(m.RES/'assets/bwr/models/block/arabelle_condenser.json',empty)
m.put(m.RES/'assets/bwr/blockstates/arabelle_condenser.json',{'variants':{'':{'model':'bwr:block/arabelle_condenser'}}})
m.put(m.RES/'assets/bwr/models/item/arabelle_condenser.json',{'parent':'bwr:block/condenser/compact','display':{'gui':{'rotation':[20,225,0],'scale':[1.0,1.0,1.0]}}})
m.put(m.RES/'data/bwr/loot_table/blocks/arabelle_condenser.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:arabelle_condenser'}],
    'conditions':[{'condition':'minecraft:block_state_property','block':'bwr:arabelle_condenser','properties':{'controller':'true'}},{'condition':'minecraft:survives_explosion'}]}]})
m.put(m.RES/'data/bwr/recipe/arabelle_condenser.json',{'type':'minecraft:crafting_shaped','pattern':['IPI','TCT','III'],'key':{'I':{'item':'minecraft:iron_block'},'P':{'item':'bwr:pressurised_tube'},'T':{'item':'bwr:high_pressure_water_pipe'},'C':{'item':'bwr:condensate_storage_tank'}},'result':{'id':'bwr:arabelle_condenser','count':1}})
v=m.read('bypass_steam_valve.json');folder=m.RES/'assets/bwr/models/block/steam_valves/bypass_steam_valve';mats=m.materials(v,folder)
vb=m.bounds([p for f in v['faces'] for p in f['vertices']]);assert all(vb[a]>=-1e-6 and vb[a+3]<=1.000001 for a in range(3)),vb
m.put(folder/'body.obj',m.obj(v['faces'],mats));m.put(folder/'body.json',m.model('bwr:models/block/steam_valves/bypass_steam_valve/body.obj'))
m.put(m.RES/'assets/bwr/blockstates/bypass_steam_valve.json',{'variants':{f'facing={d}':{'model':'bwr:block/steam_valves/bypass_steam_valve/body','y':a} for d,a in zip(('north','east','south','west'),(0,90,180,270))}})
m.put(m.RES/'assets/bwr/models/item/bypass_steam_valve.json',{'parent':'bwr:block/steam_valves/bypass_steam_valve/body','display':{'gui':{'rotation':[25,225,0],'scale':[.9,.9,.9]}}})
m.put(m.RES/'data/bwr/loot_table/blocks/bypass_steam_valve.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:bypass_steam_valve'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
m.put(m.RES/'data/bwr/recipe/bypass_steam_valve.json',{'type':'minecraft:crafting_shaped','pattern':[' C ','IPI',' I '],'key':{'I':{'item':'minecraft:iron_ingot'},'C':{'item':'minecraft:comparator'},'P':{'item':'bwr:pressurised_tube'}},'result':{'id':'bwr:bypass_steam_valve','count':1}})
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale condenser asset: '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(f'{len(m.OUTPUTS)} assets; {len(cells)} occupied condenser cells, {len(s["ports"])} physical ports; verified' if '--check' in sys.argv else f'{len(m.OUTPUTS)} assets exported; {len(cells)} occupied cells, {len(s["ports"])} ports')
