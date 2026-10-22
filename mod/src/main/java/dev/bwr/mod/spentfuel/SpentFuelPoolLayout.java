package dev.bwr.mod.spentfuel;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Surveys the structure around a pool controller: a lined box, open at the top, of pool wall blocks (the floor, and
 * four walls from the floor to the rim), with fuel racks standing inside. Read only; a survey never changes a block
 * and never loads a chunk.
 *
 * <p>The rules are the suppression pool's ({@code ConcreteBasin}) with the roof taken away: outside width and depth
 * 5 to 25 blocks, outside height 8 to 16 (a rack is {@value #RACK_HEIGHT} m tall and the pool must hold
 * {@value #MIN_WATER_ABOVE_RACKS} m of water over it), the whole shell made of pool blocks, exactly one controller, ports
 * facing outward from a side wall above the floor, and nothing but racks inside. A rack is a column of exactly
 * {@value #RACK_HEIGHT} rack blocks on the floor, never on another rack. The water fills the interior up to the rim.
 */
public final class SpentFuelPoolLayout {

    private SpentFuelPoolLayout() {
    }

    public static final int MAX_RACKS = 48;

    /**
     * A rack is one tier, {@value #RACK_HEIGHT} blocks tall (a real assembly is about 4.5 m and stands upright): a column of
     * exactly that many rack blocks standing on the pool floor, never on another rack.
     */
    public static final int RACK_HEIGHT = 4;

    /** Water over the top of the racks the pool must be deep enough to hold: shielding (the real figure is about 7 m, scaled). */
    public static final int MIN_WATER_ABOVE_RACKS = 3;

    /** Interior depth the pool needs, metres: the racks and the water over them. */
    public static final int MIN_INTERIOR_DEPTH = RACK_HEIGHT + MIN_WATER_ABOVE_RACKS;

    /** Outside height: interior depth and the floor. */
    public static final int MIN_OUTSIDE_HEIGHT = MIN_INTERIOR_DEPTH + 1;
    public static final int MAX_SHELL_BLOCKS = 4096;

    /**
     * What a survey found. {@code problem} is null for a pool that can form. {@code footprintCells} is the interior
     * area in square metres and {@code heightCells} the interior depth, floor to rim, in metres.
     */
    public record Layout(BlockPos min, BlockPos max, int footprintCells, int heightCells, List<BlockPos> racks,
                         List<BlockPos> makeupPorts, List<BlockPos> coolingIn, List<BlockPos> coolingOut,
                         Component problem) {
        public boolean ok() {
            return problem == null;
        }

        /** Height of the top of every rack, metres above the pool floor: a rack is one full-height tier. */
        public double rackTopM(BlockPos rack) {
            return RACK_HEIGHT;
        }
    }

    /** True for a block that may be part of the shell. */
    public static boolean shell(BlockState state) {
        return state.is(BwrBlocks.SPENT_FUEL_POOL_WALL.get()) || state.is(BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get())
                || state.getBlock() instanceof SpentFuelPoolPortBlock;
    }

    private static Layout failure(BlockPos min, BlockPos max, Component problem) {
        return new Layout(min, max, 0, 0, List.of(), List.of(), List.of(), List.of(), problem);
    }

    public static Layout inspect(Level level, BlockPos controller) {
        var queue = new ArrayDeque<BlockPos>();
        var seen = new HashSet<BlockPos>();
        queue.add(controller);
        seen.add(controller);
        BlockPos min = controller;
        BlockPos max = controller;
        boolean missing = false;
        while (!queue.isEmpty()) {
            BlockPos p = queue.remove();
            min = new BlockPos(Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
            max = new BlockPos(Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ()));
            if (seen.size() > MAX_SHELL_BLOCKS) {
                return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.too_large", "The pool shell is too large."));
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (!level.isLoaded(n)) {
                    missing = true;
                    continue;
                }
                if (shell(level.getBlockState(n)) && seen.add(n)) {
                    queue.add(n);
                }
            }
        }
        if (missing) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.waiting_for_chunks",
                    "The pool is waiting for its chunks to load."));
        }
        int w = max.getX() - min.getX() + 1;
        int d = max.getZ() - min.getZ() + 1;
        int h = max.getY() - min.getY() + 1;
        if (w < 5 || w > 25 || d < 5 || d > 25 || h < MIN_OUTSIDE_HEIGHT || h > 16) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.size",
                    "Build a pool 5 to 25 blocks wide and deep and 8 to 16 high, outside: racks are 4 m tall and need 3 m of water over them."));
        }
        List<BlockPos> rackBlocks = new ArrayList<>();
        List<BlockPos> makeup = new ArrayList<>();
        List<BlockPos> coolingIn = new ArrayList<>();
        List<BlockPos> coolingOut = new ArrayList<>();
        int controllers = 0;
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState s = level.getBlockState(p);
            boolean wall = p.getX() == min.getX() || p.getX() == max.getX() || p.getZ() == min.getZ() || p.getZ() == max.getZ();
            if (wall || p.getY() == min.getY()) {
                if (!shell(s)) {
                    return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.shell_gap",
                            "Complete the floor and the four walls up to the rim; missing at %s", p.toShortString()));
                }
                if (s.is(BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get())) {
                    controllers++;
                }
                if (s.getBlock() instanceof SpentFuelPoolPortBlock port) {
                    Direction face = s.getValue(SpentFuelPoolPortBlock.FACING);
                    BlockPos out = p.relative(face);
                    boolean inside = out.getX() >= min.getX() && out.getX() <= max.getX()
                            && out.getZ() >= min.getZ() && out.getZ() <= max.getZ();
                    if (!wall || p.getY() == min.getY() || inside) {
                        return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.port_placement",
                                "Ports go in a side wall above the floor, facing outward; check %s", p.toShortString()));
                    }
                    switch (port.kind()) {
                        case MAKEUP -> makeup.add(p.immutable());
                        case COOLING_IN -> coolingIn.add(p.immutable());
                        case COOLING_OUT -> coolingOut.add(p.immutable());
                    }
                }
            } else {
                boolean source = level.getFluidState(p).is(Fluids.WATER) && level.getFluidState(p).isSource();
                if (s.is(BwrBlocks.SPENT_FUEL_RACK.get())) {
                    rackBlocks.add(p.immutable());
                } else if (!s.isAir() && !source) {
                    return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.clear_interior",
                            "Clear the pool interior at %s; only racks may stand in it", p.toShortString()));
                }
            }
        }
        // racks: each is a column of exactly RACK_HEIGHT blocks standing on the floor; anything else is refused
        Set<BlockPos> loose = new HashSet<>(rackBlocks);
        List<BlockPos> racks = new ArrayList<>();
        for (BlockPos p : rackBlocks) {
            if (p.getY() != min.getY() + 1) {
                continue;
            }
            boolean whole = true;
            for (int k = 1; k < RACK_HEIGHT; k++) {
                whole &= loose.contains(p.above(k));
            }
            if (whole && !loose.contains(p.above(RACK_HEIGHT))) {
                racks.add(p);
                for (int k = 0; k < RACK_HEIGHT; k++) {
                    loose.remove(p.above(k));
                }
            }
        }
        if (!loose.isEmpty()) {
            BlockPos bad = loose.stream().min(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).get();
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.rack_support",
                    "A rack is one tier of exactly %s blocks standing on the pool floor, never on another rack; check %s",
                    RACK_HEIGHT, bad.toShortString()));
        }
        if (controllers != 1) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.one_controller",
                    "Use exactly one pool controller in the shell."));
        }
        if (racks.size() > MAX_RACKS) {
            return failure(min, max, BwrText.tr("status.bwr.spent_fuel_pool.too_many_racks",
                    "A pool holds at most %s racks.", MAX_RACKS));
        }
        racks.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return new Layout(min, max, (w - 2) * (d - 2), h - 1, List.copyOf(racks), List.copyOf(makeup), List.copyOf(coolingIn),
                List.copyOf(coolingOut), null);
    }

    /** Positions the pool claims in the registry: its racks and its ports. */
    public static Set<BlockPos> claimed(Layout layout) {
        Set<BlockPos> set = new HashSet<>();
        for (BlockPos rack : layout.racks()) {
            for (int k = 0; k < RACK_HEIGHT; k++) {
                set.add(rack.above(k));
            }
        }
        set.addAll(layout.makeupPorts());
        set.addAll(layout.coolingIn());
        set.addAll(layout.coolingOut());
        return set;
    }
}
