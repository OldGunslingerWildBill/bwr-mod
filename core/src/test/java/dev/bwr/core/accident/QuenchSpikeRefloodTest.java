package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.FuelThermal;

/**
 * Reflood branching and the quench spike, {@code SPEC.md} section 8.2.
 *
 * <p>Restoring core spray in stage 3 usually terminates the accident,
 * <b>but the outcome depends on when</b>:
 *
 * <ul>
 *   <li><b>Early</b>, peak clad around 1200-1500 C and low oxidation: clean
 *       recovery. Water removes heat far faster than the reaction makes it.</li>
 *   <li><b>Late</b>, above about 1800-2000 K: <b>quench spike</b>. Thermal shock
 *       cracks the brittle oxide and exposes fresh zirconium while the flood
 *       itself boils off a surge of steam to feed it. A transient escalation in
 *       oxidation and hydrogen before cooldown. Documented in the QUENCH
 *       experiment series and observed at TMI-2, where it produced a containment
 *       hydrogen burn.</li>
 *   <li><b>Very late</b>, post-relocation: you are cooling corium, not fuel — and
 *       {@code core/} does not model corium, so that branch does not exist here.
 *       {@link #test09_theVeryLateBranchIsNotModelled} says so.</li>
 * </ul>
 *
 * <p><b>Reflood is still always the right action.</b>
 * {@link #test06_refloodIsNeverWorseThanDoingNothingOverTheWholeAccident} measures
 * exactly how true that is — including the window in which it is nearly false. But it is not free, and
 * {@link #test03_lateRefloodMakesFarMoreHydrogenThanEarlyReflood} is how much it
 * costs.
 *
 * <h2>Nothing here is a decision</h2>
 * These tests set actuators — spray flow, covered fraction — and read
 * measurements. No method consulted returns a judgement about whether the core
 * is in trouble, because none exists. That is the player's job in Lua.
 */
public final class QuenchSpikeRefloodTest {

    private QuenchSpikeRefloodTest() {
    }

    /** Core spray used for every reflood here, kg/s. */
    private static final double SPRAY_KG_PER_S = 500.0;

    /** How long each reflood is watched, seconds. */
    private static final double OBSERVE_S = 900.0;

    /** An early reflood: peak clad well inside the 1200-1500 C band SPEC 8.2 calls clean. */
    private static final double EARLY_C = 1400.0;

    /** A late reflood: comfortably past the ~1800 K quench spike threshold. */
    private static final double LATE_C = 1900.0;

    private static void describe(String label, AccidentScenario.RefloodOutcome out) {
        Check.note("%s: reflood at %.0f C after %.0f s of heatup", label, out.peakCladAtRefloodC,
                out.secondsToTarget);
        Check.note("   before: H2 %.2f kg, oxidation %.3f%%, protective oxide %.1f um,"
                        + " reaction %.2f kg/s (kinetic %.2f — starved %.0fx)",
                out.hydrogenAtRefloodKg, 100.0 * out.oxidationAtReflood,
                out.oxideThicknessAtRefloodMicrons, out.actualRateAtRefloodKgPerS,
                out.kineticRateAtRefloodKgPerS,
                out.kineticRateAtRefloodKgPerS / Math.max(1.0e-9, out.actualRateAtRefloodKgPerS));
        Check.note("   after:  H2 +%.2f kg (%.1f%% more), oxidation %.3f%%, oxide floor %.1f um"
                        + " (%.0f%% stripped), peak H2 rate %.3f kg/s (%.0fx), peak reaction"
                        + " %.0f MW, clad %.1f C (coolant %.1f C)",
                out.refloodHydrogenKg(), 100.0 * out.refloodHydrogenFraction(),
                100.0 * out.oxidationAfter, out.minimumOxideThicknessMicrons,
                100.0 * out.oxideStrippedFraction(), out.peakHydrogenRateKgPerS,
                out.hydrogenRateAmplification(), out.peakReactionPowerMW, out.finalCladC,
                out.coolantC);
    }

