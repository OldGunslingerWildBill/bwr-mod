"""Original Blender core-spray artwork using the user's Fukushima reference GIF.

Reusable header/nozzle pieces retain their pipe diameter as the reactor grows.
The GIF is a visual reference, not a dimensional or manufacturing specification.
Run build() in Blender. Existing scenes are never cleared.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8').split('def build_condenser')[0], str(helper), 'exec'))
OUT = ROOT / 'art/models/core_sparger'
OUT.mkdir(parents=True, exist_ok=True)
METAL = material('sparger stainless steel', (.38, .43, .45), .8)
WELD = material('sparger weld bead', (.21, .25, .27), .65)
LOOPS = {'lpcs': BLUE, 'hpcs': AMBER}


def nozzle(s, band):
    # +X points inward to the core; the spray mouth points down and inward.
    sweep(s, 'formed spray branch', [(0,0,0),(.18,0,0),(.30,-.06,0),(.38,-.20,0)], .057, METAL, 12)
    axis=Vector((.08,-.14,0)).normalized();end=Vector((.38,-.20,0))
    sleeve(s,'nozzle tip',end-axis*.065,end+axis*.035,.076,.039,METAL,16)
    cylinder(s,'recessed nozzle bore',end-axis*.04,end-axis*.035,.038,DARK,12)
    sleeve(s,'branch weld',(.115,0,0),(.145,0,0),.067,.053,WELD,12)
    sleeve(s,'circuit identification collar',(0,0,-.038),(0,0,.038),.139,.125,band,20)
    box(s,'mounting saddle',(-.16,-.15,-.105),(-.10,-.075,.105),METAL)
    box(s,'radial support tab',(-.28,-.15,-.10),(-.12,-.11,.10),METAL)
    for z in (-.065,.065):bolt(s,'support stud',(-.225,-.165,z),(-.225,-.09,z),.023)


def build():
    parts={}
    s=new_scene('Realistic BWR | sparger header');parts['header']=s
    cylinder(s,'round welded header',(0,0,0),(0,0,1),.13,METAL,20)
    for loop,band in LOOPS.items():
        s=new_scene('Realistic BWR | '+loop+' nozzle');parts['nozzle_'+loop]=s
        nozzle(s,band)
        s=new_scene('Realistic BWR | '+loop+' construction segment');parts['segment_'+loop]=s
        cylinder(s,'header segment',(.5,.52,0),(.5,.52,1),.13,METAL,20)
        for z in (.08,.92):sleeve(s,'circumferential weld',(.5,.52,z-.015),(.5,.52,z+.015),.14,.128,WELD,20)
        before=set(s.objects);nozzle(s,band)
        for o in set(s.objects)-before:o.location+=xyz((.5,.52,.5))
    for name,s in parts.items():export_scene(s,name+'.json',{'id':name})
    review=new_scene('Realistic BWR | circular core spray spargers')
    for loop,y in (('lpcs',0),('hpcs',1.3)):
        radius=5.8
        sweep(review,loop+' annular header',[(radius*math.cos(i*math.tau/160),y,radius*math.sin(i*math.tau/160)) for i in range(161)],.13,METAL,20)
        for i in range(40):
            a=i*math.tau/40
            for src in parts['nozzle_'+loop].objects:
                o=src.copy();o.data=src.data.copy();review.collection.objects.link(o)
                for v in o.data.vertices:
                    x,yy,z=game(v.co)
                    v.co=xyz((radius*math.cos(a)-x*math.cos(a)+z*math.sin(a),y+yy,radius*math.sin(a)-x*math.sin(a)-z*math.cos(a)))
    review['description']='Reference-inspired circular stainless headers and inward/downward nozzles. Game ring size follows the reactor; the two loops retain their original elevations.'
    studio(review,(0,.5,0),(12,14,-15),16,(1280,960))
    review.render.filepath=str(OUT/'sparger-preview.png')
    bpy.data.libraries.write(str(OUT/'core_sparger.blend'),set(parts.values())|{review},fake_user=True)
    viewport(review,(0,.5,0),(12,14,-15),19)
    return review
