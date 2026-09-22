"""Original Blender cooling-water plant models. One unit is one Minecraft block.
References and gameplay scale choices are documented in COOLING-WATER.md.
Run build_natural(), build_mechanical(), build_pump(False), build_pump(True), build_intake().
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_ports/build_models.py'
exec(compile(helper.read_text(),str(helper),'exec'))
OUT=ROOT/'art/models/cooling';OUT.mkdir(parents=True,exist_ok=True)
TOWER=material('cooling tower concrete',(.40,.42,.40),.05)
FILL=material('cooling tower fill',(.10,.13,.12),.15)
WATER=material('cooling basin water',(.025,.20,.27),.25)
PUMP_BLUE=material('circulating water pump blue',(.018,.12,.28),.35)

def ring(s,name,c,y,r,t,mat,n=64):
    # Separate sectors keep sparse world occupancy around the circumference.
    for i in range(n):
        angles=[i*math.tau/n,(i+1)*math.tau/n]
        vs=[(c+radius*math.cos(a),yy,c+radius*math.sin(a)) for yy in (y,y+t) for radius in (r-.16,r) for a in angles]
        mesh_object(s,name,vs,[(0,1,3,2),(4,6,7,5),(0,4,5,1),(2,3,7,6),(0,2,6,4),(1,5,7,3)],mat)

def water_port(s,name,p,d,mat=BLUE):
    p=Vector(p);d=Vector(d)
    sleeve(s,name+' spool',p-d*.85,p-d*.12,.25,.205,STEEL,20)
    sleeve(s,name+' band',p-d*.42,p-d*.31,.265,.245,mat,20)
    steam_flange(s,name,p,d)

def power_port(s,cell):
    x,y,z=cell
    box(s,'electrical connection',(x,y+.13,z+.13),(x+1,y+.87,z+.87),BODY)
    box(s,'electrical terminal',(x+.95,y+.25,z+.25),(x+1,y+.75,z+.75),AMBER)

def ports_for(s,size,controller,ports):
    for p in ports:
        if p['role']=='POWER':power_port(s,p['cell'])
        else:
            center=Vector(p['cell'])+Vector((.5,.5,.5))+Vector(DIRECTIONS[p['face']])*.5
            water_port(s,p['role'],center,DIRECTIONS[p['face']],HOT if p['role']=='INLET' and size[1]>7 else BLUE)
    return {'size':size,'controller':controller,'ports':ports}

def save(s,id,meta,eye,scale):
    meta['id']=id;s['layout']=json.dumps(meta);export_scene(s,id+'.json',meta)
    r=new_scene('BWR | '+id+' studio')
    for o in s.objects:r.collection.objects.link(o)
    target=(meta['size'][0]/2,meta['size'][1]*.45,meta['size'][2]/2)
    studio(r,target,eye,scale,(1400,1200));viewport(r,target,eye,scale*1.15)
    bpy.data.libraries.write(str(OUT/(id+'.blend')),{s,r},fake_user=True)
    r.render.filepath=str(OUT/(id+'.png'));bpy.ops.render.render(write_still=True,scene=r.name)
    return r

def tower_ports(s,size):
    w,h,d=size;c=w//2
    sleeve(s,'warm water header',(c+.5,2.5,.84),(c+.5,2.5,d/2),.25,.205,STEEL,20)
    sleeve(s,'warm water riser',(c+.5,2.5,d/2),(c+.5,4.2,d/2),.25,.205,STEEL,20)
    sleeve(s,'cold basin outlet',(c+.5,1.5,d/2),(c+.5,1.5,d-.84),.25,.205,STEEL,20)
    sleeve(s,'basin suction',(c+.5,.35,d/2),(c+.5,1.5,d/2),.25,.205,STEEL,20)
    sleeve(s,'makeup header',(.84,1.5,d/2),(w/2,1.5,d/2),.25,.205,STEEL,20)
    ps=[{'role':'INLET','cell':[c,2,0],'face':'north'},
        {'role':'OUTLET','cell':[c,1,d-1],'face':'south'},
        {'role':'MAKEUP','cell':[0,1,d//2],'face':'west'}]
    if h<15:ps.append({'role':'POWER','cell':[w-1,1,d//2],'face':'east'})
    return ports_for(s,size,[c,0,d//2],ps)

def build_natural():
    s=new_scene('BWR | natural_draft_tower');c=12.5
    # Basin floor, water surface and circular perimeter. Root is in the basin floor.
    cylinder(s,'cold water basin',(c,.05,c),(c,.25,c),11.8,CONCRETE,64)
    cylinder(s,'basin water',(c,.26,c),(c,.32,c),11.5,WATER,64)
    ring(s,'basin rim',c,.25,11.95,.55,TOWER)
    for i in range(40):
        a=i*math.tau/40;b=(i+.7)*math.tau/40
        for aa in (a,b):cylinder(s,'inclined support',(c+11.25*math.cos(aa),.55,c+11.25*math.sin(aa)),(c+10.6*math.cos((a+b)/2),4.0,c+10.6*math.sin((a+b)/2)),.16,TOWER,8)
    levels=[(4+31.5*j/28,6.1+4.7*((j/28-.68)/.68)**2) for j in range(29)]
    for (y,r),(Y,R) in zip(levels,levels[1:]):
        for i in range(64):
            aa=[i*math.tau/64,(i+1)*math.tau/64]
            vs=[(c+(rr-shrink)*math.cos(a),yy,c+(rr-shrink)*math.sin(a)) for yy,rr in ((y,r),(Y,R)) for shrink in (0,.20) for a in aa]
            mesh_object(s,'hyperbolic concrete shell',vs,[(0,1,5,4),(3,2,6,7),(0,2,3,1),(4,5,7,6),(0,4,6,2),(1,3,7,5)],TOWER)
    ring(s,'top coping',c,35.48,levels[-1][1]+.07,.30,TOWER)
    # Visible fill and distributor within the open intake level.
    for x in range(4,22):
        half=math.sqrt(max(0,8.7**2-(x-c)**2))
        if half>0:box(s,'fill pack',(x-.12,3.35,c-half),(x+.12,3.8,c+half),FILL)
    cylinder(s,'water distributor',(c,3.3,c),(c,5,c),.32,STEEL,16)
    for i in range(8):
        a=i*math.tau/8;cylinder(s,'radial spray header',(c,4,c),(c+8.3*math.cos(a),4,c+8.3*math.sin(a)),.11,STEEL,10)
    for y in [1+i*.30 for i in range(14)]:cylinder(s,'access rung',(c-.32,y,1.05),(c+.32,y,1.05),.035,STEEL,8)
    meta=tower_ports(s,[25,36,25]);save(s,'natural_draft_tower',meta,(46,30,-32),54)

def fan_blades(s):
    cylinder(s,'fan hub',(0,-.10,0),(0,.18,0),.23,STEEL,16)
    for i in range(6):
        a=i*math.tau/6;points=[]
        for radius,angle,y in ((.18,a-.18,0),(1.34,a-.05,.11),(1.34,a+.17,-.01),(.25,a+.4,-.08)):
            points.append((radius*math.cos(angle),y,radius*math.sin(angle)))
        mesh_object(s,'fan blade',points,[(0,1,2,3),(3,2,1,0)],BODY)

def build_mechanical():
    s=new_scene('BWR | mechanical_draft_tower');c=8.5
    cylinder(s,'circular basin',(c,.04,c),(c,.26,c),7.95,CONCRETE,64)
    cylinder(s,'basin surface',(c,.27,c),(c,.33,c),7.65,WATER,64)
    ring(s,'basin rim',c,.25,8,.6,TOWER)
    ring(s,'fan deck perimeter',c,5.85,7.9,.30,TOWER)
    for i in range(48):
        a=i*math.tau/48
        cylinder(s,'concrete support',(c+7.6*math.cos(a),.7,c+7.6*math.sin(a)),(c+7.6*math.cos(a),5.95,c+7.6*math.sin(a)),.10,TOWER,8)
        for y in (1.25,1.8,2.35,2.9,3.45,4.0,4.55,5.1):
            b=a+math.tau/48
            vs=[(c+r*math.cos(aa),yy,c+r*math.sin(aa)) for r,yy in ((7.65,y),(7.4,y+.32)) for aa in (a,b)]
            mesh_object(s,'air inlet louver',vs,[(0,1,3,2),(2,3,1,0)],BODY)
    # Six wedge-shaped cooling cells and axial fans in a circular arrangement.
    rotors=[]
    for i in range(6):
        a=i*math.tau/6;x=c+4.9*math.cos(a);z=c+4.9*math.sin(a)
        box(s,'fill cell',(x-1.6,1.1,z-1.6),(x+1.6,4.7,z+1.6),FILL)
        sleeve(s,'fan stack',(x,5.8,z),(x,7.7,z),1.57,1.40,TOWER,32)
        sleeve(s,'fan stack lip',(x,7.55,z),(x,7.8,z),1.67,1.40,TOWER,32)
        cylinder(s,'gearbox',(x,6.1,z),(x,6.6,z),.25,PUMP_BLUE,12)
        cylinder(s,'fan bridge',(x-1.5,6.30,z),(x+1.5,6.30,z),.05,STEEL,8)
        rotors.append([x,6.55,z])
        cylinder(s,'hot distributor',(c,4.85,c),(x,4.85,z),.15,STEEL,12)
        for j in range(6):box(s,'drift eliminator',(x-1.3+j*.48,5,z-1.3),(x-1.19+j*.48,5.3,z+1.3),BODY)
    for y in [.45+i*.28 for i in range(20)]:cylinder(s,'service ladder',(c-.32,y,.82),(c+.32,y,.82),.03,STEEL,8)
    for x in (c-.4,c+.4):cylinder(s,'ladder rails',(x,.3,.82),(x,6,.82),.04,STEEL,8)
    meta=tower_ports(s,[17,8,17]);meta['rotors']=rotors
    # Template is exported separately for six animated instances in the renderer.
    rotor=new_scene('BWR | cooling fan rotor');fan_blades(rotor)
    export_scene(rotor,'cooling_fan.json',{'id':'cooling_fan','size':[3,1,3],'controller':[0,0,0],'ports':[]})
    # Render-only copies show the same blades in the editable studio.
    r=save(s,'mechanical_draft_tower',meta,(30,20,-20),25)
    for p in rotors:
        for o in rotor.objects:
            clone=o.copy();clone.location+=xyz(p);r.collection.objects.link(clone)
    bpy.data.libraries.write(str(OUT/'mechanical_draft_tower.blend'),{s,r,rotor},fake_user=True)
    bpy.ops.render.render(write_still=True,scene=r.name)

def build_pump(small=False):
    id='makeup_water_pump' if small else 'circulating_water_pump';s=new_scene('BWR | '+id)
    w,h=(3,5) if small else (5,8);c=w/2;k=.6 if small else 1
    box(s,'grouted skid',(.12,.04,.12),(w-.12,.32,w-.12),CONCRETE)
    cylinder(s,'suction can',(c,.3,c),(c,2.5*k,c),.90*k,PUMP_BLUE,32)
    cylinder(s,'pump column',(c,2*k,c),(c,4.5*k,c),.52*k,PUMP_BLUE,28)
    for y in (1,2.2,3.2,4.4):scaled_flange(s,'column flange',(c,y*k,c),(0,1,0),.65*k,.1,PUMP_BLUE,12)
    box(s,'motor support',(c-.85*k,4.5*k,c-.85*k),(c+.85*k,4.75*k,c+.85*k),BODY)
    cylinder(s,'motor frame',(c,4.75*k,c),(c,7.4*k,c),.82*k,PUMP_BLUE,32)
    cylinder(s,'motor fan cover',(c,7.4*k,c),(c,7.75*k,c),.86*k,BODY,32)
    for i in range(24):
        a=i*math.tau/24;cylinder(s,'motor cooling rib',(c+.84*k*math.cos(a),5*k,c+.84*k*math.sin(a)),(c+.84*k*math.cos(a),7.3*k,c+.84*k*math.sin(a)),.025*k,STEEL,6)
    iy=1.5;oy=2.5 if small else 3.5;py=3 if small else 5
    sleeve(s,'suction header',(c,iy,.84),(c,iy,c),.25,.205,PUMP_BLUE,24)
    sleeve(s,'discharge elbow',(c,oy,c),(c,oy,w-.84),.25,.205,PUMP_BLUE,24)
    box(s,'terminal mounting bracket',(c+.55*k,py+.3,c-.14),(w-.6,py+.43,c+.14),BODY)
    for z in (.4,w-.4):
        for x in (.4,w-.4):bolt(s,'skid anchor',(x,.25,z),(x,.46,z),.07*k)
    ps=[{'role':'INLET','cell':[int(c),1,0],'face':'north'},{'role':'OUTLET','cell':[int(c),int(oy),w-1],'face':'south'},
        {'role':'POWER','cell':[w-1,py,int(c)],'face':'east'}]
    meta=ports_for(s,[w,h,w],[int(c),0,int(c)],ps);save(s,id,meta,(w*2.3,h*1.1,-w*2),h*1.4)

def build_intake():
    s=new_scene('BWR | screened_water_intake');c=1.5
    cylinder(s,'screen base',(c,.08,c),(c,.23,c),1.12,STEEL,32)
    cylinder(s,'screen lid',(c,1.65,c),(c,1.8,c),1.12,STEEL,32)
    for y in (.26,.55,.85,1.15,1.55):sleeve(s,'screen hoop',(c,y,c),(c,y+.04,c),1.10,1.07,STEEL,32)
    for i in range(40):
        a=i*math.tau/40;cylinder(s,'intake screen bar',(c+1.07*math.cos(a),.23,c+1.07*math.sin(a)),(c+1.07*math.cos(a),1.65,c+1.07*math.sin(a)),.025,STEEL,6)
    cylinder(s,'suction bell',(c,.24,c),(c,1.35,c),.45,PUMP_BLUE,24)
    sleeve(s,'outlet spool',(c,1.5,c),(c,1.5,2.16),.25,.205,PUMP_BLUE,24)
    meta=ports_for(s,[3,2,3],[1,0,1],[{'role':'OUTLET','cell':[1,1,2],'face':'south'}])
    save(s,'screened_water_intake',meta,(7,4,-6),5)
