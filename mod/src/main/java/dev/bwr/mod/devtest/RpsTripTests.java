package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelTimers;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RpsTripData;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The RPS trip assembly (NEW-HARDWARE-SPEC.md, part 2): two momentary outputs picked by the click position, the
 * fail-safe release, the editable legends, the wiring to plant commands (nothing acts on its own), and the owner's
 * model with its two caps.
 *
 * <p>World fixtures at 95_900 / 96_300 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RpsTripTests {
    private RpsTripTests() {
    }

    static final int ORIGIN_X = 95_900;
    static final int ORIGIN_Z = 96_300;

    private static boolean press(HardwareStage s, PanelComponent trip, double cellX) {
        return PanelOps.operate(s.player, s.origin, trip.id(), OperateAction.PRESS, trip.type().clickValue(trip, cellX, 4.0));
    }

    @GameTest(template = "empty")
    public static void tripAssemblyIsRegisteredWithTwoMomentaryOutputs(GameTestHelper helper) {
        ComponentType type = ComponentTypes.RPS_TRIP;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("rps_trip"), "registered as rps_trip");
        helper.assertTrue(BwrItems.RPS_TRIP.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        var outputs = type.outputPorts(type.createData());
        helper.assertTrue(outputs.size() == 2 && outputs.get(0).key().equals("a") && outputs.get(1).key().equals("b")
                && type.inputPorts(type.createData()).isEmpty(), "two outputs, a and b, and no inputs");
        helper.assertTrue(type.isInteractive() && type.holdable(), "operated, and held like a pushbutton");
        PanelComponent probe = new PanelComponent(1, type, 0, 0, Footprint.of(16, 8), "RPS-1");
        helper.assertTrue(type.clickValue(probe, 1.0, 4.0) < 0 && type.clickValue(probe, 7.9, 4.0) < 0
                && type.clickValue(probe, 8.1, 4.0) > 0 && type.clickValue(probe, 15.0, 4.0) > 0,
                "the left half of the cell run is A (-1), the right half B (+1)");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void clickPositionPicksTheButtonAndTheOtherLetsGo(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "rps-click", s -> {
            PanelComponent trip = s.place(ComponentTypes.RPS_TRIP, 0, 0, Footprint.of(16, 8));
            helper.assertTrue(press(s, trip, 2.0), "the press was handled");
            helper.assertTrue(trip.output(0) == 1.0 && trip.output(1) == 0.0, "a click on the left half holds A only: " + trip.output(0) + " " + trip.output(1));
            helper.assertTrue(press(s, trip, 12.0), "the second press was handled");
            helper.assertTrue(trip.output(0) == 0.0 && trip.output(1) == 1.0, "moving to the right half lets go of A and holds B");
            helper.assertTrue(PanelOps.operate(s.player, s.origin, trip.id(), OperateAction.RELEASE, 0.0), "release was handled");
            helper.assertTrue(trip.output(0) == 0.0 && trip.output(1) == 0.0, "letting go clears both");
            // A scroll or a value from nowhere does nothing.
            helper.assertTrue(!PanelOps.operate(s.player, s.origin, trip.id(), OperateAction.TURN, 1.0)
                    && !PanelOps.operate(s.player, s.origin, trip.id(), OperateAction.PRESS, 0.0), "only a press with a side is a press");
            helper.assertTrue(trip.output(0) == 0.0 && trip.output(1) == 0.0, "and nothing moved");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aHeldButtonIsLetGoByTheFailSafeTimer(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "rps-failsafe", s -> {
            PanelComponent trip = s.place(ComponentTypes.RPS_TRIP, 0, 0, Footprint.of(16, 8));
            helper.assertTrue(press(s, trip, 12.0) && trip.output(1) == 1.0, "B is held");
            helper.assertTrue(PanelTimers.pending() > 0, "the fail-safe is armed");
            // The client stopped repeating PRESS (the player dropped off): the timer lets go after HOLD_FAILSAFE_TICKS (30).
            helper.runAfterDelay(45, () -> {
                helper.assertTrue(trip.output(0) == 0.0 && trip.output(1) == 0.0, "the held button was let go by the fail-safe");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void eachOutputIsWiredToItsOwnPlantCommandAndNothingActsAlone(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "rps-wiring", s -> {
            // Two relief valves stand in for the reactor's two scram channels: the commands the player would wire.
            BlockPos a = s.origin.offset(0, 2, 6);
            BlockPos b = s.origin.offset(3, 2, 6);
            try {
                s.level.setBlock(a, BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState(), 2);
                s.level.setBlock(b, BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState(), 2);
                SafetyReliefValveBlockEntity valveA = (SafetyReliefValveBlockEntity) s.level.getBlockEntity(a);
                SafetyReliefValveBlockEntity valveB = (SafetyReliefValveBlockEntity) s.level.getBlockEntity(b);
                PanelComponent trip = s.place(ComponentTypes.RPS_TRIP, 0, 0, Footprint.of(16, 8));
                trip.addSink(0, new PlantSink(a, "open", 1.0));
                trip.addSink(1, new PlantSink(b, "open", 1.0));
                helper.assertTrue(!valveA.isOpen() && !valveB.isOpen(), "nothing acts on its own: both closed before any press");
                press(s, trip, 2.0);
                helper.assertTrue(valveA.isOpen() && !valveB.isOpen(), "button A drives its command only");
                PanelOps.operate(s.player, s.origin, trip.id(), OperateAction.RELEASE, 0.0);
                helper.assertTrue(!valveA.isOpen(), "the follow-mode command ends when the button is let go");
                press(s, trip, 12.0);
                helper.assertTrue(!valveA.isOpen() && valveB.isOpen(), "button B drives the other");
                PanelOps.operate(s.player, s.origin, trip.id(), OperateAction.RELEASE, 0.0);
                helper.assertTrue(!valveB.isOpen(), "and lets go");
            } finally {
                s.level.setBlock(a, Blocks.AIR.defaultBlockState(), 2);
                s.level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void legendsDefaultEditAndSaveOnlyWhenChanged(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 192, ORIGIN_Z, "rps-legends", s -> {
            PanelComponent trip = s.place(ComponentTypes.RPS_TRIP, 0, 0, Footprint.of(16, 8));
            RpsTripData data = (RpsTripData) trip.data();
            helper.assertTrue(data.legendA.equals("RPS A TRIP") && data.legendB.equals("RPS B TRIP"), "default legends");
            helper.assertTrue(data.toTag().isEmpty(), "defaults save nothing: " + data.toTag());
            helper.assertTrue(Legends.text("rps_trip", "legend_a", trip).equals("RPS A TRIP")
                    && Legends.text("rps_trip", "legend_b", trip).equals("RPS B TRIP") && Legends.text("rps_trip", "legend_c", trip) == null,
                    "the model's legend anchors read the data");
            helper.assertTrue(trip.type().configSchema().stream().map(ConfigField::key).toList().equals(List.of("LegendA", "LegendB")),
                    "legends are the editable fields");
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putString("LegendA", "RX SCRAM A");
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, trip.id(), edit), "wrench edit applied");
            helper.assertTrue(data.legendA.equals("RX SCRAM A") && data.legendB.equals("RPS B TRIP"), "only the edited legend changed");
            CompoundTag saved = trip.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            helper.assertTrue(reloaded.type() == ComponentTypes.RPS_TRIP && reloaded.data().toTag().equals(data.toTag())
                    && reloaded.save().equals(saved), "the legends reload and the save is a fixed point: " + saved);
            helper.assertTrue(!data.toTag().contains("LegendB"), "the untouched legend is not saved");
        });
    }

    @GameTest(template = "empty")
    public static void modelHasTwoCapsAndTwoLegendAnchorsInsideItsFootprint(GameTestHelper helper) {
        BakedPanelModel model = HardwareStage.shipped("rps_trip_assembly");
        for (String name : new String[] {"cap_a", "cap_b"}) {
            BakedPanelModel.Part cap = model.part(name);
            helper.assertTrue(cap != null && cap.hasTravel && cap.travel > 0.1f && cap.axisZ < -0.99f && !cap.hasPivot,
                    name + " slides into the panel");
            for (float slide : new float[] {0.0f, 1.0f}) {
                for (int f = 0; f < cap.faceCount; f++) {
                    for (int c = 0; c < 4; c++) {
                        float z = cap.corner(f, c, 2) + cap.axisZ * cap.travel * slide;
                        helper.assertTrue(z >= -0.2f && cap.corner(f, c, 0) >= 0 && cap.corner(f, c, 0) <= model.footprintW + 0.02f
                                && cap.corner(f, c, 1) >= 0 && cap.corner(f, c, 1) <= model.footprintH + 0.02f,
                                name + " stays inside the footprint at slide " + slide + " (it sinks into the plate, like every pushbutton cap)");
                    }
                }
            }
        }
        helper.assertTrue(model.label("legend_a") != null && model.label("legend_b") != null
                && model.label("legend_a").x < model.label("legend_b").x, "legend_a is over the left button, legend_b the right");
        // The two legends come out as separate items inside the component, A left of B.
        PanelComponent probe = new PanelComponent(1, ComponentTypes.RPS_TRIP, 0, 0, Footprint.of(16, 8), "RPS-1");
        List<Legends.Item> items = Legends.items(model, Placement.fit(model, 16, 8), "rps_trip", probe);
        helper.assertTrue(items.size() == 2 && items.get(0).text().equals("RPS A TRIP") && items.get(1).text().equals("RPS B TRIP")
                && items.get(0).x() < items.get(1).x() && items.get(0).x() > 0 && items.get(1).x() < 16, "legend items: " + items);
        helper.succeed();
    }
}
