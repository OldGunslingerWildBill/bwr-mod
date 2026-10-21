package dev.bwr.mod.panel.networking;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the Networking Wrench packets on the mod's shared registrar (called from {@code PanelNetwork}) and
 * handles them. Server handlers only call {@link NetworkingOps} / {@link NetworkShare}, which validate; client
 * handlers reach the client package only from inside the handler body, so a dedicated server never loads it.
 */
public final class NetworkingNet {
    private NetworkingNet() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(NetworkingPayloads.Hover.TYPE, NetworkingPayloads.Hover.STREAM_CODEC, NetworkingNet::handleHover);
        registrar.playToServer(NetworkingPayloads.NameLinked.TYPE, NetworkingPayloads.NameLinked.STREAM_CODEC,
                NetworkingNet::handleNameLinked);
        registrar.playToServer(NetworkingPayloads.UseMachineName.TYPE, NetworkingPayloads.UseMachineName.STREAM_CODEC,
                NetworkingNet::handleUseMachineName);
        registrar.playToServer(NetworkingPayloads.Edit.TYPE, NetworkingPayloads.Edit.STREAM_CODEC, NetworkingNet::handleEdit);
        registrar.playToServer(NetworkingScreenPayloads.CcPortLookup.TYPE, NetworkingScreenPayloads.CcPortLookup.STREAM_CODEC,
                NetworkingNet::handleCcPortLookup);
        registrar.playToServer(NetworkingScreenPayloads.Open.TYPE, NetworkingScreenPayloads.Open.STREAM_CODEC,
                NetworkingNet::handleOpen);
        registrar.playToServer(NetworkingScreenPayloads.ValuesRequest.TYPE, NetworkingScreenPayloads.ValuesRequest.STREAM_CODEC,
                NetworkingNet::handleValuesRequest);
        registrar.playToClient(NetworkingScreenPayloads.ScreenData.TYPE, NetworkingScreenPayloads.ScreenData.STREAM_CODEC,
                NetworkingNet::handleScreen);
        registrar.playToClient(NetworkingScreenPayloads.Values.TYPE, NetworkingScreenPayloads.Values.STREAM_CODEC,
                NetworkingNet::handleValues);
        registrar.playToClient(NetworkingPayloads.HoverRelay.TYPE, NetworkingPayloads.HoverRelay.STREAM_CODEC,
                NetworkingNet::handleHoverRelay);
    }

    public static void handleHover(NetworkingPayloads.Hover payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            NetworkShare.handle(sender, payload.kind(), payload.pos(), payload.id());
        }
    }

    /** Public so the GameTests can drive the real handler with a decoded payload. */
    public static void handleNameLinked(NetworkingPayloads.NameLinked payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.NAME_LINKED)) {
            NetworkingOps.nameLinked(sender, payload.device());
        }
    }

    public static void handleUseMachineName(NetworkingPayloads.UseMachineName payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            NetworkingOps.useMachineName(sender, payload.pos(), payload.id());
        }
    }

    public static void handleEdit(NetworkingPayloads.Edit payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            NetworkingOps.edit(sender, payload.device(), payload.name(), payload.template(), payload.port());
        }
    }

    public static void handleCcPortLookup(NetworkingScreenPayloads.CcPortLookup payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.OPEN_SCREEN)) {
            var screen = dev.bwr.mod.panel.wiring.WiringOps.ccPortScreenFor(sender, payload.pos(), payload.id(), payload.port());
            if (screen != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sender, screen);
            }
        }
    }

    public static void handleOpen(NetworkingScreenPayloads.Open payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.OPEN_SCREEN)) {
            NetworkingOps.openScreen(sender, payload.device());
        }
    }

    public static void handleValuesRequest(NetworkingScreenPayloads.ValuesRequest payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            var values = NetworkingOps.valuesFor(sender, payload.device());
            if (values != null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sender,
                        new NetworkingScreenPayloads.Values(payload.device(), values));
            }
        }
    }

    private static void handleScreen(NetworkingScreenPayloads.ScreenData payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.NetworkingScreen.open(payload);
    }

    private static void handleValues(NetworkingScreenPayloads.Values payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.NetworkingScreen.values(payload);
    }

    private static void handleHoverRelay(NetworkingPayloads.HoverRelay payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.NetworkShareClient.receive(payload);
    }
}
