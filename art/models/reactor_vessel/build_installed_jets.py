"""Blender-authored modular BWR jet assemblies. Game-scale artwork, not plant CAD.
Only the plain straight pipe piece changes length. All bends, diffusers, suction
mouths, braces and fasteners retain uniform proportions. Existing scenes are kept.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/reactor_vessel/installed_jet'
OUT.mkdir(parents=True,exist_ok=True)
SS=material('Jet brushed stainless',(.39,.45,.48),.72)
WELD=material('Jet weld bead',(.24,.29,.31),.70)
SUPPORT=material('Jet forged support',(.19,.23,.25),.65)
PX=.35
PZ=.12
RZ=-.20

def collar(s,name,x,y,z,r):
    sleeve(s,name,(x,y-.025,z),(x,y+.025,z),r+.018,r-.015,WELD,20)

def build_installed_jets():
    pieces={}
    def part(name):
        s=new_scene('RVU | proportioned jet '+name);pieces[name]=s;return s
    s=part('lower')
    for x in (-PX,PX):
        sleeve(s,'diffuser tail pipe',(x,0,PZ),(x,.22,PZ),.20,.165,SS,24)
        sleeve(s,'fixed diffuser taper',(x,.20,PZ),(x,1.63,PZ),.20,.102,SS,24,end_outer=.13)
        collar(s,'diffuser lower weld',x,.23,PZ,.20)
        collar(s,'diffuser upper weld',x,1.59,PZ,.13)
        sleeve(s,'support plate collar',(x,.04,PZ),(x,.13,PZ),.24,.166,SUPPORT,24)
    # Circular suction-side elbow feeds the common riser, with a fixed bend radius.
    path=[(0,.32,-.43),(0,.32,-.40)]
    path += [(0,.52-.20*math.cos(i*math.pi/24),-.40+.20*math.sin(i*math.pi/24)) for i in range(13)]
    path += [(0,1.63,RZ)]
    sweep(s,'round lower riser elbow',path,.14,SS,16)
    sleeve(s,'riser inlet connection',(0,.32,-.46),(0,.32,-.42),.18,.108,SUPPORT,24)
    collar(s,'riser upper weld',0,1.59,RZ,.14)
    s=part('straight')
    for x in (-PX,PX):sleeve(s,'plain mixer tube',(x,0,PZ),(x,1,PZ),.13,.105,SS,20)
    sleeve(s,'plain riser pipe',(0,0,RZ),(0,1,RZ),.14,.115,SS,20)
    s=part('upper')
    for side in (-1,1):
        x=side*PX
        sleeve(s,'mixer mouth neck',(x,-.02,PZ),(x,.16,PZ),.13,.105,SS,24)
        sleeve(s,'flared suction bell',(x,.14,PZ),(x,.29,PZ),.13,.105,SS,24,end_outer=.19)
        collar(s,'inlet lip',x,.29,PZ,.19)
        # Smooth branch from shared riser to each down-pointing drive nozzle.
        a=Vector((0,.48,RZ));b=Vector((0,.91,RZ));c=Vector((x,.91,PZ));d=Vector((x,.43,PZ))
        path=[tuple((1-t)**3*a+3*(1-t)**2*t*b+3*(1-t)*t*t*c+t**3*d) for t in [i/20 for i in range(21)]]
        sweep(s,'fixed radius inlet gooseneck',path,.095,SS,16)
        sleeve(s,'driving nozzle',(x,.43,PZ),(x,.30,PZ),.105,.057,SS,20,end_outer=.075)
        box(s,'hold down lug',(x-.07,.72,PZ-.13),(x+.07,.82,PZ+.12),SUPPORT)
        bolt(s,'hold down stud',(x,.80,PZ),(x,.94,PZ),.030)
    sleeve(s,'common riser header',(0,-.02,RZ),(0,.55,RZ),.14,.112,SS,24)
    box(s,'hold down beam',(-.50,.78,-.015),(.50,.84,.105),SS)
    s=part('brace')
    for x in (-PX,PX):
        sleeve(s,'mixer restrainer',(x,-.04,PZ),(x,.04,PZ),.164,.133,SUPPORT,20)
        bolt(s,'restrainer bolt',(x-.05,0,PZ+.16),(x-.05,0,PZ+.21),.021)
    sleeve(s,'riser brace collar',(0,-.04,RZ),(0,.04,RZ),.177,.143,SUPPORT,20)
    box(s,'restrainer cross beam',(-.48,-.032,-.035),(.48,.032,.025),SS)
    box(s,'riser restraint bridge',(-.055,-.032,RZ),(.055,.032,.10),SS)
    for name,scene in pieces.items():
        export_scene(scene,name+'.json',{'id':name,'base_height':1.6,'crown_height':1.0,'nominal_straight':2.5})
    bpy.data.libraries.write(str(OUT/'installed_jet_parts.blend'),set(pieces.values()),fake_user=True)
    return pieces

def fit(w,h,count):
    # Square-vessel review of the Java fit: rectangle clearance and neighbor spacing.
    radius=(.375+.448)/2
    def clear(s):
        return radius*w-.48*s>=.375*w+.018 and math.hypot(radius*w+.48*s,.61*s)<=.448*w-.018
    lo,hi=0,1.25
    for _ in range(30):
        mid=(lo+hi)/2
        if clear(mid):lo=mid
        else:hi=mid
    if count>1:lo=min(lo,(2*radius*w*math.sin(math.pi/count)-.035)/(2*math.hypot(.61,.48)))
    target=2+.72*(h-8.15)-1.72
    scale=min(lo,target/4.0)
    length=max(1.4,min(3.4,target/scale-2.6))
    return scale,length,radius

def review(parts):
    s=new_scene('RVU | realistic jet proportions')
    for center,w,h,n in [(-10,7,10,12),(5,17,22,12)]:
        scale,length,radius=fit(w,h,n);top=h-6.15
        sleeve(s,'core shroud',(center,1.7,0),(center,top+.15,0),.375*w,.369*w,NAVY,96)
        for y in [1.7,top+.15]:sleeve(s,'shroud rim',(center,y-.05,0),(center,y+.05,0),.375*w,.369*w,STEEL,96)
        cylinder(s,'lower plenum support',(center,1.5,0),(center,1.7,0),.448*w,CONCRETE,96)
        for i in range(n):
            a=i*math.tau/n;x=radius*w*math.cos(a);z=radius*w*math.sin(a);yaw=math.atan2(x,z)
            for name,offset,ys in [('lower',0,1),('straight',1.6,length),('upper',1.6+length,1),('brace',1.6+length*.42,1)]:
                for source in parts[name].objects:
                    o=source.copy();o.data=source.data.copy();s.collection.objects.link(o)
                    o.name=f'{w} wide | jet {i+1} | '+source.name
                    for v in o.data.vertices:
                        gx,gy,gz=game(v.co);gx*=scale;gz*=scale
                        v.co=xyz((center+x+gx*math.cos(yaw)+gz*math.sin(yaw),1.72+(offset+gy*ys)*scale,z-gx*math.sin(yaw)+gz*math.cos(yaw)))
    studio(s,(-1,7,0),(28,25,-35),35,(1400,950))
    s.render.filepath=str(OUT/'proportioned_jets.png')
    bpy.data.libraries.write(str(OUT/'proportioned_jets.blend'),{s},fake_user=True)
    viewport(s,(-1,7,0),(28,25,-35),35)
    return s
