package dev.bwr.mod.devtest;

import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.core.chemistry.FilterDemineralizer;
import dev.bwr.core.chemistry.RwcuCooler;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.rwcu.*;
import dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The reactor water cleanup system: the chemistry in the core, the skid (pump, regenerative and non-regenerative exchangers,
 * stainless steel nanofilter, the exchangers optional) piped to the vessel and to the feedwater line, the reject path and the bottom drain, the radiation,
 * the pressure-boundary lines and the saves. Every test drives the pump and the reactor synchronously, so nothing interleaves
 * with its readings. No CC types here.
 *
 * <p>The skid, on the north side of a 9 x 9 x 12 vessel at height {@code y}: port -> pipe -> valve -> pipe -> pump -> regenerative
 * exchanger -> cooler -> tee (pipe) -> demineraliser; the tee also runs through a valve to a Condensate Storage Tank (the reject
 * path); the demineraliser's outlet returns over the top to the regenerator's cold side, and from there to a feedwater port.
 */
@GameTestHolder("bwrrwcu") @PrefixGameTestTemplate(false)
public final class RwcuTests {
    private RwcuTests() {}

    private static final double DT = 0.05;

    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String what) {
        h.assertTrue(Math.abs(actual - expected) <= tolerance, what + ": expected " + expected + " +-" + tolerance + " got " + actual);
    }

    private static void pipe(ServerLevel l, BlockPos p) {
        l.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, p), 3);
    }

    private static void tickReactor(ReactorControllerBlockEntity be) {
        ReactorControllerBlockEntity.serverTick(be.getLevel(), be.getBlockPos(), be.getBlockState(), be);
    }

    static final class Skid {
        ServerLevel l;
        BlockPos min;
        ReactorControllerBlockEntity be;
        RwcuPumpBlockEntity pump;
        RwcuRegenBlockEntity regen;
        RwcuCoolerBlockEntity cooler;
        RwcuDeminBlockEntity demin;
        MotorOperatedWaterValveBlockEntity suctionValve, rejectValve;
        CondensateStorageTankBlockEntity cst;
        BlockPos port, feed, suctionPipe, pumpPos;
        boolean coolingWater = true;

        BlockPos at(int x, int y, int z) { return min.offset(x, y, z); }

        void tick(int n) {
            for (int i = 0; i < n; i++) {
                pump.energy().receiveEnergy(100_000, false);
                if (coolingWater) {
                    cooler.plant().fillCold(RwcuCooler.CAPACITY_KG, Saturation.subcooledLiquidEnthalpyKJPerKg(RwcuCooler.COLD_C), false);
                    cooler.plant().drainHot(RwcuCooler.CAPACITY_KG, false);
                }
                RwcuPumpBlockEntity.serverTick(l, pump.getBlockPos(), pump.getBlockState(), pump);
                tickReactor(be);
            }
        }

        void start() { pump.setRunning(true); }
        void stop() { pump.setRunning(false); }
        dev.bwr.core.ReactorCore core() { return be.core(); }
    }

    /** Builds the vessel and the skid. {@code drain}: the suction is the bottom drain port at the lowest row, else a recirculation outlet. */
    private static Skid skid(GameTestHelper h, int x0, boolean drain, boolean withReject) throws Exception {
        return skid(h.getLevel(), x0, drain, withReject);
    }

    /** The same, in any level (the client check builds it in a disposable world). */
    static Skid skid(ServerLevel level, int x0, boolean drain, boolean withReject) throws Exception {
        var s = new Skid();
        s.l = level;
        s.min = new BlockPos(x0, 190, x0);
        var l = s.l;
        // A fixture left by an earlier run keeps its block entities (setting an equal state replaces nothing): clear the
        // skid and the controller first, so every run starts from new machines and a new core.
        for (int x = -2; x <= 10; x++) for (int y = 0; y <= 12; y++) for (int z = -13; z <= -2; z++) {
            var cell = s.at(x, y, z);
            if (!l.getBlockState(cell).isAir()) l.removeBlock(cell, false);
        }
        l.removeBlock(s.at(-1, 3, 4), false);
        s.be = CompactCoreRegressionTests.build(l, s.min, 9, 9, 12, false);
        int y = drain ? 1 : 6;
        s.port = s.at(5, y, -1);
        l.setBlock(s.port, (drain ? BwrBlocks.RPV_BOTTOM_DRAIN_PORT.get() : BwrBlocks.RECIRCULATION_OUTLET.get()).defaultBlockState()
                .setValue(RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 3);
        s.feed = s.at(2, y, -1);
        l.setBlock(s.feed, BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 3);
        s.suctionPipe = s.at(5, y, -2);
        pipe(l, s.suctionPipe);
        s.suctionValve = WaterValveTests.valveAt(l, s.at(5, y, -3), Direction.NORTH);
        openValve(l, s.suctionValve);
        pipe(l, s.at(5, y, -4));
        s.pumpPos = s.at(5, y, -5);
        place(l, s.pumpPos, BwrBlocks.RWCU_PUMP.get().defaultBlockState().setValue(RwcuUnitBlock.FACING, Direction.NORTH));
        place(l, s.at(5, y, -6), BwrBlocks.RWCU_REGENERATIVE_HEAT_EXCHANGER.get().defaultBlockState().setValue(RwcuUnitBlock.FACING, Direction.SOUTH));
        place(l, s.at(5, y, -7), BwrBlocks.RWCU_NONREGENERATIVE_HEAT_EXCHANGER.get().defaultBlockState().setValue(RwcuUnitBlock.FACING, Direction.SOUTH));
        pipe(l, s.at(5, y, -8));
        place(l, s.at(5, y, -9), BwrBlocks.RWCU_FILTER_DEMINERALIZER.get().defaultBlockState().setValue(RwcuUnitBlock.FACING, Direction.SOUTH));
        // The return: over the top of the cooler and the pump to the regenerator's cold inlet (east side).
        for (var p : new int[][]{{5, 0, -10}, {6, 0, -10}, {6, 1, -10}, {6, 1, -9}, {6, 1, -8}, {6, 1, -7}, {6, 1, -6}, {6, 0, -6}}) pipe(l, s.at(p[0], y + p[1], p[2]));
        // ... and from its cold outlet (west side) to the feedwater port.
        for (var p : new int[][]{{4, -6}, {3, -6}, {2, -6}, {2, -5}, {2, -4}, {2, -3}, {2, -2}}) pipe(l, s.at(p[0], y, p[1]));
        if (withReject) {
            pipe(l, s.at(4, y, -8));
            s.rejectValve = WaterValveTests.valveAt(l, s.at(3, y, -8), Direction.WEST);
            l.setBlock(s.at(2, y, -8), BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState(), 3);
            s.cst = (CondensateStorageTankBlockEntity) l.getBlockEntity(s.at(2, y, -8));
        }
        s.pump = (RwcuPumpBlockEntity) l.getBlockEntity(s.pumpPos);
        s.regen = (RwcuRegenBlockEntity) l.getBlockEntity(s.at(5, y, -6));
        s.cooler = (RwcuCoolerBlockEntity) l.getBlockEntity(s.at(5, y, -7));
        s.demin = (RwcuDeminBlockEntity) l.getBlockEntity(s.at(5, y, -9));
        SpargerRegressionTests.validate(s.be);
        tickReactor(s.be);
        return s;
    }

    /** For the client check: the reject valve open, cooling water in the cooler, the vessel water a little dirty. */
    static void openForCheck(ServerLevel l, Skid s) {
        openValve(l, s.rejectValve);
        s.cooler.plant().fillCold(RwcuCooler.CAPACITY_KG, Saturation.subcooledLiquidEnthalpyKJPerKg(RwcuCooler.COLD_C), false);
        dirty(s, 1.8);
    }

    private static void place(ServerLevel l, BlockPos p, net.minecraft.world.level.block.state.BlockState state) {
        l.setBlock(p, state, 3);
    }

    private static void openValve(ServerLevel l, MotorOperatedWaterValveBlockEntity v) {
        WaterValveTests.power(v, MotorOperatedWaterValveBlockEntity.ENERGY_CAPACITY_FE);
        v.setTarget(1.0);
        WaterValveTests.pass(l, v, 400);
    }

    private static void closeValve(ServerLevel l, MotorOperatedWaterValveBlockEntity v) {
        WaterValveTests.power(v, MotorOperatedWaterValveBlockEntity.ENERGY_CAPACITY_FE);
        v.setTarget(0.0);
        WaterValveTests.pass(l, v, 400);
    }

    /** Water that is dirty to {@code uS} uS/cm. */
    private static void dirty(Skid s, double uS) {
        var c = s.core().getChemistry();
        double target = CoolantChemistry.ionicGramsFor(uS, s.core().getLiquidMassKg());
        c.addIons(Math.max(0, target - c.getIonicG()));
    }

    private static double kappa(Skid s) { return s.core().getConductivityUsPerCm(); }

    private static ReactorControllerBlockEntity reload(GameTestHelper h, ReactorControllerBlockEntity be, java.util.function.UnaryOperator<CompoundTag> edit) throws Exception {
        var l = h.getLevel();
        var root = be.getBlockPos();
        var saved = edit.apply(be.saveWithFullMetadata(l.registryAccess()));
        l.removeBlockEntity(root);
        var next = (ReactorControllerBlockEntity) BlockEntity.loadStatic(root, l.getBlockState(root), saved, l.registryAccess());
        l.setBlockEntity(next);
        SpargerRegressionTests.validate(next);
        return next;
    }

    // ------------------------------------------------------------------------------------------------ chemistry

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 400)
    public static void aNewPlantIsCleanAndConductivityRisesWithNoCleanup(GameTestHelper h) throws Exception {
        var s = skid(h, 130_000, false, false);
        var core = s.core();
        near(h, kappa(s), CoolantChemistry.CLEAN_US_PER_CM, 1e-6, "a formed plant starts clean");
        h.assertTrue(core.getChemistry().getCrudG() < 0.01 && core.getChemistry().getActivityBq() < 1e6, "no crud to speak of, next to no activity (an unfuelled core activates nothing): " + core.getChemistry().getCrudG() + " g, " + core.getChemistry().getActivityBq() + " Bq");
        double before = kappa(s);
        for (int i = 0; i < 3600; i++) core.step(1.0);
        h.assertTrue(kappa(s) > before + 1e-5, "with no RWCU running, an hour raises the conductivity: " + before + " -> " + kappa(s));
        h.assertTrue(core.getChemistry().getCrudG() > 0, "and builds crud");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 4000)
    public static void conductivityFallsWithRwcuRunningAndKeepsRisingWithoutIt(GameTestHelper h) throws Exception {
        var s = skid(h, 130_400, false, false);
        dirty(s, 3.0);
        double a0 = kappa(s);
        s.tick(1200);
        double idle = kappa(s) - a0;
        h.assertTrue(idle >= 0, "a stopped pump cleans nothing: " + idle);
        h.assertTrue(s.pump.getFlowKgPerS() == 0, "nothing starts it: no flow without a start command");
        double b0 = kappa(s);
        s.start();
        s.tick(1200);
        h.assertTrue(s.pump.circuitValid(), "the circuit is complete: " + s.pump.connectionStatus());
        h.assertTrue(s.pump.getFlowKgPerS() > 20, "the pump moves reactor water: " + s.pump.getFlowKgPerS() + " (" + s.pump.connectionStatus() + ")");
        double fall = kappa(s) - b0;
        h.assertTrue(fall < -0.02, "running, it removes impurity: " + b0 + " -> " + kappa(s));
        h.assertTrue(s.demin.bed().ionLoadG() > 0.1, "into the resin");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 6000)
    public static void resinExhaustsAndHasToBeReplacedWithResinAndGivesSpentResin(GameTestHelper h) throws Exception {
        var s = skid(h, 130_800, false, false);
        s.core().getChemistry().addCrud(40);
        s.core().getChemistry().fromArray(new double[]{s.core().getChemistry().getIonicG(), 40, 40 * 1e12 * 0.5, 0, 0});
        dirty(s, 80.0);
        s.start();
        int ticks = 0;
        while (s.demin.bed().exhaustion() < 1.0 && ticks < 5200) {
            s.tick(20);
            ticks += 20;
            dirty(s, 80.0); // a constant impurity source, so the bed is the only sink
        }
        h.assertTrue(s.demin.bed().exhaustion() >= 1.0, "the bed exhausted: " + s.demin.bed().exhaustion() + " after " + ticks);
        near(h, s.demin.bed().ionLoadG(), FilterDemineralizer.ION_CAPACITY_G, FilterDemineralizer.SPENT_BELOW_G + 1e-6, "exhausted in proportion to what it removed");
        double held = kappa(s);
        s.tick(200);
        h.assertTrue(kappa(s) >= held * (1 - 1e-4), "an exhausted bed removes no ions: " + held + " -> " + kappa(s));
        h.assertTrue(s.demin.bed().crudLoadG() > 0 && s.demin.bed().activityBq() > 0, "it caught radioactive crud");
        // Replace it with resin.
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var resin = new ItemStack(BwrItems.DEMINERALIZER_RESIN.get(), 2);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, resin);
        s.demin.changeBed(player, resin);
        h.assertTrue(resin.getCount() == 1, "one resin consumed");
        h.assertTrue(s.demin.bed().exhaustion() == 0 && s.demin.bed().ionLoadG() == 0, "a fresh bed");
        boolean spent = false;
        for (var stack : player.getInventory().items) if (stack.is(BwrItems.SPENT_RESIN.get()) && SpentResinItem.activityBq(stack) > 0) spent = true;
        h.assertTrue(spent, "the old bed came out as radioactive Spent Resin");
        // It reads: a player carrying it is a source.
        h.assertTrue(SpentResinItem.carriedGammaWatts(player) > 0, "a carried spent bed is a gamma source");
        h.assertTrue(SpentResinItem.doseRateOf(player, player.position().add(1, 1, 0)) > 0, "and reads on the survey meter beside the carrier");
        double fresh = kappa(s);
        s.tick(400);
        h.assertTrue(kappa(s) < fresh - 1e-4, "the replaced bed cleans again: " + fresh + " -> " + kappa(s));
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void aNanofilterOnHotWaterWorksAndNothingDamagesIt(GameTestHelper h) throws Exception {
        var s = skid(h, 131_200, false, false);
        s.core().getPressureVessel().restorePressurePsig(1000);
        double vesselC = s.core().getCoolantTemperatureC();
        h.assertTrue(vesselC > 250, "a hot vessel: " + vesselC);
        // The cooler loses its cooling water: the nanofilter sees reactor-hot water, and that is no harm to it.
        dirty(s, 40);
        s.coolingWater = false;
        s.cooler.plant().restore(0, 0, 0, 0);
        double before = kappa(s);
        s.start();
        s.tick(600);
        h.assertTrue(s.pump.getFlowKgPerS() > 20, "the pump runs: " + s.pump.getFlowKgPerS());
        h.assertTrue(s.pump.getDemineraliserC() > 150, "the nanofilter sees hot water: " + s.pump.getDemineraliserC());
        h.assertTrue(s.demin.bed().ionLoadG() > 1, "and takes dissolved impurity out of it: " + s.demin.bed().ionLoadG());
        h.assertTrue(kappa(s) < before - 0.02, "so the conductivity falls: " + before + " -> " + kappa(s));
        h.assertTrue(s.demin.bed().exhaustion() < 1.0 && s.demin.readout().headlineStatus() != 3, "no alarm on the readout");
        h.assertTrue(!s.pump.connectionStatus().contains("140 F"), "the pump status has no resin warning: " + s.pump.connectionStatus());
        // The same element in a loop with full cooling water removes the same: temperature plays no part.
        var cold = skid(h, 131_600, false, false);
        cold.core().getPressureVessel().restorePressurePsig(1000);
        dirty(cold, 40);
        cold.start();
        cold.tick(600);
        h.assertTrue(cold.pump.getDemineraliserC() < 60, "at " + cold.pump.getDemineraliserC());
        near(h, s.demin.bed().ionLoadG(), cold.demin.bed().ionLoadG(), 0.12 * cold.demin.bed().ionLoadG() + 0.1, "the hot and the cooled element took about the same");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void theRegenerativeExchangerRecoversTheHeatOfTheReturnToFeedwater(GameTestHelper h) throws Exception {
        var s = skid(h, 132_000, false, false);
        s.core().getPressureVessel().restorePressurePsig(1000);
        s.start();
        s.tick(400);
        double vesselC = s.core().getCoolantTemperatureC();
        h.assertTrue(s.pump.getFlowKgPerS() > 20, "running: " + s.pump.connectionStatus());
        h.assertTrue(s.pump.getReturnC() > vesselC - 40, "the water returns to feedwater hot (" + s.pump.getReturnC() + " of " + vesselC + " C)");
        h.assertTrue(s.regen.coldOutC() == s.pump.getReturnC(), "the exchanger reads the same return temperature");
        h.assertTrue(s.pump.getDemineraliserC() < 60, "the resin sees 120 F water: " + s.pump.getDemineraliserC());
        double unregenerated = s.pump.getDemineraliserKgPerS() * (Saturation.subcooledLiquidEnthalpyKJPerKg(vesselC)
                - Saturation.subcooledLiquidEnthalpyKJPerKg(s.pump.getDemineraliserC())) / 1000.0;
        h.assertTrue(s.pump.getHeatLossMW() < 0.12 * unregenerated, "the vessel loses only the part the regenerator does not recover: "
                + s.pump.getHeatLossMW() + " MW of " + unregenerated);
        h.assertTrue(s.regen.dutyMW() > 0.85 * unregenerated, "recovered " + s.regen.dutyMW() + " MW");
        near(h, s.pump.getCoolerDutyMW(), s.pump.getHeatLossMW(), 0.01 * s.pump.getHeatLossMW() + 1e-3, "all the heat the vessel loses goes to the cooling water");
        h.assertTrue(s.cooler.plant().hot() >= 0 && s.cooler.dutyMW() > 0, "the cooler is working");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ blowdown and drain

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 4000)
    public static void blowdownThroughTheRejectValveLowersTheVesselAndFillsTheTank(GameTestHelper h) throws Exception {
        var s = skid(h, 132_400, false, true);
        s.start();
        s.tick(200);
        h.assertTrue(s.pump.getFlowKgPerS() > 20 && s.pump.getRejectKgPerS() == 0, "valve closed: no blowdown (" + s.pump.getRejectKgPerS() + ")");
        h.assertTrue(s.cst.storedKg() == 0, "the tank gained nothing");
        double mass = s.core().getLiquidMassKg(), level = s.core().getCollapsedLevelIn();
        openValve(s.l, s.rejectValve);
        s.tick(1200);
        h.assertTrue(s.pump.getRejectKgPerS() > 5, "the reject path takes a share: " + s.pump.getRejectKgPerS());
        double lost = mass - s.core().getLiquidMassKg();
        h.assertTrue(lost > 500, "the vessel lost water: " + lost);
        h.assertTrue(s.core().getCollapsedLevelIn() < level, "and its level fell");
        near(h, s.cst.storedKg(), lost, 0.03 * lost + 3, "the tank gained what the vessel lost (no water made or lost)");
        near(h, s.core().getCleanupBlowdownKgPerS(), s.pump.getRejectKgPerS(), 1e-6, "the core saw the same blowdown");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 6000)
    public static void theBottomDrainDrainsTheVesselDownToItsPort(GameTestHelper h) throws Exception {
        var s = skid(h, 132_800, true, true);
        var port = (dev.bwr.mod.reactor.RpvWaterInjectionPortBlockEntity) s.l.getBlockEntity(s.port);
        h.assertTrue(port.controller() == s.be, "the drain port belongs to the formed vessel");
        openValve(s.l, s.rejectValve);
        // Put the water just over the drain port's elevation so the run is short.
        var env = s.be.vesselEnvelope();
        double elevation = dev.bwr.mod.reactor.VesselWaterGeometry.levelAtHeight(env, s.port.getY() + .5 - env.min().getY());
        s.core().getPressureVessel().setCollapsedLevelIn(elevation + 3.0);
        h.assertTrue(RwcuCircuit.covered(s.be, s.port), "the port is covered");
        double mass = s.core().getLiquidMassKg();
        s.start();
        s.tick(400);
        h.assertTrue(s.pump.getFlowKgPerS() > 20, "the pump draws through the drain port: " + s.pump.connectionStatus());
        h.assertTrue(s.core().getLiquidMassKg() < mass - 100, "and the vessel drains: " + (mass - s.core().getLiquidMassKg()));
        int guard = 0;
        while (s.pump.getFlowKgPerS() > 0.5 && guard++ < 150) s.tick(20);
        h.assertTrue(s.pump.getFlowKgPerS() <= 0.5, "once the water is at the port the pump draws nothing more");
        h.assertTrue(s.core().getCollapsedLevelIn() <= elevation + 0.5 && s.core().getCollapsedLevelIn() > elevation - 1.0,
                "the level stopped at the port: " + s.core().getCollapsedLevelIn() + " vs " + elevation);
        h.assertTrue(s.cst.storedKg() > 100, "the water went to the tank");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ radiation

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void crudRaisesTheDoseAroundTheLinesAndCleanupLowersIt(GameTestHelper h) throws Exception {
        var s = skid(h, 133_200, false, false);
        var at = Vec3.atCenterOf(s.port.north(2));
        double clean = RadiationSources.doseRateMsvPerHour(s.l, at);
        h.assertTrue(clean < 1e-9, "a clean plant adds nothing around the lines: " + clean);
        double crud = 3000;
        s.core().getChemistry().fromArray(new double[]{s.core().getChemistry().getIonicG(), crud, crud * 1e12, 0, 0});
        double dosed = RadiationSources.doseRateMsvPerHour(s.l, at);
        h.assertTrue(dosed > 0.1, "activated crud raises the dose rate beside the line: " + dosed);
        s.start();
        s.tick(1000);
        double after = RadiationSources.doseRateMsvPerHour(s.l, at);
        h.assertTrue(s.core().getChemistry().getCrudG() < crud, "cleanup removed crud");
        h.assertTrue(s.demin.bed().activityBq() > 0, "the bed holds it");
        double demin = RadiationSources.doseRateMsvPerHour(s.l, Vec3.atCenterOf(s.demin.getBlockPos().east(2)));
        h.assertTrue(demin > 0, "and the bed is a source of its own (the spent resin of the future)");
        h.assertTrue(after < dosed, "cleanup lowered the dose beside the line: " + dosed + " -> " + after);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ pressure boundary

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 800)
    public static void anRwcuLineBreakIsALocaUpToItsIsolationValve(GameTestHelper h) throws Exception {
        var s = skid(h, 133_600, false, false);
        var be = s.be;
        tickReactor(be);
        var boundary = be.core().getBoundaryStress();
        h.assertTrue(!boundary.isBroken(BoundaryComponent.RECIRCULATION_LINE), "intact");
        // Between the valve and the pump: the valve is open, so the break drains the vessel through the suction port.
        var gap = s.at(5, 6, -4);
        s.l.removeBlock(gap, false);
        tickReactor(be);
        h.assertTrue(boundary.isBroken(BoundaryComponent.RECIRCULATION_LINE), "a broken RWCU suction line is a recirculation line break");
        h.assertTrue(!boundary.activeOpenings().isEmpty(), "it is open to the vessel");
        double mass = s.core().getPressureVessel().getLiquidMassKg();
        for (int i = 0; i < 40; i++) tickReactor(be);
        h.assertTrue(s.core().getPressureVessel().getLiquidMassKg() < mass - 1, "and drains it");
        // Closing the isolation valve isolates it.
        closeValve(s.l, s.suctionValve);
        for (int i = 0; i < 3; i++) tickReactor(be);
        h.assertTrue(boundary.activeOpenings().isEmpty() && !boundary.isBroken(BoundaryComponent.RECIRCULATION_LINE), "a closed valve between the break and the vessel isolates it");
        // Opening it again restarts the leak.
        openValve(s.l, s.suctionValve);
        for (int i = 0; i < 3; i++) tickReactor(be);
        h.assertTrue(!boundary.activeOpenings().isEmpty(), "opening the valve starts the leak again");
        // Putting the pipe back seals it.
        pipe(s.l, gap);
        for (int i = 0; i < 3; i++) tickReactor(be);
        h.assertTrue(boundary.activeOpenings().isEmpty() && !boundary.isBroken(BoundaryComponent.RECIRCULATION_LINE), "pipe back: sealed");
        // The return line to the feedwater port is a feedwater line.
        var returnGap = s.at(2, 6, -4);
        s.l.removeBlock(returnGap, false);
        tickReactor(be);
        h.assertTrue(boundary.isBroken(BoundaryComponent.FEEDWATER_LINE), "a broken RWCU return line is a feedwater line break");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 800)
    public static void aBrokenBottomDrainLineIsASmallFeedwaterClassBreak(GameTestHelper h) throws Exception {
        var s = skid(h, 134_000, true, false);
        var boundary = s.be.core().getBoundaryStress();
        s.l.removeBlock(s.suctionPipe, false);
        tickReactor(s.be);
        h.assertTrue(boundary.isBroken(BoundaryComponent.FEEDWATER_LINE), "the drain line is pressure boundary");
        var openings = boundary.activeOpenings();
        h.assertTrue(openings.size() == 1, "one opening");
        var feedwater = dev.bwr.mod.reactor.ReactorBoundaryEffects.opening(s.be.vesselEnvelope(), s.feed, BoundaryComponent.FEEDWATER_LINE, false);
        h.assertTrue(openings.getFirst().ratedLiquidKgPerS() < 0.5 * feedwater.ratedLiquidKgPerS(),
                "much smaller than a feedwater nozzle: " + openings.getFirst().ratedLiquidKgPerS() + " vs " + feedwater.ratedLiquidKgPerS());
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 800)
    public static void aCrossedLineIsNotACircuitAndMovesNoWater(GameTestHelper h) throws Exception {
        var s = skid(h, 134_400, false, false);
        s.l.removeBlock(s.at(5, 6, -8), false); // the cooler outlet no longer reaches the demineraliser
        s.start();
        s.tick(100);
        h.assertTrue(!s.pump.circuitValid() && s.pump.getFlowKgPerS() == 0, "no circuit, no flow: " + s.pump.connectionStatus());
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ saves

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 800)
    public static void chemistryTheBedAndTheCoolingWaterSurviveASaveAndReload(GameTestHelper h) throws Exception {
        var s = skid(h, 134_800, false, false);
        s.core().getChemistry().fromArray(new double[]{CoolantChemistry.ionicGramsFor(2.5, s.core().getLiquidMassKg()), 12.5, 3.0e12, 0.01, 0.002});
        double kappa = kappa(s);
        s.start();
        s.tick(200);
        var bed = s.demin.bed().toArray();
        double cold = s.cooler.plant().cold();
        var be = reload(h, s.be, t -> t);
        near(h, be.core().getChemistry().getCrudG(), s.core().getChemistry().getCrudG(), 1e-9, "crud");
        h.assertTrue(be.core().getChemistry().getActivityBq() > 0 && be.core().getChemistry().getCladdingWear() > 0, "activity and wear");
        h.assertTrue(be.core().getConductivityUsPerCm() > 2.0, "conductivity kept: " + be.core().getConductivityUsPerCm() + " (was " + kappa + ")");
        // The demineraliser and the cooler are block entities with their own saves.
        var tag = s.demin.saveWithFullMetadata(s.l.registryAccess());
        var copy = new RwcuDeminBlockEntity(s.demin.getBlockPos(), s.demin.getBlockState());
        copy.loadWithComponents(tag, s.l.registryAccess());
        h.assertTrue(java.util.Arrays.equals(bed, copy.bed().toArray()), "the resin bed round-trips");
        var coolerTag = s.cooler.saveWithFullMetadata(s.l.registryAccess());
        var coolerCopy = new RwcuCoolerBlockEntity(s.cooler.getBlockPos(), s.cooler.getBlockState());
        coolerCopy.loadWithComponents(coolerTag, s.l.registryAccess());
        near(h, coolerCopy.plant().cold(), cold, 1e-9, "the cooling water round-trips");
        var pumpCopy = new RwcuPumpBlockEntity(s.pump.getBlockPos(), s.pump.getBlockState());
        pumpCopy.loadWithComponents(s.pump.saveWithFullMetadata(s.l.registryAccess()), s.l.registryAccess());
        h.assertTrue(pumpCopy.isRunning() == s.pump.isRunning(), "the pump keeps its run state");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 800)
    public static void anOldSaveLoadsCleanAndACleanPlantWritesNothing(GameTestHelper h) throws Exception {
        var s = skid(h, 135_200, false, false);
        s.core().getChemistry().reset(s.core().getLiquidMassKg());
        var clean = s.be.saveWithFullMetadata(s.l.registryAccess());
        h.assertTrue(!clean.contains(CoolantChemistryNbt.KEY), "a clean coolant writes no chemistry key");
        s.core().getChemistry().addIons(500);
        s.core().getChemistry().addCrud(5);
        var dirty = s.be.saveWithFullMetadata(s.l.registryAccess());
        h.assertTrue(dirty.contains(CoolantChemistryNbt.KEY), "a changed one does");
        // A save from before the update has no key: strip it.
        var be = reload(h, s.be, t -> { t.remove(CoolantChemistryNbt.KEY); return t; });
        near(h, be.core().getConductivityUsPerCm(), CoolantChemistry.CLEAN_US_PER_CM, 1e-6, "an old save loads clean");
        h.assertTrue(be.core().getChemistry().getCrudG() == 0, "no crud");
        // The demineraliser block of an old world is a new block; a fresh one has a fresh bed and saves nothing.
        var fresh = new RwcuDeminBlockEntity(s.demin.getBlockPos(), s.demin.getBlockState());
        h.assertTrue(fresh.hasBed() && fresh.bed().exhaustion() == 0, "a new demineraliser has a fresh bed");
        var tag = fresh.saveWithFullMetadata(s.l.registryAccess());
        h.assertTrue(!tag.contains("Bed"), "and a fresh bed writes nothing");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the nanofilter, optional exchangers

    /**
     * The standard skid with the regenerative exchanger and/or the cooler taken out (pipe in their places). Without the
     * regenerator the return goes round the west side to the feedwater port instead of through the exchanger's cold side.
     */
    static Skid variant(ServerLevel level, int x0, boolean regen, boolean cooler, boolean withReject) throws Exception {
        var s = skid(level, x0, false, withReject);
        var l = s.l;
        int y = 6;
        if (!cooler) {
            l.removeBlock(s.at(5, y, -7), false);
            pipe(l, s.at(5, y, -7));
            s.cooler = null;
            s.coolingWater = false;
        }
        if (!regen) {
            l.removeBlock(s.at(5, y, -6), false);
            pipe(l, s.at(5, y, -6));
            s.regen = null;
            for (var p : new int[][]{{6, 0, -10}, {6, 1, -10}, {6, 1, -9}, {6, 1, -8}, {6, 1, -7}, {6, 1, -6}, {6, 0, -6},
                    {4, 0, -6}, {3, 0, -6}, {2, 0, -6}, {2, 0, -5}, {2, 0, -4}, {2, 0, -3}}) l.removeBlock(s.at(p[0], y + p[1], p[2]), false);
            for (int x = 4; x >= 0; x--) pipe(l, s.at(x, y, -10));
            for (int z = -9; z <= -2; z++) pipe(l, s.at(0, y, z));
            pipe(l, s.at(1, y, -2));
        }
        SpargerRegressionTests.validate(s.be);
        tickReactor(s.be);
        return s;
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void theLoopRunsHotWithoutTheExchangersAndTheNanofilterCleansReactorWater(GameTestHelper h) throws Exception {
        var s = variant(h.getLevel(), 140_000, false, false, false);
        s.core().getPressureVessel().restorePressurePsig(1000);
        double vesselC = s.core().getCoolantTemperatureC();
        s.core().getChemistry().fromArray(new double[]{s.core().getChemistry().getIonicG(), 40, 40 * 1e12 * 0.5, 0, 0});
        dirty(s, 40);
        // The same plant with the pump never started is the control: the water also changes with the vessel's own mass.
        var control = variant(h.getLevel(), 143_600, false, false, false);
        control.core().getPressureVessel().restorePressurePsig(1000);
        control.core().getChemistry().fromArray(new double[]{control.core().getChemistry().getIonicG(), 40, 40 * 1e12 * 0.5, 0, 0});
        dirty(control, 40);
        control.tick(600);
        s.start();
        s.tick(600);
        h.assertTrue(s.pump.circuitValid(), "pump, pipes, nanofilter, feedwater port: no exchangers needed: " + s.pump.connectionStatus());
        h.assertTrue(s.pump.getFlowKgPerS() > 20, "the pump moves water: " + s.pump.getFlowKgPerS());
        near(h, s.pump.getDemineraliserC(), vesselC, 3.0, "the nanofilter sees reactor water");
        near(h, s.pump.getReturnC(), vesselC, 3.0, "and it returns as hot");
        h.assertTrue(s.pump.getHeatLossMW() < 0.01 && s.pump.getCoolerDutyMW() == 0, "the vessel loses no heat: " + s.pump.getHeatLossMW());
        h.assertTrue(s.demin.bed().ionLoadG() > 1, "it holds dissolved impurity: " + s.demin.bed().ionLoadG());
        h.assertTrue(s.demin.bed().crudLoadG() > 0.1 && s.demin.bed().activityBq() > 0, "and radioactive crud: " + s.demin.bed().crudLoadG());
        h.assertTrue(kappa(s) < kappa(control) - 0.05, "the conductivity ends below that of the plant with no cleanup: " + kappa(control) + " vs " + kappa(s));
        h.assertTrue(s.pump.connectionStatus().contains("circuit complete"), s.pump.connectionStatus());
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void eachExchangerIsOptionalAndTheHeatLossFollowsWhatTheLoopHas(GameTestHelper h) throws Exception {
        var both = variant(h.getLevel(), 140_400, true, true, false);
        var regenOnly = variant(h.getLevel(), 140_800, true, false, false);
        var coolerOnly = variant(h.getLevel(), 141_200, false, true, false);
        for (var s : new Skid[]{both, regenOnly, coolerOnly}) {
            s.core().getPressureVessel().restorePressurePsig(1000);
            s.start();
            s.tick(400);
            h.assertTrue(s.pump.circuitValid() && s.pump.getFlowKgPerS() > 20, "a valid loop: " + s.pump.connectionStatus());
        }
        double vesselC = both.core().getCoolantTemperatureC();
        h.assertTrue(both.pump.getHeatLossMW() > 2 && both.pump.getDemineraliserC() < 60, "both: the regenerated, cooled loop of before: "
                + both.pump.getHeatLossMW() + " MW, " + both.pump.getDemineraliserC() + " C");
        h.assertTrue(regenOnly.pump.getHeatLossMW() < 0.01, "a regenerator with no cooler has nothing to recover and loses nothing: " + regenOnly.pump.getHeatLossMW());
        near(h, regenOnly.pump.getDemineraliserC(), vesselC, 3.0, "and the nanofilter is on hot water");
        h.assertTrue(coolerOnly.pump.getHeatLossMW() > 0.5, "a cooler with no regenerator takes its duty from the vessel: " + coolerOnly.pump.getHeatLossMW());
        near(h, coolerOnly.pump.getCoolerDutyMW(), coolerOnly.pump.getHeatLossMW(), 0.01 * coolerOnly.pump.getHeatLossMW() + 1e-3, "all of it to the cooling water");
        h.assertTrue(coolerOnly.pump.getDemineraliserC() < vesselC - 20, "the cooler cools what it can: " + coolerOnly.pump.getDemineraliserC());
        h.assertTrue(coolerOnly.pump.getHeatLossMW() > both.pump.getHeatLossMW(), "without the regenerator the loss is larger");
        // A regenerator used on its hot side must be used on its cold side: a nanofilter outlet that bypasses it is no circuit.
        var broken = skid(h, 141_600, false, false);
        broken.l.removeBlock(broken.at(6, 6, -6), false);
        broken.start();
        broken.tick(60);
        h.assertTrue(!broken.pump.circuitValid() && broken.pump.getFlowKgPerS() == 0, "no cold-side return, no circuit: " + broken.pump.connectionStatus());
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 4000)
    public static void blowdownFromAHotLoopFillsTheTankWithHotWater(GameTestHelper h) throws Exception {
        var s = variant(h.getLevel(), 142_000, false, false, true);
        s.core().getPressureVessel().restorePressurePsig(1000);
        double vesselC = s.core().getCoolantTemperatureC();
        openValve(s.l, s.rejectValve);
        s.start();
        s.tick(800);
        h.assertTrue(s.pump.circuitValid(), s.pump.connectionStatus());
        h.assertTrue(s.pump.getRejectKgPerS() > 5, "the reject takes a share: " + s.pump.getRejectKgPerS());
        h.assertTrue(s.cst.storedKg() > 100, "the tank gained water: " + s.cst.storedKg());
        h.assertTrue(s.cst.temperatureC() > vesselC - 15, "at the temperature the water had at the tee (the tank does not flash it): " + s.cst.temperatureC() + " of " + vesselC);
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void aLoadedNanofilterRaisesItsPressureDropAndLowersTheLoopFlow(GameTestHelper h) throws Exception {
        var s = skid(h, 142_400, false, false);
        s.start();
        s.tick(300);
        double flowNew = s.pump.getFlowKgPerS();
        double dpNew = s.demin.bed().pressureDropPsi(s.pump.getDemineraliserKgPerS());
        h.assertTrue(flowNew > 50, "a new element, a free loop: " + flowNew);
        h.assertTrue(dpNew < 10, "a new element costs little: " + dpNew);
        s.demin.bed().fromArray(new double[]{1, 0, FilterDemineralizer.CRUD_CAPACITY_G * 0.95, 1.0e13, 0});
        s.tick(300);
        double flowLoaded = s.pump.getFlowKgPerS();
        h.assertTrue(flowLoaded < 0.9 * flowNew, "a crud-loaded element lowers the flow through the pump curve: " + flowNew + " -> " + flowLoaded);
        h.assertTrue(flowLoaded > 0.6 * flowNew, "without stopping the loop: " + flowLoaded);
        h.assertTrue(s.pump.getFilterPressureDropPsi() > 4 * dpNew, "the pressure drop rose: " + dpNew + " -> " + s.pump.getFilterPressureDropPsi());
        boolean line = false;
        for (var l : s.demin.readout().lines()) if (l.label().equals("Pressure drop") && Double.parseDouble(l.value()) > 30) line = true;
        h.assertTrue(line, "and the screen shows it");
        // Ions load no cake: an element full of dissolved impurity but clean of crud costs no more pressure.
        s.demin.bed().fromArray(new double[]{1, 60, 0, 0, 0});
        s.tick(300);
        near(h, s.pump.getFlowKgPerS(), flowNew, 0.04 * flowNew, "a clean-of-crud element lets the flow come back");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 3000)
    public static void aSpentNanofilterElementIsRadioactiveWasteAndTheNewItemReplacesIt(GameTestHelper h) throws Exception {
        var s = variant(h.getLevel(), 142_800, false, false, false);
        s.core().getPressureVessel().restorePressurePsig(1000);
        s.core().getChemistry().fromArray(new double[]{s.core().getChemistry().getIonicG(), 60, 60 * 1e12 * 0.5, 0, 0});
        dirty(s, 40);
        s.start();
        s.tick(600);
        var held = s.demin.bed().toArray();
        h.assertTrue(held[2] > 0.1 && held[3] > 0, "the hot element caught crud and its activity");
        var around = Vec3.atCenterOf(s.demin.getBlockPos().east(2));
        h.assertTrue(RadiationSources.doseRateMsvPerHour(s.l, around) > 0, "a gamma source in service, read by the survey meter");
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        var item = new ItemStack(BwrItems.DEMINERALIZER_RESIN.get(), 1);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
        s.demin.changeBed(player, item);
        h.assertTrue(item.getCount() == 0, "the Stainless Steel Nanofilter Element is used up");
        h.assertTrue(s.demin.bed().exhaustion() == 0 && s.demin.bed().crudLoadG() == 0, "a new element");
        ItemStack spent = ItemStack.EMPTY;
        for (var stack : player.getInventory().items) if (stack.is(BwrItems.SPENT_RESIN.get())) spent = stack;
        h.assertTrue(!spent.isEmpty() && SpentResinItem.activityBq(spent) > 0, "the old element came out as a radioactive Spent Nanofilter Element");
        near(h, SpentResinItem.activityBq(spent), held[3], 1e-6 * held[3], "carrying the activity it held");
        h.assertTrue(SpentResinItem.doseRateOf(player, player.position().add(1, 1, 0)) > 0, "which the survey meter reads on its carrier");
        // A new element is not changed for another: the item stays.
        var again = new ItemStack(BwrItems.DEMINERALIZER_RESIN.get(), 1);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, again);
        s.demin.changeBed(player, again);
        h.assertTrue(again.getCount() == 1, "a new element is left alone");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrrwcu", timeoutTicks = 800)
    public static void aSavedResinBedLoadsAsANanofilterElementWithTheSameLoadingAndALoadedOneRoundTrips(GameTestHelper h) throws Exception {
        var s = skid(h, 143_200, false, false);
        // What the alpha.54 resin filter-demineraliser wrote: five numbers, the last the resin's heat damage.
        var tag = s.demin.saveWithFullMetadata(s.l.registryAccess());
        dev.bwr.mod.reactor.ReactorStateNbt.putDoubles(tag, "Bed", new double[]{1, 62.5, 91.0, 2.5e13, 0.4});
        var old = new RwcuDeminBlockEntity(s.demin.getBlockPos(), s.demin.getBlockState());
        old.loadWithComponents(tag, s.l.registryAccess());
        near(h, old.bed().ionLoadG(), 62.5, 0, "ions kept");
        near(h, old.bed().crudLoadG(), 91.0, 0, "crud kept");
        near(h, old.bed().activityBq(), 2.5e13, 0, "activity kept");
        near(h, old.bed().ionCapacityLeftG(), 37.5, 1e-9, "the old heat damage is not charged to the new element");
        h.assertTrue(old.bed().pressureDropAtRatedPsi() > FilterDemineralizer.DP_CLEAN_PSI + 30, "and its crud shows as pressure drop: " + old.bed().pressureDropAtRatedPsi());
        var resaved = old.saveWithFullMetadata(s.l.registryAccess());
        h.assertTrue(resaved.contains("Bed") && dev.bwr.mod.reactor.ReactorStateNbt.getDoubles(resaved, "Bed")[4] == 0, "it writes the same key with no damage");
        // A used element in a running loop round-trips with its pressure drop.
        s.demin.bed().fromArray(new double[]{1, 20, 120, 3.0e12, 0});
        double dp = s.demin.bed().pressureDropAtRatedPsi();
        var copy = new RwcuDeminBlockEntity(s.demin.getBlockPos(), s.demin.getBlockState());
        copy.loadWithComponents(s.demin.saveWithFullMetadata(s.l.registryAccess()), s.l.registryAccess());
        near(h, copy.bed().pressureDropAtRatedPsi(), dp, 1e-12, "the loaded element round-trips");
        h.succeed();
    }
}
