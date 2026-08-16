package dev.bwr.core.harness;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.instrument.IntermediateRangeMonitor;
import dev.bwr.core.instrument.NeutronDetector;
import dev.bwr.core.instrument.PeriodMeter;
import dev.bwr.core.instrument.SourceRangeMonitor;
import dev.bwr.core.kinetics.ReactivityBalance;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.VoidModel;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Standalone transient harness: runs reactor scenarios in plain Java and dumps
 * CSV to stdout. {@code SPEC.md} section 0 and the README both insist this exists
 * and gets used before a line of NeoForge is written, because a believable
 * startup, load change and scram curve are the thing that is actually hard, and
 * they are far cheaper to debug here than in a running world.
 *
 * <pre>
 *   java dev.bwr.core.harness.TransientHarness                 # every scenario
 *   java dev.bwr.core.harness.TransientHarness scram flow      # named scenarios
 *   java dev.bwr.core.harness.TransientHarness --list
 * </pre>
 *
 * <h2>Where the control logic lives, and why it lives here</h2>
 * {@link ReactorCore} has no pressure regulator, no level controller, no rod
 * sequencer and no scram logic, on purpose: the mod provides hardware and
 * physics, and the player provides control logic in CC:Tweaked. So this harness
 * has to provide it, and {@link PlantOperator} below is exactly that — a
 * stand-in for the player's Lua, written against the same public getters and
 * setters a peripheral would expose, using no privileged access of any kind.
 *
 * <p>That is not a limitation of the harness, it is the demonstration. Run any
 * scenario with the operator switched off and the plant misbehaves in an
 * instructive way: with steam demand fixed and no regulator, a rise in power
 * raises pressure, which collapses void, which adds reactivity, which raises
 * power. That loop diverges on a timescale of a minute or two, and it is real —
 * it is why every BWR has a pressure regulator and why a turbine trip without
 * bypass is an emergency. Nothing was added to the model to produce it and
 * nothing has been added to suppress it.
 */
public final class TransientHarness {

    private TransientHarness() {
    }

    /** Rated steam flow, kg/s, from the plant data. About 1941 kg/s. */
    public static final double RATED_STEAM_FLOW_KG_PER_S =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR * VoidModel.KG_PER_LB / 3600.0;

    /**
     * Installed startup neutron source strength, fraction-of-rated per second.
     *
     * <p>Sized, not guessed. The subcritical equilibrium is
     * {@code n = Lambda*S/|rho|}, the SRM chamber makes
     * {@link CoreConfig#srmCountsPerUnitPower} counts per second per unit of
     * fractional power, and a real BWR's source range monitors read a few tens of
     * counts per second on a shut-down core. Working backwards from a 4.10E+01
     * cps floor at the all-rods-in shutdown reactivity of this core, about
     * -0.107 dk/k, gives
     * <pre>   S = n*|rho|/Lambda = (41/4.1e13) * 0.1067 / 4.0e-5 = 2.67e-9</pre>
     *
     * <p><b>This is three orders of magnitude above {@link CoreConfig#neutronSource}.</b>
     * That default of 1.0e-12 per second puts a shut-down core at 0.015 cps,
     * which is two decades below the bottom of the SRM scale — the channel would
     * read 00.00 on a perfectly healthy plant and an approach to critical would
     * be unobservable until the core was nearly there. The count rate the SRM's
     * own calibration table is written around needs this value, so the harness
     * and the acceptance tests install it explicitly rather than quietly editing
     * the config a sibling module owns.
     */
    public static final double CALIBRATED_SOURCE_PER_SECOND = 2.67e-9;

    /** Scenario names, in the order {@code --list} prints them and a bare run executes them. */
    public static final String[] SCENARIOS = {
            "approach", "startup", "flow", "scram", "pressurisation"};

    public static void main(String[] args) {
        PrintStream out = System.out;
        if (args.length == 1 && ("--list".equals(args[0]) || "-l".equals(args[0]))) {
            for (String name : SCENARIOS) {
                out.println(name);
            }
            return;
        }
        String[] wanted = (args.length == 0) ? SCENARIOS : args;
        for (String name : wanted) {
            switch (name) {
                case "approach" -> approachToCritical(out);
                case "startup" -> startupAndPowerAscension(out);
                case "flow" -> flowLoadChange(out);
                case "scram" -> scramFromFullPower(out);
                case "pressurisation" -> pressurisationTransient(out);
                default -> {
                    System.err.println("unknown scenario: " + name);
                    System.err.println("known: " + String.join(", ", SCENARIOS));
                    System.exit(2);
                }
            }
        }
    }

    // ===============================================================
    // Scenario 1 — approach to critical
    // ===============================================================

