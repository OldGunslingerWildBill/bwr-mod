package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Re-samples the live inputs (plant signals, redstone) of the panels that have any, at most every
 * {@link PanelConfig#sampleTicks()} ticks. A panel is tracked only while one of its inputs is live: blocks
 * register themselves when they load or are rewired and drop out when they are removed or unload, so an idle
 * panel costs nothing per tick. Changed values go out through the normal delta batching of
 * {@code PanelSync}, one packet per chunk and tick.
 */
@EventBusSubscriber(modid = "bwr")
public final class PanelSampler {
    private PanelSampler() {
    }

    private static final Set<PanelBlockEntity> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Starts or stops sampling {@code be} according to whether it has a live input. Server thread. */
    public static void track(PanelBlockEntity be) {
        if (be.getLevel() != null && !be.getLevel().isClientSide() && !be.isRemoved() && be.hasLiveInputs()) {
            LIVE.add(be);
        } else {
            LIVE.remove(be);
        }
    }

    public static void untrack(PanelBlockEntity be) {
        LIVE.remove(be);
    }

    /** How many panels are being sampled (tests, diagnostics). */
    public static int trackedCount() {
        return LIVE.size();
    }

    public static boolean isTracked(PanelBlockEntity be) {
        return LIVE.contains(be);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (LIVE.isEmpty() || event.getServer().getTickCount() % PanelConfig.sampleTicks() != 0) {
            return;
        }
        List<PanelBlockEntity> snapshot = new ArrayList<>(LIVE);
        for (PanelBlockEntity be : snapshot) {
            if (be.isRemoved() || be.getLevel() == null || !be.getLevel().isLoaded(be.getBlockPos())) {
                LIVE.remove(be);
                continue;
            }
            be.sampleLive();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LIVE.clear();
    }
}
