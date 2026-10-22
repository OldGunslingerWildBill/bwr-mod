package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Networks that cross chunk borders: what happens to a running steam line or water line when one chunk of it is not
 * there, and when it comes back.
 *
 * <p>Every test lays a straight line of blocks along X across several chunks, starting at a chunk border, so the line
 * crosses a border every sixteen blocks. A chunk is made unavailable the only way the server does it, by dropping the
 * ticket that kept it loaded and waiting until the chunk object is gone ({@link ChunkLab} explains the distances: a
 * forced chunk keeps everything within two chunks loaded, so a chunk only unloads when every forced chunk is at least
 * three away, and a line that must lose a middle chunk is seven chunks long). The tests check, in order: nothing of
 * ours loaded a chunk by reading a block in it, the machine in the missing chunk or the cut line stopped delivering,
 * nothing was lost or created, no network was rebuilt every tick, nothing of ours logged a warning, and after the chunk
 * comes back the network forms again by itself.
 */
@GameTestHolder("bwrtick")
@PrefixGameTestTemplate(false)
public final class ChunkLinkTests {
    private ChunkLinkTests() {
    }

    /** Chunk-aligned (a multiple of 16) so that chunk index n starts at X0 + 16 n. */
    static final int X0 = 320_000;
    static final int Z0 = 320_000;
    static final int SLOT_Z = 1008;
    static final int Y = 195;

    // ------------------------------------------------------------------ the shared rig

    /** A 5 x 5 reactor in chunk 0 of a row of chunks, and the bookkeeping to take chunks of the row away. */
    private static class Rig {
        final ServerLevel level;
        final ChunkLab lab;
        final int x0;
        final int z0;
        final int chunks;
        final BlockPos min;
        final BlockPos controller;
        final int lineZ;

        Rig(GameTestHelper helper, ChunkLab lab, int x0, int z0, int chunks) {
            this.level = helper.getLevel();
            this.lab = lab;
            this.x0 = x0;
            this.z0 = z0;
            this.chunks = chunks;
            this.min = new BlockPos(x0 + 4, 190, z0 + 5);
            this.controller = min.offset(-1, 3, 2);
            this.lineZ = z0 + 7;
        }

        /** Absolute chunk index of the n-th chunk of the row (0 holds the reactor). */
        int chunkX(int n) {
            return (x0 >> 4) + n;
        }

        int row() {
            return z0 >> 4;
        }

        BlockPos line(int x) {
            return new BlockPos(x, Y, lineZ);
        }

        void forceRow() {
            lab.forceRect(chunkX(-1), row(), chunkX(chunks), row());
        }

