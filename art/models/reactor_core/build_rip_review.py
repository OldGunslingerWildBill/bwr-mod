"""Rebuild the continuous RIP core in Blender from the game's exported layout.

Uses original Blender core pieces and the supplied pump mesh. Run ExportRipLayouts
first. This is an editable assembled review, not a separate runtime model/renderer.
Dimensions: GE ABWR DCD Tier 2, Table 5.3-2. The construction grid and visual
shroud proportions are separate; the Blender review uses both game exports.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/reactor_core'

def build():
    pieces={}
    for filename in ('reactor_core.blend','abwr_shroud.blend'):
        with bpy.data.libraries.load(str(OUT/filename),link=False) as (source,target):
            target.scenes=[name for name in source.scenes if name.startswith('Realistic BWR | core ')]
        pieces.update({s.name.split('core ',1)[1].split('.')[0]:s for s in target.scenes})
    assert all(name in pieces for name in ('channel','abwr_shroud','abwr_shroud_rim','guide_cell','blade'))
    dims=json.loads((OUT/'abwr_dimensions.json').read_text(encoding='utf-8'))
    # Reuse the real pump atlas, UVs and round casing rather than a proxy cylinder.
    meshfile=ROOT/'mod/src/main/resources/assets/bwr/models/block/pumps/rip_pump/assembled.obj'
    vertices=[];uvs=[];faces=[];face_uv=[]
    for line in meshfile.read_text(encoding='utf-8').splitlines():
        a=line.split()
        if not a:continue
        if a[0]=='v':vertices.append(tuple(map(float,a[1:4])))
        elif a[0]=='vt':uvs.append(tuple(map(float,a[1:3])))
        elif a[0]=='f':
            corners=[c.split('/') for c in a[1:]]
            faces.append(tuple(int(c[0])-1 for c in corners))
            face_uv.append(tuple(int(c[1])-1 for c in corners))
    pump_mesh=bpy.data.meshes.new('Existing RIP with original atlas')
    pump_mesh.from_pydata([xyz(v) for v in vertices],[],faces);pump_mesh.update()
    layer=pump_mesh.uv_layers.new(name='Original atlas UV')
    for poly,uv in zip(pump_mesh.polygons,face_uv):
        for loop,index in zip(poly.loop_indices,uv):layer.data[loop].uv=uvs[index]
    mat=material('existing RIP atlas',(.09,.16,.22),.45)
    texture=mat.node_tree.nodes.new('ShaderNodeTexImage')
    texture.image=bpy.data.images.load(str(ROOT/'mod/src/main/resources/assets/bwr/textures/block/pumps/pump_atlas.png'),check_existing=True)
    texture.image.pack()
    shader=next(n for n in mat.node_tree.nodes if n.type=='BSDF_PRINCIPLED')
    mat.node_tree.links.new(texture.outputs['Color'],shader.inputs['Base Color'])
    pump_mesh.materials.append(mat)
    reviews=[]
    for data in json.loads((OUT/'rip_layouts.json').read_text(encoding='utf-8')):
        w,d=data['width'],data['depth'];W,D=w+2,d+2;q=data['scale']
        s=new_scene('Realistic BWR | continuous RIP core '+str(W)+'x'+str(D));reviews.append(s)
        def inst(name,offset,scale):
            for src in pieces[name].objects:
                o=src.copy();o.data=src.data;s.collection.objects.link(o)
                o.location=xyz(offset);o.scale=(scale[0],scale[2],scale[1])
        px=data['pitch_x'];pz=data['pitch_z'];detail=min(px,pz)
        radial=max(math.hypot((abs(slot%42+.5-21)+.5)*px/W,(abs(slot//42+.5-21)+.5)*pz/D) for slot in data['fuel'])
        assert radial<=dims['fuel_radius']+1e-8, 'Fuel support corner intersects shroud'
        bottom=2;top=3.85 if w==5 and d==5 else 15.85
        for slot in data['fuel']:
            x=(slot%42+.5-21)*px;z=(slot//42+.5-21)*pz
            inst('support_cell',(x,bottom-.18*detail,z),(px,detail,pz))
            inst('channel',(x,bottom,z),(px,top-bottom,pz))
            inst('bundle_top',(x,top,z),(px,detail,pz))
            inst('guide_cell',(x,top-.05,z),(px,detail,pz))
        for x,z in data['drives']:
            xx=(x+.5-w/2)*2*px;zz=(z+.5-d/2)*2*pz
            inst('guide_tube',(xx,1.28,zz),(px,.55,pz))
            inst('blade',(xx,bottom,zz),(px,top-bottom,pz))
        inst('abwr_shroud',(0,1.7,0),(W,top-1.55,D))
        for y in (1.7,top+.15):inst('abwr_shroud_rim',(0,y,0),(W,1,D))
        for i,(x,z) in enumerate(data['mounts']):
            o=bpy.data.objects.new('Annular RIP '+str(i+1),pump_mesh);s.collection.objects.link(o)
            o.location=xyz((x+1-w/2,0,z+1-d/2))
        # An open lower-head outline shows vessel clearance without hiding the pumps.
        boundary=sleeve(s,'vessel inner boundary reference',(0,-.08,0),(0,.08,0),.448,.444,STEEL,96)
        boundary.scale=(W,D,1)
        boundary=sleeve(s,'upper vessel inner boundary reference',(0,top+.15,0),(0,top+.19,0),.448,.446,STEEL,96)
        boundary.scale=(W,D,1)
        s['assembly_count']=len(data['fuel']);s['drive_count']=len(data['drives']);s['pump_count']=len(data['mounts'])
        s['legacy_capacity_mask_ratio']=q
        s['fuel_pitch_x']=px;s['fuel_pitch_z']=pz
        s['shroud_to_vessel_id']=dims['shroud_od_mm']/dims['vessel_id_mm']
        s['annular_gap_x']=(.448-dims['outer_radius'])*W
        s['annular_gap_z']=(.448-dims['outer_radius'])*D
        s['source']=dims['source']+'; original game fuel capacity, construction mounts and pump artwork.'
        studio(s,(0,top*.5,0),(W,top+W,-D*1.3),max(W,D)*1.7,(1400,1100))
    reference=next(s for s in reviews if '17x17' in s.name)
    viewport(reference,(0,8,0),(17,34,-24),31)
    bpy.data.libraries.write(str(OUT/'reactor_core_rip.blend'),set(reviews),fake_user=True)
    print('Continuous RIP review scenes saved:',[(s.name,s['assembly_count'],s['pump_count']) for s in reviews])
    return reference
