package dev.bwr.mod.panel;

import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Delayed releases for momentary inputs: a pushbutton that is let go, a spring
 * return, and the fail-safe that lets go of an input whose client stopped
 * repeating PRESS (disconnect, closed game). Panels have no block ticker, so
 * this is the one small server-wide list that gets processed each tick; it is
 * empty, and costs one {@code isEmpty()}, unless somebody is holding a control.
 *
 * <p>Entries are never persisted (outputs of momentary controls are not saved)
 * and an entry whose chunk is no longer loaded is simply dropped; this never
 * loads a chunk.
 */
@EventBusSubscriber(modid = "bwr")
public final class PanelTimers {
    private PanelTimers() {
    }

    /** A component type that wants a callback when its timer runs out. */
    public interface Timed {
        void timerFired(ServerLevel level, PanelBlockEntity owner, PanelComponent component);
    }

    private record Entry(ResourceKey<Level> dimension, BlockPos pos, int id, long due) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    /** Arms (or re-arms) the timer of one component; it fires {@code ticks} game ticks from now. */
    public static synchronized void schedule(ServerLevel level, BlockPos owner, int id, int ticks) {
        cancel(level, owner, id);
        ENTRIES.add(new Entry(level.dimension(), owner.immutable(), id, level.getGameTime() + Math.max(1, ticks)));
    }

    public static synchronized void cancel(ServerLevel level, BlockPos owner, int id) {
        ENTRIES.removeIf(e -> e.id == id && e.pos.equals(owner) && e.dimension == level.dimension());
    }

    /** Number of armed timers, for tests. */
    public static synchronized int pending() {
        return ENTRIES.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<Entry> fire = null;
        synchronized (PanelTimers.class) {
            if (ENTRIES.isEmpty()) {
                return;
            }
            for (Iterator<Entry> it = ENTRIES.iterator(); it.hasNext(); ) {
                Entry entry = it.next();
                ServerLevel level = event.getServer().getLevel(entry.dimension);
                if (level == null || !level.isLoaded(entry.pos)) {
                    it.remove();
                } else if (level.getGameTime() >= entry.due) {
                    it.remove();
                    if (fire == null) {
                        fire = new ArrayList<>();
                    }
                    fire.add(entry);
                }
            }
        }
        if (fire == null) {
            return;
        }
        for (Entry entry : fire) {
            ServerLevel level = event.getServer().getLevel(entry.dimension);
            if (level != null && level.isLoaded(entry.pos) && level.getBlockEntity(entry.pos) instanceof PanelBlockEntity be) {
                PanelComponent component = be.component(entry.id);
                if (component != null && component.type() instanceof Timed timed) {
                    timed.timerFired(level, be, component);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (PanelTimers.class) {
            ENTRIES.clear();
        }
    }
}
