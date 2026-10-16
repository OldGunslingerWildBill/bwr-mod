package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Shared cleanup for GameTest fixtures that remove a reactor.
 *
 * <p>Removing a {@code ReactorControllerBlock} that still holds fuel pops every assembly as an ItemEntity
 * ({@code ReactorControllerBlock.onRemove}). That is right for a player, but a fixture's chunks are force-loaded and
 * persistent, so the popped items are saved with the chunk and ticked again by every later run (they age only a few
 * hundred ticks per run); a long-lived run directory collected tens of thousands of them and the server tick went
 * quadratic. A fixture therefore calls {@link #drainFuel} <em>before</em> it removes a controller, which takes the
 * fuel out of the block entity and drops nothing, and {@link #discardItems} afterwards as a safety net.
 *
 * <p>Neither method loads a chunk: they look only at chunks that are already loaded.
 */
final class FixtureCleanup {
    private FixtureCleanup() {
    }

    /**
     * Empties the fuel of every reactor controller block entity in the inclusive block box {@code a}..{@code b}
     * without dropping it. Returns the number of fuel stacks discarded.
     */
    static int drainFuel(ServerLevel level, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());
        int drained = 0;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (var entry : java.util.List.copyOf(chunk.getBlockEntities().values())) {
                    BlockPos pos = entry.getBlockPos();
                    if (entry instanceof ReactorControllerBlockEntity controller
                            && pos.getX() >= minX && pos.getX() <= maxX
                            && pos.getY() >= minY && pos.getY() <= maxY
                            && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                        drained += controller.takeFuelForRemoval().size();
                    }
                }
            }
        }
        return drained;
    }

    /** Empties the controller at {@code pos}, if one is there and loaded, of its fuel, dropping nothing. */
    static int drainAt(ServerLevel level, BlockPos pos) {
        return drainFuel(level, pos, pos);
    }

    /** {@link #drainFuel} for the box {@code origin.offset(lo)}..{@code origin.offset(hi)}. */
    static int drainFuel(ServerLevel level, BlockPos origin, BlockPos lo, BlockPos hi) {
        return drainFuel(level, origin.offset(lo), origin.offset(hi));
    }

    /**
     * Discards every bwr ItemEntity (fuel assemblies, rods, casings and so on) strictly inside the inclusive block box
     * {@code a}..{@code b}. Items of other mods and of vanilla are left alone. Returns how many were discarded.
     */
    static int discardItems(ServerLevel level, BlockPos a, BlockPos b) {
        AABB bounds = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0, Math.max(a.getY(), b.getY()) + 1.0, Math.max(a.getZ(), b.getZ()) + 1.0);
        int discarded = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (bounds.contains(item.position()) && "bwr".equals(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getNamespace())) {
                item.discard();
                discarded++;
            }
        }
        return discarded;
    }

    /** {@link #discardItems} for the box {@code origin.offset(lo)}..{@code origin.offset(hi)}. */
    static int discardItems(ServerLevel level, BlockPos origin, BlockPos lo, BlockPos hi) {
        return discardItems(level, origin.offset(lo), origin.offset(hi));
    }
}
