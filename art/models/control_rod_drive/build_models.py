"""Original Blender CRD exterior from Columbia FSAR Figures 4.6-2 / 4.6-3.
The hydraulic housing is compressed to the existing one-block gameplay envelope.
No exposed cutaway, no electric motor, no change to shared supply capabilities.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8'),str(helper),'exec'))
OUT=ROOT/'art/models/control_rod_drive';OUT.mkdir(parents=True,exist_ok=True)

def build():
    s=new_scene('Realistic BWR | Columbia hydraulic control rod drive')
    s['reference']='Columbia FSAR Amendment 67, Figures 4.6-2 and 4.6-3, PDF pages 2032-2033'
    # Circumferential housing and bolted lower main flange; upper housing enters the RPV.
    cylinder(s,'hydraulic drive housing',(.5,.34,.5),(.5,1,.5),.235,STEEL,16)
    cylinder(s,'upper housing weld',(.5,.925,.5),(.5,.965,.5),.25,BODY,16)
    cylinder(s,'housing flange',(.5,.30,.5),(.5,.37,.5),.365,STEEL,16)
    cylinder(s,'main flange gasket',(.5,.282,.5),(.5,.301,.5),.348,DARK,16)
    cylinder(s,'drive main flange',(.5,.23,.5),(.5,.282,.5),.365,BODY,16)
    for i in range(8):
        a=(i+.5)*math.tau/8;x=.5+.313*math.cos(a);z=.5+.313*math.sin(a)
        cylinder(s,'main flange hex stud', (x,.19,z),(x,.24,z),.027,STEEL,6)
    cylinder(s,'position indicator probe housing',(.5,.045,.5),(.5,.24,.5),.115,BODY,12)
    cylinder(s,'probe end gland',(.5,.02,.5),(.5,.065,.5),.145,STEEL,12)
    # The pressure-over and pressure-under connectors shown on the source drawing.
    for x,d in ((0,1),(1,-1)):
        cylinder(s,'hydraulic pressure connection',(x+d*.045,.5,.5),(x+d*.285,.5,.5),.078,STEEL,12)
        sleeve(s,'water connection coupling',(x,.5,.5),(x+d*.045,.5,.5),.12,.065,BODY,12)
        cylinder(s,'water face',(x+d*.001,.5,.5),(x+d*.008,.5,.5),.065,BLUE,12)
        cylinder(s,'hydraulic riser',(x+d*.28,.32,.5),(x+d*.28,.5,.5),.075,STEEL,12)
    box(s,'position indicator connector',(.39,.02,.11),(.61,.18,.29),BODY)
    box(s,'instrument face',(.42,.045,.105),(.58,.155,.115),DARK)
    box(s,'computer connector mark',(.465,.075,.100),(.535,.13,.107),TEAL)
    box(s,'identification plate',(.44,.59,.263),(.56,.73,.27),DARK)
    export_scene(s,'control_rod_drive.json',{'id':'control_rod_drive','size':[1,1,1]})
    viewport(s,(.5,.50,.5),(2,1.2,-2),1.8)
    bpy.data.libraries.write(str(OUT/'control_rod_drive.blend'),{s},fake_user=True)
    print('Saved Columbia-inspired CRD exterior')
    return s
