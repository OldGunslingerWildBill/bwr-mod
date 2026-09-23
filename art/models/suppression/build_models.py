"""Original Blender-authored concrete basin panels and four-port RHR exchanger.
Game coordinates X/Y/Z; one unit per block. Existing Blender scenes are retained.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/suppression';OUT.mkdir(parents=True,exist_ok=True)
CEMENT=material('warm cast concrete',(.48,.46,.42),0)
COPING=material('precast coping',(.64,.62,.57),0)
JOINT=material('concrete expansion joint',(.19,.19,.17),0)
PRIMARY=material('RHR primary red',(.48,.055,.028),.25)
RETURN=material('RHR primary return amber',(.60,.31,.028),.25)
COLD=material('service water blue',(.025,.20,.46),.25)
HOT=material('service water return cyan',(.025,.40,.46),.25)
FRAME=material('RHR exchanger graphite frame',(.075,.10,.12),.6)

def panel(scene,rim=False,port=False):
    if port:
        box(scene,'concrete penetration backing',(0,0,.24),(1,1,1),CEMENT)
        for a,b in [((0,0,0),(.25,1,.24)),((.75,0,0),(1,1,.24)),((.25,0,0),(.75,.25,.24)),((.25,.75,0),(.75,1,.24))]:box(scene,'concrete around nozzle',a,b,CEMENT)
        cylinder(scene,'dark bore',(.5,.5,.232),(.5,.5,.24),.23,DARK,24)
    else:box(scene,'solid reinforced concrete',(0,0,0),(1,.88 if rim else 1,1),CEMENT)
    # Fine formwork seams on all vertical faces; the solid panel closes every joint.
    for z in (.001,.999):
        for x in (.045,.95):box(scene,'formwork edge',(x,.05,z-.0015),(x+.005,.85 if rim else .95,z+.0015),JOINT)
        for y in (.055,.84 if rim else .94):box(scene,'horizontal form seam',(.05,y,z-.0015),(.95,y+.006,z+.0015),JOINT)
        if not port:
            for x in (.18,.82):
                for y in (.22,.78):cylinder(scene,'recessed tie plug',(x,y,z-.002),(x,y,z+.002),.017,JOINT,10)
    for x in (.001,.999):
        for z in (.05,.945):box(scene,'end panel seam',(x-.0015,.05,z),(x+.0015,.85 if rim else .95,z+.006),JOINT)
    if rim:
        box(scene,'continuous coping slab',(0,.88,0),(1,1,1),COPING)
        for z in (.002,.992):box(scene,'coping drip groove',(0,.90,z),(1,.915,z+.006),JOINT)

def port_scene(name,band):
    s=new_scene('BWR | '+name);panel(s,port=True)
    sleeve(s,'wall nozzle',(.5,.5,.025),(.5,.5,.235),.25,.205,STEEL,24)
    steam_flange(s,'connection',(.5,.5,0),(0,0,-1))
    sleeve(s,'circuit identification',(.5,.5,.14),(.5,.5,.18),.26,.205,band,24)
    # Color plate stays inside the wall face and away from the round pipe bore.
    box(s,'port identity plate',(.10,.80,-.001),(.35,.875,.012),band)
    return s

def build():
    pieces={}
    for name,rim in [('wall',False),('rim',True)]:
        s=new_scene('BWR | concrete basin '+name);panel(s,rim);pieces[name]=s
    pieces['suction']=port_scene('suppression pool suction',COLD)
    pieces['return']=port_scene('suppression pool return',RETURN)
    s=new_scene('BWR | four port RHR heat exchanger');pieces['heat_exchanger']=s
    for x in (.18,.73):box(s,'anchored support foot',(x,.03,.16),(x+.09,.13,.84),FRAME)
    box(s,'lower steel cradle',(.15,.12,.15),(.85,.22,.85),FRAME)
    # Welded plate cassette, flanged end plates and external tie rods.
    box(s,'sealed exchanger cassette',(.23,.25,.23),(.77,.75,.77),TITANIUM)
    for i in range(17):
        z=.24+i*.031
        box(s,'individual plate edge',(.218,.24,z),(.782,.76,z+.007),STEEL)
    for z in (.19,.79):box(s,'heavy end plate',(.16,.19,z),(.84,.83,z+.025),FRAME)
    for x in (.20,.80):
        for y in (.23,.79):
            cylinder(s,'cassette tie rod',(x,y,.16),(x,y,.85),.021,STEEL,10)
            bolt(s,'tie rod nut',(x,y,.135),(x,y,.19),.031)
    ports=[('primary inlet',(.5,.5,0),(0,0,-1),PRIMARY),('primary return',(.5,.5,1),(0,0,1),RETURN),
           ('cooling inlet',(0,.5,.5),(-1,0,0),COLD),('cooling return',(1,.5,.5),(1,0,0),HOT)]
    for name,p,d,band in ports:
        p=Vector(p);d=Vector(d)
        sleeve(s,name+' neck',p-d*.23,p-d*.02,.245,.205,STEEL,24)
        sleeve(s,name+' colored band',p-d*.19,p-d*.145,.25,.205,band,24)
        steam_flange(s,name,p,d)
        cylinder(s,name+' dark bore',p-d*.235,p-d*.23,.205,DARK,24)
        marker(s,name,p,circuit='primary' if name.startswith('primary') else 'secondary')
    box(s,'instrument top plate',(.30,.78,.34),(.70,.82,.68),STEEL)
    box(s,'identification plate',(.37,.823,.40),(.63,.827,.60),LABEL)
    for name,scene in pieces.items():export_scene(scene,name+'.json',{'id':name})
    preview=new_scene('BWR | suppression basin and RHR equipment')
    def copy_part(name,offset):
        for source in pieces[name].objects:
            if source.type!='MESH':continue
            o=source.copy();o.data=source.data.copy();preview.collection.objects.link(o);o.location+=xyz(offset)
    for x in range(9):
        for z in range(7):
            copy_part('wall',(x,0,z))
            if x in (0,8) or z in (0,6):
                for y in range(1,5):
                    part='rim' if y==4 else 'suction' if (x,y,z)==(2,1,0) else 'return' if (x,y,z)==(6,1,0) else 'wall'
                    copy_part(part,(x,y,z))
    water=material('suppression basin water',(.045,.22,.28),.3)
    box(preview,'water surface',(1,1,1),(8,3.75,6),water)
    copy_part('heat_exchanger',(6,1,-2))
    preview['description']='Open reinforced-concrete basin; separate suction and return wall flanges; four-port passive RHR heat exchanger.'
    studio(preview,(4.5,2,2.5),(16,13,-17),14,(1400,1000))
    viewport(preview,(4.5,2,2.5),(16,13,-17),16)
    preview.render.filepath=str(OUT/'suppression-basin.png')
    bpy.data.libraries.write(str(OUT/'suppression_pool_and_heat_exchanger.blend'),set(pieces.values())|{preview},fake_user=True)
    print('Saved',len(pieces),'Blender components and basin review scene')
    return preview
