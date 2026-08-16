package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.FuelThermal;

import java.util.Random;

/**
 * The damage state itself: hydrogen, oxidation, peak temperatures and their
 * invariants. {@code SPEC.md} sections 8.2 and 8.3.
 *
 * <p>SPEC 8.2 requires two quantities be tracked separately because they are
 * what the reflood branch turns on, and what the control room needs
 * instrumentation for:
 *
 * <ul>
 *   <li><b>Peak cladding temperature</b> — monotonic, because damage is a record
 *       of what happened, not of what is happening now.</li>
 *   <li><b>Cumulative oxidation fraction</b> — monotonic, and bounded by the
 *       zirconium that is actually in the core.</li>
 * </ul>
 *
 * <p>SPEC 8.3 adds hydrogen, "the star mechanic": it accumulates, it is reported,
 * and above roughly 4% by volume in containment it is explosive. A counter that
 * can go backwards is worse than no counter, so these tests attack the
 * monotonicity directly with adversarial input rather than checking it on a
 * well-behaved transient.
 */
public final class DamageBookkeepingTest {

    private DamageBookkeepingTest() {
    }

    /**
     * <b>Peak temperatures, oxidation and hydrogen must be monotonic under any
     * input at all.</b>
     *
     * <p>Four hundred thousand ticks of deliberately hostile driving: covered
     * fractions outside 0..1, negative flows, spray slammed on and off, pressure
     * swinging by 300 psi, occasional NaN and negative time steps. Nothing in
     * that stream is physical, and none of it is allowed to make the damage
     * record go backwards or produce a non-finite temperature.
     */
    public static void test01_damageStateIsMonotonicUnderAdversarialInput() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        Random random = new Random(20250812L);

        double peak = fuel.getPeakCladTemperatureC();
        double peakFuel = fuel.getPeakFuelTemperatureC();
        double oxidation = 0.0;
        double hydrogen = 0.0;
        double oxideGrown = 0.0;
        int nanSteps = 0;
        int negativeSteps = 0;

