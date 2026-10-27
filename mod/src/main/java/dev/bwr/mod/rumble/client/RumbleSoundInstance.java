package dev.bwr.mod.rumble.client;

import dev.bwr.core.rumble.RumbleLaw;
import dev.bwr.mod.client.VisualEffectsConfig;
import dev.bwr.mod.registry.BwrSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * One looping rumble. Its volume and pitch follow the smoothed intensity of its kind ({@link RumbleLaw#soundVolume},
 * {@link RumbleLaw#soundPitch}); it plays in the ambient category, so the player's Ambient slider and the
 * {@code rumbleVolume} option both scale it. Far from the source it sits in the middle of the head, with no direction; close to the source
 * it is placed a few blocks toward it, in the listener's own frame, so it pans with the turn of the head.
 */
public final class RumbleSoundInstance extends AbstractTickableSoundInstance {
    /** Which loop. */
    public enum Kind {
        /** Pool condensation and impacts: deep irregular thumping. */
        POOL,
        /** A turbine train: a steady low hum. */
        TURBINE
    }

    /** Distance, blocks, beyond which the loop has no direction. */
    public static final double DIRECTIONAL_RANGE = 96.0;
    /** How far toward the source, blocks, the loop is placed when the source is right beside the listener. */
    public static final double PAN_BLOCKS = 3.0;

    private final Kind kind;
    private boolean released;

    public RumbleSoundInstance(Kind kind) {
        super(kind == Kind.POOL ? soundPool() : soundTurbine(), SoundSource.AMBIENT, RandomSource.create());
        this.kind = kind;
        this.looping = true;
        this.delay = 0;
        this.relative = true;
        this.attenuation = Attenuation.LINEAR;
        this.volume = 0.001f;
        this.pitch = 0.7f;
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }

    private static SoundEvent soundPool() { return BwrSounds.RUMBLE_POOL.get(); }

    private static SoundEvent soundTurbine() { return BwrSounds.RUMBLE_TURBINE.get(); }

    public Kind kind() { return kind; }

    /** End the loop on the next tick. */
    public void release() {
        released = true;
        this.volume = 0f;
    }

    /** The volume this loop is being played at, before the player's sliders. */
    public float currentVolume() { return volume; }

    @Override
    public boolean canStartSilent() { return true; }

    @Override
    public void tick() {
        if (released) {
            stop();
            return;
        }
        double intensity = RumbleClient.intensity(kind);
        this.volume = (float) (RumbleLaw.soundVolume(intensity) * VisualEffectsConfig.rumbleVolume());
        this.pitch = (float) RumbleLaw.soundPitch(intensity);
        double dist = RumbleClient.distance();
        double pan = dist < DIRECTIONAL_RANGE ? (1.0 - dist / DIRECTIONAL_RANGE) * PAN_BLOCKS : 0;
        if (pan > 0.01) {
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vector3f at = camera.getLookVector(), up = camera.getUpVector();
            Vector3f right = at.cross(up, new Vector3f());
            Vec3 d = RumbleClient.direction();
            // OpenAL listener frame: +x to the right, +y up, -z straight ahead.
            this.x = pan * (d.x * right.x + d.y * right.y + d.z * right.z);
            this.y = pan * (d.x * up.x + d.y * up.y + d.z * up.z);
            this.z = -pan * (d.x * at.x + d.y * at.y + d.z * at.z);
        } else {
            this.x = 0;
            this.y = 0;
            this.z = 0;
        }
    }
}
