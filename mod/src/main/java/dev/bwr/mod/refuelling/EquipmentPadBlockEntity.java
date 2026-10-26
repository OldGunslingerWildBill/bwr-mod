package dev.bwr.mod.refuelling;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * An equipment storage pad: where the refuelling bridge sets the steam dryer or the separator assembly down while the vessel
 * is open (a real plant's dryer/separator storage pool). One assembly per pad. It holds what it holds, the reactor it came from
 * and that vessel's size (so it is drawn as it was in the vessel). No ticker: it changes only when the bridge sets something
 * down or lifts it.
 *
 * <p>Radiation: a stored assembly is a point source at its middle ({@link VesselInternal#gammaWatts}) behind the water over it,
 * counted in vanilla water blocks standing on the pad ({@code e^(-7.07 x depth)}, {@link BridgeMotion#transmission}), so a pad at
 * the bottom of a flooded pit is quiet and a pad in air is not. One provider for every pad of the level.
 */
public class EquipmentPadBlockEntity extends BlockEntity {
    /** The top of the pad block (a 4/16 plate), where an assembly's lowest point rests. */
    public static final double TOP = 0.25;
    public static final ResourceLocation RADIATION_ID = ResourceLocation.fromNamespaceAndPath("bwr", "equipment_pads");
    private static final Map<Level, Set<BlockPos>> PADS = new WeakHashMap<>();

    @Nullable
    private VesselInternal stored;
    private BlockPos reactor = BlockPos.ZERO;
    private int width, depth, height;
    private boolean internalPumps;

    public EquipmentPadBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.EQUIPMENT_PAD.get(), pos, state);
    }

    public static void registerRadiation() {
        RadiationSources.register(RADIATION_ID, EquipmentPadBlockEntity::doseRate);
    }

    /** Every pad of a loaded chunk in the level (server). */
    public static synchronized Set<BlockPos> pads(Level level) {
        Set<BlockPos> set = PADS.get(level);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        remember();
    }

    /** Known to the bridge as soon as it is in the world (onLoad comes a tick later for a block just placed). */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        remember();
    }

    private void remember() {
        if (level != null && !level.isClientSide() && !isRemoved()) {
            synchronized (EquipmentPadBlockEntity.class) {
                PADS.computeIfAbsent(level, k -> new HashSet<>()).add(worldPosition.immutable());
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
            synchronized (EquipmentPadBlockEntity.class) {
                Set<BlockPos> set = PADS.get(level);
                if (set != null) set.remove(worldPosition);
            }
        }
    }

    @Nullable
    public VesselInternal stored() {
        return stored;
    }

    public BlockPos reactorPos() {
        return reactor;
    }

    /** The vessel the stored assembly came from (its size, for drawing), or null when empty. */
    @Nullable
    public VesselProfile profile() {
        return stored == null || width <= 0 ? null : VesselProfile.of(width, depth, height, internalPumps);
    }

    /** The bridge sets an assembly down here. */
    public void store(VesselInternal which, ReactorControllerBlockEntity from, VesselProfile vessel) {
        stored = which;
        reactor = from.getBlockPos().immutable();
        width = vessel.width;
        depth = vessel.depth;
        height = vessel.height;
        internalPumps = vessel.internalPumps;
        changed();
    }

    /** The bridge lifts the assembly off; returns what was here. */
    @Nullable
    public VesselInternal take() {
        VesselInternal was = stored;
        stored = null;
        changed();
        return was;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    /** Height of the stored assembly (lowest point to lifting point), blocks; 0 when empty. */
    public double assemblyHeight() {
        VesselProfile p = profile();
        if (p == null) return 0;
        return stored == VesselInternal.DRYER ? EquipmentPads.dryerHeight(p) : EquipmentPads.separatorHeight(p);
    }

    /** Metres of water over the middle of the stored assembly: vanilla water blocks standing on the pad. */
    public double coverM() {
        if (level == null) return 0;
        int n = 0;
        BlockPos.MutableBlockPos at = worldPosition.mutable().move(0, 1, 0);
        while (n < 48 && level.isLoaded(at) && level.getFluidState(at).is(FluidTags.WATER)) {
            n++;
            at.move(0, 1, 0);
        }
        double surface = worldPosition.getY() + 1 + n;
        double middle = worldPosition.getY() + TOP + assemblyHeight() / 2;
        return n == 0 ? 0 : Math.max(0, surface - middle);
    }

    static double doseRate(Level level, Vec3 at) {
        if (level.isClientSide()) return 0;
        double total = 0;
        for (BlockPos p : pads(level)) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof EquipmentPadBlockEntity pad) || pad.stored == null) continue;
            Vec3 middle = new Vec3(p.getX() + .5, p.getY() + TOP + pad.assemblyHeight() / 2, p.getZ() + .5);
            double distance = at.distanceTo(middle);
            if (distance > DoseModel.MAX_RANGE_M) continue;
            total += dev.bwr.mod.radiation.BlockShielding.pointSource(level, middle, at, pad.stored.gammaWatts, BridgeMotion.transmission(pad.coverM()), true);
        }
        return total;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (stored != null) {
            tag.putString("Stored", stored.id);
            tag.putLong("Reactor", reactor.asLong());
            tag.putIntArray("Vessel", new int[]{width, depth, height, internalPumps ? 1 : 0});
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored = tag.contains("Stored") ? VesselInternal.byId(tag.getString("Stored")) : null;
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