        void clearArea() {
            BlockPos lo = new BlockPos(x0 - 16, 180, z0 - 16);
            BlockPos hi = new BlockPos(x0 + 16 * chunks + 16, 215, z0 + 31);
            FixtureCleanup.drainFuel(level, lo, hi);
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                if (level.isLoaded(p) && !level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            FixtureCleanup.discardItems(level, lo, hi);
        }

        void buildReactor() throws Exception {
            var be = CompactCoreRegressionTests.build(level, min, 5, 5, 8, false);
            if (!be.isFormed()) {
                throw new IllegalStateException("fixture reactor did not form: " + be.statusLines());
            }
            for (int position : be.corePositions()) {
                be.loadAssembly(position, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
            }
            be.core().initialiseAtTotalPowerFraction(0.8);
            be.rods().setCore(be.core());
        }

        /** The reactor controller, looked up by position because a reload replaces the object. */
        ReactorControllerBlockEntity reactor() {
            return lab.peek(controller) instanceof ReactorControllerBlockEntity r ? r : null;
        }

        void remove() {
            clearArea();
        }
    }

    // ------------------------------------------------------------------ the steam fixture

    /** The reactor's east nozzle and a tube line east to a turbine outlet, optionally with an isolation valve in it. */
    private static final class SteamLine extends Rig {
        final BlockPos nozzle;
        final BlockPos outlet;
        final BlockPos msiv;
        double exportedMb;
        double producedMb;
        long lastEnergised = Long.MIN_VALUE;

        SteamLine(GameTestHelper helper, ChunkLab lab, int x0, int z0, int chunks, boolean withMsiv) {
            super(helper, lab, x0, z0, chunks);
            this.nozzle = new BlockPos(x0 + 9, Y, lineZ);
            this.outlet = new BlockPos(x0 + 16 * (chunks - 1) + 8, Y, lineZ);
            this.msiv = withMsiv ? new BlockPos(x0 + 16 * (chunks / 2) + 7, Y, lineZ) : null;
        }

        void build() throws Exception {
            forceRow();
            clearArea();
            buildReactor();
            level.setBlock(nozzle, BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(), 3);
            for (int x = nozzle.getX() + 1; x < outlet.getX(); x++) {
                if (msiv != null && x == msiv.getX()) {
                    continue;
                }
                level.setBlock(line(x), BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(), 3);
            }
            if (msiv != null) {
                var block = BwrBlocks.MSIV.get();
                var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.EAST);
                level.setBlock(msiv, state, 3);
                block.setPlacedBy(level, msiv, state, null, new ItemStack(block));
            }
            level.setBlock(outlet, BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(), 3);
            outlet().setCommandedFlowKgPerS(250);
            reactor().markStructureDirty();
        }

        TurbineSteamOutletBlockEntity outlet() {
            return lab.peek(outlet) instanceof TurbineSteamOutletBlockEntity o ? o : null;
        }

        /** Runs every tick: stands in for the grid and for whatever takes the steam, and keeps the ledger. */
        void meter() {
            long now = level.getGameTime();
            if (msiv != null && (lastEnergised == Long.MIN_VALUE || now - lastEnergised >= 20)
                    && lab.peek(msiv) instanceof dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity valve) {
                lastEnergised = now;
                valve.energy().receiveEnergy(20_000, false);
            }
            var o = outlet();
            if (o != null) {
                exportedMb += o.drainMilliBuckets(Long.MAX_VALUE, false);
            }
            if (lab.peek(nozzle) instanceof RpvSteamOutletBlockEntity n && n.isPartOfFormedReactor()) {
                producedMb += n.getLastFlowKgPerS() * 0.05 * TurbineSteamOutletBlockEntity.MILLIBUCKETS_PER_KILOGRAM;
            }
        }

        boolean delivering() {
            var o = outlet();
            return o != null && o.isAttached() && o.isNozzleFed();
        }

        String describe() {
            var o = outlet();
            var r = reactor();
            return "outlet=" + (o == null ? "missing" : "attached=" + o.isAttached() + " nozzleFed=" + o.isNozzleFed()
                    + " nozzles=" + o.getFeedNozzleCount() + " buffer=" + o.getBufferedMilliBuckets())
                    + " reactor=" + (r == null ? "missing" : "formed=" + r.isFormed()) + " exported=" + exportedMb
                    + " produced=" + producedMb;
        }
    }

    /**
     * Counts how many different cached graphs one network went through: the pipe cache hands out the same graph object
     * until something invalidates it, so a network rebuilt every tick shows a new object every tick. (The global build
     * counter would also count the other tests running beside this one.)
     */
    private static final class GraphWatch {
        final java.util.Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        private final java.util.function.Supplier<Object> graph;

        GraphWatch(java.util.function.Supplier<Object> graph) {
            this.graph = graph;
        }

        void sample() {
            Object g = graph.get();
            if (g != null) {
                seen.put(g, Boolean.TRUE);
            }
        }

        void reset() {
            seen.clear();
        }

        int distinct() {
            return seen.size();
        }
    }

    private static void noSyncLoads(ChunkLab lab, String phase) {
        if (!lab.syncLoads.isEmpty()) {
            throw new AssertionError(phase + ": our code loaded chunks by reading blocks in them: " + lab.syncLoads);
        }
    }

