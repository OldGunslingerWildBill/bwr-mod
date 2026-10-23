package dev.bwr.core.accident;

/**
 * The vessel head as a missile. Pure arithmetic: the world adapter ({@code dev.bwr.mod.accident.HeadMissileLauncher})
 * reads the blocks above the vessel and applies the numbers here.
 *
 * <h2>The chain</h2>
 * <ol>
 *   <li><b>Mass.</b> A BWR/6-class head (studs, insulation and lifting rig included) weighs 80 to 100 tonnes; the
 *       reference is {@value #REFERENCE_MASS_KG} kg on the 17 x 17 reference vessel (the 764-assembly Columbia-class
 *       core, see COMPACT-CORE.md). A head is a dished plate on a flange, so the mass follows the footprint area:
 *       {@link #massKg}. A 7 x 7 vessel's head weighs 15 t and a 23 x 23 one 165 t.</li>
 *   <li><b>Energy.</b> Two limits, and the head gets the smaller.
 *     <ul>
 *       <li><i>What the vessel holds.</i> The stored energy of the failure ({@link BlastPhysics#headFailureEnergyJ}) is
 *           the steam dome expanding at the pressure the head let go at, plus the flashing water, both read from the
 *           core's state at that moment (pressure, steam dome volume, liquid inventory). {@value #KINETIC_FRACTION} of it
 *           can become the head's kinetic energy. That share is a game calibration like the other yields in
 *           {@link BlastPhysics}: it is well under what is quoted for gas vessels that burst into a few pieces,
 *           because the opening vents the dome as soon as the head lifts, and 40% already goes to the blast cone.</li>
 *       <li><i>What the steam can push with.</i> The steam acts on the head's whole area: force {@code F = P * A} with
 *           {@code P} the gauge pressure and {@code A = pi/4 * width * depth}. It keeps pushing only while the head has
 *           not lifted clear of the flange; the vent opens after a stroke of {@value #VENT_STROKE_FRACTION} of the
 *           equivalent radius, so the work on the head is {@code (F - m g) * s} (and nothing at all if the steam
 *           cannot even lift the head's weight).</li>
 *     </ul>
 *     {@link #kineticEnergyJ} takes the smaller. At the code limit of 1,375 psig on the 17 x 17 reference vessel the
 *     stored energy is 4.9 GJ, so the share (245 MJ, 74 m/s) is the limit; the force limit is 1.9 GJ. A failure at a
 *     lower pressure has less stored energy and less force, so a weaker launch.</li>
 *   <li><b>Height.</b> Free of everything, a head with kinetic energy {@code E} rises {@code E / (m g)}: 277 m for
 *       that case. That is far more than a game wants, so a flight never climbs more than
 *       {@value #MAX_CLIMB_BLOCKS} blocks above the vessel top ({@link #MAX_CLIMB_BLOCKS}); the launch speed is then
 *       {@code sqrt(2 g H)}, 28 m/s for 40 blocks. The cap does not decide containment: the full kinetic energy
 *       does.</li>
 *   <li><b>Containment.</b> The head rises through the rows of blocks above the vessel, one metre each. A row
 *       resists by crushing: every block under the head's footprint absorbs
 *       {@code rating * 1 m^2 * 1 m} of energy, with the block's rating being the {@code 5.8 * sqrt(blast resistance)} psi
 *       that {@link ContainmentWallStrength} already uses for walls and that {@link BlastPhysics} uses to break blocks
 *       ({@link #crushJ}); a row is the sum over its blocks. A head with at least a row's capacity left punches the
 *       row out and loses that energy (and {@code m g * 1 m} of height); a head with less is stopped by it and the row
 *       cracks. Open rows cost only gravity. A head that clears every row up to the cap flies on; a head that is
 *       stopped is contained. {@link Climb} is that bookkeeping, one row at a time, so the world adapter can let a
 *       protection mod refuse a block and stop the head against it.</li>
 *   <li><b>Impact.</b> A head that lands has {@code 1/2 m v^2} when it arrives, and breaks the blocks under its
 *       footprint the same way, a few rows deep at most ({@link #MAX_CRATER_ROWS}).</li>
 * </ol>
 */
public final class HeadMissile {
    private HeadMissile() {
    }

    /** Head mass on the reference vessel, kg. */
    public static final double REFERENCE_MASS_KG = 90_000.0;

    /** Footprint of the reference vessel, blocks squared (17 x 17). */
    public static final double REFERENCE_FOOTPRINT_BLOCKS = 17.0 * 17.0;

