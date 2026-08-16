package dev.bwr.core.nodal;

import dev.bwr.core.fuel.CoreLoading;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Which control rod sits between which four fuel assemblies.
 *
 * <p>{@code SPEC.md} section 3.1: rods are cruciform and sit in the gap at the
 * corner of a 2x2 bundle group, one drive per group. That is a self-enforcing
 * lattice rule — rod count follows core size — and it is also the mapping the
 * nodal solve needs, because a rod's absorption has to be applied to <i>some</i>
 * set of cells for shadowing to appear at all.
 *
 * <p>Each rod owns the up-to-four lattice positions of its group; each occupied
 * position is owned by at most one rod. The default construction walks groups
 * outward from the centre and hands them to rods in that order, so rod 0 is the
 * centre rod and the highest-numbered rods are peripheral. A caller that knows
 * better — the multiblock, which generates real rod coordinates during
 * structure validation — supplies its own mapping instead.
 *
 * <h2>Rod index order is a contract, not a detail</h2>
 * The rod index in this map is the same index as in the notch array pushed
 * through {@code NodalFluxSolver.setRodNotchIndices} and the same index the rod
 * drives answer to. <b>Whoever numbers the drives owns that ordering, and the
 * centre-outward default agrees with it only by coincidence.</b> Structure
 * validation in the multiblock numbers drives in raster order over the interior,
 * so its rod 0 is a corner drive while this class's default rod 0 owns the
 * centre-most group. Bolt the two together without translating and every rod
 * position lands on the wrong bundles: the flux map, the peaking factor and the
 * per-rod flux weights all stay perfectly self-consistent and all describe a
 * different reactor from the one the player is operating, with a stuck
 * peripheral rod modelled as a stuck central one. The
 * {@link #RodLatticeMap(int, int, int[])} constructor exists so a caller with
 * its own numbering can state it explicitly rather than hope.
 *
 * <p>Immutable. Rebuilt when the loading pattern or the rod count changes.
 */
public final class RodLatticeMap {

    /** Lattice positions owned by each rod, ragged. */
    private final int[][] positionsByRod;
    /** Owning rod of each lattice position, or -1 where no rod reaches. */
    private final int[] rodOfPosition;

    /**
     * @param rodCount      number of control rods
     * @param positionCount lattice positions in the core
     * @param rodOfPosition owning rod index per lattice position, -1 for none
     */
    public RodLatticeMap(int rodCount, int positionCount, int[] rodOfPosition) {
        if (rodCount < 1) {
            throw new IllegalArgumentException("rodCount must be at least 1, was " + rodCount);
        }
        if (rodOfPosition == null || rodOfPosition.length != positionCount) {
            throw new IllegalArgumentException("rodOfPosition must have one entry per lattice position");
        }
        this.rodOfPosition = rodOfPosition.clone();
        int[] counts = new int[rodCount];
        for (int position = 0; position < positionCount; position++) {
            int rod = this.rodOfPosition[position];
            if (rod < -1 || rod >= rodCount) {
                throw new IllegalArgumentException("position " + position + " maps to rod " + rod
                        + ", outside -1.." + (rodCount - 1));
            }
            if (rod >= 0) {
                counts[rod]++;
            }
        }
        this.positionsByRod = new int[rodCount][];
        for (int rod = 0; rod < rodCount; rod++) {
            positionsByRod[rod] = new int[counts[rod]];
        }
        int[] filled = new int[rodCount];
        for (int position = 0; position < positionCount; position++) {
            int rod = this.rodOfPosition[position];
            if (rod >= 0) {
                positionsByRod[rod][filled[rod]++] = position;
            }
        }
    }

    /**
     * The 2x2 rule applied centre-outward: group the lattice into 2x2 blocks,
     * discard blocks holding no fuel, sort what is left by distance from the
     * centre of the core, and give the first {@code rodCount} of them a rod.
     *
     * <p>A core with more groups than rods leaves its outermost groups unrodded,
     * which is what a real plant does at the very periphery. A core with more
     * rods than groups leaves the surplus rods owning nothing; they still exist
     * as hardware, they simply have no fuel to shadow. An <b>empty</b> core has
     * no non-empty groups at all and every entry comes back -1, which is the
     * correct answer for a core with no fuel in it and a trap for any caller
     * that builds this once and keeps it: the result depends on occupancy and
     * has to be rebuilt when occupancy changes.
     *
     * <p>Rod indices here are distance rank, which is almost certainly not the
     * order the rod hardware is numbered in — see the class comment.
     */
    public static RodLatticeMap centreOutward(CoreLoading loading, int rodCount) {
        if (loading == null) {
            throw new IllegalArgumentException("loading must not be null");
        }
        if (rodCount < 1) {
            throw new IllegalArgumentException("rodCount must be at least 1, was " + rodCount);
        }
        int width = loading.latticeWidth();
        int positions = loading.positionCount();
        int groupWidth = (width + 1) / 2;
        double centre = (width - 1) / 2.0;

        List<int[]> groups = new ArrayList<>();
        double[] groupRadiusSquared = new double[groupWidth * groupWidth];
        for (int groupRow = 0; groupRow < groupWidth; groupRow++) {
            for (int groupColumn = 0; groupColumn < groupWidth; groupColumn++) {
                List<Integer> members = new ArrayList<>(4);
                for (int dRow = 0; dRow < 2; dRow++) {
                    for (int dColumn = 0; dColumn < 2; dColumn++) {
                        int column = 2 * groupColumn + dColumn;
                        int row = 2 * groupRow + dRow;
                        if (column >= width || row >= width) {
                            continue;
                        }
                        int position = row * width + column;
                        if (loading.isOccupied(position)) {
                            members.add(position);
                        }
                    }
                }
                if (members.isEmpty()) {
                    continue;
                }
                int[] group = new int[members.size() + 1];
                group[0] = groupRow * groupWidth + groupColumn;
                for (int i = 0; i < members.size(); i++) {
                    group[i + 1] = members.get(i);
                }
                double x = 2 * groupColumn + 0.5 - centre;
                double y = 2 * groupRow + 0.5 - centre;
                groupRadiusSquared[group[0]] = x * x + y * y;
                groups.add(group);
            }
        }
        groups.sort(Comparator
                .comparingDouble((int[] group) -> groupRadiusSquared[group[0]])
                .thenComparingInt(group -> group[0]));

        int[] rodOfPosition = new int[positions];
        Arrays.fill(rodOfPosition, -1);
        int rods = Math.min(rodCount, groups.size());
        for (int rod = 0; rod < rods; rod++) {
            int[] group = groups.get(rod);
            for (int i = 1; i < group.length; i++) {
                rodOfPosition[group[i]] = rod;
            }
        }
        return new RodLatticeMap(rodCount, positions, rodOfPosition);
    }

    /** Number of control rods this map covers. */
    public int rodCount() {
        return positionsByRod.length;
    }

    /** Lattice positions the given rod shadows. Never null, possibly empty. */
    public int[] positionsOfRod(int rod) {
        return positionsByRod[rod].clone();
    }

    /** Number of lattice positions the given rod shadows. */
    public int positionCountOfRod(int rod) {
        return positionsByRod[rod].length;
    }

    /** One of a rod's positions, by ordinal within that rod. Allocation-free accessor. */
    public int positionOfRod(int rod, int ordinal) {
        return positionsByRod[rod][ordinal];
    }

    /** The rod shadowing a lattice position, or -1 if none does. */
    public int rodAtPosition(int position) {
        return (position >= 0 && position < rodOfPosition.length) ? rodOfPosition[position] : -1;
    }

    /** Lattice positions this map was built for. */
    public int positionCount() {
        return rodOfPosition.length;
    }

    @Override
    public String toString() {
        int covered = 0;
        for (int rod : rodOfPosition) {
            if (rod >= 0) {
                covered++;
            }
        }
        return String.format("RodLatticeMap[%d rods shadowing %d of %d positions]",
                positionsByRod.length, covered, rodOfPosition.length);
    }
}
