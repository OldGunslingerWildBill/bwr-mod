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

    // ---- wiring: linker and binding screens

    /** Most signals or commands one {@link LinkerScreen} lists. */
    public static final int MAX_LISTED = 512;
    public static final int MAX_KEY = 64;
    public static final int MAX_LABEL = 96;

    /** Why a linker screen has no device lists. */
    public static final int LINK_OK = 0;
    public static final int LINK_NONE = 1;
    public static final int LINK_WRONG_DIMENSION = 2;
    public static final int LINK_OUT_OF_RANGE = 3;
    public static final int LINK_MISSING = 4;

    /** Ask the server to open the binding screen for a component (linker in hand). */
    public record LinkerOpen(BlockPos pos, int id) implements CustomPacketPayload {
        public static final Type<LinkerOpen> TYPE = new Type<>(BwrMod.id("panel_linker_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LinkerOpen> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, LinkerOpen::pos,
                ByteBufCodecs.VAR_INT, LinkerOpen::id,
                LinkerOpen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Bind or unbind one port of a component. {@code kind} is a {@link dev.bwr.mod.panel.wiring.WiringOps.BindKind}
     * ordinal; the fields a kind does not use are ignored. {@code refOutput} doubles as the sink position for a
     * remove. {@code sinkMode} (a {@code PlantSink.Mode} ordinal) and {@code fixed} apply to a plant output only:
     * MOMENTARY sends {@code fixed} when the output rises and ignores {@code scale}. Everything is re-validated by
     * {@code WiringOps#bind}.
     */
    public record Bind(BlockPos pos, int id, boolean output, int port, int kind, BlockPos device, String key,
                       BlockPos refOwner, int refId, int refOutput, double scale, int sinkMode, double fixed,
                       double offset)
            implements CustomPacketPayload {
        /** A request with no input offset (every binding before the input transform existed). */
        public Bind(BlockPos pos, int id, boolean output, int port, int kind, BlockPos device, String key,
                    BlockPos refOwner, int refId, int refOutput, double scale, int sinkMode, double fixed) {
            this(pos, id, output, port, kind, device, key, refOwner, refId, refOutput, scale, sinkMode, fixed, 0.0);
        }

        /** A request that follows the output (every binding before the sink mode existed). */
        public Bind(BlockPos pos, int id, boolean output, int port, int kind, BlockPos device, String key,
                    BlockPos refOwner, int refId, int refOutput, double scale) {
            this(pos, id, output, port, kind, device, key, refOwner, refId, refOutput, scale, 0, 0.0, 0.0);
        }

        public static final Type<Bind> TYPE = new Type<>(BwrMod.id("panel_bind"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Bind> STREAM_CODEC = StreamCodec.of(
                (buf, bind) -> {
                    buf.writeBlockPos(bind.pos);
                    buf.writeVarInt(bind.id);
                    buf.writeBoolean(bind.output);
                    buf.writeVarInt(bind.port);
                    buf.writeVarInt(bind.kind);
                    buf.writeBlockPos(bind.device);
                    buf.writeUtf(bind.key, MAX_KEY);
                    buf.writeBlockPos(bind.refOwner);
                    buf.writeVarInt(bind.refId);
                    buf.writeVarInt(bind.refOutput);
                    buf.writeDouble(bind.scale);
                    buf.writeVarInt(bind.sinkMode);
                    buf.writeDouble(bind.fixed);
                    buf.writeDouble(bind.offset);
                },
                buf -> new Bind(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                        buf.readVarInt(), buf.readBlockPos(), buf.readUtf(MAX_KEY), buf.readBlockPos(),
                        buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readVarInt(), buf.readDouble(),
                        buf.readDouble()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One readable signal of the linked device, as listed on the binding screen. */
    public record SignalEntry(String key, String label, String unit) {
    }

    /**
     * One command of the linked device. {@code kind} is a {@code Kind} ordinal and {@code min}/{@code max} the
     * accepted range, so the binding screen can offer a sensible momentary default; the server re-checks.
     */
    public record CommandEntry(String key, String label, boolean pulse, int kind, double min, double max) {
        public CommandEntry(String key, String label, boolean pulse) {
            this(key, label, pulse, pulse ? dev.bwr.mod.signal.Kind.PULSE.ordinal() : dev.bwr.mod.signal.Kind.NUMBER.ordinal(),
                    0.0, 1.0);
        }
    }

    /** Opens the binding screen: the component and the linked device's signals and commands. */
    public record LinkerScreen(BlockPos pos, int id, int status, BlockPos device, String deviceName,
                               List<SignalEntry> signals, List<CommandEntry> commands) implements CustomPacketPayload {
        public static final Type<LinkerScreen> TYPE = new Type<>(BwrMod.id("panel_linker_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LinkerScreen> STREAM_CODEC = StreamCodec.of(
                (buf, screen) -> {
                    buf.writeBlockPos(screen.pos);
                    buf.writeVarInt(screen.id);
                    buf.writeVarInt(screen.status);
                    buf.writeBlockPos(screen.device);
                    buf.writeUtf(screen.deviceName, MAX_LABEL);
                    buf.writeVarInt(screen.signals.size());
                    for (SignalEntry entry : screen.signals) {
                        buf.writeUtf(entry.key, MAX_KEY);
                        buf.writeUtf(entry.label, MAX_LABEL);
                        buf.writeUtf(entry.unit, MAX_KEY);
                    }
                    buf.writeVarInt(screen.commands.size());
                    for (CommandEntry entry : screen.commands) {
                        buf.writeUtf(entry.key, MAX_KEY);
                        buf.writeUtf(entry.label, MAX_LABEL);
                        buf.writeBoolean(entry.pulse);
                        buf.writeVarInt(entry.kind);
                        buf.writeDouble(entry.min);
                        buf.writeDouble(entry.max);
                    }
                },
                buf -> {
                    BlockPos pos = buf.readBlockPos();
                    int id = buf.readVarInt();
                    int status = buf.readVarInt();
                    BlockPos device = buf.readBlockPos();
                    String name = buf.readUtf(MAX_LABEL);
                    int signalCount = buf.readVarInt();
                    if (signalCount < 0 || signalCount > MAX_LISTED) {
                        throw new io.netty.handler.codec.DecoderException("panel signal list too large: " + signalCount);
                    }
                    List<SignalEntry> signals = new ArrayList<>(signalCount);
                    for (int i = 0; i < signalCount; i++) {
                        signals.add(new SignalEntry(buf.readUtf(MAX_KEY), buf.readUtf(MAX_LABEL), buf.readUtf(MAX_KEY)));
                    }
                    int commandCount = buf.readVarInt();
                    if (commandCount < 0 || commandCount > MAX_LISTED) {
                        throw new io.netty.handler.codec.DecoderException("panel command list too large: " + commandCount);
                    }
                    List<CommandEntry> commands = new ArrayList<>(commandCount);
                    for (int i = 0; i < commandCount; i++) {
                        commands.add(new CommandEntry(buf.readUtf(MAX_KEY), buf.readUtf(MAX_LABEL), buf.readBoolean(),
                                buf.readVarInt(), buf.readDouble(), buf.readDouble()));
                    }
                    return new LinkerScreen(pos, id, status, device, name, signals, commands);
                });

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
