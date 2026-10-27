package dev.bwr.mod.devtest;

import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.eccs.AssemblyPlumbing;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.gui.CavityInfo;
import dev.bwr.mod.reactor.CavityDrainFlow;
import dev.bwr.mod.reactor.CavityFillPortBlock;
import dev.bwr.mod.reactor.CavityFillPortBlockEntity;
import dev.bwr.mod.reactor.CavityFillPortBlockEntity.State;
import dev.bwr.mod.reactor.CavitySeal;
import dev.bwr.mod.reactor.FloodReadiness;
import dev.bwr.mod.reactor.FloodReadiness.Mark;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * Flooding the reactor well (REFUELLING-BRIDGE.md, "Flooding the well"): the flood checklist naming each reason a well does not flood, the relaxed well
 * survey (a well wider than the vessel, a shelf beside it), and the Cavity Fill Port filling the well by gravity from a higher Condensate Storage Tank
 * through a Motor-Operated Water Valve with the mass exact, or taking a pump's discharge. Real blocks: a formed 5 x 5 x 8 reactor and stone walls
 * ({@link CavitySealTests.Site}), stepped by the reactor's own tick. No CC types (those are in {@link CavityFloodCcChecks}). Own fixture origins.
 */
@GameTestHolder("bwrcavity")
@PrefixGameTestTemplate(false)
public final class CavityFloodTests {
    private CavityFloodTests() {
    }