    /**
     * <b>The early branch.</b> Flood a core whose peak cladding never left the
     * 1200-1500 C band and the recovery is clean: the oxide is thin, it does not
     * crack, the reaction adds a rounding error to the hydrogen already made, and
     * the cladding returns to saturation.
     */
    public static void test01_earlyRefloodIsACleanRecovery() {
        AccidentScenario.RefloodOutcome out =
                AccidentScenario.refloodAt(EARLY_C, SPRAY_KG_PER_S, OBSERVE_S);
        describe("EARLY", out);

        Check.lessThan(PhysicalConstants.QUENCH_SPIKE_THRESHOLD_C, out.peakCladAtRefloodC,
                "an early reflood must be below the quench spike threshold, degC");
        Check.lessThan(0.05, out.refloodHydrogenFraction(),
                "hydrogen the reflood itself added, as a fraction of the heatup's total");
        Check.lessThan(0.01, out.oxideStrippedFraction(),
                "fraction of the protective oxide cracked away by an early quench");
        Check.lessThan(2.0, out.hydrogenRateAmplification(),
                "hydrogen rate amplification during an early quench");
        Check.absolute(out.coolantC, out.finalCladC, 5.0,
                "cladding returns to the saturation temperature after an early reflood, degC");
        Check.exactly(out.peakCladAtRefloodC, out.peakCladAfterC,
                "an adequate reflood must not push the peak clad temperature any higher");
        Check.note("the accident is over: %.3f%% of the zirconium oxidised in total, %.1f kg of"
                        + " hydrogen, cladding back at saturation. This is the design basis, and"
                        + " it is why ECCS response time matters.",
                100.0 * out.oxidationAfter, out.hydrogenAfterRefloodKg);
    }

    /**
     * <b>The late branch — the quench spike.</b> Flood a core above the threshold
     * and three things happen at once, all of them emergent:
     *
     * <ol>
     *   <li>The rapid cooldown cracks the thick brittle oxide, and the protective
     *       layer collapses toward its floor.</li>
     *   <li>The spray flashes to steam, and that steam is the reactant the dry
     *       core had run out of.</li>
     *   <li>The reaction restarts on a nearly bare surface at nearly 1900 C, and
     *       the hydrogen rate goes up by more than an order of magnitude before
     *       the core finally cools.</li>
     * </ol>
     *
     * <p>Note what does <i>not</i> happen: the peak cladding temperature does not
     * rise. The spike is in oxidation and hydrogen, which is exactly what SPEC 8.2
     * says it is.
     */
    public static void test02_lateRefloodProducesAQuenchSpike() {
        AccidentScenario.RefloodOutcome out =
                AccidentScenario.refloodAt(LATE_C, SPRAY_KG_PER_S, OBSERVE_S);
        describe("LATE", out);

        Check.greaterThan(PhysicalConstants.QUENCH_SPIKE_THRESHOLD_C, out.peakCladAtRefloodC,
                "a late reflood must be above the quench spike threshold, degC");
        Check.greaterThan(0.20, out.refloodHydrogenFraction(),
                "the quench spike must add a substantial fraction of the heatup's own hydrogen");
        Check.greaterThan(0.80, out.oxideStrippedFraction(),
                "fraction of the protective oxide cracked away by a late quench");
        Check.greaterThan(10.0, out.hydrogenRateAmplification(),
                "hydrogen rate amplification during a late quench");
        Check.greaterThan(out.actualRateAtRefloodKgPerS * 5.0,
                out.peakReactionPowerMW / FuelThermal.ZR_HEAT_OF_REACTION_MJ_PER_KG,
                "peak reaction rate during the quench, versus the starved rate before it, kg/s");
        Check.absolute(out.coolantC, out.finalCladC, 5.0,
                "the core does still end up cooled — reflood works, it is just not free");

        // The other half of the mechanism: the core was reactant starved before
        // the flood arrived, so the flood delivered both the shock and the steam.
        Check.greaterThan(2.0, out.kineticRateAtRefloodKgPerS / out.actualRateAtRefloodKgPerS,
                "starvation factor immediately before the flood arrived");
        Check.note("this is the TMI-2 mechanism: a starved, hot, thickly oxidised core meets"
                + " water, the oxide cracks, the flood makes the reactant, and the hydrogen"
                + " arrives in containment in a rush rather than a trickle.");
    }

