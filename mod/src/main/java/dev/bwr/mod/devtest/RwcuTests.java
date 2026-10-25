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
 * filter-demineraliser) piped to the vessel and to the feedwater line, the reject path and the bottom drain, the radiation,
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
    public static void aHotDeminineraliserDamagesTheResinAndNothingStopsIt(GameTestHelper h) throws Exception {
        var s = skid(h, 131_200, false, false);
        s.core().getPressureVessel().restorePressurePsig(1000);
        double vesselC = s.core().getCoolantTemperatureC();
        h.assertTrue(vesselC > 250, "a hot vessel: " + vesselC);
        // Load some impurity into the bed first, with cold water.
        s.core().getPressureVessel().restorePressurePsig(5);
        dirty(s, 40);
        s.start();
        s.tick(600);
        double held = s.demin.bed().ionLoadG();
        h.assertTrue(held > 1 && s.demin.bed().damage() == 0, "a cooled bed in service holds impurity and is undamaged: " + held);
        // Now hot, and the cooler loses its cooling water.
        s.core().getPressureVessel().restorePressurePsig(1000);
        s.coolingWater = false;
        s.cooler.plant().restore(0, 0, 0, 0);
        double before = kappa(s);
        s.tick(200);
        h.assertTrue(s.pump.getFlowKgPerS() > 20, "the pump kept running; nothing isolated it: " + s.pump.getFlowKgPerS());
        h.assertTrue(s.pump.getDemineraliserC() > 150, "the demineraliser sees hot water: " + s.pump.getDemineraliserC());
        h.assertTrue(s.demin.bed().damage() > 0.05, "the resin is damaged: " + s.demin.bed().damage());
        h.assertTrue(s.demin.overTemperature(), "and reads over temperature");
        h.assertTrue(s.pump.connectionStatus().contains("140 F"), "the pump status says so: " + s.pump.connectionStatus());
        h.assertTrue(s.demin.bed().ionLoadG() < held, "it released what it held: " + held + " -> " + s.demin.bed().ionLoadG());
        h.assertTrue(s.demin.readout().headlineStatus() == 3, "an alarm line on the readout");
        // A properly cooled bed at the same vessel temperature takes no damage.
        s.coolingWater = true;
        var cold = skid(h, 131_600, false, false);
        cold.core().getPressureVessel().restorePressurePsig(1000);
        cold.start();
        cold.tick(200);
        h.assertTrue(cold.demin.bed().damage() == 0, "with cooling water the same bed is untouched: " + cold.demin.bed().damage());
        h.assertTrue(cold.pump.getDemineraliserC() < 60, "at " + cold.pump.getDemineraliserC());
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
}
