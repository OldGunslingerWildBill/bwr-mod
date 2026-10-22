package dev.bwr.mod.radiation;

import dev.bwr.mod.BwrMod;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The slow service behind the area radiation monitors, in place of a ticker on every block: loaded monitors register when
 * their chunk loads and drop out when they are removed or unload, and one server tick handler walks them once a second
 * (every {@link #REFRESH_TICKS} ticks) to update their lamp. With no monitor loaded the handler is one integer test.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class RadiationMonitors {
    /** Ticks between lamp refreshes: 1 Hz. */
    public static final int REFRESH_TICKS = 20;

    private RadiationMonitors() {
    }

    private static final Map<Level, Set<AreaRadiationMonitorBlockEntity>> LOADED = new WeakHashMap<>();

    static synchronized void add(AreaRadiationMonitorBlockEntity monitor) {
        Level level = monitor.getLevel();
        if (level != null && !level.isClientSide()) {
            LOADED.computeIfAbsent(level, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(monitor);
        }
    }

    static synchronized void remove(AreaRadiationMonitorBlockEntity monitor) {
        Set<AreaRadiationMonitorBlockEntity> set = LOADED.get(monitor.getLevel());
        if (set != null) {
            set.remove(monitor);
        }
    }

    /** How many monitors are registered in {@code level} (tests, diagnostics). */
    public static synchronized int loadedCount(Level level) {
        Set<AreaRadiationMonitorBlockEntity> set = LOADED.get(level);
        return set == null ? 0 : set.size();
    }

    /** Refreshes the lamps of every loaded monitor in {@code level} now. */
    public static void refreshAll(Level level) {
        List<AreaRadiationMonitorBlockEntity> snapshot;
        synchronized (RadiationMonitors.class) {
            Set<AreaRadiationMonitorBlockEntity> set = LOADED.get(level);
            if (set == null || set.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(set);
        }
        for (AreaRadiationMonitorBlockEntity monitor : snapshot) {
            if (monitor.isRemoved() || !level.isLoaded(monitor.getBlockPos())) {
                remove(monitor);
                continue;
            }
            monitor.refresh();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % REFRESH_TICKS != 0) {
            return;
        }
        long probe = dev.bwr.mod.TickProbe.start();
        try {
            for (var level : event.getServer().getAllLevels()) {
                refreshAll(level);
            }
        } finally {
            dev.bwr.mod.TickProbe.stop("RadiationMonitors", probe);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (RadiationMonitors.class) {
            LOADED.clear();
        }
    }
}
