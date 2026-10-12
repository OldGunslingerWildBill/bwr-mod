"""Bake Blender containment models; --check verifies committed generated assets."""
import json
import sys
from pathlib import Path
import importlib.util

ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern_export',ROOT/'tools-export-modern.py')
modern=importlib.util.module_from_spec(spec);spec.loader.exec_module(modern)
SOURCE=ROOT/'art/models/containment'
RES=ROOT/'mod/src/main/resources'
OUTPUT={}

def put(path,value):
    OUTPUT[path]=(json.dumps(value,indent=2)+'\n').encode() if isinstance(value,dict) else value.encode()

def bake(name):
    data=json.loads((SOURCE/(name+'.json')).read_text())
    folder=RES/'assets/bwr/models/block/containment'/name
    mats={key:'mat_'+str(i) for i,key in enumerate(data['materials'])}
    mtl=[]
    for key,material in data['materials'].items():
        mtl+=['newmtl '+mats[key], 'Kd '+' '.join(map(str,material['color'])),
              'map_Kd '+material['texture'],'']
    put(folder/'materials.mtl','\n'.join(mtl))
    for face in data['faces']:
        face['normal']=modern.normal(face['vertices'])
    put(folder/'body.obj',modern.obj(data['faces'],mats))
    put(RES/('assets/bwr/models/block/containment/'+name+'.json'),modern.model('bwr:models/block/containment/'+name+'/body.obj'))
    print(name,len(data['faces']),'triangles')

def run():
    for name in ('vault_3_frame','vault_3_leaf','vault_4_frame','vault_4_leaf',
                 'airlock_frame','airlock_inner_leaf','airlock_outer_leaf',
                 'sleeve_steam','sleeve_water','sleeve_pass_through','sleeve_power','sleeve_data'):
        bake(name)
    for block,part in (('containment_vault_door','vault_3_leaf'),('containment_vault_door_4x4','vault_4_leaf'),
                       ('containment_airlock','airlock_inner_leaf')):
        put(RES/('assets/bwr/blockstates/'+block+'.json'),{'variants':{'':{'model':'bwr:block/containment/empty'}}})
        put(RES/('assets/bwr/models/item/'+block+'.json'),{
            'parent':'bwr:block/containment/'+part,
            'display':{'gui':{'rotation':[20,210,0],'scale':[.24,.24,.24]},
                       'ground':{'scale':[.15,.15,.15]},'fixed':{'scale':[.2,.2,.2]}}})
    put(RES/'assets/bwr/models/block/containment/empty.json',{'textures':{'particle':'minecraft:block/iron_block'},'elements':[]})
    for service in ('steam','water','pass_through','power','data'):
        name='containment_'+service+('' if service=='pass_through' else '_penetration')
        put(RES/('assets/bwr/models/block/'+name+'.json'),{'parent':'bwr:block/containment/sleeve_'+service})
        variants={}
        for facing,transform in (('north',{}),('south',{}),('east',{'y':90}),('west',{'y':90}),
                                 ('up',{'x':90}),('down',{'x':90})):
            for ruptured in (False,True):
                variants['facing='+facing+',ruptured='+str(ruptured).lower()]=dict(
                    {'model':'bwr:block/'+name},**transform)
        put(RES/('assets/bwr/blockstates/'+name+'.json'),{'variants':variants})
    for path,data in OUTPUT.items():
        if '--check' in sys.argv:
            assert path.is_file() and path.read_bytes()==data,'Generated asset differs: '+str(path)
        else:
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print(len(OUTPUT),'containment assets verified' if '--check' in sys.argv else 'containment assets written')

if __name__=='__main__':run()
