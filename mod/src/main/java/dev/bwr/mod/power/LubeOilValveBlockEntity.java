package dev.bwr.mod.power;

import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a Lube Oil Valve keeps besides its position (which is the block state, {@link LubeOilValveBlock#OPEN}): whether its last redstone
 * reading was powered, so that only a change of the power is a command. No ticker. It is the valve's handle for computers and panels.
 */
public final class LubeOilValveBlockEntity extends BlockEntity {
    private boolean powered;

    public LubeOilValveBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.LUBE_OIL_VALVE.get(), pos, state);
    }

    /** The stem position, from the block state: true open (the open limit switch is made), false shut (the closed limit switch is made). */
    public boolean isOpen() { return getBlockState().getValue(LubeOilValveBlock.OPEN); }
    /** The open limit switch (ZSO): made with the valve open. */
    public boolean limitOpen() { return isOpen(); }
    /** The closed limit switch (ZSC): made with the valve shut. A quarter-turn is fast enough to be taken as instant, so exactly one is made. */
    public boolean limitClosed() { return !isOpen(); }
    /** The stem position as a fraction open, 0 shut, 1 open. */
    public double position() { return isOpen() ? 1 : 0; }

    /** Turns the valve to open or shut; the survey of every run through it is redone. Returns whether anything moved. */
    public boolean setOpen(boolean open) {
        var level = this.level;
        if (level == null || level.isClientSide() || isOpen() == open) return false;
        level.setBlock(worldPosition, getBlockState().setValue(LubeOilValveBlock.OPEN, open), Block.UPDATE_ALL);
        OilLineNetwork.changed(level, worldPosition);
        return true;
    }

    /** Redstone: power opens, losing it shuts; no change of the power is no command. */
    void redstone(boolean nowPowered) {
        if (nowPowered == powered) return;
        powered = nowPowered;
        setChanged();
        setOpen(nowPowered);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (powered) tag.putBoolean("Powered", true);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        powered = tag.getBoolean("Powered");
    }
}
