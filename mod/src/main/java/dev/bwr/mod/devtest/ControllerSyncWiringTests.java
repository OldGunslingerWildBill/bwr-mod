package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControllerStationData;
import dev.bwr.mod.panel.component.ControllerStationType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.StationLayout;
import dev.bwr.mod.panel.client.BindModel;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.steam.TurbineValveBlock;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * The manual/auto controller station wired to real plant machines the way a player wires it: the Networking Wrench selects
 * the machine, the binding payload (the one the panel link screen sends, encoded and decoded with the wire codec and given
 * to the real handler) binds the station's {@code demand} output to the machine's command and its {@code pv} input to a
 * signal, and every change after that is the player pressing RAISE and LOWER the way the client does.
 *
 * <p>World fixture at 260_000 / 260_000 and 260_200 / 260_000 (see {@link Rig}). The synchroscope half of the check lives in
 * {@link ControllerSyncSynchroscopeTests}.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ControllerSyncWiringTests {
    private ControllerSyncWiringTests() {
    }

    static final int ORIGIN_X = 260_000;
    static final int ORIGIN_Z = 260_000;

    /** A cleared stage: one panel block facing north and a survival fake player in front of it, machines behind and around it. */
    static final class Rig {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos panel;
        final FakePlayer player;
        final BlockPos lo;
        final BlockPos hi;

        Rig(GameTestHelper helper, BlockPos panel, String name) {
            this(helper, panel, name, panel.offset(-20, -4, -6), panel.offset(22, 10, 22));
        }

        Rig(GameTestHelper helper, BlockPos panel, String name, BlockPos lo, BlockPos hi) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.panel = panel;
            this.lo = lo;
            this.hi = hi;
            this.player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("controller-sync-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "cs-" + name));
        }

        AABB bounds() {
            return new AABB(lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ());
        }

        void clear() {
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                if (!level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        void start() {
            clear();
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.setShiftKeyDown(false);
            player.setPos(panel.getX() + 0.5, panel.getY() - 1.0, panel.getZ() - 1.5);
            level.setBlock(panel, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
        }

        PanelBlockEntity be() {
            return (PanelBlockEntity) level.getBlockEntity(panel);
        }

        PanelComponent place(ComponentType type, int x, int y, Footprint size) {
            PanelComponent placed = PanelOps.placeDirect(level, panel, x, y, type, size);
            helper.assertTrue(placed != null, "placement refused " + type.id() + " at " + x + "," + y);
            return placed;
        }

        /** The Networking Wrench in the player's hand with {@code device} selected, as a right-click on the machine leaves it. */
        void select(BlockPos device) {
            ItemStack stack = new ItemStack(BwrItems.NETWORKING_WRENCH.get());
            stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), device));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }

        IPayloadContext context() {
            return (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                    new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("player")) {
                            return player;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        /** Sends a binding as the client does (wire codec, then the real handler) and returns the outcome of the same request. */
        WiringOps.Result wire(PanelPayloads.Bind bind) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            PanelPayloads.Bind.STREAM_CODEC.encode(buffer, bind);
            PanelPayloads.Bind.STREAM_CODEC.decode(buffer);
            return WiringOps.bind(player, bind);
        }

        /** Binds output {@code port} of {@code c} to the command {@code key} of {@code device}, with {@code scale}. */
        WiringOps.Result bindOut(PanelComponent c, int port, BlockPos device, String key, double scale) {
            select(device);
            return wire(new PanelPayloads.Bind(panel, c.id(), true, port, WiringOps.BindKind.PLANT.ordinal(), device, key,
                    BlockPos.ZERO, 0, 0, scale));
        }

        /** Binds input {@code port} of {@code c} to the signal {@code key} of {@code device}. */
        WiringOps.Result bindIn(PanelComponent c, int port, BlockPos device, String key, double scale) {
            select(device);
            return wire(new PanelPayloads.Bind(panel, c.id(), false, port, WiringOps.BindKind.PLANT.ordinal(), device, key,
                    BlockPos.ZERO, 0, 0, scale, 0, 0.0, 0.0));
        }

        boolean op(PanelComponent c, OperateAction action, double value) {
            return PanelOps.operate(player, panel, c.id(), action, value);
        }

        /** A click on a station button the way the client sends it: the type's own click value for the button's centre. */
        boolean press(PanelComponent station, int region) {
            float[] at = StationLayout.centreOf(region);
            return op(station, OperateAction.PRESS, station.type().clickValue(station, at[0] * station.w(), at[1] * station.h()));
        }

        void release(PanelComponent station) {
            op(station, OperateAction.RELEASE, 0.0);
        }

        void finish() {
            clear();
        }
    }

    /** One machine the station drives: where it stands, the command, the scale that maps 100 % onto the command's range. */
    private record Target(String name, BlockPos at, String key, double max, DoubleSupplier readback) {
    }

    private static final double EPS = 1.0e-6;

    // ------------------------------------------------------------------------------------------ MAN against real machines

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void manualStationDrivesRealMachinesAndTheyFollowOut(GameTestHelper h) {
        BlockPos root = new BlockPos(ORIGIN_X, 245, ORIGIN_Z);
        Rig rig = new Rig(h, root, "man");
        EntityLookupProbe.runWhenLive(h, rig.bounds(), () -> {
            try {
                rig.start();
                ServerLevel l = rig.level;
                // The machines, placed through their real blocks.
                BlockPos valveAt = root.offset(-12, 0, 6);
                l.setBlock(valveAt, BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING, Direction.EAST), 3);
                BlockPos bypassAt = root.offset(-12, 0, 10);
                l.setBlock(bypassAt, BwrBlocks.BYPASS_STEAM_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING, Direction.EAST), 3);
                BlockPos recircAt = root.offset(-6, 0, 8);
                var recirc = BwrBlocks.RECIRCULATION_PUMP.get();
                var recircState = recirc.placementState();
                l.setBlock(recircAt, recircState, 3);
                recirc.setPlacedBy(l, recircAt, recircState, null, new ItemStack(recirc));
                BlockPos feedAt = root.offset(0, 0, 8);
                var feed = BwrBlocks.MOTOR_FEED_PUMP.get();
                var feedState = feed.placementState().setValue(PumpAssemblyBlock.FACING, Direction.NORTH);
                l.setBlock(feedAt, feedState, 3);
                feed.setPlacedBy(l, feedAt, feedState, null, new ItemStack(feed));
                BlockPos lpcsAt = root.offset(8, 0, 8);
                var lpcs = BwrBlocks.LPCS_PUMP.get();
                var lpcsState = lpcs.placementState().setValue(PumpAssemblyBlock.FACING, Direction.NORTH);
                l.setBlock(lpcsAt, lpcsState, 3);
                lpcs.setPlacedBy(l, lpcsAt, lpcsState, null, new ItemStack(lpcs));
                BlockPos tieAt = root.offset(16, 0, 8);
                l.setBlock(tieAt, BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);

                var valve = (TurbineValveBlockEntity) l.getBlockEntity(valveAt);
                var bypass = (TurbineValveBlockEntity) l.getBlockEntity(bypassAt);
                var motor = (dev.bwr.mod.flow.RecirculationPumpBlockEntity) l.getBlockEntity(recircAt);
                var feedPump = (dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity) l.getBlockEntity(feedAt);
                var emergency = (dev.bwr.mod.eccs.EccsPumpBlockEntity) l.getBlockEntity(lpcsAt);
                var tie = (GridTieBlockEntity) l.getBlockEntity(tieAt);
                SignalProvider tieSignals = SignalProviders.of(tie);
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(1500.0);
                List<Target> targets = List.of(
                        new Target("turbine control valve position", valveAt, "position", 1.0, valve::target),
                        new Target("bypass valve position", bypassAt, "position", 1.0, bypass::target),
                        new Target("recirculation pump speed", recircAt, "speed", 1.0, motor::getTargetSpeedFraction),
                        new Target("feedwater pump flow demand", feedAt, "flow_demand", 1.0, feedPump::getFlowDemandFraction),
                        new Target("LPCS pump speed", lpcsAt, "speed", 1.0, () -> emergency.pump().getSpeedDemandFraction()),
                        new Target("grid tie fixed MW (turbine load)", tieAt, "fixed_mw", 1500.0, () -> tieSignals.read("fixed_mw").orElse(-1)),
                        new Target("grid tie remote MW (turbine load)", tieAt, "remote_mw", 1500.0, () -> tieSignals.read("remote_mw").orElse(-1)));

                PanelComponent station = rig.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
                ControllerStationData data = (ControllerStationData) station.data();
                h.assertTrue(!data.auto && station.output(ControllerStationType.OUT_MODE) == 0.0, "a placed station is in MAN");
                for (Target t : targets) {
                    // Each machine in turn: wire it, drive it, unwire it.
                    double before = t.readback().getAsDouble();
                    h.assertTrue(rig.bindOut(station, ControllerStationType.OUT_DEMAND, t.at(), t.key(), guiScale(rig, station, t.at(), t.key())) == WiringOps.Result.OK,
                            "binding demand to " + t.name());
                    h.assertTrue(t.readback().getAsDouble() == before, t.name() + " untouched by the binding itself: " + before + " to " + t.readback().getAsDouble());
                    op(rig, station, OperateAction.SET, 0.0);
                    for (int i = 0; i < 40; i++) {
                        h.assertTrue(rig.press(station, StationLayout.RAISE), "RAISE handled");
                    }
                    rig.release(station);
                    h.assertTrue(Math.abs(t.readback().getAsDouble() - 0.40 * t.max()) < EPS * t.max(),
                            t.name() + " follows OUT 40 %: " + t.readback().getAsDouble() + " of " + t.max());
                    h.assertTrue(Math.abs(station.output(ControllerStationType.OUT_DEMAND) - 40.0) < EPS, "OUT is 40");
                    for (int i = 0; i < 10; i++) {
                        rig.press(station, StationLayout.LOWER);
                    }
                    rig.release(station);
                    h.assertTrue(Math.abs(t.readback().getAsDouble() - 0.30 * t.max()) < EPS * t.max(), t.name() + " follows LOWER to 30 %: " + t.readback().getAsDouble());
                    // A held RAISE is a PRESS repeated by the client with no RELEASE between: it keeps stepping.
                    for (int i = 0; i < 25; i++) {
                        rig.press(station, StationLayout.RAISE);
                    }
                    h.assertTrue(station.output(ControllerStationType.OUT_RAISE) == 1.0 && Math.abs(t.readback().getAsDouble() - 0.55 * t.max()) < EPS * t.max(),
                            t.name() + " follows a held RAISE to 55 %: " + t.readback().getAsDouble());
                    rig.release(station);
                    h.assertTrue(Math.abs(t.readback().getAsDouble() - 0.55 * t.max()) < EPS * t.max() && station.output(ControllerStationType.OUT_RAISE) == 0.0,
                            "letting go stops it where it was");
                    // The step is a setting; the wheel steps too.
                    data.step = 5.0;
                    rig.press(station, StationLayout.RAISE);
                    rig.release(station);
                    h.assertTrue(Math.abs(t.readback().getAsDouble() - 0.60 * t.max()) < EPS * t.max(), t.name() + " Step 5: " + t.readback().getAsDouble());
                    data.step = 1.0;
                    rig.op(station, OperateAction.TURN, 4.0);
                    h.assertTrue(Math.abs(t.readback().getAsDouble() - 0.64 * t.max()) < EPS * t.max(), t.name() + " the wheel: " + t.readback().getAsDouble());
                    // The ends: 100 % is the command's maximum exactly (never refused for a rounding error), 0 % its minimum.
                    for (int i = 0; i < 40; i++) {
                        rig.press(station, StationLayout.RAISE);
                    }
                    rig.release(station);
                    h.assertTrue(station.output(ControllerStationType.OUT_DEMAND) == 100.0 && Math.abs(t.readback().getAsDouble() - t.max()) < EPS * t.max(),
                            t.name() + " reaches its maximum at 100 %: " + t.readback().getAsDouble());
                    for (int i = 0; i < 110; i++) {
                        rig.press(station, StationLayout.LOWER);
                    }
                    rig.release(station);
                    h.assertTrue(station.output(ControllerStationType.OUT_DEMAND) == 0.0 && Math.abs(t.readback().getAsDouble()) < EPS * t.max(),
                            t.name() + " reaches 0 at 0 %: " + t.readback().getAsDouble());
                    // MAN: SP is shown but drives nothing; RAISE never moved it.
                    h.assertTrue(station.output(ControllerStationType.OUT_SETPOINT) == 50.0, "SP untouched by MAN: " + station.output(ControllerStationType.OUT_SETPOINT));
                    rig.wire(new PanelPayloads.Bind(root, station.id(), true, ControllerStationType.OUT_DEMAND, WiringOps.BindKind.NONE.ordinal(),
                            BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0));
                    h.assertTrue(station.sinks(ControllerStationType.OUT_DEMAND).isEmpty(), "unbound again");
                }
                // The physical valve follows its demand: real ticks of the real valve.
                rig.bindOut(station, ControllerStationType.OUT_DEMAND, valveAt, "position", guiScale(rig, station, valveAt, "position"));
                op(rig, station, OperateAction.SET, 40.0);
                h.assertTrue(Math.abs(valve.target() - 0.40) < EPS && valve.position() < 0.40, "demand 40 %, still closed enough: " + valve.position());
                for (int i = 0; i < 20 * 60; i++) {
                    TurbineValveBlockEntity.serverTick(l, valveAt, l.getBlockState(valveAt), valve);
                }
                h.assertTrue(Math.abs(valve.position() - 0.40) < 1.0e-3, "the valve strokes to the station's OUT: " + valve.position());
                // The PV pointer reads the machine back through a bound signal (valve position, fraction to percent).
                h.assertTrue(rig.bindIn(station, ControllerStationType.IN_PV, valveAt, "position", 100.0) == WiringOps.Result.OK, "PV binding");
                rig.be().resample(station, true);
                h.assertTrue(Math.abs(station.input(ControllerStationType.IN_PV) - valve.position() * 100.0) < 1.0e-6
                        && Math.abs(station.input(ControllerStationType.IN_PV) - 40.0) < 0.2, "PV follows the valve: " + station.input(ControllerStationType.IN_PV));
                h.succeed();
            } finally {
                dev.bwr.mod.grid.GridConfig.MAXIMUM_MW.set(1500.0);
                rig.finish();
            }
        });
    }

    /**
     * The scale the panel link screen starts a station's demand binding with for this command (what a player who types
     * nothing gets): the screen contents the server sends for the machine ({@link WiringOps#screenFor}), then
     * {@link BindModel#defaultOutputScale} on the command's entry, parsed the way the screen's field is.
     */
    static double guiScale(Rig rig, PanelComponent station, BlockPos device, String key) {
        rig.select(device);
        var screen = WiringOps.screenFor(rig.player, rig.panel, station.id());
        if (screen == null) {
            throw new AssertionError("no binding screen for " + device);
        }
        for (PanelPayloads.CommandEntry entry : screen.commands()) {
            if (entry.key().equals(key)) {
                return Double.parseDouble(BindModel.defaultOutputScale("controller_station", "demand", entry, "1"));
            }
        }
        throw new AssertionError("no command " + key + " on the screen of " + device);
    }

    private static void op(Rig rig, PanelComponent station, OperateAction action, double value) {
        rig.op(station, action, value);
    }

    // ------------------------------------------------------------------------------------------ AUTO, transfer, save and reload, broken links

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 600)
    public static void autoMovesTheSetpointOnlyTransferAndReloadKeepTheModeAndABrokenLinkIsHarmless(GameTestHelper h) {
        BlockPos root = new BlockPos(ORIGIN_X + 200, 245, ORIGIN_Z);
        Rig rig = new Rig(h, root, "auto");
        EntityLookupProbe.runWhenLive(h, rig.bounds(), () -> {
            try {
                rig.start();
                ServerLevel l = rig.level;
                BlockPos valveAt = root.offset(-8, 0, 6);
                l.setBlock(valveAt, BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING, Direction.EAST), 3);
                var valve = (TurbineValveBlockEntity) l.getBlockEntity(valveAt);
                PanelComponent station = rig.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
                ControllerStationData data = (ControllerStationData) station.data();
                // Nothing was selected for us: a placed station is in MAN and its demand is 0.
                h.assertTrue(!data.auto && data.demand == 0.0 && station.output(ControllerStationType.OUT_MODE) == 0.0, "default is MAN, OUT 0");
                h.assertTrue(rig.bindOut(station, ControllerStationType.OUT_DEMAND, valveAt, "position", guiScale(rig, station, valveAt, "position")) == WiringOps.Result.OK, "demand to the valve");
                h.assertTrue(rig.bindIn(station, ControllerStationType.IN_PV, valveAt, "position", 100.0) == WiringOps.Result.OK, "PV from the valve");
                // MAN to 40 %: the plant follows.
                op(rig, station, OperateAction.SET, 40.0);
                h.assertTrue(Math.abs(valve.target() - 0.40) < EPS, "MAN 40 %: valve target " + valve.target());
                // Go to AUTO: nothing about the plant changes by itself.
                h.assertTrue(rig.press(station, StationLayout.AUTO), "AUTO pressed");
                h.assertTrue(data.auto && station.output(ControllerStationType.OUT_MODE) == 1.0 && Math.abs(valve.target() - 0.40) < EPS,
                        "selecting AUTO is the only thing that happened: valve target " + valve.target());
                // RAISE and LOWER move the SP, never OUT or the valve.
                for (int i = 0; i < 10; i++) {
                    rig.press(station, StationLayout.RAISE);
                }
                rig.release(station);
                h.assertTrue(Math.abs(station.output(ControllerStationType.OUT_SETPOINT) - 60.0) < EPS && station.output(ControllerStationType.OUT_DEMAND) == 40.0
                        && Math.abs(valve.target() - 0.40) < EPS, "AUTO RAISE x10: SP 60, OUT and valve unchanged: sp " + station.output(ControllerStationType.OUT_SETPOINT));
                for (int i = 0; i < 25; i++) {
                    rig.press(station, StationLayout.LOWER);
                }
                rig.release(station);
                h.assertTrue(Math.abs(station.output(ControllerStationType.OUT_SETPOINT) - 35.0) < EPS && Math.abs(valve.target() - 0.40) < EPS, "AUTO LOWER x25: SP 35");
                // Mode, demand and SP survive a save and a reload of the whole panel block; loading sends nothing to the valve.
                valve.setTarget(0.123);
                CompoundTag saved = rig.be().saveWithoutMetadata(l.registryAccess());
                rig.be().loadWithComponents(saved, l.registryAccess());
                PanelComponent back = rig.be().component(station.id());
                ControllerStationData backData = (ControllerStationData) back.data();
                h.assertTrue(backData.auto && back.output(ControllerStationType.OUT_MODE) == 1.0 && back.output(ControllerStationType.OUT_DEMAND) == 40.0
                                && Math.abs(back.output(ControllerStationType.OUT_SETPOINT) - 35.0) < EPS,
                        "AUTO, OUT 40 and SP 35 are back after a reload: " + backData.toTag());
                h.assertTrue(valve.target() == 0.123, "loading the panel sent nothing to the plant: valve target " + valve.target());
                // Back to MAN with no auto_demand signal: bumpless, OUT unchanged, the plant not touched.
                valve.setTarget(0.40);
                h.assertTrue(rig.press(station, StationLayout.MAN), "MAN pressed");
                h.assertTrue(!backData.auto && back.output(ControllerStationType.OUT_DEMAND) == 40.0 && Math.abs(valve.target() - 0.40) < EPS,
                        "AUTO to MAN is bumpless with no outside demand: OUT " + back.output(ControllerStationType.OUT_DEMAND));
                // A broken link: remove the machine. Pressing RAISE does not throw, OUT still moves, nothing else does.
                l.removeBlock(valveAt, false);
                h.assertTrue(rig.press(station, StationLayout.RAISE) && back.output(ControllerStationType.OUT_DEMAND) == 41.0, "RAISE with the machine gone still moves OUT");
                rig.release(station);
                rig.be().resample(back, true);
                h.assertTrue(Double.isNaN(back.input(ControllerStationType.IN_PV)), "PV of a missing machine is no signal, not zero: " + back.input(ControllerStationType.IN_PV));
                h.assertTrue(ControllerStationType.shownDemand(back) == 41.0 && Math.abs(ControllerStationType.shownSetpoint(back) - 35.0) < EPS,
                        "what the bar and the SP pointer show: " + ControllerStationType.shownDemand(back) + " / " + ControllerStationType.shownSetpoint(back));
                // An unbound station (no PV, no setpoint, no auto demand), degenerate scale: no exception, sane readings.
                PanelComponent bare = rig.place(ComponentTypes.CONTROLLER_STATION, 4, 0, Footprint.of(3, 6));
                ControllerStationData bareData = (ControllerStationData) bare.data();
                bareData.min = 5.0;
                bareData.max = 5.0;
                h.assertTrue(Double.isNaN(bare.input(ControllerStationType.IN_PV)) && bareData.fraction(Double.NaN) == 0.0
                        && bareData.span() == 1.0 && !Double.isNaN(ControllerStationType.shownSetpoint(bare)), "an unbound, zero span station reads sanely");
                rig.press(bare, StationLayout.AUTO);
                rig.press(bare, StationLayout.RAISE);
                rig.release(bare);
                rig.press(bare, StationLayout.MAN);
                h.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    // ------------------------------------------------------------------------------------------ the reactor's recirculation command

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 800)
    public static void manualStationDrivesTheReactorRecirculationDemandThroughTheLinkPath(GameTestHelper h) {
        BlockPos min = new BlockPos(ORIGIN_X + 400, 190, ORIGIN_Z);
        BlockPos root = min.offset(-20, 4, 0);
        Rig rig = new Rig(h, root, "reactor", root.offset(-4, -3, -4), root.offset(4, 3, 4));
        AABB all = rig.bounds().minmax(new AABB(min.getX() - 6, min.getY() - 6, min.getZ() - 6, min.getX() + 18, min.getY() + 14, min.getZ() + 18));
        EntityLookupProbe.runWhenLive(h, all, () -> {
            ServerLevel l = rig.level;
            try {
                rig.start();
                for (int x = -4; x <= 14; x++) {
                    for (int z = -4; z <= 14; z++) {
                        for (int y = -4; y <= 10; y++) {
                            boolean shell = x == -4 || x == 14 || z == -4 || z == 14 || y == -4 || y == 10;
                            l.setBlock(min.offset(x, y, z), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                        }
                    }
                }
                var reactor = CompactCoreRegressionTests.build(l, min, 11, 11, 8, false);
                h.assertTrue(reactor.isFormed(), "reference vessel formed");
                PanelComponent station = rig.place(ComponentTypes.CONTROLLER_STATION, 0, 0, Footprint.of(3, 6));
                SignalProvider rp = SignalProviders.at(l, reactor.getBlockPos());
                BlockPos reactorAt = reactor.getBlockPos();
                // Boron injection (0 to 100 ppm/min) has a readback on the core: the screen's default scale is 1 and OUT 40 % is 40 ppm/min.
                double boronScale = guiScale(rig, station, reactorAt, "boron_injection");
                h.assertTrue(boronScale == 1.0 && rig.bindOut(station, ControllerStationType.OUT_DEMAND, reactorAt, "boron_injection", boronScale) == WiringOps.Result.OK,
                        "demand to boron_injection at scale " + boronScale);
                for (int i = 0; i < 40; i++) {
                    rig.press(station, StationLayout.RAISE);
                }
                rig.release(station);
                h.assertTrue(Math.abs(reactor.core().getBoronInjectionPpmPerMinute() - 40.0) < EPS, "OUT 40 % is 40 ppm/min: " + reactor.core().getBoronInjectionPpmPerMinute());
                // Recirculation flow demand (0 to 1.2 of rated): the screen's default scale is 0.012. The reactor of this fixture has no
                // pumps, so the demand has nothing to act on and cannot be read back; what is checked is that the command is accepted at
                // every OUT the station can send, with no rounding refusal at the top.
                double recircScale = guiScale(rig, station, reactorAt, "recirc_flow");
                h.assertTrue(Math.abs(recircScale - 0.012) < 1.0e-12, "default scale for 0 to 1.2: " + recircScale);
                for (int percent = 0; percent <= 100; percent++) {
                    h.assertTrue(rp.command("recirc_flow", percent * recircScale), "recirc_flow accepted at OUT " + percent + " %: " + percent * recircScale);
                }
                h.assertTrue(rig.bindOut(station, ControllerStationType.OUT_DEMAND, reactorAt, "recirc_flow", recircScale) == WiringOps.Result.OK
                        && station.sinks(ControllerStationType.OUT_DEMAND).size() == 2, "a second sink on the same output");
                rig.be().resample(station, true);
                h.assertTrue(rig.bindIn(station, ControllerStationType.IN_PV, reactorAt, "power", 100.0) == WiringOps.Result.OK, "PV from power");
                rig.be().resample(station, true);
                h.assertTrue(!Double.isNaN(station.input(ControllerStationType.IN_PV)), "PV reads the reactor power: " + station.input(ControllerStationType.IN_PV));
                h.succeed();
            } finally {
                rig.finish();
                for (BlockPos p : BlockPos.betweenClosed(min.offset(-4, -4, -4), min.offset(14, 10, 14))) {
                    if (!l.getBlockState(p).isAir()) {
                        l.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------ the panel link screen's default scale

    @GameTest(template = "empty", batch = "controllersync")
    public static void theLinkScreenStartsAStationDemandBindingAtOneHundredthOfTheCommandsRange(GameTestHelper h) {
        var number = (java.util.function.BiFunction<Double, Double, PanelPayloads.CommandEntry>) (min, max) ->
                new PanelPayloads.CommandEntry("k", "K", false, Kind.NUMBER.ordinal(), min, max);
        h.assertTrue(Double.parseDouble(BindModel.defaultOutputScale("controller_station", "demand", number.apply(0.0, 1.0), "1")) == 0.01,
                "0 to 1 command: 0.01 a percent");
        h.assertTrue(Math.abs(Double.parseDouble(BindModel.defaultOutputScale("controller_station", "demand", number.apply(0.0, 1.2), "1")) - 0.012) < 1.0e-12,
                "0 to 1.2: 0.012");
        h.assertTrue(Double.parseDouble(BindModel.defaultOutputScale("controller_station", "demand", number.apply(0.0, 1500.0), "1")) == 15.0
                && Double.parseDouble(BindModel.defaultOutputScale("controller_station", "demand", number.apply(0.0, 100.0), "1")) == 1.0,
                "0 to 1500: 15, 0 to 100: 1");
        // Anything else keeps what the field already said.
        h.assertTrue(BindModel.defaultOutputScale("controller_station", "demand",
                        new PanelPayloads.CommandEntry("open", "Open", false, Kind.BOOLEAN.ordinal(), 0.0, 1.0), "7").equals("1"),
                "a boolean command is sent 1:1");
        h.assertTrue(BindModel.defaultOutputScale("controller_station", "raise", number.apply(0.0, 1.0), "7").equals("7")
                && BindModel.defaultOutputScale("setpoint_knob", "value", number.apply(0.0, 1.0), "7").equals("7")
                && BindModel.defaultOutputScale("controller_station", "demand", null, "7").equals("1"),
                "other outputs and other components are left as they were");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------ the synchroscope's lamps

    @GameTest(template = "empty", batch = "controllersync")
    public static void theSynchroscopeLampsAreRoundLensesNotSquarePushbuttonCaps(GameTestHelper h) {
        PanelComponent scope = new PanelComponent(1, ComponentTypes.SYNCHROSCOPE, 0, 0, Footprint.of(6, 8), "SY-1");
        var model = dev.bwr.mod.panel.client.ComponentModels.get(ComponentTypes.SYNCHROSCOPE.id());
        List<float[]> atLens = new java.util.ArrayList<>();
        model.buildStatic(new dev.bwr.mod.panel.client.QuadSink() {
            @Override
            public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3,
                             float z3, int rgb, int normal, boolean emissive) {
                if (z0 == 1.8f && z1 == 1.8f && z2 == 1.8f && z3 == 1.8f) {
                    atLens.add(new float[] {x0, y0, x1, y1, x2, y2, x3, y3});
                }
            }
        }, scope);
        // Two lamps, each a fan of triangles (a quad with its last corner repeated); a square cap would be 2 four-cornered quads.
        h.assertTrue(atLens.size() >= 36 && atLens.stream().allMatch(q -> q[4] == q[6] && q[5] == q[7]),
                "each lamp lens is a fan of triangles, not a rectangle: " + atLens.size() + " pieces at the lens elevation");
        h.succeed();
    }
}
