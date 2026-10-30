package dev.bwr.mod.power;

import dev.bwr.mod.registry.BwrSounds;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The two turbine sounds that carry for kilometres (TURBINE-MAINTENANCE.md, "Sounds"): the bang of a blade or disc thrown, and the shaft
 * excursion. Vanilla sends a sound to the players within 16 x volume blocks and fades it linearly, so it cannot do this; here the server
 * works out, for every player of the level inside {@link Kind#range}, what that player hears, and sends one {@link BlastSoundPayload}.
 * An effect only: nothing is saved, nothing reads it back, and nothing is loaded to work it out.
 *
 * <h2>Gain with distance</h2>
 * Full out to {@link Kind#nearBlocks}, then falling as {@code (near / d) ^ 0.45} (a game-friendly law, gentler than the real
 * inverse-distance one's 6 dB per doubling: about 2.7 dB), and over the last 40 % of the range a smooth fade to nothing. The bang (3 km):
 * 0.21 at 1 km, 0.14 at 2 km, 0.05 at 2.5 km, silent at 3 km. The excursion (10 km): 0.26 at 1 km, 0.16 at 3 km, 0.09 at 7 km, 0.05 at 8
 * km, 0.015 at 9 km, silent at 10 km.
 *
 * <h2>Which variant: air and walls</h2>
 * Each sound has three files, clear, distant (low-passed: air absorbs the highs) and muffled (low-passed hard: a wall). Over 150 to 600 blocks
 * the clear one crossfades into the distant one; from {@link Kind#heavyFrom} of the range to the end of it the distant one crossfades into the
 * muffled one (a far blast ends as a faint low thud; the excursion's distant file is gentler, so some of its character is still there at 6 to
 * 8 km). A wall does the same at any distance: the blocks of a ray from the source to the player's head, counted as
 * {@code 1 - exp(-n / 3)} (1 block 28 %, 3 blocks 63 %, 6 blocks 86 %) crossfade towards the muffled file and take up to 40 % off the gain.
 *
 * <h2>The ray</h2>
 * Sampled every half block, the cells counted once each; only cells of chunks that are completely loaded count
 * ({@link LoadedWorld#state}, which never loads or waits for a chunk), the others are open air. A ray longer than 2 x 96 blocks is
 * traced only for its first 96 blocks from the source and its last 96 from the player, the nearest walls at each end, so at most
 * {@value #MAX_SAMPLES} samples; the machine's own casing and the first 1.5 blocks are not counted.
 *
 * <h2>Delay</h2>
 * Sound travels 343 blocks a second; the player is told how many ticks to wait, at most {@link #MAX_DELAY_TICKS} (8 s, reached at 2.7
 * km, where the real delay would be 8 s; at 10 km it would be 29 s). The rumble and the shake ({@code Rumble.impulse}) are not delayed: a
 * shock in the ground arrives before the sound in the air.
 */
public final class BlastSounds {
    private BlastSounds() {}

    /** Which sound; its range, where it stops being full, and where its distant file gives way to the muffled one (a share of the range). */
    public enum Kind {
        BANG(3000.0, 32.0, 0.5),
        EXCURSION(10000.0, 48.0, 0.7);

        public final double range, nearBlocks, heavyFrom;

        Kind(double range, double nearBlocks, double heavyFrom) {
            this.range = range;
            this.nearBlocks = nearBlocks;
            this.heavyFrom = heavyFrom;
        }

        /** The sound event of a variant. */
        public SoundEvent event(Variant v) {
            return switch (this) {
                case BANG -> (switch (v) {
                    case CLEAR -> BwrSounds.TURBINE_MISSILE_BANG;
                    case DISTANT -> BwrSounds.TURBINE_MISSILE_BANG_DISTANT;
                    case MUFFLED -> BwrSounds.TURBINE_MISSILE_BANG_MUFFLED;
                }).get();
                case EXCURSION -> (switch (v) {
                    case CLEAR -> BwrSounds.TURBINE_EXCURSION;
                    case DISTANT -> BwrSounds.TURBINE_EXCURSION_DISTANT;
                    case MUFFLED -> BwrSounds.TURBINE_EXCURSION_MUFFLED;
                }).get();
            };
        }
    }

    public enum Variant { CLEAR, DISTANT, MUFFLED }

    /** The final gain of each variant for one listener (0 to 1 each; they sum to the gain). */
    public record Mix(double clear, double distant, double muffled) {
        public double total() { return clear + distant + muffled; }
    }

    public static final double EXPONENT = 0.45, FADE_FROM = 0.6;
    /** The clear sound gives way to the distant one between these distances, blocks. */
    public static final double DISTANT_FROM = 150, DISTANT_TO = 600;
    public static final double SPEED_OF_SOUND = 343.0;
    public static final int MAX_DELAY_TICKS = 160;
    /** A wall's muffling saturates as {@code 1 - exp(-n / OCCLUSION_BLOCKS)}; at most this share of the gain goes. */
    public static final double OCCLUSION_BLOCKS = 3.0, OCCLUDED_LOSS = 0.4;
    public static final double STEP = 0.5, SEGMENT = 96.0, SKIP_NEAR_SOURCE = 1.5;
    public static final int MAX_SAMPLES = 2 * ((int) (SEGMENT / STEP) + 1);
    /** A player who would hear less than this gets nothing sent. */
    public static final double MIN_GAIN = 0.004;
    public static final float DISC_PITCH = 0.82f;

    private static volatile BiConsumer<ServerPlayer, BlastSoundPayload> sender = (player, payload) -> PacketDistributor.sendToPlayer(player, payload);
    private static volatile Function<ServerLevel, ? extends Collection<ServerPlayer>> players = ServerLevel::players;

    /** Replace the packet sender (tests). Returns the previous one. */
    public static BiConsumer<ServerPlayer, BlastSoundPayload> setSender(BiConsumer<ServerPlayer, BlastSoundPayload> next) {
        var old = sender;
        sender = next;
        return old;
    }

    /** Replace where the players of a level come from (tests, which use players that are not in the world). Returns the previous one. */
    public static Function<ServerLevel, ? extends Collection<ServerPlayer>> setPlayers(Function<ServerLevel, ? extends Collection<ServerPlayer>> next) {
        var old = players;
        players = next;
        return old;
    }

    private static double smooth(double x) {
        x = Mth.clamp(x, 0.0, 1.0);
        return x * x * (3 - 2 * x);
    }

    /** The gain of a blast at a distance in the open: 1 near, 0 at the range. */
    public static double distanceGain(Kind kind, double distance) {
        if (!(distance < kind.range)) return 0;
        double g = distance <= kind.nearBlocks ? 1.0 : Math.pow(kind.nearBlocks / distance, EXPONENT);
        return g * (1.0 - smooth((distance - FADE_FROM * kind.range) / ((1.0 - FADE_FROM) * kind.range)));
    }

    /** How muffled {@code n} blocks of wall make a sound: 0 for none, towards 1. */
    public static double occlusion(int blocks) {
        return 1.0 - Math.exp(-Math.max(0, blocks) / OCCLUSION_BLOCKS);
    }

    /** What a listener {@code distance} away with {@code blocks} of wall in the way hears of each variant. */
    public static Mix mix(Kind kind, double distance, int blocks) {
        double wall = occlusion(blocks);
        double g = distanceGain(kind, distance) * (1.0 - OCCLUDED_LOSS * wall);
        double far = smooth((distance - kind.heavyFrom * kind.range) / ((1.0 - kind.heavyFrom) * kind.range));
        double heavy = Math.max(wall, far);
        double distant = smooth((distance - DISTANT_FROM) / (DISTANT_TO - DISTANT_FROM));
        return new Mix(g * (1 - heavy) * (1 - distant), g * (1 - heavy) * distant, g * heavy);
    }

    /** Ticks a sound takes to cross {@code distance} blocks, at most {@link #MAX_DELAY_TICKS}. */
    public static int delayTicks(double distance) {
        return (int) Math.min(MAX_DELAY_TICKS, Math.round(distance / SPEED_OF_SOUND * 20.0));
    }

    /**
     * The number of solid blocks between {@code from} (the source) and {@code to} (a player's head) in chunks that are completely loaded
     * (see the class comment): at most {@link #MAX_SAMPLES} samples, and never a chunk load.
     */
    public static int solidBlocks(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double length = d.length();
        if (!(length > 1e-6)) return 0;
        Vec3 u = d.scale(1.0 / length);
        if (length <= 2 * SEGMENT) return trace(level, from, from, to, length);
        return trace(level, from, from, from.add(u.scale(SEGMENT)), SEGMENT) + trace(level, from, to.subtract(u.scale(SEGMENT)), to, SEGMENT);
    }

    private static int trace(ServerLevel level, Vec3 source, Vec3 a, Vec3 b, double length) {
        int n = (int) Math.ceil(length / STEP);
        int count = 0;
        long last = Long.MIN_VALUE;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            Vec3 p = a.lerp(b, i / (double) n);
            if (p.distanceToSqr(source) < SKIP_NEAR_SOURCE * SKIP_NEAR_SOURCE) continue;
            cell.set(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
            if (cell.asLong() == last) continue;
            last = cell.asLong();
            BlockState state = LoadedWorld.state(level, cell);
            if (state != null && !state.isAir() && state.canOcclude() && !(state.getBlock() instanceof PowerModuleBlock)) count++;
        }
        return count;
    }

    /** What {@code ear} hears of a blast at {@code source}: the packet, or null if it is out of range or too faint. */
    static BlastSoundPayload hear(ServerLevel level, Kind kind, Vec3 source, Vec3 ear, float pitch) {
        double d = ear.distanceTo(source);
        if (!(d < kind.range)) return null;
        Mix m = mix(kind, d, d > 2 ? solidBlocks(level, source, ear) : 0);
        if (m.total() < MIN_GAIN) return null;
        return new BlastSoundPayload(kind.ordinal(), source.x, source.y, source.z, (float) m.clear(), (float) m.distant(), (float) m.muffled(),
                delayTicks(d), pitch);
    }

    /** A blast at {@code source}: every player of the level inside its range is told what they hear. */
    public static void play(ServerLevel level, Kind kind, Vec3 source, float pitch) {
        if (level.isClientSide() || !Double.isFinite(source.x) || !Double.isFinite(source.y) || !Double.isFinite(source.z)) return;
        for (ServerPlayer p : players.apply(level)) {
            if (p.isRemoved()) continue;
            BlastSoundPayload payload = hear(level, kind, source, p.getEyePosition(), pitch);
            if (payload != null) sender.accept(p, payload);
        }
    }
}
