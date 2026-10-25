package dev.bwr.mod.signal;

import dev.bwr.mod.condenser.CondenserBlockEntity;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.ProcessAssembly;
import dev.bwr.mod.eccs.PumpAssemblyCapabilities;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.suppression.SuppressionPoolPortBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds the block entity that owns a device at a position: the controller of a multiblock pump or MSIV,
 * the root of a vault door, airlock, condenser, cooling unit or storage tank, the pool behind a pool port.
 * Shared by the CC peripheral lookup and the signal layer so both always agree on which device a position
 * means. Never loads a chunk.
 */
public final class DeviceResolver {
    private DeviceResolver() {}

    /** The owning block entity, or null for an unloaded position, an empty cell or an unassembled part. */
    public static BlockEntity resolve(Level level, BlockPos pos) {
        BlockState state = dev.bwr.mod.world.LoadedWorld.state(level, pos);
        if (state == null) return null;
        BlockEntity be;
        if (state.getBlock() instanceof ContainmentVaultDoorBlock vault) be = vault.owner(level, pos, state);
        else if (state.getBlock() instanceof ContainmentAirlockBlock airlock) be = airlock.owner(level, pos, state);
        else if (state.getBlock() instanceof ProcessAssembly) be = PumpAssemblyCapabilities.controller(level, pos, state);
        else if (state.getBlock() instanceof dev.bwr.mod.isotope.LargeRtgBlock) be = dev.bwr.mod.isotope.LargeRtgBlock.controller(level, pos, state);
        else if (state.getBlock() instanceof MainSteamIsolationValveBlock msiv) be = msiv.controller(level, pos, state);
        else be = dev.bwr.mod.world.LoadedWorld.blockEntity(level, pos);
        if (be == null || be.isRemoved()) return null;
        if (be instanceof CondenserBlockEntity part) be = part.owner();
        else if (be instanceof CoolingBlockEntity part) be = part.owner();
        else if (be instanceof CondensateStorageTankBlockEntity part) be = part.owner();
        else if (be instanceof SuppressionPoolPortBlockEntity part) be = part.owner();
        return be;
    }
}
