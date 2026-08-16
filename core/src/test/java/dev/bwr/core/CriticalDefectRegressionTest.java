package dev.bwr.core;

import dev.bwr.core.harness.TransientHarness;
import dev.bwr.core.instrument.IntermediateRangeMonitor;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;

import java.util.Arrays;

/**
 * Regression cover for the critical defects the audit found, one test per
 * defect, each written so that it fails if the defect comes back.
 *
 * <p>Every test here is named for the wrong behaviour it forbids rather than for
 * the component it exercises, because that is what makes a regression test
 * readable two years later: the interesting thing about it is not that a scram
 * latch round trips, it is that a save used to <em>cancel</em> a scram.
 *
 * <h2>What this module cannot reach, and why saying so matters</h2>
 * {@code core/} has no Minecraft on its classpath — that is the design, and
 * {@link #test08_thisModuleCannotReachTheMinecraftSideDefects()} asserts it
 * rather than assuming it. Three of the ten criticals are mod-side and have
 * <b>no coverage here at all</b>:
 * <ul>
 *   <li>the multiblock rebuild on chunk load,</li>
 *   <li>the recirculation pump's energy capability,</li>
 *   <li>the {@code CMD_ROD_STEP} overflow in the peripheral command path.</li>
 * </ul>
 * They need a test source set that can load {@code net.minecraft} classes. A
 * comment claiming they are "covered by the physics tests" would be false, and
 * this class exists partly to make that impossible to believe by accident.
 */
public final class CriticalDefectRegressionTest {

    private CriticalDefectRegressionTest() {
    }