    private static BlockPos origin(int i) {
        return new BlockPos(94_000 + 120 * i, 190, 95_600);
    }

    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String what) {
        h.assertTrue(Math.abs(actual - expected) <= tolerance, what + ": expected " + expected + " +-" + tolerance + " got " + actual);
    }

    private static FloodReadiness.Item item(List<FloodReadiness.Item> items, String id) {
        for (var i : items) {
            if (i.id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("no checklist line " + id + " in " + items);
    }

    private static String all(List<FloodReadiness.Item> items) {
        StringBuilder b = new StringBuilder();
        for (var i : items) {
            b.append(i.id()).append(' ').append(i.mark()).append(": ").append(i.english()).append(" | ");
        }
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------------------- the checklist

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void theChecklistSaysWhyTheWellDoesNotFloodForEachCause(GameTestHelper h) throws Exception {
        var s = new CavitySealTests.Site(h.getLevel(), origin(0), 5, 5, 8);
        try {
            s.survey();
            // the head on, every stud at load
            var items = FloodReadiness.of(s.be);
            h.assertTrue(item(items, "head").mark() == Mark.FAIL && item(items, "head").english().contains("head is on"), "head: " + all(items));
            h.assertTrue(item(items, "studs").mark() == Mark.FAIL && item(items, "studs").english().contains("still at load"), "studs: " + all(items));
            h.assertTrue(!FloodReadiness.canFlood(items), "it cannot flood");
            // the head off, no wall at all: no seal, and it says which side and how far
            s.be.setVesselState(VesselState.REFUELING);
            items = FloodReadiness.of(s.be);
            h.assertTrue(item(items, "head").mark() == Mark.PASS && item(items, "studs").mark() == Mark.PASS, "head and studs pass: " + all(items));
            var seal = item(items, "seal");
            h.assertTrue(seal.mark() == Mark.FAIL && seal.english().contains("No cavity seal") && seal.english().contains("blocks from the vessel at x")
                    && seal.english().contains("drywell"), "no seal: " + seal.english());
            h.assertTrue(item(items, "well").mark() == Mark.FAIL, "and no well");
            // one side two blocks off: that side named, two blocks
            s.ring(1, 1, 3, 1, 3, 16);
            s.survey();
            seal = item(FloodReadiness.of(s.be), "seal");
            h.assertTrue(seal.mark() == Mark.FAIL && seal.english().contains("west side the wall is 2 blocks"), "the west side 2 blocks off: " + seal.english());
            s.clear();
            s = new CavitySealTests.Site(h.getLevel(), origin(0), 5, 5, 8);
            s.be.setVesselState(VesselState.REFUELING);
            // a seal under the flange, the wall open above it: the well does not reach the flange, and it says the row and the block
            s.ring(1, 3, 7);
            s.survey();
            items = FloodReadiness.of(s.be);
            var well = item(items, "well");
            h.assertTrue(item(items, "seal").mark() == Mark.PASS && well.mark() == Mark.FAIL && well.english().contains("does not reach the flange")
                    && well.english().contains("at y " + (s.bottom() + 8)) && well.english().contains("wall is open"), "the well opens at the flange row: " + well.english());
            h.assertTrue(s.seal().wellEnd() != null && s.seal().wellEnd().kind() == CavitySeal.WellEnd.Kind.LEAK && s.seal().wellEnd().row() == s.bottom() + 8,
                    "the survey says where: " + s.seal().wellEnd());
            // the shaft closed up: seal and well pass; nothing goes in, and the level is under the flange
            s.ring(1, 8, 16);
            s.survey();
            items = FloodReadiness.of(s.be);
            h.assertTrue(item(items, "seal").mark() == Mark.PASS && item(items, "well").mark() == Mark.PASS && FloodReadiness.canFlood(items), "it can flood: " + all(items));
            h.assertTrue(item(items, "well").english().contains("up to y " + (s.bottom() + 17)), "the well's top: " + item(items, "well").english());
            h.assertTrue(item(items, "inflow").mark() == Mark.FAIL && item(items, "inflow").english().contains("Nothing is putting water in")
                    && item(items, "inflow").english().contains("Cavity Fill Port"), "nothing goes in: " + item(items, "inflow").english());
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 10.0);
            items = FloodReadiness.of(s.be);
            h.assertTrue(item(items, "level").mark() == Mark.FAIL && item(items, "level").english().contains("below the flange"), "under the flange: " + item(items, "level").english());
            // feedwater going in: it says so, and how long to the flange
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 1.0);
            s.be.core().setFeedwaterFlowKgPerS(1200.0);
            s.tick();
            items = FloodReadiness.of(s.be);
            h.assertTrue(item(items, "inflow").mark() == Mark.PASS && item(items, "inflow").english().contains("feedwater 1,200"), "feedwater: " + item(items, "inflow").english());
            h.assertTrue(item(items, "level").english().contains("reaches the flange in"), "the time to the flange: " + item(items, "level").english());
            for (int i = 0; i < 400; i++) {
                s.tick();
            }
            items = FloodReadiness.of(s.be);
            h.assertTrue(item(items, "level").mark() == Mark.PASS && item(items, "level").english().contains("over the flange"), "flooded: " + item(items, "level").english());
            h.assertTrue(item(items, "outflow").mark() != Mark.FAIL && !item(items, "outflow").english().contains("drywell")
                    && !item(items, "outflow").english().contains("refuel floor"), "nothing is lost from a sealed well with room: " + item(items, "outflow").english());
            h.assertTrue(item(items, "depth").english().contains("over the top of the fuel"), "the depth: " + item(items, "depth").english());
            // the screen carries the same lines
            var info = CavityInfo.capture(s.be);
            h.assertTrue(info.flood().size() == items.size() && info.flood().get(0).markEnum() == Mark.PASS, "the INFO tab's checklist: " + info.flood().size());
            s.be.core().setFeedwaterFlowKgPerS(0.0);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void theChecklistShowsWaterLeavingIntoTheDrywellAndOverTheTop(GameTestHelper h) throws Exception {
        var s = new CavitySealTests.Site(h.getLevel(), origin(1), 5, 5, 8);
        try {
            // no seal: the water the feed brings over the flange goes into the drywell
            s.survey();
            s.be.setVesselState(VesselState.REFUELING);
            var vessel = s.be.core().getPressureVessel();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN - 0.2);
            s.be.core().setFeedwaterFlowKgPerS(1200.0);
            for (int i = 0; i < 200; i++) {
                s.tick();
            }
            var out = item(FloodReadiness.of(s.be), "outflow");
            h.assertTrue(out.mark() == Mark.FAIL && out.english().contains("into the drywell") && out.english().contains("no usable well"), "into the drywell: " + out.english());
            // a short well, full: over the top onto the refuel floor
            s.ring(1, 3, 10);
            s.survey();
            h.assertTrue(s.seal().wellUsable(), "a short well: " + s.seal().english());
            double capacity = s.seal().profile().toWell().capacityM3();
            vessel.setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN);
            vessel.restoreLiquidMassKg(vessel.getLiquidMassKg() + capacity * 1000.0);
            for (int i = 0; i < 100; i++) {
                s.tick();
            }
            out = item(FloodReadiness.of(s.be), "outflow");
            h.assertTrue(out.mark() == Mark.FAIL && out.english().contains("onto the refuel floor") && out.english().contains("the well is full"), "over the top: " + out.english());
            s.be.core().setFeedwaterFlowKgPerS(0.0);
        } finally {
            s.clear();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the well survey

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aWellWiderThanTheVesselAndAShelfBesideItStillHoldWater(GameTestHelper h) throws Exception {
        var s = new CavitySealTests.Site(h.getLevel(), origin(2), 5, 5, 8);
        try {
            // the seal at the flange; from the flange down to the seal row a solid floor out to five blocks; above it the well's wall stands five blocks off
            for (int off = 1; off <= 5; off++) {
                s.ring(off, 3, 8);
            }
            s.ring(5, 9, 16);
            s.survey();
            var seal = s.seal();
            h.assertTrue(seal.present() && seal.sealRow() == seal.flangeRow() && seal.wellUsable() && seal.wellTopRow() == s.bottom() + 16,
                    "a well four blocks wider than the vessel holds water to the top of its wall (it ended at the flange when the survey stopped at three): "
                            + seal.english() + " top " + (seal.wellTopRow() - s.bottom()));
            double wide = seal.profile().toWell().capacityM3();
            h.assertTrue(wide > 7 * 15 * 15, "and its capacity is the wide shaft's: " + wide);
            // a shelf one block lower than the floor on the east side, out to seven blocks: part of the well's top layer
            s.clear();
            s = new CavitySealTests.Site(h.getLevel(), origin(2), 5, 5, 8);
            s.ring(1, 3, 16);
            var env = s.env();
            int top = s.bottom() + 16;
            for (int x = env.max().getX() + 1; x <= env.max().getX() + 7; x++) {
                for (int z = env.min().getZ() - 1; z <= env.max().getZ() + 1; z++) {
                    s.level.setBlock(new BlockPos(x, top - 1, z), Blocks.STONE.defaultBlockState(), 2);
                    s.level.setBlock(new BlockPos(x, top, z), Blocks.AIR.defaultBlockState(), 2);
                }
                s.level.setBlock(new BlockPos(x, top, env.min().getZ() - 2), Blocks.STONE.defaultBlockState(), 2);
                s.level.setBlock(new BlockPos(x, top, env.max().getZ() + 2), Blocks.STONE.defaultBlockState(), 2);
            }
            for (int z = env.min().getZ() - 2; z <= env.max().getZ() + 2; z++) {
                s.level.setBlock(new BlockPos(env.max().getX() + 8, top, z), Blocks.STONE.defaultBlockState(), 2);
            }
            s.survey();
            h.assertTrue(s.seal().wellUsable() && s.seal().wellTopRow() == top,
                    "the shelf beside the well is part of its top layer, the well is not cut a row short: top " + (s.seal().wellTopRow() - s.bottom()));
            // and an open refuel floor still ends a well where it should
            h.assertTrue(s.seal().wellEnd() != null && s.seal().wellEnd().kind() == CavitySeal.WellEnd.Kind.LEAK && s.seal().wellEnd().row() == top + 1,
                    "the open floor above ends it: " + s.seal().wellEnd());
        } finally {
            s.clear();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the fill port

    /** The well, a fill port in its west wall, the line out through a valve and up to a tank {@code rise} blocks over the port. */
    static final class Rig {
        final ServerLevel l;
        final CavitySealTests.Site s;
        final BlockPos port;
        final BlockPos valvePos;
        final BlockPos tankPos;
        final int sillRow;
        final MotorOperatedWaterValveBlockEntity valve;
        final CondensateStorageTankBlockEntity tank;
        final CavityFillPortBlockEntity portBe;

        Rig(ServerLevel l, BlockPos o, int rise) throws Exception {
            this.l = l;
            this.s = new CavitySealTests.Site(l, o, 5, 5, 8);
            s.ring(1, 3, 16);
            s.survey();
            if (s.seal() == null || !s.seal().present() || !s.seal().wellUsable()) {
                throw new IllegalStateException("the well is usable: " + (s.seal() == null ? "no survey" : s.seal().english()));
            }
            s.be.setVesselState(VesselState.REFUELING);
            var env = s.env();
            int x0 = env.min().getX();
            int zc = (env.min().getZ() + env.max().getZ()) / 2;
            sillRow = s.bottom() + 10;
            port = new BlockPos(x0 - 1, sillRow, zc);
            l.setBlock(port, BwrBlocks.CAVITY_FILL_PORT.get().defaultBlockState().setValue(CavityFillPortBlock.FACING, Direction.WEST), 3);
            portBe = (CavityFillPortBlockEntity) l.getBlockEntity(port);
            pipe(port.west());
            valvePos = port.west(2);
            valve = WaterValveTests.valveAt(l, valvePos, Direction.WEST);
            pipe(port.west(3));
            for (int dy = 1; dy < rise; dy++) {
                pipe(port.west(3).above(dy));
            }
            tankPos = port.west(3).above(rise);
            l.setBlock(tankPos, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
            tank = (CondensateStorageTankBlockEntity) l.getBlockEntity(tankPos);
        }

        void pipe(BlockPos p) {
            l.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        }

        PressureVessel vessel() {
            return s.be.core().getPressureVessel();
        }

        /** Water in the well with its surface {@code depth} metres over the port's sill (negative: under it, in the well over the flange). */
        void setWater(double depth) {
            double lip = VesselWaterGeometry.rim(s.env());
            vessel().setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + (sillRow - s.bottom() + depth - lip) / PressureVessel.METRES_PER_INCH);
        }

        void openValve() {
            WaterValveTests.power(valve, MotorOperatedWaterValveBlockEntity.ENERGY_CAPACITY_FE);
            valve.setTarget(1.0);
            WaterValveTests.pass(l, valve, 400);
        }

        void tick(int n) {
            for (int i = 0; i < n; i++) {
                s.tick();
            }
        }

        void finish() {
            s.clear();
        }
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aFillPortFloodsTheWellFromAHigherTankThroughAValveWithTheMassExact(GameTestHelper h) throws Exception {
        var r = new Rig(h.getLevel(), origin(3), 9);
        try {
            r.tank.fillKg(400_000);
            r.setWater(0.5);
            r.tick(2);
            // the valve shut: nothing moves
            h.assertTrue(r.portBe.state() == State.VALVE_CLOSED, "a shut valve: " + r.portBe.state());
            double mass = r.vessel().getLiquidMassKg();
            double cst = r.tank.availableWaterKg();
            r.tick(40);
            near(h, r.tank.availableWaterKg(), cst, 1e-9, "a shut valve passes nothing");
            near(h, r.vessel().getLiquidMassKg(), mass, 0.5, "and the vessel gets nothing");
            // open: the tank fills the well by gravity, by the drain port's law
            r.openValve();
            r.tick(1);
            h.assertTrue(r.portBe.state() == State.FILLING, "filling: " + r.portBe.state() + " " + r.portBe.statusLines());
            double head = r.tankPos.getY() + 0.5 - (r.s.bottom() + r.s.be.wellSurfaceLocal());
            near(h, r.portBe.headM(), head, 0.02, "the head is the tank's flange over the water at the port");
            near(h, r.portBe.flowKgPerS(), CavityDrainFlow.kgPerS(head, 1.0), 20.0, "the flow follows the law");
            mass = r.vessel().getLiquidMassKg();
            cst = r.tank.availableWaterKg();
            double total0 = r.portBe.totalKg();
            double level0 = r.s.be.wellSurfaceLocal();
            r.tick(400);
            double moved = r.portBe.totalKg() - total0;
            h.assertTrue(moved > 10_000, "it moved water: " + moved);
            near(h, cst - r.tank.availableWaterKg(), moved, 1e-6, "what left the tank is what the port passed");
            near(h, r.vessel().getLiquidMassKg() - mass, moved, 2.0, "and what the vessel gained");
            h.assertTrue(r.s.be.wellSurfaceLocal() > level0, "the well's surface rose: " + level0 + " to " + r.s.be.wellSurfaceLocal());
            // the checklist names it
            var inflow = item(FloodReadiness.of(r.s.be), "inflow");
            h.assertTrue(inflow.mark() == Mark.PASS && inflow.english().contains("fill ports by gravity"), "the inflow line: " + inflow.english());
            var info = CavityInfo.capture(r.s.be);
            h.assertTrue(info.fills().size() == 1 && info.fillFlowKgPerS() > 100, "the INFO tab lists the port: " + info.fills());
            // the water at the tank's height: no head, no flow
            r.setWater(r.tankPos.getY() + 0.6 - r.sillRow);
            r.tick(2);
            h.assertTrue(r.portBe.state() == State.NO_HEAD && r.portBe.flowKgPerS() == 0.0, "no head above the tank: " + r.portBe.state());
            // the head on: nothing goes into the well
            r.setWater(0.5);
            r.s.be.setVesselState(VesselState.SHUTDOWN);
            r.tick(2);
            h.assertTrue(r.portBe.state() == State.HEAD_ON && r.portBe.pumpReactor() == null, "the head on: " + r.portBe.state());
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aFillPortTakesAPumpsDischargeIntoTheWellAndRefusesOutsideIt(GameTestHelper h) throws Exception {
        var r = new Rig(h.getLevel(), origin(4), 9);
        try {
            // no tank on the line: ready for a pump, and a pump's discharge line ending at it delivers into this reactor
            r.l.setBlock(r.tankPos, Blocks.AIR.defaultBlockState(), 3);
            r.tick(2);
            h.assertTrue(r.portBe.state() == State.PUMP_LINE, "ready for a pump: " + r.portBe.state());
            h.assertTrue(r.portBe.pumpReactor() == r.s.be, "a pump at it delivers to this reactor");
            var line = new AssemblyPlumbing.Line(List.of(r.port), Set.of(), true, 1.0);
            h.assertTrue(AssemblyPlumbing.waterReceiver(r.l, line) == r.s.be, "the pumps' plumbing finds the reactor through the fill port");
            // the head on: the pump has nowhere to put the water
            r.s.be.setVesselState(VesselState.SHUTDOWN);
            r.tick(2);
            h.assertTrue(r.portBe.pumpReactor() == null && AssemblyPlumbing.waterReceiver(r.l, line) == null, "head on: no receiver");
            r.s.be.setVesselState(VesselState.REFUELING);
            // turned into the well (its wet face outside): not in the well
            r.l.setBlock(r.port, BwrBlocks.CAVITY_FILL_PORT.get().defaultBlockState().setValue(CavityFillPortBlock.FACING, Direction.EAST), 3);
            r.tick(2);
            var turned = (CavityFillPortBlockEntity) r.l.getBlockEntity(r.port);
            h.assertTrue(turned.state() == State.NOT_IN_WELL && turned.pumpReactor() == null, "a backward port: " + turned.state());
            // below the seal row: refused
            BlockPos low = new BlockPos(r.port.getX(), r.s.seal().sealRow() - 1, r.port.getZ());
            r.l.setBlock(r.port, Blocks.STONE.defaultBlockState(), 3);
            r.l.setBlock(low, BwrBlocks.CAVITY_FILL_PORT.get().defaultBlockState().setValue(CavityFillPortBlock.FACING, Direction.WEST), 3);
            r.tick(2);
            var lowBe = (CavityFillPortBlockEntity) r.l.getBlockEntity(low);
            h.assertTrue(lowBe.state() != State.PUMP_LINE && lowBe.pumpReactor() == null, "a port below the seal (or outside the well): " + lowBe.state());
            if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                CavityFloodCcChecks.verifyFlood(h, r.s.be);
            }
        } finally {
            r.finish();
        }
        h.succeed();
    }
}
