package dev.bwr.core.accident;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.FuelThermal;

/**
 * Scenario builders shared by the severe accident tests, {@code SPEC.md}
 * section 8.
 *
 * <p>Not a test class — {@link dev.bwr.core.AcceptanceTests} only reflects over
 * the classes it lists, and this one is deliberately not listed. It exists so
 * every accident test starts a core the same way, rather than each test
 * inventing its own coupling and quietly testing a different model.
 *
 * <h2>Two different steam numbers, and they are not the same number</h2>
 * {@link FuelThermal} takes steam twice, for two unrelated jobs:
 * <ul>
 *   <li>{@code setSteamCoolingFlowKgPerS} — steam sweeping past uncovered fuel.
 *       This is a <b>heat sink</b>, and a poor one.</li>
 *   <li>{@code setSteamSupplyKgPerS} — steam available to the zirconium-water
 *       reaction. This is a <b>reactant</b>, and running out of it stops the
 *       reaction dead.</li>
 * </ul>
 * {@code ReactorCore.tick} sets the second to vessel boiloff plus the spray
 * water the core just evaporated, which is the feedback loop behind the quench
 * spike: the flood that cools the fuel is also the flood that feeds the
 * reaction. {@link #wireSteamSupplyToBoiloffAndSpray} reproduces that exactly.
 * Tests that want the temperature feedback isolated from the reactant feedback
 * instead call {@link #giveAmpleSteamSupply} and say so.
 */
final class AccidentScenario {

    private AccidentScenario() {
    }

    /** Dome pressure held through every scenario, psig. */
    static final double PSIG = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;

    /** Game tick, seconds — the step length the mod actually uses. */
    static final double TICK = 0.05;

    /**
     * Decay heat held constant in the tests that need the Zr feedback isolated,
     * fraction of rated. Two percent is roughly what a saturated core makes
     * twenty minutes after shutdown, and holding it fixed means any acceleration
     * seen afterwards cannot have come from the heat source.
     */
    static final double CONSTANT_DECAY_FRACTION = 0.02;

    /** Steam sweeping the uncovered fuel in the standard dry-core scenario, kg/s. */
    static final double DRY_CORE_STEAM_COOLING_KG_PER_S = 2.0;

    /** A core that has been sitting at rated power and full pressure. */
    static FuelThermal ratedCore() {
        FuelThermal fuel = new FuelThermal(new CoreConfig());
        fuel.initialiseToSteadyState(1.0, PSIG);
        return fuel;
    }

    /**
     * Stage 1: the level is below the active fuel and the only thing touching
     * the rods is the steam still boiling out of what is left. The reactant
     * supply is set to the same stream, which is the physically coupled case.
     */
    static void uncover(FuelThermal fuel, double steamKgPerS) {
        fuel.setCoveredFuelFraction(0.0);
        fuel.setSteamCoolingFlowKgPerS(steamKgPerS);
        fuel.setSteamSupplyKgPerS(steamKgPerS);
    }

    /**
     * Leave the reaction unlimited by reactant so the only feedback in play is
     * temperature. Not a realistic dry core — a deliberate isolation, used only
     * where the test says so.
     */
    static void giveAmpleSteamSupply(FuelThermal fuel) {
        fuel.setSteamSupplyKgPerS(FuelThermal.DEFAULT_STEAM_SUPPLY_KG_PER_S);
    }

    /** Exactly what {@code ReactorCore.tick} does with the reactant supply. */
    static void wireSteamSupplyToBoiloffAndSpray(FuelThermal fuel) {
        fuel.setSteamSupplyKgPerS(
                fuel.getSteamCoolingFlowKgPerS() + fuel.getSprayEvaporationKgPerS());
    }

    /** One tick with no fission power at all. */
    static void tick(FuelThermal fuel, double decayFraction) {
        fuel.step(0.0, decayFraction, PSIG, TICK);
    }

    /** One tick with the reactant supply re-derived the way {@code ReactorCore} does. */
    static void tickWired(FuelThermal fuel, double decayFraction) {
        fuel.step(0.0, decayFraction, PSIG, TICK);
        wireSteamSupplyToBoiloffAndSpray(fuel);
    }

    /** Run wired for a wall-clock duration at constant decay heat. */
    static void runWired(FuelThermal fuel, double seconds, double decayFraction) {
        int steps = (int) Math.round(seconds / TICK);
        for (int i = 0; i < steps; i++) {
            tickWired(fuel, decayFraction);
        }
    }

