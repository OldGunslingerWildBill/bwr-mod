package dev.bwr.mod.panel.networking;

import dev.bwr.mod.BwrMod;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Part 4: the packets of the machine Networking screen. The screen is opened by the server (the wrench's sneak-click
 * answers with a {@link ScreenData}); its live values come from one request and one response, asked for at most once
 * a second while the screen is open. No packet is sent per tick.
 */
public final class NetworkingScreenPayloads {
    private NetworkingScreenPayloads() {
    }

    /** Most signals, commands or links one {@link ScreenData} lists. */
    public static final int MAX_LISTED = 512;
    public static final int MAX_LINKS = 128;
    private static final int MAX_KEY = 64;
    private static final int MAX_LABEL = 96;
    private static final int MAX_TEXT = NetworkingPayloads.MAX_TEXT;

    /** Ask the server for the Networking screen of a device (opening it, or refreshing it after an unlink). */
    public record Open(BlockPos device) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(BwrMod.id("network_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Open::device,
                Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One signal row: what it is, what it measures, and how many components read it. */
    public record NetSignal(String key, String label, String unit, int quantity, int readers) {
    }

    /** One command row: what it is, its {@code Kind} ordinal, and how many components command it. */
    public record NetCommand(String key, String label, int kind, int users) {
    }

    /**
     * One line link to the device: the panel block and component, the caption it carries, the signal or command key
     * and its label, and where on the component it sits ({@code port}, and for a command the sink position) so the
     * row's Unlink can send an ordinary bind request.
     */
    public record NetLink(BlockPos panel, int id, String component, String caption, boolean command, int port, int sink,
                          String key, String keyLabel) {
    }

    /**
     * The whole Networking screen of a device. {@code port} is the CC port (-1 when none is set) and {@code portUsers}
     * how many machines claim that port on the CC network of the device (0 when unknown); {@code cc} says whether CC
     * ports apply at all (CC:Tweaked is installed).
     */
    public record ScreenData(BlockPos device, String typeKey, String name, String template, int port, int portUsers,
                             boolean cc, List<NetSignal> signals, List<NetCommand> commands, List<NetLink> links)
            implements CustomPacketPayload {
        public static final Type<ScreenData> TYPE = new Type<>(BwrMod.id("network_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenData> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    buf.writeBlockPos(data.device);
                    buf.writeUtf(data.typeKey, MAX_LABEL);
                    buf.writeUtf(data.name, MAX_TEXT);
                    buf.writeUtf(data.template, MAX_TEXT);
                    buf.writeVarInt(data.port + 1);
                    buf.writeVarInt(data.portUsers);
                    buf.writeBoolean(data.cc);
                    buf.writeVarInt(data.signals.size());
                    for (NetSignal signal : data.signals) {
                        buf.writeUtf(signal.key, MAX_KEY);
                        buf.writeUtf(signal.label, MAX_LABEL);
                        buf.writeUtf(signal.unit, MAX_KEY);
                        buf.writeVarInt(signal.quantity);
                        buf.writeVarInt(signal.readers);
                    }
                    buf.writeVarInt(data.commands.size());
                    for (NetCommand command : data.commands) {
                        buf.writeUtf(command.key, MAX_KEY);
                        buf.writeUtf(command.label, MAX_LABEL);
                        buf.writeVarInt(command.kind);
                        buf.writeVarInt(command.users);
                    }
                    buf.writeVarInt(data.links.size());
                    for (NetLink link : data.links) {
                        buf.writeBlockPos(link.panel);
                        buf.writeVarInt(link.id);
                        buf.writeUtf(link.component, MAX_LABEL);
                        buf.writeUtf(link.caption, MAX_LABEL);
                        buf.writeBoolean(link.command);
                        buf.writeVarInt(link.port);
                        buf.writeVarInt(link.sink);
                        buf.writeUtf(link.key, MAX_KEY);
                        buf.writeUtf(link.keyLabel, MAX_LABEL);
                    }
                },
                buf -> {
                    BlockPos device = buf.readBlockPos();
                    String typeKey = buf.readUtf(MAX_LABEL);
                    String name = buf.readUtf(MAX_TEXT);
                    String template = buf.readUtf(MAX_TEXT);
                    int port = buf.readVarInt() - 1;
                    int portUsers = buf.readVarInt();
                    boolean cc = buf.readBoolean();
                    int signalCount = buf.readVarInt();
                    if (signalCount < 0 || signalCount > MAX_LISTED) {
                        throw new DecoderException("network signal list too large: " + signalCount);
                    }
                    List<NetSignal> signals = new ArrayList<>(signalCount);
                    for (int i = 0; i < signalCount; i++) {
                        signals.add(new NetSignal(buf.readUtf(MAX_KEY), buf.readUtf(MAX_LABEL), buf.readUtf(MAX_KEY),
                                buf.readVarInt(), buf.readVarInt()));
                    }
                    int commandCount = buf.readVarInt();
                    if (commandCount < 0 || commandCount > MAX_LISTED) {
                        throw new DecoderException("network command list too large: " + commandCount);
                    }
                    List<NetCommand> commands = new ArrayList<>(commandCount);
                    for (int i = 0; i < commandCount; i++) {
                        commands.add(new NetCommand(buf.readUtf(MAX_KEY), buf.readUtf(MAX_LABEL), buf.readVarInt(),
                                buf.readVarInt()));
                    }
                    int linkCount = buf.readVarInt();
                    if (linkCount < 0 || linkCount > MAX_LINKS) {
                        throw new DecoderException("network link list too large: " + linkCount);
                    }
                    List<NetLink> links = new ArrayList<>(linkCount);
                    for (int i = 0; i < linkCount; i++) {
                        links.add(new NetLink(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(MAX_LABEL),
                                buf.readUtf(MAX_LABEL), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                                buf.readUtf(MAX_KEY), buf.readUtf(MAX_LABEL)));
                    }
                    return new ScreenData(device, typeKey, name, template, port, portUsers, cc, signals, commands, links);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The open screen asks for the live values of its device (at most once a second). */
    public record ValuesRequest(BlockPos device) implements CustomPacketPayload {
        public static final Type<ValuesRequest> TYPE = new Type<>(BwrMod.id("network_values_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ValuesRequest> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ValuesRequest::device,
                ValuesRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The live values, as text, in the order of the screen's signal list. */
    public record Values(BlockPos device, List<String> values) implements CustomPacketPayload {
        public static final Type<Values> TYPE = new Type<>(BwrMod.id("network_values"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Values> STREAM_CODEC = StreamCodec.of(
                (buf, data) -> {
                    buf.writeBlockPos(data.device);
                    buf.writeVarInt(data.values.size());
                    for (String value : data.values) {
                        buf.writeUtf(value, MAX_LABEL);
                    }
                },
                buf -> {
                    BlockPos device = buf.readBlockPos();
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_LISTED) {
                        throw new DecoderException("network value list too large: " + count);
                    }
                    List<String> values = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        values.add(buf.readUtf(MAX_LABEL));
                    }
                    return new Values(device, values);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
