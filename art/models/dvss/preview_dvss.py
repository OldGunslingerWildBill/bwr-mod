"""Run after build_dvss.py in Blender. Adds a studio rig, outside game geometry."""
import bpy,math
from pathlib import Path
from mathutils import Vector
out=Path(__file__).resolve().parent
scene=bpy.context.scene
for previous in list(scene.collection.children):
    if previous.name.startswith('DVSS | preview rig'):
        for member in list(previous.objects):bpy.data.objects.remove(member,do_unlink=True)
        bpy.data.collections.remove(previous)
rig=bpy.data.collections.new('DVSS | preview rig');scene.collection.children.link(rig)
camera=bpy.data.cameras.new('DVSS camera');camera.type='ORTHO';camera.ortho_scale=12.7
obj=bpy.data.objects.new('DVSS camera',camera);rig.objects.link(obj)
obj.location=(15,16.67,13.33);target=Vector((2.5,-2.5,4.67));obj.rotation_euler=(target-obj.location).to_track_quat('-Z','Y').to_euler();scene.camera=obj
for name,pos,energy,size in [('key',(4,4,9),2300,7),('fill',(-5,1,5),1600,6),('rim',(2,-6,7),2500,4)]:
    light=bpy.data.lights.new('DVSS '+name,'AREA');light.energy=energy*(5/3)**2;light.shape='DISK';light.size=size*5/3
    o=bpy.data.objects.new('DVSS '+name,light);rig.objects.link(o);o.location=Vector(pos)*5/3;o.rotation_euler=(target-o.location).to_track_quat('-Z','Y').to_euler()
scene.world=bpy.data.worlds.new('DVSS studio');scene.world.color=(.12,.14,.17)
scene.render.engine='BLENDER_EEVEE';scene.render.resolution_x=1000;scene.render.resolution_y=1100;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.film_transparent=False
scene.render.filepath=str(out/'dvss_preview.png')
bpy.data.libraries.write(str(out/'dvss_recirculation_pump.blend'),{scene},compress=True)
bpy.ops.render.render(write_still=True)
print('Rendered',scene.render.filepath)
# Inspect the continuous suction bend from behind, underneath the casing.
saved_matrix=obj.matrix_world.copy();saved_scale=camera.ortho_scale
obj.location=(8,-10,2.8);target=Vector((2.5,-3.3,1.15))
obj.rotation_euler=(target-obj.location).to_track_quat('-Z','Y').to_euler();camera.ortho_scale=4.9
scene.render.filepath=str(out/'dvss_suction_detail.png')
bpy.ops.render.render(write_still=True)
obj.matrix_world=saved_matrix;camera.ortho_scale=saved_scale
scene.render.filepath=str(out/'dvss_preview.png')
