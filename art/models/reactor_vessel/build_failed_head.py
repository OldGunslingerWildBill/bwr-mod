"""Original stylized pressure-failure asset, authored/exported in Blender.
The crown is missing; torn steel and bent studs remain on the vessel rim.
This is game artwork, not a prediction of an actual reactor failure shape.
"""
from pathlib import Path
helper=Path(__file__).with_name('build_models.py')
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))

def build_failure():
    s=new_scene('RVU | failed head torn rim')
    steel=material('Accident oxidized steel',(.27,.22,.19),.68)
    edge=material('Accident exposed fracture',(.52,.51,.49),.65)
    # Closed thickness with an irregular jagged edge; the centre remains open.
    n=96;vs=[]
    for row in range(4):
        for i in range(n):
            a=i*math.tau/n
            top=.28+.11*math.sin(i*2.4)+(.65 if i%13==0 else 0)
            r=(.458 if row in (0,1) else .437)
            y=.18 if row in (0,3) else top
            if row in (1,2):r+=.012*math.sin(i*1.3)
            vs.append((r*math.cos(a),y,r*math.sin(a)))
    fs=[]
    for row in range(4):
        for i in range(n):fs.append((row*n+i,row*n+(i+1)%n,((row+1)%4)*n+(i+1)%n,((row+1)%4)*n+i))
    mesh_object(s,'jagged torn crown rim',vs,fs,steel)
    # Folded segment of head remains attached at the rear; no full lid floats above the opening.
    for i in range(9):
        a0=(i/9*.68+.1)*math.pi;a1=((i+1)/9*.68+.1)*math.pi
        pts=[]
        for r,y in [(.449,.22),(.443,.63),(.40,1.03),(.37,1.34),(.361,1.31),(.39,.99),(.433,.6),(.439,.22)]:
            pts.extend([(r*math.cos(a0),y,r*math.sin(a0)),(r*math.cos(a1),y,r*math.sin(a1))])
        faces=[(j*2,j*2+1,((j+1)%8)*2+1,((j+1)%8)*2) for j in range(8)]
        faces += [tuple(range(0,16,2)),tuple(range(15,0,-2))]
        mesh_object(s,'peeled head steel segment',pts,faces,edge)
    for i in range(24):
        a=i*math.tau/24;r=.472
        cylinder(s,'sheared tension stud',(r*math.cos(a),.05,r*math.sin(a)),
                 ((r+.006*math.sin(i))*math.cos(a),.3+(i%3)*.055,(r+.006)*math.sin(a)),.0038,SKIRT,8)
    export_scene(s,'failed_head.json',{'id':'failed_head','purpose':'Stylized missing crown and damaged head rim'})
    preview=new_scene('RVU | damaged vessel head review')
    for source in s.objects:
        o=source.copy();o.data=source.data.copy();preview.collection.objects.link(o)
        for v in o.data.vertices:
            p=game(v.co);v.co=xyz((p[0]*17,p[1]*1.85,p[2]*17))
    sleeve(preview,'review vessel barrel',(0,-5,0),(0,0,0),7.82,7.61,SHELL,96)
    sleeve(preview,'review bolted flange',(0,0,0),(0,.3,0),8.245,7.395,STEEL,96)
    studio(preview,(0,-1,0),(20,17,24),24,(1100,850))
    preview.render.filepath=str(OUT/'failed_head_preview.png')
    bpy.data.libraries.write(str(OUT/'failed_head.blend'),{s,preview},fake_user=True)
    viewport(preview,(0,-1,0),(20,17,24),24)
    return preview
