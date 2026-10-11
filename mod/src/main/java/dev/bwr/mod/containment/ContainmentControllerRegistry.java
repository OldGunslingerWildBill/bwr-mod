package dev.bwr.mod.containment;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Loaded controller index. Edits visit one chunk's controllers, not every room. */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class ContainmentControllerRegistry {
    private ContainmentControllerRegistry() {}

    private static final Map<Level, Set<BlockPos>> ACTIVE = new WeakHashMap<>();
    private static final Map<Level, Map<Long, Set<BlockPos>>> BY_CHUNK = new WeakHashMap<>();

    static void register(ContainmentControllerBlockEntity controller) {
        Level level = controller.getLevel();
        if (level != null && !level.isClientSide())
            ACTIVE.computeIfAbsent(level, unused -> new HashSet<>())
                    .add(controller.getBlockPos().immutable());
    }

    static void unregister(ContainmentControllerBlockEntity controller) {
        Level level = controller.getLevel();
        if (level == null) return;
        Set<BlockPos> active = ACTIVE.get(level);
        if (active != null) active.remove(controller.getBlockPos());
        unindex(controller);
    }

    static void index(ContainmentControllerBlockEntity controller) {
        register(controller);
        unindex(controller);
        Level level = controller.getLevel();
        if (level == null || controller.interiorMin() == null || controller.interiorMax() == null) return;
        Map<Long, Set<BlockPos>> chunks = BY_CHUNK.computeIfAbsent(level, unused -> new HashMap<>());
        int depth = ContainmentConfig.STRENGTH_SCAN_DEPTH.get();
        BlockPos min = controller.interiorMin();
        BlockPos max = controller.interiorMax();
        for (int cx = (min.getX() - depth) >> 4; cx <= (max.getX() + depth) >> 4; cx++)
            for (int cz = (min.getZ() - depth) >> 4; cz <= (max.getZ() + depth) >> 4; cz++) {
                long chunk = ChunkPos.asLong(cx, cz);
                chunks.computeIfAbsent(chunk, unused -> new HashSet<>()).add(controller.getBlockPos().immutable());
                controller.indexedChunks().add(chunk);
            }
    }

    private static void unindex(ContainmentControllerBlockEntity controller) {
        Level level = controller.getLevel();
        Map<Long, Set<BlockPos>> chunks = level == null ? null : BY_CHUNK.get(level);
        if (chunks != null) for (long chunk : controller.indexedChunks()) {
            Set<BlockPos> positions = chunks.get(chunk);
            if (positions == null) continue;
            positions.remove(controller.getBlockPos());
            if (positions.isEmpty()) chunks.remove(chunk);
        }
        controller.indexedChunks().clear();
    }

    /** At most the controllers whose bounds cover this chunk are examined. */
    public static ContainmentControllerBlockEntity linkedReactor(Level level, BlockPos reactorPos) {
        Map<Long, Set<BlockPos>> chunks = BY_CHUNK.get(level);
        if (chunks == null) return null;
        Set<BlockPos> candidates = chunks.get(ChunkPos.asLong(reactorPos));
        if (candidates == null) return null;
        Set<BlockPos> active = ACTIVE.get(level);
        if (active == null) return null;
        for (BlockPos pos : candidates) {
            if (!active.contains(pos) || !level.isLoaded(pos)) continue;
            ContainmentControllerBlockEntity controller = level.getBlockEntity(pos)
                    instanceof ContainmentControllerBlockEntity found ? found : null;
            if (controller != null && !controller.isRemoved() && level.isLoaded(pos)
                    && controller.isSealed() && controller.strengthReady()
                    && controller.contains(reactorPos)) return controller;
        }
        return null;
    }

    private static void changed(Level level, BlockPos edited) {
        if (level.isClientSide()) return;
        Map<Long, Set<BlockPos>> chunks = BY_CHUNK.get(level);
        if (chunks == null) return;
        Set<BlockPos> candidates = chunks.get(ChunkPos.asLong(edited));
        if (candidates == null) return;
        Set<BlockPos> active = ACTIVE.get(level);
        if (active == null) return;
        for (BlockPos pos : candidates) {
            if (!active.contains(pos) || !level.isLoaded(pos)) continue;
            ContainmentControllerBlockEntity controller = level.getBlockEntity(pos)
                    instanceof ContainmentControllerBlockEntity found ? found : null;
            if (controller != null && controller.watches(edited)) controller.blockChanged(edited);
        }
    }

    /** A datapack strength change invalidates ratings without rebuilding room geometry. */
    static void strengthsReloaded() {
        for (var entry : ACTIVE.entrySet()) {
            Level level = entry.getKey();
            for (BlockPos pos : entry.getValue())
                if (level.isLoaded(pos)
                        && level.getBlockEntity(pos) instanceof ContainmentControllerBlockEntity controller)
                    controller.markStrengthDirty();
        }
    }

    @SubscribeEvent
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level) changed(level, event.getPos());
    }

    @SubscribeEvent
    public static void broken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level) changed(level, event.getPos());
    }
}
