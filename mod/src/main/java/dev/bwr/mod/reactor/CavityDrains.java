package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * The cavity drain ports that are loaded, per level, so a formed reactor can find the ones in its well without scanning
 * blocks. A port adds itself when it loads and leaves when it is removed or its chunk goes. {@link #generation()} changes
 * with every addition and removal; the reactor re-reads its list only when that moved. Server side only.
 */
public final class CavityDrains {
    private CavityDrains() {
    }

    private static final Map<Level, Set<BlockPos>> PORTS = new WeakHashMap<>();
    private static int generation;

    public static int generation() {
        return generation;
    }

    static void add(Level level, BlockPos pos) {
        if (level != null && !level.isClientSide() && PORTS.computeIfAbsent(level, l -> new HashSet<>()).add(pos.immutable())) {
            generation++;
        }
    }

    static void remove(Level level, BlockPos pos) {
        Set<BlockPos> set = PORTS.get(level);
        if (set != null && set.remove(pos)) {
            generation++;
        }
    }

    /** The loaded ports with a position inside the box, grown by {@code margin} blocks on every side. */
    public static List<BlockPos> near(Level level, BlockPos min, BlockPos max, int margin) {
        Set<BlockPos> set = PORTS.get(level);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : set) {
            if (p.getX() >= min.getX() - margin && p.getX() <= max.getX() + margin && p.getZ() >= min.getZ() - margin
                    && p.getZ() <= max.getZ() + margin && p.getY() >= min.getY() - margin) {
                out.add(p);
            }
        }
        out.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
        return out;
    }
}
