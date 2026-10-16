package dev.bwr.mod.panel.mimic;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The mimic tape's packets. Nothing is trusted: every client-to-server payload goes through {@link MimicOps}, which
 * re-checks the item in hand, the reach, the permission and every number.
 */
public final class MimicPayloads {
    private MimicPayloads() {
    }

    /** The tape used on a panel block: the cursor in cells from the top-left of that block's face. */
    public record Use(BlockPos pos, double cu, double cv, boolean offHand) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(BwrMod.id("mimic_use"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Use> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Use::pos,
                ByteBufCodecs.DOUBLE, Use::cu,
                ByteBufCodecs.DOUBLE, Use::cv,
                ByteBufCodecs.BOOL, Use::offHand,
                Use::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sneak-scroll with the tape: move its mode on by {@code steps}. */
    public record Mode(int steps, boolean offHand) implements CustomPacketPayload {
        public static final Type<Mode> TYPE = new Type<>(BwrMod.id("mimic_mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Mode> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Mode::steps,
                ByteBufCodecs.BOOL, Mode::offHand,
                Mode::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A style edit from the path screen: the fields of {@link MimicStyle#SCHEMA}. */
    public record Configure(BlockPos pos, int id, CompoundTag edit) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(BwrMod.id("mimic_configure"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Configure> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Configure::pos,
                ByteBufCodecs.VAR_INT, Configure::id,
                ByteBufCodecs.COMPOUND_TAG, Configure::edit,
                Configure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: open the style screen of this path. */
    public record OpenConfig(BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<OpenConfig> TYPE = new Type<>(BwrMod.id("mimic_open_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenConfig> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenConfig::pos,
                ByteBufCodecs.VAR_INT, OpenConfig::id,
                OpenConfig::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Use.TYPE, Use.STREAM_CODEC, MimicPayloads::handleUse);
        registrar.playToServer(Mode.TYPE, Mode.STREAM_CODEC, MimicPayloads::handleMode);
        registrar.playToServer(Configure.TYPE, Configure.STREAM_CODEC, MimicPayloads::handleConfigure);
        registrar.playToClient(OpenConfig.TYPE, OpenConfig.STREAM_CODEC, MimicPayloads::handleOpenConfig);
    }

    private static void handleUse(Use payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            MimicOps.use(sender, payload.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, payload.pos(),
                    payload.cu(), payload.cv());
        }
    }

    private static void handleMode(Mode payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender && payload.steps() != 0 && Math.abs(payload.steps()) <= 8) {
            MimicOps.cycleMode(sender, payload.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    payload.steps());
        }
    }

    private static void handleConfigure(Configure payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender) {
            MimicOps.configure(sender, payload.pos(), payload.id(), payload.edit());
        }
    }

    private static void handleOpenConfig(OpenConfig payload, IPayloadContext context) {
        dev.bwr.mod.panel.client.PanelClientHooks.openMimicConfig(payload.pos(), payload.id());
    }
}
