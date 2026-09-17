"""Import the supplied scale-review pack, preserving atlas UVs and clipped meshes.

Usage: python tools-import-pump-pack.py path/to/BWR_Pump_Assets_Scale_Review.zip
Only runtime geometry is shipped. No Blender installation is needed.
"""
import hashlib
import json
import math
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent / 'mod/src/main/resources'
IDS = ('lpcs_pump', 'rhr_pump', 'hpcs_pump', 'motor_feed_pump',
       'turbine_feed_pump', 'jet_pump', 'rip_pump')
# Final grid sockets. The intervening spool reaches the supplied visual flange.
PORTS = {
    'lpcs_pump': [('WATER_SUCTION', [0,0,1], 'west'), ('WATER_DISCHARGE', [3,0,1], 'east')],
    'rhr_pump': [('WATER_SUCTION', [0,1,1], 'west'), ('WATER_DISCHARGE', [1,2,1], 'up')],
    'hpcs_pump': [('WATER_SUCTION', [0,1,1], 'west'), ('WATER_DISCHARGE', [1,3,1], 'up')],
    'motor_feed_pump': [('WATER_SUCTION', [1,1,1], 'up'), ('WATER_DISCHARGE', [3,1,1], 'up')],
    'turbine_feed_pump': [('WATER_SUCTION', [1,2,1], 'up'), ('WATER_DISCHARGE', [3,2,1], 'up'),
                          ('STEAM_INLET', [5,2,1], 'up'), ('STEAM_EXHAUST', [5,1,2], 'south')],
    'jet_pump': [('WATER_DISCHARGE', [0,0,0], 'down'), ('WATER_DISCHARGE', [1,0,0], 'down'),
                 ('WATER_SUCTION', [1,0,0], 'north')],
    'rip_pump': [],  # Internal wet end and vessel mounting flange, not external pipe sockets.
}
VECTORS = dict(west=(-1,0,0), east=(1,0,0), up=(0,1,0), down=(0,-1,0), north=(0,0,-1), south=(0,0,1))

def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8')

def append_box(text, lo, hi):
    n = sum(line.startswith('v ') for line in text.splitlines())
    t = sum(line.startswith('vt ') for line in text.splitlines())
    p = [(lo[0],lo[1],lo[2]),(hi[0],lo[1],lo[2]),(hi[0],hi[1],lo[2]),(lo[0],hi[1],lo[2]),
         (lo[0],lo[1],hi[2]),(hi[0],lo[1],hi[2]),(hi[0],hi[1],hi[2]),(lo[0],hi[1],hi[2])]
    text += '\nusemtl pump_atlas\n' + ''.join('v %.7f %.7f %.7f\n'%v for v in p)
    text += 'vt 0.05 0.05\n'
    for face in ((1,4,3,2),(5,6,7,8),(1,5,8,4),(2,3,7,6),(4,8,7,3),(1,2,6,5)):
        text += 'f '+' '.join(f'{n+i}/{t+1}' for i in face)+'\n'
    return text

