package dev.bwr.mod.eccs;

import dev.bwr.core.eccs.BoronSolution;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public final class SlcTankBlockEntity extends BlockEntity {
    private final BoronSolution solution=new BoronSolution();
    public SlcTankBlockEntity(BlockPos p,BlockState s){super(BwrBlockEntities.SLC_TANK.get(),p,s);}
    public BoronSolution solution(){return solution;}
    public boolean ready(){return level!=null&&!isRemoved()&&getBlockState().getBlock() instanceof PumpAssemblyBlock b&&b.isFull(getBlockState())&&b.complete(level,worldPosition,getBlockState());}
    public boolean addCharge(){if(!ready()||!solution.charge(true))return false;setChanged();return true;}
    public BoronSolution.Batch draw(double kg){if(!ready())return new BoronSolution.Batch(0,0,20);var batch=solution.remove(kg,true);if(batch.massKg()>0)setChanged();return batch;}
    // Clear water enters at the top. The chemical inventory may only leave through the SLC circuit.
    private final IFluidHandler inlet=new IFluidHandler(){
        public int getTanks(){return 1;}
        public FluidStack getFluidInTank(int tank){return FluidStack.EMPTY;}
        public int getTankCapacity(int tank){return (int)BoronSolution.CAPACITY_KG;}
        public boolean isFluidValid(int tank,FluidStack f){return tank==0&&f.getFluid()==Fluids.WATER;}
        public int fill(FluidStack f,FluidAction action){
            if(!ready()||level.isClientSide()||!isFluidValid(0,f))return 0;
            int accepted=(int)solution.addWater(f.getAmount(),ThermalWater.temperature(f,13),false);
            if(action.execute()&&accepted>0){solution.addWater(accepted,ThermalWater.temperature(f,13),true);setChanged();}
            return accepted;
        }
        public FluidStack drain(int n,FluidAction action){return FluidStack.EMPTY;}
        public FluidStack drain(FluidStack f,FluidAction action){return FluidStack.EMPTY;}
    };
    public IFluidHandler waterInlet(){return inlet;}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider p){super.saveAdditional(tag,p);tag.putDouble("SolutionKg",solution.massKg());tag.putDouble("BorateKg",solution.borateKg());tag.putDouble("SolutionC",solution.temperatureC());}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider p){super.loadAdditional(tag,p);solution.restore(tag.getDouble("SolutionKg"),tag.getDouble("BorateKg"),tag.contains("SolutionC")?tag.getDouble("SolutionC"):20);}
}
