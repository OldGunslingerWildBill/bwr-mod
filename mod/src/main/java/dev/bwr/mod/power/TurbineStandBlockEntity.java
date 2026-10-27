package dev.bwr.mod.power;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What a turbine hall stand holds (TURBINE-MAINTENANCE.md): two LP blade sections on a blade stand, one hood on a casing laydown stand,
 * as items, one per slot. The blade stand is an item handler on every face (hoppers and pipes put spare sections on and take any off);
 * the crane takes and sets loads through the same slots. The contents are saved only while there are any and synced for drawing.
 */
public class TurbineStandBlockEntity extends BlockEntity {
    private static final Map<Level, Set<BlockPos>> BLADE_STANDS = new WeakHashMap<>(), CASING_STANDS = new WeakHashMap<>();

    private final ItemStackHandler items;

    public TurbineStandBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.TURBINE_STAND.get(), pos, state);
        boolean blades = kind(state) == TurbineStandBlock.Kind.BLADES;
        items = new ItemStackHandler(blades ? 2 : 1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return blades ? TurbineLoadItem.isBladeSection(stack) : TurbineLoadItem.isHood(stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            protected void onContentsChanged(int slot) {
                setChanged();
                if (level != null && !level.isClientSide()) {
                    level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
                }
            }
        };
    }

    private static TurbineStandBlock.Kind kind(BlockState s) {
        return s.getBlock() instanceof TurbineStandBlock b ? b.kind() : TurbineStandBlock.Kind.BLADES;
    }

    public TurbineStandBlock.Kind kind() {
        return kind(getBlockState());
    }

    public Direction facing() {
        return getBlockState().hasProperty(TurbineStandBlock.FACING) ? getBlockState().getValue(TurbineStandBlock.FACING) : Direction.NORTH;
    }

    public ItemStackHandler items() {
        return items;
    }

    /** Puts one load in the first free slot it is valid for; returns what did not fit. */
    public ItemStack insert(ItemStack stack) {
        ItemStack rest = stack;
        for (int i = 0; i < items.getSlots() && !rest.isEmpty(); i++) {
            rest = items.insertItem(i, rest, false);
        }
        return rest;
    }

    /** Takes the load from the highest occupied slot (a player's sneak-click). */
    public ItemStack extractLast() {
        for (int i = items.getSlots() - 1; i >= 0; i--) {
            if (!items.getStackInSlot(i).isEmpty()) {
                return items.extractItem(i, 1, false);
            }
        }
        return ItemStack.EMPTY;
    }

    public int count() {
        int n = 0;
        for (int i = 0; i < items.getSlots(); i++) if (!items.getStackInSlot(i).isEmpty()) n++;
        return n;
    }

    void clearForRemoval() {
        for (int i = 0; i < items.getSlots(); i++) items.setStackInSlot(i, ItemStack.EMPTY);
    }

    // ---- where the loads sit, for the crane and the renderer

    /** The middle of a slot's place: a blade stand's two saddles are 2.5 blocks each side of the stand across its facing; the cradle's is the stand. */
    public double[] slotXZ(int slot) {
        double x = worldPosition.getX() + 0.5, z = worldPosition.getZ() + 0.5;
        if (kind() == TurbineStandBlock.Kind.BLADES) {
            Direction side = facing().getClockWise();
            double s = slot == 0 ? 2.5 : -2.5;
            x += side.getStepX() * s;
            z += side.getStepZ() * s;
        }
        return new double[]{x, z};
    }

    /** Every stand of this kind in a loaded chunk of the level (server). */
    public static synchronized Set<BlockPos> stands(Level level, TurbineStandBlock.Kind kind) {
        Set<BlockPos> s = (kind == TurbineStandBlock.Kind.BLADES ? BLADE_STANDS : CASING_STANDS).get(level);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        remember();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        remember();
    }

    private void remember() {
        if (level != null && !level.isClientSide() && !isRemoved()) {
            synchronized (TurbineStandBlockEntity.class) {
                (kind() == TurbineStandBlock.Kind.BLADES ? BLADE_STANDS : CASING_STANDS).computeIfAbsent(level, k -> new HashSet<>()).add(worldPosition.immutable());
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
            synchronized (TurbineStandBlockEntity.class) {
                Set<BlockPos> s = (kind() == TurbineStandBlock.Kind.BLADES ? BLADE_STANDS : CASING_STANDS).get(level);
                if (s != null) s.remove(worldPosition);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (count() > 0) {
            tag.put("Items", items.serializeNBT(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Items")) {
            items.deserializeNBT(registries, tag.getCompound("Items"));
        } else {
            for (int i = 0; i < items.getSlots(); i++) items.setStackInSlot(i, ItemStack.EMPTY);
        }
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
