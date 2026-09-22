"""Detailed cooling exteriors, authored in Blender. Dimensions and flange locations retained.
Photo references: SPIG Cooling Towers (2024), pp. 2-4; Black & Veatch Columbia station photo.
Run build_natural_revision() and build_mechanical_revision() independently in Blender MCP.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
exec(compile((ROOT/'art/models/cooling/build_models.py').read_text(),str(ROOT/'art/models/cooling/build_models.py'),'exec'))
PANEL=material('weathered concrete panel',(.46,.47,.44),.0)
METAL=material('galvanised service steel',(.38,.42,.43),.6)
DECK=material('fan deck anti slip',(.22,.25,.25),.15)
LOUVER=material('grey fibreglass louvers',(.36,.40,.38),.05)

def rail(s,c,r,y,n=64):
    for i in range(n):
        a=i*math.tau/n;b=(i+1)*math.tau/n
        for height in (.5,1.0):cylinder(s,'handrail',(c+r*math.cos(a),y+height,c+r*math.sin(a)),(c+r*math.cos(b),y+height,c+r*math.sin(b)),.028,METAL,6)
        if i%2==0:cylinder(s,'rail post',(c+r*math.cos(a),y,c+r*math.sin(a)),(c+r*math.cos(a),y+1.03,c+r*math.sin(a)),.035,METAL,6)

def ladder(s,x,z,bottom,top):
    for X in (x-.27,x+.27):cylinder(s,'ladder stringer',(X,bottom,z),(X,top,z),.042,METAL,8)
    for i in range(int((top-bottom)/.27)):
        y=bottom+i*.27;cylinder(s,'ladder tread',(x-.27,y,z),(x+.27,y,z),.035,METAL,6)
    for i in range(int((top-bottom)/1.1)):
        y=bottom+1+i*1.1
        path=[(x+.42*math.cos(a),y,z-.40*math.sin(a)) for a in [j*math.pi/12 for j in range(13)]]
        sweep(s,'ladder safety hoop',path,.024,METAL,6)

def build_natural_revision():
    global save
    actual_save=save;captured={}
    def capture(scene,id,meta,eye,scale):captured.update(scene=scene,meta=meta);return scene
    try:save=capture;build_natural()
    finally:save=actual_save
    s=captured['scene'];c=12.5
    palette=[material('concrete lift tone '+str(i),(.44+i*.012,.45+i*.012,.42+i*.012),0) for i in range(7)]
    shell=[o for o in s.objects if o.name.startswith('hyperbolic concrete shell')]
    for i,o in enumerate(shell):o.data.materials.clear();o.data.materials.append(palette[((i//64)*3+i%64*11)%7])
    # Formwork lift joints, open base, fill trays and service access visible at player height.
    for j in range(1,28):
        t=j/28;y=4+31.5*t;r=6.1+4.7*((t-.68)/.68)**2
        for i in range(64):
            a=i*math.tau/64;b=(i+1)*math.tau/64
            cylinder(s,'concrete pour joint',(c+(r+.005)*math.cos(a),y,c+(r+.005)*math.sin(a)),(c+(r+.005)*math.cos(b),y,c+(r+.005)*math.sin(b)),.014,PANEL,4)
    ring(s,'intake service ledge',c,1.0,11.9,.12,METAL);rail(s,c,11.9,1.12)
    for i in range(40):
        a=i*math.tau/40;x=c+10.7*math.cos(a);z=c+10.7*math.sin(a)
        cylinder(s,'distribution support',(x,.45,z),(x,3.3,z),.11,PANEL,8)
        for y in (2.8,3.0,3.2):
            b=(i+1)*math.tau/40;cylinder(s,'fill tray rim',(x,y,z),(c+10.7*math.cos(b),y,c+10.7*math.sin(b)),.045,METAL,6)
    ladder(s,c,1.0,.7,4.15)
    box(s,'maintenance landing',(c-.7,3.95,.45),(c+.7,4.08,1.9),DECK)
    for x in (c-.66,c+.66):cylinder(s,'landing guard',(x,4.05,.5),(x,4.95,.5),.035,METAL,6)
    # Fine visible lateral distribution branches and down-facing spray nozzles.
    for z in (7,10,13,16,19):
        cylinder(s,'spray lateral',(6,3.9,z),(19,3.9,z),.055,STEEL,8)
        for x in range(7,19,2):cylinder(s,'spray nozzle',(x,3.9,z),(x,3.65,z),.038,STEEL,6)
    s['reference']='SPIG 2024 natural draft tower photograph; original game-scale geometry'
    return actual_save(s,'natural_draft_tower',captured['meta'],(47,27,-35),53)

def build_mechanical_revision():
    s=new_scene('BWR | detailed Columbia style tower');c=8.5
    cylinder(s,'concrete basin',(c,.04,c),(c,.28,c),8.0,PANEL,80)
    cylinder(s,'cold basin water',(c,.285,c),(c,.32,c),7.68,WATER,80)
    ring(s,'basin coping',c,.30,8.05,.55,PANEL,80)
    # Dense louver panels, radial partitions, braced columns and shallow fan deck.
    for i in range(48):
        a=i*math.tau/48;b=(i+1)*math.tau/48
        def at(r,y,t):return(c+r*math.cos(t),y,c+r*math.sin(t))
        cylinder(s,'perimeter upright',at(7.65,.65,a),at(7.65,5.72,a),.10,PANEL,8)
        if i%2==0:cylinder(s,'diagonal frame brace',at(7.63,.85,a),at(7.63,5.4,b),.055,METAL,6)
        for j in range(12):
            y=1+j*.365
            vs=[at(r,yy,t) for r,yy in ((7.72,y),(7.47,y+.23)) for t in (a,b)]
            mesh_object(s,'angled intake louver',vs,[(0,1,3,2),(2,3,1,0)],LOUVER)
        # Wedge deck tessellates around the fan openings, generated below instead of capping them.
    centers=[(c,c)]+[(c+r*math.cos(i*math.tau/n),c+r*math.sin(i*math.tau/n)) for r,n in ((2.8,6),(5.6,12)) for i in range(n)]
    # Gridded deck skips circular fan apertures; small panels retain real holes.
    step=.28
    for ix in range(57):
        for iz in range(57):
            x=.52+ix*step;z=.52+iz*step
            if math.hypot(x-c,z-c)>7.83 or any(math.hypot(x-X,z-Z)<1.12 for X,Z in centers):continue
            box(s,'fan deck panel',(x-step/2,5.69,z-step/2),(x+step/2,5.80,z+step/2),DECK)
    ring(s,'fan deck coping',c,5.67,7.99,.20,PANEL,80);rail(s,c,7.86,5.87,80)
    rotors=[]
    for i,(x,z) in enumerate(centers):
        # Nineteen compact induced-draft cells, matching Columbia's circular arrangement.
        taper(s,'flared fan stack',(x,5.76,z),(x,7.12,z),1.31,1.24,1.16,1.12,LOUVER)
        sleeve(s,'fan bell lip',(x,7.08,z),(x,7.22,z),1.31,1.12,METAL,28)
        cylinder(s,'gearbox',(x,6.06,z),(x,6.43,z),.19,PUMP_BLUE,12)
        cylinder(s,'fan support bridge',(x-1.2,6.05,z),(x+1.2,6.05,z),.065,METAL,8)
        cylinder(s,'motor shaft',(x,6.10,z),(x+1.15,6.10,z),.037,STEEL,8)
        box(s,'fan motor',(x+1.04,5.93,z-.17),(x+1.32,6.25,z+.17),PUMP_BLUE)
        rotors.append([x,6.47,z])
        # Ribbed fill blocks below each fan and spray ring plumbing.
        for k in range(9):box(s,'fill cassette',(x-.91+k*.22,1.2,z-.95),(x-.84+k*.22,4.35,z+.95),FILL)
        cylinder(s,'spray header',(x-1,4.75,z),(x+1,4.75,z),.065,STEEL,8)
        for k in (-.65,0,.65):cylinder(s,'spray nozzle',(x+k,4.75,z),(x+k,4.5,z),.035,STEEL,6)
        for angle in (0,math.pi):
            X=x+1.28*math.cos(angle);Z=z+1.28*math.sin(angle)
            cylinder(s,'fan stack seam',(X,5.83,Z),(X,7.04,Z),.018,METAL,6)
    ladder(s,c,.53,.45,6.0)
    box(s,'service access platform',(c-.64,5.77,.33),(c+.64,5.87,1.6),DECK)
    for i in range(6):
        a=i*math.tau/6;cylinder(s,'radial water distributor',(c,4.8,c),(c+6.8*math.cos(a),4.8,c+6.8*math.sin(a)),.10,STEEL,10)
    meta=tower_ports(s,[17,8,17]);meta['rotors']=rotors
    rotor=new_scene('BWR | detailed cooling fan');fan_blades(rotor)
    for o in rotor.objects:o.scale*=.81
    export_scene(rotor,'cooling_fan.json',{'id':'cooling_fan','size':[3,1,3],'controller':[0,0,0],'ports':[]})
    r=save(s,'mechanical_draft_tower',meta,(30,21,-20),25)
    for p in rotors:
        for o in rotor.objects:
            clone=o.copy();clone.location+=xyz(p);r.collection.objects.link(clone)
    bpy.data.libraries.write(str(OUT/'mechanical_draft_tower.blend'),{s,r,rotor},fake_user=True)
    bpy.ops.render.render(write_still=True,scene=r.name)
    return r
