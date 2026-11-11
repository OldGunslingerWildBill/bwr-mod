package dev.bwr.mod.devtest;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.LinkSelection;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelPlayerCleanup;
import dev.bwr.mod.panel.component.AnnunciatorControlType;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorHorns;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.AnnunciatorTrigger;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.AnnunciatorAddresses;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.panel.wiring.RedstoneSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * The annunciator's alarm features that work on a server: the lens swatches and the light address of a window (saved
 * only when set), the horn bit of the alarm sequence (set by a new alarm; cleared by ACK and by SILENCE, which changes
 * nothing else), the address calls a computer makes ({@link AnnunciatorAddresses}, which the {@code bwr_panel}
 * peripheral wraps), the trigger the binding screen sends with a wiring tool in hand, and linking a component first
 * and the device second ({@link LinkSelection}). The horn's sound itself is the client's and is not played here; which
 * groups sound is ({@link AnnunciatorHorns}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AnnunciatorAlarmTests {
    static final int ORIGIN_X = 142_300;
    static final int ORIGIN_Z = 143_828;

    private AnnunciatorAlarmTests() {
    }

    private static PanelComponent window(HardwareStage s, int x, int y, int group) {
        PanelComponent w = s.place(ComponentTypes.ANNUNCIATOR, x, y, Footprint.of(1, 1));
        ((AnnunciatorData) w.data()).group = group;
        s.be().setInput(w, 0, 0.0);
        return w;
    }

    private static AnnunciatorData data(PanelComponent w) {
        return (AnnunciatorData) w.data();
    }

    private static CompoundTag config(String key, String value) {
        CompoundTag config = new CompoundTag();
        config.putString(key, value);
        CompoundTag edit = new CompoundTag();
        edit.put("Config", config);
        return edit;
    }

    private static void press(HardwareStage s, PanelComponent module, int button) {
        s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        s.helper.assertTrue(PanelOps.operate(s.player, s.origin, module.id(), OperateAction.PRESS, button), "button " + button + " pressed");
        PanelOps.operate(s.player, s.origin, module.id(), OperateAction.RELEASE, 0.0);
    }

    private static String refusal(Runnable call) {
        try {
            call.run();
            return null;
        } catch (IllegalArgumentException refused) {
            return String.valueOf(refused.getMessage());
        }
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aWindowOffersThreeLensColoursAndSavesItsLightAddressOnlyWhenSet(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "aa-address", s -> {
            PanelComponent w = window(s, 0, 0, 1);
            AnnunciatorData data = data(w);
            ConfigField colour = ComponentTypes.ANNUNCIATOR.configSchema().stream().filter(f -> f.key().equals("Color")).findFirst().orElseThrow();
            helper.assertTrue(colour.swatchNames().equals(List.of("white", "yellow", "red"))
                    && colour.swatchColor("white") == 0xF0F0E0 && colour.swatchColor("yellow") == 0xFFD000
                    && colour.swatchColor("red") == 0xE02020, "the lens swatches are white, yellow and red: " + colour.swatchNames());
            helper.assertTrue(data.color == 0xFFB000 && AnnunciatorData.DEFAULT_COLOR == 0xFFB000, "the default colour is what it was");

            CompoundTag fresh = data.toTag();
            helper.assertTrue(!fresh.contains(AnnunciatorData.KEY_ADDRESS) && !fresh.contains(AnnunciatorData.KEY_AUDIBLE)
                    && data.address == AnnunciatorData.NO_ADDRESS, "a window nobody addressed saves no address and no horn bit: " + fresh);

            s.wrench();
            helper.assertTrue(PanelOps.configure(s.player, s.origin, w.id(), config(AnnunciatorData.KEY_ADDRESS, "42")) && data.address == 42,
                    "address 42 typed in the wrench screen: " + data.address);
            helper.assertTrue("00042".equals(data.toTag().getString(AnnunciatorData.KEY_ADDRESS)) && "00042".equals(data.addressText()),
                    "saved and shown as five digits: " + data.toTag());
            AnnunciatorData loaded = new AnnunciatorData();
            loaded.load(data.toTag());
            helper.assertTrue(loaded.address == 42, "and read back: " + loaded.address);
            // any colour still loads and saves as it is
            CompoundTag odd = data.toTag();
            odd.putInt("Color", 0x123456);
            loaded.load(odd);
            helper.assertTrue(loaded.color == 0x123456 && loaded.toTag().getInt("Color") == 0x123456, "a colour that is no swatch is kept");

            for (String top : new String[] {"0", "10000", "00007"}) {
                PanelOps.configure(s.player, s.origin, w.id(), config(AnnunciatorData.KEY_ADDRESS, top));
                helper.assertTrue(data.address == Integer.parseInt(top), top + " is an address: " + data.address);
            }
            for (String bad : new String[] {"10001", "abc", "-1", "1.5", ""}) {
                PanelOps.configure(s.player, s.origin, w.id(), config(AnnunciatorData.KEY_ADDRESS, "9"));
                PanelOps.configure(s.player, s.origin, w.id(), config(AnnunciatorData.KEY_ADDRESS, bad));
                helper.assertTrue(data.address == AnnunciatorData.NO_ADDRESS && !data.toTag().contains(AnnunciatorData.KEY_ADDRESS),
                        "'" + bad + "' is no address, and none is saved: " + data.address);
            }
            helper.assertTrue(AnnunciatorData.parseAddress(" 12 ") == 12 && AnnunciatorData.parseAddress("100000") == -1
                    && AnnunciatorData.parseAddress(null) == -1 && "10000".equals(AnnunciatorData.formatAddress(10000)), "parsing");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theHornSoundsFromANewAlarmUntilItIsAcknowledgedOrSilenced(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "aa-horn", s -> {
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(4, 2));
            PanelComponent a = window(s, 0, 0, 1);
            PanelComponent b = window(s, 1, 0, 2);
            AnnunciatorLogic alarm = data(a).logic;
            List<dev.bwr.mod.panel.PanelBlockEntity> panels = List.of(s.be());
            helper.assertTrue(!alarm.audible() && AnnunciatorHorns.sounding(panels, s.level).isEmpty(), "nothing sounds at rest");

            s.be().setInput(a, 0, 1.0);
            helper.assertTrue(alarm.state() == AnnunciatorLogic.State.ALARM && alarm.audible()
                    && data(a).toTag().getBoolean(AnnunciatorData.KEY_AUDIBLE), "a new alarm sounds, and that is saved");
            Map<BlockPos, AnnunciatorHorns.Horn> horns = AnnunciatorHorns.sounding(panels, s.level);
            helper.assertTrue(horns.size() == 1 && Math.abs(horns.values().iterator().next().x() - (s.origin.getX() + 0.5)) < 1e-9,
                    "one horn for the group, at the panel: " + horns);
            AnnunciatorData reloaded = new AnnunciatorData();
            reloaded.load(data(a).toTag());
            helper.assertTrue(reloaded.logic.audible(), "it still sounds after a reload");
            CompoundTag old = data(a).toTag();
            old.remove(AnnunciatorData.KEY_AUDIBLE);
            reloaded.load(old);
            helper.assertTrue(reloaded.logic.state() == AnnunciatorLogic.State.ALARM && !reloaded.logic.audible(),
                    "an alarm saved before the horn existed loads silent");

            // SILENCE stops the horn of its group and changes nothing else.
            s.be().setInput(b, 0, 1.0);
            press(s, module, AnnunciatorControlType.SILENCE);
            helper.assertTrue(!alarm.audible() && alarm.state() == AnnunciatorLogic.State.ALARM
                    && alarm.display() == AnnunciatorLogic.Display.FLASH && !data(a).toTag().contains(AnnunciatorData.KEY_AUDIBLE),
                    "silenced: quiet, still flashing, and the bit is no longer saved");
            helper.assertTrue(data(b).logic.audible() && AnnunciatorHorns.sounding(panels, s.level).size() == 1,
                    "group 2 was not silenced by group 1's button, so the panel still sounds");
            s.be().setInput(b, 0, 0.0);
            data(b).logic.acknowledge();
            helper.assertTrue(AnnunciatorHorns.sounding(panels, s.level).isEmpty(), "nothing sounds once both are quiet");

            // The condition clearing does not start it again; ACK then turns the window off.
            s.be().setInput(a, 0, 0.0);
            helper.assertTrue(!alarm.audible() && alarm.state() == AnnunciatorLogic.State.ALARM, "a cleared, unacknowledged alarm stays quiet");
            press(s, module, AnnunciatorControlType.ACK);
            helper.assertTrue(alarm.state() == AnnunciatorLogic.State.NORMAL && !alarm.audible(), "acknowledged: off");

            // A new alarm sounds again; ACK stops the horn and leaves the window steady.
            s.be().setInput(a, 0, 1.0);
            helper.assertTrue(alarm.audible(), "the next alarm sounds again");
            press(s, module, AnnunciatorControlType.ACK);
            helper.assertTrue(alarm.state() == AnnunciatorLogic.State.ACKED && !alarm.audible(), "ACK stops the horn");
            s.be().setInput(a, 0, 0.0);
            helper.assertTrue(alarm.state() == AnnunciatorLogic.State.NORMAL && !alarm.audible(), "cleared: off and quiet");

            // The bit only ever goes with an unacknowledged alarm.
            AnnunciatorLogic logic = new AnnunciatorLogic();
            logic.restore(AnnunciatorLogic.State.ACKED.ordinal(), true, true);
            helper.assertTrue(!logic.audible(), "an acknowledged alarm cannot be restored sounding");
            logic.restore(AnnunciatorLogic.State.ALARM.ordinal(), true, true);
            helper.assertTrue(logic.audible() && logic.silence() && !logic.audible() && !logic.silence(), "silence reports whether it was sounding");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aComputerDrivesLightsByTheirAddress(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 128, ORIGIN_Z, "aa-lights", s -> {
            PanelComponent a = window(s, 0, 0, 1);
            PanelComponent b = window(s, 1, 0, 1);
            PanelComponent wired = window(s, 2, 0, 1);
            PanelComponent plain = window(s, 3, 0, 2);
            data(a).address = 101;
            data(b).address = 101;
            data(wired).address = 7;
            data(a).line1 = "RPV LEVEL";
            data(a).line2 = "LOW";
            wired.setSource(0, RedstoneSource.INSTANCE);
            var be = s.be();

            helper.assertTrue(AnnunciatorAddresses.OFF.equals(AnnunciatorAddresses.state(be, 101)), "off at rest");
            helper.assertTrue(AnnunciatorAddresses.drive(be, 101, 1.0) == 2 && a.source(0) instanceof CcSource && b.source(0) instanceof CcSource,
                    "both windows at 101 are wired to the computer and driven");
            helper.assertTrue(data(a).logic.state() == AnnunciatorLogic.State.ALARM && data(b).logic.state() == AnnunciatorLogic.State.ALARM
                    && data(a).logic.audible() && AnnunciatorAddresses.ALARM.equals(AnnunciatorAddresses.state(be, 101)), "setAlarm(101, true): alarm");
            data(a).logic.acknowledge();
            helper.assertTrue(AnnunciatorAddresses.ALARM.equals(AnnunciatorAddresses.state(be, 101)), "one of two still unacknowledged reads alarm");
            data(b).logic.acknowledge();
            helper.assertTrue(AnnunciatorAddresses.ACKNOWLEDGED.equals(AnnunciatorAddresses.state(be, 101)), "both acknowledged");
            helper.assertTrue(AnnunciatorAddresses.drive(be, 101, 0.0) == 2 && AnnunciatorAddresses.OFF.equals(AnnunciatorAddresses.state(be, 101)),
                    "setAlarm(101, false): off");

            // setValue: the window's own condition and setpoint judge the number.
            data(a).condition = AnnunciatorLogic.BELOW;
            data(a).setpoint = 10.0;
            AnnunciatorAddresses.drive(be, 101, 5.0);
            helper.assertTrue(data(a).logic.state() == AnnunciatorLogic.State.ALARM && data(b).logic.state() == AnnunciatorLogic.State.ALARM,
                    "5 is below 10 for one window and above 0.5 for the other");

            String unknown = refusal(() -> AnnunciatorAddresses.drive(be, 555, 1.0));
            helper.assertTrue(unknown != null && unknown.contains("00555"), "an address no window has is an error: " + unknown);
            helper.assertTrue(refusal(() -> AnnunciatorAddresses.state(be, 555)) != null, "also for a read");
            helper.assertTrue(refusal(() -> AnnunciatorAddresses.drive(be, 10001, 1.0)) != null
                    && refusal(() -> AnnunciatorAddresses.drive(be, -1, 1.0)) != null, "addresses run from 0 to 10000");
            helper.assertTrue(refusal(() -> AnnunciatorAddresses.drive(be, 101, Double.NaN)) != null, "the value must be a number");
            String elsewhere = refusal(() -> AnnunciatorAddresses.drive(be, 7, 1.0));
            helper.assertTrue(elsewhere != null && elsewhere.contains(wired.label()) && elsewhere.contains(RedstoneSource.INSTANCE.kind())
                    && wired.source(0) == RedstoneSource.INSTANCE && data(wired).logic.state() == AnnunciatorLogic.State.NORMAL,
                    "a window wired to something else is refused by name and left alone: " + elsewhere);

            List<Map<String, Object>> all = AnnunciatorAddresses.describe(be);
            helper.assertTrue(all.size() == 4, "every window is listed: " + all);
            Map<String, Object> first = all.stream().filter(e -> a.label().equals(e.get("label"))).findFirst().orElseThrow();
            helper.assertTrue(Integer.valueOf(101).equals(first.get("address")) && List.of("RPV LEVEL", "LOW").equals(first.get("legend"))
                    && Integer.valueOf(data(a).color).equals(first.get("color")) && Integer.valueOf(1).equals(first.get("group"))
                    && AnnunciatorAddresses.ALARM.equals(first.get("state")), "address, label, legend, colour, group and state: " + first);
            Map<String, Object> unaddressed = all.stream().filter(e -> plain.label().equals(e.get("label"))).findFirst().orElseThrow();
            helper.assertTrue(!unaddressed.containsKey("address") && Integer.valueOf(2).equals(unaddressed.get("group")),
                    "a window without an address has no address entry: " + unaddressed);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theBindingScreenSetsOnlyAWindowsTriggerWithAWiringToolInHand(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 192, ORIGIN_Z, "aa-trigger", s -> {
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(4, 2));
            PanelComponent w = window(s, 0, 0, 1);
            AnnunciatorData data = data(w);
            data.line1 = "KEEP";
            s.be().setInput(w, 0, 5.0);
            data.logic.acknowledge();
            s.be().setInput(w, 0, 0.0);
            helper.assertTrue(data.logic.state() == AnnunciatorLogic.State.NORMAL, "off before");

            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(!WiringOps.setTrigger(s.player, s.origin, w.id(), AnnunciatorTrigger.edit(AnnunciatorLogic.BELOW, 12.5))
                    && AnnunciatorLogic.ABOVE.equals(data.condition), "no wiring tool, no trigger");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
            helper.assertTrue(!PanelOps.configure(s.player, s.origin, w.id(), AnnunciatorTrigger.edit(AnnunciatorLogic.BELOW, 12.5))
                    && AnnunciatorLogic.ABOVE.equals(data.condition), "the wrench's own edit still needs the wrench");
            helper.assertTrue(WiringOps.setTrigger(s.player, s.origin, w.id(), AnnunciatorTrigger.edit(AnnunciatorLogic.BELOW, 12.5))
                    && AnnunciatorLogic.BELOW.equals(data.condition) && data.setpoint == 12.5, "the linker sets condition and setpoint");
            helper.assertTrue(data.logic.state() == AnnunciatorLogic.State.ALARM, "and the input (0, below 12.5) is judged again at once");

            CompoundTag more = AnnunciatorTrigger.edit(AnnunciatorLogic.EQUALS, 3.0);
            more.getCompound("Config").putString("Line1", "CHANGED");
            more.getCompound("Config").putInt("AnnGroup", 5);
            more.getCompound("Config").putString(AnnunciatorData.KEY_ADDRESS, "77");
            more.putString("Label", "RENAMED");
            String label = w.label();
            helper.assertTrue(WiringOps.setTrigger(s.player, s.origin, w.id(), more) && AnnunciatorLogic.EQUALS.equals(data.condition)
                    && data.setpoint == 3.0 && "KEEP".equals(data.line1) && data.group == 1 && data.address == AnnunciatorData.NO_ADDRESS
                    && label.equals(w.label()), "nothing but the trigger is taken from the edit");
            helper.assertTrue(!WiringOps.setTrigger(s.player, s.origin, w.id(), config("Line1", "X"))
                    && !WiringOps.setTrigger(s.player, s.origin, module.id(), AnnunciatorTrigger.edit(AnnunciatorLogic.BELOW, 1.0)),
                    "an edit without a trigger, or for something that is no window, is refused");

            helper.assertTrue(AnnunciatorTrigger.parse("abc") == null && AnnunciatorTrigger.parse("") == null
                    && AnnunciatorTrigger.parse(" 2.5 ") == 2.5 && "3".equals(AnnunciatorTrigger.format(3.0))
                    && "0.5".equals(AnnunciatorTrigger.format(0.5)), "the setpoint field");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aComponentCanBeSelectedFirstAndTheDeviceSecond(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 256, ORIGIN_Z, "aa-link", s -> {
            PanelComponent w = window(s, 0, 0, 1);
            BlockPos valve = s.origin.offset(2, -1, 2);
            var block = BwrBlocks.MSIV.get();
            var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
            s.level.setBlock(valve, state, 3);
            block.setPlacedBy(s.level, valve, state, null, new ItemStack(block));
            BlockPos stone = s.origin.offset(-2, -1, 2);
            s.level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            var uuid = s.player.getUUID();
            LinkSelection.forget(uuid);

            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(!LinkSelection.select(s.player, s.origin, w.id()) && LinkSelection.of(s.player) == null, "no linker, no selection");
            ItemStack linker = new ItemStack(BwrItems.PANEL_LINKER.get());
            s.player.setItemInHand(InteractionHand.MAIN_HAND, linker);
            helper.assertTrue(!LinkSelection.linkDevice(s.player, linker, valve.above()) && PanelLinkerItem.linked(linker) == null,
                    "without a selection a click on a device is the device's own");
            helper.assertTrue(!LinkSelection.select(s.player, s.origin, 9_999), "a component that is not there cannot be selected");
            helper.assertTrue(LinkSelection.select(s.player, s.origin, w.id()) && LinkSelection.holds(s.player, s.origin, w.id())
                    && !LinkSelection.holds(s.player, s.origin, w.id() + 1), "the window is selected");

            helper.assertTrue(!LinkSelection.linkDevice(s.player, linker, stone) && PanelLinkerItem.linked(linker) == null,
                    "a block that reports no signals is left to itself");
            helper.assertTrue(LinkSelection.linkDevice(s.player, linker, valve.above()), "a click on the device is taken");
            GlobalPos stored = PanelLinkerItem.linked(linker);
            helper.assertTrue(stored != null && stored.pos().equals(valve.above()) && stored.dimension().equals(s.level.dimension()),
                    "the device is stored on the linker as a sneak-click would store it: " + stored);
            PanelPayloads.LinkerScreen screen = WiringOps.screenFor(s.player, s.origin, w.id());
            helper.assertTrue(screen != null && screen.status() == PanelPayloads.LINK_OK && !screen.signals().isEmpty(),
                    "the binding screen of the selected window lists the device's signals");

            // Standing at the device, out of arm's reach of the panel: the selection stands in for the reach.
            s.player.setPos(s.origin.getX() + 14.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
            helper.assertTrue(!PanelOps.inReach(s.player, s.origin), "the fixture really is out of reach");
            PanelPayloads.Bind redstone = new PanelPayloads.Bind(s.origin, w.id(), false, 0, BindKind.REDSTONE.ordinal(), BlockPos.ZERO, "",
                    BlockPos.ZERO, 0, 0, 1.0);
            helper.assertTrue(WiringOps.screenFor(s.player, s.origin, w.id()) != null && WiringOps.bind(s.player, redstone) == Result.OK
                    && w.source(0) == RedstoneSource.INSTANCE, "the selected window is wired from there");
            helper.assertTrue(WiringOps.setTrigger(s.player, s.origin, w.id(), AnnunciatorTrigger.edit(AnnunciatorLogic.BELOW, 2.0))
                    && AnnunciatorLogic.BELOW.equals(data(w).condition), "with its trigger");
            helper.assertTrue(!LinkSelection.select(s.player, s.origin, w.id()), "but selecting needs the panel in reach");

            // Sneak-click in the air forgets the device and the component; then reach is the rule again.
            s.player.setShiftKeyDown(true);
            linker.getItem().use(s.level, s.player, InteractionHand.MAIN_HAND);
            s.player.setShiftKeyDown(false);
            helper.assertTrue(PanelLinkerItem.linked(linker) == null && LinkSelection.of(s.player) == null, "both selections are forgotten");
            helper.assertTrue(WiringOps.screenFor(s.player, s.origin, w.id()) == null && WiringOps.bind(s.player, redstone) == Result.OUT_OF_REACH,
                    "without a selection the panel must be in reach");

            // Leaving forgets it too, and a device stored the old way still works the old way.
            s.player.setPos(s.origin.getX() + 0.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
            helper.assertTrue(LinkSelection.select(s.player, s.origin, w.id()), "selected again");
            PanelPlayerCleanup.forget(uuid);
            helper.assertTrue(LinkSelection.of(s.player) == null, "a player who leaves holds no selection");
            linker.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(s.level.dimension(), valve));
            PanelPayloads.LinkerScreen old = WiringOps.screenFor(s.player, s.origin, w.id());
            helper.assertTrue(old != null && old.status() == PanelPayloads.LINK_OK, "device first, then the component, as before");
        });
    }
}
