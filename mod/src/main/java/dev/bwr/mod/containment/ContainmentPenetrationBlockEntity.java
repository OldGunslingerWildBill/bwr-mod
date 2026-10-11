package dev.bwr.mod.containment;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A spring-return integral valve. Time is advanced only when hardware is queried or commanded. */
public final class ContainmentPenetrationBlockEntity extends BlockEntity {
    private static final int ENERGY_CAPACITY = 20_000;
    private static final int OPEN_FE_PER_TICK = 100;
    private static final int HOLD_FE_PER_TICK = 20;
    private static final double STROKE_PER_TICK = 1.0 / 80.0;
    private int energyFe;
    private double position;
    private boolean demandOpen = true;
    private boolean computerControlled;
    private long observedTick = -1;
    private long powerBudgetTick = Long.MIN_VALUE;
    private int powerBudgetUsed;

    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override public int receiveEnergy(int amount, boolean simulate) {
            advance();
            int accepted = Math.min(Math.max(0, amount), ENERGY_CAPACITY - energyFe);
            if (!simulate && accepted > 0) { energyFe += accepted; setChanged(); }
            return accepted;
        }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { advance(); return energyFe; }
        @Override public int getMaxEnergyStored() { return ENERGY_CAPACITY; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    };

    public ContainmentPenetrationBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONTAINMENT_PENETRATION.get(), pos, state);
    }
    public IEnergyStorage energy() { return energy; }
    public boolean isComputerControlled() { return computerControlled; }
    public void setComputerControlled(boolean value) { computerControlled = value; setChanged(); }
    public boolean demandOpen() { return demandOpen; }
    public void setDemandOpen(boolean value) { advance(); demandOpen = value; setChanged(); }
    public double position() {
        if (getBlockState().getValue(ContainmentPenetrationBlock.RUPTURED)) return 0.0;
        if (!((ContainmentPenetrationBlock)getBlockState().getBlock()).hasValve()) return 1.0;
        advance();
        return position;
    }
    public boolean isIsolated() { return position() <= 0.0; }
    public boolean hasPowerToOpen() { advance(); return energyFe >= OPEN_FE_PER_TICK; }
    public int feAllowance(int requested) {
        if (level == null) return 0;
        long tick = level.getGameTime();
        if (tick != powerBudgetTick) { powerBudgetTick = tick; powerBudgetUsed = 0; }
        return Math.min(Math.max(0, requested),
                Math.max(0, ContainmentConfig.POWER_PENETRATION_FE_PER_TICK.get() - powerBudgetUsed));
    }
    public void recordFe(int amount) { powerBudgetUsed += Math.max(0, amount); }

    private void advance() {
        if (level == null || level.isClientSide()
                || !((ContainmentPenetrationBlock)getBlockState().getBlock()).hasValve()) return;
        long now = level.getGameTime();
        if (observedTick < 0 || now < observedTick) { observedTick = now; return; }
        long elapsed = now - observedTick;
        if (elapsed <= 0) return;
        observedTick = now;
        // At most a full energy buffer's lifetime plus a closing stroke needs
        // individual steps. A long-unloaded valve consumes no offline power.
        long steps = Math.min(elapsed, ENERGY_CAPACITY / HOLD_FE_PER_TICK + 80L);
        int beforeEnergy = energyFe;
        double beforePosition = position;
        for (long i = 0; i < steps; i++) {
            int cost = position < 1.0 ? OPEN_FE_PER_TICK : HOLD_FE_PER_TICK;
            boolean powered = demandOpen && energyFe >= cost;
            if (powered) energyFe -= cost;
            position = Math.clamp(position + (powered ? STROKE_PER_TICK : -STROKE_PER_TICK), 0.0, 1.0);
        }
        if (elapsed > steps) position = 0.0;
        if (energyFe != beforeEnergy || position != beforePosition) setChanged();
    }

    public List<String> statusLines() {
        var service = ((ContainmentPenetrationBlock)getBlockState().getBlock()).service();
        List<String> lines = new ArrayList<>();
        lines.add(service.name().replace('_', ' ') + " containment penetration");
        lines.add(ContainmentPenetrationRatings.nameplate(service));
        lines.add(getBlockState().getValue(ContainmentPenetrationBlock.RUPTURED) ? "RUPTURED: leaks; replace block"
                : String.format(Locale.ROOT, "Isolation %.0f%% open | %d FE", position() * 100.0, energyFe));
        return lines;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        advance();
        tag.putInt("EnergyFe", energyFe);
        tag.putDouble("Position", position);
        tag.putBoolean("DemandOpen", demandOpen);
        tag.putBoolean("ComputerControlled", computerControlled);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energyFe = Math.clamp(tag.getInt("EnergyFe"), 0, ENERGY_CAPACITY);
        double saved = tag.getDouble("Position");
        position = Double.isFinite(saved) ? Math.clamp(saved, 0.0, 1.0) : 0.0;
        demandOpen = !tag.contains("DemandOpen") || tag.getBoolean("DemandOpen");
        computerControlled = tag.getBoolean("ComputerControlled");
        observedTick = -1;
    }
}
