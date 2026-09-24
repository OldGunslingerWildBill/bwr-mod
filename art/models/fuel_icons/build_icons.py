"""Author and render the fuel catalogue in Blender. Run with blender --background --python this-file."""
import bpy, json, math
from pathlib import Path
from mathutils import Vector
ROOT=Path(__file__).resolve().parents[3]
ASSETS=ROOT/'mod/src/main/resources/assets/bwr'
MANIFEST=json.loads((Path(__file__).parent/'catalog.json').read_text())
scene=bpy.data.scenes.new('BWR | Fuel and specialty rod studio')
bpy.context.window.scene=scene
def mat(name,color,metal=.4):
 m=bpy.data.materials.new(name);m.use_nodes=True
 n=next(n for n in m.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
 n.inputs['Base Color'].default_value=(*color,1);n.inputs['Metallic'].default_value=metal;n.inputs['Roughness'].default_value=.34
 return m
steel=mat('Brushed zirconium',(.40,.48,.53));dark=mat('Graphite shadow',(.07,.10,.13));accent=mat('Replaceable identification bands',(.1,.5,.2));white=mat('Ceramic end fittings',(.76,.83,.85))
def box(name,at,size,m):
 bpy.ops.mesh.primitive_cube_add(size=1,location=at);o=bpy.context.object;o.name=name;o.dimensions=size;o.data.materials.append(m);return o
def cyl(name,at,r,h,m):
 bpy.ops.mesh.primitive_cylinder_add(vertices=12,radius=r,depth=h,location=at);o=bpy.context.object;o.name=name;o.data.materials.append(m);return o
for x in range(6):
 for y in range(6):cyl('Zirconium-clad fuel pin',((x-2.5)*.14,(y-2.5)*.14,1.72),.056,2.65,steel)
for z in [.39,.83,1.5,2.16,2.82,3.07]:
 for x in [-.44,.44]:box('Spacer perimeter',(x,0,z),(.08,.98,.11),accent if z in [.83,2.82] else steel)
 for y in [-.44,.44]:box('Spacer perimeter',(0,y,z),(.96,.08,.11),accent if z in [.83,2.82] else steel)
box('Lower tie plate',(0,0,.32),(1,1,.15),dark);box('Upper tie plate',(0,0,3.17),(1,1,.18),white)
for x in [-.30,.30]:box('Lifting bail leg',(x,0,3.46),(.11,.11,.4),steel)
box('Lifting bail',(0,0,3.65),(.71,.11,.11),steel)
cyl('Lower seating nose',(0,0,.10),.18,.23,steel)
fuel_objects=list(scene.objects)
for o in fuel_objects:o.hide_render=True
# A visibly distinct cassette for sources, fixed absorbers and irradiation targets.
for x in [-.2,0,.2]:cyl('Sealed rod capsule',(x,0,1.7),.085,2.8,steel)
for z in [.28,.75,2.7,3.17]:box('Cassette cross brace',(0,0,z),(.73,.32,.15),accent)
for x in [-.3,.3]:box('Cassette spine',(x,0,1.73),(.075,.12,2.9),dark)
box('Cassette lifting loop',(0,0,3.48),(.68,.10,.10),white)
for x in [-.29,.29]:box('Cassette lifting support',(x,0,3.32),(.1,.1,.33),white)
rod_objects=[o for o in scene.objects if o not in fuel_objects]
for o in rod_objects:o.hide_render=True
# Small cylindrical sealed sample/charge containers, with a recognizable colour cap.
cyl('Sample capsule',(0,0,1.65),.48,1.7,white);cyl('Sample cap',(0,0,2.52),.51,.20,accent);cyl('Sample base',(0,0,.75),.5,.15,dark)
box('Sample label',(0,-.484,1.68),(.62,.03,.75),accent)
sample_objects=[o for o in scene.objects if o not in fuel_objects+rod_objects]
for o in sample_objects:o.hide_render=True
cam=bpy.data.cameras.new('Fuel icon camera');camera=bpy.data.objects.new(cam.name,cam);scene.collection.objects.link(camera);scene.camera=camera
cam.type='ORTHO';cam.ortho_scale=4.05;camera.location=(4,-7,4.0);center=Vector((0,0,1.85));camera.rotation_euler=(center-camera.location).to_track_quat('-Z','Y').to_euler()
for name,at,power,size in [('key',(-3,-4,6),600,5),('fill',(4,-1,3),350,4),('rim',(1,3,5),650,3)]:
 d=bpy.data.lights.new(name,'AREA');d.energy=power;d.size=size;o=bpy.data.objects.new(name,d);scene.collection.objects.link(o);o.location=at;o.rotation_euler=(center-o.location).to_track_quat('-Z','Y').to_euler()
scene.world=bpy.data.worlds.new('Fuel studio');scene.world.color=(.20,.20,.20)
try:scene.render.engine='BLENDER_EEVEE'
except TypeError:pass
scene.render.film_transparent=True;scene.render.resolution_x=scene.render.resolution_y=128;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.image_settings.color_mode='RGBA'
try:scene.view_settings.view_transform='AgX'
except TypeError:pass
dest=ASSETS/'textures/item/fuel';dest.mkdir(parents=True,exist_ok=True)
def render(name,group,color):
 for o in fuel_objects+rod_objects+sample_objects:o.hide_render=o not in group
 node=next(n for n in accent.node_tree.nodes if n.type=='BSDF_PRINCIPLED');node.inputs['Base Color'].default_value=(*color,1)
 scene.render.filepath=str(ASSETS/'textures/item/fuel_assembly.png' if name=='fuel_assembly' else dest/(name+'.png'));bpy.ops.render.render(write_still=True,scene=scene.name)
for entry in MANIFEST:render(entry['id'],fuel_objects if entry['kind']=='fuel' else rod_objects if entry['kind']=='rod' else sample_objects,entry['color'])
render('fuel_assembly',fuel_objects,(.07,.5,.17))
bpy.ops.wm.save_as_mainfile(filepath=str(Path(__file__).parent/'fuel_icons.blend'))
print('BWR FUEL ICONS:',len(MANIFEST)+1,'rendered')
