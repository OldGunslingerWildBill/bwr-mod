package dev.bwr.mod.guide.client;

import dev.bwr.mod.gui.BwrMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

/**
 * The menu behind the guide screen, so the screen can stand on {@code BwrScreen} like every other screen of the mod. It
 * exists only on the client: the book opens its screen without asking the server, there are no slots, no snapshot and
 * no command.
 */
public final class GuideMenu extends BwrMenu {
    public GuideMenu(int containerId, Inventory inventory) {
        super(null, containerId, inventory, BlockPos.ZERO, null);
    }

    @Override
    public boolean stillValid(net.minecraft.world.entity.player.Player who) {
        return true;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
    }
}
