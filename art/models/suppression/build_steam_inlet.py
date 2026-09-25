"""Blender source for the enclosed tank's steam flange and review scene."""
from pathlib import Path
source=Path(__file__).with_name('build_models.py')
exec(compile(source.read_text(encoding='utf-8'),str(source),'exec'))

def build_steam_inlet():
    band=material('suppression steam orange',(.82,.20,.025),.35)
    port=port_scene('suppression tank steam inlet',band)
    marker(port,'Steam inlet face',(.5,.5,0),circuit='steam')
    export_scene(port,'steam_inlet.json',{'id':'steam_inlet'})
    studio(port,(.5,.5,.5),(2.8,2.3,-3),1.65,(128,128))
    port.render.film_transparent=True
    port.render.image_settings.file_format='PNG'
    icon=ROOT/'mod/src/main/resources/assets/bwr/textures/item/inventory/suppression_pool_steam_inlet.png'
    icon.parent.mkdir(parents=True,exist_ok=True);port.render.filepath=str(icon)
    bpy.ops.render.render(write_still=True,scene=port.name)

    wall=new_scene('BWR | enclosed tank concrete');panel(wall)
    returned=port_scene('enclosed tank fill and return',RETURN)
    suction=port_scene('enclosed tank suction',COLD)
    preview=new_scene('BWR | enclosed suppression tank')
    for x in range(9):
        for y in range(5):
            for z in range(7):
                if x not in (0,8) and y not in (0,4) and z not in (0,6):continue
                part=port if (x,y,z)==(2,2,0) else returned if (x,y,z)==(6,1,0) else suction if (x,y,z)==(4,1,0) else wall
                for obj in part.objects:
                    if obj.type!='MESH':continue
                    copy=obj.copy();copy.data=obj.data.copy();preview.collection.objects.link(copy);copy.location+=xyz((x,y,z))
    studio(preview,(4.5,2,3.5),(16,13,-17),14,(1200,900))
    viewport(preview,(4.5,2,3.5),(16,13,-17),16)
    preview.render.filepath=str(OUT/'suppression-tank.png')
    bpy.ops.render.render(write_still=True,scene=preview.name)
    bpy.data.libraries.write(str(OUT/'enclosed_suppression_tank.blend'),{port,wall,returned,suction,preview},fake_user=True)
    print('Exported enclosed suppression tank steam flange, icon and review scene')

if __name__=='__main__':build_steam_inlet()
