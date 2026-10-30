package dev.bwr.mod.floodlight;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds back the re-plan after a change of a floodlight's aim from its screen or from a computer, so a burst of clicks makes one
 * re-plan and not one a click.
 *
 * <p>The first change of a burst starts a timer of {@link #DELAY_TICKS}; changes that come while it runs only change the lamp's
 * fields (the head already follows them); when it ends the lamp re-plans once, for the aim it has by then. A burst that goes on
 * longer than the timer is cut into re-plans {@link #DELAY_TICKS} apart, so a held button costs at most one plan in four
 * ticks. This is not a ticker per block: the map holds a lamp only from a change until its re-plan, and the server tick does
 * nothing while it is empty. A lamp that was removed, or whose chunk is not loaded when the timer ends, is dropped (the
 * lamp re-plans itself the next time its chunk is ticking and it is lit).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class AimReplans {
    /** Ticks between a change of aim and the re-plan: a fifth of a second. */
    public static final int DELAY_TICKS = 4;

    /** Dimension to (lamp position to the game time its re-plan is due). Server thread only. */
    private static final Map<ResourceKey<Level>, Map<Long, Long>> DUE = new HashMap<>();

    private AimReplans() {
    }

    /** Asks for a re-plan of the lamp at {@code pos} in {@link #DELAY_TICKS}, unless one is already waiting. */
    static void request(ServerLevel level, BlockPos pos) {
        DUE.computeIfAbsent(level.dimension(), k -> new HashMap<>()).putIfAbsent(pos.asLong(), level.getGameTime() + DELAY_TICKS);
    }

    /** How many lamps are waiting for their re-plan (tests). */
    public static int waiting() {
        int n = 0;
        for (var map : DUE.values()) {
            n += map.size();
        }
        return n;
    }

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        if (DUE.isEmpty()) {
            return;
        }
        for (var it = DUE.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerLevel level = event.getServer().getLevel(entry.getKey());
            if (level == null) {
                it.remove();
                continue;
            }
            long now = level.getGameTime();
            List<Long> due = new ArrayList<>();
            for (var cell : entry.getValue().entrySet()) {
                if (cell.getValue() <= now) {
                    due.add(cell.getKey());
                }
            }
            for (long key : due) {
                entry.getValue().remove(key);
                BlockPos pos = BlockPos.of(key);
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof BeamLampBlockEntity lamp) {
                    lamp.reconcile();
                }
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        DUE.clear();
    }
}
