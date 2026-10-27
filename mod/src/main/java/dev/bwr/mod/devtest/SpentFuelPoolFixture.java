package dev.bwr.mod.devtest;

import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolLayout;
import dev.bwr.mod.spentfuel.SpentFuelPoolPortBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * A spent fuel pool built block by block for the GameTests and the client check: a lined box {@code w x d x h} with
 * its corner at {@code min} (floor at {@code min.y}, walls up to {@code min.y + h - 1}), the controller in the west
 * wall, the three water ports in the north wall (they face north, out of the pool), and racks (columns of four rack
 * blocks) standing on the floor along the south side. The pool must be 8 high outside to form. No CC types here, so a {@code @GameTestHolder} class may use it.
 */
final class SpentFuelPoolFixture {

    final ServerLevel level;
    final BlockPos min;
    final int w;
    final int d;
    final int h;
    final BlockPos controller;
    final BlockPos makeup;
    final BlockPos coolingIn;
    final BlockPos coolingOut;
    final List<BlockPos> racks = new ArrayList<>();
    SpentFuelPoolBlockEntity be;

    private SpentFuelPoolFixture(ServerLevel level, BlockPos min, int w, int d, int h) {
        this.level = level;
        this.min = min;
        this.w = w;
        this.d = d;
        this.h = h;
        this.controller = min.offset(0, 2, d / 2);
        this.makeup = min.offset(2, h - 2, 0);
        this.coolingIn = min.offset(4, h - 2, 0);
        this.coolingOut = min.offset(6, h - 2, 0);
    }

    /**
     * Builds the pool and surveys it. {@code rackCount} racks are placed on the floor, filling the row nearest the
     * south wall first and then the next row (so up to {@code 2 * (w - 2)} on a pool of the usual size).
     */
    static SpentFuelPoolFixture build(ServerLevel level, BlockPos min, int w, int d, int h, int rackCount) {
        return build(level, min, w, d, h, rackCount, false);
    }

    /**
     * As {@link #build(ServerLevel, BlockPos, int, int, int, int)}, but the racks fill the whole floor row by row (from the
     * south wall northward, west to east in each row) instead of the two southern rows: for the 2,048-rack pools.
     */
    static SpentFuelPoolFixture buildFull(ServerLevel level, BlockPos min, int w, int d, int h, int rackCount) {
        return build(level, min, w, d, h, rackCount, true);
    }

    private static SpentFuelPoolFixture build(ServerLevel level, BlockPos min, int w, int d, int h, int rackCount, boolean everyRow) {
        SpentFuelPoolFixture f = new SpentFuelPoolFixture(level, min, w, d, h);
        f.forceChunks();
        f.clearBox();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                for (int y = 0; y < h; y++) {
                    boolean shell = y == 0 || x == 0 || x == w - 1 || z == 0 || z == d - 1;
                    if (shell) {
                        level.setBlock(min.offset(x, y, z), BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
                    }
                }
            }
        }
        level.setBlock(f.controller, BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get().defaultBlockState(), 2);
        level.setBlock(f.makeup, BwrBlocks.SPENT_FUEL_POOL_MAKEUP_PORT.get().defaultBlockState()
                .setValue(SpentFuelPoolPortBlock.FACING, Direction.NORTH), 2);
        level.setBlock(f.coolingIn, BwrBlocks.SPENT_FUEL_POOL_COOLING_IN.get().defaultBlockState()
                .setValue(SpentFuelPoolPortBlock.FACING, Direction.NORTH), 2);
        level.setBlock(f.coolingOut, BwrBlocks.SPENT_FUEL_POOL_COOLING_OUT.get().defaultBlockState()
                .setValue(SpentFuelPoolPortBlock.FACING, Direction.NORTH), 2);
        int placed = 0;
        for (int row = 0; row < (everyRow ? d - 2 : 2) && placed < rackCount; row++) {
            for (int x = 1; x < w - 1 && placed < rackCount; x++) {
                BlockPos at = min.offset(x, 1, d - 2 - row);
                for (int k = 0; k < SpentFuelPoolLayout.RACK_HEIGHT; k++) {
                    level.setBlock(at.above(k), BwrBlocks.SPENT_FUEL_RACK.get().defaultBlockState(), 2);
                }
                f.racks.add(at);
                placed++;
            }
        }
        f.be = (SpentFuelPoolBlockEntity) level.getBlockEntity(f.controller);
        f.be.scanNow();
        return f;
    }

    private void clearBox() {
        // a pool left by an earlier run still holds its fuel; empty it so that clearing the blocks drops nothing
        if (level.getBlockEntity(controller) instanceof SpentFuelPoolBlockEntity old) {
            old.removalDrops();
        }
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                for (int y = -1; y <= h + 1; y++) {
                    level.setBlock(min.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        FixtureCleanup.discardItems(level, min.offset(-2, -2, -2), min.offset(w + 2, h + 3, d + 2));
    }

    /** Forces (and loads) the chunks of the pool and 16 blocks round it. */
    void forceChunks() {
        for (int cx = (min.getX() - 16) >> 4; cx <= (min.getX() + w + 16) >> 4; cx++) {
            for (int cz = (min.getZ() - 16) >> 4; cz <= (min.getZ() + d + 16) >> 4; cz++) {
                FixtureChunks.force(level, min, cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
    }

    /** Lets go of the chunks {@link #forceChunks} forced (they unload unless something else wants them). */
    void releaseChunks() {
        for (int cx = (min.getX() - 16) >> 4; cx <= (min.getX() + w + 16) >> 4; cx++) {
            for (int cz = (min.getZ() - 16) >> 4; cz <= (min.getZ() + d + 16) >> 4; cz++) {
                FixtureChunks.force(level, min, cx, cz, false);
            }
        }
    }

    /** Empties the pool and takes it down. */
    void dispose() {
        if (be != null) {
            be.removalDrops();
        }
        clearBox();
        releaseChunks();
    }

    /** A spent assembly stamped as discharged {@code ageSeconds} before {@code now} (a game tick, not necessarily the level's). */
    static ItemStack assembly(double burnupMwdPerTonne, double ageSeconds, long now) {
        ItemStack stack = FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),
                new FuelAssemblyData("leu", 0.035, 180.0, burnupMwdPerTonne, 1.0));
        SpentFuelHeat.stamp(stack, now - Math.round(ageSeconds * SpentFuelHeat.TICKS_PER_SECOND));
        return stack;
    }

    /** Puts {@code count} copies of an assembly into the pool through its item handler. Returns how many went in. */
    int fill(int count, double burnupMwdPerTonne, double ageSeconds, long now) {
        int placed = 0;
        var handler = be.itemHandler();
        for (int slot = 0; slot < handler.getSlots() && placed < count; slot++) {
            if (handler.insertItem(slot, assembly(burnupMwdPerTonne, ageSeconds, now), false).isEmpty()) {
                placed++;
            }
        }
        return placed;
    }
}
