"""Run in Blender with the original BWR pump scenes loaded.

Copies the supplied editable parts into isolated scenes; never modifies the
source gallery or DVSS. Exports mesh/UV data for tools-export-modern.py.
Minecraft coordinates: X/right, Y/up, Z/back; one unit is one block.
"""
import bpy
import bmesh
import json
import math
from pathlib import Path
from mathutils import Vector, Matrix

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'art/models/modern'
OUT.mkdir(parents=True, exist_ok=True)
CONFIG = {
    'lpcs_pump': ([5, 5, 3], 1.0, [('water_suction', [0, 0, 1], 'west'), ('water_discharge', [4, 0, 1], 'east')]),
    'hpcs_pump': ([9, 4, 3], 1.0, [('water_suction', [0, 1, 1], 'west'), ('water_discharge', [2, 3, 1], 'up')]),
    'rhr_pump': ([7, 3, 3], 1.15, [('water_suction', [0, 1, 1], 'west'), ('water_discharge', [1, 2, 1], 'up')]),
    'motor_feed_pump': ([7, 3, 3], 1.25, [('water_suction', [1, 2, 1], 'up'), ('water_discharge', [3, 2, 1], 'up')]),
    'turbine_feed_pump': ([9, 3, 3], 1.25, [('water_suction', [1, 2, 1], 'up'), ('water_discharge', [3, 2, 1], 'up'),
                                                     ('steam_inlet', [6, 2, 1], 'up'), ('steam_exhaust', [7, 1, 2], 'south')]),
}
DIRECTIONS = {'down': (0, -1, 0), 'up': (0, 1, 0), 'north': (0, 0, -1),
              'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0)}
SCENES = []

def xyz(p):
    return Vector((p[0], -p[2], p[1]))

def game(p):
    return (p[0], p[2], -p[1])

def material(name, rgb, metal=0.0):
    m = bpy.data.materials.new('Modern | ' + name)
    m.diffuse_color = (*rgb, 1)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value = (*rgb, 1)
    bsdf.inputs['Metallic'].default_value = metal
    bsdf.inputs['Roughness'].default_value = 0.32
    return m

STEEL = material('machined steel', (.48, .55, .59), .65)
BODY = material('pipe steel', (.24, .29, .32), .6)
DARK = material('gasket', (.022, .028, .033))
PAINT = material('paint band', (.85, .85, .85))
PAINT['tint_index'] = 0
BLUE = material('suction band', (.015, .28, .55))
TEAL = material('discharge band', (.025, .45, .32))
AMBER = material('steam band', (.8, .28, .025))

def mesh_object(scene, name, vs, fs, mat):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([xyz(p) for p in vs], [], fs)
    mesh.update()
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(mesh)
    bm.free()
    # Sample the interior of the solid material tile, not a texture-atlas seam.
    uv = mesh.uv_layers.new(name='Material UV')
    for loop in uv.data:
        loop.uv = (.5, .5)
    o = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(o)
    mesh.materials.append(mat)
    return o

def basis(axis):
    d = Vector(axis).normalized()
    a = d.cross(Vector((0, 1, 0)) if abs(d.y) < .9 else Vector((1, 0, 0))).normalized()
    return d, a, d.cross(a).normalized()

def sleeve(scene, name, a, b, outer, inner, mat, segments=20, end_outer=None):
    """Hollow flange/spool with annular end faces; no closed plug in its bore."""
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b-a)
    rings = [(a, outer), (b, outer if end_outer is None else end_outer), (b, inner), (a, inner)]
    vs = [tuple(p + radius*(math.cos(t*math.tau/segments)*u + math.sin(t*math.tau/segments)*v))
          for p, radius in rings for t in range(segments)]
    fs = [(r*segments+t, r*segments+(t+1)%segments, ((r+1)%4)*segments+(t+1)%segments, ((r+1)%4)*segments+t)
          for r in range(4) for t in range(segments)]
    return mesh_object(scene, name, vs, fs, mat)

