package dev.bwr.mod.devtest;

import dev.bwr.core.grid.Synchronizer;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SyncMath;
import dev.bwr.mod.panel.component.SynchroscopeType;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.TurbineValveBlock;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A full synchronisation through a real panel, the way a player does it: a synchroscope wired (Networking Wrench, binding
 * payload) to the grid tie's {@code phase_degrees} and {@code slip_hz}, three pushbuttons wired to the tie's
 * {@code close_breaker} and {@code trip_breaker}, the turbine rolled and trimmed by the real fractional control valve with
 * real steam. Neither the shaft speed nor the phase is ever written.
 *
 * <p>World fixture at 260_800 / 260_000 (see {@link ControllerSyncWiringTests.Rig}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ControllerSyncSynchroscopeTests {
    private ControllerSyncSynchroscopeTests() {
    }

    private static final BlockPos ROOT = new BlockPos(260_800, 245, 260_000);

    @GameTest(template = "empty", batch = "controllersync", timeoutTicks = 400)
    public static void synchroscopeTurnsTheRightWayAtTheRightRateAndAPanelButtonClosesInPhase(GameTestHelper h) {
        var l = h.getLevel();
        BlockPos lo = ROOT.offset(-28, -8, -12);
        BlockPos hi = ROOT.offset(20, 9, 12);
        BlockPos panelAt = ROOT.offset(10, 0, -10);
        ControllerSyncWiringTests.Rig rig = new ControllerSyncWiringTests.Rig(h, panelAt, "sync", lo, hi);
        EntityLookupProbe.runWhenLive(h, rig.bounds(), () -> {
            long time = l.getGameTime();
            try {
                rig.start();
                var supply = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), ROOT.west(20), Direction.EAST);
                var lp = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), ROOT, Direction.EAST);
                var gen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), ROOT.east(8), Direction.EAST);
                var condenser = PowerModuleRuntimeCheck.attachCondenser(lp);
                var a = supply.block().portPosition(supply.getBlockPos(), supply.getBlockState(), AssemblyPort.STEAM_EXHAUST)
                        .relative(supply.block().portFace(supply.getBlockState(), AssemblyPort.STEAM_EXHAUST));
                var b = lp.block().portPosition(lp.getBlockPos(), lp.getBlockState(), AssemblyPort.STEAM_INLET)
                        .relative(lp.block().portFace(lp.getBlockState(), AssemblyPort.STEAM_INLET));
                var ah = new BlockPos(a.getX(), ROOT.getY() + 6, ROOT.getZ() - 8);
                var bh = new BlockPos(b.getX(), ah.getY(), ah.getZ());
                PowerModuleRuntimeCheck.pipe(l, a, new BlockPos(a.getX(), ah.getY(), a.getZ()));
                PowerModuleRuntimeCheck.pipe(l, new BlockPos(a.getX(), ah.getY(), a.getZ()), ah);
                PowerModuleRuntimeCheck.pipe(l, ah, bh);
                PowerModuleRuntimeCheck.pipe(l, bh, new BlockPos(b.getX(), bh.getY(), b.getZ()));
                PowerModuleRuntimeCheck.pipe(l, new BlockPos(b.getX(), bh.getY(), b.getZ()), b);
                var vp = new BlockPos(ROOT.getX() - 10, ah.getY(), ah.getZ());
                l.setBlock(vp, BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING, Direction.EAST), 3);
                var valve = (TurbineValveBlockEntity) l.getBlockEntity(vp);
                var tiePos = gen.block().terminal(gen.getBlockPos(), gen.getBlockState()).relative(gen.block().terminalFace(gen.getBlockState()));
                l.setBlock(tiePos, BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);
                var tie = (GridTieBlockEntity) l.getBlockEntity(tiePos);
                var otherPos = ROOT.offset(14, 0, 8);
                l.setBlock(otherPos, BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);
                var other = (GridTieBlockEntity) l.getBlockEntity(otherPos);
                h.assertTrue(tie.bindGenerator(gen.getBlockPos()) && other.bindGenerator(gen.getBlockPos()), "both ties bound");
                h.assertTrue(tie.configure(new dev.bwr.core.grid.GridDemand(0, 50, 0, 0, 50, 24000)), "fixed demand");

                // The panel: a synchroscope, a CLOSE, a TRIP and a CLOSE for the second tie, wired through the link path.
                PanelComponent scope = rig.place(ComponentTypes.SYNCHROSCOPE, 0, 7, Footprint.of(6, 8));
                PanelComponent closeBtn = rig.place(ComponentTypes.PUSHBUTTON, 0, 0, Footprint.of(4, 4));
                PanelComponent tripBtn = rig.place(ComponentTypes.PUSHBUTTON, 5, 0, Footprint.of(4, 4));
                PanelComponent closeOther = rig.place(ComponentTypes.PUSHBUTTON, 10, 0, Footprint.of(4, 4));
                h.assertTrue(rig.bindIn(scope, SynchroscopeType.IN_PHASE, tiePos, "phase_degrees", 1.0) == WiringOps.Result.OK, "phase binding");
                h.assertTrue(rig.bindIn(scope, SynchroscopeType.IN_SLIP, tiePos, "slip_hz", 1.0) == WiringOps.Result.OK, "slip binding");
                h.assertTrue(rig.bindOut(closeBtn, 0, tiePos, "close_breaker", 1.0) == WiringOps.Result.OK, "CLOSE binding");
                h.assertTrue(rig.bindOut(tripBtn, 0, tiePos, "trip_breaker", 1.0) == WiringOps.Result.OK, "TRIP binding");
                h.assertTrue(rig.bindOut(closeOther, 0, otherPos, "close_breaker", 1.0) == WiringOps.Result.OK, "second CLOSE binding");
                h.assertTrue(!tie.closed() && !other.closed(), "wiring a button closes nothing");

                int closedAt = -1, tripAt = -1;
                double closeRPM = 0, closePhase = 0, closeSlip = 0, pointer = 0, previousPhase = tie.phase();
                int slowTicks = 0, fastTicks = 0, rateWindows = 0, lockedTicks = 0;
                double windowSum = 0, windowSlip = 0;
                int windowLength = 0;
                boolean windowClean = true;
                long delivered = 0;
                String badClose = null;
                for (int tick = 0; tick < 10000; tick++) {
                    ((ServerLevelData) l.getLevelData()).setGameTime(ControllerSyncSynchroscopeTests.time(time, tick));
                    var tag = supply.saveWithoutMetadata(l.registryAccess());
                    var steam = new CompoundTag();
                    steam.putDouble("Mass", 400);
                    steam.putDouble("Enthalpy", 2490);
                    steam.putDouble("Pressure", 145);
                    tag.put("Exhaust", steam);
                    supply.loadWithComponents(tag, l.registryAccess());
                    condenser.plant().steam.take(condenser.plant().steam.mass());
                    // The operator's hand on the real fractional valve (no governor exists); RPM and phase are never written.
                    double desired = 1500.9, base = tie.closed() ? .012 : 0;
                    valve.setTarget(gen.rpm < desired - 5 ? .09 : gen.rpm < desired ? base + .002 : Math.max(0, base - .002));
                    valve.stroke(.05);
                    PowerTrain.tick(gen);
                    tie.tickDevice();
                    other.tickDevice();
                    if (!tie.closed()) {
                        double slip = tie.slip();
                        double delta = SyncMath.wrap(tie.phase() - previousPhase);
                        double expected = SyncMath.wrap(360.0 * slip * 0.05);
                        // Per tick: the phase turns by 360 degrees a second per hertz of slip, in the direction of the slip.
                        if (Math.abs(360.0 * slip * 0.05) < 90.0) {
                            h.assertTrue(Math.abs(SyncMath.wrap(delta - expected)) < 0.3,
                                    "phase step " + delta + " is not 360 x slip x dt = " + expected + " (slip " + slip + " rpm " + gen.rpm + ")");
                            if (slip > 0.05) {
                                fastTicks++;
                                h.assertTrue(delta > 0, "a fast generator turns the pointer clockwise: " + delta);
                            } else if (slip < -0.05) {
                                slowTicks++;
                                h.assertTrue(delta < 0, "a slow generator turns the pointer anticlockwise: " + delta);
                            }
                        }
                        // The panel samples the tie as the game does (every other tick); the instrument's own arithmetic runs on that.
                        if (tick % 2 == 0) {
                            rig.be().resample(scope, true);
                            h.assertTrue(Math.abs(scope.input(SynchroscopeType.IN_PHASE) - tie.phase()) < 1.0e-9
                                    && Math.abs(scope.input(SynchroscopeType.IN_SLIP) - slip) < 1.0e-9,
                                    "the bound inputs are the tie's phase and slip: " + scope.input(0) + " / " + scope.input(1));
                            pointer = SyncMath.follow(SyncMath.advance(pointer, scope.input(SynchroscopeType.IN_SLIP), 0.1),
                                    scope.input(SynchroscopeType.IN_PHASE), 0.1, 0.12);
                        }
                        // A stretch of 20 ticks at a small slip: one second of pointer travel is 360 x the mean slip.
                        if (Math.abs(slip) < 0.4) {
                            windowSum += delta;
                            windowSlip += slip;
                            windowLength++;
                            if (windowLength == 20) {
                                double expectedTurn = 360.0 * (windowSlip / 20.0);
                                h.assertTrue(Math.abs(windowSum - expectedTurn) < Math.max(1.0, 0.03 * Math.abs(expectedTurn)),
                                        "one second at slip " + windowSlip / 20.0 + " Hz turns the pointer " + windowSum + " (expected " + expectedTurn + ")");
                                rateWindows++;
                                windowSum = windowSlip = 0;
                                windowLength = 0;
                            }
                        } else {
                            windowSum = windowSlip = 0;
                            windowLength = 0;
                        }
                    }
                    previousPhase = tie.phase();
                    if (closedAt < 0 && gen.rpm > 1499 && gen.rpm < 1502 && tie.withinWindow()) {
                        closeRPM = gen.rpm;
                        closePhase = tie.phase();
                        closeSlip = tie.slip();
                        h.assertTrue(Math.abs(SyncMath.wrap(pointer - closePhase)) < 15.0,
                                "the pointer the scope would draw is at the phase when the player closes: " + pointer + " vs " + closePhase);
                        h.assertTrue(Math.abs(pointer) <= 10.0 + 5.0 && Math.abs(closeSlip) <= Synchronizer.SLIP_LIMIT, "pointer at 12 o'clock within the window");
                        // The player presses CLOSE on the panel: held for a moment, then let go.
                        h.assertTrue(rig.op(closeBtn, OperateAction.PRESS, 1.0), "CLOSE pressed");
                        rig.op(closeBtn, OperateAction.RELEASE, 0.0);
                        h.assertTrue(tie.closed() && !tie.lastCloseFault() && gen.damage() == 0, "the panel's CLOSE closed the breaker in phase, no damage");
                        h.assertTrue(!other.closed(), "the other tie is untouched");
                        closedAt = tick;
                        rig.be().resample(scope, true);
                        h.assertTrue(Double.isNaN(scope.input(SynchroscopeType.IN_PHASE)) && Double.isNaN(scope.input(SynchroscopeType.IN_SLIP)),
                                "tied: the tie publishes no phase and no slip, the scope has no signal and parks at SYNC (12 o'clock) with its flag");
                    }
                    if (closedAt >= 0 && tripAt < 0 && tick > closedAt + 80) {
                        delivered = tie.ledger().totalFE();
                        h.assertTrue(delivered > 0 && delivered == gen.delivered().totalFE(), "the generator is tied: it delivered " + delivered + " FE");
                        h.assertTrue(rig.op(tripBtn, OperateAction.PRESS, 1.0), "TRIP pressed");
                        rig.op(tripBtn, OperateAction.RELEASE, 0.0);
                        h.assertTrue(!tie.closed(), "the panel's TRIP opened the breaker");
                        rig.be().resample(scope, true);
                        h.assertTrue(scope.input(SynchroscopeType.IN_PHASE) == 0.0, "reopened: the phase restarts at 0 (the spec's separation approximation)");
                        tripAt = tick;
                        previousPhase = tie.phase();
                    }
                    if (tripAt >= 0 && lockedTicks == 0 && tick > tripAt + 40 && Math.abs(other.phase()) > 45 && gen.rpm > 1499 && gen.rpm < 1502) {
                        lockedTicks = 1;
                        double angle = other.phase();
                        double slip = other.slip();
                        h.assertTrue(rig.op(closeOther, OperateAction.PRESS, 1.0), "second CLOSE pressed");
                        rig.op(closeOther, OperateAction.RELEASE, 0.0);
                        double expectedDamage = Synchronizer.closingDamage(angle, slip);
                        h.assertTrue(other.closed() && other.lastCloseFault() && gen.damage() > 0 && Math.abs(gen.damage() - expectedDamage) < 1.0e-9,
                                "closing out of phase (" + angle + " degrees, slip " + slip + ") is allowed and damages the generator by the game's law: "
                                        + gen.damage() + " vs " + expectedDamage);
                        badClose = "bad close at " + angle + " degrees, slip " + slip + " Hz: damage " + gen.damage() + ", failed=" + gen.failed();
                        break;
                    }
                }
                h.assertTrue(closedAt >= 0 && tripAt >= 0 && badClose != null,
                        "the operator completed sync, trip and bad close; closedAt " + closedAt + " tripAt " + tripAt + " rpm " + gen.rpm + " phase " + tie.phase());
                h.assertTrue(slowTicks > 10 && fastTicks > 10, "both directions were seen: slow " + slowTicks + " fast " + fastTicks);
                h.assertTrue(rateWindows >= 1, "at least one 1 s window of rate checked: " + rateWindows);
                com.mojang.logging.LogUtils.getLogger().info("CONTROLLER-SYNC: closed at {} s, {} rpm, phase {} slip {} Hz, delivered {} FE; slow ticks {} fast ticks {} rate windows {}; {}",
                        closedAt / 20.0, closeRPM, closePhase, closeSlip, delivered, slowTicks, fastTicks, rateWindows, badClose);
                h.succeed();
            } finally {
                for (var p : BlockPos.betweenClosed(lo, hi)) {
                    l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
                ((ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    private static long time(long start, int tick) {
        return start + tick + 1;
    }
}
