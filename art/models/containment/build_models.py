"""Build editable containment doors in Blender and export triangulated source meshes.

Run with Blender --background --factory-startup --python build_models.py.
The game OBJ assets are baked from these JSON sources by tools-export-containment.py.
"""
import bpy
import math
import sys
from pathlib import Path
from mathutils import Vector

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'art/models/modern'))
import build_models as helpers
from build_models import new_scene, mesh_object, material, export_scene, xyz

OUT=ROOT/'art/models/containment'
PREVIEW=ROOT/'tmp'
OUT.mkdir(parents=True,exist_ok=True)
PREVIEW.mkdir(parents=True,exist_ok=True)
helpers.OUT=OUT

FRAME=material('graphite frame',(.12,.16,.19),.72)
LEAF=material('painted armored leaf',(.18,.29,.33),.6)
TRIM=material('brushed alloy',(.52,.58,.6),.7)
SEAL=material('black elastomer seal',(.025,.035,.04),.05)
WARN=material('safety ochre',(.74,.36,.045),.3)
HINGE=material('hinge housing',(.24,.29,.3),.8)
STEAM=material('steam band',(.75,.36,.07),.35)
WATER=material('water band',(.04,.38,.68),.35)
POWER=material('electrical band',(.83,.66,.09),.35)
DATA=material('data band',(.15,.72,.50),.35)

def box(scene,name,lo,hi,mat):
    x,y,z=lo;X,Y,Z=hi
    vs=[(x,y,z),(X,y,z),(X,Y,z),(x,Y,z),(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)]
    fs=[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]
    return mesh_object(scene,name,vs,fs,mat)

def cylinder(scene,name,a,b,r,mat,segments=16):
    a,b=Vector(a),Vector(b);axis=(b-a).normalized()
    side=axis.cross(Vector((0,1,0)) if abs(axis.y)<.9 else Vector((1,0,0))).normalized();other=axis.cross(side)
    vs=[tuple(p+r*(math.cos(i*math.tau/segments)*side+math.sin(i*math.tau/segments)*other))
        for p in (a,b) for i in range(segments)]
    fs=[tuple(range(segments-1,-1,-1)),tuple(range(segments,2*segments))]
    fs += [(i,(i+1)%segments,(i+1)%segments+segments,i+segments) for i in range(segments)]
    return mesh_object(scene,name,vs,fs,mat)

def ring(scene,name,z0,z1,outer,inner,mat,segments=24):
    vs=[]
    for z,r in ((z0,outer),(z1,outer),(z1,inner),(z0,inner)):
        for i in range(segments):
            t=i*math.tau/segments
            vs.append((.5+r*math.cos(t),.5+r*math.sin(t),z))
    fs=[(a*segments+i,a*segments+(i+1)%segments,((a+1)%4)*segments+(i+1)%segments,((a+1)%4)*segments+i)
        for a in range(4) for i in range(segments)]
    return mesh_object(scene,name,vs,fs,mat)

def vault(size):
    frame=new_scene('Vault %d frame'%size)
    leaf=new_scene('Vault %d leaf'%size)
    # The wall plane is XY in game coordinates; exterior is toward negative Z.
    box(frame,'left structural jamb',(0,0,.12),(.26,size,.89),FRAME)
    box(frame,'right structural jamb',(size-.26,0,.12),(size,size,.89),FRAME)
    box(frame,'lintel',(.18,size-.26,.12),(size-.18,size,.89),FRAME)
    box(frame,'threshold',(.18,0,.12),(size-.18,.26,.89),FRAME)
    for mat,z0,z1,outer,inner,label in ((SEAL,.15,.27,.255,.34,'rubber compression seal'),
                                         (FRAME,.14,.21,.34,.39,'recessed inner lip')):
        box(frame,label+' left',(outer,outer,z0),(inner,size-outer,z1),mat)
        box(frame,label+' right',(size-inner,outer,z0),(size-outer,size-outer,z1),mat)
        box(frame,label+' bottom',(inner,outer,z0),(size-inner,inner,z1),mat)
        box(frame,label+' top',(inner,size-inner,z0),(size-inner,size-outer,z1),mat)
    for y in (.55,size/2,size-.55):
        cylinder(frame,'hinge pin',(0.17,y-.24,.40),(.17,y+.24,.40),.105,HINGE)
        box(frame,'hinge bracket',(.10,y-.09,.23),(.5,y+.09,.59),TRIM)
    for x in (.12,size-.12):
        for y in (.12,size-.12):
            cylinder(frame,'frame anchor',(x,y,.03),(x,y,.10),.043,TRIM,10)
    box(leaf,'armored slab',(.27,.28,.27),(size-.28,size-.28,.52),LEAF)
    box(leaf,'outer rolled edge',(.27,.28,.18),(size-.28,size-.28,.27),TRIM)
    box(leaf,'center armor panel',(.43,.47,.12),(size-.48,size-.47,.18),LEAF)
    for y in (.6,size/2,size-.6):
        box(leaf,'radial dog bar',(.42,y-.065,.055),(size-.37,y+.065,.13),TRIM)
        box(leaf,'painted lock indicator',(size-.55,y-.075,.045),(size-.38,y+.075,.058),WARN)
    cylinder(leaf,'handwheel hub',(size-.87,size/2,.005),(size-.87,size/2,.15),.14,HINGE)
    for i in range(8):
        angle=i*math.tau/8
        x=size-.87+.21*math.cos(angle);y=size/2+.21*math.sin(angle)
        cylinder(leaf,'handwheel spoke',(size-.87,size/2,-.003),(x,y,-.003),.024,TRIM,8)
        cylinder(leaf,'handwheel grip',(x,y,-.055),(x,y,.03),.026,TRIM,8)
    export_scene(frame,f'vault_{size}_frame.json',{'size':[size,size,1],'part':'frame'})
    export_scene(leaf,f'vault_{size}_leaf.json',{'size':[size,size,1],'part':'leaf'})
    render_parts('vault_'+str(size),[frame,leaf],size,size,1)

