package dev.bwr.mod.power;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A Turbine Shaft Wreck's record (TURBINE-MAINTENANCE.md): the LP and the flow it came from, saved only when known, and synced for
 * drawing. Loaded wrecks are listed per level for the crane's survey.
 */
public class TurbineWreckBlockEntity extends BlockEntity {
    private static final Map<Level, Set<BlockPos>> WRECKS = new WeakHashMap<>();
    private BlockPos source;
    private int flow;

    public TurbineWreckBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.TURBINE_SHAFT_WRECK.get(), pos, state);
    }

    public Direction.Axis axis() {
        return getBlockState().hasProperty(TurbineWreckBlock.AXIS) ? getBlockState().getValue(TurbineWreckBlock.AXIS) : Direction.Axis.X;
    }

    /** The LP it was thrown out of, or null. */
    public BlockPos source() {
        return source;
    }

    public int flow() {
        return flow;
    }

    void from(BlockPos lp, int flow) {
        this.source = lp.immutable();
        this.flow = flow;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    /** Every loaded shaft wreck of a level, server side. */
    public static synchronized Set<BlockPos> wrecks(Level level) {
        var s = WRECKS.get(level);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    private void remember() {
        if (level != null && !level.isClientSide() && !isRemoved()) {
            synchronized (TurbineWreckBlockEntity.class) {
                WRECKS.computeIfAbsent(level, k -> new HashSet<>()).add(worldPosition.immutable());
            }
        }
    }

    private void forget() {
        if (level != null && !level.isClientSide()) {
            synchronized (TurbineWreckBlockEntity.class) {
                var s = WRECKS.get(level);
                if (s != null) s.remove(worldPosition);
            }
        }
    }

    @Override public void onLoad() { super.onLoad(); remember(); }
    @Override public void setLevel(Level level) { super.setLevel(level); remember(); }
    @Override public void setRemoved() { forget(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { forget(); super.onChunkUnloaded(); }

    @Override
    protected void saveAdditional(CompoundTag t, HolderLookup.Provider registries) {
        super.saveAdditional(t, registries);
        if (source != null) {
            t.putLong("Source", source.asLong());
            t.putByte("Flow", (byte) flow);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag t, HolderLookup.Provider registries) {
        super.loadAdditional(t, registries);
        source = t.contains("Source") ? BlockPos.of(t.getLong("Source")) : null;
        flow = Math.max(0, Math.min(1, t.getByte("Flow")));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