def import_pack(archive):
    with zipfile.ZipFile(archive) as z:
        base = next(n[:-len('runtime/data/bwr/pump_models/lpcs_pump.json')] for n in z.namelist()
                    if n.endswith('runtime/data/bwr/pump_models/lpcs_pump.json'))
        for name in z.namelist():
            relative = name[len(base+'runtime/'):]
            if name.startswith(base+'runtime/assets/') and not name.endswith('/'):
                target = ROOT / relative
                if ROOT.resolve() not in target.resolve().parents:
                    raise ValueError('Unsafe archive member')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(name))
        for id in IDS:
            manifest = json.loads(z.read(base+f'runtime/data/bwr/pump_models/{id}.json'))
            w,h,d = (manifest[k] for k in ('width','height','depth'))
            controller = manifest['controller_cell']
            if isinstance(controller, dict):
                controller = controller['offset']
            manifest['game_ports'] = [dict(role=r,cell=p,face=f) for r,p,f in PORTS[id]]
            manifest.pop('rotations', None)
            for cell in manifest['cells']:
                cell['bounds'] = [min(1,max(0,value)) for value in cell['bounds']]
            directory = ROOT / f'assets/bwr/models/block/pumps/{id}'
            # Visible short orthogonal adapters make every tube terminate on the model.
            meshes = {c['index']: (directory/f"cell_{c['index']}.obj").read_text() for c in manifest['cells']}
            for port, source in zip(PORTS[id], manifest['ports']):
                _,cell,face = port
                endpoint = [cell[i]+.5+VECTORS[face][i]*.5 for i in range(3)]
                start = source['assembly_position'][:]
                axis = next(i for i,x in enumerate(VECTORS[face]) if x)
                # Move outwards first, then across just inside the assembly boundary.
                bend = start[:]
                bend[axis] = endpoint[axis]-VECTORS[face][axis]*.14
                lateral = endpoint[:]
                lateral[axis] = bend[axis]
                for a,b in zip((start,bend,lateral),(bend,lateral,endpoint)):
                    lo = [max(0,min(a[i],b[i])-.105) for i in range(3)]
                    hi = [min((w,h,d)[i],max(a[i],b[i])+.105) for i in range(3)]
                    for c in manifest['cells']:
                        off = c['offset']
                        low = [max(0,lo[i]-off[i]) for i in range(3)]
                        high = [min(1,hi[i]-off[i]) for i in range(3)]
                        if any(low[i]>=high[i]-1e-8 for i in range(3)): continue
                        meshes[c['index']] = append_box(meshes[c['index']],low,high)
                        old = c['bounds']
                        c['bounds'] = ([min(old[i],low[i]) for i in range(3)]+[max(old[i+3],high[i]) for i in range(3)]) if old else low+high
            for n,text in meshes.items(): (directory/f'cell_{n}.obj').write_text(text)
            # Normalise the full assembly for both inventory and saved one-cell machines.
            assembled = (directory/'assembled.obj').read_text()
            points = [[float(x) for x in line.split()[1:4]] for line in assembled.splitlines() if line.startswith('v ')]
            low = [min(p[i] for p in points) for i in range(3)]
            high = [max(p[i] for p in points) for i in range(3)]
            scale = .9/max(high[i]-low[i] for i in range(3))
            result = []
            for line in assembled.splitlines():
                if line.startswith('v '):
                    p = list(map(float,line.split()[1:4]))
                    line = 'v '+' '.join(f'{(p[i]-(low[i]+high[i])/2)*scale+.5:.7f}' for i in range(3))
                result.append(line)
            (directory/'compact.obj').write_text('\n'.join(result)+'\n')
            model = json.loads((directory/'cell_0.json').read_text())
            model['model'] = f'bwr:models/block/pumps/{id}/compact.obj'
            dump(directory/'compact.json',model)
            dump(ROOT/f'assets/bwr/models/item/{id}.json',{'parent':f'bwr:block/pumps/{id}/compact','display':{
                'gui':{'rotation':[25,225,0],'translation':[0,0,0],'scale':[.8,.8,.8]},
                'ground':{'scale':[.3,.3,.3]},'fixed':{'scale':[.7,.7,.7]}}})
            variants = {}
            for facing,rotation in zip(('north','east','south','west'),(0,90,180,270)):
                for n in range(96):
                    for assembled in (False,True):
                        model = f'bwr:block/pumps/{id}/cell_{n}' if assembled and n<w*h*d else f'bwr:block/pumps/{id}/compact' if not assembled else 'bwr:block/pumps/empty'
                        key = f'assembled={str(assembled).lower()},cell={n},facing={facing}'
                        variants[key] = dict(model=model,y=rotation)
            # SIZE is deliberately omitted: both saved variants use the same paired geometry.
            dump(ROOT/f'assets/bwr/blockstates/{id}.json',{'variants':variants})
            root_index = controller[0]+w*(controller[2]+d*controller[1])
            dump(ROOT/f'data/bwr/loot_table/blocks/{id}.json',{'type':'minecraft:block','pools':[{
                'rolls':1,'entries':[{'type':'minecraft:item','name':f'bwr:{id}'}],
                'conditions':[{'condition':'minecraft:block_state_property','block':f'bwr:{id}','properties':{'cell':str(root_index)}},
                              {'condition':'minecraft:survives_explosion'}]}]})
            dump(ROOT/f'data/bwr/pump_models/{id}.json',manifest)
        dump(ROOT/'assets/bwr/models/block/pumps/empty.json',{'textures':{'particle':'bwr:block/pumps/pump_atlas'},'elements':[]})
    print('Imported seven pump assemblies; source SHA256:',hashlib.sha256(Path(archive).read_bytes()).hexdigest())

if __name__ == '__main__': import_pack(sys.argv[1])
