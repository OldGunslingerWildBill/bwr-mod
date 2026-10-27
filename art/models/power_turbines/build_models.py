"""Blender 5: the main turbine train's exterior modules, metres = blocks.

Headless:  blender --background --factory-startup --python art/models/power_turbines/build_models.py
then       python tools-export-power-turbines.py   (slices the meshes into per-cell OBJ files)

Writes art/models/power_turbines/{hp_turbine,lp_turbine,nuclear_generator}.json and power_turbines.blend.

The HP and LP follow large nuclear tandem-compound practice (a 1,100-1,350 MWe BWR train: one double-flow HP,
double-flow LPs on their condensers, half-speed shaft). Generic and brand-free; game scale, not a CAD replica.

Fixed by other code (MODULAR-TURBINES.md, TURBINE-MAINTENANCE.md):
  * footprints HP/generator 5 x 5 x 9, LP 7 x 5 x 7; ports as in the layout files;
  * the shaft along z at the module's middle, y = 2.5, couplings 0.04..0.28 from each end (as the generator);
  * the LP's horizontal joint at exactly y = 3: everything at y >= 3 is the crane-lifted hood
    (PowerModuleBlock.HOOD_FROM_Y), everything below stays as the open lower casing;
  * the LP blade sections TurbineHallRenderers draws: centred 1.5 from the LP middle, last-stage wheels 2.5 from
    it (TurbineMissile.wheel), tips 2.3 from the shaft.
The generator is unchanged (build_generator is the original code path).
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
CONCRETE = material('foundation concrete', (.23,.23,.215))
LUG = material('lifting lug yellow', (.78,.50,.02), .2)
HOOD = material('exhaust hood enamel', (.47,.52,.50), .15)


# ------------------------------------------------------------------ background-safe scene handling
def new_scene(name):
    # No window in --background: never touch the context's scene; nothing here is named "anim_prev".
    scene = bpy.data.scenes.new(name)
    SCENES.append(scene)
    return scene


def export_scene(scene, filename, meta):
    """The shared exporter's output, read straight from the (modifier-free, untransformed) meshes."""
    faces, boxes, mats = [], [], {}
    for o in scene.objects:
        if o.type != 'MESH':
            continue
        me = o.data
        me.calc_loop_triangles()
        points = [game(o.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in points) for a in range(3)]+[max(p[a] for p in points) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            key = m.name
            if key not in mats:
                bsdf = next((n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled'), None) if m.node_tree else None
                rgb = list(bsdf.inputs['Base Color'].default_value[:3]) if bsdf else list(m.diffuse_color[:3])
                info = {'color': [srgb(x) for x in rgb], 'texture': 'minecraft:block/white_concrete', 'tint': int(m.get('tint_index', -1))}
                if info['tint'] >= 0:
                    info['color'] = [1, 1, 1]
                mats[key] = info
            vs = [list(points[i])+list(uv.data[loop].uv if uv else (.5, .5)) for i, loop in zip(t.vertices, t.loops)]
            if (Vector(vs[1][:3])-Vector(vs[0][:3])).cross(Vector(vs[2][:3])-Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices': vs, 'material': key, 'part': o.name})
    (OUT/filename).write_text(json.dumps(dict(meta, faces=faces, boxes=boxes, materials=mats), separators=(',', ':')), encoding='utf-8')
    print(filename, len(faces), 'triangles')


# ------------------------------------------------------------------ primitives (game coordinates: x right, y up, z along the shaft)
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
    # Semicircular hood above its horizontal split joint (the generator's end coolers).
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

def extrude(s, name, poly, axis, a0, a1, mat):
    """A closed prism: the 2D polygon (convex or not) swept along an axis. Axis z: (x, y); x: (z, y); y: (x, z)."""
    put = {'z': lambda u,v,w: (u,v,w), 'x': lambda u,v,w: (w,v,u), 'y': lambda u,v,w: (u,w,v)}[axis]
    n = len(poly)
    vs = [put(u,v,a) for a in (a0,a1) for u,v in poly]
    fs = [tuple(range(n-1,-1,-1)), tuple(range(n,2*n))]+[(i,(i+1)%n,n+(i+1)%n,n+i) for i in range(n)]
    return mesh_object(s, name, vs, fs, mat)

def lathe(s, name, profile, mat, c, segments=32, t0=0.0, t1=math.tau):
    """A closed (z, r) outline turned about the shaft line through c = (x, y); a partial turn gets end caps."""
    full = abs(t1-t0-math.tau) < 1e-9
    n = len(profile); rings = segments if full else segments+1
    vs = [(c[0]+r*math.cos(t0+(t1-t0)*k/segments), c[1]+r*math.sin(t0+(t1-t0)*k/segments), z) for k in range(rings) for z,r in profile]
    fs = [(k*n+i, k*n+(i+1)%n, ((k+1)%rings)*n+(i+1)%n, ((k+1)%rings)*n+i) for k in range(segments) for i in range(n)]
    if not full:
        fs += [tuple(range(n)), tuple((rings-1)*n+i for i in range(n))]
    return mesh_object(s, name, vs, fs, mat)

def bar(s, name, z0, xa0, xb0, z1, xa1, xb1, y0, y1, mat):
    """A box whose x span changes linearly along z (a flange following a cone)."""
    vs=[(xa0,y0,z0),(xb0,y0,z0),(xb1,y0,z1),(xa1,y0,z1),(xa0,y1,z0),(xb0,y1,z0),(xb1,y1,z1),(xa1,y1,z1)]
    return mesh_object(s,name,vs,[(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)],mat)

def bend(s, name, a, da, b, db, r, mat, steps=8, segments=16):
    """A closed round pipe from a (leaving along da) to b (arriving along db): a quadratic sweep with end caps."""
    a,b,da,db = Vector(a),Vector(b),Vector(da).normalized(),Vector(db).normalized()
    # control point: where the leg leaving a meets the leg arriving at b (closest approach of the two lines)
    w = da.cross(db)
    if w.length < 1e-6:
        ctrl = (a+b)/2
    else:
        r0 = a-b; A = da.dot(da); B = da.dot(-db); Cc = db.dot(db); D = da.dot(r0); E = (-db).dot(r0)
        ctrl = a+da*((B*E-Cc*D)/(A*Cc-B*B))
    plane = w.normalized() if w.length > 1e-6 else basis(da)[1]
    vs = []
    for j in range(steps+1):
        t = j/steps
        p = (1-t)**2*a+2*(1-t)*t*ctrl+t*t*b
        tan = (2*(1-t)*(ctrl-a)+2*t*(b-ctrl)).normalized()
        u = plane; v = tan.cross(u).normalized()
        vs += [tuple(p+r*(math.cos(i*math.tau/segments)*u+math.sin(i*math.tau/segments)*v)) for i in range(segments)]
    n = segments
    fs = [(j*n+i, j*n+(i+1)%n, (j+1)*n+(i+1)%n, (j+1)*n+i) for j in range(steps) for i in range(n)]
    fs += [tuple(range(n-1,-1,-1)), tuple(steps*n+i for i in range(n))]
    return mesh_object(s, name, vs, fs, mat)

def nut(s, name, a, b, r, sides=6):
    """A nut or stud end standing on a surface: sides and a top, no hidden bottom face."""
    a, b = Vector(a), Vector(b); d, u, v = basis(b-a)
    vs = [tuple(p+r*(math.cos(i*math.tau/sides+math.pi/sides)*u+math.sin(i*math.tau/sides+math.pi/sides)*v)) for p in (a, b) for i in range(sides)]
    fs = [tuple(range(sides, 2*sides))]+[(i, (i+1)%sides, (i+1)%sides+sides, i+sides) for i in range(sides)]
    return mesh_object(s, name, vs, fs, STEEL)

def stud_ring(s, name, centre, normal, radius, count, length=.08, r=.035, phase=0.0):
    p = Vector(centre); d,u,v = basis(normal)
    for i in range(count):
        a = phase+i*math.tau/count
        q = p+radius*(math.cos(a)*u+math.sin(a)*v)
        nut(s, name, q, q+d*length, r, 4)

def manway(s, name, centre, normal, radius, mat):
    """A bolted round access cover standing proud of a wall."""
    p = Vector(centre); d = Vector(normal).normalized()
    cylinder(s, name+' cover', p, p+d*.07, radius, mat, 16)
    nut(s, name+' handle', p+d*.07+Vector((0,.08,0)), p+d*.13+Vector((0,.08,0)), .035, 4)
    stud_ring(s, name+' stud', p+d*.07, d, radius*.84, 8, .05, .03, math.pi/8)

def couplings(s, cx, depth, z_ranges):
    """The generator's own shaft and coupling geometry, so the train's couplings meet face to face."""
    for a, b in z_ranges:
        cylinder(s,'continuous steel shaft',(cx,2.5,a),(cx,2.5,b),.17,STEEL)
    for z in (.16,depth-.16):
        cylinder(s,'bolted shaft coupling',(cx,2.5,max(.005,z-.12)),(cx,2.5,min(depth-.005,z+.12)),.34,STEEL)
        for i in range(8):
            a=i*math.tau/8
            cylinder(s,'coupling stud',(cx+.265*math.cos(a),2.5+.265*math.sin(a),max(.003,z-.135)),(cx+.265*math.cos(a),2.5+.265*math.sin(a),min(depth-.003,z+.135)),.025,DARK,6)

def offset_line(pts, d):
    """An open polyline moved d to its left (outward for the hood's clockwise profile), mitred at the corners."""
    out = []
    for i, p in enumerate(pts):
        ns = []
        for a, b in ((pts[i-1], p) if i > 0 else (None, None), (p, pts[i+1]) if i < len(pts)-1 else (None, None)):
            if a is None: continue
            dx, dy = b[0]-a[0], b[1]-a[1]; l = math.hypot(dx, dy); ns.append((-dy/l, dx/l))
        nx, ny = sum(n[0] for n in ns), sum(n[1] for n in ns); l = math.hypot(nx, ny); nx, ny = nx/l, ny/l
        k = d/max(.3, nx*ns[0][0]+ny*ns[0][1])
        out.append((p[0]+nx*k, p[1]+ny*k))
    return out

def shell(s, name, outer, thickness, z0, z1, mat):
    """An open-bottomed sheet shell (a U in section) of the given wall thickness, swept along z."""
    inner = offset_line(outer, -thickness)
    return extrude(s, name, outer+inner[::-1], 'z', z0, z1, mat)


# ------------------------------------------------------------------ the HP turbine (5 wide x 5 high x 9 long)
# Outer casing silhouette (z, r): gland, end cover, exhaust cones, the two exhaust ends and the inlet belt.
HP_OUTLINE = [(1.12,.50),(1.40,.50),(1.40,.95),(1.80,1.45),(2.25,1.62),(3.25,1.62),(3.50,1.90),(5.50,1.90),
              (5.75,1.62),(6.75,1.62),(7.20,1.45),(7.60,.95),(7.60,.50),(7.88,.50)]

def hp_radius(z):
    pts = HP_OUTLINE[2:-2]
    for (za,ra),(zb,rb) in zip(pts, pts[1:]):
        if za <= z <= zb and zb > za:
            return ra+(rb-ra)*(z-za)/(zb-za)
    return .5

def build_hp():
    id, w, h, depth = 'hp_turbine', 5, 5, 9
    s = new_scene('BWR | '+id); cx, cy, cz = w/2, 2.5, depth/2; C = (cx, cy)
    # Foundation table and sole plates; the casing hangs on its paws at the joint, clear of the table.
    box(s,'turbine foundation table',(.06,0,.06),(w-.06,.34,depth-.06),CONCRETE)
    for z0, z1 in ((.30,1.10),(7.90,8.70)):
        box(s,'pedestal sole plate',(1.10,.34,z0-.04),(3.90,.40,z1+.04),DARK)
        box(s,'bearing pedestal',(1.20,.40,z0),(3.80,1.90,z1),BASE)
        box(s,'bearing pedestal plinth',(1.14,.40,z0-.03),(3.86,.62,z1+.03),BASE)
        box(s,'bearing pedestal top',(1.60,1.90,z0+.05),(3.40,2.18,z1-.05),BASE)
        box(s,'bearing housing lower half',(1.80,2.18,z0+.08),(3.20,2.50,z1-.08),RIB)
        lathe(s,'bearing cap',[(z0+.08,.30),(z0+.08,.62),(z1-.08,.62),(z1-.08,.30)],RIB,C,16,0,math.pi)
        box(s,'bearing cap joint',(1.74,2.44,z0+.06),(3.26,2.56,z1-.06),STEEL)
        for x in (1.84,3.16):
            for z in (z0+.2, z1-.2):
                nut(s,'bearing cap bolt',(x,2.56,z),(x,2.66,z),.04,4)
        cylinder(s,'bearing breather',(cx,3.05,(z0+z1)/2),(cx,3.28,(z0+z1)/2),.06,STEEL,8)
        cylinder(s,'lube oil drain',(3.86,1.2,(z0+z1)/2),(4.25,1.2,(z0+z1)/2),.07,STEEL,8)
        cylinder(s,'lube oil drain',(4.25,.40,(z0+z1)/2),(4.25,1.27,(z0+z1)/2),.07,STEEL,8)
        cylinder(s,'lube oil supply',(1.14,1.55,z0+.12),(1.14,1.55,z1-.12),.05,STEEL,8)
    # Casing paws resting on the pedestal tops at the joint level.
    for zp in (.92, 7.48):
        for x0 in (1.20, 3.38):
            box(s,'casing support paw',(x0,1.90,zp),(x0+.42,2.40,zp+.60),BASE)
    couplings(s, cx, depth, [(.015, depth-.015)])
    # The double-flow casing: steam in at the middle belt, out at both ends. Upper and lower halves bolted at y = 2.5.
    lathe(s,'double-flow HP outer casing',HP_OUTLINE+[(7.88,.30),(1.12,.30)],CASING,C,32)
    for z0 in (1.10, 7.78):
        cylinder(s,'gland housing',(cx,cy,z0),(cx,cy,z0+.12),.58,RIB,20)
    for z in (2.30, 3.10, 5.90, 6.70):
        r = hp_radius(z)
        sleeve(s,'lagging band',(cx,cy,z-.045),(cx,cy,z+.045),r+.03,r-.06,RIB,32)
    # The heavy horizontal joint flange, following the casing outline, and its studs and nuts.
    pts = HP_OUTLINE[2:-2]
    for (za,ra),(zb,rb) in zip(pts, pts[1:]):
        if zb <= za: continue
        for side in (-1, 1):
            xa0, xb0 = cx+side*(ra-.10), cx+side*(ra+.20)
            xa1, xb1 = cx+side*(rb-.10), cx+side*(rb+.20)
            bar(s,'horizontal joint flange',za,min(xa0,xb0),max(xa0,xb0),zb,min(xa1,xb1),max(xa1,xb1),2.38,2.62,STEEL)
    z = 1.92
    while z < 7.1:
        for side in (-1, 1):
            x = cx+side*(hp_radius(z)+.09)
            nut(s,'joint stud nut',(x,2.62,z),(x,2.76,z),.055)
        z += .37
    # Main steam: the inlet chamber on the belt, four steam leads to the upper-half nozzles, and the top connection.
    box(s,'main steam inlet chamber',(1.62,3.95,3.82),(3.38,4.62,5.18),CASING)
    box(s,'inlet chamber cover',(1.55,4.62,3.75),(3.45,4.72,5.25),STEEL)
    for x, z in ((1.64,3.95),(1.64,4.5),(1.64,5.05),(3.36,3.95),(3.36,4.5),(3.36,5.05),(2.1,3.84),(2.9,3.84),(2.1,5.16),(2.9,5.16)):
        nut(s,'inlet cover stud',(x,4.72,z),(x,4.80,z),.035,4)
    port(s,'main steam inlet',(cx,h,cz),(0,1,0),(cx,4.70,cz),CASING)
    for side in (-1, 1):
        for z in (4.12, 4.88):
            ang = math.radians(90+side*48)
            nozzle = Vector((cx+1.90*math.cos(ang), cy+1.90*math.sin(ang), z)); radial = Vector((math.cos(ang), math.sin(ang), 0))
            cylinder(s,'steam lead nozzle',nozzle-radial*.12,nozzle+radial*.12,.27,RIB,12)
            bend(s,'steam lead',nozzle+radial*.10,radial,(cx+side*.80,4.38,z),(-side,0,0),.17,CASING,6,10)
    # Lower-half steam leads, coming up through the foundation table.
    for side in (-1, 1):
        for z in (4.12, 4.88):
            x = cx+side*.95
            y = cy-math.sqrt(1.90**2-.95**2)
            cylinder(s,'lower steam lead',(x,.30,z),(x,y+.2,z),.17,CASING,10)
            cylinder(s,'lower steam lead collar',(x,.30,z),(x,.46,z),.25,RIB,10)
    # Exhaust: a nozzle at each end of the lower half into the crossaround header, which leaves on the left port face.
    for z in (2.75, 6.25):
        cylinder(s,'HP exhaust nozzle',(1.60,1.5,z),(.58,1.5,z),.30,CASING,20)
        cylinder(s,'exhaust nozzle collar',(1.32,1.5,z),(1.12,1.5,z),.40,RIB,16)
        cylinder(s,'exhaust nozzle flange',(.98,1.5,z),(.90,1.5,z),.38,STEEL,16)
    cylinder(s,'crossaround exhaust header',(.58,1.5,2.42),(.58,1.5,6.58),.32,CASING,20)
    for z in (2.50, 6.50):
        cylinder(s,'header end band',(.58,1.5,z-.06),(.58,1.5,z+.06),.36,STEEL,20)
    for z in (3.45, 5.55):
        cylinder(s,'header lagging band',(.58,1.5,z-.04),(.58,1.5,z+.04),.345,RIB,20)
        box(s,'header support',(.50,.34,z-.07),(.66,1.20,z+.07),BASE)
    port(s,'HP exhaust steam',(0,1.5,cz),(-1,0,0),(.58,1.5,cz),CASING)
    # Casing drains and instrument lines down to the table.
    for z in (2.0, 7.0):
        cylinder(s,'casing drain',(cx,.34,z),(cx,cy-hp_radius(z)+.1,z),.07,STEEL,8)
    for z in (3.6, 5.4):
        cylinder(s,'casing drain',(cx+.5,.34,z),(cx+.5,cy-math.sqrt(1.9**2-.25)+.1,z),.06,STEEL,8)
    ports=[('STEAM_INLET',[w//2,h-1,depth//2],'up'),('STEAM_EXHAUST',[0,1,depth//2],'west')]
    return finish(s, id, w, h, depth, ports)


# ------------------------------------------------------------------ the LP turbine (7 wide x 5 high x 7 long)
LP_JOINT = 3.0   # PowerModuleBlock.HOOD_FROM_Y: the hood is everything above
# The fabricated exhaust hood's section: vertical side walls, sloped shoulders, a flat roof (clockwise from the joint).
LP_HOOD = [(.30,3.20),(.30,4.15),(1.55,4.90),(5.45,4.90),(6.70,4.15),(6.70,3.20)]

def lp_hood_top(x):
    for (xa,ya),(xb,yb) in zip(LP_HOOD[1:], LP_HOOD[2:]):
        if xa <= x <= xb:
            return ya+(yb-ya)*(x-xa)/(xb-xa)
    return 3.2

def build_lp():
    id, w, h, depth = 'lp_turbine', 7, 5, 7
    s = new_scene('BWR | '+id); cx, cy, cz = w/2, 2.5, depth/2; C = (cx, cy)
    E0, E1 = .56, depth-.56          # outer faces of the end walls
    # ---- below the joint: skid, exhaust opening into the condenser, the lower outer casing, bearings
    box(s,'foundation skid',(.12,.04,.12),(w-.12,.28,depth-.12),BASE)
    box(s,'large lower exhaust casing opening',(.40,.28,.70),(w-.40,.31,depth-.70),DARK)
    for x0, x1 in ((.30,.40),(w-.40,w-.30)):
        box(s,'lower casing side wall',(x0,.28,.62),(x1,2.80,depth-.62),HOOD)
    for z0, z1 in ((E0,.70),(depth-.70,E1)):
        box(s,'lower casing end wall',(.27,.28,z0),(w-.27,2.80,z1),HOOD)
    # walers and ribs on the sides, ribs on the ends
    for x0, x1 in ((.20,.30),(w-.30,w-.20)):
        box(s,'lower casing waler',(x0,2.18,.62),(x1,2.30,depth-.62),RIB)
        box(s,'lower casing waler',(x0,.80,.62),(x1,.92,depth-.62),RIB)
        for z in (1.15, 2.35, 4.65, 5.85):
            box(s,'lower casing stiffener',(x0,.28,z-.05),(x1,2.80,z+.05),RIB)
    for z0, z1 in ((E0-.10,E0),(depth-E0,depth-E0+.10)):
        for x in (.90, 2.05, w-2.05, w-.90):
            box(s,'end wall stiffener',(x-.05,.28,z0),(x+.05,2.80,z1),RIB)
        box(s,'end wall waler',(.27,1.92,z0),(w-.27,2.02,z1),RIB)
    # lower joint flange: its top face is the joint (y = 3)
    for x0, x1 in ((.08,.40),(w-.40,w-.08)):
        box(s,'lower joint flange',(x0,2.80,.44),(x1,LP_JOINT,depth-.44),STEEL)
    # (flush with the end wall over the bearing, so the bearing cap stays clear of the joint)
    for z0, z1, m0, m1 in ((.44,.70,E0,.70),(depth-.70,depth-.44,depth-.70,E1)):
        box(s,'lower joint flange',(.40,2.80,z0),(2.75,LP_JOINT,z1),STEEL)
        box(s,'lower joint flange',(w-2.75,2.80,z0),(w-.40,LP_JOINT,z1),STEEL)
        box(s,'lower joint flange',(2.75,2.80,m0),(w-2.75,LP_JOINT,m1),STEEL)
    # bearing pedestals and housings outside the end walls (all under the joint, so they stay when the hood comes off)
    for z0, z1 in ((.30,E0),(depth-E0,depth-.30)):
        box(s,'bearing pedestal',(2.85,.28,z0),(4.15,2.05,z1),BASE)
        box(s,'bearing housing lower half',(2.95,2.05,z0+.02),(4.05,2.50,z1-.02),RIB)
        lathe(s,'bearing cap',[(z0+.02,.25),(z0+.02,.45),(z1-.02,.45),(z1-.02,.25)],RIB,C,16,0,math.pi)
        box(s,'bearing cap joint',(2.90,2.45,z0),(4.10,2.55,z1),STEEL)
        for x in (2.97, 4.03):
            nut(s,'bearing cap bolt',(x,2.55,(z0+z1)/2),(x,2.64,(z0+z1)/2),.035,4)
        cylinder(s,'lube oil drain',(4.15,1.4,(z0+z1)/2),(4.45,1.4,(z0+z1)/2),.06,STEEL,8)
    couplings(s, cx, depth, [(.015,.82),(depth-.82,depth-.015)])
    # inside, seen with the hood off: the inner casing's lower half (the rotor seat), its joint, its paws, the gland saddles
    INNER = [(1.25,2.25),(1.70,2.25),(2.00,2.14),(5.00,2.14),(5.30,2.25),(5.75,2.25),
             (5.75,2.13),(5.30,2.13),(5.00,2.02),(2.00,2.02),(1.70,2.13),(1.25,2.13)]
    lo = math.asin(.47/2.25)
    lathe(s,'inner casing lower half',INNER,CASING,C,16,math.pi-lo,2*math.pi+lo)
    for side in (-1, 1):
        xa, xb = (cx-2.42, cx-2.10) if side < 0 else (cx+2.10, cx+2.42)
        box(s,'inner casing joint flange',(xa,2.84,1.25),(xb,2.98,5.75),STEEL)
        for z in (1.50, 3.50, 5.50):
            box(s,'inner casing support paw',((.40 if side < 0 else xb),2.86,z-.12),((xa if side < 0 else w-.40),2.98,z+.12),BASE)
    belt = math.asin(.45/2.02)
    lathe(s,'inlet chamber lower half',[(3.30,.70),(3.30,2.02),(3.70,2.02),(3.70,.70)],CASING,C,16,math.pi-belt,2*math.pi+belt)
    for z0, z1 in ((.70,.84),(depth-.84,depth-.70)):
        lathe(s,'gland saddle',[(z0,.34),(z0,.62),(z1,.62),(z1,.34)],STEEL,C,10,math.pi,2*math.pi)
        box(s,'gland saddle web',(cx-.62,.31,z0),(cx-.48,2.5,z1),BASE)
        box(s,'gland saddle web',(cx+.48,.31,z0),(cx+.62,2.5,z1),BASE)
    # access manways in the lower end walls, the condensate return on the left face
    for x in (1.47, w-1.47):
        manway(s,'end wall manway',(x,1.35,E0),(0,0,-1),.32,HOOD)
        manway(s,'end wall manway',(x,1.35,depth-E0),(0,0,1),.32,HOOD)
    port(s,'condensate return water',(0,1.5,cz),(-1,0,0),(.32,1.5,cz),HOOD)
    # ---- the hood: all at or above the joint
    for x0, x1 in ((.08,.40),(w-.40,w-.08)):
        box(s,'upper joint flange',(x0,LP_JOINT,.44),(x1,3.20,depth-.44),STEEL)
    for z0, z1, m0, m1 in ((.44,.70,E0,.70),(depth-.70,depth-.44,depth-.70,E1)):
        box(s,'upper joint flange',(.40,LP_JOINT,z0),(2.75,3.20,z1),STEEL)
        box(s,'upper joint flange',(w-2.75,LP_JOINT,z0),(w-.40,3.20,z1),STEEL)
        box(s,'upper joint flange',(2.75,LP_JOINT,m0),(w-2.75,3.20,m1),STEEL)
    for x in (.20, w-.20):
        z = .62
        while z < depth-.55:
            nut(s,'joint stud nut',(x,3.20,z),(x,3.30,z),.05)
            z += .48
    for z in (.52, depth-.52):
        for x in (.62, 1.30, 2.0, 2.62, w-2.62, w-2.0, w-1.30, w-.62):
            nut(s,'joint stud nut',(x,3.20,z),(x,3.30,z),.05)
    shell(s,'exhaust hood',LP_HOOD,.08,.62,depth-.62,HOOD)
    end = [(.27,3.20),(.27,4.16),(1.54,4.93),(w-1.54,4.93),(w-.27,4.16),(w-.27,3.20)]
    for z0, z1 in ((E0,.70),(depth-.70,E1)):
        extrude(s,'exhaust hood end wall',end,'z',z0,z1,HOOD)
    for z in (2.45, 4.55):
        shell(s,'hood stiffener rib',offset_line(LP_HOOD,.06),.07,z-.045,z+.045,RIB)
    for z in (1.15, depth-1.15):
        for x0, x1 in ((.22,.30),(w-.30,w-.22)):
            box(s,'hood side stiffener',(x0,3.20,z-.045),(x1,4.12,z+.045),RIB)
    for z0, z1 in ((E0-.10,E0),(depth-E0,depth-E0+.10)):
        for x in (.90, 2.05, w-2.05, w-.90):
            box(s,'hood end wall stiffener',(x-.05,3.20,z0),(x+.05,lp_hood_top(x)-.04,z1),RIB)
    # crossover inlet on the roof, relief diaphragms at the ends, lifting lugs where the crane's slings go
    cylinder(s,'crossover inlet reinforcing pad',(cx,4.86,cz),(cx,4.95,cz),.62,STEEL,24)
    flange(s,'LP crossover steam inlet',(cx,h,cz),(0,1,0))
    sleeve(s,'LP crossover steam inlet identification band',(cx,4.95,cz),(cx,4.88,cz),.30,.205,AMBER)
    for z in (1.42, depth-1.42):
        cylinder(s,'atmospheric relief diaphragm ring',(cx,4.88,z),(cx,4.96,z),.56,STEEL,24)
        cylinder(s,'atmospheric relief diaphragm',(cx,4.96,z),(cx,4.98,z),.46,DARK,24)
        stud_ring(s,'diaphragm ring stud',(cx,4.96,z),(0,1,0),.51,10,.03,.025)
    for sx in (-1, 1):
        for sz in (-1, 1):
            x, z = cx+sx*2.2, cz+sz*2.2
            yb = lp_hood_top(x)
            box(s,'hood lifting lug',(x-.05,yb-.06,z-.16),(x+.05,yb+.20,z+.16),LUG)
            cylinder(s,'hood lifting lug eye',(x-.07,yb+.11,z),(x+.07,yb+.11,z),.07,STEEL,8)
    for z in (1.90, depth-1.90):
        manway(s,'hood manway',(.30,3.72,z),(-1,0,0),.30,HOOD)
        manway(s,'hood manway',(w-.30,3.72,z),(1,0,0),.30,HOOD)
    # the inner casing's upper half and crossover belt, lifted with the hood (seen from below on the hook)
    up = math.asin(.52/2.0)
    lathe(s,'inner casing upper half',INNER,CASING,C,14,up,math.pi-up)
    for side in (-1, 1):
        xa, xb = (cx-2.42, cx-2.10) if side < 0 else (cx+2.10, cx+2.42)
        box(s,'inner casing upper joint flange',(xa,3.0,1.25),(xb,3.14,5.75),STEEL)
    cylinder(s,'crossover inlet pipe',(cx,4.86,cz),(cx,4.55,cz),.36,CASING,20)
    ports=[('STEAM_INLET',[w//2,h-1,depth//2],'up')]
    return finish(s, id, w, h, depth, ports)


def finish(s, id, w, h, depth, ports):
    meta={'id':id,'size':[w,h,depth],'controller':[w//2,0,depth//2],
          'ports':[{'role':r,'cell':c,'face':f} for r,c,f in ports],
          'shaft_height':2,'shaft_axis':'north/south',
          'authoring':'Blender 5 headless; large nuclear tandem-compound practice, generic; game scale, not a CAD replica'}
    export_scene(s,id+'.json',meta)
    return s


# ------------------------------------------------------------------ the generator (not remodelled: the original code path)
def build_generator():
    id,w,h,depth='nuclear_generator',5,5,9
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
    meta={'id':id,'size':[w,h,depth],'controller':[w//2,0,depth//2],'ports':[],
          'shaft_height':2,'shaft_axis':'north/south','authoring':'Blender 5 / TX-10 and TX-NLCH inspired; game scale, not a CAD replica'}
    export_scene(s,id+'.json',meta)
    return s


MODULES=[build_hp(),build_lp(),build_generator()]
# A review scene framing the whole train (HP, three LPs, generator) with the shafts on one line.
gallery=new_scene('BWR | Modular main turbine review')
z=0
for source,depth in ((MODULES[0],9),(MODULES[1],7),(MODULES[1],7),(MODULES[1],7),(MODULES[2],9)):
    width=7 if source==MODULES[1] else 5
    for o in source.objects:
        clone=o.copy();gallery.collection.objects.link(clone);clone.location += xyz(((7-width)/2,0,z))
    z+=depth
# libraries.write crashes on never-shown scenes in --background, so save a copy of the whole (factory-fresh) file,
# minus the startup scene's cube, camera and light.
for o in list(bpy.data.objects):
    if o.users_scene and all(sc.name == 'Scene' for sc in o.users_scene):
        bpy.data.objects.remove(o)
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'power_turbines.blend'),copy=True,compress=True)
print('Built hp_turbine, lp_turbine and nuclear_generator plus the train review scene.')
