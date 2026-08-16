package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.DecayHeat;
import dev.bwr.core.thermal.FuelThermal;

/**
 * The escalation chain of {@code SPEC.md} section 8.1, stage by stage.
 *
 * <pre>
 *   1  core uncovery        level below top of active fuel, steam cooling only   recoverable
 *   2  cladding heatup      fuel temperature climbing on decay heat              recoverable
 *   3  Zr-water reaction    above ~1200 C, exothermic, autocatalytic, makes H2   conditional
 *   4  cladding failure     gap activity release                                 permanent
 *   5  fuel melt            ~2800 C UO2, relocation, corium                      no
 *   6  RPV lower head failure                                                    no
 *   7  molten core-concrete interaction                                          no
 * </pre>
 *
 * <h2>What is under test and what is not</h2>
 * {@code FuelThermal} models stages 1 through 3 mechanistically and reports the
 * temperature that defines stage 5. It does <b>not</b> model stages 4, 6 or 7 —
 * there is no gap release, no relocation, no corium, no lower head and no
 * basemat anywhere in {@code core/}. These tests therefore prove the chain as
 * far as the code claims to go and say so explicitly where it stops, rather
 * than asserting the absence of code nobody wrote.
 *
 * <h2>Stage 3 is the whole point</h2>
 * {@link #test04_stage3TheReactionAcceleratesItselfAtConstantDecayHeat} is the
 * important test in this file. It holds the heat source rigidly constant and
 * shows the temperature rise rate climbing anyway, from 0.48 degC/s to nearly
 * 15 — a factor of thirty-one, with not one watt of extra decay heat. That
 * self-acceleration is why a severe accident has a cliff instead of a slope,
 * and it is the physics behind Fukushima.
 *
 * <p>Every scenario here runs with <b>zero fission power</b>. The rods are in
 * and they stay in. Nothing that happens below is stopped by a scram.
 */
public final class SevereAccidentEscalationTest {

    private SevereAccidentEscalationTest() {
    }

