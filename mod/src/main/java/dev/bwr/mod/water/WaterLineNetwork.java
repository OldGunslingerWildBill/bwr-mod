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
    private record Delivery(long tick, int kg) {}
    private static final Map<Level, LinkedHashMap<BlockPos, Delivery>> DELIVERIES = new WeakHashMap<>();

    /** Water actually accepted by a non-returning sink this or the previous tick. */
    public static double lastDeliveredKgPerS(Level level, BlockPos sink) {
        var entries = DELIVERIES.get(level);
        if (entries == null) return 0.0;
        Delivery delivery = entries.get(sink);
        return delivery != null && level.getGameTime() - delivery.tick() <= 1
                && level.getGameTime() >= delivery.tick() ? delivery.kg() * 20.0 : 0.0;
    }

    private static void delivered(Level level, BlockPos sink, int kg) {
        if (kg <= 0) return;
        var entries = DELIVERIES.computeIfAbsent(level, unused -> new LinkedHashMap<>());
        long tick = level.getGameTime();
        Delivery old = entries.get(sink);
        entries.put(sink.immutable(), new Delivery(tick, kg + (old != null && old.tick() == tick ? old.kg() : 0)));
        if (entries.size() > 2048) entries.remove(entries.keySet().iterator().next());
    }

    public static boolean acceptsLineOn(BlockState state, Direction face) {
        if (state.getBlock() instanceof dev.bwr.mod.containment.ContainmentPenetrationBlock penetration)
            return penetration.acceptsWaterLineOn(state, face);
        if(state.getBlock() instanceof MotorOperatedWaterValveBlock valve)return valve.acceptsWaterLineOn(state,face);
        if(state.getBlock() instanceof dev.bwr.mod.power.TurbineAuxBlock aux)return aux.acceptsWaterLineOn(state,face);
        if(state.getBlock() instanceof dev.bwr.mod.reactor.CavityDrainPortBlock)return face==state.getValue(dev.bwr.mod.reactor.CavityDrainPortBlock.FACING);
        if(state.getBlock() instanceof dev.bwr.mod.reactor.CavityFillPortBlock)return face==state.getValue(dev.bwr.mod.reactor.CavityFillPortBlock.FACING);
        if(state.getBlock() instanceof dev.bwr.mod.rods.ControlRodDriveBlock)return true;
        if(state.getBlock() instanceof WaterDischargeBlock)return face==state.getValue(WaterDischargeBlock.FACING).getOpposite();
        if(state.getBlock() instanceof dev.bwr.mod.suppression.SuppressionPoolPortBlock)return face==state.getValue(dev.bwr.mod.suppression.SuppressionPoolPortBlock.FACING);
        if(state.getBlock() instanceof dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock)return face==state.getValue(dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock.FACING);
        if(state.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock)return dev.bwr.mod.suppression.RhrHeatExchangerBlock.acceptsWaterLineOn(state,face);
        if(state.getBlock() instanceof dev.bwr.mod.containment.ContainmentSprayHeaderBlock)return true;
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
                record Sink(BlockPos pos, IFluidHandler handler) {}
                List<Sink> sinks=new ArrayList<>();
                for(var route:dev.bwr.mod.piping.PipeTopology.routes(level,graph)) {
                    var end=route.node();
                    if(end.kind()!=dev.bwr.mod.piping.PipeTopology.Kind.END || route.opening()<=0)continue;
                    var p=end.pos();var state=dev.bwr.mod.world.LoadedWorld.state(level,p);if(state==null)continue;var face=end.face();
                    boolean receiver=state.is(BwrBlocks.CONTROL_ROD_DRIVE.get())||state.is(BwrBlocks.WATER_DISCHARGE_PORT.get())||state.is(BwrBlocks.WATER_OUTLET_CHUTE.get())||state.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())
                            || state.is(BwrBlocks.SUPPRESSION_POOL_RETURN.get())
                            || state.is(BwrBlocks.SPENT_FUEL_POOL_MAKEUP_PORT.get())
                            || state.is(BwrBlocks.SPENT_FUEL_POOL_COOLING_IN.get())
                            || state.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock
                            && dev.bwr.mod.suppression.RhrHeatExchangerBlock.portOf(state,face)==dev.bwr.mod.suppression.RhrHeatExchangerBlock.HxPort.SHELL_IN
                            || state.getBlock() instanceof dev.bwr.mod.cooling.CoolingBlock
                            && (state.getValue(dev.bwr.mod.cooling.CoolingBlock.PORT)==dev.bwr.mod.cooling.CoolingBlock.Port.INLET
                                ||state.getValue(dev.bwr.mod.cooling.CoolingBlock.PORT)==dev.bwr.mod.cooling.CoolingBlock.Port.MAKEUP)
                            || state.getBlock() instanceof dev.bwr.mod.power.TurbineAuxBlock aux&&aux.hasWater()&&face==aux.coldInFace(state)
                            || state.getBlock() instanceof dev.bwr.mod.condenser.CondenserBlock
                            && state.getValue(dev.bwr.mod.condenser.CondenserBlock.PORT).inlet()
                            || state.getBlock() instanceof ProcessAssembly assembly&&assembly.portAt(state,face)==AssemblyPort.WATER_SUCTION;
                    if(!receiver)continue;
                    // The block and its entity are handed over, so the lookup reads nothing through the level: a sink
                    // many chunks down the pipe must not be kept loaded by the fill that serves it.
                    var sink=Capabilities.FluidHandler.BLOCK.getCapability(level,p,state,dev.bwr.mod.world.LoadedWorld.blockEntity(level,p),face);
                    var identity=dev.bwr.mod.world.LiveCapabilities.fluidIdentity(sink);
                    if(identity!=null&&unique.add(identity))sinks.add(new Sink(p.immutable(),sink));
                }
                int filled = 0;
                for (Sink sink : sinks) {
                    int accepted = sink.handler().fill(stack.copyWithAmount(stack.getAmount() - filled), action);
                    if (action.execute()) delivered(level, sink.pos(), accepted);
                    filled += accepted;
                    if (filled == stack.getAmount()) break;
                }
                return filled;
            }
        };
    }
}
