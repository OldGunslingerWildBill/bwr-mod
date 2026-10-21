package dev.bwr.mod.panel.networking;

import dev.bwr.mod.panel.LinkTools;
import dev.bwr.mod.panel.PanelConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part 2: sharing wrench lines. A player holding the Networking Wrench who points at a device or a component sends
 * one small hover packet when the target changes (at most four a second). The server relays it to the players within
 * the share distance, in the same dimension; each receiver draws the lines from the panels it has loaded itself. The
 * relay carries only the target, never a binding list, and nothing here acts on the plant.
 */
public final class NetworkShare {
    private NetworkShare() {
    }

    /** Ticks between two relays from one player (4 a second is 5 ticks; the slack tolerates a late client tick). */
    private static final int MIN_TICKS = 3;
    private static final Map<UUID, Long> LAST = new HashMap<>();

    /**
     * The players that should see {@code sender}'s lines: everyone else in the sender's dimension within
     * {@code distance} blocks. A distance of 0 (or less) is nobody.
     */
    public static List<ServerPlayer> recipients(ServerPlayer sender, int distance) {
        return recipients(sender, distance, sender.server.getPlayerList().getPlayers());
    }

    /** {@link #recipients(ServerPlayer, int)} over an explicit set of candidates (the filter itself, for tests). */
    public static List<ServerPlayer> recipients(ServerPlayer sender, int distance, java.util.Collection<ServerPlayer> candidates) {
        List<ServerPlayer> out = new ArrayList<>();
        if (distance <= 0) {
            return out;
        }
        double limit = (double) distance * distance;
        for (ServerPlayer other : candidates) {
            if (other != sender && other.level() == sender.level() && other.distanceToSqr(sender) <= limit) {
                out.add(other);
            }
        }
        return out;
    }

    /** The distance a relay uses now: the server's share distance, never more than the link range. */
    public static int shareDistance() {
        return PanelConfig.networkLineShareDistance();
    }

    /**
     * Handles a hover packet from {@code sender}: validates it and relays it. A target without the wrench in hand is
     * a clear. Returns how many players were sent it (clears count too), or -1 when it was dropped.
     */
    public static int handle(ServerPlayer sender, int kind, BlockPos pos, int id) {
        if (kind < NetworkingPayloads.KIND_NONE || kind > NetworkingPayloads.KIND_COMPONENT) {
            return -1;
        }
        if (!dev.bwr.mod.world.AssemblyAccess.mayOperate(sender)) {
            return -1;
        }
        if (LinkTools.heldWrench(sender).isEmpty()) {
            kind = NetworkingPayloads.KIND_NONE;
        }
        if (kind == NetworkingPayloads.KIND_NONE
                && !dev.bwr.mod.panel.PanelRateLimit.tryAcquire(sender, dev.bwr.mod.panel.PanelRateLimit.Kind.HOVER_CLEAR)) {
            // A clear is not subject to the 3-tick spacing (it must always follow a target), but a stream of them
            // is a flood: each one goes to every player in range.
            return -1;
        }
        long now = sender.level().getGameTime();
        Long last = LAST.get(sender.getUUID());
        if (kind != NetworkingPayloads.KIND_NONE && last != null && now >= last && now - last < MIN_TICKS) {
            return -1;
        }
        LAST.put(sender.getUUID(), now);
        BlockPos at = kind == NetworkingPayloads.KIND_NONE ? BlockPos.ZERO : pos;
        int count = 0;
        for (ServerPlayer other : recipients(sender, shareDistance())) {
            PacketDistributor.sendToPlayer(other, new NetworkingPayloads.HoverRelay(sender.getUUID(), kind, at,
                    kind == NetworkingPayloads.KIND_COMPONENT ? id : 0));
            count++;
        }
        return count;
    }

    /** Forgets a player's throttle state (they left). */
    public static void forget(UUID player) {
        LAST.remove(player);
    }
}
