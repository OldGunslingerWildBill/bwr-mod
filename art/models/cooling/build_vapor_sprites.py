"""Render soft cooling-tower droplet clouds in Blender for inexpensive game billboards."""
import bpy
from pathlib import Path
from mathutils import Vector

ROOT=Path(__file__).resolve().parents[3]
bpy.ops.wm.read_factory_settings(use_empty=True)
scene=bpy.context.scene
scene.name='BWR | cooling vapor sprite studio'
scene.render.engine='CYCLES';scene.cycles.samples=48
scene.cycles.use_denoising=True
scene.render.resolution_x=128;scene.render.resolution_y=128;scene.render.resolution_percentage=100
scene.render.film_transparent=True
scene.render.image_settings.file_format='PNG';scene.render.image_settings.color_mode='RGBA'
scene.view_settings.view_transform='Standard'
world=bpy.data.worlds.new('Cloud illumination');world.use_nodes=True
world.node_tree.nodes['Background'].inputs['Color'].default_value=(1,1,1,1)
world.node_tree.nodes['Background'].inputs['Strength'].default_value=.65;scene.world=world
bpy.ops.mesh.primitive_plane_add(size=2,rotation=(1.57079632679,0,0))
cloud=bpy.context.object;cloud.name='Procedural condensed droplet volume'
mat=bpy.data.materials.new('Soft heterogeneous white vapor');mat.use_nodes=True
nodes=mat.node_tree.nodes;links=mat.node_tree.links;nodes.clear()
output=nodes.new('ShaderNodeOutputMaterial');mix=nodes.new('ShaderNodeMixShader')
transparent=nodes.new('ShaderNodeBsdfTransparent');white=nodes.new('ShaderNodeEmission');white.inputs['Color'].default_value=(1,1,1,1)
links.new(transparent.outputs[0],mix.inputs[1]);links.new(white.outputs[0],mix.inputs[2]);links.new(mix.outputs[0],output.inputs['Surface'])
coords=nodes.new('ShaderNodeTexCoord');distance=nodes.new('ShaderNodeVectorMath');distance.operation='DISTANCE';distance.inputs[1].default_value=(.5,.5,0)
links.new(coords.outputs['UV'],distance.inputs[0])
falloff=nodes.new('ShaderNodeMapRange');falloff.inputs['From Min'].default_value=.02;falloff.inputs['From Max'].default_value=.49;falloff.inputs['To Min'].default_value=1;falloff.inputs['To Max'].default_value=0;falloff.clamp=True;links.new(distance.outputs['Value'],falloff.inputs['Value'])
soft=nodes.new('ShaderNodeMath');soft.operation='POWER';soft.inputs[1].default_value=1.2;links.new(falloff.outputs['Result'],soft.inputs[0])
noise=nodes.new('ShaderNodeTexNoise');noise.noise_dimensions='4D';noise.inputs['Scale'].default_value=5;noise.inputs['Detail'].default_value=3;noise.inputs['Roughness'].default_value=.65;links.new(coords.outputs['UV'],noise.inputs['Vector'])
contrast=nodes.new('ShaderNodeMapRange');contrast.inputs['From Min'].default_value=.30;contrast.inputs['From Max'].default_value=.68;contrast.inputs['To Min'].default_value=.15;contrast.inputs['To Max'].default_value=1.5;contrast.clamp=True;links.new(noise.outputs['Fac'],contrast.inputs['Value'])
mul=nodes.new('ShaderNodeMath');mul.operation='MULTIPLY';links.new(contrast.outputs['Result'],mul.inputs[0]);links.new(soft.outputs[0],mul.inputs[1]);links.new(mul.outputs[0],mix.inputs[0])
cloud.data.materials.append(mat)
bpy.ops.object.light_add(type='AREA',location=(-3,-4,5));light=bpy.context.object;light.name='Broad white key';light.data.energy=850;light.data.shape='DISK';light.data.size=5;light.rotation_euler=(Vector((0,0,0))-light.location).to_track_quat('-Z','Y').to_euler()
bpy.ops.object.camera_add(location=(0,-5,0));camera=bpy.context.object;camera.rotation_euler=(Vector((0,0,0))-camera.location).to_track_quat('-Z','Y').to_euler();camera.data.type='ORTHO';camera.data.ortho_scale=2.1;scene.camera=camera
folder=ROOT/'mod/src/main/resources/assets/bwr/textures/particle';folder.mkdir(parents=True,exist_ok=True)
for i in range(4):
    noise.inputs['W'].default_value=i*2.73
    scene.render.filepath=str(folder/f'cooling_vapor_{i}.png')
    bpy.ops.render.render(write_still=True)
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(Path(__file__).with_name('cooling_vapor.blend')))
