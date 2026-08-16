package dev.bwr.mod.reactor;

import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The geometry of one assembled reactor, and the rules that decide whether it
 * is assembled at all.
 *
 * <p>Validation runs on neighbour change, never per tick. It is deliberately
 * chatty on failure: {@code SPEC.md} section 3.3 requires that a missing
 * control rod drive names its coordinate, because the error messages are how a
 * player learns the geometry.
 *
 * <h2>Lattice rule</h2>
 * Control rods are cruciform and sit in the gap between four fuel assemblies —
 * one rod per 2x2 bundle group. That rule is self-enforcing: rod count follows
 * core size rather than being configured, which is exactly how a real core is
 * laid out. A BWR/6 has 748 assemblies to 177 rods, a ratio of 4.2.
 */
public final class ReactorStructure {

    /** Smallest interior footprint worth calling a reactor. */
    public static final int MIN_INTERIOR = 5;
    /** Largest interior footprint, to bound validation cost. */
    public static final int MAX_INTERIOR = 21;
    /**
     * Minimum interior height: lower plenum, at least two rows of active fuel,
     * and a steam dome deep enough to hold both core spray rings.
     *
     * <p>Eight, not seven, and the extra block is load-bearing. {@code SPEC.md}
     * section 9.4 puts the HPCS sparger ring four blocks above the top of active
     * fuel; {@link #DOME_BLOCKS_ABOVE_ACTIVE_FUEL} therefore has to be at least
     * five for that ring to have anywhere legal to go inside the vessel. With a
     * two-block dome — which is what this was — {@code requiredY(HPCS)} landed
     * one block <i>above the top shell layer</i>, in open air outside the
     * pressure boundary, for every possible vessel size. There was no legal
     * in-vessel position for an HPCS segment at all, so spray ring completeness
     * could never exceed 0.5 and every core spray injection in the plant
     * delivered exactly half its pumped flow, permanently.
     */
    public static final int MIN_INTERIOR_HEIGHT = 8;
    /**
     * Interior rows reserved above the top of active fuel for the steam dome and
     * the two sparger rings: LPCS one block up, HPCS four, and one more above
     * HPCS for the dome proper.
     */
    public static final int DOME_BLOCKS_ABOVE_ACTIVE_FUEL = 5;

    /**
     * How far the interior walk may run vertically before giving up. Vessels are
     * bounded in footprint by {@link #MAX_INTERIOR} but not in height, so this
     * is only a runaway guard.
     */
    private static final int INTERIOR_HEIGHT_GUARD = 64;

    private final BlockPos interiorMin;
    private final BlockPos interiorMax;
    private final List<BlockPos> rodPositions;
    private final List<BlockPos> crdPositions;
    private final int topOfActiveFuelY;
    private final int assemblyCount;
    private double sprayRingCompleteness = 1.0;

    private ReactorStructure(BlockPos interiorMin, BlockPos interiorMax,
                             List<BlockPos> rodPositions, List<BlockPos> crdPositions,
                             int topOfActiveFuelY, int assemblyCount) {
        this.interiorMin = interiorMin;
        this.interiorMax = interiorMax;
        this.rodPositions = Collections.unmodifiableList(rodPositions);
        this.crdPositions = Collections.unmodifiableList(crdPositions);
        this.topOfActiveFuelY = topOfActiveFuelY;
        this.assemblyCount = assemblyCount;
    }

    public BlockPos interiorMin() {
        return interiorMin;
    }

    public BlockPos interiorMax() {
        return interiorMax;
    }

    /** One entry per control rod, in a stable order that indexes the core's rod arrays. */
    public List<BlockPos> rodPositions() {
        return rodPositions;
    }

    /** The drive beneath each rod, parallel to {@link #rodPositions()}. */
    public List<BlockPos> crdPositions() {
        return crdPositions;
    }

    public int controlRodCount() {
        return rodPositions.size();
    }

    public int assemblyCount() {
        return assemblyCount;
    }

    /** Elevation of the top of active fuel, which the sparger rings key off. */
    public int topOfActiveFuelY() {
        return topOfActiveFuelY;
    }

    /**
     * Fraction of the core spray rings that is actually built, 0..1.
     * Spray capacity scales with this; an incomplete ring degrades rather than
     * invalidating the multiblock.
     */
    public double sprayRingCompleteness() {
        return sprayRingCompleteness;
    }

