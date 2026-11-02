package dev.bwr.mod.panel.component;

import java.util.Arrays;
import java.util.Set;

/**
 * Which of a type's footprints are on offer. A type's {@code FOOTPRINTS} list only ever grows at the end and is never
 * reordered, because item stacks hold an index into it and placed components hold a raw width and height. A size that
 * is no longer wanted is therefore <em>retired</em>, not removed ({@link ComponentType#retiredFootprints()}): it stays
 * on the list, so an old stack with that index still places that size and a placed part keeps it, but nothing offers
 * it any more.
 *
 * <p>Everything that lists or steps through sizes (the size picker, right-click cycling, the picker packet) goes
 * through {@link #offered}; nothing else filters retired sizes. The size a fresh item starts at is
 * {@link #defaultIndex}.
 */
public final class Footprints {
    private Footprints() {}

    /** The retired footprint indices of {@code type} (never null). */
    public static Set<Integer> retired(ComponentType type) {
        return type.retiredFootprints();
    }

    /** Whether footprint index {@code index} of {@code type} is retired. */
    public static boolean isRetired(ComponentType type, int index) {
        return type.retiredFootprints().contains(index);
    }

    /**
     * The footprint indices on offer, from the smallest size to the biggest ({@link ComponentType#sizeOrder}) with the
     * retired ones left out: what the size picker lists and right-click cycling steps through.
     */
    public static int[] offered(ComponentType type) {
        return Arrays.stream(type.sizeOrder()).filter(index -> !isRetired(type, index)).toArray();
    }

    /** The footprint index a fresh item of {@code type} starts at, or -1 when the type has no fixed starting size. */
    public static int defaultIndex(ComponentType type) {
        return type.realDefaultIndex();
    }
}
