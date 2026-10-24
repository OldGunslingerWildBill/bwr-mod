package dev.bwr.mod.refuelling;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A head stand: where the reactor building crane sets the vessel head down while the vessel is open. The block marks the middle of
 * the head; the head is as big as the vessel (a 17 x 17 vessel has a 17 m wide head), so it lies on a cradle drawn to its size over
 * the floor round the block, which has to be clear and between the crane's rails ({@link ReactorCraneBlockEntity} checks). It holds
 * the head of one reactor, remembers that vessel's size so the head is drawn as it was, and has no ticker: it changes only when the
 * crane sets the head down or lifts it.
 */
public class HeadStandBlockEntity extends BlockEntity {
    /** The top of the plinth block, m. */
    public static final double TOP = 0.25;
    /** How high the cradle raises the head's mating face above the plinth, m. */
    public static final double CRADLE_HEIGHT = 0.6;
    /** The head's mating face rests this far above the floor of the stand block, m. */
    public static final double REST = TOP + CRADLE_HEIGHT;
    private static final Map<Level, Set<BlockPos>> STANDS = new WeakHashMap<>();

    private boolean holding;
    private BlockPos reactor = BlockPos.ZERO;
    private int width, depth, height;
    private boolean internalPumps;

    public HeadStandBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.HEAD_STAND.get(), pos, state);
    }

    /** Every head stand of a loaded chunk in the level (server). */
    public static synchronized Set<BlockPos> stands(Level level) {
        Set<BlockPos> set = STANDS.get(level);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        remember();
    }

    /** Known to the crane as soon as it is in the world (onLoad comes a tick later for a block just placed). */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        remember();
    }

    private void remember() {
        if (level != null && !level.isClientSide() && !isRemoved()) {
            synchronized (HeadStandBlockEntity.class) {
                STANDS.computeIfAbsent(level, k -> new HashSet<>()).add(worldPosition.immutable());
            }
        }
    }

    @Override
    public void setRemoved() {
        forget();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        forget();
        super.onChunkUnloaded();
    }

    private void forget() {
        if (level != null && !level.isClientSide()) {
            synchronized (HeadStandBlockEntity.class) {
                Set<BlockPos> set = STANDS.get(level);
                if (set != null) set.remove(worldPosition);
            }
        }
    }

    /** Whether the stand holds a vessel head. */
    public boolean holdsHead() {
        return holding;
    }

    public BlockPos reactorPos() {
        return reactor;
    }

    /** The vessel the head came from (its size, for drawing), or null when empty. */
    @Nullable
    public VesselProfile profile() {
        return !holding || width <= 0 ? null : VesselProfile.of(width, depth, height, internalPumps);
    }

    /** The crane sets a head down here. */
    public void store(ReactorControllerBlockEntity from, VesselProfile vessel) {
        holding = true;
        reactor = from.getBlockPos().immutable();
        width = vessel.width;
        depth = vessel.depth;
        height = vessel.height;
        internalPumps = vessel.internalPumps;
        changed();
    }

    /** The crane lifts the head off; returns whether there was one. */
    public boolean take() {
        boolean was = holding;
        holding = false;
        changed();
        return was;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (holding) {
            tag.putBoolean("Holding", true);
            tag.putLong("Reactor", reactor.asLong());
            tag.putIntArray("Vessel", new int[]{width, depth, height, internalPumps ? 1 : 0});
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        holding = tag.getBoolean("Holding");
        reactor = tag.contains("Reactor") ? BlockPos.of(tag.getLong("Reactor")) : BlockPos.ZERO;
        int[] v = tag.getIntArray("Vessel");
        width = v.length > 0 ? v[0] : 0;
        depth = v.length > 1 ? v[1] : 0;
        height = v.length > 2 ? v[2] : 0;
        internalPumps = v.length > 3 && v[3] != 0;
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
