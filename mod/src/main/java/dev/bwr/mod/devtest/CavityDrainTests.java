package dev.bwr.mod.devtest;

import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.gui.CavityInfo;
import dev.bwr.mod.reactor.CavityDrainFlow;
import dev.bwr.mod.reactor.CavityDrainPortBlock;
import dev.bwr.mod.reactor.CavityDrainPortBlockEntity;
import dev.bwr.mod.reactor.CavityDrainPortBlockEntity.State;
import dev.bwr.mod.reactor.CavityDrains;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Cavity Drain Port, through real blocks: a formed reactor with a stone well, a drain port in the well wall, High-Pressure Water
 * Pipe, a Motor-Operated Water Valve, and a Condensate Storage Tank below. Water is set in the well at a chosen depth over the port's
 * sill and the reactor's own tick does the rest, so every reading is what the game would do. No CC types (those are in
 * {@link WaterValveCcChecks}). Own fixture origins.
 */
@GameTestHolder("bwrcavity")
@PrefixGameTestTemplate(false)
public final class CavityDrainTests {
    private CavityDrainTests() {
    }

    private static final int Z = 94_400;

    /** The well, the port in its west wall, the line out through a valve to a tank {@code drop} blocks below the port. */
    private static final class Rig {
        final GameTestHelper h;
        final ServerLevel l;
        final CavitySealTests.Site s;
        final BlockPos port;
        final BlockPos valvePos;
        final BlockPos tankPos;
        final int sillRow;
        MotorOperatedWaterValveBlockEntity valve;
        CondensateStorageTankBlockEntity tank;
        CavityDrainPortBlockEntity portBe;

        Rig(GameTestHelper h, int index, int drop) throws Exception {
            this.h = h;
            this.l = h.getLevel();
            this.s = new CavitySealTests.Site(l, new BlockPos(94_000 + 120 * index, 190, Z), 5, 5, 8);
            s.ring(1, 3, 16);
            s.survey();
            h.assertTrue(s.seal() != null && s.seal().present() && s.seal().wellUsable(), "the well is usable: " + (s.seal() == null ? "no survey" : s.seal().english()));
            s.be.setVesselState(VesselState.REFUELING);
            var env = s.env();
            int x0 = env.min().getX();
            int zc = (env.min().getZ() + env.max().getZ()) / 2;
            int y = s.bottom() + 10;
            sillRow = y;
            port = new BlockPos(x0 - 1, y, zc);
            l.setBlock(port, BwrBlocks.CAVITY_DRAIN_PORT.get().defaultBlockState().setValue(CavityDrainPortBlock.FACING, Direction.WEST), 3);
            portBe = (CavityDrainPortBlockEntity) l.getBlockEntity(port);
            pipe(port.west());
            valvePos = port.west(2);
            valve = WaterValveTests.valveAt(l, valvePos, Direction.WEST);
            pipe(port.west(3));
            for (int dy = 1; dy < drop; dy++) {
                pipe(port.west(3).below(dy));
            }
            tankPos = port.west(3).below(drop);
            l.setBlock(tankPos, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
            tank = (CondensateStorageTankBlockEntity) l.getBlockEntity(tankPos);
        }

        void pipe(BlockPos p) {
            l.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
        }

        PressureVessel vessel() {
            return s.be.core().getPressureVessel();
        }

        double sillLocal() {
            return sillRow - s.bottom();
        }

        /** Water in the well with its surface {@code depth} metres over the port's sill. */
        void setWater(double depth) {
            double lip = VesselWaterGeometry.rim(s.env());
            vessel().setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + (sillLocal() + depth - lip) / PressureVessel.METRES_PER_INCH);
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

        /** Ticks until the port says {@code state} (at most {@code max} ticks); the ticks it took. */
        int until(State state, int max) {
            int n = 0;
            while (portBe.state() != state && n < max) {
                s.tick();
                n++;
            }
            return n;
        }

        double cst() {
            return tank.storedKg();
        }

        void finish() {
            s.clear();
        }
    }

    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String what) {
        h.assertTrue(Math.abs(actual - expected) <= tolerance, what + ": expected " + expected + " +-" + tolerance + " got " + actual);
    }

