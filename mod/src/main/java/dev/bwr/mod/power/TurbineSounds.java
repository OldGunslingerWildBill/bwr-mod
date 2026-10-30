package dev.bwr.mod.power;

import dev.bwr.core.turbine.ShaftDynamics;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.core.turbine.TurbineFire;
import dev.bwr.mod.registry.BwrSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What a turbine accident sounds like (TURBINE-MAINTENANCE.md, "Sounds"): an effect only, played from the server; nothing reads anything
 * back and the plant never depends on it (no save changes, no state).
 *
 * <h2>How far a sound carries</h2>
 * The bang and the excursion carry for kilometres, to every player of the level in range, which vanilla cannot do: they are
 * played by {@link BlastSounds}, which sends each player a packet with the gain, the variant mix and the delay worked out for them. The rest are vanilla
 * sounds from {@link ServerLevel#playSound}: Minecraft sends one to the players within {@code 16 x volume} blocks (a volume under 1
 * reaches 16) and the client fades it linearly over that distance, at full loudness (the volume is capped at 1) up close, so the volumes
 * below are the ranges in blocks divided by 16. Nothing is loaded to play a sound.
 *
 * <h2>The pseudo-loops</h2>
 * A server cannot start a looping sound, and the client does not know a turbine's speed or whether a section burns (neither is synced).
 * So the whine and the fires are one-shots of {@value #LOOP_SECONDS} s that overlap by 0.6 s, played once every {@link #PERIOD_TICKS}
 * ticks by the steps that already run once a second ({@code PowerTrain.tick} and {@link TurbineFires#second}); each fades in and out
 * with equal-power curves (tools-make-turbine-sounds.py), so two copies make a steady level. When the step stops calling (the fire is out,
 * the shaft slowed, the chunk unloaded) the last copy fades away within {@value #LOOP_SECONDS} s.
 */
public final class TurbineSounds {
    private TurbineSounds() {}

    /** The impact is a vanilla sound: its volume, and so its range in blocks, is 16 x volume (see above). */
    public static final float IMPACT_VOLUME = 5f;
    /** The bang of a blade, and of a disc fragment, which is deeper. */
    public static final float BANG_BLADE_PITCH = 1.0f, BANG_DISC_PITCH = BlastSounds.DISC_PITCH;
    /** A piece that stops within this many blocks of where it left is within the bang's own tail: it gets no impact sound of its own. */
    public static final double IMPACT_MIN_BLOCKS = 4.0;

    /** The pseudo-loops are played every second; each sample is a little longer so the copies overlap. */
    public static final int PERIOD_TICKS = 20;
    public static final double LOOP_SECONDS = 1.6;
    /** The whine plays from just under rated speed; its volume is {@link #WHINE_RATED_VOLUME} there, rising to {@link #WHINE_MAX_VOLUME} at 150 % speed. */
    public static final double WHINE_MIN_RPM = 0.98 * ShaftDynamics.RATED_RPM;
    public static final float WHINE_RATED_VOLUME = 0.10f, WHINE_MAX_VOLUME = 2.5f;
    /** The whine's pitch follows the shaft speed (a blade-passing frequency is the speed times the blade count) within the game's limits of 0.5 to 2. */
    public static final float WHINE_MIN_PITCH = 0.5f, WHINE_MAX_PITCH = 2.0f;
    public static final float FIRE_OIL_VOLUME = 2.5f, FIRE_HYDROGEN_VOLUME = 2.0f;

    private static void play(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch);
    }

    /**
     * A missile left its rotor: the bang at the wheel it was thrown from, and, if it stopped in something well away from there, a smaller
     * metallic impact where it did. A piece that ran out of range, or reached the edge of the world or of the loaded chunks, lands nowhere
     * we know of and makes no second sound.
     */
    static void missile(ServerLevel level, TurbineBlades.Missile kind, Vec3 origin, Vec3 end, TurbineMissile.Stop stop) {
        boolean disc = kind == TurbineBlades.Missile.DISC;
        BlastSounds.play(level, BlastSounds.Kind.BANG, origin, disc ? BANG_DISC_PITCH : BANG_BLADE_PITCH);
        boolean landed = switch (stop) {
            case STOPPED_IN_BLOCK, UNBREAKABLE, PROTECTED, CASING -> true;
            case UNLOADED, OUT_OF_WORLD, RANGE, REST -> false;
        };
        if (landed && origin.distanceTo(end) > IMPACT_MIN_BLOCKS) {
            play(level, end, BwrSounds.TURBINE_MISSILE_IMPACT.get(), IMPACT_VOLUME, 1.0f);
        }
    }

    /** A rotor half tore out of its casing at {@code seat}: one long sound (the tearing, the boom, the roll, the thud) from there. */
    static void excursion(ServerLevel level, Vec3 seat) {
        BlastSounds.play(level, BlastSounds.Kind.EXCURSION, seat, 1.0f);
    }

    /** The volume of the whine at a shaft speed: {@link #WHINE_RATED_VOLUME} at rated speed, {@link #WHINE_MAX_VOLUME} at 150 % of it. */
    public static float whineVolume(double rpm) {
        double over = Mth.clamp((rpm / ShaftDynamics.RATED_RPM - 1.0) / 0.5, 0.0, 1.0);
        return (float) (WHINE_RATED_VOLUME + (WHINE_MAX_VOLUME - WHINE_RATED_VOLUME) * over);
    }

    /** The pitch of the whine at a shaft speed: 1 at rated speed, in proportion to it. */
    public static float whinePitch(double rpm) {
        return Mth.clamp((float) (rpm / ShaftDynamics.RATED_RPM), WHINE_MIN_PITCH, WHINE_MAX_PITCH);
    }

    /** Once a second, for each LP the train's step advanced: its blade-passing whine, at the pitch and volume of its speed. */
    static void whine(ServerLevel level, java.util.List<PowerModuleBlockEntity> active) {
        for (PowerModuleBlockEntity m : active) {
            if (!m.lp() || m.sheared() || !(m.rpm >= WHINE_MIN_RPM)) continue;
            var p = m.getBlockPos();
            play(level, new Vec3(p.getX() + 0.5, p.getY() + 2.5, p.getZ() + 0.5), BwrSounds.TURBINE_OVERSPEED_WHINE.get(), whineVolume(m.rpm), whinePitch(m.rpm));
        }
    }

    /** A burning section's roar for the next second: the oil fire's deep roar, or the hydrogen fire's hiss. Played where the section is. */
    static void fire(ServerLevel level, PowerModuleBlockEntity m) {
        if (!m.burning()) return;
        boolean hydrogen = m.fire() == TurbineFire.Kind.HYDROGEN;
        var p = m.getBlockPos();
        play(level, new Vec3(p.getX() + 0.5, p.getY() + 1.5, p.getZ() + 0.5),
                (hydrogen ? BwrSounds.TURBINE_FIRE_HYDROGEN : BwrSounds.TURBINE_FIRE_OIL).get(), hydrogen ? FIRE_HYDROGEN_VOLUME : FIRE_OIL_VOLUME, 1.0f);
    }
}
