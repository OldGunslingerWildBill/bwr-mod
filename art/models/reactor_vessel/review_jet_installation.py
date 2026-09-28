"""Blender fit review using the same shipped pump OBJ and placement math as the renderer.
No existing scene is removed. The pump remains original Blender-authored artwork.
The vessel wall is omitted only in this review so the downcomer can be inspected.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/reactor_vessel'

def import_mesh(s,path,name):
    vs=[];uv=[];faces=[];tex=[]
    for line in path.read_text().splitlines():
        p=line.split()
        if not p:continue
        if p[0]=='v':vs.append(tuple(map(float,p[1:4])))
        elif p[0]=='vt':uv.append(tuple(map(float,p[1:3])))
        elif p[0]=='f':
            indices=[q.split('/') for q in p[1:]]
            faces.append(tuple(int(q[0])-1 for q in indices));tex.append(tuple(int(q[1])-1 for q in indices))
    o=mesh_object(s,name,vs,faces,STEEL)
    layer=o.data.uv_layers.new(name='UVMap')
    for poly,indices in zip(o.data.polygons,tex):
        for loop,idx in zip(poly.loop_indices,indices):layer.data[loop].uv=uv[idx]
    return o

def build_jet_review():
    s=new_scene('RVU | installed jets around the shroud')
    path=ROOT/'mod/src/main/resources/assets/bwr/models/block/pumps/jet_pump/assembled.obj'
    source=import_mesh(s,path,'Blender jet source')
    mat=material('Jet original pump atlas',(1,1,1),.45)
    image=bpy.data.images.load(str(ROOT/'mod/src/main/resources/assets/bwr/textures/block/pumps/pump_atlas.png'),check_existing=True)
    mat.use_nodes=True
    shader=next(n for n in mat.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    tex=mat.node_tree.nodes.new('ShaderNodeTexImage');tex.image=image
    mat.node_tree.links.new(tex.outputs['Color'],shader.inputs['Base Color'])
    source.data.materials.clear();source.data.materials.append(mat)
    s.collection.objects.unlink(source)
    for center,w,h,n in [(-10,7,10,12),(5,17,22,12)]:
        top=h-6.15;bottom=1.72;jet_top=2+.72*(top-2)
        radius=(.375+.448)/2
        scale=min(1,((.448-.375)*w/2-.025)/math.hypot(.472175,.47275),
                  (2*radius*w*math.sin(math.pi/n)-.035)/(2*math.hypot(.472175,.47275)))
        sleeve(s,'core shroud',(center,1.7,0),(center,top+.15,0),.375*w,.369*w,NAVY,96)
        for y in [1.7,top+.15]:sleeve(s,'shroud rim',(center,y-.05,0),(center,y+.05,0),.375*w,.369*w,STEEL,96)
        cylinder(s,'lower plenum support',(center,1.5,0),(center,1.7,0),.448*w,CONCRETE,96)
        for i in range(n):
            a=i*math.tau/n;x=radius*w*math.cos(a);z=radius*w*math.sin(a);yaw=math.atan2(x,z)
            o=source.copy();o.data=source.data.copy();s.collection.objects.link(o);o.name=f'{w} wide | installed jet {i+1}'
            for v in o.data.vertices:
                gx,gy,gz=game(v.co);gx*=scale;gz=(gz+.24725)*scale
                v.co=xyz((center+x+gx*math.cos(yaw)+gz*math.sin(yaw),bottom+gy*(jet_top-bottom)/5.3,z-gx*math.sin(yaw)+gz*math.cos(yaw)))
        # Rim outline records vessel inside diameter without hiding the pump fit.
        sleeve(s,'vessel inner-wall reference',(center,1.48,0),(center,1.52,0),.448*w,.443*w,STEEL,96)
    s['reference']='GE BWR/6 arrangement; NRC Issue 12: jet pump inlets approximately two-thirds up core height.'
    s['game_behavior']='Tall construction geometry stays at saved cells until formation. Installed models sit outside the shroud.'
    studio(s,(-1,7,0),(28,25,-35),35,(1400,950))
    s.render.filepath=str(OUT/'installed_jets.png')
    bpy.data.libraries.write(str(OUT/'installed_jets.blend'),{s},fake_user=True)
    viewport(s,(-1,7,0),(28,25,-35),35)
    return s
