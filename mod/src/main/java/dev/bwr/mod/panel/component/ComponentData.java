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

    /** Convenience: {@link #save} into a fresh tag. */
    public final CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        save(tag);
        return tag;
    }
}
