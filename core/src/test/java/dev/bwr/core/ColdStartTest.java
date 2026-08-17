package dev.bwr.core;

import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.harness.TransientHarness;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A reactor that has just been built, and the heat-up out of it.
 *
 * <h2>The defect these are the regression for</h2>
 * The first in-world playtest formed a vessel with <b>no fuel in it at all</b>
 * and the control panel read 1025 psig, 287 degC coolant, 287 degC fuel and
 * 287 degC peak clad: hot standby, on a plant that contained nothing that could
 * have heated it and had never been asked to. Formation ran
 * {@link ReactorCore#initialiseHotShutdown()}, which is a legitimate starting
 * point for a scenario that begins hot and the wrong one for a plant that begins
 * at all.
 *
 * <p>Hot standby is a condition an operator <i>reaches</i>. The vessel holds
 * about 190 GJ of sensible heat at rated pressure and somebody has to put it
 * there, so a plant nobody has heated is at atmospheric pressure with its water
 * at the boiling point for atmospheric pressure — see
 * {@link PressureVessel#initialiseCold()} for what "cold" can and cannot mean in
 * a vessel model whose temperature is its pressure.
 *
 * <h2>What the heat-up test is really checking</h2>
 * That the fix did not quietly become a different judgement. It would have been
 * easy to make a cold plant warm itself up, or to leave a floor under pressure,
 * or to have formation decide the plant "should" be at hot standby after a while.
 * Every one of those is the model deciding what condition the plant ought to be
 * in, which is the line this project does not cross. So there are two tests
 * side by side: a cold plant left alone stays exactly where it was put, and a
 * cold plant whose rods a player withdraws climbs to rated pressure on its own
 * heat with no valve opening itself along the way.
 */
public final class ColdStartTest {

    private ColdStartTest() {
    }

    /** A calibrated installed source, matching what the mod fits to a built plant. */
    private static final double SOURCE_PER_SECOND = 4.0e-11;

    private static ReactorCore coldCore() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.setNeutronSourceStrengthPerSecond(SOURCE_PER_SECOND);
        core.initialiseCold();
        return core;
    }

    /**
     * A plant that has never operated: atmospheric dome pressure, water at the
     * boiling point for it, fuel and clad at the same temperature, no irradiation
     * history, and the whole core still covered.
     */
    public static void test01_aNewlyBuiltPlantIsColdAndAtAtmosphericPressure() {
        ReactorCore core = coldCore();
        double saturationC = Saturation.temperatureCelsiusFromPsig(0.0);

        Check.note("cold: %.2f psig, coolant %.2f degC, fuel %.2f degC, peak clad %.2f degC",
                core.getPressurePsig(), core.getCoolantTemperatureC(), core.getFuelTemperatureC(),
                core.getPeakCladTemperatureC());
        Check.note("level: indicated %.2f in, two-phase %.2f in, collapsed %.2f in, "
                        + "%.0f kg of water, uncovered %.3f",
                core.getIndicatedLevelIn(), core.getTwoPhaseLevelIn(), core.getCollapsedLevelIn(),
                core.getLiquidMassKg(), core.getUncoveredFuelFraction());

        Check.exactly(PressureVessel.COLD_SHUTDOWN_PRESSURE_PSIG, core.getPressurePsig(),
                "a plant nobody has heated is at atmospheric pressure");
        Check.exactly(saturationC, core.getCoolantTemperatureC(),
                "coolant temperature is saturation at the dome pressure and nothing else");
        Check.exactly(saturationC, core.getFuelTemperatureC(), "fuel sits at coolant temperature");
        Check.exactly(saturationC, core.getCladTemperatureC(), "clad sits at coolant temperature");

        // The playtest's actual complaint, as a number: 287.44 degC is T_sat at
        // rated pressure, and a plant that has never been pressurised must not be
        // reporting it anywhere — including in the monotonic peak, which survives
        // an initialisation unless it is explicitly cleared.
        double ratedSaturationC =
                Saturation.temperatureCelsiusFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
        Check.lessThan(ratedSaturationC - 100.0, core.getFuelTemperatureC(),
                "fuel temperature on a cold plant");
        Check.lessThan(ratedSaturationC - 100.0, core.getPeakCladTemperatureC(),
                "peak clad temperature on a plant that has never been hot");
        Check.exactly(saturationC, core.getPeakCladTemperatureC(),
                "the peak clad record of a plant that has never operated is where it is now");
        Check.exactly(saturationC, core.getPeakFuelTemperatureC(),
                "the peak fuel record of a plant that has never operated is where it is now");

        // No history of any kind.
        Check.exactly(0.0, core.getDecayHeatFraction(), "decay heat with no irradiation history");
        Check.exactly(0.0, core.getOxidationFraction(), "cladding oxidation on new fuel");
        Check.exactly(0.0, core.getHydrogenGeneratedKg(), "hydrogen generated by a new plant");
        Check.exactly(0.0, core.getAverageBurnupMwdPerTonne(), "burnup on a new core");
        Check.exactly(0.0, core.getBoronPpm(), "dissolved boron in a new plant");
        Check.exactly(0.0, core.getVoidFraction(), "void in water that is not boiling");

        // Every valve shut and every pump off except recirculation, which sits on
        // its low-speed startup detent exactly as hot shutdown leaves it.
        Check.exactly(0.0, core.getCommandedSteamFlowKgPerS(), "steam leaving a shut vessel");
        Check.exactly(0.0, core.getFeedwaterFlowKgPerS(), "feedwater into a shut vessel");
        Check.exactly(0.0, core.getInjectionFlowKgPerS(), "injection into a shut vessel");
        Check.exactly(PhysicalConstants.RECIRC_LOW_SPEED_FRACTION, core.getCoreFlowFraction(),
                "recirculation on its low-speed startup detent");

        // Shut down, and by a wide margin: every rod is in, and cold clean is the
        // most reactive a shut core gets, so if the margin holds here it holds.
        Check.lessThan(0.0, core.getReactivityDkOverK(), "a core with every rod in is subcritical");
        Check.lessThan(-5.0, core.getReactivityDollars(), "shutdown margin, cold and clean");
        Check.exactly(core.getControlRodCount(), core.getChargedAccumulatorCount(),
                "every accumulator charged on a new plant");
        Check.isFalse(core.isScramActive(), "no scram signal on a plant nobody has scrammed");

        // Fuel covered. Cold water is denser, so the same inventory stands lower
        // than it will when hot — 119 in below instrument zero against 8 above —
        // and the level instrument reads high on top of that because its variable
        // leg is full of cold water and it was calibrated for hot.
        Check.exactly(0.0, core.getUncoveredFuelFraction(), "uncovered fuel in a full vessel");
        Check.lessThan(0.0, core.getCollapsedLevelIn(),
                "collapsed level of a cold vessel holding the hot normal inventory");
        Check.greaterThan(PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN, core.getCollapsedLevelIn(),
                "the water still covers the fuel");
        Check.greaterThan(core.getCollapsedLevelIn(), core.getIndicatedLevelIn(),
                "a hot-calibrated DP cell reads high on a cold vessel");
    }

    /**
     * <b>The player's report, as a test.</b> A vessel with no fuel in it makes no
     * heat and no pressure, however long it is left running.
     *
     * <p>An empty lattice has no k-infinity, so the reactivity balance is many
     * dollars negative and the source-driven subcritical equilibrium is a flux of
     * no consequence. Nothing else in the model is a heat source with the valves
     * shut, the feedwater off and no fission product inventory — so the pressure
     * has to sit exactly where it was put.
     */
    public static void test02_anUnfuelledCoreMakesNoHeatAndNoPressure() {
        CoreConfig config = new CoreConfig();
        ReactorCore core = new ReactorCore(config, new CoreLoading(ReactorCore.DEFAULT_LATTICE_WIDTH));
        core.setNeutronSourceStrengthPerSecond(SOURCE_PER_SECOND);
        core.initialiseCold();

        double pressureAtStart = core.getPressurePsig();
        double fuelAtStart = core.getFuelTemperatureC();
        double massAtStart = core.getLiquidMassKg();
        Check.note("unfuelled core: k-inf %.4f, rho %+.5f dk/k (%.2f$), %.2f psig, %.2f degC",
                core.getAggregateKInfinity(), core.getReactivityDkOverK(),
                core.getReactivityDollars(), pressureAtStart, core.getFuelTemperatureC());

        double worstPressure = pressureAtStart;
        double worstPower = 0.0;
        for (int i = 0; i < 20 * 600; i++) { // 600 s
            core.step();
            worstPressure = Math.max(worstPressure, core.getPressurePsig());
            worstPower = Math.max(worstPower, core.getTotalPowerFractionOfRated());
        }

        Check.note("after 600 s: %.6f psig (peak %.6f), %.4f MWth (peak %.3e of rated), "
                        + "fuel %.4f degC, %.1f kg",
                core.getPressurePsig(), worstPressure, core.getThermalPowerMW(), worstPower,
                core.getFuelTemperatureC(), core.getLiquidMassKg());

        Check.absolute(pressureAtStart, core.getPressurePsig(), 1.0e-9,
                "dome pressure of an unfuelled vessel after 600 s");
        Check.absolute(pressureAtStart, worstPressure, 1.0e-9,
                "the highest pressure an unfuelled vessel reached");
        Check.absolute(0.0, core.getPressureRateOfChangePsiPerSecond(), 1.0e-9,
                "rate of change of pressure with no heat source");
        Check.lessThan(1.0e-9, worstPower, "thermal power an empty lattice produced");
        Check.absolute(fuelAtStart, core.getFuelTemperatureC(), 1.0e-6,
                "fuel temperature with nothing heating it");
        Check.absolute(massAtStart, core.getLiquidMassKg(), 1.0e-6,
                "water inventory with nothing boiling it");
        // Not exactly zero, and it should not be: the source-driven flux is a
        // real if utterly negligible power, so the void model answers it with a
        // real if utterly negligible void — 4e-246 of the channel.
        Check.lessThan(1.0e-12, core.getVoidFraction(), "void in a vessel that is not boiling");
    }

    /**
     * A fuelled cold plant, left entirely alone, is still exactly where it was
     * put two minutes later — pressure to the last bit, every rod where it stood.
     * Nothing warms this plant up on its own: deciding that a reactor ought to be
     * at hot standby is a judgement, and judgements belong in the player's Lua.
     */
    public static void test03_aColdPlantLeftAloneStaysCold() {
        ReactorCore core = coldCore();
        double pressureAtStart = core.getPressurePsig();
        int[] rodsAtStart = core.getRodNotchIndices();

        for (int i = 0; i < 20 * 120; i++) {
            core.step();
        }

        Check.note("120 s untouched: %.6f psig, %.4f degC, %.3e of rated, rods still at notch %d",
                core.getPressurePsig(), core.getCoolantTemperatureC(),
                core.getTotalPowerFractionOfRated(), core.getRodNotchIndex(0));
        Check.absolute(pressureAtStart, core.getPressurePsig(), 1.0e-9,
                "dome pressure of a cold plant nobody touched");
        Check.arraysExactly(rodsAtStart, core.getRodNotchIndices(),
                "no rod moves without a demand behind it");
        Check.lessThan(1.0e-9, core.getTotalPowerFractionOfRated(),
                "thermal power of a shut-down core with every rod in");
        Check.exactly(0.0, core.getCommandedSteamFlowKgPerS(), "no valve opened by itself");
        Check.exactly(0.0, core.getFeedwaterFlowKgPerS(), "no pump started by itself");
    }

    /**
     * <b>The heat-up.</b> A player withdrawing rods against a shut vessel takes
     * the plant from atmospheric pressure to rated on its own fission heat, and
     * the level rises through the normal band as the water expands.
     *
     * <p>The loop below is the stand-in for the player's control program. It
     * polls total thermal power once a second — as often as a Lua program gets to
     * look — and indexes a handful of drives a notch at a time to hold it near
     * eight per cent, withdrawing three rods at a time and inserting eight,
     * because an ascension needs a firmer hand on the way down than on the way up
     * once the void starts collapsing under the rising pressure. It touches
     * nothing but the public actuators, because there is nothing else to touch:
     * no warm-up mode, no pressure controller, no automatic anything. That is as
     * much the subject of this test as the pressure is.
     *
     * <p>It takes about 25 minutes of plant time, which is faster than a real
     * plant would ever do it — a BWR heat-up is held to about 100 degF an hour to
     * limit thermal stress in the vessel wall and flanges. Nothing here stops it,
     * and nothing should: this model does not track vessel thermal stress, so a
     * rate limit would be a judgement invented to look responsible rather than a
     * consequence of anything the model knows.
     */
    public static void test04_theOperatorHeatsThePlantAndNothingElseDoes() {
        ReactorCore core = coldCore();
        TransientHarness.RodSequencer sequencer = new TransientHarness.RodSequencer(core);
        double collapsedAtStart = core.getCollapsedLevelIn();
        double targetPowerFraction = 0.08;

        int ticks = 0;
        double worstUncovered = 0.0;
        double worstPower = 0.0;
        boolean reachedRated = false;
        for (int i = 0; i < 20 * 5400; i++) { // up to 90 minutes of plant time
            if (i % 20 == 0) {
                double power = core.getTotalPowerFractionOfRated();
                if (power < targetPowerFraction) {
                    // More rods at a time while the core is still deep, because a
                    // notch down there is worth very little.
                    sequencer.withdrawOneNotch(power < 1.0e-8 ? 6 : 3);
                } else if (power > 1.05 * targetPowerFraction) {
                    sequencer.insertOneNotch(8);
                }
            }
            core.step();
            ticks++;
            worstUncovered = Math.max(worstUncovered, core.getUncoveredFuelFraction());
            worstPower = Math.max(worstPower, core.getTotalPowerFractionOfRated());
            if (core.getPressurePsig() >= PhysicalConstants.RATED_DOME_PRESSURE_PSIG) {
                reachedRated = true;
                break;
            }
        }

        Check.note("heat-up: %.0f s of plant time to %.1f psig / %.2f degC, %.2f%% power "
                        + "(peak %.2f%%), mean notch %.1f, void %.4f",
                ticks * core.getConfig().tickSeconds, core.getPressurePsig(),
                core.getCoolantTemperatureC(), 100.0 * core.getTotalPowerFractionOfRated(),
                100.0 * worstPower, TransientHarness.averageNotch(core), core.getVoidFraction());
        Check.note("level: collapsed %.2f -> %.2f in, indicated %.2f in, %.0f kg of water, "
                        + "worst uncovered fraction %.4f",
                collapsedAtStart, core.getCollapsedLevelIn(), core.getIndicatedLevelIn(),
                core.getLiquidMassKg(), worstUncovered);

        Check.isTrue(reachedRated,
                "a player withdrawing rods against a shut vessel must be able to reach rated "
                        + "pressure; got %.1f psig after %.0f s", core.getPressurePsig(),
                ticks * core.getConfig().tickSeconds);
        Check.greaterThan(collapsedAtStart, core.getCollapsedLevelIn(),
                "the water expands as it heats, so the collapsed level rises");
        Check.exactly(0.0, worstUncovered, "the fuel stays covered through a heat-up");

        // Nothing opened on the plant's behalf on the way up. The vessel went past
        // every SRV spring setting in PhysicalConstants without any of them
        // lifting, because in this mod they are player-actuated and the model has
        // no opinion about when they should be.
        Check.exactly(0.0, core.getReliefSteamFlowKgPerS(), "no relief valve may open by itself");
        Check.exactly(0.0, core.getFeedwaterFlowKgPerS(), "no feedwater pump may start by itself");
        Check.isFalse(core.isScramActive(), "nothing may scram the plant on its own");
    }

    /**
     * Hot shutdown still starts hot, and cold and hot shutdown differ in the
     * vessel and in nothing else.
     *
     * <p>Both entry points run one shared body, so this is the test that the
     * sharing did not change what {@link ReactorCore#initialiseHotShutdown()}
     * means — the acceptance suite and {@code TransientHarness} both stage plants
     * that begin hot, and they must keep getting exactly that — and that
     * {@link ReactorCore#initialiseCold()} is a statement about the vessel rather
     * than a second, subtly different idea of what a shut-down reactor is.
     */
    public static void test05_hotShutdownStillStartsHotAndDiffersOnlyInTheVessel() {
        ReactorCore hot = new ReactorCore(new CoreConfig());
        hot.setNeutronSourceStrengthPerSecond(SOURCE_PER_SECOND);
        hot.initialiseHotShutdown();
        ReactorCore cold = coldCore();

        Check.note("hot %.1f psig / %.2f degC / level %.2f in; cold %.1f psig / %.2f degC / "
                        + "level %.2f in",
                hot.getPressurePsig(), hot.getCoolantTemperatureC(), hot.getIndicatedLevelIn(),
                cold.getPressurePsig(), cold.getCoolantTemperatureC(), cold.getIndicatedLevelIn());

        Check.exactly(PhysicalConstants.RATED_DOME_PRESSURE_PSIG, hot.getPressurePsig(),
                "hot shutdown is at rated dome pressure");
        Check.exactly(Saturation.temperatureCelsiusFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG),
                hot.getCoolantTemperatureC(), "hot shutdown is at saturation for rated pressure");
        Check.exactly(PressureVessel.NORMAL_COLLAPSED_LEVEL_IN, hot.getCollapsedLevelIn(),
                "hot shutdown is at the normal collapsed level");
        Check.exactly(hot.getLiquidMassKg(), cold.getLiquidMassKg(),
                "both hold the plant's nominal coolant inventory; only its density differs");

        // Everything that is not the vessel is the same shut-down plant.
        Check.arraysExactly(hot.getRodNotchIndices(), cold.getRodNotchIndices(),
                "rod pattern of a shut-down plant");
        Check.arraysExactly(hot.getAccumulatorCharges(), cold.getAccumulatorCharges(),
                "accumulator charge of a shut-down plant");
        Check.exactly(hot.getDecayHeatFraction(), cold.getDecayHeatFraction(),
                "decay heat of a plant with no irradiation history");
        Check.exactly(hot.getXenonAtomsPerCm3(), cold.getXenonAtomsPerCm3(),
                "xenon of a plant that has never made any");
        Check.exactly(hot.getBoronPpm(), cold.getBoronPpm(), "boron of a plant nobody has injected");
        Check.exactly(hot.getCoreFlowFraction(), cold.getCoreFlowFraction(),
                "recirculation lineup of a shut-down plant");
        Check.exactly(hot.getCommandedSteamFlowKgPerS(), cold.getCommandedSteamFlowKgPerS(),
                "steam flow of a shut-down plant");
        Check.exactly(hot.getVoidFraction(), cold.getVoidFraction(),
                "void in a plant that is not boiling");
        Check.exactly(hot.getElapsedSeconds(), cold.getElapsedSeconds(),
                "elapsed time on the clock of a freshly initialised plant");

        // And the cold one really is the colder plant, in every quantity that
        // pressure drags along with it.
        Check.lessThan(hot.getPressurePsig(), cold.getPressurePsig(), "cold dome pressure");
        Check.lessThan(hot.getCoolantTemperatureC(), cold.getCoolantTemperatureC(),
                "cold coolant temperature");
        Check.lessThan(hot.getFuelTemperatureC(), cold.getFuelTemperatureC(),
                "cold fuel temperature");
        Check.lessThan(hot.getCollapsedLevelIn(), cold.getCollapsedLevelIn(),
                "denser water stands lower");
        // Cold and clean is the most reactive a shut core is: the Doppler term is
        // measured from a fuel temperature 188 degC lower, and it is negative.
        Check.greaterThan(hot.getReactivityDkOverK(), cold.getReactivityDkOverK(),
                "a cold core is the more reactive of the two, and both are far subcritical");
        Check.lessThan(0.0, cold.getReactivityDkOverK(), "still shut down");
    }

    /**
     * <b>Nothing published goes NaN, and nothing that cannot be negative is.</b>
     *
     * <p>Every correlation in the thermal model is a power law fitted around the
     * operating point, and the cold path runs all of them at a seventieth of the
     * pressure they were fitted at, with the fuel and the coolant at the same
     * temperature and a boiling boundary at neither end of the core. That is a
     * corner nothing else in the suite visits, and it is exactly the shape of
     * corner where a {@code 0/0}, a {@code log(0)} or a square root of a small
     * negative gets in — quietly, because a NaN in a lagged state propagates for
     * a while before it reaches a panel.
     *
     * <p>So this sweeps the published surface rather than a chosen handful of it:
     * every no-argument {@code double} getter on {@link ReactorCore}, found by
     * reflection so a measurement added tomorrow is covered today, sampled through
     * a whole heat-up from atmospheric to rated. A hand-written list of getters
     * would eventually stop matching the class, which is the failure mode the
     * persistence comparator was rewritten to avoid.
     *
     * <p>NaN is the universal assertion — no reading may ever be one. Infinity is
     * <i>not</i> a failure: an indicated period of positive infinity is a period
     * meter sitting on the centre of its scale and is the correct reading for a
     * steady core. Sign is asserted only for the quantities that physically have
     * one, named individually below.
     */
    public static void test06_noPublishedMeasurementGoesNaNThroughAHeatUp() {
        ReactorCore core = coldCore();
        TransientHarness.RodSequencer sequencer = new TransientHarness.RodSequencer(core);

        List<Method> readings = new ArrayList<>();
        for (Method method : ReactorCore.class.getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getReturnType() == double.class
                    && method.getName().startsWith("get")
                    && !Modifier.isStatic(method.getModifiers())) {
                readings.add(method);
            }
        }
        readings.sort(Comparator.comparing(Method::getName));
        Check.isTrue(readings.size() > 20,
                "reflection must have found the published measurement surface, found %d"
                        + " — a sweep over nothing passes trivially", readings.size());

        // Quantities with a physical floor of zero. Everything absent from this
        // set is legitimately signed — reactivity, levels, rates of change, the
        // period meters — and is only checked for NaN.
        Set<String> mustNotBeNegative = new TreeSet<>(List.of(
                "getPressurePsia", "getLiquidMassKg", "getCoolantTemperatureC",
                "getFuelTemperatureC", "getCladTemperatureC", "getPeakCladTemperatureC",
                "getPeakFuelTemperatureC", "getOxidationFraction", "getHydrogenGeneratedKg",
                "getZirconiumReactionPowerMW", "getVoidFraction", "getExitVoidFraction",
                "getExitQuality", "getBoilingBoundaryFraction", "getCoreInletSubcoolingKJPerKg",
                "getNeutronPowerFraction", "getDecayHeatFraction", "getTotalPowerFractionOfRated",
                "getThermalPowerMW", "getDecayHeatMW", "getBoronPpm", "getXenonAtomsPerCm3",
                "getIodineAtomsPerCm3", "getXenonFractionOfRatedEquilibrium",
                "getAverageBurnupMwdPerTonne", "getAggregateKInfinity", "getKEffectiveAllRodsOut",
                "getCoreFlowFraction", "getCoreFlowKgPerS", "getUncoveredFuelFraction",
                "getBreakSteamFlowKgPerS", "getBreakLiquidFlowKgPerS",
                "getDeliveredFeedwaterFlowKgPerS", "getHeatPerFissionScaleFactor",
                "getPromptLifetimeSeconds", "getBetaEffective", "getElapsedSeconds"));
        // Every name in that set has to still be a method, or the set is quietly
        // asserting nothing about a getter that has been renamed.
        Set<String> found = new TreeSet<>();
        for (Method method : readings) {
            found.add(method.getName());
        }
        List<String> stale = new ArrayList<>(mustNotBeNegative);
        stale.removeAll(found);
        Check.isTrue(stale.isEmpty(),
                "these are asserted non-negative but are no longer getters on ReactorCore, so "
                        + "the assertion covers nothing: %s", stale);

        double bottomTapIn = core.getPressureVessel().getBottomTapIn();
        int samples = 0;
        for (int i = 0; i < 20 * 2400; i++) { // up to 40 minutes of heat-up
            if (i % 20 == 0) {
                double power = core.getTotalPowerFractionOfRated();
                if (power < 0.08) {
                    sequencer.withdrawOneNotch(power < 1.0e-8 ? 6 : 3);
                } else if (power > 0.084) {
                    sequencer.insertOneNotch(8);
                }
            }
            core.step();

            if (i % 20 != 0) {
                continue;
            }
            samples++;
            for (Method method : readings) {
                double value = invoke(method, core);
                Check.isFalse(Double.isNaN(value),
                        "%s went NaN %.1f s into a heat-up at %.1f psig",
                        method.getName(), core.getElapsedSeconds(), core.getPressurePsig());
                if (mustNotBeNegative.contains(method.getName())) {
                    Check.isTrue(value >= 0.0,
                            "%s went negative (%.6g) %.1f s into a heat-up at %.1f psig",
                            method.getName(), value, core.getElapsedSeconds(),
                            core.getPressurePsig());
                }
            }
            // The level instrument cannot read below its own lower tap: the
            // variable leg has no column left to lose. It can and does read above
            // the upper tap on a cold vessel, because the water in the leg is
            // denser than the calibration assumed — see PressureVessel.
            Check.isTrue(core.getIndicatedLevelIn() >= bottomTapIn,
                    "indicated level %.2f in is below the instrument's own lower tap at %.2f in",
                    core.getIndicatedLevelIn(), bottomTapIn);
            if (core.getPressurePsig() >= PhysicalConstants.RATED_DOME_PRESSURE_PSIG) {
                break;
            }
        }

        Check.note("swept %d measurements at %d points from 0 psig to %.1f psig; "
                        + "coolant %.2f degC, void %.4f, indicated level %.2f in at the end",
                readings.size(), samples, core.getPressurePsig(), core.getCoolantTemperatureC(),
                core.getVoidFraction(), core.getIndicatedLevelIn());
        Check.greaterThan(100, samples, "the sweep must actually have run a heat-up");
        Check.greaterThan(PressureVessel.COLD_SHUTDOWN_PRESSURE_PSIG, core.getPressurePsig(),
                "the plant must have heated up during the sweep");
    }

    private static double invoke(Method method, ReactorCore core) {
        try {
            return (double) method.invoke(core);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read ReactorCore." + method.getName(), e);
        }
    }
}
