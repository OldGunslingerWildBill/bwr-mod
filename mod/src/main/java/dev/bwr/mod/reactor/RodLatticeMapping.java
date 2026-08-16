package dev.bwr.mod.reactor;

import dev.bwr.core.nodal.RodLatticeMap;
import dev.bwr.mod.gui.CoreLattice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The translation between the multiblock's rod numbering and the lattice
 * numbering the nodal solve works in.
 *
 * <h2>Why this class has to exist</h2>
 * There are two independent index spaces in play and nothing makes them agree
 * on its own.
 *
 * <ul>
 *   <li><b>Rod index</b> is assigned by {@link ReactorStructure#validate} in
 *       <i>raster order</i> over the vessel interior: it walks {@code for x}
 *       then {@code for z} and appends a rod wherever both offsets are odd, so
 *       rod 0 is the drive nearest {@code (minX+1, minZ+1)} — a <b>corner</b>
 *       drive. {@code ReactorControllerBlockEntity.bindDrives} then binds the
 *       drive block at {@code crdPositions().get(i)} to rod {@code i}, and that
 *       is the number the panel, the peripheral and the core's rod arrays all
 *       use.</li>
 *   <li><b>Lattice position</b> is an index into {@code CoreLoading}'s square
 *       31x31 array, and the positions a vessel actually uses are the first
 *       {@code assemblyCount} squares taken <i>centre-outward</i> — see
 *       {@link CoreLattice#corePositions}, which is the ordering the refuelling
 *       screen, the wire format and {@code ReactorCore.defaultCoreLoading} all
 *       share.</li>
 * </ul>
 *
 * <p>{@code RodLatticeMap.centreOutward}, which is what the solver builds for
 * itself when nobody supplies a map, numbers rods by <i>distance rank</i>: its
 * rod 0 owns the centre-most 2x2 group. Bolted to a raster-numbered set of
 * drives, the two are a permutation of each other, and the symptom is quiet
 * rather than loud — the flux map, the peaking factor and the per-rod flux
 * weights all stay perfectly self-consistent and all describe a different
 * reactor from the one in front of the player. Withdraw the corner drive and
 * the hot spot appears dead centre; a stuck peripheral rod is modelled as a
 * stuck central one, which is the highest-consequence case inverted. This class
 * is the translation that makes rod {@code i} mean the same rod on both sides,
 * and {@code NodalFluxSolver.setRodLatticeMap} exists to receive it.
 *
 * <h2>How the mapping is built</h2>
 * The vessel interior is a {@code width x depth} rectangle of assembly
 * positions; the core the physics sees is a circle inscribed in the 31x31
 * lattice, because {@link CoreLattice} takes the first {@code assemblyCount}
 * squares centre-outward and that set is a disc. Equal area, different outline.
 *
 * <p>So the interior is laid over the middle of the lattice, square for square:
 * interior offset {@code (u, v)} models the lattice square {@code (u + dx,
 * v + dz)} where the offsets centre the rectangle on the lattice. Where that
 * square is a core position — which it is for the whole middle of any vessel —
 * the correspondence is exact, and a rod's four bundles are four lattice
 * neighbours, so its absorption makes one local depression rather than four
 * scattered dents. The interior's <b>corners</b> stick out past the disc and
 * have no square of their own; they are given the core positions the disc has
 * left over around its rim, nearest first. The two shortfalls are the same size
 * — the rectangle and the disc hold the same number of cells — so every
 * interior cell ends up on exactly one core position and every core position is
 * used.
 *
 * <p>Roughly a tenth of the cells of a maximum-size vessel are corners handled
 * that way, and those land further round the rim than their bearing would
 * suggest. That distortion is confined to the outermost ring, where rod worth
 * is lowest. It is the price of every rod owning four real fuel positions, and
 * it is much the cheaper error: the alternative rules that were tried first —
 * pairing the two sets by radial rank, with ties broken by raster order or by
 * bearing — preserve radius beautifully and shear the bearings, and on a 9x9
 * vessel they put rod 0's four bundles at three different compass points.
 *
 * <p>Each rod owns the 2x2 group of interior cells it sits at the corner of —
 * {@code SPEC.md} section 3.1 — which for a rod at odd offsets {@code (u, v)} is
 * {@code {u-1, u} x {v-1, v}}. Those groups are disjoint and aligned on even
 * offsets, exactly as {@code centreOutward}'s groups are aligned on even
 * lattice indices, so no lattice position ends up owned by two rods.
 *
 * <p>Every comparison here is exact integer arithmetic, and every tie is broken
 * on an index. Nothing depends on floating-point rounding or on a sort being
 * stable, because a mapping that came out differently on a different machine
 * would quietly move every rod onto a different set of bundles across a world
 * reload.
 *
 * <h2>Occupancy is deliberately not an input</h2>
 * {@code centreOutward} discards 2x2 groups that hold no fuel and has to be
 * rebuilt whenever the loading changes. This mapping is pure geometry: a rod
 * does not move when a bundle is pulled out from beside it, so the map is a
 * function of the vessel's shape alone and survives refuelling untouched. That
 * is why the solver tracks whether its map was supplied rather than inferring
 * it — see {@code NodalFluxSolver.hasExplicitRodLatticeMap}. One consequence
 * worth knowing: in a partly loaded core a rod's owned-position count includes
 * its empty neighbours, so the per-rod flux weight is the mean flux over all
 * four of its physical positions counting an empty one as zero. That is the
 * honest reading — a rod with nothing loaded beside it really is sitting in
 * less flux — and it agrees with the default once the core is full.
 */
public final class RodLatticeMapping {

    private RodLatticeMapping() {
    }

    /**
     * Pair every interior cell with the lattice position that models it.
     *
     * <p>Indexed by {@code v * interiorWidth + u}, where {@code u} is the offset
     * east from {@code interiorMin} and {@code v} the offset south. Entries are
     * -1 only if the interior has more cells than the lattice has core
     * positions, which a {@link ReactorStructure#MAX_INTERIOR} of 21 against a
     * lattice of 31 does not allow today.
     *
     * <p>Which lattice squares are core positions at all is
     * {@link CoreLattice#corePositions}'s decision and is not second-guessed
     * here — that set is the refuelling screen's slot list and the wire format.
     * Only which interior cell sits on which of them is decided here.
     *
     * @param interiorWidth interior extent along x, cells
     * @param interiorDepth interior extent along z, cells
     * @param latticeWidth  side of the physics lattice, {@code CoreLoading.latticeWidth()}
     */
    public static int[] interiorToLattice(int interiorWidth, int interiorDepth, int latticeWidth) {
        if (interiorWidth < 1 || interiorDepth < 1) {
            throw new IllegalArgumentException("interior must have at least one cell, was "
                    + interiorWidth + "x" + interiorDepth);
        }
        if (latticeWidth < 1) {
            throw new IllegalArgumentException("latticeWidth must be at least 1, was " + latticeWidth);
        }
        int cells = interiorWidth * interiorDepth;
        int[] corePositions = CoreLattice.corePositions(latticeWidth, cells);
        boolean[] isCorePosition = new boolean[latticeWidth * latticeWidth];
        boolean[] claimed = new boolean[latticeWidth * latticeWidth];
        for (int position : corePositions) {
            isCorePosition[position] = true;
        }

        // Centre the interior rectangle on the lattice. Integer division, so an
        // even-sided vessel sits half a square off centre; that is a half-cell
        // of asymmetry in a model whose cells are 15 cm across, and the
        // alternative is a fractional lattice coordinate that does not exist.
        int offsetColumn = (latticeWidth - interiorWidth) / 2;
        int offsetRow = (latticeWidth - interiorDepth) / 2;

        int[] latticeOfCell = new int[cells];
        Arrays.fill(latticeOfCell, -1);

        // Centre-outward, so that every cell whose own square exists has taken
        // it before the corners start looking for somewhere to go. That
        // ordering is what makes the leftover pass below deterministic and
        // small: the cells that miss are exactly the ones outside the disc, and
        // they are the outermost ones.
        Integer[] order = new Integer[cells];
        for (int i = 0; i < cells; i++) {
            order[i] = i;
        }
        Arrays.sort(order, centreOutwardOrder(interiorWidth, interiorDepth));

        List<Integer> unplaced = new ArrayList<>();
        for (int rank = 0; rank < cells; rank++) {
            int cell = order[rank];
            int column = (cell % interiorWidth) + offsetColumn;
            int row = (cell / interiorWidth) + offsetRow;
            if (column < 0 || row < 0 || column >= latticeWidth || row >= latticeWidth) {
                unplaced.add(cell);
                continue;
            }
            int position = row * latticeWidth + column;
            if (!isCorePosition[position] || claimed[position]) {
                unplaced.add(cell);
                continue;
            }
            latticeOfCell[cell] = position;
            claimed[position] = true;
        }

        // The interior's corners, taking what the disc has left around its rim.
        for (int cell : unplaced) {
            long column = (cell % interiorWidth) + offsetColumn;
            long row = (cell / interiorWidth) + offsetRow;
            int best = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int position : corePositions) {
                if (claimed[position]) {
                    continue;
                }
                long dColumn = (position % latticeWidth) - column;
                long dRow = (position / latticeWidth) - row;
                long distance = dColumn * dColumn + dRow * dRow;
                // Strictly less, and corePositions is in a fixed order, so an
                // equidistant pair always resolves the same way.
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = position;
                }
            }
            if (best < 0) {
                continue; // more interior cells than core positions; leaves -1
            }
            latticeOfCell[cell] = best;
            claimed[best] = true;
        }
        return latticeOfCell;
    }

    /**
     * Distance from the middle of the grid, ties broken by raster index.
     * Offsets are doubled so that an even-sided grid still has integer
     * coordinates and the comparison stays exact.
     *
     * @param width  cells per row of the grid being ordered
     * @param height rows of the grid being ordered
     */
    private static Comparator<Integer> centreOutwardOrder(int width, int height) {
        return (a, b) -> {
            long ax = 2L * (a % width) - (width - 1);
            long ay = 2L * (a / width) - (height - 1);
            long bx = 2L * (b % width) - (width - 1);
            long by = 2L * (b / width) - (height - 1);
            int radius = Long.compare(ax * ax + ay * ay, bx * bx + by * by);
            return radius != 0 ? radius : Integer.compare(a, b);
        };
    }

    /**
     * Build the rod-to-lattice mapping for one vessel, in the rod numbering the
     * drives answer to.
     *
     * <p>Rod {@code i} of the result is the rod at
     * {@code (rodInteriorX[i], rodInteriorZ[i])}, so the caller's array order is
     * the rod numbering — pass {@link ReactorStructure#rodPositions()} in its
     * own order and rod {@code i} here is the rod the drive at
     * {@code crdPositions().get(i)} operates.
     *
     * @param interiorWidth interior extent along x, cells
     * @param interiorDepth interior extent along z, cells
     * @param rodInteriorX  each rod's offset east from the interior minimum
     * @param rodInteriorZ  each rod's offset south from the interior minimum
     * @param latticeWidth  side of the physics lattice, {@code CoreLoading.latticeWidth()}
     * @return a map covering {@code rodInteriorX.length} rods and
     *         {@code latticeWidth * latticeWidth} lattice positions
     */
    public static RodLatticeMap build(int interiorWidth, int interiorDepth,
                                      int[] rodInteriorX, int[] rodInteriorZ,
                                      int latticeWidth) {
        if (rodInteriorX == null || rodInteriorZ == null
                || rodInteriorX.length != rodInteriorZ.length) {
            throw new IllegalArgumentException("rod coordinates must be parallel arrays");
        }
        int[] latticeOfCell = interiorToLattice(interiorWidth, interiorDepth, latticeWidth);

        int[] rodOfPosition = new int[latticeWidth * latticeWidth];
        Arrays.fill(rodOfPosition, -1);
        for (int rod = 0; rod < rodInteriorX.length; rod++) {
            // The 2x2 bundle group this rod sits at the corner of. Structure
            // validation only ever places a rod at odd offsets, so u-1 and v-1
            // are always inside the interior; the bounds test is here for the
            // caller that has not been written yet rather than for this one.
            for (int du = -1; du <= 0; du++) {
                for (int dv = -1; dv <= 0; dv++) {
                    int u = rodInteriorX[rod] + du;
                    int v = rodInteriorZ[rod] + dv;
                    if (u < 0 || v < 0 || u >= interiorWidth || v >= interiorDepth) {
                        continue;
                    }
                    int position = latticeOfCell[v * interiorWidth + u];
                    if (position >= 0) {
                        rodOfPosition[position] = rod;
                    }
                }
            }
        }
        return new RodLatticeMap(rodInteriorX.length, rodOfPosition.length, rodOfPosition);
    }
}