    /**
     * Heat a wired core until the cladding reaches a target temperature.
     *
     * @return seconds elapsed, or {@link Double#NaN} if the target was never
     *         reached inside {@code limitSeconds} — itself a result worth
     *         asserting on, because a core that stabilises below the reaction
     *         onset has been saved
     */
    static double heatUntilCladReaches(FuelThermal fuel, double targetC,
                                       double decayFraction, double limitSeconds) {
        int limit = (int) Math.round(limitSeconds / TICK);
        for (int i = 0; i < limit; i++) {
            if (fuel.getCladTemperatureC() >= targetC) {
                return i * TICK;
            }
            tickWired(fuel, decayFraction);
        }
        return Double.NaN;
    }

    /**
     * The ECCS action of {@code SPEC.md} section 8.2: core spray on and the
     * vessel refilled so the fuel is under water again.
     */
    static void reflood(FuelThermal fuel, double sprayKgPerS) {
        fuel.setCoreSprayFlowKgPerS(sprayKgPerS);
        fuel.setCoveredFuelFraction(1.0);
    }

    /** Everything one reflood scenario measured. */
    static final class RefloodOutcome {
        double secondsToTarget;
        double peakCladAtRefloodC;
        double hydrogenAtRefloodKg;
        double oxidationAtReflood;
        double oxideThicknessAtRefloodMicrons;
        double hydrogenRateAtRefloodKgPerS;
        double kineticRateAtRefloodKgPerS;
        double actualRateAtRefloodKgPerS;

        double hydrogenAfterRefloodKg;
        double oxidationAfter;
        double minimumOxideThicknessMicrons;
        double peakHydrogenRateKgPerS;
        double peakReactionPowerMW;
        double peakCladAfterC;
        double finalCladC;
        double coolantC;

        /** Hydrogen produced by the reflood itself, kg. The quench spike, weighed. */
        double refloodHydrogenKg() {
            return hydrogenAfterRefloodKg - hydrogenAtRefloodKg;
        }

        /** Reflood hydrogen as a fraction of everything the heatup had already made. */
        double refloodHydrogenFraction() {
            return refloodHydrogenKg() / hydrogenAtRefloodKg;
        }

        /** How much the reflood multiplied the hydrogen production rate. */
        double hydrogenRateAmplification() {
            return peakHydrogenRateKgPerS / Math.max(1.0e-12, hydrogenRateAtRefloodKgPerS);
        }

        /** Fraction of the protective oxide the quench cracked away. */
        double oxideStrippedFraction() {
            return 1.0 - minimumOxideThicknessMicrons / oxideThicknessAtRefloodMicrons;
        }
    }

    /**
     * Heat a dry core to {@code refloodAtCladC} on constant decay heat, then
     * flood it and watch for {@code observeSeconds}.
     */
    static RefloodOutcome refloodAt(double refloodAtCladC, double sprayKgPerS, double observeSeconds) {
        FuelThermal fuel = ratedCore();
        uncover(fuel, DRY_CORE_STEAM_COOLING_KG_PER_S);

        RefloodOutcome out = new RefloodOutcome();
        out.secondsToTarget =
                heatUntilCladReaches(fuel, refloodAtCladC, CONSTANT_DECAY_FRACTION, 20_000.0);
        out.peakCladAtRefloodC = fuel.getPeakCladTemperatureC();
        out.hydrogenAtRefloodKg = fuel.getHydrogenGeneratedKg();
        out.oxidationAtReflood = fuel.getOxidationFraction();
        out.oxideThicknessAtRefloodMicrons = fuel.getProtectiveOxideThicknessMicrons();
        out.hydrogenRateAtRefloodKgPerS = fuel.getHydrogenGenerationRateKgPerS();
        out.kineticRateAtRefloodKgPerS = fuel.getKineticReactionRateKgPerS();
        out.actualRateAtRefloodKgPerS = fuel.getZirconiumReactionRateKgPerS();

        reflood(fuel, sprayKgPerS);
        out.minimumOxideThicknessMicrons = Double.MAX_VALUE;
        int steps = (int) Math.round(observeSeconds / TICK);
        for (int i = 0; i < steps; i++) {
            tickWired(fuel, CONSTANT_DECAY_FRACTION);
            out.peakHydrogenRateKgPerS =
                    Math.max(out.peakHydrogenRateKgPerS, fuel.getHydrogenGenerationRateKgPerS());
            out.peakReactionPowerMW =
                    Math.max(out.peakReactionPowerMW, fuel.getZirconiumReactionPowerMW());
            out.minimumOxideThicknessMicrons =
                    Math.min(out.minimumOxideThicknessMicrons, fuel.getProtectiveOxideThicknessMicrons());
        }
        out.hydrogenAfterRefloodKg = fuel.getHydrogenGeneratedKg();
        out.oxidationAfter = fuel.getOxidationFraction();
        out.peakCladAfterC = fuel.getPeakCladTemperatureC();
        out.finalCladC = fuel.getCladTemperatureC();
        out.coolantC = fuel.getCoolantTemperatureC();
        return out;
    }
}