    /**
     * Which lattice positions each of this vessel's rods shadows, in the same
     * rod numbering {@link #rodPositions()} uses.
     *
     * <p>The rod indices this class hands out are raster order over the
     * interior; the ones {@code RodLatticeMap.centreOutward} invents for itself
     * are distance rank. They are not the same numbering, so the multiblock has
     * to state its own — see {@link RodLatticeMapping} for what goes wrong when
     * it does not, and {@code ReactorControllerBlockEntity.installRodLatticeMap}
     * for where the result is handed to the solver.
     *
     * @param latticeWidth side of the physics lattice, {@code CoreLoading.latticeWidth()}
     */
    public dev.bwr.core.nodal.RodLatticeMap rodLatticeMap(int latticeWidth) {
        int width = interiorMax.getX() - interiorMin.getX() + 1;
        int depth = interiorMax.getZ() - interiorMin.getZ() + 1;
        int[] rodX = new int[rodPositions.size()];
        int[] rodZ = new int[rodPositions.size()];
        for (int i = 0; i < rodPositions.size(); i++) {
            BlockPos rod = rodPositions.get(i);
            rodX[i] = rod.getX() - interiorMin.getX();
            rodZ[i] = rod.getZ() - interiorMin.getZ();
        }
        return RodLatticeMapping.build(width, depth, rodX, rodZ, latticeWidth);
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    /**
     * Attempt to assemble a structure around a controller.
     *
     * @return the structure if valid, otherwise null; {@code result} always
     *         carries the reasons either way
     */
    public static ReactorStructure validate(Level level, BlockPos controllerPos,
                                            ValidationResult result) {
        // Walk inward from the controller until we leave the wall, to find the interior.
        BlockPos seed = findInteriorSeed(level, controllerPos);
        if (seed == null) {
            result.fail(controllerPos,
                    "controller is not mounted on a reactor vessel wall with open space behind it");
            return null;
        }

        int[] bounds = measureInterior(level, seed);
        if (bounds == null) {
            result.fail(seed, "interior is not a closed rectangular volume");
            return null;
        }
        BlockPos min = new BlockPos(bounds[0], bounds[1], bounds[2]);
        BlockPos max = new BlockPos(bounds[3], bounds[4], bounds[5]);

        int width = max.getX() - min.getX() + 1;
        int depth = max.getZ() - min.getZ() + 1;
        int height = max.getY() - min.getY() + 1;

        if (width < MIN_INTERIOR || depth < MIN_INTERIOR) {
            result.fail(min, "interior footprint " + width + "x" + depth
                    + " is smaller than the minimum " + MIN_INTERIOR + "x" + MIN_INTERIOR);
            return null;
        }
        if (width > MAX_INTERIOR || depth > MAX_INTERIOR) {
            result.fail(min, "interior footprint " + width + "x" + depth
                    + " exceeds the maximum " + MAX_INTERIOR + "x" + MAX_INTERIOR);
            return null;
        }
        if (height < MIN_INTERIOR_HEIGHT) {
            result.fail(min, "interior height " + height + " is below the minimum "
                    + MIN_INTERIOR_HEIGHT + "; a vessel needs lower plenum, active fuel and a steam"
                    + " dome deep enough for both core spray rings");
            return null;
        }

        // Shell must be closed all the way round.
        checkShellClosed(level, min, max, result);
        if (result.hasEnoughFailures()) {
            return null;
        }

        // Active fuel occupies the middle of the vessel; the dome is above it,
        // and the dome has to be deep enough to hold both sparger rings —
        // see DOME_BLOCKS_ABOVE_ACTIVE_FUEL.
        int activeFuelBottomY = min.getY() + 1;
        int activeFuelTopY = max.getY() - DOME_BLOCKS_ABOVE_ACTIVE_FUEL;
        if (activeFuelTopY <= activeFuelBottomY) {
            result.fail(min, "no room for active fuel between the lower plenum and the steam dome");
            return null;
        }

        // Rod lattice: one rod per 2x2 assembly group, offset one in from the wall.
        List<BlockPos> rods = new ArrayList<>();
        List<BlockPos> crds = new ArrayList<>();
        int assemblies = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                assemblies++;
                boolean rodHere = ((x - min.getX()) % 2 == 1) && ((z - min.getZ()) % 2 == 1);
                if (!rodHere) {
                    continue;
                }
                BlockPos rod = new BlockPos(x, activeFuelBottomY, z);
                // Rods enter from the bottom, so the drive sits directly beneath
                // the vessel floor under its own rod.
                BlockPos crd = new BlockPos(x, min.getY() - 2, z);
                rods.add(rod);
                crds.add(crd);

                BlockState driveState = level.getBlockState(crd);
                if (!driveState.is(BwrBlocks.CONTROL_ROD_DRIVE.get())) {
                    if (!result.hasEnoughFailures()) {
                        result.fail(crd, "missing control rod drive for the rod at "
                                + x + ", " + z + "; every rod needs its own drive beneath it");
                    }
                }
            }
        }

        if (rods.isEmpty()) {
            result.fail(min, "interior is too small to place any control rods");
            return null;
        }
        if (!result.isValid()) {
            return null;
        }

