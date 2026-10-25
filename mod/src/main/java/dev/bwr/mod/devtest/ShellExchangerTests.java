package dev.bwr.mod.devtest;

import dev.bwr.core.pool.RhrHeatExchanger;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.cooling.CoolingBlock;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.RhrHeatExchangerBlock;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import dev.bwr.mod.water.WaterOutletChuteBlock;
import dev.bwr.mod.water.WaterOutletChuteBlockEntity;
import dev.bwr.mod.water.WaterOutletChuteBlockEntity.WaterState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The shell-and-tube RHR exchanger with river cooling water: screened intake, makeup pump, exchanger shell, outlet chute.
 * Every fixture is unique per run. Ticks are driven by hand, the way the other plant GameTests do, so a whole loop runs
 * inside one server tick.
 */
@GameTestHolder("bwrshell") @PrefixGameTestTemplate(false)
public final class ShellExchangerTests {
    private static final int RUN_SHIFT = Math.floorMod(java.util.UUID.randomUUID().hashCode(), 100_000) * 64;

    private static void near(GameTestHelper h, double a, double b, double tolerance, String message) {
        h.assertTrue(Double.isFinite(a) && Math.abs(a - b) <= tolerance, message + ": " + a + " vs " + b);
    }

    private static void load(ServerLevel l, BlockPos a, BlockPos b) {
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++) l.getChunk(x, z);
    }

    private static void pipe(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) l.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, p), 3);
    }

    private static CoolingBlockEntity place(ServerLevel l, CoolingBlock b, BlockPos root, Direction facing) {
        var s = b.defaultBlockState().setValue(CoolingBlock.FACING, facing);
        l.setBlock(root, s, 3);
        b.setPlacedBy(l, root, s, null, new ItemStack(b));
        return (CoolingBlockEntity) l.getBlockEntity(root);
    }

    private static BlockPos port(CoolingBlockEntity be, CoolingBlock.Port role) {
        return be.layout().world(be.root(), be.getBlockState().getValue(CoolingBlock.FACING),
                be.layout().ports.stream().filter(c -> c.role() == role).findFirst().orElseThrow());
    }

    private static void fill(ServerLevel l, BlockPos a, BlockPos b, net.minecraft.world.level.block.state.BlockState s) {
        for (var p : BlockPos.betweenClosed(a, b)) l.setBlock(p, s, 2);
    }

    private static void solid(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) if (l.getBlockState(p).isAir()) l.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
    }

    private static int sources(ServerLevel l, BlockPos a, BlockPos b) {
        int n = 0;
        for (var p : BlockPos.betweenClosed(a, b)) if (l.getFluidState(p).isSource()) n++;
        return n;
    }

    private static void setTime(ServerLevel l, long t) {
        ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(t);
    }

    /** Walls of stone round a pond so source blocks cannot spread while the fixture stands. */
    private static void pond(ServerLevel l, BlockPos min, BlockPos max) {
        solid(l, min.offset(-1, -1, -1), new BlockPos(max.getX() + 1, min.getY() - 1, max.getZ() + 1));
        solid(l, min.offset(-1, 0, -1), new BlockPos(max.getX() + 1, max.getY(), min.getZ() - 1));
        solid(l, new BlockPos(min.getX() - 1, min.getY(), max.getZ() + 1), new BlockPos(max.getX() + 1, max.getY(), max.getZ() + 1));
        solid(l, new BlockPos(min.getX() - 1, min.getY(), min.getZ()), new BlockPos(min.getX() - 1, max.getY(), max.getZ()));
        solid(l, new BlockPos(max.getX() + 1, min.getY(), min.getZ()), new BlockPos(max.getX() + 1, max.getY(), max.getZ()));
        fill(l, min, max, Blocks.WATER.defaultBlockState());
    }

    /** Everything of one river-cooled RHR loop. */
    public record Loop(SuppressionPoolBlockEntity pool, EccsPumpBlockEntity rhr, RhrHeatExchangerBlockEntity hx, BlockPos hxPos,
                        CoolingBlockEntity intake, CoolingBlockEntity makeup, BlockPos chutePos, BlockPos riverMin, BlockPos riverMax) { }

    /**
     * The basin and RHR pump from the existing suppression fixture, a screened intake in a western pond, the makeup pump and
     * its line to the exchanger's shell inlet (west flange), and the outlet chute on the shell outlet (east flange)
     * facing a lake that stands for the river downstream.
     */
    public static Loop build(ServerLevel l, BlockPos o) throws Exception {
        load(l, o.offset(-30, 0, -30), o.offset(30, 0, 14));
        var pool = SuppressionBasinRegressionTests.build(l, o);
        var rhr = SuppressionBasinRegressionTests.connect(l, o);
        SuppressionBasinRegressionTests.validate(pool);
        var hxPos = o.offset(6, 6, -3);
        // Downstream river: a lake east of the exchanger, one block below the chute's lip (surface 190 + 5). Walls and floor go
        // only into air, so the basin's rim and the primary-side pipes stay as they are.
        var riverMin = o.offset(8, 4, -12);
        var riverMax = o.offset(24, 5, -1);
        pond(l, riverMin, riverMax);
        var chutePos = hxPos.east();
        var chuteState = BwrBlocks.WATER_OUTLET_CHUTE.get().defaultBlockState().setValue(WaterOutletChuteBlock.FACING, Direction.EAST);
        l.setBlock(chutePos, chuteState, 3);
        // Upstream river with the screened intake, then the makeup pump and the shell supply line.
        var intakeRoot = o.offset(-10, 4, -20);
        pond(l, intakeRoot.offset(-4, -1, -5), intakeRoot.offset(4, 0, 1));
        var intake = place(l, BwrBlocks.SCREENED_WATER_INTAKE.get(), intakeRoot, Direction.NORTH);
        var makeup = place(l, BwrBlocks.MAKEUP_WATER_PUMP.get(), intakeRoot.offset(0, 0, 7), Direction.NORTH);
        pipe(l, port(intake, CoolingBlock.Port.OUTLET).south(), port(makeup, CoolingBlock.Port.INLET).north());
        var out = port(makeup, CoolingBlock.Port.OUTLET).south();
        var in = hxPos.west();
        pipe(l, out, new BlockPos(out.getX(), out.getY(), in.getZ()));
        pipe(l, new BlockPos(out.getX(), out.getY(), in.getZ()), in);
        return new Loop(pool, rhr, (RhrHeatExchangerBlockEntity) l.getBlockEntity(hxPos), hxPos, intake, makeup, chutePos, riverMin, riverMax);
    }

    /** One server tick of the loop in plant order: river intake, makeup pump, RHR pump (which circulates the exchanger), exchanger outlet. */
    private static void tick(ServerLevel l, long time, Loop loop, boolean makeupPowered) {
        setTime(l, time);
        var intake = loop.intake();
        CoolingBlockEntity.serverTick(l, intake.getBlockPos(), intake.getBlockState(), intake);
        var makeup = loop.makeup();
        if (makeupPowered) { makeup.power().receiveEnergy(Integer.MAX_VALUE, false); makeup.setTarget(1); }
        CoolingBlockEntity.serverTick(l, makeup.getBlockPos(), makeup.getBlockState(), makeup);
        loop.rhr().energy().setStored(loop.rhr().energy().getMaxEnergyStored());
        EccsPumpBlockEntity.serverTick(l, loop.rhr().getBlockPos(), loop.rhr().getBlockState(), loop.rhr());
        RhrHeatExchangerBlockEntity.serverTick(l, loop.hxPos(), l.getBlockState(loop.hxPos()), loop.hx());
    }

    @GameTest(template = "empty", templateNamespace = "bwrshell", timeoutTicks = 600)
    public static void riverLoopCoolsRhrThroughShell(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var o = new BlockPos(5100 + RUN_SHIFT, 190, 5100);
        var loop = build(l, o);
        var pool = loop.pool();
        var hx = loop.hx();
        var chute = (WaterOutletChuteBlockEntity) l.getBlockEntity(loop.chutePos());
        long time = l.getGameTime();
        var data = l.getServer().getWorldData().overworldData();
        pool.pool().fromArray(new dev.bwr.core.pool.SuppressionPool(105_000, 80).toArray());
        int riverSources = sources(l, loop.riverMin(), loop.riverMax());
        h.assertTrue(riverSources == 17 * 12 * 2, "fixture river should be 408 source blocks, was " + riverSources);
        h.assertTrue(loop.intake().submerged() && chute.waterState() == WaterState.OPEN, "intake or chute is not at open water: " + chute.waterState());
        try {
            // No shell flow: the makeup pump is stopped, so the exchanger has no cooling water and removes no heat. The RHR pump
            // runs the whole time (it takes about four seconds to spin up), so the exchanger is ready the moment water arrives.
            for (int i = 0; i < 100; i++) {
                tick(l, time + i, loop, false);
                h.assertTrue(hx.plant().heatMW() == 0, "heat moved with no cooling water: tick " + i);
            }
            near(h, pool.pool().getTemperatureC(), 80, 0, "pool cooled without shell flow");
            near(h, hx.plant().cold() + hx.plant().hot(), 0, 0, "water reached the shell with the makeup pump stopped");
            h.assertTrue(chute.totalKg() == 0, "chute discharged with no flow");

            // River water in: the makeup pump now delivers its 75 kg per tick (1,500 kg/s).
            double heatMJ = 0, maxDuty = 0, sumIn = 0, sumOut = 0, sumPrimaryIn = 0, sumPrimaryOut = 0;
            int working = 0;
            for (int i = 0; i < 160; i++) {
                tick(l, time + 100 + i, loop, true);
                var p = hx.plant();
                if (p.heatMW() > 0) {
                    heatMJ += p.heatMW() * .05;
                    maxDuty = Math.max(maxDuty, p.heatMW());
                    sumIn += p.secondaryInC(); sumOut += p.secondaryOutC();
                    sumPrimaryIn += p.primaryInC(); sumPrimaryOut += p.primaryOutC();
                    working++;
                }
            }
            h.assertTrue(working > 120, "exchanger did not work on most ticks: " + working);
            h.assertTrue(pool.pool().getTemperatureC() < 79.8, "RHR pool was not cooled by river water: " + pool.pool().getTemperatureC());
            near(h, maxDuty, RhrHeatExchanger.RATING_MW, 1e-3, "duty is not the exchanger rating");
            near(h, sumIn / working, 13, 0.01, "shell inlet is not river temperature");
            h.assertTrue(sumOut / working > sumIn / working + 3, "shell outlet did not warm: " + sumOut / working);
            h.assertTrue(sumPrimaryOut / working < sumPrimaryIn / working, "tube outlet is not cooler than tube inlet");
            h.assertTrue(chute.totalKg() > 4000, "chute returned too little water: " + chute.totalKg());
            double rise = chute.temperatureC() - WaterOutletChuteBlockEntity.RIVER_C;
            h.assertTrue(rise > 3 && rise < 11.5, "discharge rise over river out of range: " + rise);
            h.assertTrue(chute.heatToRiverMW() > 20, "heat to river readout: " + chute.heatToRiverMW());
            // Energy: the heat the exchanger took from the pool is the heat above river temperature the chute returned
            // (plus whatever is still warm inside the exchanger).
            double h13 = Saturation.subcooledLiquidEnthalpyKJPerKg(13);
            double returnedKJ = chute.heatKJ() - chute.totalKg() * h13;
            near(h, heatMJ * 1000, returnedKJ + hx.plant().hotEnergyKJ(), 1.0, "heat into the river does not match heat out of the pool");
            // Water balance: everything the intake drew is still in the plant or went back to the river.
            double held = loop.intake().plant().input() + loop.intake().plant().output() + loop.makeup().plant().input()
                    + loop.makeup().plant().output() + hx.plant().cold() + hx.plant().hot();
            near(h, loop.intake().riverDrawnKg(), held + chute.totalKg(), 0.01, "water was created or destroyed between river and river");
            h.assertTrue(sources(l, loop.riverMin(), loop.riverMax()) == riverSources, "source blocks created or destroyed in the river");
            h.assertTrue(l.getBlockState(chute.receivingCell()).getFluidState().isSource() || l.getBlockState(chute.receivingCell()).getFluidState().is(Fluids.WATER), "receiving cell is not water");

            // Save and reload both ends in the middle of the loop, then keep running.
            double kg = chute.totalKg(), tempC = chute.temperatureC(), hxHot = hx.plant().hot(), hxCold = hx.plant().cold();
            var hxSaved = hx.saveWithFullMetadata(l.registryAccess());
            var chuteSaved = chute.saveWithFullMetadata(l.registryAccess());
            l.removeBlockEntity(loop.hxPos());
            l.removeBlockEntity(loop.chutePos());
            l.setBlockEntity(BlockEntity.loadStatic(loop.hxPos(), l.getBlockState(loop.hxPos()), hxSaved, l.registryAccess()));
            l.setBlockEntity(BlockEntity.loadStatic(loop.chutePos(), l.getBlockState(loop.chutePos()), chuteSaved, l.registryAccess()));
            var hx2 = (RhrHeatExchangerBlockEntity) l.getBlockEntity(loop.hxPos());
            var chute2 = (WaterOutletChuteBlockEntity) l.getBlockEntity(loop.chutePos());
            near(h, chute2.totalKg(), kg, 0, "chute total lost on reload");
            near(h, chute2.temperatureC(), tempC, 1e-9, "chute temperature lost on reload");
            near(h, hx2.plant().hot(), hxHot, 1e-9, "hot water lost on reload");
            near(h, hx2.plant().cold(), hxCold, 1e-9, "cold water lost on reload");
            h.assertTrue(chute2.waterState() == WaterState.OPEN, "reloaded chute lost its water");
            var reloaded = new Loop(loop.pool(), loop.rhr(), hx2, loop.hxPos(), loop.intake(), loop.makeup(), loop.chutePos(), loop.riverMin(), loop.riverMax());
            for (int i = 0; i < 40; i++) tick(l, time + 400 + i, reloaded, true);
            h.assertTrue(chute2.totalKg() > kg + 1000, "loop did not keep running after reload: " + chute2.totalKg());
            h.assertTrue(hx2.plant().heatMW() > 0, "exchanger idle after reload");
            System.out.println("SHELL EXCHANGER RIVER LOOP PASS: intake -> makeup pump -> shell -> outlet chute, " + String.format("%.1f MW, rise %.1f C, %.0f kg returned", maxDuty, rise, kg));
        } finally {
            setTime(l, time);
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrshell", timeoutTicks = 300)
    public static void outletChuteRefusesWithoutOpenWater(GameTestHelper h) {
        var l = h.getLevel();
        var o = new BlockPos(5300 + RUN_SHIFT, 190, 5300);
        load(l, o.offset(-8, 0, -8), o.offset(14, 0, 8));
        fill(l, o.offset(-6, -6, -6), o.offset(12, 8, 6), Blocks.AIR.defaultBlockState());
        fill(l, o.offset(-6, -7, -6), o.offset(12, -7, 6), Blocks.STONE.defaultBlockState());
        var hxPos = o;
        l.setBlock(hxPos, BwrBlocks.RHR_HEAT_EXCHANGER.get().defaultBlockState(), 3); // facing north: shell outlet is east
        var chutePos = hxPos.east();
        l.setBlock(chutePos, BwrBlocks.WATER_OUTLET_CHUTE.get().defaultBlockState().setValue(WaterOutletChuteBlock.FACING, Direction.EAST), 3);
        var hx = (RhrHeatExchangerBlockEntity) l.getBlockEntity(hxPos);
        var chute = (WaterOutletChuteBlockEntity) l.getBlockEntity(chutePos);
        var mouth = chutePos.east();
        long time = l.getGameTime();
        long[] clock = {time};
        try {
            // Heated shell water waiting at the outlet.
            hx.plant().restore(0, 5000, Saturation.subcooledLiquidEnthalpyKJPerKg(13), Saturation.subcooledLiquidEnthalpyKJPerKg(20));
            var inlet = l.getCapability(Capabilities.FluidHandler.BLOCK, chutePos, Direction.WEST);
            h.assertTrue(inlet != null, "chute has no inlet on its back");
            for (var side : new Direction[]{Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN})
                h.assertTrue(l.getCapability(Capabilities.FluidHandler.BLOCK, chutePos, side) == null, "chute accepts water on " + side);
            // Dry: nothing in front of the mouth.
            expect(h, l, chute, clock, WaterState.DRY, "air in front");
            refuses(h, l, hx, chute, hxPos, clock, inlet, "dry mouth");
            // A solid block in the mouth.
            l.setBlock(mouth, Blocks.STONE.defaultBlockState(), 2);
            expect(h, l, chute, clock, WaterState.BLOCKED, "stone in the mouth");
            refuses(h, l, hx, chute, hxPos, clock, inlet, "blocked mouth");
            // A single source block (a puddle) is not a river.
            l.setBlock(mouth, Blocks.WATER.defaultBlockState(), 2);
            expect(h, l, chute, clock, WaterState.SHALLOW, "one source block");
            refuses(h, l, hx, chute, hxPos, clock, inlet, "puddle");
            // Flowing water alone (no source blocks) does not count.
            l.setBlock(mouth, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3), 2);
            expect(h, l, chute, clock, WaterState.SHALLOW, "flowing water only");
            // Lava is not water.
            l.setBlock(mouth, Blocks.LAVA.defaultBlockState(), 2);
            expect(h, l, chute, clock, WaterState.DRY, "lava");
            l.setBlock(mouth, Blocks.AIR.defaultBlockState(), 2);
            // Open water over a drop: the mouth is air and the river is two blocks down.
            fill(l, mouth.offset(0, -3, -2), mouth.offset(4, -2, 2), Blocks.WATER.defaultBlockState());
            expect(h, l, chute, clock, WaterState.OPEN, "river below the lip");
            h.assertTrue(l.getFluidState(chute.receivingCell()).isSource() && chute.receivingCell().getY() == mouth.getY() - 2, "receiving cell is not the surface two blocks down");
            // Switched off: refuses even over open water.
            chute.setEnabled(false);
            refuses(h, l, hx, chute, hxPos, clock, inlet, "disabled chute");
            chute.setEnabled(true);
            // Open water: the same heated water now runs out at the exchanger's outlet rate, and nothing is created or lost.
            int sourcesBefore = sources(l, mouth.offset(0, -3, -2), mouth.offset(4, -2, 2));
            double hotBefore = hx.plant().hot();
            for (int i = 0; i < 20; i++) {
                setTime(l, ++clock[0]);
                RhrHeatExchangerBlockEntity.serverTick(l, hxPos, l.getBlockState(hxPos), hx);
            }
            near(h, chute.totalKg(), 2400, 0, "chute should take 120 kg per tick for 20 ticks");
            near(h, hx.plant().hot() + chute.totalKg(), hotBefore, 1e-9, "outlet water was created or destroyed");
            near(h, chute.temperatureC(), 20, 0.01, "chute temperature is not the water's");
            near(h, chute.heatToRiverMW(), 2400 * 4.184 * 7 / 1000, 0.5, "heat to river MW");
            h.assertTrue(sources(l, mouth.offset(0, -3, -2), mouth.offset(4, -2, 2)) == sourcesBefore, "chute changed the river's source blocks");
            // Take the water away: the stream stops at once.
            fill(l, mouth.offset(0, -3, -2), mouth.offset(4, -2, 2), Blocks.AIR.defaultBlockState());
            double total = chute.totalKg();
            setTime(l, ++clock[0]);
            RhrHeatExchangerBlockEntity.serverTick(l, hxPos, l.getBlockState(hxPos), hx);
            near(h, chute.totalKg(), total, 0, "chute kept discharging after the river went");
            System.out.println("OUTLET CHUTE REFUSAL PASS: dry, blocked, puddle, flowing-only, lava, disabled; open water over a drop; no source blocks changed");
        } finally {
            setTime(l, time);
        }
        h.succeed();
    }

    private static void expect(GameTestHelper h, ServerLevel l, WaterOutletChuteBlockEntity chute, long[] clock, WaterState want, String label) {
        setTime(l, ++clock[0]);
        h.assertTrue(chute.waterState() == want, label + ": expected " + want + " but chute says " + chute.waterState());
        h.assertTrue((chute.waterState() == WaterState.OPEN) == chute.clearMouth(), label + ": clearMouth disagrees with the state");
    }

    private static void refuses(GameTestHelper h, ServerLevel l, RhrHeatExchangerBlockEntity hx, WaterOutletChuteBlockEntity chute, BlockPos hxPos,
                                long[] clock, net.neoforged.neoforge.fluids.capability.IFluidHandler inlet, String label) {
        double hot = hx.plant().hot(), total = chute.totalKg();
        setTime(l, ++clock[0]);
        h.assertTrue(inlet.fill(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE) == 0, label + ": the chute accepted water");
        for (int i = 0; i < 5; i++) {
            setTime(l, ++clock[0]);
            RhrHeatExchangerBlockEntity.serverTick(l, hxPos, l.getBlockState(hxPos), hx);
        }
        near(h, hx.plant().hot(), hot, 0, label + ": hot water left the exchanger");
        near(h, chute.totalKg(), total, 0, label + ": chute counted water it refused");
    }

    @GameTest(template = "empty", templateNamespace = "bwrshell", timeoutTicks = 200)
    public static void placedExchangersKeepTheirConnections(GameTestHelper h) {
        var l = h.getLevel();
        var o = new BlockPos(5500 + RUN_SHIFT, 195, 5500);
        l.getChunkAt(o);
        // The port layout of every exchanger placed before the shell-and-tube model: tube side on the facing axis,
        // shell inlet counter-clockwise of facing, shell outlet clockwise. Written out literally, not through the block's helpers.
        record Old(Direction facing, Direction tubeIn, Direction tubeOut, Direction shellIn, Direction shellOut) { }
        var layouts = new Old[]{
                new Old(Direction.NORTH, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST),
                new Old(Direction.EAST, Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH),
                new Old(Direction.SOUTH, Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST),
                new Old(Direction.WEST, Direction.WEST, Direction.EAST, Direction.SOUTH, Direction.NORTH)};
        for (var old : layouts) {
            var pos = o.offset(old.facing().get2DDataValue() * 4, 0, 0);
            var state = BwrBlocks.RHR_HEAT_EXCHANGER.get().defaultBlockState().setValue(RhrHeatExchangerBlock.FACING, old.facing());
            l.setBlock(pos, state, 3);
            h.assertTrue(RhrHeatExchangerBlock.primaryIn(state) == old.tubeIn() && RhrHeatExchangerBlock.primaryOut(state) == old.tubeOut()
                    && RhrHeatExchangerBlock.coldIn(state) == old.shellIn() && RhrHeatExchangerBlock.hotOut(state) == old.shellOut(), "port faces moved for " + old.facing());
            for (var side : Direction.values()) {
                var cap = l.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
                boolean shell = side == old.shellIn() || side == old.shellOut();
                h.assertTrue((cap != null) == shell, "fluid capability on " + side + " for " + old.facing());
                h.assertTrue(dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(state, side) == side.getAxis().isHorizontal(), "pipe acceptance on " + side);
            }
            var block = (RhrHeatExchangerBlock) state.getBlock();
            h.assertTrue(block.portAt(state, old.tubeIn()) == dev.bwr.mod.eccs.AssemblyPort.WATER_SUCTION
                    && block.portAt(state, old.tubeOut()) == dev.bwr.mod.eccs.AssemblyPort.WATER_DISCHARGE
                    && block.portAt(state, old.shellIn()) == null && block.portAt(state, old.shellOut()) == null, "tube-side assembly ports moved for " + old.facing());
            // A pipe beside each flange still connects to it.
            for (var side : new Direction[]{old.tubeIn(), old.tubeOut(), old.shellIn(), old.shellOut()}) {
                var q = pos.relative(side);
                var pipe = BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, q);
                h.assertTrue(pipe.getValue(net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION.get(side.getOpposite())), "pipe does not connect to the " + side + " flange");
            }
            // Block entity written by the plate-exchanger build (no ColdH/HotH keys) loads into the new block unchanged.
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("id", "bwr:rhr_heat_exchanger");
            tag.putInt("x", pos.getX()); tag.putInt("y", pos.getY()); tag.putInt("z", pos.getZ());
            tag.putDouble("Cold", 1234); tag.putDouble("Hot", 567); tag.putDouble("HotEnergyKJ", 8900);
            l.removeBlockEntity(pos);
            l.setBlockEntity(BlockEntity.loadStatic(pos, state, tag, l.registryAccess()));
            var hx = (RhrHeatExchangerBlockEntity) l.getBlockEntity(pos);
            near(h, hx.plant().cold(), 1234, 0, "old cold water");
            near(h, hx.plant().hot(), 567, 0, "old hot water");
            near(h, hx.plant().hotEnergyKJ(), 8900, 1e-6, "old hot energy");
            var cold = l.getCapability(Capabilities.FluidHandler.BLOCK, pos, old.shellIn());
            h.assertTrue(cold.getFluidInTank(0).getAmount() == 1234, "old cold water not visible through the shell inlet");
        }
        // The one new saved key is written only when an intake has actually drawn water.
        var intake = place(l, BwrBlocks.SCREENED_WATER_INTAKE.get(), o.offset(0, 0, 12), Direction.NORTH);
        h.assertTrue(!intake.saveWithoutMetadata(l.registryAccess()).contains("DrawnKg"), "unused intake wrote a new key");
        h.succeed();
    }
}