    // ---------------------------------------------------------------------------------------------------- the law

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 100)
    public static void theFlowLawIsAGravityOrificeTimesTheOpening(GameTestHelper h) {
        near(h, CavityDrainFlow.openKgPerS(1.0), 0.6 * 0.2 * 1000.0 * Math.sqrt(2 * 9.81), 1.0e-9, "1 m of head");
        near(h, CavityDrainFlow.openKgPerS(4.0), 2.0 * CavityDrainFlow.openKgPerS(1.0), 1.0e-9, "four times the head, twice the flow");
        h.assertTrue(CavityDrainFlow.openKgPerS(100.0) == CavityDrainFlow.LINE_LIMIT_KG_PER_S, "the 0.2 m2 bore passes at most 1,500 kg/s");
        h.assertTrue(CavityDrainFlow.openKgPerS(0.0) == 0.0 && CavityDrainFlow.openKgPerS(-3.0) == 0.0 && CavityDrainFlow.openKgPerS(Double.NaN) == 0.0, "no head, no flow");
        near(h, CavityDrainFlow.kgPerS(2.0, 0.5), 0.5 * CavityDrainFlow.openKgPerS(2.0), 1.0e-9, "half open is half the flow");
        h.assertTrue(CavityDrainFlow.kgPerS(2.0, 0.0) == 0.0 && CavityDrainFlow.kgPerS(2.0, Double.NaN) == 0.0, "closed passes nothing");
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- draining

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aFloodedWellDrainsToTheTankThroughPortPipeAndValveAndStopsAtThePortHeight(GameTestHelper h) throws Exception {
        var r = new Rig(h, 20, 1);
        try {
            r.openValve();
            r.setWater(0.5);
            var vessel = r.vessel();
            var profile = r.s.seal().profile();
            double sill = Math.max(r.sillLocal(), profile.plateTop());
            double keep = profile.waterUpTo(sill);
            double water0 = vessel.getWellWaterM3();
            double mass0 = vessel.getLiquidMassKg();
            double density = mass0 / (vessel.volumeAtFlangeM3() + water0);
            double expectedKg = (water0 - keep) * density;
            h.assertTrue(expectedKg > 20_000 && expectedKg < 30_000, "the fixture holds about 0.5 m x 49 m2 over the sill: " + expectedKg);
            h.assertTrue(r.cst() == 0.0, "the tank starts empty");
            near(h, r.s.be.wellSurfaceLocal(), r.sillLocal() + 0.5, 0.01, "the surface stands 0.5 m over the sill");
            r.tick(5);
            h.assertTrue(r.portBe.state() == State.DRAINING && r.portBe.flowKgPerS() > 100, "draining: " + r.portBe.state() + " " + r.portBe.flowKgPerS() + " kg/s");
            h.assertTrue(r.portBe.lineOpening() == 1.0 && r.portBe.valveCount() == 1 && r.portBe.receiverPos().equals(r.tankPos), "the line is read: " + r.portBe.lineOpening());
            h.assertTrue(r.cst() > 0, "water arrived in the tank");
            int ticks = 5 + r.until(State.AT_SILL, 3500);
            h.assertTrue(r.portBe.state() == State.AT_SILL, "the drain reached its sill, not still " + r.portBe.state() + " after " + ticks + " ticks, surface " + r.s.be.wellSurfaceLocal());
            double massLost = mass0 - vessel.getLiquidMassKg();
            near(h, r.cst(), massLost, 2.0, "the tank holds exactly what the vessel lost");
            near(h, r.cst(), expectedKg, 0.02 * expectedKg, "and that is the water that stood over the sill");
            near(h, r.s.be.wellSurfaceLocal(), r.sillLocal(), 0.01, "the level stands at the port's height");
            r.tick(100);
            near(h, r.cst(), massLost, 2.0, "nothing more leaves at the sill");
            h.assertTrue(r.portBe.flowKgPerS() == 0.0 && r.portBe.state() == State.AT_SILL, "idle at the sill");
            h.assertTrue(vessel.getWellWaterM3() > 0.0, "the water below the sill stays in the well (and the vessel is untouched)");
            // the reactor's ticks here are synchronous (the clock does not move), so let two game ticks pass: a meter that reads
            // the latest tick reads nothing once the drain has stopped
            var data = (net.minecraft.world.level.storage.ServerLevelData) r.l.getLevelData();
            long now = r.l.getGameTime();
            data.setGameTime(now + 2);
            try {
                near(h, r.valve.flowKgPerS(), 0.0, 1.0e-9, "no flow metered through the valve at the sill");
            } finally {
                data.setGameTime(now);
            }
            // the INFO tab's and the computers' data
            r.setWater(0.3);
            r.tick(10);
            var info = CavityInfo.capture(r.s.be);
            h.assertTrue(info.known() && info.drains().size() == 1, "the INFO tab lists the port: " + info.drains());
            var d = info.drains().get(0);
            h.assertTrue(d.state() == State.DRAINING.ordinal() && d.flowKgPerS() > 100 && d.depthM() > 0.2 && d.opening() == 1.0 && d.valves() == 1
                    && d.x() == r.port.getX() && d.y() == r.port.getY() && d.z() == r.port.getZ(), "the port's line state is on the INFO tab: " + d);
            h.assertTrue(info.drainFlowKgPerS() == d.flowKgPerS() && d.stateText().getString().toLowerCase().contains("draining"), "total and text");
            h.assertTrue(r.valve.flowKgPerS() > 100, "the valve meters the water that passes it: " + r.valve.flowKgPerS());
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 3000)
    public static void theValveThrottlesTheDrainInProportionAndTheFlowFollowsTheLaw(GameTestHelper h) throws Exception {
        var r = new Rig(h, 21, 1);
        try {
            r.openValve();
            r.setWater(0.8);
            double c0 = r.cst();
            double predicted = 0;
            for (int i = 0; i < 40; i++) {
                r.s.tick();
                predicted += CavityDrainFlow.kgPerS(r.portBe.headM(), r.portBe.lineOpening()) * 0.05;
            }
            double full = r.cst() - c0;
            h.assertTrue(full > 800, "a second of full flow moves a lot: " + full);
            near(h, full, predicted, 2.0, "the water moved is the sum of Cd A rho sqrt(2 g H) over the ticks");
            // half open, from the same level
            r.valve.setTarget(0.5);
            WaterValveTests.power(r.valve, 20_000);
            WaterValveTests.pass(r.l, r.valve, 400);
            near(h, r.valve.position(), 0.5, 1.0e-9, "the valve is half open");
            r.tick(1);
            near(h, r.portBe.lineOpening(), 0.5, 1.0e-9, "the line opening is the valve's");
            r.setWater(0.8);
            c0 = r.cst();
            predicted = 0;
            for (int i = 0; i < 40; i++) {
                r.s.tick();
                predicted += CavityDrainFlow.kgPerS(r.portBe.headM(), r.portBe.lineOpening()) * 0.05;
            }
            double half = r.cst() - c0;
            near(h, half / full, 0.5, 0.03, "half open passes half: " + half + " against " + full);
            near(h, half, predicted, 2.0, "and still follows the law");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 3000)
    public static void aFullTankStopsTheFlowAndRoomStartsItAgain(GameTestHelper h) throws Exception {
        var r = new Rig(h, 22, 1);
        try {
            r.openValve();
            r.setWater(0.5);
            var tank = r.tank.tank();
            tank.fill(ThermalWater.atTemperature(tank.getCapacity(), 30.0), IFluidHandler.FluidAction.EXECUTE);
            h.assertTrue(tank.getFluidAmount() == tank.getCapacity(), "the tank is full");
            double mass = r.vessel().getLiquidMassKg();
            r.tick(40);
            h.assertTrue(r.portBe.state() == State.RECEIVER_FULL && r.portBe.flowKgPerS() == 0.0, "a full tank: " + r.portBe.state());
            near(h, r.vessel().getLiquidMassKg(), mass, 0.01, "nothing left the vessel");
            tank.drain(50_000, IFluidHandler.FluidAction.EXECUTE);
            r.tick(40);
            h.assertTrue(r.portBe.state() == State.DRAINING && r.portBe.flowKgPerS() > 100, "room again, flowing: " + r.portBe.state());
            h.assertTrue(tank.getFluidAmount() > tank.getCapacity() - 50_000 + 500, "the tank took water again");
            near(h, mass - r.vessel().getLiquidMassKg(), tank.getFluidAmount() - (tank.getCapacity() - 50_000), 2.0, "what it took is what the vessel lost");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 3000)
    public static void aClosedValveNoValveNoTankOrABackwardPortMeansNoFlow(GameTestHelper h) throws Exception {
        var r = new Rig(h, 23, 1);
        try {
            r.setWater(0.5);
            double mass = r.vessel().getLiquidMassKg();
            r.tick(40);
            h.assertTrue(r.portBe.state() == State.VALVE_CLOSED && r.portBe.flowKgPerS() == 0.0 && r.cst() == 0.0, "the valve is closed: " + r.portBe.state());
            near(h, r.vessel().getLiquidMassKg(), mass, 0.01, "a closed valve: nothing leaves the vessel");
            // opened only so far as it has stroked: half power, half open, half the stroke
            WaterValveTests.power(r.valve, 4_000);
            r.valve.setTarget(1.0);
            WaterValveTests.pass(r.l, r.valve, 400);
            near(h, r.valve.position(), 0.2, 1.0e-9, "80 ticks of drive");
            r.tick(20);
            h.assertTrue(r.portBe.state() == State.DRAINING && r.portBe.lineOpening() == r.valve.position(), "an open valve drains: " + r.portBe.state());
            // no valve: a pipe in its place
            r.l.removeBlock(r.valvePos, false);
            r.pipe(r.valvePos);
            double before = r.cst();
            r.tick(40);
            h.assertTrue(r.portBe.state() == State.NO_VALVE && r.cst() == before, "no motor-operated valve on the line, no flow: " + r.portBe.state());
            // no tank
            r.l.removeBlock(r.tankPos, false);
            r.tick(5);
            h.assertTrue(r.portBe.state() == State.NO_RECEIVER && r.portBe.flowKgPerS() == 0.0, "the line reaches no tank: " + r.portBe.state());
            // the flange pointing into the well: the wet face is outside it
            r.portBe.getLevel().setBlock(r.port, r.l.getBlockState(r.port).setValue(CavityDrainPortBlock.FACING, Direction.EAST), 3);
            r.tick(5);
            h.assertTrue(r.portBe.state() == State.NOT_IN_WELL, "a port the wrong way round: " + r.portBe.state());
            // a ceiling drain cannot work
            r.l.setBlock(r.port, r.l.getBlockState(r.port).setValue(CavityDrainPortBlock.FACING, Direction.UP), 3);
            r.tick(5);
            h.assertTrue(r.portBe.state() == State.NOT_IN_WELL, "a ceiling port: " + r.portBe.state());
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 3000)
    public static void aPortBelowTheSealRowDoesNotDrain(GameTestHelper h) throws Exception {
        var r = new Rig(h, 24, 1);
        try {
            r.openValve();
            // move the port, pipe and valve down five rows, to the ring below the seal (row bottom + 5)
            var low = r.port.below(5);
            r.l.setBlock(low, BwrBlocks.CAVITY_DRAIN_PORT.get().defaultBlockState().setValue(CavityDrainPortBlock.FACING, Direction.WEST), 3);
            r.l.setBlock(r.port, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            var lowBe = (CavityDrainPortBlockEntity) r.l.getBlockEntity(low);
            for (int i = 1; i <= 3; i++) {
                r.l.setBlock(low.west(i), BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(), 3);
            }
            r.l.setBlock(low.west(2), r.l.getBlockState(r.valvePos), 3);
            r.l.setBlock(low.west(4), BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
            var lowValve = (MotorOperatedWaterValveBlockEntity) r.l.getBlockEntity(low.west(2));
            WaterValveTests.power(lowValve, 20_000);
            lowValve.setTarget(1.0);
            WaterValveTests.pass(r.l, lowValve, 400);
            h.assertTrue(lowValve.position() == 1.0, "the low line's valve is open");
            r.setWater(0.5);
            double mass = r.vessel().getLiquidMassKg();
            r.tick(40);
            h.assertTrue(lowBe.state() != State.DRAINING && lowBe.flowKgPerS() == 0.0, "below the seal the drain does nothing: " + lowBe.state());
            near(h, r.vessel().getLiquidMassKg(), mass, 0.01, "nothing left the vessel by the low port");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 3000)
    public static void aComputerReadsTheDrainFromTheReactorAndThePort(GameTestHelper h) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            h.succeed();
            return;
        }
        var r = new Rig(h, 26, 1);
        try {
            r.openValve();
            r.setWater(0.8);
            r.tick(30);
            WaterValveCcChecks.verifyCavity(h, r.s.be, r.portBe);
        } finally {
            r.finish();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- save and reload

    private static void validate(ReactorControllerBlockEntity be) throws Exception {
        var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
        method.setAccessible(true);
        method.invoke(be, be.getLevel());
        be.finishCavitySurvey();
    }

    /** Takes a block entity out and puts back one loaded from its own saved data, as a chunk save and load does. */
    private static BlockEntity reloaded(ServerLevel l, BlockEntity be) {
        BlockPos pos = be.getBlockPos();
        CompoundTag saved = be.saveWithFullMetadata(l.registryAccess());
        l.removeBlockEntity(pos);
        BlockEntity next = BlockEntity.loadStatic(pos, l.getBlockState(pos), saved, l.registryAccess());
        l.setBlockEntity(next);
        return next;
    }

    @GameTest(template = "empty", templateNamespace = "bwrcavity", timeoutTicks = 4000)
    public static void aDrainSurvivesASaveAndReloadOfTheReactorTheValveAndTheTank(GameTestHelper h) throws Exception {
        var r = new Rig(h, 25, 1);
        try {
            r.openValve();
            r.setWater(0.8);
            r.tick(100);
            h.assertTrue(r.portBe.state() == State.DRAINING, "draining before the save: " + r.portBe.state());
            double cstBefore = r.cst();
            double positionBefore = r.valve.position();
            int energyBefore = r.valve.energy().getEnergyStored();
            r.s.be = (ReactorControllerBlockEntity) reloaded(r.l, r.s.be);
            validate(r.s.be);
            r.valve = (MotorOperatedWaterValveBlockEntity) reloaded(r.l, r.valve);
            r.tank = (CondensateStorageTankBlockEntity) reloaded(r.l, r.tank);
            r.portBe = (CavityDrainPortBlockEntity) reloaded(r.l, r.portBe);
            if (!CavityDrains.near(r.l, r.s.env().min(), r.s.env().max(), 4).contains(r.port)) {
                r.portBe.onLoad(); // a chunk load calls it; setBlockEntity alone does not
            }
            h.assertTrue(r.valve.position() == positionBefore && r.valve.energy().getEnergyStored() == energyBefore, "the valve kept its position and its FE");
            near(h, r.cst(), cstBefore, 1.0, "the tank kept its water");
            h.assertTrue(r.s.be.isFormed() && r.s.seal() != null && r.s.seal().present(), "the reactor and its seal came back");
            double massAfterReload = r.vessel().getLiquidMassKg();
            r.tick(100);
            h.assertTrue(r.portBe.state() == State.DRAINING && r.portBe.flowKgPerS() > 100, "still draining after the reload: " + r.portBe.state());
            h.assertTrue(r.cst() > cstBefore + 1000, "the tank keeps filling: " + (r.cst() - cstBefore));
            near(h, massAfterReload - r.vessel().getLiquidMassKg(), r.cst() - cstBefore, 3.0, "water is conserved across the reload");
        } finally {
            r.finish();
        }
        h.succeed();
    }
}
