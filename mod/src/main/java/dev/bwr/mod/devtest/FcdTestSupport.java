package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Shared fixture of the full core display tests: a real formed reactor of a chosen interior size in a far-away,
 * force-loaded area. Every test class owns one origin (75_900 and up, 2_000 apart, from {@code FcdRodTests}) and
 * steps {@code slot * 64} blocks along X for each of its tests, so tests never share chunks. The area is emptied
 * before the build (a persistent-world rerun finds the previous run's blocks) and again by {@link #release}, and the
 * chunks are released in every outcome.
 */
final class FcdTestSupport {
    private FcdTestSupport() {
    }

    /** Interior height of every fixture reactor. */
    static final int HEIGHT = 8;

    /** The south-west-bottom corner of a fixture slot. */
    static BlockPos slot(int origin, int slot) {
        return new BlockPos(origin + slot * 64, 190, origin);
    }

    /** Force-loads (or releases) every chunk a reactor of this size at {@code min} and the panels beside it need. */
    static void hold(ServerLevel level, BlockPos min, int width, int depth, boolean force) {
        for (int x = (min.getX() - 20) >> 4; x <= (min.getX() + width + 20) >> 4; x++) {
            for (int z = (min.getZ() - 20) >> 4; z <= (min.getZ() + depth + 20) >> 4; z++) {
                level.setChunkForced(x, z, force);
                if (force) {
                    level.getChunk(x, z);
                }
            }
        }
    }

    /** Empties the reactor volume, the drive layer and the panel area. */
    static void clear(ServerLevel level, BlockPos min, int width, int depth) {
        for (int x = -4; x <= width + 1; x++) {
            for (int y = -3; y <= HEIGHT + 4; y++) {
                for (int z = -1; z <= depth + 1; z++) {
                    BlockPos pos = min.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** Forces the chunks, empties the area and builds a formed reactor with every drive supplied. */
    static ReactorControllerBlockEntity formed(GameTestHelper helper, BlockPos min, int width, int depth) {
        ServerLevel level = helper.getLevel();
        hold(level, min, width, depth, true);
        clear(level, min, width, depth);
        try {
            ReactorControllerBlockEntity be = CompactCoreRegressionTests.build(level, min, width, depth, false);
            helper.assertTrue(be.isFormed(), "fixture reactor did not form: " + be.statusLines());
            supplyDrives(level, be);
            return be;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Gives every control rod drive its full power and water buffers, as a connected supply does. */
    static void supplyDrives(ServerLevel level, ReactorControllerBlockEntity be) {
        for (BlockPos crd : be.structure().crdPositions()) {
            if (level.getBlockEntity(crd) instanceof ControlRodDriveBlockEntity drive) {
                drive.hardware().setEnergyStoredFe(drive.hardware().getEnergyCapacityFe());
                drive.hardware().setWaterStoredMb(drive.hardware().getWaterCapacityMb());
            }
        }
    }

    /** Takes the supplies away from every drive. */
    static void starveDrives(ServerLevel level, ReactorControllerBlockEntity be) {
        for (BlockPos crd : be.structure().crdPositions()) {
            if (level.getBlockEntity(crd) instanceof ControlRodDriveBlockEntity drive) {
                drive.hardware().setEnergyStoredFe(0.0);
                drive.hardware().setWaterStoredMb(0.0);
            }
        }
    }

    /** Empties the area and releases its chunks. Safe to call twice. */
    static void release(ServerLevel level, BlockPos min, int width, int depth) {
        clear(level, min, width, depth);
        hold(level, min, width, depth, false);
    }

    /**
     * A timed test on a formed reactor: {@code body} runs two ticks after the chunks are forced (an earlier run's
     * entities are not accessible before that), registers {@link #then} steps, and the fixture is released and the
     * test succeeded after the last step, or released and failed in any other outcome.
     */
    static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos min;
        final int width;
        final int depth;
        ReactorControllerBlockEntity be;
        /** Scratch for steps that hand a number to a later step. */
        int stopDemand;
        double scratch;
        private final java.util.List<Runnable> steps = new java.util.ArrayList<>();
        private final java.util.List<Integer> delays = new java.util.ArrayList<>();

        private Run(GameTestHelper helper, BlockPos min, int width, int depth) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.min = min;
            this.width = width;
            this.depth = depth;
        }

        /** Forces the chunks now, builds the reactor and runs {@code body} two ticks later. */
        static void start(GameTestHelper helper, int origin, int slot, int width, int depth,
                          java.util.function.Consumer<Run> body) {
            Run run = new Run(helper, slot(origin, slot), width, depth);
            hold(run.level, run.min, width, depth, true);
            helper.runAfterDelay(2, () -> {
                try {
                    run.be = formed(helper, run.min, width, depth);
                    body.accept(run);
                } catch (RuntimeException | Error e) {
                    run.release();
                    throw e;
                }
                run.next();
            });
        }

        /** A step to run {@code delay} ticks after the previous one. */
        void then(int delay, Runnable step) {
            delays.add(delay);
            steps.add(step);
        }

        void release() {
            FcdTestSupport.release(level, min, width, depth);
        }

        private void next() {
            if (steps.isEmpty()) {
                release();
                helper.succeed();
                return;
            }
            int delay = delays.remove(0);
            Runnable step = steps.remove(0);
            helper.runAfterDelay(delay, () -> {
                try {
                    step.run();
                } catch (RuntimeException | Error e) {
                    release();
                    throw e;
                }
                next();
            });
        }
    }
}
