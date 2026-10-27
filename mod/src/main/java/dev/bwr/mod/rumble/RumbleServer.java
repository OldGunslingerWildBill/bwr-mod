package dev.bwr.mod.rumble;

import dev.bwr.core.rumble.RumbleLaw;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import dev.bwr.mod.BwrMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Keeps the active rumble sources of each level and tells each player what they feel (RUMBLE.md).
 *
 * <p>Every {@value #PERIOD_TICKS} ticks (4 Hz) it takes the sources of every level that has any, and for each player <i>of that
 * level</i> works out the combined intensity with {@link RumbleLaw}. The player does not have to be near, tracking the source or in a
 * loaded chunk around it: only the sources and the player's position are used, so someone 8,000 blocks away is told as surely as someone beside the
 * pool. It never touches a chunk, never iterates blocks, and does nothing at all while no source exists. A packet goes out only
 * when the answer moved by more than a few per cent (or every {@value #HEARTBEAT_TICKS} ticks while something is felt, so a lost
 * state cannot stay forever); a player whose sources stopped, or who left the level, is sent one zero.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class RumbleServer {
    private RumbleServer() {}

    /** Send period: 4 Hz at most per player. */
    public static final int PERIOD_TICKS = 5;
    /** While something is felt and nothing changes, repeat the state this often. */
    public static final int HEARTBEAT_TICKS = 40;
    /** Most sources one level keeps; beyond it the weakest is dropped. */
    public static final int MAX_SOURCES_PER_LEVEL = 512;

    static final class Src {
        double x, y, z, powerW;
        Rumble.Kind kind;
        long expire, start;
        int tau;
    }

    private static final class LevelSources {
        final ServerLevel level;
        final Map<Long, Src> map = new HashMap<>();
        LevelSources(ServerLevel level) { this.level = level; }
    }

    private static final class Sent {
        final ServerPlayer player;
        RumblePayload last;
        long tick;
        Sent(ServerPlayer player) { this.player = player; }
    }

    private static final Map<ResourceKey<Level>, LevelSources> LEVELS = new HashMap<>();
    private static final Map<UUID, Sent> SENT = new HashMap<>();
    private static volatile BiConsumer<ServerPlayer, RumblePayload> sender = (player, payload) -> PacketDistributor.sendToPlayer(player, payload);

    // ---- sources

    static void put(ServerLevel level, long key, Vec3 pos, Rumble.Kind kind, double powerW, long expire, long start, int tau) {
        if (level == null || level.isClientSide()) return;
        if (!(powerW > 0) || !Double.isFinite(powerW) || !Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) {
            remove(level, key);
            return;
        }
        var ls = LEVELS.computeIfAbsent(level.dimension(), k -> new LevelSources(level));
        Src s = ls.map.get(key);
        if (s == null) {
            if (ls.map.size() >= MAX_SOURCES_PER_LEVEL) {
                Long weakest = null;
                double w = powerW;
                for (var e : ls.map.entrySet()) if (e.getValue().powerW < w) { w = e.getValue().powerW; weakest = e.getKey(); }
                if (weakest == null) return;
                ls.map.remove(weakest);
            }
            s = new Src();
            ls.map.put(key, s);
        }
        s.x = pos.x; s.y = pos.y; s.z = pos.z;
        s.powerW = powerW;
        s.kind = kind;
        s.expire = expire;
        s.start = start;
        s.tau = tau;
    }

    static void remove(ServerLevel level, long key) {
        var ls = LEVELS.get(level.dimension());
        if (ls != null) ls.map.remove(key);
    }

    /** Number of live sources in a level (tests and the status line). */
    public static int sourceCount(ServerLevel level) {
        var ls = LEVELS.get(level.dimension());
        return ls == null ? 0 : ls.map.size();
    }

    /** Power, W, of one source as last set (0 if there is none). For tests and status lines. */
    public static double powerOf(ServerLevel level, long key) {
        var ls = LEVELS.get(level.dimension());
        Src s = ls == null ? null : ls.map.get(key);
        return s == null ? 0 : s.powerW;
    }

    /** Forget every source and every player's state. For tests and server stop. */
    public static void clearAll() {
        LEVELS.clear();
        SENT.clear();
    }

    /** Replace the packet sender (tests). Returns the previous one. */
    public static BiConsumer<ServerPlayer, RumblePayload> setSender(BiConsumer<ServerPlayer, RumblePayload> next) {
        var old = sender;
        sender = next;
        return old;
    }

    // ---- what a player feels

    /** Combined state at a point from a set of sources at a game time. {@link RumblePayload#NONE} if nothing reaches it. */
    static RumblePayload feel(Collection<Src> sources, double px, double py, double pz, long now) {
        double ground = 0, machine = 0, best = 0, bx = 0, by = 0, bz = 0, bd = 0;
        for (Src s : sources) {
            if (s.expire < now) continue;
            double p = s.tau > 0 ? s.powerW * Math.exp(-(double) Math.max(0, now - s.start) / s.tau) : s.powerW;
            double dx = s.x - px, dy = s.y - py, dz = s.z - pz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double raw = s.kind.profile.raw(p, d);
            if (raw <= 0) continue;
            if (s.kind == Rumble.Kind.TURBINE) machine += raw * raw; else ground += raw * raw;
            if (raw > best) { best = raw; bx = dx; by = dy; bz = dz; bd = d; }
        }
        ground = Math.sqrt(ground);
        machine = Math.sqrt(machine);
        if (ground < RumbleLaw.PERCEPTION) ground = 0;
        if (machine < RumbleLaw.PERCEPTION) machine = 0;
        if (ground == 0 && machine == 0) return RumblePayload.NONE;
        double inv = bd > 1e-6 ? 1.0 / bd : 0;
        return new RumblePayload((float) ground, (float) machine, (float) (bx * inv), (float) (by * inv), (float) (bz * inv), (float) bd);
    }

    private static boolean differs(float a, float b) {
        float m = Math.max(a, b);
        return m > 0 && Math.abs(a - b) > 0.08f * m;
    }

    private static boolean meaningful(RumblePayload last, RumblePayload now) {
        if (last.isNone() != now.isNone()) return true;
        if (now.isNone()) return false;
        if (differs(last.pool(), now.pool()) || differs(last.turbine(), now.turbine()) || differs(last.distance(), now.distance())) return true;
        float dot = last.dx() * now.dx() + last.dy() * now.dy() + last.dz() * now.dz();
        return dot < 0.985f && now.distance() > 1;
    }

    private static void update(ServerPlayer player, RumblePayload now, long tick, Set<UUID> seen) {
        Sent s = SENT.get(player.getUUID());
        if (s == null) {
            if (now.isNone()) return;
            s = new Sent(player);
            SENT.put(player.getUUID(), s);
            s.last = RumblePayload.NONE;
        }
        if (seen != null) seen.add(player.getUUID());
        boolean beat = !now.isNone() && tick - s.tick >= HEARTBEAT_TICKS;
        if (meaningful(s.last, now) || beat) {
            sender.accept(player, now);
            s.last = now;
            s.tick = tick;
        }
        if (now.isNone() && s.last.isNone()) SENT.remove(player.getUUID());
    }

    /**
     * One pass. {@code playersOf} gives the players of a level (the game passes {@code ServerLevel::players}). Public so tests can drive
     * it with a game time of their own and their own players.
     */
    public static void step(long now, Function<ServerLevel, ? extends Collection<ServerPlayer>> playersOf) {
        if (LEVELS.isEmpty() && SENT.isEmpty()) return;
        Set<UUID> seen = new HashSet<>();
        for (Iterator<LevelSources> it = LEVELS.values().iterator(); it.hasNext(); ) {
            LevelSources ls = it.next();
            ls.map.values().removeIf(s -> s.expire < now);
            if (ls.map.isEmpty()) { it.remove(); continue; }
            for (ServerPlayer p : playersOf.apply(ls.level)) {
                if (p.isRemoved()) continue;
                update(p, feel(ls.map.values(), p.getX(), p.getY(), p.getZ(), now), now, seen);
            }
        }
        for (Iterator<Map.Entry<UUID, Sent>> it = SENT.entrySet().iterator(); it.hasNext(); ) {
            Sent s = it.next().getValue();
            if (seen.contains(s.player.getUUID())) continue;
            // Nothing in the level this player is in any more (sources stopped, or they changed dimension, or left).
            if (!s.last.isNone() && !s.player.isRemoved()) sender.accept(s.player, RumblePayload.NONE);
            it.remove();
        }
    }

    // ---- events

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (LEVELS.isEmpty() && SENT.isEmpty()) return;
        long now = event.getServer().overworld().getGameTime();
        if (now % PERIOD_TICKS == 0) step(now, ServerLevel::players);
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        clearAll();
    }
}
