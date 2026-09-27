"""RVU interface parts, authored/exported inside Blender. No existing scenes modified.

Steam nozzle silhouette: Columbia FSAR ML23346A215 PDF p1940, Fig 4.1-2.
Game-sized original geometry; not an engineering dimensional reproduction.
Run build() using Blender MCP. Local +Z points out of the vessel; face is Z=0.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
helper = ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'), str(helper), 'exec'))
OUT = ROOT/'art/models/reactor_vessel'

def build():
    pieces = {}
    for name, band in [('steam_nozzle', AMBER), ('water_nozzle', BLUE)]:
        s = new_scene('RVU | '+name); pieces[name] = s
        sleeve(s, 'forged tapered nozzle neck', (0,0,-.70), (0,0,-.30), .36, .22, STEEL, 32, end_outer=.30)
        sleeve(s, 'round outlet neck', (0,0,-.32), (0,0,-.06), .30, .22, STEEL, 32)
        sleeve(s, 'service identification band', (0,0,-.27), (0,0,-.20), .305, .299, band, 32)
        sleeve(s, 'reinforced weld shoulder', (0,0,-.70), (0,0,-.56), .42, .22, BODY, 32, end_outer=.36)
        steam_flange(s, 'pipe connection', (0,0,0), (0,0,1))
    s = new_scene('RVU | controller_panel'); pieces['controller_panel'] = s
    box(s, 'shallow instrument enclosure', (-.43,-.43,-.20), (.43,.43,-.025), BODY)
    box(s, 'steel panel bezel', (-.41,-.41,-.03), (.41,.41,0), STEEL)
    box(s, 'recessed dark instrument face', (-.365,-.365,-.015), (.365,.365,.002), DARK)
    screen = material('RVU screen glass', (.018,.085,.09), .15)
    glow = material('RVU instrument green', (.09,.66,.46), .1)
    box(s, 'display glass', (-.30,-.03,.003), (.30,.29,.008), screen)
    for i,h in enumerate((.11,.20,.15,.25,.18)):
        x=-.25+i*.10; box(s, 'display bar', (x,0,.01), (x+.06,h,.012), glow)
    box(s, 'display baseline', (-.27,-.01,.01), (.27,0,.012), glow)
    for i,m in enumerate((TEAL,AMBER,BLUE)):
        cylinder(s,'status lens',(-.23+i*.23,-.18,.002),(-.23+i*.23,-.18,.019),.044,m,16)
    box(s,'computer socket',(-.12,-.33,.003),(.12,-.24,.012),STEEL)
    box(s,'computer socket recess',(-.095,-.31,.014),(.095,-.26,.016),DARK)
    for x in (-.39,.39):
        for y in (-.39,.39): bolt(s,'captive face screw',(x,y,-.02),(x,y,.012),.022)
    for name,s in pieces.items(): export_scene(s,name+'.json',{'id':name})
    review = new_scene('RVU | integrated interfaces review')
    for name,x in [('steam_nozzle',-1.3),('water_nozzle',0),('controller_panel',1.3)]:
        for source in pieces[name].objects:
            o=source.copy();o.data=source.data.copy();review.collection.objects.link(o);o.location+=xyz((x,0,0))
    review['reference']='Columbia FSAR ML23346A215 PDF page 1940, Figure 4.1-2'
    studio(review,(0,0,-.18),(2.7,2.2,5),4.8,(1200,700))
    review.render.filepath=str(OUT/'rvu_interfaces.png')
    bpy.data.libraries.write(str(OUT/'rvu_interfaces.blend'),set(pieces.values())|{review},fake_user=True)
    viewport(review,(0,0,-.18),(2.7,2.2,5),4.8)
    return review
