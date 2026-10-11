package dev.bwr.mod.accident;

import dev.bwr.core.accident.SevereAccident;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrBlocks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The visible half of molten core-concrete interaction: corium blocks under a
 * failed vessel, following {@link SevereAccident}'s ablation depth down.
 *
 * <h2>Geometry</h2>
 * Anchored once, at the first update after lower head failure, and saved, so a
 * vessel rebuilt afterwards does not move the pool. The floor is the first
 * full-cube block below the vessel shell in the centre column, skipping BWR
 * hardware (the control rod drives hang there) and block entities, at most
 * {@link #MAX_FLOOR_SEARCH} blocks down. The footprint is a disc centred under
 * the vessel with radius {@code min(interior width, depth) / 2 - 0.5}: the
 * vessel's inner radius, kept inside the shell's projection. Layers, in order:
 * <ol start="0">
 *   <li>the pool, one block above the floor, at full radius; it only fills air
 *       and replaceable blocks, so it puddles around drives and walls;</li>
 *   <li>to 3. the floor and the two blocks below it, one layer per
 *       {@link #METRES_PER_BLOCK} of ablation (rounded up, so the first
 *       millimetre melts the surface), each {@link #LAYER_SHRINK_BLOCKS}
 *       narrower than the one above: the cavity tapers like a real ablation
 *       front. Three layers is the full 3 m basemat;</li>
 *   <li>basemat melt-through: the single centre column, one block deeper.</li>
 * </ol>
 *
 * <h2>Cooling</h2>
 * The core has no ex-vessel temperature ({@code getDebrisTemperatureC} is the
 * in-vessel bed and reads zero once the head has failed), so quenched is
 * defined by what the core does report: the pool is hot while
 * {@link SevereAccident#isCoreConcreteInteractionActive()}, and freezes once
 * that has been false for {@link #COOLING_DELAY_TICKS} consecutive ticks —
 * water on the floor removing all the decay heat, or the melt having gone
 * through the basemat, where the model stops attacking the cavity. Freezing is
 * permanent: every block placed afterwards is placed cold, and nothing ever
 * turns hot again.
 *
 * <h2>Rules</h2>
 * Never replaces unbreakable blocks, block entities or BWR blocks (formed or
 * not: every multiblock member is a {@code bwr:} block, and an unformed one is
 * still somebody's plant). Never loads a chunk: an unloaded position is put
 * aside and retried later. Work is a bounded number of positions per tick and
 * progress is two indices into the fixed position list, saved, so a reload
 * neither places a block twice nor forgets one.
 */
public final class CoriumPool {

    /** Deepest floor search below the vessel shell, blocks. */
    public static final int MAX_FLOOR_SEARCH = 24;

    /** Concrete ablated per layer of floor blocks converted, m. */
    public static final double METRES_PER_BLOCK = 1.0;

    /** Floor layers at full basemat depth. */
    public static final int FLOOR_LAYERS =
            (int) Math.round(SevereAccident.BASEMAT_THICKNESS_M / METRES_PER_BLOCK);

    /** Each floor layer is this much narrower than the one above, blocks. */
    public static final double LAYER_SHRINK_BLOCKS = 0.75;

    /** Narrowest footprint, blocks; still includes the centre column. */
    public static final double MIN_RADIUS_BLOCKS = 0.75;

    /** Widest footprint ever accepted from a save, blocks. */
    public static final double MAX_RADIUS_BLOCKS = 16.0;

    /** Ticks without concrete attack before the pool freezes (one minute). */
    public static final int COOLING_DELAY_TICKS = 1200;

    /** Ticks between floor searches when none was found. */
    public static final int FLOOR_RETRY_TICKS = 100;

    /** Positions held back for unloaded chunks; past this the sweep waits. */
    public static final int MAX_DEFERRED = 4096;

    private static final int LAYER_COUNT = FLOOR_LAYERS + 2;
    private static final int SAVE_VERSION = 1;

    private boolean anchored;
    private int centreX2;
    private int centreZ2;
    private int floorY;
    private double radius;
    private int placed;
    private int cooled;
    private int quenchTicks;
    private boolean solidified;
    private final LongLinkedOpenHashSet deferredPlace = new LongLinkedOpenHashSet();
    private final LongLinkedOpenHashSet deferredCool = new LongLinkedOpenHashSet();

    private int floorRetry;
    private List<List<BlockPos>> layers;

    /**
     * One tick of work.
     *
     * @param interiorMin  vessel interior, for the first anchoring only
     * @param interiorMax  vessel interior, for the first anchoring only
     * @param alterWorld   false: keep track of the accident, change nothing
     * @param budget       block positions this call may examine
     * @return true while there is work left to do
     */
    public boolean update(ServerLevel level, BlockPos interiorMin, BlockPos interiorMax,
                          SevereAccident accident, boolean alterWorld, int budget) {
        if (!accident.isLowerHeadFailed()) {
            return false;
        }
        if (!solidified) {
            if (accident.isCoreConcreteInteractionActive()) {
                quenchTicks = 0;
            } else if (++quenchTicks >= COOLING_DELAY_TICKS) {
                solidified = true;
            }
        }
        if (!alterWorld) {
            return false;
        }
        int work = Math.max(1, budget);
        if (!anchored) {
            if (floorRetry > 0) {
                floorRetry--;
                return true;
            }
            if (interiorMin == null || interiorMax == null || !anchor(level, interiorMin, interiorMax)) {
                floorRetry = FLOOR_RETRY_TICKS;
                return true;
            }
            work = Math.max(1, work - MAX_FLOOR_SEARCH);
        }

        int wanted = positionsUpTo(wantedLayers(accident));
        work -= retry(level, deferredPlace, Math.max(1, work / 4), true);
        while (work > 0 && placed < wanted) {
            if (!place(level, positionAt(placed))) {
                break;
            }
            placed++;
            work--;
        }
        if (solidified) {
            work -= retry(level, deferredCool, Math.max(1, work / 4), false);
            while (work > 0 && cooled < placed) {
                if (!cool(level, positionAt(cooled))) {
                    break;
                }
                cooled++;
                work--;
            }
        }
        return placed < wanted || (solidified && cooled < placed)
                || !deferredPlace.isEmpty() || !deferredCool.isEmpty();
    }

    /** Layers the accident has reached: pool, floor layers by depth, melt-through. */
    public static int wantedLayers(SevereAccident accident) {
        if (!accident.isLowerHeadFailed()) {
            return 0;
        }
        if (accident.isBasematFailed()) {
            return LAYER_COUNT;
        }
        double blocks = Math.ceil(accident.getAblationDepthM() / METRES_PER_BLOCK - 1.0e-6);
        return 1 + (int) Math.max(0, Math.min(FLOOR_LAYERS, blocks));
    }

    // ---------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------

    private boolean anchor(ServerLevel level, BlockPos interiorMin, BlockPos interiorMax) {
        int cx2 = interiorMin.getX() + interiorMax.getX() + 1;
        int cz2 = interiorMin.getZ() + interiorMax.getZ() + 1;
        int span = Math.min(interiorMax.getX() - interiorMin.getX(), interiorMax.getZ() - interiorMin.getZ()) + 1;
        int x = Math.floorDiv(cx2, 2), z = Math.floorDiv(cz2, 2);
        // interiorMin.y - 1 is the bottom head; the drives hang below that.
        int top = interiorMin.getY() - 2;
        int bottom = Math.max(level.getMinBuildHeight(), top - MAX_FLOOR_SEARCH + 1);
        for (int y = top; y >= bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.isLoaded(p)) {
                return false;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir() || isBwr(s) || s.hasBlockEntity() || !s.isCollisionShapeFullBlock(level, p)) {
                continue;
            }
            anchored = true;
            centreX2 = cx2;
            centreZ2 = cz2;
            floorY = y;
            radius = clampRadius(span / 2.0 - 0.5);
            layers = null;
            return true;
        }
        return false;
    }

    /** Positions of one layer, nearest the centre first. */
    public List<BlockPos> layer(int index) {
        return layers().get(index);
    }

    private List<List<BlockPos>> layers() {
        if (layers == null) {
            List<List<BlockPos>> built = new ArrayList<>(LAYER_COUNT);
            built.add(disc(floorY + 1, radius));
            for (int i = 0; i < FLOOR_LAYERS; i++) {
                built.add(disc(floorY - i, Math.max(MIN_RADIUS_BLOCKS, radius - LAYER_SHRINK_BLOCKS * i)));
            }
            built.add(List.of(centre().below(FLOOR_LAYERS)));
            layers = built;
        }
        return layers;
    }

    private List<BlockPos> disc(int y, double r) {
        double cx = centreX2 / 2.0, cz = centreZ2 / 2.0;
        int x0 = Math.floorDiv(centreX2, 2), z0 = Math.floorDiv(centreZ2, 2);
        int reach = (int) Math.ceil(r) + 1;
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double ex = x0 + dx + 0.5 - cx, ez = z0 + dz + 0.5 - cz;
                if (ex * ex + ez * ez <= r * r + 1.0e-9) {
                    out.add(new BlockPos(x0 + dx, y, z0 + dz));
                }
            }
        }
        Comparator<BlockPos> order = Comparator.comparingDouble(p -> {
            double ex = p.getX() + 0.5 - cx, ez = p.getZ() + 0.5 - cz;
            return ex * ex + ez * ez;
        });
        out.sort(order.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return List.copyOf(out);
    }

    private int positionsUpTo(int layerCount) {
        int total = 0;
        for (int i = 0; i < Math.min(layerCount, LAYER_COUNT); i++) {
            total += layers().get(i).size();
        }
        return total;
    }

    private BlockPos positionAt(int index) {
        for (List<BlockPos> layer : layers()) {
            if (index < layer.size()) {
                return layer.get(index);
            }
            index -= layer.size();
        }
        throw new IndexOutOfBoundsException(index);
    }

    // ---------------------------------------------------------------
    // Block changes
    // ---------------------------------------------------------------

    /** False only when the position could not even be put aside. */
    private boolean place(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return defer(deferredPlace, pos);
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(BwrBlocks.CORIUM.get()) || isProtected(level, pos, state)) {
            return true;
        }
        if (pos.getY() > floorY && !state.isAir() && !state.canBeReplaced()) {
            return true; // The pool flows around things; it does not eat walls.
        }
        level.setBlock(pos, BwrBlocks.CORIUM.get().defaultBlockState().setValue(CoriumBlock.HOT, !solidified),
                Block.UPDATE_ALL);
        return true;
    }

    private boolean cool(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return defer(deferredCool, pos);
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(BwrBlocks.CORIUM.get()) && state.getValue(CoriumBlock.HOT)) {
            level.setBlock(pos, state.setValue(CoriumBlock.HOT, false), Block.UPDATE_ALL);
        }
        return true;
    }

    private static boolean defer(LongLinkedOpenHashSet set, BlockPos pos) {
        if (set.size() >= MAX_DEFERRED && !set.contains(pos.asLong())) {
            return false;
        }
        set.add(pos.asLong());
        return true;
    }

    /** Retry put-aside positions, oldest first, rotating any still unloaded. Returns work spent. */
    private int retry(ServerLevel level, LongLinkedOpenHashSet set, int limit, boolean placing) {
        int spent = 0;
        int rounds = Math.min(limit, set.size());
        for (int i = 0; i < rounds; i++) {
            long key = set.removeFirstLong();
            BlockPos pos = BlockPos.of(key);
            spent++;
            if (!level.isLoaded(pos)) {
                set.add(key);
                continue;
            }
            if (placing) {
                place(level, pos);
            } else {
                cool(level, pos);
            }
        }
        return spent;
    }

    /** Blocks corium must never replace. */
    public static boolean isProtected(Level level, BlockPos pos, BlockState state) {
        return state.getDestroySpeed(level, pos) < 0.0f
                || state.hasBlockEntity()
                || (isBwr(state) && !state.is(BwrBlocks.CORIUM.get()));
    }

    private static boolean isBwr(BlockState state) {
        return BwrMod.MOD_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    private static double clampRadius(double r) {
        return Double.isFinite(r) ? Math.max(MIN_RADIUS_BLOCKS, Math.min(MAX_RADIUS_BLOCKS, r)) : MIN_RADIUS_BLOCKS;
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    public boolean isAnchored() {
        return anchored;
    }

    public boolean isSolidified() {
        return solidified;
    }

    /** Positions swept so far, including any skipped or put aside. */
    public int placedCount() {
        return placed;
    }

    public int cooledCount() {
        return cooled;
    }

    public int floorY() {
        return floorY;
    }

    public double radius() {
        return radius;
    }

    /** The floor block in the centre column. */
    public BlockPos centre() {
        return new BlockPos(Math.floorDiv(centreX2, 2), floorY, Math.floorDiv(centreZ2, 2));
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    public void write(CompoundTag t) {
        t.putInt("Version", SAVE_VERSION);
        t.putBoolean("Anchored", anchored);
        t.putInt("CentreX2", centreX2);
        t.putInt("CentreZ2", centreZ2);
        t.putInt("FloorY", floorY);
        t.putDouble("Radius", radius);
        t.putInt("Placed", placed);
        t.putInt("Cooled", cooled);
        t.putInt("QuenchTicks", quenchTicks);
        t.putBoolean("Solidified", solidified);
        t.putLongArray("DeferredPlace", deferredPlace.toLongArray());
        t.putLongArray("DeferredCool", deferredCool.toLongArray());
    }

    /** An empty tag is an old save, or no accident: nothing placed yet. */
    public void read(CompoundTag t) {
        anchored = t.getBoolean("Anchored");
        centreX2 = t.getInt("CentreX2");
        centreZ2 = t.getInt("CentreZ2");
        floorY = t.getInt("FloorY");
        radius = clampRadius(t.getDouble("Radius"));
        layers = null;
        floorRetry = 0;
        int total = anchored ? positionsUpTo(LAYER_COUNT) : 0;
        placed = Math.max(0, Math.min(total, t.getInt("Placed")));
        cooled = Math.max(0, Math.min(placed, t.getInt("Cooled")));
        quenchTicks = Math.max(0, t.getInt("QuenchTicks"));
        solidified = t.getBoolean("Solidified");
        deferredPlace.clear();
        deferredCool.clear();
        for (long p : t.getLongArray("DeferredPlace")) {
            if (deferredPlace.size() < MAX_DEFERRED) deferredPlace.add(p);
        }
        for (long p : t.getLongArray("DeferredCool")) {
            if (deferredCool.size() < MAX_DEFERRED) deferredCool.add(p);
        }
    }
}
