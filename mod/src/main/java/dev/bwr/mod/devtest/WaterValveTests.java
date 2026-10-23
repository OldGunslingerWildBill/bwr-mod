package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.mod.eccs.AssemblyPlumbing;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.ControllerStationData;
import dev.bwr.mod.panel.component.ControllerStationType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.water.MotorOperatedWaterValveBlock;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity.FailMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;

/**
 * The Motor-Operated Water Valve: its stroke, its FE, its fail position, how it is commanded (redstone, a panel through the
 * Networking Wrench link, signals), how it throttles a water line and isolates a feedwater line break, how it saves, and how it
 * is rated. No CC types here (the computer half is {@link WaterValveCcChecks}). The valve has no ticker: time is advanced when it is
 * asked, so the tests move the game clock to let strokes pass, and put it back.
 */
@GameTestHolder("bwrcavity")
@PrefixGameTestTemplate(false)
public final class WaterValveTests {
    private WaterValveTests() {
    }

    private static final int X = 412_000;

    // ---------------------------------------------------------------------------------------------------- helpers

    static MotorOperatedWaterValveBlockEntity valveAt(ServerLevel l, BlockPos at, Direction facing) {
        l.removeBlock(at, false);
        l.setBlock(at, BwrBlocks.MOTOR_OPERATED_WATER_VALVE.get().defaultBlockState().setValue(MotorOperatedWaterValveBlock.FACING, facing), 3);
        return (MotorOperatedWaterValveBlockEntity) l.getBlockEntity(at);
    }

    /** Lets {@code ticks} game ticks pass for the valve alone: the clock moves, the valve is asked, the clock is put back. */
    static void pass(ServerLevel l, MotorOperatedWaterValveBlockEntity v, long ticks) {
        var data = (ServerLevelData) l.getLevelData();
        long t = l.getGameTime();
        v.position(); // starts its own clock if it has none
        data.setGameTime(t + ticks);
        try {
            v.position();
        } finally {
            data.setGameTime(t);
        }
    }

    static void power(MotorOperatedWaterValveBlockEntity v, int fe) {
        v.energy().receiveEnergy(fe, false);
    }

