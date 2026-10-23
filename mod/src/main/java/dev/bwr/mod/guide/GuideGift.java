package dev.bwr.mod.guide;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.registry.BwrAttachments;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The first-join gift: a player who joins a world with {@code giveGuideOnFirstJoin} on is given one guide book, once. The
 * "given" mark is a player attachment (saved only once set, kept through death), so it survives relogs and a lost book
 * is never replaced for free. With the option off nothing is given and nothing is marked, so a player who joined while it
 * was off still gets their book the first time they join after it is turned on.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class GuideGift {
    private GuideGift() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            giveOnFirstJoin(player);
        }
    }

    /** Gives the book if the option is on and this player has never had one; true when a book was given. */
    public static boolean giveOnFirstJoin(ServerPlayer player) {
        if (!GameplayConfig.giveGuideOnFirstJoin() || player.hasData(BwrAttachments.GUIDE_GIVEN.get())) {
            return false;
        }
        player.setData(BwrAttachments.GUIDE_GIVEN.get(), true);
        ItemStack book = new ItemStack(BwrItems.GUIDE_BOOK.get());
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        return true;
    }
}
