package dev.bwr.mod.panel;

import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player token buckets for the panel packets that cost the server more than they cost the client: a clear hover
 * relayed to every nearby player, a name push or a screen open that scans the loaded panels, a configure that re-sends
 * a whole panel, an operate that fires sinks. Every limit is generous (a burst, then a steady rate far above what a
 * hand or a screen produces), so quick legitimate use is never refused; only a flood is. The state is per player and is
 * dropped when the player leaves ({@link #forget}).
 */
public final class PanelRateLimit {
    private PanelRateLimit() {
    }

    /** What is limited: the burst size (tokens a full bucket holds) and the refill per server tick. */
    public enum Kind {
        /** A hover that clears the lines; each relay goes to every player in the share distance. */
        HOVER_CLEAR(10, 0.5),
        /** "Name linked components": scans every panel in link range and resolves its devices. */
        NAME_LINKED(6, 0.1),
        /** Opening the Networking screen: the same scan. */
        OPEN_SCREEN(6, 0.2),
        /** A configure ends in a full panel sync to every watcher. */
        CONFIGURE(10, 0.2),
        /** A press, turn or release; a held control re-sends about once a tick, a drag a little more. */
        OPERATE(200, 4.0),
        /** A craft request at the fabrication desk (FABRICATION-DESK.md); a shift-click makes up to eight crafts in one. */
        FABRICATE(10, 0.5);

        final int burst;
        final double perTick;

        Kind(int burst, double perTick) {
            this.burst = burst;
            this.perTick = perTick;
        }
    }

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }

    private static final Map<UUID, Map<Kind, Bucket>> BUCKETS = new HashMap<>();

    /** Takes one token for {@code player}; false when the bucket is empty (the packet is to be dropped). */
    public static boolean tryAcquire(ServerPlayer player, Kind kind) {
        return tryAcquire(player.getUUID(), kind, player.server.getTickCount());
    }

    /** {@link #tryAcquire(ServerPlayer, Kind)} at an explicit tick (the logic itself, for tests). */
    public static boolean tryAcquire(UUID player, Kind kind, long now) {
        Bucket bucket = BUCKETS.computeIfAbsent(player, id -> new EnumMap<>(Kind.class))
                .computeIfAbsent(kind, k -> new Bucket(k.burst, now));
        if (now > bucket.last) {
            bucket.tokens = Math.min(kind.burst, bucket.tokens + (now - bucket.last) * kind.perTick);
        }
        bucket.last = now;
        if (bucket.tokens < 1.0) {
            return false;
        }
        bucket.tokens -= 1.0;
        return true;
    }

    /** Forgets a player's buckets (they left). */
    public static void forget(UUID player) {
        BUCKETS.remove(player);
    }

    /** Number of players with state, for tests. */
    public static int tracked() {
        return BUCKETS.size();
    }
}
