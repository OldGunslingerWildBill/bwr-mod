"""Blender shroud proportions from GE ABWR DCD Tier 2, Table 5.3-2.

ExportRipLayouts supplies the same normalized dimensions used by the game.
The flange overhang is original game artwork, not an engineering dimension.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/reactor_core'

def build():
    data=json.loads((OUT/'abwr_dimensions.json').read_text(encoding='utf-8'))
    channel=material('ABWR stainless shroud',(.31,.36,.38),.75)
    scenes=[]
    for name,radius,height,mat in (
            ('abwr_shroud',data['outer_radius'],1,channel),
            ('abwr_shroud_rim',data['rim_radius'],.13,STEEL)):
        s=new_scene('Realistic BWR | core '+name);scenes.append(s)
        sleeve(s,name,(0,0,0),(0,height,0),radius,data['inner_radius'],mat,96)
        s['source']=data['source']
        export_scene(s,name+'.json',{'id':name,'source':data['source']})
    bpy.data.libraries.write(str(OUT/'abwr_shroud.blend'),set(scenes),fake_user=True)
    print('ABWR shroud and rim exported:',data)
