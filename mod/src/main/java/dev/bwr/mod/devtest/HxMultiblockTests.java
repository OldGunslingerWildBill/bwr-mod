package dev.bwr.mod.devtest;

import dev.bwr.core.pool.RhrHeatExchanger;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.cooling.CoolingBlock;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.AssemblyPlumbing;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.eccs.SuctionSource;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.RhrHeatExchangerBlock;
import dev.bwr.mod.suppression.RhrHeatExchangerBlock.HxPort;
import dev.bwr.mod.suppression.RhrHeatExchangerBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import dev.bwr.mod.water.WaterOutletChuteBlock;
import dev.bwr.mod.water.WaterOutletChuteBlockEntity;
import dev.bwr.mod.water.WaterOutletChuteBlockEntity.WaterState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The full-size (3 x 8 x 3) RHR heat exchanger: placement and breaking in all four facings, a blocked footprint, both water
 * circuits through its flange cells, the river loop (intake, makeup pump, shell, outlet chute) and the single blocks saved
 * before it. Fixtures are unique per run; ticks are driven by hand as in ShellExchangerTests.
 */
@GameTestHolder("bwrhx") @PrefixGameTestTemplate(false)
public final class HxMultiblockTests {
    private static final int RUN_SHIFT = Math.floorMod(java.util.UUID.randomUUID().hashCode(), 100_000) * 64;
    private static final RhrHeatExchangerBlock HX = BwrBlocks.RHR_HEAT_EXCHANGER.get();

    private static void near(GameTestHelper h, double a, double b, double tolerance, String message) {
        h.assertTrue(Double.isFinite(a) && Math.abs(a - b) <= tolerance, message + ": " + a + " vs " + b);
    }

