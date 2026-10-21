package dev.bwr.mod.panel.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.wiring.PanelNetworkIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Lets go of the client-side panel state that points into a level when the player leaves it: the GPU buffers of the
 * mesh cache and the set of panels behind the Networking Wrench lines. Both used to be cleaned only while a level was
 * rendering or one block entity at a time, so a disconnect left them holding the old level.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelClientCleanup {
    private PanelClientCleanup() {
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        releaseAll();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            releaseAll();
        }
    }

    /** Frees everything now (also used by the client checks). */
    public static void releaseAll() {
        PanelMeshCache.releaseAll();
        PanelNetworkIndex.clear();
    }
}
