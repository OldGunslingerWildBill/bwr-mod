package dev.bwr.mod.suppression;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Server-thread membership index, updated when pool formation changes. */
public final class FormedSuppressionPoolRegistry {
    private FormedSuppressionPoolRegistry() {}
    private static final Map<Level, Set<BlockPos>> FORMED = new WeakHashMap<>();

    public static void update(SuppressionPoolBlockEntity pool) {
        Level level = pool.getLevel();
        if (level == null || level.isClientSide()) return;
        Set<BlockPos> positions = FORMED.computeIfAbsent(level, unused -> new HashSet<>());
        if (!pool.isRemoved() && pool.isFormed()) positions.add(pool.getBlockPos().immutable());
        else positions.remove(pool.getBlockPos());
    }

    public static void remove(SuppressionPoolBlockEntity pool) {
        Set<BlockPos> positions = FORMED.get(pool.getLevel());
        if (positions != null) positions.remove(pool.getBlockPos());
    }

    public static List<SuppressionPoolBlockEntity> controllers(Level level) {
        Set<BlockPos> positions = FORMED.get(level);
        if (positions == null) return List.of();
        List<SuppressionPoolBlockEntity> result = new ArrayList<>();
        var iterator = positions.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof SuppressionPoolBlockEntity pool
                    && !pool.isRemoved() && pool.isFormed()) result.add(pool);
            else iterator.remove();
        }
        return result;
    }
}
