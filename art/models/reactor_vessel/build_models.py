"""Blender-authored scalable BWR vessel exterior. Run build() through Blender MCP.

The barrel and heads use unit-diameter X/Z coordinates; heights are in blocks.
Bolts and port spools remain independent so their dimensions do not stretch.
Original artwork using a conventional BWR vessel silhouette, sized for the game.
No existing Blender scenes are cleared or overwritten.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
helper = ROOT / 'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'), str(helper), 'exec'))
OUT = ROOT / 'art/models/reactor_vessel'
OUT.mkdir(parents=True, exist_ok=True)
SHELL = material('RPV satin steel', (.55, .60, .63), .70)
SEAM = material('RPV weld metal', (.23, .26, .28), .72)
SKIRT = material('RPV support skirt', (.09, .12, .14), .55)


def revolved(s, name, rings, mat, segments=96):
    vs = [(r*math.cos(i*math.tau/segments), y, r*math.sin(i*math.tau/segments))
          for r,y in rings for i in range(segments)]
    fs = [(j*segments+i, j*segments+(i+1)%segments,
           (j+1)*segments+(i+1)%segments, (j+1)*segments+i)
          for j in range(len(rings)-1) for i in range(segments)]
    return mesh_object(s, name, vs, fs, mat)


def build():
    pieces = {}
    def part(name):
        s = new_scene('BWR | pressure vessel ' + name)
        pieces[name] = s
        return s
    s = part('barrel')
    sleeve(s, 'continuous forged steel barrel', (0,0,0), (0,1,0), .46, .448, SHELL, 96)
    for i in range(12):
        a=i*math.tau/12
        cylinder(s, 'subtle longitudinal weld', (.4603*math.cos(a),0,.4603*math.sin(a)),
                 (.4603*math.cos(a),1,.4603*math.sin(a)), .0006, SEAM, 6)
    s = part('bottom')
    revolved(s, 'dished lower head', [(0,.18)]+[(.46*math.sin(i*math.pi/24),1.2-1.02*math.cos(i*math.pi/24)) for i in range(1,13)], SHELL)
    sleeve(s, 'load bearing skirt', (0,.08,0), (0,.98,0), .36,.347,SKIRT,64)
    sleeve(s, 'skirt base ring', (0,.04,0), (0,.18,0), .40,.32,STEEL,64)
    s = part('flange')
    sleeve(s, 'lower head flange', (0,0,0),(0,.20,0),.485,.435,STEEL,96)
    sleeve(s, 'head joint', (0,.20,0),(0,.225,0),.482,.435,DARK,96)
    sleeve(s, 'upper head flange', (0,.225,0),(0,.42,0),.485,.435,SHELL,96)
    s = part('head')
    revolved(s, 'closed elliptical removable head',
             [(.46*math.cos(i*math.pi/32),1.65*math.sin(i*math.pi/32)) for i in range(17)], SHELL)
    # Small lifting ears only. BWR control drives enter through the lower head.
    for x in (-.21,.21):
        sleeve(s,'head lifting ear',(x,1.48,-.012),(x,1.48,.012),.055,.027,STEEL,24)
    s = part('weld')
    sleeve(s, 'circumferential weld', (0,-.018,0),(0,.018,0),.461,.458,SEAM,96)
    s = part('stud')
    cylinder(s,'head tension stud',(0,0,0),(0,.57,0),.065,SKIRT,12)
    cylinder(s,'head washer',(0,.40,0),(0,.45,0),.115,STEEL,16)
    bolt(s,'hex tension nut',(0,.45,0),(0,.55,0),.10)
    s = part('spool')
    sleeve(s,'port welded spool',(0,0,0),(0,0,1),.30,.22,SHELL,24)
    s = part('collar')
    sleeve(s,'port reinforcement pad',(0,0,-.06),(0,0,.06),.46,.22,STEEL,32)
    for name, scene in pieces.items():
        export_scene(scene, name+'.json', {'id':name})
    review=new_scene('BWR | pressure vessel preview')
    def inst(name,scale,offset):
        for source in pieces[name].objects:
            o=source.copy();o.data=source.data.copy();review.collection.objects.link(o)
            for v in o.data.vertices:
                p=game(v.co);v.co=xyz(tuple(p[a]*scale[a]+offset[a] for a in range(3)))
    d,h=17,22
    inst('bottom',(d,1,d),(0,0,0))
    head_height=min(h*.22,d*.18)
    flange_y=h-head_height-.40
    inst('barrel',(d,flange_y-1.15,d),(0,1.2,0))
    inst('flange',(d,1,d),(0,flange_y,0))
    inst('head',(d,head_height/1.65,d),(0,h-head_height,0))
    for y in range(4,h-3,4):inst('weld',(d,1,d),(0,y,0))
    for i in range(64):
        a=i*math.tau/64;inst('stud',(1,1,1),(.472*d*math.cos(a),flange_y,.472*d*math.sin(a)))
    for y in (3,17):
        inst('spool',(1,1,1.4),(0,y,-d/2))
        inst('collar',(1,1,1),(0,y,-.46*d))
    review['description']='Scalable closed BWR vessel, reference preview 17 x 22 x 17; actual game dimensions follow the build.'
    studio(review,(0,h*.5,0),(d*1.7,h*1.3,-d*2),h*1.40,(1200,1400))
    review.render.filepath=str(OUT/'reactor_vessel.png')
    bpy.data.libraries.write(str(OUT/'reactor_vessel.blend'),set(pieces.values())|{review},fake_user=True)
    viewport(review,(0,h*.5,0),(d*1.7,h*1.3,-d*2),h*1.40)
    return review
