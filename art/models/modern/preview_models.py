"""Render the authored .blend in background Blender; no game or source scene is edited."""
import bpy
import math
from pathlib import Path
from mathutils import Vector

OUT=Path(__file__).resolve().parent

def enum_set(obj,key,value):
    allowed=[i.identifier for i in obj.bl_rna.properties[key].enum_items]
    if value not in allowed: raise ValueError((key,value,allowed))
    setattr(obj,key,value)

def mat(name,color):
    m=bpy.data.materials.new(name);m.use_nodes=True
    n=next(n for n in m.node_tree.nodes if n.bl_idname=='ShaderNodeBsdfPrincipled')
    n.inputs['Base Color'].default_value=(*color,1)
    return m

def studio(scene,target,span):
    bpy.context.window.scene=scene
    try: scene.render.engine='BLENDER_EEVEE'
    except TypeError: pass
    camera=bpy.data.cameras.new('Preview camera');o=bpy.data.objects.new('Preview camera',camera);scene.collection.objects.link(o)
    target=Vector(target);o.location=target+Vector((-span*.8,-span*1.05,span*.68))
    o.rotation_euler=(target-o.location).to_track_quat('-Z','Y').to_euler()
    enum_set(camera,'type','ORTHO');camera.ortho_scale=span*1.22;scene.camera=o
    types=[i.identifier for i in bpy.types.BlendDataLights.bl_rna.functions['new'].parameters['type'].enum_items]
    assert 'AREA' in types
    for name,offset,power,size in [('key',(-5,-5,10),2000,8),('fill',(5,-1,7),1600,7),('rim',(2,7,8),2300,6)]:
        light=bpy.data.lights.new(name,'AREA');light.energy=power;light.size=size
        lamp=bpy.data.objects.new(name,light);scene.collection.objects.link(lamp);lamp.location=target+Vector(offset)
        lamp.rotation_euler=(target-lamp.location).to_track_quat('-Z','Y').to_euler()
    me=bpy.data.meshes.new('studio floor');me.from_pydata([(-50,-50,-.035),(50,-50,-.035),(50,50,-.035),(-50,50,-.035)],[],[(0,1,2,3)])
    floor=bpy.data.objects.new('studio floor',me);scene.collection.objects.link(floor);me.materials.append(mat('floor',(.045,.055,.065)))
    scene.world=bpy.data.worlds.new('Preview world');scene.world.use_nodes=True
    next(n for n in scene.world.node_tree.nodes if n.bl_idname=='ShaderNodeBackground').inputs[0].default_value=(.18,.21,.25,1)
    scene.render.resolution_x=1400;scene.render.resolution_y=900;scene.render.resolution_percentage=100
    enum_set(scene.render.image_settings,'file_format','PNG')

for id,width,height in [('lpcs_pump',5,5),('hpcs_pump',9,4),('rhr_pump',7,3),('motor_feed_pump',7,3),('turbine_feed_pump',9,3)]:
    scene=bpy.data.scenes['BWR | modern '+id]
    studio(scene,(width/2,-1.5,height*.45),width+2)
    scene.render.filepath=str(OUT/(id+'_preview.png'));bpy.ops.render.render(write_still=True)

scene=bpy.data.scenes.new('Pipe fittings preview')
for i,mask in enumerate((12,36,44,63)):
    source=next(s for s in bpy.data.scenes if s.name.startswith('BWR | pipe '+str(mask).zfill(2)))
    for row,color in enumerate(((.025,.35,.75),(.95,.3,.025))):
        for o in source.objects:
            if o.type!='MESH':continue
            clone=o.copy();clone.data=o.data.copy();clone.location+=Vector((i*1.6,-row*2,0));scene.collection.objects.link(clone)
            for j,m in enumerate(clone.data.materials):
                if m.get('tint_index',-1)==0:clone.data.materials[j]=mat('service band',color)
studio(scene,(2.9,-1.5,.5),7.7)
scene.render.filepath=str(OUT/'pipe_fittings_preview.png');bpy.ops.render.render(write_still=True)
bpy.context.window.scene=bpy.data.scenes['BWR | modern rhr_pump']
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'modern_pumps_and_pipes.blend'),compress=True)
