"""Bake the Blender generator circuit breaker (GCB, the Grid Tie's model) into 6 x 3 x 4 block-local cells; --check is read-only.

Source: art/models/generator_breaker/generator_breaker.json (closed) and generator_breaker_open.json, written by
art/models/generator_breaker/build_gcb.py. Same route as the MSIV (tools-export-msiv.py, tools-export-modern.py bake_assembly_cells):
one OBJ model per cell, a manifest with the collision bounds, a blockstate, the item model and the loot table. Cells with no geometry use
bwr:block/pumps/empty and get no files. Cell index = x + 6 * (z + 4 * y), as for the pump assemblies; the controller is the cubicle cell.
"""
import importlib.util
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('modern',ROOT/'tools-export-modern.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.SOURCE=ROOT/'art/models/generator_breaker'
SIZE=(6,3,4);COUNT=SIZE[0]*SIZE[1]*SIZE[2]
_read=m.read
def read(name):
    # The viewing glass is a solid colour in this pipeline (no alpha), so it would hide the position indicator and the disconnector
    # blades behind it: it is left out of the baked cells (the Blender source keeps it).
    source=_read(name)
    source['faces']=[f for f in source['faces'] if 'viewing glass' not in f['material']]
    return source
m.read=read
m.bake_assembly_cells('generator_breaker',suffix='')
m.bake_assembly_cells('generator_breaker_open',suffix='')
# The open breaker differs from the closed one only in the position indicator and the disconnector blades, so it shares the manifest
# (collision) and the item model of the closed one.
for extra in (m.RES/'data/bwr/pump_models/generator_breaker_open.json',m.RES/'assets/bwr/models/block/pumps/generator_breaker_open/compact.obj',
              m.RES/'assets/bwr/models/block/pumps/generator_breaker_open/compact.json'):m.OUTPUTS.pop(extra)
def empty(state,i):
    p=m.RES/f'assets/bwr/models/block/pumps/{state}/cell_{i}.obj'
    return not any(line.startswith('f ') for line in m.OUTPUTS[p].decode().splitlines())
blank=set()
for state in ('generator_breaker','generator_breaker_open'):
    for i in range(COUNT):
        if empty(state,i):
            blank.add(i)
            for ext in ('obj','json'):m.OUTPUTS.pop(m.RES/f'assets/bwr/models/block/pumps/{state}/cell_{i}.{ext}')
# cells empty in one state but not the other would break the shared manifest: both states must agree
for state in ('generator_breaker','generator_breaker_open'):
    for i in range(COUNT):
        has=(m.RES/f'assets/bwr/models/block/pumps/{state}/cell_{i}.obj') in m.OUTPUTS
        assert has==(i not in blank),(state,i)
variants={'assembled=false':{'model':'bwr:block/grid_tie'}}
for direction,angle in zip(('north','east','south','west'),(0,90,180,270)):
    for closed in (True,False):
        for i in range(COUNT):
            mesh='pumps/empty' if i in blank else f'pumps/generator_breaker{"" if closed else "_open"}/cell_{i}'
            variants[f'assembled=true,cell={i},closed={str(closed).lower()},facing={direction}']={'model':'bwr:block/'+mesh,'y':angle}
m.put(m.RES/'assets/bwr/blockstates/grid_tie.json',{'variants':variants})
# Saved one-block Grid Ties keep the cube (assembled=false); the held item shows the full breaker.
m.put(m.RES/'assets/bwr/models/item/grid_tie.json',{'parent':'bwr:block/pumps/generator_breaker/compact',
      'display':{'gui':{'rotation':[25,225,0],'scale':[.95,.95,.95]}}})
m.put(m.RES/'data/bwr/loot_table/blocks/grid_tie.json',{'type':'minecraft:block','pools':[{
    'rolls':1,'entries':[{'type':'minecraft:item','name':'bwr:grid_tie'}],
    'conditions':[{'condition':'minecraft:any_of','terms':[
                      {'condition':'minecraft:block_state_property','block':'bwr:grid_tie','properties':{'assembled':'false'}},
                      {'condition':'minecraft:block_state_property','block':'bwr:grid_tie','properties':{'assembled':'true','cell':'0'}}]},
                  {'condition':'minecraft:survives_explosion'}]}]})
for p in (m.SOURCE/'textures').glob('*.png'):m.put(m.RES/'assets/bwr/textures/block/modern'/p.name,p.read_bytes())
for p,data in m.OUTPUTS.items():
    if '--check' in sys.argv:assert p.is_file() and p.read_bytes()==data,'Stale GCB asset: '+str(p)
    else:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
print(len(m.OUTPUTS),'GCB assets verified' if '--check' in sys.argv else 'GCB assets written',f'({COUNT-len(blank)} of {COUNT} cells have geometry)')
