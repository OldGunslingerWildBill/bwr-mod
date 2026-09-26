"""Clip original Blender Terry skids into bounded NeoForge OBJ cells. --check verifies."""
import importlib.util
import json
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/terry'

def run():
    for id,old_count in (('rcic_twl',18),('hpci_turbine',60)):
        source=m.bake_assembly_cells(id,'_terry');count=__import__('math').prod(source['size']);variants={}
        for modern,n in ((False,old_count),(True,count)):
            for facing,angle in zip(('north','east','south','west'),(0,90,180,270)):
                for i in range(256):
                    path=(f'pumps/{id}_terry' if modern else f'turbines/{id}')+f'/cell_{i}' if i<n else 'pumps/empty'
                    variants[f'cell={i},facing={facing},modern={str(modern).lower()}']={'model':'bwr:block/'+path,'y':angle}
        m.put(m.RES/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
        m.put(m.RES/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/pumps/{id}_terry/compact',
               'display':{'gui':{'rotation':[25,225,0],'scale':[.85,.85,.85]}}})
        print(id,source['size'],len(source['faces']),'triangles',count,'cells; legacy layouts preserved')
    for path,data in m.OUTPUTS.items():
        if '--check' in sys.argv:
            assert path.is_file() and path.read_bytes()==data, 'Generated asset differs: '+str(path)
        else:
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print(len(m.OUTPUTS),'Terry assets verified' if '--check' in sys.argv else 'Terry assets written')

if __name__=='__main__':run()
