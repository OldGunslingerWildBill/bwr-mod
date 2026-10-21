package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelTimers;
import dev.bwr.mod.panel.client.ComponentModels;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.JoystickData;
import dev.bwr.mod.panel.component.JoystickType;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The joystick bypass switch ({@code bwr:joystick_switch}): one output per direction and a position, never two directions
 * at once, the click regions, the lever staying where it was put (and saved) or springing back, the fail-safe, the
 * config round trip and that its legends scale with it.
 *
 * <p>World fixture at 182_200 / 181_000 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class JoystickTests {
    private JoystickTests() {
    }

    static final int ORIGIN_X = 182_200;
    static final int ORIGIN_Z = 181_000;

    /** A click on the plate at fractions {@code (u, v)}, the way the client sends it. */
    private static boolean click(HardwareStage s, PanelComponent sw, double u, double v) {
        return PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.PRESS, sw.type().clickValue(sw, u * sw.w(), v * sw.h()));
    }

    private static void release(HardwareStage s, PanelComponent sw) {
        PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.RELEASE, 0.0);
    }

    private static String outputs(PanelComponent sw) {
        return (int) sw.output(0) + "" + (int) sw.output(1) + (int) sw.output(2) + (int) sw.output(3) + "/" + (int) sw.output(4);
    }

    @GameTest(template = "empty")
    public static void joystickIsRegisteredWithOneOutputPerDirection(GameTestHelper helper) {
        ComponentType type = ComponentTypes.JOYSTICK_SWITCH;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("joystick_switch"), "registered as joystick_switch");
        helper.assertTrue(BwrItems.JOYSTICK_SWITCH.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        List<String> out = type.outputPorts(type.createData()).stream().map(PortSpec::key).toList();
        helper.assertTrue(out.equals(List.of("up", "down", "left", "right", "position")) && type.inputPorts(type.createData()).isEmpty(),
                "outputs: " + out);
        helper.assertTrue(type.isInteractive() && type.holdable(), "operated, and held for the spring return");
        List<Footprint> sizes = type.footprints();
        helper.assertTrue(type.realSizes() == 3 && sizes.get(type.realDefaultIndex()).equals(Footprint.of(2, 2))
                && sizes.contains(Footprint.of(1, 1)) && sizes.contains(Footprint.of(4, 4)),
                "a new item starts at twice the real size, 2 x 2, with the exact 1 x 1 listed: " + sizes);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void clickRegionsAreTheCentreAndTheFourSides(GameTestHelper helper) {
        helper.assertTrue(JoystickType.positionAt(0.5, 0.5) == JoystickData.CENTRE && JoystickType.positionAt(0.55, 0.45) == JoystickData.CENTRE,
                "the boot is the centre");
        helper.assertTrue(JoystickType.positionAt(0.5, 0.05) == JoystickData.UP && JoystickType.positionAt(0.5, 0.95) == JoystickData.DOWN
                && JoystickType.positionAt(0.05, 0.5) == JoystickData.LEFT && JoystickType.positionAt(0.95, 0.5) == JoystickData.RIGHT,
                "the four sides");
        helper.assertTrue(JoystickType.positionAt(0.1, 0.2) == JoystickData.LEFT && JoystickType.positionAt(0.2, 0.1) == JoystickData.UP
                && JoystickType.positionAt(0.9, 0.8) == JoystickData.RIGHT && JoystickType.positionAt(0.8, 0.9) == JoystickData.DOWN
                && JoystickType.positionAt(0.0, 0.0) == JoystickData.UP, "a corner goes to the nearer axis, up and down winning a tie");
        ComponentType type = ComponentTypes.JOYSTICK_SWITCH;
        for (Footprint size : List.of(Footprint.of(1, 1), Footprint.of(2, 2), Footprint.of(8, 8), Footprint.of(16, 16))) {
            PanelComponent probe = new PanelComponent(1, type, 0, 0, size, "JS-1");
            helper.assertTrue(type.clickValue(probe, size.w() * 0.5, size.h() * 0.1) == 1 + JoystickData.UP
                    && type.clickValue(probe, size.w() * 0.5, size.h() * 0.5) == 1 + JoystickData.CENTRE
                    && type.clickValue(probe, size.w() * 0.9, size.h() * 0.5) == 1 + JoystickData.RIGHT, "click values at " + size);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theLeverStaysWhereItIsPutAndOnlyOneDirectionIsEverOn(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "js-hold", s -> {
            PanelComponent sw = s.place(ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(8, 8));
            helper.assertTrue(outputs(sw).equals("0000/0"), "starts at the centre: " + outputs(sw));
            helper.assertTrue(click(s, sw, 0.5, 0.05) && outputs(sw).equals("1000/1"), "up: " + outputs(sw));
            helper.assertTrue(click(s, sw, 0.95, 0.5) && outputs(sw).equals("0001/4"), "right (up let go): " + outputs(sw));
            helper.assertTrue(click(s, sw, 0.5, 0.95) && outputs(sw).equals("0100/2"), "down: " + outputs(sw));
            helper.assertTrue(click(s, sw, 0.05, 0.5) && outputs(sw).equals("0010/3"), "left: " + outputs(sw));
            release(s, sw);
            helper.assertTrue(outputs(sw).equals("0010/3"), "without a spring it stays after a release: " + outputs(sw));
            helper.assertTrue(click(s, sw, 0.5, 0.5) && outputs(sw).equals("0000/0"), "a click on the boot centres it: " + outputs(sw));
            // A program sets a position; nonsense is refused.
            helper.assertTrue(PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, 2.0) && outputs(sw).equals("0100/2"), "SET 2 is down");
            helper.assertTrue(!PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.SET, 9.0) && outputs(sw).equals("0100/2"), "SET 9 is refused");
            helper.assertTrue(!PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.PRESS, 0.0), "a press of nothing is refused");
            helper.assertTrue(!PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.TURN, 1.0), "scrolling does nothing");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSpringReturnLeverGoesBackWhenLetGoAndByTheFailSafe(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "js-spring", s -> {
            PanelComponent sw = s.place(ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(8, 8));
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putBoolean("Spring", true);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit) && ((JoystickData) sw.data()).spring, "spring on");
            click(s, sw, 0.5, 0.05);
            helper.assertTrue(outputs(sw).equals("1000/1") && PanelTimers.pending() > 0, "up while held, fail-safe armed: " + outputs(sw));
            release(s, sw);
            helper.assertTrue(outputs(sw).equals("0000/0"), "let go: back to the centre: " + outputs(sw));
            click(s, sw, 0.05, 0.5);
            helper.assertTrue(outputs(sw).equals("0010/3"), "left while held");
            helper.runAfterDelay(45, () -> {
                helper.assertTrue(outputs(sw).equals("0000/0"), "the fail-safe let it go: " + outputs(sw));
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void positionIsSavedOnlyOffCentreWithoutSpringAndConfigRoundTrips(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "js-save", s -> {
            PanelComponent sw = s.place(ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(8, 8));
            JoystickData data = (JoystickData) sw.data();
            helper.assertTrue(!data.toTag().contains("Position") && !data.toTag().contains("Spring"), "a centred lever saves no position: " + data.toTag());
            click(s, sw, 0.95, 0.5);
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putString("LegendUp", "BYPASS-X");
            config.putString("LegendLeft", "NORM");
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit), "legends edited");
            helper.assertTrue(data.legendUp.equals("BYPASS") && data.legendLeft.equals("NORM") && data.position == JoystickData.RIGHT,
                    "legend cut to 6 characters, the lever not moved by the edit: " + data.toTag());
            CompoundTag saved = sw.save();
            helper.assertTrue(saved.getCompound("Data").getInt("Position") == JoystickData.RIGHT, "saved at RIGHT");
            PanelComponent reloaded = PanelComponent.load(saved);
            reloaded.type().onLoaded(reloaded);
            helper.assertTrue(outputs(reloaded).equals("0001/4") && reloaded.save().equals(saved), "a reload puts the lever and the outputs back");
            // A spring lever is never saved off centre, and an old (empty) tag is a centred, default switch.
            data.spring = true;
            data.position = JoystickData.UP;
            JoystickData sprung = new JoystickData();
            sprung.load(data.toTag());
            helper.assertTrue(sprung.spring && sprung.position == JoystickData.CENTRE, "a sprung lever loads centred");
            JoystickData blank = new JoystickData();
            blank.load(new CompoundTag());
            helper.assertTrue(!blank.spring && blank.position == JoystickData.CENTRE && blank.legendRight.equals("RIGHT"), "an empty tag loads as the defaults");
        });
    }

    @GameTest(template = "empty")
    public static void legendsScaleWithThePlateAndStayOnIt(GameTestHelper helper) {
        PanelComponent small = new PanelComponent(1, ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(1, 1), "JS-1");
        PanelComponent big = new PanelComponent(2, ComponentTypes.JOYSTICK_SWITCH, 0, 0, Footprint.of(16, 16), "JS-2");
        var model = ComponentModels.get(ComponentTypes.JOYSTICK_SWITCH.id());
        List<Legends.Item> a = model.textLayout(small, 1.0f);
        List<Legends.Item> b = model.textLayout(big, 1.0f);
        helper.assertTrue(a.size() == 4 && b.size() == 4, "four legends");
        for (int i = 0; i < a.size(); i++) {
            float ratio = b.get(i).capCells() / a.get(i).capCells();
            helper.assertTrue(Math.abs(ratio - 16.0f) < 0.5f, "\"" + a.get(i).text() + "\" is 16 times as big on the 16 times bigger plate: " + ratio);
            Legends.Item x = a.get(i);
            float half = x.capCells() * GaugeScale.textCaps(x.text()) / 2.0f;
            boolean sideways = x.rotation() != 0.0f;
            float halfX = sideways ? x.capCells() / 2.0f : half;
            float halfY = sideways ? half : x.capCells() / 2.0f;
            helper.assertTrue(x.x() - halfX >= -1.0e-4f && x.x() + halfX <= small.w() + 1.0e-4f && x.y() - halfY >= -1.0e-4f
                    && x.y() + halfY <= small.h() + 1.0e-4f, "\"" + x.text() + "\" lies on the plate: " + x);
        }
        helper.succeed();
    }
}
