package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;

/**
 * The CC-independent signal layer: reads are sane, equal the CC peripheral's when CC is present, commands
 * obey the same power rules as the CC setters, and an unloaded position never resolves (or loads).
 *
 * <p>Every fixture is built and read inside one tick, so nothing needs force-loading; each test rebuilds its
 * fixture from scratch so a persistent GameTest world can run it twice.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class SignalLayerTests {
    private SignalLayerTests() {}

    static double read(GameTestHelper h, SignalProvider p, String key) {
        var value = p.read(key);
        h.assertTrue(value.isPresent(), "signal " + key + " has no value");
        return value.getAsDouble();
    }

    static void clear(ServerLevel l, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) l.getChunk((center.getX() + x) >> 4, (center.getZ() + z) >> 4);
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, radius, radius)))
            l.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void signalApiShape(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var root = new BlockPos(52200, 210, 52200);
        clear(l, root, 4);
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        l.setBlock(root, state, 3);
        block.setPlacedBy(l, root, state, null, new ItemStack(block));
        var provider = SignalProviders.at(l, root);
        h.assertTrue(provider != null, "MSIV has no signal provider");
        var keys = new HashSet<String>();
        for (var spec : provider.signals()) {
            h.assertTrue(keys.add(spec.key()), "duplicate signal key " + spec.key());
            h.assertTrue(spec.key().matches("[a-z][a-z0-9_]*"), "signal key not lowercase snake_case: " + spec.key());
            h.assertTrue(spec.min() <= spec.max(), "inverted range on " + spec.key());
        }
        for (var cmd : provider.commands()) h.assertTrue(cmd.key().matches("[a-z][a-z0-9_]*"), "bad command key " + cmd.key());
        h.assertTrue(!provider.read("no_such_signal").isPresent() && !provider.command("no_such_command", 1),
                "unknown keys must read empty and refuse");
        h.assertTrue(!provider.command("open", Double.NaN), "NaN command accepted");
        // Every cell of the assembled valve resolves to the same controller.
        var upper = SignalProviders.at(l, root.above(2));
        h.assertTrue(upper != null && upper.read("position").equals(provider.read("position")), "upper MSIV cell does not resolve to the controller");
        l.removeBlock(root.above(2), false);
        l.removeBlock(root.above(), false);
        l.removeBlock(root, false);
        h.assertTrue(SignalProviders.at(l, root) == null, "removed device still has a provider");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void msivCommandsObeyActuatorPower(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var root = new BlockPos(52100, 210, 52100);
        clear(l, root, 4);
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        l.setBlock(root, state, 3);
        block.setPlacedBy(l, root, state, null, new ItemStack(block));
        var valve = (MainSteamIsolationValveBlockEntity) l.getBlockEntity(root);
        valve.setDemandOpen(false);
        valve.tickValve(5);
        var provider = SignalProviders.at(l, root);
        h.assertTrue(provider != null, "no MSIV provider");
        h.assertTrue(read(h, provider, "powered") == 0 && read(h, provider, "position") == 0 && read(h, provider, "demand_open") == 0,
                "unpowered closed MSIV reads wrong");
        h.assertTrue(!provider.command("open", 1) && valve.isDemandOpen() && valve.isComputerControlled(),
                "unpowered MSIV open command must record the demand like CC and report it cannot move yet");
        h.assertTrue(provider.command("open", 0) && valve.isComputerControlled() && !valve.isDemandOpen(), "close command refused");
        valve.energy().receiveEnergy(20_000, false);
        h.assertTrue(provider.command("open", 1) && valve.isDemandOpen(), "powered MSIV refused to open");
        for (int i = 0; i < 90; i++) valve.tickValve(.05);
        h.assertTrue(read(h, provider, "position") == 1 && read(h, provider, "powered") == 1
                        && read(h, provider, "energy_fe") == valve.energy().getEnergyStored(), "powered MSIV readout wrong");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.msiv(h, valve, provider);
        l.removeBlock(root.above(2), false);
        l.removeBlock(root.above(), false);
        l.removeBlock(root, false);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pumpSignalsAndCommands(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var root = new BlockPos(52300, 210, 52300);
        clear(l, root, 12);
        var recirc = BwrBlocks.RECIRCULATION_PUMP.get();
        var state = recirc.placementState();
        l.setBlock(root, state, 3);
        recirc.setPlacedBy(l, root, state, null, new ItemStack(recirc));
        var motor = (dev.bwr.mod.flow.RecirculationPumpBlockEntity) l.getBlockEntity(root);
        var provider = SignalProviders.at(l, root);
        h.assertTrue(motor != null && provider != null, "recirculation pump has no provider");
        h.assertTrue(provider.command("speed", 0.5) && motor.getTargetSpeedFraction() == 0.5 && motor.isComputerControlled(),
                "recirculation speed command did not reach the motor");
        h.assertTrue(!provider.command("speed", 7) && motor.getTargetSpeedFraction() == 0.5, "out-of-range speed accepted");
        h.assertTrue(read(h, provider, "speed_demand") == 0.5 && read(h, provider, "speed") == motor.getActualSpeedFraction(), "recirculation pump readout wrong");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.recirculationPump(h, motor, provider);
        l.removeBlock(root, false);

        clear(l, root, 12);
        var feed = BwrBlocks.MOTOR_FEED_PUMP.get();
        var feedState = feed.placementState().setValue(PumpAssemblyBlock.FACING, Direction.NORTH);
        l.setBlock(root, feedState, 3);
        feed.setPlacedBy(l, root, feedState, null, new ItemStack(feed));
        var pump = (dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity) l.getBlockEntity(root);
        var feedProvider = SignalProviders.at(l, root);
        h.assertTrue(pump != null && feedProvider != null, "feedwater pump has no provider");
        h.assertTrue(feedProvider.command("run", 1) && pump.isRunning() && pump.isComputerControlled(), "feedwater run command failed");
        h.assertTrue(feedProvider.command("flow_demand", 0.4) && pump.getFlowDemandFraction() == 0.4, "feedwater flow demand command failed");
        h.assertTrue(read(h, feedProvider, "running") == 1 && read(h, feedProvider, "flow") == pump.getDeliveredFlowKgPerS(), "feedwater readout wrong");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.feedwaterPump(h, pump, feedProvider);
        feedProvider.command("run", 0);
        clear(l, root, 12);

        var eccs = BwrBlocks.LPCS_PUMP.get();
        var eccsState = eccs.placementState().setValue(PumpAssemblyBlock.FACING, Direction.NORTH);
        l.setBlock(root, eccsState, 3);
        eccs.setPlacedBy(l, root, eccsState, null, new ItemStack(eccs));
        var emergency = (dev.bwr.mod.eccs.EccsPumpBlockEntity) l.getBlockEntity(root);
        var eccsProvider = SignalProviders.at(l, root);
        h.assertTrue(emergency != null && eccsProvider != null, "ECCS pump has no provider");
        h.assertTrue(eccsProvider.command("speed", 0.75) && emergency.pump().getSpeedDemandFraction() == 0.75, "ECCS speed command failed");
        h.assertTrue(eccsProvider.command("run", 1) && emergency.isRunning() && read(h, eccsProvider, "running") == 1, "ECCS run command failed");
        eccsProvider.command("run", 0);
        h.assertTrue(read(h, eccsProvider, "rated_flow") > 0 && read(h, eccsProvider, "flow") == emergency.getDeliveredFlowKgPerS(), "ECCS readout wrong");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.eccsPump(h, emergency, eccsProvider);
        clear(l, root, 12);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void suppressionPoolSignals(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var at = new BlockPos(52400, 210, 52400);
        clear(l, at, 4);
        l.setBlock(at, BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(), 3);
        var pool = (SuppressionPoolBlockEntity) l.getBlockEntity(at);
        var provider = SignalProviders.at(l, at);
        h.assertTrue(pool != null && provider != null, "suppression pool has no provider");
        h.assertTrue(read(h, provider, "formed") == 0, "unformed pool reads formed");
        h.assertTrue(Double.isFinite(read(h, provider, "temperature")) && read(h, provider, "level") >= 0 && read(h, provider, "level") <= 1,
                "pool readings out of range");
        h.assertTrue(provider.command("rhr_duty", 0.5) && pool.getRhrDuty() == 0.5, "RHR duty command did not reach the pool");
        h.assertTrue(!provider.command("rhr_duty", 3) && pool.getRhrDuty() == 0.5, "out-of-range RHR duty accepted");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.suppressionPool(h, pool, provider);
        pool.setRhrDuty(0);
        l.removeBlock(at, false);
        h.assertTrue(SignalProviders.at(l, at) == null, "removed pool still resolves");
        h.succeed();
    }

    private static void survey(ServerLevel level, ContainmentControllerBlockEntity controller) {
        controller.markDirty();
        for (int i = 0; i < 64; i++) {
            ContainmentControllerBlockEntity.serverTick(level, controller.getBlockPos(), level.getBlockState(controller.getBlockPos()), controller);
            if (!controller.isScanning() && controller.strengthReady()) return;
        }
        throw new AssertionError("containment scan exceeded 64 ticks");
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void reactorAndContainmentSignals(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var min = new BlockPos(52500, 190, 52500);
        int width = 11, depth = 11, height = 8;
        for (int x = -4; x <= width + 3; x++) for (int z = -4; z <= depth + 3; z++) {
            l.getChunk((min.getX() + x) >> 4, (min.getZ() + z) >> 4);
            for (int y = -4; y <= height + 2; y++) {
                boolean shell = x == -4 || x == width + 3 || z == -4 || z == depth + 3 || y == -4 || y == height + 2;
                l.setBlock(min.offset(x, y, z), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
            }
        }
        ReactorControllerBlockEntity reactor = CompactCoreRegressionTests.build(l, min, width, depth, height, false);
        h.assertTrue(reactor.isFormed(), "reference vessel did not form");
        var roomPos = min.offset(-3, 0, -3);
        l.setBlock(roomPos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        var room = (ContainmentControllerBlockEntity) l.getBlockEntity(roomPos);
        survey(l, room);
        for (int i = 0; i < 3; i++) ReactorControllerBlockEntity.serverTick(l, reactor.getBlockPos(), l.getBlockState(reactor.getBlockPos()), reactor);

        var rp = SignalProviders.at(l, reactor.getBlockPos());
        h.assertTrue(rp != null, "reactor controller has no provider");
        h.assertTrue(read(h, rp, "formed") == 1, "formed reactor reads unformed");
        double power = read(h, rp, "power");
        h.assertTrue(power >= 0 && power <= 1.2, "reactor power out of range: " + power);
        h.assertTrue(read(h, rp, "pressure") >= 0 && Double.isFinite(read(h, rp, "level")) && read(h, rp, "core_flow") >= 0, "reactor readings out of range");
        int rods = (int) read(h, rp, "rod_count");
        h.assertTrue(rods > 0 && rp.read("rod_1_position").isPresent() && rp.read("rod_" + rods + "_demand").isPresent()
                        && !rp.read("rod_" + (rods + 1) + "_position").isPresent(), "per-rod signals wrong");
        h.assertTrue(rp.signals().stream().anyMatch(s -> s.key().equals("rod_" + rods + "_accumulator")), "per-rod signal specs missing");
        h.assertTrue(rp.readText("vessel_state").isPresent(), "vessel state text missing");

        h.assertTrue(!rp.command("rod_1_position", 3) && !rp.command("rod_1_position", 50) && !rp.command("rod_" + (rods + 1) + "_position", 0),
                "invalid rod command accepted");
        h.assertTrue(rp.command("rod_1_position", 10) && reactor.rods().getCommandedNotchLabel(0) == 10, "rod command did not set the demand");
        h.assertTrue(rp.command("scram", 1) && read(h, rp, "scram") == 1, "scram command failed");
        h.assertTrue(rp.command("reset_scram", 1), "reset command failed");
        h.assertTrue(rp.command("recirc_flow", 0.5) && read(h, rp, "recirc_flow_demand") == reactor.core().getRecirculationFlowFractionDemand(), "recirculation demand readback wrong");
        reactor.rods().setNotchLabelDemand(0, 0);

        var cp = SignalProviders.at(l, roomPos);
        h.assertTrue(cp != null && read(h, cp, "sealed") == 1 && read(h, cp, "free_volume") > 0, "containment room readings wrong");
        h.assertTrue(room.linkedReactors().contains(reactor.getBlockPos()), "room did not link the reactor");
        h.assertTrue(Double.isFinite(read(h, cp, "h2_percent")) && Double.isFinite(read(h, cp, "pressure")) && read(h, cp, "o2_percent") > 0,
                "containment atmosphere readings wrong");
        h.assertTrue(cp.commands().isEmpty(), "containment controller must expose no commands");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.reactorAndContainment(h, reactor, rp, room, cp);

        // A reactor-less room reports no atmosphere signal instead of inventing zeros.
        var empty = new BlockPos(52700, 190, 52700);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -4; y <= 4; y++) {
            l.getChunk((empty.getX() + x) >> 4, (empty.getZ() + z) >> 4);
            boolean wall = Math.abs(x) == 4 || Math.abs(z) == 4 || Math.abs(y) == 4;
            l.setBlock(empty.offset(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
        }
        BlockPos sleeve = empty.offset(4, 0, 0);
        l.setBlock(sleeve, BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState().setValue(ContainmentPenetrationBlock.FACING, Direction.EAST), 3);
        l.setBlock(empty, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        var bare = (ContainmentControllerBlockEntity) l.getBlockEntity(empty);
        survey(l, bare);
        var bareProvider = SignalProviders.at(l, empty);
        h.assertTrue(read(h, bareProvider, "sealed") == 1 && !bareProvider.read("h2_percent").isPresent() && !bareProvider.read("bypass_flow").isPresent(),
                "reactor-less room reported atmosphere");

        var pen = SignalProviders.at(l, sleeve);
        var valve = (ContainmentPenetrationBlockEntity) l.getBlockEntity(sleeve);
        h.assertTrue(pen != null && valve != null && read(h, pen, "isolated") == 1, "penetration provider wrong");
        boolean demandBefore = valve.demandOpen();
        h.assertTrue(!pen.command("open", 1) && valve.demandOpen() == demandBefore && !valve.isComputerControlled(),
                "unpowered penetration accepted an open command");
        valve.energy().receiveEnergy(8_000, false);
        h.assertTrue(pen.command("open", 1) && valve.demandOpen() && valve.isComputerControlled(), "powered penetration refused to open");
        h.assertTrue(pen.command("open", 0) && !valve.demandOpen(), "penetration close failed");
        if (BwrMod.isComputerCraftPresent()) SignalCcChecks.penetration(h, valve, pen);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unloadedAndEmptyPositionsHaveNoProvider(GameTestHelper h) {
        var l = h.getLevel();
        var far = new BlockPos(9_000_000, 100, 9_000_000);
        h.assertTrue(!l.isLoaded(far), "fixture chunk is unexpectedly loaded");
        h.assertTrue(SignalProviders.at(l, far) == null, "unloaded position resolved a provider");
        h.assertTrue(!l.isLoaded(far), "looking up an unloaded position loaded its chunk");
        var air = new BlockPos(52900, 150, 52900);
        l.getChunk(air.getX() >> 4, air.getZ() >> 4);
        l.setBlock(air, Blocks.AIR.defaultBlockState(), 2);
        h.assertTrue(SignalProviders.at(l, air) == null, "empty air has a provider");
        l.setBlock(air, Blocks.STONE.defaultBlockState(), 2);
        h.assertTrue(SignalProviders.at(l, air) == null, "plain stone has a provider");
        l.setBlock(air, Blocks.AIR.defaultBlockState(), 2);
        h.assertTrue(SignalProviders.at(null, air) == null, "null level did not return null");
        h.succeed();
    }
}
