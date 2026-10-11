package dev.bwr.mod.mekanism;

import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Mekanism-only live capability bridge; this class is never loaded without Mekanism. */
final class ContainmentChemicalBridge implements IChemicalHandler {
    private final Level level;
    private final BlockPos pos;
    private final Direction side;

    ContainmentChemicalBridge(Level level, BlockPos pos, Direction side) {
        this.level = level; this.pos = pos.immutable(); this.side = side;
    }
    private IChemicalHandler current() {
        if (!level.isLoaded(pos)) return null;
        var state = level.getBlockState(pos);
        if (!state.is(BwrBlocks.CONTAINMENT_PASS_THROUGH.get())
                || state.getValue(ContainmentPenetrationBlock.RUPTURED)
                || !ContainmentPenetrationBlock.axial(state, side)
                || !(level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity valve)
                || valve.position() <= 0.0) return null;
        var next = pos.relative(side.getOpposite());
        if (!level.isLoaded(next) || level.getBlockState(next).getBlock() instanceof ContainmentPenetrationBlock
                || level.getBlockState(next).is(BwrBlocks.PRESSURISED_TUBE.get())
                || level.getBlockState(next).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get())) return null;
        return level.getCapability(MekanismSteam.CHEMICAL_HANDLER, next, side);
    }
    @Override public int getChemicalTanks() { var h = current(); return h == null ? 0 : h.getChemicalTanks(); }
    @Override public ChemicalStack getChemicalInTank(int tank) { var h = current(); return h == null ? ChemicalStack.EMPTY : h.getChemicalInTank(tank); }
    @Override public void setChemicalInTank(int tank, ChemicalStack stack) { var h = current(); if (h != null) h.setChemicalInTank(tank, stack); }
    @Override public long getChemicalTankCapacity(int tank) { var h = current(); return h == null ? 0 : h.getChemicalTankCapacity(tank); }
    @Override public boolean isValid(int tank, ChemicalStack stack) { var h = current(); return h != null && h.isValid(tank, stack); }
    @Override public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
        var h = current(); return h == null ? stack : h.insertChemical(tank, stack, action);
    }
    @Override public ChemicalStack extractChemical(int tank, long amount, Action action) {
        var h = current(); return h == null ? ChemicalStack.EMPTY : h.extractChemical(tank, amount, action);
    }
}