    /** Lightest and heaviest head the model will give, kg. */
    public static final double MIN_MASS_KG = 5_000.0;
    public static final double MAX_MASS_KG = 400_000.0;

    /** Share of the stored failure energy that can become the head's kinetic energy. */
    public static final double KINETIC_FRACTION = 0.05;

    /** Stroke over which the steam keeps pushing the head before the vent opens, as a fraction of the head's equivalent radius. */
    public static final double VENT_STROKE_FRACTION = 0.1;

    /** Gravity, m/s^2. One block is one metre and a tick is a twentieth of a second. */
    public static final double GRAVITY = 9.81;
    public static final double TICKS_PER_SECOND = 20.0;

    /** The highest a head climbs above the vessel top, blocks. */
    public static final int MAX_CLIMB_BLOCKS = 40;

    /** A head stopped within this many rows of the vessel top stays lodged under what stopped it. */
    public static final int LODGE_ROWS = 1;

    /** The deepest an impact digs, rows. */
    public static final int MAX_CRATER_ROWS = 3;

    /** The most blocks an impact removes. */
    public static final int MAX_CRATER_BLOCKS = 600;

    /** Drift of an uncontained head: the clear gap between its landing footprint and the vessel (corner to near edge), blocks (minimum, then spread). */
    public static final double DRIFT_MIN_BLOCKS = 3.0;
    public static final double DRIFT_SPREAD_BLOCKS = 10.0;

    private static final double PA_PER_PSI = 6894.757;

    /** Area of the head's footprint (an ellipse in the vessel's outside width and depth), m^2. */
    public static double areaM2(int width, int depth) {
        return Math.PI / 4.0 * Math.max(1, width) * Math.max(1, depth);
    }

    /**
     * How far from the vessel's axis the centre of a thrown head comes down, blocks: the vessel's corner radius, plus the
     * head's own radius, plus {@link #DRIFT_MIN_BLOCKS}, plus {@code unit} (0 to 1) of {@link #DRIFT_SPREAD_BLOCKS}. The head
     * therefore never lands on the vessel it came from.
     */
    public static double driftDistanceBlocks(int width, int depth, double unit) {
        double w = Math.max(1, width), d = Math.max(1, depth);
        return Math.hypot(w, d) / 2.0 + Math.max(w, d) / 2.0 + DRIFT_MIN_BLOCKS + Math.clamp(unit, 0.0, 1.0) * DRIFT_SPREAD_BLOCKS;
    }

    /** Mass of a head for a vessel with the given outside footprint, kg. */
    public static double massKg(int width, int depth) {
        double area = Math.max(1, width) * (double) Math.max(1, depth);
        return Math.clamp(REFERENCE_MASS_KG * area / REFERENCE_FOOTPRINT_BLOCKS, MIN_MASS_KG, MAX_MASS_KG);
    }

    /** Work the steam does on the head over the vent stroke, J: {@code (P A - m g) s}, never negative. */
    public static double steamWorkJ(double pressurePsig, int width, int depth) {
        double force = Math.max(0.0, finite(pressurePsig)) * PA_PER_PSI * areaM2(width, depth);
        double radius = 0.5 * Math.sqrt(Math.max(1, width) * (double) Math.max(1, depth));
        double stroke = VENT_STROKE_FRACTION * radius;
        return Math.max(0.0, force - massKg(width, depth) * GRAVITY) * stroke;
    }

    /**
     * Kinetic energy the head leaves with, J: the smaller of {@value #KINETIC_FRACTION} of the stored energy and the
     * work the steam does on the head ({@link #steamWorkJ}).
     */
    public static double kineticEnergyJ(double storedEnergyJ, double pressurePsig, int width, int depth) {
        return Math.min(KINETIC_FRACTION * Math.max(0.0, finite(storedEnergyJ)), steamWorkJ(pressurePsig, width, depth));
    }

    /** Height the head would rise to with nothing in the way and no cap, blocks: {@code E / (m g)}. */
    public static double freeRiseBlocks(double massKg, double kineticJ) {
        return Math.max(0.0, finite(kineticJ)) / (Math.max(1.0, finite(massKg)) * GRAVITY);
    }

    /** Height a flight actually climbs to, blocks: the free rise, capped. */
    public static double apexBlocks(double massKg, double kineticJ) {
        return Math.min(MAX_CLIMB_BLOCKS, freeRiseBlocks(massKg, kineticJ));
    }

    /** Speed that carries a body up {@code blocks} against gravity, m/s. */
    public static double speedForRise(double blocks) {
        return Math.sqrt(2.0 * GRAVITY * Math.max(0.0, finite(blocks)));
    }

