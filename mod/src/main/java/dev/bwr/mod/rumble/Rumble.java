package dev.bwr.mod.rumble;

import dev.bwr.core.rumble.RumbleLaw;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The one door through which anything makes the ground shake and the air hum (RUMBLE.md): a pool taking in relief valve steam, a
 * turbine train shaking on its bearings, and later a missile or a blast. An effect only: it reads nothing back and nothing in the plant
 * ever depends on it.
 *
 * <h2>Three ways in</h2>
 * <ul>
 *   <li>{@link #sustain(ServerLevel, BlockPos, Kind, double)}: a source that goes on, refreshed by its owner at least every
 *       {@link #LAPSE_TICKS} ticks (the pool and the turbine hook do it every fifth tick, from tickers that already exist). Stop
 *       calling and it fades out on its own, so a broken block or an unloaded chunk can never leave a rumble running.</li>
 *   <li>{@link #emit(ServerLevel, Vec3, Kind, double)}: a short burst of a given power, about a second long.</li>
 *   <li>{@link #impulse(ServerLevel, Vec3, double)}: a one-shot with an energy in joules, which dies away over a few seconds. This is the
 *       call for a missile landing or a blast.</li>
 * </ul>
 * All of them are safe to call from any server-thread code, any number of times; they cost a map write. Delivery to players
 * (every player of the level, in range or not, however far) is {@link RumbleServer}'s job.
 */
public final class Rumble {
    private Rumble() {}

    /** How a source's energy reaches people; chooses the distance law and the sound. */
    public enum Kind {
        /** Steam condensing in a suppression pool: ground-borne, irregular chugging. */
        POOL(RumbleLaw.Profile.GROUND),
        /** A shaft train shaking: building-borne, steady. */
        TURBINE(RumbleLaw.Profile.MACHINE),
        /** One-shot energy from a missile or a blast: ground-borne. */
        IMPACT(RumbleLaw.Profile.GROUND);

        public final RumbleLaw.Profile profile;
        Kind(RumbleLaw.Profile profile) { this.profile = profile; }
    }

    /** A sustained source lapses this many ticks after its last refresh. */
    public static final int LAPSE_TICKS = 40;
    /** An {@link #emit} burst lasts this many ticks. */
    public static final int BURST_TICKS = 20;

    /** Stable key for a source owned by a block: its position and kind. */
    public static long keyOf(BlockPos owner, Kind kind) {
        return owner.asLong() * 3L + kind.ordinal();
    }

    /**
     * Keep a continuous source going at a power, W, at the centre of the owner block. A power of zero (or less) removes it.
     *
     * @param powerW what the source dissipates into the ground or the structure: for steam condensing in a pool, mass flow times
     *               enthalpy; for a turbine, {@link RumbleLaw#turbinePowerW}
     */
    public static void sustain(ServerLevel level, BlockPos owner, Kind kind, double powerW) {
        sustain(level, keyOf(owner, kind), Vec3.atCenterOf(owner), kind, powerW);
    }

    /** As {@link #sustain(ServerLevel, BlockPos, Kind, double)} with your own key and position. */
    public static void sustain(ServerLevel level, long key, Vec3 pos, Kind kind, double powerW) {
        RumbleServer.put(level, key, pos, kind, powerW, level.getGameTime() + LAPSE_TICKS, 0, 0);
    }

    /** As above, counting the lapse from a game time you give (for tests that drive their own clock). */
    public static void sustainAt(ServerLevel level, long key, Vec3 pos, Kind kind, double powerW, long gameTime) {
        RumbleServer.put(level, key, pos, kind, powerW, gameTime + LAPSE_TICKS, 0, 0);
    }

    /** Remove a sustained source at once. */
    public static void release(ServerLevel level, BlockPos owner, Kind kind) {
        RumbleServer.remove(level, keyOf(owner, kind));
    }

    /** A short burst, about a second, of the given power at a place. */
    public static void emit(ServerLevel level, Vec3 pos, Kind kind, double powerW) {
        long key = 0x5000_0000_0000_0000L ^ BlockPos.containing(pos).asLong() * 3L + kind.ordinal();
        RumbleServer.put(level, key, pos, kind, powerW, level.getGameTime() + BURST_TICKS, 0, 0);
    }

    /**
     * A one-shot release of energy, J, at a place (a missile landing, an explosion). Its power is the energy spread over
     * {@link RumbleLaw#IMPULSE_TAU_S} and dies away exponentially. For scale: a 100 t body at 50 m/s is 1.25e8 J, felt a few hundred
     * blocks away; ten times the energy is felt ten times as far.
     */
    public static void impulse(ServerLevel level, Vec3 pos, double energyJ) {
        if (!(energyJ > 0) || !Double.isFinite(energyJ)) return;
        long now = level.getGameTime();
        int tau = (int) Math.round(RumbleLaw.IMPULSE_TAU_S * 20);
        long key = 0x6000_0000_0000_0000L ^ (now * 1_000_003L + level.random.nextInt(1 << 20));
        RumbleServer.put(level, key, pos, Kind.IMPACT, RumbleLaw.impulsePowerW(energyJ), now + 6L * tau, now, tau);
    }

    /** The intensity a source of this power would have at a distance, after the perception limit (0 = nothing felt). */
    public static double intensityAt(Kind kind, double powerW, double distanceBlocks) {
        return kind.profile.intensity(powerW, distanceBlocks);
    }
}
