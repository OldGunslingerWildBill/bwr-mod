package dev.bwr.mod.refuelling;

import dev.bwr.mod.registry.BwrEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shield plug section while the reactor building crane has it on the hook (BUILDING-CRANE.md): one entity that holds the exact block states of
 * every block of the section (the plug blocks with their tier and seams, and the crane anchors), after the blocks have left the world. It hangs
 * from the hook and moves with it; when the crane sets it down it puts the blocks back exactly and removes itself.
 *
 * <h2>Blocks are never lost</h2>
 * <ul>
 *   <li>The entity is saved with its chunk and loaded with it; it never despawns, and a world saved with a section hanging loads with the same
 *       section hanging, once.</li>
 *   <li>Nothing can hurt, push, pick, ride or collide with it; {@code /summon} cannot make one.</li>
 *   <li>Once a second, while loaded, it checks that its crane (the console block entity) is loaded, still has the crane's runway and still holds
 *       this entity. If the crane is gone, or its runway is broken, or the crane does not claim it (it is an orphan), the section is
 *       <b>put back where it was taken from</b> if every one of those places is clear and loaded; if any place is no longer clear (never replacing a
 *       block) the section's blocks are <b>dropped as items</b> at the hook instead. Either way nothing is lost.</li>
 *   <li>Removing it by any means that destroys it ({@code /kill}, discarding) does the same: the blocks go back or drop; only a deliberate
 *       set-down ({@link #settle}) or an unloading chunk removes it silently.</li>
 *   <li>It never loads a chunk: the crane moves it only into loaded chunks, and a set-down checks the chunks first.</li>
 * </ul>
 */
public class PlugSectionEntity extends Entity implements IEntityWithComplexSpawn {

    /** A block of the section, in cells from the section's minimum corner. */
    public record Cell(int x, int y, int z, BlockState state) {
    }

    private final List<Cell> cells = new ArrayList<>();
    /** Where the section was taken from: its minimum corner. */
    private BlockPos pickupMin = BlockPos.ZERO;
    /** The crane console that carries it. */
    private BlockPos cranePos = BlockPos.ZERO;
    /** The pick point (the hook's column), relative to the minimum corner, blocks. */
    private double pickX, pickZ;
    /** True once the blocks are back in the world or dropped: removing the entity is then no loss. */
    private boolean settled;

    public PlugSectionEntity(EntityType<? extends PlugSectionEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    // ---------------------------------------------------------------
    // Taking and placing
    // ---------------------------------------------------------------

    /**
     * Takes a section off the world: the entity is created (holding every block's exact state) and added at the hook column, then the blocks
     * are removed. Returns null, with nothing changed, if the entity cannot be added.
     */
    @Nullable
    public static PlugSectionEntity take(ServerLevel level, BlockPos crane, PlugSections.Section s, Vec3 at) {
        PlugSectionEntity e = new PlugSectionEntity(BwrEntities.PLUG_SECTION.get(), level);
        for (BlockPos p : s.cells()) {
            e.cells.add(new Cell(p.getX() - s.min().getX(), p.getY() - s.min().getY(), p.getZ() - s.min().getZ(), level.getBlockState(p)));
        }
        double[] pick = s.pick();
        e.pickupMin = s.min().immutable();
        e.cranePos = crane.immutable();
        e.pickX = pick[0] - s.min().getX();
        e.pickZ = pick[1] - s.min().getZ();
        e.setPos(at.x, at.y, at.z);
        if (!level.addFreshEntity(e)) {
            return null;
        }
        List<BlockPos> cells = s.cells();
        // anchors first, so a plug block never goes while something stands on it
        for (int i = cells.size() - 1; i >= 0; i--) {
            level.setBlock(cells.get(i), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (BlockPos p : cells) {
            level.updateNeighborsAt(p, net.minecraft.world.level.block.Blocks.AIR);
        }
        return e;
    }

    public List<Cell> cells() {
        return cells;
    }

    public BlockPos pickupMin() {
        return pickupMin;
    }

    public BlockPos cranePos() {
        return cranePos;
    }

    public double pickX() {
        return pickX;
    }

    public double pickZ() {
        return pickZ;
    }

    /** Height of the section in blocks. */
    public int sizeY() {
        int top = 0;
        for (Cell c : cells) {
            top = Math.max(top, c.y() + 1);
        }
        return top;
    }

    public int sizeX() {
        int top = 0;
        for (Cell c : cells) {
            top = Math.max(top, c.x() + 1);
        }
        return top;
    }

    public int sizeZ() {
        int top = 0;
        for (Cell c : cells) {
            top = Math.max(top, c.z() + 1);
        }
        return top;
    }

    /** True once the section's blocks are back in the world (or were dropped). */
    public boolean settled() {
        return settled;
    }

    /** Why the section cannot be set down with its minimum corner at {@code min} (every cell must be air, in a loaded chunk), or null. */
    @Nullable
    public Component placeProblem(Level level, BlockPos min, boolean needFloor) {
        List<BlockPos> abs = new ArrayList<>();
        for (Cell c : cells) {
            abs.add(min.offset(c.x(), c.y(), c.z()));
        }
        return needFloor ? PlugSections.placeProblem(level, abs, min.getY(), BlockPos.ZERO) : PlugSections.placeProblem(level, abs, Integer.MIN_VALUE, BlockPos.ZERO);
    }

    /**
     * Puts every block back with its minimum corner at {@code min}, exactly as it was taken (state for state), after checking that every cell is clear
     * and loaded. Returns null on success (the entity is then {@link #settled}; the caller discards it), or the reason nothing was placed.
     */
    @Nullable
    public Component settle(ServerLevel level, BlockPos min, boolean needFloor) {
        Component why = placeProblem(level, min, needFloor);
        if (why != null) {
            return why;
        }
        // plug blocks first, the anchors standing on them last
        for (int pass = 0; pass < 2; pass++) {
            for (Cell c : cells) {
                boolean anchor = c.state().getBlock() instanceof CraneAnchorBlock;
                if (anchor == (pass == 1)) {
                    level.setBlock(min.offset(c.x(), c.y(), c.z()), c.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
        for (Cell c : cells) {
            BlockPos p = min.offset(c.x(), c.y(), c.z());
            level.updateNeighborsAt(p, c.state().getBlock());
        }
        settled = true;
        return null;
    }

    /**
     * Gets the blocks out of the entity when it must go: back at the place it was taken from if that is clear and loaded, otherwise as dropped
     * items at the entity. Returns true when it is done (nothing is held any more).
     */
    public boolean releaseSafely(ServerLevel level, boolean mustFinish) {
        if (settled || cells.isEmpty()) {
            return true;
        }
        if (settle(level, pickupMin, false) == null) {
            return true;
        }
        boolean allLoaded = true;
        for (Cell c : cells) {
            allLoaded &= level.isLoaded(pickupMin.offset(c.x(), c.y(), c.z()));
        }
        if (!allLoaded && !mustFinish) {
            return false;
        }
        Map<net.minecraft.world.item.Item, Integer> items = new LinkedHashMap<>();
        for (Cell c : cells) {
            var item = c.state().getBlock().asItem();
            if (item != net.minecraft.world.item.Items.AIR) {
                items.merge(item, 1, Integer::sum);
            }
        }
        for (var e : items.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getDefaultMaxStackSize());
                Containers.dropItemStack(level, getX(), getY() + 0.5, getZ(), new ItemStack(e.getKey(), n));
                left -= n;
            }
        }
        settled = true;
        return true;
    }

    /** Sets the pose from the hook: the bottom of the section under the pick point. */
    public void follow(double x, double y, double z) {
        setPos(x, y, z);
    }

    // ---------------------------------------------------------------
    // The guard
    // ---------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel server && tickCount > 0 && tickCount % 20 == 0 && !settled) {
            guard(server);
        }
    }

    private void guard(ServerLevel level) {
        if (!level.isLoaded(cranePos)) {
            return;
        }
        BuildingCraneBlockEntity crane = level.getBlockEntity(cranePos) instanceof BuildingCraneBlockEntity c ? c : null;
        if (crane == null || !crane.ownsPlugEntity(getUUID())) {
            // the crane is gone, or does not hold this section: an orphan
            if (releaseSafely(level, false) && crane != null) {
                crane.plugReleased(level, getUUID());
            }
            if (settled) {
                discard();
            }
            return;
        }
        if (!crane.runwayStillStands()) {
            if (releaseSafely(level, false)) {
                crane.plugLost(level, getUUID());
                discard();
            }
        }
    }

    /** Called when the entity leaves the level for any reason: destroyed ({@code /kill}, discarded) it gives up its blocks; unloading keeps them. */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        RemovalReason reason = getRemovalReason();
        if (!settled && reason != null && reason.shouldDestroy() && level() instanceof ServerLevel server && !cells.isEmpty()) {
            releaseSafely(server, true);
        }
    }

    // ---------------------------------------------------------------
    // Nothing touches it
    // ---------------------------------------------------------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean skipAttackInteraction(Entity entity) {
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public void push(Entity entity) {
    }

    @Override
    public void push(double x, double y, double z) {
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean canChangeDimensions(Level from, Level to) {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    /** The whole section, for the camera's cull test (the entity's own box is a metre). */
    @Override
    public AABB getBoundingBoxForCulling() {
        double x0 = getX() - pickX, z0 = getZ() - pickZ;
        return new AABB(x0 - 1, getY() - 1, z0 - 1, x0 + sizeX() + 1, getY() + sizeY() + 1, z0 + sizeZ() + 1);
    }

    // ---------------------------------------------------------------
    // Persistence and sync
    // ---------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Cell c : cells) {
            CompoundTag t = new CompoundTag();
            t.putInt("X", c.x());
            t.putInt("Y", c.y());
            t.putInt("Z", c.z());
            t.put("S", NbtUtils.writeBlockState(c.state()));
            list.add(t);
        }
        tag.put("Cells", list);
        tag.putLong("Pickup", pickupMin.asLong());
        tag.putLong("Crane", cranePos.asLong());
        tag.putDouble("PickX", pickX);
        tag.putDouble("PickZ", pickZ);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        cells.clear();
        ListTag list = tag.getList("Cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), t.getCompound("S"));
            cells.add(new Cell(t.getInt("X"), t.getInt("Y"), t.getInt("Z"), state));
        }
        pickupMin = BlockPos.of(tag.getLong("Pickup"));
        cranePos = BlockPos.of(tag.getLong("Crane"));
        pickX = tag.getDouble("PickX");
        pickZ = tag.getDouble("PickZ");
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeLong(cranePos.asLong());
        buf.writeDouble(pickX);
        buf.writeDouble(pickZ);
        buf.writeVarInt(cells.size());
        for (Cell c : cells) {
            buf.writeVarInt(c.x());
            buf.writeVarInt(c.y());
            buf.writeVarInt(c.z());
            buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(c.state()));
        }
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        cranePos = BlockPos.of(buf.readLong());
        pickX = buf.readDouble();
        pickZ = buf.readDouble();
        int n = buf.readVarInt();
        cells.clear();
        for (int i = 0; i < n; i++) {
            int x = buf.readVarInt(), y = buf.readVarInt(), z = buf.readVarInt();
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(buf.readVarInt());
            cells.add(new Cell(x, y, z, state == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : state));
        }
    }
}