def sleeve_model(service,band):
    scene=new_scene('Containment '+service+' sleeve')
    box(scene,'welded sleeve plate',(.06,.06,.12),(.94,.94,.88),FRAME)
    for z in (.02,.90):
        box(scene,'reinforcing face plate',(.12,.12,z),(.88,.88,z+.08),TRIM)
        if service in ('steam','water','pass_through'):
            ring(scene,'flange',z-.05,z+.09,.345,.22,TRIM)
            ring(scene,'bore gasket',z-.051,z-.025,.225,.18,SEAL)
            ring(scene,'process identification',z+.10,z+.14,.28,.23,band)
            for i in range(8):
                t=i*math.tau/8;x=.5+.31*math.cos(t);y=.5+.31*math.sin(t)
                cylinder(scene,'flange bolt',(x,y,z-.065),(x,y,z+.105),.027,HINGE,8)
        else:
            box(scene,'electrical receptacle',(.30,.28,z-.065),(.70,.72,z+.11),HINGE)
            box(scene,'insulating trim',(.34,.32,z-.068),(.66,.68,z-.055),band)
            for x in (.41,.5,.59):
                cylinder(scene,'sealed contact',(x,.5,z-.09),(x,.5,z-.06),.023,TRIM,8)
        for x in (.17,.83):
            for y in (.17,.83):cylinder(scene,'plate bolt',(x,y,z-.013),(x,y,z+.095),.026,HINGE,8)
    source='sleeve_'+service
    export_scene(scene,source+'.json',{'size':[1,1,1],'part':'sleeve','service':service})
    render_parts(source,[scene],1,1,1)

def render_parts(label,parts,width,height,depth):
    preview=new_scene(label+' preview')
    for source in parts:
        for ob in source.objects: preview.collection.objects.link(ob)
    preview.render.engine='CYCLES';preview.cycles.samples=16
    preview.render.resolution_x=640;preview.render.resolution_y=640;preview.render.resolution_percentage=100
    preview.render.image_settings.file_format='PNG'
    preview.world=bpy.data.worlds.new(label+' world')
    preview.world.use_nodes=True
    preview.world.node_tree.nodes['Background'].inputs['Color'].default_value=(.20,.25,.29,1)
    preview.world.node_tree.nodes['Background'].inputs['Strength'].default_value=.65
    light_data=bpy.data.lights.new(label+' softbox','AREA');light_data.energy=1400;light_data.shape='DISK';light_data.size=7
    light=bpy.data.objects.new(label+' light',light_data);preview.collection.objects.link(light)
    light.location=xyz((width/2+1,height+3,-4))
    target=Vector(xyz((width/2,height/2,depth/2)))
    light.rotation_euler=(target-light.location).to_track_quat('-Z','Y').to_euler()
    cam_data=bpy.data.cameras.new(label+' camera');cam_data.type='ORTHO';cam_data.ortho_scale=max(width,height,depth)*1.35
    camera=bpy.data.objects.new(label+' camera',cam_data);preview.collection.objects.link(camera);preview.camera=camera
    positions={'front':(width/2,height/2,-max(width,height,depth)*3),'side':(-max(width,height,depth)*3,height/2,depth/2),
               'top':(width/2,height+max(width,height,depth)*3,depth/2)}
    for view,point in positions.items():
        camera.location=xyz(point);camera.rotation_euler=(target-camera.location).to_track_quat('-Z','Y').to_euler()
        preview.render.filepath=str(PREVIEW/(label+'-'+view+'.png'))
        bpy.context.window.scene=preview
        bpy.ops.render.render(write_still=True)

vault(3)
vault(4)
for service,band in (('steam',STEAM),('water',WATER),('pass_through',WARN),('power',POWER),('data',DATA)):
    sleeve_model(service,band)
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'containment_doors.blend'))
