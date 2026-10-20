package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelTimers;
import dev.bwr.mod.panel.client.ComponentModels;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControllerStationData;
import dev.bwr.mod.panel.component.ControllerStationType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.component.StationLayout;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The manual/auto controller station ({@code bwr:controller_station}): its inputs and outputs, what MAN and AUTO do (and
 * that nothing acts by itself), the held RAISE and LOWER, the click regions, the config round trip, what is saved, and that
 * its text scales with it.
 *
 * <p>World fixtures at 181_000 / 181_000 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ControllerStationTests {
    private ControllerStationTests() {
    }

    static final int ORIGIN_X = 181_000;
    static final int ORIGIN_Z = 181_000;

    private static boolean op(HardwareStage s, PanelComponent station, OperateAction action, double value) {
        return PanelOps.operate(s.player, s.origin, station.id(), action, value);
    }

    /** Presses the button of {@code region} the way the client does: the type's own click value for its centre. */
    private static boolean press(HardwareStage s, PanelComponent station, int region) {
        float[] at = StationLayout.centreOf(region);
        return op(s, station, OperateAction.PRESS, station.type().clickValue(station, at[0] * station.w(), at[1] * station.h()));
    }

    private static void release(HardwareStage s, PanelComponent station) {
        op(s, station, OperateAction.RELEASE, 0.0);
    }

    private static double out(PanelComponent station, int index) {
        return station.output(index);
    }

    @GameTest(template = "empty")
    public static void stationIsRegisteredWithItsInputsOutputsAndRealSizes(GameTestHelper helper) {
        ComponentType type = ComponentTypes.CONTROLLER_STATION;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("controller_station"), "registered as controller_station");
        helper.assertTrue(BwrItems.CONTROLLER_STATION.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        List<String> in = type.inputPorts(type.createData()).stream().map(PortSpec::key).toList();
        List<String> out = type.outputPorts(type.createData()).stream().map(PortSpec::key).toList();
        helper.assertTrue(in.equals(List.of("pv", "setpoint", "auto_demand")), "inputs: " + in);
        helper.assertTrue(out.equals(List.of("demand", "mode", "raise", "lower", "setpoint")), "outputs: " + out);
        helper.assertTrue(type.isInteractive() && type.holdable() && type.scrollable(), "operated, held and scrolled");
        List<Footprint> sizes = type.footprints();
        helper.assertTrue(type.realSizes() == 2 && sizes.get(type.realDefaultIndex()).equals(Footprint.of(3, 6))
                && sizes.contains(Footprint.of(2, 4)), "a new item starts at the real 3 x 6: " + sizes);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void inManualRaiseAndLowerMoveTheDemandAndPulseTheirOutputs(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "cs-manual", s -> {
            PanelComponent st = s.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
            helper.assertTrue(out(st, 1) == 0.0 && out(st, 0) == 0.0, "starts in MAN at 0 %");
            for (int i = 0; i < 3; i++) {
                helper.assertTrue(press(s, st, StationLayout.RAISE), "RAISE handled");
            }
            helper.assertTrue(out(st, 0) == 3.0, "three presses of RAISE at 1 % a step: " + out(st, 0));
            helper.assertTrue(out(st, 2) == 1.0 && out(st, 3) == 0.0, "raise is held, lower is not");
            release(s, st);
            helper.assertTrue(out(st, 2) == 0.0 && out(st, 0) == 3.0, "released: momentary again, demand kept");
            press(s, st, StationLayout.LOWER);
            helper.assertTrue(out(st, 0) == 2.0 && out(st, 3) == 1.0 && out(st, 2) == 0.0, "LOWER lowers by a step and pulses lower");
            release(s, st);
            for (int i = 0; i < 5; i++) {
                press(s, st, StationLayout.LOWER);
            }
            release(s, st);
            helper.assertTrue(out(st, 0) == 0.0, "demand stops at 0: " + out(st, 0));
            op(s, st, OperateAction.SET, 99.5);
            press(s, st, StationLayout.RAISE);
            press(s, st, StationLayout.RAISE);
            release(s, st);
            helper.assertTrue(out(st, 0) == 100.0, "and at 100: " + out(st, 0));
            // The wheel moves it too, by notches.
            op(s, st, OperateAction.TURN, -3.0);
            helper.assertTrue(out(st, 0) == 97.0, "scrolling down three notches: " + out(st, 0));
            // A click outside the buttons is not handled.
            helper.assertTrue(!op(s, st, OperateAction.PRESS, 0.0), "a click on the scale window does nothing");
            // The step is a setting.
            ((ControllerStationData) st.data()).step = 2.5;
            press(s, st, StationLayout.LOWER);
            release(s, st);
            helper.assertTrue(out(st, 0) == 94.5, "Step 2.5: " + out(st, 0));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void inAutoTheBarShowsTheBoundDemandAndTheStationOnlyMovesItsOwnSetpoint(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "cs-auto", s -> {
            PanelComponent st = s.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
            PanelBlockEntity panel = s.be();
            op(s, st, OperateAction.SET, 30.0);
            panel.setInput(st, ControllerStationType.IN_AUTO_DEMAND, 70.0);
            helper.assertTrue(ControllerStationType.shownDemand(st) == 30.0, "in MAN the bar shows the station's own demand");
            press(s, st, StationLayout.AUTO);
            helper.assertTrue(out(st, 1) == 1.0, "AUTO: mode output 1");
            helper.assertTrue(ControllerStationType.shownDemand(st) == 70.0, "in AUTO the bar shows the bound demand: " + ControllerStationType.shownDemand(st));
            helper.assertTrue(out(st, 0) == 30.0, "the station's own demand output is left where MAN left it");
            helper.assertTrue(!op(s, st, OperateAction.SET, 55.0) && out(st, 0) == 30.0, "a program may not set the demand while in AUTO");
            // No bound setpoint: RAISE and LOWER move the local setpoint (1 % of a 0 to 100 span a press).
            helper.assertTrue(out(st, 4) == 50.0, "the local setpoint starts mid-span: " + out(st, 4));
            press(s, st, StationLayout.RAISE);
            release(s, st);
            helper.assertTrue(out(st, 4) == 51.0 && out(st, 0) == 30.0, "RAISE in AUTO moves the setpoint, not the demand: " + out(st, 4));
            helper.assertTrue(ControllerStationType.shownSetpoint(st) == 51.0, "and the pointer shows it");
            // A bound setpoint is remote: the pointer follows it and RAISE moves nothing (the pulse still shows).
            panel.setInput(st, ControllerStationType.IN_SETPOINT, 40.0);
            helper.assertTrue(ControllerStationType.shownSetpoint(st) == 40.0, "the pointer follows the bound setpoint");
            press(s, st, StationLayout.RAISE);
            helper.assertTrue(out(st, 2) == 1.0 && out(st, 4) == 51.0 && out(st, 0) == 30.0, "RAISE with a remote setpoint changes nothing but the pulse");
            release(s, st);
            // Back to MAN: the demand takes over from the bar, so the output does not jump.
            press(s, st, StationLayout.MAN);
            helper.assertTrue(out(st, 1) == 0.0 && out(st, 0) == 70.0, "MAN takes over the demand the bar showed: " + out(st, 0));
            helper.assertTrue(ControllerStationType.shownDemand(st) == 70.0, "the bar still shows 70");
            // And now the bound demand is ignored again.
            panel.setInput(st, ControllerStationType.IN_AUTO_DEMAND, 10.0);
            helper.assertTrue(ControllerStationType.shownDemand(st) == 70.0 && out(st, 0) == 70.0, "in MAN a changing bound demand is ignored");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void nothingMovesByItselfNoControlLawRunsInTheStation(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "cs-passive", s -> {
            PanelComponent st = s.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
            PanelBlockEntity panel = s.be();
            press(s, st, StationLayout.AUTO);
            op(s, st, OperateAction.TURN, 5.0);
            double sp = out(st, 4);
            // A process variable far from the setpoint, in AUTO and in MAN, for a while: no output may move.
            panel.setInput(st, ControllerStationType.IN_PV, 5.0);
            panel.setInput(st, ControllerStationType.IN_SETPOINT, 80.0);
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(out(st, 0) == 0.0 && out(st, 1) == 1.0 && out(st, 4) == sp, "AUTO with a deviation: demand " + out(st, 0)
                        + " mode " + out(st, 1) + " setpoint " + out(st, 4));
                helper.assertTrue(out(st, 2) == 0.0 && out(st, 3) == 0.0, "no button output is ever set by the station itself");
                press(s, st, StationLayout.MAN);
                panel.setInput(st, ControllerStationType.IN_PV, 95.0);
                helper.runAfterDelay(60, () -> {
                    helper.assertTrue(out(st, 0) == 0.0, "MAN with a different deviation: demand still " + out(st, 0));
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBoundProcessVariableAndAHeldButtonLetGoByTheFailSafe(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 192, ORIGIN_Z, "cs-bind", s -> {
            PanelComponent st = s.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
            PanelBlockEntity panel = s.be();
            st.setSource(ControllerStationType.IN_PV, InputSources.constant(42.0));
            panel.resample(st, true);
            helper.assertTrue(st.input(0) == 42.0 && Double.isNaN(st.input(1)) && Double.isNaN(st.input(2)),
                    "pv follows its source; setpoint and auto demand have no signal");
            press(s, st, StationLayout.RAISE);
            helper.assertTrue(out(st, 2) == 1.0 && PanelTimers.pending() > 0, "RAISE held, fail-safe armed");
            helper.runAfterDelay(45, () -> {
                helper.assertTrue(out(st, 2) == 0.0, "let go by the fail-safe");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void clickRegionsAreTheRegionsTheModelDraws(GameTestHelper helper) {
        ComponentType type = ComponentTypes.CONTROLLER_STATION;
        for (Footprint size : List.of(Footprint.of(3, 6), Footprint.of(2, 4), Footprint.of(6, 12), Footprint.of(12, 24))) {
            PanelComponent probe = new PanelComponent(1, type, 0, 0, size, "CS-1");
            for (int region : new int[] {StationLayout.AUTO, StationLayout.MAN, StationLayout.RAISE, StationLayout.LOWER}) {
                float[] at = StationLayout.centreOf(region);
                helper.assertTrue(type.clickValue(probe, at[0] * size.w(), at[1] * size.h()) == region, "region " + region + " at " + size);
            }
            helper.assertTrue(type.clickValue(probe, 0.3 * size.w(), 0.3 * size.h()) == StationLayout.NONE, "the scale window is no button at " + size);
        }
        // The four buttons never overlap, and the left one is left of the right one.
        helper.assertTrue(StationLayout.LEFT_X1 < StationLayout.RIGHT_X0 && StationLayout.LENS_Y1 < StationLayout.CAP_Y0
                && StationLayout.WIN_Y1 < StationLayout.LENS_Y0, "layout: window, lamp row, cap row, top to bottom");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void configRoundTripsAndOnlyLatchedStateIsKeptWhenNotDefault(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 256, ORIGIN_Z, "cs-config", s -> {
            PanelComponent st = s.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
            ControllerStationData data = (ControllerStationData) st.data();
            CompoundTag fresh = data.toTag();
            helper.assertTrue(!fresh.contains("Auto") && !fresh.contains("Demand") && !fresh.contains("SpFraction")
                    && fresh.getDouble("Max") == 100.0, "a new station saves its config only, not latched state: " + fresh);
            // An old tag (an empty one) loads as the defaults.
            ControllerStationData blank = new ControllerStationData();
            blank.load(new CompoundTag());
            helper.assertTrue(blank.min == 0.0 && blank.max == 100.0 && !blank.auto && blank.demand == 0.0 && blank.step == 1.0
                    && blank.units.isEmpty(), "missing keys load as the defaults");
            // Operate, then edit the config with the wrench: the latched state survives the edit.
            op(s, st, OperateAction.SET, 42.0);
            press(s, st, StationLayout.AUTO);
            op(s, st, OperateAction.TURN, 10.0);
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putDouble("Min", -50.0);
            config.putDouble("Max", 250.0);
            config.putString("Units", "psig");
            config.putDouble("Step", 5000.0);
            config.putDouble("SpStep", 2.0);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, st.id(), edit), "the wrench edit was applied");
            helper.assertTrue(data.min == -50.0 && data.max == 250.0 && data.units.equals("psig") && data.spStep == 2.0
                    && data.step == ControllerStationData.MAX_STEP, "config applied and clamped: " + data.toTag());
            helper.assertTrue(data.auto && data.demand == 42.0 && Math.abs(data.spFraction - 0.6) < 1.0e-9,
                    "mode, demand and setpoint were not touched by the edit: " + data.toTag());
            helper.assertTrue(out(st, 0) == 42.0 && out(st, 1) == 1.0 && Math.abs(out(st, 4) - (-50.0 + 0.6 * 300.0)) < 1.0e-9,
                    "outputs follow: the setpoint is in the new units: " + out(st, 4));
            // Save and reload as a world save does.
            CompoundTag saved = st.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            reloaded.type().onLoaded(reloaded);
            helper.assertTrue(reloaded.type() == ComponentTypes.CONTROLLER_STATION && reloaded.save().equals(saved), "reloads and re-saves unchanged");
            helper.assertTrue(reloaded.output(0) == 42.0 && reloaded.output(1) == 1.0 && reloaded.output(2) == 0.0 && reloaded.output(3) == 0.0
                    && Math.abs(reloaded.output(4) - st.output(4)) < 1.0e-9, "outputs are restored from the latched state, the momentary ones are off");
            helper.assertTrue(reloaded.w() == 3 && reloaded.h() == 6, "footprint kept");
        });
    }

    @GameTest(template = "empty")
    public static void printedTextScalesWithTheStationAndStaysOnIt(GameTestHelper helper) {
        PanelComponent small = new PanelComponent(1, ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6), "CS-1");
        PanelComponent big = new PanelComponent(2, ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(12, 24), "CS-2");
        ((ControllerStationData) small.data()).units = "MPa";
        ((ControllerStationData) big.data()).units = "MPa";
        var model = ComponentModels.get(ComponentTypes.CONTROLLER_STATION.id());
        List<Legends.Item> a = model.textLayout(small, 1.0f);
        List<Legends.Item> b = model.textLayout(big, 1.0f);
        helper.assertTrue(a.size() == b.size() && a.size() == 13, "same text at both sizes: " + a.size());
        for (int i = 0; i < a.size(); i++) {
            Legends.Item x = a.get(i);
            Legends.Item y = b.get(i);
            float ratio = y.capCells() / x.capCells();
            helper.assertTrue(x.text().equals(y.text()) && Math.abs(ratio - 4.0f) < 0.2f, "\"" + x.text() + "\" is four times as big on the 4 times bigger station: " + ratio);
            helper.assertTrue(x.x() >= 0 && x.x() <= small.w() && x.y() - x.capCells() / 2 >= 0 && x.y() + x.capCells() / 2 <= small.h(),
                    "\"" + x.text() + "\" is on the small station: " + x);
        }
        helper.succeed();
    }
}
