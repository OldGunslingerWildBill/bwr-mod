"""Export the Blender turbine auxiliary meshes (main oil tank, generator cooling skid, shaft oil pump, the duplex oil filter housing in four
states, the lube oil conditioner); --check verifies without writes.

Blender side:  blender --background --python art/models/turbine_aux/build_models.py
This tool turns the exported meshes into the OBJ models, blockstates, item models, loot tables and recipes.
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('modern', ROOT / 'tools-export-modern.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.SOURCE = ROOT / 'art/models/turbine_aux'

HORIZONTAL_VARIANTS = {f'facing={d}': {'model': None, 'y': a} for d, a in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270))}


def block(id, variants, recipe):
    """One body model; recipe None writes no recipe file (the recipe tools own it)."""
    s = m.read(id + '.json')
    folder = m.RES / f'assets/bwr/models/block/turbine_aux/{id}'
    points = [p for f in s['faces'] for p in f['vertices']]
    b = m.bounds(points)
    assert all(b[a] >= -1e-6 and b[a + 3] <= 1.000001 for a in range(3)), (id, b)
    mats = m.materials(s, folder)
    m.put(folder / 'body.obj', m.obj(s['faces'], mats))
    m.put(folder / 'body.json', m.model(f'bwr:models/block/turbine_aux/{id}/body.obj'))
    model = f'bwr:block/turbine_aux/{id}/body'
    m.put(m.RES / f'assets/bwr/blockstates/{id}.json', {'variants': {k: dict(v, model=model) if v else {'model': model} for k, v in variants.items()}})
    m.put(m.RES / f'assets/bwr/models/item/{id}.json',
          {'parent': model, 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
    m.put(m.RES / f'data/bwr/loot_table/blocks/{id}.json',
          {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:' + id}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    if recipe is not None:
        m.put(m.RES / f'data/bwr/recipe/{id}.json', dict(recipe, result={'id': 'bwr:' + id, 'count': 1}))


def conditioner():
    """The Lube Oil Conditioner: the body (dark lamp lens) always, and the lit lamp lens, its own emissive part, when the blockstate says it
    runs (property lit). The lit part is full-bright through emissive_ambient (the OBJ loader takes the light level from the material's Ka, as
    the industrial lamps' lit lenses do, LIGHTING.md) and its texture has a labPBR _s map with the emission in the alpha channel."""
    id = 'lube_oil_conditioner'
    block(id, {}, None)
    lamp = m.read(id + '_lamp_on.json')
    folder = m.RES / f'assets/bwr/models/block/turbine_aux/{id}'
    assert len(lamp['materials']) == 1, lamp['materials'].keys()
    key = next(iter(lamp['materials']))
    obj = m.obj(lamp['faces'], {key: 'lamp_on'}).replace('mtllib materials.mtl', 'mtllib lamp_on.mtl')
    m.put(folder / 'lamp_on.obj', obj)
    m.put(folder / 'lamp_on.mtl', 'newmtl lamp_on\nKd 1 1 1\nKa 1 1 1\nmap_Kd ' + lamp['materials'][key]['texture'] + '\n\n')
    m.put(folder / 'lamp_on.json', dict(m.model(f'bwr:models/block/turbine_aux/{id}/lamp_on.obj'), emissive_ambient=True))
    body, lit = f'bwr:block/turbine_aux/{id}/body', f'bwr:block/turbine_aux/{id}/lamp_on'
    parts = []
    # side_b and off_open belong to every TurbineAuxBlock (the filter housing uses them) and mean nothing here: any value takes the part,
    # named so that every state of the block gets exactly one body and, when it runs, one lamp (tools-audit-assets.py insists on it).
    anyside = {'side_b': 'false|true', 'off_open': 'false|true'}
    for d, a in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270)):
        turn = {'y': a} if a else {}
        parts.append({'when': dict({'facing': d}, **anyside), 'apply': dict({'model': body}, **turn)})
        parts.append({'when': dict({'facing': d, 'lit': 'true'}, **anyside), 'apply': dict({'model': lit}, **turn)})
    m.put(m.RES / f'assets/bwr/blockstates/{id}.json', {'multipart': parts})


