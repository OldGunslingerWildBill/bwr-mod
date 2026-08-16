package dev.bwr.core.boundary;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * How the recirculation system is built, and therefore which parts of the
 * pressure boundary exist to be overpressured. {@code SPEC.md} sections 4.4
 * and 7.
 *
 * <p>This is the one place the reactor internal pump upgrade earns its keep.
 * A jet pump plant carries its recirculation loops <i>outside</i> the vessel:
 * two large-bore pipes leave below the core, run through pumps, and come back
 * in. Those pipes are part of the pressure boundary, they are the biggest
 * penetration on the vessel, and a break in one drains the core from below
 * while simultaneously taking the driving head away. An ABWR-style plant with
 * pumps mounted through the vessel bottom head has no such pipe to lose. The
 * remaining boundary — steam lines, feedwater lines, the head itself — is the
 * same on both.
 *
 * <p>So reactor internal pumps do two things at once here. They remove the
 * most exposed component, which lowers the total rate at which the plant
 * accumulates damage and lowers the total failure hazard once it is over the
 * code limit, so a RIP plant survives an overpressure event measurably longer.
 * And they remove the <i>worst</i> failure mode outright: a RIP plant cannot
 * suffer the design basis large-break LOCA, because the pipe that break is
 * defined on does not exist. That is a mechanical advantage from geometry,
 * which is what {@code SPEC.md} section 4.4 asks for — not a balance number.
 *
 * <p>Nothing here is a judgement. This enum says what pipework is present; it
 * does not say whether the plant is being operated acceptably.
 */
public enum PlantConfiguration {

    /**
     * External recirculation loops driving jet pumps — BWR/4 through BWR/6.
     * Every component in {@link BoundaryComponent} is present.
     */
    EXTERNAL_RECIRCULATION_LOOPS("External recirculation loops",
            EnumSet.of(BoundaryComponent.MAIN_STEAM_LINE,
                    BoundaryComponent.FEEDWATER_LINE,
                    BoundaryComponent.RECIRCULATION_LINE,
                    BoundaryComponent.REACTOR_VESSEL_HEAD)),

    /**
     * Reactor internal pumps — ABWR. No external recirculation piping exists,
     * so {@link BoundaryComponent#RECIRCULATION_LINE} is not part of this
     * plant's pressure boundary and can neither accumulate stress nor break.
     */
    REACTOR_INTERNAL_PUMPS("Reactor internal pumps",
            EnumSet.of(BoundaryComponent.MAIN_STEAM_LINE,
                    BoundaryComponent.FEEDWATER_LINE,
                    BoundaryComponent.REACTOR_VESSEL_HEAD));

    private final String displayName;
    private final Set<BoundaryComponent> exposed;
    private final double totalExposureWeight;

    PlantConfiguration(String displayName, Set<BoundaryComponent> exposed) {
        this.displayName = displayName;
        this.exposed = Collections.unmodifiableSet(exposed);
        double sum = 0.0;
        for (BoundaryComponent component : exposed) {
            sum += component.exposureWeight();
        }
        this.totalExposureWeight = sum;
    }

    /** Human-readable name for a panel or a log line. */
    public String displayName() {
        return displayName;
    }

    /** The pressure boundary components this plant actually has. Immutable. */
    public Set<BoundaryComponent> exposedComponents() {
        return exposed;
    }

    /** Whether this plant has the given component at all. */
    public boolean exposes(BoundaryComponent component) {
        return exposed.contains(component);
    }

    /**
     * Sum of the exposure weights present. 3.25 for external loops against
     * 2.05 for reactor internal pumps, so a RIP plant accumulates about 37%
     * less total stress per second of overpressure and carries a
     * correspondingly smaller failure hazard.
     */
    public double totalExposureWeight() {
        return totalExposureWeight;
    }
}
