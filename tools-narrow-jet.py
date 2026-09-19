"""Fit the supplied jet model into one column, preserving atlas UVs and legacy saves.

Current mesh is only 1.111 blocks wide inside its old two-block reservation.
An 0.85 horizontal scale fits it into one column while retaining its six-block
height, depth, source parts and materials. --check verifies without writing.
"""
import json,math,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent/'mod/src/main/resources'
SCALE=.85

def narrow_jet(root=ROOT,refresh_legacy=False,check=False):
    outputs={}
    def put(p,value):outputs[p]=json.dumps(value,indent=2)+'\n' if isinstance(value,dict) else value
    base=root/'assets/bwr/models/block/pumps'
    current=base/'jet_pump';legacy=base/'jet_pump_legacy'
    source=current if refresh_legacy or not legacy.exists() else legacy
    manifest_path=root/'data/bwr/pump_models'/('jet_pump.json' if source==current else 'jet_pump_legacy.json')
    original=json.loads(manifest_path.read_text())
    assert (original['width'],original['height'],original['depth'])==(2,6,1)
    # Preserve every original cell and its resource path for old assembled saves.
    for p in source.iterdir():
        if p.is_file():put(legacy/p.name,p.read_text().replace('pumps/jet_pump/','pumps/jet_pump_legacy/'))
    put(root/'data/bwr/pump_models/jet_pump_legacy.json',original)

    def transform(text,offset_x=0,center=1,out_center=.5,counts=(0,0,0)):
        result=[];seen=[0,0,0]
        for line in text.splitlines():
            parts=line.split()
            if not parts:continue
            if parts[0]=='v':
                x,y,z=map(float,parts[1:4]);x=(x+offset_x-center)*SCALE+out_center
                result.append(f'v {x:.9f} {y:.9f} {z:.9f}');seen[0]+=1
            elif parts[0]=='vt':result.append(line);seen[1]+=1
            elif parts[0]=='vn':
                n=list(map(float,parts[1:4]));n[0]/=SCALE;length=math.sqrt(sum(v*v for v in n))
                result.append('vn '+' '.join(f'{v/length:.9f}' for v in n));seen[2]+=1
            elif parts[0]=='f':
                indices=[]
                for vertex in parts[1:]:
                    fields=vertex.split('/')
                    indices.append('/'.join(str(int(v)+counts[a]) if v else '' for a,v in enumerate(fields)))
                result.append('f '+' '.join(indices))
            elif parts[0] not in ('mtllib','o'):result.append(line)
        return '\n'.join(result)+'\n',tuple(counts[i]+seen[i] for i in range(3))

    cells=[]
    for y in range(6):
        text='mtllib materials.mtl\no jet_column_'+str(y)+'\n';counts=(0,0,0)
        for x in range(2):
            part,counts=transform((source/f'cell_{y*2+x}.obj').read_text(),x,counts=counts);text+=part
        points=[list(map(float,line.split()[1:4])) for line in text.splitlines() if line.startswith('v ')]
        bounds=[min(p[a] for p in points) for a in range(3)]+[max(p[a] for p in points) for a in range(3)]
        assert all(-1e-7<=v<=1+1e-7 for v in bounds),bounds
        put(current/f'cell_{y}.obj',text)
        model=json.loads((source/'cell_0.json').read_text());model['model']=f'bwr:models/block/pumps/jet_pump/cell_{y}.obj'
        put(current/f'cell_{y}.json',model)
        cells.append(dict(index=y,offset=[0,y,0],bounds=bounds,triangles=sum(line.startswith('f ') for line in text.splitlines())))
    for name,center,out in [('compact',.5,.5),('assembled',0,0)]:
        text,_=transform((source/f'{name}.obj').read_text(),center=center,out_center=out)
        put(current/f'{name}.obj','mtllib materials.mtl\no jet_pump\n'+text)
    put(current/'materials.mtl',(source/'materials.mtl').read_text())
    manifest={**original,'width':1,'status':'Installed one-column jet assembly; no external ports',
              'cells':cells,'game_ports':[],'anchor_in_assembly_grid':[.5,0,.725],'anchor_from_controller':[.5,0,.725]}
    put(root/'data/bwr/pump_models/jet_pump.json',manifest)
    variants={}
    for narrow in (False,True):
        id='jet_pump' if narrow else 'jet_pump_legacy';count=6 if narrow else 12
        for facing,rotation in zip(('north','east','south','west'),(0,90,180,270)):
            for i in range(256):
                for full in (False,True):
                    model=f'pumps/{id}/cell_{i}' if full and i<count else 'pumps/empty' if full else 'pumps/jet_pump/compact'
                    variants[f'assembled={str(full).lower()},cell={i},facing={facing},narrow={str(narrow).lower()}']={'model':'bwr:block/'+model,'y':rotation}
    put(root/'assets/bwr/blockstates/jet_pump.json',{'variants':variants})
    for p,data in outputs.items():
        if check:
            if not p.exists() or p.read_text()!=data:raise ValueError('Jet asset differs: '+str(p))
        else:p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data,encoding='utf-8')
    print(f'Jet model: six 1x1 cells; 0.85 horizontal scale; {len(outputs)} current/legacy assets '+('verified' if check else 'written'))

if __name__=='__main__':narrow_jet(check='--check' in sys.argv)
