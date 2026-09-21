"""Bake Blender-authored pumps and pipe fittings, preserving UVs. --check is read-only."""
import json
import math
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent
SOURCE=ROOT/'art/models/modern'
RES=ROOT/'mod/src/main/resources'
IDS=('lpcs_pump','hpcs_pump','rhr_pump','motor_feed_pump','turbine_feed_pump')
DIRS=('down','up','north','south','west','east')
OUTPUTS={}
EPS=1e-7

def put(path,data):
    OUTPUTS[path]=(json.dumps(data,indent=2)+'\n').encode() if isinstance(data,dict) else data.encode() if isinstance(data,str) else data

def cross(a,b): return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def sub(a,b): return tuple(a[i]-b[i] for i in range(3))
def length(a): return math.sqrt(sum(x*x for x in a))
def normal(p):
    n=cross(sub(p[1],p[0]),sub(p[2],p[0])); size=length(n)
    return tuple(x/size for x in n) if size>1e-12 else (0,0,0)
def area(p): return sum(length(cross(sub(p[i],p[0]),sub(p[i+1],p[0])))/2 for i in range(1,len(p)-1))
def bounds(points):
    return [min(p[i] for p in points) for i in range(3)]+[max(p[i] for p in points) for i in range(3)] if points else []

def clip(poly,axis,plane,above):
    out=[]
    for a,b in zip(poly[-1:]+poly[:-1],poly):
        da,db=a[axis]-plane,b[axis]-plane
        inside_a=da>=-EPS if above else da<=EPS
        inside_b=db>=-EPS if above else db<=EPS
        if inside_a!=inside_b:
            t=da/(da-db)
            p=[a[k]+t*(b[k]-a[k]) for k in range(5)]
            p[axis]=plane
            out.append(p)
        if inside_b: out.append(b)
    result=[]
    for p in out:
        if not result or length(sub(p,result[-1]))>EPS: result.append(p)
    if len(result)>1 and length(sub(result[0],result[-1]))<=EPS: result.pop()
    return result

def read(name):
    source=json.loads((SOURCE/name).read_text(encoding='utf-8'))
    for face in source['faces']:
        for p in face['vertices']:
            for i in range(3):
                if abs(p[i]-round(p[i]))<1e-6: p[i]=round(p[i])
        face['normal']=normal(face['vertices'])
        face['bounds']=bounds(face['vertices'])
    return source

def obj(faces,mats):
    verts={};uvs={};normals={};commands=[];current=None
    def index(table,key):
        key=tuple(round(v,9) for v in key)
        if key not in table: table[key]=len(table)+1
        return table[key]
    for f in faces:
        if area(f['vertices'])<1e-12: continue
        mat=mats[f['material']]
        if mat!=current: commands.append('usemtl '+mat);current=mat
        ni=index(normals,f['normal'])
        commands.append('f '+' '.join(f'{index(verts,p[:3])}/{index(uvs,p[3:])}/{ni}' for p in f['vertices']))
    lines=['# Authored in Blender; exported by tools-export-modern.py','mtllib materials.mtl','s off']
    for prefix,table in (('v',verts),('vt',uvs),('vn',normals)):
        lines += [prefix+' '+' '.join(f'{v:.9f}' for v in key) for key in table]
    return '\n'.join(lines+commands)+'\n'

def materials(source,folder):
    mats={key:'mat_'+str(i) for i,key in enumerate(source['materials'])}
    lines=[]
    for key,m in source['materials'].items():
        lines += ['newmtl '+mats[key],'Kd '+' '.join(str(x) for x in m['color']),'map_Kd '+m['texture']]
        if m['tint']>=0: lines.append('neoforge_TintIndex '+str(m['tint']))
        lines.append('')
    put(folder/'materials.mtl','\n'.join(lines))
    return mats

def model(location):
    return {'loader':'neoforge:obj','model':location,'automatic_culling':False,'shade_quads':True,
            'flip_v':True,'emissive_ambient':False,'ambientocclusion':False,
            'textures':{'particle':'minecraft:block/iron_block'},'render_type':'minecraft:solid'}

