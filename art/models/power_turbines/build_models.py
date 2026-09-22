"""Blender 5: TX-10 / TX-NLCH inspired exterior modules, metres = blocks.

Run through Blender MCP. Only creates new scenes, preserving existing work.
Exports editable scenes and triangulated meshes for tools-export-power-turbines.py.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
# Share the existing Blender mesh/UV and hollow flange authoring primitives.
helper = ROOT / 'art/models/modern/build_models.py'
exec(compile(helper.read_text().split('def build_pump(id):')[0], str(helper), 'exec'))
OUT = ROOT / 'art/models/power_turbines'
OUT.mkdir(parents=True, exist_ok=True)
CASING = material('turbine warm enamel', (.57,.60,.57), .15)
BASE = material('cast lower casing', (.19,.24,.27), .4)
RIB = material('flange silver', (.39,.44,.45), .55)
GEN = material('generator mint enamel', (.30,.51,.45), .18)
COPPER = material('terminal copper', (.5,.21,.065), .65)

def box(s, name, low, high, mat):
    x,y,z=low; X,Y,Z=high
    return mesh_object(s,name,[(x,y,z),(X,y,z),(X,Y,z),(x,Y,z),(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)],
                       [(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)],mat)

def cylinder(s,name,a,b,r,mat,segments=24):
    a,b=Vector(a),Vector(b);d,u,v=basis(b-a)
    vs=[tuple(p+r*(math.cos(i*math.tau/segments)*u+math.sin(i*math.tau/segments)*v)) for p in (a,b) for i in range(segments)]
    fs=[tuple(range(segments-1,-1,-1)),tuple(range(segments,2*segments))]
    fs += [(i,(i+1)%segments,(i+1)%segments+segments,i+segments) for i in range(segments)]
    return mesh_object(s,name,vs,fs,mat)

def hood(s,name,cx,y,z0,z1,r,mat):
    # Semicircular exhaust hood above its horizontal split joint.
    arc=[(cx+r*math.cos(i*math.pi/20),y+r*math.sin(i*math.pi/20)) for i in range(21)]
    vs=[(x,Y,z) for z in (z0,z1) for x,Y in arc]
    fs=[tuple(range(20,-1,-1)),tuple(range(21,42))]
    fs += [(i,i+1,i+22,i+21) for i in range(20)]
    fs.append((20,0,21,41))
    return mesh_object(s,name,vs,fs,mat)

def port(s,name,p,d,start,mat):
    p=Vector(p);d=Vector(d)
    sleeve(s,name+' neck',start,p-d*.13,.29,.205,mat)
    sleeve(s,name+' identification band',p-d*.35,p-d*.25,.30,.205,AMBER if 'steam' in name else BLUE)
    flange(s,name,p,d)

def build(id,w,h,depth):
    s=new_scene('BWR | '+id);cx=w/2;cz=depth/2
    box(s,'foundation skid',(.12,.04,.12),(w-.12,.28,depth-.12),BASE)
    for x in (.25,w-.55): box(s,'longitudinal foundation rail',(x,.28,.28),(x+.3,.55,depth-.28),RIB)
    cylinder(s,'continuous steel shaft',(cx,2.5,.015),(cx,2.5,depth-.015),.17,STEEL)
    for z in (.16,depth-.16):
        cylinder(s,'bolted shaft coupling',(cx,2.5,max(.005,z-.12)),(cx,2.5,min(depth-.005,z+.12)),.34,STEEL)
        for i in range(8):
            a=i*math.tau/8
            cylinder(s,'coupling stud',(cx+.265*math.cos(a),2.5+.265*math.sin(a),max(.003,z-.135)),(cx+.265*math.cos(a),2.5+.265*math.sin(a),min(depth-.003,z+.135)),.025,DARK,6)
    for z in (.7,depth-.7):
        box(s,'bearing pedestal',(cx-.5,.5,z-.32),(cx+.5,2.3,z+.32),BASE)
        cylinder(s,'split bearing housing',(cx,2.5,z-.38),(cx,2.5,z+.38),.45,RIB)
        box(s,'bearing inspection cover',(cx-.22,2.82,z-.22),(cx+.22,3,z+.22),CASING)
    ports=[]
    if id=='hp_turbine':
        cylinder(s,'double-flow HP casing',(cx,2.5,1.5),(cx,2.5,depth-1.5),1.25,CASING,32)
        box(s,'HP horizontal split flange',(cx-1.38,2.42,1.4),(cx+1.38,2.59,depth-1.4),RIB)
        for z in (1.5,2.1,3.1,4.5,5.9,6.9,7.5):
            sleeve(s,'casing reinforcing ring',(cx,2.5,z-.09),(cx,2.5,z+.09),1.30,1.22,RIB,32)
        for z in [1.6+i*.43 for i in range(14)]:
            for x in (cx-1.3,cx+1.3): cylinder(s,'split-line bolt',(x,2.59,z),(x,2.70,z),.06,STEEL,6)
        box(s,'steam chest',(cx-.48,3.3,cz-.65),(cx+.48,4.2,cz+.65),CASING)
        port(s,'main steam inlet',(cx,h,cz),(0,1,0),(cx,4.05,cz),CASING)
        port(s,'HP exhaust steam',(0,1.5,cz),(-1,0,0),(cx-.85,1.5,cz),CASING)
        ports=[('STEAM_INLET',[w//2,h-1,depth//2],'up'),('STEAM_EXHAUST',[0,1,depth//2],'west')]
    elif id=='lp_turbine':
        box(s,'large lower exhaust casing',(.5,.45,1.25),(w-.5,2.15,depth-1.25),BASE)
        hood(s,'double-flow LP exhaust hood',cx,2.12,1.3,depth-1.3,2.22,CASING)
        box(s,'LP horizontal split flange',(.4,2.08,1.2),(w-.4,2.23,depth-1.2),RIB)
        for z in (1.3,2,3.5,5,5.7): hood(s,'hood stiffener',cx,2.12,z-.055,z+.055,2.27,RIB)
        for x in (.7,w-.95):
            for z in (1.5,2.5,3.5,4.5,5.5): box(s,'exhaust casing rib',(x,.5,z-.06),(x+.25,2.05,z+.06),RIB)
        port(s,'LP crossover steam inlet',(cx,h,cz),(0,1,0),(cx,4.15,cz),CASING)
        ports=[('STEAM_INLET',[w//2,h-1,depth//2],'up')]
    else:
        box(s,'stator bed',(cx-1.55,.55,1.2),(cx+1.55,1.4,depth-1.2),BASE)
        cylinder(s,'long TX-NLCH stator barrel',(cx,2.5,1.3),(cx,2.5,depth-1.3),1.3,GEN,32)
        for z in (1.4,depth-1.4):
            cylinder(s,'end shield',(cx,2.5,z-.13),(cx,2.5,z+.13),1.38,RIB,32)
            hood(s,'end cooler hood',cx,2.5,z+.22,z+.9 if z<cz else z+.45,1.85,GEN)
        for z in (2.2,3,3.8,4.6,5.4,6.2,7):
            for x in (cx-1.6,cx+1.45): box(s,'stator support rib',(x,.62,z),(x+.15,1.9,z+.10),RIB)
        box(s,'exciter cabinet',(cx+.95,.55,depth-2.6),(w-.18,2.35,depth-.8),GEN)
        box(s,'generator terminal housing',(w-.75,1.14,cz-.36),(w,1.86,cz+.36),BASE)
        box(s,'FE power terminal',(w-.08,1.25,cz-.25),(w,1.75,cz+.25),COPPER)
        for z in (cz-1,cz+1):
            cylinder(s,'cooling manifold',(cx-1.55,1.3,z),(cx-1.55,3.1,z),.075,STEEL,10)
    meta={'id':id,'size':[w,h,depth],'controller':[w//2,0,depth//2],
          'ports':[{'role':r,'cell':c,'face':f} for r,c,f in ports],
          'shaft_height':2,'shaft_axis':'north/south','authoring':'Blender 5 / TX-10 and TX-NLCH inspired; game scale, not a CAD replica'}
    export_scene(s,id+'.json',meta)
    return s

MODULES=[build('hp_turbine',5,5,9),build('lp_turbine',7,5,7),build('nuclear_generator',5,5,9)]
bpy.data.libraries.write(str(OUT/'power_turbines.blend'),set(MODULES),fake_user=True)
# Frame a complete train in a separate review scene, without changing authored coordinates.
gallery=new_scene('BWR | Modular main turbine review')
z=0
for source,depth in ((MODULES[0],9),(MODULES[1],7),(MODULES[1],7),(MODULES[1],7),(MODULES[2],9)):
    width=7 if source==MODULES[1] else 5
    for o in source.objects:
        clone=o.copy();gallery.collection.objects.link(clone);clone.location += xyz(((7-width)/2,0,z))
    z+=depth
for screen in bpy.data.screens:
    for area in screen.areas:
        if area.type=='VIEW_3D':
            area.spaces.active.region_3d.view_location=xyz((3.5,2,19.5))
            area.spaces.active.region_3d.view_distance=43
            from mathutils import Quaternion
            area.spaces.active.region_3d.view_rotation=Quaternion((.86,.32,.15,.37)).normalized()
print('Created three editable modules plus train review scene; previous scenes preserved.')
