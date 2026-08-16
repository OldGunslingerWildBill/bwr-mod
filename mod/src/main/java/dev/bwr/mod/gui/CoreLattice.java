package dev.bwr.mod.gui;

import java.util.Arrays;

/**
 * Which squares of the lattice are actually core positions, and in what order.
 *
 * <p>{@code CoreLoading} is a square 31x31 array because indexing is trivial
 * that way, but a real core is a circle inscribed in that square and a
 * multiblock only has room for as many bundles as its interior footprint. So
 * the positions in use are the first {@code assemblyCount} squares taken
 * centre-outward — exactly the rule
 * {@code ReactorCore.defaultCoreLoading} uses to fill a fresh core, reproduced
 * here so the GUI, the block entity and the physics all agree on which square
 * is position 0.
 *
 * <p>That ordering is also the wire format: the reactor snapshot sends one
 * entry per <i>core slot</i> in this order rather than one per lattice square,
 * which is what keeps a 21x21 vessel's map at 441 entries instead of 961, and
 * lets the client rebuild the geometry from two integers.
 */
public final class CoreLattice {

    private CoreLattice() {
    }

    /**
     * Lattice indices of the core slots, centre-outward.
     *
     * @param latticeWidth  side of the square lattice, {@code CoreLoading.latticeWidth()}
     * @param assemblyCount how many positions this vessel actually has
     * @return lattice index for each core slot, length {@code assemblyCount} clamped to the lattice
     */
    public static int[] corePositions(int latticeWidth, int assemblyCount) {
        if (latticeWidth < 1) {
            return new int[0];
        }
        int total = latticeWidth * latticeWidth;
        int wanted = Math.max(0, Math.min(assemblyCount, total));

        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) {
            order[i] = i;
        }
        double centre = (latticeWidth - 1) / 2.0;
        // Arrays.sort on boxed values is stable, so squares at equal radius keep
        // their index order. That stability is what makes the ordering the same
        // on the client as on the server.
        Arrays.sort(order, (a, b) -> Double.compare(
                radiusSquared(a, latticeWidth, centre), radiusSquared(b, latticeWidth, centre)));

        int[] out = new int[wanted];
        for (int i = 0; i < wanted; i++) {
            out[i] = order[i];
        }
        return out;
    }

    private static double radiusSquared(int index, int width, double centre) {
        double x = (index % width) - centre;
        double y = (index / width) - centre;
        return x * x + y * y;
    }
}
