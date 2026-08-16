package dev.bwr.core.boundary;

/**
 * One pressure boundary component that let go, and the conditions it let go
 * under. {@code SPEC.md} section 7.
 *
 * <p>A record rather than a bare enum because the interesting part of a
 * failure is not <i>what</i> broke but <i>what the plant was doing when it
 * did</i>. Kept so that a player who comes back to a wrecked plant can read
 * how it happened rather than guess, and so that the accumulated stress at
 * the moment of failure is available as evidence that the break was earned.
 *
 * @param component        what broke
 * @param atSeconds        model clock reading when it broke, seconds since the
 *                         boundary model was reset
 * @param pressurePsig     dome pressure at the moment of failure, psig
 * @param accumulatedStress how much stress that component had accumulated,
 *                         in units where {@link BoundaryStress#FAILURE_STRESS}
 *                         is one exhausted life
 */
public record BoundaryFailure(
        BoundaryComponent component,
        double atSeconds,
        double pressurePsig,
        double accumulatedStress
) {

    /** True for a failure the plant does not come back from. */
    public boolean isTerminal() {
        return component.isTerminal();
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "%s failed at %.1f s, %.0f psig, accumulated stress %.2f%s",
                component.displayName(), atSeconds, pressurePsig, accumulatedStress,
                component.isTerminal() ? " (terminal)" : "");
    }
}
