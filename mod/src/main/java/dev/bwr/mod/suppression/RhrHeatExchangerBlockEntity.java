package dev.bwr.mod.suppression;

import dev.bwr.core.pool.RhrHeatExchanger;
import dev.bwr.mod.eccs.AssemblyPlumbing;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Passive exchanger. A live RHR pump drives the primary loop; a separate pump supplies cold water. */
public class RhrHeatExchangerBlockEntity extends BlockEntity {
    private final RhrHeatExchanger exchanger=new RhrHeatExchanger();
    private final AssemblyPlumbing.Cache returnRoute=new AssemblyPlumbing.Cache();
    private long readoutTick=Long.MIN_VALUE,processedTick=Long.MIN_VALUE;
    private final IFluidHandler inlet=handler(true),outlet=handler(false);
    public RhrHeatExchangerBlockEntity(BlockPos p,BlockState s){super(BwrBlockEntities.RHR_HEAT_EXCHANGER.get(),p,s);}
    public RhrHeatExchanger plant(){return exchanger;}
    public IFluidHandler secondary(boolean input){return input?inlet:outlet;}
    private boolean live(){return level!=null&&!level.isClientSide()&&!isRemoved()&&level.isLoaded(worldPosition)&&level.getBlockEntity(worldPosition)==this;}
    private void resetReadouts(){if(level!=null&&readoutTick!=level.getGameTime()){readoutTick=level.getGameTime();exchanger.clearReadouts();}}
    public SuppressionPoolBlockEntity returnPool(){
        if(!live())return null;
        return AssemblyPlumbing.endpoint(level,returnRoute.trace(level,worldPosition,getBlockState(),AssemblyPort.WATER_DISCHARGE),SuppressionPoolBlockEntity.class);
    }
    /** Heat is exchanged only on a running pump's actual tick, never from a stale command. */
    public void circulate(SuppressionPoolBlockEntity pool,double flow){
        resetReadouts();
        if(!live()||pool==null||!pool.isFormed()||returnPool()!=pool||!Double.isFinite(flow)||flow<=0||processedTick==level.getGameTime())return;
        processedTick=level.getGameTime();
        exchanger.step(pool.pool(),flow,.05);
        pool.reportPhysicalCooling(worldPosition,level.getGameTime(),exchanger.heatMW());
        pool.setChanged();setChanged();
    }
    public static void serverTick(Level l,BlockPos p,BlockState s,RhrHeatExchangerBlockEntity be){
        be.resetReadouts();
        Direction face=RhrHeatExchangerBlock.hotOut(s);var next=p.relative(face);
        if(!l.isLoaded(next))return;
        var available=be.outlet.drain((int)(RhrHeatExchanger.SECONDARY_FLOW/20),IFluidHandler.FluidAction.SIMULATE);
        if(available.isEmpty())return;
        var sink=l.getCapability(Capabilities.FluidHandler.BLOCK,next,face.getOpposite());
        if(sink!=null){int filled=sink.fill(available,IFluidHandler.FluidAction.EXECUTE);be.outlet.drain(filled,IFluidHandler.FluidAction.EXECUTE);}
    }
    private IFluidHandler handler(boolean input){return new IFluidHandler(){
        public int getTanks(){return 1;}
        public FluidStack getFluidInTank(int tank){int n=tank==0&&live()?(int)Math.floor(input?exchanger.cold():exchanger.hot()):0;return n>0?dev.bwr.mod.water.ThermalWater.stack(n,input?exchanger.coldH():exchanger.hotH()):FluidStack.EMPTY;}
        public int getTankCapacity(int tank){return tank==0?RhrHeatExchanger.CAPACITY_KG:0;}
        public boolean isFluidValid(int tank,FluidStack s){return tank==0&&input&&s.is(Fluids.WATER);}
        public int fill(FluidStack s,FluidAction action){
            if(!live()||!isFluidValid(0,s))return 0;
            // NeoForge accounts whole mB. Never store a fractional amount then report a rounded value.
            int n=(int)Math.floor(exchanger.fillCold(s.getAmount(),dev.bwr.mod.water.ThermalWater.enthalpy(s),true));
            if(n>0&&action.execute()){exchanger.fillCold(n,dev.bwr.mod.water.ThermalWater.enthalpy(s),false);setChanged();}
            return n;
        }
        public FluidStack drain(FluidStack s,FluidAction action){return s.is(Fluids.WATER)?drain(s.getAmount(),action):FluidStack.EMPTY;}
        public FluidStack drain(int amount,FluidAction action){if(input||!live())return FluidStack.EMPTY;int n=(int)Math.floor(exchanger.drainHot(amount,true));if(n<=0)return FluidStack.EMPTY;double h=exchanger.hotH();if(action.execute()){exchanger.drainHot(n,false);setChanged();}return dev.bwr.mod.water.ThermalWater.stack(n,h);}
    };}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);t.putDouble("ColdH",exchanger.coldH());t.putDouble("HotH",exchanger.hotH());t.putDouble("Cold",exchanger.cold());t.putDouble("Hot",exchanger.hot());t.putDouble("HotEnergyKJ",exchanger.hotEnergyKJ());}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);if(t.contains("ColdH"))exchanger.restore(t.getDouble("Cold"),t.getDouble("Hot"),t.getDouble("ColdH"),t.getDouble("HotH"));else exchanger.restore(t.getDouble("Cold"),t.getDouble("Hot"),t.getDouble("HotEnergyKJ"));readoutTick=processedTick=Long.MIN_VALUE;}
}
