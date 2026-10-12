package dev.bwr.mod.panel.net;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.OperateAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the panel packets on the mod's shared registrar (so they ride the
 * protocol version declared in {@code BwrGuiNetwork}) and handles them.
 *
 * <p>Handlers run on the main thread. Server handlers do nothing but call
 * {@link PanelOps}, which validates; client handlers only touch common types
 * except {@link #handleOpenConfig}, which reaches the client package.
 */
public final class PanelNetwork {
    private PanelNetwork() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(PanelPayloads.Place.TYPE, PanelPayloads.Place.STREAM_CODEC, PanelNetwork::handlePlace);
        registrar.playToServer(PanelPayloads.Remove.TYPE, PanelPayloads.Remove.STREAM_CODEC, PanelNetwork::handleRemove);
        registrar.playToServer(PanelPayloads.Configure.TYPE, PanelPayloads.Configure.STREAM_CODEC,
                PanelNetwork::handleConfigure);
        registrar.playToServer(PanelPayloads.Operate.TYPE, PanelPayloads.Operate.STREAM_CODEC, PanelNetwork::handleOperate);
        registrar.playToClient(PanelPayloads.State.TYPE, PanelPayloads.State.STREAM_CODEC, PanelNetwork::handleState);
        registrar.playToClient(PanelPayloads.Delta.TYPE, PanelPayloads.Delta.STREAM_CODEC, PanelNetwork::handleDelta);
        registrar.playToClient(PanelPayloads.OpenConfig.TYPE, PanelPayloads.OpenConfig.STREAM_CODEC,
                PanelNetwork::handleOpenConfig);
    }

    // ---- server

    private static void handlePlace(PanelPayloads.Place payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.place(sender, payload.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    payload.pos(), payload.cx(), payload.cy());
        }
    }

    private static void handleRemove(PanelPayloads.Remove payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.remove(sender, payload.pos(), payload.id());
        }
    }

    private static void handleConfigure(PanelPayloads.Configure payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.configure(sender, payload.pos(), payload.id(), payload.edit());
        }
    }

    private static void handleOperate(PanelPayloads.Operate payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.operate(sender, payload.pos(), payload.id(), OperateAction.byId(payload.action()), payload.value());
        }
    }

    // ---- client

    private static PanelBlockEntity panelAt(IPayloadContext context, BlockPos pos) {
        Level level = context.player().level();
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
    }

    private static void handleState(PanelPayloads.State payload, IPayloadContext context) {
        PanelBlockEntity be = panelAt(context, payload.pos());
        if (be != null) {
            be.applyNetworkState(payload.tag());
        }
    }

    private static void handleDelta(PanelPayloads.Delta payload, IPayloadContext context) {
        for (PanelPayloads.Entry entry : payload.entries()) {
            PanelBlockEntity be = panelAt(context, entry.pos());
            if (be != null) {
                be.applyDelta(new PanelBlockEntity.ValueDelta(entry.id(), entry.output(), entry.index(), entry.value()));
            }
        }
    }

    private static void handleOpenConfig(PanelPayloads.OpenConfig payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.PanelClientHooks.openConfig(payload.pos(), payload.id());
    }
}
