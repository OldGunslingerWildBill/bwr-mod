"""Blender-authored one-block axial steam valves. Existing scenes are preserved."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/modern/build_models.py'
exec(compile(helper.read_text().split('def build_pump(id):')[0],str(helper),'exec'))
OUT=ROOT/'art/models/steam_valves'
OUT.mkdir(parents=True,exist_ok=True)
RED=material('stop valve red',(.48,.018,.012),.25)
GOLD=material('control actuator brass',(.65,.30,.035),.5)
for id in ('steam_stop_valve','turbine_control_valve'):
    scene=new_scene('BWR | '+id)
    # Face centers match the new round high-pressure steam pipe exactly.
    sleeve(scene,'steam bore',(0.5,.5,0),(0.5,.5,1),.225,.19,BODY)
    sleeve(scene,'cast valve chest',(0.5,.5,.23),(0.5,.5,.77),.295,.19,BODY)
    for z,d in ((0,(0,0,-1)),(1,(0,0,1))):
        flange(scene,'steam connection',(0.5,.5,z),d,radius=.31,bolts=8)
    sleeve(scene,'bonnet',(0.5,.68,.5),(0.5,.80,.5),.16,.06,STEEL)
    sleeve(scene,'bonnet gasket',(0.5,.795,.5),(0.5,.815,.5),.18,.06,DARK)
    for x in (.38,.62):
        for z in (.38,.62):bolt(scene,'bonnet stud',(x,.78,z),(x,.84,z),.019)
    bolt(scene,'stem',(0.5,.78,.5),(0.5,.98,.5),.035)
    if id=='steam_stop_valve':
        sleeve(scene,'red handwheel',(0.5,.95,.5),(0.5,.985,.5),.245,.205,RED)
        for a in range(4):
            angle=a*math.tau/4
            bolt(scene,'handwheel spoke',(0.5,.965,.5),(.5+.215*math.cos(angle),.965,.5+.215*math.sin(angle)),.014)
    else:
        sleeve(scene,'servo actuator',(0.5,.83,.5),(0.5,.965,.5),.155,.035,GOLD)
        sleeve(scene,'actuator top cover',(0.5,.95,.5),(0.5,.99,.5),.17,.03,STEEL)
        sleeve(scene,'position indicator band',(0.5,.865,.5),(0.5,.89,.5),.16,.154,AMBER)
    export_scene(scene,id+'.json',{'size':[1,1,1],'controller':[0,0,0],'ports':[]})
# Review scene links the new meshes only; all older work remains in its own scenes.
review=bpy.data.scenes.new('BWR | Steam valve review')
for i,scene in enumerate(SCENES):
    for o in scene.objects:
        duplicate=o.copy();review.collection.objects.link(duplicate);duplicate.location.x+=i*1.5
bpy.context.window.scene=review
for area in bpy.context.screen.areas:
    if area.type=='VIEW_3D':
        area.spaces.active.shading.type='MATERIAL'
        area.spaces.active.region_3d.view_location=xyz((1.25,.55,.5))
        area.spaces.active.region_3d.view_distance=4
        area.spaces.active.region_3d.view_rotation=Vector((2,-3,2)).to_track_quat('Z','Y')
bpy.data.libraries.write(str(OUT/'steam_valves.blend'),set(SCENES+[review]),fake_user=True)
print('Saved two editable Blender valve models and exported their meshes.')
