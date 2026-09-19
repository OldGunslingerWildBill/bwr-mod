"""Run in Blender (MCP or Text Editor). Creates an isolated, editable DVSS scene.

Original game model inspired by Flowserve's DVSS cutaway; dimensions, motor,
grid elbows and colours are artistic choices. No manufacturer's mesh is used.
Minecraft coordinates are X/right, Y/up, Z/back. One unit = one game block.
"""
import bpy, bmesh, math, json
from pathlib import Path
from mathutils import Vector, Matrix

# MCP callers pass this saved script's path as __file__ in the exec globals.
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'art/models/dvss'
OUT.mkdir(parents=True, exist_ok=True)
SCENE = bpy.data.scenes.new('BWR | DVSS recirculation pump | 5x10x5')
bpy.context.window.scene = SCENE
COL = bpy.data.collections.new('DVSS | game geometry')
SCENE.collection.children.link(COL)
MATS = {}
for name, rgb, metal in [
    ('cast_steel',(.32,.39,.40),.5),('motor_green',(.08,.23,.19),.35),
    ('machined',(.64,.69,.69),.75),('dark',(.06,.085,.095),.35),
    ('brass',(.65,.43,.12),.65),('suction_blue',(.05,.43,.65),.3),
    ('discharge_teal',(.10,.65,.50),.3),('plate',(.8,.84,.79),.2)]:
    m=bpy.data.materials.new('DVSS | '+name); m.diffuse_color=(*rgb,1)
    m.use_nodes=True
    bsdf=next(n for n in m.node_tree.nodes if n.bl_idname=='ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value=(*rgb,1)
    bsdf.inputs['Metallic'].default_value=metal
    bsdf.inputs['Roughness'].default_value=.32 if name=='machined' else .47
    MATS[name]=m

def xyz(p): return Vector((p[0],-p[2],p[1]))
def finish(o,name,mat):
    o.name='DVSS | '+name
    for c in list(o.users_collection): c.objects.unlink(o)
    COL.objects.link(o);o.data.materials.append(MATS[mat]);return o
def cylinder(name,a,b,r,mat,n=24,r2=None):
    a,b=xyz(a),xyz(b);delta=b-a
    bpy.ops.mesh.primitive_cone_add(vertices=n,radius1=r,radius2=r if r2 is None else r2,
        depth=delta.length,location=(a+b)/2)
    o=bpy.context.object;o.rotation_euler=delta.to_track_quat('Z','Y').to_euler()
    return finish(o,name,mat)
def box(name,c,sz,mat):
    bpy.ops.mesh.primitive_cube_add(size=1,location=xyz(c))
    o=bpy.context.object;o.scale=(sz[0],sz[2],sz[1]);return finish(o,name,mat)
def mesh(name,vs,fs,mat):
    me=bpy.data.meshes.new(name);me.from_pydata([xyz(v) for v in vs],[],fs);me.update()
    bm=bmesh.new();bm.from_mesh(me);bmesh.ops.recalc_face_normals(bm,faces=bm.faces);bm.to_mesh(me);bm.free()
    o=bpy.data.objects.new('DVSS | '+name,me);COL.objects.link(o);me.materials.append(MATS[mat]);return o
def ring(name,y,r,thick,mat,n=32):
    return cylinder(name,(1.5,y-thick/2,1.5),(1.5,y+thick/2,1.5),r,mat,n)
def bolts(name,y,r,n=12,size=.052):
    for i in range(n):
        a=i*math.tau/n;x=1.5+r*math.cos(a);z=1.5+r*math.sin(a)
        cylinder(name+str(i),(x,y,z),(x,y+.075,z),size,'machined',6)
def nozzle(name,a,b,r,color,barrel=True):
    delta=(Vector(b)-Vector(a)).normalized()
    if barrel: cylinder(name+' barrel',a,Vector(b)-delta*.055,r,'cast_steel',32)
    start=Vector(b)-delta*.16;end=Vector(b)-delta*.015
    cylinder(name+' flange',start,end,r+.10,'machined',24)
    cylinder(name+' colour band',Vector(b)-delta*.22,Vector(b)-delta*.16,r+.035,color,24)
    # Dark recessed mouth, with an unobstructed connection centred on the grid face.
    cylinder(name+' mouth',Vector(b)-delta*.017,Vector(b)-delta*.006,r*.79,'dark',24)
    u=delta.cross(Vector((1,0,0))).normalized();v=delta.cross(u)
    for i in range(8):
        c=Vector(b)-delta*.003+(u*math.cos(i*math.tau/8)+v*math.sin(i*math.tau/8))*(r+.055)
        cylinder(name+' bolt '+str(i),c-delta*.04,c,.034,'dark',6)

# Low skid, four supports, bottom suction elbow and cast volute casing.
for x in (.5,2.5):
    box('base rail',(x,.095,1.5),(.28,.19,2.8),'dark')
    for z in (.48,2.52):
        box('mount foot',(x,.22,z),(.52,.26,.48),'cast_steel')
        cylinder('anchor',(x,.35,z),(x,.41,z),.06,'machined',6)
        cylinder('casing support',(x,.3,z),(1.5+(x-1.5)*.68,1.43,1.5+(z-1.5)*.65),.095,'cast_steel',8)
# One welded mesh runs from inside the axial inlet, around a tangent 90-degree
# bend and out to the rear flange. No intersecting end caps at the old joint.
# After uniform scaling the pipe axis is exactly half a block above the floor.
radius=.23; bend=.65; radial=32; sections=16
path=[((1.5,1.13,1.5),(0,0,-1))]
for i in range(sections+1):
    a=i*math.pi/(2*sections)
    path.append(((1.5,.95-bend*math.sin(a),2.15-bend*math.cos(a)),(0,math.sin(a),math.cos(a))))
path.append(((1.5,.3,2.945),(0,1,0)))
# The axial straight section shares the same ring orientation as the first bend ring.
path[0]=(path[0][0],(0,0,1))
vs=[]
for center,normal in path:
    for j in range(radial):
        a=j*math.tau/radial
        vs.append(tuple(center[k]+radius*(math.cos(a)*(k==0)+math.sin(a)*normal[k]) for k in range(3)))
fs=[(i*radial+j,i*radial+(j+1)%radial,(i+1)*radial+(j+1)%radial,(i+1)*radial+j)
    for i in range(len(path)-1) for j in range(radial)]
pipe=mesh('continuous suction elbow',vs,fs,'cast_steel')
# Only the two terminal rings are open; the entire bend is one connected surface.
bm=bmesh.new();bm.from_mesh(pipe.data)
assert sum(e.is_boundary for e in bm.edges)==2*radial
assert all(e.is_manifold or e.is_boundary for e in bm.edges)
bm.free()
pipe['continuous_suction']=True
nozzle('SUCTION',(1.5,.3,2.15),(1.5,.3,3),.20,'suction_blue',barrel=False)
ring('suction shoulder',.95,.48,.16,'machined')
ring('lower casing taper',1.15,.67,.22,'cast_steel')
ring('double volute body',1.52,.93,.62,'cast_steel',40)
cylinder('rounded lower shoulder',(1.5,1.05,1.5),(1.5,1.24,1.5),.57,'cast_steel',40,r2=.91)
cylinder('upper shoulder',(1.5,1.82,1.5),(1.5,1.97,1.5),.93,'cast_steel',40,r2=.77)
ring('casing closure',1.98,.95,.12,'machined');bolts('closure stud ',2.045,.84,16)
nozzle('DISCHARGE',(1.5,1.5,.9),(1.5,1.5,0),.27,'discharge_teal')

# Open tapered motor lantern, matching the broad frame in the DVSS cutaway.
ring('lantern lower flange',2.16,.74,.14,'cast_steel');bolts('lower lantern stud ',2.235,.64)
for i in range(4):
    mid=math.pi/4+i*math.pi/2;vs=[]
    for y,r in ((2.22,.69),(3.82,1.03)):
        for radius in (r-.14,r):
            for j in range(5):
                a=mid-.25+j*.125;vs.append((1.5+radius*math.cos(a),y,1.5+radius*math.sin(a)))
    fs=[]
    for j in range(4):fs.extend([(j,j+1,11+j,10+j),(5+j,15+j,16+j,6+j),
                              (j,5+j,6+j,j+1),(10+j,11+j,16+j,15+j)])
    fs.extend([(0,10,15,5),(4,9,19,14)]);mesh('lantern rib '+str(i),vs,fs,'cast_steel')
ring('lantern top flange',3.87,1.10,.17,'machined');bolts('motor flange bolt ',3.96,.98,16)
cylinder('polished shaft',(1.5,2.05,1.5),(1.5,3.93,1.5),.11,'machined',20)
ring('seal cartridge',2.36,.36,.36,'dark');ring('seal retainer',2.54,.41,.09,'machined')
bolts('seal bolt ',2.59,.33,8,.035)
ring('coupling lower',3.09,.23,.35,'machined');ring('coupling split',3.28,.28,.1,'brass')
ring('coupling upper',3.44,.23,.22,'machined')
# Seal service lines are decorative small-bore instrumentation, not process ports.
for x in (.95,2.05):
    cylinder('seal tube',(x,2.05,1.5),(x,2.62,1.5),.025,'machined',8)
    cylinder('seal return',(x,2.62,1.5),(1.5,2.62,1.5),.025,'machined',8)
    cylinder('seal fitting',(x,2.20,1.5),(x,2.32,1.5),.055,'brass',6)

# Game motor: deliberately a complete exterior above the manufacturer's bare-pump drawing.
ring('motor foot',4.04,.90,.19,'motor_green')
ring('lower end bell',4.23,.83,.25,'motor_green')
ring('motor shell',4.88,.78,1.18,'motor_green',40)
for i in range(24):
    a=i*math.tau/24;x=1.5+.81*math.cos(a);z=1.5+.81*math.sin(a)
    o=box('cooling fin',(x,4.87,z),(.085,1.03,.035),'motor_green')
    o.rotation_euler.z=-a
ring('upper end bell',5.49,.85,.15,'motor_green')
cylinder('fan taper',(1.5,5.57,1.5),(1.5,5.72,1.5),.84,'motor_green',32,r2=.7)
ring('fan cap',5.76,.7,.08,'dark')
for i in range(5):ring('fan guard rim',5.58+i*.034,.847,.013,'dark')
box('terminal box',(2.43,4.63,1.5),(.48,.58,.65),'motor_green')
box('terminal cover',(2.68,4.63,1.5),(.04,.50,.55),'dark')
for z in (1.34,1.66):cylinder('cable gland',(2.43,4.24,z),(2.43,4.34,z),.075,'brass',8)
box('nameplate',(1.5,4.81,.59),(.66,.31,.018),'plate')
for x in (1.21,1.79):
    for y in (4.7,4.92):cylinder('plate screw',(x,y,.57),(x,y,.581),.017,'dark',6)
# Simple raised DVSS lettering remains legible without a special font texture.
patterns={'D':['110','101','101','101','110'],'V':['101','101','101','101','010'],
          'S':['111','100','111','001','111']}
for n,ch in enumerate('DVSS'):
    for row,line in enumerate(patterns[ch]):
        for col,bit in enumerate(line):
            if bit=='1':box('nameplate '+ch,(1.756-n*.135-col*.032,4.88-row*.032,.572),(.029,.029,.012),'dark')

# Scale the entire assembly uniformly: one game block per Blender metre.
bpy.context.view_layer.update()
for obj in COL.objects: obj.matrix_world=Matrix.Scale(5/3,4)@obj.matrix_world
bpy.context.view_layer.update()

# Export evaluated triangles with material colours; runtime clipping uses the same mesh.
faces=[];boxes=[];dg=bpy.context.evaluated_depsgraph_get()
for obj in COL.objects:
    evaluated=obj.evaluated_get(dg);me=evaluated.to_mesh();me.calc_loop_triangles()
    coords=[obj.matrix_world@v.co for v in me.vertices]
    pts=[(round(v.x,7),round(v.z,7),round(-v.y,7)) for v in coords]
    boxes.append([min(p[a] for p in pts) for a in range(3)]+[max(p[a] for p in pts) for a in range(3)])
    color=tuple(round(c,4) for c in obj.data.materials[0].diffuse_color[:3])
    for t in me.loop_triangles:faces.append({'points':[pts[i] for i in t.vertices],'color':color,'part':obj.name})
    evaluated.to_mesh_clear()
(OUT/'dvss_mesh.json').write_text(json.dumps({'size':[5,10,5],'controller':[2,0,2],
    'ports':[{'role':'WATER_SUCTION','cell':[2,0,4],'face':'south'},
             {'role':'WATER_DISCHARGE','cell':[2,2,0],'face':'north'}],
    'boxes':boxes,'faces':faces},separators=(',',':')),encoding='utf-8')
SCENE['source_reference']='https://www.flowserve.com/products/products-catalog/pumps/nuclear-products/nuclear-pumps/flowserve-dvss-nuclear-pump/'
SCENE['game_footprint']='5 wide x 10 high x 5 deep. Rear suction at 0.5; front discharge at 2.5 blocks.'
bpy.data.libraries.write(str(OUT/'dvss_recirculation_pump.blend'),{SCENE},compress=True)
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        area.spaces.active.region_3d.view_location=xyz((2.5,4.7,2.5))
        area.spaces.active.region_3d.view_distance=15
        area.spaces.active.shading.color_type='MATERIAL'
print('DVSS model:',len(COL.objects),'objects;',len(faces),'triangles. Original project preserved.')
