package dev.bwr.mod.panel.networking;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Wire format of the Networking Wrench packets. Everything a client sends is re-validated by
 * {@link NetworkingOps} / {@link NetworkShare}; nothing here is trusted, only decoded with bounded sizes.
 */
public final class NetworkingPayloads {
    private NetworkingPayloads() {
    }

    /** What a hover points at; the ordinals travel in the packets. */
    public static final int KIND_NONE = 0;
    public static final int KIND_DEVICE = 1;
    public static final int KIND_COMPONENT = 2;

    /** The holder's client says what the wrench points at now (or nothing). Sent only when it changes. */
    public record Hover(int kind, BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<Hover> TYPE = new Type<>(BwrMod.id("network_hover"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hover> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Hover::kind,
                BlockPos.STREAM_CODEC, Hover::pos,
                ByteBufCodecs.VAR_INT, Hover::id,
                Hover::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The server tells nearby players what {@code sender} is pointing at. It carries no binding lists. */
    public record HoverRelay(UUID sender, int kind, BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<HoverRelay> TYPE = new Type<>(BwrMod.id("network_hover_relay"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HoverRelay> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, HoverRelay::sender,
                ByteBufCodecs.VAR_INT, HoverRelay::kind,
                BlockPos.STREAM_CODEC, HoverRelay::pos,
                ByteBufCodecs.VAR_INT, HoverRelay::id,
                HoverRelay::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- machine names (part 3)

    /** The Networking screen's "Name linked components" button for the machine at {@code device}. */
    public record NameLinked(BlockPos device) implements CustomPacketPayload {
        public static final Type<NameLinked> TYPE = new Type<>(BwrMod.id("network_name_linked"));
        public static final StreamCodec<RegistryFriendlyByteBuf, NameLinked> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, NameLinked::device,
                NameLinked::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The panel config screen's "Use machine name" button for one component. */
    public record UseMachineName(BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<UseMachineName> TYPE = new Type<>(BwrMod.id("network_use_machine_name"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UseMachineName> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, UseMachineName::pos,
                ByteBufCodecs.VAR_INT, UseMachineName::id,
                UseMachineName::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Longest name and template a client may send (the server cleans and cuts them further). */
    public static final int MAX_TEXT = 64;

    /** {@link Edit#port} values: leave the port as it is, or give the machine no port. Any other value is a port to set. */
    public static final int PORT_KEEP = -1;
    public static final int PORT_CLEAR = 10_000;

    /**
     * The Networking screen's Done: the machine's name, caption template and CC port. {@code port} is
     * {@link #PORT_KEEP}, {@link #PORT_CLEAR} or a port from 0 to 9999; anything else is refused whole.
     */
    public record Edit(BlockPos device, String name, String template, int port) implements CustomPacketPayload {
        /** An edit that leaves the CC port alone (every edit before ports existed). */
        public Edit(BlockPos device, String name, String template) {
            this(device, name, template, PORT_KEEP);
        }

        public static final Type<Edit> TYPE = new Type<>(BwrMod.id("network_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Edit::device,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Edit::name,
                ByteBufCodecs.stringUtf8(MAX_TEXT), Edit::template,
                ByteBufCodecs.VAR_INT, Edit::port,
                Edit::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
