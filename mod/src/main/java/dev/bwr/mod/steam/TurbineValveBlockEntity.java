package dev.bwr.mod.steam;

import dev.bwr.mod.registry.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Manual actuator and shared flow allowance. Never decides when the plant should stop. */
public class TurbineValveBlockEntity extends BlockEntity {
    private double target,position;
    private long ledgerTick=Long.MIN_VALUE;
    private double deliveredKg;
    private final Map<BlockPos,Double> claims=new HashMap<>();
    public TurbineValveBlockEntity(BlockPos p,BlockState s){super(BwrBlockEntities.TURBINE_VALVE.get(),p,s);}
    public boolean stopValve(){return getBlockState().is(BwrBlocks.STEAM_STOP_VALVE.get());}
    public boolean bypassValve(){return getBlockState().is(BwrBlocks.BYPASS_STEAM_VALVE.get());}
    public double target(){return target;}
    public double position(){return position;}
    public void setTarget(double fraction){
        if(!Double.isFinite(fraction))return;
        target=stopValve()?(fraction>0?1:0):Math.round(Math.max(0,Math.min(1,fraction))*1000)/1000.0;
        setChanged();
    }
    public static void serverTick(Level l,BlockPos p,BlockState s,TurbineValveBlockEntity v){v.stroke(.05);}
    public void stroke(double seconds){
        if(!Double.isFinite(seconds)||seconds<=0||position==target)return;
        double step=seconds/(stopValve()?.5:2);
        position=position<target?Math.min(target,position+step):Math.max(target,position-step);setChanged();
    }
    private void ledger(){
        long now=level.getGameTime();
        if(now!=ledgerTick){ledgerTick=now;deliveredKg=0;claims.clear();}
    }
    /** Source allowance is shared by every consumer, including consumers on different shafts. */
    public double allowance(BlockPos source,double unthrottledKg){
        ledger();return Math.max(0,unthrottledKg*position-claims.getOrDefault(source,0.0));
    }
    public void record(BlockPos source,double kg){
        if(!(kg>0)||!Double.isFinite(kg))return;
        ledger();claims.merge(source.immutable(),kg,Double::sum);deliveredKg+=kg;
    }
    /** Latest metered tick, retained for one tick so menu/tile ordering cannot flicker the meter. */
    public double flowKgPerS(){
        if(level==null||ledgerTick==Long.MIN_VALUE)return 0;
        long age=level.getGameTime()-ledgerTick;return age>=0&&age<=1?deliveredKg*20:0;
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);t.putDouble("Target",target);t.putDouble("Position",position);}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);target=finite(t.getDouble("Target"));position=finite(t.getDouble("Position"));
        if(stopValve())target=target>0?1:0;
        ledgerTick=Long.MIN_VALUE;claims.clear();deliveredKg=0;
    }
    private static double finite(double x){return Double.isFinite(x)?Math.max(0,Math.min(1,x)):0;}
}
