package dev.bwr.mod.spentfuel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-thread index of the pool controllers of a level and of the racks and ports each one has claimed.
 *
 * <p>This is what lets the rack and port blocks do without a block entity or a ticker: a rack or port asks "who owns
 * me" and is answered from a hash map filled when the controller last scanned its structure, and a block that is
 * placed or broken near a pool asks for that pool to rescan. Claims are dropped when the controller unloads, is
 * removed or stops being formed, so a stale claim can never reach a controller that is no longer there.
 */
public final class SpentFuelPoolRegistry {

    private SpentFuelPoolRegistry() {
    }

    /** How far from a controller a changed block can matter: the largest pool is 25 blocks across. */
    private static final int REACH = 26;

    private static final class Entry {
        final Set<BlockPos> controllers = new HashSet<>();
        final Map<BlockPos, BlockPos> claims = new HashMap<>();
    }

    private static final Map<Level, Entry> LEVELS = new WeakHashMap<>();

    private static Entry entry(Level level) {
        return LEVELS.computeIfAbsent(level, unused -> new Entry());
    }

    public static void register(SpentFuelPoolBlockEntity pool) {
        Level level = pool.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        entry(level).controllers.add(pool.getBlockPos().immutable());
    }

    public static void unregister(SpentFuelPoolBlockEntity pool) {
        Level level = pool.getLevel();
        if (level == null) {
            return;
        }
        Entry e = LEVELS.get(level);
        if (e == null) {
            return;
        }
        BlockPos at = pool.getBlockPos();
        e.controllers.remove(at);
        e.claims.values().removeIf(at::equals);
    }

    /** Replace the claims of a controller with exactly these positions. */
    public static void claim(SpentFuelPoolBlockEntity pool, Iterable<BlockPos> positions) {
        Level level = pool.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        Entry e = entry(level);
        BlockPos at = pool.getBlockPos().immutable();
        e.claims.values().removeIf(at::equals);
        for (BlockPos p : positions) {
            e.claims.put(p.immutable(), at);
        }
    }

    public static void release(SpentFuelPoolBlockEntity pool) {
        Level level = pool.getLevel();
        if (level == null) {
            return;
        }
        Entry e = LEVELS.get(level);
        if (e != null) {
            BlockPos at = pool.getBlockPos();
            e.claims.values().removeIf(at::equals);
        }
    }

    /** The formed pool that owns the rack or port at {@code pos}, or null. Never loads a chunk. */
    public static SpentFuelPoolBlockEntity ownerOf(Level level, BlockPos pos) {
        Entry e = LEVELS.get(level);
        if (e == null) {
            return null;
        }
        BlockPos controller = e.claims.get(pos);
        if (controller == null || !level.isLoaded(controller)) {
            return null;
        }
        if (level.getBlockEntity(controller) instanceof SpentFuelPoolBlockEntity pool
                && !pool.isRemoved() && pool.isFormed() && pool.owns(pos)) {
            return pool;
        }
        return null;
    }

    /** Every pool controller that is loaded in this level, formed or not. */
    public static List<SpentFuelPoolBlockEntity> controllers(Level level) {
        Entry e = LEVELS.get(level);
        if (e == null) {
            return List.of();
        }
        List<SpentFuelPoolBlockEntity> result = new ArrayList<>();
        var iterator = e.controllers.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof SpentFuelPoolBlockEntity pool && !pool.isRemoved()) {
                result.add(pool);
            } else {
                iterator.remove();
            }
        }
        return result;
    }

    /** A block that can be part of a pool was placed or removed at {@code at}: the pools around it rescan. */
    public static void changed(Level level, BlockPos at) {
        if (level.isClientSide()) {
            return;
        }
        Entry e = LEVELS.get(level);
        if (e == null || e.controllers.isEmpty()) {
            return;
        }
        for (BlockPos controller : e.controllers) {
            if (Math.abs(controller.getX() - at.getX()) <= REACH && Math.abs(controller.getY() - at.getY()) <= REACH
                    && Math.abs(controller.getZ() - at.getZ()) <= REACH
                    && level.isLoaded(controller)
                    && level.getBlockEntity(controller) instanceof SpentFuelPoolBlockEntity pool) {
                pool.markStructureDirty();
            }
        }
    }
}
