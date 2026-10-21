package dev.bwr.mod.panel.board;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends the server's premade boards to a client on login and on {@code /reload}, so the blueprint's ghost and tooltip
 * work on a dedicated server. Like the fuel registry, the boards are server data: the client only draws from them.
 * One packet per board (the packet is the board's JSON, parsed again by the client with the same parser), preceded by a
 * packet that says how many boards there are, so a board removed by a reload disappears on the client too.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class BoardTemplateSync {
    private BoardTemplateSync() {
    }

    private static final String VERSION = "1";

    /** One board, as the JSON text of its file. {@code first} starts a new set and drops the client's old boards. */
    public record Payload(ResourceLocation id, String json, boolean first) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Payload> TYPE = new CustomPacketPayload.Type<>(BwrMod.id("board_template"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Payload::id,
                ByteBufCodecs.stringUtf8(BoardTemplates.MAX_JSON_CHARS + 16), Payload::json,
                ByteBufCodecs.BOOL, Payload::first,
                Payload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A set with no boards at all: the client forgets the ones it had. */
    public record Clear() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Clear> TYPE = new CustomPacketPayload.Type<>(BwrMod.id("board_template_clear"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Clear> CODEC = StreamCodec.unit(new Clear());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client side: the set being received. */
    private static final Map<ResourceLocation, BoardTemplate> RECEIVING = new LinkedHashMap<>();
    private static final Map<ResourceLocation, String> RECEIVING_JSON = new LinkedHashMap<>();

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(VERSION);
        registrar.playToClient(Payload.TYPE, Payload.CODEC, BoardTemplateSync::receive);
        registrar.playToClient(Clear.TYPE, Clear.CODEC, (payload, context) -> context.enqueueWork(() -> BoardTemplates.install(Map.of(), Map.of())));
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Map<ResourceLocation, String> sources = BoardTemplates.sources();
        event.getRelevantPlayers().forEach(player -> {
            if (sources.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new Clear());
                return;
            }
            boolean first = true;
            for (Map.Entry<ResourceLocation, String> entry : sources.entrySet()) {
                PacketDistributor.sendToPlayer(player, new Payload(entry.getKey(), entry.getValue(), first));
                first = false;
            }
        });
    }

    private static void receive(Payload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.first()) {
                RECEIVING.clear();
                RECEIVING_JSON.clear();
            }
            BoardTemplate template = BoardTemplates.parseLogged(payload.id(), payload.json(), "server");
            if (template != null) {
                RECEIVING.put(payload.id(), template);
                RECEIVING_JSON.put(payload.id(), payload.json());
            }
            // Installed after every packet: the set is complete when the last one arrives, and a half set is still usable.
            BoardTemplates.install(RECEIVING, RECEIVING_JSON);
        });
    }
}
