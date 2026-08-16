package dev.bwr.core.eccs;

import dev.bwr.core.thermal.Saturation;

/**
 * The steam turbine that drives RCIC and HPCI — {@code SPEC.md} section 9.1.
 *
 * <h2>Why this class is the whole point of turbine-driven ECCS</h2>
 * These two systems need <b>no AC power at all</b>. Their motive force is the
 * reactor's own steam, so they are the only injection that survives a station
 * blackout. In exchange they are hostage to the reactor's own condition in two
 * ways that fall straight out of the thermodynamics below and are not written
 * anywhere as a rule:
 *
 * <ul>
 *   <li><b>They consume steam.</b> Every kilogram the turbine passes is a
 *       kilogram that left the vessel, so running HPCI is a pressure-control
 *       action whether the operator meant it as one or not.</li>
 *   <li><b>They die as the vessel depressurises.</b> Specific work falls with
 *       the expansion ratio, so a blown-down vessel cannot spin the turbine
 *       hard enough to overcome its own windage. Nobody wrote a cutoff
 *       pressure; the machine simply stops making power. Blow the vessel down
 *       with the ADS and you have traded your blackout-proof injection for the
 *       big motor-driven pumps — which is exactly the decision the ADS is
 *       supposed to force.</li>
 * </ul>
 *
 * <p>Turbine back pressure is the exhaust destination — the suppression pool
 * airspace. So a containment that has been allowed to pressurise takes power
 * away from the very pumps keeping the core covered. That is the Fukushima
 * Unit 2 and 3 failure mode, and it is emergent here rather than scripted.
 *
 * <h2>The approximation, stated honestly</h2>
 * Expansion of saturated steam is treated as polytropic with Zeuner's exponent
 * {@code n = 1.135}, with the inlet specific volume from the ideal gas law for
 * steam. Ideal gas overstates the specific volume of saturated steam at
 * 1000 psia by roughly 30%, which flatters the specific work by about the same;
 * the isentropic efficiencies below are calibrated against real machines with
 * that bias included, so the delivered shaft power lands in the right place.
 * The alternative is a steam table library, and {@code SPEC.md} section 6.1 is
 * explicit that this model does not carry one.
 *
 * <p>Pure Java, no Minecraft imports.
 */
public final class SteamTurbineDrive {

    private SteamTurbineDrive() {
    }

    /** Zeuner's polytropic exponent for expanding saturated steam. */
    public static final double POLYTROPIC_EXPONENT = 1.135;

    /** Specific gas constant for water vapour, J/kg/K. */
    public static final double STEAM_GAS_CONSTANT_J_PER_KG_K = 461.5;

    /** Pascals per psi. */
    public static final double PA_PER_PSI = 6894.757;

    /**
     * Ideal specific work available from expanding saturated steam between two
     * pressures, kJ/kg. Zero if there is no expansion ratio to work with.
     *
     * @param inletPsia   turbine inlet pressure, psia — the reactor dome
     * @param exhaustPsia turbine exhaust pressure, psia — the suppression pool
     */
    public static double specificWorkKJPerKg(double inletPsia, double exhaustPsia) {
        double p1 = Saturation.clampPressurePsia(inletPsia);
        double p2 = Saturation.clampPressurePsia(exhaustPsia);
        if (p2 >= p1) {
            return 0.0;
        }
        double t1K = Saturation.temperatureCelsiusFromPsia(p1) + 273.15;
        double v1 = STEAM_GAS_CONSTANT_J_PER_KG_K * t1K / (p1 * PA_PER_PSI);
        double n = POLYTROPIC_EXPONENT;
        double ratio = Math.pow(p2 / p1, (n - 1.0) / n);
        double workJPerKg = (n / (n - 1.0)) * (p1 * PA_PER_PSI) * v1 * (1.0 - ratio);
        return Math.max(0.0, workJPerKg / 1000.0);
    }

    /**
     * Shaft power a given steam flow produces, watts.
     *
     * @param steamKgPerS steam admitted to the turbine
     * @param inletPsia   dome pressure, psia
     * @param exhaustPsia back pressure, psia
     * @param efficiency  isentropic efficiency of the machine
     */
    public static double shaftPowerWatts(double steamKgPerS, double inletPsia,
                                         double exhaustPsia, double efficiency) {
        if (!(steamKgPerS > 0.0)) {
            return 0.0;
        }
        return steamKgPerS * specificWorkKJPerKg(inletPsia, exhaustPsia) * 1000.0
                * Math.max(0.0, efficiency);
    }

    /**
     * Steam a turbine must swallow to produce a given shaft power, kg/s.
     * Grows without bound as the expansion ratio collapses, which is the
     * physical reason a depressurising vessel starves its own turbine drives.
     *
     * @return the demand, or {@link Double#POSITIVE_INFINITY} when there is no
     *         useful expansion left at all
     */
    public static double steamDemandKgPerS(double shaftWatts, double inletPsia,
                                           double exhaustPsia, double efficiency) {
        if (!(shaftWatts > 0.0)) {
            return 0.0;
        }
        double perKgJ = specificWorkKJPerKg(inletPsia, exhaustPsia) * 1000.0 * Math.max(0.0, efficiency);
        if (!(perKgJ > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        return shaftWatts / perKgJ;
    }
}
