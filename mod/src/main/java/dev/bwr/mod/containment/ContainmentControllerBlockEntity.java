package dev.bwr.mod.containment;

import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.reactor.RecirculationPortBlock;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.water.WaterLineNetwork;
import dev.bwr.mod.water.WaterDischargeBlock;
import dev.bwr.mod.eccs.CondensateStorageTankBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.FormedSuppressionPoolRegistry;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
    private List<BlockPos> linkedSprayHeaders = List.of();
    private final Set<Long> indexedChunks = new HashSet<>();
    private Scan scan;
    private boolean strengthDirty = true;
    private boolean strengthReady;
    private WeakestSpot weakest;
    private final Map<BoundaryFace, ContainmentStrength.Column> ratings = new HashMap<>();
    /** Published only after a complete rating pass; live valve state is still queried per crossing. */
    private List<BoundaryFace> penetrationFaces = List.of(); // All rated openings, including doors.
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
    /** A forced dual-open personnel passage has a smaller gas aperture than a missing wall. */
    public boolean isForcedAirlockLeak() {
        if (sealed || stale || leak == null || level == null || !level.isLoaded(leak)) return false;
        BlockState state = level.getBlockState(leak);
        if (!(state.getBlock() instanceof ContainmentAirlockBlock airlock)) return false;
        var owner = airlock.owner(level, leak, state);
        return owner != null && owner.innerOpen() && owner.outerOpen() && !owner.damaged();
    }
    public boolean isStale() { return stale; }
    public boolean isScanning() { return scan != null; }
    public int freeVolume() { return freeVolume; }
    public BlockPos interiorMin() { return min; }
    public BlockPos interiorMax() { return max; }
    public BlockPos leak() { return leak; }
    public List<BoundaryFace> boundaryFaces() { return boundary; }
    public List<BlockPos> linkedReactors() { return linkedReactors; }
    public List<BlockPos> linkedPools() { return linkedPools; }
    public List<BlockPos> linkedSprayHeaders() { return linkedSprayHeaders; }
    public boolean strengthReady() { return strengthReady; }
    public WeakestSpot weakestSpot() { return weakest; }
    public double failurePressurePsig() { return weakest == null ? 140.0 : weakest.ratingPsig(); }
    public List<BlockPos> openings() {
        return penetrationFaces.stream().map(BoundaryFace::wall).distinct()
                .filter(pos -> level != null && level.isLoaded(pos)
                        && (level.getBlockState(pos).getBlock() instanceof ContainmentPenetrationBlock
                            || level.getBlockState(pos).getBlock() instanceof ContainmentVaultDoorBlock
                            || level.getBlockState(pos).getBlock() instanceof ContainmentAirlockBlock))
                .toList();
    }

    /** Flow out of the sealed boundary through open, connected process crossings. */
    public double bypassFlowFraction(ReactorControllerBlockEntity reactor) {
        if (level == null || !sealed || weakest == null || reactor.structure() == null) return 0.0;
        double flow = 0.0;
        var boundaries = penetrationFaces;
        for (BlockPos nozzlePos : reactor.structure().steamOutletPositions()) {
            if (!level.isLoaded(nozzlePos)
                    || !(level.getBlockEntity(nozzlePos) instanceof RpvSteamOutletBlockEntity nozzle)) continue;
            PipeTopology.Graph inside = PipeTopology.get(level, nozzlePos, null, true, false);
            double best = 0.0;
            for (BoundaryFace face : boundaries)
                best = Math.max(best, openCrossing(inside, face, ContainmentPenetrationBlock.Service.STEAM));
            flow += nozzle.getLastFlowKgPerS() * best;
        }
        Map<BlockPos, Double> waterSinks = new HashMap<>();
        for (BlockPos port : reactor.structure().waterInjectionPositions()) {
            if (!level.isLoaded(port) || !(level.getBlockState(port).getBlock() instanceof RecirculationPortBlock b)
                    || !b.isOutlet()) continue;
            PipeTopology.Graph inside = PipeTopology.get(level, port, null, false, false);
            for (BoundaryFace face : boundaries)
                waterCrossings(inside, face, waterSinks);
        }
        for (var sink : waterSinks.entrySet())
            flow += WaterLineNetwork.lastDeliveredKgPerS(level, sink.getKey()) * sink.getValue();
        return Math.clamp(flow / RpvSteamOutletBlockEntity.RATED_STEAM_FLOW_KG_PER_S, 0.0, 1.0);
    }

    private double openCrossing(PipeTopology.Graph inside, BoundaryFace face,
                                ContainmentPenetrationBlock.Service expected) {
        BlockPos pos = face.wall();
        if (!level.isLoaded(pos)) return 0.0;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ContainmentPenetrationBlock penetration)
                || penetration.service() != expected
                || state.getValue(ContainmentPenetrationBlock.RUPTURED)
                || !ContainmentPenetrationBlock.axial(state, face.normal())
                || !(level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity valve)) return 0.0;
        double inlet = PipeTopology.routes(level, inside).stream()
                .filter(route -> route.node().pos().equals(pos))
                .mapToDouble(PipeTopology.Route::opening).max().orElse(0.0);
        if (inlet <= 0.0) return 0.0;
        PipeTopology.Graph outside = PipeTopology.get(level, pos, face.normal(),
                expected == ContainmentPenetrationBlock.Service.STEAM, false);
        double exit = PipeTopology.routes(level, outside).stream()
                .filter(route -> route.node().kind() == PipeTopology.Kind.END
                        && !encloses(route.node().pos()))
                .mapToDouble(PipeTopology.Route::opening).max().orElse(0.0);
        return Math.min(Math.min(inlet, exit), valve.position());
    }

    private void waterCrossings(PipeTopology.Graph inside, BoundaryFace face,
                                Map<BlockPos, Double> sinks) {
        BlockPos pos = face.wall();
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ContainmentPenetrationBlock penetration)
                || penetration.service() != ContainmentPenetrationBlock.Service.WATER
                || state.getValue(ContainmentPenetrationBlock.RUPTURED)
                || !ContainmentPenetrationBlock.axial(state, face.normal())
                || !(level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity valve)) return;
        double inlet = PipeTopology.routes(level, inside).stream()
                .filter(route -> route.node().pos().equals(pos))
                .mapToDouble(PipeTopology.Route::opening).max().orElse(0.0);
        if (inlet <= 0) return;
        PipeTopology.Graph outside = PipeTopology.get(level, pos, face.normal(), false, true);
        for (PipeTopology.Route route : PipeTopology.routes(level, outside)) {
            BlockPos sink = route.node().pos();
            if (route.node().kind() != PipeTopology.Kind.END || encloses(sink)
                    || !level.isLoaded(sink) || !nonReturningWaterSink(sink)) continue;
            double opening = Math.min(Math.min(inlet, route.opening()), valve.position());
            if (opening > 0) sinks.merge(sink, opening, Math::max);
        }
    }

    private boolean nonReturningWaterSink(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof WaterDischargeBlock
                || state.getBlock() instanceof CondensateStorageTankBlock
                || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("bwr");
    }
    Set<Long> indexedChunks() { return indexedChunks; }

    public boolean contains(BlockPos pos) {
        return min != null && max != null && inside(pos, min, max);
    }

    /**
     * True when a block or one of its six neighbours is an interior cell of the
     * last survey, so a reactor controller set in its vessel wall still counts.
     * The bounding box is not enough: an L-shaped room's box takes in the room
     * next door.
     */
    public boolean encloses(BlockPos pos) {
        if (interiorCells.contains(pos.asLong())) return true;
        for (Direction direction : Direction.values())
            if (interiorCells.contains(pos.relative(direction).asLong())) return true;
        return false;
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
        lines.add("Openings: " + openings().size());
        lines.add("Linked reactors: " + linkedReactors.size() + "; suppression pools: " + linkedPools.size());
        if (level != null) for (BlockPos reactorPos : linkedReactors) {
            if (!level.isLoaded(reactorPos)
                    || !(level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity reactor)
                    || !reactor.isFormed()) continue;
            var gas = reactor.core().getContainment();
            lines.add(String.format(java.util.Locale.ROOT,
                    "Atmosphere: H2 %.2f%%, O2 %.2f%%, %.2f psig",
                    gas.getHydrogenPercent(), gas.getOxygenPercent(), gas.getPressurePsig()));
            break;
        }
        return lines;
    }

    private void completed(Scan finished, Level level) {
        sealed = true;
        stale = false;
        leak = null;
        freeVolume = finished.gasCells;
        min = new BlockPos(finished.minX, finished.minY, finished.minZ);
        max = new BlockPos(finished.maxX, finished.maxY, finished.maxZ);
        boundary = List.copyOf(finished.boundary);
        boundaryWalls.clear();
        for (BoundaryFace face : boundary) boundaryWalls.add(face.wall().asLong());
        interiorCells.clear();
        interiorCells.addAll(finished.visited);
        frontierIsOutside.clear();
        linkedReactors = FormedReactorRegistry.controllers(level).stream()
                .map(BlockEntity::getBlockPos).filter(this::encloses).toList();
        linkedPools = FormedSuppressionPoolRegistry.controllers(level).stream()
                .map(BlockEntity::getBlockPos).filter(this::encloses).toList();
        var sprays = new ArrayList<BlockPos>();
        for (long cell : finished.visited) {
            BlockPos at = BlockPos.of(cell);
            if (level.isLoaded(at) && level.getBlockState(at).is(BwrBlocks.CONTAINMENT_SPRAY_HEADER.get())) sprays.add(at);
        }
        linkedSprayHeaders = List.copyOf(sprays);
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
        // Keep last surveyed membership while the room leaks. It is needed to
        // associate a dual-open airlock with its reactor after a chunk reload,
        // before the interior-cell survey has been rebuilt.
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
            // One boundary scan for the completed rating pass. Neither reactor
            // ticks nor the CC opening readout scan thousands of wall faces.
            penetrationFaces = ratings.keySet().stream()
                    .filter(face -> level.isLoaded(face.wall()) && (
                            level.getBlockState(face.wall()).getBlock() instanceof ContainmentPenetrationBlock
                            || level.getBlockState(face.wall()).getBlock() instanceof ContainmentVaultDoorBlock
                            || level.getBlockState(face.wall()).getBlock() instanceof ContainmentAirlockBlock))
                    .sorted(java.util.Comparator.comparingLong((BoundaryFace face) -> face.wall().asLong())
                            .thenComparingInt(face -> face.normal().ordinal()))
                    .toList();
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
        if (level.getBlockState(weakest.position()).getBlock() instanceof ContainmentVaultDoorBlock door) {
            BlockState state = level.getBlockState(weakest.position());
            ContainmentVaultDoorBlockEntity owner = door.owner(level, weakest.position(), state);
            if (owner != null) owner.jamBlownOpen();
            else return null;
        } else if (level.getBlockState(weakest.position()).getBlock() instanceof ContainmentAirlockBlock airlock) {
            BlockState state = level.getBlockState(weakest.position());
            ContainmentAirlockBlockEntity owner = airlock.owner(level, weakest.position(), state);
            if (owner != null) owner.jamBlownOpen();
            else return null;
        } else if (level.getBlockState(weakest.position()).getBlock() instanceof ContainmentPenetrationBlock) {
            BlockState state = level.getBlockState(weakest.position());
            level.setBlock(weakest.position(), state.setValue(ContainmentPenetrationBlock.RUPTURED, true),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        } else for (BlockPos pos : column.layers()) level.destroyBlock(pos, true);
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
        tag.putLongArray("LinkedSprayHeaders", linkedSprayHeaders.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("PenetrationPositions", penetrationFaces.stream().mapToLong(face -> face.wall().asLong()).toArray());
        tag.putIntArray("PenetrationNormals", penetrationFaces.stream().mapToInt(face -> face.normal().ordinal()).toArray());
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
        linkedSprayHeaders = positions(tag.getLongArray("LinkedSprayHeaders"));
        long[] facePositions = tag.getLongArray("PenetrationPositions");
        int[] faceNormals = tag.getIntArray("PenetrationNormals");
        List<BoundaryFace> savedFaces = new ArrayList<>();
        for (int i = 0; i < Math.min(facePositions.length, faceNormals.length); i++)
            if (faceNormals[i] >= 0 && faceNormals[i] < Direction.values().length)
                savedFaces.add(new BoundaryFace(BlockPos.of(facePositions[i]), Direction.values()[faceNormals[i]]));
        penetrationFaces = List.copyOf(savedFaces);
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
        private final Long2LongOpenHashMap parents = new Long2LongOpenHashMap();
        private final List<BoundaryFace> boundary = new ArrayList<>();
        private int minX, minY, minZ, maxX, maxY, maxZ;
        private BlockPos firstCrossing;
        /** Interior cells holding gas. Water is passable for the seal but is not free volume. */
        private int gasCells = 1; // The controller's own cell.

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
                        controller.escaped(firstCrossing != null ? firstCrossing : traceLeak(level, current));
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
                    parents.put(next.asLong(), current.asLong());
                    if (state.getFluidState().isEmpty()) gasCells++;
                    if (controller.min != null && controller.max != null
                            && firstCrossing == null && !inside(next, controller.min, controller.max))
                        firstCrossing = next.immutable();
                    queue.add(next.immutable());
                    minX = Math.min(minX, next.getX()); maxX = Math.max(maxX, next.getX());
                    minY = Math.min(minY, next.getY()); maxY = Math.max(maxY, next.getY());
                    minZ = Math.min(minZ, next.getZ()); maxZ = Math.max(maxZ, next.getZ());
                    if (visited.size() > limit || maxX - minX + 1 > span
                            || maxY - minY + 1 > span || maxZ - minZ + 1 > span) {
                        controller.escaped(firstCrossing != null ? firstCrossing : traceLeak(level, next));
                        return;
                    }
                }
            }
            if (queue.isEmpty()) controller.completed(this, level);
        }

        /**
         * Without a previous sealed box, the cell where a cap was hit can be far
         * from the gap. Follow the search path from the controller toward the
         * escape and find where it first leaves cover: no wall overhead within
         * the span. The opening is a path cell boxed in by four or more wall
         * faces, a hole through a wall or roof: the covered cell just before
         * that point (a doorway), else the first one after it (a roof hole
         * above), else the nearest one before it. A leak with no uncovered cell
         * (into a cave) reports the first boxed-in cell from the controller.
         */
        private BlockPos traceLeak(ServerLevel level, BlockPos escape) {
            LongArrayList path = new LongArrayList();
            long cell = escape.asLong();
            path.add(cell);
            while (parents.containsKey(cell)) {
                cell = parents.get(cell);
                path.add(cell);
            }
            // The last entry is the controller; lower indices lead outward.
            int span = ContainmentConfig.MAX_SPAN.get();
            int uncovered = -1;
            for (int i = path.size() - 1; i >= 0 && uncovered < 0; i--)
                if (!coveredAbove(level, BlockPos.of(path.getLong(i)), span)) uncovered = i;
            if (uncovered < 0) {
                for (int i = path.size() - 1; i >= 0; i--)
                    if (boxedIn(level, path.getLong(i))) return BlockPos.of(path.getLong(i));
                return escape;
            }
            if (uncovered + 1 < path.size() && boxedIn(level, path.getLong(uncovered + 1)))
                return BlockPos.of(path.getLong(uncovered + 1));
            for (int i = uncovered; i >= 0; i--)
                if (boxedIn(level, path.getLong(i))) return BlockPos.of(path.getLong(i));
            for (int i = uncovered + 2; i < path.size(); i++)
                if (boxedIn(level, path.getLong(i))) return BlockPos.of(path.getLong(i));
            return BlockPos.of(path.getLong(uncovered));
        }

        /** Wall blocks only, not sky light: light lags block edits by several ticks. */
        private static boolean coveredAbove(ServerLevel level, BlockPos pos, int span) {
            int top = Math.min(level.getMaxBuildHeight() - 1, pos.getY() + span);
            BlockPos.MutableBlockPos cursor = pos.mutable();
            for (int y = pos.getY() + 1; y <= top; y++) {
                cursor.setY(y);
                if (ContainmentWall.isWall(level, cursor, level.getBlockState(cursor))) return true;
            }
            return false;
        }

        private static boolean boxedIn(ServerLevel level, long packed) {
            return wallFaces(level, BlockPos.of(packed)) >= 4;
        }

        private static int wallFaces(ServerLevel level, BlockPos pos) {
            int walls = 0;
            for (Direction direction : Direction.values()) {
                BlockPos side = pos.relative(direction);
                if (level.isLoaded(side) && ContainmentWall.isWall(level, side, level.getBlockState(side))) walls++;
            }
            return walls;
        }
    }
}
