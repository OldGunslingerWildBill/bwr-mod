"""Blender sources for the connectable closed condenser and steam bypass valve.

Run build_ports() and build_bypass() separately via Blender MCP. The original
high-detail exterior/cutaway scenes are preserved. One unit is one block.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(),str(helper),'exec'))
OUT=ROOT/'art/models/condenser_ports'
OUT.mkdir(parents=True,exist_ok=True)
HOT=material('warm cooling return band',(.7,.12,.025),.12)
VALVE_BLUE=material('bypass valve blue enamel',(.018,.10,.18),.3)


def taper(s,name,a,b,outer0,outer1,inner0,inner1,mat):
    a,b=Vector(a),Vector(b);d,u,v=basis(b-a);n=24
    vs=[tuple(p+r*(math.cos(i*math.tau/n)*u+math.sin(i*math.tau/n)*v))
        for p,r in ((a,outer0),(b,outer1),(b,inner1),(a,inner0)) for i in range(n)]
    fs=[(j*n+i,j*n+(i+1)%n,((j+1)%4)*n+(i+1)%n,((j+1)%4)*n+i) for j in range(4) for i in range(n)]
    return mesh_object(s,name,vs,fs,mat)


def hollow_path(s,name,path,outer,inner,mat):
    pts=[Vector(p) for p in path];n=20;vs=[];u=None
    for j,p in enumerate(pts):
        d=(pts[min(len(pts)-1,j+1)]-pts[max(0,j-1)]).normalized()
        u=basis(d)[1] if u is None else (u-d*u.dot(d)).normalized();v=d.cross(u).normalized()
        for r in (outer,inner):vs.extend(tuple(p+r*(math.cos(i*math.tau/n)*u+math.sin(i*math.tau/n)*v)) for i in range(n))
    fs=[]
    for j in range(len(pts)-1):
        for layer in (0,1):
            a=j*2*n+layer*n;b=a+2*n
            fs.extend((a+i,a+(i+1)%n,b+(i+1)%n,b+i) for i in range(n))
    for j in (0,len(pts)-1):
        a=j*2*n;fs.extend((a+i,a+(i+1)%n,a+n+(i+1)%n,a+n+i) for i in range(n))
    return mesh_object(s,name,vs,fs,mat)


def seal_shell_transition(s, center=3.5, center_z=3.5):
    """Seat the tapered neck on the actual lower shell, including its corners."""
    def bounds(o):
        # Mesh edits have not passed through Blender's dependency graph yet;
        # bound_box can still describe the unscaled source at this point.
        points=[game(o.matrix_world@v.co) for v in o.data.vertices]
        return [min(p[a] for p in points) for a in range(3)]+[max(p[a] for p in points) for a in range(3)]
    ends=[bounds(o) for o in s.objects if o.type=='MESH' and 'lower shell face' in o.name]
    sides=[bounds(o) for o in s.objects if o.type=='MESH' and 'shell side' in o.name]
    # An overlap hides the seam at grazing angles; matching the casing's depth
    # also closes the gap that a vertical-only extension would leave behind it.
    low_x=min(b[0] for b in sides);high_x=max(b[3] for b in sides)
    low_z=min(b[2] for b in ends);high_z=max(b[5] for b in ends)
    join_y=min(b[4] for b in ends)-.08
    for o in s.objects:
        if o.type!='MESH' or not o.name.startswith('LP exhaust transition panel'):continue
        for i in (0,1,4,5):
            v=o.data.vertices[i];x,y,z=game(v.co);skin=.07 if i>=4 else 0
            v.co=xyz((low_x+skin if x<center else high_x-skin,
                      join_y,low_z+skin if z<center_z else high_z-skin))
        o.data.update()


def build_legacy_ports():
    source=bpy.data.scenes.get('BWR | Arabelle 1700 condenser exterior')
    if source is None:
        with bpy.data.libraries.load(str(ROOT/'art/models/condenser_msiv/arabelle_1700_condenser.blend')) as (src,dst):
            dst.scenes=['BWR | Arabelle 1700 condenser exterior']
        source=dst.scenes[0]
    s=new_scene('BWR | Connectable condenser exterior')
    # Internal inspection geometry stays in the original file, saving in-game draw cost.
    skip=('titanium tubes','perforated tube sheets','tube support','bundle tie','shell stay',
          'neck feedwater heater','heater head','dished heater cover','heater instrument elbow',
          'CW elbow','CW nozzle','hotwell drain','condensate outlet')
    for o in source.objects:
        if o.type!='MESH' or any(k in o.name for k in skip):continue
        clone=o.copy();clone.data=o.data.copy();s.collection.objects.link(clone)
        if 'heater support ring' in o.name or 'upper service riser' in o.name:
            clone.location+=xyz((.5,-.5,0))
    ports=[]
    def endpoint(role,cell,face,bay):
        ports.append({'role':role,'cell':cell,'face':face,'bay':bay})
        p=Vector(cell)+Vector((.5,.5,.5))+Vector(DIRECTIONS[face])*.5
        marker(s,f'Bay {bay} | {role} {len(ports)}',p,role=role,cell=cell,face=face)
    for bay,cx in enumerate((4,12,20),1):
        prefix=f'Bay {bay} | '
        # Convert the circled former heater-head locations into open steam nozzles.
        sleeve(s,prefix+'bypass diffusion barrel',(cx+.5,10.5,2.90),(cx+.5,10.5,18.60),.78,.68,STEEL,36)
        scaled_flange(s,prefix+'bypass barrel joint',(cx+.5,10.5,2.90),(0,0,-1),.90,.68,STEEL,20)
        taper(s,prefix+'bypass inlet reducer',(cx+.5,10.5,2.12),(cx+.5,10.5,2.9),.25,.78,.205,.68,STEEL)
        steam_flange(s,prefix+'bypass steam inlet',(cx+.5,10.5,2),(0,0,-1))
        sleeve(s,prefix+'bypass steam band',(cx+.5,10.5,2.15),(cx+.5,10.5,2.3),.29,.21,AMBER,24)
        endpoint('BYPASS',[cx,10,2],'north',bay)
        for hot in (True,False):
            zbase=.5 if hot else 21.5;zc=1.5 if hot else 20.5;sign=-1 if hot else 1
            for dx in (-1.5,1.5):
                x=cx+dx;role='HOT' if hot else 'COLD';label=prefix+role+' cooling water'
                start=2.21 if hot else 20.25
                path=[(x,5.9,start)]+[(x,4.9+math.cos(t*math.pi/32),zc+sign*math.sin(t*math.pi/32)) for t in range(17)]+[(x,1.55,zbase)]
                hollow_path(s,label+' elbow',path,.55,.46,STEEL)
                taper(s,label+' bottom reducer',(x,1.55,zbase),(x,1.10,zbase),.55,.25,.46,.205,STEEL)
                steam_flange(s,label+' connection',(x,1,zbase),(0,-1,0))
                sleeve(s,label+' identification band',(x,1.8,zbase),(x,2.0,zbase),.558,.548,HOT if hot else BLUE,24)
                # The large chamber flange remains at the shell wall.
                scaled_flange(s,label+' waterbox',(x,5.9,start),(0,0,-sign),.69,.46,STEEL,20)
                endpoint(role,[int(x),1,int(zbase)],'down',bay)
        # Separate condensate outlet; never joins the circulating-water inventory.
        sleeve(s,prefix+'condensate neck',(cx+.5,2.5,19.3),(cx+.5,2.5,22.9),.25,.205,STEEL,24)
        steam_flange(s,prefix+'condensate outlet',(cx+.5,2.5,23),(0,0,1))
        endpoint('CONDENSATE',[cx,2,22],'south',bay)
    # A half-metre-centred large CW elbow is wider than one block. Reserve an
    # extra front/rear row so its outside wall never enters an unowned block.
    for o in s.objects:
        o.location+=xyz((0,0,1))
        if 'cell' in o:o['cell']=[int(o['cell'][0]),int(o['cell'][1]),int(o['cell'][2])+1]
    for port in ports:port['cell'][2]+=1
    meta={'id':'arabelle_condenser','size':[25,14,25],'controller':[1,0,5],'ports':ports,
          'reference_plant_mwe':1700,'reference_heat_rejection_mw':2750,'cutaway':False}
    s['layout']=json.dumps(meta)
    s['description']='Closed exterior. Front bypass steam in / front hot CW out / rear cold CW in / separate condensate out.'
    export_scene(s,'arabelle_condenser_legacy.json',meta)
    review=new_scene('BWR | Condenser ports studio')
    for o in s.objects:review.collection.objects.link(o)
    studio(review,(12,6.7,10.7),(42,26,-29),36)
    viewport(review,(12,6.7,10.7),(42,26,-29),41)
    bpy.data.libraries.write(str(OUT/'condenser_ports_legacy.blend'),{s,review},fake_user=True)
    print('Condenser ready:',len(ports),'grid-aligned connections')


def build_ports():
    """Transverse condenser: wall-facing cooling ports and round hotwell makeup sockets."""
    source=bpy.data.scenes.get('BWR | Arabelle 1700 condenser exterior')
    if source is None:
        with bpy.data.libraries.load(str(ROOT/'art/models/condenser_msiv/arabelle_1700_condenser.blend')) as (src,dst):
            dst.scenes=['BWR | Arabelle 1700 condenser exterior']
        source=dst.scenes[0]
    lp=bpy.data.scenes.get('BWR | lp_turbine')
    if lp is None:
        with bpy.data.libraries.load(str(ROOT/'art/models/power_turbines/power_turbines.blend')) as (src,dst):
            dst.scenes=['BWR | lp_turbine']
        lp=dst.scenes[0]
    # Use the real LP skid and lower casing, not estimates from a photograph.
    def game_bounds(o):
        points=[game(o.matrix_world@Vector(p)) for p in o.bound_box]
        return [min(p[a] for p in points) for a in range(3)]+[max(p[a] for p in points) for a in range(3)]
    skid=game_bounds(next(o for o in lp.objects if o.name.startswith('foundation skid')))
    casing=game_bounds(next(o for o in lp.objects if o.name.startswith('large lower exhaust casing')))
    s=new_scene('BWR | Transverse LP condenser')
    skip=('titanium tubes','perforated tube sheets','tube support','bundle tie','shell stay',
          'neck feedwater heater','heater head','dished heater cover','heater instrument elbow',
          'heater support ring','upper service riser','exhaust neck','expansion joint',
          'CW elbow','CW nozzle','hotwell drain','condensate outlet','shell inspection manway')
    def compress_y(y):
        levels=[(0,0),(.5,.3),(2.5,1),(8.8,3.5),(9.75,4),(14,6)]
        for (a,b),(c,d) in zip(levels,levels[1:]):
            if y<=c:return b+(y-a)*(d-b)/(c-a)
        return 6
    for o in source.objects:
        if o.type!='MESH' or not o.name.startswith('Bay 1 | ') or any(k in o.name for k in skip):continue
        clone=o.copy();clone.data=o.data.copy();clone.matrix_world=Matrix.Identity(4);s.collection.objects.link(clone)
        for vert in clone.data.vertices:
            x,y,z=game(o.matrix_world@vert.co)
            vert.co=xyz((.7+x*.7,compress_y(y),1.18+(z-2.15)*6.64/18.16))
        clone.data.update()
    box(s,'center foundation crossbeam',(.5,.18,4.1),(6.5,.43,4.9),CONCRETE)
    # Shaped transition matches the LP lower exhaust casing; seating rim matches its skid.
    lo=(1.1,3.75,1.35,5.9,4.65,7.65)
    # Rotate the real LP -90 degrees about its centre and centre it above this seat.
    def rotate_bounds(b):return [b[2],b[1],8-b[3],b[5],b[4],8-b[0]]
    skid=rotate_bounds(skid);casing=rotate_bounds(casing)
    x0,z0,x1,z1=casing[0],casing[2],casing[3],casing[5]
    vertices=[(lo[0],lo[1],lo[2]),(lo[3],lo[1],lo[2]),(lo[3],lo[1],lo[5]),(lo[0],lo[1],lo[5]),
              (x0,5.72,z0),(x1,5.72,z0),(x1,5.72,z1),(x0,5.72,z1)]
    # Four panel faces with a thin inner skin; no cutaway panels or projecting chimneys.
    for i in range(4):
        j=(i+1)%4;outer=[vertices[i],vertices[j],vertices[j+4],vertices[i+4]]
        inner=[(x+(0.07 if x<3.5 else -.07),y,z+(0.07 if z<4.5 else -.07)) for x,y,z in outer]
        mesh_object(s,'LP exhaust transition panel',outer+inner,[(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],NAVY)
    seal_shell_transition(s,3.5,4.5)
    for z in (z0,z1-.1):box(s,'LP neck gasket',(x0,5.72,z),(x1,5.89,z+.1),DARK)
    for x in (x0,x1-.1):box(s,'LP neck gasket',(x,5.72,z0+.1),(x+.1,5.89,z1-.1),DARK)
    for z,Z in ((skid[2],z0),(z1,skid[5])):box(s,'LP seating deck',(skid[0],5.89,z),(skid[3],6,Z),BODY)
    for x,X in ((skid[0],x0),(x1,skid[3])):box(s,'LP seating deck',(x,5.89,z0),(X,6,z1),BODY)
    for x in (.24,6.76):
        for z in (2,3,4,5,6,7):bolt(s,'LP seating stud',(x,5.87,z),(x,5.99,z),.04)
    ports=[]
    def endpoint(role,cell,face):
        ports.append({'role':role,'cell':cell,'face':face,'bay':1})
        p=Vector(cell)+Vector((.5,.5,.5))+Vector(DIRECTIONS[face])*.5
        marker(s,role+' port',p,role=role,cell=cell,face=face)
    steam_flange(s,'bypass steam inlet',(3.5,4.5,0),(0,0,-1))
    taper(s,'bypass steam reducer',(3.5,4.5,.12),(3.5,4.5,.9),.25,.38,.205,.31,STEEL)
    sleeve(s,'bypass inlet spool',(3.5,4.5,.9),(3.5,4.5,1.65),.38,.31,STEEL,24)
    sleeve(s,'bypass identifier',(3.5,4.5,.75),(3.5,4.5,.84),.38,.36,AMBER,24)
    endpoint('BYPASS',[3,4,0],'north')
    for hot in (True,False):
        role='HOT' if hot else 'COLD';sign=-1 if hot else 1;zc=1.05 if hot else 7.95;end=.5 if hot else 8.5
        for x in (1.5,5.5):
            name=role+' cooling water '+str(x)
            path=[(x,2.5,1.30 if hot else 7.70)]+[(x,1.95+.55*math.cos(i*math.pi/32),zc+sign*.55*math.sin(i*math.pi/32)) for i in range(17)]+[(x,1.35,end)]
            hollow_path(s,name+' elbow',path,.34,.265,STEEL)
            taper(s,name+' reducer',(x,1.35,end),(x,1.12,end),.34,.25,.265,.205,STEEL)
            steam_flange(s,name+' flange',(x,1,end),(0,-1,0))
            sleeve(s,name+' band',(x,1.55,end),(x,1.69,end),.35,.338,HOT if hot else BLUE,20)
            endpoint(role,[int(x),1,int(end)],'down')
    sleeve(s,'condensate outlet neck',(3.5,1.5,7.4),(3.5,1.5,8.88),.25,.205,STEEL,24)
    steam_flange(s,'condensate outlet flange',(3.5,1.5,9),(0,0,1))
    endpoint('CONDENSATE',[3,1,8],'south')
    # Rebuild these fittings after resizing the shell, so every section stays circular.
    # The side wall is inset: the cell immediately west remains free for a pipe,
    # even when two LP foundations are only seven blocks apart along the shaft.
    for z in (2.5,6.5):
        name='hotwell makeup '+str(z)
        sleeve(s,name+' neck',(1.12,2.5,z),(1.6,2.5,z),.25,.205,STEEL,32)
        steam_flange(s,name+' open flange',(1,2.5,z),(-1,0,0))
        sleeve(s,name+' blue band',(1.17,2.5,z),(1.25,2.5,z),.258,.245,BLUE,32)
        endpoint('MAKEUP',[1,2,int(z)],'west')
    # Narrow service platform remains inside the module footprint.
    for i in range(29):box(s,'service grating',(.4+i*.214,3.43,.79),(.44+i*.214,3.5,1.23),STEEL)
    for x in (.38,2,3.5,5,6.62):cylinder(s,'guardrail upright',(x,3.5,.77),(x,4.07,.77),.025,STEEL,8)
    for y in (3.8,4.06):cylinder(s,'guardrail',(.38,y,.77),(6.62,y,.77),.023,STEEL,10)
    for x in (6.1,6.65):cylinder(s,'service ladder upright',(x,.25,1.05),(x,3.50,1.05),.03,STEEL,10)
    for y in [.45+i*.27 for i in range(11)]:cylinder(s,'service ladder rung',(6.1,y,1.05),(6.65,y,1.05),.022,STEEL,8)
    meta={'id':'arabelle_condenser','size':[7,6,9],'controller':[3,0,4],'ports':ports,'layout_version':4,
          'lp_root_offset':[0,6,0],'lp_relative_facing':'west','reference_plant_mwe':1700,'reference_heat_rejection_mw':2750,'cutaway':False}
    s['layout']=json.dumps(meta);s['description']='One closed condenser per LP, perpendicular to the shaft. Eight ports; two round side makeup inlets feed the hotwell.'
    export_scene(s,'arabelle_condenser.json',meta)
    review=new_scene('BWR | Transverse condenser studio')
    for o in s.objects:review.collection.objects.link(o)
    studio(review,(3.5,2.8,4.5),(-13,9,-13),13,(1400,1100))
    fitted=new_scene('BWR | LP and transverse fitted condenser')
    for o in s.objects:fitted.collection.objects.link(o)
    for o in lp.objects:
        if o.type!='MESH':continue
        clone=o.copy();clone.data=o.data.copy();clone.matrix_world=Matrix.Identity(4);fitted.collection.objects.link(clone)
        for vert in clone.data.vertices:
            x,y,z=game(o.matrix_world@vert.co);vert.co=xyz((z,y+6,8-x))
        clone.data.update()
    studio(fitted,(3.5,5.2,4.5),(-16,13,-16),17,(1200,1400))
    viewport(fitted,(3.5,5.2,4.5),(-16,13,-16),22)
    bpy.data.libraries.write(str(OUT/'condenser_ports.blend'),{s,review,fitted},fake_user=True)
    print('Transverse condenser:',len(ports),'ports; LP root exactly 6 blocks above condenser root; source bounds',skid,casing)


def build_bypass():
    s=new_scene('BWR | Steam bypass control valve')
    sleeve(s,'bypass globe body',(.5,.5,.25),(.5,.5,.75),.265,.205,VALVE_BLUE,28)
    for z,d in ((0,(0,0,-1)),(1,(0,0,1))):
        steam_flange(s,'bypass flange '+str(z),(.5,.5,z),d)
        sleeve(s,'steam neck '+str(z),(.5,.5,.12 if z==0 else .75),(.5,.5,.25 if z==0 else .88),.25,.205,VALVE_BLUE,24)
    cylinder(s,'globe bonnet',(.5,.64,.5),(.5,.79,.5),.155,VALVE_BLUE,24)
    cylinder(s,'bonnet retaining ring',(.5,.73,.5),(.5,.77,.5),.20,STEEL,24)
    for i in range(8):
        a=i*math.tau/8;x=.5+.17*math.cos(a);z=.5+.17*math.sin(a)
        bolt(s,'bonnet stud',(x,.73,z),(x,.785,z),.018)
    cylinder(s,'stem',(.5,.77,.5),(.5,.87,.5),.036,STEEL,12)
    for x in (.39,.61):box(s,'actuator yoke',(x-.02,.775,.465),(x+.02,.875,.535),BODY)
    cylinder(s,'diaphragm actuator',(.5,.865,.5),(.5,.96,.5),.21,VALVE_BLUE,32)
    sleeve(s,'actuator split flange',(.5,.90,.5),(.5,.93,.5),.23,.205,STEEL,32)
    for i in range(10):
        a=i*math.tau/10;x=.5+.212*math.cos(a);z=.5+.212*math.sin(a)
        bolt(s,'diaphragm retaining bolt',(x,.895,z),(x,.94,z),.014)
    box(s,'positioner',(.70,.72,.42),(.91,.86,.60),STEEL)
    box(s,'positioner face',(.905,.746,.45),(.92,.83,.57),LABEL)
    hollow_path(s,'actuator signal line',[(.81,.85,.50),(.81,.89,.50),(.71,.93,.50)],.017,.01,BODY)
    sleeve(s,'amber bypass identifier',(.5,.5,.22),(.5,.5,.30),.272,.255,AMBER,24)
    meta={'id':'bypass_steam_valve','size':[1,1,1],'controller':[0,0,0],
          'ports':[{'role':'STEAM_INLET','cell':[0,0,0],'face':'north'},{'role':'STEAM_EXHAUST','cell':[0,0,0],'face':'south'}]}
    s['layout']=json.dumps(meta);export_scene(s,'bypass_steam_valve.json',meta)
    review=new_scene('BWR | Steam bypass valve studio')
    for o in s.objects:review.collection.objects.link(o)
    studio(review,(.5,.53,.5),(3.1,2.4,-3.3),1.5,(1100,1100))
    for o in review.objects:
        if o.type=='LIGHT':o.location*=.09;o.data.energy*=.009;o.data.size*=.09
    viewport(review,(.5,.53,.5),(3.1,2.4,-3.3),2)
    bpy.data.libraries.write(str(OUT/'bypass_steam_valve.blend'),{s,review},fake_user=True)
    print('Bypass valve saved')
