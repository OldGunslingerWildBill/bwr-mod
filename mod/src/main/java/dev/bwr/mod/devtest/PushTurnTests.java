package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelTimers;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PushTurnData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.Poses;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The ADS push-to-turn switch (NEW-HARDWARE-SPEC.md, part 3): the knob turns only while it is pushed in, the position is
 * kept and saved only when it is not AUTO, the spring return, the fail-safe release, the legends, and the model built
 * from the owner's photo. The ADS controller's own inputs are in {@link AdsPanelInputTests}.
 *
 * <p>World fixtures at 95_900 / 96_700 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PushTurnTests {
    private PushTurnTests() {
    }

    static final int ORIGIN_X = 95_900;
    static final int ORIGIN_Z = 96_700;

    private static boolean op(HardwareStage s, PanelComponent sw, OperateAction action, double value) {
        return PanelOps.operate(s.player, s.origin, sw.id(), action, value);
    }

    @GameTest(template = "empty")
    public static void switchIsRegisteredWithPositionAndPushedOutputs(GameTestHelper helper) {
        ComponentType type = ComponentTypes.PUSH_TURN_SWITCH;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("push_turn_switch"), "registered");
        helper.assertTrue(BwrItems.PUSH_TURN_SWITCH.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        var outputs = type.outputPorts(type.createData());
        helper.assertTrue(outputs.size() == 2 && outputs.get(0).key().equals("position") && outputs.get(1).key().equals("pushed"),
                "outputs: position, pushed");
        helper.assertTrue(type.isInteractive() && type.holdable() && type.scrollable(), "held to push in, scrolled to turn");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aPlainClickOnlyPushesAndTheKnobTurnsOnlyWhilePushedIn(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "pt-turn", s -> {
            PanelComponent sw = s.place(ComponentTypes.PUSH_TURN_SWITCH, 0, 0, Footprint.of(8, 8));
            helper.assertTrue(sw.output(0) == 0.0 && sw.output(1) == 0.0, "starts at AUTO, not pushed");
            // Turning without pushing: refused, nothing moves.
            helper.assertTrue(!op(s, sw, OperateAction.TURN, 1.0), "scrolling a knob that is not pushed in does nothing");
            helper.assertTrue(sw.output(0) == 0.0, "still AUTO");
            // A click (a press from either half) pushes it in and changes nothing else.
            helper.assertTrue(op(s, sw, OperateAction.PRESS, 1.0) && op(s, sw, OperateAction.PRESS, -1.0), "presses are handled");
            helper.assertTrue(sw.output(1) == 1.0 && sw.output(0) == 0.0, "pushed in, position unchanged by a click");
            // Pushed in: turn up to ARM, down to AUTO; a scroll of zero leaves it.
            helper.assertTrue(op(s, sw, OperateAction.TURN, 1.0) && sw.output(0) == 1.0, "turned to ARM while pushed");
            helper.assertTrue(op(s, sw, OperateAction.TURN, 3.0) && sw.output(0) == 1.0, "further up stays at ARM");
            helper.assertTrue(op(s, sw, OperateAction.TURN, -1.0) && sw.output(0) == 0.0, "turned back to AUTO while pushed");
            helper.assertTrue(op(s, sw, OperateAction.TURN, 1.0) && sw.output(0) == 1.0, "ARM again");
            // Let go: it stays where it was left, and no longer turns.
            helper.assertTrue(op(s, sw, OperateAction.RELEASE, 0.0) && sw.output(1) == 0.0 && sw.output(0) == 1.0,
                    "released: not pushed, still at ARM");
            helper.assertTrue(!op(s, sw, OperateAction.TURN, -1.0) && sw.output(0) == 1.0, "and it will not turn back without being pushed");
            // SET (a CC call) obeys the same rule.
            helper.assertTrue(!op(s, sw, OperateAction.SET, 0.0) && sw.output(0) == 1.0, "SET is refused while not pushed in");
            op(s, sw, OperateAction.PRESS, 1.0);
            helper.assertTrue(op(s, sw, OperateAction.SET, 0.0) && sw.output(0) == 0.0, "SET works while pushed in");
            op(s, sw, OperateAction.RELEASE, 0.0);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void springReturnBringsItBackToAutoOnRelease(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "pt-spring", s -> {
            PanelComponent sw = s.place(ComponentTypes.PUSH_TURN_SWITCH, 0, 0, Footprint.of(8, 8));
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putBoolean("Spring", true);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit), "spring configured");
            helper.assertTrue(((PushTurnData) sw.data()).spring, "spring is on");
            op(s, sw, OperateAction.PRESS, 1.0);
            op(s, sw, OperateAction.TURN, 1.0);
            helper.assertTrue(sw.output(0) == 1.0, "ARM while held");
            op(s, sw, OperateAction.RELEASE, 0.0);
            helper.assertTrue(sw.output(0) == 0.0 && sw.output(1) == 0.0 && ((PushTurnData) sw.data()).position == 0,
                    "released: back to AUTO and not pushed");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void positionIsSavedOnlyWhenNotAutoAndLegendsAreEditable(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "pt-save", s -> {
            PanelComponent sw = s.place(ComponentTypes.PUSH_TURN_SWITCH, 0, 0, Footprint.of(8, 8));
            PushTurnData data = (PushTurnData) sw.data();
            helper.assertTrue(data.toTag().isEmpty(), "a switch at AUTO with default settings saves nothing: " + data.toTag());
            helper.assertTrue(data.legend1.equals("AUTO") && data.legend2.equals("ARM")
                    && Legends.text("push_turn_switch", "pos1", sw).equals("AUTO") && Legends.text("push_turn_switch", "pos2", sw).equals("ARM"),
                    "legends AUTO and ARM");
            op(s, sw, OperateAction.PRESS, 1.0);
            op(s, sw, OperateAction.TURN, 1.0);
            op(s, sw, OperateAction.RELEASE, 0.0);
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putString("Legend2", "FIRE");
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, sw.id(), edit), "legend edited");
            CompoundTag saved = sw.save();
            CompoundTag dataTag = saved.getCompound("Data");
            helper.assertTrue(dataTag.getInt("Position") == 1 && dataTag.getString("Legend2").equals("FIRE") && !dataTag.contains("Legend1")
                    && !dataTag.contains("Spring"), "saved: position and the changed legend only: " + dataTag);
            PanelComponent reloaded = PanelComponent.load(saved);
            reloaded.type().onLoaded(reloaded);
            helper.assertTrue(reloaded.output(0) == 1.0 && reloaded.output(1) == 0.0 && reloaded.save().equals(saved),
                    "a reload puts the knob back at ARM, not pushed, and re-saves unchanged");
            // And back to AUTO: the key goes away again.
            op(s, sw, OperateAction.PRESS, 1.0);
            op(s, sw, OperateAction.TURN, -1.0);
            op(s, sw, OperateAction.RELEASE, 0.0);
            helper.assertTrue(!sw.save().getCompound("Data").contains("Position"), "AUTO is the default and is not saved");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aHeldKnobIsLetGoByTheFailSafeTimer(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 192, ORIGIN_Z, "pt-failsafe", s -> {
            PanelComponent sw = s.place(ComponentTypes.PUSH_TURN_SWITCH, 0, 0, Footprint.of(8, 8));
            op(s, sw, OperateAction.PRESS, 1.0);
            helper.assertTrue(sw.output(1) == 1.0 && PanelTimers.pending() > 0, "pushed in, fail-safe armed");
            helper.runAfterDelay(45, () -> {
                helper.assertTrue(sw.output(1) == 0.0, "the knob was let go by the fail-safe");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void modelKnobTurnsAndSlidesInsideItsFootprint(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("push_turn_switch");
        BakedPanelModel.Part knob = model.part("knob");
        helper.assertTrue(knob != null && knob.hasPivot && knob.hasTravel && Math.abs(knob.axisZ) > 0.99f,
                "the knob turns about the panel normal and slides along it");
        helper.assertTrue(model.part("body") != null && model.part("body").faceCount > 0, "a body");
        helper.assertTrue(model.label("pos1") != null && model.label("pos2") != null && model.label("caption") != null
                && model.label("pos1").x < model.label("pos2").x, "AUTO left, ARM right, a caption");
        // The knob reaches no further than its pivot plus its radius at any turn, pushed or not, and stays in front of the panel.
        float radius = 0.0f;
        for (int f = 0; f < knob.faceCount; f++) {
            for (int c = 0; c < 4; c++) {
                radius = Math.max(radius, (float) Math.hypot(knob.corner(f, c, 0) - knob.pivotX, knob.corner(f, c, 1) - knob.pivotY));
                float pushed = knob.corner(f, c, 2) + knob.axisZ * knob.travel;
                helper.assertTrue(pushed > -0.06f, "the pushed in knob stays out of the panel's back");
            }
        }
        helper.assertTrue(knob.pivotX - radius >= 0 && knob.pivotX + radius <= model.footprintW && knob.pivotY - radius >= 0
                && knob.pivotY + radius <= model.footprintH, "any turn of the knob is inside the footprint: radius " + radius);
        helper.assertTrue(Math.abs(Poses.detentAngle(0, 2)) == 45.0f && Poses.detentAngle(0, 2) < 0 && Poses.detentAngle(1, 2) > 0,
                "AUTO turns the knob 45 degrees to the left, ARM 45 degrees to the right");
        PanelComponent probe = new PanelComponent(1, ComponentTypes.PUSH_TURN_SWITCH, 0, 0, Footprint.of(8, 8), "PT-1");
        List<Legends.Item> items = Legends.items(model, Placement.fit(model, 8, 8), "push_turn_switch", probe);
        helper.assertTrue(items.size() == 3 && items.stream().map(Legends.Item::text).toList().containsAll(List.of("AUTO", "ARM", "PT-1")),
                "legend items: " + items);
        helper.succeed();
    }
}
