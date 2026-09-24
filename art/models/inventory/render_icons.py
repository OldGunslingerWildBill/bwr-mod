"""Blender-only reproducible inventory renders of the shipped OBJ meshes.

Run: blender --background --python art/models/inventory/render_icons.py
Only GUI models are replaced; held items and placed machines retain their mesh.
"""
import bpy, json, math
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'mod/src/main/resources/assets/bwr'

def resolve(model):
    if model.get('loader') == 'neoforge:obj':
        return ASSETS / model['model'].split(':', 1)[1]
    parent = model.get('parent', '')
    if parent.startswith('bwr:'):
        return resolve(json.loads((ASSETS / 'models' / (parent[4:] + '.json')).read_text()))

def render(item, obj):
    scene = bpy.data.scenes.new('Inventory | ' + item)
    verts, faces, indices, colors = [], [], [], {}
    active = None
    for line in (obj.parent / 'materials.mtl').read_text().splitlines():
        words = line.split()
        if not words: continue
        if words[0] == 'newmtl': active = words[1]
        elif words[0] == 'Kd': colors[active] = list(map(float, words[1:4]))
    matids = {name: i for i, name in enumerate(colors)}
    for line in obj.read_text().splitlines():
        words = line.split()
        if not words: continue
        if words[0] == 'v':
            x, y, z = map(float, words[1:4]); verts.append((x, -z, y))
        elif words[0] == 'usemtl': active = words[1]
        elif words[0] == 'f':
            faces.append([int(v.split('/')[0])-1 for v in words[1:]])
            indices.append(matids[active])
    mesh = bpy.data.meshes.new(item); mesh.from_pydata(verts, [], faces); mesh.update()
    model = bpy.data.objects.new(item, mesh); scene.collection.objects.link(model)
    for name, color in colors.items():
        mat = bpy.data.materials.new(item + ' ' + name); mat.use_nodes = True
        node = next(n for n in mat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
        node.inputs['Base Color'].default_value = (*color, 1)
        node.inputs['Roughness'].default_value = .5
        mesh.materials.append(mat)
    for poly, index in zip(mesh.polygons, indices): poly.material_index = index
    low = Vector([min(v[a] for v in verts) for a in range(3)])
    high = Vector([max(v[a] for v in verts) for a in range(3)])
    center = (low + high)/2; span = max(high-low)
    cam = bpy.data.cameras.new(item + ' camera'); camera = bpy.data.objects.new(cam.name, cam)
    scene.collection.objects.link(camera); scene.camera = camera
    if 'ORTHO' in [v.identifier for v in cam.bl_rna.properties['type'].enum_items]: cam.type = 'ORTHO'
    camera.location = center + Vector((1.7, -2.2, 1.6))*span
    camera.rotation_euler = (center-camera.location).to_track_quat('-Z','Y').to_euler()
    inverse = camera.rotation_euler.to_matrix().transposed()
    projected = [inverse @ (Vector(v)-center) for v in verts]
    extent = max(max(v[a] for v in projected)-min(v[a] for v in projected) for a in (0,1))
    cam.ortho_scale = extent * 1.14
    for name, offset, power in [('key',(-2,-3,4),500),('fill',(3,-1,2),300),('rim',(0,3,3),400)]:
        lamp = bpy.data.lights.new(item+name, 'AREA'); lamp.energy = power*span*span; lamp.size = 3*span
        light = bpy.data.objects.new(lamp.name, lamp); scene.collection.objects.link(light)
        light.location = center + Vector(offset)*span
        light.rotation_euler = (center-light.location).to_track_quat('-Z','Y').to_euler()
    try: scene.render.engine = 'BLENDER_EEVEE'
    except TypeError: pass
    scene.render.film_transparent = True
    scene.render.resolution_x = scene.render.resolution_y = 128; scene.render.resolution_percentage = 100
    for prop, value in [('file_format','PNG'),('color_mode','RGBA')]:
        if value in [v.identifier for v in scene.render.image_settings.bl_rna.properties[prop].enum_items]:
            setattr(scene.render.image_settings,prop,value)
    texture = ASSETS / 'textures/item/inventory' / (item + '.png'); texture.parent.mkdir(parents=True,exist_ok=True)
    scene.render.filepath = str(texture)
    bpy.ops.render.render(write_still=True, scene=scene.name)
    gui = {'gui_light':'front','ambientocclusion':False,'textures':{'icon':'bwr:item/inventory/'+item,'particle':'bwr:item/inventory/'+item},
           'elements':[{'from':[0,0,8],'to':[16,16,8],'shade':False,'faces':{
               'north':{'uv':[16,0,0,16],'texture':'#icon'},'south':{'uv':[0,0,16,16],'texture':'#icon'}}}]}
    path = ASSETS / 'models/inventory' / (item + '.json'); path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(gui,indent=2)+'\n')
    # The background process owns these transient scenes, not the user's live session.
    for ob in list(scene.objects): bpy.data.objects.remove(ob,do_unlink=True)
    bpy.data.scenes.remove(scene)
    print('BWR INVENTORY ICON:',item,flush=True)

def main():
    items = []
    for path in sorted((ASSETS / 'models/item').glob('*.json')):
        # Painted pipes retain their live tint, and their small meshes are inexpensive.
        if path.stem in ('pressurised_tube','high_pressure_water_pipe'): continue
        obj = resolve(json.loads(path.read_text()))
        if obj is None or not obj.exists(): continue
        items.append(path.stem)
        render(path.stem,obj)
    (ASSETS / 'inventory_icons.json').write_text(json.dumps(items,indent=2)+'\n')

if __name__ == '__main__': main()