    /** A valve in a given state, made the way a world makes it: from its saved data. */
    static MotorOperatedWaterValveBlockEntity valveWith(ServerLevel l, BlockPos at, double position, double target, int fe, FailMode mode) {
        var v = valveAt(l, at, Direction.NORTH);
        CompoundTag tag = new CompoundTag();
        if (fe != 0) tag.putInt("EnergyFe", fe);
        if (target != 0) tag.putDouble("Target", target);
        if (position != 0) tag.putDouble("Position", position);
        if (mode != FailMode.AS_IS) tag.putString("FailMode", mode.key());
        v.loadWithComponents(tag, l.registryAccess());
        return v;
    }

    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String what) {
        h.assertTrue(Math.abs(actual - expected) <= tolerance, what + ": expected " + expected + " got " + actual);
    }

    // ---------------------------------------------------------------------------------------------------- stroke and power

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void aValveStrokesInTwentySecondsAndDrawsFiftyFePerTick(GameTestHelper h) {
        var l = h.getLevel();
        var v = valveAt(l, new BlockPos(X, 200, X), Direction.NORTH);
        h.assertTrue(v.position() == 0.0 && v.target() == 0.0 && v.failMode() == FailMode.AS_IS, "a new valve is closed, as-is");
        h.assertTrue(v.energy().getMaxEnergyStored() == 20_000 && v.energy().getEnergyStored() == 0, "a 20 000 FE buffer, empty");
        h.assertTrue(v.setTarget(1.0), "demand accepted");
        pass(l, v, 400);
        h.assertTrue(v.position() == 0.0, "without power the motor cannot move it: " + v.position());
        power(v, 20_000);
        pass(l, v, 399);
        near(h, v.position(), 399.0 / 400.0, 1.0e-9, "one tick short of the full stroke");
        h.assertTrue(v.position() < 1.0, "not yet open one tick before 20 s: " + v.position());
        pass(l, v, 1);
        h.assertTrue(v.position() == 1.0, "fully open after 400 ticks (20 s): " + v.position());
        h.assertTrue(v.energy().getEnergyStored() == 0, "a full stroke drew exactly 20 000 FE (400 ticks at 50): " + v.energy().getEnergyStored());
        pass(l, v, 5000);
        h.assertTrue(v.position() == 1.0 && v.energy().getEnergyStored() == 0, "it holds its position with no power and no draw");
        // closing again at the same rate, on half the power
        v.setTarget(0.0);
        power(v, 10_000);
        pass(l, v, 200);
        near(h, v.position(), 0.5, 1.0e-9, "half the power is half the stroke");
        h.assertTrue(v.energy().getEnergyStored() == 0, "all of it spent: " + v.energy().getEnergyStored());
        pass(l, v, 400);
        near(h, v.position(), 0.5, 1.0e-9, "and then it stops where the power ran out");
        h.assertTrue(!v.isPowered(), "not powered with an empty buffer");
        h.assertTrue(!v.setTarget(Double.NaN) && !v.setTarget(Double.POSITIVE_INFINITY), "a non-finite demand is refused");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void aPartialDemandStopsAtTheDemandedOpening(GameTestHelper h) {
        var l = h.getLevel();
        var v = valveAt(l, new BlockPos(X, 200, X + 8), Direction.NORTH);
        power(v, 20_000);
        v.setTarget(0.3);
        pass(l, v, 2000);
        near(h, v.position(), 0.3, 1.0e-9, "stops at 30 %");
        h.assertTrue(v.energy().getEnergyStored() == 20_000 - 120 * 50, "120 ticks of drive: " + v.energy().getEnergyStored());
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- fail position

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void failAsIsHoldsWhereItWasWhenThePowerIsGone(GameTestHelper h) {
        var l = h.getLevel();
        var v = valveWith(l, new BlockPos(X, 200, X + 16), 0.5, 0.5, 0, FailMode.AS_IS);
        pass(l, v, 3000);
        near(h, v.position(), 0.5, 1.0e-12, "FAIL AS-IS with no power stays where it is");
        var w = valveWith(l, new BlockPos(X, 200, X + 18), 0.5, 0.5, 2000, FailMode.AS_IS);
        pass(l, w, 3000);
        near(h, w.position(), 0.5, 1.0e-12, "FAIL AS-IS with power stays where it is");
        h.assertTrue(w.energy().getEnergyStored() == 2000, "and draws nothing to hold: " + w.energy().getEnergyStored());
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void failOpenStrokesOpenOnPowerLossUsingNoFe(GameTestHelper h) {
        var l = h.getLevel();
        // 2000 FE of buffer holds the latch for 100 ticks (20 FE/t), then the spring opens it at the normal stroke rate
        var v = valveWith(l, new BlockPos(X, 200, X + 24), 0.5, 0.5, 2000, FailMode.OPEN);
        pass(l, v, 50);
        near(h, v.position(), 0.5, 1.0e-12, "held by its latch while it has power");
        h.assertTrue(v.energy().getEnergyStored() == 1000, "the latch draws 20 FE/t: " + v.energy().getEnergyStored());
        pass(l, v, 100);
        near(h, v.position(), 0.5 + 50 * 0.0025, 1.0e-9, "the buffer ran dry at tick 100; 50 ticks of spring since");
        pass(l, v, 400);
        h.assertTrue(v.position() == 1.0, "FAIL OPEN reaches fully open: " + v.position());
        h.assertTrue(v.energy().getEnergyStored() == 0, "and it used no FE to do it");
        // no FE at all: the spring needs none
        var dry = valveWith(l, new BlockPos(X, 200, X + 26), 0.2, 0.2, 0, FailMode.OPEN);
        pass(l, dry, 400);
        h.assertTrue(dry.position() == 1.0 && dry.energy().getEnergyStored() == 0, "FAIL OPEN with no FE at all opens: " + dry.position());
        // and it cannot be driven closed without power: the motor is what holds it away from the spring
        dry.setTarget(0.0);
        pass(l, dry, 800);
        h.assertTrue(dry.position() == 1.0, "no power, no stroke away from the fail position");
        // power keeps it where the player put it: topped up before the buffer runs dry, it never fails
        var kept = valveWith(l, new BlockPos(X, 200, X + 28), 0.5, 0.5, 2000, FailMode.OPEN);
        for (int i = 0; i < 10; i++) {
            pass(l, kept, 90);
            power(kept, 2000);
        }
        near(h, kept.position(), 0.5, 1.0e-12, "fed, a fail-open valve stays put");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void failClosedStrokesClosedOnPowerLossUsingNoFe(GameTestHelper h) {
        var l = h.getLevel();
        var v = valveWith(l, new BlockPos(X, 200, X + 32), 0.5, 0.5, 2000, FailMode.CLOSED);
        pass(l, v, 99);
        near(h, v.position(), 0.5, 1.0e-12, "held by its latch while it has power");
        pass(l, v, 51);
        near(h, v.position(), 0.5 - 50 * 0.0025, 1.0e-9, "50 ticks of spring after the buffer ran dry at tick 100");
        pass(l, v, 400);
        h.assertTrue(v.position() == 0.0 && v.energy().getEnergyStored() == 0, "FAIL CLOSED reaches fully closed with no FE spent: " + v.position());
        var dry = valveWith(l, new BlockPos(X, 200, X + 34), 0.8, 0.8, 0, FailMode.CLOSED);
        pass(l, dry, 400);
        h.assertTrue(dry.position() == 0.0 && dry.energy().getEnergyStored() == 0, "FAIL CLOSED with no FE at all closes: " + dry.position());
        // power opens it (the motor works against the spring) and the spring takes it back when the power goes
        power(dry, 20_000);
        dry.setTarget(1.0);
        pass(l, dry, 400);
        h.assertTrue(dry.position() == 1.0, "powered, it opens: " + dry.position());
        // the buffer is spent (400 x 50 FE): the latch cannot be paid, the spring closes it
        pass(l, dry, 400);
        h.assertTrue(dry.position() == 0.0, "the power gone, the spring closes it: " + dry.position());
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void theFailPositionIsASettingTheSignalsAndTheSneakClickReach(GameTestHelper h) {
        var l = h.getLevel();
        var v = valveAt(l, new BlockPos(X, 200, X + 40), Direction.NORTH);
        var provider = SignalProviders.of(v);
        h.assertTrue(provider != null && provider.read("fail_mode").orElse(-1) == 0.0, "default FAIL AS-IS reads 0");
        h.assertTrue(provider.command("fail_mode", 1) && v.failMode() == FailMode.OPEN && provider.read("fail_mode").orElse(-1) == 1.0, "1 is FAIL OPEN");
        h.assertTrue(provider.command("fail_mode", 2) && v.failMode() == FailMode.CLOSED, "2 is FAIL CLOSED");
        h.assertTrue(!provider.command("fail_mode", 3) && !provider.command("fail_mode", 0.5) && !provider.command("fail_mode", -1) && v.failMode() == FailMode.CLOSED,
                "anything else is refused and changes nothing");
        h.assertTrue(provider.command("fail_mode", 0) && v.failMode() == FailMode.AS_IS, "0 is FAIL AS-IS again");
        h.assertTrue(FailMode.AS_IS.next() == FailMode.OPEN && FailMode.OPEN.next() == FailMode.CLOSED && FailMode.CLOSED.next() == FailMode.AS_IS, "the sneak-click cycle");
        h.assertTrue(FailMode.byKey("fail_open") == FailMode.OPEN && FailMode.byKey("Fail-Closed") == FailMode.CLOSED && FailMode.byKey("as-is") == FailMode.AS_IS
                && FailMode.byKey("sideways") == null, "the words the computers use");
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- saves

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void theValveSavesItsStateAndTheFailPositionAndWritesNothingByDefault(GameTestHelper h) {
        var l = h.getLevel();
        var at = new BlockPos(X, 200, X + 48);
        var plain = valveAt(l, at, Direction.EAST);
        var saved = plain.saveWithFullMetadata(l.registryAccess());
        for (String key : new String[] {"EnergyFe", "Target", "Position", "FailMode", "Redstone"}) {
            h.assertTrue(!saved.contains(key), "a default valve writes no " + key + ": " + saved.getAllKeys());
        }
        var v = valveWith(l, new BlockPos(X, 200, X + 50), 0.3, 0.7, 1234, FailMode.CLOSED);
        v.redstone(9);
        saved = v.saveWithFullMetadata(l.registryAccess());
        h.assertTrue(saved.getString("FailMode").equals("closed"), "FAIL CLOSED is saved: " + saved.getAllKeys());
        var back = (MotorOperatedWaterValveBlockEntity) BlockEntity.loadStatic(v.getBlockPos(), v.getBlockState(), saved, l.registryAccess());
        l.removeBlockEntity(v.getBlockPos());
        l.setBlockEntity(back);
        h.assertTrue(back.failMode() == FailMode.CLOSED, "the fail position loaded: " + back.failMode());
        near(h, back.target(), 0.6, 1.0e-9, "the demand loaded (redstone 9 of 15 replaced the 0.7 demand)");
        // a save from before the fail position existed has no key and loads as-is
        var old = new CompoundTag();
        old.putDouble("Position", 0.25);
        var loaded = plain;
        loaded.loadWithComponents(old, l.registryAccess());
        h.assertTrue(loaded.failMode() == FailMode.AS_IS && loaded.energy().getEnergyStored() == 0, "an old or bare save loads FAIL AS-IS, no power");
        near(h, loaded.position(), 0.25, 1.0e-12, "its position kept");
        // damaged or hostile data cannot make a NaN or an out-of-range valve
        var bad = new CompoundTag();
        bad.putDouble("Position", Double.NaN);
        bad.putDouble("Target", 7.0);
        bad.putInt("EnergyFe", -5);
        bad.putString("FailMode", "sideways");
        var cleaned = plain;
        cleaned.loadWithComponents(bad, l.registryAccess());
        h.assertTrue(cleaned.target() == 1.0 && cleaned.failMode() == FailMode.AS_IS && cleaned.energy().getEnergyStored() == 0, "hostile data is clamped");
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- redstone

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void redstoneDemandsStrengthOverFifteenAndOnlyAChangeIsACommand(GameTestHelper h) {
        var l = h.getLevel();
        var at = new BlockPos(X, 200, X + 56);
        var v = valveAt(l, at, Direction.EAST);
        h.assertTrue(v.target() == 0.0, "no signal, closed");
        var source = at.north();
        l.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        h.assertTrue(v.target() == 1.0, "a redstone block (15) demands fully open: " + v.target());
        l.setBlock(source, Blocks.AIR.defaultBlockState(), 3);
        h.assertTrue(v.target() == 0.0, "no signal demands closed: " + v.target());
        v.redstone(7);
        near(h, v.target(), 7.0 / 15.0, 0.0006, "strength 7 demands 7/15");
        v.redstone(15);
        h.assertTrue(v.target() == 1.0, "strength 15 demands open");
        // a command from elsewhere is not undone by a neighbour that changes without changing the signal
        l.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        h.assertTrue(v.target() == 1.0, "a changed signal is the latest command");
        h.assertTrue(v.setTarget(0.4), "a panel or computer demand after it");
        l.setBlock(at.south(), Blocks.STONE.defaultBlockState(), 3);
        l.setBlock(at.south(), Blocks.AIR.defaultBlockState(), 3);
        near(h, v.target(), 0.4, 1.0e-9, "a neighbour changing without the signal changing is no command: the latest demand wins");
        l.setBlock(source, Blocks.AIR.defaultBlockState(), 3);
        h.assertTrue(v.target() == 0.0, "the signal falling is a command again");
        // placed beside a powered block it starts at that demand
        var next = new BlockPos(X, 200, X + 60);
        l.setBlock(next.north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        var placed = valveAt(l, next, Direction.EAST);
        h.assertTrue(placed.target() == 1.0, "a valve placed beside a powered block takes the demand: " + placed.target());
        l.setBlock(next.north(), Blocks.AIR.defaultBlockState(), 3);
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the rating

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void theRatingIsShownOnTheTooltipTheStatusTheSignalsAndTheItem(GameTestHelper h) {
        var l = h.getLevel();
        var v = valveAt(l, new BlockPos(X, 200, X + 64), Direction.NORTH);
        String status = String.join(" ", v.statusLines());
        h.assertTrue(status.contains("2,500") && status.contains("1,250"), "the status text names the pipe's rating and the wear threshold: " + status);
        var lines = new ArrayList<net.minecraft.network.chat.Component>();
        BwrBlocks.MOTOR_OPERATED_WATER_VALVE.get().appendHoverText(new ItemStack(BwrBlocks.MOTOR_OPERATED_WATER_VALVE.get()),
                net.minecraft.world.item.Item.TooltipContext.EMPTY, lines, net.minecraft.world.item.TooltipFlag.NORMAL);
        String tip = lines.stream().map(c -> dev.bwr.mod.text.BwrText.english(c)).reduce("", (a, b) -> a + " " + b);
        var pipeTip = new ArrayList<net.minecraft.network.chat.Component>();
        BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().appendHoverText(new ItemStack(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()),
                net.minecraft.world.item.Item.TooltipContext.EMPTY, pipeTip, net.minecraft.world.item.TooltipFlag.NORMAL);
        String pipeRating = dev.bwr.mod.text.BwrText.english(pipeTip.get(0));
        h.assertTrue(tip.contains(pipeRating) && tip.contains("1,250"), "the valve's tooltip carries the pipe's own nameplate (" + pipeRating + ") and the wear threshold: " + tip);
        var provider = SignalProviders.of(v);
        h.assertTrue(provider.read("rating_psi").orElse(0) == 2500.0 && provider.read("wear_above_psig").orElse(0) == 1250.0, "the signals carry the rating");
        h.assertTrue(MotorOperatedWaterValveBlockEntity.wearAbovePsig() == dev.bwr.core.boundary.BoundaryStress.DESIGN_PRESSURE_PSIG,
                "the wear threshold is the pipe model's, not a second number");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void aComputerCommandsTheValveAndReadsItsRating(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            h.succeed();
            return;
        }
        var at = new BlockPos(X, 200, X + 68);
        var v = valveAt(h.getLevel(), at, Direction.NORTH);
        WaterValveCcChecks.verifyValve(h, at, v);
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- panel and signals

    @GameTest(template = "empty", templateNamespace = "bwrcavity", batch = "waterpanel", timeoutTicks = 600)
    public static void aPanelStationAndASwitchDriveTheValveThroughTheWrenchLink(GameTestHelper h) {
        var root = new BlockPos(X + 200, 245, X);
        var rig = new ControllerSyncWiringTests.Rig(h, root, "water");
        EntityLookupProbe.runWhenLive(h, rig.bounds(), () -> {
            try {
                rig.start();
                var l = rig.level;
                var at = root.offset(-8, 0, 6);
                var valve = valveAt(l, at, Direction.EAST);
                var station = rig.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
                var data = (ControllerStationData) station.data();
                h.assertTrue(rig.bindOut(station, ControllerStationType.OUT_DEMAND, at, "position",
                        ControllerSyncWiringTests.guiScale(rig, station, at, "position")) == WiringOps.Result.OK, "the station's demand binds to the valve's position command");
                h.assertTrue(valve.target() == 0.0, "binding moves nothing");
                rig.op(station, OperateAction.SET, 40.0);
                near(h, valve.target(), 0.40, 1.0e-6, "the station's OUT 40 % demands 0.4");
                for (int i = 0; i < 10; i++) rig.press(station, dev.bwr.mod.panel.component.StationLayout.RAISE);
                rig.release(station);
                near(h, valve.target(), 0.50, 1.0e-6, "RAISE x10 steps it to 50 %");
                h.assertTrue(rig.bindIn(station, ControllerStationType.IN_PV, at, "position", 100.0) == WiringOps.Result.OK, "the position comes back as the PV");
                power(valve, 20_000);
                pass(l, valve, 1000);
                rig.be().resample(station, true);
                near(h, station.input(ControllerStationType.IN_PV), 50.0, 0.01, "PV follows the valve's real position");
                // a control switch bound to the open command
                var sw = rig.place(ComponentTypes.CONTROL_SWITCH, 6, 0, Footprint.of(8, 12));
                var other = valveAt(l, root.offset(-8, 0, 10), Direction.EAST);
                h.assertTrue(rig.bindOut(sw, 1, other.getBlockPos(), "open", 1.0) == WiringOps.Result.OK, "the switch's OPEN flag binds to the open command");
                rig.op(sw, OperateAction.SET, ControlSwitchData.OPEN);
                h.assertTrue(other.target() == 1.0, "switch to OPEN: the valve is demanded fully open: " + other.target());
                rig.op(sw, OperateAction.SET, ControlSwitchData.NORMAL);
                h.assertTrue(other.target() == 0.0, "switch to NORMAL: demanded closed: " + other.target());
                // the signals a gauge may measure
                var signals = SignalProviders.of(valve);
                h.assertTrue(signals.read("position").isPresent() && signals.read("open").isPresent() && signals.read("closed").isPresent() && signals.read("flow").isPresent(),
                        "position, open, closed and flow are signals");
                h.assertTrue(signals.signals().stream().filter(s -> s.key().equals("position")).findFirst().map(s -> dev.bwr.mod.signal.Quantity.of(s)).orElse(null)
                        == dev.bwr.mod.signal.Quantity.POSITION, "position is a POSITION quantity");
                h.assertTrue(signals.commands().stream().anyMatch(c -> c.key().equals("position")) && signals.commands().stream().anyMatch(c -> c.key().equals("open")),
                        "position and open are commands");
                h.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------- throttling a line

    /** Pipe from {@code a} to {@code b} (inclusive), in a straight run. */
    private static void pipes(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) {
            l.setBlock(p.immutable(), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        }
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 200)
    public static void aValveThrottlesTheRouteInProportionToItsOpening(GameTestHelper h) {
        var l = h.getLevel();
        var start = new BlockPos(X, 200, X + 80);
        pipes(l, start, start.east(3));
        var valveAt = start.east(4);
        var v = valveAt(l, valveAt, Direction.EAST);
        pipes(l, start.east(5), start.east(8));
        var end = start.east(9);
        l.setBlock(end, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
        var graph = PipeTopology.get(l, start, null, false, false);
        h.assertTrue(graph.ends().stream().anyMatch(n -> n.pos().equals(end)) && !graph.truncated(), "the line reaches the tank through the valve");
        h.assertTrue(graph.edges().keySet().stream().anyMatch(n -> n.kind() == PipeTopology.Kind.VALVE && n.pos().equals(valveAt)), "the valve is a valve node of the water graph");
        power(v, 20_000);
        for (double opening : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
            v.setTarget(opening);
            power(v, 20_000);
            pass(l, v, 800);
            var route = PipeTopology.routes(l, PipeTopology.get(l, start, null, false, false)).stream().filter(r -> r.node().pos().equals(end)).findFirst().orElseThrow();
            near(h, route.opening(), opening, 1.0e-9, "the route is open exactly as far as the valve");
            h.assertTrue(route.waterValves().size() == 1 && route.waterValves().get(0) == v, "the route knows its valve");
            h.assertTrue(PipeTopology.get(l, start, null, false, false) == graph, "moving the valve rebuilds no geometry");
        }
        // cleanup
        for (var p : BlockPos.betweenClosed(start, end)) l.removeBlock(p.immutable(), false);
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 400)
    public static void aValveOnAPumpSuctionLineScalesThePumpsLineOpening(GameTestHelper h) {
        var l = h.getLevel();
        var pumpAt = new BlockPos(X, 200, X + 100);
        for (var p : BlockPos.betweenClosed(pumpAt.offset(-12, -2, -12), pumpAt.offset(12, 4, 12))) l.removeBlock(p.immutable(), false);
        var pump = BwrBlocks.LPCS_PUMP.get();
        var state = pump.placementState().setValue(PumpAssemblyBlock.FACING, Direction.NORTH);
        l.setBlock(pumpAt, state, 3);
        pump.setPlacedBy(l, pumpAt, state, null, new ItemStack(pump));
        var port = pump.portPosition(pumpAt, state, AssemblyPort.WATER_SUCTION);
        var face = pump.portFace(state, AssemblyPort.WATER_SUCTION);
        h.assertTrue(face.getAxis().isHorizontal(), "the suction flange faces sideways: " + face);
        var valveAt = port.relative(face);
        var v = valveAt(l, valveAt, face);
        l.setBlock(valveAt.relative(face), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        var tank = valveAt.relative(face, 2);
        l.setBlock(tank, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
        power(v, 20_000);
        for (double opening : new double[] {1.0, 0.5, 0.2}) {
            v.setTarget(opening);
            power(v, 20_000);
            pass(l, v, 800);
            var line = AssemblyPlumbing.trace(l, pumpAt, l.getBlockState(pumpAt), AssemblyPort.WATER_SUCTION);
            h.assertTrue(line.valid() && line.ends().contains(tank), "the suction line reaches the tank: " + line);
            near(h, line.opening(), opening, 1.0e-9, "the pump's line opening is the valve's");
        }
        v.setTarget(0.0);
        power(v, 20_000);
        pass(l, v, 800);
        var shut = AssemblyPlumbing.trace(l, pumpAt, l.getBlockState(pumpAt), AssemblyPort.WATER_SUCTION);
        h.assertTrue(shut.ends().isEmpty(), "a closed valve leaves the pump no source: " + shut);
        // a plain water line with no valve is full-bore as it always was
        l.removeBlock(valveAt, false);
        pipes(l, valveAt, valveAt);
        var plain = AssemblyPlumbing.trace(l, pumpAt, l.getBlockState(pumpAt), AssemblyPort.WATER_SUCTION);
        h.assertTrue(plain.opening() == 1.0 && plain.ends().contains(tank), "no valve, full bore: " + plain);
        for (var p : BlockPos.betweenClosed(pumpAt.offset(-12, -2, -12), pumpAt.offset(12, 4, 12))) l.removeBlock(p.immutable(), false);
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the reactor's lines

    private static void tick(ReactorControllerBlockEntity be) {
        ReactorControllerBlockEntity.serverTick(be.getLevel(), be.getBlockPos(), be.getBlockState(), be);
    }

    private static void ticks(ReactorControllerBlockEntity be, int n) {
        for (int i = 0; i < n; i++) tick(be);
    }

    private static double mass(ReactorControllerBlockEntity be) {
        var v = be.core().getPressureVessel();
        return v.getLiquidMassKg() + v.getSteamMassKg();
    }

    private static ReactorControllerBlockEntity reload(GameTestHelper h, ReactorControllerBlockEntity be) throws Exception {
        var l = h.getLevel();
        var root = be.getBlockPos();
        var saved = be.saveWithFullMetadata(l.registryAccess());
        l.removeBlockEntity(root);
        var next = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, l.getBlockState(root), saved, l.registryAccess());
        l.setBlockEntity(next);
        SpargerRegressionTests.validate(next);
        return next;
    }

    private static BlockPos feedPort(ServerLevel l, BlockPos at) {
        l.setBlock(at, BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState()
                .setValue(dev.bwr.mod.reactor.RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 3);
        return at;
    }

    /**
     * The feedwater line of LocaRepairTests, with a motor-operated valve in the place of the containment water penetration: a
     * break beyond the closed valve is isolated and no LOCA; stroking it open starts the leak; stroking it shut isolates it again.
     */
    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 300)
    public static void aClosedWaterValveIsolatesAFeedwaterLineBreak(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var min = new BlockPos(X + 400, 190, X + 400);
        var be = CompactCoreRegressionTests.build(l, min, 9, 9, false);
        var feed = feedPort(l, min.offset(4, 5, -1));
        l.setBlock(feed.north(), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        l.setBlock(feed.north(2), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        var valveAt = feed.north(3);
        var valve = valveAt(l, valveAt, Direction.NORTH);
        pipes(l, feed.north(4), feed.north(6));
        l.setBlock(feed.north(7), BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
        SpargerRegressionTests.validate(be);
        ticks(be, 2);
        h.assertTrue(valve.position() == 0.0, "the fixture valve is closed");
        h.assertTrue(be.boundaryEffects().lineCellCount(feed) == 6, "feed line not established through the valve: " + be.boundaryEffects().lineCellCount(feed));
        var gap = feed.north(5);
        l.removeBlock(gap, false);
        tick(be);
        h.assertTrue(be.boundaryEffects().breaks().size() == 1 && !be.boundaryEffects().breaks().getFirst().open(),
                "a break behind a closed valve is isolated, not open: " + be.boundaryEffects().breaks());
        var boundary = be.core().getBoundaryStress();
        h.assertTrue(!boundary.isBroken(BoundaryComponent.FEEDWATER_LINE) && boundary.activeOpenings().isEmpty(), "a break behind a closed valve drained the vessel");
        double before = mass(be);
        ticks(be, 5);
        h.assertTrue(Math.abs(mass(be) - before) < .01, "the vessel's inventory fell through a closed valve: " + (before - mass(be)));
        power(valve, 20_000);
        valve.setTarget(1.0);
        pass(l, valve, 400);
        tick(be);
        h.assertTrue(boundary.isBroken(BoundaryComponent.FEEDWATER_LINE), "stroking the valve open started the LOCA");
        valve.setTarget(0.0);
        power(valve, 20_000);
        pass(l, valve, 400);
        tick(be);
        h.assertTrue(!boundary.isBroken(BoundaryComponent.FEEDWATER_LINE) && boundary.activeOpenings().isEmpty(), "stroking it shut isolated the break again");
        be = reload(h, be);
        tick(be);
        h.assertTrue(be.boundaryEffects().breaks().size() == 1 && !be.core().getBoundaryStress().isBroken(BoundaryComponent.FEEDWATER_LINE),
                "reload kept the break isolated");
        l.setBlock(gap, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        tick(be);
        h.assertTrue(be.boundaryEffects().breaks().isEmpty(), "the pipe put back sealed it");
        h.succeed();
    }

    /**
     * Wear and failure are the pipe's: the feedwater line wears under overpressure by BoundaryStress, and when it fails the whip
     * takes the block at the break (a valve there goes like a pipe) and spares the rest of the line, a valve beside it included.
     */
    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 300)
    public static void overpressureWearBreaksTheLineAndTheValveGoesLikeAPipe(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var min = new BlockPos(X + 700, 190, X + 700);
        var be = CompactCoreRegressionTests.build(l, min, 9, 9, 12, false);
        var feed = feedPort(l, min.offset(4, 3, -1));
        l.setBlock(feed.north(), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        var spared = valveAt(l, feed.north(2), Direction.NORTH);
        pipes(l, feed.north(3), feed.north(3));
        l.setBlock(feed.north(4), BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
        SpargerRegressionTests.validate(be);
        tick(be);
        var boundary = be.core().getBoundaryStress();
        h.assertTrue(boundary.getStress(BoundaryComponent.FEEDWATER_LINE) == 0.0, "a new line has no wear");
        // Above the rating the line wears, as a pipe line: 1500 psig, 125 psi over the 1250 gate squared
        int guard = 0;
        while (!boundary.isBroken(BoundaryComponent.FEEDWATER_LINE) && guard++ < 600) {
            boundary.step(1500.0, 60.0);
        }
        h.assertTrue(boundary.isBroken(BoundaryComponent.FEEDWATER_LINE), "overpressure wear never failed the line in " + guard + " minutes");
        h.assertTrue(boundary.getStress(BoundaryComponent.FEEDWATER_LINE) >= 1.0, "it failed only after wearing out: " + boundary.getStress(BoundaryComponent.FEEDWATER_LINE));
        ticks(be, 6);
        h.assertTrue(l.getBlockState(feed.north()).isAir(), "the pipe at the break was destroyed");
        h.assertTrue(l.getBlockState(feed.north(2)).is(BwrBlocks.MOTOR_OPERATED_WATER_VALVE.get()), "the valve beside the break is part of the line and is spared");
        // the same line with the valve itself at the break
        var min2 = new BlockPos(X + 1000, 190, X + 1000);
        var be2 = CompactCoreRegressionTests.build(l, min2, 9, 9, 12, false);
        var feed2 = feedPort(l, min2.offset(4, 3, -1));
        valveAt(l, feed2.north(), Direction.NORTH);
        pipes(l, feed2.north(2), feed2.north(2));
        l.setBlock(feed2.north(3), BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
        SpargerRegressionTests.validate(be2);
        tick(be2);
        be2.core().getBoundaryStress().forceFailure(BoundaryComponent.FEEDWATER_LINE, 1400);
        ticks(be2, 6);
        h.assertTrue(l.getBlockState(feed2.north()).isAir(), "a valve at the break goes like the pipe it replaced");
        h.succeed();
    }
}
