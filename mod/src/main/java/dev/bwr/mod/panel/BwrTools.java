package dev.bwr.mod.panel;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The two hand tools of the control room, the Panel Wrench and the Networking Wrench. The rule for both: a player who
 * holds one of them in either hand is working on the plant, not operating it, so no block turns, toggles, opens or starts
 * under the click ({@link NetworkingWrenchEvents} skips the block's own use). The tool's own action still runs where it has
 * one: the Networking Wrench selects a device or opens its Networking screen, the Panel Wrench steps the finish of a
 * panel, trim or furniture piece on a sneak-click. Panels and their components handle the two tools themselves.
 */
public final class BwrTools {
    private BwrTools() {
    }

    /** Whether {@code stack} is the Panel Wrench or the Networking Wrench. */
    public static boolean isBwrTool(ItemStack stack) {
        return stack.getItem() instanceof PanelWrenchItem || stack.getItem() instanceof NetworkingWrenchItem;
    }

    /** Whether the player holds the Panel Wrench or the Networking Wrench in either hand. */
    public static boolean holdsBwrTool(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (isBwrTool(player.getItemInHand(hand))) {
                return true;
            }
        }
        return false;
    }
}