    /**
     * <b>The headline comparison of SPEC 8.2.</b> Same core, same spray, same
     * decay heat, same everything except <i>when</i>. The late reflood produces
     * far more hydrogen — both in the reflood itself and in total.
     */
    public static void test03_lateRefloodMakesFarMoreHydrogenThanEarlyReflood() {
        AccidentScenario.RefloodOutcome early =
                AccidentScenario.refloodAt(EARLY_C, SPRAY_KG_PER_S, OBSERVE_S);
        AccidentScenario.RefloodOutcome late =
                AccidentScenario.refloodAt(LATE_C, SPRAY_KG_PER_S, OBSERVE_S);

        Check.note("reflood-generated hydrogen: early (%.0f C) %.2f kg, late (%.0f C) %.2f kg"
                        + " — %.0fx more", early.peakCladAtRefloodC, early.refloodHydrogenKg(),
                late.peakCladAtRefloodC, late.refloodHydrogenKg(),
                late.refloodHydrogenKg() / early.refloodHydrogenKg());
        Check.note("as a share of what the heatup had already made: early %.1f%%, late %.1f%%",
                100.0 * early.refloodHydrogenFraction(), 100.0 * late.refloodHydrogenFraction());
        Check.note("total hydrogen at the end of the accident: early %.1f kg, late %.1f kg"
                        + " — %.1fx", early.hydrogenAfterRefloodKg, late.hydrogenAfterRefloodKg,
                late.hydrogenAfterRefloodKg / early.hydrogenAfterRefloodKg);
        Check.note("4%% hydrogen by volume is the deflagration limit in air, which is why real"
                + " Mark I and II containments are nitrogen inerted. Full oxidation of this core"
                + " would make %.0f kg.",
                FuelThermal.DEFAULT_ZIRCONIUM_INVENTORY_KG
                        * FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG);

        Check.greaterThan(10.0, late.refloodHydrogenKg() / early.refloodHydrogenKg(),
                "late reflood hydrogen as a multiple of early reflood hydrogen");
        Check.greaterThan(early.refloodHydrogenFraction() * 10.0, late.refloodHydrogenFraction(),
                "late reflood hydrogen fraction versus early");
        Check.greaterThan(early.hydrogenAfterRefloodKg, late.hydrogenAfterRefloodKg,
                "total hydrogen after a late reflood, kg");
        Check.greaterThan(early.oxidationAfter, late.oxidationAfter,
                "total oxidation fraction after a late reflood");
    }

    /**
     * <b>The branch is a threshold, not a ramp.</b> Sweep the reflood temperature
     * across the whole band. The cost of waiting rises monotonically, and it
     * hinges at {@link PhysicalConstants#QUENCH_SPIKE_THRESHOLD_C}: below it the
     * oxide survives the quench intact, above it the oxide cracks and the cost
     * jumps by an order of magnitude.
     */
    public static void test04_theBranchHingesOnTheQuenchSpikeThreshold() {
        double[] refloodAt = {1250, 1350, 1450, 1600, 1700, 1800, 1900, 2000};
        double previousHydrogen = -1.0;
        double previousFractionAbove = -1.0;
        double lastBelow = Double.NaN;
        double firstAbove = Double.NaN;

        for (double target : refloodAt) {
            AccidentScenario.RefloodOutcome out =
                    AccidentScenario.refloodAt(target, SPRAY_KG_PER_S, OBSERVE_S);
            Check.note("reflood at %4.0f C: +%7.2f kg H2 (%5.1f%% of %6.2f kg already made),"
                            + " oxide %6.1f -> %5.1f um, peak reaction %7.0f MW",
                    target, out.refloodHydrogenKg(), 100.0 * out.refloodHydrogenFraction(),
                    out.hydrogenAtRefloodKg, out.oxideThicknessAtRefloodMicrons,
                    out.minimumOxideThicknessMicrons, out.peakReactionPowerMW);

            Check.greaterThan(previousHydrogen, out.refloodHydrogenKg(),
                    "reflood hydrogen must rise monotonically with reflood temperature"
                            + " (at " + target + " C), kg");
            previousHydrogen = out.refloodHydrogenKg();

            if (target < PhysicalConstants.QUENCH_SPIKE_THRESHOLD_C) {
                lastBelow = out.refloodHydrogenFraction();
                Check.lessThan(0.01, out.oxideStrippedFraction(),
                        "no oxide cracking below the threshold, at " + target + " C");
                Check.lessThan(0.02, out.refloodHydrogenFraction(),
                        "reflood hydrogen as a fraction of the heatup's own, below the threshold");
            } else {
                if (Double.isNaN(firstAbove)) {
                    firstAbove = out.refloodHydrogenFraction();
                }
                Check.greaterThan(previousFractionAbove, out.refloodHydrogenFraction(),
                        "above the threshold the reflood cost must rise monotonically as a"
                                + " fraction too (at " + target + " C)");
                previousFractionAbove = out.refloodHydrogenFraction();
            }
        }
        Check.note("last reflood below %.0f C cost %.1f%% extra hydrogen; first one above cost"
                        + " %.1f%% — a %.0fx step at the threshold",
                PhysicalConstants.QUENCH_SPIKE_THRESHOLD_C, 100.0 * lastBelow, 100.0 * firstAbove,
                firstAbove / lastBelow);
        Check.greaterThan(3.0, firstAbove / lastBelow,
                "step in reflood hydrogen cost across the quench spike threshold");
        Check.note("below the threshold the cost is flat at about 1%% and the ratio wanders by"
                + " fractions of a percent, because nothing cracks and what little the reflood"
                + " adds is just the last second of ordinary oxidation on the way down. The"
                + " branch is a hinge, not a slope.");
    }

