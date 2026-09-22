"""Bake the Blender MSIV into three block-local cells; --check is read-only."""
import importlib.util
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/condenser_msiv'
m.bake_assembly_cells('msiv',suffix='')
variants={}
for direction,angle in zip(('north','east','south','west'),(0,90,180,270)):
    for assembled in (False,True):
        for part in range(3):
            for opened in (False,True):
                # Saved one-block valves keep their cube and omnidirectional geometry
                # until replaced. Newly placed valves always use the full Blender model.
                mesh=f'pumps/msiv/cell_{part}' if assembled else ('msiv_open' if opened else 'msiv_closed')
                key=f'assembled={str(assembled).lower()},facing={direction},open={str(opened).lower()},part={part}'
                variants[key]={'model':'bwr:block/'+mesh,'y':angle}
m.put(m.RES/'assets/bwr/blockstates/msiv.json',{'variants':variants})
m.put(m.RES/'assets/bwr/models/item/msiv.json',{'parent':'bwr:block/pumps/msiv/compact',
      'display':{'gui':{'rotation':[15,225,0],'scale':[1.05,1.05,1.05]}}})
m.put(m.RES/'data/bwr/loot_table/blocks/msiv.json',{'type':'minecraft:block','pools':[{
    'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:msiv'}],
    'conditions':[{'condition':'minecraft:block_state_property','block':'bwr:msiv','properties':{'part':'0'}},
                  {'condition':'minecraft:survives_explosion'}]}]})
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale MSIV asset: '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'MSIV assets verified' if '--check' in sys.argv else 'MSIV assets written')
