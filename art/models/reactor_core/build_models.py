"""Blender-authored core pieces, based on Columbia FSAR 4.1.2 / 5.3-5.
Original game artwork. No cycle-specific fuel loading or engineering CAD implied.
Piece X/Z coordinates are one assembly pitch; runtime scales to the saved core.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/reactor_core';OUT.mkdir(parents=True,exist_ok=True)
CHANNEL=material('zirconium fuel channel',(.31,.36,.38),.75)
GRID=material('stainless top guide',(.14,.20,.23),.7)
INSERT=material('specialty insert cap',(.29,.22,.08),.5)

def square_frame(s,name,y,outer,inner,height,mat):
    # One manifold frame, rather than four overlapping boxes and hidden faces.
    vs=[(x*r,h,z*r) for r,h in ((outer,y),(outer,y+height),(inner,y+height),(inner,y))
        for x,z in ((-1,-1),(1,-1),(1,1),(-1,1))]
    fs=[(j*4+i,j*4+(i+1)%4,((j+1)%4)*4+(i+1)%4,((j+1)%4)*4+i) for j in range(4) for i in range(4)]
    return mesh_object(s,name,vs,fs,mat)

def build():
    pieces={}
    def part(name):
        s=new_scene('Realistic BWR | core '+name);pieces[name]=s;return s
    s=part('channel')
    # The opaque tie plate covers the channel interior; omit its invisible walls.
    box(s,'fuel channel casing',(-.43,0,-.43),(.43,1,.43),CHANNEL)
    s=part('bundle_top')
    square_frame(s,'upper tie plate',0,.43,.34,.10,STEEL)
    box(s,'dark channel interior',(-.34,.004,-.34),(.34,.024,.34),DARK)
    for t in (-.18,.18):
        box(s,'tie plate rib x',(-.34,.012,t-.018),(.34,.07,t+.018),CHANNEL)
        box(s,'tie plate rib z',(t-.018,.012,-.34),(t+.018,.07,.34),CHANNEL)
    for x in (-.25,.25):box(s,'lifting bail upright',(x-.024,.09,-.025),(x+.024,.33,.025),STEEL)
    box(s,'lifting bail handle',(-.274,.30,-.025),(.274,.35,.025),STEEL)
    s=part('insert_top')
    square_frame(s,'specialty channel rim',0,.43,.34,.10,INSERT)
    box(s,'specialty insert plate',(-.33,.01,-.33),(.33,.06,.33),GRID)
    for x in (-.24,.24):box(s,'insert lifting handle',(x-.025,.06,-.025),(x+.025,.28,.025),STEEL)
    box(s,'insert handle',(-.265,.25,-.025),(.265,.30,.025),STEEL)
    s=part('guide_cell')
    square_frame(s,'open top guide cell',0,.5,.468,.12,GRID)
    s=part('support_cell')
    square_frame(s,'orificed lower fuel support',0,.49,.22,.18,STEEL)
    sleeve(s,'fuel inlet support nozzle',(0,-.12,0),(0,0,0),.24,.18,GRID,8)
    s=part('guide_tube')
    sleeve(s,'control blade guide tube',(0,0,0),(0,1,0),.30,.27,GRID,16)
    s=part('blade')
    box(s,'cruciform east west wing',(-.86,0,-.025),(.86,1,.025),CHANNEL)
    box(s,'cruciform north south wing',(-.025,0,-.86),(.025,1,.86),CHANNEL)
    s=part('shroud')
    sleeve(s,'continuous core shroud',(0,0,0),(0,1,0),.375,.369,CHANNEL,96)
    s=part('shroud_rim')
    sleeve(s,'core shroud support flange',(0,0,0),(0,.13,0),.385,.369,STEEL,96)
    for name,s in pieces.items():export_scene(s,name+'.json',{'id':name})
    # Exact version-2 game mask, 764 channels / 185 drive cells at 17 x 17.
    review=new_scene('Realistic BWR | Columbia-inspired 764 assembly core')
    def inst(name,offset,scale):
        for src in pieces[name].objects:
            o=src.copy();o.data=src.data;review.collection.objects.link(o)
            o.location=xyz(offset);o.scale=(scale[0],scale[2],scale[1])
    w=15;fuel=set();drives=[]
    inside=lambda x,z,limit:225*(x*x*w*w+z*z*w*w)<=limit*w**4
    for x in range(w):
        for z in range(w):
            if inside(2*x+1-w,2*z+1-w,236):
                drives.append((x,z))
                for dx in range(2):
                    for dz in range(2):fuel.add((2*x+dx,2*z+dz))
    for x in range(2*w):
        for z in range(2*w):
            if inside(2*x+1-2*w,2*z+1-2*w,970):fuel.add((x,z))
    radial=max(math.hypot(abs(x+.5-w)+.5,abs(z+.5-w)+.5) for x,z in fuel)
    pitch=min(.35*17/w,.362*17/radial);bottom=2;top=15.85
    for x,z in sorted(fuel):
        at=((x+.5-w)*pitch,bottom,(z+.5-w)*pitch)
        inst('support_cell',(at[0],bottom-.18*pitch,at[2]),(pitch,pitch,pitch))
        inst('channel',at,(pitch,top-bottom,pitch))
        inst('bundle_top',(at[0],top,at[2]),(pitch,pitch,pitch))
        inst('guide_cell',(at[0],top-.05,at[2]),(pitch,pitch,pitch))
    for x,z in drives:
        at=((2*x+1-w)*pitch,1.3,(2*z+1-w)*pitch)
        inst('guide_tube',at,(pitch,.6,pitch))
        inst('blade',(at[0],bottom,at[2]),(pitch,top-bottom,pitch))
    inst('shroud',(0,1.7,0),(17,top-1.55,17))
    for y in (1.7,top+.15):inst('shroud_rim',(0,y,0),(17,1,17))
    review['assembly_count']=len(fuel);review['drive_count']=len(drives)
    review['source']='Columbia FSAR Amendment 67, Figures 4.3-1, 5.3-5; game mask retained.'
    studio(review,(0,11,0),(16,33,-22),25,(1400,1200))
    viewport(review,(0,11,0),(16,33,-22),27)
    bpy.data.libraries.write(str(OUT/'reactor_core.blend'),set(pieces.values())|{review},fake_user=True)
    print('Core saved:',len(fuel),'assemblies;',len(drives),'drives')
    return review
