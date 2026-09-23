package dev.bwr.mod.gui.net;

import dev.bwr.mod.gui.BwrMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The two packets every screen in the mod runs on: a snapshot down, a command
 * up. Registered from {@code BwrMod}.
 *
 * <p>Both handlers are dist-agnostic on purpose. They only ever touch
 * {@link Player#containerMenu}, which is a common type, so nothing here has to
 * be guarded against loading on a dedicated server and there is no client-only
 * class in the call path.
 */
public final class BwrGuiNetwork {

    /** Bumped when the wire format of any menu snapshot changes incompatibly. */
    private static final String VERSION = "5";

    private BwrGuiNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToClient(MenuSyncPayload.TYPE, MenuSyncPayload.STREAM_CODEC,
                BwrGuiNetwork::handleSync);
        registrar.playToServer(MenuCommandPayload.TYPE, MenuCommandPayload.STREAM_CODEC,
                BwrGuiNetwork::handleCommand);
    }

    private static void handleSync(MenuSyncPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player.containerMenu instanceof BwrMenu menu
                && menu.containerId == payload.containerId()) {
            menu.acceptSnapshot(payload.data());
        }
    }

    private static void handleCommand(MenuCommandPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer sender)) {
            return;
        }
        // Three gates, all of them ownership rather than judgement: the player
        // must have this menu open, the command must be for that menu, and the
        // block must still be there and in reach. Whether the command is a good
        // idea is not checked, here or anywhere.
        if (sender.containerMenu instanceof BwrMenu menu
                && menu.containerId == payload.containerId()
                && menu.stillValid(sender)) {
            menu.handleCommand(sender, payload.command(), payload.a(), payload.b());
        }
    }
}
