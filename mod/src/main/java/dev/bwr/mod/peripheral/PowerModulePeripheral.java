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
        m.put("flowKgPerS",module.flowKgPerS);m.put("shaftMW",module.shaftMW);m.put("electricMW",module.electricMW);m.put("trainMW",module.trainMW);
        m.put("inletPsia",module.inletPsia);m.put("outletPsia",module.outletPsia);m.put("inletC",module.inletC);m.put("outletC",module.outletC);
        m.put("waterKg",module.waterStored());m.put("storedFE",module.energyStored());m.put("hpSections",module.hpCount);m.put("lpSections",module.lpCount);m.put("generators",module.generatorCount);m.put("status",module.status);return m;
    }
}
