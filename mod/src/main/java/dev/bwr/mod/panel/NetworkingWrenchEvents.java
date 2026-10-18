package dev.bwr.mod.panel;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A plain click with the Networking Wrench selects the device. Many plant devices open their own screen on a plain
 * right-click, which would swallow the click before the wrench saw it; with the wrench in hand the block's own use is
 * skipped (the way vanilla skips it for a sneaking player) so the item decides. Panels keep their own handling.
 */
@EventBusSubscriber(modid = "bwr")
public final class NetworkingWrenchEvents {
    private NetworkingWrenchEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().getItem() instanceof NetworkingWrenchItem
                && !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof PanelBlock)) {
            event.setUseBlock(TriState.FALSE);
        }
    }
}
