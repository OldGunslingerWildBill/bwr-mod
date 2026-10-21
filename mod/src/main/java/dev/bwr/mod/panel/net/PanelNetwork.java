package dev.bwr.mod.panel.net;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.wiring.WiringOps;
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
        registrar.playToServer(PanelPayloads.SetSize.TYPE, PanelPayloads.SetSize.STREAM_CODEC, PanelNetwork::handleSetSize);
        registrar.playToServer(PanelPayloads.Remove.TYPE, PanelPayloads.Remove.STREAM_CODEC, PanelNetwork::handleRemove);
        registrar.playToServer(PanelPayloads.Configure.TYPE, PanelPayloads.Configure.STREAM_CODEC,
                PanelNetwork::handleConfigure);
        registrar.playToServer(PanelPayloads.Operate.TYPE, PanelPayloads.Operate.STREAM_CODEC, PanelNetwork::handleOperate);
        registrar.playToServer(PanelPayloads.RecorderMessage.TYPE, PanelPayloads.RecorderMessage.STREAM_CODEC,
                PanelNetwork::handleRecorderMessage);
        registrar.playToServer(PanelPayloads.LinkerOpen.TYPE, PanelPayloads.LinkerOpen.STREAM_CODEC,
                PanelNetwork::handleLinkerOpen);
        registrar.playToServer(PanelPayloads.Bind.TYPE, PanelPayloads.Bind.STREAM_CODEC, PanelNetwork::handleBind);
        registrar.playToClient(PanelPayloads.LinkerScreen.TYPE, PanelPayloads.LinkerScreen.STREAM_CODEC,
                PanelNetwork::handleLinkerScreen);
        registrar.playToClient(PanelPayloads.State.TYPE, PanelPayloads.State.STREAM_CODEC, PanelNetwork::handleState);
        registrar.playToClient(PanelPayloads.Delta.TYPE, PanelPayloads.Delta.STREAM_CODEC, PanelNetwork::handleDelta);
        registrar.playToClient(PanelPayloads.Content.TYPE, PanelPayloads.Content.STREAM_CODEC, PanelNetwork::handleContent);
        registrar.playToClient(PanelPayloads.OpenConfig.TYPE, PanelPayloads.OpenConfig.STREAM_CODEC,
                PanelNetwork::handleOpenConfig);
        dev.bwr.mod.panel.mimic.MimicPayloads.register(registrar);
        dev.bwr.mod.panel.networking.NetworkingNet.register(registrar);
    }

    // ---- server

    private static void handlePlace(PanelPayloads.Place payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.place(sender, payload.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    payload.pos(), payload.cx(), payload.cy());
        }
    }

    /** Public so the GameTests can drive the real handler with a decoded payload. */
    public static void handleSetSize(PanelPayloads.SetSize payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender && dev.bwr.mod.world.AssemblyAccess.mayOperate(sender)) {
            net.minecraft.world.item.ItemStack held = sender.getItemInHand(
                    payload.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            dev.bwr.mod.panel.component.ComponentItem.select(held, sender, payload.variant());
        }
    }

    private static void handleRemove(PanelPayloads.Remove payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.remove(sender, payload.pos(), payload.id());
        }
    }

    private static void handleConfigure(PanelPayloads.Configure payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.CONFIGURE)) {
            PanelOps.configure(sender, payload.pos(), payload.id(), payload.edit());
        }
    }

    private static void handleOperate(PanelPayloads.Operate payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.OPERATE)) {
            PanelOps.operate(sender, payload.pos(), payload.id(), OperateAction.byId(payload.action()), payload.value());
        }
    }

    private static void handleRecorderMessage(PanelPayloads.RecorderMessage payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            PanelOps.recorderMessage(sender, payload.pos(), payload.id(), payload.text());
        }
    }

    private static void handleLinkerOpen(PanelPayloads.LinkerOpen payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.OPEN_SCREEN)) {
            WiringOps.openScreen(sender, payload.pos(), payload.id());
        }
    }

    /** Public so the GameTests can drive the real handler with a decoded payload. */
    public static void handleBind(PanelPayloads.Bind payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            var outcome = WiringOps.bindDetailed(sender, payload);
            if (outcome.result() != WiringOps.Result.OK) {
                sender.displayClientMessage(outcome.detail() != null ? outcome.detail()
                        : dev.bwr.mod.panel.PanelMessages.wiringRefused(outcome.result()), true);
            }
        }
    }

    // ---- client

    private static void handleLinkerScreen(PanelPayloads.LinkerScreen payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.PanelClientHooks.openBind(payload);
    }

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

    private static void handleContent(PanelPayloads.Content payload, IPayloadContext context) {
        PanelBlockEntity be = panelAt(context, payload.pos());
        if (be != null) {
            be.applyContent(payload.id(), payload.bytes());
        }
    }

    private static void handleOpenConfig(PanelPayloads.OpenConfig payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.PanelClientHooks.openConfig(payload.pos(), payload.id());
    }
}