    /**
     * Approach to critical from a shut-down core, by inverse count rate.
     *
     * <p>Rods are withdrawn in steps and the source range count rate is allowed
     * to settle after each. The count rate climbs hyperbolically, because the
     * subcritical equilibrium {@code n = Lambda*S/|rho|} goes to infinity as
     * {@code rho} goes to zero, and the reciprocal of that hyperbola —
     * {@code 1/M = CR_0/CR}, which is proportional to {@code |rho|} — falls
     * toward zero on something close to a straight line. Extrapolating it to the
     * axis predicts the critical rod position <i>before</i> the core gets there.
     * That is the entire technique, and it is available only because the point
     * kinetics carries a source term.
     *
     * <p>The step size shrinks as the extrapolation closes, and the dwell after
     * each step lengthens, for the reason a real operator does both: near
     * critical the multiplication is large, so the same notch is worth far more
     * counts, and the settling time of the subcritical multiplication grows as
     * {@code beta/(|rho|*lambda_eff)} — tens of seconds at a dollar out, minutes
     * at a few cents.
     */
    public static void approachToCritical(PrintStream out) {
        ReactorCore core = shutdownCore();
        PlantOperator operator = new PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(false);

        banner(out, "approach", "Approach to critical by inverse count rate, 1/M");
        // srm_inv_period_per_s, not srm_period_s: see logApproachRow.
        out.println("t_s,rods_avg_notch,rods_avg_label,rho_dkk,rho_dollars,n_fraction,"
                + "srm_cps,inverse_m,srm_status,srm_inv_period_per_s,pressure_psig,coolant_c");

        // Block withdrawal to a deeply subcritical starting pattern. The bottom
        // of the S-curve is shallow, so the first eight notches on every rod are
        // worth less than the four that follow them.
        core.setAllRodNotchDemand(8);
        runSeconds(core, operator, 90.0, null);

        double referenceCountRate = averageCountRate(core);
        RodSequencer sequencer = new RodSequencer(core);
        logApproachRow(out, core, referenceCountRate);

        for (int step = 0; step < 400; step++) {
            double inverseM = referenceCountRate / Math.max(1.0e-30, averageCountRate(core));
            if (inverseM < 0.02) {
                break;
            }
            int rodsThisStep = (inverseM > 0.30) ? 12 : (inverseM > 0.10 ? 4 : 1);
            double dwellSeconds = (inverseM > 0.30) ? 25.0 : (inverseM > 0.10 ? 60.0 : 120.0);

            sequencer.withdrawOneNotch(rodsThisStep);
            runSeconds(core, operator, dwellSeconds, null);
            logApproachRow(out, core, referenceCountRate);

            if (core.getReactivityDkOverK() > 0.0) {
                break; // critical, and the 1/M line said so a dozen steps ago
            }
        }
        out.printf(Locale.ROOT,
                "# critical reached at average notch %.2f, rho = %+.6f dk/k, SRM %.4E cps%n",
                averageNotch(core), core.getReactivityDkOverK(), averageCountRate(core));
    }

    /**
     * One row of the approach trace.
     *
     * <p>The period column carries the meter's <b>inverse period</b>, 1/T per
     * second, rather than the period itself. That is not cosmetic. A period meter
     * legitimately indicates an infinite period at steady state — it is the centre
     * of the scale, not an error — and {@code String.format("%.4E", infinity)}
     * renders the uppercase string {@code INFINITY}, which
     * {@code Double.parseDouble} rejects and which numeric CSV readers coerce to
     * an object column. Twenty of this scenario's rows and most of the startup
     * scenario's were unparseable that way. Inverse period is the movement's own
     * deflection unit, is finite and continuous through the centre — steady flux
     * is 0.0, not a discontinuity — and {@code PeriodMeter}'s javadoc already
     * recommends it for exactly this. Take the reciprocal if you want seconds.
     */
    private static void logApproachRow(PrintStream out, ReactorCore core, double referenceCountRate) {
        double countRate = averageCountRate(core);
        SourceRangeMonitor srm = core.getSourceRangeMonitor(0);
        out.printf(Locale.ROOT, "%.1f,%.3f,%.1f,%+.6e,%+.4f,%.6e,%.4E,%.5f,%s,%+.4E,%.1f,%.2f%n",
                core.getElapsedSeconds(), averageNotch(core), 2.0 * averageNotch(core),
                core.getReactivityDkOverK(), core.getReactivityDollars(),
                core.getNeutronPowerFraction(), countRate,
                referenceCountRate / Math.max(1.0e-30, countRate),
                srm.getStatus(), core.getSourceRangePeriodMeter(0).getInversePeriodPerSecond(),
                core.getPressurePsig(), core.getCoolantTemperatureC());
    }

    // ===============================================================
    // Scenario 2 — startup and power ascension
    // ===============================================================

    /**
     * Startup and power ascension from a shut-down core to rated power.
     *
     * <p>Twelve decades of flux, which is why there are three kinds of neutron
     * detector and why the operator hands over between them. The trace shows the
     * SRM count rate climbing until dead time starts eating it, the IRM being
     * ranged up by hand a detent at a time, and the APRM coming on scale in the
     * last half decade of the IRM's travel — with no gap anywhere in which no
     * instrument reads true.
     *
     * <p>Above a few percent the character of the plant changes completely.
     * Below it the core has no thermal feedback worth the name and power is
     * whatever the rods say; above it void and Doppler dominate, the period
     * shortens and lengthens on its own, and the operator's job stops being
     * "pull rods" and starts being "hold pressure and level while the physics
     * settles".
     */
    public static void startupAndPowerAscension(PrintStream out) {
        ReactorCore core = shutdownCore();
        PlantOperator operator = new PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(true);
        operator.setFlowControlEnabled(true);
        operator.setTargetTotalPowerFraction(1.0);

        banner(out, "startup", "Startup and power ascension, shutdown to rated");
        // inv_period_per_s, not period_s: see logApproachRow for why the period
        // columns carry the meter's deflection units rather than seconds.
        out.println("t_s,rods_avg_notch,rho_dkk,rho_dollars,n_fraction,decay_fraction,"
                + "total_percent,srm_cps,irm_range,irm_div,aprm_percent,inv_period_per_s,"
                + "pressure_psig,void,flow_fraction,fuel_c,level_in,steam_kgps,fw_kgps");

        // Long, because a real startup is. Two thousand seconds of rod withdrawal
        // to reach criticality, then twelve decades of flux at the hundred-second
        // period the procedure is written around: about eight more e-folds per
        // decade of power, and the last decade is slowest because that is where
        // void and Doppler start pushing back.
        double logInterval = 5.0;
        double nextLog = 0.0;
        double endTime = 14000.0;
        while (core.getElapsedSeconds() < endTime) {
            if (core.getElapsedSeconds() >= nextLog) {
                logStartupRow(out, core, operator);
                nextLog += logInterval;
            }
            operator.tick();
            core.step();
            if (core.getTotalPowerFractionOfRated() > 0.995 && operator.isPowerSettled()) {
                break;
            }
        }
        logStartupRow(out, core, operator);
        out.printf(Locale.ROOT,
                "# reached %.2f%% of rated in %.0f s, average notch %.2f, rho %+.6f dk/k%n",
                100.0 * core.getTotalPowerFractionOfRated(), core.getElapsedSeconds(),
                averageNotch(core), core.getReactivityDkOverK());
    }

