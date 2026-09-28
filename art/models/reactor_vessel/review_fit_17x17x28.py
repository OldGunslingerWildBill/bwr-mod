"""Editable Blender assembly from the actual 17x17x28 Minecraft verification snapshot.
Run build_fit_review() in Blender after :mod:runReactorFitCheck has exported layout.json.
Existing scenes are preserved. Meshes come from our original Blender source exports.
The removable front shell and head are hidden only for this inspection scene.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/reactor_vessel'

def build_fit_review():
    runtime=ROOT/'mod/run/reactorFitCheck/reactor-fit-check/layout.json'
    snapshot=ROOT/'art/models/reactor_vessel/fit_17x17x28-layout.json'
    data=json.loads((runtime if runtime.exists() else snapshot).read_text())
    assert (data['width'],data['depth'],data['height'])==(17,17,27)
    s=new_scene('RVU | verified 17x17x28 assembly')
    cache={}
    def source(path,cut=False):
        key=(path,cut)
        if key in cache:return cache[key]
        model=json.loads((ROOT/'art/models'/path).read_text())
        vertices=[];faces=[];indices=[];mats=[];lookup={}
        for name,mat in model['materials'].items():
            lookup[name]=len(mats)
            mats.append(material('Fit | '+name,tuple(v**2.2 for v in mat['color']),.42))
        for face in model['faces']:
            points=[v[:3] for v in face['vertices']]
            if cut and sum(v[0] for v in points)/len(points)<0:continue
            base=len(vertices);vertices.extend(xyz(v) for v in points)
            faces.append(tuple(range(base,base+len(points))));indices.append(lookup[face['material']])
        mesh=bpy.data.meshes.new('Fit | '+path+(' rear half' if cut else ''))
        mesh.from_pydata(vertices,[],faces);mesh.update()
        for mat in mats:mesh.materials.append(mat)
        for poly,idx in zip(mesh.polygons,indices):poly.material_index=idx
        cache[key]=mesh;return mesh
    def part(name,path,pos=(0,0,0),scale=(1,1,1),yaw=0,cut=False,hidden=False):
        o=bpy.data.objects.new(name,source(path,cut));s.collection.objects.link(o)
        o.location=xyz(pos);o.scale=(scale[0],scale[2],scale[1]);o.rotation_euler.z=-yaw
        o.hide_viewport=hidden;o.hide_render=hidden
        return o
    w=data['width'];d=data['depth'];h=data['height'];core=data['core']
    top=core['top'];bottom=core['bottom'];px=core['pitchX'];pz=core['pitchZ'];detail=min(px,pz)
    head_height=min(h*.22,min(w,d)*.18);flange=h-head_height-.4
    part('Dished lower head','reactor_vessel/bottom.json',scale=(w,1,d),cut=True)
    part('Barrel | rear half for inspection','reactor_vessel/barrel.json',(0,1.16,0),(w,flange-1.10,d),cut=True)
    part('Complete barrel | enable for closed exterior','reactor_vessel/barrel.json',(0,1.16,0),(w,flange-1.10,d),hidden=True)
    part('Removable vessel head','reactor_vessel/head.json',(0,h-head_height,0),(w,head_height/1.65,d),hidden=True)
    part('Vessel top flange','reactor_vessel/flange.json',(0,flange,0),(w,1,d))
    part('Shroud | rear half for inspection','reactor_core/shroud.json',(0,1.7,0),(w,top-1.55,d),cut=True)
    for y in [1.7,top+.15]:part('Shroud rim','reactor_core/shroud_rim.json',(0,y,0),(w,1,d))
    for i,cell in enumerate(data['fuel']):
        x=cell['x'];z=cell['z']
        part(f'Bundle {i+1} | 9-block channel','reactor_core/channel.json',(x,bottom,z),(px,top-bottom,pz))
        part(f'Bundle {i+1} handle','reactor_core/bundle_top.json',(x,top,z),(px,detail,pz))
        part(f'Bundle {i+1} support','reactor_core/support_cell.json',(x,bottom-.18*detail,z),(px,detail,pz))
        part(f'Bundle {i+1} guide','reactor_core/guide_cell.json',(x,top-.05,z),(px,detail,pz))
    for i,m in enumerate(data['drives']):
        x=m['x'];z=m['z'];scale=m['scale']
        part(f'CRD {i+1} | aligned with blade','control_rod_drive/control_rod_drive.json',(x-.5*scale,-1,z-.5*scale),(scale,1,scale))
        part(f'CRD {i+1} penetration','reactor_core/guide_tube.json',(x,-.03,z),(scale*.65,m['neckHeight']+.03,scale*.65))
        part(f'CRD {i+1} upper guide','reactor_core/guide_tube.json',(x,1.28,z),(px,.55,pz))
    for i,m in enumerate(data['jets']):
        scale=m['scale'];length=m['straight']
        for piece,y,sy in [('lower',0,1),('straight',1.6,length),('upper',1.6+length,1),('brace',1.6+.42*length,1)]:
            part(f'Jet {i+1} | {piece}','reactor_vessel/installed_jet/'+piece+'.json',
                 (m['x'],m['bottom']+y*scale,m['z']),(scale,scale*sy,scale),m['yaw'])
    for label,y in [('CRD bottom',-1),('Core bottom',bottom),('Core top',top),('Vessel cap',h)]:
        marker(s,label,(-9,y,0),height_blocks=y+1)
    s['dimensions']='17x17 construction footprint; 28 total height from CRD bottom to cap; 9-block active fuel'
    s['counts']='764 fuel bundles; 185 mapped control drives; 12 installed jet assemblies'
    s['layout_source']='Actual Minecraft client snapshot: mod/run/reactorFitCheck/reactor-fit-check/layout.json'
    s['cutaway']='Front barrel / head omitted only to inspect internals; in-game closed vessel is fully enclosed.'
    studio(s,(0,11,0),(-32,24,-35),34,(1400,1200))
    viewport(s,(0,11,0),(-32,24,-35),38)
    s.render.filepath=str(OUT/'fit_17x17x28.png')
    bpy.data.libraries.write(str(OUT/'fit_17x17x28.blend'),{s},fake_user=True)
    print('FIT REVIEW',len(s.objects),'objects',len(data['fuel']),'bundles',len(data['drives']),'drives')
    return s
