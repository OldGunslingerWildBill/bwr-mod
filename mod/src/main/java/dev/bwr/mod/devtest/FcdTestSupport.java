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
        // The controller holds the fuel of the previous run or test; take it out first so removing the block pops nothing.
        FixtureCleanup.drainFuel(level, min.offset(-4, -3, -1), min.offset(width + 1, HEIGHT + 4, depth + 1));
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
        clearWall(level, min);
        // Safety net: nothing of ours may stay behind in the saved chunks. The box reaches far below the fixture
        // because a dropped item falls out of it, and stays inside this fixture's own X/Z footprint.
        FixtureCleanup.discardItems(level, min.offset(-6, -80, -3), min.offset(width + 3, HEIGHT + 4, depth + 3));
        hold(level, min, width, depth, false);
    }

    /** The top-left block of the panel wall of a fixture: columns run toward -X, rows downward, facing north. */
    static BlockPos wallOrigin(BlockPos min) {
        return min.offset(8, 26, -8);
    }

    /** Empties the 8 x 8 block area a panel wall may use. */
    static void clearWall(ServerLevel level, BlockPos min) {
        BlockPos origin = wallOrigin(min);
        for (int c = -1; c <= 8; c++) {
            for (int r = -1; r <= 8; r++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = origin.offset(-c, -r, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(origin).inflate(12, 12, 6))) {
            item.discard();
        }
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
        /** A survival fake player in front of the wall, set by {@link #wall}. */
        net.neoforged.neoforge.common.util.FakePlayer player;
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

        /** Builds a vertical panel wall of {@code cols x rows} blocks and puts a fake player in front of it. */
        void wall(int cols, int rows) {
            clearWall(level, min);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    level.setBlock(wallAt(c, r), dev.bwr.mod.registry.BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(dev.bwr.mod.panel.PanelBlock.FACING, net.minecraft.core.Direction.NORTH), 3);
                }
            }
            BlockPos origin = wallOrigin(min);
            player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                    java.util.UUID.nameUUIDFromBytes("fcd-test-player".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "fcd-test"));
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.setShiftKeyDown(false);
            player.setPos(origin.getX() - 1.5, origin.getY() - 1.0, origin.getZ() - 2.5);
        }

        BlockPos wallAt(int col, int row) {
            return wallOrigin(min).relative(net.minecraft.core.Direction.NORTH.getCounterClockWise(), col).below(row);
        }

        dev.bwr.mod.panel.PanelBlockEntity panel(int col, int row) {
            return (dev.bwr.mod.panel.PanelBlockEntity) level.getBlockEntity(wallAt(col, row));
        }

        /** Binds input 0 of a core display to the reactor as the linker would. */
        dev.bwr.mod.panel.wiring.WiringOps.Outcome bindReactor(dev.bwr.mod.panel.PanelBlockEntity owner,
                                                                dev.bwr.mod.panel.component.PanelComponent component,
                                                                BlockPos device) {
            return bindReactor(owner, component, device, "formed");
        }

        /** Binds input 0 to {@code device}'s signal {@code key} as the linker would. */
        dev.bwr.mod.panel.wiring.WiringOps.Outcome bindReactor(dev.bwr.mod.panel.PanelBlockEntity owner,
                                                                dev.bwr.mod.panel.component.PanelComponent component,
                                                                BlockPos device, String key) {
            net.minecraft.world.item.ItemStack linker = new net.minecraft.world.item.ItemStack(dev.bwr.mod.registry.BwrItems.PANEL_LINKER.get());
            linker.set(dev.bwr.mod.fuel.BwrDataComponents.LINKED_DEVICE.get(),
                    net.minecraft.core.GlobalPos.of(level.dimension(), device));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, linker);
            return dev.bwr.mod.panel.wiring.WiringOps.bindDetailed(player, new dev.bwr.mod.panel.net.PanelPayloads.Bind(
                    owner.getBlockPos(), component.id(), false, 0, dev.bwr.mod.panel.wiring.WiringOps.BindKind.PLANT.ordinal(),
                    device, key, BlockPos.ZERO, 0, 0, 1.0));
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
