package dev.bwr.mod.panel;

import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The two tools that can wire a panel to a plant device: the Panel Linker and the Networking Wrench. Both store
 * the selected device in the same {@code LINKED_DEVICE} component and are accepted by {@code WiringOps} through
 * {@link #held}, so a bind made with either is the same bind. The Panel Linker itself is unchanged.
 */
public final class LinkTools {
    private LinkTools() {
    }

    /** Whether {@code stack} is one of the two wiring tools. */
    public static boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof PanelLinkerItem || stack.getItem() instanceof NetworkingWrenchItem;
    }

    /** The wiring tool in either hand (main hand first), or empty. */
    public static ItemStack held(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (isTool(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The Networking Wrench in either hand, or empty. */
    public static ItemStack heldWrench(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof NetworkingWrenchItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The device stored on a tool, or null. */
    public static GlobalPos linked(ItemStack stack) {
        return PanelLinkerItem.linked(stack);
    }
}
