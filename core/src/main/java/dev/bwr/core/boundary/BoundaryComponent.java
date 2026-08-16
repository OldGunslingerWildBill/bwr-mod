package dev.bwr.core.boundary;

/**
 * One piece of the reactor pressure boundary that overpressure can wear out.
 * {@code SPEC.md} section 7.
 *
 * <p>Each carries an <b>exposure weight</b>: how fast it accumulates stress
 * relative to the main steam lines, which are taken as unity. The weights encode
 * geometry, not balance. A large-bore recirculation suction line is the most
 * exposed penetration on a jet pump plant and the vessel head is a forging
 * several inches thick, so they sit at opposite ends of the same scale, and the
 * consequences of losing them sit at opposite ends of another one.
 *
 * <p>Nothing here is a setpoint and nothing here is a probability. The weight is
 * a property of the pipe.
 */
public enum BoundaryComponent {

    /**
     * Main steam lines. Full dome pressure, largest steam-side bore, and the
     * lines the SRVs and MSIVs are hung off. Breaking one depressurises the
     * vessel fast: voids <i>form</i>, reactivity goes negative and power drops,
     * but the level swell and the subsequent shrink are violent.
     */
    MAIN_STEAM_LINE("Main steam line", 1.00, false),

    /**
     * Feedwater lines. Slightly less exposed than the steam lines — smaller bore
     * and cooler metal. Losing one is the slow failure: makeup goes away, level
     * declines, and the player's ECCS timers start whether or not they have
     * written any.
     */
    FEEDWATER_LINE("Feedwater line", 0.80, false),

    /**
     * External recirculation piping. The nasty one, and the reason
     * {@code SPEC.md} section 4.4 makes reactor internal pumps a genuine
     * upgrade rather than a cosmetic one: a break here loses core flow and
     * vessel inventory simultaneously, which is the design basis LOCA that sizes
     * ECCS on a jet pump plant.
     *
     * <p>Present only on {@link PlantConfiguration#EXTERNAL_RECIRCULATION_LOOPS}.
     */
    RECIRCULATION_LINE("Recirculation line", 1.20, false),

    /**
     * The reactor vessel head and its closure studs. Thick, so it accumulates
     * stress four times more slowly than a steam line and only sustained gross
     * overpressure ever gets it there. Terminal when it goes.
     */
    REACTOR_VESSEL_HEAD("Reactor vessel head", 0.25, true);

    private final String displayName;
    private final double exposureWeight;
    private final boolean terminal;

    BoundaryComponent(String displayName, double exposureWeight, boolean terminal) {
        this.displayName = displayName;
        this.exposureWeight = exposureWeight;
        this.terminal = terminal;
    }

    /** Human-readable name for a panel or a log line. */
    public String displayName() {
        return displayName;
    }

    /** Stress accumulation rate relative to the main steam lines. */
    public double exposureWeight() {
        return exposureWeight;
    }

    /** True for a failure the plant does not come back from. */
    public boolean isTerminal() {
        return terminal;
    }
}
