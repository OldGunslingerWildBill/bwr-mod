package dev.bwr.mod.rwcu;

import dev.bwr.core.chemistry.RwcuCooler;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;
import java.util.Locale;

/**
 * The non-regenerative exchanger. Cooling water is piped in at its blue flange and the heated water leaves at the cyan
 * one (the same two-circuit arrangement as the RHR exchanger); the pump asks it for the duty it needs and it can give only
 * what the cooling water it holds allows. Its ticker only pushes the heated water out to whatever is piped to the outlet.
 */
public class RwcuCoolerBlockEntity extends BlockEntity implements RwcuUnit {
    private final RwcuCooler plant = new RwcuCooler();
    private long dutyTick = Long.MIN_VALUE;
    private final IFluidHandler inlet = handler(true), outlet = handler(false);

    public RwcuCoolerBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RWCU_COOLER.get(), pos, state);
    }

    public RwcuCooler plant() { return plant; }
    public IFluidHandler secondary(boolean input) { return input ? inlet : outlet; }

    private boolean live() { return level != null && !level.isClientSide() && !isRemoved() && level.isLoaded(worldPosition) && level.getBlockEntity(worldPosition) == this; }

    /** The most the cooler can remove right now, MW. */
    public double maxDutyMW(double dt) { return plant.maxDutyMW(dt); }

    /** Takes the duty from the process water into the cooling water. */
    public double commit(double dutyMW, double dt) {
        double done = plant.commit(dutyMW, dt);
        if (done > 0) dutyTick = level == null ? 0 : level.getGameTime();
        setChanged();
        return done;
    }

    /** Duty taken this tick or the last, MW. */
    public double dutyMW() { return level != null && level.getGameTime() - dutyTick <= 2 ? plant.dutyMW() : 0; }

    public static void serverTick(Level l, BlockPos p, BlockState s, RwcuCoolerBlockEntity be) {
        if (!(s.getBlock() instanceof RwcuCoolerBlock block)) return;
        Direction face = block.faceOf(s, RwcuUnitBlock.Role.COOLING_OUT);
        var next = p.relative(face);
        if (!l.isLoaded(next) || be.plant.hot() < 1) return;
        var available = be.outlet.drain((int) (RwcuCooler.SECONDARY_FLOW / 20), IFluidHandler.FluidAction.SIMULATE);
        if (available.isEmpty()) return;
        var sink = l.getCapability(Capabilities.FluidHandler.BLOCK, next, face.getOpposite());
        if (sink != null) {
            int filled = sink.fill(available, IFluidHandler.FluidAction.EXECUTE);
            be.outlet.drain(filled, IFluidHandler.FluidAction.EXECUTE);
        }
    }

    private IFluidHandler handler(boolean input) {
        return new IFluidHandler() {
            @Override public int getTanks() { return 1; }
            @Override public FluidStack getFluidInTank(int tank) {
                int n = tank == 0 && live() ? (int) Math.floor(input ? plant.cold() : plant.hot()) : 0;
                return n > 0 ? ThermalWater.stack(n, input ? plant.coldH() : plant.hotH()) : FluidStack.EMPTY;
            }
            @Override public int getTankCapacity(int tank) { return tank == 0 ? RwcuCooler.CAPACITY_KG : 0; }
            @Override public boolean isFluidValid(int tank, FluidStack s) { return tank == 0 && input && s.is(Fluids.WATER); }
            @Override public int fill(FluidStack s, FluidAction action) {
                if (!live() || !isFluidValid(0, s)) return 0;
                int n = (int) Math.floor(plant.fillCold(s.getAmount(), ThermalWater.enthalpy(s), true));
                if (n > 0 && action.execute()) { plant.fillCold(n, ThermalWater.enthalpy(s), false); setChanged(); }
                return n;
            }
            @Override public FluidStack drain(FluidStack s, FluidAction action) { return s.is(Fluids.WATER) ? drain(s.getAmount(), action) : FluidStack.EMPTY; }
            @Override public FluidStack drain(int amount, FluidAction action) {
                if (input || !live()) return FluidStack.EMPTY;
                int n = (int) Math.floor(plant.drainHot(amount, true));
                if (n <= 0) return FluidStack.EMPTY;
                double h = plant.hotH();
                if (action.execute()) { plant.drainHot(n, false); setChanged(); }
                return ThermalWater.stack(n, h);
            }
        };
    }

    private static String n(double v, int d) { return String.format(Locale.ROOT, "%." + d + "f", v); }

    @Override public Readout readout() {
        double duty = dutyMW();
        boolean on = duty > 0;
        boolean dry = plant.cold() < 1 && !on;
        return new Readout(on ? "Cooling the process water" : dry ? "No cooling water" : "Idle", dry ? 2 : on ? 1 : 0, List.of(
                new Line("Heat removed", on ? n(duty, 2) : "-", "MW of " + (int) RwcuCooler.RATING_MW, 0),
                new Line("Cooling water held", n(plant.cold(), 0), "kg", dry ? 2 : 0),
                new Line("Cooling water in", n(plant.coldTemperatureC(), 1), "C", 0),
                new Line("Cooling water out", on ? n(plant.outletC(), 1) : "-", "C", 0),
                new Line("Cooling water flow", on ? n(plant.coolingFlowKgPerS(), 1) : "-", "kg/s", 0),
                new Line("Heated water waiting", n(plant.hot(), 0), "kg", plant.hot() > 0.9 * RwcuCooler.CAPACITY_KG ? 2 : 0)));
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t, r);
        if (plant.cold() > 0 || plant.hot() > 0) {
            t.putDouble("Cold", plant.cold());
            t.putDouble("Hot", plant.hot());
            t.putDouble("ColdH", plant.coldH());
            t.putDouble("HotH", plant.hotH());
        }
    }

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        double h = Saturation.subcooledLiquidEnthalpyKJPerKg(RwcuCooler.COLD_C);
        plant.restore(t.getDouble("Cold"), t.getDouble("Hot"), t.contains("ColdH") ? t.getDouble("ColdH") : h, t.contains("HotH") ? t.getDouble("HotH") : h);
        dutyTick = Long.MIN_VALUE;
    }
}
