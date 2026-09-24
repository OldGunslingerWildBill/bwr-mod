package dev.bwr.mod.water;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Counts discharged mass/heat, never creates an infinite source-water block. */
public final class WaterDischargeBlockEntity extends BlockEntity {
    public static final int KG_PER_TICK=3000;
    private boolean enabled=true;
    private long lastTick=Long.MIN_VALUE;
    private int used;
    private double totalKg,totalHeatKJ,lastTemperature=13;
    public WaterDischargeBlockEntity(BlockPos p,BlockState s){super(BwrBlockEntities.WATER_DISCHARGE.get(),p,s);}
    public boolean enabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;setChanged();}
    public boolean clearMouth(){
        if(level==null||isRemoved())return false;
        BlockPos front=worldPosition.relative(getBlockState().getValue(WaterDischargeBlock.FACING));
        if(!level.isLoaded(front))return false;
        var s=level.getBlockState(front);
        return s.isAir()||s.getFluidState().is(FluidTags.WATER)&&s.getCollisionShape(level,front).isEmpty();
    }
    public double totalKg(){return totalKg;}
    public double heatKJ(){return totalHeatKJ;}
    public double temperatureC(){return lastTemperature;}
    public double flowKgPerS(){return level!=null&&level.getGameTime()-lastTick<=1?used*20.0:0;}
    private final IFluidHandler sink=new IFluidHandler(){
        public int getTanks(){return 1;}
        public FluidStack getFluidInTank(int i){return FluidStack.EMPTY;}
        public int getTankCapacity(int i){return KG_PER_TICK;}
        public boolean isFluidValid(int i,FluidStack f){return i==0&&f.getFluid()==Fluids.WATER;}
        public FluidStack drain(int n,FluidAction a){return FluidStack.EMPTY;}
        public FluidStack drain(FluidStack f,FluidAction a){return FluidStack.EMPTY;}
        public int fill(FluidStack f,FluidAction action){
            if(level==null||level.isClientSide()||!enabled||!clearMouth()||!isFluidValid(0,f)
                    ||!Double.isFinite(ThermalWater.enthalpy(f))||ThermalWater.enthalpy(f)<0)return 0;
            long now=level.getGameTime();int remaining=KG_PER_TICK-(lastTick==now?used:0);
            int amount=Math.min(f.getAmount(),remaining);
            if(action.execute()&&amount>0){
                if(lastTick!=now){lastTick=now;used=0;}
                used+=amount;totalKg+=amount;totalHeatKJ+=amount*ThermalWater.enthalpy(f);lastTemperature=ThermalWater.temperature(f,13);setChanged();
                if(level instanceof net.minecraft.server.level.ServerLevel server && now%5==0){
                    var d=getBlockState().getValue(WaterDischargeBlock.FACING);
                    server.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,worldPosition.getX()+.5+d.getStepX()*.7,worldPosition.getY()+.45,worldPosition.getZ()+.5+d.getStepZ()*.7,4,.08,.03,.08,.02);
                }
            }
            return amount;
        }
    };
    public IFluidHandler inlet(){return sink;}
    @Override protected void saveAdditional(CompoundTag n,HolderLookup.Provider p){super.saveAdditional(n,p);n.putBoolean("Enabled",enabled);n.putDouble("DischargedKg",totalKg);n.putDouble("DischargedHeatKJ",totalHeatKJ);n.putDouble("TemperatureC",lastTemperature);}
    @Override protected void loadAdditional(CompoundTag n,HolderLookup.Provider p){super.loadAdditional(n,p);enabled=!n.contains("Enabled")||n.getBoolean("Enabled");totalKg=finite(n.getDouble("DischargedKg"));totalHeatKJ=finite(n.getDouble("DischargedHeatKJ"));lastTemperature=finite(n.getDouble("TemperatureC"));lastTick=Long.MIN_VALUE;used=0;}
    private static double finite(double v){return Double.isFinite(v)?Math.max(0,v):0;}
}