    /**
     * <b>Stage 1.</b> Uncovery is a cliff in the heat sink, not a gentle
     * degradation, and the two terms in the split conductance move against each
     * other: less water means more uncovered fuel <i>and</i> less steam to cool
     * it.
     *
     * <p>Also records the honest limitation of a two-node lumped model — see the
     * partial-uncovery sweep at the end. Fractional uncovery barely moves the
     * average because the submerged part of a whole-core node still carries an
     * enormous conductance. Real hot spots uncover before the average does; this
     * model cannot see them, and a test that pretended otherwise would be lying.
     */
    public static void test01_stage1UncoveryCollapsesTheHeatSink() {
        FuelThermal covered = AccidentScenario.ratedCore();
        double coveredConductance = covered.getCladToCoolantConductanceMWPerC();
        Check.note("covered at rated: coolant %.1f C, clad %.1f C, fuel %.1f C, U_cc %.1f MW/degC",
                covered.getCoolantTemperatureC(), covered.getCladTemperatureC(),
                covered.getFuelTemperatureC(), coveredConductance);
        Check.absolute(12.0, covered.getCladTemperatureC() - covered.getCoolantTemperatureC(), 3.0,
                "clad superheat at rated with the fuel covered, degC");

        FuelThermal uncovered = AccidentScenario.ratedCore();
        AccidentScenario.uncover(uncovered, 2.0);
        AccidentScenario.tickWired(uncovered, AccidentScenario.CONSTANT_DECAY_FRACTION);
        double dryConductance = uncovered.getCladToCoolantConductanceMWPerC();
        Check.note("uncovered with 2 kg/s of steam: U_cc %.5f MW/degC — %.0fx worse",
                dryConductance, coveredConductance / dryConductance);
        Check.greaterThan(1000.0, coveredConductance / dryConductance,
                "collapse in clad-to-coolant conductance on uncovery");

        // The two terms move against each other: halve the steam and the only
        // remaining sink halves with it.
        FuelThermal starved = AccidentScenario.ratedCore();
        AccidentScenario.uncover(starved, 1.0);
        AccidentScenario.tickWired(starved, AccidentScenario.CONSTANT_DECAY_FRACTION);
        Check.relative(0.5, starved.getCladToCoolantConductanceMWPerC() / dryConductance, 1.0e-9,
                "steam cooling is linear in steam flow, so less water is less cooling");

        // Honest limitation of a lumped model, recorded rather than asserted away.
        StringBuilder sweep = new StringBuilder();
        double previous = Double.NEGATIVE_INFINITY;
        for (double fraction : new double[]{1.0, 0.9, 0.5, 0.2, 0.05, 0.0}) {
            FuelThermal fuel = AccidentScenario.ratedCore();
            fuel.setCoveredFuelFraction(fraction);
            fuel.setSteamCoolingFlowKgPerS(40.0);
            fuel.setSteamSupplyKgPerS(40.0);
            AccidentScenario.runWired(fuel, 600.0, AccidentScenario.CONSTANT_DECAY_FRACTION);
            sweep.append(String.format(" %.2f->%.0fC", fraction, fuel.getCladTemperatureC()));
            Check.isTrue(fuel.getCladTemperatureC() >= previous - 1.0e-9,
                    "clad temperature must not fall as the fuel is uncovered further"
                            + " (covered %.2f gave %.1f C, previous %.1f C)",
                    fraction, fuel.getCladTemperatureC(), previous);
            previous = fuel.getCladTemperatureC();
        }
        Check.note("clad after 600 s versus covered fraction:%s", sweep);
        Check.note("LIMITATION: two lumped nodes average the wet and dry parts of the core, so"
                + " partial uncovery barely moves the average. The transition is at the last few"
                + " percent of level. Real cores uncover hot channels first; this model cannot"
                + " represent that and the accident here is a whole-core one.");
    }

    /**
     * <b>Stage 2.</b> A station blackout with no fission at all. The rods are in
     * from the first tick and never come out; the fission product inventory does
     * not care. Decay heat alone walks the cladding from 300 C to the reaction
     * onset in about twenty minutes.
     */
    public static void test02_stage2DecayHeatAloneWalksTheCladToOnset() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        DecayHeat decay = DecayHeat.saturatedAt(1.0);
        AccidentScenario.uncover(fuel, 5.0);

        Check.note("decay heat at the moment of shutdown: %.3f%% of rated = %.1f MW",
                100.0 * decay.getFractionOfRated(), decay.getThermalMW(PhysicalConstants.RATED_THERMAL_MW));

