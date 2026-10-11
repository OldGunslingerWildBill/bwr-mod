package dev.bwr.mod.containment;

import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.suppression.FormedSuppressionPoolRegistry;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One cached, time-sliced survey of the room surrounding a controller. */
public final class ContainmentControllerBlockEntity extends BlockEntity {
    public record BoundaryFace(BlockPos wall, Direction normal) {}

    private boolean sealed;
    private boolean stale;
    private boolean dirty = true;
    private int freeVolume;
    private BlockPos min;
    private BlockPos max;
    private BlockPos leak;
    private int sinceScan;
    private List<BoundaryFace> boundary = List.of();
    private List<BlockPos> linkedReactors = List.of();
    private List<BlockPos> linkedPools = List.of();
    private final Set<Long> indexedChunks = new HashSet<>();
    private Scan scan;

    public ContainmentControllerBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONTAINMENT_CONTROLLER.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  ContainmentControllerBlockEntity controller) {
        if (!(level instanceof ServerLevel server)) return;
        controller.sinceScan++;
        if (controller.sinceScan >= ContainmentConfig.REVALIDATE_TICKS.get()) controller.dirty = true;
        if (controller.scan == null && controller.dirty) {
            controller.scan = new Scan(pos);
            controller.dirty = false;
            controller.sinceScan = 0;
        }
        if (controller.scan != null) controller.scan.advance(server, controller,
                ContainmentConfig.SCAN_CELLS_PER_TICK.get());
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isSealed() { return sealed; }
    public boolean isStale() { return stale; }
    public boolean isScanning() { return scan != null; }
    public int freeVolume() { return freeVolume; }
    public BlockPos interiorMin() { return min; }
    public BlockPos interiorMax() { return max; }
    public BlockPos leak() { return leak; }
    public List<BoundaryFace> boundaryFaces() { return boundary; }
    public List<BlockPos> linkedReactors() { return linkedReactors; }
    public List<BlockPos> linkedPools() { return linkedPools; }
    Set<Long> indexedChunks() { return indexedChunks; }

    public boolean contains(BlockPos pos) {
        return min != null && max != null && inside(pos, min, max);
    }

    public boolean watches(BlockPos pos) {
        return min != null && max != null
                && pos.getX() >= min.getX() - 16 && pos.getX() <= max.getX() + 16
                && pos.getY() >= min.getY() - 16 && pos.getY() <= max.getY() + 16
                && pos.getZ() >= min.getZ() - 16 && pos.getZ() <= max.getZ() + 16;
    }

    private static boolean inside(BlockPos pos, BlockPos min, BlockPos max) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add(sealed ? "Containment sealed" : "Containment leaking");
        if (scan != null) lines.add("Survey in progress: " + scan.visited.size() + " cells");
        if (stale) lines.add("Survey stale: a boundary chunk is unloaded");
        if (leak != null) lines.add("Leak at " + leak.getX() + ", " + leak.getY() + ", " + leak.getZ());
        if (sealed) lines.add("Free volume: " + freeVolume + " m3");
        lines.add("Linked reactors: " + linkedReactors.size() + "; suppression pools: " + linkedPools.size());
        return lines;
    }

    private void completed(Scan finished, Level level) {
        sealed = true;
        stale = false;
        leak = null;
        freeVolume = finished.visited.size();
        min = new BlockPos(finished.minX, finished.minY, finished.minZ);
        max = new BlockPos(finished.maxX, finished.maxY, finished.maxZ);
        boundary = List.copyOf(finished.boundary);
        linkedReactors = FormedReactorRegistry.controllers(level).stream()
                .map(BlockEntity::getBlockPos).filter(this::contains).toList();
        linkedPools = FormedSuppressionPoolRegistry.controllers(level).stream()
                .map(BlockEntity::getBlockPos).filter(this::contains).toList();
        scan = null;
        ContainmentControllerRegistry.index(this);
        setChanged();
    }

    private void escaped(BlockPos at) {
        sealed = false;
        stale = false;
        leak = at.immutable();
        scan = null;
        linkedReactors = List.of();
        linkedPools = List.of();
        setChanged();
    }

    private void unloaded() {
        // A missing chunk is unknown, not an opening. Retain the last complete
        // survey and try again on the timer; never call a chunk-loading API.
        stale = true;
        scan = null;
        setChanged();
    }

    @Override public void onLoad() {
        super.onLoad();
        dirty = true;
        ContainmentControllerRegistry.register(this);
        if (min != null && max != null) ContainmentControllerRegistry.index(this);
    }

    @Override public void setRemoved() {
        ContainmentControllerRegistry.unregister(this);
        super.setRemoved();
    }

    @Override public void onChunkUnloaded() {
        ContainmentControllerRegistry.unregister(this);
        super.onChunkUnloaded();
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Sealed", sealed);
        tag.putBoolean("Stale", stale);
        tag.putInt("FreeVolume", freeVolume);
        if (min != null) tag.putLong("InteriorMin", min.asLong());
        if (max != null) tag.putLong("InteriorMax", max.asLong());
        if (leak != null) tag.putLong("Leak", leak.asLong());
        tag.putLongArray("LinkedReactors", linkedReactors.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("LinkedPools", linkedPools.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        sealed = tag.getBoolean("Sealed");
        stale = tag.getBoolean("Stale");
        freeVolume = Math.max(0, tag.getInt("FreeVolume"));
        min = tag.contains("InteriorMin", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("InteriorMin")) : null;
        max = tag.contains("InteriorMax", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("InteriorMax")) : null;
        leak = tag.contains("Leak", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("Leak")) : null;
        linkedReactors = positions(tag.getLongArray("LinkedReactors"));
        linkedPools = positions(tag.getLongArray("LinkedPools"));
        dirty = true;
        scan = null;
    }

    private static List<BlockPos> positions(long[] packed) {
        List<BlockPos> positions = new ArrayList<>(packed.length);
        for (long value : packed) positions.add(BlockPos.of(value));
        return List.copyOf(positions);
    }

    private static final class Scan {
        private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        private final LongOpenHashSet visited = new LongOpenHashSet();
        private final List<BoundaryFace> boundary = new ArrayList<>();
        private int minX, minY, minZ, maxX, maxY, maxZ;
        private BlockPos firstCrossing;

        Scan(BlockPos start) {
            BlockPos origin = start.immutable();
            queue.add(origin);
            visited.add(origin.asLong());
            minX = maxX = origin.getX();
            minY = maxY = origin.getY();
            minZ = maxZ = origin.getZ();
        }

        void advance(ServerLevel level, ContainmentControllerBlockEntity controller, int budget) {
            int limit = ContainmentConfig.MAX_INTERIOR_CELLS.get();
            int span = ContainmentConfig.MAX_SPAN.get();
            for (int count = 0; count < budget && !queue.isEmpty(); count++) {
                BlockPos current = queue.removeFirst();
                for (Direction direction : Direction.values()) {
                    BlockPos next = current.relative(direction);
                    if (next.getY() < level.getMinBuildHeight() || next.getY() >= level.getMaxBuildHeight()) {
                        controller.escaped(firstCrossing != null ? firstCrossing : next);
                        return;
                    }
                    if (!level.isLoaded(next)) {
                        controller.unloaded();
                        return;
                    }
                    BlockState state = level.getBlockState(next);
                    if (ContainmentWall.isWall(level, next, state)) {
                        boundary.add(new BoundaryFace(next.immutable(), direction));
                        continue;
                    }
                    if (!visited.add(next.asLong())) continue;
                    if (controller.min != null && controller.max != null
                            && firstCrossing == null && !inside(next, controller.min, controller.max))
                        firstCrossing = next.immutable();
                    queue.add(next.immutable());
                    minX = Math.min(minX, next.getX()); maxX = Math.max(maxX, next.getX());
                    minY = Math.min(minY, next.getY()); maxY = Math.max(maxY, next.getY());
                    minZ = Math.min(minZ, next.getZ()); maxZ = Math.max(maxZ, next.getZ());
                    if (visited.size() > limit || maxX - minX + 1 > span
                            || maxY - minY + 1 > span || maxZ - minZ + 1 > span) {
                        controller.escaped(firstCrossing != null ? firstCrossing : next);
                        return;
                    }
                }
            }
            if (queue.isEmpty()) controller.completed(this, level);
        }
    }
}
