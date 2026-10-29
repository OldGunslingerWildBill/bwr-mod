package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.piping.OilLineNetwork.Role;
import dev.bwr.mod.power.LubeOilValveBlock;
import dev.bwr.mod.power.LubeOilValveBlockEntity;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The lube oil piping (TURBINE-AUX.md, Piping): the Lube Oil Pipe as a network of its own, the oil ports of the oil equipment, the Lube
 * Oil Valve, a tank in another room feeding a train, and the old rule (a tank bolted on the train feeds the whole train) unchanged.
 * Namespace {@code bwroil}, on the generator fixture of {@link TurbineAuxTests}.
 *
 * <p>The layout most tests use (the generator faces east, root is its controller): its supply port is on the north flank and its drain
 * port on the south flank; the Main Oil Tank stands "in another room" at root+(0,0,8) facing east, so its discharge (front) is on the
 * east and its return inlet (back) on the west. The supply run goes round the east end, the drain run down the west side.
 */
@GameTestHolder("bwroil")
@PrefixGameTestTemplate(false)
public final class OilPipingTests {
    private OilPipingTests() {}

    @FunctionalInterface
    private interface Body {
        void run(TurbineAuxTests.Fx f) throws Exception;
    }

    private static void run(GameTestHelper h, int x, Body body) {
        var l = h.getLevel();
        var root = new BlockPos(x, 245, 79000);
        EntityLookupProbe.runWhenLive(h, TurbineAuxTests.Fx.bounds(root), () -> {
            long time = l.getGameTime();
            TurbineAuxTests.Fx f = null;
            try {
                f = new TurbineAuxTests.Fx(l, root);
                body.run(f);
                h.succeed();
            } finally {
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    // ------------------------------------------------------------------ layout helpers

    /** Lube Oil Pipe along the straight segments between the points (inclusive), arms resolved against what is there. */
    private static void pipes(TurbineAuxTests.Fx f, BlockPos... points) {
        for (int i = 0; i + 1 < points.length; i++) {
            for (var p : BlockPos.betweenClosed(points[i], points[i + 1]))
                f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().defaultBlockState(), 3);
        }
        for (var p : points) f.l.setBlock(p, BwrBlocks.LUBE_OIL_PIPE.get().stateWithConnections(f.l, p), 3);
    }

    private static BlockPos at(TurbineAuxTests.Fx f, int dx, int dy, int dz) { return f.root.offset(dx, dy, dz); }

    /** The Main Oil Tank in the other room, east-facing: discharge east, return west. A full tank, the AC pump switched on and fed. */
    private static TurbineAuxBlockEntity roomTank(TurbineAuxTests.Fx f, int litres) {
        f.l.setBlock(at(f, 0, 0, 8), BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST), 3);
        TurbineAuxBlock.attach(f.l, at(f, 0, 0, 8));
        var tank = (TurbineAuxBlockEntity) f.l.getBlockEntity(at(f, 0, 0, 8));
        tank.addOil(litres);
        tank.setAcPump(true);
        tank.setDcPump(true);
        return tank;
    }

    /** Supply: the generator's north flank, round the east end, to the tank's discharge. The pipe cell where the valve may go is (3, 0, -3). */
    private static void supplyRun(TurbineAuxTests.Fx f) {
        pipes(f, at(f, 0, 0, -3), at(f, 6, 0, -3), at(f, 6, 0, 8), at(f, 1, 0, 8));
    }

    /** Drain: the generator's south flank, along the west side, to the tank's return. */
    private static void drainRun(TurbineAuxTests.Fx f) {
        pipes(f, at(f, 0, 0, 3), at(f, -1, 0, 3), at(f, -1, 0, 8));
    }

    private static void feed(TurbineAuxBlockEntity tank) {
        tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false);
        tank.dcEnergy.receiveEnergy(TurbineAuxBlockEntity.DC_CAPACITY_FE, false);
    }

    private static void valveAt(TurbineAuxTests.Fx f, BlockPos pos, boolean open) {
        f.l.setBlock(pos, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.EAST)
                .setValue(LubeOilValveBlock.OPEN, open), 3);
    }

    private static LubeOilValveBlockEntity valve(TurbineAuxTests.Fx f, BlockPos pos) { return (LubeOilValveBlockEntity) f.l.getBlockEntity(pos); }

