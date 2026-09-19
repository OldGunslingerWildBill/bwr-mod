"""Bake current and legacy Blender DVSS meshes into NeoForge OBJ cells. --check is read-only."""
import importlib.util,sys,json,math
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('turbine_mesh',ROOT/'tools-import-turbines.py')
mesh=importlib.util.module_from_spec(spec);sys.modules[spec.name]=mesh;spec.loader.exec_module(mesh)
CLIP_EPS=mesh.EPS
outputs={};resources=ROOT/'mod/src/main/resources'
def put(path,data):outputs[path]=json.dumps(data,indent=2)+'\n' if isinstance(data,dict) else data

def bake(filename,id):
    source=json.loads((ROOT/'art/models/dvss'/filename).read_text())
    faces=[]
    for f in source['faces']:
        pts=[tuple(p) for p in f['points']];n=mesh.normal(pts);length=math.sqrt(mesh.dot(n,n))
        if length<1e-12:raise ValueError('Degenerate source triangle')
        faces.append(mesh.Face(pts,tuple(v/length for v in n),tuple(f['color']),f['part']))
    size=source['size'];mesh.EPS=CLIP_EPS
    cells,area=mesh.clip_cells(faces,source['boxes'],size)
    mesh.EPS=1e-7  # obj_text rounds curved-surface normals to nine places.
    mats={c:'dvss_'+str(i) for i,c in enumerate(sorted(set(f.color for f in faces)))}
    folder=resources/f'assets/bwr/models/block/pumps/{id}'
    mtl=[]
    for color,name in mats.items():
        mtl += ['newmtl '+name,'Kd '+' '.join(str(c) for c in color),'map_Kd minecraft:block/white_concrete','']
    put(folder/'materials.mtl','\n'.join(mtl))
    for c in cells:
        text=mesh.obj_text(c['faces'],mats,'materials.mtl')
        mesh.verify_obj(text,mats,True);put(folder/f"cell_{c['index']}.obj",text)
        put(folder/f"cell_{c['index']}.json",mesh.obj_model(f"bwr:models/block/pumps/{id}/cell_{c['index']}.obj"))
    box=mesh.bounds(p for f in faces for p in f.points);center=mesh.center(box)
    scale=.9/max(box[a+3]-box[a] for a in range(3))
    compact=[mesh.Face([tuple((p[a]-center[a])*scale+.5 for a in range(3)) for p in f.points],f.normal,f.color,f.part) for f in faces]
    text=mesh.obj_text(compact,mats,'materials.mtl');mesh.verify_obj(text,mats,True)
    put(folder/'compact.obj',text);put(folder/'compact.json',mesh.obj_model(f'bwr:models/block/pumps/{id}/compact.obj'))
    ctrl=source['controller'];idx=ctrl[0]+size[0]*(ctrl[2]+size[2]*ctrl[1])
    put(resources/f'data/bwr/pump_models/{id}.json',{'width':size[0],'height':size[1],'depth':size[2],
        'controller_cell':ctrl,'game_ports':source['ports'],'cells':[{k:v for k,v in c.items() if k!='faces'} for c in cells]})
    print(f'{id}: {len(faces)} triangles, {len(cells)} cells; surface area conserved ({area:.3f}).')
    return len(cells),idx

current=bake('dvss_mesh.json','recirculation_pump')
legacy=bake('dvss_legacy_mesh.json','recirculation_pump_legacy')
put(resources/'assets/bwr/models/item/recirculation_pump.json',{'parent':'bwr:block/pumps/recirculation_pump/compact',
    'display':{'gui':{'rotation':[20,225,0],'scale':[.9,.9,.9]},'ground':{'scale':[.3,.3,.3]},'fixed':{'scale':[.7,.7,.7]}}})
put(resources/'assets/bwr/models/block/recirculation_pump.json',{'parent':'bwr:block/pumps/recirculation_pump/compact'})
variants={}
for enlarged,(count,controller) in ((False,legacy),(True,current)):
    id='recirculation_pump' if enlarged else 'recirculation_pump_legacy'
    for facing,rotation in zip(('north','east','south','west'),(0,90,180,270)):
        for i in range(256):
            for full in (False,True):
                model=f'pumps/{id}/cell_{i}' if full and i<count else 'pumps/empty' if full else 'pumps/recirculation_pump/compact'
                variants[f'assembled={str(full).lower()},cell={i},enlarged={str(enlarged).lower()},facing={facing}']={'model':'bwr:block/'+model,'y':rotation}
put(resources/'assets/bwr/blockstates/recirculation_pump.json',{'variants':variants})
terms=[{'condition':'minecraft:block_state_property','block':'bwr:recirculation_pump',
        'properties':{'cell':str(root),'enlarged':str(large).lower()}} for large,(_,root) in ((False,legacy),(True,current))]
put(resources/'data/bwr/loot_table/blocks/recirculation_pump.json',{'type':'minecraft:block','pools':[{'rolls':1,
    'entries':[{'type':'minecraft:item','name':'bwr:recirculation_pump'}],
    'conditions':[{'condition':'minecraft:any_of','terms':terms},{'condition':'minecraft:survives_explosion'}]}]})
for p,text in outputs.items():
    if '--check' in sys.argv:
        if not p.exists() or p.read_text(encoding='utf-8')!=text:raise ValueError('Asset differs: '+str(p))
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
print(f'DVSS: {len(outputs)} generated assets verified.' if '--check' in sys.argv else f'DVSS: wrote {len(outputs)} assets.')
