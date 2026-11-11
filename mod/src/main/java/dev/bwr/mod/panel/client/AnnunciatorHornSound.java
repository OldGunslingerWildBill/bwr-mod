package dev.bwr.mod.panel.client;

import dev.bwr.mod.registry.BwrSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The annunciator horn of one panel group: the looping {@code bwr:annunciator_horn} at the panel, in the blocks
 * category with ordinary (linear) attenuation. {@link AnnunciatorHornClient} starts it, moves it and lets it go; it
 * also stops by itself when it has not been {@linkplain #keep kept} for a while, so a horn can never outlive whatever
 * was keeping it (a panel that unloaded, a broken block, a level the player left, or a failure of the keeper).
 */
public final class AnnunciatorHornSound extends AbstractTickableSoundInstance {
    /** Client ticks a horn plays on without being kept before it stops itself (the keeper looks every few ticks). */
    static final int ORPHAN_TICKS = 30;

    private boolean released;
    private int unkept;

    AnnunciatorHornSound(double x, double y, double z) {
        super(BwrSounds.ANNUNCIATOR_HORN.get(), SoundSource.BLOCKS, RandomSource.create());
        this.looping = true;
        this.delay = 0;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
        this.volume = 1.0f;
        this.pitch = 1.0f;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Still wanted, and where (the alarms of a group can move as windows are silenced one board at a time). */
    void keep(double x, double y, double z) {
        this.unkept = 0;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** End the loop on the next tick. */
    void release() {
        released = true;
    }

    @Override
    public void tick() {
        if (released || ++unkept > ORPHAN_TICKS) {
            stop();
        }
    }
}