    // ------------------------------------------------------------------ ports

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 100)
    public static void everyPieceOfOilEquipmentHasItsOwnOilPorts(GameTestHelper h) {
        // The Main Oil Tank: four oil ports on four faces, none on its two cooling water flanges.
        var tankState = BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST);
        var tank = BwrBlocks.MAIN_OIL_TANK.get();
        h.assertTrue(tank.oilPortAt(tankState, Direction.EAST) == Role.TANK_DISCHARGE, "discharge on the front");
        h.assertTrue(tank.oilPortAt(tankState, Direction.WEST) == Role.TANK_RETURN, "return on the back");
        h.assertTrue(tank.oilPortAt(tankState, Direction.DOWN) == Role.TANK_SUCTION, "suction below");
        h.assertTrue(tank.oilPortAt(tankState, Direction.UP) == Role.TANK_FILL, "fill on top");
        h.assertTrue(tank.oilPortAt(tankState, tank.coldInFace(tankState)) == null && tank.oilPortAt(tankState, tank.hotOutFace(tankState)) == null,
                "the cooling water flanges carry no oil");
        for (Direction d : Direction.values())
            h.assertTrue(!(tank.acceptsWaterLineOn(tankState, d) && tank.oilPortAt(tankState, d) != null), "no face is both a water flange and an oil port: " + d);
        // The shaft pump: suction below, discharge on top. The cooling skid has none. The housing is a conduit with an inlet and an outlet.
        var pumpState = BwrBlocks.SHAFT_OIL_PUMP.get().defaultBlockState();
        h.assertTrue(BwrBlocks.SHAFT_OIL_PUMP.get().oilPortAt(pumpState, Direction.DOWN) == Role.PUMP_SUCTION
                && BwrBlocks.SHAFT_OIL_PUMP.get().oilPortAt(pumpState, Direction.UP) == Role.PUMP_DISCHARGE, "shaft pump suction below, discharge on top");
        for (Direction d : Direction.values())
            h.assertTrue(BwrBlocks.GENERATOR_COOLING_SKID.get().oilPortAt(BwrBlocks.GENERATOR_COOLING_SKID.get().defaultBlockState(), d) == null, "the cooling skid has no oil port");
        var housing = BwrBlocks.OIL_FILTER_HOUSING.get();
        var hs = housing.defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST);
        h.assertTrue(housing.acceptsOilLineOn(hs, Direction.EAST) && housing.acceptsOilLineOn(hs, Direction.WEST) && !housing.acceptsOilLineOn(hs, Direction.UP),
                "the housing takes a pipe on its inlet and outlet faces only");
        h.assertTrue(OilLineNetwork.roleAt(hs, Direction.EAST) == null, "and has no port role: it is a conduit");
        // Every kind of turbine section: exactly one supply and one drain port, on the two flanks at floor level in the middle of the casing.
        for (PowerModuleBlock b : new PowerModuleBlock[] {BwrBlocks.HP_TURBINE.get(), BwrBlocks.LP_TURBINE.get(), BwrBlocks.NUCLEAR_GENERATOR.get()}) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var s = b.placementState().setValue(PowerModuleBlock.FACING, facing);
                int supply = 0, drain = 0;
                for (int cell = 0; cell < b.cellCount(s); cell++) {
                    var cs = s.setValue(PowerModuleBlock.CELL, cell);
                    for (Direction d : Direction.values()) {
                        var role = b.oilPortAt(cs, d);
                        if (role == Role.SECTION_SUPPLY) { supply++; h.assertTrue(d == b.oilFace(s, true), b + " supply face"); }
                        else if (role == Role.SECTION_DRAIN) { drain++; h.assertTrue(d == b.oilFace(s, false), b + " drain face"); }
                        else h.assertTrue(role == null, "no other role on a section");
                    }
                }
                h.assertTrue(supply == 1 && drain == 1, b + " facing " + facing + ": one supply and one drain port, found " + supply + "/" + drain);
                h.assertTrue(b.oilFace(s, true) == b.oilFace(s, false).getOpposite(), "on opposite flanks");
                var root = BlockPos.ZERO;
                for (int cell = 0; cell < b.cellCount(s); cell++) {
                    var pos = root.offset(dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(b.cellOffset(s, cell), facing));
                    var cs = s.setValue(PowerModuleBlock.CELL, cell);
                    if (b.oilPortAt(cs, b.oilFace(s, true)) == Role.SECTION_SUPPLY) h.assertTrue(pos.equals(b.oilCell(root, s, true)), b + " oilCell is the supply cell");
                    if (b.oilPortAt(cs, b.oilFace(s, false)) == Role.SECTION_DRAIN) h.assertTrue(pos.equals(b.oilCell(root, s, false)), b + " oilCell is the drain cell");
                }
            }
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ a piped tank in another room

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void aPipedTankInAnotherRoomFeedsATrain(GameTestHelper h) {
        run(h, 84000, f -> {
            var tank = roomTank(f, 10_000);
            f.gen.rpm = 1500;
            f.tick(f.gen, 40, () -> feed(tank));
            h.assertTrue(tank.module() == null && f.gen.auxEntities().isEmpty(), "the tank is bolted on nothing: it feeds the train only by its pipes");
            h.assertTrue(f.gen.aux().oilFilm == 0 && !f.gen.aux().oilFed && !f.gen.aux().lubeGoverned, "with no pipe the train has no oil system at all, as before: film " + f.gen.aux().oilFilm);
            supplyRun(f);
            drainRun(f);
            h.assertTrue(OilLineNetwork.survey(f.l, tank.getBlockPos(), Direction.EAST).joins(Role.SECTION_SUPPLY), "the discharge run joins the generator's supply");
            h.assertTrue(OilLineNetwork.survey(f.l, tank.getBlockPos(), Direction.WEST).joins(Role.SECTION_DRAIN), "the return run joins the generator's drain");
            f.gen.bearingC = 25;
            f.tick(f.gen, 100, () -> feed(tank));
            var aux = f.gen.aux();
            h.assertTrue(aux.lubeGoverned && aux.oilFed && aux.oilPiped && aux.oilSupplyPiped && aux.oilDrainPiped, "the piped oil governs the train and reaches the generator");
            double flow = TurbineAux.AC_PUMP_FLOW + TurbineAux.DC_PUMP_FLOW;
            h.assertTrue(Math.abs(aux.oilFilm - TurbineAux.film(flow, f.gen.rpm)) < 1e-6 && Math.abs(aux.oilPsig - TurbineAux.oilPsig(flow)) < 1e-6,
                    "the AC and DC pumps' delivery arrives as the generator's film and header pressure: film " + aux.oilFilm + ", " + aux.oilPsig + " psig");
            h.assertTrue(aux.oilTank && aux.oilC > 24 && !Double.isNaN(aux.oilLevel), "the train reads the tank it is piped to");
            h.assertTrue(tank.acRunning() && tank.psig() > 15, "and the tank runs its pump against that header");
            h.assertTrue(f.gen.bearingC < 60, "the bearings are cooled by it: " + f.gen.bearingC);
            var gs = SignalProviders.of(f.gen);
            h.assertTrue(gs.read("oil_fed").getAsDouble() == 1 && gs.read("oil_supply_piped").getAsDouble() == 1 && gs.read("oil_drain_piped").getAsDouble() == 1,
                    "the signals say it is fed and piped");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void aSectionWhoseDrainIsNotPipedBackIsNotFed(GameTestHelper h) {
        run(h, 84100, f -> {
            var tank = roomTank(f, 10_000);
            f.gen.rpm = 1500;
            supplyRun(f);
            f.tick(f.gen, 60, () -> feed(tank));
            var aux = f.gen.aux();
            h.assertTrue(aux.oilPiped && aux.oilSupplyPiped && !aux.oilDrainPiped && !aux.oilFed && aux.oilFilm == 0,
                    "oil out by the supply with no way back: the section is not fed (film " + aux.oilFilm + ")");
            drainRun(f);
            f.tick(f.gen, 20, () -> feed(tank));
            h.assertTrue(f.gen.aux().oilFed && f.gen.aux().oilFilm > 0.9, "the drain run closes the loop and the oil arrives");
        });
    }

    // ------------------------------------------------------------------ the valve

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void aClosedOilValveStarvesTheBearingsAndTheyHeatUpByTheExistingLaws(GameTestHelper h) {
        run(h, 84200, f -> {
            var tank = roomTank(f, 10_000);
            f.gen.rpm = 1500;
            supplyRun(f);
            drainRun(f);
            var valvePos = at(f, 3, 0, -3);
            valveAt(f, valvePos, true);
            f.tick(f.gen, 200, () -> feed(tank));
            double opened = f.gen.bearingC;
            h.assertTrue(f.gen.aux().oilFed && f.gen.aux().oilFilm > 0.9 && opened < 60, "with the valve open the bearings are cooled: " + opened);
            h.assertTrue(f.gen.damage() < 0.001, "and take no damage");
            // Shut by hand: a right-click, the way a player does it.
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var hit = new BlockHitResult(Vec3.atCenterOf(valvePos), Direction.UP, valvePos, false);
            f.l.getBlockState(valvePos).useWithoutItem(f.l, player, hit);
            h.assertTrue(!f.l.getBlockState(valvePos).getValue(LubeOilValveBlock.OPEN), "a click turned the valve shut");
            f.tick(f.gen, 600, () -> feed(tank));
            var aux = f.gen.aux();
            h.assertTrue(!aux.oilFed && aux.oilFilm == 0 && aux.oilPsig == 0, "a shut valve stops the oil: film " + aux.oilFilm + ", " + aux.oilPsig + " psig");
            h.assertTrue(aux.oilPiped && aux.oilSupplyPiped, "the section is still on its header (shutting a valve never puts it on the integral header)");
            h.assertTrue(f.gen.bearingC > 115 && f.gen.damage() > 0.01, "the bearings heat by the existing law and the shaft takes damage: "
                    + f.gen.bearingC + " C, damage " + f.gen.damage());
            h.assertTrue(f.gen.aux().bearingAlarm && aux.oilPressureAlarm, "the alarms read it (nothing trips)");
            // Opened again, the oil is back.
            f.l.getBlockState(valvePos).useWithoutItem(f.l, player, hit);
            f.tick(f.gen, 40, () -> feed(tank));
            h.assertTrue(f.gen.aux().oilFed && f.gen.aux().oilFilm > 0.9, "opened again the oil is back");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void theValveIsOperatedByHandByRedstoneAndBySignals(GameTestHelper h) {
        run(h, 84300, f -> {
            var pos = at(f, 3, 0, -3);
            valveAt(f, pos, true);
            var v = valve(f, pos);
            h.assertTrue(v.isOpen() && v.limitOpen() && !v.limitClosed() && v.position() == 1, "a new valve is open, its open limit switch made");
            // Redstone: only a change counts. A new valve beside no power stays open; power then no power shuts it; power opens it.
            var rs = pos.north();
            f.l.setBlock(rs, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            h.assertTrue(v.isOpen(), "power on an open valve leaves it open");
            f.l.setBlock(rs, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(!v.isOpen() && v.limitClosed() && !v.limitOpen() && v.position() == 0, "losing the power shuts it, its closed limit switch made");
            f.l.setBlock(rs, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            h.assertTrue(v.isOpen(), "power opens it");
            f.l.setBlock(rs, Blocks.AIR.defaultBlockState(), 3);
            // Signals: both limit switches, the position, and the command.
            var s = SignalProviders.at(f.l, pos);
            h.assertTrue(s != null, "the valve has a signal provider");
            h.assertTrue(s.read("open").getAsDouble() == 0 && s.read("closed").getAsDouble() == 1 && s.read("position").getAsDouble() == 0, "shut reads closed");
            h.assertTrue(s.command("open", 1) && v.isOpen(), "the open command opens it");
            h.assertTrue(s.read("open").getAsDouble() == 1 && s.read("closed").getAsDouble() == 0 && s.read("position").getAsDouble() == 1, "open reads open");
            h.assertTrue(s.command("open", 0.2) && !v.isOpen(), "a command under one half shuts it");
            h.assertTrue(s.commands().stream().anyMatch(c -> c.key().equals("open")), "and the command is listed");
            // Saves: only a powered reading is a key.
            var tag = v.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(tag.getAllKeys().isEmpty(), "an unpowered valve saves no key: " + tag.getAllKeys());
            f.l.setBlock(rs, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            h.assertTrue(v.saveWithoutMetadata(f.l.registryAccess()).contains("Powered"), "a powered one saves that");
            f.l.setBlock(rs, Blocks.AIR.defaultBlockState(), 3);
            // A computer face (when CC is installed).
            if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) OilPipingCcChecks.valve(h, f.l, pos);
        });
    }

    // ------------------------------------------------------------------ the filter in-line

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void theFilterWorksInLineInTheDischargeLine(GameTestHelper h) {
        run(h, 84400, f -> {
            var tank = roomTank(f, 10_000);
            f.gen.rpm = 1500;
            supplyRun(f);
            drainRun(f);
            f.tick(f.gen, 60, () -> feed(tank));
            double before = tank.oilLitres();
            f.tick(f.gen, 600, () -> feed(tank));
            double bare = before - tank.oilLitres();
            h.assertTrue(bare > 20 && tank.oilFilter() == null, "with no housing the piped tank loses oil at the old rate: " + bare);
            // The housing in the discharge line: inlet toward the tank, outlet toward the header.
            var housingPos = at(f, 3, 0, 8); // two cells down the line from the tank: not bolted on it
            f.l.setBlock(housingPos, BwrBlocks.OIL_FILTER_HOUSING.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST), 3);
            var housing = (TurbineAuxBlockEntity) f.l.getBlockEntity(housingPos);
            h.assertTrue(f.l.getBlockState(housingPos).getBlock() == BwrBlocks.OIL_FILTER_HOUSING.get() && housing.module() == null, "the housing stands in the line, bolted on nothing");
            h.assertTrue(housing.tank() == tank && tank.oilFilter() == housing, "the housing in the line is the tank's filter and the tank is the housing's");
            h.assertTrue(f.gen.aux().oilFed, "oil still passes through the housing to the header");
            var cartridge = new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get());
            housing.cartridgeSlot().setStackInSlot(0, cartridge);
            before = tank.oilLitres();
            f.tick(f.gen, 600, () -> feed(tank));
            double filtered = before - tank.oilLitres();
            h.assertTrue(filtered > 0 && filtered < bare * 0.13, "through a clean cartridge in the line the loss is a tenth: " + filtered + " against " + bare);
            h.assertTrue(housing.filterLoad() > 0, "the cartridge loaded up by what flowed");
            h.assertTrue(f.gen.aux().filterHousing && f.gen.aux().oilFed, "the train reads the housing");
            // Backwards in the line the housing is a plain conduit (the oil enters by its outlet): it filters nothing.
            f.l.setBlock(housingPos, BwrBlocks.OIL_FILTER_HOUSING.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.WEST), 3);
            var backwards = (TurbineAuxBlockEntity) f.l.getBlockEntity(housingPos);
            backwards.cartridgeSlot().setStackInSlot(0, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()));
            h.assertTrue(tank.oilFilter() == null && f.gen.aux().oilFed, "a housing put in backwards filters nothing but still passes the oil");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void aHousingBoltedOnTheTankStillWorksAsBefore(GameTestHelper h) {
        run(h, 84450, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            var housing = f.skid(BwrBlocks.OIL_FILTER_HOUSING.get(), tank.getBlockPos().relative(Direction.SOUTH), Direction.SOUTH);
            housing.cartridgeSlot().setStackInSlot(0, new ItemStack(BwrItems.OIL_FILTER_CARTRIDGE.get()));
            f.tick(f.gen, 100);
            h.assertTrue(housing.tank() == tank && tank.oilFilter() == housing, "direct contact still decides the housing's tank");
            h.assertTrue(housing.filterLoad() > 0 && f.gen.aux().filterHousing, "and filters as before");
        });
    }

    // ------------------------------------------------------------------ the shaft pump

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void theShaftPumpWithNoSuctionLineDeliversNothing(GameTestHelper h) {
        run(h, 84500, f -> {
            var pump = f.shaftPump();
            var pumpPos = pump.getBlockPos();
            // A tank elsewhere, not piped to the pump: nothing is drawn.
            var tankPos = pumpPos.offset(0, -1, 4);
            f.l.setBlock(tankPos, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST), 3);
            TurbineAuxBlock.attach(f.l, tankPos);
            var tank = (TurbineAuxBlockEntity) f.l.getBlockEntity(tankPos);
            tank.addOil(10_000);
            h.assertTrue(tank.module() == null, "the tank is not bolted on the train");
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tick(f.gen, 100);
            h.assertTrue(f.gen.aux().oilFilm == 0 && f.gen.aux().oilPsig == 0 && f.gen.bearingC > 30,
                    "a shaft pump with no suction line and no tank on its train delivers nothing: film " + f.gen.aux().oilFilm);
            h.assertTrue(!pump.suctionPiped(), "its suction is not piped");
            // The suction line: the pump's suction (below it) to the tank's suction outlet (below it).
            pipes(f, pumpPos.below(), pumpPos.offset(0, -2, 0), pumpPos.offset(0, -2, 4), tankPos.below());
            h.assertTrue(pump.suctionPiped() && pump.oilSurvey(Role.PUMP_SUCTION).reaches(Role.TANK_SUCTION, tankPos), "the suction run reaches the tank");
            f.gen.bearingC = 25;
            f.tick(f.gen, 60);
            h.assertTrue(f.gen.aux().oilFilm > 0.99 && f.gen.aux().oilFed, "with the suction line the pump delivers at rated speed: film " + f.gen.aux().oilFilm);
            h.assertTrue(f.gen.aux().oilTank && tank.psig() > 20, "and the tank it draws from is the train's tank: " + tank.psig() + " psig");
            // The line is cut: the pump is dry again.
            f.l.setBlock(pumpPos.offset(0, -2, 2), Blocks.AIR.defaultBlockState(), 3);
            f.tick(f.gen, 5);
            h.assertTrue(f.gen.aux().oilFilm == 0 && !pump.oilSurvey(Role.PUMP_SUCTION).reaches(Role.TANK_SUCTION, tankPos), "a cut suction line delivers nothing");
            // A shut valve in it does too, and the pump is still on its suction pipe (it does not fall back to anything).
            f.l.setBlock(pumpPos.offset(0, -2, 2), BwrBlocks.LUBE_OIL_PIPE.get().defaultBlockState(), 3);
            f.tick(f.gen, 5);
            h.assertTrue(f.gen.aux().oilFilm > 0.99, "mended");
            f.l.setBlock(pumpPos.offset(0, -2, 2), BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.SOUTH)
                    .setValue(LubeOilValveBlock.OPEN, false), 3);
            f.tick(f.gen, 5);
            h.assertTrue(pump.suctionPiped() && f.gen.aux().oilFilm == 0, "a shut valve in the suction line starves it");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void theShaftPumpOnTheGeneratorWithItsTankOnTheTrainNeedsNoSuctionPipe(GameTestHelper h) {
        run(h, 84550, f -> {
            var tank = f.tank(10_000);
            var pump = f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tick(f.gen, 60);
            h.assertTrue(!pump.suctionPiped() && !pump.dischargePiped() && !tank.dischargePiped(), "nothing is piped");
            double expected = TurbineAux.shaftPumpFlow(1500) * TurbineAux.suction(tank.oilLitres());
            h.assertTrue(Math.abs(f.gen.aux().pumpFlow - expected) < 1e-9 && f.gen.aux().oilFilm > 0.99,
                    "the shaft pump delivers exactly as it always did with no pipe: " + f.gen.aux().pumpFlow + " against " + expected);
            h.assertTrue(f.gen.aux().oilFed && !f.gen.aux().oilPiped, "on the integral header");
        });
    }

    // ------------------------------------------------------------------ precedence and old trains

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void aTankBoltedOnTheTrainKeepsFeedingTheWholeTrainAsBeforeWithNoPipe(GameTestHelper h) {
        run(h, 84600, f -> {
            var hp = f.addHp();
            var tank = f.tank(10_000);
            f.gen.markAuxRequired();
            tank.setAcPump(true);
            hp.rpm = f.gen.rpm = 1500;
            f.tick(f.gen, 60, () -> feed(tank));
            double expected = TurbineAux.AC_PUMP_FLOW;
            for (var m : new PowerModuleBlockEntity[] {f.gen, hp}) {
                var aux = m.aux();
                h.assertTrue(aux.oilFed && !aux.oilPiped && Math.abs(aux.oilPsig - TurbineAux.oilPsig(expected)) < 1e-9
                                && Math.abs(aux.oilFilm - TurbineAux.film(expected, m.rpm)) < 1e-9,
                        "every section of the train gets the header's oil exactly as before: " + aux.oilPsig + " psig, film " + aux.oilFilm);
            }
            h.assertTrue(!tank.dischargePiped(), "the tank's discharge is not piped");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aBoltedTankWhoseDischargeIsPipedFeedsOnlyTheSectionsItsHeaderReaches(GameTestHelper h) {
        run(h, 84700, f -> {
            var hp = f.addHp();
            // The tank is bolted against the generator's drain cell, south flank, facing south: its return inlet (back) is on that drain
            // port, a direct connection with no pipe, and its discharge (front) is piped round to the generator's supply.
            var tankPos = at(f, 0, 0, 3);
            f.l.setBlock(tankPos, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.SOUTH), 3);
            TurbineAuxBlock.attach(f.l, tankPos);
            var tank = (TurbineAuxBlockEntity) f.l.getBlockEntity(tankPos);
            tank.addOil(10_000);
            tank.setAcPump(true);
            h.assertTrue(tank.module() == f.gen && !tank.dischargePiped(), "bolted on the train, nothing piped yet");
            hp.rpm = f.gen.rpm = 1500;
            f.gen.markAuxRequired();
            f.tick(f.gen, 40, () -> feed(tank));
            h.assertTrue(hp.aux().oilFed && f.gen.aux().oilFed && !f.gen.aux().oilPiped, "before any pipe the bolted tank feeds both sections");
            pipes(f, at(f, 0, 0, 4), at(f, 6, 0, 4), at(f, 6, 0, -3), at(f, 0, 0, -3));
            h.assertTrue(tank.dischargePiped(), "the discharge is piped to the generator's supply now");
            hp.bearingC = f.gen.bearingC = 25;
            f.tick(f.gen, 100, () -> feed(tank));
            h.assertTrue(f.gen.aux().oilFed && f.gen.aux().oilFilm > 0.6 && f.gen.aux().oilDrainPiped, "the generator is fed by its header and the direct return");
            h.assertTrue(!hp.aux().oilFed && hp.aux().oilFilm == 0 && hp.aux().oilPsig == 0,
                    "but the HP section, which the header does not reach, has no oil though the tank is bolted on its train: film " + hp.aux().oilFilm);
            h.assertTrue(hp.aux().oilPiped && !hp.aux().oilSupplyPiped, "and it reads that its own supply is not piped");
            f.tick(f.gen, 400, () -> feed(tank));
            h.assertTrue(hp.bearingC > 115 && f.gen.bearingC < 90, "its bearings heat by the existing law while the fed section's stay cool: HP " + hp.bearingC + ", generator " + f.gen.bearingC);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 400)
    public static void aHeaderReachingEverySectionFeedsEveryBearing(GameTestHelper h) {
        run(h, 84800, f -> {
            var hp = f.addHp();
            var tank = roomTank(f, 10_000);
            hp.rpm = f.gen.rpm = 1500;
            pipes(f, at(f, -9, 0, -3), at(f, 6, 0, -3), at(f, 6, 0, 8), at(f, 1, 0, 8));
            pipes(f, at(f, -9, 0, 3), at(f, 0, 0, 3));
            pipes(f, at(f, -1, 0, 3), at(f, -1, 0, 8));
            f.tick(f.gen, 100, () -> feed(tank));
            for (var m : new PowerModuleBlockEntity[] {hp, f.gen}) {
                h.assertTrue(m.aux().oilFed && m.aux().oilSupplyPiped && m.aux().oilDrainPiped && m.aux().oilFilm > 0.9, m.block() + " is fed");
                h.assertTrue(m.bearingC < 60, "and cool: " + m.bearingC);
            }
            // Cut the HP section's own branch of the supply header: only it is starved.
            f.l.setBlock(at(f, -5, 0, -3), Blocks.AIR.defaultBlockState(), 3);
            hp.bearingC = 25;
            f.tick(f.gen, 40, () -> feed(tank));
            h.assertTrue(!hp.aux().oilFed && hp.aux().oilFilm == 0 && f.gen.aux().oilFed, "a cut header starves the section beyond the cut and no other");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 200)
    public static void aTrainThatWasNeverPipedRunsExactlyAsItAlwaysDidAndSavesNothingNew(GameTestHelper h) {
        run(h, 84900, f -> {
            var tank = f.tank(10_000);
            var shaftPump = f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            tank.setAcPump(true);
            for (int i = 0; i < 40; i++) {
                feed(tank);
                f.tick(f.gen, 1);
            }
            double flow = TurbineAux.AC_PUMP_FLOW + TurbineAux.shaftPumpFlow(1500) * TurbineAux.suction(tank.oilLitres());
            h.assertTrue(f.gen.aux().pumpFlow == flow && f.gen.aux().oilPsig == TurbineAux.oilPsig(flow) && f.gen.aux().oilFilm == TurbineAux.film(flow, f.gen.rpm),
                    "the unpiped train's readings are the old laws' exactly: " + f.gen.aux().pumpFlow + " against " + flow);
            h.assertTrue(!f.gen.aux().oilPiped && !f.gen.aux().oilSupplyPiped && f.gen.aux().oilFed, "and it is not marked piped");
            var gs = SignalProviders.of(f.gen);
            h.assertTrue(gs.read("oil_supply_piped").isEmpty() && gs.read("oil_drain_piped").isEmpty() && gs.read("oil_fed").getAsDouble() == 1,
                    "the piping signals read no signal where nothing is piped");
            // No save key is new: every key of the three skids and the module was there before piping.
            var known = java.util.Set.of("Host", "AcOn", "DcOn", "StatorOn", "AcFe", "DcFe", "OilL", "OilC", "H2Kg", "CoolantC", "StatorFlow", "Cartridge", "ColdKg", "ColdH", "HotKg", "HotH");
            for (var be : new TurbineAuxBlockEntity[] {tank, shaftPump})
                for (String k : be.saveWithoutMetadata(f.l.registryAccess()).getAllKeys()) h.assertTrue(known.contains(k), "a new save key on a skid: " + k);
            for (String k : f.gen.saveWithoutMetadata(f.l.registryAccess()).getAllKeys())
                h.assertTrue(!k.toLowerCase().contains("oilline") && !k.toLowerCase().contains("pip"), "a piping key on the module: " + k);
        });
    }

    // ------------------------------------------------------------------ separate networks

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 100)
    public static void oilPipesNeverJoinWaterOrSteamNetworks(GameTestHelper h) {
        var l = h.getLevel();
        var base = new BlockPos(85000, 245, 79000);
        EntityLookupProbe.runWhenLive(h, new net.minecraft.world.phys.AABB(base.getX() - 12, base.getY() - 3, base.getZ() - 12, base.getX() + 12, base.getY() + 6, base.getZ() + 12), () -> {
            var cells = BlockPos.betweenClosed(base.offset(-6, -1, -6), base.offset(6, 3, 6));
            try {
                for (var p : cells) l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                // A row: water pipe, oil pipe, steam pipe, oil pipe, water pipe, side by side.
                var water = BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get();
                var steam = BwrBlocks.PRESSURISED_TUBE.get();
                var oil = BwrBlocks.LUBE_OIL_PIPE.get();
                var row = new net.minecraft.world.level.block.Block[] {water, oil, steam, oil, water};
                for (int i = 0; i < row.length; i++) l.setBlock(base.offset(i, 0, 0), row[i].defaultBlockState(), 3);
                for (int i = 0; i < row.length; i++) {
                    var p = base.offset(i, 0, 0);
                    l.setBlock(p, row[i] == water ? water.stateWithConnections(l, p) : row[i] == steam ? steam.stateWithConnections(l, p) : oil.stateWithConnections(l, p), 3);
                }
                for (int i = 0; i < row.length; i++) {
                    var p = base.offset(i, 0, 0);
                    var s = l.getBlockState(p);
                    h.assertTrue(row[i] == oil || !s.getValue(PipeBlock.EAST) && !s.getValue(PipeBlock.WEST), "a water or steam pipe grows no arm toward an oil pipe: " + i);
                    if (row[i] == oil) h.assertTrue(!s.getValue(PipeBlock.EAST) && !s.getValue(PipeBlock.WEST), "an oil pipe grows no arm toward a water or steam pipe: " + i);
                }
                h.assertTrue(!dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(oil.defaultBlockState(), Direction.EAST)
                        && !dev.bwr.mod.steam.SteamLineNetwork.acceptsLineOn(oil.defaultBlockState(), Direction.EAST), "neither line rule accepts an oil pipe");
                h.assertTrue(!OilLineNetwork.acceptsLineOn(water.defaultBlockState(), Direction.EAST) && !OilLineNetwork.acceptsLineOn(steam.defaultBlockState(), Direction.EAST),
                        "and the oil rule accepts neither");
                var waterGraph = dev.bwr.mod.piping.PipeTopology.get(l, base, null, false, false);
                var steamGraph = dev.bwr.mod.piping.PipeTopology.get(l, base.offset(2, 0, 0), null, true, false);
                h.assertTrue(!waterGraph.cells().contains(base.offset(1, 0, 0)) && !steamGraph.cells().contains(base.offset(1, 0, 0)) && !steamGraph.cells().contains(base.offset(3, 0, 0)),
                        "a water or steam survey never includes an oil pipe cell");
                // Oil pipes beside the water flanges of a Main Oil Tank and the machines that take water and steam do not join them.
                var tankPos = base.offset(0, 0, 4);
                l.setBlock(tankPos, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.NORTH), 3);
                var tank = BwrBlocks.MAIN_OIL_TANK.get();
                var ts = l.getBlockState(tankPos);
                l.setBlock(tankPos.relative(tank.coldInFace(ts)), oil.stateWithConnections(l, tankPos.relative(tank.coldInFace(ts))), 3);
                l.setBlock(tankPos.relative(tank.hotOutFace(ts)), oil.stateWithConnections(l, tankPos.relative(tank.hotOutFace(ts))), 3);
                for (Direction d : new Direction[] {tank.coldInFace(ts), tank.hotOutFace(ts)}) {
                    var pipe = l.getBlockState(tankPos.relative(d));
                    h.assertTrue(!pipe.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d.getOpposite())), "an oil pipe does not join the tank's cooling water flange " + d);
                }
                l.setBlock(tankPos.relative(tank.coldInFace(ts)), water.stateWithConnections(l, tankPos.relative(tank.coldInFace(ts))), 3);
                h.assertTrue(l.getBlockState(tankPos.relative(tank.coldInFace(ts))).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(tank.coldInFace(ts).getOpposite())),
                        "while the water pipe does join it (the cooling water flange)");
                l.setBlock(tankPos.relative(ts.getValue(TurbineAuxBlock.FACING)), water.stateWithConnections(l, tankPos.relative(ts.getValue(TurbineAuxBlock.FACING))), 3);
                h.assertTrue(!l.getBlockState(tankPos.relative(ts.getValue(TurbineAuxBlock.FACING))).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(ts.getValue(TurbineAuxBlock.FACING).getOpposite())),
                        "a water pipe does not join the tank's oil discharge port");
                h.succeed();
            } finally {
                for (var p : cells) l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
        });
    }

    // ------------------------------------------------------------------ surveys are kept and redone

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "oilbatch", timeoutTicks = 300)
    public static void aSurveyIsKeptWhileNothingChangesAndRedoneWhenABlockOnTheRunDoes(GameTestHelper h) {
        run(h, 85100, f -> {
            var tank = roomTank(f, 10_000);
            f.gen.rpm = 1500;
            supplyRun(f);
            drainRun(f);
            var valvePos = at(f, 3, 0, -3);
            valveAt(f, valvePos, true);
            f.tick(f.gen, 5, () -> feed(tank));
            long built = OilLineNetwork.buildCount();
            f.tick(f.gen, 200, () -> feed(tank));
            h.assertTrue(OilLineNetwork.buildCount() == built, "200 ticks with nothing changing build no survey: " + (OilLineNetwork.buildCount() - built));
            h.assertTrue(OilLineNetwork.cached(f.l, tank.getBlockPos(), Direction.EAST) != null, "the tank's discharge survey is held");
            valve(f, valvePos).setOpen(false);
            h.assertTrue(OilLineNetwork.cached(f.l, tank.getBlockPos(), Direction.EAST) == null, "toggling the valve invalidated it");
            f.tick(f.gen, 3, () -> feed(tank));
            h.assertTrue(OilLineNetwork.buildCount() - built <= 4 && !f.gen.aux().oilFed, "and it was built again, once for each port that asked: " + (OilLineNetwork.buildCount() - built));
            built = OilLineNetwork.buildCount();
            f.l.setBlock(at(f, 6, 0, 2), BwrBlocks.LUBE_OIL_PIPE.get().defaultBlockState(), 3); // a block beside the run
            f.l.setBlock(at(f, 6, 0, 2), Blocks.AIR.defaultBlockState(), 3);
            f.tick(f.gen, 3, () -> feed(tank));
            h.assertTrue(OilLineNetwork.buildCount() > built, "a pipe added or taken away beside the run redoes the surveys that ran by it");
        });
    }

    // ------------------------------------------------------------------ a run across an unloaded chunk

    @GameTest(template = "empty", templateNamespace = "bwroil", batch = "chunklink", timeoutTicks = 1500)
    public static void aRunAcrossAnUnloadedChunkIsNotConnectedAndNothingIsLoaded(GameTestHelper h) {
        ChunkLab lab = new ChunkLab(h.getLevel());
        LogWatch log = LogWatch.begin();
        ServerLevel level = h.getLevel();
        int x0 = ChunkLinkTests.X0;
        int z0 = ChunkLinkTests.Z0 + 20 * ChunkLinkTests.SLOT_Z;
        int y = ChunkLinkTests.Y;
        int chunks = 7, row = z0 >> 4;
        BlockPos tankPos = new BlockPos(x0 + 2, y, z0 + 7);
        BlockPos farPump = new BlockPos(x0 + 16 * 6 + 10, y, z0 + 7);
        int[] seen = new int[1];
        Script script = new Script(h, 1500, () -> { }, () -> {
            try {
                BlockPos lo = new BlockPos(x0 - 16, y - 3, z0 - 16), hi = new BlockPos(x0 + 16 * chunks + 16, y + 3, z0 + 16);
                for (BlockPos p : BlockPos.betweenClosed(lo, hi))
                    if (level.isLoaded(p) && !level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            } finally {
                log.stop_();
                lab.close();
            }
        });
        script.then(1, () -> {
            lab.forceRect((x0 >> 4) - 1, row, (x0 >> 4) + chunks, row);
            // A tank at one end and a shaft pump 100 blocks away: a Lube Oil Pipe run under them joins the tank's suction outlet to the pump's suction inlet.
            level.setBlock(tankPos, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.EAST), 3);
            level.setBlock(farPump, BwrBlocks.SHAFT_OIL_PUMP.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.WEST), 3);
            for (int x = tankPos.getX(); x <= farPump.getX(); x++)
                level.setBlock(new BlockPos(x, y - 1, z0 + 7), BwrBlocks.LUBE_OIL_PIPE.get().defaultBlockState(), 3);
        });
        script.then(5, () -> {
            var survey = OilLineNetwork.survey(level, tankPos, Direction.DOWN);
            h.assertTrue(survey.joins(Role.PUMP_SUCTION) && survey.reaches(Role.PUMP_SUCTION, farPump),
                    "whole, the 97 block run joins the tank's suction outlet to the pump's suction inlet: " + survey.topo);
            lab.syncLoads.clear();
            for (int c = 1; c <= 5; c++) lab.unforce((x0 >> 4) + c, row);
        });
        script.await(() -> "chunk 3 to unload: " + lab.describe((x0 >> 4) + 3, row), 400, () -> !lab.loaded((x0 >> 4) + 3, row));
        script.then(1, () -> {
            for (int i = 0; i < 20; i++) {
                var survey = OilLineNetwork.survey(level, tankPos, Direction.DOWN);
                if (!survey.joins(Role.PUMP_SUCTION)) seen[0]++;
            }
            h.assertTrue(seen[0] == 20, "with a chunk of the run unloaded the pump is not connected to the tank");
            if (!lab.syncLoads.isEmpty()) throw new AssertionError("our code loaded chunks by reading blocks in them: " + lab.syncLoads);
            if (!log.problems.isEmpty()) throw new AssertionError("our code logged warnings or errors: " + log.problems);
        });
        script.then(1, () -> {
            for (int c = 1; c <= 5; c++) lab.request((x0 >> 4) + c, row);
        });
        script.await("the run to join again once the chunks are back", 300, () -> OilLineNetwork.survey(level, tankPos, Direction.DOWN).joins(Role.PUMP_SUCTION));
        script.then(5, () -> {
            if (!lab.syncLoads.isEmpty()) throw new AssertionError("our code loaded chunks when the run came back: " + lab.syncLoads);
            if (!log.problems.isEmpty()) throw new AssertionError("our code logged warnings or errors: " + log.problems);
        });
        script.start();
    }

    // ------------------------------------------------------------------ recipes

    @GameTest(template = "empty", templateNamespace = "bwrmektier", timeoutTicks = 100)
    public static void thePipeAndTheValveHaveARecipeInBothInstalls(GameTestHelper h) {
        boolean mek = net.neoforged.fml.ModList.get().isLoaded("mekanism");
        var manager = h.getLevel().getServer().getRecipeManager();
        for (String name : new String[] {"lube_oil_pipe", "lube_oil_valve"}) {
            var live = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? name + "_mekanism" : name));
            var dead = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", mek ? name : name + "_mekanism"));
            h.assertTrue(live.isPresent() && dead.isEmpty(), name + ": the " + (mek ? "Mekanism" : "vanilla") + " recipe is the live one and the other is not loaded");
            RecipeHolder<?> holder = live.get();
            var result = holder.value().getResultItem(h.getLevel().registryAccess());
            h.assertTrue(result.getItem() == (name.endsWith("pipe") ? BwrItems.LUBE_OIL_PIPE.get() : BwrItems.LUBE_OIL_VALVE.get()), name + " makes itself");
            h.assertTrue(result.getCount() == (name.endsWith("pipe") ? 6 : 1), name + " count");
            boolean steel = holder.value().getIngredients().stream().anyMatch(i -> java.util.Arrays.stream(i.getItems()).anyMatch(s ->
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains("steel")));
            boolean iron = holder.value().getIngredients().stream().anyMatch(i -> java.util.Arrays.stream(i.getItems()).anyMatch(s ->
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("iron_")));
            h.assertTrue(mek ? steel && !iron : iron, name + (mek ? " asks for steel and no iron with Mekanism" : " is made of iron without it"));
        }
        h.succeed();
    }
}
