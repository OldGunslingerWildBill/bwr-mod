package dev.bwr.mod.devtest;

import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControllerStationData;
import dev.bwr.mod.panel.component.ControllerStationType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.StationLayout;
import dev.bwr.mod.panel.component.StationPid;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The controller station's built-in PID in a real plant run: a station in AUTO on a real recirculation pump (first order
 * lag, 3 s accelerating and 11 s decelerating), its {@code demand} wired to the pump's {@code speed} command and its
 * {@code pv} to the pump's measured {@code speed}, through the Networking Wrench and the binding payload. The pump and
 * the panel are stepped the way the server does (the panel sampled every second tick, the pump every tick, the game clock
 * advanced); nothing is written into the loop except what a player does: pressing the station's buttons.
 *
 * <p>World fixtures at 261_000 and up (see {@link ControllerSyncWiringTests.Rig}), batch {@code controllersync}.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ControllerStationPidTests {
    private ControllerStationPidTests() {
    }

    private static final int ORIGIN_X = 261_000;
    private static final int ORIGIN_Z = 260_000;
    private static final double EPS = 1.0e-6;

    /** A stage with a station and a pump wired to each other, and the clock to step them. */
    private static final class Loop {
        final ControllerSyncWiringTests.Rig rig;
        final ServerLevel level;
        final RecirculationPumpBlockEntity pump;
        final BlockPos pumpAt;
        final PanelComponent station;
        final ControllerStationData data;
        final long start;
        long ticks;
        /** Samples: {seconds, PV, demand, SP}. */
        final List<double[]> log = new ArrayList<>();

        Loop(GameTestHelper h, BlockPos root, String name) {
            rig = new ControllerSyncWiringTests.Rig(h, root, name);
            level = rig.level;
            start = level.getGameTime();
            rig.start();
            pumpAt = root.offset(0, 0, 8);
            var block = BwrBlocks.RECIRCULATION_PUMP.get();
            var state = block.placementState();
            level.setBlock(pumpAt, state, 3);
            block.setPlacedBy(level, pumpAt, state, null, new ItemStack(block));
            pump = (RecirculationPumpBlockEntity) level.getBlockEntity(pumpAt);
            station = rig.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
            data = (ControllerStationData) station.data();
            if (rig.bindOut(station, ControllerStationType.OUT_DEMAND, pumpAt, "speed",
                    ControllerSyncWiringTests.guiScale(rig, station, pumpAt, "speed")) != WiringOps.Result.OK
                    || rig.bindIn(station, ControllerStationType.IN_PV, pumpAt, "speed", 100.0) != WiringOps.Result.OK) {
                throw new AssertionError("wiring the station to the pump failed");
            }
        }

        /** Runs the plant for {@code seconds}: the clock, the supply, the panel's sampler (every second tick) and the pump. */
        void run(double seconds) {
            int steps = (int) Math.round(seconds * 20.0);
            for (int i = 0; i < steps; i++) {
                ticks++;
                ((ServerLevelData) level.getLevelData()).setGameTime(start + ticks);
                pump.receiveEnergyFe(RecirculationPumpBlockEntity.MAX_FE_PER_TICK, false);
                if (ticks % 2 == 0) {
                    rig.be().sampleLive();
                    log.add(new double[] {ticks / 20.0, pump.getActualSpeedFraction() * 100.0, data.demand,
                            ControllerStationType.shownSetpoint(station)});
                }
                pump.tickPump(0.05);
            }
        }

        double pv() {
            return pump.getActualSpeedFraction() * 100.0;
        }

        /** Presses RAISE (positive) or LOWER (negative) {@code presses} times: in AUTO this moves the local setpoint. */
        void sp(int presses) {
            for (int i = 0; i < Math.abs(presses); i++) {
                rig.press(station, presses > 0 ? StationLayout.RAISE : StationLayout.LOWER);
            }
            rig.release(station);
        }

        void auto() {
            rig.press(station, StationLayout.AUTO);
        }

        void man() {
            rig.press(station, StationLayout.MAN);
        }

        /** Seconds after {@code from} until the PV stays within {@code band} of {@code target} to the end of the log, or -1. */
        double settle(double from, double target, double band) {
            double at = -1.0;
            for (int i = log.size() - 1; i >= 0 && log.get(i)[0] >= from; i--) {
                if (Math.abs(log.get(i)[1] - target) > band) {
                    break;
                }
                at = log.get(i)[0];
            }
            return at < 0 ? -1.0 : at - from;
        }

        double peak(double from, double to, boolean max) {
            double best = max ? -1.0e9 : 1.0e9;
            for (double[] l : log) {
                if (l[0] >= from && l[0] < to) {
                    best = max ? Math.max(best, l[1]) : Math.min(best, l[1]);
                }
            }
            return best;
        }

        void finish() {
            ((ServerLevelData) level.getLevelData()).setGameTime(start);
            rig.finish();
        }
    }

    private static BlockPos root(int slot) {
        return new BlockPos(ORIGIN_X + slot * 100, 245, ORIGIN_Z);
    }

    // ------------------------------------------------------------------------------------------ step response, MAN, defaults

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void autoStepResponseConvergesWithoutOscillationAndNothingRunsInMan(GameTestHelper h) {
        BlockPos root = root(0);
        var probe = new ControllerSyncWiringTests.Rig(h, root, "pid-step");
        EntityLookupProbe.runWhenLive(h, probe.bounds(), () -> {
            Loop loop = new Loop(h, root, "pid-step");
            try {
                // A placed station is in MAN: with the PV far from the SP for a minute, nothing moves the demand or the pump.
                h.assertTrue(!loop.data.auto && loop.station.output(ControllerStationType.OUT_DEMAND) == 0.0, "placed in MAN, OUT 0");
                loop.run(60);
                h.assertTrue(loop.data.demand == 0.0 && loop.pump.getTargetSpeedFraction() == 0.0 && loop.station.output(ControllerStationType.OUT_SETPOINT) == 50.0,
                        "MAN: a minute with PV 0 and SP 50, and the demand is still " + loop.data.demand);
                // Tuned for this plant: Kp 1, Ti 5 s. Select AUTO: the SP is 50, the pump stands still.
                loop.data.kp = 1.0;
                loop.data.ti = 5.0;
                loop.auto();
                h.assertTrue(loop.data.demand == 0.0, "selecting AUTO moved nothing: " + loop.data.demand);
                double t0 = loop.ticks / 20.0;
                loop.run(120);
                double overshoot1 = loop.peak(t0, t0 + 120, true) - 50.0;
                double settle1 = loop.settle(t0, 50.0, 2.0);
                h.assertTrue(overshoot1 <= 2.0 && settle1 >= 0 && settle1 <= 60.0 && Math.abs(loop.pv() - 50.0) < 0.5,
                        "step to 50: overshoot " + overshoot1 + " settle " + settle1 + " pv " + loop.pv());
                // An SP step of 20 %: RAISE x 20.
                loop.sp(20);
                h.assertTrue(Math.abs(loop.station.output(ControllerStationType.OUT_SETPOINT) - 70.0) < EPS, "SP is 70");
                double t1 = loop.ticks / 20.0;
                loop.run(120);
                double overshoot2 = loop.peak(t1, t1 + 120, true) - 70.0;
                double settle2 = loop.settle(t1, 70.0, 2.0);
                h.assertTrue(overshoot2 <= 2.0 && settle2 >= 0 && settle2 <= 40.0, "step to 70: overshoot " + overshoot2 + " settle " + settle2);
                // And down again, on the pump's slower way down: 70 to 40.
                loop.sp(-30);
                double t2 = loop.ticks / 20.0;
                loop.run(120);
                double undershoot = 40.0 - loop.peak(t2, t2 + 120, false);
                double settle3 = loop.settle(t2, 40.0, 2.0);
                h.assertTrue(undershoot <= 2.0 && settle3 >= 0 && settle3 <= 60.0, "step to 40: undershoot " + undershoot + " settle " + settle3);
                // No sustained oscillation: the last 60 s hold within half a percent.
                double lo = loop.peak(t2 + 60, t2 + 120, false);
                double hi = loop.peak(t2 + 60, t2 + 120, true);
                h.assertTrue(hi - lo < 0.5, "the last 60 s sit within 0.5 %: " + lo + " .. " + hi);
                // The defaults (Kp 0.5, Ti 20 s) are slower but converge without overshoot on this plant.
                loop.data.kp = ControllerStationData.DEFAULT_KP;
                loop.data.ti = ControllerStationData.DEFAULT_TI;
                loop.sp(30);
                double t3 = loop.ticks / 20.0;
                loop.run(400);
                double over3 = loop.peak(t3, t3 + 400, true) - 70.0;
                double settle4 = loop.settle(t3, 70.0, 2.0);
                h.assertTrue(over3 <= 2.0 && settle4 >= 0 && settle4 <= 300.0, "defaults 40 to 70: overshoot " + over3 + " settle " + settle4);
                com.mojang.logging.LogUtils.getLogger().info("PID-RESPONSE tuned Kp 1 Ti 5: 0->50 overshoot {} % settle {} s; 50->70 overshoot {} % settle {} s; 70->40 undershoot {} % settle {} s; "
                        + "defaults Kp 0.5 Ti 20: 40->70 overshoot {} % settle {} s", overshoot1, settle1, overshoot2, settle2, undershoot, settle3, over3, settle4);
                h.succeed();
            } finally {
                loop.finish();
            }
        });
    }

    // ------------------------------------------------------------------------------------------ disturbance

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void autoRejectsAPlantGainDisturbance(GameTestHelper h) {
        BlockPos root = root(1);
        var probe = new ControllerSyncWiringTests.Rig(h, root, "pid-dist");
        EntityLookupProbe.runWhenLive(h, probe.bounds(), () -> {
            Loop loop = new Loop(h, root, "pid-dist");
            try {
                loop.data.kp = 1.0;
                loop.data.ti = 5.0;
                loop.auto();
                loop.run(120);
                h.assertTrue(Math.abs(loop.pv() - 50.0) < 0.5, "settled at 50: " + loop.pv());
                double before = loop.data.demand;
                // The disturbance: the actuator loses 40 % of its gain (the sink's scale goes from 0.01 to 0.006).
                loop.rig.wire(new PanelPayloads.Bind(loop.rig.panel, loop.station.id(), true, ControllerStationType.OUT_DEMAND,
                        WiringOps.BindKind.NONE.ordinal(), BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0));
                h.assertTrue(loop.rig.bindOut(loop.station, ControllerStationType.OUT_DEMAND, loop.pumpAt, "speed", 0.006) == WiringOps.Result.OK, "rebound at 0.006");
                loop.pump.setTargetSpeedFraction(before * 0.006); // the command already sent, at the new gain
                double t0 = loop.ticks / 20.0;
                loop.run(150);
                double dip = 50.0 - loop.peak(t0, t0 + 150, false);
                double recovery = loop.settle(t0, 50.0, 2.0);
                h.assertTrue(dip < 12.0 && recovery >= 0 && recovery <= 60.0 && Math.abs(loop.data.demand - before / 0.6) < 3.0,
                        "disturbance: PV dipped " + dip + " %, recovered in " + recovery + " s, OUT " + before + " -> " + loop.data.demand);
                com.mojang.logging.LogUtils.getLogger().info("PID-DISTURBANCE actuator gain -40 %: PV dip {} %, back within 2 % in {} s, OUT {} -> {}", dip, recovery, before, loop.data.demand);
                h.succeed();
            } finally {
                loop.finish();
            }
        });
    }

    // ------------------------------------------------------------------------------------------ limits and anti-windup

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void autoHonoursTheOutputLimitsAndDoesNotWindUp(GameTestHelper h) {
        BlockPos root = root(2);
        var probe = new ControllerSyncWiringTests.Rig(h, root, "pid-limit");
        EntityLookupProbe.runWhenLive(h, probe.bounds(), () -> {
            Loop loop = new Loop(h, root, "pid-limit");
            try {
                loop.data.kp = 1.0;
                loop.data.ti = 5.0;
                loop.data.outMax = 60.0;
                loop.data.outMin = 20.0;
                loop.auto();
                // The first look at the pump: OUT is 0, below the low limit, so the PID takes it up to 20.
                loop.sp(40); // SP 90, beyond what 60 % output can reach
                loop.run(120);
                double max = 0.0;
                for (double[] l : loop.log) {
                    max = Math.max(max, l[2]);
                }
                h.assertTrue(max <= 60.0 + EPS && Math.abs(loop.data.demand - 60.0) < EPS && loop.pump.getTargetSpeedFraction() <= 0.6 + EPS
                        && Math.abs(loop.pv() - 60.0) < 1.0, "saturated at the high limit: max OUT " + max + " demand " + loop.data.demand + " pv " + loop.pv());
                // Two minutes against the limit; now the SP drops to 30: the output leaves the limit at once (no windup).
                loop.sp(-60);
                h.assertTrue(Math.abs(loop.station.output(ControllerStationType.OUT_SETPOINT) - 30.0) < EPS, "SP 30");
                loop.run(0.5);
                h.assertTrue(loop.data.demand < 59.0, "the output left its limit within half a second of the setpoint dropping: " + loop.data.demand);
                double t0 = loop.ticks / 20.0 - 0.5;
                loop.run(100);
                h.assertTrue(loop.settle(t0, 30.0, 2.0) >= 0 && loop.settle(t0, 30.0, 2.0) <= 60.0, "and the PV came to 30: settle " + loop.settle(t0, 30.0, 2.0));
                // The low limit: SP 0 asks for 0 % output, the station holds 20.
                loop.sp(-30);
                loop.run(120);
                double min = 1.0e9;
                for (double[] l : loop.log) {
                    if (l[0] > t0 + 20) {
                        min = Math.min(min, l[2]);
                    }
                }
                h.assertTrue(min >= 20.0 - EPS && Math.abs(loop.data.demand - 20.0) < EPS, "the low limit holds: min OUT " + min + " demand " + loop.data.demand);
                h.succeed();
            } finally {
                loop.finish();
            }
        });
    }

    // ------------------------------------------------------------------------------------------ PV loss, transfers

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void autoFreezesWithoutAProcessSignalAndTransfersAreBumpless(GameTestHelper h) {
        BlockPos root = root(3);
        var probe = new ControllerSyncWiringTests.Rig(h, root, "pid-transfer");
        EntityLookupProbe.runWhenLive(h, probe.bounds(), () -> {
            Loop loop = new Loop(h, root, "pid-transfer");
            try {
                loop.data.kp = 1.0;
                loop.data.ti = 5.0;
                // MAN to AUTO: the demand the hand left (37 %) is where the PID starts, with the PV far from the SP.
                loop.rig.op(loop.station, OperateAction.SET, 37.0);
                loop.run(30);
                double settled = loop.pv();
                h.assertTrue(Math.abs(settled - 37.0) < 0.5 && Math.abs(loop.pump.getTargetSpeedFraction() - 0.37) < EPS, "MAN 37 %: PV " + settled);
                loop.auto();
                h.assertTrue(loop.data.demand == 37.0 && loop.station.output(ControllerStationType.OUT_MODE) == 1.0, "AUTO selected: OUT unchanged at " + loop.data.demand);
                loop.run(0.2); // the first samples after AUTO only learn the PV
                h.assertTrue(loop.data.demand == 37.0, "the first samples moved nothing: " + loop.data.demand);
                loop.run(0.5);
                h.assertTrue(loop.data.demand > 37.0 && loop.data.demand < 40.0, "and what follows is a small step towards the SP, not a jump: " + loop.data.demand);
                loop.run(120);
                h.assertTrue(Math.abs(loop.pv() - 50.0) < 0.5, "AUTO took the PV to the SP 50: " + loop.pv());
                // AUTO to MAN: the last OUT is held while the PV moves on its own.
                double last = loop.data.demand;
                loop.man();
                h.assertTrue(loop.data.demand == last && loop.station.output(ControllerStationType.OUT_MODE) == 0.0, "MAN: OUT held at " + last);
                loop.pump.setTargetSpeedFraction(0.9); // somebody else moves the pump: the PV runs away from the SP
                loop.run(30);
                h.assertTrue(loop.data.demand == last, "MAN: nothing moves OUT, PV " + loop.pv() + ", OUT " + loop.data.demand);
                loop.pump.setTargetSpeedFraction(last * 0.01);
                loop.run(60);
                // PV loss in AUTO: the signal goes (the binding is cleared: no signal), the SP moves: OUT freezes, nothing winds up.
                loop.auto();
                loop.run(30);
                h.assertTrue(Math.abs(loop.pv() - 50.0) < 1.0, "back in AUTO and settled: " + loop.pv());
                double held = loop.data.demand;
                h.assertTrue(loop.rig.wire(new PanelPayloads.Bind(loop.rig.panel, loop.station.id(), false, ControllerStationType.IN_PV,
                        WiringOps.BindKind.NONE.ordinal(), BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0)) == WiringOps.Result.OK, "PV unbound");
                h.assertTrue(Double.isNaN(loop.station.input(ControllerStationType.IN_PV)), "no PV signal");
                loop.sp(30); // SP 80, with no PV
                loop.run(120);
                h.assertTrue(loop.data.demand == held && Math.abs(loop.pump.getTargetSpeedFraction() - held * 0.01) < EPS,
                        "two minutes without a PV: OUT frozen at " + held + ", is " + loop.data.demand);
                // The signal returns: no jump, then the PID takes the PV to the new SP.
                h.assertTrue(loop.rig.bindIn(loop.station, ControllerStationType.IN_PV, loop.pumpAt, "speed", 100.0) == WiringOps.Result.OK, "PV rebound");
                loop.run(0.1);
                h.assertTrue(loop.data.demand == held, "the first samples after the signal returns move nothing: " + loop.data.demand);
                loop.run(0.3);
                h.assertTrue(Math.abs(loop.data.demand - held) < 3.0, "and only gently after that: " + loop.data.demand + " from " + held);
                double t0 = loop.ticks / 20.0;
                loop.run(120);
                h.assertTrue(loop.settle(t0, 80.0, 2.0) >= 0, "and the PV reaches 80: " + loop.pv());
                h.succeed();
            } finally {
                loop.finish();
            }
        });
    }

    // ------------------------------------------------------------------------------------------ outside demand, sampler, config

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void anOutsideDemandOverridesThePidAndTheSamplerRunsAnAutoStation(GameTestHelper h) {
        BlockPos root = root(4);
        ControllerSyncWiringTests.Rig rig = new ControllerSyncWiringTests.Rig(h, root, "pid-ext");
        EntityLookupProbe.runWhenLive(h, rig.bounds(), () -> {
            boolean[] done = {false};
            try {
                rig.start();
                PanelComponent st = rig.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
                ControllerStationData data = (ControllerStationData) st.data();
                // PV comes from a second station's setpoint output (20 on its scale): a component source is pushed, not polled, so
                // nothing but a station in AUTO keeps this panel sampled.
                PanelComponent reference = rig.place(ComponentTypes.CONTROLLER_STATION, 4, 0, Footprint.of(3, 6));
                ((ControllerStationData) reference.data()).spFraction = 0.2;
                reference.type().onLoaded(reference);
                h.assertTrue(rig.wire(new PanelPayloads.Bind(rig.panel, st.id(), false, ControllerStationType.IN_PV, WiringOps.BindKind.COMPONENT.ordinal(),
                        BlockPos.ZERO, "", rig.panel, reference.id(), ControllerStationType.OUT_SETPOINT, 1.0)) == WiringOps.Result.OK, "PV bound to the reference");
                rig.be().pressOutput(reference, ControllerStationType.OUT_SETPOINT, 20.0); // a component source is pushed when the output is announced
                h.assertTrue(st.input(ControllerStationType.IN_PV) == 20.0, "PV from the reference station: " + st.input(ControllerStationType.IN_PV));
                h.assertTrue(!dev.bwr.mod.panel.wiring.PanelSampler.isTracked(rig.be()), "no live input: the panel is not sampled while in MAN");
                // An outside demand bound to auto_demand: AUTO follows it at once, the demand output follows it, the PID stays idle.
                rig.be().setInput(st, ControllerStationType.IN_AUTO_DEMAND, 64.0);
                rig.op(st, OperateAction.SET, 30.0);
                rig.press(st, StationLayout.AUTO);
                h.assertTrue(data.demand == 64.0 && st.output(ControllerStationType.OUT_DEMAND) == 64.0, "AUTO with an outside demand: OUT follows it at once: " + data.demand);
                rig.be().setInput(st, ControllerStationType.IN_AUTO_DEMAND, 41.0);
                rig.be().sampleLive();
                h.assertTrue(data.demand == 41.0 && st.output(ControllerStationType.OUT_DEMAND) == 41.0, "and follows its changes on the next sample: " + data.demand);
                h.assertTrue(rig.press(st, StationLayout.MAN) && data.demand == 41.0, "MAN: no bump, OUT stays 41");
                // Back in AUTO with the outside demand gone: the PID starts from where OUT is (bumpless) and, the PV being 20 below the SP, raises it.
                rig.be().setInput(st, ControllerStationType.IN_AUTO_DEMAND, Double.NaN);
                rig.press(st, StationLayout.AUTO);
                h.assertTrue(data.demand == 41.0, "the outside demand gone: OUT stays 41 until the PID moves it");
                // The server's own sampler (real ticks) runs the PID of this station.
                dev.bwr.mod.panel.PanelBlockEntity beBefore = rig.be();
                StringBuilder trace = new StringBuilder("same block entity " + (rig.be() == beBefore) + ", tracked after AUTO " + dev.bwr.mod.panel.wiring.PanelSampler.isTracked(rig.be()) + "/" + rig.be().hasLiveInputs());
                for (int delay : new int[] {1, 2, 3, 5, 10}) {
                    h.runAfterDelay(delay, () -> trace.append(" | +").append(delay).append(": tracked ").append(dev.bwr.mod.panel.wiring.PanelSampler.isTracked(rig.be()))
                            .append(" last ").append(data.pidLastStep).append(" ready ").append(data.pidReady).append(" demand ").append(data.demand));
                }
                h.runAfterDelay(100, () -> {
                    try {
                        // The component as the panel holds it now (not the reference taken before the ticks ran).
                        PanelComponent current = rig.be().component(st.id());
                        ControllerStationData now = (ControllerStationData) current.data();
                        h.assertTrue(now.demand > 41.0 && now.demand <= 100.0 && current.output(ControllerStationType.OUT_DEMAND) == now.demand,
                                "the sampler raised OUT on its own while the PV was below the SP: " + now.demand + " (same component " + (current == st) + ", same block entity " + (rig.be() == beBefore)
                                        + ", tracked " + dev.bwr.mod.panel.wiring.PanelSampler.isTracked(rig.be()) + ", auto " + now.auto + ", pv " + current.input(ControllerStationType.IN_PV)
                                        + ", ready " + now.pidReady + ", last " + now.pidLastStep + ", now " + h.getLevel().getGameTime() + ") " + trace);
                        done[0] = true;
                        rig.finish();
                        h.succeed();
                    } finally {
                        if (!done[0]) {
                            rig.finish();
                        }
                    }
                });
            } catch (RuntimeException | Error e) {
                rig.finish();
                throw e;
            }
        });
    }

    @GameTest(template = "empty", batch = "controllersync")
    public static void pidActionConfigAndSavesBehave(GameTestHelper h) {
        // Direct and reverse action, on the pure arithmetic: a PV above the SP lowers a direct output and raises a reverse one.
        for (boolean reverse : new boolean[] {false, true}) {
            ControllerStationData d = new ControllerStationData();
            d.reverse = reverse;
            d.demand = 50.0;
            h.assertTrue(Double.isNaN(StationPid.step(d, 50.0, 70.0, 0.1)), "the first step only learns the PV");
            double next = StationPid.step(d, 50.0, 70.0, 0.1);
            h.assertTrue(reverse ? next > 50.0 : next < 50.0, (reverse ? "reverse" : "direct") + " action with PV above SP: " + next);
        }
        // Derivative: the PV accelerating away adds a push against it.
        ControllerStationData withD = new ControllerStationData();
        withD.demand = 50.0;
        withD.td = 5.0;
        ControllerStationData noD = new ControllerStationData();
        noD.demand = 50.0;
        StationPid.step(withD, 50.0, 50.0, 0.1);
        StationPid.step(noD, 50.0, 50.0, 0.1);
        StationPid.step(withD, 50.0, 50.0, 0.1);
        StationPid.step(noD, 50.0, 50.0, 0.1);
        double a = StationPid.step(withD, 50.0, 51.0, 0.1);
        double b = StationPid.step(noD, 50.0, 51.0, 0.1);
        h.assertTrue(a < b, "with Td the PV kicking up lowers OUT further: " + a + " vs " + b);
        // A gap or a signal loss restarts the memory.
        h.assertTrue(Double.isNaN(StationPid.step(withD, 50.0, Double.NaN, 0.1)) && !withD.pidReady
                && Double.isNaN(StationPid.step(withD, 50.0, 50.0, StationPid.MAX_GAP + 1.0)), "NaN PV and a long gap freeze and forget");
        // Saves: defaults write nothing new, an old tag loads as the defaults, a changed PID round trips, bad limits are not trusted.
        ControllerStationData fresh = new ControllerStationData();
        CompoundTag tag = fresh.toTag();
        h.assertTrue(!tag.contains("Kp") && !tag.contains("Ti") && !tag.contains("Td") && !tag.contains("Reverse") && !tag.contains("OutMin")
                && !tag.contains("OutMax"), "a default station saves no PID keys: " + tag);
        ControllerStationData old = new ControllerStationData();
        old.load(new CompoundTag());
        h.assertTrue(old.kp == 0.5 && old.ti == 20.0 && old.td == 0.0 && !old.reverse && old.outMin == 0.0 && old.outMax == 100.0, "an old save loads the PID defaults");
        ControllerStationData tuned = new ControllerStationData();
        tuned.kp = 2.0;
        tuned.ti = 8.0;
        tuned.td = 1.5;
        tuned.reverse = true;
        tuned.outMin = 10.0;
        tuned.outMax = 90.0;
        ControllerStationData back = new ControllerStationData();
        back.load(tuned.toTag());
        h.assertTrue(back.kp == 2.0 && back.ti == 8.0 && back.td == 1.5 && back.reverse && back.outMin == 10.0 && back.outMax == 90.0
                && !back.pidReady, "a tuned PID round trips and starts without memory");
        CompoundTag bad = new CompoundTag();
        bad.putDouble("OutMin", 80.0);
        bad.putDouble("OutMax", 80.5);
        bad.putDouble("Kp", 1.0e9);
        bad.putDouble("Ti", 0.0);
        ControllerStationData sane = new ControllerStationData();
        sane.load(bad);
        h.assertTrue(sane.outMin == 0.0 && sane.outMax == 100.0 && sane.kp == ControllerStationData.MAX_KP && sane.ti == ControllerStationData.MIN_TI,
                "limits with no room reset and the gains clamp: " + sane.toTag());
        h.succeed();
    }

    @GameTest(template = "empty", batch = "controllersync")
    public static void bothSynchronisingLampsFollowTheDarkLampCurve(GameTestHelper h) {
        for (int lamp = 0; lamp < 2; lamp++) {
            h.assertTrue(dev.bwr.mod.panel.component.SyncMath.lampBrightness(lamp, 0.0) == 0.0
                    && Math.abs(dev.bwr.mod.panel.component.SyncMath.lampBrightness(lamp, 90.0) - 0.5) < EPS
                    && Math.abs(dev.bwr.mod.panel.component.SyncMath.lampBrightness(lamp, -90.0) - 0.5) < EPS
                    && Math.abs(dev.bwr.mod.panel.component.SyncMath.lampBrightness(lamp, 180.0) - 1.0) < EPS,
                    "lamp " + lamp + ": dark in phase, half at 90, full at 180");
        }
        h.succeed();
    }
}
