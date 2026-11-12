package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.AnnunciatorSounds;
import dev.bwr.mod.registry.BwrSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The annunciator horn of one panel group: one looping sound (the panel alarm {@code bwr:annunciator_horn}, the
 * synthesised horn, or a custom sound a window is set to) at the panel, in the blocks
 * category with ordinary (linear) attenuation. {@link AnnunciatorHornClient} starts it, moves it and lets it go; it
 * also stops by itself when it has not been {@linkplain #keep kept} for a while, so a horn can never outlive whatever
 * was keeping it (a panel that unloaded, a broken block, a level the player left, or a failure of the keeper).
 */
public final class AnnunciatorHornSound extends AbstractTickableSoundInstance {
    /** Client ticks a horn plays on without being kept before it stops itself (the keeper looks every few ticks). */
    static final int ORPHAN_TICKS = 30;

    private boolean released;
    private int unkept;

    /**
     * @param sound the id of the sound to loop; the panel alarm when it is that or is no id at all (the caller has
     *              already replaced a sound this client does not know)
     */
    AnnunciatorHornSound(String sound, double x, double y, double z) {
        super(event(sound), SoundSource.BLOCKS, RandomSource.create());
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

    private static SoundEvent event(String sound) {
        ResourceLocation location = AnnunciatorSounds.PANEL_ID.equals(sound) ? null : ResourceLocation.tryParse(sound);
        // A sound of a resource pack has a definition but no registered event: an event made here plays it all the same.
        return location == null ? BwrSounds.ANNUNCIATOR_HORN.get() : SoundEvent.createVariableRangeEvent(location);
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
