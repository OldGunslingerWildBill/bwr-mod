"""Original modular cylindrical tank components. Run build() in Blender.
Horizontal coordinates are normalized to diameter=1; vertical roof/base heights
and the independent pipe/ladder details remain full-size in the game renderer.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(),str(helper),'exec'))
OUT=ROOT/'art/models/condensate_tank';OUT.mkdir(parents=True,exist_ok=True)
ENAMEL=material('tank pearl enamel',(.58,.65,.67),.25)
TRIM=material('tank blue trim',(.022,.14,.22),.30)

def build():
    pieces={}
    s=new_scene('BWR | CST base');pieces['base']=s
    cylinder(s,'round concrete plinth',(0,0,0),(0,.12,0),.49,CONCRETE,64)
    cylinder(s,'lower blue skirt',(0,.12,0),(0,.18,0),.475,TRIM,64)
    s=new_scene('BWR | CST wall');pieces['wall']=s
    sleeve(s,'continuous enamel shell',(0,0,0),(0,1,0),.47,.457,ENAMEL,64)
    # Vertical weld lines are geometric, with slight contrast rather than black stripes.
    for i in range(16):
        a=i*math.tau/16;x,z=.471*math.cos(a),.471*math.sin(a)
        cylinder(s,'vertical weld',(x,0,z),(x,1,z),.001,STEEL,6)
    s=new_scene('BWR | CST roof');pieces['roof']=s
    n=64;vs=[(r*math.cos(i*math.tau/n),y,r*math.sin(i*math.tau/n)) for r,y in ((.47,0),(.08,.40)) for i in range(n)]
    mesh_object(s,'shallow cone roof',vs,[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]+[tuple(range(n,2*n))],ENAMEL)
    sleeve(s,'roof perimeter lip',(0,0,0),(0,.065,0),.478,.456,TRIM,64)
    cylinder(s,'inspection hatch',(0,.40,0),(0,.48,0),.085,STEEL,24)
    cylinder(s,'vent riser',(0,.48,0),(0,1.04,0),.025,STEEL,16)
    cylinder(s,'vent rain cap',(0,1.04,0),(0,1.10,0),.045,TRIM,24)
    # Roof safety rail; normalized radial dimensions, fixed vertical dimensions.
    for i in range(24):
        a=i*math.tau/24;x,z=.438*math.cos(a),.438*math.sin(a)
        cylinder(s,'railing post',(x,.03,z),(x,.91,z),.0025,STEEL,6)
    for y in (.48,.91):sweep(s,'roof handrail',[(.438*math.cos(i*math.tau/64),y,.438*math.sin(i*math.tau/64)) for i in range(65)],.007,STEEL,6)
    s=new_scene('BWR | CST pipe flange');pieces['port']=s
    sleeve(s,'water nozzle',(0,0,.83),(0,0,.12),.25,.205,STEEL,24)
    sleeve(s,'water band',(0,0,.40),(0,0,.29),.262,.245,BLUE,24)
    steam_flange(s,'tank water',(0,0,0),(0,0,-1))
    s=new_scene('BWR | CST ladder');pieces['ladder']=s
    for x in (-.25,.25):cylinder(s,'ladder rail',(x,0,0),(x,1,0),.025,STEEL,8)
    for y in (.15,.50,.85):cylinder(s,'ladder rung',(-.25,y,0),(.25,y,0),.025,STEEL,8)
    for x in (-.25,.25):cylinder(s,'ladder bracket',(x,.5,0),(x,.5,.16),.022,TRIM,8)
    for name,s in pieces.items():export_scene(s,name+'.json',{'id':name})
    review=new_scene('BWR | scalable condensate tank preview')
    def inst(name,scale,offset):
        for source in pieces[name].objects:
            o=source.copy();o.data=source.data.copy();review.collection.objects.link(o)
            for v in o.data.vertices:
                p=game(v.co);v.co=xyz(tuple(p[a]*scale[a]+offset[a] for a in range(3)))
    d,h=7,8
    inst('base',(d,1,d),(0,0,0));inst('wall',(d,h-1.48,d),(0,.18,0));inst('roof',(d,1,d),(0,h-1.3,0))
    inst('port',(1,1,1),(0,1.5,-d/2))
    for y in range(h-1):inst('ladder',(1,1,1),(.7,y+.2,-math.sqrt((.47*d)**2-.7**2)-.05))
    studio(review,(0,h*.46,0),(d*2,h*1.1,-d*2),h*1.5,(1200,1200))
    review.render.filepath=str(OUT/'condensate_tank.png')
    try:review.render.engine='CYCLES';review.cycles.samples=24
    except TypeError:pass
    bpy.data.libraries.write(str(OUT/'condensate_tank.blend'),set(pieces.values())|{review},fake_user=True)
    viewport(review,(0,h*.46,0),(d*2,h*1.1,-d*2),h*1.5)
    bpy.ops.render.render(write_still=True)
