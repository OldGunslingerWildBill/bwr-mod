package dev.bwr.mod.gui;

import java.util.Arrays;

/**
 * Legacy centre-outward fuel positions. Compact cores use CompactCoreLayout.
 * Modern GUI packets carry the exact allowed indices rather than inferring them.
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
 * <h2>The ordering is computed once per lattice width and then remembered</h2>
 * It is a pure function of {@code latticeWidth} — no reactor, no fuel, no world
 * state — and there is exactly one lattice width in the game
 * ({@code ReactorCore.DEFAULT_LATTICE_WIDTH}, 31). It was nevertheless being
 * rebuilt from scratch on <b>every</b> call, and the calls are in the menu sync
 * hot path: {@code CoreMapSnapshot.write} runs it once per core map the server
 * pushes (1 Hz per open reactor panel, 1 Hz per open refuelling screen, plus one
 * per refuelling command), {@code CoreMapSnapshot.read} runs it again on the
 * client for every map that arrives, and
 * {@code ReactorControllerBlockEntity.corePositions()} runs it once per
 * refuelling command on top of that.
 *
 * <p>Each rebuild allocated an {@code Integer[961]}, boxed 961 integers — only
 * the first 128 of which come out of the {@code Integer} cache — ran a stable
 * merge sort over them that allocates its own working array and unboxes both
 * arguments roughly ten thousand times, and then threw the whole lot away. That
 * is on the order of 25 kB of immediately-dead objects per call, so a
 * single-player session with the control room panel open was producing some
 * 50 kB a second of garbage for a result that had not changed since the vessel
 * was built. That is the shape a player reads as "memory climbs while I watch
 * the panel": nothing is retained, but the heap graph sawtooths and the
 * collector runs far more often than it should.
 *
 * <p>So the sorted order is built once for a given width, cached, and callers
 * are handed a copy of the prefix they asked for. The copy stays because the
 * array is public API and two callers store it in fields; sharing the cached
 * array would make one caller's mutation everybody's. One {@code int[441]} is
 * an order of magnitude less than what the rebuild cost, and none of it is
 * boxed.
 */
public final class CoreLattice {

    private CoreLattice() {
    }

    /**
     * A lattice width and the centre-outward ordering of all its squares.
     *
     * <p>Published through a single volatile reference so that the array is
     * fully built before any other thread can see it. That matters: in single
     * player the server thread calls this from {@code CoreMapSnapshot.write}
     * and the render thread calls it from {@code CoreMapSnapshot.read}, so both
     * hit the cache concurrently. Worst case under a race is that two threads
     * build the same ordering and one of the two identical arrays is discarded,
     * which is harmless — the ordering is deterministic.
     */
    private record Ordering(int latticeWidth, int[] order) {
    }

    private static volatile Ordering cached;

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

        Ordering ordering = cached;
        if (ordering == null || ordering.latticeWidth() != latticeWidth) {
            ordering = new Ordering(latticeWidth, buildOrdering(latticeWidth, total));
            cached = ordering;
        }
        // A copy, never the cached array itself: CoreMapSnapshot.read assigns
        // the result straight into a field the GUI reads, and handing out the
        // shared array would let any caller that ever decides to sort or clear
        // it corrupt every future core map in the process.
        return Arrays.copyOf(ordering.order(), wanted);
    }

    /**
     * Every square of one lattice, nearest the centre first.
     *
     * <p>Still the boxed stable sort, unchanged, because the stability is what
     * makes squares at equal radius come out in index order and therefore what
     * makes the client and the server agree on which square is slot 0. It runs
     * once per lattice width per session now instead of once per packet, so the
     * cost of the boxing no longer matters and the ordering is bit-identical to
     * what every previous build produced.
     */
    private static int[] buildOrdering(int latticeWidth, int total) {
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

        int[] out = new int[total];
        for (int i = 0; i < total; i++) {
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
