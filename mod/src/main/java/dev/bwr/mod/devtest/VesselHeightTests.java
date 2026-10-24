package dev.bwr.mod.devtest;

import dev.bwr.core.thermal.SteamSeparation;
import dev.bwr.core.thermal.VesselBuild;
import dev.bwr.core.turbine.PowerTurbine;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.reactor.VesselHeightBuild;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What the height of a built vessel does (VESSEL-HEIGHT.md): the build the real geometry measures, the inventory and dome a
 * formed vessel takes from it, saves that keep their level in any height, and wet steam through a real turbine.
 * Heights are the player's count (the envelope plus the control rod drive layer: 17 x 17 x 28 is a 27 high envelope).
 * No CC types here, so a {@code @GameTestHolder} class may use it.
 */
@GameTestHolder("bwrheight")
@PrefixGameTestTemplate(false)
public final class VesselHeightTests {
    private VesselHeightTests() {
    }

    // The same four numbers per height as core/src/test/.../VesselHeightTest: separator scale, chimney, water column, dome.
    private static final double[][] BUILDS = {
            {20, 0.207662306, 36.756228161, 41.646675467, 73.373019911},
            {28, 0.543957538, 96.280484292, 109.090684321, 137.756742150},
            {39, 1.000000000, 179.494485294, 203.044485294, 225.066071429},
            {43, 1.000000000, 245.408350840, 268.958350840, 225.066071429},
            {60, 1.000000000, 525.542279412, 549.092279412, 225.066071429},
    };

    private static void near(GameTestHelper h, double expected, double actual, double relative, String what) {
        h.assertTrue(Math.abs(expected - actual) <= relative * Math.max(1e-12, Math.abs(expected)),
                what + ": expected " + expected + ", got " + actual);
    }

    private static void validate(ReactorControllerBlockEntity be) throws Exception {
        var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
        method.setAccessible(true);
        method.invoke(be, be.getLevel());
    }

    private static void nextTick(ServerLevel l) {
        ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(l.getGameTime() + 1);
    }

    /** A formed reactor of the player's height {@code playerHeight} on a 17 wide footprint, interior minimum corner {@code min}. */
    private static ReactorControllerBlockEntity vessel(ServerLevel l, BlockPos min, int playerHeight) throws Exception {
        // envelope height = player height - 1; interior = envelope - 2
        return CompactCoreRegressionTests.build(l, min, 15, 15, playerHeight - 3, false);
    }

