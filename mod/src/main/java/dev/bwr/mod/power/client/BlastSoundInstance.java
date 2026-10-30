package dev.bwr.mod.power.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * One variant of a turbine blast at the gain the server worked out (TURBINE-MAINTENANCE.md, "Sounds"). Vanilla's 16-block rolloff would cut a
 * blast kilometres away, so the sound is placed in the listener's own frame half a block toward the source (relative, with the shortest
 * attenuation it can have: 3 % off) and its volume is the final gain; it is turned every tick toward the source's direction, so it pans with
 * the turn of the head and with the player's movement. Plays in the Blocks category (the player's Blocks slider scales it).
 */
public final class BlastSoundInstance extends AbstractTickableSoundInstance {
    /** How far toward the source, blocks, the sound is placed; the linear attenuation over 16 blocks takes {@code PAN / 16} off it. */
    public static final double PAN_BLOCKS = 0.5;
    private static final double LOSS = 1.0 - PAN_BLOCKS / 16.0;

    private final Vec3 source;

    public BlastSoundInstance(SoundEvent sound, double gain, float pitch, Vec3 source) {
        super(sound, SoundSource.BLOCKS, RandomSource.create());
        this.source = source;
        this.looping = false;
        this.delay = 0;
        this.relative = true;
        this.attenuation = Attenuation.LINEAR;
        this.volume = (float) Math.min(1.0, gain / LOSS);
        this.pitch = pitch;
        place();
    }

    @Override
    public void tick() {
        place();
    }

    private void place() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            stop();
            return;
        }
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 to = source.subtract(camera.getPosition());
        double length = to.length();
        if (!(length > 1.0)) {
            this.x = this.y = this.z = 0;
            return;
        }
        Vec3 d = to.scale(1.0 / length);
        Vector3f at = camera.getLookVector(), up = camera.getUpVector();
        Vector3f right = at.cross(up, new Vector3f());
        // OpenAL listener frame: +x to the right, +y up, -z straight ahead.
        this.x = PAN_BLOCKS * (d.x * right.x + d.y * right.y + d.z * right.z);
        this.y = PAN_BLOCKS * (d.x * up.x + d.y * up.y + d.z * up.z);
        this.z = -PAN_BLOCKS * (d.x * at.x + d.y * at.y + d.z * at.z);
    }

    /** The gain this instance was asked for (checks). */
    public double gain() { return volume * LOSS; }

    /** The pitch it plays at (checks). */
    public float pitchValue() { return pitch; }
}