def bake_assembly_cells(id, suffix='_modern'):
    """Shared Blender mesh clipping for pump and main-turbine assemblies."""
    source=read(id+'.json');w,h,d=source['size'];count=w*h*d
    assert count<=256 and w%2==1 and d%2==1
    folder=RES/f'assets/bwr/models/block/pumps/{id}{suffix}'
    mats=materials(source,folder);faces=source['faces'];cells=[];after=0
    for i in range(count):
        off=(i%w,i//(w*d),(i//w)%d);out=[];collision=[]
        for face in faces:
            b=face['bounds'];n=face['normal']
            if any(b[a+3]<off[a]-EPS or b[a]>off[a]+1+EPS for a in range(3)): continue
            if any(abs(b[a+3]-b[a])<EPS and abs(b[a]-round(b[a]))<EPS
                   and off[a]!=round(b[a])-(1 if n[a]>0 else 0) for a in range(3) if abs(n[a])>.5): continue
            p=face['vertices']
            for axis in range(3):
                p=clip(p,axis,off[axis],True) if p else []
                p=clip(p,axis,off[axis]+1,False) if p else []
            if len(p)<3 or area(p)<1e-12: continue
            p=[[max(0,min(1,v[a]-off[a])) for a in range(3)]+v[3:] for v in p]
            polygons=[p] if len(p)<=4 else [[p[0],p[j],p[j+1]] for j in range(1,len(p)-1)]
            for poly in polygons:
                after+=area(poly);out.append(dict(face,vertices=poly))
        for b in source['boxes']:
            low=[max(b[a],off[a])-off[a] for a in range(3)]
            high=[min(b[a+3],off[a]+1)-off[a] for a in range(3)]
            if all(high[a]>low[a]+EPS for a in range(3)): collision.extend((low,high))
        for f in out: collision.extend(v[:3] for v in f['vertices'])
        cells.append({'index':i,'offset':list(off),'bounds':bounds(collision)})
        put(folder/f'cell_{i}.obj',obj(out,mats))
        put(folder/f'cell_{i}.json',model(f'bwr:models/block/pumps/{id}{suffix}/cell_{i}.obj'))
    before=sum(area(f['vertices']) for f in faces)
    assert math.isclose(before,after,rel_tol=2e-6,abs_tol=2e-6),(id,before,after)
    all_points=[p for f in faces for p in f['vertices']];b=bounds(all_points)
    assert all(b[a]>=-EPS and b[a+3]<=source['size'][a]+EPS for a in range(3)),(id,b)
    scale=.9/max(b[a+3]-b[a] for a in range(3))
    compact=[dict(f,vertices=[[(p[a]-(b[a]+b[a+3])/2)*scale+.5 for a in range(3)]+p[3:] for p in f['vertices']]) for f in faces]
    put(folder/'compact.obj',obj(compact,mats));put(folder/'compact.json',model(f'bwr:models/block/pumps/{id}{suffix}/compact.obj'))
    put(RES/f'data/bwr/pump_models/{id}{suffix}.json',{'width':w,'height':h,'depth':d,'controller_cell':source['controller'],
                                                   'game_ports':source['ports'],'cells':cells})
    return source

def bake_pump(id):
    source=bake_assembly_cells(id);w,h,d=source['size'];count=w*h*d;faces=source['faces']
    legacy=json.loads((RES/f'data/bwr/pump_models/{id}.json').read_text())
    old_count=legacy['width']*legacy['height']*legacy['depth'];old_ctrl=legacy['controller_cell']
    old_index=old_ctrl[0]+legacy['width']*(old_ctrl[2]+legacy['depth']*old_ctrl[1])
    c=source['controller'];new_index=c[0]+w*(c[2]+d*c[1]);variants={}
    for modern,n in ((False,old_count),(True,count)):
        for facing,angle in zip(('north','east','south','west'),(0,90,180,270)):
            for full in (False,True):
                for i in range(256):
                    resource=f'pumps/{id}'+('_modern' if modern else '')+f'/cell_{i}' if full and i<n else 'pumps/empty' if full else f'pumps/{id}_modern/compact'
                    variants[f'assembled={str(full).lower()},cell={i},facing={facing},modern={str(modern).lower()}']={'model':'bwr:block/'+resource,'y':angle}
    put(RES/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
    put(RES/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/pumps/{id}_modern/compact','display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
    terms=[{'condition':'minecraft:block_state_property','block':'bwr:'+id,'properties':{'cell':str(index),'modern':str(modern).lower()}}
           for modern,index in ((False,old_index),(True,new_index))]
    put(RES/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:'+id}],
         'conditions':[{'condition':'minecraft:any_of','terms':terms},{'condition':'minecraft:survives_explosion'}]}]})
    print(f'{id}: {w}x{h}x{d}, {len(faces)} triangles, {count} cells; UVs and surface area preserved')

def bake_pipes():
    folder=RES/'assets/bwr/models/block/pipes/round'
    variants={}
    sources=[read('pipe_'+str(mask)+'.json') for mask in range(64)]
    shared={k:v for source in sources for k,v in source['materials'].items()}
    mats=materials({'materials':dict(sorted(shared.items()))},folder)
    for mask,source in enumerate(sources):
        b=bounds([p for f in source['faces'] for p in f['vertices']])
        assert all(b[a]>=-EPS and b[a+3]<=1+EPS for a in range(3)),(mask,b)
        # Every pattern uses the same named materials from one Blender run.
        put(folder/f'connection_{mask}.obj',obj(source['faces'],mats))
        put(folder/f'connection_{mask}.json',model(f'bwr:models/block/pipes/round/connection_{mask}.obj'))
        key=','.join(f'{name}={str(bool(mask & (1<<i))).lower()}' for i,name in enumerate(DIRS))
        variants[key]={'model':f'bwr:block/pipes/round/connection_{mask}'}
    for id in ('pressurised_tube','high_pressure_water_pipe'):
        put(RES/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
        put(RES/f'assets/bwr/models/item/{id}.json',{'parent':'bwr:block/pipes/round/connection_12',
            'display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]},'ground':{'scale':[.5,.5,.5]}}})
    print('Pipes: 64 Blender fittings shared by steam and water; painted bands use tint index 0')

def run():
    for id in IDS: bake_pump(id)
    bake_pipes()
    for p in (SOURCE/'textures').glob('*.png'): put(RES/'assets/bwr/textures/block/modern'/p.name,p.read_bytes())
    for p,data in OUTPUTS.items():
        if '--check' in sys.argv:
            assert p.is_file() and p.read_bytes()==data,'Generated asset differs: '+str(p)
        else:
            p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
    print(len(OUTPUTS),'modern assets verified' if '--check' in sys.argv else 'modern assets written')

if __name__=='__main__': run()
