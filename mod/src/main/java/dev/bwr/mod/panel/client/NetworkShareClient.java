package dev.bwr.mod.panel.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.VisualEffectsConfig;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part 2, client side. Sends what this player's wrench points at (only when the target changes, at most four a
 * second, and a clear when it is empty or the wrench is put away) and keeps what other players point at, to be
 * drawn by {@link NetworkLines} from this client's own loaded panel data, a little fainter and tinted with the
 * sharing player's colour. The server already filtered by its share distance; here the smaller client
 * {@code networkLineViewDistance} applies (0 hides everyone's lines).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class NetworkShareClient {
    private NetworkShareClient() {
    }

    /** One other player's hover, tinted with their name colour. */
    public record Shared(NetworkHover hover, float[] tint) {
    }

    private record Entry(NetworkHover hover, float[] tint) {
    }

    /** Ticks between two packets (4 a second). */
    private static final int SEND_TICKS = 5;

    private static final Map<UUID, Entry> OTHERS = new HashMap<>();
    private static final List<Shared> VISIBLE = new ArrayList<>();
    private static NetworkHover lastSent = NetworkHover.NONE;
    private static int sinceSend = SEND_TICKS;
    private static long sent;

    /** Hover packets sent since the game started, for the opt-in client check. */
    public static long debugSent() { return sent; }

    /** A relay arrived. */
    public static void receive(NetworkingPayloads.HoverRelay relay) {
        if (relay.kind() == NetworkingPayloads.KIND_NONE) {
            OTHERS.remove(relay.sender());
            return;
        }
        NetworkHover hover = new NetworkHover(relay.kind(), relay.pos(), relay.id());
        OTHERS.put(relay.sender(), new Entry(hover, tint(relay.sender())));
    }

    /** The sharing player's colour: their team colour when they have one, else a steady colour from their id. */
    private static float[] tint(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
        int rgb;
        if (player != null && player.getTeam() != null && player.getTeam().getColor().getColor() != null) {
            rgb = player.getTeam().getColor().getColor();
        } else {
            rgb = Mth.hsvToRgb((id.hashCode() & 0xFFFF) / 65536.0f, 0.65f, 1.0f);
        }
        return new float[] {(rgb >> 16 & 0xFF) / 255.0f, (rgb >> 8 & 0xFF) / 255.0f, (rgb & 0xFF) / 255.0f};
    }

    /** The other players' hovers worth drawing now: in this level and within the client's view distance. */
    public static List<Shared> visible(Minecraft minecraft) {
        VISIBLE.clear();
        if (OTHERS.isEmpty() || minecraft.level == null || minecraft.player == null) {
            return VISIBLE;
        }
        int view = Math.min(VisualEffectsConfig.networkLineViewDistance(), PanelConfig.linkRange());
        double limit = (double) view * view;
        var it = OTHERS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Player player = minecraft.level.getPlayerByUUID(entry.getKey());
            if (player == null) {
                it.remove(); // left, or changed dimension
                continue;
            }
            if (view > 0 && player.distanceToSqr(minecraft.player) <= limit) {
                VISIBLE.add(new Shared(entry.getValue().hover(), entry.getValue().tint()));
            }
        }
        return VISIBLE;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            lastSent = NetworkHover.NONE;
            return;
        }
        sinceSend++;
        NetworkHover now = NetworkHover.current(minecraft);
        if (now.equals(lastSent) || sinceSend < SEND_TICKS) {
            return;
        }
        lastSent = now;
        sinceSend = 0;
        sent++;
        PacketDistributor.sendToServer(new NetworkingPayloads.Hover(now.kind(), now.pos(), now.id()));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        OTHERS.clear();
        lastSent = NetworkHover.NONE;
    }
}