def duplex_housing(recipe):
    """The Oil Filter Housing: four baked bodies (side A or B in service, the off-service lid closed or open), chosen by the blockstate
    properties side_b and off_open (TURBINE-AUX.md). One shared materials file, so the four meshes use one numbering."""
    id = 'oil_filter_housing'
    labels = ('a_closed', 'a_open', 'b_closed', 'b_open')
    sources = {l: m.read(f'{id}_{l}.json') for l in labels}
    folder = m.RES / f'assets/bwr/models/block/turbine_aux/{id}'
    combined = {}
    for l in labels:
        for k, v in sources[l]['materials'].items():
            assert combined.setdefault(k, v) == v, (l, k)
    mats = m.materials({'materials': dict(sorted(combined.items()))}, folder)
    for l in labels:
        src = sources[l]
        b = m.bounds([p for f in src['faces'] for p in f['vertices']])
        assert all(b[a] >= -1e-6 and b[a + 3] <= 1.000001 for a in range(3)), (l, b)
        m.put(folder / f'body_{l}.obj', m.obj(src['faces'], mats))
        m.put(folder / f'body_{l}.json', m.model(f'bwr:models/block/turbine_aux/{id}/body_{l}.obj'))
    variants = {}
    for d, a in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270)):
        for side_b in ('false', 'true'):
            for off_open in ('false', 'true'):
                label = ('b' if side_b == 'true' else 'a') + ('_open' if off_open == 'true' else '_closed')
                variants[f'facing={d},side_b={side_b},off_open={off_open}'] = {'model': f'bwr:block/turbine_aux/{id}/body_{label}', 'y': a}
    m.put(m.RES / f'assets/bwr/blockstates/{id}.json', {'variants': variants})
    m.put(m.RES / f'assets/bwr/models/item/{id}.json',
          {'parent': f'bwr:block/turbine_aux/{id}/body_a_closed', 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
    m.put(m.RES / f'data/bwr/loot_table/blocks/{id}.json',
          {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'bwr:' + id}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    m.put(m.RES / f'data/bwr/recipe/{id}.json', dict(recipe, result={'id': 'bwr:' + id, 'count': 1}))



def shaped(pattern, key):
    return {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pattern,
            'key': {k: {'item': v} for k, v in key.items()}}


IRON, COPPER = 'minecraft:iron_ingot', 'minecraft:copper_ingot'
block('main_oil_tank', dict(HORIZONTAL_VARIANTS),
      shaped(['ITI', 'PBP', 'III'], {'I': IRON, 'T': 'minecraft:cauldron', 'P': 'bwr:high_pressure_water_pipe', 'B': 'minecraft:bucket'}))
block('generator_cooling_skid', dict(HORIZONTAL_VARIANTS),
      shaped(['ICI', 'PTP', 'III'], {'I': IRON, 'C': COPPER, 'P': 'bwr:high_pressure_water_pipe', 'T': 'minecraft:cauldron'}))
duplex_housing(shaped(['ILI', 'BPB', 'III'], {'I': IRON, 'L': 'minecraft:lever', 'B': 'minecraft:bucket', 'P': 'bwr:high_pressure_water_pipe'}))
conditioner()
block('shaft_oil_pump', dict(HORIZONTAL_VARIANTS),
      shaped(['ICI', 'RPR', 'III'], {'I': IRON, 'C': COPPER, 'R': 'minecraft:redstone', 'P': 'minecraft:piston'}))

for p, content in m.OUTPUTS.items():
    if '--check' in sys.argv:
        assert p.exists() and p.read_bytes() == content, 'Stale turbine aux asset: ' + str(p)
    else:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)
print(len(m.OUTPUTS), 'turbine aux assets verified' if '--check' in sys.argv else 'turbine aux assets exported')
