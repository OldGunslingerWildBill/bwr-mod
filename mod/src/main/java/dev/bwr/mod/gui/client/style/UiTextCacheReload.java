package dev.bwr.mod.gui.client.style;

import dev.bwr.mod.BwrMod;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Forgets the style's shaped-text cache ({@link Ui#clearTextCache}) whenever resources reload, since fonts and languages may have changed. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class UiTextCacheReload implements ResourceManagerReloadListener {
    @SubscribeEvent
    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new UiTextCacheReload());
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        Ui.clearTextCache();
    }
}
