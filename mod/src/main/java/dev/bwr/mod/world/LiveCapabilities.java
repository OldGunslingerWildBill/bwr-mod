package dev.bwr.mod.world;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** A remote port must never retain a controller's inventory across chunk unloads. */
public final class LiveCapabilities {
    private LiveCapabilities() {}
    public interface FluidProxy extends IFluidHandler { IFluidHandler current(); }
    /** The physical tank behind live port proxies; used to reserve shared capacity only once. */
    public static IFluidHandler fluidIdentity(IFluidHandler handler) {
        for(int i=0;i<8&&handler instanceof FluidProxy proxy;i++)handler=proxy.current();
        return handler;
    }
    private static <T> Supplier<T> resolve(Level level, BlockPos position, Block block, Function<BlockState,T> lookup) {
        BlockPos pos = position.immutable();
        return () -> {
            if (!level.isLoaded(pos)) return null;
            BlockState state = level.getBlockState(pos);
            return state.is(block) ? lookup.apply(state) : null;
        };
    }
    public static IEnergyStorage energy(Level level, BlockPos pos, Block block, Function<BlockState,IEnergyStorage> lookup) {
        var current = resolve(level,pos,block,lookup);
        return new IEnergyStorage() {
            public int receiveEnergy(int max,boolean simulate) { var e=current.get(); return e==null?0:e.receiveEnergy(max,simulate); }
            public int extractEnergy(int max,boolean simulate) { var e=current.get(); return e==null?0:e.extractEnergy(max,simulate); }
            public int getEnergyStored() { var e=current.get(); return e==null?0:e.getEnergyStored(); }
            public int getMaxEnergyStored() { var e=current.get(); return e==null?0:e.getMaxEnergyStored(); }
            public boolean canExtract() { var e=current.get(); return e!=null&&e.canExtract(); }
            public boolean canReceive() { var e=current.get(); return e!=null&&e.canReceive(); }
        };
    }
    public static IFluidHandler fluid(Level level, BlockPos pos, Block block, Function<BlockState,IFluidHandler> lookup) {
        var current = resolve(level,pos,block,lookup);
        return new FluidProxy() {
            public IFluidHandler current() { return current.get(); }
            public int getTanks() { var f=current.get(); return f==null?0:f.getTanks(); }
            public FluidStack getFluidInTank(int tank) { var f=current.get(); return f==null?FluidStack.EMPTY:f.getFluidInTank(tank); }
            public int getTankCapacity(int tank) { var f=current.get(); return f==null?0:f.getTankCapacity(tank); }
            public boolean isFluidValid(int tank,FluidStack fluid) { var f=current.get(); return f!=null&&f.isFluidValid(tank,fluid); }
            public int fill(FluidStack fluid,FluidAction action) { var f=current.get(); return f==null?0:f.fill(fluid,action); }
            public FluidStack drain(FluidStack fluid,FluidAction action) { var f=current.get(); return f==null?FluidStack.EMPTY:f.drain(fluid,action); }
            public FluidStack drain(int max,FluidAction action) { var f=current.get(); return f==null?FluidStack.EMPTY:f.drain(max,action); }
        };
    }
}
