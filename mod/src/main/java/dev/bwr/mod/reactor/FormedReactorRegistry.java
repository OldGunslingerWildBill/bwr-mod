package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.*;

/** Server-thread index updated by formation/removal, independent of flow measurements. */
public final class FormedReactorRegistry {
    private static final Map<Level, Set<BlockPos>> FORMED = new WeakHashMap<>();
    private FormedReactorRegistry() {}

    public static void add(ReactorControllerBlockEntity controller) {
        Level level = controller.getLevel();
        if (level != null && !level.isClientSide() && !controller.isRemoved() && controller.isFormed())
            FORMED.computeIfAbsent(level, unused -> new LinkedHashSet<>()).add(controller.getBlockPos().immutable());
    }

    public static void remove(ReactorControllerBlockEntity controller) {
        var positions = FORMED.get(controller.getLevel());
        if (positions != null) positions.remove(controller.getBlockPos());
    }

    public static List<ReactorControllerBlockEntity> controllers(Level level) {
        var positions = FORMED.get(level);
        if (positions == null) return List.of();
        List<ReactorControllerBlockEntity> result = new ArrayList<>();
        var iterator = positions.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof ReactorControllerBlockEntity controller
                    && !controller.isRemoved() && controller.isFormed()) result.add(controller);
            else iterator.remove();
        }
        return result;
    }
}