def bolt(scene, name, a, b, radius=.025):
    a, b = Vector(a), Vector(b)
    d, u, v = basis(b-a)
    n = 6
    vs = [tuple(p+radius*(math.cos(t*math.tau/n)*u+math.sin(t*math.tau/n)*v)) for p in (a,b) for t in range(n)]
    fs = [tuple(range(n-1,-1,-1)), tuple(range(n,2*n))]
    fs += [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    return mesh_object(scene,name,vs,fs,STEEL)

def flange(scene, name, endpoint, outward, radius=.41, bolts=8):
    p = Vector(endpoint)
    d,u,v = basis(outward)
    sleeve(scene,name+' flange',p-d*.12,p-d*.025,radius,.205,STEEL)
    sleeve(scene,name+' gasket',p-d*.025,p,radius*.93,.205,DARK)
    for i in range(bolts):
        c = p + radius*.81*(math.cos(i*math.tau/bolts)*u+math.sin(i*math.tau/bolts)*v)
        bolt(scene,name+' stud '+str(i),c-d*.14,c-d*.018,.026)

def new_scene(name):
    scene = bpy.data.scenes.new(name)
    bpy.context.window.scene = scene
    SCENES.append(scene)
    return scene

def srgb(x):
    return 12.92*x if x <= .0031308 else 1.055*x**(1/2.4)-.055

def export_scene(scene, filename, meta):
    bpy.context.window.scene = scene
    dg = bpy.context.evaluated_depsgraph_get()
    faces, boxes, mats = [], [], {}
    for o in scene.objects:
        if o.type != 'MESH':
            continue
        ev = o.evaluated_get(dg)
        me = ev.to_mesh()
        me.calc_loop_triangles()
        points = [game(ev.matrix_world @ v.co) for v in me.vertices]
        boxes.append([min(p[a] for p in points) for a in range(3)]+[max(p[a] for p in points) for a in range(3)])
        uv = me.uv_layers.active
        for t in me.loop_triangles:
            m = me.materials[t.material_index]
            key = m.name
            if key not in mats:
                bsdf = next((n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeBsdfPrincipled'),None) if m.node_tree else None
                image_node = next((n for n in m.node_tree.nodes if n.bl_idname == 'ShaderNodeTexImage' and n.image),None) if m.node_tree else None
                rgb = list(bsdf.inputs['Base Color'].default_value[:3]) if bsdf else list(m.diffuse_color[:3])
                info = {'color':[srgb(x) for x in rgb], 'texture':'minecraft:block/white_concrete', 'tint':int(m.get('tint_index',-1))}
                if image_node and bsdf and bsdf.inputs['Base Color'].is_linked:
                    image_path = Path(bpy.path.abspath(image_node.image.filepath))
                    texture = image_path.stem if image_path.parent == OUT/'textures' else 'modern_'+''.join(c if c.isalnum() else '_' for c in image_node.image.name.lower())
                    target = OUT / 'textures' / (texture+'.png')
                    target.parent.mkdir(exist_ok=True)
                    copied = image_node.image.copy()
                    copied.filepath_raw = str(target)
                    copied.file_format = 'PNG'
                    copied.save()
                    copied.pack()
                    # Only the copied material is changed, never the original gallery.
                    image_node.image = copied
                    info.update(color=[1,1,1],texture='bwr:block/modern/'+texture)
                if info['tint'] >= 0:
                    info['color'] = [1,1,1]
                mats[key] = info
            vs = [list(points[i])+list(uv.data[loop].uv if uv else (.5,.5)) for i,loop in zip(t.vertices,t.loops)]
            # Degenerate source triangles carry no surface and are not exported.
            if (Vector(vs[1][:3])-Vector(vs[0][:3])).cross(Vector(vs[2][:3])-Vector(vs[0][:3])).length > 1e-10:
                faces.append({'vertices':vs,'material':key,'part':o.name})
        ev.to_mesh_clear()
    (OUT/filename).write_text(json.dumps(dict(meta,faces=faces,boxes=boxes,materials=mats),separators=(',',':')),encoding='utf-8')
    print(filename, len(faces), 'triangles')

def build_pump(id):
    size,scale,ports = CONFIG[id]
    source = bpy.data.scenes.get('BWR | '+id)
    if source is None or not source.objects:
        raise ValueError('Load the original pump gallery first: '+id)
    scene = new_scene('BWR | modern '+id)
    transform = Matrix.Translation(xyz((size[0]/2,0,size[2]/2))) @ Matrix.Scale(scale,4)
    port_names = [r for r,_,_ in ports]
    materials = {}
    for o in source.objects:
        if o.type != 'MESH' or any(o.name.startswith(id+'__'+r) for r in port_names):
            continue
        clone = o.copy()
        clone.data = o.data.copy()
        clone.name = 'modern | '+o.name
        scene.collection.objects.link(clone)
        clone.matrix_world = transform @ o.matrix_world
        for i,m in enumerate(clone.data.materials):
            if m.name not in materials:
                materials[m.name] = m.copy()
            clone.data.materials[i] = materials[m.name]
    # Place each neck on one axis from the casing to the grid-face center.
    for role,cell,face in ports:
        axis = Vector(DIRECTIONS[face])
        endpoint = Vector(cell)+Vector((.5,.5,.5))+axis*.5
        original = source.objects[id+'__'+role+'_neck']
        p = [Vector(game(transform @ original.matrix_world @ Vector(c))) for c in original.bound_box]
        k = next(i for i in range(3) if axis[i])
        inner_end = min(v[k] for v in p) if axis[k]>0 else max(v[k] for v in p)
        start = endpoint.copy()
        start[k] = inner_end-axis[k]*.08
        # A straight neck enters the casing at the snapped center; no lateral adapter.
        sleeve(scene,role+' neck',start,endpoint-axis*.08,.25,.205,BODY)
        flange(scene,role,endpoint,axis)
        band = AMBER if role.startswith('steam') else BLUE if role=='water_suction' else TEAL
        sleeve(scene,role+' service band',endpoint-axis*.30,endpoint-axis*.20,.257,.249,band)
        marker = bpy.data.objects.new(role.upper(),None)
        marker.location = xyz(endpoint)
        marker['face'] = face
        marker['cell'] = cell
        scene.collection.objects.link(marker)
    meta = {'id':id,'size':size,'controller':[size[0]//2,0,size[2]//2], 'scale':scale,
            'ports':[{'role':r.upper(),'cell':c,'face':f} for r,c,f in ports]}
    scene['layout'] = json.dumps(meta)
    export_scene(scene,id+'.json',meta)
    return scene

def curved_tube(scene,name,path,tangents,radius,mat):
    """Sweep a continuous round tube through a right-angle bend."""
    n=16
    plane=Vector(tangents[0]).cross(Vector(tangents[-1])).normalized()
    vs=[]
    for p,t in zip(path,tangents):
        u=plane;v=Vector(t).cross(u).normalized()
        vs += [tuple(Vector(p)+radius*(math.cos(i*math.tau/n)*u+math.sin(i*math.tau/n)*v)) for i in range(n)]
    fs=[(j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i) for j in range(len(path)-1) for i in range(n)]
    return mesh_object(scene,name,vs,fs,mat)

def build_pipes():
    # The order matches Direction.values() in Minecraft.
    dirs=list(DIRECTIONS.values())
    models=[]
    for mask in range(64):
        scene=new_scene('BWR | pipe '+str(mask).zfill(2))
        active=[Vector(dirs[i]) for i in range(6) if mask & (1<<i)]
        c=Vector((.5,.5,.5))
        if len(active)==2 and abs(active[0].dot(active[1]))<.1:
            a,b=active
            # Straight tails keep the flanges and dye bands coaxial with the bore.
            radius=.32
            origin=c+(a+b)*radius
            path=[];tangents=[]
            for i in range(13):
                angle=i*math.pi/24
                path.append(origin-radius*(b*math.cos(angle)+a*math.sin(angle)))
                tangents.append(b*math.sin(angle)-a*math.cos(angle))
            curved_tube(scene,'swept elbow',path,tangents,.25,BODY)
            for d in active:
                sleeve(scene,'elbow tail',c+d*radius,c+d*.5,.25,.205,BODY,16)
        elif len(active)==2:
            sleeve(scene,'straight barrel',c+active[0]*.5,c+active[1]*.5,.25,.205,BODY,16)
        else:
            # Cast round junction, including a closed end on an unused fitting.
            vs=[];fs=[];n=16;rows=8
            for j in range(rows+1):
                angle=math.pi*j/rows
                for i in range(n):
                    vs.append(tuple(c+.265*Vector((math.sin(angle)*math.cos(i*math.tau/n),math.cos(angle),math.sin(angle)*math.sin(i*math.tau/n)))))
            fs=[(j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i) for j in range(rows) for i in range(n)]
            mesh_object(scene,'cast round junction',vs,fs,BODY)
            for i,d in enumerate(active):
                sleeve(scene,'branch '+str(i),c+d*.16,c+d*.5,.25,.205,BODY,16)
        for i,d in enumerate(active):
            # Narrow clamped flange and a replaceable painted identification band.
            p=c+d*.5
            sleeve(scene,'coupling '+str(i),p-d*.065,p,.315,.205,STEEL,16)
            sleeve(scene,'paint '+str(i),p-d*.16,p-d*.09,.26,.249,PAINT,16)
            _,u,v=basis(d)
            for j in range(4):
                q=p+.287*(math.cos(j*math.pi/2)*u+math.sin(j*math.pi/2)*v)
                bolt(scene,'fastener',q-d*.075,q-d*.01,.018)
        if not active:
            sleeve(scene,'paint center',(.5,.41,.5),(.5,.59,.5),.273,.265,PAINT,16)
        export_scene(scene,'pipe_'+str(mask)+'.json',{'mask':mask})
        models.append(scene)
    return models

def save_library():
    bpy.data.libraries.write(str(OUT/'modern_pumps_and_pipes.blend'),set(SCENES),compress=True)

if __name__ == '__main__':
    for id in CONFIG:
        build_pump(id)
    build_pipes()
    save_library()
