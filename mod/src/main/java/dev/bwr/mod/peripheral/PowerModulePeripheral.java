package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import java.util.*;

/** Optional CC adapter. Commands and samples run on the server thread. */
public final class PowerModulePeripheral implements IPeripheral {
    private final PowerModuleBlockEntity module;
    public PowerModulePeripheral(PowerModuleBlockEntity module){this.module=module;}
    @Override public String getType(){return module.block().generator()?"bwr_generator":module.block().highPressure()?"bwr_hp_turbine":"bwr_lp_turbine";}
    @Override public boolean equals(IPeripheral other){return other instanceof PowerModulePeripheral p&&p.module==module;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("running",module.running);m.put("control", "upstream_steam_valve");m.put("rpm",module.rpm);
        m.put("speedPercent",module.speedPercent());m.put("overspeed",module.overspeed());
        m.put("kineticEnergyMJ",module.kineticEnergyKJ()/1000);m.put("shaftLossMW",module.trainLossKJ*20/1000);
        m.put("damage",module.damage());m.put("failed",module.failed());
        m.put("deliveredMWNow",dev.bwr.core.grid.EnergyUnits.mw(module.delivered().lastTickFE()));
        m.put("deliveredMW1s",dev.bwr.core.grid.EnergyUnits.mw(module.delivered().averageFE(20)));
        m.put("deliveredMW1min",dev.bwr.core.grid.EnergyUnits.mw(module.delivered().averageFE(1200)));
        m.put("deliveredMWh",dev.bwr.core.grid.EnergyUnits.mwh(module.delivered().totalFE()));
        m.put("generatedMW1s",module.generatedMW1s());m.put("generatedFETick",module.generatedFELastTick());
        m.put("deliveredFETick",module.delivered().lastTickFE());m.put("bufferDeltaFETick",module.bufferDeltaFELastTick());
        m.put("sampleTicks",module.delivered().sampleTicks());m.put("windowReady1s",module.delivered().sampleTicks()>=20);
        m.put("windowReady1min",module.delivered().sampleTicks()>=1200);m.put("counterSaturated",module.delivered().saturated());
        m.put("flowKgPerS",module.flowKgPerS);m.put("shaftMW",module.shaftMW);m.put("electricMW",module.electricMW);m.put("trainMW",module.trainMW);
        m.put("inletPsia",module.inletPsia);m.put("inletMoisturePercent",module.inletMoisturePercent);m.put("outletPsia",module.outletPsia);m.put("inletC",module.inletC);m.put("outletC",module.outletC);
        m.put("waterKg",module.waterStored());m.put("storedFE",module.energyStored());m.put("hpSections",module.hpCount);m.put("lpSections",module.lpCount);m.put("generators",module.generatorCount);m.put("status",module.status);
        auxiliaries(m);blades(m);return m;
    }
    /** The blade sections, the casing, reverse power and the last breaker close (TURBINE-MAINTENANCE.md). Readings only; nothing trips. */
    private void blades(Map<String,Object> m){
        m.put("shaftDamage",module.shaftDamage());
        m.put("reversePowerMW",module.reverseMW);m.put("motoring",module.motoring);
        if(module.hasCloseShock()){m.put("lastCloseAngleDegrees",module.closeAngle());m.put("lastCloseTorquePu",module.closeTorquePu());}
        if(!module.lp())return;
        m.put("coverOff",module.coverOff());m.put("hoodTempC",module.hoodC);
        var list=new ArrayList<Map<String,Object>>();
        for(int f=0;f<dev.bwr.core.turbine.TurbineBlades.FLOWS;f++){
            var s=new LinkedHashMap<String,Object>();s.put("flow",f==0?"A":"B");s.put("installed",module.bladeIn(f));
            s.put("damage",module.blade(f));s.put("bladeThrown",module.bladeThrown(f));list.add(s);
        }
        m.put("bladeSections",list);
        m.put("bladeDamageA",module.blade(0));m.put("bladeDamageB",module.blade(1));
    }
    /** The turbine auxiliaries (TURBINE-AUX.md): fields of a system the train does not have are absent. Alarms are readings; nothing trips. */
    private void auxiliaries(Map<String,Object> m){
        var a=module.aux();m.put("auxGoverned",a.lubeGoverned||a.bowGoverned||a.genGoverned);
        if(a.lubeGoverned){
            m.put("oilPressurePsig",a.oilPsig);m.put("oilTempC",a.oilC);m.put("oilLevel",a.oilLevel);m.put("oilFilm",a.oilFilm);
            m.put("bearingTempC",module.bearingC);m.put("oilPressureAlarm",a.oilPressureAlarm);m.put("oilLevelAlarm",a.oilLevelAlarm);
            m.put("oilTempAlarm",a.oilTempAlarm);m.put("bearingAlarm",a.bearingAlarm);m.put("oilTank",a.oilTank);m.put("shaftOilPump",a.shaftPump);
        }
        if(a.bowGoverned){
            m.put("vibrationMmPerS",module.vibration);m.put("vibrationAlarm",a.vibrationAlarm);
            if(!module.block().generator()){m.put("rotorTempC",module.rotorC);m.put("rotorBow",module.bow());}
        }
        if(module.block().generator()){
            m.put("turningGear",module.turningGear());m.put("turningGearRolling",a.gearRolling);m.put("turningGearPowered",a.gearFed);
            if(a.genGoverned){
                m.put("windingTempC",module.windingC);m.put("statorFlow",a.statorFlow);m.put("h2PressurePsig",a.h2Psig);m.put("coolantTempC",a.coolantC);
                m.put("windingAlarm",a.windingAlarm);m.put("h2Alarm",a.h2Alarm);
            }
        }
    }
    /** Engages or releases the generator's turning gear. Engaging above the gear's overrun speed raises an error. */
    @LuaFunction(mainThread=true) public void setTurningGear(boolean on) throws dan200.computercraft.api.lua.LuaException {
        if(!module.setTurningGear(on))throw new dan200.computercraft.api.lua.LuaException(module.block().generator()
                ?"The turning gear cannot be engaged above "+(int)dev.bwr.core.turbine.TurbineAux.GEAR_MAX_RPM+" rpm":"Only the generator has a turning gear");
    }
    @LuaFunction(mainThread=true) public boolean getTurningGear(){return module.turningGear();}
}
