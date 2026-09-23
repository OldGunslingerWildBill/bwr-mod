"""Detailed original Blender pumps, informed by Flowserve VCT and KSB Etanorm sections.
Geometry is adapted to game scale and grid-centred ports; no manufacturer mesh is copied.
Run build_circulating(), build_makeup(), then build_spray_components() through Blender MCP.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/cooling/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
ENAMEL=material('pump midnight blue enamel',(.025,.10,.18),.35)
MOTOR=material('motor charcoal cast iron',(.065,.08,.095),.35)
GUARD=material('coupling guard safety ochre',(.7,.36,.025),.18)
BRASS=material('instrument brass',(.40,.26,.07),.65)
DIAL=material('instrument white dial',(.75,.78,.72),.05)

def gauge(s,name,p):
    x,y,z=p
    cylinder(s,name+' stem',(x,y-.25,z),(x,y,z),.027,BRASS,10)
    cylinder(s,name+' case',(x,y,z+.04),(x,y,z-.06),.12,STEEL,24)
    cylinder(s,name+' dial',(x,y,z-.061),(x,y,z-.065),.10,DIAL,24)
    for i in range(9):
        a=(.18+i*.2)*math.pi
        cylinder(s,name+' graduation',(x+.075*math.cos(a),y+.075*math.sin(a),z-.067),(x+.092*math.cos(a),y+.092*math.sin(a),z-.067),.004,DARK,5)
    cylinder(s,name+' pointer',(x,y,z-.071),(x-.05,y+.06,z-.071),.006,RED,5)

def eye(s,name,p,r=.11):
    x,y,z=p
    sweep(s,name,[(x+r*math.cos(a*math.tau/24),y+r*math.sin(a*math.tau/24),z) for a in range(25)],.023,STEEL,8)

def anchors(s,w,d):
    box(s,'grouted concrete foundation',(.12,.025,.12),(w-.12,.20,d-.12),CONCRETE)
    for x in (.32,w-.32):
        box(s,'channel skid rail',(x-.10,.20,.30),(x+.10,.37,d-.30),MOTOR)
        for z in (.40,d/2,d-.40):
            box(s,'anchor washer',(x-.09,.37,z-.09),(x+.09,.40,z+.09),STEEL)
            bolt(s,'anchor stud and hex nut',(x,.39,z),(x,.48,z),.065)
    for z in (.45,d/2,d-.45):box(s,'skid transverse stiffener',(.35,.2,z-.06),(w-.35,.37,z+.06),BODY)

def finish(s,id,meta,eye_pos,scale):
    meta['layout_version']=3
    # All nozzles meet a block face at its centre and retain old direction semantics.
    for port in meta['ports']:
        if port['role']=='POWER':power_port(s,port['cell'])
        else:
            face=DIRECTIONS[port['face']]
            center=Vector(port['cell'])+Vector((.5,.5,.5))+Vector(face)*.5
            water_port(s,port['role'],center,face,BLUE if port['role']=='INLET' else TEAL)
    meta['id']=id;s['layout']=json.dumps(meta)
    export_scene(s,id+'.json',meta)
    preview=new_scene('BWR | detailed '+id+' studio')
    for o in s.objects:preview.collection.objects.link(o)
    target=(meta['size'][0]/2,meta['size'][1]*.45,meta['size'][2]/2)
    studio(preview,target,eye_pos,scale,(1440,1200));viewport(preview,target,eye_pos,scale*1.12)
    bpy.data.libraries.write(str(OUT/(id+'.blend')),{s,preview},fake_user=True)
    preview.render.filepath=str(OUT/(id+'.png'))
    print(id,'meshes',sum(o.type=='MESH' for o in s.objects))
    return preview

def build_circulating():
    s=new_scene('BWR | VCT inspired circulating pump');c=2.5;anchors(s,5,5)
    # The wet-pit bowl sits in a closed suction barrel for the pipe-fed game installation.
    cylinder(s,'formed suction barrel',(c,.37,c),(c,1.8,c),1.02,ENAMEL,48)
    taper(s,'bowl reducing transition',(c,1.75,c),(c,2.35,c),1.02,.65,.9,.55,ENAMEL)
    cylinder(s,'fabricated outer column',(c,2.20,c),(c,3.45,c),.65,ENAMEL,48)
    for y,r in ((.65,1.11),(1.85,1.06),(2.4,.76),(3.05,.76)):
        scaled_flange(s,'column bolted splice',(c,y,c),(0,1,0),r,r*.70,STEEL,20)
    for i in range(8):
        a=i*math.tau/8
        p=(c+1.0*math.cos(a),.4,c+1.0*math.sin(a));q=(c+.73*math.cos(a),1.05,c+.73*math.sin(a))
        cylinder(s,'barrel mounting rib',p,q,.075,ENAMEL,6)
    # Five-mitre discharge head, a distinct feature of the reference VCT.
    path=[(c,2.95,c)]+[(c,2.95+.55*math.sin(i*math.pi/10),c+.55*(1-math.cos(i*math.pi/10))) for i in range(1,6)]+[(c,3.5,3.7)]
    hollow_path(s,'five mitre discharge elbow',path,.60,.48,ENAMEL)
    scaled_flange(s,'discharge head flange',(c,3.5,3.72),(0,0,1),.70,.48,STEEL,16)
    taper(s,'discharge grid reducer',(c,3.5,3.72),(c,3.5,4.28),.60,.25,.48,.205,ENAMEL)
    taper(s,'suction barrel nozzle',(c,1.5,.72),(c,1.5,1.7),.25,.60,.205,.48,ENAMEL)
    scaled_flange(s,'suction barrel flange',(c,1.5,1.47),(0,0,-1),.70,.48,STEEL,16)
    # Motor stool is open around the steel shaft, with access windows and tie bolts.
    for y in (3.8,4.95):scaled_flange(s,'motor stool mounting ring',(c,y,c),(0,1,0),1.04,.40,ENAMEL,16)
    cylinder(s,'polished drive shaft',(c,3.4,c),(c,5.13,c),.12,STEEL,24)
    for y in (3.6,4.05,4.75):cylinder(s,'shaft coupling ring',(c,y,c),(c,y+.18,c),.29,STEEL,32)
    for x in (1.73,3.17):
        for z in (1.73,3.17):
            box(s,'open motor stool post',(x-.09,3.75,z-.09),(x+.09,4.94,z+.09),ENAMEL)
            bolt(s,'motor stool securing bolt',(x,4.94,z),(x,5.07,z),.07)
    # Finned motor, ventilated cowling, lower bearing housing and inspection covers.
    cylinder(s,'motor lower thrust housing',(c,5.0,c),(c,5.45,c),.81,MOTOR,48)
    cylinder(s,'motor stator housing',(c,5.35,c),(c,7.18,c),.87,MOTOR,48)
    for i in range(40):
        a=i*math.tau/40
        radial=Vector((math.cos(a),0,math.sin(a)));tangent=Vector((-math.sin(a),0,math.cos(a)))
        vs=[tuple(Vector((c,y,c))+radial*r+tangent*t) for y in (5.48,7.12) for r,t in ((.84,-.012),(.99,-.012),(.99,.012),(.84,.012))]
        mesh_object(s,'radial motor cooling fin',vs,[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)],ENAMEL)
    for y in (5.4,7.17):scaled_flange(s,'motor end bell',(c,y,c),(0,1,0),.96,.72,MOTOR,16)
    cylinder(s,'fan cowling',(c,7.16,c),(c,7.65,c),.91,ENAMEL,48)
    for i in range(40):
        a=i*math.tau/40
        cylinder(s,'fan cowling ventilation slot',(c+.918*math.cos(a),7.25,c+.918*math.sin(a)),(c+.918*math.cos(a),7.52,c+.918*math.sin(a)),.026,DARK,6)
    cylinder(s,'fan cover top',(c,7.63,c),(c,7.77,c),.94,STEEL,48)
    for x in (1.85,3.15):eye(s,'lifting lug',(x,7.82,c),.10)
    box(s,'motor identification plate',(2.14,6.00,1.49),(2.86,6.30,1.52),STEEL)
    for i in range(4):box(s,'engraved nameplate line',(2.21,6.04+i*.05,1.482),(2.76,6.05+i*.05,1.489),LABEL)
    # Instrument/flush lines and realistic junction-box conduit.
    sweep(s,'seal flushing tube',[(3.05,1.2,2.35),(3.30,1.2,2.35),(3.30,4.12,2.35),(2.75,4.12,2.35)],.030,BRASS,10)
    for y in (1.6,2.8,3.8):box(s,'flush line bracket',(3.22,y,2.27),(3.38,y+.045,2.43),STEEL)
    gauge(s,'discharge pressure gauge',(3.26,4.28,3.0))
    cylinder(s,'gauge root valve',(3.26,3.86,3),(3.26,4.05,3),.055,BRASS,12)
    box(s,'motor terminal pedestal',(3.20,5.25,2.25),(4.5,5.7,2.75),MOTOR)
    sweep(s,'electrical conduit',[(4.6,5.35,2.65),(4.6,4.9,2.65),(3.55,4.9,2.65),(3.55,5.5,2.65)],.045,DARK,10)
    return finish(s,'circulating_water_pump',{'size':[5,8,5],'controller':[2,0,2],'ports':[{'role':'INLET','cell':[2,1,0],'face':'north'},{'role':'OUTLET','cell':[2,3,4],'face':'south'},{'role':'POWER','cell':[4,5,2],'face':'east'}]},(12,9,-11),11)

def build_makeup():
    s=new_scene('BWR | horizontal centrifugal makeup pump');anchors(s,3,7);x=1.5;y=1.35
    # Spiral cast volute, axial suction eye and bolted back-pullout cover.
    n=64;vs=[]
    for z in (1.25,1.87):
        for i in range(n):
            a=i*math.tau/n;r=.62+.20*i/n
            vs.append((x+r*math.cos(a),y+r*math.sin(a),z))
    mesh_object(s,'cast spiral volute',vs,[tuple(range(n-1,-1,-1)),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)],ENAMEL)
    cylinder(s,'suction casing cover',(x,y,1.13),(x,y,1.27),.59,ENAMEL,48)
    scaled_flange(s,'volute back pullout',(x,y,1.92),(0,0,1),.67,.31,STEEL,16)
    # Axial inlet rises slightly to the exact one-block pipe centre.
    taper(s,'axial suction reducer',(x,1.5,.65),(x,y,1.25),.25,.37,.205,.29,ENAMEL)
    for z in (1.20,1.78):box(s,'volute foot',(.82,.37,z),(2.18,.66,z+.18),ENAMEL)
    for xx in (.91,2.09):
        for z in (1.3,1.88):bolt(s,'pump foot bolt',(xx,.53,z),(xx,.71,z),.045)
    cylinder(s,'mechanical seal carrier',(x,y,1.9),(x,y,2.45),.29,ENAMEL,32)
    for z in (2.03,2.40):scaled_flange(s,'bearing end cover',(x,y,z),(0,0,1),.37,.15,STEEL,10)
    cylinder(s,'bearing oil housing',(x,y,2.45),(x,y,3.12),.24,MOTOR,32)
    cylinder(s,'shaft',(x,y,2.8),(x,y,4.1),.095,STEEL,20)
    for z in (3.22,3.56):cylinder(s,'flexible coupling hub',(x,y,z),(x,y,z+.16),.22,STEEL,24)
    cylinder(s,'coupling elastomer',(x,y,3.37),(x,y,3.56),.245,DARK,24)
    # Real perforated guard: separated bars, not an opaque yellow cube.
    for z in [3.1+i*.075 for i in range(12)]:
        path=[(x+.42*math.cos(i*math.pi/16),y+.42*math.sin(i*math.pi/16),z) for i in range(17)]
        sweep(s,'coupling safety grille hoop',path,.012,GUARD,6)
    for i in range(13):
        a=i*math.pi/12
        cylinder(s,'coupling safety grille rail',(x+.42*math.cos(a),y+.42*math.sin(a),3.1),(x+.42*math.cos(a),y+.42*math.sin(a),3.94),.012,GUARD,6)
    for xx in (1.05,1.95):box(s,'guard mounting leg',(xx-.025,.37,3.22),(xx+.025,1.37,3.83),GUARD)
    # Conventional horizontal TEFC motor.
    cylinder(s,'motor drive end bell',(x,y,3.95),(x,y,4.28),.53,MOTOR,40)
    cylinder(s,'motor finned stator',(x,y,4.22),(x,y,5.82),.56,MOTOR,48)
    for i in range(36):
        a=i*math.tau/36
        cylinder(s,'motor longitudinal fin',(x+.59*math.cos(a),y+.59*math.sin(a),4.25),(x+.59*math.cos(a),y+.59*math.sin(a),5.84),.027,ENAMEL,6)
    cylinder(s,'motor fan cowling',(x,y,5.84),(x,y,6.4),.59,ENAMEL,48)
    cylinder(s,'motor end grille',(x,y,6.40),(x,y,6.43),.51,DARK,48)
    for yy in [y-.42+i*.07 for i in range(13)]:
        half=math.sqrt(max(0,.49**2-(yy-y)**2))
        cylinder(s,'fan grille bar',(x-half,yy,6.44),(x+half,yy,6.44),.016,STEEL,6)
    for z in (4.4,5.6):
        box(s,'motor cast feet',(.81,.37,z),(2.19,.83,z+.22),MOTOR)
        for xx in (.91,2.09):bolt(s,'motor mounting bolt',(xx,.65,z+.11),(xx,.86,z+.11),.045)
    eye(s,'motor lifting eye',(x,2.07,5.1),.1)
    # Tangential discharge and above-motor header preserve the established south outlet.
    path=[(1.86,1.96,1.56),(1.86,2.18,1.56),(1.86,2.38,1.65),(1.75,2.5,1.87),(1.5,2.5,2.15),(1.5,2.5,6.20)]
    hollow_path(s,'radial discharge elbow and header',path,.23,.185,ENAMEL)
    scaled_flange(s,'discharge riser flange',(1.86,2.17,1.56),(0,1,0),.33,.185,STEEL,10)
    for z in (2.6,5.9):
        scaled_flange(s,'discharge header joint',(1.5,2.5,z),(0,0,1),.34,.185,STEEL,10)
    cylinder(s,'discharge header support',(1.5,.37,6.58),(1.5,2.28,6.58),.055,BODY,12)
    box(s,'motor junction box',(1.9,1.27,4.90),(2.68,1.75,5.65),MOTOR)
    sweep(s,'seal flush copper tube',[(1.18,1.5,1.8),(.99,1.5,2.1),(.99,1.86,2.5),(1.5,1.86,2.5),(1.5,1.6,2.5)],.02,BRASS,8)
    cylinder(s,'oil sight glass',(1.74,1.42,2.78),(1.85,1.42,2.78),.075,BRASS,16)
    cylinder(s,'oil reservoir',(1.16,1.44,2.72),(1.16,1.81,2.72),.055,STEEL,12)
    gauge(s,'pump pressure gauge',(.78,2.2,1.55))
    box(s,'motor nameplate',(.83,1.19,4.6),(.87,1.53,5.35),STEEL)
    for z in (4.68,4.84,5.0,5.16):box(s,'nameplate lettering',(.817,1.24,z),(.83,1.46,z+.017),LABEL)
    return finish(s,'makeup_water_pump',{'size':[3,3,7],'controller':[1,0,3],'ports':[{'role':'INLET','cell':[1,1,0],'face':'north'},{'role':'OUTLET','cell':[1,2,6],'face':'south'},{'role':'POWER','cell':[2,1,5],'face':'east'}]},(10,6,-10),9.2)

def build_spray_components():
    global OUT
    OUT=ROOT/'art/models/suppression'
    water=material('metered suppression water',(.035,.21,.29),.25)
    s=new_scene('BWR | metered pool water surface');box(s,'water volume',(0,-1,0),(1,0,1),water)
    export_scene(s,'water_surface.json',{'id':'water_surface'})
    nozzle=new_scene('BWR | suppression spray rail segment')
    cylinder(nozzle,'spray distribution rail',(0,.0,0),(1,.0,0),.06,STEEL,16)
    for x in (.25,.75):
        cylinder(nozzle,'drop neck',(x,0,0),(x,-.15,0),.035,STEEL,12)
        cylinder(nozzle,'full cone nozzle',(x,-.13,0),(x,-.20,0),.075,BRASS,16)
        cylinder(nozzle,'nozzle aperture',(x,-.201,0),(x,-.207,0),.032,DARK,12)
    export_scene(nozzle,'spray_rail.json',{'id':'spray_rail'})
    riser=new_scene('BWR | suppression spray vertical supply')
    cylinder(riser,'supply riser',(.5,0,.5),(.5,1,.5),.06,STEEL,16)
    export_scene(riser,'spray_riser.json',{'id':'spray_riser'})
    bpy.data.libraries.write(str(OUT/'pool_water_and_spray.blend'),{s,nozzle,riser},fake_user=True)
    viewport(nozzle,(.5,-.1,0),(1.5,.8,-1.4),1.7)
    print('Saved water surface, spray nozzles and vertical supply components')
