package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A feedwater line four chunks long (PORT-LOCA-UPDATE.md, "Line breaks"): the reactor in chunk 0, the line east to a
 * tank in chunk 3, which is the only chunk that unloads when chunk 0 keeps its ticket. An intact line whose far chunk
 * goes away is not broken; a break recorded in that chunk is neither repaired nor duplicated while the chunk is gone
 * or after it comes back; putting the pipe back afterwards seals it. Chunks go and come back through tickets only.
 */
@GameTestHolder("bwrtick")
@PrefixGameTestTemplate(false)
public final class LocaChunkTests {
    private LocaChunkTests() {
    }

    static final int X0 = 360_000;
    static final int Z0 = 360_000;
    static final int Y = 195;

    @GameTest(template = "empty", templateNamespace = "bwrtick", batch = "locachunk", timeoutTicks = 1800)
    public static void lineBreakInAnUnloadedChunkIsDeferred(GameTestHelper h) {
        var level = h.getLevel();
        ChunkLab lab = new ChunkLab(level);
        LogWatch log = LogWatch.begin();
        int row = Z0 >> 4;
        int cx0 = X0 >> 4;
        BlockPos min = new BlockPos(X0 + 4, 190, Z0 + 5);
        BlockPos controller = min.offset(-1, 3, 2);
        BlockPos port = new BlockPos(X0 + 9, Y, Z0 + 7);
        BlockPos tank = new BlockPos(X0 + 16 * 3 + 8, Y, Z0 + 7);
        BlockPos gap = new BlockPos(X0 + 16 * 3 + 3, Y, Z0 + 7);
        java.util.function.Supplier<ReactorControllerBlockEntity> reactor =
                () -> lab.peek(controller) instanceof ReactorControllerBlockEntity r ? r : null;
        Runnable clear = () -> {
            BlockPos lo = new BlockPos(X0 - 16, 185, Z0 - 16), hi = new BlockPos(X0 + 16 * 4 + 16, 205, Z0 + 31);
            FixtureCleanup.drainFuel(level, lo, hi);
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                if (level.isLoaded(p) && !level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            FixtureCleanup.discardItems(level, lo, hi);
        };
        Runnable noSyncLoads = () -> {
            if (!lab.syncLoads.isEmpty()) {
                throw new AssertionError("our code loaded chunks by reading blocks in them: " + lab.syncLoads);
            }
            if (!log.problems.isEmpty()) {
                throw new AssertionError("our code logged warnings or errors: " + log.problems.subList(0, Math.min(5, log.problems.size())));
            }
        };
        java.util.function.BooleanSupplier broken = () -> reactor.get() != null && reactor.get().core() != null
                && reactor.get().core().getBoundaryStress().isBroken(BoundaryComponent.FEEDWATER_LINE);
        java.util.function.IntSupplier breaks = () -> reactor.get() == null ? -1 : reactor.get().boundaryEffects().breaks().size();
        Script script = new Script(h, 1800, () -> {
        }, () -> {
            try {
                clear.run();
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            lab.forceRect(cx0 - 1, row, cx0 + 4, row);
            clear.run();
            try {
                var be = CompactCoreRegressionTests.build(level, min, 5, 5, 8, false);
                h.assertTrue(be.isFormed(), "fixture reactor did not form: " + be.statusLines());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            level.setBlock(port, BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState()
                    .setValue(RpvWaterInjectionPortBlock.FACING, Direction.EAST), 3);
            for (int x = port.getX() + 1; x < tank.getX(); x++) {
                level.setBlock(new BlockPos(x, Y, port.getZ()), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
            }
            level.setBlock(tank, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
            reactor.get().markStructureDirty();
        });
        script.then(40, () -> {
            int cells = reactor.get().boundaryEffects().lineCellCount(port);
            h.assertTrue(cells == tank.getX() - port.getX() - 1, "the four-chunk feed line was not established: " + cells + " cells");
            h.assertTrue(!broken.getAsBoolean() && breaks.getAsInt() == 0, "the intact line broke");
            lab.syncLoads.clear();
            for (int c = -1; c <= 4; c++) if (c != 0) lab.unforce(cx0 + c, row);
        });
        script.await(() -> "chunk 3 to unload: " + lab.describe(cx0 + 3, row), 400, () -> !lab.loaded(cx0 + 3, row));
        script.then(60, () -> {
            h.assertTrue(!lab.loaded(cx0 + 3, row), "the plant loaded chunk 3 again");
            h.assertTrue(!broken.getAsBoolean() && breaks.getAsInt() == 0,
                    "an intact line whose chunk unloaded was taken for a break: " + reactor.get().boundaryEffects().breaks());
            noSyncLoads.run();
            for (int c = -1; c <= 4; c++) lab.request(cx0 + c, row);
        });
        script.awaitChunks(() -> "chunk 3 to come back: " + lab.describe(cx0 + 3, row), 400, () -> lab.loaded(cx0 + 3, row));
        script.then(40, () -> {
            h.assertTrue(!broken.getAsBoolean() && breaks.getAsInt() == 0, "the line broke when its chunk came back");
            h.assertTrue(reactor.get().boundaryEffects().lineCellCount(port) == tank.getX() - port.getX() - 1,
                    "the line was forgotten across the chunk reload");
            level.removeBlock(gap, false);
        });
        script.then(5, () -> {
            h.assertTrue(broken.getAsBoolean() && breaks.getAsInt() == 1, "the break in chunk 3 was not a LOCA: " + reactor.get().boundaryEffects().breaks());
            h.assertTrue(reactor.get().boundaryEffects().breaks().getFirst().at().equals(gap), "the break is not at the gap");
            lab.syncLoads.clear();
            for (int c = -1; c <= 4; c++) if (c != 0) lab.unforce(cx0 + c, row);
        });
        script.await(() -> "chunk 3 to unload with the break in it: " + lab.describe(cx0 + 3, row), 400, () -> !lab.loaded(cx0 + 3, row));
        script.then(60, () -> {
            h.assertTrue(broken.getAsBoolean(), "the break was repaired while its chunk was gone");
            h.assertTrue(breaks.getAsInt() == 1, "the unloaded chunk made a second break or lost the first: " + reactor.get().boundaryEffects().breaks());
            noSyncLoads.run();
            for (int c = -1; c <= 4; c++) lab.request(cx0 + c, row);
        });
        script.awaitChunks(() -> "chunk 3 to come back with the break: " + lab.describe(cx0 + 3, row), 400, () -> lab.loaded(cx0 + 3, row));
        script.then(40, () -> {
            h.assertTrue(broken.getAsBoolean() && breaks.getAsInt() == 1, "the break changed when its chunk came back: "
                    + reactor.get().boundaryEffects().breaks());
            level.setBlock(gap, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        });
        script.then(5, () -> {
            h.assertTrue(!broken.getAsBoolean() && breaks.getAsInt() == 0, "the pipe put back did not seal the break");
            noSyncLoads.run();
        });
        script.start();
    }
}