    /**
     * <b>Below the threshold, speed does not matter.</b> Quench a 1400 C core as
     * violently as the model allows and the oxide does not crack: the cracking
     * term is gated on peak temperature as well as cooling rate, so a cold-ish
     * core cannot be shocked into a spike.
     */
    public static void test05_belowTheThresholdEvenAViolentQuenchCannotCrackTheOxide() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.heatUntilCladReaches(fuel, 1400.0,
                AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);

        double thicknessBefore = fuel.getProtectiveOxideThicknessMicrons();
        AccidentScenario.reflood(fuel, 2000.0);
        double minimumThickness = thicknessBefore;
        double fastestCooling = 0.0;
        for (int i = 0; i < 4000; i++) {
            double before = fuel.getCladTemperatureC();
            AccidentScenario.tickWired(fuel, AccidentScenario.CONSTANT_DECAY_FRACTION);
            fastestCooling = Math.max(fastestCooling,
                    (before - fuel.getCladTemperatureC()) / AccidentScenario.TICK);
            minimumThickness = Math.min(minimumThickness, fuel.getProtectiveOxideThicknessMicrons());
        }
        Check.note("1400 C core hit with 2000 kg/s of spray: cooled at up to %.0f degC/s"
                        + " (cracking onset is %.0f degC/s) and the protective oxide went"
                        + " %.2f -> %.2f um", fastestCooling,
                FuelThermal.QUENCH_CRACKING_ONSET_C_PER_S, thicknessBefore, minimumThickness);
        Check.greaterThan(FuelThermal.QUENCH_CRACKING_ONSET_C_PER_S * 10.0, fastestCooling,
                "the cooldown really was violent, degC/s");
        Check.greaterThan(thicknessBefore * 0.999, minimumThickness,
                "protective oxide thickness after a violent sub-threshold quench, microns");
        Check.note("the gate is peak clad temperature, %.0f C. Below it the layer is thin and"
                        + " ductile enough to follow the cooldown; above it, it is not.",
                PhysicalConstants.QUENCH_SPIKE_THRESHOLD_C);
    }

    /**
     * <b>Reflood is always still the right action.</b> SPEC 8.2 asserts it; this
     * measures it, and the measurement is more interesting than the assertion.
     *
     * <h3>Over the whole accident, reflood never costs more hydrogen</h3>
     * Run the accident out to four hours with and without a reflood at each of
     * several temperatures. Doing nothing oxidises the entire zirconium inventory
     * and leaves the cladding at eight thousand degrees. Every reflood, however
     * late, ends at or below that hydrogen and every one of them ends with the
     * core at saturation temperature. There is no temperature at which waiting is
     * better.
     *
     * <h3>Over a short window, a very late reflood really can cost more</h3>
     * And this is the part worth knowing. A dry core is <b>reactant starved</b>:
     * with only a couple of kilos a second of steam reaching it, its oxidation is
     * capped no matter how hot it gets. A flood removes that cap — five hundred
     * kilos a second of spray flashes to five hundred kilos a second of reactant.
     * So in the first fifteen minutes, flooding a core above roughly 2200 C makes
     * <i>more</i> hydrogen than leaving it alone.
     *
     * <p>That is not an argument for leaving it alone. The unflooded core is still
     * heating, still oxidising, and heading for the same total by a slower road
     * with a molten core at the end of it. But it is why "reflood is not free" is
     * a real statement and not a decoration, and it is measured here rather than
     * asserted away.
     */
    public static void test06_refloodIsNeverWorseThanDoingNothingOverTheWholeAccident() {
        final double horizonSeconds = 4.0 * 3600.0;
        FuelThermal nothing = AccidentScenario.ratedCore();
        AccidentScenario.uncover(nothing, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.runWired(nothing, horizonSeconds, AccidentScenario.CONSTANT_DECAY_FRACTION);
        double doNothingHydrogen = nothing.getHydrogenGeneratedKg();
        Check.note("no reflood at all, %.0f h after uncovery: %.1f kg of hydrogen, %.1f%% of the"
                        + " zirconium gone, cladding at %.0f C", horizonSeconds / 3600.0,
                doNothingHydrogen, 100.0 * nothing.getOxidationFraction(),
                nothing.getCladTemperatureC());

        for (double target : new double[]{1400, 1800, 2400, 3000}) {
            FuelThermal fuel = AccidentScenario.ratedCore();
            AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
            boolean flooded = false;
            int steps = (int) Math.round(horizonSeconds / AccidentScenario.TICK);
            double refloodedAt = Double.NaN;
            for (int i = 0; i < steps; i++) {
                if (!flooded && fuel.getCladTemperatureC() >= target) {
                    AccidentScenario.reflood(fuel, SPRAY_KG_PER_S);
                    flooded = true;
                    refloodedAt = i * AccidentScenario.TICK;
                }
                AccidentScenario.tickWired(fuel, AccidentScenario.CONSTANT_DECAY_FRACTION);
            }
            Check.isTrue(flooded, "the core never reached %.0f C inside the horizon", target);
            Check.note("reflood at %4.0f C (t = %5.0f s): %7.1f kg of hydrogen by the four hour"
                            + " mark (%.0f%% of doing nothing), %.1f%% oxidised, cladding at"
                            + " %.0f C", target, refloodedAt, fuel.getHydrogenGeneratedKg(),
                    100.0 * fuel.getHydrogenGeneratedKg() / doNothingHydrogen,
                    100.0 * fuel.getOxidationFraction(), fuel.getCladTemperatureC());
            Check.lessThan(doNothingHydrogen * (1.0 + 1.0e-9), fuel.getHydrogenGeneratedKg(),
                    "total hydrogen with a reflood at " + target + " C versus none, kg");
            Check.lessThan(nothing.getCladTemperatureC(), fuel.getCladTemperatureC(),
                    "cladding temperature after a reflood at " + target + " C versus none, degC");
            Check.absolute(fuel.getCoolantTemperatureC(), fuel.getCladTemperatureC(), 5.0,
                    "a reflood at " + target + " C still ends with the core at saturation, degC");
        }

        // The short-window exception, measured rather than hidden.
        double crossoverC = Double.NaN;
        for (double target : new double[]{1400, 1800, 2000, 2200, 2400}) {
            FuelThermal idle = AccidentScenario.ratedCore();
            AccidentScenario.uncover(idle, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
            AccidentScenario.heatUntilCladReaches(idle, target,
                    AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);
            double baseline = idle.getHydrogenGeneratedKg();
            AccidentScenario.runWired(idle, OBSERVE_S, AccidentScenario.CONSTANT_DECAY_FRACTION);
            double idleHydrogen = idle.getHydrogenGeneratedKg() - baseline;

            AccidentScenario.RefloodOutcome flooded =
                    AccidentScenario.refloodAt(target, SPRAY_KG_PER_S, OBSERVE_S);
            double ratio = flooded.refloodHydrogenKg() / idleHydrogen;
            if (Double.isNaN(crossoverC) && ratio > 1.0) {
                crossoverC = target;
            }
            Check.note("first %.0f s from %4.0f C: do nothing +%7.1f kg H2 and end at %6.0f C;"
                            + " reflood +%7.1f kg and end at %.0f C — reflood costs %.0f%% of"
                            + " inaction over that window", OBSERVE_S, target, idleHydrogen,
                    idle.getCladTemperatureC(), flooded.refloodHydrogenKg(), flooded.finalCladC,
                    100.0 * ratio);
            Check.lessThan(idle.getCladTemperatureC(), flooded.finalCladC,
                    "reflood always wins on temperature, from " + target + " C");
        }
        Check.finite(crossoverC, "temperature above which a reflood out-produces inaction over"
                + " the first fifteen minutes");
        Check.note("SHORT-WINDOW CROSSOVER at about %.0f C. Above that, the first quarter hour"
                + " after a reflood makes more hydrogen than the first quarter hour of inaction,"
                + " because inaction leaves the reaction starved of steam and the flood does not."
                + " The four-hour totals above are the answer to whether that matters: it does"
                + " not. But a player watching a hydrogen gauge during a late reflood will see it"
                + " climb, and that is correct, not a bug.", crossoverC);
        Check.inRange(1800.0, 2600.0, crossoverC, "short-window crossover temperature, degC");
    }

    /**
     * <b>The spike is driven by the cooling <i>rate</i>, so a gentler flood is a
     * gentler spike.</b> Ramp the covered fraction from zero to one over one
     * second, ten seconds, a minute, five minutes. The slower the quench front
     * sweeps the fuel, the less oxide is shattered and the less hydrogen comes
     * out — which is the operational advice the QUENCH programme produced, and it
     * falls out of the rate-gated cracking term rather than being written down.
     */
    public static void test07_aSlowerQuenchIsAGentlerQuench() {
        double previousHydrogen = Double.MAX_VALUE;
        for (double rampSeconds : new double[]{1.0, 10.0, 60.0, 300.0}) {
            FuelThermal fuel = AccidentScenario.ratedCore();
            AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
            AccidentScenario.heatUntilCladReaches(fuel, LATE_C,
                    AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);
            double hydrogenBefore = fuel.getHydrogenGeneratedKg();
            double thicknessBefore = fuel.getProtectiveOxideThicknessMicrons();

            fuel.setCoreSprayFlowKgPerS(300.0);
            double minimumThickness = thicknessBefore;
            int steps = (int) Math.round(1200.0 / AccidentScenario.TICK);
            for (int i = 0; i < steps; i++) {
                fuel.setCoveredFuelFraction(Math.min(1.0, i * AccidentScenario.TICK / rampSeconds));
                AccidentScenario.tickWired(fuel, AccidentScenario.CONSTANT_DECAY_FRACTION);
                minimumThickness =
                        Math.min(minimumThickness, fuel.getProtectiveOxideThicknessMicrons());
            }
            double produced = fuel.getHydrogenGeneratedKg() - hydrogenBefore;
            Check.note("reflood from %.0f C with the level restored over %5.0f s: +%7.2f kg H2,"
                            + " oxide %6.1f -> %5.1f um, clad ends at %.0f C", LATE_C, rampSeconds,
                    produced, thicknessBefore, minimumThickness, fuel.getCladTemperatureC());
            Check.lessThan(previousHydrogen, produced,
                    "a slower reflood must make less hydrogen than a faster one (ramp "
                            + rampSeconds + " s)");
            Check.absolute(fuel.getCoolantTemperatureC(), fuel.getCladTemperatureC(), 5.0,
                    "the core is still cooled at the end of a slow reflood, degC");
            previousHydrogen = produced;
        }
        Check.note("slower is gentler — but every one of these floods ended with the core cooled,"
                + " so the choice is between degrees of cost, not between acting and not.");
    }

    /**
     * <b>Steam starvation, and why a dry core is a loaded spring.</b> The
     * reaction consumes steam, and a core with almost none left oxidises far
     * below the rate its temperature would allow. That deficit is stored: the
     * moment water arrives the reaction resumes at the full kinetic rate.
     *
     * <p>This is the second half of the quench spike mechanism, and it is why
     * {@code setSteamSupplyKgPerS} must be wired to real boiloff rather than left
     * at the rated-flow default.
     */
    public static void test08_steamStarvationStoresTheReactionUntilWaterArrives() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, 1.0);
        AccidentScenario.heatUntilCladReaches(fuel, 1800.0,
                AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);

        double kinetic = fuel.getKineticReactionRateKgPerS();
        double actual = fuel.getZirconiumReactionRateKgPerS();
        Check.note("dry core at %.0f C with 1 kg/s of steam: the temperature alone would give"
                        + " %.2f kg Zr/s, the reactant allows %.3f — starved by %.0fx."
                        + " Steam consumption is %.4f kg/s against a supply of %.1f.",
                fuel.getCladTemperatureC(), kinetic, actual, kinetic / actual,
                fuel.getSteamConsumptionKgPerS(), fuel.getSteamSupplyKgPerS());
        Check.greaterThan(5.0, kinetic / actual, "starvation factor on a dry core");
        Check.absolute(fuel.getSteamSupplyKgPerS(), fuel.getSteamConsumptionKgPerS(), 1.0e-9,
                "a starved reaction consumes exactly the steam it is given, kg/s");

        double before = fuel.getHydrogenGeneratedKg();
        fuel.setSteamSupplyKgPerS(400.0);
        for (int i = 0; i < 20; i++) {
            fuel.step(0.0, AccidentScenario.CONSTANT_DECAY_FRACTION, AccidentScenario.PSIG,
                    AccidentScenario.TICK);
            fuel.setSteamSupplyKgPerS(400.0);
        }
        Check.note("one second after the steam surge arrives: reaction %.2f kg Zr/s, %+.2f kg of"
                        + " hydrogen in that one second, clad now %.0f C",
                fuel.getZirconiumReactionRateKgPerS(), fuel.getHydrogenGeneratedKg() - before,
                fuel.getCladTemperatureC());
        Check.greaterThan(actual * 5.0, fuel.getZirconiumReactionRateKgPerS(),
                "reaction rate one second after the reactant arrives, kg/s");
        Check.greaterThan(0.0, fuel.getHydrogenGeneratedKg() - before,
                "hydrogen produced in the first second after water reaches a starved core, kg");
    }

    /**
     * <b>The very late branch does not exist here.</b> SPEC 8.2's third case is
     * "post-relocation: you are cooling corium, not fuel". Nothing in
     * {@code core/} models relocation or corium, so a reflood after fuel melt is
     * handled by the same two-node fuel model as any other reflood.
     *
     * <p>This test does not assert that this is correct. It asserts that the code
     * stays finite and keeps its damage bookkeeping honest in a regime it was
     * never written for, and records the gap in the transcript so it is not
     * discovered later by a player.
     */
    public static void test09_theVeryLateBranchIsNotModelled() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.heatUntilCladReaches(fuel, PhysicalConstants.FUEL_MELT_C + 200.0,
                AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);
        Check.greaterThan(1.0, fuel.getPeakFuelTemperatureFractionOfMelt(),
                "the core really is past the UO2 melting point");

        double hydrogenBefore = fuel.getHydrogenGeneratedKg();
        double oxidationBefore = fuel.getOxidationFraction();
        double peakBefore = fuel.getPeakCladTemperatureC();
        AccidentScenario.reflood(fuel, SPRAY_KG_PER_S);
        AccidentScenario.runWired(fuel, OBSERVE_S, AccidentScenario.CONSTANT_DECAY_FRACTION);

        Check.note("reflood of a core %.0f C past melt: +%.1f kg H2, oxidation %.1f%% -> %.1f%%,"
                        + " clad settles at %.1f C", fuel.getPeakFuelTemperatureC()
                        - PhysicalConstants.FUEL_MELT_C,
                fuel.getHydrogenGeneratedKg() - hydrogenBefore, 100.0 * oxidationBefore,
                100.0 * fuel.getOxidationFraction(), fuel.getCladTemperatureC());
        Check.finite(fuel.getCladTemperatureC(), "clad temperature after a post-melt reflood");
        Check.finite(fuel.getHydrogenGeneratedKg(), "hydrogen after a post-melt reflood");
        Check.greaterThan(oxidationBefore - 1.0e-12, fuel.getOxidationFraction(),
                "oxidation fraction stays monotonic through a post-melt reflood");
        Check.exactly(peakBefore, fuel.getPeakCladTemperatureC(),
                "an adequate post-melt reflood still does not raise the peak clad temperature");
        Check.note("GAP: SPEC 8.2's 'very late (post-relocation)' branch is not implemented."
                + " There is no corium, no relocation and no debris bed in core/, so this reflood"
                + " cools intact fuel rods that in reality would no longer be rods.");
    }
}
