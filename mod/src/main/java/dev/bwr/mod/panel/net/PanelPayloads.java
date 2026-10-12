package dev.bwr.mod.panel.net;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire format of the control panel packets. A component is addressed by
 * {@code (owner block position, component id)}; every client-to-server payload
 * is re-validated by {@link dev.bwr.mod.panel.PanelOps}, so nothing here is
 * trusted, only decoded with bounded sizes.
 */
public final class PanelPayloads {
    private PanelPayloads() {
    }

    /** Most value changes one delta packet carries (the server batches per chunk and tick). */
    public static final int MAX_DELTAS = 4096;

    // ---- client to server

    /** Place the component item in hand with its top-left at this cell of this block. */
    public record Place(BlockPos pos, int cx, int cy, boolean offHand) implements CustomPacketPayload {
        public static final Type<Place> TYPE = new Type<>(BwrMod.id("panel_place"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Place> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Place::pos,
                ByteBufCodecs.VAR_INT, Place::cx,
                ByteBufCodecs.VAR_INT, Place::cy,
                ByteBufCodecs.BOOL, Place::offHand,
                Place::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Remove a component with the wrench in hand. */
    public record Remove(BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = new Type<>(BwrMod.id("panel_remove"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Remove> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Remove::pos,
                ByteBufCodecs.VAR_INT, Remove::id,
                Remove::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Apply a config edit ({@code Label}, {@code Config}, {@code Inputs}); see PanelOps#configure. */
    public record Configure(BlockPos pos, int id, CompoundTag edit) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(BwrMod.id("panel_configure"));
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

    /** Press / release / turn / set on an input component. {@code action} is an OperateAction ordinal. */
    public record Operate(BlockPos pos, int id, int action, double value) implements CustomPacketPayload {
        public static final Type<Operate> TYPE = new Type<>(BwrMod.id("panel_operate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Operate> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Operate::pos,
                ByteBufCodecs.VAR_INT, Operate::id,
                ByteBufCodecs.VAR_INT, Operate::action,
                ByteBufCodecs.DOUBLE, Operate::value,
                Operate::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- server to client

    /** The whole panel state of one block entity (components, claims, live values). */
    public record State(BlockPos pos, CompoundTag tag) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(BwrMod.id("panel_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, State::pos,
                ByteBufCodecs.COMPOUND_TAG, State::tag,
                State::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Bulk content changes of one component (see ComponentData#drainPatch), bounded by {@code ComponentData.MAX_PATCH}. */
    public record Content(BlockPos pos, int id, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Content> TYPE = new Type<>(BwrMod.id("panel_content"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Content> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Content::pos,
                ByteBufCodecs.VAR_INT, Content::id,
                ByteBufCodecs.byteArray(dev.bwr.mod.panel.component.ComponentData.MAX_PATCH), Content::bytes,
                Content::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Open the config screen of a component (server-initiated, for the vanilla right-click path). */
    public record OpenConfig(BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<OpenConfig> TYPE = new Type<>(BwrMod.id("panel_open_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenConfig> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenConfig::pos,
                ByteBufCodecs.VAR_INT, OpenConfig::id,
                OpenConfig::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One changed live value. */
    public record Entry(BlockPos pos, int id, boolean output, int index, double value) {
    }

    /** Batched live-value changes for one chunk and tick. */
    public record Delta(List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Delta> TYPE = new Type<>(BwrMod.id("panel_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Delta> STREAM_CODEC = StreamCodec.of(
                (buf, delta) -> {
                    buf.writeVarInt(delta.entries.size());
                    for (Entry entry : delta.entries) {
                        buf.writeBlockPos(entry.pos);
                        buf.writeVarInt(entry.id);
                        buf.writeBoolean(entry.output);
                        buf.writeVarInt(entry.index);
                        buf.writeDouble(entry.value);
                    }
                },
                buf -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_DELTAS) {
                        throw new io.netty.handler.codec.DecoderException("panel delta too large: " + count);
                    }
                    List<Entry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        entries.add(new Entry(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(),
                                buf.readVarInt(), buf.readDouble()));
                    }
                    return new Delta(entries);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