    /** Time to rise {@code blocks} and fall back to where it started, seconds. */
    public static double flightSeconds(double blocks) {
        return 2.0 * speedForRise(blocks) / GRAVITY;
    }

    /**
     * Energy one block of the given rating absorbs when a head is pushed through it, J: the rating as a pressure over
     * the one square metre of the block's face, through its one metre of thickness. The rating is floored like the
     * blast model floors it ({@link BlastPhysics#effectiveRating}).
     */
    public static double crushJ(double ratingPsi) {
        return BlastPhysics.effectiveRating(ratingPsi) * PA_PER_PSI;
    }

    /** Energy a body of {@code massKg} has falling at {@code speedBlocksPerTick}, J. */
    public static double impactEnergyJ(double massKg, double speedBlocksPerTick) {
        double v = Math.abs(finite(speedBlocksPerTick)) * TICKS_PER_SECOND;
        return 0.5 * Math.max(0.0, finite(massKg)) * v * v;
    }

    /** What happened when the head met the next row. */
    public enum Step {
        /** The head crossed the row (an open row, or one it had the energy to punch out). */
        PASSED,
        /** The row is stronger than what the head has left: the head stops against it and the row cracks. */
        STOPPED,
        /** The head ran out of energy before it reached the next row, in the open or in the hole it had punched. */
        PEAKED
    }

    /**
     * The head rising row by row. Create it with the mass and kinetic energy, then call {@link #next} with the
     * capacity (J) of each row, bottom first. A capacity of 0 or less is an open row; {@code Double.POSITIVE_INFINITY}
     * is a row nothing can break.
     */
    public static final class Climb {
        private final double gravityRowJ;
        private double energyJ;
        private int rows;
        private double stopFraction;

        public Climb(double massKg, double kineticJ) {
            this.gravityRowJ = Math.max(1.0, finite(massKg)) * GRAVITY;
            this.energyJ = Math.max(0.0, finite(kineticJ));
        }

        /** Rows crossed so far; after a stop, the index of the row that stopped the head. */
        public int rows() {
            return rows;
        }

        /** Energy left, J. */
        public double energyJ() {
            return energyJ;
        }

        /** After {@link Step#STOPPED}: the share (0 to 1) of the row's capacity the head used before it stopped. */
        public double stopFraction() {
            return stopFraction;
        }

        /** True once the head has crossed {@link #MAX_CLIMB_BLOCKS} rows with energy to spare. */
        public boolean cleared() {
            return rows >= MAX_CLIMB_BLOCKS && energyJ > 0.0;
        }

        /** Height the head reaches after clearing the rows it has crossed, blocks, capped at the climb limit. */
        public double apexBlocks(double massKg) {
            double apex = rows + freeRiseBlocks(massKg, energyJ);
            return Math.min(MAX_CLIMB_BLOCKS, apex);
        }

        /** True when the head has at least {@code capacityJ} left, so that it punches a row of that capacity out. */
        public boolean canPunch(double capacityJ) {
            return energyJ >= capacityJ;
        }

        /** Meet the next row, whose blocks together absorb {@code capacityJ}. */
        public Step next(double capacityJ) {
            double cap = Double.isNaN(capacityJ) ? 0.0 : capacityJ;
            if (cap > 0.0) {
                if (energyJ < cap) {
                    stopFraction = Double.isInfinite(cap) ? 0.0 : energyJ / cap;
                    energyJ = 0.0;
                    return Step.STOPPED;
                }
                energyJ -= cap;
            }
            energyJ -= gravityRowJ;
            rows++;
            if (energyJ <= 0.0) {
                energyJ = 0.0;
                return Step.PEAKED;
            }
            return Step.PASSED;
        }
    }

    /** The outcome of a whole climb through known rows (for tests and tools). */
    public record Resolution(Step last, int rows, double stopFraction, double residualJ, boolean cleared, double apexBlocks) {
        public boolean contained() {
            return !cleared;
        }
    }

    /** Run a climb through the given row capacities, bottom first. Rows past the end of the array are open. */
    public static Resolution resolve(double massKg, double kineticJ, double[] rowCapacityJ) {
        Climb climb = new Climb(massKg, kineticJ);
        Step last = Step.PASSED;
        for (int i = 0; i < MAX_CLIMB_BLOCKS; i++) {
            double cap = i < rowCapacityJ.length ? rowCapacityJ[i] : 0.0;
            last = climb.next(cap);
            if (last != Step.PASSED) break;
        }
        return new Resolution(last, climb.rows(), climb.stopFraction(), climb.energyJ(), climb.cleared(), climb.apexBlocks(massKg));
    }

    private static double finite(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}
