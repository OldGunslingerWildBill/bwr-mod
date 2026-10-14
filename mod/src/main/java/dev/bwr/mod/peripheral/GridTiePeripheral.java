package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.grid.*;
import dev.bwr.mod.grid.GridTieBlockEntity;
import net.minecraft.core.BlockPos;
import java.util.*;

public final class GridTiePeripheral implements IPeripheral {
    private final GridTieBlockEntity tie;
    public GridTiePeripheral(GridTieBlockEntity tie){this.tie=tie;}
    @Override public String getType(){return "bwr_grid_tie";}
    @Override public boolean equals(IPeripheral other){return other instanceof GridTiePeripheral p&&p.tie==tie;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){
        var m=new LinkedHashMap<String,Object>();var d=tie.settings();
        m.put("breakerClosed",tie.closed());m.put("bindingValid",tie.generator()!=null);m.put("exportEnabled",tie.exportEnabled());
        m.put("syncValid",tie.syncValid());m.put("withinSyncWindow",tie.withinWindow());
        if(tie.generator()!=null){m.put("rpm",tie.generator().rpm);m.put("shaftFrequencyHz",tie.generator().rpm/30);}
        if(tie.syncValid()){m.put("phaseDegrees",tie.phase());m.put("slipHz",tie.slip());}
        m.put("demandMode",d.mode());m.put("fixedMW",d.fixedMW());m.put("remoteMW",d.remoteMW());m.put("demandMW",tie.demandMW());m.put("demandMW1s",tie.demandMW1s());
        m.put("deliveredMWNow",EnergyUnits.mw(tie.ledger().lastTickFE()));m.put("deliveredMW1s",EnergyUnits.mw(tie.ledger().averageFE(20)));
        m.put("deliveredMW1min",EnergyUnits.mw(tie.ledger().averageFE(1200)));m.put("deliveredMWh",EnergyUnits.mwh(tie.ledger().totalFE()));
        m.put("sampleTicks",tie.ledger().sampleTicks());m.put("windowReady1s",tie.ledger().sampleTicks()>=20);m.put("windowReady1min",tie.ledger().sampleTicks()>=1200);m.put("counterSaturated",tie.ledger().saturated());
        if(tie.hasClose()){m.put("lastCloseAngleDegrees",tie.lastAngle());m.put("lastCloseSlipHz",tie.lastSlip());m.put("lastCloseFault",tie.lastCloseFault());}
        return m;
    }
    private boolean configure(int mode,double fixed,double remote,double min,double max,long period)throws LuaException{
        try{return tie.configure(new GridDemand(mode,fixed,remote,min,max,period));}catch(IllegalArgumentException e){throw new LuaException(e.getMessage());}
    }
    @LuaFunction(mainThread=true) public boolean setDemandMode(int mode)throws LuaException{var d=tie.settings();return configure(mode,d.fixedMW(),d.remoteMW(),d.minimumMW(),d.maximumMW(),d.periodTicks());}
    @LuaFunction(mainThread=true) public boolean setFixedDemandMW(double value)throws LuaException{var d=tie.settings();return configure(d.mode(),value,d.remoteMW(),d.minimumMW(),d.maximumMW(),d.periodTicks());}
    @LuaFunction(mainThread=true) public boolean setRemoteDemandMW(double value)throws LuaException{var d=tie.settings();return configure(d.mode(),d.fixedMW(),value,d.minimumMW(),d.maximumMW(),d.periodTicks());}
    @LuaFunction(mainThread=true) public boolean setDailyCurve(double min,double max,int period)throws LuaException{var d=tie.settings();return configure(d.mode(),d.fixedMW(),d.remoteMW(),min,max,period);}
    @LuaFunction(mainThread=true) public void resetCurve(){tie.resetCurve();}
    @LuaFunction(mainThread=true) public boolean closeBreaker(){return tie.closeBreaker();}
    @LuaFunction(mainThread=true) public void tripBreaker(){tie.tripBreaker();}
    @LuaFunction(mainThread=true) public boolean bindGenerator(int x,int y,int z){return tie.bindGenerator(new BlockPos(x,y,z));}
    @LuaFunction(mainThread=true) public boolean clearGenerator(){return tie.clearGenerator();}
}
