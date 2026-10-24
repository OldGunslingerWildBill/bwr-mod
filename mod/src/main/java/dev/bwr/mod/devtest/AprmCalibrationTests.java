package dev.bwr.mod.devtest;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Player APRM calibration through the real command path: a steady, fuelled, formed 15 x 15 reactor is
 * deliberately miscalibrated by a signal command, recalibrated by hand from the published readings (the heat
 * balance and the channel's own indication), and the indication reaches the heat balance within tolerance. The
 * gain is bounded, nothing recalibrates by itself, and the gain survives a save and a reload.
 *
 * <p>Fixture origin 175_840 / 175_840, {@code slot * 64} blocks apart in X (see {@link FcdTestSupport}); every
 * origin in {@code devtest} is distinct.
 *
 * <h2>The steady plant</h2>
 * The fixture has no recirculation pumps, so once the world ticks the core settles on natural circulation, about
 * a fifth of rated power. A heat balance is only meaningful on a steady plant, so the body first stages the core at
 * 80 % and then runs it, directly on the core, for 120 simulated seconds while a stand-in turbine and feedwater
 * pump hold steam flow and feedwater flow at the boil-off the power can sustain (the same arithmetic as
 * {@code TransientHarness.PlantOperator.boilOffFeedforwardKgPerS}). At the end power moves about 0.05 % per
 * second and pressure is flat; the flows stay where they were left, nothing in the fixture writes either, and
 * the logged numbers show the heat balance still agreeing with the core for the whole test. Nothing here touches
 * a gain.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AprmCalibrationTests {
    static final int ORIGIN = 175_840;
    private static final int TIMEOUT = 700;
    private static final int SETTLE_STEPS = 2400;

    private static double read(SignalProvider p, String key) {
        return p.read(key).orElse(Double.NaN);
    }

    /** Releases the forced chunks even if the test times out and so never reaches a step's own cleanup. */
    private static void releaseOnTimeout(FcdTestSupport.Run run) {
        run.helper.runAfterDelay(TIMEOUT - 3, run::release);
    }

    /** One tick of the stand-in turbine and feedwater pump, then of the core. No recirculation hardware exists. */
    private static void plantStep(ReactorCore core) {
        core.setRecirculationFlowFraction(0.0);
        double rise = Saturation.vapourEnthalpyKJPerKg(core.getPressurePsia())
                - core.getPressureVessel().getFeedwaterEnthalpyKJPerKg();
        double flow = core.getThermalPowerMW() * 1000.0 / rise;
        core.setTurbineSteamFlowKgPerS(flow);
        core.setFeedwaterFlowKgPerS(flow);
        core.step();
    }

    private static void atSteadyPower(FcdTestSupport.Run run) {
        ReactorControllerBlockEntity be = run.be;
        for (int position : be.corePositions()) {
            be.loadAssembly(position, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
        }
        ReactorCore core = be.core();
        core.initialiseAtTotalPowerFraction(0.8);
        be.rods().setCore(core);
        for (int i = 0; i < SETTLE_STEPS; i++) {
            plantStep(core);
        }
    }

    /** One line of evidence per step: the logged numbers are the proof the steady state held. */
    private static void log(String step, FcdTestSupport.Run run) {
        SignalProvider p = SignalProviders.of(run.be);
        System.out.printf(java.util.Locale.ROOT,
                "APRM CALIBRATION GAMETEST %s: power %.4f (%.3f MW), heat balance %.3f%% = %.3f MW, APRM 1-6 %.2f %.2f %.2f %.2f %.2f %.2f, gains 3/5 %.4f %.4f, %.1f psig%n",
                step, read(p, "power"), read(p, "thermal_mw"), read(p, "heat_balance_percent"), read(p, "heat_balance_mw"),
                read(p, "aprm_1_percent"), read(p, "aprm_2_percent"), read(p, "aprm_3_percent"), read(p, "aprm_4_percent"),
                read(p, "aprm_5_percent"), read(p, "aprm_6_percent"), read(p, "aprm_3_gain"), read(p, "aprm_5_gain"),
                read(p, "pressure"));
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = TIMEOUT)
    public static void miscalibratedAprmsReachHeatBalanceThroughTheCommandPathAndPersist(GameTestHelper h) {
        double[] baseline = new double[6];
        FcdTestSupport.Run.start(h, ORIGIN, 0, 15, 15, run -> {
            releaseOnTimeout(run);
            atSteadyPower(run);

            run.then(10, () -> {
                log("baseline", run);
                SignalProvider p = SignalProviders.of(run.be);
                double hb = read(p, "heat_balance_percent");
                double truth = read(p, "power") * 100.0;
                h.assertTrue(truth > 10.0 && truth < 40.0, "fixture is not on natural circulation power: " + truth + "%");
                h.assertTrue(Math.abs(hb - truth) < 0.5, "heat balance " + hb + "% disagrees with the steady core's " + truth + "%");
                h.assertTrue(Math.abs(read(p, "heat_balance_mw") / dev.bwr.core.PhysicalConstants.RATED_THERMAL_MW * 100.0 - hb) < 1e-9,
                        "heat_balance_mw and heat_balance_percent disagree");
                for (int n = 1; n <= 6; n++) {
                    baseline[n - 1] = read(p, "aprm_" + n + "_percent");
                    double error = read(p, "aprm_" + n + "_heat_balance_error");
                    h.assertTrue(Math.abs(error - (baseline[n - 1] - hb)) < 1e-9, "aprm_" + n + "_heat_balance_error is not indicated minus heat balance");
                    h.assertTrue(Math.abs(error) < 0.25 * hb, "APRM " + n + " starts " + error + " points from the heat balance");
                    h.assertTrue(read(p, "aprm_" + n + "_gain") == 1.0, "APRM " + n + " gain is not 1.0 as built");
                }
                // The spec is a bounded NUMBER command and the signal list carries all of it.
                var spec = p.commands().stream().filter(c -> c.key().equals("aprm_3_gain")).findFirst();
                h.assertTrue(spec.isPresent() && spec.get().min() == 0.5 && spec.get().max() == 2.0, "aprm_3_gain command spec missing or unbounded");
                h.assertTrue(p.commands().stream().noneMatch(c -> c.key().equals("aprm_7_gain")), "a seventh APRM command exists");

                // Refusals change nothing.
                for (double bad : new double[]{0.4, 2.5, 0.0, -1.0, 100.0, Double.NaN, Double.POSITIVE_INFINITY}) {
                    h.assertTrue(!p.command("aprm_3_gain", bad), "aprm_3_gain accepted " + bad);
                }
                h.assertTrue(!p.command("aprm_7_gain", 1.0) && !p.command("aprm_0_gain", 1.0), "a missing APRM accepted a gain");
                h.assertTrue(read(p, "aprm_3_gain") == 1.0, "a refused command changed the gain");

                // Miscalibrate two channels in opposite directions.
                h.assertTrue(p.command("aprm_3_gain", 0.80), "in-range gain refused");
                h.assertTrue(p.command("aprm_5_gain", 1.45), "in-range gain refused");
                h.assertTrue(read(p, "aprm_3_gain") == 0.80 && run.be.core().getAveragePowerRangeGain(2) == 0.80, "gain did not reach the core");
            });

            run.then(20, () -> {
                log("miscalibrated", run);
                SignalProvider p = SignalProviders.of(run.be);
                double hb = read(p, "heat_balance_percent");
                h.assertTrue(read(p, "aprm_3_heat_balance_error") < -0.2 * hb, "APRM 3 should read well low, error " + read(p, "aprm_3_heat_balance_error"));
                // Re-baselined (vessel height physics): the margin follows the plant's actual power. It was "error > 0.2 x heat balance",
                // which only holds when the fixture's natural-circulation power is high enough (21.4 % then, 15.4 % now: 3.014 against
                // 3.108). The gain adds 45 % of what the channel read at baseline, so the error must have risen from its baseline
                // value by at least half of that.
                h.assertTrue(read(p, "aprm_5_heat_balance_error") > (baseline[4] - hb) + 0.5 * 0.45 * baseline[4], "APRM 5 should read well high, error " + read(p, "aprm_5_heat_balance_error"));
                // The procedure, by hand: new gain = gain * heat balance / indicated.
                double gain = read(p, "aprm_3_gain") * hb / read(p, "aprm_3_percent");
                h.assertTrue(p.command("aprm_3_gain", gain), "computed calibration " + gain + " refused");
            });

            run.then(20, () -> {
                log("recalibrated 3", run);
                SignalProvider p = SignalProviders.of(run.be);
                double hb = read(p, "heat_balance_percent");
                double error = read(p, "aprm_3_heat_balance_error");
                h.assertTrue(Math.abs(error) < 0.5, "recalibrated APRM 3 still " + error + " points from the heat balance");
                // Nothing recalibrated the other miscalibrated channel; the untouched ones did not move.
                h.assertTrue(read(p, "aprm_5_heat_balance_error") > (baseline[4] - hb) + 0.5 * 0.45 * baseline[4], "APRM 5 was recalibrated by something other than the player");
                h.assertTrue(read(p, "aprm_5_gain") == 1.45, "APRM 5 gain changed without a command");
                for (int n : new int[]{1, 2, 4, 6}) {
                    h.assertTrue(read(p, "aprm_" + n + "_gain") == 1.0, "APRM " + n + " gain changed");
                    h.assertTrue(Math.abs(read(p, "aprm_" + n + "_percent") - baseline[n - 1]) < 0.5, "APRM " + n + " moved when APRM 3 was calibrated");
                }
                if (BwrMod.isComputerCraftPresent()) {
                    AprmCalibrationCcChecks.recalibrateFive(h, run.be, p);
                } else {
                    double gain = read(p, "aprm_5_gain") * hb / read(p, "aprm_5_percent");
                    h.assertTrue(p.command("aprm_5_gain", gain), "computed calibration " + gain + " refused");
                }
            });

            run.then(20, () -> {
                log("recalibrated 5", run);
                SignalProvider p = SignalProviders.of(run.be);
                for (int n : new int[]{3, 5}) {
                    h.assertTrue(Math.abs(read(p, "aprm_" + n + "_heat_balance_error")) < 0.5,
                            "recalibrated APRM " + n + " still " + read(p, "aprm_" + n + "_heat_balance_error") + " points from the heat balance");
                }
                double g3 = read(p, "aprm_3_gain");
                double g5 = read(p, "aprm_5_gain");
                h.assertTrue(g3 > 0.9 && g3 < 1.5 && g5 > 0.9 && g5 < 1.5, "calibrated gains implausible: " + g3 + ", " + g5);
                run.scratch = g3;
                // A save carries the gains, and the reload brings them back exactly.
                var saved = run.be.saveWithFullMetadata(run.level.registryAccess());
                ReactorControllerBlockEntity restored = FcdRodTests.reload(run, saved);
                h.assertTrue(restored.isFormed(), "restored reactor is not formed");
                SignalProvider q = SignalProviders.of(restored);
                h.assertTrue(read(q, "aprm_3_gain") == g3, "APRM 3 gain lost on reload: " + read(q, "aprm_3_gain") + " vs " + g3);
                h.assertTrue(read(q, "aprm_5_gain") == g5, "APRM 5 gain lost on reload: " + read(q, "aprm_5_gain") + " vs " + g5);
                for (int n : new int[]{1, 2, 4, 6}) {
                    h.assertTrue(read(q, "aprm_" + n + "_gain") == 1.0, "APRM " + n + " gain changed on reload");
                }
            });

            run.then(10, () -> {
                // The saved state carries no steam or feedwater flow (outlets and pumps write those each tick), so
                // the restored plant is steadied again by the same stand-in turbine before anything is compared.
                for (int i = 0; i < SETTLE_STEPS / 2; i++) {
                    plantStep(run.be.core());
                }
                log("after reload and 60 s settle", run);
                SignalProvider q = SignalProviders.of(run.be);
                h.assertTrue(read(q, "aprm_3_gain") == run.scratch, "APRM 3 gain drifted after reload");
                for (int n : new int[]{3, 5}) {
                    double error = read(q, "aprm_" + n + "_heat_balance_error");
                    h.assertTrue(Math.abs(error) < 0.75, "after reload APRM " + n + " is " + error + " points from the heat balance");
                }
            });
        });
    }
}
