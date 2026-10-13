package dev.bwr.mod.containment;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Finite nozzle inventory; actual flow is metered once per room tick. */
public final class ContainmentSprayHeaderBlockEntity extends BlockEntity {
    public static final int CAPACITY_KG = 1000;
    public static final double MAX_FLOW_KG_PER_S = 500.0;
    private double waterKg;
    private double waterTemperatureC = ThermalWater.AMBIENT_C;
    private double lastFlowKgPerS;
    private long sampledTick = Long.MIN_VALUE;
    private final IFluidHandler inlet = new IFluidHandler() {
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) {
            return tank == 0 ? ThermalWater.atTemperature((int) waterKg, waterTemperatureC) : FluidStack.EMPTY;
        }
        @Override public int getTankCapacity(int tank) { return tank == 0 ? CAPACITY_KG : 0; }
        @Override public boolean isFluidValid(int tank, FluidStack water) {
            return tank == 0 && water.is(Fluids.WATER);
        }
        @Override public int fill(FluidStack water, FluidAction action) {
            if (!live() || !isFluidValid(0, water)) return 0;
            int accepted = (int) Math.min(water.getAmount(), Math.floor(CAPACITY_KG - waterKg));
            if (accepted > 0 && action.execute()) receiveWaterKg(accepted, ThermalWater.temperature(water, ThermalWater.AMBIENT_C));
            return accepted;
        }
        @Override public FluidStack drain(FluidStack water, FluidAction action) { return FluidStack.EMPTY; }
        @Override public FluidStack drain(int amount, FluidAction action) { return FluidStack.EMPTY; }
    };

    public record Spray(double flowKgPerS, double temperatureC) {}
    public ContainmentSprayHeaderBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONTAINMENT_SPRAY_HEADER.get(), pos, state);
    }
    private boolean live() {
        return level != null && !level.isClientSide() && !isRemoved() && level.isLoaded(worldPosition)
                && level.getBlockEntity(worldPosition) == this;
    }
    public IFluidHandler waterInlet() { return inlet; }
    public double getWaterKg() { return waterKg; }
    public double getWaterTemperatureC() { return waterTemperatureC; }
    public double getLastFlowKgPerS() { return lastFlowKgPerS; }
    public double availableSpaceKg() { return Math.max(0, CAPACITY_KG - waterKg); }
    public double receiveWaterKg(double requested, double temperatureC) {
        if (!live() || !Double.isFinite(requested) || !Double.isFinite(temperatureC) || requested <= 0) return 0;
        double accepted = Math.min(requested, availableSpaceKg());
        if (accepted <= 0) return 0;
        waterTemperatureC = (waterTemperatureC * waterKg + temperatureC * accepted) / (waterKg + accepted);
        waterKg += accepted;
        setChanged();
        return accepted;
    }
    /** The reactor samples this once per world tick; no block ticker runs. */
    public Spray takeForRoom(long now, double dt) {
        if (!live() || !(dt > 0) || sampledTick == now) return new Spray(0, waterTemperatureC);
        sampledTick = now;
        double used = Math.min(waterKg, MAX_FLOW_KG_PER_S * dt);
        waterKg -= used;
        lastFlowKgPerS = used / dt;
        if (used > 0) setChanged();
        return new Spray(lastFlowKgPerS, waterTemperatureC);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("WaterKg", waterKg);
        tag.putDouble("WaterC", waterTemperatureC);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        waterKg = Math.clamp(tag.getDouble("WaterKg"), 0, CAPACITY_KG);
        waterTemperatureC = tag.contains("WaterC") && Double.isFinite(tag.getDouble("WaterC"))
                ? tag.getDouble("WaterC") : ThermalWater.AMBIENT_C;
        lastFlowKgPerS = 0;
        sampledTick = Long.MIN_VALUE;
    }
}