    private static void quiet(LogWatch log, String phase) {
        if (!log.problems.isEmpty()) {
            throw new AssertionError(phase + ": our code logged warnings or errors: " + log.problems.subList(0, Math.min(5, log.problems.size())));
        }
    }

    // ------------------------------------------------------------------ steam: a chunk goes away and comes back

    /**
     * The outlet is in the last chunk of a four chunk line. Only the reactor chunk keeps a ticket, so the outlet
     * chunk unloads while the three chunks before it stay loaded (chunk 3 is three away from chunk 0).
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1500)
    public static void steamLineTailChunkUnloadsAndReloads(GameTestHelper h) {
        steamLineScenario(h, 0, 4, false, new int[]{0}, new int[]{3});
    }

    /**
     * Seven chunks, the reactor in the first and the outlet in the last, an isolation valve in the fourth. Only the
     * two end chunks keep a ticket, so the middle chunk (with the valve) unloads and the line is cut in the middle.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1700)
    public static void steamLineMiddleChunkUnloadsWithValveInIt(GameTestHelper h) {
        steamLineScenario(h, 1, 7, true, new int[]{0, 6}, new int[]{3});
    }

    private static void steamLineScenario(GameTestHelper h, int slot, int chunks, boolean withMsiv, int[] keep, int[] gone) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        SteamLine line = new SteamLine(h, lab, X0, Z0 + slot * SLOT_Z, chunks, withMsiv);
        int row = line.row();
        double[] mark = new double[2];
        GraphWatch graphs = new GraphWatch(() -> PipeTopology.cached(line.level, line.outlet, null, true, false));
        Script script = new Script(h, 1500, () -> {
            line.meter();
            graphs.sample();
        }, () -> {
            try {
                line.remove();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            try {
                line.build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.then(150, () -> {
            h.assertTrue(line.delivering(), "steam line never connected: " + line.describe());
            h.assertTrue(line.exportedMb > 0, "no steam reached the outlet over 150 ticks: " + line.describe());
            mark[0] = line.exportedMb;
            lab.syncLoads.clear();
        });
        script.then(40, () -> {
            h.assertTrue(line.exportedMb > mark[0], "steady line stopped delivering: " + line.describe());
            for (int c = -1; c <= chunks; c++) {
                boolean kept = false;
                for (int k : keep) {
                    kept |= k == c;
                }
                if (!kept) {
                    lab.unforce(line.chunkX(c), row);
                }
            }
        });
        for (int g : gone) {
            script.await(() -> "chunk " + g + " to unload: " + lab.describe(line.chunkX(g), row), 400, () -> !lab.loaded(line.chunkX(g), row));
        }
        script.then(1, () -> {
            mark[1] = line.exportedMb;
            graphs.reset();
        });
        script.then(120, () -> {
            for (int g : gone) {
                h.assertTrue(!lab.loaded(line.chunkX(g), row), "chunk " + g + " was loaded again by the running plant");
            }
            noSyncLoads(lab, "while the chunk is missing");
            quiet(log, "while the chunk is missing");
            h.assertTrue(line.exportedMb - mark[1] < 1.0,
                    "steam still arrived at the outlet through a missing chunk: " + (line.exportedMb - mark[1]) + " mB");
            h.assertTrue(graphs.distinct() <= 4, "the steam line was rebuilt " + graphs.distinct() + " times in 120 ticks with nothing changing");
            h.assertTrue(line.reactor() != null && line.reactor().isFormed(), "the reactor lost its structure while a far chunk was missing");
        });
        script.then(1, () -> {
            // Ticket only: the chunk must come back the way a returning player brings it back, through the ticket
            // system with the plant running, not through a synchronous load by the test.
            for (int c = -1; c <= chunks; c++) {
                lab.request(line.chunkX(c), row);
            }
            mark[0] = line.exportedMb;
        });
        script.await(() -> "the line to deliver again after the chunk came back: " + line.describe(), 300,
                () -> line.delivering() && line.exportedMb - mark[0] > 100);
        script.then(100, () -> {
            h.assertTrue(line.delivering(), "steam line did not re-form after the chunk reloaded: " + line.describe());
            noSyncLoads(lab, "after the reload");
            quiet(log, "after the reload");
            // Nothing created: what reached the outlet cannot exceed what the nozzle passed (slack for the buffer).
            h.assertTrue(line.exportedMb <= line.producedMb * 1.02 + 2_000,
                    "outlet received " + line.exportedMb + " mB but the nozzle passed only " + line.producedMb);
        });
        script.start();
    }

    /**
     * The mirror image of the tail test: the turbine outlet is in a chunk that keeps its ticket and the reactor, 100
     * blocks away at the other end of the line, is not. The outlet knows its reactor by position; it must treat the
     * reactor as absent while its chunk is, instead of reading it (which loads the chunk and holds it), must not draw
     * steam from a reactor it cannot reach, and must find the reactor again when the chunk comes back.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1700)
    public static void steamOutletDoesNotLoadOrFeedFromAReactorChunkThatIsGone(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        SteamLine line = new SteamLine(h, lab, X0, Z0 + 6 * SLOT_Z, 7, false);
        int row = line.row();
        double[] mark = new double[1];
        Script script = new Script(h, 1700, line::meter, () -> {
            try {
                line.remove();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            try {
                line.build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.await(() -> "the line to deliver: " + line.describe(), 300, () -> line.delivering() && line.exportedMb > 100);
        script.then(1, () -> {
            lab.syncLoads.clear();
            for (int c = -1; c <= 5; c++) {
                lab.unforce(line.chunkX(c), row);
            }
        });
        script.await(() -> "the reactor chunk to unload: " + lab.describe(line.chunkX(0), row), 400, () -> !lab.loaded(line.chunkX(0), row));
        script.then(5, () -> mark[0] = line.exportedMb);
        script.then(120, () -> {
            h.assertTrue(!lab.loaded(line.chunkX(0), row),
                    "the reactor chunk was loaded again by a block entity 100 blocks away: " + lab.describe(line.chunkX(0), row));
            noSyncLoads(lab, "outlet with its reactor chunk gone");
            quiet(log, "outlet with its reactor chunk gone");
            h.assertTrue(line.exportedMb - mark[0] < 1.0,
                    "the outlet drew steam from a reactor it cannot reach: " + (line.exportedMb - mark[0]) + " mB");
            for (int c = -1; c <= 5; c++) {
                lab.request(line.chunkX(c), row);
            }
            mark[0] = line.exportedMb;
        });
        script.await(() -> "the line to deliver again: " + line.describe(), 400, () -> line.delivering() && line.exportedMb - mark[0] > 100);
        script.then(20, () -> {
            noSyncLoads(lab, "reactor chunk back");
            quiet(log, "reactor chunk back");
        });
        script.start();
    }

    // ------------------------------------------------------------------ steam: break and place at a chunk edge

    /**
     * Breaks the tube on each side of a chunk border and puts it back. The line must stop delivering while it is cut
     * and deliver again once it is whole, and the two tubes at the border must grow arms toward each other.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1200)
    public static void steamLineBrokenAndRepairedAtAChunkEdge(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        SteamLine line = new SteamLine(h, lab, X0, Z0 + 2 * SLOT_Z, 3, false);
        int edge = line.x0 + 16; // first block of chunk 1
        double[] mark = new double[1];
        Script script = new Script(h, 1200, line::meter, () -> {
            try {
                line.remove();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            try {
                line.build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.await(() -> "the line to deliver: " + line.describe(), 300, () -> line.delivering() && line.exportedMb > 100);
        script.then(1, () -> {
            lab.syncLoads.clear();
            BlockState west = line.level.getBlockState(line.line(edge - 1));
            BlockState east = line.level.getBlockState(line.line(edge));
            h.assertTrue(west.is(BwrBlocks.PRESSURISED_TUBE.get()) && east.is(BwrBlocks.PRESSURISED_TUBE.get()), "border tubes missing");
            line.level.destroyBlock(line.line(edge - 1), false);
        });
        // What the outlet had already buffered when the line broke still leaves; after that nothing may arrive.
        script.then(5, () -> mark[0] = line.exportedMb);
        script.then(115, () -> {
            h.assertTrue(line.exportedMb - mark[0] < 1.0,
                    "steam still arrives through a tube broken on the west side of the chunk edge: " + line.describe());
            h.assertTrue(!line.level.getBlockState(line.line(edge)).getValue(PipeBlock.WEST), "orphaned arm toward the gap");
            var tube = BwrBlocks.PRESSURISED_TUBE.get();
            line.level.setBlock(line.line(edge - 1), tube.stateWithConnections(line.level, line.line(edge - 1)), 3);
            mark[0] = line.exportedMb;
        });
        script.await(() -> "the repaired line to deliver: " + line.describe(), 200,
                () -> line.delivering() && line.exportedMb - mark[0] > 50);
        script.then(1, () -> {
            h.assertTrue(line.level.getBlockState(line.line(edge - 1)).getValue(PipeBlock.EAST), "no arm toward the east side of the chunk edge");
            h.assertTrue(line.level.getBlockState(line.line(edge)).getValue(PipeBlock.WEST), "no arm toward the west side of the chunk edge");
            line.level.destroyBlock(line.line(edge), false);
        });
        script.then(5, () -> mark[0] = line.exportedMb);
        script.then(115, () -> {
            h.assertTrue(line.exportedMb - mark[0] < 1.0, "steam still arrives through a tube broken on the east side of the chunk edge");
            var tube = BwrBlocks.PRESSURISED_TUBE.get();
            line.level.setBlock(line.line(edge), tube.stateWithConnections(line.level, line.line(edge)), 3);
            mark[0] = line.exportedMb;
        });
        script.await(() -> "the line to deliver after the second repair: " + line.describe(), 200,
                () -> line.delivering() && line.exportedMb - mark[0] > 50);
        script.then(1, () -> {
            noSyncLoads(lab, "editing at the chunk edge");
            quiet(log, "editing at the chunk edge");
        });
        script.start();
    }

    // ------------------------------------------------------------------ steam: every chunk saved and read back

    /**
     * Unloads every chunk of the plant (reactor, line and outlet) at once, so the whole network is written to the
     * chunk files, then brings them back by ticket. The reactor must re-form with the same fuel and about the same
     * pressure (nothing ticked in between), the line must re-form by itself, and nothing may be duplicated.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1500)
    public static void steamNetworkSplitAcrossChunksSurvivesSaveAndReload(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        SteamLine line = new SteamLine(h, lab, X0, Z0 + 3 * SLOT_Z, 4, false);
        int[] fuel = new int[1];
        double[] pressure = new double[1];
        double[] mark = new double[1];
        double[] restoredPsi = new double[1];
        Script script = new Script(h, 1500, line::meter, () -> {
            try {
                line.remove();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            try {
                line.build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.await(() -> "the line to deliver: " + line.describe(), 300, () -> line.delivering() && line.exportedMb > 100);
        script.then(1, () -> {
            lab.syncLoads.clear();
            fuel[0] = line.reactor().core().getCoreLoading().loadedAssemblyCount();
            pressure[0] = line.reactor().core().getPressurePsig();
            h.assertTrue(fuel[0] > 0, "fixture reactor has no fuel");
            for (int c = -1; c <= line.chunks; c++) {
                lab.unforce(line.chunkX(c), line.row());
            }
        });
        script.await("every chunk of the plant to unload", 600, () -> {
            for (int c = -1; c <= line.chunks; c++) {
                if (lab.loaded(line.chunkX(c), line.row())) {
                    return false;
                }
            }
            return true;
        });
        script.then(40, () -> {
            // From the far end back: the chunks are brought back in the order they are asked for, and the reactor (chunk 0)
            // starts to run, and to climb, as soon as its own entities are back, so asking for it last keeps the outlet
            // from being the late one.
            for (int c = line.chunks; c >= -1; c--) {
                lab.request(line.chunkX(c), line.row());
            }
        });
        // The plant is climbing in pressure (about 1.2 psi a tick in this fixture) before it is saved and after it is
        // read back, so what the 60 psi below measures is how many ticks the reload leaves the parts of the line missing:
        // each chunk's entities come back from disk on their own schedule and a block entity does not tick before that.
        // Paced, that is a tick or two; at the server's unthrottled half a millisecond a tick it was 15 to 40.
        script.awaitChunks(() -> "the reactor to be back and formed: " + line.describe(), 600,
                () -> line.reactor() != null && line.reactor().isFormed());
        script.then(0, () -> {
            double restored = line.reactor().core().getPressurePsig();
            restoredPsi[0] = restored;
            h.assertTrue(Math.abs(restored - pressure[0]) < 10,
                    "the reactor did not come back at the pressure it was saved at: " + pressure[0] + " -> " + restored);
        });
        script.then(1, () -> mark[0] = line.exportedMb);
        script.awaitChunks(() -> "the line to deliver again: " + line.describe(), 400,
                () -> line.delivering() && line.exportedMb - mark[0] > 100);
        script.then(20, () -> {
            var r = line.reactor();
            h.assertTrue(r.core().getCoreLoading().loadedAssemblyCount() == fuel[0],
                    "fuel changed across the reload: " + fuel[0] + " -> " + r.core().getCoreLoading().loadedAssemblyCount());
            com.mojang.logging.LogUtils.getLogger().info("CHUNK LINK RELOAD: pressure saved at {} psi, {} psi on the first tick of the reloaded reactor, {} psi after delivering again",
                    pressure[0], restoredPsi[0], r.core().getPressurePsig());
            h.assertTrue(Math.abs(r.core().getPressurePsig() - pressure[0]) < 60,
                    "pressure jumped across the reload: " + pressure[0] + " -> " + r.core().getPressurePsig());
            int popped = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(line.min).inflate(40)).size();
            h.assertTrue(popped == 0, popped + " items dropped while the plant was saved and reloaded");
            noSyncLoads(lab, "save and reload");
            quiet(log, "save and reload");
        });
        script.start();
    }

    // ------------------------------------------------------------------ the world border and the world's edge

    /**
     * A reactor inside the world border and a steam line that crosses it into a chunk beyond it, at the largest
     * coordinates a block can have. Block entities outside the border do not tick, so the outlet is inert; the
     * reactor and the part of the line inside must keep working without errors, without loading chunks and without
     * rebuilding the line every tick.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 800)
    public static void steamLineAcrossTheWorldBorderAtTheEdgeOfTheWorld(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        // 29,999,968 is a chunk start; the default border sits at 29,999,984, in the middle of chunk 1.
        SteamLine line = new SteamLine(h, lab, 29_999_968, 1_000_000, 2, false);
        GraphWatch graphs = new GraphWatch(() -> PipeTopology.cached(line.level, line.outlet, null, true, false));
        Script script = new Script(h, 800, () -> {
            line.meter();
            graphs.sample();
        }, () -> {
            try {
                line.remove();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            try {
                line.build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            h.assertTrue(line.level.getWorldBorder().getMaxX() < line.outlet.getX(), "the outlet is not beyond the world border");
        });
        script.then(200, () -> {
            h.assertTrue(line.reactor() != null && line.reactor().isFormed(), "reactor did not form at the edge of the world");
            lab.syncLoads.clear();
            graphs.reset();
        });
        script.then(200, () -> {
            h.assertTrue(graphs.distinct() <= 4, "the line was rebuilt " + graphs.distinct() + " times in 200 ticks beyond the world border");
            h.assertTrue(line.reactor().isFormed(), "reactor left unformed by a line that ends beyond the world border");
            noSyncLoads(lab, "world border");
            quiet(log, "world border");
        });
        script.start();
    }

    // ------------------------------------------------------------------ water: nothing lost or made

    /**
     * A water pipe 97 blocks long from chunk 0 to a condensate tank in chunk 6; a source pushes into the first pipe
     * every tick. With the middle chunk unloaded nothing may be accepted (the pipe is cut) and nothing may be lost;
     * when the chunk is back the water must flow again. Accepted water must equal what the tank gained, always.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1500)
    public static void waterLineConservesWaterWhileAMiddleChunkIsGone(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        ServerLevel level = h.getLevel();
        int x0 = X0;
        int z0 = Z0 + 4 * SLOT_Z;
        int chunks = 7;
        int row = z0 >> 4;
        BlockPos first = new BlockPos(x0 + 2, Y, z0 + 7);
        BlockPos last = new BlockPos(x0 + 16 * 6 + 8, Y, z0 + 7);
        BlockPos tankPos = last.east();
        long[] accepted = new long[1];
        long[] mark = new long[1];
        GraphWatch graphs = new GraphWatch(() -> PipeTopology.cached(level, first, null, false, false));
        Runnable pump = () -> {
            graphs.sample();
            if (!level.isLoaded(first)) {
                return;
            }
            IFluidHandler pipe = level.getCapability(Capabilities.FluidHandler.BLOCK, first, Direction.WEST);
            if (pipe != null) {
                accepted[0] += pipe.fill(new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 100), IFluidHandler.FluidAction.EXECUTE);
            }
        };
        Script script = new Script(h, 1500, pump, () -> {
            try {
                BlockPos lo = new BlockPos(x0 - 16, Y - 3, z0 - 16);
                BlockPos hi = new BlockPos(x0 + 16 * chunks + 16, Y + 3, z0 + 16);
                for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                    if (level.isLoaded(p) && !level.getBlockState(p).isAir()) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            lab.forceRect((x0 >> 4) - 1, row, (x0 >> 4) + chunks, row);
            for (int x = first.getX(); x <= last.getX(); x++) {
                level.setBlock(new BlockPos(x, Y, z0 + 7), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
            }
            level.setBlock(tankPos, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
        });
        script.then(60, () -> {
            var tank = (CondensateStorageTankBlockEntity) lab.peek(tankPos);
            h.assertTrue(accepted[0] > 0, "the water line accepted nothing while whole");
            h.assertTrue(Math.abs(accepted[0] - tank.storedKg()) <= 2, "water not conserved while whole: accepted "
                    + accepted[0] + ", in tank " + tank.storedKg());
            lab.syncLoads.clear();
            for (int c = 1; c <= 5; c++) {
                lab.unforce((x0 >> 4) + c, row);
            }
        });
        script.await(() -> "chunk 3 to unload: " + lab.describe((x0 >> 4) + 3, row), 400, () -> !lab.loaded((x0 >> 4) + 3, row));
        script.then(1, () -> {
            mark[0] = accepted[0];
            graphs.reset();
        });
        script.then(120, () -> {
            h.assertTrue(accepted[0] == mark[0], "water was accepted into a pipe cut by an unloaded chunk: " + (accepted[0] - mark[0]));
            noSyncLoads(lab, "water line with a chunk gone");
            quiet(log, "water line with a chunk gone");
            h.assertTrue(graphs.distinct() <= 4, "the water line was rebuilt " + graphs.distinct() + " times in 120 ticks with nothing changing");
        });
        script.then(1, () -> {
            for (int c = 1; c <= 5; c++) {
                lab.request((x0 >> 4) + c, row);
            }
        });
        script.await("water to flow again after the chunk came back", 300, () -> accepted[0] > mark[0]);
        script.then(40, () -> {
            var tank = (CondensateStorageTankBlockEntity) lab.peek(tankPos);
            h.assertTrue(Math.abs(accepted[0] - tank.storedKg()) <= 2, "water not conserved across the unload: accepted "
                    + accepted[0] + ", in tank " + tank.storedKg());
            noSyncLoads(lab, "after the water line came back");
            quiet(log, "after the water line came back");
        });
        script.start();
    }

    // ------------------------------------------------------------------ a machine that searches for its neighbours

    /**
     * An old single-block LPCS pump in chunk 1 looks for its reactor, pool and tank inside a 24 block cube. The tank
     * is in chunk 3. After a restart (every chunk unloaded, then only the reactor chunk wanted again) the pump's chunk
     * is loaded and ticking while the tank's chunk is not, so its first scan cannot see the tank. The pump used to
     * clear the binding it had saved, and, with a reactor bound, not look again for 600 ticks: it ignored its tank for
     * up to thirty seconds after the tank's chunk came back. It must keep the binding.
     */
    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "chunklink", timeoutTicks = 1700)
    public static void pumpKeepsItsTankBindingWhenItsFirstScanCannotSeeTheTankChunk(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        Rig rig = new Rig(h, lab, X0, Z0 + 5 * SLOT_Z, 5);
        ServerLevel level = h.getLevel();
        BlockPos pumpPos = new BlockPos(rig.x0 + 27, Y, rig.lineZ);
        BlockPos tankPos = new BlockPos(rig.x0 + 50, Y, rig.lineZ);
        Runnable drive = () -> {
            // Tick the pump as the world does; its chunk is only block-ticking when the reactor chunk alone is wanted.
            if (lab.peek(pumpPos) instanceof EccsPumpBlockEntity p) {
                EccsPumpBlockEntity.serverTick(level, pumpPos, p.getBlockState(), p);
            }
        };
        Script script = new Script(h, 1700, drive, () -> {
            try {
                rig.remove();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            try {
                rig.forceRow();
                rig.clearArea();
                rig.buildReactor();
                level.setBlock(pumpPos, BwrBlocks.LPCS_PUMP.get().defaultBlockState(), 3);
                level.setBlock(tankPos, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        script.await("the pump to find its reactor and tank", 200, () ->
                lab.peek(pumpPos) instanceof EccsPumpBlockEntity p && p.hasReactor() && p.hasTank());
        script.then(1, () -> {
            for (int c = -1; c <= rig.chunks; c++) {
                lab.unforce(rig.chunkX(c), rig.row());
            }
        });
        script.await("every chunk of the row to unload", 600, () -> {
            for (int c = -1; c <= rig.chunks; c++) {
                if (lab.loaded(rig.chunkX(c), rig.row())) {
                    return false;
                }
            }
            return true;
        });
        script.then(20, () -> {
            lab.syncLoads.clear();
            // Only the reactor chunk: chunk 1 (the pump) loads and ticks, chunk 3 (the tank) stays unloaded.
            lab.request(rig.chunkX(0), rig.row());
        });
        script.await(() -> "the pump to be loaded again: " + lab.describe(rig.chunkX(1), rig.row()), 400,
                () -> lab.peek(pumpPos) instanceof EccsPumpBlockEntity);
        script.then(100, () -> {
            h.assertTrue(!lab.loaded(rig.chunkX(3), rig.row()), "the tank chunk was loaded by something: " + lab.describe(rig.chunkX(3), rig.row()));
            h.assertTrue(lab.peek(pumpPos) instanceof EccsPumpBlockEntity p && p.hasReactor(), "the pump lost its reactor");
            noSyncLoads(lab, "pump scanning with a chunk missing");
            lab.request(rig.chunkX(3), rig.row());
        });
        script.await("the tank chunk to load", 300, () -> lab.loaded(rig.chunkX(3), rig.row()));
        script.then(5, () -> {
            h.assertTrue(lab.peek(pumpPos) instanceof EccsPumpBlockEntity p && p.hasTank(),
                    "the pump forgot its tank during a scan that could not see the tank's chunk");
            quiet(log, "pump scanning with a chunk missing");
        });
        script.start();
    }
}
