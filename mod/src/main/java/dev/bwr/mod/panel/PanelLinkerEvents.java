package dev.bwr.mod.panel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The second step of linking a panel component first ({@link LinkSelection}): a plain right-click on a plant device
 * with the Panel Linker, while a component is selected, wires that component to the device instead of operating the
 * device. It is taken here, before the block's own use, because many plant devices open their screen on a right-click
 * and would swallow it. Without a selection, on a panel, while sneaking or on a block that reports no signals nothing
 * changes: the click goes where it always went.
 */
@EventBusSubscriber(modid = "bwr")
public final class PanelLinkerEvents {
    private PanelLinkerEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
                || player.isShiftKeyDown() || !(event.getItemStack().getItem() instanceof PanelLinkerItem)
                || event.getLevel().getBlockState(event.getPos()).getBlock() instanceof PanelBlock) {
            return;
        }
        if (LinkSelection.linkDevice(player, event.getItemStack(), event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
        }
    }
}
