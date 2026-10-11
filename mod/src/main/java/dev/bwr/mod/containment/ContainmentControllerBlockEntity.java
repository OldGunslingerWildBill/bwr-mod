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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One cached, time-sliced survey of the room surrounding a controller. */
public final class ContainmentControllerBlockEntity extends BlockEntity {
    public record BoundaryFace(BlockPos wall, Direction normal) {}
    public record WeakestSpot(BlockPos position, Direction normal, String material,
                              int layers, double ratingPsig, boolean deep) {}

    private boolean sealed;
    private boolean stale;
    private boolean dirty = true;
    private int freeVolume;
    private BlockPos min;
    private BlockPos max;
    private BlockPos leak;
    private int sinceScan;
    private List<BoundaryFace> boundary = List.of();
    private final LongOpenHashSet boundaryWalls = new LongOpenHashSet();
    private final LongOpenHashSet interiorCells = new LongOpenHashSet();
    private final Map<Long, Boolean> frontierIsOutside = new HashMap<>();
    private List<BlockPos> linkedReactors = List.of();
    private List<BlockPos> linkedPools = List.of();
    private final Set<Long> indexedChunks = new HashSet<>();
    private Scan scan;
    private boolean strengthDirty = true;
    private boolean strengthReady;
    private WeakestSpot weakest;
    private final Map<BoundaryFace, ContainmentStrength.Column> ratings = new HashMap<>();
    private final Map<Long, Set<BoundaryFace>> ratingDependencies = new HashMap<>();
    private final Set<BoundaryFace> pendingRatingFaces = new HashSet<>();
    private ArrayDeque<BoundaryFace> ratingQueue;

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
        controller.updateStrength(server, Math.min(4_000, ContainmentConfig.SCAN_CELLS_PER_TICK.get()));
    }

    public void markDirty() {
        dirty = true;
    }

    public void markStrengthDirty() {
        strengthDirty = true;
    }

    /** Geometry changes rescan the room; outer reinforcement edits refresh only dependent columns. */
    public void blockChanged(BlockPos edited) {
        if (contains(edited) || boundaryWalls.contains(edited.asLong())) {
            markDirty();
            return;
        }
        Set<BoundaryFace> dependent = ratingDependencies.get(edited.asLong());
        if (dependent != null) pendingRatingFaces.addAll(dependent);
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
    public boolean strengthReady() { return strengthReady; }
    public WeakestSpot weakestSpot() { return weakest; }
    public double failurePressurePsig() { return weakest == null ? 140.0 : weakest.ratingPsig(); }
    Set<Long> indexedChunks() { return indexedChunks; }

    public boolean contains(BlockPos pos) {
        return min != null && max != null && inside(pos, min, max);
    }

    public boolean watches(BlockPos pos) {
        int depth = ContainmentConfig.STRENGTH_SCAN_DEPTH.get();
        return min != null && max != null
                && pos.getX() >= min.getX() - depth && pos.getX() <= max.getX() + depth
                && pos.getY() >= min.getY() - depth && pos.getY() <= max.getY() + depth
                && pos.getZ() >= min.getZ() - depth && pos.getZ() <= max.getZ() + depth;
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
        if (weakest != null) lines.add("Weakest spot: " + weakest.position().toShortString()
                + " " + String.format(java.util.Locale.ROOT, "%.1f psig", weakest.ratingPsig())
                + " (" + weakest.layers() + " layers" + (weakest.deep() ? ", deep" : "") + ")");
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
        boundaryWalls.clear();
        for (BoundaryFace face : boundary) boundaryWalls.add(face.wall().asLong());
        interiorCells.clear();
        interiorCells.addAll(finished.visited);
        frontierIsOutside.clear();
        linkedReactors = FormedReactorRegistry.controllers(level).stream()
                .map(BlockEntity::getBlockPos).filter(this::contains).toList();
        linkedPools = FormedSuppressionPoolRegistry.controllers(level).stream()
                .map(BlockEntity::getBlockPos).filter(this::contains).toList();
        scan = null;
        strengthDirty = true;
        ContainmentControllerRegistry.index(this);
        updateStrength(level, Math.min(4_000, ContainmentConfig.SCAN_CELLS_PER_TICK.get()));
        setChanged();
    }

    private void escaped(BlockPos at) {
        sealed = false;
        stale = false;
        leak = at.immutable();
        scan = null;
        linkedReactors = List.of();
        linkedPools = List.of();
        strengthReady = false;
        setChanged();
    }

    private void unloaded() {
        // A missing chunk is unknown, not an opening. Retain the last complete
        // survey and try again on the timer; never call a chunk-loading API.
        stale = true;
        scan = null;
        setChanged();
    }

    private void updateStrength(Level level, int budget) {
        if (!sealed || scan != null || budget <= 0) return;
        if (strengthDirty) {
            ratings.clear();
            ratingDependencies.clear();
            pendingRatingFaces.clear();
            ratingQueue = new ArrayDeque<>(new java.util.LinkedHashSet<>(boundary));
            strengthReady = false;
            strengthDirty = false;
        }
        if (ratingQueue == null && !pendingRatingFaces.isEmpty()) {
            ratingQueue = new ArrayDeque<>(pendingRatingFaces);
            pendingRatingFaces.clear();
            strengthReady = false;
        }
        if (ratingQueue == null) return;
        for (int count = 0; count < budget && !ratingQueue.isEmpty(); count++) {
            BoundaryFace face = ratingQueue.removeFirst();
            ContainmentStrength.Column prior = ratings.remove(face);
            if (prior != null) forget(prior);
            ContainmentStrength.Column column = ContainmentStrength.rate(level, face,
                    ContainmentConfig.STRENGTH_SCAN_DEPTH.get());
            if (column == null) {
                stale = true;
                ratingQueue = null;
                return;
            }
            Boolean exterior = exteriorFrontier(level, column.frontier());
            if (exterior == null) {
                stale = true;
                ratingQueue = null;
                return;
            }
            if (!exterior) continue;
            ratings.put(face, column);
            remember(column);
        }
        if (ratingQueue.isEmpty()) {
            ratingQueue = null;
            weakest = ratings.values().stream()
                    .min(java.util.Comparator.comparingDouble(ContainmentStrength.Column::ratingPsig)
                            .thenComparingLong(column -> column.position().asLong())
                            .thenComparingInt(column -> column.face().normal().ordinal()))
                    .map(column -> new WeakestSpot(column.position(), column.face().normal(),
                            column.material(), column.layerCount(), column.ratingPsig(), column.deep()))
                    .orElse(null);
            strengthReady = weakest != null;
            setChanged();
        }
    }

    /** Return whether the air beyond a wall column can reach outside the room envelope. */
    private Boolean exteriorFrontier(Level level, BlockPos frontier) {
        if (frontier == null) return true; // A depth-capped solid column is a boundary.
        if (interiorCells.contains(frontier.asLong())) return false;
        if (!inside(frontier, min, max)) return true;
        Boolean cached = frontierIsOutside.get(frontier.asLong());
        if (cached != null) return cached;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LongOpenHashSet component = new LongOpenHashSet();
        queue.add(frontier);
        component.add(frontier.asLong());
        boolean outside = false;
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (!inside(current, min, max)) { outside = true; break; }
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (component.contains(next.asLong())) continue;
                if (!level.isLoaded(next)) return null;
                if (ContainmentWall.isWall(level, next, level.getBlockState(next))) continue;
                if (interiorCells.contains(next.asLong())) {
                    // An island within the same room, including the controller itself.
                    continue;
                }
                component.add(next.asLong());
                queue.add(next.immutable());
                if (component.size() > ContainmentConfig.MAX_INTERIOR_CELLS.get()) return null;
            }
        }
        for (long pos : component) frontierIsOutside.put(pos, outside);
        return outside;
    }

    private void remember(ContainmentStrength.Column column) {
        for (BlockPos pos : column.layers()) ratingDependencies
                .computeIfAbsent(pos.asLong(), unused -> new HashSet<>()).add(column.face());
        if (column.frontier() != null) ratingDependencies
                .computeIfAbsent(column.frontier().asLong(), unused -> new HashSet<>()).add(column.face());
    }

    private void forget(ContainmentStrength.Column column) {
        for (BlockPos pos : column.layers()) forget(pos, column.face());
        if (column.frontier() != null) forget(column.frontier(), column.face());
    }

    private void forget(BlockPos pos, BoundaryFace face) {
        Set<BoundaryFace> faces = ratingDependencies.get(pos.asLong());
        if (faces == null) return;
        faces.remove(face);
        if (faces.isEmpty()) ratingDependencies.remove(pos.asLong());
    }

    /**
     * Physically opens only the least-rated continuous column after a core failure event.
     * A failure during a rating recompute uses the previous weakest spot, re-rated now.
     */
    public BlockPos ruptureWeakest(ServerLevel level) {
        if (!sealed || weakest == null) return null;
        BoundaryFace face = new BoundaryFace(weakest.position(), weakest.normal());
        ContainmentStrength.Column column = ratings.get(face);
        if (column == null) column = ContainmentStrength.rate(level, face,
                ContainmentConfig.STRENGTH_SCAN_DEPTH.get());
        if (column == null) { stale = true; return null; }
        for (BlockPos pos : column.layers()) if (!level.isLoaded(pos)) { stale = true; return null; }
        for (BlockPos pos : column.layers()) level.destroyBlock(pos, true);
        sealed = false;
        leak = weakest.position();
        strengthReady = false;
        markDirty();
        setChanged();
        return weakest.position();
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
        if (weakest != null) {
            CompoundTag spot = new CompoundTag();
            spot.putLong("Pos", weakest.position().asLong());
            spot.putInt("Normal", weakest.normal().ordinal());
            spot.putString("Material", weakest.material());
            spot.putInt("Layers", weakest.layers());
            spot.putDouble("RatingPsig", weakest.ratingPsig());
            spot.putBoolean("Deep", weakest.deep());
            tag.put("Weakest", spot);
        }
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
        weakest = null;
        if (tag.contains("Weakest", Tag.TAG_COMPOUND)) {
            CompoundTag spot = tag.getCompound("Weakest");
            int normal = Math.clamp(spot.getInt("Normal"), 0, Direction.values().length - 1);
            weakest = new WeakestSpot(BlockPos.of(spot.getLong("Pos")), Direction.values()[normal],
                    spot.getString("Material"), Math.max(0, spot.getInt("Layers")),
                    Math.max(0, spot.getDouble("RatingPsig")), spot.getBoolean("Deep"));
        }
        strengthDirty = true;
        strengthReady = false;
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