    private static void load(ServerLevel l, BlockPos a, BlockPos b) {
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++) l.getChunk(x, z);
    }

    /** Item entities are only visible in entity-loaded chunks: the drop counts hold these chunks for the test's duration. */
    private static java.util.List<long[]> force(ServerLevel l, BlockPos a, BlockPos b) {
        var held = new java.util.ArrayList<long[]>();
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++)
                if (l.setChunkForced(x, z, true)) held.add(new long[]{x, z});
        return held;
    }

    private static void release(ServerLevel l, java.util.List<long[]> held) {
        for (var c : held) l.setChunkForced((int) c[0], (int) c[1], false);
    }

    private static void fill(ServerLevel l, BlockPos a, BlockPos b, BlockState s) {
        for (var p : BlockPos.betweenClosed(a, b)) l.setBlock(p, s, 2);
    }

    private static void pipe(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) l.setBlock(p, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, p), 3);
    }

    private static void setTime(ServerLevel l, long t) {
        ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(t);
    }

    /** Places the exchanger item the way a player does (BlockItem.place: placement check, controller, then the other cells). */
    static InteractionResult placeItem(ServerLevel l, BlockPos at, Direction facing) {
        var stack = new ItemStack(HX);
        // A player looking 'facing.getOpposite()' places a machine whose front (red tube inlet) faces them.
        return ((BlockItem) stack.getItem()).place(new DirectionalPlaceContext(l, at, facing.getOpposite(), stack, Direction.UP));
    }

    /** A survival player with a pickaxe breaks one cell (playerWillDestroy, the break event, loot), not a bare destroyBlock. */
    static void breakAsPlayer(ServerLevel l, BlockPos p) {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
        var held = player.getMainHandItem().copy();
        var mode = player.gameMode.getGameModeForPlayer();
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            player.setPos(p.getX() + 2.5, p.getY(), p.getZ() + .5);
            player.gameMode.destroyBlock(p);
        } finally {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            player.gameMode.changeGameModeForPlayer(mode);
        }
    }

    /** The flange cells written out literally for a machine facing north (offsets from the controller), not read from the layout. */
    private record Flange(HxPort role, BlockPos northOffset, Direction northFace) { }
    private static final Flange[] FLANGES = {
            new Flange(HxPort.TUBE_IN, new BlockPos(0, 1, -1), Direction.NORTH),
            new Flange(HxPort.TUBE_OUT, new BlockPos(0, 1, 1), Direction.SOUTH),
            new Flange(HxPort.SHELL_IN, new BlockPos(-1, 2, 0), Direction.WEST),
            new Flange(HxPort.SHELL_OUT, new BlockPos(1, 6, 0), Direction.EAST)};

    private static BlockPos turned(BlockPos root, BlockPos northOffset, Direction facing) {
        return root.offset(dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(northOffset, facing));
    }

    private static Direction turned(Direction northFace, Direction facing) {
        return dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(northFace, facing);
    }

    @GameTest(template = "empty", templateNamespace = "bwrhx", timeoutTicks = 300)
    public static void assemblyPlacesAndBreaksInAllFacings(GameTestHelper h) {
        var l = h.getLevel();
        var o = new BlockPos(6300 + RUN_SHIFT, 190, 6300);
        load(l, o.offset(-4, 0, -4), o.offset(60, 0, 8));
        var forced = force(l, o.offset(-4, 0, -4), o.offset(60, 0, 8));
        // The tickets take effect between ticks; the checks run once the chunks hold entities.
        h.runAfterDelay(3, () -> {
            try { placementChecks(h, l, o); } finally { release(l, forced); }
            h.succeed();
        });
    }

    private static void placementChecks(GameTestHelper h, ServerLevel l, BlockPos o) {
        fill(l, o.offset(-3, -1, -3), o.offset(58, 10, 6), Blocks.AIR.defaultBlockState());
        fill(l, o.offset(-3, -1, -3), o.offset(58, -1, 6), Blocks.STONE.defaultBlockState());
        int count = RhrHeatExchangerBlock.LAYOUT.count();
        h.assertTrue(count == 72, "footprint should be 3 x 8 x 3 = 72 cells, was " + count);
        for (var facing : Direction.Plane.HORIZONTAL) {
            var root = o.offset(facing.get2DDataValue() * 14, 0, 0);
            h.assertTrue(placeItem(l, root, facing).consumesAction(), "placement refused on a clear footprint facing " + facing);
            var rootState = l.getBlockState(root);
            h.assertTrue(rootState.is(HX) && RhrHeatExchangerBlock.isAssembled(rootState) && rootState.getValue(RhrHeatExchangerBlock.FACING) == facing,
                    "controller missing or wrong facing for " + facing + ": " + rootState);
            var be = l.getBlockEntity(root) instanceof RhrHeatExchangerBlockEntity e ? e : null;
            h.assertTrue(be != null && be.assembled(), "controller has no exchanger block entity, facing " + facing);
            // Every cell of the 3 x 8 x 3 box is ours, only the controller holds a block entity, and every cell resolves to it
            // (the panel, CC and the Networking Wrench all go through this lookup).
            int cells = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 8; dy++) for (int dz = -1; dz <= 1; dz++) {
                var p = root.offset(dx, dy, dz);
                var s = l.getBlockState(p);
                h.assertTrue(s.is(HX) && RhrHeatExchangerBlock.isAssembled(s), "cell " + p + " is not part of the assembly facing " + facing);
                h.assertTrue((l.getBlockEntity(p) != null) == p.equals(root), "block entity outside the controller at " + p);
                h.assertTrue(RhrHeatExchangerBlock.controller(l, p, s) == be, "cell " + p + " does not address the controller");
                h.assertTrue(dev.bwr.mod.signal.DeviceResolver.resolve(l, p) == be, "device lookup (CC, wrench) of " + p + " is not the controller");
                // Loot: the controller cell's state drops the one item, the other cells' states drop nothing of their own.
                int loot = net.minecraft.world.level.block.Block.getDrops(s, l, p, l.getBlockEntity(p)).stream().mapToInt(ItemStack::getCount).sum();
                h.assertTrue(loot == (p.equals(root) ? 1 : 0), "cell " + p + " loot is " + loot);
                cells++;
            }
            h.assertTrue(cells == 72 && HX.complete(l, root, rootState), "assembly incomplete facing " + facing);
            h.assertTrue(l.getBlockState(root.above(8)).isAir() && l.getBlockState(root.offset(2, 0, 0)).isAir(), "assembly spilled out of its footprint");
            // Flanges: exactly the four flange cell faces expose a port; the shell-side two expose water; pipes join only those.
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 8; dy++) for (int dz = -1; dz <= 1; dz++) {
                var p = root.offset(dx, dy, dz);
                var s = l.getBlockState(p);
                for (var side : Direction.values()) {
                    Flange expected = null;
                    for (var f : FLANGES) if (turned(root, f.northOffset(), facing).equals(p) && turned(f.northFace(), facing) == side) expected = f;
                    var port = RhrHeatExchangerBlock.portOf(s, side);
                    h.assertTrue(port == (expected == null ? null : expected.role()), "port on " + p + " " + side + " is " + port + " facing " + facing);
                    boolean shell = expected != null && (expected.role() == HxPort.SHELL_IN || expected.role() == HxPort.SHELL_OUT);
                    h.assertTrue((l.getCapability(Capabilities.FluidHandler.BLOCK, p, side) != null) == shell, "water capability on " + p + " " + side);
                    h.assertTrue(dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s, side) == (expected != null), "pipe acceptance on " + p + " " + side);
                }
            }
            // Faces match the single block's convention: red on the front, amber behind, blue counter-clockwise, cyan clockwise.
            h.assertTrue(RhrHeatExchangerBlock.portFace(rootState, HxPort.TUBE_IN) == facing
                    && RhrHeatExchangerBlock.portFace(rootState, HxPort.TUBE_OUT) == facing.getOpposite()
                    && RhrHeatExchangerBlock.portFace(rootState, HxPort.SHELL_IN) == facing.getCounterClockWise()
                    && RhrHeatExchangerBlock.portFace(rootState, HxPort.SHELL_OUT) == facing.getClockWise(), "flange faces facing " + facing);
            for (var f : FLANGES) {
                var q = turned(root, f.northOffset(), facing).relative(turned(f.northFace(), facing));
                var pipe = BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, q);
                h.assertTrue(pipe.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(turned(f.northFace(), facing).getOpposite())), "pipe does not connect to " + f.role() + " facing " + facing);
                // ...and a pipe next to a plain cell does not.
                var plain = turned(root, new BlockPos(0, 4, -1), facing).relative(turned(Direction.NORTH, facing));
                var none = BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, plain);
                h.assertTrue(!none.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(turned(Direction.SOUTH, facing))), "pipe connected to the plain shell facing " + facing);
            }
            // Survival-style break of a top cell: exactly one item drops and every cell goes; nothing outside is touched.
            var neighbour = root.offset(-2, 0, 0);
            l.setBlock(neighbour, Blocks.STONE.defaultBlockState(), 3);
            breakAsPlayer(l, root.offset(1, 7, 1));
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 8; dy++) for (int dz = -1; dz <= 1; dz++)
                h.assertTrue(!l.getBlockState(root.offset(dx, dy, dz)).is(HX), "orphan cell left after breaking, facing " + facing + " at " + root.offset(dx, dy, dz));
            h.assertTrue(l.getBlockState(neighbour).is(Blocks.STONE), "breaking removed a neighbour");
            l.removeBlock(neighbour, false);
        }
        long drops = l.getEntitiesOfClass(ItemEntity.class, new AABB(o).inflate(70)).stream().filter(e -> e.getItem().is(HX.asItem())).mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(drops == 4, "four broken assemblies should drop four items, dropped " + drops);
        // A blocked footprint refuses placement and changes nothing: one stone block in the top corner, then a wall beside.
        var root = o.offset(0, 0, 0);
        for (var blocker : new BlockPos[]{root.offset(1, 7, 1), root.offset(-1, 3, 0), root.offset(0, 1, -1)}) {
            l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
            h.assertTrue(!placeItem(l, root, Direction.NORTH).consumesAction(), "placement accepted with " + blocker + " blocked");
            h.assertTrue(l.getBlockState(root).isAir() && l.getBlockState(blocker).is(Blocks.STONE), "refused placement changed the world at " + blocker);
            for (var p : BlockPos.betweenClosed(root.offset(-1, 0, -1), root.offset(1, 7, 1))) h.assertTrue(!l.getBlockState(p).is(HX), "refused placement left a cell at " + p);
            l.removeBlock(blocker, false);
        }
        // Breaking the controller itself also clears the whole unit.
        h.assertTrue(placeItem(l, root, Direction.EAST).consumesAction(), "placement refused after clearing");
        l.destroyBlock(root, false);
        for (var p : BlockPos.betweenClosed(root.offset(-1, 0, -1), root.offset(1, 7, 1))) h.assertTrue(!l.getBlockState(p).is(HX), "cell left after breaking the controller at " + p);
        System.out.println("HX MULTIBLOCK PLACEMENT PASS: 72 cells, one controller, 4 facings, flange faces, pipes, one drop each, blocked footprint refused");
    }

    /** Everything of one river-cooled RHR loop through a full-size exchanger. */
    public record Loop(SuppressionPoolBlockEntity pool, EccsPumpBlockEntity rhr, RhrHeatExchangerBlockEntity hx, BlockPos root,
                       CoolingBlockEntity intake, CoolingBlockEntity makeup, BlockPos chutePos, BlockPos riverMin, BlockPos riverMax) { }

    private static void solid(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) if (l.getBlockState(p).isAir()) l.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
    }

    private static void pond(ServerLevel l, BlockPos min, BlockPos max) {
        solid(l, min.offset(-1, -1, -1), new BlockPos(max.getX() + 1, min.getY() - 1, max.getZ() + 1));
        solid(l, min.offset(-1, 0, -1), new BlockPos(max.getX() + 1, max.getY(), min.getZ() - 1));
        solid(l, new BlockPos(min.getX() - 1, min.getY(), max.getZ() + 1), new BlockPos(max.getX() + 1, max.getY(), max.getZ() + 1));
        solid(l, new BlockPos(min.getX() - 1, min.getY(), min.getZ()), new BlockPos(min.getX() - 1, max.getY(), max.getZ()));
        solid(l, new BlockPos(max.getX() + 1, min.getY(), min.getZ()), new BlockPos(max.getX() + 1, max.getY(), max.getZ()));
        fill(l, min, max, Blocks.WATER.defaultBlockState());
    }

    private static int sources(ServerLevel l, BlockPos a, BlockPos b) {
        int n = 0;
        for (var p : BlockPos.betweenClosed(a, b)) if (l.getFluidState(p).isSource()) n++;
        return n;
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

    /**
     * The suppression basin and RHR pump of the existing fixture; a full-size exchanger facing north at o + (4, 0, -8); the pump
     * discharge piped to its red flange and its amber flange back to the basin return; a screened intake and makeup pump in a
     * western pond feeding the blue flange; the outlet chute on the cyan flange, eight metres up, dropping into a lake.
     */
    public static Loop build(ServerLevel l, BlockPos o) throws Exception {
        load(l, o.offset(-30, 0, -30), o.offset(30, 0, 14));
        var pool = SuppressionBasinRegressionTests.build(l, o);
        fill(l, o.offset(-1, 0, -16), o.offset(22, 9, -4), Blocks.AIR.defaultBlockState());
        var block = BwrBlocks.RHR_PUMP.get();
        var pumpRoot = o.offset(12, 0, 3);
        var ps = block.placementState();
        l.setBlock(pumpRoot, ps, 3);
        block.setPlacedBy(l, pumpRoot, ps, null, new ItemStack(block));
        var rhr = (EccsPumpBlockEntity) l.getBlockEntity(pumpRoot);
        rhr.setPanelControlled(true); rhr.setSuctionSource(SuctionSource.SUPPRESSION_POOL); rhr.setMode(EccsPumpBlockEntity.Mode.POOL_COOLING);
        rhr.setRunning(true); rhr.setSpeedDemandFraction(1);
        var root = o.offset(4, 0, -8);
        if (!placeItem(l, root, Direction.NORTH).consumesAction()) throw new IllegalStateException("exchanger placement refused");
        // RHR pump discharge -> red tube inlet (north face of cell (0,1,-1)); amber tube outlet (south of (0,1,1)) -> basin return.
        pipe(l, o.offset(10, 3, 3), o.offset(10, 6, 3));
        pipe(l, o.offset(10, 6, 3), o.offset(10, 6, -10));
        pipe(l, o.offset(4, 6, -10), o.offset(9, 6, -10));
        pipe(l, o.offset(4, 1, -10), o.offset(4, 5, -10));
        pipe(l, o.offset(4, 1, -6), o.offset(4, 1, -1));
        pipe(l, o.offset(5, 1, -1), o.offset(6, 1, -1));
        // Downstream river under the chute: the chute stands against the cyan flange at y 6, the lake surface is at y 3.
        var riverMin = o.offset(7, 2, -13);
        var riverMax = o.offset(20, 3, -4);
        pond(l, riverMin, riverMax);
        var chutePos = root.offset(2, 6, 0);
        l.setBlock(chutePos, BwrBlocks.WATER_OUTLET_CHUTE.get().defaultBlockState().setValue(WaterOutletChuteBlock.FACING, Direction.EAST), 3);
        // Upstream: screened intake, makeup pump, shell supply line to the blue flange (west face of cell (-1,2,0)).
        var intakeRoot = o.offset(-10, 4, -24);
        pond(l, intakeRoot.offset(-4, -1, -5), intakeRoot.offset(4, 0, 1));
        var intake = place(l, BwrBlocks.SCREENED_WATER_INTAKE.get(), intakeRoot, Direction.NORTH);
        var makeup = place(l, BwrBlocks.MAKEUP_WATER_PUMP.get(), intakeRoot.offset(0, 0, 7), Direction.NORTH);
        pipe(l, port(intake, CoolingBlock.Port.OUTLET).south(), port(makeup, CoolingBlock.Port.INLET).north());
        var out = port(makeup, CoolingBlock.Port.OUTLET).south();
        var in = root.offset(-2, 2, 0);
        pipe(l, out, new BlockPos(out.getX(), out.getY(), in.getZ()));
        pipe(l, new BlockPos(out.getX(), out.getY(), in.getZ()), new BlockPos(in.getX(), out.getY(), in.getZ()));
        pipe(l, new BlockPos(in.getX(), Math.min(out.getY(), in.getY()), in.getZ()), new BlockPos(in.getX(), Math.max(out.getY(), in.getY()), in.getZ()));
        SuppressionBasinRegressionTests.validate(pool);
        return new Loop(pool, rhr, (RhrHeatExchangerBlockEntity) l.getBlockEntity(root), root, intake, makeup, chutePos, riverMin, riverMax);
    }

    private static void tick(ServerLevel l, long time, Loop loop, boolean makeupPowered) {
        setTime(l, time);
        var intake = loop.intake();
        CoolingBlockEntity.serverTick(l, intake.getBlockPos(), intake.getBlockState(), intake);
        var makeup = loop.makeup();
        if (makeupPowered) { makeup.power().receiveEnergy(Integer.MAX_VALUE, false); makeup.setTarget(1); }
        CoolingBlockEntity.serverTick(l, makeup.getBlockPos(), makeup.getBlockState(), makeup);
        loop.rhr().energy().setStored(loop.rhr().energy().getMaxEnergyStored());
        EccsPumpBlockEntity.serverTick(l, loop.rhr().getBlockPos(), loop.rhr().getBlockState(), loop.rhr());
        RhrHeatExchangerBlockEntity.serverTick(l, loop.root(), l.getBlockState(loop.root()), loop.hx());
    }

    @GameTest(template = "empty", templateNamespace = "bwrhx", timeoutTicks = 200)
    public static void assemblyCarriesWaterOnBothCircuits(GameTestHelper h) {
        var l = h.getLevel();
        var o = new BlockPos(6500 + RUN_SHIFT, 195, 6500);
        load(l, o.offset(-3, 0, -3), o.offset(3, 0, 3));
        fill(l, o.offset(-3, -1, -3), o.offset(3, 9, 3), Blocks.AIR.defaultBlockState());
        h.assertTrue(placeItem(l, o, Direction.SOUTH).consumesAction(), "placement refused");
        var s = l.getBlockState(o);
        var hx = (RhrHeatExchangerBlockEntity) l.getBlockEntity(o);
        // Facing south: blue (shell in) is on the east face of world cell o+(1,2,0); cyan (shell out) on the west face of o+(-1,6,0).
        var blueCell = o.offset(1, 2, 0);
        var cyanCell = o.offset(-1, 6, 0);
        var blue = l.getCapability(Capabilities.FluidHandler.BLOCK, blueCell, Direction.EAST);
        var cyan = l.getCapability(Capabilities.FluidHandler.BLOCK, cyanCell, Direction.WEST);
        h.assertTrue(blue != null && cyan != null, "shell flanges expose no water");
        h.assertTrue(blue.fill(new FluidStack(Fluids.WATER, 2000), FluidAction.EXECUTE) == 2000, "blue flange refused cooling water");
        h.assertTrue(cyan.fill(new FluidStack(Fluids.WATER, 10), FluidAction.EXECUTE) == 0 && blue.drain(10, FluidAction.EXECUTE).isEmpty(), "shell flanges crossed roles");
        near(h, hx.plant().cold(), 2000, 0, "cooling water did not reach the controller's shell inventory");
        // Hot water leaves through the cyan flange cell: a pipe there carries it; the exchanger pushes it on its own tick.
        hx.plant().restore(2000, 500, Saturation.subcooledLiquidEnthalpyKJPerKg(13), Saturation.subcooledLiquidEnthalpyKJPerKg(30));
        var sinkPos = cyanCell.west();
        l.setBlock(sinkPos, BwrBlocks.WATER_OUTLET_CHUTE.get().defaultBlockState().setValue(WaterOutletChuteBlock.FACING, Direction.WEST), 3);
        fill(l, sinkPos.offset(-6, -4, -3), sinkPos.offset(-1, -3, 3), Blocks.WATER.defaultBlockState());
        var chute = (WaterOutletChuteBlockEntity) l.getBlockEntity(sinkPos);
        long time = l.getGameTime();
        try {
            for (int i = 0; i < 3; i++) { setTime(l, time + 1 + i); RhrHeatExchangerBlockEntity.serverTick(l, o, s, hx); }
            h.assertTrue(chute.waterState() == WaterState.OPEN, "test chute does not face water: " + chute.waterState());
            near(h, chute.totalKg(), 360, 0, "cyan flange did not hand 120 kg per tick to the chute");
            near(h, hx.plant().hot(), 140, 1e-9, "hot water not taken from the shell outlet");
        } finally { setTime(l, time); }
        // Tube side: the pump traces red and amber on their cells; the tube flanges expose no generic tank.
        h.assertTrue(HX.portPosition(o, s, AssemblyPort.WATER_SUCTION).equals(o.offset(0, 1, 1)) && HX.portFace(s, AssemblyPort.WATER_SUCTION) == Direction.SOUTH, "red flange cell");
        h.assertTrue(HX.portPosition(o, s, AssemblyPort.WATER_DISCHARGE).equals(o.offset(0, 1, -1)) && HX.portFace(s, AssemblyPort.WATER_DISCHARGE) == Direction.NORTH, "amber flange cell");
        h.assertTrue(l.getCapability(Capabilities.FluidHandler.BLOCK, o.offset(0, 1, 1), Direction.SOUTH) == null, "red flange exposes a tank");
        // Saved and reloaded: the controller's water survives and the cached flange handler rebinds.
        var saved = hx.saveWithFullMetadata(l.registryAccess());
        l.removeBlockEntity(o);
        l.setBlockEntity(BlockEntity.loadStatic(o, s, saved, l.registryAccess()));
        h.assertTrue(blue.getFluidInTank(0).getAmount() == 2000, "blue flange did not rebind to the reloaded controller");
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrhx", timeoutTicks = 600)
    public static void riverLoopCoolsThroughFullSizeExchanger(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var o = new BlockPos(6700 + RUN_SHIFT, 190, 6700);
        var loop = build(l, o);
        var pool = loop.pool();
        var hx = loop.hx();
        var chute = (WaterOutletChuteBlockEntity) l.getBlockEntity(loop.chutePos());
        h.assertTrue(hx != null && hx.assembled(), "the loop's exchanger is not the full-size unit");
        h.assertTrue(hx.returnPool() == pool, "amber flange return does not reach the basin");
        h.assertTrue(AssemblyPlumbing.endpoint(l, AssemblyPlumbing.trace(l, loop.rhr().getBlockPos(), loop.rhr().getBlockState(), AssemblyPort.WATER_DISCHARGE),
                RhrHeatExchangerBlockEntity.class) == hx, "RHR pump discharge does not reach the exchanger controller through the red flange");
        pool.pool().fromArray(new dev.bwr.core.pool.SuppressionPool(105_000, 80).toArray());
        int riverSources = sources(l, loop.riverMin(), loop.riverMax());
        h.assertTrue(loop.intake().submerged() && chute.waterState() == WaterState.OPEN, "intake or chute is not at open water: " + chute.waterState());
        long time = l.getGameTime();
        try {
            for (int i = 0; i < 100; i++) {
                tick(l, time + i, loop, false);
                h.assertTrue(hx.plant().heatMW() == 0, "heat moved with no cooling water: tick " + i);
            }
            near(h, pool.pool().getTemperatureC(), 80, 0, "pool cooled without shell flow");
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
            h.assertTrue(working > 120, "exchanger did not work on most ticks: " + working + " " + loop.rhr().connectionStatus());
            h.assertTrue(pool.pool().getTemperatureC() < 79.8, "RHR pool was not cooled by river water: " + pool.pool().getTemperatureC());
            near(h, maxDuty, RhrHeatExchanger.RATING_MW, 1e-3, "duty is not the exchanger rating");
            near(h, sumIn / working, 13, 0.01, "shell inlet is not river temperature");
            h.assertTrue(sumOut / working > sumIn / working + 3, "shell outlet did not warm: " + sumOut / working);
            h.assertTrue(sumPrimaryOut / working < sumPrimaryIn / working, "tube outlet is not cooler than tube inlet");
            h.assertTrue(chute.totalKg() > 4000, "chute returned too little water: " + chute.totalKg());
            double h13 = Saturation.subcooledLiquidEnthalpyKJPerKg(13);
            near(h, heatMJ * 1000, chute.heatKJ() - chute.totalKg() * h13 + hx.plant().hotEnergyKJ(), 1.0, "heat into the river does not match heat out of the pool");
            double held = loop.intake().plant().input() + loop.intake().plant().output() + loop.makeup().plant().input()
                    + loop.makeup().plant().output() + hx.plant().cold() + hx.plant().hot();
            near(h, loop.intake().riverDrawnKg(), held + chute.totalKg(), 0.01, "water was created or destroyed between river and river");
            h.assertTrue(sources(l, loop.riverMin(), loop.riverMax()) == riverSources, "source blocks created or destroyed in the river");
            near(h, pool.pool().getMassKg(), 105_000, 1e-6, "the tube-side loop lost basin water");
            // Break the multiblock's return: a pipe in the amber line goes, the loop stops removing heat at once.
            double temp = pool.pool().getTemperatureC();
            l.removeBlock(o.offset(4, 1, -3), false);
            for (int i = 0; i < 5; i++) tick(l, time + 300 + i, loop, true);
            h.assertTrue(hx.plant().heatMW() == 0, "exchanger kept working with its amber return broken");
            near(h, pool.pool().getTemperatureC(), temp, 1e-9, "broken return still cooled the pool");
            System.out.println("HX MULTIBLOCK RIVER LOOP PASS: intake -> makeup pump -> full-size shell -> chute, " + String.format("%.1f MW over %d ticks, %.0f kg returned", maxDuty, working, chute.totalKg()));
        } finally {
            setTime(l, time);
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrhx", timeoutTicks = 200)
    public static void savedSingleBlockStaysSingleUntilReplaced(GameTestHelper h) {
        var l = h.getLevel();
        var o = new BlockPos(6900 + RUN_SHIFT, 195, 6900);
        load(l, o.offset(-6, 0, -6), o.offset(6, 0, 6));
        var forced = force(l, o.offset(-6, 0, -6), o.offset(6, 0, 6));
        // The tickets take effect between ticks; the checks run once the chunks hold entities.
        h.runAfterDelay(3, () -> {
            try { legacyChecks(h, l, o); } finally { release(l, forced); }
            h.succeed();
        });
    }

    private static void legacyChecks(GameTestHelper h, ServerLevel l, BlockPos o) {
        fill(l, o.offset(-4, -1, -4), o.offset(4, 9, 4), Blocks.AIR.defaultBlockState());
        // A blockstate as an alpha.54 save stores it: no 'assembled', no 'cell'.
        var tag = new CompoundTag();
        tag.putString("Name", "bwr:rhr_heat_exchanger");
        var props = new CompoundTag();
        props.putString("facing", "east");
        tag.put("Properties", props);
        var saved = NbtUtils.readBlockState(l.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
        h.assertTrue(saved.is(HX) && !RhrHeatExchangerBlock.isAssembled(saved) && saved.getValue(RhrHeatExchangerBlock.FACING) == Direction.EAST, "old state did not load as a single block: " + saved);
        // Neighbours on every side stay as they are: loading a save never expands a single block.
        for (var d : Direction.values()) l.setBlock(o.relative(d), Blocks.STONE.defaultBlockState(), 3);
        l.setBlock(o, saved, 3);
        for (var d : Direction.values()) h.assertTrue(l.getBlockState(o.relative(d)).is(Blocks.STONE), "single block expanded into " + d);
        for (var d : Direction.values()) l.removeBlock(o.relative(d), false);
        // Its old block entity data (pre-alpha.54 keys, no ColdH/HotH) loads and its flanges are where they were.
        var be = new CompoundTag();
        be.putString("id", "bwr:rhr_heat_exchanger");
        be.putInt("x", o.getX()); be.putInt("y", o.getY()); be.putInt("z", o.getZ());
        be.putDouble("Cold", 1500); be.putDouble("Hot", 600); be.putDouble("HotEnergyKJ", 7000);
        l.removeBlockEntity(o);
        l.setBlockEntity(BlockEntity.loadStatic(o, saved, be, l.registryAccess()));
        var hx = (RhrHeatExchangerBlockEntity) l.getBlockEntity(o);
        h.assertTrue(hx != null && !hx.assembled(), "single block lost its block entity or claims to be assembled");
        near(h, hx.plant().cold(), 1500, 0, "old cold water");
        near(h, hx.plant().hot(), 600, 0, "old hot water");
        near(h, hx.plant().hotEnergyKJ(), 7000, 1e-6, "old hot energy");
        // Facing east: red east, amber west, blue north, cyan south, all on the one block; top and bottom are no ports.
        h.assertTrue(RhrHeatExchangerBlock.portOf(saved, Direction.EAST) == HxPort.TUBE_IN && RhrHeatExchangerBlock.portOf(saved, Direction.WEST) == HxPort.TUBE_OUT
                && RhrHeatExchangerBlock.portOf(saved, Direction.NORTH) == HxPort.SHELL_IN && RhrHeatExchangerBlock.portOf(saved, Direction.SOUTH) == HxPort.SHELL_OUT
                && RhrHeatExchangerBlock.portOf(saved, Direction.UP) == null, "single block flanges moved");
        var cold = l.getCapability(Capabilities.FluidHandler.BLOCK, o, Direction.NORTH);
        h.assertTrue(cold != null && cold.getFluidInTank(0).getAmount() == 1500, "old cold water not visible through the single block's shell inlet");
        h.assertTrue(HX.portPosition(o, saved, AssemblyPort.WATER_SUCTION).equals(o) && HX.complete(l, o, saved), "single block's tube side moved off the block");
        h.assertTrue(dev.bwr.mod.signal.DeviceResolver.resolve(l, o) == hx, "device lookup of the single block");
        // Picking it up returns the item; placing that item again builds the full-size unit.
        // Its drop is decided by the loot table for its state: exactly one exchanger item. (Item entities spawned into a fixture
        // chunk whose entity section is still loading are hidden from entity queries, so the loot itself is checked.)
        var drops = net.minecraft.world.level.block.Block.getDrops(l.getBlockState(o), l, o, l.getBlockEntity(o));
        h.assertTrue(drops.size() == 1 && drops.get(0).is(HX.asItem()) && drops.get(0).getCount() == 1, "single block does not drop exactly one exchanger: " + drops);
        breakAsPlayer(l, o);
        h.assertTrue(l.getBlockState(o).isAir() && l.getBlockEntity(o) == null, "the single block did not break: " + l.getBlockState(o));
        h.assertTrue(placeItem(l, o, Direction.EAST).consumesAction(), "the picked-up item did not place");
        var placed = l.getBlockState(o);
        h.assertTrue(RhrHeatExchangerBlock.isAssembled(placed) && HX.complete(l, o, placed), "placing the item again did not build the full-size unit");
        h.assertTrue(l.getBlockState(o.above(7)).is(HX), "full-size unit is not eight blocks tall");
        System.out.println("HX MULTIBLOCK LEGACY PASS: old state and NBT load as a single block, neighbours untouched, item re-places as 3x8x3");
    }
}