    private static void logStartupRow(PrintStream out, ReactorCore core, PlantOperator operator) {
        IntermediateRangeMonitor irm = core.getIntermediateRangeMonitor(0);
        // total_percent is logged in scientific notation because this scenario
        // spans twelve decades of it and a fixed-point column would read 0.0000
        // for the first two thirds of the run.
        out.printf(Locale.ROOT,
                "%.1f,%.3f,%+.6e,%+.4f,%.6e,%.6e,%.6E,%.4E,%d,%.2f,%.4f,%+.4E,"
                        + "%.2f,%.5f,%.4f,%.1f,%.2f,%.2f,%.2f%n",
                core.getElapsedSeconds(), averageNotch(core), core.getReactivityDkOverK(),
                core.getReactivityDollars(), core.getNeutronPowerFraction(),
                core.getDecayHeatFraction(), 100.0 * core.getTotalPowerFractionOfRated(),
                averageCountRate(core), irm.getRange(), irm.getScaleDivisions(),
                core.getAveragePowerRangePercent(0), operator.indicatedInversePeriodPerSecond(),
                core.getPressurePsig(), core.getVoidFraction(), core.getCoreFlowFraction(),
                core.getFuelTemperatureC(), core.getIndicatedLevelIn(),
                core.getSteamGenerationKgPerS(), core.getFeedwaterFlowKgPerS());
    }

    // ===============================================================
    // Scenario 3 — load change on recirculation flow alone
    // ===============================================================

    /**
     * A load change made entirely on recirculation flow, with every rod standing
     * still.
     *
     * <p>This is the manoeuvre that makes a BWR a BWR. Cut the flow and the same
     * core power has less water to heat, so the coolant picks up more enthalpy
     * per kilogram, boils earlier and harder, and void fraction rises. Void
     * reactivity is negative, so power falls — until the reduced power makes less
     * void again and the loop settles at a new, lower power. Nothing was
     * commanded. No rod moved. The operator asked for less flow and the
     * neutronics decided what that was worth.
     *
     * <p>The trace also shows the two lags that make this a dynamical system
     * rather than an algebraic one: the pump flywheel on the way down, and the
     * channel transit between a change in boiling rate and the void that results.
     */
    public static void flowLoadChange(PrintStream out) {
        ReactorCore core = ratedCore();
        PlantOperator operator = new PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);

        banner(out, "flow", "Load change on recirculation flow alone, rods stationary");
        out.println("t_s,flow_demand,flow_actual,n_fraction,total_percent,void,exit_quality,"
                + "rho_dkk,rho_void,rho_doppler,fuel_c,pressure_psig,steam_kgps,"
                + "aprm_percent,rods_avg_notch");

        double dt = core.getConfig().tickSeconds;
        double logInterval = 2.0;
        double nextLog = 0.0;
        double rampStart = 60.0;
        double rampEnd = 120.0;
        double restoreStart = 360.0;
        double restoreEnd = 420.0;
        double endTime = 700.0;

