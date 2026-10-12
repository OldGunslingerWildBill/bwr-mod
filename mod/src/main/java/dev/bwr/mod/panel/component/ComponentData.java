package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;

/**
 * The type-specific, per-instance part of a component: its configuration and
 * any latched state (a toggle's position, an annunciator's acknowledge flag).
 * One subclass per {@link ComponentType}.
 *
 * <p>The same NBT is used for world saves and for the S2C full-state packet, so
 * whatever {@link #save} writes the client can draw. Keys that are declared as
 * {@link ConfigField}s are editable through the wrench; every other key is
 * latched state that only the type's own interaction code may change.
 *
 * <p>Do not hold live signal values here: input and output values belong to the
 * {@link PanelComponent} (they are synced as deltas and never saved).
 *
 * <p>Implementations must make {@code load(save())} an identity, and must
 * tolerate a missing key (use the default) so old saves keep loading when a
 * field is added later.
 */
public abstract class ComponentData {
    /** Writes configuration and latched state. */
    public abstract void save(CompoundTag tag);

    /** Reads what {@link #save} wrote. Missing keys mean "default". Must not throw on bad input. */
    public abstract void load(CompoundTag tag);

    // ---- host link and content patches (for types with bulk content: displays, recorders)

    /** What a data object may ask of the block entity that owns its component. Set by {@link #attach}. */
    public interface Host {
        /** Game time in ticks of the level the component is in (the synced client time on a client); 0 before it has one. */
        long gameTime();

        /** Footprint width of the component in cells. */
        int cellsWide();

        /** Footprint height of the component in cells. */
        int cellsHigh();

        /** Marks the component as needing its static geometry redrawn (bumps its revision). */
        void touch();

        /** The data changed in a way that must reach clients as a {@link #drainPatch patch} and be saved. */
        void contentChanged();
    }

    private Host host;

    /**
     * Called by the owning block entity when the component is added, loaded or received, before it is used. The
     * data may then ask the host for the clock and its size, and report {@link #contentChanged() content changes}.
     */
    public final void attach(Host host) {
        this.host = host;
        attached();
    }

    /** The host, or null until {@link #attach} (tests and half-built components). */
    protected final Host host() {
        return host;
    }

    /** Hook after {@link #attach}; size dependent state can be set up here. */
    protected void attached() {
    }

    /** Tells the host that bulk content changed (server): it is saved and a patch is sent next tick. */
    protected final void contentChanged() {
        if (host != null) {
            host.contentChanged();
        }
    }

    /**
     * Server: the changes since the last call, as an opaque byte array of at most
     * {@link #MAX_PATCH} bytes, or null when there are none. Types with no bulk content leave this alone.
     */
    public byte[] drainPatch() {
        return null;
    }

    /** Client: applies a patch made by {@link #drainPatch}. Must ignore malformed input. */
    public void applyPatch(byte[] patch) {
    }

    /** Upper bound of one patch; the wire codec refuses longer ones. */
    public static final int MAX_PATCH = 16384;

    /** Convenience: {@link #save} into a fresh tag. */
    public final CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        save(tag);
        return tag;
    }
}
