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

import java.lang.ref.WeakReference;
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
 * and this never loads a chunk. When an entry's chunk stops being loaded, the
 * entry fires at once instead of at its due time: a chunk leaves the loaded set
 * some ticks before its block entities are unloaded (and the panel's own unload
 * hook lets go of what is held), and in that gap a held control's command would
 * otherwise keep acting on a plant device in a chunk that stays loaded. The panel
 * is reached through a weak reference taken when the timer was armed, never
 * through the world.
 */
@EventBusSubscriber(modid = "bwr")
public final class PanelTimers {
    private PanelTimers() {
    }

    /** A component type that wants a callback when its timer runs out. */
    public interface Timed {
        void timerFired(ServerLevel level, PanelBlockEntity owner, PanelComponent component);
    }

    private record Entry(ResourceKey<Level> dimension, BlockPos pos, int id, long due, WeakReference<PanelBlockEntity> panel) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    /** Arms (or re-arms) the timer of one component; it fires {@code ticks} game ticks from now. */
    public static synchronized void schedule(ServerLevel level, BlockPos owner, int id, int ticks) {
        cancel(level, owner, id);
        // The owner is loaded here: the timer is armed by something acting on it.
        PanelBlockEntity panel = level.getBlockEntity(owner) instanceof PanelBlockEntity be ? be : null;
        ENTRIES.add(new Entry(level.dimension(), owner.immutable(), id, level.getGameTime() + Math.max(1, ticks),
                new WeakReference<>(panel)));
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
        List<Entry> leaving = null;
        synchronized (PanelTimers.class) {
            if (ENTRIES.isEmpty()) {
                return;
            }
            for (Iterator<Entry> it = ENTRIES.iterator(); it.hasNext(); ) {
                Entry entry = it.next();
                ServerLevel level = event.getServer().getLevel(entry.dimension);
                if (level == null || !level.isLoaded(entry.pos)) {
                    it.remove();
                    if (level != null) {
                        if (leaving == null) {
                            leaving = new ArrayList<>();
                        }
                        leaving.add(entry);
                    }
                } else if (level.getGameTime() >= entry.due) {
                    it.remove();
                    if (fire == null) {
                        fire = new ArrayList<>();
                    }
                    fire.add(entry);
                }
            }
        }
        if (leaving != null) {
            // The chunk has gone but the panel object has not been unloaded yet: fire the timer now through the panel
            // itself (its unload hook would do the same a few ticks later). Once the panel is removed, that hook has
            // already let go, so there is nothing to do.
            for (Entry entry : leaving) {
                PanelBlockEntity be = entry.panel.get();
                ServerLevel level = event.getServer().getLevel(entry.dimension);
                if (be != null && !be.isRemoved() && level != null) {
                    PanelComponent component = be.component(entry.id);
                    if (component != null && component.type() instanceof Timed timed) {
                        timed.timerFired(level, be, component);
                    }
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
