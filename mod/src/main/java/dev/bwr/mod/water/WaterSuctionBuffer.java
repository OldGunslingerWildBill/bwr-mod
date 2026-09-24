package dev.bwr.mod.water;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Finite incoming condensate, using the mod's 1 mB = 1 kg water boundary. */
public final class WaterSuctionBuffer {
    private double debt;
    private final FluidTank tank;
    private final Runnable changed;
    public WaterSuctionBuffer(Runnable changed) {
        this.changed = changed;
        tank = new ThermalWaterTank(2000,32,()->debt) {
            @Override protected void onContentsChanged() { changed.run(); }
            // This is a receiving buffer; an external pipe cannot remove water already owed to the pump.
            @Override public net.neoforged.neoforge.fluids.FluidStack drain(int amount, IFluidHandler.FluidAction action) {
                return super.drain(Math.min(amount, (int)Math.floor(availableKg())), action);
            }
        };
    }
    public FluidTank tank() { return tank; }
    public double enthalpy(){return ThermalWater.enthalpy(tank.getFluid(),32);}
    public double temperatureC(){return ThermalWater.temperature(tank.getFluid(),32);}
    public double availableKg() { return Math.max(0, tank.getFluidAmount() - debt); }
    public double drawKg(double requested) {
        double got = Math.min(Math.max(0, requested), availableKg());
        double total = debt + got;
        int whole = (int)Math.floor(total);
        // Retire the old fraction before draining so the guarded public handler permits repayment.
        debt = 0;
        int drained = tank.drain(whole, IFluidHandler.FluidAction.EXECUTE).getAmount();
        debt = total - drained;
        if (got > 0) changed.run();
        return got;
    }
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = tank.writeToNBT(registries, new CompoundTag());
        tag.putDouble("DrawDebtKg", debt);
        return tag;
    }
    public void load(HolderLookup.Provider registries, CompoundTag tag) {
        tank.readFromNBT(registries, tag);
        double value = tag.getDouble("DrawDebtKg");
        debt = Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
