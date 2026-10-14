package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.grid.*;
import dev.bwr.mod.signal.DeviceCommands;
import net.minecraft.core.BlockPos;
import java.util.*;

public final class GridTiePeripheral implements IPeripheral {
    private final GridTieBlockEntity tie;
    public GridTiePeripheral(GridTieBlockEntity tie){this.tie=tie;}
    public String getType(){return "bwr_grid_tie";}
    public boolean equals(IPeripheral other){return other instanceof GridTiePeripheral p&&p.tie==tie;}
    @LuaFunction(mainThread=true) public Map<String,Object> getStatus(){return GridTelemetry.tie(tie);}
    @LuaFunction(mainThread=true) public boolean setDemandMode(double n){return DeviceCommands.grid(tie,"demand_mode",n);}
    @LuaFunction(mainThread=true) public boolean setFixedDemandMW(double n){return DeviceCommands.grid(tie,"fixed_mw",n);}
    @LuaFunction(mainThread=true) public boolean setRemoteDemandMW(double n){return DeviceCommands.grid(tie,"remote_mw",n);}
    @LuaFunction(mainThread=true) public boolean setDailyCurve(double min,double max,double period){return Double.isFinite(period)&&period==Math.rint(period)&&period>=20&&period<=1728000&&DeviceCommands.gridCurve(tie,min,max,(long)period);}
    @LuaFunction(mainThread=true) public boolean resetCurve(){return DeviceCommands.grid(tie,"reset_curve",1);}
    @LuaFunction(mainThread=true) public boolean closeBreaker(){return DeviceCommands.grid(tie,"close_breaker",1);}
    @LuaFunction(mainThread=true) public boolean tripBreaker(){return DeviceCommands.grid(tie,"trip_breaker",1);}
    @LuaFunction(mainThread=true) public boolean bindGenerator(double x,double y,double z){return coordinate(x)&&coordinate(y)&&coordinate(z)&&tie.bindGenerator(new BlockPos((int)x,(int)y,(int)z));}
    private static boolean coordinate(double v){return Double.isFinite(v)&&v==Math.rint(v)&&v>=Integer.MIN_VALUE&&v<=Integer.MAX_VALUE;}
    @LuaFunction(mainThread=true) public boolean clearGenerator(){return tie.clearGenerator();}
    @LuaFunction(mainThread=true) public List<Map<String,Object>> getMeters(){return GridTelemetry.meters(tie);}
    @LuaFunction(mainThread=true) public boolean setMeters(Map<?,?> table){
        if(table.size()>32)return false;var positions=new ArrayList<BlockPos>();
        for(int i=1;i<=table.size();i++){
            Object row=table.get((double)i);if(row==null)row=table.get(i);
            if(!(row instanceof Map<?,?> v))return false;
            var xyz=new int[3];String[] keys={"x","y","z"};
            for(int j=0;j<3;j++){
                Object n=v.get(keys[j]);if(n==null)n=v.get((double)(j+1));if(n==null)n=v.get(j+1);
                if(!(n instanceof Number number))return false;double d=number.doubleValue();
                if(!Double.isFinite(d)||d!=Math.rint(d)||d<Integer.MIN_VALUE||d>Integer.MAX_VALUE)return false;xyz[j]=(int)d;
            }
            positions.add(new BlockPos(xyz[0],xyz[1],xyz[2]));
        }return tie.setMeters(positions);
    }
}
