"""Original Blender geometry from the user's reference photographs.

Run in Blender via MCP, one build function at a time. Does not clear existing
scenes. Coordinates are Minecraft X/Y/Z; one unit is one metre/block.
Arabelle data constrain tube length and height, not undocumented shell dimensions.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/modern/build_models.py'
exec(compile(helper.read_text().split('def build_pump(id):')[0], str(helper), 'exec'))
OUT = ROOT / 'art/models/condenser_msiv'
OUT.mkdir(parents=True, exist_ok=True)
RED = material('MSIV red enamel', (.40, .027, .018), .20)
NAVY = material('condenser blue enamel', (.025, .115, .19), .35)
TITANIUM = material('titanium tube bundle', (.38, .43, .46), .82)
CONCRETE = material('foundation concrete', (.17, .19, .20), .0)
LABEL = material('identification plate', (.025, .032, .039), .25)


def box(s, name, low, high, mat):
    x,y,z=low; X,Y,Z=high
    return mesh_object(s,name,[(x,y,z),(X,y,z),(X,Y,z),(x,Y,z),(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)],
                       [(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)],mat)


def cylinder(s, name, a, b, r, mat, n=24):
    a,b=Vector(a),Vector(b);d,u,v=basis(b-a)
    vs=[tuple(p+r*(math.cos(i*math.tau/n)*u+math.sin(i*math.tau/n)*v)) for p in (a,b) for i in range(n)]
    return mesh_object(s,name,vs,[tuple(range(n-1,-1,-1)),tuple(range(n,2*n))]+
                       [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)],mat)


def sweep(s, name, path, r, mat, n=10):
    """Parallel transported circular section; also suitable for helical springs."""
    pts=[Vector(p) for p in path];vs=[];u=None
    for j,p in enumerate(pts):
        d=(pts[min(len(pts)-1,j+1)]-pts[max(0,j-1)]).normalized()
        if u is None:u=basis(d)[1]
        else:u=(u-d*u.dot(d)).normalized()
        v=d.cross(u).normalized()
        vs.extend(tuple(p+r*(math.cos(i*math.tau/n)*u+math.sin(i*math.tau/n)*v)) for i in range(n))
    fs=[(j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i) for j in range(len(pts)-1) for i in range(n)]
    fs += [tuple(range(n-1,-1,-1)),tuple(range((len(pts)-1)*n,len(pts)*n))]
    return mesh_object(s,name,vs,fs,mat)


def scaled_flange(s,name,p,d,r,bore,mat=STEEL,count=16):
    p=Vector(p);d,u,v=basis(d)
    sleeve(s,name+' flange',p-d*.12,p,r,bore,mat,32)
    sleeve(s,name+' sealing ring',p-d*.15,p-d*.12,r*.96,bore,DARK,32)
    for i in range(count):
        q=p+r*.84*(math.cos(i*math.tau/count)*u+math.sin(i*math.tau/count)*v)
        bolt(s,name+' bolt',q-d*.20,q+d*.035,r*.055)


def marker(s,name,p,**data):
    o=bpy.data.objects.new(name,None);o.location=xyz(p);s.collection.objects.link(o)
    for k,v in data.items():o[k]=v
    return o


def steam_flange(s,name,p,axis):
    p=Vector(p);d,u,v=basis(axis)
    sleeve(s,name+' flange',p-d*.12,p-d*.025,.40,.205,STEEL,24)
    # The sealing face is inside the bolt circle; an oversized black gasket
    # would conceal all of the visible fasteners.
    sleeve(s,name+' gasket',p-d*.025,p,.27,.205,DARK,24)
    for i in range(12):
        q=p+.324*(math.cos(i*math.tau/12)*u+math.sin(i*math.tau/12)*v)
        bolt(s,name+' stud '+str(i),q-d*.14,q-d*.006,.026)


def viewport(s, target, eye, distance):
    bpy.context.window.scene=s
    for area in bpy.context.screen.areas:
        if area.type=='VIEW_3D':
            space=area.spaces.active
            if 'SOLID' in [i.identifier for i in space.shading.bl_rna.properties['type'].enum_items]:space.shading.type='SOLID'
            if 'MATERIAL' in [i.identifier for i in space.shading.bl_rna.properties['color_type'].enum_items]:space.shading.color_type='MATERIAL'
            space.overlay.show_overlays=False
            space.region_3d.view_rotation=(xyz(target)-xyz(eye)).to_track_quat('-Z','Y')
            space.region_3d.view_location=xyz(target);space.region_3d.view_distance=distance
            if 'PERSP' in [i.identifier for i in space.region_3d.bl_rna.properties['view_perspective'].enum_items]:space.region_3d.view_perspective='PERSP'


def studio(s,target,eye,size,resolution=(1600,1100)):
    cam=bpy.data.cameras.new(s.name+' camera');o=bpy.data.objects.new(cam.name,cam);s.collection.objects.link(o)
    o.location=xyz(eye);o.rotation_euler=(xyz(target)-o.location).to_track_quat('-Z','Y').to_euler()
    if 'ORTHO' in [i.identifier for i in cam.bl_rna.properties['type'].enum_items]:cam.type='ORTHO'
    cam.ortho_scale=size;cam.clip_end=1000;s.camera=o
    for label,p,power,area in [('key',(-10,32,-16),48000,16),('fill',(40,22,5),34000,14),('rim',(10,30,30),50000,12)]:
        data=bpy.data.lights.new(s.name+' '+label,type='AREA');data.energy=power;data.size=area
        if 'DISK' in [i.identifier for i in data.bl_rna.properties['shape'].enum_items]:data.shape='DISK'
        light=bpy.data.objects.new(data.name,data);s.collection.objects.link(light);light.location=xyz(p)
        light.rotation_euler=(xyz(target)-light.location).to_track_quat('-Z','Y').to_euler()
    world=bpy.data.worlds.new(s.name+' studio');world.use_nodes=True
    next(n for n in world.node_tree.nodes if n.type=='BACKGROUND').inputs['Color'].default_value=(.20,.23,.27,1)
    next(n for n in world.node_tree.nodes if n.type=='BACKGROUND').inputs['Strength'].default_value=.45;s.world=world
    s.render.resolution_x,s.render.resolution_y=resolution;s.render.resolution_percentage=100
    if 'PNG' in [i.identifier for i in s.render.image_settings.bl_rna.properties['file_format'].enum_items]:s.render.image_settings.file_format='PNG'
    s.render.film_transparent=False


def build_msiv():
    s=new_scene('BWR | MSIV reference model')
    s['description']='Original spring-return MSIV, photographed exterior reference; 1 x 3 x 1 block assembly.'
    # Continuous open bore: both flange faces meet the existing round pipe exactly.
    sleeve(s,'cast valve body',(.5,.46,.14),(.5,.46,.86),.31,.205,RED,32)
    steam_flange(s,'north steam',(.5,.46,0),(0,0,-1))
    steam_flange(s,'south steam',(.5,.46,1),(0,0,1))
    sleeve(s,'north neck',(.5,.46,.025),(.5,.46,.27),.258,.205,RED,24)
    sleeve(s,'south neck',(.5,.46,.73),(.5,.46,.975),.258,.205,RED,24)
    # Pipe centre Y=.5 is mandatory for adjacent one-block tube connections.
    for o in s.objects:o.location.z+=.04
    d=Vector((0,1,-.15)).normalized();base=Vector((.5,.66,.62));u=Vector((1,0,0));v=d.cross(u)
    at=lambda t:base+d*t
    cylinder(s,'angled Y-pattern bonnet',at(0),at(.32),.255,RED,28)
    scaled_flange(s,'bonnet',at(.35),d,.30,.13,RED,12)
    cylinder(s,'stem packing gland',at(.34),at(.65),.13,STEEL,24)
    cylinder(s,'valve stem',at(.58),at(1.31),.052,STEEL,16)
    for t in (.60,1.36):
        p=at(t)
        # Yokes are square plates perpendicular to the slightly inclined stem.
        vs=[tuple(p+u*x+v*z+d*y) for y in (-.042,.042) for x,z in ((-.32,-.24),(.32,-.24),(.32,.24),(-.32,.24))]
        mesh_object(s,'red spring yoke',vs,[(3,2,1,0),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)],RED)
    for x in (-.235,.235):
        for z in (-.16,.16):
            offset=x*u+z*v
            cylinder(s,'spring guide rod',at(.60)+offset,at(1.36)+offset,.024,STEEL,10)
            turns=11;steps=turns*12
            path=[tuple(at(.65+.66*j/steps)+offset+.064*(math.cos(j*turns*math.tau/steps)*u+math.sin(j*turns*math.tau/steps)*v)) for j in range(steps+1)]
            sweep(s,'exposed return spring',path,.0155,STEEL,6)
            bolt(s,'guide rod retaining nut',at(1.36)+offset,at(1.42)+offset,.038)
    cylinder(s,'actuator lower cylinder',at(1.40),at(1.64),.21,STEEL,24)
    cylinder(s,'actuator barrel',at(1.64),at(2.04),.24,STEEL,28)
    for t in (1.63,2.04):
        cylinder(s,'actuator end cap',at(t-.035),at(t+.035),.275,STEEL,8)
    for x,z in ((-.20,-.20),(.20,-.20),(.20,.20),(-.20,.20)):
        offset=x*u+z*v
        cylinder(s,'black actuator tie rod',at(1.62)+offset,at(2.08)+offset,.020,DARK,8)
        bolt(s,'actuator tie nut',at(2.065)+offset,at(2.105)+offset,.033)
    for x in (-.21,.21):
        p=at(2.07)+u*x
        path=[tuple(p+Vector((.052*math.cos(t*math.tau/24),.054+.052*math.sin(t*math.tau/24),0))) for t in range(25)]
        sweep(s,'lifting eye',path,.014,STEEL,8)
    box(s,'position transmitter',(.82,.92,.34),(.98,1.16,.54),LABEL)
    box(s,'manufacturer nameplate',(.38,.69,.10),(.62,.78,.12),STEEL)
    for x in (.21,.72):box(s,'mounting foot',(x,.04,.31),(x+.07,.28,.69),BODY)
    marker(s,'STEAM_INLET',(.5,.5,0),face='north',cell=[0,0,0])
    marker(s,'STEAM_EXHAUST',(.5,.5,1),face='south',cell=[0,0,0])
    meta={'id':'msiv','size':[1,3,1],'controller':[0,0,0],'stroke_seconds':4,
          'ports':[{'role':'STEAM_INLET','cell':[0,0,0],'face':'north'},{'role':'STEAM_EXHAUST','cell':[0,0,0],'face':'south'}]}
    s['layout']=json.dumps(meta);export_scene(s,'msiv.json',meta)
    # Separate render scene keeps cameras/studio out of the game export.
    review=new_scene('BWR | MSIV studio')
    for o in s.objects:review.collection.objects.link(o)
    studio(review,(.5,1.4,.5),(4,2.7,-5),3.7,(1000,1200))
    # Small model needs proportionally smaller softboxes.
    for o in review.objects:
        if o.type=='LIGHT':o.location*=.12;o.data.energy*=.012;o.data.size*=.12
    viewport(review,(.5,1.4,.5),(4,2.7,-5),4.4)
    bpy.data.libraries.write(str(OUT/'msiv.blend'),{s,review},fake_user=True)
    print('MSIV saved; meshes',sum(o.type=='MESH' for o in s.objects))
    return s


def bundle(s,name,cx):
    """Representative editable tube array; not 115,000 m2 of literal game mesh."""
    vertices=[];faces=[];sheet_v=[];sheet_f=[];n=10
    # Two banks with a central steam lane. Tubes are 15.5 m long.
    for side in (-1,1):
        for row in range(15):
            for col in range(9):
                x=cx+side*(.38+col*.27);y=4.00+row*.27
                if (row<2 or row>12) and col>6:continue
                start=len(vertices);r=.047
                vertices += [(x+r*math.cos(i*math.tau/n),y+r*math.sin(i*math.tau/n),z) for z in (3.5,19.0) for i in range(n)]
                faces += [(start+i,start+(i+1)%n,start+(i+1)%n+n,start+i+n) for i in range(n)]
                # Actual apertures in both tube sheets, with square cell borders.
                for z in (3.48,19.02):
                    k=len(sheet_v)
                    for radius in (r,.137):
                        for i in range(n):
                            a=i*math.tau/n;dx=math.cos(a);dy=math.sin(a)
                            scale=radius if radius==r else radius/max(abs(dx),abs(dy))
                            sheet_v.append((x+scale*dx,y+scale*dy,z))
                    sheet_f += [(k+i,k+(i+1)%n,k+(i+1)%n+n,k+i+n) for i in range(n)]
    mesh_object(s,name+' titanium tubes',vertices,faces,TITANIUM)
    mesh_object(s,name+' perforated tube sheets',sheet_v,sheet_f,STEEL)
    for z in (6,9,12,15,17.5):
        # Interrupted supports expose the tube forest in the cutaway.
        for x in (cx-2.60,cx,cx+2.60):box(s,name+' tube support',(x-.045,3.6,z-.05),(x+.045,8.05,z+.05),BODY)
        for y in (3.6,5.85,8.0):box(s,name+' bundle tie',(cx-2.65,y,z-.05),(cx+2.65,y+.07,z+.05),BODY)


def build_condenser():
    s=new_scene('BWR | Arabelle 1700 condenser exterior')
    s['reference']='Arabelle Solutions 1,700 MWe reference: 2,750 MW heat rejection, 15.5 m tubes, 14 m hotwell-to-turbine height.'
    s['dimensions_note']='24.8 m wide x 14 m high x 21.5 m deep overall is an artistic envelope; only tube length and height are sourced.'
    s['model_status']='Detailed visual reference. Port markers describe intended interfaces; no condenser simulation is implemented by this file.'
    metric=[i.identifier for i in s.unit_settings.bl_rna.properties['system'].enum_items]
    if 'METRIC' in metric:s.unit_settings.system='METRIC'
    s.unit_settings.scale_length=1
    remove_for_cutaway=[]
    for bay,cx in enumerate((4.0,12.0,20.0),1):
        prefix='Bay '+str(bay)+' | '
        # Stiffened hotwell and independent concrete foundations.
        for x in (cx-2.75,cx+2.75):
            for z in (4.1,17.8):
                box(s,prefix+'concrete foundation',(x-.55,0,z-.8),(x+.55,1.75,z+.8),CONCRETE)
                box(s,prefix+'sliding support shoe',(x-.62,1.72,z-.86),(x+.62,1.88,z+.86),STEEL)
        box(s,prefix+'hotwell floor',(cx-3.0,1.8,3.05),(cx+3.0,2.0,19.4),NAVY)
        for x in (cx-3.05,cx+2.95):box(s,prefix+'hotwell wall',(x,2,3.05),(x+.1,3.50,19.4),NAVY)
        for z in (3.05,19.30):box(s,prefix+'hotwell end',(cx-3,2,z),(cx+3,3.5,z+.1),NAVY)
        for z in [3.1+i*1.02 for i in range(17)]:
            for x in (cx-3.15,cx+3.05):box(s,prefix+'hotwell stiffener',(x,2.04,z),(x+.1,3.46,z+.10),BODY)
        # Longitudinal shell; cutaway removes only the right bay's near/front panels.
        for side,x in enumerate((cx-3.30,cx+3.18)):
            ob=box(s,prefix+'shell side',(x,3.45,3.2),(x+.12,10.0,19.35),NAVY)
            if bay==3 and side==1:remove_for_cutaway.append(ob)
        for z in (3.2,19.23):
            ob=box(s,prefix+'lower shell face',(cx-3.18,3.45,z),(cx+3.18,8.70,z+.12),NAVY)
            if bay==3 and z==3.2:remove_for_cutaway.append(ob)
        for z in [3.25+i*1.45 for i in range(12)]:
            for x in (cx-3.38,cx+3.28):
                o=box(s,prefix+'shell external rib',(x,3.50,z),(x+.10,10.05,z+.12),NAVY)
                if bay==3 and x>cx:remove_for_cutaway.append(o)
        # Large rectangular exhaust neck, open at the turbine interface.
        for x in (cx-3.20,cx+3.08):
            ob=box(s,prefix+'exhaust neck side',(x,9.75,4),(x+.12,13.86,18.6),NAVY)
            if bay==3 and x>cx:remove_for_cutaway.append(ob)
        for z in (4,18.48):
            ob=box(s,prefix+'exhaust neck end',(cx-3.1,9.75,z),(cx+3.1,13.86,z+.12),NAVY)
            if bay==3 and z==4:remove_for_cutaway.append(ob)
        for x in (cx-3.3,cx+3.06):box(s,prefix+'expansion joint side',(x,13.86,3.90),(x+.24,14,18.72),DARK)
        for z in (3.90,18.48):box(s,prefix+'expansion joint end',(cx-3.08,13.86,z),(cx+3.06,14,z+.24),DARK)
        # Stay plates between steam neck and bundles.
        for z in (4.5,7.5,10.5,13.5,16.5,18.0):
            for x in (cx-2.9,cx+2.9):cylinder(s,prefix+'shell stay',(x,8.4,z),(x,12.7,z),.045,STEEL,10)
        bundle(s,prefix,cx)
        # Two circulating-water chambers, with bolted removable covers.
        for end,z in (('front',2.15),('rear',19.35)):
            for dx in (-1.58,1.58):
                o=box(s,prefix+end+' waterbox',(cx+dx-1.53,3.55,z),(cx+dx+1.53,8.35,z+.96),NAVY)
                if bay==3 and end=='front':remove_for_cutaway.append(o)
                face=z-.06 if end=='front' else z+1.02
                cover=box(s,prefix+end+' removable cover',(cx+dx-1.48,3.64,face),(cx+dx+1.48,8.25,face+.08),BODY)
                if bay==3 and end=='front':remove_for_cutaway.append(cover)
                # Cover seam, corner studs and lifting lugs.
                for xx in (cx+dx-1.37,cx+dx+1.37):
                    for y in [3.80+j*.41 for j in range(11)]:
                        o=bolt(s,prefix+'waterbox cover stud',(xx,y,face-.035),(xx,y,face+.12),.049)
                        if bay==3 and end=='front':remove_for_cutaway.append(o)
        # Pair of visible 90-degree CW elbows with true continuous pipe bends.
        for side,dx in enumerate((-1.55,1.55)):
            x=cx+dx;y=5.9;zc=1.60;radius=1.0
            path=[(x,y,2.22)]+[(x,y-radius+radius*math.cos(t*math.pi/2/16),zc-radius*math.sin(t*math.pi/2/16)) for t in range(17)]+[(x,.9,zc-radius)]
            ob=sweep(s,prefix+'CW elbow',path,.55,STEEL,24)
            if bay==3:remove_for_cutaway.append(ob)
            for p,d in (((x,.85,zc-radius),(0,-1,0)),((x,y,2.15),(0,0,1))):
                before=set(s.objects);scaled_flange(s,prefix+'CW nozzle',p,d,.69,.47,STEEL,20)
                if bay==3:remove_for_cutaway.extend(set(s.objects)-before)
            marker(s,prefix+('COOLING_WATER_IN' if side==0 else 'COOLING_WATER_OUT'),(x,.85,zc-radius),role='cooling_water',direction='down')
        # Neck-mounted LP feedwater heater, a prominent feature of reference 1.
        cylinder(s,prefix+'neck feedwater heater',(cx,11.00,2.9),(cx,11.00,18.6),.78,STEEL,40)
        scaled_flange(s,prefix+'heater head',(cx,11.00,2.78),(0,0,-1),.95,.01,STEEL,24)
        cylinder(s,prefix+'dished heater cover',(cx,11,2.55),(cx,11,2.78),.69,STEEL,32)
        for dx in (-.30,.30):
            p=[(cx+dx,11.05,2.55),(cx+dx,11.05,2.15),(cx+dx,11.20,2),(cx+dx,11.55,2)]
            sweep(s,prefix+'heater instrument elbow',p,.10,BODY,12)
        for dx in (-1.05,-.35,.35,1.05):
            # Four upper extraction/vent risers.
            path=[(cx+dx,11.60,5.2),(cx+dx,12.00,5.2),(cx+dx,12.5,5.35),(cx+dx,13.0,5.8),(cx+dx,14.0,5.8)]
            sweep(s,prefix+'upper service riser',path,.23,STEEL,20)
        for z in (4,8,12,16,18):
            sleeve(s,prefix+'heater support ring',(cx,11,z-.07),(cx,11,z+.07),.82,.765,BODY,32)
        # Accessible manways, level instrumentation and hotwell outlet.
        for z in (6.0,16.0):
            p=(cx-3.42,6.2,z);scaled_flange(s,prefix+'shell inspection manway',p,(-1,0,0),.44,.01,STEEL,12)
        cylinder(s,prefix+'hotwell drain',(cx,2.55,19.3),(cx,2.55,21.5),.27,STEEL,20)
        scaled_flange(s,prefix+'condensate outlet',(cx,2.55,21.5),(0,0,1),.42,.23,STEEL,12)
        marker(s,prefix+'CONDENSATE_OUT',(cx,2.55,21.5),role='condensate',direction='south')
        marker(s,prefix+'LP_STEAM_IN',(cx,14,11.3),role='exhaust_steam',direction='up')
        cylinder(s,prefix+'level gauge',(cx+3.34,2.2,18.6),(cx+3.34,3.5,18.6),.055,STEEL,12)
        for y in (2.2,3.5):cylinder(s,prefix+'gauge takeoff',(cx+3.08,y,18.6),(cx+3.34,y,18.6),.045,STEEL,10)
        box(s,prefix+'asset plate',(cx-.6,9.2,3.08),(cx+.6,9.62,3.14),LABEL)
    # Common platform, grating, handrails and an accessible ladder.
    for i in range(97):box(s,'platform grating slat',(.45+i*.24,8.62,1.85),(.50+i*.24,8.70,3.1),STEEL)
    for z in (1.85,3.0):box(s,'platform longitudinal beam',(.4,8.35,z),(23.6,8.62,z+.1),BODY)
    for x in [0.4+i*1.45 for i in range(17)]:cylinder(s,'handrail stanchion',(x,8.68,1.83),(x,9.83,1.83),.034,STEEL,10)
    for y in (9.23,9.83):cylinder(s,'continuous handrail',(.4,y,1.83),(23.6,y,1.83),.034,STEEL,12)
    for x in (7.1,7.8):cylinder(s,'ladder stile',(x,.2,1.5),(x,9.1,1.5),.05,STEEL,12)
    for y in [.45+i*.31 for i in range(27)]:cylinder(s,'ladder rung',(7.1,y,1.5),(7.8,y,1.5),.031,STEEL,10)
    for y in (3,4.3,5.6,6.9,8.2):
        path=[(7.45+.47*math.cos(i*math.pi/20),y,1.5-.47*math.sin(i*math.pi/20)) for i in range(21)]
        sweep(s,'ladder safety hoop',path,.027,STEEL,8)
    for a in (math.pi*.20,math.pi*.5,math.pi*.8):
        x=7.45+.47*math.cos(a);z=1.5-.47*math.sin(a)
        cylinder(s,'ladder cage rail',(x,3,z),(x,8.2,z),.022,STEEL,8)
    # Air removal header visible along the right shell, independent of CW.
    cylinder(s,'air extraction header',(23.62,9.1,4),(23.62,9.1,20.8),.13,STEEL,16)
    for z in (6,10,14,18):cylinder(s,'air cooler takeoff',(23.2,8.2,z),(23.62,9.1,z),.085,STEEL,12)
    marker(s,'AIR_REMOVAL',(23.62,9.1,20.8),role='noncondensable_gas',direction='south')
    # Preserve separate exterior and inspection scenes using shared editable mesh data.
    cut=new_scene('BWR | Arabelle 1700 condenser cutaway')
    for o in s.objects:
        if o not in remove_for_cutaway:cut.collection.objects.link(o)
    cut['description']='Right-bay waterbox and side shell removed to expose representative titanium tubes and supports.'
    for bay in (1,2,3):
        collection=bpy.data.collections.new('Condenser | Bay '+str(bay));s.collection.children.link(collection)
        for o in list(s.collection.objects):
            if o.name.startswith('Bay '+str(bay)+' | '):collection.objects.link(o);s.collection.objects.unlink(o)
    studio(s,(12,6.8,10.7),(42,26,-29),36)
    studio(cut,(12,6.8,10.7),(42,26,-29),36)
    viewport(cut,(12,6.8,10.7),(42,26,-29),41)
    bpy.data.libraries.write(str(OUT/'arabelle_1700_condenser.blend'),{s,cut},fake_user=True)
    print('Condenser saved:',sum(o.type=='MESH' for o in s.objects),'editable mesh objects')
    print('Cutaway removes',len(remove_for_cutaway),'cover objects; exterior is intact in its own scene.')
    return s,cut