        // Spargers, and then one more validity check before anything is built.
        // checkSpargerRings raises hard failures — a segment at the wrong
        // elevation, or set to the wrong loop for the elevation it is at — but
        // it used to run after the last isValid() check, so those failures were
        // recorded and then discarded. The reactor formed anyway and the
        // controller printed "Reactor formed: ..." three lines above
        // "FAULT: sparger segment is at the wrong elevation", which teaches the
        // player that a fault is something they can disregard.
        double ringCompleteness =
                checkSpargerRings(level, min, max, activeFuelTopY, result);
        if (!result.isValid()) {
            return null;
        }

        ReactorStructure structure =
                new ReactorStructure(min, max, rods, crds, activeFuelTopY, assemblies);
        structure.sprayRingCompleteness = ringCompleteness;
        return structure;
    }

    /**
     * Find an open block adjacent to the controller that is genuinely inside the
     * vessel.
     *
     * <p>"Not shell" is not the same as "inside", and taking the first
     * not-shell neighbour meant three of the six mountings seeded validation
     * <b>outside</b> the plant. {@code Direction.values()} is declared DOWN, UP,
     * NORTH, SOUTH, WEST, EAST, so whenever the interior lay EAST, SOUTH or UP
     * of the controller — a controller on the west wall, on the north wall, or
     * in the bottom shell face — the exterior neighbour was tested first and
     * won. The interior walk then ran away through open air until its runaway
     * guard stopped it and the plant was rejected with "interior is not a closed
     * rectangular volume". A correct 7x7x7 vessel formed with the controller on
     * the east wall and refused to form with it on the west, with nothing in the
     * message to say why.
     *
     * <p>So a candidate has to prove it is enclosed: a ray in each of the six
     * directions must reach shell before it runs out. That is true of any block
     * inside a closed vessel and false of the air, stone or machinery on the
     * outside face of the wall, and it also rejects the outer corner that a
     * controller in the top or bottom row of a face has as its UP or DOWN
     * neighbour — {@code checkShellClosed} deliberately does not require outer
     * corners to be shell, so that corner is frequently open.
     */
    private static BlockPos findInteriorSeed(Level level, BlockPos controllerPos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos candidate = controllerPos.relative(d);
            if (isInteriorBlock(level, candidate) && isEnclosed(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** True when shell is reachable from here in all six directions. */
    private static boolean isEnclosed(Level level, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            int limit = d.getAxis().isVertical() ? INTERIOR_HEIGHT_GUARD : MAX_INTERIOR + 1;
            boolean hitShell = false;
            BlockPos.MutableBlockPos cursor = pos.mutable();
            for (int i = 0; i < limit; i++) {
                cursor.move(d);
                if (!isInteriorBlock(level, cursor)) {
                    hitShell = true;
                    break;
                }
            }
            if (!hitShell) {
                return false;
            }
        }
        return true;
    }

    /** Interior space is anything that is not part of the shell. */
    private static boolean isInteriorBlock(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.is(BwrBlocks.REACTOR_VESSEL.get())
                && !s.is(BwrBlocks.REACTOR_CONTROLLER.get());
    }

    /**
     * Expand from a seed to the largest axis-aligned box of interior space.
     * Returns {minX, minY, minZ, maxX, maxY, maxZ}, or null if it runs away.
     */
    private static int[] measureInterior(Level level, BlockPos seed) {
        int minX = seed.getX();
        int maxX = seed.getX();
        int minY = seed.getY();
        int maxY = seed.getY();
        int minZ = seed.getZ();
        int maxZ = seed.getZ();

        int guard = MAX_INTERIOR + 4;
        while (isInteriorBlock(level, new BlockPos(minX - 1, seed.getY(), seed.getZ()))
                && seed.getX() - minX < guard) {
            minX--;
        }
        while (isInteriorBlock(level, new BlockPos(maxX + 1, seed.getY(), seed.getZ()))
                && maxX - seed.getX() < guard) {
            maxX++;
        }
        while (isInteriorBlock(level, new BlockPos(seed.getX(), seed.getY(), minZ - 1))
                && seed.getZ() - minZ < guard) {
            minZ--;
        }
        while (isInteriorBlock(level, new BlockPos(seed.getX(), seed.getY(), maxZ + 1))
                && maxZ - seed.getZ() < guard) {
            maxZ++;
        }
        while (isInteriorBlock(level, new BlockPos(seed.getX(), minY - 1, seed.getZ()))
                && seed.getY() - minY < INTERIOR_HEIGHT_GUARD) {
            minY--;
        }
        while (isInteriorBlock(level, new BlockPos(seed.getX(), maxY + 1, seed.getZ()))
                && maxY - seed.getY() < INTERIOR_HEIGHT_GUARD) {
            maxY++;
        }

        if (maxX - minX + 1 > MAX_INTERIOR || maxZ - minZ + 1 > MAX_INTERIOR) {
            return null;
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Every block of the six faces surrounding the interior must be shell. */
    private static void checkShellClosed(Level level, BlockPos min, BlockPos max,
                                         ValidationResult result) {
        for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
            for (int y = min.getY() - 1; y <= max.getY() + 1; y++) {
                for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
                    boolean insideX = x >= min.getX() && x <= max.getX();
                    boolean insideY = y >= min.getY() && y <= max.getY();
                    boolean insideZ = z >= min.getZ() && z <= max.getZ();
                    if (insideX && insideY && insideZ) {
                        continue; // interior
                    }
                    // Only the six faces, not the outer corners.
                    int outside = (insideX ? 0 : 1) + (insideY ? 0 : 1) + (insideZ ? 0 : 1);
                    if (outside != 1) {
                        continue;
                    }
                    BlockPos p = new BlockPos(x, y, z);
                    if (!isShell(level, p)) {
                        if (result.hasEnoughFailures()) {
                            return;
                        }
                        result.fail(p, "gap in the reactor vessel shell");
                    }
                }
            }
        }
    }

    private static boolean isShell(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.is(BwrBlocks.REACTOR_VESSEL.get())
                || s.is(BwrBlocks.REACTOR_CONTROLLER.get())
                || s.is(BwrBlocks.CORE_SPRAY_SPARGER.get())
                || s.is(BwrBlocks.PRESSURISED_TUBE.get())
                || s.is(BwrBlocks.RECIRCULATION_PUMP.get())
                || s.is(BwrBlocks.JET_PUMP.get());
    }

    /**
     * Check the two sparger rings. Missing segments degrade spray capacity in
     * proportion; a segment at the wrong elevation is a hard failure, because
     * that one is a build mistake worth teaching rather than battle damage.
     *
     * @return ring completeness across both loops, 0..1
     */
    private static double checkSpargerRings(Level level, BlockPos min, BlockPos max,
                                            int topOfActiveFuelY, ValidationResult result) {
        int present = 0;
        int expected = 0;

        for (CoreSpraySpargerBlock.Loop loop : CoreSpraySpargerBlock.Loop.values()) {
            int y = CoreSpraySpargerBlock.requiredY(loop, topOfActiveFuelY);
            int loopPresent = 0;
            int loopExpected = 0;

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    boolean perimeter = x == min.getX() || x == max.getX()
                            || z == min.getZ() || z == max.getZ();
                    if (!perimeter) {
                        continue;
                    }
                    loopExpected++;
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    // The segment has to belong to the loop being counted, not
                    // merely be a sparger. Counting any sparger at the right
                    // elevation let a ring of LPCS segments sitting at the HPCS
                    // elevation report the HPCS loop as complete, so the plant
                    // claimed spray capacity it did not have.
                    if (s.is(BwrBlocks.CORE_SPRAY_SPARGER.get())
                            && s.getValue(CoreSpraySpargerBlock.LOOP) == loop) {
                        loopPresent++;
                    }
                }
            }

            present += loopPresent;
            expected += loopExpected;

            if (loopPresent == 0) {
                result.degrade("no " + loop.getSerializedName().toUpperCase(java.util.Locale.ROOT)
                        + " sparger ring built; that loop delivers no core spray");
            } else if (loopPresent < loopExpected) {
                result.degrade("the " + loop.getSerializedName().toUpperCase(java.util.Locale.ROOT)
                        + " sparger ring is " + loopPresent + "/" + loopExpected
                        + " complete, so it sprays at " + Math.round(100.0 * loopPresent / loopExpected)
                        + "% of capacity");
            }
        }

        // A segment at the wrong height is a build error, and worth saying so.
        int hpcsY = CoreSpraySpargerBlock.requiredY(CoreSpraySpargerBlock.Loop.HPCS, topOfActiveFuelY);
        int lpcsY = CoreSpraySpargerBlock.requiredY(CoreSpraySpargerBlock.Loop.LPCS, topOfActiveFuelY);
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!s.is(BwrBlocks.CORE_SPRAY_SPARGER.get())) {
                        continue;
                    }
                    if (y != hpcsY && y != lpcsY) {
                        if (!result.hasEnoughFailures()) {
                            result.fail(p, "sparger segment is at the wrong elevation; HPCS belongs at y="
                                    + hpcsY + " (4 above top of active fuel) and LPCS at y=" + lpcsY
                                    + " (1 above)");
                        }
                    } else if (!CoreSpraySpargerBlock.isAtCorrectElevation(s, p, topOfActiveFuelY)) {
                        if (!result.hasEnoughFailures()) {
                            result.fail(p, "sparger segment is set to the wrong loop for y=" + y);
                        }
                    }
                }
            }
        }

        return expected == 0 ? 0.0 : (double) present / expected;
    }
}
