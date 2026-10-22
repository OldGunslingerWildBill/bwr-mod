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

# ---------------------------------------------------------------------------------------------------------------
# Containment doors, second generation (alpha.49).
#
# Every moving part is its own mesh so the renderer can move it: the leaf swings about its hinge pin, the latch dogs
# slide out of their keepers before it does, the hand-wheels turn, and the status lenses light up in place. The parts
# are authored at their RESTING pose (door closed, dogs engaged) in the same block-local game coordinates as before;
# wheels and lenses are authored at the origin and placed by the renderer (see VaultDoorRenderer / AirlockRenderer).
# ---------------------------------------------------------------------------------------------------------------
LENS_RED=material('lens red',(.86,.05,.04),.1)
LENS_AMBER=material('lens amber',(.96,.55,.03),.1)
LENS_GREEN=material('lens green',(.06,.74,.16),.1)
LENS_OFF=material('dark lens glass',(.02,.025,.03),.05)
DOG=material('machined latch steel',(.66,.70,.72),.9)
GUIDE=material('dark keeper steel',(.06,.075,.085),.6)

def prism(scene,name,poly,z0,z1,mat):
    """Closed prism of a simple (possibly concave) polygon given as (x,y) points, extruded from z0 to z1."""
    n=len(poly)
    vs=[(x,y,z0) for x,y in poly]+[(x,y,z1) for x,y in poly]
    fs=[tuple(range(n)),tuple(range(2*n-1,n-1,-1))]
    fs+=[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    return mesh_object(scene,name,vs,fs,mat)

def d_band(scene,name,pin,pout,z0,z1,mat):
    """A flat ring between two closed polylines of equal length (inner and outer), z0 to z1."""
    n=len(pin)
    vs=[(x,y,z0) for x,y in pin]+[(x,y,z1) for x,y in pin]+[(x,y,z0) for x,y in pout]+[(x,y,z1) for x,y in pout]
    fs=[]
    for i in range(n):
        j=(i+1)%n
        fs+=[(n+i,n+j,3*n+j,3*n+i),(i,2*n+i,2*n+j,j),(i,j,n+j,n+i),(2*n+i,3*n+i,3*n+j,2*n+j)]
    return mesh_object(scene,name,vs,fs,mat)

def torus(scene,name,c,R,r,mat,seg=18,tube=4):
    """Rim of a hand-wheel: axis along game Z, centre c."""
    cx,cy,cz=c
    vs=[];fs=[]
    for i in range(seg):
        t=i*math.tau/seg
        for j in range(tube):
            p=j*math.tau/tube+math.pi/tube
            rr=R+r*math.cos(p)
            vs.append((cx+rr*math.cos(t),cy+rr*math.sin(t),cz+r*math.sin(p)))
    for i in range(seg):
        i2=(i+1)%seg
        for j in range(tube):
            j2=(j+1)%tube
            fs.append((i*tube+j,i*tube+j2,i2*tube+j2,i2*tube+j))
    return mesh_object(scene,name,vs,fs,mat)

def handwheel(scene,name,c,R,tube,hub_r,hub_half,spokes=6,seg=18):
    cx,cy,cz=c
    torus(scene,name+' rim',c,R,tube,TRIM,seg)
    cylinder(scene,name+' hub',(cx,cy,cz-hub_half),(cx,cy,cz+hub_half),hub_r,HINGE,10)
    for i in range(spokes):
        t=i*math.tau/spokes+math.pi/spokes
        cylinder(scene,name+' spoke',(cx,cy,cz),(cx+(R-tube*.4)*math.cos(t),cy+(R-tube*.4)*math.sin(t),cz),tube*.55,TRIM,4)

def lens(scene,name,mat):
    cylinder(scene,name,(0,0,0),(0,0,-.03),.04,mat,8)

JAMB=.30
VAULT_PIVOT=(.31,.38)        # hinge pin (x,z); VaultDoorRenderer swings the leaf about it
VAULT_TRAVEL=.26             # how far a latch dog slides back before the leaf moves
VAULT_WHEEL_Z=.08            # plane of the hand-wheel rim
VAULT_NOTCH=.11              # half-width of a keeper notch

class Edge:
    """One side of a vault frame. u runs from the opening edge outwards into the jamb, v along the side."""
    def __init__(self,kind,size):self.kind=kind;self.size=size
    def pt(self,u,v):
        n=self.size
        return {'right':(n-JAMB+u,v),'left':(JAMB-u,v),'top':(v,n-JAMB+u),'bottom':(v,JAMB-u)}[self.kind]
    def box(self,scene,name,u0,u1,v0,v1,z0,z1,mat):
        (xa,ya),(xb,yb)=self.pt(u0,v0),self.pt(u1,v1)
        return box(scene,name,(min(xa,xb),min(ya,yb),z0),(max(xa,xb),max(ya,yb),z1),mat)

def vault_layout(size):
    n=2 if size==3 else 3
    dogs=[JAMB+(size-2*JAMB)*(i+.5)/n for i in range(n)]
    k=3 if size==3 else 4
    hinges=[.7+(size-1.4)*i/(k-1) for i in range(k)]
    return dogs,hinges,(dogs[0]+dogs[1])/2

def bolt(scene,name,x,y,z0,z1,mat=HINGE,r=.032):
    return cylinder(scene,name,(x,y,z0),(x,y,z1),r,mat,6)

def vault(size):
    frame=new_scene('Vault %d frame'%size)
    leaf=new_scene('Vault %d leaf'%size)
    edges={k:new_scene('Vault %d dogs %s'%(size,k)) for k in ('right','top','bottom')}
    wheel=new_scene('Vault %d wheel'%size)
    item=new_scene('Vault %d item'%size)
    dogs,hinges,lamp_y=vault_layout(size)
    cx=cy=size/2
    px,pz=VAULT_PIVOT
    # ---- frame: thick graphite ring. Behind z .22 it is solid; the front land (z .10-.22) carries a keeper notch for every dog.
    box(frame,'left jamb outer web',(0,0,.22),(.15,size,.54),FRAME)
    box(frame,'left jamb front land',(0,0,.10),(JAMB,size,.22),FRAME)
    box(frame,'left jamb back web',(0,0,.54),(JAMB,size,.90),FRAME)
    box(frame,'right jamb base',(size-JAMB,0,.22),(size,size,.90),FRAME)
    box(frame,'lintel base',(JAMB,size-JAMB,.22),(size-JAMB,size,.90),FRAME)
    box(frame,'threshold base',(JAMB,0,.22),(size-JAMB,JAMB,.90),FRAME)
    for kind in ('right','top','bottom'):
        e=Edge(kind,size);lo,hi=(0,size) if kind=='right' else (JAMB,size-JAMB)
        e.box(frame,kind+' land back strip',.14,JAMB,lo,hi,.10,.22,FRAME)
        cuts=[lo]
        for v in dogs:cuts+=[v-VAULT_NOTCH,v+VAULT_NOTCH]
        cuts.append(hi)
        for i in range(0,len(cuts),2):
            if cuts[i+1]-cuts[i]>.01:e.box(frame,kind+' land',0,.14,cuts[i],cuts[i+1],.10,.22,FRAME)
        for v in dogs:e.box(frame,kind+' keeper floor',0,.14,v-VAULT_NOTCH+.012,v+VAULT_NOTCH-.012,.200,.22,WARN)
        e.box(frame,kind+' compression seal',-.03,0,JAMB,size-JAMB,.24,.52,SEAL)
    # hinge: three knuckles to a pin; the frame's two sit in the groove cut into the left jamb
    for y in hinges:
        for lo,hi in ((-.27,-.09),(.09,.27)):
            cylinder(frame,'hinge frame knuckle',(px,y+lo,pz),(px,y+hi,pz),.10,HINGE,10)
            box(frame,'hinge frame bracket',(.15,y+lo,pz-.08),(px,y+hi,pz+.08),TRIM)
        cylinder(frame,'hinge pin',(px,y-.30,pz),(px,y+.30,pz),.04,TRIM,6)
    for y in (.5,size-.5):
        bolt(frame,'frame bolt',size-.08,y,.06,.10)
    for y in ((.5,size/2,size-.5) if size==3 else (.5,size/2-.5,size/2+.5,size-.5)):
        bolt(frame,'frame bolt',.08,y,.06,.10)
    for x in ((size/2,) if size==3 else (size/2-.5,size/2+.5)):
        bolt(frame,'frame bolt',x,size-.08,.06,.10);bolt(frame,'frame bolt',x,.08,.06,.10)
    # status lamp column: dark recesses only; the renderer draws the lit lens (red open, amber moving, green sealed)
    box(frame,'lamp housing',(size-.15,lamp_y-.24,.05),(size-.02,lamp_y+.24,.10),GUIDE)
    for d in (.14,0,-.14):box(frame,'lamp recess',(size-.12,lamp_y+d-.045,.044),(size-.055,lamp_y+d+.045,.05),LENS_OFF)
    # ---- leaf: armoured slab z .22-.54, hinge edge at x .33 (clear of the jamb by .03 on the other three sides)
    lo_,hi_=JAMB+.03,size-JAMB-.03
    box(leaf,'armoured slab',(lo_,lo_,.22),(hi_,hi_,.54),LEAF)
    box(leaf,'face rim left',(lo_+.03,lo_+.03,.18),(lo_+.13,hi_-.03,.22),TRIM)
    box(leaf,'face rim right',(hi_-.13,lo_+.03,.18),(hi_-.03,hi_-.03,.22),TRIM)
    box(leaf,'face rim bottom',(lo_+.13,lo_+.03,.18),(hi_-.13,lo_+.13,.22),TRIM)
    box(leaf,'face rim top',(lo_+.13,hi_-.13,.18),(hi_-.13,hi_-.03,.22),TRIM)
    cylinder(leaf,'gear housing',(cx,cy,.17),(cx,cy,.22),.30,HINGE,16)
    cylinder(leaf,'gear stub',(cx,cy,.12),(cx,cy,.17),.15,TRIM,12)
    for i in range(6):
        t=i*math.tau/6
        bolt(leaf,'housing bolt',cx+.23*math.cos(t),cy+.23*math.sin(t),.145,.17,TRIM,.024)
    for kind in ('right','top','bottom'):
        e=Edge(kind,size)
        for v in dogs:
            for side in (-1,1):
                e.box(leaf,'dog guide rail',-.80,-.04,v+side*.095-.015,v+side*.095+.015,.17,.22,GUIDE)
            e.box(leaf,'dog drive cam',-.95,-.80,v-.08,v+.08,.17,.22,GUIDE)
    # interior face (the side that swings into the room): ribs, drive housing, lock indicators
    box(leaf,'interior rib left',(lo_+.07,lo_+.07,.54),(lo_+.17,hi_-.07,.60),LEAF)
    box(leaf,'interior rib right',(hi_-.17,lo_+.07,.54),(hi_-.07,hi_-.07,.60),LEAF)
    box(leaf,'interior rib bottom',(lo_+.17,lo_+.07,.54),(hi_-.17,lo_+.17,.60),LEAF)
    box(leaf,'interior rib top',(lo_+.17,hi_-.17,.54),(hi_-.17,hi_-.07,.60),LEAF)
    box(leaf,'interior cross rib h',(lo_+.17,cy-.06,.54),(hi_-.17,cy+.06,.60),LEAF)
    box(leaf,'interior cross rib v',(cx-.06,lo_+.17,.54),(cx+.06,hi_-.17,.60),LEAF)
    cylinder(leaf,'interior drive housing',(cx,cy,.54),(cx,cy,.66),.22,HINGE,14)
    for v in dogs:
        box(leaf,'lock indicator',(hi_-.40,v-.06,.54),(hi_-.24,v+.06,.57),WARN)
    for y in hinges:
        cylinder(leaf,'hinge leaf knuckle',(px,y-.085,pz),(px,y+.085,pz),.10,HINGE,10)
        box(leaf,'hinge strap inside',(px,y-.085,.54),(.80,y+.085,.60),TRIM)
        box(leaf,'hinge strap outside',(px,y-.085,.17),(.80,y+.085,.22),TRIM)
    # ---- latch dogs, one mesh per side, engaged. They slide VAULT_TRAVEL toward the leaf centre before it swings.
    for kind,scene in edges.items():
        e=Edge(kind,size)
        for v in dogs:
            e.box(scene,'latch dog bar',-.62,.12,v-.07,v+.07,.12,.18,DOG)
            e.box(scene,'latch dog head',.00,.12,v-.09,v+.09,.105,.195,WARN)
    # ---- hand-wheel, authored at the origin (plane z=0) and placed at (size/2,size/2,VAULT_WHEEL_Z) by the renderer
    R=.40 if size==3 else .50
    handwheel(wheel,'wheel',(0,0,0),R,.04,.09,.08)
    # ---- inventory model: the closed door with everything in place
    for source in (frame,leaf,edges['right'],edges['top'],edges['bottom']):
        for ob in source.objects:item.collection.objects.link(ob)
    wi=new_scene('Vault %d item wheel'%size)
    handwheel(wi,'wheel',(cx,cy,VAULT_WHEEL_Z),R,.04,.09,.08)
    for ob in wi.objects:item.collection.objects.link(ob)
    for scene,file,part in ((frame,'vault_%d_frame.json','frame'),(leaf,'vault_%d_leaf.json','leaf'),
                            (edges['right'],'vault_%d_dogs_right.json','dogs'),(edges['top'],'vault_%d_dogs_top.json','dogs'),
                            (edges['bottom'],'vault_%d_dogs_bottom.json','dogs'),(wheel,'vault_%d_wheel.json','wheel'),
                            (item,'vault_%d_item.json','item')):
        export_scene(scene,file%size,{'size':[size,size,1],'part':part})
    render_parts('vault_'+str(size),[item],size,size,1)

def dline(cx,y0,yc,r,seg):
    """Outline of a D: flat bottom at y0, straight sides, round top of radius r centred (cx,yc)."""
    pts=[(cx+r,y0),(cx+r,yc)]
    for i in range(1,seg):
        t=math.pi*i/seg;pts.append((cx+r*math.cos(t),yc+r*math.sin(t)))
    return pts+[(cx-r,yc),(cx-r,y0)]

AIR_HOLE_R,AIR_HOLE_Y0,AIR_YC=.37,.14,1.42
AIR_LEAF_R,AIR_LEAF_Y0=.41,.10
AIR_PIVOT_X=.07
AIR_WHEEL=(.5,.95)
AIR_FACES=((.58,1),(2.42,-1))      # chamber face of each plate and which way the chamber lies (+z for inner, -z for outer)
AIR_LAMP_X,AIR_LAMP_Y=.95,1.15
AIR_VALVE=(.70,1.93,1.5)
AIR_ARCH=10

def airlock():
    frame=new_scene('Airlock frame')
    leaves=[new_scene('Airlock inner leaf'),new_scene('Airlock outer leaf')]
    wheel=new_scene('Airlock wheel');valve=new_scene('Airlock valve wheel')
    item=new_scene('Airlock item')
    pin=dline(.5,AIR_HOLE_Y0,AIR_YC,AIR_HOLE_R,AIR_ARCH)
    pout=dline(.5,AIR_LEAF_Y0,AIR_YC,AIR_LEAF_R,AIR_ARCH)
    for index,(zc,sg) in enumerate(AIR_FACES):
        leaf=leaves[index]
        def zr(d0,d1):
            a,b=zc+sg*d0,zc+sg*d1;return min(a,b),max(a,b)
        def dbox(scene,name,x0,x1,y0,y1,d0,d1,mat):
            za,zb=zr(d0,d1);return box(scene,name,(x0,y0,za),(x1,y1,zb),mat)
        def dcyl(scene,name,x,y,d0,d1,r,mat,seg=8):
            return cylinder(scene,name,(x,y,zc+sg*d0),(x,y,zc+sg*d1),r,mat,seg)
        # bulkhead plate with a D-shaped opening, 0.20 thick, filling the whole 1 x 2 cell face
        za,zb=zr(-.20,0)
        w=.5-AIR_HOLE_R
        box(frame,'plate left strip',(0,0,za),(w,2,zb),FRAME)
        box(frame,'plate right strip',(1-w,0,za),(1,2,zb),FRAME)
        box(frame,'plate threshold',(w,0,za),(1-w,AIR_HOLE_Y0,zb),FRAME)
        arc=[(.5+AIR_HOLE_R*math.cos(math.pi*i/AIR_ARCH),AIR_YC+AIR_HOLE_R*math.sin(math.pi*i/AIR_ARCH)) for i in range(AIR_ARCH+1)]
        prism(frame,'plate lintel',[(w,2.0),(1-w,2.0)]+arc,za,zb,FRAME)
        zs0,zs1=zr(0,.02)
        d_band(frame,'door seal',pin,pout,zs0,zs1,SEAL)
        # hinge brackets on the plate, and the pin
        for y in (.30,1.55):
            dbox(frame,'hinge bracket',.03,.11,y-.11,y+.11,0,.08,TRIM)
            cylinder(frame,'hinge pin',(AIR_PIVOT_X,y-.12,zc+sg*.08),(AIR_PIVOT_X,y+.12,zc+sg*.08),.022,TRIM,6)
            cylinder(frame,'hinge frame knuckle',(AIR_PIVOT_X,y-.115,zc+sg*.08),(AIR_PIVOT_X,y-.035,zc+sg*.08),.04,HINGE,8)
            cylinder(frame,'hinge frame knuckle',(AIR_PIVOT_X,y+.035,zc+sg*.08),(AIR_PIVOT_X,y+.115,zc+sg*.08),.04,HINGE,8)
        # lamp columns on the right strip, both faces of the plate; the renderer lights one lens per column
        for d0,d1 in ((0,.03),(-.23,-.20)):
            dbox(frame,'lamp housing',AIR_LAMP_X-.04,AIR_LAMP_X+.04,AIR_LAMP_Y-.26,AIR_LAMP_Y+.26,min(d0,d1),max(d0,d1),GUIDE)
            face=d1 if d1>0 else d0
            for off in (.15,0,-.15):
                lo_d,hi_d=(face,face+.006) if face>0 else (face-.006,face)
                dbox(frame,'lamp recess',AIR_LAMP_X-.04,AIR_LAMP_X+.04,AIR_LAMP_Y+off-.04,AIR_LAMP_Y+off+.04,lo_d,hi_d,LENS_OFF)
        # ---- the D-shaped leaf on the chamber side of the plate
        dz0,dz1=zr(.02,.14)
        prism(leaf,'D-shaped slab',pout,dz0,dz1,LEAF)
        rz0,rz1=zr(.14,.17)
        prism(leaf,'raised centre panel',dline(.5,.22,AIR_YC,.31,AIR_ARCH),rz0,rz1,LEAF)
        rim=dline(.5,.16,AIR_YC,.36,AIR_ARCH)
        for i,(x,y) in enumerate(rim):
            if i%2==0:cylinder(leaf,'rim bolt',(x,y,zc+sg*.14),(x,y,zc+sg*.17),.024,TRIM,6)
        dcyl(leaf,'wheel shaft',AIR_WHEEL[0],AIR_WHEEL[1],-.10,.24,.05,HINGE,10)
        for y in (.30,1.55):
            dbox(leaf,'hinge strap',AIR_PIVOT_X-.015,.34,y-.085,y+.085,.14,.17,TRIM)
            cylinder(leaf,'hinge leaf knuckle',(AIR_PIVOT_X,y-.075,zc+sg*.08),(AIR_PIVOT_X,y+.075,zc+sg*.08),.04,HINGE,8)
            dbox(leaf,'hinge arm',AIR_PIVOT_X-.015,.14,y-.085,y+.085,.02,.17,TRIM)
    # override lamp housing: lit amber by the renderer while a computer holds both leaves open
    za,zb=AIR_FACES[0][0]-.20-.03,AIR_FACES[0][0]-.20
    box(frame,'override housing',(.02,1.50,za),(.11,1.60,zb),GUIDE)
    box(frame,'override recess',(.035,1.515,za-.006),(.095,1.585,za),LENS_OFF)
    # chamber: floor plate, and the pressure-equalising line along the ceiling with its valve
    box(frame,'chamber floor plate',(.04,0,.58),(.96,.04,2.42),TRIM)
    for z in (.9,1.5,2.1):box(frame,'chamber floor rib',(.04,.04,z-.04),(.96,.06,z+.04),HINGE)
    cylinder(frame,'equalising line',(.88,1.93,.60),(.88,1.93,2.40),.04,TRIM,8)
    for z in (.62,2.38):cylinder(frame,'equalising flange',(.88,1.93,z-.015),(.88,1.93,z+.015),.065,HINGE,8)
    box(frame,'equalising valve body',(.80,1.87,1.42),(.96,2.0,1.58),HINGE)
    cylinder(frame,'valve bonnet',(.76,1.93,1.5),(.80,1.93,1.5),.05,TRIM,8)
    cylinder(frame,'valve stem',(.70,1.93,1.5),(.76,1.93,1.5),.012,TRIM,6)
    box(frame,'chamber lamp',(.38,1.96,1.35),(.62,2.0,1.65),LEAF)
    # wheels at the origin: one per leaf face, plus the small valve wheel
    handwheel(wheel,'leaf wheel',(0,0,0),.20,.026,.05,.06,spokes=5,seg=16)
    handwheel(valve,'valve wheel',(0,0,0),.065,.014,.022,.02,spokes=4,seg=12)
    for ob in list(frame.objects)+list(leaves[0].objects)+list(leaves[1].objects):item.collection.objects.link(ob)
    wi=new_scene('Airlock item wheels')
    for index,(zc,sg) in enumerate(AIR_FACES):
        for d in (.24,-.14):handwheel(wi,'leaf wheel',(AIR_WHEEL[0],AIR_WHEEL[1],zc+sg*d),.20,.026,.05,.06,spokes=5,seg=16)
    handwheel(wi,'valve wheel',AIR_VALVE,.065,.014,.022,.02,spokes=4,seg=12)
    for ob in wi.objects:item.collection.objects.link(ob)
    for scene,file,part in ((frame,'airlock_frame.json','frame'),(leaves[0],'airlock_inner_leaf.json','inner_leaf'),
                            (leaves[1],'airlock_outer_leaf.json','outer_leaf'),(wheel,'airlock_wheel.json','wheel'),
                            (valve,'airlock_valve_wheel.json','wheel'),(item,'airlock_item.json','item')):
        export_scene(scene,file,{'size':[1,2,3],'part':part})
    render_parts('airlock',[item],1,2,3)

def lens_models():
    for name,mat in (('red',LENS_RED),('amber',LENS_AMBER),('green',LENS_GREEN)):
        scene=new_scene('Door lens '+name);lens(scene,'lens',mat)
        export_scene(scene,'door_lens_%s.json'%name,{'size':[1,1,1],'part':'lens'})

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
airlock()
lens_models()
for service,band in (('steam',STEAM),('water',WATER),('pass_through',WARN),('power',POWER),('data',DATA)):
    sleeve_model(service,band)
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'containment_doors.blend'))