        double secondsToOnset = Double.NaN;
        double decayAtOnset = Double.NaN;
        double t = 0.0;
        for (int i = 0; i < 20 * 3600 * 2; i++) {
            double fraction = decay.step(0.0, AccidentScenario.TICK);
            fuel.step(0.0, fraction, AccidentScenario.PSIG, AccidentScenario.TICK);
            fuel.setSteamSupplyKgPerS(5.0);
            t += AccidentScenario.TICK;
            if (Double.isNaN(secondsToOnset)
                    && fuel.getCladTemperatureC() >= PhysicalConstants.ZR_REACTION_ONSET_C) {
                secondsToOnset = t;
                decayAtOnset = fraction;
                break;
            }
        }
        Check.finite(secondsToOnset, "time to reach the Zr reaction onset on decay heat alone");
        Check.note("clad reached %.0f C at t = %.0f s (%.1f min) with fission power held at zero"
                        + " the whole way; decay heat was still %.2f%% = %.1f MW",
                PhysicalConstants.ZR_REACTION_ONSET_C, secondsToOnset, secondsToOnset / 60.0,
                100.0 * decayAtOnset, decayAtOnset * PhysicalConstants.RATED_THERMAL_MW);
        Check.inRange(600.0, 3600.0, secondsToOnset,
                "minutes from uncovery to reaction onset on decay heat alone, seconds");
        Check.greaterThan(0.01, decayAtOnset, "decay heat fraction still running at onset");
        Check.lessThan(1.0, fuel.getOxidationFraction() * 100.0,
                "oxidation is still negligible at the moment of onset, percent");
    }

    /**
     * <b>Stage 3, the onset.</b> Nothing in the code declares 1200 C to be a
     * threshold. It falls out of the Baker-Just rate law evaluated on the oxide
     * film a normally-operated core already carries, and this test measures the
     * curve rather than taking the constant's word for it.
     *
     * <p>The Arrhenius factor should roughly triple per 100 C, the reaction
     * should be negligible at 1000 C, comparable to decay heat at 1200 C, and an
     * order of magnitude past anything decay heat can do by 1800 C.
     */
    public static void test03_stage3TheOnsetIsEmergentFromTheRateLaw() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        double[] temperatures = {1000, 1100, 1200, 1300, 1400, 1500, 1600, 1800, 2000};
        double[] powerMW = new double[temperatures.length];
        for (int i = 0; i < temperatures.length; i++) {
            powerMW[i] = fuel.kineticReactionRateKgPerS(temperatures[i])
                    * FuelThermal.ZR_HEAT_OF_REACTION_MJ_PER_KG;
            Check.note("%5.0f C: %8.3f kg Zr/s, %9.1f MW", temperatures[i],
                    fuel.kineticReactionRateKgPerS(temperatures[i]), powerMW[i]);
        }

        Check.lessThan(5.0, powerMW[0], "reaction power at 1000 C — must be negligible, MW");
        Check.inRange(15.0, 45.0, powerMW[2],
                "reaction power at the 1200 C onset — must be comparable to hour-old decay heat, MW");
        Check.greaterThan(1000.0, powerMW[7],
                "reaction power at 1800 C — must dwarf decay heat, MW");

        double worstRatio = Double.MAX_VALUE;
        double bestRatio = 0.0;
        for (int i = 1; i <= 5; i++) {
            double ratio = powerMW[i] / powerMW[i - 1];
            worstRatio = Math.min(worstRatio, ratio);
            bestRatio = Math.max(bestRatio, ratio);
        }
        Check.note("Arrhenius factor per 100 degC across 1000-1500 C: %.2fx at the top of the"
                        + " band down to %.2fx at the bottom ('roughly triples per 100 degC')."
                        + " It shrinks as temperature rises because exp(-B/T) flattens — that is"
                        + " an exponential in 1/T, not in T.", bestRatio, worstRatio);
        Check.inRange(2.0, 4.0, worstRatio, "smallest per-100-degC amplification");
        Check.inRange(2.0, 4.0, bestRatio, "largest per-100-degC amplification");

        // The onset constant is documentation of the curve, not an input to it.
        double onsetPower = fuel.kineticReactionRateKgPerS(PhysicalConstants.ZR_REACTION_ONSET_C)
                * FuelThermal.ZR_HEAT_OF_REACTION_MJ_PER_KG;
        double hourOldDecayMW = 0.0135 * PhysicalConstants.RATED_THERMAL_MW;
        Check.note("at the declared onset of %.0f C the reaction makes %.1f MW; decay heat an hour"
                        + " after shutdown is %.1f MW — the constant describes the curve, it does"
                        + " not create it", PhysicalConstants.ZR_REACTION_ONSET_C, onsetPower,
                hourOldDecayMW);
        Check.inRange(0.3, 3.0, onsetPower / hourOldDecayMW,
                "reaction power at the declared onset, as a multiple of hour-old decay heat");
    }

    /**
     * <b>Stage 3, the cliff. The most important test in this file.</b>
     *
     * <p>Decay heat is pinned at a bit-identical 2% of rated for the entire run,
     * fission power is zero, the coolant sink never changes and the steam supply
     * never changes. The <i>only</i> thing that varies is the cladding
     * temperature and what the zirconium does at it.
     *
     * <p>The rise rate must therefore do something a heat balance with a fixed
     * source cannot do on its own: it must <b>increase</b>. Before onset the rate
     * decays slowly, because the sink term grows with temperature. After onset it
     * turns around and climbs, and the second derivative stays positive all the
     * way to melt.
     *
     * <p>The reactant supply is deliberately left ample here. Steam starvation is
     * the <i>other</i> limit on the reaction and it is tested on its own in
     * {@code QuenchSpikeRefloodTest}; mixing the two would leave it ambiguous
     * which feedback the ramp came from.
     */
    public static void test04_stage3TheReactionAcceleratesItselfAtConstantDecayHeat() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, 2.0);
        AccidentScenario.giveAmpleSteamSupply(fuel);
        final double decay = AccidentScenario.CONSTANT_DECAY_FRACTION;
        final double decayMW = decay * PhysicalConstants.RATED_THERMAL_MW;

        double slowestRate = Double.MAX_VALUE;
        double slowestAtC = Double.NaN;
        double fastestRate = 0.0;
        double fastestAtC = Double.NaN;
        double reactionOvertakesDecayAtC = Double.NaN;
        double lastRate = Double.NaN;
        double previousRate = Double.NaN;
        int accelerating = 0;
        int decelerating = 0;
        double t = 0.0;

        for (int i = 0; i < 20 * 3600; i++) {
            double before = fuel.getCladTemperatureC();
            AccidentScenario.tick(fuel, decay);
            t += AccidentScenario.TICK;
            double rate = (fuel.getCladTemperatureC() - before) / AccidentScenario.TICK;

            // Skip the first few seconds: the core starts from a rated steady
            // state and the sink disappearing is a step change, not the physics
            // under test.
            if (t < 30.0) {
                continue;
            }
            if (rate < slowestRate) {
                slowestRate = rate;
                slowestAtC = fuel.getCladTemperatureC();
            }
            if (rate > fastestRate) {
                fastestRate = rate;
                fastestAtC = fuel.getCladTemperatureC();
            }
            if (Double.isNaN(reactionOvertakesDecayAtC)
                    && fuel.getZirconiumReactionPowerMW() > decayMW) {
                reactionOvertakesDecayAtC = fuel.getCladTemperatureC();
                Check.note("the reaction overtakes decay heat at %.0f C, t = %.0f s:"
                                + " Zr %.1f MW versus decay %.1f MW",
                        reactionOvertakesDecayAtC, t, fuel.getZirconiumReactionPowerMW(), decayMW);
            }
            // Once past onset the rise rate must never turn back down.
            if (fuel.getCladTemperatureC() > PhysicalConstants.ZR_REACTION_ONSET_C
                    && !Double.isNaN(previousRate)) {
                if (rate > previousRate) {
                    accelerating++;
                } else {
                    decelerating++;
                }
            }
            previousRate = rate;
            lastRate = rate;
            if (fuel.getFuelTemperatureC() > PhysicalConstants.FUEL_MELT_C) {
                break;
            }
        }

        Check.note("decay heat pinned at %.1f MW and fission at zero for the whole run", decayMW);
        Check.note("slowest clad rise %.4f degC/s at %.0f C (pre-onset, sink still growing)",
                slowestRate, slowestAtC);
        Check.note("fastest clad rise %.4f degC/s at %.0f C — %.1fx the slowest, with the heat"
                        + " source unchanged", fastestRate, fastestAtC, fastestRate / slowestRate);
        Check.note("past onset: %d accelerating ticks, %d decelerating — %.2f%% accelerating",
                accelerating, decelerating,
                100.0 * accelerating / Math.max(1, accelerating + decelerating));
        Check.note("melt reached at t = %.0f s, final rise rate %.3f degC/s, H2 %.0f kg,"
                        + " oxidation %.1f%%", t, lastRate, fuel.getHydrogenGeneratedKg(),
                100.0 * fuel.getOxidationFraction());

        Check.greaterThan(5.0, fastestRate / slowestRate,
                "self-acceleration: fastest heatup rate as a multiple of the slowest,"
                        + " with the heat source held constant");
        Check.greaterThan(slowestAtC, fastestAtC,
                "the fastest heatup must happen at a higher temperature than the slowest");
        Check.finite(reactionOvertakesDecayAtC,
                "temperature at which the reaction outproduces decay heat");
        Check.lessThan(1800.0, reactionOvertakesDecayAtC,
                "the reaction must overtake decay heat below 1800 C");
        Check.lessThan(0.5, 100.0 * decelerating / Math.max(1, accelerating + decelerating),
                "percentage of post-onset ticks where the rise rate fell — the ramp must be"
                        + " monotonically accelerating");
        Check.greaterThan(PhysicalConstants.FUEL_MELT_C, fuel.getPeakFuelTemperatureC(),
                "peak fuel temperature at the end of an unmitigated ramp, degC");
    }

    /**
     * <b>Stage 3 is conditional, and this is the other branch.</b> Enough steam
     * cooling and the core finds a thermal equilibrium below the reaction onset
     * and simply sits there. Nothing rescued it and nothing declared it safe: the
     * heat balance closed.
     */
    public static void test05_adequateCoolingStopsTheChainBeforeStage3() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        DecayHeat decay = DecayHeat.saturatedAt(1.0);
        AccidentScenario.uncover(fuel, 40.0);

        double peakRate = 0.0;
        for (int i = 0; i < 20 * 3600 * 3; i++) {
            double fraction = decay.step(0.0, AccidentScenario.TICK);
            fuel.step(0.0, fraction, AccidentScenario.PSIG, AccidentScenario.TICK);
            fuel.setSteamSupplyKgPerS(40.0);
            peakRate = Math.max(peakRate, fuel.getHydrogenGenerationRateKgPerS());
        }
        Check.note("three hours uncovered with 40 kg/s of steam cooling: peak clad %.0f C,"
                        + " clad now %.0f C, decay heat %.2f%%, H2 %.2f kg, oxidation %.4f%%",
                fuel.getPeakCladTemperatureC(), fuel.getCladTemperatureC(),
                100.0 * decay.getFractionOfRated(), fuel.getHydrogenGeneratedKg(),
                100.0 * fuel.getOxidationFraction());
        Check.lessThan(PhysicalConstants.ZR_REACTION_ONSET_C, fuel.getPeakCladTemperatureC(),
                "peak clad temperature with adequate steam cooling, degC");
        Check.lessThan(0.01, fuel.getOxidationFraction(),
                "oxidation fraction after three hours of adequately cooled uncovery");
        Check.lessThan(0.005, peakRate, "peak hydrogen generation rate, kg/s");
        Check.lessThan(0.0, fuel.getCladTemperatureC() - fuel.getPeakCladTemperatureC(),
                "the core is past its peak and cooling as decay heat falls, degC");
        Check.note("the core is stable but not saved: it is still uncovered, still making %.0f MW"
                        + " and entirely dependent on that steam continuing to arrive",
                decay.getFractionOfRated() * PhysicalConstants.RATED_THERMAL_MW);
        Check.note("and 'below onset' is not 'zero': %.1f kg of hydrogen came out of three hours"
                        + " at a peak of %.0f C, at up to %.4f kg/s. The onset is where the curve"
                        + " turns, not where it starts.",
                fuel.getHydrogenGeneratedKg(), fuel.getPeakCladTemperatureC(), peakRate);
    }

    /**
     * <b>Stage 5, and where the model stops.</b> An unmitigated ramp passes the
     * UO2 melting point, and the melt fraction readout crosses one. Beyond that
     * point {@code core/} has nothing: no relocation, no corium, no lower head,
     * no basemat. The model keeps integrating a two-node core that no longer
     * exists, so the temperatures it reports past melt are not physical.
     *
     * <p>This test asserts the crossing and records the gap, because a silent
     * gap in a severe accident model is worse than a documented one.
     */
    public static void test06_stage5FuelMeltIsReachedAndReportedThenTheModelStops() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        DecayHeat decay = DecayHeat.saturatedAt(1.0);
        AccidentScenario.uncover(fuel, 5.0);

        double secondsToMelt = Double.NaN;
        double t = 0.0;
        for (int i = 0; i < 20 * 3600 * 2; i++) {
            double fraction = decay.step(0.0, AccidentScenario.TICK);
            fuel.step(0.0, fraction, AccidentScenario.PSIG, AccidentScenario.TICK);
            fuel.setSteamSupplyKgPerS(5.0);
            t += AccidentScenario.TICK;
            if (fuel.getPeakFuelTemperatureFractionOfMelt() >= 1.0) {
                secondsToMelt = t;
                break;
            }
        }
        Check.finite(secondsToMelt, "time to fuel melt in an unmitigated station blackout");
        Check.note("fuel reached %.0f C (%.0f%% of the %.0f C UO2 melting point) at t = %.0f s"
                        + " (%.0f min) with zero fission power throughout",
                fuel.getPeakFuelTemperatureC(),
                100.0 * fuel.getPeakFuelTemperatureFractionOfMelt(),
                PhysicalConstants.FUEL_MELT_C, secondsToMelt, secondsToMelt / 60.0);
        Check.note("at melt: peak clad %.0f C, oxidation %.1f%%, hydrogen %.0f kg"
                        + " (TMI-2 reached roughly a third oxidised)",
                fuel.getPeakCladTemperatureC(), 100.0 * fuel.getOxidationFraction(),
                fuel.getHydrogenGeneratedKg());
        Check.inRange(1200.0, 7200.0, secondsToMelt, "seconds from uncovery to fuel melt");
        Check.greaterThan(1.0, fuel.getPeakFuelTemperatureFractionOfMelt(),
                "peak fuel temperature as a fraction of the melting point");
        Check.greaterThan(0.2, fuel.getOxidationFraction(), "oxidation fraction at fuel melt");

        // Continue past melt to show what the model does — and does not — do.
        AccidentScenario.runWired(fuel, 1800.0, decay.getFractionOfRated());
        Check.note("half an hour past melt the two-node model is still integrating and reports"
                + " clad %.0f C. There is no relocation, no corium and no lower head in core/,"
                + " so this number is bookkeeping, not physics.", fuel.getCladTemperatureC());
        Check.note("NOT MODELLED anywhere in core/: stage 4 cladding failure and gap activity"
                + " release, stage 6 RPV lower head failure, stage 7 molten core-concrete"
                + " interaction. SPEC 8.1 lists them; no class implements them.");
        Check.finite(fuel.getCladTemperatureC(), "clad temperature stays finite past melt");
        Check.finite(fuel.getHydrogenGeneratedKg(), "hydrogen stays finite past melt");
    }

    /**
     * <b>Decay heat has no off switch, and a scram does not touch it.</b>
     *
     * <p>The rods go in on the first tick of this test and fission power is
     * exactly zero for the entire run. The core melts anyway. There is no method
     * on {@link DecayHeat} that zeroes the inventory except
     * {@code clearInventory}, which is documented as defuelling and is a
     * statement about what is physically in the fuel rather than a control
     * action.
     */
    public static void test07_scramDoesNotStopDecayHeatAndDecayHeatHasNoOffSwitch() {
        DecayHeat decay = DecayHeat.saturatedAt(1.0);
        double atShutdown = decay.getFractionOfRated();
        Check.note("the instant the rods hit bottom: %.3f%% of rated = %.0f MW",
                100.0 * atShutdown, decay.getThermalMW(PhysicalConstants.RATED_THERMAL_MW));

        double[] marks = {1.0, 10.0, 100.0, 3600.0, 86400.0};
        double elapsed = 0.0;
        int mark = 0;
        for (int i = 0; i < 20 * 86400 && mark < marks.length; i++) {
            decay.step(0.0, AccidentScenario.TICK);
            elapsed += AccidentScenario.TICK;
            while (mark < marks.length && elapsed >= marks[mark]) {
                Check.note("%8.0f s after scram with fission at zero: %.4f%% = %.1f MW",
                        marks[mark], 100.0 * decay.getFractionOfRated(),
                        decay.getThermalMW(PhysicalConstants.RATED_THERMAL_MW));
                Check.finiteAndPositive(decay.getFractionOfRated(),
                        "decay heat " + marks[mark] + " s after scram");
                mark++;
            }
        }
        Check.greaterThan(0.005, decay.getFractionOfRated(),
                "decay heat a day after scram, fraction of rated — a scram cannot reach this");
        Check.greaterThan(0.0, decay.projectedFractionOfRated(365.0 * 86400.0),
                "decay heat projected a year out, fraction of rated");

        // And it is not just a number on a gauge: it melts the core by itself.
        FuelThermal fuel = AccidentScenario.ratedCore();
        DecayHeat live = DecayHeat.saturatedAt(1.0);
        AccidentScenario.uncover(fuel, 2.0);
        // Hoisted, so that the number handed to the fuel model and the number the
        // test reports as "the fission power supplied" are the same variable.
        // They used to be two separate literal zeroes and the maximum was taken
        // over `Math.max(maximumFissionSeen, 0.0)` — the identity — so the
        // assertion below compared a local against itself and would have passed
        // unchanged if the loop had started feeding rated fission power into the
        // heatup, which is the one thing this test exists to rule out.
        double fissionPowerSupplied = 0.0;
        double maximumFissionSeen = 0.0;
        double secondsToMelt = Double.NaN;
        for (int i = 0; i < 20 * 3600 * 3; i++) {
            double fraction = live.step(fissionPowerSupplied, AccidentScenario.TICK);
            maximumFissionSeen = Math.max(maximumFissionSeen, fissionPowerSupplied);
            fuel.step(fissionPowerSupplied, fraction, AccidentScenario.PSIG, AccidentScenario.TICK);
            fuel.setSteamSupplyKgPerS(2.0);
            if (fuel.getPeakFuelTemperatureFractionOfMelt() >= 1.0) {
                secondsToMelt = i * AccidentScenario.TICK;
                break;
            }
        }
        Check.exactly(0.0, maximumFissionSeen, "fission power supplied at any point in this test");
        Check.finite(secondsToMelt, "time to melt with the rods fully inserted from tick one");
        Check.note("melt at t = %.0f s (%.0f min) after uncovery, with the rods fully inserted"
                + " the entire time", secondsToMelt, secondsToMelt / 60.0);
        Check.greaterThan(1.0, fuel.getPeakFuelTemperatureFractionOfMelt(),
                "a scrammed, uncooled core melts on decay heat alone, fraction of melting point");
        Check.note("a scrammed core with the rods fully inserted from tick one melted anyway:"
                        + " peak fuel %.0f C, %.0f kg of hydrogen. Only sustained cooling helps.",
                fuel.getPeakFuelTemperatureC(), fuel.getHydrogenGeneratedKg());

        // The one door, and it is labelled 'defuelled', not 'off'.
        DecayHeat defuelled = DecayHeat.saturatedAt(1.0);
        defuelled.clearInventory();
        Check.exactly(0.0, defuelled.getFractionOfRated(),
                "clearInventory empties the fission product inventory");
        Check.note("clearInventory() is the only way to zero decay heat. It discards the fission"
                + " product inventory, which is a claim about what is in the fuel — correct for a"
                + " fresh or defuelled core, and not something a control action can do.");
    }
}
