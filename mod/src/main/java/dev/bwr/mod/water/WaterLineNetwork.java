package dev.bwr.mod.water;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Water hydraulics and the NeoForge condensate-return boundary. Never traverses a machine. */
public final class WaterLineNetwork {
    private WaterLineNetwork() {}
    public static final int MAX_PIPE_BLOCKS = 256;

    public static boolean acceptsLineOn(BlockState state, Direction face) {
        if(state.getBlock() instanceof dev.bwr.mod.rods.ControlRodDriveBlock)return true;
        if(state.getBlock() instanceof WaterDischargeBlock)return face==state.getValue(WaterDischargeBlock.FACING).getOpposite();
        if(state.getBlock() instanceof dev.bwr.mod.suppression.SuppressionPoolPortBlock)return face==state.getValue(dev.bwr.mod.suppression.SuppressionPoolPortBlock.FACING);
        if(state.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock)return face.getAxis().isHorizontal();
        if(state.getBlock() instanceof CondensateStorageTankBlock)return CondensateStorageTankBlock.acceptsWater(state,face);
        if(state.getBlock() instanceof dev.bwr.mod.cooling.CoolingBlock)return dev.bwr.mod.cooling.CoolingBlock.acceptsWater(state,face);
        if(state.getBlock() instanceof dev.bwr.mod.condenser.CondenserBlock)return dev.bwr.mod.condenser.CondenserBlock.acceptsWater(state,face);
        if (state.getBlock() instanceof ProcessAssembly assembly) {
            AssemblyPort port = assembly.portAt(state, face);
            return port != null && !port.isSteam();
        }
        if (state.getBlock() instanceof dev.bwr.mod.reactor.RpvWaterInjectionPortBlock)
            return face == state.getValue(dev.bwr.mod.reactor.RpvWaterInjectionPortBlock.FACING);
        if (state.getBlock() instanceof dev.bwr.mod.flow.RecirculationPumpBlock pump) return pump.waterPortAt(state,face)!=null;
        return state.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())
                || state.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())
                || state.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get());
    }

    /** Foreign water handlers (including Mekanism turbine valves) can push condensate in. */
    public static boolean connectsTo(Level level, BlockPos pos, Direction face) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (acceptsLineOn(state, face)) return true;
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("bwr")) return false;
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, face);
        if (handler == null) return false;
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        for (int i = 0; i < handler.getTanks(); i++) {
            if (handler.isFluidValid(i, water) || handler.getFluidInTank(i).getFluid() == Fluids.WATER) return true;
        }
        return false;
    }

    /**
     * A push-through inlet, not another inventory. Every accepted mB is immediately
     * stored in one actual tank. Only known suction receivers are fill destinations;
     * foreign handlers are never called recursively through another pipe network.
     */
    public static IFluidHandler inlet(Level level, BlockPos at) {
        BlockPos origin = at.immutable();
        return new IFluidHandler() {
            @Override public int getTanks() { return 1; }
            @Override public FluidStack getFluidInTank(int tank) { return FluidStack.EMPTY; }
            @Override public int getTankCapacity(int tank) { return 2000; }
            @Override public boolean isFluidValid(int tank, FluidStack stack) { return stack.getFluid() == Fluids.WATER; }
            @Override public FluidStack drain(FluidStack stack, FluidAction action) { return FluidStack.EMPTY; }
            @Override public FluidStack drain(int amount, FluidAction action) { return FluidStack.EMPTY; }
            @Override public int fill(FluidStack stack, FluidAction action) {
                if (level.isClientSide() || stack.isEmpty() || !isFluidValid(0, stack)
                        || !level.isLoaded(origin) || !level.getBlockState(origin).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())) return 0;
                var graph=dev.bwr.mod.piping.PipeTopology.get(level,origin,null,false,false);
                if(graph.truncated())return 0;
                Set<IFluidHandler> unique=Collections.newSetFromMap(new IdentityHashMap<>());
                List<IFluidHandler> sinks=new ArrayList<>();
                for(var end:graph.ends()) {
                    var p=end.pos();if(!level.isLoaded(p))continue;
                    var state=level.getBlockState(p);var face=end.face();
                    boolean receiver=state.is(BwrBlocks.CONTROL_ROD_DRIVE.get())||state.is(BwrBlocks.WATER_DISCHARGE_PORT.get())||state.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())
                            || state.is(BwrBlocks.SUPPRESSION_POOL_RETURN.get())
                            || state.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock
                            && face==dev.bwr.mod.suppression.RhrHeatExchangerBlock.coldIn(state)
                            || state.getBlock() instanceof dev.bwr.mod.cooling.CoolingBlock
                            && (state.getValue(dev.bwr.mod.cooling.CoolingBlock.PORT)==dev.bwr.mod.cooling.CoolingBlock.Port.INLET
                                ||state.getValue(dev.bwr.mod.cooling.CoolingBlock.PORT)==dev.bwr.mod.cooling.CoolingBlock.Port.MAKEUP)
                            || state.getBlock() instanceof dev.bwr.mod.condenser.CondenserBlock
                            && state.getValue(dev.bwr.mod.condenser.CondenserBlock.PORT).inlet()
                            || state.getBlock() instanceof ProcessAssembly assembly&&assembly.portAt(state,face)==AssemblyPort.WATER_SUCTION;
                    if(!receiver)continue;
                    var sink=level.getCapability(Capabilities.FluidHandler.BLOCK,p,face);
                    var identity=dev.bwr.mod.world.LiveCapabilities.fluidIdentity(sink);
                    if(identity!=null&&unique.add(identity))sinks.add(sink);
                }
                int filled = 0;
                for (IFluidHandler sink : sinks) {
                    filled += sink.fill(stack.copyWithAmount(stack.getAmount() - filled), action);
                    if (filled == stack.getAmount()) break;
                }
                return filled;
            }
        };
    }
}