    private static void clear(ServerLevel l, BlockPos a, BlockPos b) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (!l.getBlockState(p).isAir()) {
                l.removeBlock(p, false);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrheight", timeoutTicks = 100)
    public static void vesselHeightBuildsMatchTheCore(GameTestHelper h) {
        VesselBuild reference = VesselHeightBuild.ofEnvelope(17, 17, 27);
        near(h, VesselBuild.REFERENCE.separatorRatio(), reference.separatorRatio(), 1e-8, "reference separator scale");
        near(h, VesselBuild.REFERENCE.chimneyColumnIn(), reference.chimneyColumnIn(), 1e-8, "reference chimney");
        near(h, VesselBuild.REFERENCE.waterColumnIn(), reference.waterColumnIn(), 1e-8, "reference water column");
        near(h, VesselBuild.REFERENCE.domeEquivalentIn(), reference.domeEquivalentIn(), 1e-8, "reference dome");
        h.assertTrue(reference.isReference(), "the 17x17x28 envelope is the reference build");
        for (double[] row : BUILDS) {
            VesselBuild b = VesselHeightBuild.ofEnvelope(17, 17, (int) row[0] - 1);
            near(h, row[1], b.separatorRatio(), 1e-8, "separator scale at " + (int) row[0]);
            near(h, row[2], b.chimneyColumnIn(), 1e-8, "chimney at " + (int) row[0]);
            near(h, row[3], b.waterColumnIn(), 1e-8, "water column at " + (int) row[0]);
            near(h, row[4], b.domeEquivalentIn(), 1e-8, "dome at " + (int) row[0]);
        }
        // True scale, as the v2 geometry draws it: the first whole player height at which the drawn separators reach
        // Columbia's proportions (the horizontal scale) is 39, not 43. Above it the standpipes lengthen.
        h.assertTrue(VesselHeightBuild.ofEnvelope(17, 17, 37).separatorRatio() < 1.0, "a 38 high vessel is still squashed");
        h.assertTrue(VesselHeightBuild.ofEnvelope(17, 17, 38).separatorRatio() == 1.0, "a 39 high vessel is at true scale");
        h.assertTrue(VesselHeightBuild.ofEnvelope(17, 17, 42).chimneyColumnIn() > VesselHeightBuild.ofEnvelope(17, 17, 38).chimneyColumnIn() + 60,
                "extra height above true scale is chimney");
        // A wide vessel drawn with the same proportions is the same physical vessel: 23 wide needs a taller envelope for the same scale.
        h.assertTrue(VesselHeightBuild.ofEnvelope(23, 23, 40).separatorRatio() < 1.0, "23x23x41 is still squashed");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrheight", timeoutTicks = 200)
    public static void aFormedVesselTakesTheBuildOfItsHeight(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        BlockPos refMin = new BlockPos(81000, 150, 81000), tallMin = new BlockPos(81100, 150, 81000);
        try {
            var ref = vessel(l, refMin, 28);
            var tall = vessel(l, tallMin, 43);
            h.assertTrue(ref.isFormed() && tall.isFormed(), "both vessels form: " + ref.statusLines() + " / " + tall.statusLines());
            var rc = ref.core();
            var tc = tall.core();
            h.assertTrue(rc.getVesselBuild().isReference(), "the 17x17x28 vessel has the reference build");
            near(h, 180.0, rc.getPressureVessel().getSteamDomeVolumeM3(), 1e-12, "reference steam dome is the calibrated 180 m3");
            h.assertTrue(rc.getPressureVessel().getInventoryScale() == 1.0, "reference water is the calibrated inventory");
            VesselBuild b43 = tc.getVesselBuild();
            near(h, BUILDS[3][1], b43.separatorRatio(), 1e-8, "tall separator scale");
            near(h, 180.0, rc.getPressureVessel().getSteamDomeVolumeM3(), 1e-12, "the reference dome is the calibrated 180 m3");
            near(h, 180.0 * b43.domeRatio(), tc.getPressureVessel().getSteamDomeVolumeM3(), 1e-9, "the tall dome follows the build");
            near(h, b43.waterMassFactor(), tc.getPressureVessel().getInventoryScale(), 1e-12, "tall water");
            h.assertTrue(tc.getPressureVessel().getInventoryScale() > 1.25, "43 high holds well over a quarter more water");
            // Same level, more water.
            near(h, rc.getCollapsedLevelIn(), tc.getCollapsedLevelIn(), 1e-9, "cold collapsed level is the same");
            near(h, rc.getLiquidMassKg() * b43.waterMassFactor(), tc.getLiquidMassKg(), 1e-9, "tall water mass");
            // The separators.
            near(h, SteamSeparation.DESIGN_MOISTURE, SteamSeparation.bandMoisture(tc.getSeparatorHeightRatio()), 1e-12, "tall steam moisture");
            h.assertTrue(rc.getSteamMoistureFraction() > 0.008 && rc.getSteamMoistureFraction() < 0.009,
                    "the reference reads 0.86 % moisture: " + rc.getSteamMoistureFraction());
            h.assertTrue(tc.getChimneyFactor() > 1.1 && rc.getChimneyFactor() == 1.0, "chimney factor, tall and reference");
        } finally {
            clear(l, refMin.offset(-2, -3, -2), refMin.offset(18, 28, 18));
            clear(l, tallMin.offset(-2, -3, -2), tallMin.offset(18, 43, 18));
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrheight", timeoutTicks = 200)
    public static void aSavedLevelIsKeptInAnyHeightOfVessel(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        BlockPos refMin = new BlockPos(81200, 150, 81000), tallMin = new BlockPos(81300, 150, 81000);
        try {
            var ref = vessel(l, refMin, 28);
            var tall = vessel(l, tallMin, 43);
            ref.core().getPressureVessel().setCollapsedLevelIn(-33.0);
            double level = ref.core().getCollapsedLevelIn();
            CompoundTag refSave = ref.saveWithFullMetadata(l.registryAccess());
            // The reference vessel's save has nothing new in it: it is an older save, to the letter.
            h.assertTrue(!refSave.contains("InventoryScale") && !refSave.contains("WellInventoryScale"), "no new keys in a reference save");
            // The same save, loaded into a vessel 43 high: the level stands where it was, on more water.
            BlockPos tallController = tall.getBlockPos();
            var reloaded = (ReactorControllerBlockEntity) BlockEntity.loadStatic(tallController, l.getBlockState(tallController), refSave, l.registryAccess());
            l.setBlockEntity(reloaded);
            validate(reloaded);
            h.assertTrue(reloaded.isFormed(), "the tall vessel re-forms with the older save: " + reloaded.statusLines());
            near(h, level, reloaded.core().getCollapsedLevelIn(), 1e-12, "an older save keeps its level in a taller vessel");
            h.assertTrue(reloaded.core().getPressureVessel().getInventoryScale() > 1.25, "and it is the taller vessel's water");
            // A save of the tall vessel round-trips to the bit.
            double tallLevel = reloaded.core().getCollapsedLevelIn();
            CompoundTag tallSave = reloaded.saveWithFullMetadata(l.registryAccess());
            var again = (ReactorControllerBlockEntity) BlockEntity.loadStatic(tallController, l.getBlockState(tallController), tallSave, l.registryAccess());
            l.setBlockEntity(again);
            validate(again);
            h.assertTrue(again.core().getCollapsedLevelIn() == tallLevel, "a tall vessel's level survives a reload to the bit: " + again.core().getCollapsedLevelIn() + " vs " + tallLevel);
        } finally {
            clear(l, refMin.offset(-2, -3, -2), refMin.offset(18, 28, 18));
            clear(l, tallMin.offset(-2, -3, -2), tallMin.offset(18, 43, 18));
        }
        h.succeed();
    }

    // -----------------------------------------------------------------------------------------------------------------

    private static void seed(PowerModuleBlockEntity m, double kg, double moisture) {
        var t = m.saveWithoutMetadata(m.getLevel().registryAccess());
        var steam = new CompoundTag();
        steam.putDouble("Mass", kg);
        steam.putDouble("Enthalpy", 2770);
        steam.putDouble("Pressure", 1015);
        if (moisture > 0) {
            steam.putDouble("Moisture", moisture);
        }
        t.put("Inlet", steam);
        // No LP behind this HP: empty its outlet each tick so it always has room.
        var empty = new CompoundTag();
        empty.putDouble("Mass", 0);
        empty.putDouble("Enthalpy", 0);
        empty.putDouble("Pressure", 0);
        t.put("Exhaust", empty);
        t.putDouble("RPM", 1500);
        t.putBoolean("Running", true);
        m.loadWithComponents(t, m.getLevel().registryAccess());
    }

    /** No LP behind this HP: the plant's crossover would take its exhaust. Reloading keeps the inlet, with its moisture. */
    private static void emptyExhaust(PowerModuleBlockEntity m) {
        var t = m.saveWithoutMetadata(m.getLevel().registryAccess());
        var empty = new CompoundTag();
        empty.putDouble("Mass", 0);
        empty.putDouble("Enthalpy", 0);
        empty.putDouble("Pressure", 0);
        t.put("Exhaust", empty);
        m.loadWithComponents(t, m.getLevel().registryAccess());
    }

    /** A turbine fed steam of one moisture for 40 ticks: its work per kilogram, and the wear it picked up. */
    private record Run(double kJPerKg, double wear, double moisturePercent) {
    }

    private static Run feed(ServerLevel l, PowerModuleBlockEntity hp, PowerModuleBlockEntity gen, double moisture) {
        double before = hp.damage();
        double kg = 0, kj = 0;
        for (int t = 0; t < 40; t++) {
            nextTick(l);
            seed(hp, 100, moisture);
            PowerTrain.tick(gen);
            kg += hp.flowKgPerS / 20;
            kj += hp.shaftMW * 1000 / 20;
            gen.energy.extractEnergy(Integer.MAX_VALUE, false);
        }
        return new Run(kj / kg, hp.damage() - before, hp.inletMoisturePercent);
    }

    // These two move the world's game clock a tick at a time to drive a turbine (as the turbine runtime checks do), so they run
    // in a batch of their own, alone, and put the clock back: anything timed by game time (cooling fuel, recorder dots) would
    // otherwise run fast beside them.
    @GameTest(template = "empty", templateNamespace = "bwrheight", batch = "heightclock", timeoutTicks = 400)
    public static void wetSteamLowersTurbineOutputAndWearsTheBlades(GameTestHelper h) {
        var l = h.getLevel();
        long clock = l.getGameTime();
        BlockPos base = new BlockPos(81400, 150, 81000);
        try {
            var hp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), base, Direction.EAST);
            var gen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), base.east(9), Direction.EAST);
            Run dry = feed(l, hp, gen, 0.0);
            Run design = feed(l, hp, gen, SteamSeparation.DESIGN_MOISTURE);
            Run wet = feed(l, hp, gen, 0.02);
            Run wetter = feed(l, hp, gen, 0.05);
            h.assertTrue(dry.kJPerKg > 200, "the turbine does work on dry steam: " + dry.kJPerKg + " kJ/kg");
            near(h, dry.kJPerKg, design.kJPerKg, 1e-9, "design moisture is the rated work");
            h.assertTrue(dry.wear == 0 && design.wear == 0, "dry steam and design steam wear nothing");
            near(h, 2.0, wet.moisturePercent, 1e-9, "the turbine reads the moisture it was fed");
            near(h, dry.kJPerKg * PowerTurbine.moistureWorkFactor(0.02), wet.kJPerKg, 1e-6, "2 % moisture costs 0.77 x 1.9 % of the work");
            h.assertTrue(wetter.kJPerKg < wet.kJPerKg && wet.kJPerKg < dry.kJPerKg, "more moisture, less work");
            h.assertTrue(wet.wear > 0 && wetter.wear > 2 * wet.wear, "and more wear: " + wet.wear + " then " + wetter.wear);
            near(h, PowerTurbine.erosionIncrement(100.0 * 40, 0.02), wet.wear, 1e-6, "the wear is the liquid passed over design over the erosion mass");
            near(h, wet.wear + wetter.wear, hp.damage(), 1e-9, "wear is the turbine's one damage counter");
            h.assertTrue(!hp.failed(), "and it has not failed");
        } finally {
            clear(l, base.offset(-5, -8, -8), base.offset(25, 8, 12));
            ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(clock);
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrheight", batch = "heightclock", timeoutTicks = 600)
    public static void steamFromAHighLevelArrivesWetAtTheTurbine(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        long clock = l.getGameTime();
        BlockPos min = new BlockPos(81600, 150, 81000);
        try {
            var reactor = vessel(l, min, 28);
            reactor.core().initialiseHotShutdown();
            // A steam nozzle in the north wall, high in the dome, and a pipe up and over to an HP turbine and its generator.
            BlockPos nozzlePos = new BlockPos(min.getX() + 7, min.getY() + 20, min.getZ() - 1);
            l.setBlock(nozzlePos, BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(), 3);
            int ty = min.getY() + 52;
            BlockPos turbinePos = new BlockPos(nozzlePos.getX(), ty, nozzlePos.getZ() + 5);
            var hp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), turbinePos, Direction.EAST);
            var gen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), turbinePos.east(9), Direction.EAST);
            int top = ty + 8;
            var port = hp.block().portPosition(hp.getBlockPos(), hp.getBlockState(), AssemblyPort.STEAM_INLET);
            // up from the nozzle, over to the inlet's column, down onto the inlet
            PowerModuleRuntimeCheck.pipe(l, nozzlePos.north(), new BlockPos(nozzlePos.getX(), top, nozzlePos.getZ() - 1));
            PowerModuleRuntimeCheck.pipe(l, new BlockPos(nozzlePos.getX(), top, nozzlePos.getZ() - 1), new BlockPos(nozzlePos.getX(), top, port.getZ()));
            PowerModuleRuntimeCheck.pipe(l, new BlockPos(nozzlePos.getX(), top, port.getZ()), new BlockPos(port.getX(), top, port.getZ()));
            PowerModuleRuntimeCheck.pipe(l, new BlockPos(port.getX(), top, port.getZ()), port.above());
            var nozzle = (RpvSteamOutletBlockEntity) l.getBlockEntity(nozzlePos);
            nozzle.noteController(reactor.getBlockPos());
            nozzle.setPosition(1);
            nozzle.refreshAttachment(l);
            nozzle.refreshLine(l);
            nextTick(l);
            h.assertTrue(nozzle.flowKgPerS(reactor.core().getPressurePsig()) > 0, "the fixture nozzle passes steam");
            h.assertTrue(dev.bwr.mod.power.PowerSteamNetwork.sources(hp).size() == 1, "the HP inlet reaches the nozzle");
            double[] seen = new double[2];
            for (int phase = 0; phase < 2; phase++) {
                if (phase == 1) {
                    // Overfilled: 12 in over L8.
                    reactor.core().getPressureVessel().setCollapsedLevelIn(SteamSeparation.L8_VESSEL_IN + 12.0 - 530.0);
                }
                for (int t = 0; t < 60; t++) {
                    nextTick(l);
                    // The vessel keeps its pressure and its level; the fixture is about the steam's moisture.
                    reactor.core().getPressureVessel().restorePressurePsig(1025);
                    nozzle.flowKgPerS(reactor.core().getPressurePsig());
                    emptyExhaust(hp);
                    PowerTrain.tick(gen);
                    gen.energy.extractEnergy(Integer.MAX_VALUE, false);
                }
                seen[phase] = hp.inletMoisturePercent;
                h.assertTrue(seen[phase] > 0, "no steam reached the turbine, phase " + phase + ": status '" + hp.status + "' flow " + hp.flowKgPerS
                        + " inlet " + hp.inletMass() + " nozzle last flow " + nozzle.getLastFlowKgPerS() + " open " + nozzle.getLineOpenFraction()
                        + " gen " + gen.status + " formed " + reactor.isFormed() + " pressure " + reactor.core().getPressurePsig());
                near(h, 100 * reactor.core().getSteamMoistureFraction(), seen[phase], 1e-6, "the turbine reads the vessel's moisture, phase " + phase);
            }
            h.assertTrue(seen[0] > 0.8 && seen[0] < 0.9, "normal level: the reference's 0.86 %: " + seen[0]);
            h.assertTrue(seen[1] > 5 * seen[0], "12 in over L8: carryover rises sharply: " + seen[1] + " vs " + seen[0]);
            h.assertTrue(hp.damage() > 0, "and the blades are wearing");
        } finally {
            clear(l, min.offset(-2, -3, -4), min.offset(30, 60, 20));
            ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(clock);
        }
        h.succeed();
    }
}