    private static TransientHarness.PlantOperator operatorFor(ReactorCore core) {
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);
        return operator;
    }

    // ---------------------------------------------------------------
    // 1. The scram latch must survive a save
    // ---------------------------------------------------------------

    /**
     * <b>A save must not cancel a scram in progress.</b>
     *
     * <p>{@code scramActive} used to be absent from {@link ReactorState}, so a
     * chunk unload mid-stroke brought the drives back <i>unlatched</i> at
     * whatever notch they had reached, with the demand collapsed onto that same
     * notch. On a de-energised CRD bus — the ATWS case, where the scram path is
     * the only thing that could still move a rod — that left the rods stopped
     * permanently mid-stroke while the half-spent accumulators quietly recharged
     * over the evidence.
     *
     * <p>Both halves of that are checked, and the second half is checked against
     * the counterfactual: the same snapshot restored and then deliberately
     * un-latched is driven forward alongside, and it must exhibit exactly the
     * old failure. A regression test for a latch that only checks the latch came
     * back true would still pass if the latch stopped doing anything.
     */
    public static void test01_aSaveMustNotCancelAScramInProgress() {
        ReactorCore original = TransientHarness.ratedCore();
        original.setBurnupEnabled(false);
        TransientHarness.runSeconds(original, operatorFor(original), 10.0, null);
        original.scram();
        // Stop mid-stroke: rods partly in, accumulators partly spent.
        TransientHarness.runSeconds(original, operatorFor(original), 1.0, null);

        int notchAtSave = original.getRodNotchIndex(0);
        double chargeAtSave = original.getAccumulatorCharge(0);
        Check.isTrue(original.isScramActive(), "the core must be scrammed when it is saved");
        Check.isTrue(notchAtSave > RodWorth.NOTCH_INDEX_FULLY_INSERTED,
                "the save must catch the rods mid-stroke, not already in; notch %d", notchAtSave);
        Check.inRange(0.0, 1.0 - 1.0e-9, chargeAtSave,
                "the save must catch the accumulators partly spent");
        Check.note("saved mid-scram at notch %d with accumulator 0 at %.4f charge",
                notchAtSave, chargeAtSave);

        ReactorState snapshot = original.toState();
        Check.exactly(true, snapshot.scramActive(), "the snapshot must record the scram latch");

        // The restore that gets it right, on a de-energised, un-watered CRD bus:
        // nothing but the scram path can move these rods.
        ReactorCore latched = new ReactorCore(new CoreConfig());
        latched.setBurnupEnabled(false);
        latched.fromState(snapshot);
        Check.exactly(true, latched.isScramActive(), "the restored core must resume scrammed");
        Check.exactly(notchAtSave, latched.getRodNotchIndex(0), "rod 0 resumes where it was");
        Check.exactly(chargeAtSave, latched.getAccumulatorCharge(0),
                "accumulator 0 resumes with the charge it had");
        latched.setCrdPowered(false);
        latched.setCrdWaterSupplied(false);

        // The restore that gets it wrong, reproduced exactly: the latch cleared,
        // and — this is the half that did the damage — the standing demand
        // collapsed onto the notch each rod had reached, which is what a restore
        // with no scramActive component had to fall back on. CRD power and water
        // are left available, because that is the state a real plant is in and
        // it is what let the accumulators refill.
        ReactorCore unlatched = new ReactorCore(new CoreConfig());
        unlatched.setBurnupEnabled(false);
        unlatched.fromState(snapshot);
        unlatched.resetScram();
        int[] achieved = snapshot.rodNotches();
        for (int rod = 0; rod < achieved.length; rod++) {
            unlatched.setRodNotchDemand(rod, achieved[rod]);
        }

        double worstChargeRise = 0.0;
        double previousCharge = latched.getAccumulatorCharge(0);
        for (int i = 0; i < 20 * 30; i++) { // 30 s
            latched.step();
            unlatched.step();
            double charge = latched.getAccumulatorCharge(0);
            worstChargeRise = Math.max(worstChargeRise, charge - previousCharge);
            previousCharge = charge;
        }

        Check.note("30 s later: latched core rod 0 at notch %d with %.4f charge; "
                        + "un-latched core rod 0 at notch %d with %.4f charge",
                latched.getRodNotchIndex(0), latched.getAccumulatorCharge(0),
                unlatched.getRodNotchIndex(0), unlatched.getAccumulatorCharge(0));

        // The rods finish the stroke on the scram path, with no CRD power and no
        // CRD water, because the latch is what selects that path.
        Check.exactly(RodWorth.NOTCH_INDEX_FULLY_INSERTED, latched.getRodNotchIndex(0),
                "a restored scram must finish driving the rods in, with the CRD bus dead");
        for (int rod = 0; rod < latched.getControlRodCount(); rod++) {
            Check.exactly(RodWorth.NOTCH_INDEX_FULLY_INSERTED, latched.getRodNotchIndex(rod),
                    "rod " + rod + " after the restored scram completes");
        }
        // And nothing recharges while the signal is in. Not "recharges slowly":
        // not at all, on any tick.
        Check.exactly(0.0, worstChargeRise,
                "an accumulator must not gain charge on any tick while the scram is latched");
        Check.isTrue(latched.getAccumulatorCharge(0) <= chargeAtSave,
                "accumulator 0 must not be above its saved charge after finishing the stroke: "
                        + "%.6f against %.6f", latched.getAccumulatorCharge(0), chargeAtSave);

        // The counterfactual, which is the defect: rods stopped where they were,
        // accumulators refilling over the evidence.
        Check.exactly(notchAtSave, unlatched.getRodNotchIndex(0),
                "with the latch dropped the rods stop mid-stroke — this is the defect, kept "
                        + "here so the test above is measuring something");
        Check.greaterThan(chargeAtSave, unlatched.getAccumulatorCharge(0),
                "with the latch dropped the accumulators recharge over the evidence");
    }

    // ---------------------------------------------------------------
    // 2. Peak fuel temperature must never decrease across a round trip
    // ---------------------------------------------------------------

    /**
     * <b>A save must not un-melt the fuel.</b>
     *
     * <p>Peak fuel temperature has no reconstruction. Heat flows fuel to clad, so
     * peak fuel is always strictly above peak clad, and the restore used to floor
     * it at {@code max(peakCladTempC, fuelTempC)} — a floor that always loses. A
     * core that touched 2400 degC and then cooled came back reporting a peak near
     * the saturation temperature, and
     * {@code getPeakFuelTemperatureFractionOfMelt()}, which is computed from this
     * and nothing else, forgot the accident had happened.
     *
     * <p>The awkward case is deliberately the one that is checked: a core that is
     * <i>cold now</i> and melted <i>then</i>. A hot core would survive the old
     * floor by accident.
     */
    public static void test02_aSaveMustNotUnMeltTheFuel() {
        ReactorCore core = TransientHarness.ratedCore();
        core.setBurnupEnabled(false);
        ReactorState atRated = core.toState();

        // 2400 degC of history on a core whose live temperatures are ordinary.
        // Every other component is the real snapshot, so nothing else about this
        // state is contrived.
        final double meltedPeakC = 2400.0;
        ReactorState melted = withPeakFuelTemperature(atRated, meltedPeakC);
        Check.greaterThan(melted.peakCladTempC(), melted.peakFuelTempC(),
                "the probe state must have peak fuel above peak clad, which is the case the "
                        + "old max(peakClad, fuelTemp) floor could not represent");
        Check.greaterThan(melted.fuelTempC(), melted.peakFuelTempC(),
                "and above the live fuel temperature, which is the other half of that floor");

        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        restored.fromState(melted);
        Check.exactly(meltedPeakC, restored.getPeakFuelTemperatureC(),
                "peak fuel temperature must come back exactly, not be reconstructed");
        Check.exactly(meltedPeakC, restored.toState().peakFuelTempC(),
                "and must be reported back out of the record unchanged");
        Check.relative(meltedPeakC / PhysicalConstants.FUEL_MELT_C,
                restored.getFuelThermal().getPeakFuelTemperatureFractionOfMelt(), 1.0e-12,
                "the melt fraction the restored core reports");

        // Ten reload cycles with the core stepping in between. Monotonic means
        // monotonic: not one round trip, and never downward.
        double previousPeak = restored.getPeakFuelTemperatureC();
        ReactorState carried = restored.toState();
        for (int cycle = 0; cycle < 10; cycle++) {
            ReactorCore next = new ReactorCore(new CoreConfig());
            next.setBurnupEnabled(false);
            next.fromState(carried);
            for (int i = 0; i < 20; i++) { // a second of running between reloads
                next.step();
            }
            double peak = next.getPeakFuelTemperatureC();
            Check.isTrue(peak >= previousPeak,
                    "peak fuel temperature fell across reload %d: %.9f -> %.9f",
                    cycle, previousPeak, peak);
            previousPeak = peak;
            carried = next.toState();
        }
        Check.note("ten reload cycles with a second of running between each: peak fuel held at "
                + "%.1f degC (%.4f of the melting point)", previousPeak,
                previousPeak / PhysicalConstants.FUEL_MELT_C);
        Check.greaterThan(meltedPeakC - 1.0e-9, previousPeak,
                "peak fuel temperature after ten reloads");
    }

    /**
     * The same record with one component replaced. Written through the canonical
     * constructor on purpose: adding a component to {@link ReactorState} must
     * break this call site, exactly as the record's class comment demands.
     */
    private static ReactorState withPeakFuelTemperature(ReactorState state, double peakFuelTempC) {
        return new ReactorState(
                state.neutronPower(), state.precursors(), state.sourceStrength(),
                state.decayHeatFraction(), state.decayGroups(), state.fuelTempC(),
                state.cladTempC(), state.peakCladTempC(), peakFuelTempC,
                state.oxidationFraction(), state.hydrogenKg(),
                state.protectiveOxideArealKgPerM2(), state.coolantTempC(), state.pressurePsig(),
                state.voidFraction(), state.coreInletEnthalpyKJPerKg(),
                state.coreFlowFraction(), state.waterLevelIn(),
                state.xenon(), state.iodine(), state.boronPpm(), state.burnupMwdPerTonne(),
                state.rodNotches(), state.accumulatorCharge(), state.reactivityTotal(),
                state.betaEff(), state.promptLifetime(), state.rodFluxWeights(),
                state.fuelExcessReactivityDkOverK(),
                state.dopplerCoefficientPerCAtAnchor(), state.elapsedSeconds(),
                state.scramActive(), state.intermediateRangeMonitorRanges());
    }

    // ---------------------------------------------------------------
    // 3. IRM range switches must survive a reload
    // ---------------------------------------------------------------

    /**
     * <b>A reload must not range the instruments on the operator's behalf.</b>
     *
     * <p>Ranging an IRM is manual by design. The block entity builds a fresh core
     * on every chunk load and a fresh core's channels sit on their most sensitive
     * detent, so not persisting the switch positions was <em>silent
     * auto-ranging</em>: a mid-startup core came back with eight pegged channels
     * reading an infinite period.
     *
     * <p>The pattern used here is deliberately not uniform — a uniform pattern
     * would round trip through a bug that stored one number for the whole rack.
     */
    public static void test03_aReloadMustNotRangeTheIrmsForYou() {
        ReactorCore core = TransientHarness.shutdownCore();
        core.setBurnupEnabled(false);
        int channels = core.getIntermediateRangeMonitorRanges().length;
        Check.greaterThan(1, channels, "there must be more than one IRM channel to get wrong");

        int[] wanted = new int[channels];
        for (int i = 0; i < channels; i++) {
            // 1, 3, 5, 7, ... wrapped into the switch's travel: distinct, and
            // none of them is the detent a fresh core comes up on.
            wanted[i] = IntermediateRangeMonitor.LOWEST_RANGE + 1
                    + (2 * i) % (IntermediateRangeMonitor.HIGHEST_RANGE
                    - IntermediateRangeMonitor.LOWEST_RANGE);
            core.setIntermediateRangeMonitorRange(i, wanted[i]);
        }
        Check.arraysExactly(wanted, core.getIntermediateRangeMonitorRanges(),
                "the switches must go where they are put");

        ReactorState snapshot = core.toState();
        Check.arraysExactly(wanted, snapshot.intermediateRangeMonitorRanges(),
                "the snapshot must carry the switch positions");

        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        int[] fresh = restored.getIntermediateRangeMonitorRanges();
        restored.fromState(snapshot);

        Check.note("fresh core comes up on %s; saved rack was on %s; after the reload %s",
                Arrays.toString(fresh), Arrays.toString(wanted),
                Arrays.toString(restored.getIntermediateRangeMonitorRanges()));
        Check.arraysExactly(wanted, restored.getIntermediateRangeMonitorRanges(),
                "the reload must leave every range switch where the operator left it");
        // The fresh core's detents are what a dropped component restores to, so
        // the test only means something if they differ from the saved pattern.
        boolean differsFromFresh = false;
        for (int i = 0; i < channels; i++) {
            differsFromFresh |= fresh[i] != wanted[i];
        }
        Check.isTrue(differsFromFresh,
                "the saved pattern must differ from what a fresh core comes up on, or this "
                        + "test would pass with the component dropped");

        // A record from before the component existed carries no ranges at all,
        // and must leave the switches alone rather than moving them to detent 1.
        ReactorCore legacyTarget = new ReactorCore(new CoreConfig());
        legacyTarget.setBurnupEnabled(false);
        for (int i = 0; i < channels; i++) {
            legacyTarget.setIntermediateRangeMonitorRange(i, wanted[i]);
        }
        legacyTarget.fromState(withIrmRanges(snapshot, new int[0]));
        Check.arraysExactly(wanted, legacyTarget.getIntermediateRangeMonitorRanges(),
                "a record that predates the component must leave the switches where they are");
    }

    private static ReactorState withIrmRanges(ReactorState state, int[] ranges) {
        return new ReactorState(
                state.neutronPower(), state.precursors(), state.sourceStrength(),
                state.decayHeatFraction(), state.decayGroups(), state.fuelTempC(),
                state.cladTempC(), state.peakCladTempC(), state.peakFuelTempC(),
                state.oxidationFraction(), state.hydrogenKg(),
                state.protectiveOxideArealKgPerM2(), state.coolantTempC(), state.pressurePsig(),
                state.voidFraction(), state.coreInletEnthalpyKJPerKg(),
                state.coreFlowFraction(), state.waterLevelIn(),
                state.xenon(), state.iodine(), state.boronPpm(), state.burnupMwdPerTonne(),
                state.rodNotches(), state.accumulatorCharge(), state.reactivityTotal(),
                state.betaEff(), state.promptLifetime(), state.rodFluxWeights(),
                state.fuelExcessReactivityDkOverK(),
                state.dopplerCoefficientPerCAtAnchor(), state.elapsedSeconds(),
                state.scramActive(), ranges);
    }

    // ---------------------------------------------------------------
    // 4. Rod worth must depend on where the rod is
    // ---------------------------------------------------------------

    /**
     * <b>A central rod must be worth materially more than a peripheral one.</b>
     *
     * <p>The nodal solve computes per-rod flux weights and the reactivity balance
     * used to throw them away, so every rod came out worth
     * {@code totalRodWorth / rodCount}. Numerically that looks fine and
     * spatially it is wrong: a stuck central rod cost the same shutdown margin as
     * a stuck peripheral one, and the rod pattern search could not tell the
     * difference between withdrawing one and withdrawing the other.
     *
     * <p>The dead behaviour has a signature that is easy to assert against: with
     * uniform weighting every ratio below is exactly 1.000.
     */
    public static void test04_rodWorthMustDependOnWhereTheRodIs() {
        ReactorCore core = TransientHarness.ratedCore();
        RodWorth rodWorth = core.getRodWorth();
        int rods = core.getControlRodCount();
        Check.greaterThan(1, rods, "there must be more than one rod for position to matter");

        // Rods are numbered by distance rank from the centre of the lattice
        // (RodLatticeMap.centreOutward), so rod 0 is central and the last rod is
        // the most peripheral one that owns any fuel at all.
        double central = Math.abs(rodWorth.rodFullWorthDkOverK(0));
        double peripheral = Math.abs(rodWorth.rodFullWorthDkOverK(rods - 1));
        double[] weights = rodWorth.getRodFluxWeights();
        double minimumWeight = Arrays.stream(weights).min().orElseThrow();
        double maximumWeight = Arrays.stream(weights).max().orElseThrow();

        Check.note("%d rods: central rod worth %.4g dk/k, peripheral %.4g dk/k, ratio %.2f",
                rods, rodWorth.rodFullWorthDkOverK(0), rodWorth.rodFullWorthDkOverK(rods - 1),
                central / peripheral);
        Check.note("per-rod flux weights span %.4f to %.4f (uniform weighting would be 1.000 "
                + "everywhere, which is what this test exists to forbid)",
                minimumWeight, maximumWeight);

        Check.finiteAndPositive(peripheral, "peripheral rod worth magnitude");
        Check.greaterThan(2.0, central / peripheral,
                "a central rod must be worth at least twice a peripheral one");
        Check.greaterThan(1.5, maximumWeight / minimumWeight,
                "the per-rod flux weights must actually spread");

        // The total is unchanged by the redistribution — the weights are
        // normalised to mean one, so the shape moves worth between rods without
        // inventing any. This is what makes it safe to fix.
        int[] allIn = new int[rods];
        double total = rodWorth.totalInsertedWorthDkOverK(allIn);
        Check.relative(rodWorth.getTotalRodWorthDkOverK(), total, 1.0e-9,
                "all rods in must be worth the configured total, however it is distributed");

        // And it must survive a reload, or the identity of the stuck rod changes
        // when the chunk unloads.
        ReactorCore restored = new ReactorCore(new CoreConfig());
        restored.setBurnupEnabled(false);
        restored.fromState(core.toState());
        double restoredCentral = Math.abs(restored.getRodWorth().rodFullWorthDkOverK(0));
        double restoredPeripheral =
                Math.abs(restored.getRodWorth().rodFullWorthDkOverK(rods - 1));
        Check.note("after a reload: central %.4g, peripheral %.4g, ratio %.2f",
                restoredCentral, restoredPeripheral, restoredCentral / restoredPeripheral);
        Check.greaterThan(2.0, restoredCentral / restoredPeripheral,
                "a restored core must also know which rods are central");
    }

    // ---------------------------------------------------------------
    // 5. The vessel must conserve mass
    // ---------------------------------------------------------------

    /**
     * <b>With every valve shut the vessel must not lose water, and at the
     * pressure floor it must not make steam out of nothing.</b>
     *
     * <p>Dome steam mass is not an independent state: it is
     * {@code V_dome * rho_g(P)}, so the only way steam mass changes is for
     * pressure to change. When the pressure solve was clamped at the ceiling or
     * the floor, the boiling and steam-removal terms went on being applied at
     * full size against a pressure change that had been refused, and the balance
     * broke by exactly the refused fraction: 187 tonnes lost in 75 s with every
     * valve shut, and 237 tonnes of steam from nothing in 600 s at the floor.
     */
    public static void test05_theVesselMustConserveMassAtBothPressureLimits() {
        // --- the ceiling: heat with nowhere to go, every valve shut.
        CoreConfig config = new CoreConfig();
        PressureVessel vessel = new PressureVessel(config);
        vessel.initialiseToNormalLevel();
        vessel.setFeedwaterFlowKgPerS(0.0);
        vessel.setInjectionFlowKgPerS(0.0);
        vessel.setLiquidLeakKgPerS(0.0);
        vessel.setSteamLeakKgPerS(0.0);

        double startMass = totalMass(vessel);
        double worstExcursion = 0.0;
        for (int i = 0; i < 20 * 75; i++) { // 75 s
            vessel.step(config.ratedThermalMW, 0.37, 0.0, 0.0, 0.0, 0.05);
            worstExcursion = Math.max(worstExcursion,
                    Math.abs(totalMass(vessel) - startMass) / startMass);
        }
        double endMass = totalMass(vessel);
        Check.note("75 s at rated power with every valve shut: %.3f -> %.3f kg (%+.4g kg, "
                        + "%.3g relative), ending at %.0f psig",
                startMass, endMass, endMass - startMass,
                (endMass - startMass) / startMass, vessel.getPressurePsig());
        Check.greaterThan(PressureVessel.MAXIMUM_PRESSURE_PSIG - 1.0, vessel.getPressurePsig(),
                "this case has to reach the pressure ceiling, or the clamp is not being exercised");
        // The defect lost 187 t of 227 t, a relative 0.82. A bound of 1e-4 is
        // four orders of magnitude inside that and ten times the measured 1.1e-5.
        Check.lessThan(1.0e-4, worstExcursion,
                "vessel mass must be conserved with every valve shut, even against the "
                        + "pressure ceiling");

        // --- the floor: an open relief valve on a vessel with no heat in it.
        PressureVessel blowdown = new PressureVessel(config);
        blowdown.initialiseToNormalLevel();
        blowdown.setFeedwaterFlowKgPerS(0.0);
        blowdown.setInjectionFlowKgPerS(0.0);
        blowdown.setLiquidLeakKgPerS(0.0);
        blowdown.setSteamLeakKgPerS(0.0);

        final double reliefDemandKgPerS = 2000.0;
        int floorTick = -1;
        for (int i = 0; i < 20 * 3000; i++) {
            blowdown.step(0.0, 0.0, 0.0, 0.0, reliefDemandKgPerS, 0.05);
            if (blowdown.getPressurePsig() <= PressureVessel.MINIMUM_PRESSURE_PSIG + 1.0e-9) {
                floorTick = i;
                break;
            }
        }
        Check.isTrue(floorTick >= 0,
                "the blowdown must actually reach the pressure floor for this case to mean "
                        + "anything");
        double massAtFloor = totalMass(blowdown);

        for (int i = 0; i < 20 * 600; i++) { // 600 s sitting on the floor
            blowdown.step(0.0, 0.0, 0.0, 0.0, reliefDemandKgPerS, 0.05);
        }
        double massAfter = totalMass(blowdown);
        Check.note("floor reached at t = %.1f s with %.1f kg aboard; 600 s more with a %.0f kg/s "
                        + "relief demand left %.1f kg (%+.4g kg)",
                floorTick * 0.05, massAtFloor, reliefDemandKgPerS, massAfter,
                massAfter - massAtFloor);
        Check.note("relief demand %.0f kg/s, steam actually removed %.6g kg/s, "
                        + "steam generated %.6g kg/s",
                blowdown.getCommandedSteamRemovalKgPerS(), blowdown.getSteamRemovalKgPerS(),
                blowdown.getSteamGenerationKgPerS());
        // The defect ADDED 237 tonnes here. A microgram is a bound no leak and no
        // fabrication can hide inside.
        Check.absolute(massAtFloor, massAfter, 1.0e-6,
                "a vessel sitting on the pressure floor must neither gain nor lose mass");
        // An open valve on an empty steam space passes nothing, and the readback
        // has to say so rather than repeat the demand back.
        Check.exactly(0.0, blowdown.getSteamRemovalKgPerS(),
                "steam actually removed at the pressure floor");
        Check.exactly(reliefDemandKgPerS, blowdown.getCommandedSteamRemovalKgPerS(),
                "the commanded flow is still what the player asked for");
    }

    private static double totalMass(PressureVessel vessel) {
        return vessel.getLiquidMassKg() + vessel.getSteamMassKg();
    }

    // ---------------------------------------------------------------
    // 6. A full vessel at low pressure is not an uncovered core
    // ---------------------------------------------------------------

    /**
     * <b>A vessel with its whole inventory in it must not report the core
     * uncovered, at any pressure.</b>
     *
     * <p>Collapsed level is inventory divided by saturated liquid density, and
     * the density fit was a power law calibrated over 800-1400 psia with a
     * negative exponent. Extrapolated downward it inflated without limit — +64%
     * at atmospheric — so a vessel blown down to 0 psig with all 220 tonnes still
     * aboard reported three quarters of the core dry, and every ECCS decision a
     * control program made from that was made on a lie.
     */
    public static void test06_aFullVesselAtLowPressureIsNotAnUncoveredCore() {
        CoreConfig config = new CoreConfig();
        PressureVessel vessel = new PressureVessel(config);
        vessel.initialiseToNormalLevel();

        double inventoryKg = vessel.getLiquidMassKg();
        double ratedPsia = vessel.getPressurePsia();
        double ratedDensity = Saturation.liquidDensityKgPerM3(ratedPsia);
        double ratedHeightIn = vessel.getCollapsedLevelIn()
                + PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
        Check.exactly(1.0, vessel.getCoveredFuelFraction(), "the core is covered at rated");

        for (double psig : new double[]{800.0, 600.0, 400.0, 200.0, 50.0, 0.0}) {
            vessel.restorePressurePsig(psig);
            vessel.restoreLiquidMassKg(inventoryKg);

            double density = Saturation.liquidDensityKgPerM3(vessel.getPressurePsia());
            double heightIn = vessel.getCollapsedLevelIn()
                    + PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;

            Check.note("%6.1f psig: rho_f %.1f kg/m3, collapsed level %+8.2f in "
                            + "(TAF is %+.1f in), covered %.4f",
                    psig, density, vessel.getCollapsedLevelIn(),
                    PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN, vessel.getCoveredFuelFraction());

            // The headline: the same water is still in the vessel.
            Check.exactly(1.0, vessel.getCoveredFuelFraction(),
                    "a full vessel at " + psig + " psig must report the core fully covered");
            Check.greaterThan(PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN,
                    vessel.getCollapsedLevelIn(),
                    "collapsed level at " + psig + " psig must stay above the top of active fuel");

            // Water is denser cold, so the same mass occupies less height — and
            // the height must scale exactly as the inverse of the density it is
            // divided by. This is mass conservation against the density
            // correlation, not a re-derivation of the level formula, and it is
            // what caught the extrapolated power law: at 0 psig that fit gave
            // 1571 kg/m3 against a true 959, and the height it produced was 39%
            // of the right one.
            Check.relative(ratedHeightIn * ratedDensity / density, heightIn, 1.0e-12,
                    "collapsed height at " + psig + " psig against inventory over density");
            Check.lessThan(ratedHeightIn, heightIn,
                    "colder water is denser, so the same inventory must sit lower");
        }

        // And through the record, which is where a control program reads it.
        vessel.restorePressurePsig(0.0);
        vessel.restoreLiquidMassKg(inventoryKg);
        ReactorCore core = TransientHarness.shutdownCore();
        ReactorState depressurised = withLevelAndPressure(core.toState(),
                vessel.getCollapsedLevelIn(), vessel.getPressurePsig());
        Check.isFalse(depressurised.coreUncovered(),
                "ReactorState.coreUncovered() must be false for a full vessel at 0 psig");
    }

    private static ReactorState withLevelAndPressure(ReactorState state,
                                                     double waterLevelIn, double pressurePsig) {
        return new ReactorState(
                state.neutronPower(), state.precursors(), state.sourceStrength(),
                state.decayHeatFraction(), state.decayGroups(), state.fuelTempC(),
                state.cladTempC(), state.peakCladTempC(), state.peakFuelTempC(),
                state.oxidationFraction(), state.hydrogenKg(),
                state.protectiveOxideArealKgPerM2(), state.coolantTempC(), pressurePsig,
                state.voidFraction(), state.coreInletEnthalpyKJPerKg(),
                state.coreFlowFraction(), waterLevelIn,
                state.xenon(), state.iodine(), state.boronPpm(), state.burnupMwdPerTonne(),
                state.rodNotches(), state.accumulatorCharge(), state.reactivityTotal(),
                state.betaEff(), state.promptLifetime(), state.rodFluxWeights(),
                state.fuelExcessReactivityDkOverK(),
                state.dopplerCoefficientPerCAtAnchor(), state.elapsedSeconds(),
                state.scramActive(), state.intermediateRangeMonitorRanges());
    }

    // ---------------------------------------------------------------
    // 7. What this module cannot reach
    // ---------------------------------------------------------------

    /**
     * <b>Three of the ten criticals have no coverage in this module, and this
     * test is where that is written down.</b>
     *
     * <p>The claim "core/ has no Minecraft on its classpath" is the reason, so it
     * is asserted rather than asserted-in-a-comment: if a Minecraft class ever
     * becomes loadable from here, the physics module has stopped being pure Java
     * and this test fails, which is a separate defect worth catching on its own.
     */
    public static void test08_thisModuleCannotReachTheMinecraftSideDefects() {
        String[] minecraftClasses = {
                "net.minecraft.world.level.Level",
                "net.minecraft.nbt.CompoundTag",
                "net.neoforged.neoforge.energy.IEnergyStorage",
        };
        for (String name : minecraftClasses) {
            boolean loadable = true;
            try {
                Class.forName(name, false, CriticalDefectRegressionTest.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError expected) {
                loadable = false;
            }
            Check.isFalse(loadable,
                    "%s is on core/'s classpath — the physics module is supposed to be pure "
                            + "Java, and a test here that touches Minecraft is a test that "
                            + "belongs in mod/", name);
        }

        Check.note("NOT COVERED HERE, and not coverable here: the multiblock rebuild on chunk "
                + "load, the recirculation pump's energy capability, and the CMD_ROD_STEP "
                + "overflow in the peripheral command path. All three are mod-side, all three "
                + "need net.minecraft on the classpath, and none of them is exercised by any "
                + "test in core/. They need a mod-side test source set; asserting them from "
                + "here is not possible, and claiming otherwise would be worse than the gap.");
        Check.note("Covered here instead: the scram latch across a save (test01), peak fuel "
                + "temperature monotonicity across a reload (test02), IRM range switch "
                + "persistence (test03), position-dependent rod worth (test04), vessel mass "
                + "conservation at both pressure limits (test05), and the low-pressure level "
                + "indication (test06).");
    }
}