        for (int i = 0; i < 400_000; i++) {
            fuel.setCoveredFuelFraction(random.nextBoolean()
                    ? random.nextDouble() : (random.nextBoolean() ? -1.0 : 5.0));
            fuel.setSteamCoolingFlowKgPerS(random.nextDouble() * 100.0 - 20.0);
            fuel.setCoreSprayFlowKgPerS(random.nextBoolean() ? 0.0 : random.nextDouble() * 2000.0);
            fuel.setSteamSupplyKgPerS(random.nextDouble() * 500.0);

            double dt = random.nextBoolean() ? AccidentScenario.TICK : random.nextDouble() * 2.0;
            if (i % 997 == 0) {
                dt = Double.NaN;
                nanSteps++;
            } else if (i % 1013 == 0) {
                dt = -1.0;
                negativeSteps++;
            }
            fuel.step(random.nextBoolean() ? 0.0 : random.nextDouble(),
                    random.nextDouble() * 0.07,
                    AccidentScenario.PSIG + random.nextDouble() * 300.0 - 150.0, dt);

            Check.isTrue(fuel.getPeakCladTemperatureC() >= peak,
                    "peak clad temperature fell at tick %d: %.9f -> %.9f",
                    i, peak, fuel.getPeakCladTemperatureC());
            Check.isTrue(fuel.getPeakFuelTemperatureC() >= peakFuel,
                    "peak fuel temperature fell at tick %d: %.9f -> %.9f",
                    i, peakFuel, fuel.getPeakFuelTemperatureC());
            Check.isTrue(fuel.getOxidationFraction() >= oxidation,
                    "oxidation fraction fell at tick %d: %.12f -> %.12f",
                    i, oxidation, fuel.getOxidationFraction());
            Check.isTrue(fuel.getHydrogenGeneratedKg() >= hydrogen,
                    "hydrogen mass fell at tick %d: %.9f -> %.9f",
                    i, hydrogen, fuel.getHydrogenGeneratedKg());
            Check.isTrue(fuel.getTotalOxideThicknessMicrons() >= oxideGrown,
                    "total oxide grown fell at tick %d: %.9f -> %.9f",
                    i, oxideGrown, fuel.getTotalOxideThicknessMicrons());
            Check.finite(fuel.getCladTemperatureC(), "clad temperature at tick " + i);
            Check.finite(fuel.getFuelTemperatureC(), "fuel temperature at tick " + i);
            Check.finiteAndNonNegative(fuel.getHydrogenGeneratedKg(), "hydrogen at tick " + i);

            peak = fuel.getPeakCladTemperatureC();
            peakFuel = fuel.getPeakFuelTemperatureC();
            oxidation = fuel.getOxidationFraction();
            hydrogen = fuel.getHydrogenGeneratedKg();
            oxideGrown = fuel.getTotalOxideThicknessMicrons();
        }
        Check.note("400000 adversarial ticks (%d with a NaN step, %d with a negative step):"
                        + " every monotonic quantity held. Ended at peak clad %.1f C, peak fuel"
                        + " %.1f C, oxidation %.5f%%, hydrogen %.3f kg.",
                nanSteps, negativeSteps, peak, peakFuel, 100.0 * oxidation, hydrogen);
        Check.note("note that the protective oxide is deliberately NOT monotonic — cracking is"
                + " what makes the quench spike — while the total oxide grown is.");
    }

    /**
     * <b>Hydrogen is stoichiometric with the zirconium consumed, and neither can
     * exceed what is in the core.</b> Run a core to complete oxidation and check
     * the books close: 2 kg of H2 per 91.22 kg of Zr, oxidation fraction saturating
     * at one, hydrogen saturating at 2650 kg.
     */
    public static void test02_hydrogenIsStoichiometricAndBoundedByTheInventory() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.giveAmpleSteamSupply(fuel);

        double worstStoichiometryError = 0.0;
        for (int i = 0; i < 20 * 3600 * 4; i++) {
            fuel.step(0.0, AccidentScenario.CONSTANT_DECAY_FRACTION, AccidentScenario.PSIG,
                    AccidentScenario.TICK);
            if (fuel.getOxidisedZirconiumKg() > 1.0) {
                double expected = fuel.getOxidisedZirconiumKg()
                        * FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG;
                worstStoichiometryError = Math.max(worstStoichiometryError,
                        Math.abs(fuel.getHydrogenGeneratedKg() - expected) / expected);
            }
        }
        double fullCoreHydrogen = FuelThermal.DEFAULT_ZIRCONIUM_INVENTORY_KG
                * FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG;
        Check.note("four hours of an unmitigated dry core: oxidation %.4f, zirconium consumed"
                        + " %.0f of %.0f kg, hydrogen %.1f kg against a stoichiometric ceiling of"
                        + " %.1f kg", fuel.getOxidationFraction(), fuel.getOxidisedZirconiumKg(),
                fuel.getZirconiumInventoryKg(), fuel.getHydrogenGeneratedKg(), fullCoreHydrogen);
        Check.note("worst relative disagreement between hydrogen and 2 x 2.016 / 91.22 times the"
                + " zirconium consumed: %.3g", worstStoichiometryError);

        Check.lessThan(1.0e-12, worstStoichiometryError, "hydrogen-zirconium stoichiometry error");
        Check.inRange(0.0, 1.0, fuel.getOxidationFraction(),
                "oxidation fraction must saturate at one, not run past it");
        Check.relative(1.0, fuel.getOxidationFraction(), 1.0e-6,
                "an unmitigated four-hour dry core consumes the whole zirconium inventory");
        Check.relative(fullCoreHydrogen, fuel.getHydrogenGeneratedKg(), 1.0e-6,
                "hydrogen at complete oxidation, kg");
        Check.absolute(0.0, fuel.getZirconiumReactionRateKgPerS(), 1.0e-9,
                "the reaction must stop when there is no zirconium left, kg/s");
        Check.note("2650 kg of hydrogen is the whole-core number SPEC 8.3 is about. Containment"
                + " inerting is the counter-measure, and it is the player's to build.");
    }

    /**
     * <b>The two states SPEC 8.2 requires survive a reload — but only if the
     * protective oxide goes with them.</b>
     *
     * <p>{@code restoreDamageState} restores peak clad temperature, oxidation
     * fraction and hydrogen exactly. It does <b>not</b> restore the protective
     * oxide layer: it recomputes it as the as-built film plus everything grown,
     * which is the right answer for a core that has never been quenched and the
     * wrong one for a core in the middle of a quench spike, where the layer has
     * been cracked back to its floor.
     *
     * <p>{@code FuelThermal} provides {@code restoreProtectiveArealMassKgPerM2}
     * for exactly this, and with it the continuation is identical. This test
     * measures both, so that whoever wires up persistence can see what is lost by
     * skipping it.
     */
    public static void test03_reloadingMidQuenchSpikeNeedsTheProtectiveOxideToo() {
        FuelThermal live = AccidentScenario.ratedCore();
        AccidentScenario.uncover(live, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.heatUntilCladReaches(live, 1900.0,
                AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);
        AccidentScenario.reflood(live, 500.0);
        AccidentScenario.runWired(live, 2.0, AccidentScenario.CONSTANT_DECAY_FRACTION);

        Check.note("caught mid quench spike: clad %.1f C, peak %.1f C, oxidation %.5f, H2 %.3f kg,"
                        + " protective oxide %.1f um (as-built film is %.1f um)",
                live.getCladTemperatureC(), live.getPeakCladTemperatureC(),
                live.getOxidationFraction(), live.getHydrogenGeneratedKg(),
                live.getProtectiveOxideThicknessMicrons(),
                FuelThermal.DEFAULT_INITIAL_OXIDE_AREAL_MASS_KG_PER_M2
                        / FuelThermal.ZIRCONIUM_DENSITY_KG_PER_M3
                        * FuelThermal.PILLING_BEDWORTH_RATIO * 1.0e6);

        FuelThermal partial = restore(live, false);
        FuelThermal complete = restore(live, true);

        Check.exactly(live.getPeakCladTemperatureC(), partial.getPeakCladTemperatureC(),
                "peak clad temperature round trips exactly");
        Check.exactly(live.getHydrogenGeneratedKg(), partial.getHydrogenGeneratedKg(),
                "hydrogen mass round trips exactly");
        Check.relative(live.getOxidationFraction(), partial.getOxidationFraction(), 1.0e-9,
                "oxidation fraction round trips");
        Check.exactly(live.getProtectiveArealMassKgPerM2(),
                complete.getProtectiveArealMassKgPerM2(),
                "protective oxide round trips when it is carried across explicitly");

        Check.note("protective oxide after the reload: %.1f um without carrying it (%.1f um with)."
                        + " The reaction rate the restored core would see is %.2f kg/s versus"
                        + " %.2f kg/s live.",
                partial.getProtectiveOxideThicknessMicrons(),
                complete.getProtectiveOxideThicknessMicrons(),
                partial.kineticReactionRateKgPerS(live.getCladTemperatureC()),
                live.kineticReactionRateKgPerS(live.getCladTemperatureC()));

        double liveBefore = live.getHydrogenGeneratedKg();
        double partialBefore = partial.getHydrogenGeneratedKg();
        double completeBefore = complete.getHydrogenGeneratedKg();
        for (FuelThermal core : new FuelThermal[]{live, partial, complete}) {
            AccidentScenario.reflood(core, 500.0);
            core.setSteamCoolingFlowKgPerS(AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
            AccidentScenario.runWired(core, 600.0, AccidentScenario.CONSTANT_DECAY_FRACTION);
        }
        double liveRest = live.getHydrogenGeneratedKg() - liveBefore;
        double partialRest = partial.getHydrogenGeneratedKg() - partialBefore;
        double completeRest = complete.getHydrogenGeneratedKg() - completeBefore;

        Check.note("hydrogen still to come from the spike: live %.2f kg, reload carrying the"
                        + " protective oxide %.2f kg, reload without it %.2f kg (%.0f%% of the"
                        + " spike lost)", liveRest, completeRest, partialRest,
                100.0 * (1.0 - partialRest / liveRest));
        Check.relative(liveRest, completeRest, 1.0e-9,
                "a reload that carries the protective oxide continues the spike identically");
        Check.lessThan(liveRest * 0.9, partialRest,
                "a reload that drops the protective oxide loses part of the spike, kg");
        Check.note("This was a real defect and is now fixed. ReactorState previously carried"
                + " peakCladTempC and oxidationFraction but neither the hydrogen mass nor the"
                + " protective oxide, and ReactorCore.fromState passed the *live* hydrogen value"
                + " into restoreDamageState — so a save zeroed the hydrogen counter and cancelled"
                + " an in-progress quench spike. ReactorState now carries hydrogenKg and"
                + " protectiveOxideArealKgPerM2, fromState restores the film after the oxidation"
                + " inversion, and ReactorStateRoundTripTest asserts both survive exactly. The"
                + " numbers above are the cost of getting it wrong, kept as the reason the two"
                + " fields exist.");
    }

    private static FuelThermal restore(FuelThermal source, boolean carryProtectiveOxide) {
        FuelThermal restored = new FuelThermal(new CoreConfig());
        restored.restoreTemperatures(source.getFuelTemperatureC(), source.getCladTemperatureC());
        restored.restoreDamageState(source.getPeakCladTemperatureC(), source.getOxidationFraction(),
                source.getHydrogenGeneratedKg());
        if (carryProtectiveOxide) {
            restored.restoreProtectiveArealMassKgPerM2(source.getProtectiveArealMassKgPerM2());
        }
        return restored;
    }

    /**
     * <b>The stoichiometry the readouts are built on is the real stoichiometry,
     * and the one book that is kept independently balances against it.</b>
     *
     * <p>This test used to assert six "four views of one quantity" identities,
     * and five of them could not fail. {@code getHydrogenGenerationRateKgPerS()}
     * is assigned {@code rate * HYDROGEN_PER_ZIRCONIUM_KG_PER_KG} and
     * {@code getZirconiumReactionRateKgPerS()} is assigned that same
     * {@code rate}, so
     * {@code relative(rate * HYDROGEN_PER_ZIRCONIUM, hydrogenRate, 1e-12)}
     * compared an expression against itself; the same held for steam
     * consumption, reaction power, oxidation fraction and oxide thickness. Edit
     * {@code STEAM_PER_ZIRCONIUM_KG_PER_KG} to a wrong number and every one of
     * them still passed at machine precision, while the steam-starvation branch
     * in {@code QuenchSpikeRefloodTest} quietly starved at the wrong flow.
     *
     * <p>So the constants themselves are checked here, against the molar masses
     * they are derived from — Zr 91.22, H2 2.016, H2O 18.015, the 586 kJ/mol
     * heat of reaction and the Pilling-Bedworth ratio of the ZrO2 and Zr molar
     * volumes. Those are numbers from a periodic table, not from
     * {@code FuelThermal}, so they can disagree with it.
     *
     * <p>What survives from the old test is the one comparison that was always
     * real: {@code hydrogenGeneratedKg} is accumulated in a field of its own,
     * tick by tick, so checking it against the consumed zirconium is a genuine
     * cross-check of two separately kept books.
     */
    public static void test04_theOxidationReadoutsAreConsistentViewsOfOneQuantity() {
        // Molar masses, g/mol. Independent of anything in this codebase.
        final double zirconiumMolarMass = 91.22;
        final double hydrogenMolarMass = 2.016;
        final double waterMolarMass = 18.015;
        // Zr + 2 H2O -> ZrO2 + 2 H2. Two moles of each per mole of zirconium.
        double hydrogenPerZirconium = 2.0 * hydrogenMolarMass / zirconiumMolarMass;
        double steamPerZirconium = 2.0 * waterMolarMass / zirconiumMolarMass;
        // 586 kJ per mole of Zr, over 91.22 g/mol, is MJ per kg of Zr.
        double heatOfReactionMJPerKg = 586.0 / zirconiumMolarMass;

        // A tenth of a percent. The constants are quoted to six figures and the
        // last of those is rounding, so the band has to admit that — but it is
        // three orders of magnitude tighter than any real error in this
        // stoichiometry could be. A missing factor of two is 100% out, the wrong
        // molar mass for water is 12% out, and grams for kilograms is a factor
        // of a thousand. All three fail here.
        final double roundingBand = 1.0e-3;
        Check.note("stoichiometry from molar masses: H2 %.6f (constant %.6f, %+.4f%%), "
                        + "H2O %.6f (constant %.6f, %+.4f%%), %.4f MJ/kg (constant %.4f)",
                hydrogenPerZirconium, FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG,
                100.0 * (FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG - hydrogenPerZirconium)
                        / hydrogenPerZirconium,
                steamPerZirconium, FuelThermal.STEAM_PER_ZIRCONIUM_KG_PER_KG,
                100.0 * (FuelThermal.STEAM_PER_ZIRCONIUM_KG_PER_KG - steamPerZirconium)
                        / steamPerZirconium,
                heatOfReactionMJPerKg, FuelThermal.ZR_HEAT_OF_REACTION_MJ_PER_KG);
        Check.relative(hydrogenPerZirconium, FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG,
                roundingBand, "HYDROGEN_PER_ZIRCONIUM_KG_PER_KG against 2 x 2.016 / 91.22");
        Check.relative(steamPerZirconium, FuelThermal.STEAM_PER_ZIRCONIUM_KG_PER_KG,
                roundingBand, "STEAM_PER_ZIRCONIUM_KG_PER_KG against 2 x 18.015 / 91.22");
        Check.relative(heatOfReactionMJPerKg, FuelThermal.ZR_HEAT_OF_REACTION_MJ_PER_KG,
                roundingBand, "ZR_HEAT_OF_REACTION_MJ_PER_KG against 586 kJ/mol over 91.22 g/mol");
        // Steam and hydrogen come out of the same balanced equation, so their
        // ratio is the molar-mass ratio exactly and carries no fitting freedom
        // at all. This one is tight.
        Check.relative(waterMolarMass / hydrogenMolarMass,
                FuelThermal.STEAM_PER_ZIRCONIUM_KG_PER_KG
                        / FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG, 1.0e-4,
                "steam per hydrogen must be M(H2O) / M(H2) — two moles of each, so the "
                        + "zirconium cancels");

        // Pilling-Bedworth is the molar volume of the oxide over that of the
        // metal: (M_ZrO2 / rho_ZrO2) / (M_Zr / rho_Zr). ZrO2 is 123.22 g/mol.
        double zirconiaMolarMass = zirconiumMolarMass + 2.0 * 15.999;
        double pillingBedworth =
                (zirconiaMolarMass / FuelThermal.ZIRCONIA_DENSITY_KG_PER_M3)
                        / (zirconiumMolarMass / FuelThermal.ZIRCONIUM_DENSITY_KG_PER_M3);
        Check.note("Pilling-Bedworth from molar volumes at the densities this model uses: "
                        + "%.4f, constant %.4f", pillingBedworth, FuelThermal.PILLING_BEDWORTH_RATIO);
        Check.relative(pillingBedworth, FuelThermal.PILLING_BEDWORTH_RATIO, 0.02,
                "PILLING_BEDWORTH_RATIO against the ZrO2 and Zr molar volumes");

        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.heatUntilCladReaches(fuel, 1800.0,
                AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);

        // The genuinely independent book: hydrogenGeneratedKg is a running sum
        // of its own, accumulated tick by tick, so it really can drift away from
        // the reacted areal mass the oxidation fraction is computed from. This
        // is the one comparison in the old test that was ever capable of
        // failing, and it is kept at its original tolerance.
        double consumed = fuel.getOxidisedZirconiumKg();
        Check.finiteAndPositive(consumed, "zirconium consumed by the time the clad reaches 1800 C");
        Check.relative(consumed * FuelThermal.HYDROGEN_PER_ZIRCONIUM_KG_PER_KG,
                fuel.getHydrogenGeneratedKg(), 1.0e-12,
                "accumulated hydrogen against the separately accumulated consumed zirconium");
        // And the same comparison against the chemistry rather than against the
        // constant, at the rounding band, so a wrong constant cannot satisfy
        // both books at once.
        Check.relative(consumed * hydrogenPerZirconium, fuel.getHydrogenGeneratedKg(),
                roundingBand,
                "accumulated hydrogen against the consumed zirconium and the molar-mass "
                        + "stoichiometry");

        Check.note("at %.0f C: %.0f kg Zr consumed = %.4f%% oxidised = %.1f kg H2 = %.1f um of"
                        + " oxide, making %.1f MW and eating %.2f kg/s of steam",
                fuel.getCladTemperatureC(), consumed, 100.0 * fuel.getOxidationFraction(),
                fuel.getHydrogenGeneratedKg(), fuel.getTotalOxideThicknessMicrons(),
                fuel.getZirconiumReactionPowerMW(), fuel.getSteamConsumptionKgPerS());
    }

    /**
     * <b>Peak fuel temperature and the melt fraction are the stage 5 readout.</b>
     * The fraction is a plain ratio against the UO2 melting point, it is
     * monotonic because the peak it divides is, and it keeps counting past one
     * rather than clamping — a reactor that reached 1.4 melted harder than one
     * that reached 1.01, and the player should be able to tell.
     */
    public static void test05_theMeltFractionIsAPlainMonotonicRatio() {
        // The denominator, checked against the literature rather than against
        // the getter that divides by it. This test used to assert
        // `relative(peakFuelC / FUEL_MELT_C, fraction, 1e-15)`, which is exactly
        // how getPeakFuelTemperatureFractionOfMelt() is written — the same
        // quotient of the same two numbers, so it could not disagree, and an
        // edit to FUEL_MELT_C would have moved both sides together. The
        // measured UO2 melting point is 2847 +/- 30 degC (Fink); a 2% band
        // around it catches a constant that has drifted or lost a digit.
        Check.relative(2847.0, PhysicalConstants.FUEL_MELT_C, 0.02,
                "UO2 melting point, the denominator of the melt fraction");
        Check.note("melt fraction denominator: FUEL_MELT_C = %.0f degC against a measured "
                + "2847 degC", PhysicalConstants.FUEL_MELT_C);

        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.giveAmpleSteamSupply(fuel);

        double previous = fuel.getPeakFuelTemperatureFractionOfMelt();
        double crossedAt = Double.NaN;
        double peakAtCrossing = Double.NaN;
        double elapsed = 0.0;
        for (int i = 0; i < 20 * 3600; i++) {
            fuel.step(0.0, AccidentScenario.CONSTANT_DECAY_FRACTION, AccidentScenario.PSIG,
                    AccidentScenario.TICK);
            elapsed += AccidentScenario.TICK;
            double fraction = fuel.getPeakFuelTemperatureFractionOfMelt();
            Check.isTrue(fraction >= previous, "melt fraction fell at t = %.2f s: %.9f -> %.9f",
                    elapsed, previous, fraction);
            if (Double.isNaN(crossedAt) && fraction >= 1.0) {
                crossedAt = elapsed;
                peakAtCrossing = fuel.getPeakFuelTemperatureC();
            }
            previous = fraction;
            if (fraction > 1.4) {
                break;
            }
        }
        // The fraction must reach one when the fuel reaches the melting point,
        // not before and not after. This is the property the ratio exists to
        // deliver, and unlike recomputing the quotient it is checked against the
        // temperature scale rather than against the division that produced it:
        // a fraction scaled by anything other than the melting point crosses at
        // the wrong temperature and fails here.
        Check.absolute(PhysicalConstants.FUEL_MELT_C, peakAtCrossing, 25.0,
                "peak fuel temperature at the tick the melt fraction first reached one, degC");
        Check.finite(crossedAt, "time at which the melt fraction crossed one");
        Check.note("melt fraction crossed 1.0 at t = %.0f s and kept counting to %.3f"
                        + " (peak fuel %.0f C against a %.0f C melting point) without clamping",
                crossedAt, previous, fuel.getPeakFuelTemperatureC(), PhysicalConstants.FUEL_MELT_C);
        Check.greaterThan(1.4, previous, "melt fraction is not clamped at one");
    }

    /**
     * <b>Zero-length, negative and non-finite steps are no-ops.</b> A game tick
     * that arrives with a bad delta must not corrupt the damage record — this is
     * the difference between a laggy server and a spontaneous meltdown.
     */
    public static void test06_badTimeStepsAreNoOps() {
        FuelThermal fuel = AccidentScenario.ratedCore();
        AccidentScenario.uncover(fuel, AccidentScenario.DRY_CORE_STEAM_COOLING_KG_PER_S);
        AccidentScenario.heatUntilCladReaches(fuel, 1600.0,
                AccidentScenario.CONSTANT_DECAY_FRACTION, 20_000.0);

        double clad = fuel.getCladTemperatureC();
        double fuelTemp = fuel.getFuelTemperatureC();
        double hydrogen = fuel.getHydrogenGeneratedKg();
        double oxidation = fuel.getOxidationFraction();
        double protective = fuel.getProtectiveArealMassKgPerM2();

        for (double dt : new double[]{0.0, -1.0, -0.05, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            double returned = fuel.step(0.5, 0.05, AccidentScenario.PSIG, dt);
            Check.exactly(clad, returned, "step(dt = " + dt + ") must return the clad temperature");
            Check.exactly(clad, fuel.getCladTemperatureC(), "clad after a dt = " + dt + " step");
            Check.exactly(fuelTemp, fuel.getFuelTemperatureC(), "fuel after a dt = " + dt + " step");
            Check.exactly(hydrogen, fuel.getHydrogenGeneratedKg(),
                    "hydrogen after a dt = " + dt + " step");
            Check.exactly(oxidation, fuel.getOxidationFraction(),
                    "oxidation after a dt = " + dt + " step");
            Check.exactly(protective, fuel.getProtectiveArealMassKgPerM2(),
                    "protective oxide after a dt = " + dt + " step");
        }
        Check.note("six malformed time steps (zero, negative, NaN, both infinities) left a"
                        + " %.1f C core with %.3f kg of hydrogen bit-for-bit unchanged",
                clad, hydrogen);
    }
}
