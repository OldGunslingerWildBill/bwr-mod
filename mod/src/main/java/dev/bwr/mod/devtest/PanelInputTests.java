package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.BarGraphData;
import dev.bwr.mod.panel.component.BarGraphLogic;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.KeySwitchData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelKeyItem;
import dev.bwr.mod.panel.component.PushbuttonData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.component.SetpointKnobData;
import dev.bwr.mod.panel.component.ThumbwheelData;
import dev.bwr.mod.panel.component.ToggleSwitchData;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Control panel inputs, indicators and annunciators (spec 2.1, first two
 * tables): placement and operation of each type, spring return, key refusal,
 * the annunciator sequence with group ACK and RESET, and the save / network
 * round trip of every one.
 *
 * <p>Same discipline as {@link PanelFoundationTests}: each test builds at its own
 * far-away fixture, force-loads the chunks it needs, empties the volume first
 * (a second run on a persistent world starts clean) and releases the chunks in
 * every outcome. Tests that wait for a timer (spring return, fail-safe) run
 * their checks a fixed number of ticks later.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelInputTests {
    private PanelInputTests() {
    }

    private static final Direction FACING = Direction.NORTH;

    /** A cleared, force-loaded stage with a survival fake player standing in front of the panels. */
    private static final class Stage {
        final ServerLevel level;
        final BlockPos origin;
        final FakePlayer player;
        private Runnable afterCheck;

        private Stage(GameTestHelper helper, int slot) {
            level = helper.getLevel();
            origin = new BlockPos(44_900 + slot * 64, 110, 47_300);
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("panel-input-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "panel-input-test-" + slot));
        }

        /**
         * Runs {@code body} on a clean, force-loaded fixture two ticks after forcing the chunks. If the body
         * registered an {@link #after} check it runs {@code afterTicks} later, and the chunks are released in
         * every outcome, including a failure in either phase.
         */
        static void run(GameTestHelper helper, int slot, int afterTicks, java.util.function.Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            helper.runAfterDelay(2, () -> {
                try {
                    s.clear();
                    s.player.setGameMode(GameType.SURVIVAL);
                    s.player.getInventory().clearContent();
                    s.player.setShiftKeyDown(false);
                    s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
                    body.accept(s);
                } catch (RuntimeException | Error e) {
                    s.release();
                    throw e;
                }
                if (s.afterCheck == null) {
                    s.release();
                    helper.succeed();
                } else {
                    helper.runAfterDelay(afterTicks, () -> {
                        try {
                            s.afterCheck.run();
                        } finally {
                            s.release();
                        }
                        helper.succeed();
                    });
                }
            });
        }

        static void run(GameTestHelper helper, int slot, java.util.function.Consumer<Stage> body) {
            run(helper, slot, 3, body);
        }

        void after(Runnable check) {
            afterCheck = check;
        }

        private AABB area() {
            return new AABB(origin).inflate(24, 12, 12);
        }

        private void hold(boolean force) {
            for (int x = (origin.getX() - 40) >> 4; x <= (origin.getX() + 40) >> 4; x++) {
                for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                    FixtureChunks.force(level, origin, x, z, force);
                    if (force) {
                        level.getChunk(x, z);
                    }
                }
            }
        }

        private void clear() {
            for (int x = -24; x <= 4; x++) {
                for (int y = -12; y <= 12; y++) {
                    for (int z = -6; z <= 6; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area())) {
                item.discard();
            }
        }

        BlockPos col(int column) {
            return origin.relative(FACING.getCounterClockWise(), column);
        }

        BlockPos at(int column, int row) {
            return col(column).below(row);
        }

        void wall(int columns, int rows) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    level.setBlock(at(c, r), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, FACING), 3);
                }
            }
        }

        PanelBlockEntity be(BlockPos pos) {
            return (PanelBlockEntity) level.getBlockEntity(pos);
        }

        PanelGroup.Result place(Item item, int variant, BlockPos pos, int cx, int cy) {
            ItemStack stack = new ItemStack(item);
            if (variant >= 0) { // The fixture explicitly requests a footprint, including index zero.
                stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), variant);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return PanelOps.place(player, InteractionHand.MAIN_HAND, pos, cx, cy);
        }

        /** Places an item that must fit and returns the new component (the last one the block owns). */
        PanelComponent put(GameTestHelper helper, Item item, int variant, BlockPos pos, int cx, int cy) {
            PanelGroup.Result result = place(item, variant, pos, cx, cy);
            helper.assertTrue(result == PanelGroup.Result.OK, item + " at " + cx + "," + cy + ": " + result);
            List<PanelComponent> owned = be(pos).owned();
            return owned.get(owned.size() - 1);
        }

        void wrench() {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
        }

        /** Applies a config edit with the wrench (which replaces whatever is in the main hand). */
        boolean configure(BlockPos pos, PanelComponent component, CompoundTag config) {
            wrench();
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            return PanelOps.configure(player, pos, component.id(), edit);
        }

        boolean operate(BlockPos pos, PanelComponent component, OperateAction action, double value) {
            return PanelOps.operate(player, pos, component.id(), action, value);
        }

        void release() {
            clear();
            hold(false);
        }
    }

    private static CompoundTag config(Object... pairs) {
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value instanceof Integer n) {
                tag.putInt(key, n);
            } else if (value instanceof Double d) {
                tag.putDouble(key, d);
            } else if (value instanceof Boolean b) {
                tag.putBoolean(key, b);
            } else {
                tag.putString(key, (String) value);
            }
        }
        return tag;
    }

    // ---- pure state machines (no world)

    @GameTest(template = "empty")
    public static void annunciatorLogicFollowsSequenceA(GameTestHelper helper) {
        AnnunciatorLogic logic = new AnnunciatorLogic();
        helper.assertTrue(logic.display() == AnnunciatorLogic.Display.OFF, "starts dark");
        helper.assertTrue(!logic.update(false) && logic.display() == AnnunciatorLogic.Display.OFF, "a clear condition changes nothing");
        helper.assertTrue(logic.update(true) && logic.display() == AnnunciatorLogic.Display.FLASH, "alarm flashes");
        helper.assertTrue(!logic.update(true) && logic.state() == AnnunciatorLogic.State.ALARM, "a persisting condition stays in alarm");
        helper.assertTrue(!logic.reset(), "reset never silences an unacknowledged alarm");
        helper.assertTrue(logic.acknowledge() && logic.display() == AnnunciatorLogic.Display.STEADY, "acknowledge goes steady");
        helper.assertTrue(!logic.acknowledge(), "acknowledging twice is a no-op");
        helper.assertTrue(!logic.reset(), "reset does nothing while the condition holds");
        helper.assertTrue(logic.update(false) && logic.display() == AnnunciatorLogic.Display.OFF, "acknowledged and cleared goes dark");

        // Cleared before it was acknowledged: keeps flashing until acknowledged, then goes out.
        logic.update(true);
        logic.update(false);
        helper.assertTrue(logic.display() == AnnunciatorLogic.Display.FLASH, "an unacknowledged cleared alarm keeps flashing");
        helper.assertTrue(logic.acknowledge() && logic.display() == AnnunciatorLogic.Display.OFF, "acknowledging it puts it out");

        // Manual reset: acknowledged and cleared stays steady until RESET.
        AnnunciatorLogic manual = new AnnunciatorLogic();
        manual.setManualReset(true);
        manual.update(true);
        manual.acknowledge();
        manual.update(false);
        helper.assertTrue(manual.display() == AnnunciatorLogic.Display.STEADY, "manual mode holds an acknowledged cleared alarm");
        helper.assertTrue(manual.reset() && manual.display() == AnnunciatorLogic.Display.OFF, "reset clears it");
        manual.update(true);
        manual.update(false);
        helper.assertTrue(manual.acknowledge() && manual.display() == AnnunciatorLogic.Display.STEADY,
                "manual mode: acknowledging a cleared alarm leaves it steady for reset");

        AnnunciatorLogic restored = new AnnunciatorLogic();
        logic.update(true);
        restored.restore(logic.stateId(), logic.condition());
        helper.assertTrue(restored.state() == logic.state() && restored.condition(), "state round trips");
        restored.restore(99, false);
        helper.assertTrue(restored.state() == AnnunciatorLogic.State.NORMAL, "a bad saved state means normal");

        helper.assertTrue(AnnunciatorLogic.evaluate(AnnunciatorLogic.ABOVE, 2, 1) && !AnnunciatorLogic.evaluate(AnnunciatorLogic.ABOVE, 1, 1),
                "above is strict");
        helper.assertTrue(AnnunciatorLogic.evaluate(AnnunciatorLogic.BELOW, 0, 1) && !AnnunciatorLogic.evaluate(AnnunciatorLogic.BELOW, 1, 1),
                "below is strict");
        helper.assertTrue(AnnunciatorLogic.evaluate(AnnunciatorLogic.EQUALS, 1.0, 1.0) && !AnnunciatorLogic.evaluate(AnnunciatorLogic.EQUALS, 1.1, 1.0),
                "equals");
        helper.assertTrue(!AnnunciatorLogic.evaluate(AnnunciatorLogic.ABOVE, Double.NaN, 1)
                && !AnnunciatorLogic.evaluate(AnnunciatorLogic.BELOW, Double.NaN, 1)
                && !AnnunciatorLogic.evaluate(AnnunciatorLogic.EQUALS, Double.NaN, Double.NaN), "no signal is never an alarm");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void barGraphLogicLightsSegmentsAndBands(GameTestHelper helper) {
        helper.assertTrue(BarGraphLogic.lit(Double.NaN, 0, 100, 10) == 0, "no signal lights nothing");
        helper.assertTrue(BarGraphLogic.lit(0, 0, 100, 10) == 0 && BarGraphLogic.lit(100, 0, 100, 10) == 10, "ends of the scale");
        helper.assertTrue(BarGraphLogic.lit(50, 0, 100, 10) == 5 && BarGraphLogic.lit(54, 0, 100, 10) == 5, "rounds to the nearest segment");
        helper.assertTrue(BarGraphLogic.lit(-40, 0, 100, 10) == 0 && BarGraphLogic.lit(400, 0, 100, 10) == 10, "clamped");
        helper.assertTrue(BarGraphLogic.lit(5, 0, 0, 10) == 0 && BarGraphLogic.lit(5, 0, 100, 0) == 0, "a degenerate scale lights nothing");
        helper.assertTrue(BarGraphLogic.band(0, 0, 100, 10, 70, 90) == BarGraphLogic.NORMAL, "bottom is normal");
        helper.assertTrue(BarGraphLogic.band(7, 0, 100, 10, 70, 90) == BarGraphLogic.WARN, "75 is warn");
        helper.assertTrue(BarGraphLogic.band(9, 0, 100, 10, 70, 90) == BarGraphLogic.ALARM, "95 is alarm");
        helper.assertTrue(BarGraphLogic.band(9, 0, 100, 10, 30, 10) == BarGraphLogic.NORMAL
                && BarGraphLogic.band(2, 0, 100, 10, 30, 10) == BarGraphLogic.WARN
                && BarGraphLogic.band(0, 0, 100, 10, 30, 10) == BarGraphLogic.ALARM, "low side bands when alarm is below warn");
        helper.succeed();
    }

    // ---- placement

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void everyInputAndIndicatorPlacesAtEveryFootprint(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall(4, 6);
            List<ComponentType> types = List.of(ComponentTypes.PUSHBUTTON, ComponentTypes.TOGGLE_SWITCH,
                    ComponentTypes.ROTARY_SELECTOR, ComponentTypes.CONTROL_SWITCH, ComponentTypes.KEY_SWITCH,
                    ComponentTypes.SETPOINT_KNOB, ComponentTypes.THUMBWHEEL, ComponentTypes.ANNUNCIATOR, ComponentTypes.BAR_GRAPH);
            for (ComponentType type : types) {
                for (int variant = 0; variant < type.footprints().size(); variant++) {
                    Footprint footprint = type.footprints().get(variant);
                    PanelComponent placed = null;
                    search:
                    for (int row = 0; row < 6; row++) {
                        for (int column = 0; column < 4; column++) {
                            for (int cy = 0; cy < 16; cy++) {
                                for (int cx = 0; cx < 16; cx++) {
                                    BlockPos pos = s.at(column, row);
                                    if (s.place(type.item(), variant, pos, cx, cy) == PanelGroup.Result.OK) {
                                        List<PanelComponent> owned = s.be(pos).owned();
                                        placed = owned.get(owned.size() - 1);
                                        break search;
                                    }
                                }
                            }
                        }
                    }
                    helper.assertTrue(placed != null, type.id() + " " + footprint + " found no room on a 4x6 wall");
                    helper.assertTrue(placed.type() == type && placed.w() == footprint.w() && placed.h() == footprint.h(),
                            type.id() + " keeps the footprint " + footprint);
                    helper.assertTrue(placed.label().startsWith(type.labelPrefix() + "-"), "label " + placed.label());
                    helper.assertTrue(placed.inputCount() == type.inputPorts(placed.data()).size()
                            && placed.outputCount() == type.outputPorts(placed.data()).size(), type.id() + " ports follow the type");
                }
            }
        });
    }

    // ---- inputs

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void pushbuttonIsOneWhileHeldAndLetsGoByItself(GameTestHelper helper) {
        Stage.run(helper, 1, 40, s -> {
            s.wall(2, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            PanelComponent held = s.put(helper, BwrItems.PUSHBUTTON.get(), 0, pos, 0, 0);
            PanelComponent clicked = s.put(helper, BwrItems.PUSHBUTTON.get(), 0, pos, 6, 0);
            PanelComponent abandoned = s.put(helper, BwrItems.PUSHBUTTON.get(), 0, pos, 12, 0);
            helper.assertTrue(held.w() == 6 && held.h() == 6 && held.outputCount() == 1 && held.output(0) == 0.0, "6x6, one output, idle");

            helper.assertTrue(s.operate(pos, held, OperateAction.PRESS, 0), "press is handled");
            helper.assertTrue(held.output(0) == 1.0, "1 while held");
            helper.assertTrue(s.operate(pos, held, OperateAction.PRESS, 0) && held.output(0) == 1.0, "the client's repeat keeps it at 1");
            helper.assertTrue(s.operate(pos, held, OperateAction.RELEASE, 0) && held.output(0) == 0.0, "0 on release");
            helper.assertTrue(!s.operate(pos, held, OperateAction.TURN, 1) && !s.operate(pos, held, OperateAction.SET, 1),
                    "a pushbutton ignores turning and setting");

            // The vanilla right-click fallback (no client hook): a short pulse that lets go by itself.
            helper.assertTrue(PanelOps.forwardUse(s.player, be, 6, 0, 6.5, 0.5).consumesAction(), "bare-handed click is consumed");
            helper.assertTrue(clicked.output(0) == 1.0, "the click pressed it");
            // A press whose release never comes (client gone) is let go by the fail-safe.
            s.operate(pos, abandoned, OperateAction.PRESS, 0);
            helper.assertTrue(abandoned.output(0) == 1.0, "abandoned press is down");
            s.after(() -> {
                helper.assertTrue(clicked.output(0) == 0.0, "the pulse ended by itself");
                helper.assertTrue(abandoned.output(0) == 0.0, "the fail-safe released the abandoned press");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void toggleSwitchLatchesAndFollowsScroll(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelComponent toggle = s.put(helper, BwrItems.TOGGLE_SWITCH.get(), 0, pos, 0, 0);
            helper.assertTrue(toggle.w() == 6 && toggle.h() == 8 && toggle.output(0) == 0.0, "6x8 and off");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.PRESS, 0) && toggle.output(0) == 1.0, "click turns it on");
            helper.assertTrue(((ToggleSwitchData) toggle.data()).on, "the position is latched");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.RELEASE, 0) == false && toggle.output(0) == 1.0,
                    "release does nothing to a maintained switch");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.PRESS, 0) && toggle.output(0) == 0.0, "click again turns it off");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.TURN, 3) && toggle.output(0) == 1.0, "scroll up switches on");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.TURN, 1) && toggle.output(0) == 1.0, "scroll up again stays on");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.TURN, -1) && toggle.output(0) == 0.0, "scroll down switches off");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.SET, 1) && toggle.output(0) == 1.0, "set 1");
            helper.assertTrue(s.operate(pos, toggle, OperateAction.SET, 0.2) && toggle.output(0) == 0.0, "set below one half is off");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void rotarySelectorStopsAtItsEnds(GameTestHelper helper) {
        Stage.run(helper, 3, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelComponent rotary = s.put(helper, BwrItems.ROTARY_SELECTOR.get(), 0, pos, 0, 0);
            RotarySelectorData data = (RotarySelectorData) rotary.data();
            helper.assertTrue(rotary.w() == 8 && rotary.h() == 10 && data.positions == 3 && rotary.output(0) == 0.0, "8x10, three detents");
            helper.assertTrue(s.configure(pos, rotary, config("Positions", 4, "Names", "OFF, LOW,,HIGH")), "configure");
            helper.assertTrue(data.positions == 4 && data.nameAt(0).equals("OFF") && data.nameAt(1).equals("LOW")
                    && data.nameAt(2).equals("3") && data.nameAt(3).equals("HIGH") && data.nameAt(4).equals("5"),
                    "names: blank entries fall back to the position number");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.TURN, 1) && rotary.output(0) == 1.0, "scroll up one");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.PRESS, 1) && rotary.output(0) == 2.0, "click on the right half");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.PRESS, 1) && s.operate(pos, rotary, OperateAction.PRESS, 1)
                    && rotary.output(0) == 3.0, "stops at the last detent");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.PRESS, -1) && rotary.output(0) == 2.0, "left half goes down");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.SET, 0) && rotary.output(0) == 0.0, "set 0");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.TURN, -1) && rotary.output(0) == 0.0, "stops at the first detent");
            helper.assertTrue(s.operate(pos, rotary, OperateAction.SET, 99) && rotary.output(0) == 3.0, "set is clamped to the last detent");
            // Fewer positions than the current one: the output follows the config.
            helper.assertTrue(s.configure(pos, rotary, config("Positions", 2)) && data.position == 1 && rotary.output(0) == 1.0,
                    "shrinking the selector pulls the position (and the output) into range");
            helper.assertTrue(s.configure(pos, rotary, config("Positions", 50)) && data.positions == RotarySelectorData.MAX_POSITIONS,
                    "positions are clamped to six");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void controlSwitchSpringsBackToNormalAndShowsRealState(GameTestHelper helper) {
        Stage.run(helper, 4, 14, s -> {
            s.wall(1, 2);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            PanelComponent sw = s.put(helper, BwrItems.CONTROL_SWITCH.get(), 0, pos, 0, 0);
            PanelComponent flicked = s.put(helper, BwrItems.CONTROL_SWITCH.get(), 0, pos, 8, 0);
            PanelComponent scrolled = s.put(helper, BwrItems.CONTROL_SWITCH.get(), 0, pos, 0, 12);
            helper.assertTrue(sw.w() == 8 && sw.h() == 12 && sw.outputCount() == 3 && sw.inputCount() == 2, "8x12, three outputs, two lamps");
            helper.assertTrue(((ControlSwitchData) sw.data()).spring, "spring return is the default");

            helper.assertTrue(s.operate(pos, sw, OperateAction.PRESS, -1), "hold at CLOSE");
            helper.assertTrue(sw.output(0) == -1 && sw.output(1) == 0 && sw.output(2) == 1, "position -1, close=1, open=0");
            helper.assertTrue(s.operate(pos, sw, OperateAction.PRESS, -1) && sw.output(0) == -1, "the repeat keeps it there");
            helper.assertTrue(s.operate(pos, sw, OperateAction.RELEASE, 0), "let go");
            helper.assertTrue(sw.output(0) == 0 && sw.output(1) == 0 && sw.output(2) == 0, "the spring takes it back to NORMAL");
            helper.assertTrue(s.operate(pos, sw, OperateAction.PRESS, 1) && sw.output(0) == 1 && sw.output(1) == 1 && sw.output(2) == 0, "hold at OPEN");
            helper.assertTrue(s.operate(pos, sw, OperateAction.PRESS, 0) && sw.output(0) == 0, "pressing the middle is NORMAL");
            s.operate(pos, sw, OperateAction.RELEASE, 0);

            // The lamps show whatever their inputs say, independent of the handle.
            helper.assertTrue(be.setInput(sw, 0, 1.0) && be.setInput(sw, 1, 0.0), "drive the lamps");
            helper.assertTrue(sw.input(0) == 1.0 && sw.input(1) == 0.0 && sw.output(0) == 0, "red lit, green dark, handle at NORMAL");
            helper.assertTrue(Double.isNaN(s.be(pos).component(flicked.id()).input(0)), "an unwired lamp has no signal");

            // The vanilla fallback on the right third: a pulse to OPEN, then the spring.
            helper.assertTrue(PanelOps.forwardUse(s.player, be, 8 + 6, 1, 14.5, 1.5).consumesAction(), "click consumed");
            helper.assertTrue(flicked.output(0) == 1, "right third opens");
            // A scroll flick springs back too.
            helper.assertTrue(s.operate(pos, scrolled, OperateAction.TURN, -1) && scrolled.output(0) == -1, "scroll down to CLOSE");
            helper.assertTrue(s.operate(pos, scrolled, OperateAction.TURN, -1) && scrolled.output(0) == -1, "and no further");
            s.after(() -> {
                helper.assertTrue(flicked.output(0) == 0 && flicked.output(1) == 0, "the clicked switch sprang back");
                helper.assertTrue(scrolled.output(0) == 0 && scrolled.output(2) == 0, "the scrolled switch sprang back");
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void controlSwitchWithoutSpringStaysWhereItIsPut(GameTestHelper helper) {
        Stage.run(helper, 5, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            PanelComponent sw = s.put(helper, BwrItems.CONTROL_SWITCH.get(), 0, pos, 0, 0);
            helper.assertTrue(s.configure(pos, sw, config("Spring", false, "OpenText", "START", "RedText", "RUN")), "no spring");
            s.operate(pos, sw, OperateAction.PRESS, 1);
            s.operate(pos, sw, OperateAction.RELEASE, 0);
            helper.assertTrue(sw.output(0) == 1 && sw.output(1) == 1, "stays at OPEN after release");
            var registries = s.level.registryAccess();
            PanelBlockEntity reloaded = new PanelBlockEntity(be.getBlockPos(), be.getBlockState());
            reloaded.loadWithComponents(be.saveWithoutMetadata(registries), registries);
            PanelComponent copy = reloaded.component(sw.id());
            helper.assertTrue(copy.output(0) == 1 && copy.output(1) == 1 && copy.output(2) == 0, "a latched handle comes back after a restart");
            ControlSwitchData data = (ControlSwitchData) copy.data();
            helper.assertTrue(!data.spring && data.openText.equals("START") && data.redText.equals("RUN"), "captions saved");
            // Turning the spring back on lands on NORMAL.
            helper.assertTrue(s.configure(pos, sw, config("Spring", true)) && sw.output(0) == 0 && sw.output(1) == 0,
                    "enabling the spring returns the handle to NORMAL");
            s.operate(pos, sw, OperateAction.PRESS, -1);
            var springy = new PanelBlockEntity(be.getBlockPos(), be.getBlockState());
            springy.loadWithComponents(be.saveWithoutMetadata(registries), registries);
            helper.assertTrue(springy.component(sw.id()).output(0) == 0, "a spring-return switch never reloads displaced");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void keySwitchRefusesWithoutTheKey(GameTestHelper helper) {
        Stage.run(helper, 6, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            PanelComponent key = s.put(helper, BwrItems.KEY_SWITCH.get(), 0, pos, 0, 0);
            KeySwitchData data = (KeySwitchData) key.data();
            helper.assertTrue(key.w() == 6 && key.h() == 8 && data.requireKey && key.output(0) == 0.0, "6x8, key required, off");

            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(!s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 0.0, "bare-handed: refused");
            helper.assertTrue(PanelOps.forwardUse(s.player, be, 0, 0, 0.5, 0.5).consumesAction() && key.output(0) == 0.0,
                    "the vanilla click is refused too");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.STICK));
            helper.assertTrue(!s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 0.0, "a stick is not a key");
            helper.assertTrue(!s.operate(pos, key, OperateAction.SET, 1) && !s.operate(pos, key, OperateAction.TURN, 1)
                    && key.output(0) == 0.0, "no other action gets round the lock");

            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_KEY.get()));
            helper.assertTrue(s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 1.0, "an unnamed key fits an unnamed lock");
            helper.assertTrue(s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 0.0, "and turns it back");

            // A coded lock.
            helper.assertTrue(s.configure(pos, key, config("KeyCode", "RX-1")) && data.keyCode.equals("RX-1"), "set the key code");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_KEY.get()));
            helper.assertTrue(!s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 0.0, "the unnamed key no longer fits");
            ItemStack wrong = new ItemStack(BwrItems.PANEL_KEY.get());
            wrong.set(DataComponents.CUSTOM_NAME, Component.literal("RX-2"));
            s.player.setItemInHand(InteractionHand.MAIN_HAND, wrong);
            helper.assertTrue(!s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 0.0, "the wrong key is refused");
            ItemStack right = new ItemStack(BwrItems.PANEL_KEY.get());
            right.set(DataComponents.CUSTOM_NAME, Component.literal("RX-1"));
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            s.player.setItemInHand(InteractionHand.OFF_HAND, right);
            helper.assertTrue(PanelKeyItem.holdsKey(s.player, "RX-1") && !PanelKeyItem.holdsKey(null, "RX-1"), "either hand counts");
            helper.assertTrue(s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 1.0, "the right key in the off hand works");

            // A lock that does not need a key.
            s.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            helper.assertTrue(!s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 1.0, "refused again with the key gone");
            helper.assertTrue(s.configure(pos, key, config("RequireKey", false)), "drop the key requirement");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(s.operate(pos, key, OperateAction.PRESS, 0) && key.output(0) == 0.0, "bare hands work when no key is required");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void setpointKnobAndThumbwheelOperate(GameTestHelper helper) {
        Stage.run(helper, 7, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelComponent knob = s.put(helper, BwrItems.SETPOINT_KNOB.get(), 0, pos, 0, 0);
            helper.assertTrue(knob.w() == 8 && knob.h() == 8 && knob.output(0) == 0.0, "8x8 at 0 percent");
            helper.assertTrue(s.configure(pos, knob, config("Step", 2.5)), "step 2.5");
            helper.assertTrue(s.operate(pos, knob, OperateAction.TURN, 1) && knob.output(0) == 2.5, "one notch up");
            helper.assertTrue(s.operate(pos, knob, OperateAction.TURN, 4) && knob.output(0) == 12.5, "four notches");
            helper.assertTrue(s.operate(pos, knob, OperateAction.TURN, -100) && knob.output(0) == 0.0, "clamped at 0");
            helper.assertTrue(s.operate(pos, knob, OperateAction.SET, 150) && knob.output(0) == 100.0, "set is clamped at 100");
            helper.assertTrue(s.operate(pos, knob, OperateAction.PRESS, -1) && knob.output(0) == 87.5, "a click on the left half lowers by five steps");
            helper.assertTrue(s.operate(pos, knob, OperateAction.PRESS, 1) && knob.output(0) == 100.0, "right half raises");
            helper.assertTrue(((SetpointKnobData) knob.data()).value == 100.0, "latched");
            helper.assertTrue(s.configure(pos, knob, config("Step", 1000.0)) && ((SetpointKnobData) knob.data()).step == SetpointKnobData.MAX_STEP,
                    "the step is bounded");

            // Four digits: variant 3 of the footprint list.
            PanelComponent wheel = s.put(helper, BwrItems.THUMBWHEEL.get(), 3, pos, 0, 8);
            helper.assertTrue(wheel.w() == 12 && wheel.h() == 6 && wheel.output(0) == 0.0, "four digits, 12x6");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.SET, 1234) && wheel.output(0) == 1234, "set 1234");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.PRESS, 1) && wheel.output(0) == 2234, "top half of the first digit raises it");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.PRESS, -4) && wheel.output(0) == 2233, "bottom half of the last digit lowers it");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.PRESS, -1) && wheel.output(0) == 1233, "first digit down");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.TURN, 3) && wheel.output(0) == 1243, "scroll up over the third digit");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.SET, 9999) && s.operate(pos, wheel, OperateAction.PRESS, 1)
                    && wheel.output(0) == 999, "9 rolls to 0 without carrying");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.PRESS, -1) && wheel.output(0) == 9999, "and 0 rolls back to 9");
            helper.assertTrue(!s.operate(pos, wheel, OperateAction.PRESS, 0) && !s.operate(pos, wheel, OperateAction.PRESS, 5)
                    && !s.operate(pos, wheel, OperateAction.TURN, -9) && wheel.output(0) == 9999, "a digit that does not exist is refused");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.SET, 1.0e6) && wheel.output(0) == 9999, "set is limited to the digits");
            helper.assertTrue(s.operate(pos, wheel, OperateAction.SET, -5) && wheel.output(0) == 0, "and to zero");
            helper.assertTrue(((ThumbwheelData) wheel.data()).value == 0, "latched");
        });
    }

    // ---- annunciators

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void annunciatorsFollowTheSequenceWithGroupAckAndReset(GameTestHelper helper) {
        Stage.run(helper, 8, s -> {
            s.wall(2, 2);
            BlockPos left = s.at(0, 0);
            BlockPos right = s.at(1, 0);
            PanelBlockEntity leftBe = s.be(left);
            PanelBlockEntity rightBe = s.be(right);
            PanelComponent a1 = s.put(helper, BwrItems.ANNUNCIATOR.get(), 0, left, 0, 0);
            PanelComponent a2 = s.put(helper, BwrItems.ANNUNCIATOR.get(), 0, right, 4, 0);
            PanelComponent a3 = s.put(helper, BwrItems.ANNUNCIATOR.get(), 0, left, 0, 8);
            PanelComponent ack = s.put(helper, BwrItems.PUSHBUTTON.get(), 0, left, 12, 0);
            PanelComponent reset = s.put(helper, BwrItems.PUSHBUTTON.get(), 0, left, 12, 6);
            PanelComponent plain = s.put(helper, BwrItems.PUSHBUTTON.get(), 0, left, 12, 12);
            helper.assertTrue(a1.w() == 12 && a1.h() == 8 && a1.inputCount() == 1 && a1.outputCount() == 0, "12x8, one input, display only");

            helper.assertTrue(s.configure(left, a3, config("AnnGroup", 2, "Line1", "TURBINE", "Line2", "TRIP")), "group 2");
            helper.assertTrue(s.configure(left, ack, config("Role", PushbuttonData.ROLE_ACK, "AnnGroup", 1)), "ack button");
            helper.assertTrue(s.configure(left, reset, config("Role", PushbuttonData.ROLE_RESET)), "reset button");
            AnnunciatorData d1 = (AnnunciatorData) a1.data();
            AnnunciatorData d2 = (AnnunciatorData) a2.data();
            AnnunciatorData d3 = (AnnunciatorData) a3.data();
            helper.assertTrue(d3.group == 2 && d3.line1.equals("TURBINE") && d3.line2.equals("TRIP") && d1.group == 1, "config applied");
            helper.assertTrue(Double.isNaN(a1.input(0)) && d1.logic.display() == AnnunciatorLogic.Display.OFF, "no signal: dark");

            helper.assertTrue(s.configure(right, a2, config("Reset", AnnunciatorData.RESET_MANUAL)), "manual reset on the second window");

            leftBe.setInput(a1, 0, 0.0);
            rightBe.setInput(a2, 0, 0.0);
            leftBe.setInput(a3, 0, 0.0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.OFF && d2.logic.display() == AnnunciatorLogic.Display.OFF,
                    "conditions clear: all dark");

            leftBe.setInput(a1, 0, 1.0);
            leftBe.setInput(a3, 0, 1.0);
            rightBe.setInput(a2, 0, 1.0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.FLASH && d2.logic.display() == AnnunciatorLogic.Display.FLASH
                    && d3.logic.display() == AnnunciatorLogic.Display.FLASH, "alarm: all flash");

            // A pushbutton with no role does not acknowledge.
            s.operate(left, plain, OperateAction.PRESS, 0);
            s.operate(left, plain, OperateAction.RELEASE, 0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.FLASH, "an unassigned button acknowledges nothing");

            // Group ACK reaches windows in other blocks of the same group, and only that group.
            helper.assertTrue(s.operate(left, ack, OperateAction.PRESS, 0) && ack.output(0) == 1.0, "press ACK");
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.STEADY && d2.logic.display() == AnnunciatorLogic.Display.STEADY,
                    "group 1 acknowledged, across both blocks");
            helper.assertTrue(d3.logic.display() == AnnunciatorLogic.Display.FLASH, "group 2 is untouched");
            s.operate(left, ack, OperateAction.RELEASE, 0);

            // Automatic reset on the first window; manual on the second.
            leftBe.setInput(a1, 0, 0.0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.OFF, "acknowledged and cleared goes out by itself");
            rightBe.setInput(a2, 0, 0.0);
            helper.assertTrue(d2.logic.display() == AnnunciatorLogic.Display.STEADY, "manual window stays steady until RESET");
            helper.assertTrue(s.operate(left, reset, OperateAction.PRESS, 0), "press RESET");
            s.operate(left, reset, OperateAction.RELEASE, 0);
            helper.assertTrue(d2.logic.display() == AnnunciatorLogic.Display.OFF, "RESET clears it");
            helper.assertTrue(d3.logic.display() == AnnunciatorLogic.Display.FLASH, "group 2 still alarming and unacknowledged");

            // Cleared before anybody looked: keeps flashing until acknowledged.
            leftBe.setInput(a1, 0, 1.0);
            leftBe.setInput(a1, 0, 0.0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.FLASH, "cleared but unacknowledged keeps flashing");
            s.operate(left, ack, OperateAction.PRESS, 0);
            s.operate(left, ack, OperateAction.RELEASE, 0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.OFF, "acknowledging puts it out");

            // Losing the signal holds the state; the window never invents an alarm.
            leftBe.setInput(a3, 0, Double.NaN);
            helper.assertTrue(d3.logic.display() == AnnunciatorLogic.Display.FLASH, "no signal holds the state");
            leftBe.setInput(a1, 0, Double.NaN);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.OFF, "no signal on a dark window stays dark");

            // The configured condition: below and equal-to a setpoint.
            helper.assertTrue(s.configure(left, a1, config("Condition", AnnunciatorLogic.BELOW, "Setpoint", 10.0)), "alarm below 10");
            leftBe.setInput(a1, 0, 12.0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.OFF, "12 is not below 10");
            leftBe.setInput(a1, 0, 9.0);
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.FLASH, "9 is below 10");
            helper.assertTrue(s.configure(left, a1, config("Condition", AnnunciatorLogic.EQUALS, "Setpoint", 9.0)), "alarm at exactly 9");
            helper.assertTrue(d1.logic.display() == AnnunciatorLogic.Display.FLASH, "state kept across the config edit");

            // The alarm state survives a save, unacknowledged state included.
            var registries = s.level.registryAccess();
            PanelBlockEntity reloaded = new PanelBlockEntity(left, leftBe.getBlockState());
            reloaded.loadWithComponents(leftBe.saveWithoutMetadata(registries), registries);
            AnnunciatorData reloaded3 = (AnnunciatorData) reloaded.component(a3.id()).data();
            helper.assertTrue(reloaded3.logic.display() == AnnunciatorLogic.Display.FLASH && reloaded3.group == 2
                    && reloaded3.line2.equals("TRIP"), "an unacknowledged alarm is still unacknowledged after a restart");
            helper.assertTrue(reloaded.savePanel(false).equals(leftBe.savePanel(false)), "save is a fixed point");
        });
    }

    // ---- bar graph and indicators

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void barGraphTakesAnyOrientationAndHasNoSignalState(GameTestHelper helper) {
        Stage.run(helper, 9, s -> {
            s.wall(2, 2);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            PanelComponent vertical = s.put(helper, BwrItems.BAR_GRAPH.get(), 0, pos, 0, 0);
            PanelComponent horizontal = s.put(helper, BwrItems.BAR_GRAPH.get(), 4, pos, 4, 0);
            helper.assertTrue(vertical.w() == 3 && vertical.h() == 24 && horizontal.w() == 24 && horizontal.h() == 3, "3x24 and 24x3");
            helper.assertTrue(Double.isNaN(vertical.input(0)), "a bar graph with nothing wired has no signal");
            helper.assertTrue(s.configure(pos, vertical, config("Min", 0.0, "Max", 200.0, "Warn", 150.0, "Alarm", 180.0)), "scale");
            BarGraphData data = (BarGraphData) vertical.data();
            helper.assertTrue(data.max == 200.0 && data.warn == 150.0 && data.alarm == 180.0, "scale applied");
            helper.assertTrue(s.configure(pos, vertical, config("Min", 300.0, "Max", 100.0)) && data.max > data.min,
                    "an inverted scale is repaired rather than dividing by zero");
            be.setInput(vertical, 0, 120.0);
            helper.assertTrue(vertical.input(0) == 120.0, "a value arrives");
            be.setInput(vertical, 0, Double.NaN);
            helper.assertTrue(Double.isNaN(vertical.input(0)) && BarGraphLogic.lit(vertical.input(0), data.min, data.max, vertical.h()) == 0,
                    "no signal lights nothing");
        });
    }

    // ---- saves

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void everyInputAndIndicatorSurvivesSaveReloadAndNetworkSync(GameTestHelper helper) {
        Stage.run(helper, 10, s -> {
            // The components are 6 to 18 cells across, so each is anchored in a block of its own (ids are per block).
            s.wall(5, 3);
            BlockPos pPb = s.at(0, 0);
            BlockPos pTs = s.at(1, 0);
            BlockPos pRs = s.at(2, 0);
            BlockPos pCs = s.at(3, 0);
            BlockPos pKs = s.at(4, 0);
            BlockPos pSk = s.at(0, 1);
            BlockPos pTw = s.at(1, 1);
            BlockPos pAn = s.at(3, 1);
            BlockPos pBg = s.at(4, 1);
            BlockPos pStraddler = s.at(0, 2);
            PanelComponent pb = s.put(helper, BwrItems.PUSHBUTTON.get(), 1, pPb, 0, 0);
            PanelComponent ts = s.put(helper, BwrItems.TOGGLE_SWITCH.get(), 0, pTs, 0, 0);
            PanelComponent rs = s.put(helper, BwrItems.ROTARY_SELECTOR.get(), 0, pRs, 0, 0);
            PanelComponent cs = s.put(helper, BwrItems.CONTROL_SWITCH.get(), 0, pCs, 0, 0);
            PanelComponent ks = s.put(helper, BwrItems.KEY_SWITCH.get(), 0, pKs, 0, 0);
            PanelComponent sk = s.put(helper, BwrItems.SETPOINT_KNOB.get(), 0, pSk, 0, 0);
            PanelComponent tw = s.put(helper, BwrItems.THUMBWHEEL.get(), 5, pTw, 0, 0);
            PanelComponent an = s.put(helper, BwrItems.ANNUNCIATOR.get(), 1, pAn, 0, 0);
            PanelComponent bg = s.put(helper, BwrItems.BAR_GRAPH.get(), 1, pBg, 0, 0);
            PanelComponent straddler = s.put(helper, BwrItems.ANNUNCIATOR.get(), 0, pStraddler, 10, 0);
            helper.assertTrue(straddler.w() == 12 && s.be(s.at(1, 2)).claimAt(5, 0) != null, "one annunciator straddles into the next block");

            s.wrench();
            for (var edit : List.<Object[]>of(
                    new Object[] {pPb, pb, config("Color", 0x2040FF, "Role", "reset", "AnnGroup", 3)},
                    new Object[] {pTs, ts, config("Color", 0x112233)},
                    new Object[] {pRs, rs, config("Positions", 5, "Names", "A,B,C,D,E", "Color", 0x445566)},
                    new Object[] {pCs, cs, config("Spring", false, "CloseText", "STOP", "NormalText", "AUTO", "OpenText", "START",
                            "RedText", "RUNNING", "GreenText", "STOPPED")},
                    new Object[] {pKs, ks, config("KeyCode", "VAULT", "RequireKey", true)},
                    new Object[] {pSk, sk, config("Step", 0.5, "Color", 0x778899)},
                    new Object[] {pTw, tw, config("Color", 0xFFAA00)},
                    new Object[] {pAn, an, config("Line1", "HI PRESS", "Line2", "RPV", "Color", 0xFF3030, "Condition", "below",
                            "Setpoint", -2.5, "AnnGroup", 4, "Reset", "manual")},
                    new Object[] {pBg, bg, config("Min", -10.0, "Max", 90.0, "Warn", 60.0, "Alarm", 80.0, "Color", 0x3399FF)})) {
                helper.assertTrue(s.configure((BlockPos) edit[0], (PanelComponent) edit[1], (CompoundTag) edit[2]),
                        "configure " + ((PanelComponent) edit[1]).label());
            }

            // Operate the latched ones.
            ItemStack vaultKey = new ItemStack(BwrItems.PANEL_KEY.get());
            vaultKey.set(DataComponents.CUSTOM_NAME, Component.literal("VAULT"));
            s.operate(pTs, ts, OperateAction.PRESS, 0);
            s.operate(pRs, rs, OperateAction.SET, 3);
            s.operate(pCs, cs, OperateAction.PRESS, -1);
            s.operate(pCs, cs, OperateAction.RELEASE, 0);
            s.player.setItemInHand(InteractionHand.MAIN_HAND, vaultKey);
            s.operate(pKs, ks, OperateAction.PRESS, 0);
            s.operate(pSk, sk, OperateAction.SET, 42.5);
            s.operate(pTw, tw, OperateAction.SET, 3141);
            s.be(pAn).setInput(an, 0, -3.0);
            s.be(pBg).setInput(bg, 0, 55.0);
            s.be(pCs).setInput(cs, 0, 1.0);
            helper.assertTrue(ts.output(0) == 1 && rs.output(0) == 3 && cs.output(0) == -1 && ks.output(0) == 1
                    && sk.output(0) == 42.5 && tw.output(0) == 3141, "operated: " + ts.output(0) + " " + rs.output(0) + " " + cs.output(0)
                    + " " + ks.output(0) + " " + sk.output(0) + " " + tw.output(0));
            helper.assertTrue(((AnnunciatorData) an.data()).logic.display() == AnnunciatorLogic.Display.FLASH, "the annunciator is in alarm");

            var registries = s.level.registryAccess();
            java.util.Map<BlockPos, PanelBlockEntity> reloadedAt = new java.util.HashMap<>();
            for (BlockPos where : List.of(pPb, pTs, pRs, pCs, pKs, pSk, pTw, pAn, pBg, pStraddler)) {
                PanelBlockEntity owner = s.be(where);
                CompoundTag saved = owner.saveWithoutMetadata(registries);
                PanelBlockEntity copy = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
                copy.loadWithComponents(saved, registries);
                helper.assertTrue(copy.savePanel(false).equals(owner.savePanel(false)), "save is a fixed point of load");
                reloadedAt.put(where, copy);
            }

            PushbuttonData pbData = (PushbuttonData) reloadedAt.get(pPb).component(pb.id()).data();
            helper.assertTrue(reloadedAt.get(pPb).component(pb.id()).w() == 8 && pbData.color == 0x2040FF && pbData.role.equals("reset")
                    && pbData.group == 3, "pushbutton");
            helper.assertTrue(((ToggleSwitchData) reloadedAt.get(pTs).component(ts.id()).data()).color == 0x112233
                    && reloadedAt.get(pTs).component(ts.id()).output(0) == 1.0, "toggle: config and latched output");
            RotarySelectorData rsData = (RotarySelectorData) reloadedAt.get(pRs).component(rs.id()).data();
            helper.assertTrue(rsData.positions == 5 && rsData.names.equals("A,B,C,D,E")
                    && reloadedAt.get(pRs).component(rs.id()).output(0) == 3.0, "rotary");
            PanelComponent csCopy = reloadedAt.get(pCs).component(cs.id());
            ControlSwitchData csData = (ControlSwitchData) csCopy.data();
            helper.assertTrue(!csData.spring && csData.closeText.equals("STOP") && csData.greenText.equals("STOPPED")
                    && csCopy.output(0) == -1 && csCopy.output(2) == 1, "control switch");
            KeySwitchData ksData = (KeySwitchData) reloadedAt.get(pKs).component(ks.id()).data();
            helper.assertTrue(ksData.keyCode.equals("VAULT") && ksData.requireKey
                    && reloadedAt.get(pKs).component(ks.id()).output(0) == 1.0, "key switch");
            SetpointKnobData skData = (SetpointKnobData) reloadedAt.get(pSk).component(sk.id()).data();
            helper.assertTrue(skData.step == 0.5 && skData.value == 42.5 && reloadedAt.get(pSk).component(sk.id()).output(0) == 42.5, "knob");
            ThumbwheelData twData = (ThumbwheelData) reloadedAt.get(pTw).component(tw.id()).data();
            helper.assertTrue(reloadedAt.get(pTw).component(tw.id()).w() == 18 && twData.value == 3141
                    && reloadedAt.get(pTw).component(tw.id()).output(0) == 3141, "thumbwheel (six digits, 18 cells)");
            AnnunciatorData anData = (AnnunciatorData) reloadedAt.get(pAn).component(an.id()).data();
            helper.assertTrue(anData.line1.equals("HI PRESS") && anData.condition.equals("below") && anData.setpoint == -2.5
                    && anData.group == 4 && anData.resetMode.equals("manual") && anData.color == 0xFF3030
                    && anData.logic.display() == AnnunciatorLogic.Display.FLASH && anData.logic.manualReset(), "annunciator");
            BarGraphData bgData = (BarGraphData) reloadedAt.get(pBg).component(bg.id()).data();
            helper.assertTrue(bgData.min == -10.0 && bgData.max == 90.0 && bgData.warn == 60.0 && bgData.alarm == 80.0
                    && bgData.color == 0x3399FF && reloadedAt.get(pBg).component(bg.id()).h() == 12, "bar graph");

            // The network form carries live values and latched state to a client copy.
            java.util.function.Function<BlockPos, PanelBlockEntity> clientOf = where -> {
                PanelBlockEntity copy = new PanelBlockEntity(where, s.be(where).getBlockState());
                copy.applyNetworkState(s.be(where).savePanel(true));
                return copy;
            };
            PanelBlockEntity clientAn = clientOf.apply(pAn);
            PanelBlockEntity clientBg = clientOf.apply(pBg);
            PanelBlockEntity clientCs = clientOf.apply(pCs);
            PanelBlockEntity clientTs = clientOf.apply(pTs);
            PanelBlockEntity clientTw = clientOf.apply(pTw);
            helper.assertTrue(clientAn.component(an.id()).input(0) == -3.0 && clientBg.component(bg.id()).input(0) == 55.0
                    && clientCs.component(cs.id()).input(0) == 1.0, "client sees the live inputs");
            helper.assertTrue(clientTs.component(ts.id()).output(0) == 1.0 && clientTw.component(tw.id()).output(0) == 3141,
                    "client sees the operated outputs");
            AnnunciatorData clientAnData = (AnnunciatorData) clientAn.component(an.id()).data();
            helper.assertTrue(clientAnData.logic.display() == AnnunciatorLogic.Display.FLASH, "client sees the alarm state");
            // The client runs the same state machine from the same deltas.
            clientAn.applyDelta(new PanelBlockEntity.ValueDelta(an.id(), false, 0, 0.0));
            helper.assertTrue(clientAnData.logic.display() == AnnunciatorLogic.Display.FLASH, "cleared but unacknowledged keeps flashing on the client");
            clientTs.applyDelta(new PanelBlockEntity.ValueDelta(ts.id(), true, 0, 0.0));
            helper.assertTrue(clientTs.component(ts.id()).output(0) == 0.0, "an output delta moves the toggle on the client");
        });
    }
}
