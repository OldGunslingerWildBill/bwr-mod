package dev.bwr.mod.panel.component;

import java.util.Arrays;

/**
 * Which of a type's footprints are on offer. A type may retire a size ({@link ComponentType#retiredFootprints()}): it
 * stays in {@link ComponentType#footprints()} at its index, so an old item stack or a saved panel that holds it still
 * places and loads at that size, but it is no longer chosen: the size picker, the cycling of the item and the picker's
 * server side ({@link ComponentItem#select}) all go through {@link #offered} and {@link #retired}.
 * A new item starts at {@link ComponentType#realDefaultIndex()}, which must not be a retired size.
 */
public final class Footprints {
    private Footprints() {
    }

    /** Whether footprint index {@code index} of {@code type} is retired (out of range counts as retired). */
    public static boolean retired(ComponentType type, int index) {
        return index < 0 || index >= type.footprints().size() || type.retiredFootprints().contains(type.footprints().get(index));
    }

    /** The footprint indices to offer, smallest to biggest ({@link ComponentType#sizeOrder()} without the retired ones). */
    public static int[] offered(ComponentType type) {
        return Arrays.stream(type.sizeOrder()).filter(index -> !retired(type, index)).toArray();
    }
}
