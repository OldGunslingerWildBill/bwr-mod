"""Original SLC and ADS hardware, authored in Blender. One unit = one block.
Reference: NRC Reactor Concepts Manual BWR SLC schematic (tank + displacement pumps).
Dimensions and details are game representations, not vendor manufacturing drawings.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
helper=ROOT/'art/models/condenser_msiv/build_models.py'
exec(compile(helper.read_text(encoding='utf-8').split('def build_condenser')[0],str(helper),'exec'))
OUT=ROOT/'art/models/alpha';OUT.mkdir(parents=True,exist_ok=True)
GREEN=material('SLC enamel',(.075,.19,.12),.25)
IVORY=material('tank insulation',(.56,.57,.50),.12)
YELLOW=material('safety yellow',(.76,.43,.025),.15)

def port(s,name,p,d,r=.25):
    p=Vector(p);d=Vector(d);p-=d*.025
    sleeve(s,name+' spool',p-d*.42,p-d*.08,r*.72,r*.59,STEEL,24)
    scaled_flange(s,name,p,d,r,r*.59,STEEL,8)

def tank():
    s=new_scene('BWR | SLC boron tank')
    box(s,'foundation',(.28,.01,.28),(2.72,.22,2.72),CONCRETE)
    cylinder(s,'vertical solution tank',(1.5,.42,1.5),(1.5,3.48,1.5),1.03,IVORY,48)
    for y in (.42,.55,1.50,2.5,3.46): sleeve(s,'rolled seam',(1.5,y,1.5),(1.5,y+.035,1.5),1.044,1.015,STEEL,48)
    cylinder(s,'dished head',(1.5,3.46,1.5),(1.5,3.61,1.5),.97,STEEL,48)
    for x in (.85,2.15):
        for z in (.85,2.15):box(s,'support',(x-.1,.20,z-.1),(x+.1,.51,z+.1),BODY)
    port(s,'solution outlet',(1.5,.5,.015),(0,0,-1),.29)
    port(s,'water fill',(1.5,3.985,1.5),(0,1,0),.25)
    cylinder(s,'charge hatch',(1.5,3.57,2.12),(1.5,3.76,2.12),.25,STEEL,24)
    for x in (1.10,1.90):cylinder(s,'ladder stile',(x,.25,2.62),(x,3.72,2.62),.028,STEEL,10)
    for i in range(13):cylinder(s,'ladder rung',(1.10,.36+i*.255,2.62),(1.90,.36+i*.255,2.62),.025,STEEL,10)
    box(s,'borate placard',(1.04,2.05,.43),(1.96,2.52,.48),YELLOW)
    for x in (1.13,1.87):
        for y in (2.12,2.45):bolt(s,'placard bolt',(x,y,.412),(x,y,.45),.025)
    cylinder(s,'sight glass',(2.57,.74,1.42),(2.57,3.09,1.42),.05,DARK,12)
    for y in (.76,3.06):cylinder(s,'level tap',(2.48,y,1.42),(2.62,y,1.42),.09,STEEL,12)
    export_scene(s,'slc_boron_tank.json',{'size':[3,4,3],'controller':[1,0,1],'ports':[
      {'role':'WATER_SUCTION','cell':[1,3,1],'face':'up'}, {'role':'WATER_DISCHARGE','cell':[1,0,0],'face':'north'}]})
    return s

def pump():
    s=new_scene('BWR | SLC positive displacement pump')
    box(s,'skid',(.12,.06,.55),(2.88,.20,2.45),BODY)
    for z in (.66,2.34):box(s,'skid rail',(.06,.02,z-.07),(2.94,.30,z+.07),STEEL)
    cylinder(s,'motor',(1.75,.79,1.68),(2.65,.79,1.68),.43,GREEN,32)
    for i in range(15):
        a=i*math.tau/15
        cylinder(s,'motor cooling fin',(1.81,.79+math.cos(a)*.445,1.68+math.sin(a)*.445),(2.53,.79+math.cos(a)*.445,1.68+math.sin(a)*.445),.025,GREEN,8)
    cylinder(s,'fan shroud',(2.59,.79,1.68),(2.78,.79,1.68),.45,BODY,32)
    box(s,'motor terminal',(2.02,1.10,1.46),(2.45,1.45,1.94),GREEN)
    cylinder(s,'coupling',(1.29,.79,1.68),(1.82,.79,1.68),.21,YELLOW,20)
    box(s,'crankcase',(.48,.29,1.26),(1.38,1.15,2.13),GREEN)
    for x in (.66,1.08):
        cylinder(s,'plunger',(x,.67,.80),(x,.67,1.40),.12,STEEL,20)
        cylinder(s,'plunger gland',(x,.67,1.02),(x,.67,1.24),.19,BODY,20)
        box(s,'liquid end',(x-.17,.43,.60),(x+.17,.89,.90),STEEL)
        for y in (.47,.84):bolt(s,'head stud',(x,y,.54),(x,y,.66),.038)
    sweep(s,'suction manifold',[(.03,.5,1.5),(.32,.5,1.5),(.32,.5,.75),(1.08,.5,.75)],.11,STEEL,16)
    sweep(s,'discharge manifold',[(.66,.86,.75),(1.22,.86,.75),(1.45,.50,.75),(2.60,.50,.75),(2.60,.50,1.5),(2.985,.50,1.5)],.085,STEEL,16)
    port(s,'suction',(.015,.5,1.5),(-1,0,0),.26)
    port(s,'discharge',(2.985,.5,1.5),(1,0,0),.23)
    cylinder(s,'pulse dampener',(1.38,.91,.75),(1.38,1.64,.75),.15,STEEL,24)
    cylinder(s,'pressure gauge stem',(.70,1.05,1.73),(.70,1.53,1.73),.026,STEEL,10)
    cylinder(s,'pressure gauge',(.70,1.55,1.57),(.70,1.55,1.70),.15,IVORY,24)
    cylinder(s,'gauge needle',(.70,1.55,1.565),(.64,1.62,1.565),.008,DARK,6)
    export_scene(s,'slc_pump.json',{'size':[3,2,3],'controller':[1,0,1],'ports':[
      {'role':'WATER_SUCTION','cell':[0,0,1],'face':'west'},{'role':'WATER_DISCHARGE','cell':[2,0,1],'face':'east'}]})
    return s

def small(id):
    s=new_scene('BWR | '+id)
    if id=='ads_controller':
        box(s,'cabinet',(.06,.05,.30),(.57,.97,.90),IVORY)
        box(s,'cabinet door',(.08,.09,.265),(.54,.93,.30),STEEL)
        box(s,'display',(.14,.60,.24),(.47,.82,.266),DARK)
        for x in (.2,.4):cylinder(s,'pushbutton',(x,.46,.23),(x,.46,.28),.045,RED if x==.4 else GREEN,16)
        for x in (.69,.88):
            cylinder(s,'nitrogen receiver',(x,.10,.66),(x,.84,.66),.085,BODY,24)
            cylinder(s,'bottle regulator',(x,.83,.66),(x,.96,.66),.037,STEEL,12)
        sweep(s,'air manifold',[(.50,.88,.76),(.69,.88,.76),(.88,.88,.76)],.015,STEEL,10)
    elif id=='ads_relief_valve':
        sleeve(s,'steam inlet',(.04,.35,.50),(.50,.35,.50),.20,.15,STEEL,24)
        port(s,'steam flange',(.015,.35,.5),(-1,0,0),.28)
        cylinder(s,'valve casing',(.5,.32,.5),(.5,.61,.5),.25,RED,24)
        cylinder(s,'stem',(.5,.55,.5),(.5,.84,.5),.06,STEEL,16)
        cylinder(s,'pneumatic actuator',(.5,.70,.5),(.5,.97,.5),.18,BODY,24)
        for x in (.35,.65):cylinder(s,'actuator tie rod',(x,.65,.5),(x,.96,.5),.024,STEEL,8)
        sleeve(s,'pool discharge',(.5,.30,.5),(.5,.015,.5),.16,.12,STEEL,24)
        port(s,'downcomer',(.5,.015,.5),(0,-1,0),.23)
        box(s,'solenoid',(.72,.65,.42),(.95,.84,.61),DARK)
        sweep(s,'pilot tube',[(.84,.70,.42),(.84,.66,.29),(.56,.66,.29),(.56,.74,.34)],.014,STEEL,8)
    elif id=='borate_charge':
        cylinder(s,'charge canister',(.5,.08,.5),(.5,.83,.5),.28,IVORY,32)
        for y in (.08,.80):sleeve(s,'rim',(.5,y,.5),(.5,y+.045,.5),.29,.26,STEEL,32)
        cylinder(s,'lid',(.5,.83,.5),(.5,.87,.5),.275,STEEL,32)
        sweep(s,'handle',[(.34,.86,.5),(.34,.96,.5),(.66,.96,.5),(.66,.86,.5)],.016,BODY,8)
        box(s,'borate label',(.32,.28,.209),(.68,.64,.24),YELLOW)
    else:
        box(s,'outfall base',(.05,.01,.12),(.95,.16,.92),CONCRETE)
        sleeve(s,'outfall pipe',(.5,.5,.985),(.5,.5,.07),.29,.24,STEEL,32)
        port(s,'supply flange',(.5,.5,.985),(0,0,1),.38)
        sleeve(s,'mouth rim',(.5,.5,.04),(.5,.5,.10),.32,.24,STEEL,32)
        for x in (.38,.62):cylinder(s,'mouth guard',(x,.29,.05),(x,.71,.05),.012,BODY,8)
        for z in (.24,.72):box(s,'saddle',(.32,.14,z-.06),(.68,.26,z+.06),BODY)
    export_scene(s,id+'.json',{'size':[1,1,1],'controller':[0,0,0],'ports':[]})
    return s

def build():
    scenes=[tank(),pump()]+[small(id) for id in ('ads_controller','ads_relief_valve','water_discharge_port','borate_charge')]
    bpy.data.libraries.write(str(OUT/'alpha_hardware.blend'),set(scenes),fake_user=True)
    viewport(scenes[1],(1.5,.8,1.5),(5,4,-4),5)
    print('Authored',[(s.name,len(s.objects)) for s in scenes])

if __name__=='__main__':build()
