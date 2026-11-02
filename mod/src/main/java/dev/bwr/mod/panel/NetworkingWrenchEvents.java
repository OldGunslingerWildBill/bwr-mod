package dev.bwr.mod.panel;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A plain click with the Panel Wrench or the Networking Wrench never operates the block it hits. Many plant devices open
 * their own screen or turn on a plain right-click, which would swallow the click before the tool saw it, and a valve or door
 * must not move because someone only meant to select it or look at its signals; with a tool in either hand the block's own
 * use is skipped (the way vanilla skips it for a sneaking player) so the item decides ({@link BwrTools}). The off hand counts:
 * the main hand's click would otherwise reach the block with an empty hand. Panels keep their own handling.
 */
@EventBusSubscriber(modid = "bwr")
public final class NetworkingWrenchEvents {
    private NetworkingWrenchEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (BwrTools.holdsBwrTool(event.getEntity())
                && !(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof PanelBlock)) {
            event.setUseBlock(TriState.FALSE);
        }
    }
}
