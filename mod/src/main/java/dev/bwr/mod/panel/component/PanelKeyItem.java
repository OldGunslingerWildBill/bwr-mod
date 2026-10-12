package dev.bwr.mod.panel.component;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A panel key. Its code is its custom name (rename it in an anvil), so one
 * ring of keys can be cut for several key switches without any extra data
 * component; an unnamed key has the empty code. A {@link KeySwitchType} that
 * requires a key is operable only while a key with the switch's code is in
 * either hand.
 */
public final class PanelKeyItem extends Item {
    public PanelKeyItem(Properties properties) {
        super(properties);
    }

    /** The code of a key stack: its custom name, or "" when it has none. */
    public static String code(ItemStack stack) {
        net.minecraft.network.chat.Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    /** Whether {@code player} holds a key with this code in either hand. */
    public static boolean holdsKey(ServerPlayer player, String code) {
        if (player == null) {
            return false;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof PanelKeyItem && code(stack).equals(code)) {
                return true;
            }
        }
        return false;
    }
}