        while (core.getElapsedSeconds() < endTime) {
            double t = core.getElapsedSeconds();
            if (t >= nextLog) {
                logFlowRow(out, core);
                nextLog += logInterval;
            }
            double demand;
            if (t < rampStart) {
                demand = 1.0;
            } else if (t < rampEnd) {
                demand = 1.0 - 0.30 * (t - rampStart) / (rampEnd - rampStart);
            } else if (t < restoreStart) {
                demand = 0.70;
            } else if (t < restoreEnd) {
                demand = 0.70 + 0.30 * (t - restoreStart) / (restoreEnd - restoreStart);
            } else {
                demand = 1.0;
            }
            core.setRecirculationFlowFraction(demand);
            operator.tick();
            core.step(dt);
        }
        logFlowRow(out, core);
    }

    private static void logFlowRow(PrintStream out, ReactorCore core) {
        ReactivityBalance.Breakdown breakdown = core.getReactivityBreakdown();
        out.printf(Locale.ROOT, "%.1f,%.4f,%.4f,%.6f,%.4f,%.5f,%.5f,%+.6e,%+.6e,%+.6e,"
                        + "%.1f,%.2f,%.2f,%.4f,%.3f%n",
                core.getElapsedSeconds(), core.getRecirculationFlowFractionDemand(),
                core.getCoreFlowFraction(), core.getNeutronPowerFraction(),
                100.0 * core.getTotalPowerFractionOfRated(), core.getVoidFraction(),
                core.getExitQuality(), breakdown.totalDkOverK(), breakdown.voidDkOverK(),
                breakdown.dopplerDkOverK(), core.getFuelTemperatureC(), core.getPressurePsig(),
                core.getSteamGenerationKgPerS(), core.getAveragePowerRangePercent(0),
                averageNotch(core));
    }

    // ===============================================================
    // Scenario 4 — scram from full power
    // ===============================================================

    /**
     * Scram from rated power: the prompt drop, the delayed neutron tail, and the
     * decay heat that neither of them touches.
     *
     * <p>Three distinct things happen and the trace separates them.
     * <ul>
     *   <li>Within the three-second insertion stroke, fission power falls by a
     *       factor of about sixteen. That is the <b>prompt drop</b>,
     *       {@code beta/(beta-rho)}, and it is over almost before the rods are.</li>
     *   <li>Then fission power decays on the precursor spectrum, ending on the
     *       longest-lived group's 80-second time constant. <b>It never reaches
     *       zero</b>, and it must not: delayed neutrons are why a reactor is
     *       controllable at all, and a model whose scrammed core goes to exactly
     *       zero has them wrong.</li>
     *   <li>Decay heat does not care. It is 6.6% of rated the instant the rods
     *       hit bottom and about 4.5% a minute later, and there is no rod
     *       position and no boron concentration that changes either figure. This
     *       is the driver of every severe accident in the spec, and the scram
     *       did not help.</li>
     * </ul>
     *
     * <p>Level shrink is visible too: void collapses within a couple of seconds,
     * the swell that was holding indicated level up disappears, and the
     * indication drops sharply while the vessel inventory is actually rising.
     */
    public static void scramFromFullPower(PrintStream out) {
        ReactorCore core = ratedCore();
        PlantOperator operator = new PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);

        banner(out, "scram", "SCRAM from rated power: prompt drop, delayed tail, decay heat");
        out.println("t_s,scram,rods_avg_notch,charged_accumulators,rho_dkk,rho_dollars,"
                + "n_fraction,decay_fraction,total_percent,thermal_mw,decay_mw,"
                + "void,pressure_psig,level_indicated_in,level_collapsed_in,fuel_c,clad_c,"
                + "steam_kgps,srm_cps,aprm_percent");

        double dt = core.getConfig().tickSeconds;
        double scramTime = 10.0;
        double endTime = 600.0;
        boolean fired = false;
        double nextLog = 0.0;

        while (core.getElapsedSeconds() < endTime) {
            double t = core.getElapsedSeconds();
            if (t >= nextLog) {
                logScramRow(out, core);
                // Dense sampling through the prompt drop, sparse through the tail.
                double sinceScram = t - scramTime;
                nextLog += (sinceScram > -1.0 && sinceScram < 20.0) ? 0.25
                        : (sinceScram < 120.0 ? 2.0 : 10.0);
            }
            if (!fired && t >= scramTime) {
                core.scram();
                fired = true;
            }
            operator.tick();
            core.step(dt);
        }
        logScramRow(out, core);
        out.printf(Locale.ROOT,
                "# 600 s after scram: fission %.4E of rated, decay heat %.4f%% = %.1f MW, "
                        + "charged accumulators %d of %d%n",
                core.getNeutronPowerFraction(), 100.0 * core.getDecayHeatFraction(),
                core.getDecayHeatMW(), core.getChargedAccumulatorCount(), core.getControlRodCount());
    }

    private static void logScramRow(PrintStream out, ReactorCore core) {
        out.printf(Locale.ROOT, "%.2f,%d,%.3f,%d,%+.6e,%+.4f,%.6e,%.6e,%.4f,%.1f,%.1f,"
                        + "%.5f,%.2f,%.2f,%.2f,%.1f,%.1f,%.2f,%.4E,%.4f%n",
                core.getElapsedSeconds(), core.isScramActive() ? 1 : 0, averageNotch(core),
                core.getChargedAccumulatorCount(), core.getReactivityDkOverK(),
                core.getReactivityDollars(), core.getNeutronPowerFraction(),
                core.getDecayHeatFraction(), 100.0 * core.getTotalPowerFractionOfRated(),
                core.getThermalPowerMW(), core.getDecayHeatMW(), core.getVoidFraction(),
                core.getPressurePsig(), core.getIndicatedLevelIn(), core.getCollapsedLevelIn(),
                core.getFuelTemperatureC(), core.getCladTemperatureC(),
                core.getSteamGenerationKgPerS(), averageCountRate(core),
                core.getAveragePowerRangePercent(0));
    }

    // ===============================================================
    // Scenario 5 — pressurisation transient
    // ===============================================================

    /**
     * Isolation of the steam outlet at rated power — an MSIV closure — with no
     * scram and no relief.
     *
     * <p>The payoff of {@code SPEC.md} section 6.3, and it is entirely emergent.
     * Nothing in the model knows that closing a valve should raise power. What
     * happens is a chain of four separate models each doing its own job: the
     * vessel loses its steam sink and pressure climbs; saturation temperature
     * climbs with it, so the water arriving at the core inlet is suddenly
     * subcooled and the denser steam takes up less room; the void fraction falls
     * and the boiling boundary climbs; and the void reactivity term, being
     * negative, hands back positive reactivity as the void goes away. Power
     * surges, Doppler fights it on the fuel time constant, and pressure keeps
     * going because the extra power makes more steam and there is still nowhere
     * for it to go.
     *
     * <p><b>No safety or relief valve opens in this trace, and none will.</b>
     * SRVs in this mod are player-actuated. The transient runs to whatever the
     * vessel does, past the 1250 psig design pressure and the 1375 psig code
     * limit, because deciding to lift a valve or fire a scram is the player's
     * job. This is precisely why a real plant scrams on MSIV closure rather than
     * waiting for a flux trip: by the time the flux tells you, the pressure has
     * already done it.
     */
    public static void pressurisationTransient(PrintStream out) {
        ReactorCore core = ratedCore();
        PlantOperator operator = new PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(false);

        banner(out, "pressurisation",
                "Steam line isolation at rated power: void collapse adds reactivity");
        out.println("t_s,isolated,pressure_psig,dpdt_psi_s,coolant_c,void,quasistatic_void,"
                + "boiling_boundary,subcooling_kjkg,rho_dkk,rho_void,rho_doppler,rho_dollars,"
                + "n_fraction,total_percent,fuel_c,level_indicated_in,steam_gen_kgps,steam_out_kgps");

        double dt = core.getConfig().tickSeconds;
        double isolationTime = 20.0;
        // Twenty seconds of isolation, which is well clear of the vessel model's
        // numerical pressure ceiling of 5000 psig — rows sitting flat against a
        // clamp are an artefact of the guard and not reactor behaviour. Twenty
        // seconds is also more than enough: by then the void has collapsed by a
        // quarter, the reactivity it handed back has quadrupled the power, and the
        // vessel is at three times its design pressure. Everything past the
        // 1375 psig code limit belongs to the overpressure and failure model of
        // SPEC section 7, not to more rows of this trace.
        double endTime = 40.0;
        boolean isolated = false;
        double nextLog = 0.0;

        while (core.getElapsedSeconds() < endTime) {
            double t = core.getElapsedSeconds();
            if (t >= nextLog) {
                logPressurisationRow(out, core, isolated);
                nextLog += (t >= isolationTime - 1.0) ? 0.25 : 2.0;
            }
            if (!isolated && t >= isolationTime) {
                // MSIVs shut. The regulator is left in service and is simply
                // outrun: its valves are on the wrong side of the closed
                // isolation valve, which is exactly the real failure.
                isolated = true;
                operator.setPressureRegulatorEnabled(false);
                core.setTurbineSteamFlowKgPerS(0.0);
                core.setBypassSteamFlowKgPerS(0.0);
                core.setReliefSteamFlowKgPerS(0.0);
            }
            operator.tick();
            core.step(dt);
        }
        logPressurisationRow(out, core, isolated);
        out.printf(Locale.ROOT,
                "# %.0f s after isolation: %.0f psig (design %.0f, code limit %.0f), "
                        + "void %.4f, power %.1f%% of rated, peak clad %.0f degC%n",
                endTime - isolationTime, core.getPressurePsig(),
                PhysicalConstants.VESSEL_DESIGN_PRESSURE_PSIG,
                PhysicalConstants.VESSEL_CODE_LIMIT_PSIG, core.getVoidFraction(),
                100.0 * core.getTotalPowerFractionOfRated(), core.getPeakCladTemperatureC());
    }

    private static void logPressurisationRow(PrintStream out, ReactorCore core, boolean isolated) {
        ReactivityBalance.Breakdown breakdown = core.getReactivityBreakdown();
        out.printf(Locale.ROOT, "%.2f,%d,%.2f,%+.3f,%.2f,%.5f,%.5f,%.4f,%.2f,"
                        + "%+.6e,%+.6e,%+.6e,%+.4f,%.6f,%.4f,%.1f,%.2f,%.2f,%.2f%n",
                core.getElapsedSeconds(), isolated ? 1 : 0, core.getPressurePsig(),
                core.getPressureRateOfChangePsiPerSecond(), core.getCoolantTemperatureC(),
                core.getVoidFraction(), core.getVoidModel().getQuasiStaticVoidFraction(),
                core.getBoilingBoundaryFraction(), core.getCoreInletSubcoolingKJPerKg(),
                breakdown.totalDkOverK(), breakdown.voidDkOverK(), breakdown.dopplerDkOverK(),
                core.getReactivityDollars(), core.getNeutronPowerFraction(),
                100.0 * core.getTotalPowerFractionOfRated(), core.getFuelTemperatureC(),
                core.getIndicatedLevelIn(), core.getSteamGenerationKgPerS(),
                core.getCommandedSteamFlowKgPerS());
    }

    // ===============================================================
    // The player's control logic, standing in for CC:Tweaked Lua
    // ===============================================================

    /**
     * Everything {@link ReactorCore} deliberately refuses to do: hold pressure,
     * hold level, and drive rods toward a power target.
     *
     * <p>Written against the public API only. There is no privileged access here
     * and nothing this class does could not be done from Lua through the
     * peripheral, which is the point — if a scenario needs something this class
     * cannot express, the peripheral is missing a getter.
     *
     * <p>The three loops are deliberately simple and deliberately imperfect. A
     * real player would write better ones, and would discover the same things
     * about them that anyone tuning a plant discovers.
     */
    public static final class PlantOperator {

        /** Pressure setpoint the regulator holds, psig. */
        public static final double PRESSURE_SETPOINT_PSIG = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;

        /**
         * Regulator gain, kg/s of steam flow per psi of error. The vessel's
         * pressure capacity is about 59 kg per psi, so 20 kg/s/psi closes the
         * loop with roughly a three second time constant — fast against the
         * pressure transient and slow against the tick.
         */
        public static final double PRESSURE_GAIN_KG_PER_S_PER_PSI = 20.0;

        /** Indicated level setpoint, inches on the instrument-zero scale. */
        public static final double LEVEL_SETPOINT_IN = 30.0;

        /** Level controller gain, kg/s of feedwater per inch of error. */
        public static final double LEVEL_GAIN_KG_PER_S_PER_IN = 40.0;

        /**
         * Total power above which the operator puts feedwater in service. Feeding
         * a vessel that is not boiling just condenses steam and depressurises it,
         * so an operator waits. Judgement, which is why it lives here.
         */
        public static final double FEEDWATER_IN_SERVICE_POWER_FRACTION = 0.01;

        /** Reactor period the rod control aims for during ascension, seconds. */
        public static final double TARGET_PERIOD_SECONDS = 80.0;

        /** Seconds between rod control decisions. */
        public static final double ROD_CONTROL_INTERVAL_SECONDS = 4.0;

        /** Total power at which the operator starts walking the recirculation pumps up. */
        public static final double FLOW_ASCENSION_START_POWER_FRACTION = 0.15;

        /** Total power by which the recirculation pumps are at rated flow. */
        public static final double FLOW_ASCENSION_END_POWER_FRACTION = 0.75;

        private final ReactorCore core;
        private final RodSequencer sequencer;

        private boolean pressureRegulatorEnabled = true;
        private boolean levelControlEnabled = true;
        private boolean rodControlEnabled = false;
        private boolean flowControlEnabled = false;

        private double targetTotalPowerFraction = 1.0;
        private double secondsSinceRodDecision = 0.0;
        private double previousTotalPower = 0.0;
        private double powerSettledSeconds = 0.0;

        public PlantOperator(ReactorCore core) {
            this.core = core;
            this.sequencer = new RodSequencer(core);
        }

        /** Run one control cycle. Call once per tick, before {@code core.step()}. */
        public void tick() {
            double dt = core.getConfig().tickSeconds;

            if (pressureRegulatorEnabled) {
                // Load feedforward plus proportional pressure error, which is what
                // a real pressure regulator does: the valves are positioned for the
                // load the core is carrying, and the error term only has to catch
                // the difference.
                double error = core.getPressurePsig() - PRESSURE_SETPOINT_PSIG;
                double demand = boilOffFeedforwardKgPerS() + PRESSURE_GAIN_KG_PER_S_PER_PSI * error;
                demand = Math.max(0.0, Math.min(1.25 * RATED_STEAM_FLOW_KG_PER_S, demand));
                core.setTurbineSteamFlowKgPerS(demand);
            }

            if (levelControlEnabled
                    && core.getTotalPowerFractionOfRated() >= FEEDWATER_IN_SERVICE_POWER_FRACTION) {
                double error = LEVEL_SETPOINT_IN - core.getIndicatedLevelIn();
                double demand = boilOffFeedforwardKgPerS() + LEVEL_GAIN_KG_PER_S_PER_IN * error;
                demand = Math.min(demand, coldFeedQuenchLimitKgPerS());
                demand = Math.max(0.0, Math.min(1.4 * RATED_STEAM_FLOW_KG_PER_S, demand));
                core.setFeedwaterFlowKgPerS(demand);
            }

            manageInstrumentRanges();

            if (flowControlEnabled) {
                driveRecirculationTowardTarget();
            }

            if (rodControlEnabled) {
                secondsSinceRodDecision += dt;
                if (secondsSinceRodDecision >= ROD_CONTROL_INTERVAL_SECONDS) {
                    secondsSinceRodDecision = 0.0;
                    driveRodsTowardTarget();
                }
            }

            double total = core.getTotalPowerFractionOfRated();
            if (Math.abs(total - previousTotalPower) < 1.0e-4 * Math.max(1.0e-6, total)) {
                powerSettledSeconds += dt;
            } else {
                powerSettledSeconds = 0.0;
            }
            previousTotalPower = total;
        }

        /**
         * The steam the core's thermal power can actually boil, kg/s.
         *
         * <p>This is the feedforward term for both the pressure regulator and the
         * level controller, and it is deliberately <b>not</b>
         * {@code getSteamGenerationKgPerS()}. That reading is <i>net</i> steam
         * leaving the water, so it includes flashing: let pressure fall and the
         * whole vessel inventory flashes, net steam generation spikes, and a
         * regulator feeding that forward opens its valves wider — which drops
         * pressure further, which flashes more water. That loop is a real way to
         * lose a plant and it is the operator's bug, not the model's. Feeding
         * forward the boil-off the core power can sustain breaks it: the
         * feedforward stays where the load is and the error term, being negative,
         * shuts the valves and recovers the pressure.
         *
         * <p>Uses only public getters and the same saturation correlations the
         * peripheral exposes, so it is a calculation a Lua program could do.
         */
        public double boilOffFeedforwardKgPerS() {
            double psia = core.getPressurePsia();
            double enthalpyRise = Saturation.vapourEnthalpyKJPerKg(psia)
                    - Saturation.subcooledLiquidEnthalpyKJPerKg(core.getFeedwaterTemperatureC());
            if (!(enthalpyRise > 0.0)) {
                return 0.0;
            }
            return core.getThermalPowerMW() * 1000.0 / enthalpyRise;
        }

        /**
         * The feed flow whose subcooling would absorb the entire core thermal
         * power, kg/s. Feed any faster than this and the vessel is being cooled
         * down rather than levelled off.
         *
         * <p>Without this limit the level controller destroys the plant during an
         * ascension, and it does it in a completely realistic way. Indicated level
         * at low power sits low because there is no void swell holding it up, so a
         * fixed setpoint shows a twenty-inch error; a proportional gain sized for
         * rated flow then demands nine hundred kilograms a second of 216 degC
         * water into a vessel boiling twenty. That is three hundred megawatts of
         * cooling against thirty of core power: pressure craters, the density
         * error in the level instrument's reference leg makes the indication read
         * <i>higher</i> as it does so, the controller slams the valves shut, and
         * the plant is left depressurised with a level indication that is lying
         * about it. Every step of that is real behaviour of real hardware, which
         * is why it belongs in the operator's logic and not in the model.
         *
         * <p>The limit is not a setpoint and not a trip. It is the operator
         * declining to put more cold water in than the core can reheat, which is
         * arithmetic a Lua program can do from the same public getters.
         */
        public double coldFeedQuenchLimitKgPerS() {
            double psia = core.getPressurePsia();
            double subcooling = Saturation.liquidEnthalpyKJPerKg(psia)
                    - Saturation.subcooledLiquidEnthalpyKJPerKg(core.getFeedwaterTemperatureC());
            if (!(subcooling > 0.0)) {
                return Double.MAX_VALUE;
            }
            return core.getThermalPowerMW() * 1000.0 / subcooling;
        }

        /**
         * Recirculation flow during an ascension. A BWR startup is done on rods to
         * the bottom of the flow control range and then on flow, so the operator
         * walks the pumps up from their low-speed setting as power comes on. Left
         * at the startup setting the core simply cannot reach rated: at a quarter
         * of rated flow the void fraction at full power would be enormous and the
         * void coefficient holds power down long before it gets there.
         */
        private void driveRecirculationTowardTarget() {
            double total = core.getTotalPowerFractionOfRated();
            double fraction = (total - FLOW_ASCENSION_START_POWER_FRACTION)
                    / (FLOW_ASCENSION_END_POWER_FRACTION - FLOW_ASCENSION_START_POWER_FRACTION);
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            double demand = PhysicalConstants.RECIRC_LOW_SPEED_FRACTION
                    + fraction * (1.0 - PhysicalConstants.RECIRC_LOW_SPEED_FRACTION);
            core.setRecirculationFlowFraction(demand);
        }

        /**
         * Range the IRMs by hand, one detent at a time, as an operator does. The
         * mod will never do this on its own: mis-ranging is a real startup hazard
         * and auto-ranging deletes the skill of avoiding it along with the
         * hazard. This is the player choosing to automate it, which is allowed
         * and is a completely different thing.
         */
        private void manageInstrumentRanges() {
            for (IntermediateRangeMonitor irm : core.getIntermediateRangeMonitors()) {
                double divisions = irm.getScaleDivisions();
                if (divisions > 100.0 && irm.getRange() < IntermediateRangeMonitor.HIGHEST_RANGE) {
                    irm.rangeUp();
                } else if (divisions < 8.0 && irm.getRange() > IntermediateRangeMonitor.LOWEST_RANGE) {
                    irm.rangeDown();
                }
            }
        }

        /**
         * Rod control. Below the APRM's range the operator flies on period, which
         * is what the startup procedure is written in; above it, on indicated
         * power, because period stops being the useful quantity once thermal
         * feedback is setting it.
         */
        private void driveRodsTowardTarget() {
            double total = core.getTotalPowerFractionOfRated();
            double indicatedPercent = core.getAveragePowerRangePercent(0);

            if (indicatedPercent >= 5.0) {
                double errorPercent = 100.0 * targetTotalPowerFraction - indicatedPercent;
                if (errorPercent > 1.5) {
                    sequencer.withdrawOneNotch(1);
                } else if (errorPercent < -1.5) {
                    sequencer.insertOneNotch(1);
                }
                return;
            }

            double period = indicatedPeriodSeconds();
            if (total >= targetTotalPowerFraction) {
                sequencer.insertOneNotch(1);
                return;
            }
            if (!Double.isFinite(period) || period < 0.0) {
                // Steady or falling. Add reactivity; more of it while the core is
                // still deep, because a notch down there is worth very little.
                sequencer.withdrawOneNotch(total < 1.0e-8 ? 6 : 2);
            } else if (period > 1.6 * TARGET_PERIOD_SECONDS) {
                sequencer.withdrawOneNotch(total < 1.0e-8 ? 6 : 1);
            } else if (period < 0.6 * TARGET_PERIOD_SECONDS) {
                sequencer.insertOneNotch(1);
            }
        }

        /**
         * Indicated period from whichever channel the operator has selected,
         * seconds.
         *
         * <p>Channel selection is a judgement and it belongs here. The rule used
         * is the obvious one — take the period from the lowest-range instrument
         * that is still on scale — and it is worth noticing that this is exactly
         * the judgement a rolled-over SRM defeats: a saturated source range
         * channel reads a plausible count rate on its way back down, and its
         * period meter faithfully reports a <i>negative</i> period on a core that
         * is climbing. Cross-checking the IRM is what catches it.
         */
        public double indicatedPeriodSeconds() {
            PeriodMeter meter = selectedPeriodMeter();
            return meter == null ? Double.POSITIVE_INFINITY : meter.getPeriodSeconds();
        }

        /**
         * The same selected channel's meter deflection, 1/T per second.
         *
         * <p>The quantity to log and to plot. It is finite and continuous through
         * the centre of the scale, where {@link #indicatedPeriodSeconds()} is
         * legitimately infinite — steady flux reads 0.0 here and
         * {@code Infinity} there. Nothing is lost: the period is the reciprocal.
         */
        public double indicatedInversePeriodPerSecond() {
            PeriodMeter meter = selectedPeriodMeter();
            return meter == null ? 0.0 : meter.getInversePeriodPerSecond();
        }

        /**
         * The period meter the operator is watching, or null when no channel is
         * trustworthy. Shared by both indications above so they can never
         * disagree about which channel they came from.
         */
        private PeriodMeter selectedPeriodMeter() {
            for (int channel = 0; channel < ReactorCore.SOURCE_RANGE_CHANNELS; channel++) {
                SourceRangeMonitor srm = core.getSourceRangeMonitor(channel);
                if (srm.getStatus() == NeutronDetector.DetectorStatus.ONSCALE
                        && srm.getCountsPerSecond() < 0.5 * srm.getPeakIndicatedCountsPerSecond()) {
                    return core.getSourceRangePeriodMeter(channel);
                }
            }
            for (int channel = 0; channel < ReactorCore.INTERMEDIATE_RANGE_CHANNELS; channel++) {
                if (core.getIntermediateRangeMonitor(channel).isOnScale()) {
                    return core.getIntermediateRangePeriodMeter(channel);
                }
            }
            return null;
        }

        /** True when total power has held still for ten seconds. */
        public boolean isPowerSettled() {
            return powerSettledSeconds >= 10.0;
        }

        public void setPressureRegulatorEnabled(boolean enabled) {
            this.pressureRegulatorEnabled = enabled;
        }

        public void setLevelControlEnabled(boolean enabled) {
            this.levelControlEnabled = enabled;
        }

        public void setRodControlEnabled(boolean enabled) {
            this.rodControlEnabled = enabled;
        }

        /**
         * Whether the operator walks recirculation flow up with power. Off by
         * default so a scenario that is studying the flow handle owns it outright.
         */
        public void setFlowControlEnabled(boolean enabled) {
            this.flowControlEnabled = enabled;
        }

        public void setTargetTotalPowerFraction(double fraction) {
            this.targetTotalPowerFraction = fraction;
        }

        public RodSequencer sequencer() {
            return sequencer;
        }
    }

    /**
     * Withdraws and inserts rods a notch at a time, sweeping through the core so
     * the pattern stays roughly uniform.
     *
     * <p>There are no banks in this model — one drive per rod, BWR practice — so
     * "withdraw a group" means what it says: command several individual drives.
     * A real rod withdrawal sequence is a great deal more careful than this
     * about which rods and in what order, and getting that wrong is a real way
     * to hurt a core. That, too, is the player's problem.
     */
    public static final class RodSequencer {
        private final ReactorCore core;
        private int cursor = 0;

        public RodSequencer(ReactorCore core) {
            this.core = core;
        }

        /** Command {@code rodCount} rods one notch further out. */
        public void withdrawOneNotch(int rodCount) {
            int rods = core.getControlRodCount();
            for (int i = 0; i < rodCount; i++) {
                for (int tried = 0; tried < rods; tried++) {
                    int r = cursor;
                    cursor = (cursor + 1) % rods;
                    if (core.getRodNotchDemand(r) < RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN) {
                        core.setRodNotchDemand(r, core.getRodNotchDemand(r) + 1);
                        break;
                    }
                }
            }
        }

        /** Command {@code rodCount} rods one notch further in. */
        public void insertOneNotch(int rodCount) {
            int rods = core.getControlRodCount();
            for (int i = 0; i < rodCount; i++) {
                for (int tried = 0; tried < rods; tried++) {
                    cursor = (cursor - 1 + rods) % rods;
                    if (core.getRodNotchDemand(cursor) > RodWorth.NOTCH_INDEX_FULLY_INSERTED) {
                        core.setRodNotchDemand(cursor, core.getRodNotchDemand(cursor) - 1);
                        break;
                    }
                }
            }
        }
    }

    // ===============================================================
    // Shared setup
    // ===============================================================

    /**
     * A hot shut-down core with a live startup source: rated pressure, every rod
     * in, no irradiation history, recirculation on its low-speed startup setting.
     */
    public static ReactorCore shutdownCore() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.setNeutronSourceStrengthPerSecond(CALIBRATED_SOURCE_PER_SECOND);
        core.initialiseHotShutdown();
        return core;
    }

    /** A core in equilibrium at rated total thermal power, with saturated xenon and decay heat. */
    public static ReactorCore ratedCore() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.setNeutronSourceStrengthPerSecond(CALIBRATED_SOURCE_PER_SECOND);
        core.initialiseAtTotalPowerFraction(1.0);
        return core;
    }

    /** Steps the core for a wall of simulated seconds, running the operator each tick. */
    public static void runSeconds(ReactorCore core, PlantOperator operator,
                                  double seconds, Runnable perTick) {
        double dt = core.getConfig().tickSeconds;
        int ticks = (int) Math.round(seconds / dt);
        for (int i = 0; i < ticks; i++) {
            if (operator != null) {
                operator.tick();
            }
            core.step(dt);
            if (perTick != null) {
                perTick.run();
            }
        }
    }

    /** Mean indicated count rate across the source range channels, cps. */
    public static double averageCountRate(ReactorCore core) {
        double sum = 0.0;
        for (int channel = 0; channel < ReactorCore.SOURCE_RANGE_CHANNELS; channel++) {
            sum += core.getSourceRangeCountsPerSecond(channel);
        }
        return sum / ReactorCore.SOURCE_RANGE_CHANNELS;
    }

    /** Mean rod notch index across the core. A pattern summary, not a bank position. */
    public static double averageNotch(ReactorCore core) {
        double sum = 0.0;
        int[] notches = core.getRodNotchIndices();
        for (int notch : notches) {
            sum += notch;
        }
        return sum / notches.length;
    }

    private static void banner(PrintStream out, String name, String description) {
        out.println();
        out.println("# ============================================================");
        out.println("# BEGIN " + name + " — " + description);
        out.printf(Locale.ROOT,
                "# rated %.0f MWt, %d assemblies, %d rods, dome %.0f psig = %.1f degC, "
                        + "T_sat fit at 1000 psia = %.2f degF%n",
                PhysicalConstants.RATED_THERMAL_MW, PhysicalConstants.FUEL_ASSEMBLIES,
                PhysicalConstants.CONTROL_RODS, PhysicalConstants.RATED_DOME_PRESSURE_PSIG,
                Saturation.temperatureCelsiusFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG),
                Saturation.temperatureFahrenheitFromPsia(1000.0));
        out.println("# ============================================================");
    }
}
