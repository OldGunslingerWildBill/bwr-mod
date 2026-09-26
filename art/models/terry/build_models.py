"""Original Terry-inspired RCIC and HPCI skids, built in Blender, metres/blocks.

Rebuild with Blender --background --python this_file. No vendor meshes are used.
Separate scenes preserve the rest of the plant's Blender libraries.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf8').split('def build_msiv():')[0], str(helper), 'exec'))
OUT = ROOT / 'art/models/terry'
OUT.mkdir(parents=True, exist_ok=True)
GREEN = material('Terry | green machinery enamel', (.065, .14, .09), .28)
INSULATION = material('Terry | silver insulation jacket', (.46, .49, .50), .48)
ORANGE = material('Terry | orange valve gear', (.65, .075, .012), .22)
WHITE = material('Terry | gauge dial', (.80, .83, .77), .0)
SCREEN = material('Terry | computer screen', (.018, .22, .24), .12)
YELLOW = material('Terry | coupling guard', (.62, .39, .012), .18)


def pipe(s, name, points, r=.10, mat=STEEL):
    pts=[Vector(p) for p in points];smooth=[pts[0]]
    for a,b,c in zip(pts,pts[1:],pts[2:]):
        length=min((b-a).length*.42,(c-b).length*.42,r*1.6)
        start=b+(a-b).normalized()*length;end=b+(c-b).normalized()*length
        smooth.extend((1-t)**2*start+2*(1-t)*t*b+t*t*end for t in (i/6 for i in range(7)))
    smooth.append(pts[-1])
    sweep(s, name, smooth, r, mat, 16)


def gauge(s, x, y, z, r=.12):
    cylinder(s, 'pressure gauge bezel', (x,y,z), (x,y,z-.065), r, STEEL, 20)
    cylinder(s, 'cream gauge dial', (x,y,z-.065), (x,y,z-.071), r*.85, WHITE, 20)
    pipe(s, 'gauge needle', [(x,y,z-.075),(x+r*.44,y+r*.42,z-.075)], .008, DARK)
    for a in range(0, 8):
        t=math.pi*(.1+a*.115)
        q=Vector((x,y,z-.075))+Vector((math.cos(t),math.sin(t),0))*r*.7
        pipe(s,'gauge tick',[q,q+Vector((math.cos(t),math.sin(t),0))*r*.10],.004,DARK)


def handwheel(s, x,y,z, r=.21):
    sleeve(s,'valve handwheel',(x,y,z),(x,y,z-.035),r,r-.035,ORANGE,20)
    for a in range(3):
        t=a*math.tau/3
        pipe(s,'handwheel spoke',[(x,y,z-.015),(x+r*.87*math.cos(t),y+r*.87*math.sin(t),z-.015)],.02,ORANGE)


def base(s, w):
    for z in (.35, 3.9):
        box(s,'skid longitudinal I beam',(.15,.10,z),(w-.15,.34,z+.35),GREEN)
        box(s,'I beam top flange',(.1,.31,z-.07),(w-.1,.38,z+.42),GREEN)
    for x in (.35,w/2,w-.35):
        box(s,'skid cross member',(x-.12,.16,.35),(x+.12,.32,4.25),GREEN)
        for z in (.40,4.12):
            box(s,'anchor foot',(x-.25,.03,z-.2),(x+.25,.14,z+.2),DARK)
            for dx in (-.17,.17):bolt(s,'foundation anchor',(x+dx,.15,z),(x+dx,.24,z),.035)


def machine(s, hp):
    w=7 if hp else 5; base(s,w)
    cy=2.13 if hp else 1.72; cx=2.1 if hp else 1.4; radius=1.22 if hp else .88
    # Solid-wheel turbine: transverse axis, heavy split casing and pedestal.
    for x in (cx-.63,cx+.63):
        box(s,'turbine pedestal',(x-.16,.35,1.62),(x+.16,cy-.18,3.38),GREEN)
    cylinder(s,'Terry turbine insulated casing' if hp else 'Terry GS turbine casing',
             (cx-.62,cy,2.5),(cx+.62,cy,2.5),radius,INSULATION if hp else GREEN,40)
    for x in (cx-.64,cx+.64):
        scaled_flange(s,'split casing bolted cover',(x,cy,2.5),(-1 if x<cx else 1,0,0),radius+.055,.14,STEEL,20)
        cylinder(s,'dished bearing cover',(x,cy,2.5),(x+(-.09 if x<cx else .09),cy,2.5),radius*.62,INSULATION if hp else GREEN,32)
    for z in (2.5-radius,2.5+radius):
        box(s,'horizontal casing split',(cx-.72,cy-.055,z-.035),(cx+.72,cy+.055,z+.035),STEEL)
        for x in (cx-.53,cx-.25,cx+.05,cx+.37,cx+.59):
            bolt(s,'split casing stud',(x,cy-.12,z),(x,cy+.14,z),.028)
    if hp:
        # Jacket seams and straps avoid a featureless silver cylinder.
        for x in (cx-.45,cx-.15,cx+.2,cx+.48):
            sleeve(s,'insulation band',(x-.018,cy,2.5),(x+.018,cy,2.5),radius+.012,radius,STEEL,40)
    # Shaft with a separate water pump: it is not an inlet attached to turbine casing.
    px=w-1.50
    cylinder(s,'turbine output shaft',(cx+.70,cy,2.5),(px-.60,cy,2.5),.10,STEEL,20)
    cylinder(s,'coupling guard',(cx+.78,cy,2.5),(px-.53,cy,2.5),.27,YELLOW,24)
    for x in (px-.45,px+.35):
        box(s,'pump foot',(x-.14,.34,1.96),(x+.14,cy-.14,3.04),GREEN)
    cylinder(s,'high head centrifugal pump barrel',(px-.50,cy,2.5),(px+.50,cy,2.5),.59,GREEN,32)
    for x in (px-.51,px+.51):scaled_flange(s,'pump casing flange',(x,cy,2.5),(1,0,0),.65,.19,GREEN,12)
    for z in (2.02,2.98):
        for y in (cy-.25,cy+.25):cylinder(s,'pump barrel tie rod',(px-.59,y,z),(px+.59,y,z),.025,STEEL,10)
    # Lubrication reservoir, oil cooler and bearing pipes.
    box(s,'lubrication oil reservoir',(.65,.38,.82),(cx+1,.73,1.28),GREEN)
    cylinder(s,'horizontal oil cooler',(.70,.97,1.10),(cx+.85,.97,1.10),.19,STEEL,20)
    for x in (.78,cx+.73):scaled_flange(s,'cooler end',(x,.97,1.10),(1,0,0),.23,.04,STEEL,8)
    pipe(s,'oil supply header',[(cx+.67,cy,2.5),(cx+.80,cy,1.52),(cx+.80,.68,1.52)],.035,ORANGE)
    pipe(s,'oil return',[(cx-.74,cy,2.5),(cx-.86,cy-.3,2.5),(cx-.86,.68,1.02)],.03,ORANGE)
    gauge(s,cx+.60,1.27,.84)
    # Steam chest, governor pedestal, trip linkage and sprung actuator.
    gy=3.10 if hp else 2.65; gx=cx-.22
    cylinder(s,'steam chest',(gx,gy,2.5),(gx+.65,gy,2.5),.32,INSULATION if hp else GREEN,28)
    for x in (gx,gx+.65):scaled_flange(s,'chest cover',(x,gy,2.5),(1,0,0),.37,.06,STEEL,12)
    pipe(s,'steam chest connection',[(gx+.35,gy,2.50),(gx+.35,cy+.6,2.5)],.24,INSULATION if hp else GREEN)
    for z in (2.20,2.80):
        cylinder(s,'governor guide',(gx+.2,gy+.24,z),(gx+.2,gy+.85,z),.028,STEEL,10)
        path=[(gx+.2+.065*math.cos(i*math.tau/12),gy+.30+i*.47/72,z+.065*math.sin(i*math.tau/12)) for i in range(73)]
        sweep(s,'governor return spring',path,.012,STEEL,6)
    box(s,'governor yoke',(gx+.02,gy+.82,2.06),(gx+.38,gy+.91,2.94),ORANGE)
    cylinder(s,'governor spindle',(gx+.20,gy+.30,2.5),(gx+.20,gy+.83,2.5),.045,STEEL,12)
    pipe(s,'speed governor linkage',[(cx+.78,cy+.3,2.5),(cx+.78,gy+.74,2.5),(gx+.2,gy+.85,2.5)],.035,ORANGE)
    # Visible control station and instruments face the maintenance aisle.
    qx=4.5 if hp else 3.5
    box(s,'control stand',(qx-.08,.35,.18),(qx+.08,2.32,.34),GREEN)
    box(s,'CC Tweaked control enclosure',(qx-.43,2.16,.08),(qx+.43,2.93,.44),STEEL)
    box(s,'computer panel bezel',(qx-.35,2.43,.05),(qx+.35,2.85,.085),DARK)
    box(s,'computer panel display',(qx-.28,2.49,.038),(qx+.28,2.79,.055),SCREEN)
    for dx,mat in ((-.24,GREEN),(0,ORANGE),(.24,WHITE)):
        cylinder(s,'panel selector',(qx+dx,2.29,.035),(qx+dx,2.29,.065),.052,mat,12)
    for x in (cx-.35,cx+.08):gauge(s,x,gy-.15,1.50,.14)
    pipe(s,'blue instrument sensing line',[(cx+.5,gy,1.55),(cx+.5,gy+.4,1.55),(.75,gy+.4,1.55),(.75,.65,1.55)],.028,BLUE)
    handwheel(s,cx-.35,1.55,1.35,.18)
    if hp:
        # Service platform and ladder seen in the supplied HPCI photograph.
        for x in (.4,3.75):
            for z in (3.8,4.55):box(s,'platform support',(x-.04,.34,z-.04),(x+.04,2.05,z+.04),STEEL)
        for x in range(35):box(s,'service platform grating',(.35+x*.1,2,3.75),(.40+x*.1,2.07,4.60),STEEL)
        for x in (.4,1.55,2.70,3.75):
            pipe(s,'platform railing upright',[(x,2.06,4.57),(x,3.1,4.57)],.03,STEEL)
        for y in (2.56,3.10):pipe(s,'platform handrail',[(.4,y,4.57),(3.75,y,4.57)],.032,STEEL)
        for x in (3.10,3.62):pipe(s,'ladder stile',[(x,.3,4.75),(x,2.25,4.75)],.028,STEEL)
        for i in range(7):pipe(s,'ladder rung',[(3.10,.36+i*.27,4.75),(3.62,.36+i*.27,4.75)],.025,STEEL)
    return s


PORTS = {
 'rcic_twl': [('STEAM_INLET',[0,2,2],'west'),('STEAM_EXHAUST',[1,1,0],'north'),
              ('WATER_SUCTION',[4,1,2],'east'),('WATER_DISCHARGE',[3,1,4],'south')],
 'hpci_turbine': [('STEAM_INLET',[0,3,2],'west'),('STEAM_EXHAUST',[2,1,0],'north'),
                  ('WATER_SUCTION',[6,1,2],'east'),('WATER_DISCHARGE',[5,1,4],'south')]
}


def build(id):
    hp=id=='hpci_turbine';w,h= (7,5) if hp else (5,4)
    s=new_scene('Realistic BWR | Terry '+('HPCI' if hp else 'RCIC')+' skid')
    machine(s,hp);cx=2.1 if hp else 1.4;cy=2.13 if hp else 1.72
    gy=3.10 if hp else 2.65;px=w-1.50;ports=[]
    directions={'west':(-1,0,0),'east':(1,0,0),'north':(0,0,-1),'south':(0,0,1)}
    for role,cell,face in PORTS[id]:
        d=Vector(directions[face]);p=Vector(cell)+Vector((.5,.5,.5))+d*.5
        inner=p-d*.32
        band={'STEAM_INLET':AMBER,'STEAM_EXHAUST':INSULATION,'WATER_SUCTION':BLUE,'WATER_DISCHARGE':TEAL}[role]
        if role=='STEAM_INLET':
            route=[(cx+.10,gy,2.5),(.73,gy,2.5),(.73,p.y,2.5),inner]
        elif role=='STEAM_EXHAUST':
            route=[(cx,cy-.22,2.0),(p.x,cy-.22,1.2),(p.x,p.y,.8),inner]
        elif role=='WATER_SUCTION':route=[(px+.55,cy,2.5),(w-.58,cy,2.5),(w-.58,p.y,2.5),inner]
        else:route=[(px,cy,2.9),(px,p.y,3.5),(p.x,p.y,4.15),inner]
        # Remove coincident consecutive points before building a circular sweep.
        points=[]
        for q in route:
            if not points or (Vector(q)-Vector(points[-1])).length>.001:points.append(q)
        pipe(s,role+' continuous nozzle',points,.245,INSULATION if role.startswith('STEAM') else STEEL)
        sleeve(s,role+' open terminal bore',inner,p,.245,.205,STEEL,24)
        steam_flange(s,role+' block face flange',p,d)
        sleeve(s,role+' identification band',p-d*.27,p-d*.18,.254,.244,band,24)
        marker(s,role,p,role=role,cell=cell,face=face)
        ports.append({'role':role,'cell':cell,'face':face})
    s['description']='Original reference-inspired exterior; one Blender unit is one Minecraft block.'
    s['ports']='Round bores meet block faces; amber steam in, silver exhaust, blue suction, teal discharge.'
    export_scene(s,id+'.json',{'size':[w,h,5],'controller':[0,0,0],'ports':ports})
    preview=new_scene(s.name+' studio')
    for o in s.objects:preview.collection.objects.link(o)
    studio(preview,(w/2,h/2,2.5),(w+6,h+4,-8),w+3,(1200,900))
    try:preview.render.engine='CYCLES'
    except TypeError:pass
    if hasattr(preview,'cycles'):preview.cycles.samples=24
    preview.render.film_transparent=True
    preview.render.filepath=str(OUT/(id+'.png'))
    viewport(s,(w/2,h/2,2.5),(w+6,h+4,-8),w+5)
    bpy.data.libraries.write(str(OUT/(id+'.blend')),{s,preview},compress=True)
    bpy.ops.render.render(write_still=True,scene=preview.name)
    print('TERRY BUILT',id,len(s.objects),'objects')


if __name__=='__main__':
    build('rcic_twl')
    build('hpci_turbine')
