package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/**
 * Who put a block down, for the few ticks until a reactor notices that it sealed a pressure-boundary break, so that
 * the "break sealed" message goes to the player who did it. Only the last 64 placements per level are kept, and only
 * for {@link #RECENT_TICKS}; nothing is saved and nothing ticks.
 */
@EventBusSubscriber(modid = "bwr")
public final class PipePlacements {
    private PipePlacements() {}
    private record Placement(UUID player, long tick) {}
    private static final long RECENT_TICKS = 200;
    private static final Map<Level, LinkedHashMap<BlockPos, Placement>> RECENT = new WeakHashMap<>();

    @SubscribeEvent
    public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) return;
        var recent = RECENT.computeIfAbsent(level, unused -> new LinkedHashMap<>());
        recent.remove(event.getPos());
        recent.put(event.getPos().immutable(), new Placement(player.getUUID(), level.getGameTime()));
        while (recent.size() > 64) recent.remove(recent.keySet().iterator().next());
    }

    /** The player who placed a block at {@code pos} in the last {@link #RECENT_TICKS} ticks and is still here, or null. */
    public static ServerPlayer placer(ServerLevel level, BlockPos pos) {
        var recent = RECENT.get(level);
        var placement = recent == null ? null : recent.get(pos);
        if (placement == null || level.getGameTime() - placement.tick() > RECENT_TICKS) return null;
        return level.getPlayerByUUID(placement.player()) instanceof ServerPlayer player ? player : null;
    }
}
