package dev.bwr.mod.panel;

import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.networking.NetworkShare;
import dev.bwr.mod.panel.networking.NetworkingOps;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.UUID;

/** Drops the small per-player server maps of the panel layer when a player leaves, so they do not grow for ever. */
@EventBusSubscriber(modid = "bwr")
public final class PanelPlayerCleanup {
    private PanelPlayerCleanup() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        PanelPlayerSessions.begin(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity().getUUID());
    }

    /** The cleanup itself, for tests. */
    public static void forget(UUID player) {
        PanelPlayerSessions.end(player);
        PanelRateLimit.forget(player);
        NetworkShare.forget(player);
        NetworkingOps.forget(player);
        MimicOps.forget(player);
    }
}
