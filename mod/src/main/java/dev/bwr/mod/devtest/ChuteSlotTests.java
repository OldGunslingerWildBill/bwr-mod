package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.reactor.VesselWaterGeometry;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.CanalExchange;
import dev.bwr.mod.refuelling.CanalExchange.State;
import dev.bwr.mod.refuelling.CanalFlow;
import dev.bwr.mod.refuelling.CanalGateBlock;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.refuelling.RefuellingRailBlock;
import dev.bwr.mod.refuelling.TransferCanalBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolLayout;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The cattle chute as a slot: canal stacks cut through a spent fuel pool's wall and a reactor well's wall in one row, so that with the
 * gates open the pool, the canal and the flooded well are one body of water ({@link CanalExchange}). Real blocks: a formed 5 x 5 x 8
 * reactor with its head off and a stone well touching it, a 7 x 7 pool two blocks east of the well with the slot in its west wall, and
 * the canal between. The exchange is stepped the way the pool's controller steps it, alone, so the inventories add up exactly. Namespace
 * {@code bwrbridge} ({@code -PbwrOnlyBridge}); own fixture origins.
 */
@GameTestHolder("bwrbridge")
@PrefixGameTestTemplate(false)
public final class ChuteSlotTests {
    private ChuteSlotTests() {
    }

    private static final double DT = 1.0 / 20.0;

    private static BlockPos origin(int i) {
        return new BlockPos(131_000 + 200 * i, 190, 133_000);
    }

    /**
     * The reactor, its well, the pool and the canal. The pool's floor block is {@code poolDy} above the vessel's bottom row and its walls
     * {@code poolH} high; the slot is cut from the rim down to the lowest row the pool allows (six above the pool's floor block), and the
     * well's wall and the canal between stand to the same top.
     */
    static final class Rig {
        final ServerLevel l;
        final CavitySealTests.Site s;
        final SpentFuelPoolFixture pool;
        final int x1;
        final int zc;
        final int slotBottom;
        final int top;
        /** The canal's columns from the slot (index 0) to the well's wall (index 2). */
        final int[] columnsX;

        Rig(GameTestHelper h, int index, int poolDy, int poolH, int racks) throws Exception {
            this(h.getLevel(), origin(index), poolDy, poolH, racks);
        }

        /** Also for the client check: no GameTest helper, a failed build throws. */
        Rig(ServerLevel level, BlockPos o, int poolDy, int poolH, int racks) throws Exception {
            this.l = level;
            for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
                for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                    FixtureChunks.force(l, o, cx, cz, true);
                    l.getChunk(cx, cz);
                }
            }
            this.s = new CavitySealTests.Site(l, o, 5, 5, 8);
            var env = s.env();
            x1 = env.max().getX();
            zc = (env.min().getZ() + env.max().getZ()) / 2;
            int bottom = s.bottom();
            int poolY = bottom + poolDy;
            pool = SpentFuelPoolFixture.build(l, new BlockPos(x1 + 3, poolY, zc - 3), 7, 7, poolH, racks);
            slotBottom = poolY + 1 + SpentFuelPoolLayout.RACK_HEIGHT + SpentFuelPoolLayout.SLOT_SILL_ABOVE_RACKS;
            top = poolY + poolH - 1;
            s.ring(1, 3, top - bottom);
            columnsX = new int[]{x1 + 3, x1 + 2, x1 + 1};
            for (int x : columnsX) {
                column(x, x == x1 + 3, true);
            }
            s.be.setVesselState(VesselState.REFUELING);
            s.survey();
            pool.be.scanNow();
            if (s.seal() == null || !s.seal().present() || !s.seal().wellUsable()) {
                throw new IllegalStateException("the well is usable with the canal in its wall: " + (s.seal() == null ? "no survey" : s.seal().english()));
            }
            if (!pool.be.isFormed()) {
                throw new IllegalStateException("the pool forms with the slot in its wall: " + english(pool.be.problem()));
            }
        }

        /** A stack of canal (or gate) blocks from the slot's bottom row to the top, along x. */
        void column(int x, boolean gate, boolean open) {
            for (int y = slotBottom; y <= top; y++) {
                var block = gate ? BwrBlocks.CANAL_GATE.get() : BwrBlocks.TRANSFER_CANAL.get();
                BlockState st = block.defaultBlockState().setValue(TransferCanalBlock.AXIS, Direction.Axis.X)
                        .setValue(TransferCanalBlock.TOP, y == top).setValue(TransferCanalBlock.FLOOR, y == slotBottom);
                if (gate) {
                    st = st.setValue(CanalGateBlock.OPEN, open);
                }
                l.setBlock(new BlockPos(x, y, zc), st, 2);
            }
        }

        void gate(boolean open) {
            for (int y = slotBottom; y <= top; y++) {
                BlockPos p = new BlockPos(columnsX[0], y, zc);
                l.setBlock(p, l.getBlockState(p).setValue(CanalGateBlock.OPEN, open), 3);
            }
        }

        BlockState at(int column, int y) {
            return l.getBlockState(new BlockPos(columnsX[column], y, zc));
        }

        ReactorControllerBlockEntity reactor() {
            return s.be;
        }

        PressureVessel vessel() {
            return s.be.core().getPressureVessel();
        }

        CanalExchange exchange() {
            return pool.be.canalExchange();
        }

        double sill() {
            return slotBottom + TransferCanalBlock.FLOOR_THICKNESS;
        }

        double poolFloor() {
            return pool.be.layout().min().getY() + 1;
        }

        /** The pool's water with its surface at world y {@code y}. */
        void poolAt(double y) {
            pool.be.pool().restore(1000.0 * pool.be.layout().footprintCells() * (y - poolFloor()), 25.0);
        }

        double poolY() {
            return poolFloor() + pool.be.pool().levelM();
        }

        /** The well's water with its surface at world y {@code y} (above the lip). */
        void wellAt(double y) {
            double lip = VesselWaterGeometry.rim(s.env());
            vessel().setCollapsedLevelIn(PressureVessel.FLANGE_LEVEL_IN + (y - s.bottom() - lip) / PressureVessel.METRES_PER_INCH);
        }

        double wellY() {
            return s.bottom() + s.be.wellSurfaceLocal();
        }

        double total() {
            return pool.be.pool().massKg() + vessel().getLiquidMassKg();
        }

        boolean step() {
            return exchange().step(l, pool.be, DT);
        }

        void finish() {
            pool.dispose();
            s.clear();
            for (int cx = (s.min.getX() - 24) >> 4; cx <= (s.min.getX() + 40) >> 4; cx++) {
                for (int cz = (s.min.getZ() - 24) >> 4; cz <= (s.min.getZ() + 24) >> 4; cz++) {
                    FixtureChunks.force(l, s.min, cx, cz, false);
                }
            }
        }
    }

    private static String english(Component c) {
        return c == null ? "(none)" : BwrText.english(c);
    }

    private static void near(GameTestHelper h, double actual, double expected, double tolerance, String what) {
        h.assertTrue(Math.abs(actual - expected) <= tolerance, what + ": expected " + expected + " +-" + tolerance + " got " + actual);
    }

    // ---------------------------------------------------------------------------------------------------- the law

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 100)
    public static void theSlotsFlowLawIsTheDrainPortsWithTheSlotsCrossSection(GameTestHelper h) {
        near(h, CanalFlow.kgPerS(1.0, 2.0), 0.6 * 0.75 * 2.0 * 1000.0 * Math.sqrt(2 * 9.81), 1.0e-9, "1 m of head over 2 m of slot");
        near(h, CanalFlow.kgPerS(4.0, 2.0), 2.0 * CanalFlow.kgPerS(1.0, 2.0), 1.0e-9, "four times the head, twice the flow");
        near(h, CanalFlow.kgPerS(1.0, 1.0), 0.5 * CanalFlow.kgPerS(1.0, 2.0), 1.0e-9, "half the slot, half the flow");
        h.assertTrue(CanalFlow.kgPerS(50.0, 20.0) == CanalFlow.LIMIT_KG_PER_S, "capped at 20,000 kg/s");
        h.assertTrue(CanalFlow.kgPerS(0.0, 2.0) == 0.0 && CanalFlow.kgPerS(1.0, 0.0) == 0.0 && CanalFlow.kgPerS(Double.NaN, 1.0) == 0.0,
                "no head or no water over the sill: no flow");
        h.assertTrue(CanalExchange.fill(10.5, 10, false, false) == 8 && CanalExchange.fill(12.0, 10, false, false) == 16
                && CanalExchange.fill(12.0, 11, true, false) == 15 && CanalExchange.fill(10.1, 10, false, true) == 0
                && CanalExchange.fill(9.0, 10, false, false) == 0, "the canal block's water follows the level");
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the slot rules

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void aPoolFormsWithASlotInItsWallAndTheWellKeepsItsSeal(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 0, 4, 8, 2);
        try {
            var layout = r.pool.be.layout();
            h.assertTrue(layout.slots().size() == 1, "one slot: " + layout.slots());
            var slot = layout.slots().get(0);
            h.assertTrue(slot.x() == r.columnsX[0] && slot.z() == r.zc && slot.bottomY() == r.slotBottom && slot.topY() == r.top
                    && slot.outward() == Direction.WEST, "the slot is where it was cut: " + slot);
            h.assertTrue(layout.racks().size() == 2 && layout.footprintCells() == 25, "the pool is the same pool: " + layout.racks().size());
            String report = String.join(" ", BwrText.english(r.pool.be.statusComponents()));
            h.assertTrue(report.contains("slot") && report.contains("sill"), "the survey reports the slot: " + report);
            var seal = r.s.seal();
            h.assertTrue(seal.sealRow() == seal.flangeRow() && seal.wellTopRow() == r.top,
                    "the seal at the flange and the well up to the top of its wall: seal " + seal.sealRow() + " top " + seal.wellTopRow());
            r.step();
            h.assertTrue(r.exchange().state() != State.NO_ROW && r.exchange().state() != State.NO_WELL && r.exchange().reactorPos() != null
                    && r.exchange().columns().size() == 3, "the slot reaches the well through the canal: " + r.exchange().state());
            // a pool without a slot reports none
            for (int y = r.slotBottom; y <= r.top; y++) {
                r.l.setBlock(new BlockPos(r.columnsX[0], y, r.zc), BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
            }
            r.pool.be.scanNow();
            h.assertTrue(r.pool.be.isFormed() && r.pool.be.layout().slots().isEmpty(), "lined again: no slot");
            r.step();
            h.assertTrue(r.exchange().state() == State.NO_SLOT, "and nothing to exchange: " + r.exchange().state());
        } finally {
            r.finish();
        }
        h.succeed();
    }

    private static void refused(GameTestHelper h, SpentFuelPoolFixture f, String word, String what) {
        f.be.scanNow();
        String why = english(f.be.problem());
        h.assertTrue(!f.be.isFormed() && why.contains(word), what + ": " + (f.be.isFormed() ? "formed" : why));
    }

    private static void canal(ServerLevel l, BlockPos p, Direction.Axis axis) {
        l.setBlock(p, BwrBlocks.TRANSFER_CANAL.get().defaultBlockState().setValue(TransferCanalBlock.AXIS, axis), 2);
    }

    private static void lining(ServerLevel l, BlockPos p) {
        l.setBlock(p, BwrBlocks.SPENT_FUEL_POOL_WALL.get().defaultBlockState(), 2);
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void badSlotsAreRefusedWithAReason(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        SpentFuelPoolFixture f = SpentFuelPoolFixture.build(l, origin(1), 7, 7, 8, 0);
        try {
            h.assertTrue(f.be.isFormed(), "the plain pool forms");
            BlockPos min = f.min;
            int topY = min.getY() + 7;
            // in the floor
            canal(l, min.offset(3, 0, 3), Direction.Axis.X);
            refused(h, f, "floor", "a canal in the floor");
            lining(l, min.offset(3, 0, 3));
            // at a corner, from the rim down
            for (int y = 6; y <= 7; y++) {
                canal(l, min.offset(6, y, 6), Direction.Axis.X);
            }
            refused(h, f, "corner", "a slot at a corner");
            for (int y = 6; y <= 7; y++) {
                lining(l, min.offset(6, y, 6));
            }
            // not from the rim: a canal block with lining over it
            canal(l, min.offset(6, 6, 3), Direction.Axis.X);
            refused(h, f, "straight stack", "a window, not a slot from the rim");
            // turned along the wall
            canal(l, min.offset(6, 7, 3), Direction.Axis.Z);
            refused(h, f, "Turn", "a canal along the wall, not through it");
            canal(l, min.offset(6, 7, 3), Direction.Axis.X);
            f.be.scanNow();
            h.assertTrue(f.be.isFormed() && f.be.layout().slots().size() == 1, "a straight slot through the wall forms: " + english(f.be.problem()));
            // too deep: its lowest block one row lower, level with the top of the racks plus nothing
            canal(l, min.offset(6, 5, 3), Direction.Axis.X);
            refused(h, f, "too deep", "a slot down to the rack tops");
            h.assertTrue(english(f.be.problem()).contains("y " + (min.getY() + 6)), "and says how high: " + english(f.be.problem()));
            lining(l, min.offset(6, 5, 3));
            f.be.scanNow();
            h.assertTrue(f.be.isFormed(), "lined under the slot it forms again");
            h.assertTrue(topY == f.be.layout().max().getY(), "the rim");
        } finally {
            f.dispose();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the water

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void anOpenGateEqualisesThePoolAndTheWellWithExactMass(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 2, 4, 8, 0);
        try {
            double sill = r.sill();
            r.poolAt(r.top + 1.0);
            r.wellAt(r.slotBottom + 0.5);
            near(h, r.wellY(), r.slotBottom + 0.5, 0.01, "the well's surface");
            double total = r.total();
            double pool0 = r.pool.be.pool().massKg();
            double head = r.poolY() - r.wellY();
            double expected = CanalFlow.kgPerS(head, r.top + 1.0 - sill);
            r.step();
            h.assertTrue(r.exchange().state() == State.TO_WELL, "the pool runs into the well: " + r.exchange().state());
            near(h, r.exchange().flowKgPerS(), expected, 20.0 + 1.0e-6, "the law with the slot's cross-section");
            near(h, r.total(), total, 1.0e-6, "what the pool lost the well gained");
            int steps = 1;
            double lastPool = r.pool.be.pool().massKg();
            while (r.exchange().state() == State.TO_WELL && steps < 4000) {
                r.step();
                steps++;
                h.assertTrue(r.pool.be.pool().massKg() <= lastPool, "never back the other way, step " + steps);
                lastPool = r.pool.be.pool().massKg();
                near(h, r.total(), total, 1.0e-6, "mass at step " + steps);
            }
            h.assertTrue(r.exchange().state() == State.LEVEL, "level after " + steps + " steps: " + r.exchange().state());
            near(h, r.poolY(), r.wellY(), 0.005, "one surface");
            h.assertTrue(r.poolY() > sill && r.pool.be.pool().massKg() < pool0, "the pool went down to it");
            near(h, -r.exchange().totalKg(), r.pool.be.pool().massKg() - pool0, 1.0e-6, "the exchange counted it");
            // the canal stands at the shared level
            double shared = Math.max(r.poolY(), r.wellY());
            h.assertTrue(r.at(1, r.slotBottom).getValue(TransferCanalBlock.WATER), "the canal holds water");
            for (int c = 0; c < 3; c++) {
                for (int y = r.slotBottom; y <= r.top; y++) {
                    int want = CanalExchange.fill(shared, y, y == r.top, y == r.slotBottom);
                    h.assertTrue(r.at(c, y).getValue(TransferCanalBlock.FILL) == want && r.at(c, y).getValue(TransferCanalBlock.WATER) == (want > 0),
                            "the canal's water follows the level at column " + c + " y " + y + ": " + r.at(c, y) + ", want fill " + want);
                }
            }
            // and the other way: the well higher
            r.wellAt(r.top + 0.8);
            r.poolAt(r.slotBottom + 0.6);
            total = r.total();
            double poolBefore = r.pool.be.pool().massKg();
            r.step();
            h.assertTrue(r.exchange().state() == State.TO_POOL && r.exchange().flowKgPerS() < 0, "the well runs into the pool: " + r.exchange().state());
            for (int i = 0; i < 4000 && r.exchange().state() == State.TO_POOL; i++) {
                r.step();
            }
            near(h, r.total(), total, 1.0e-6, "mass the other way");
            h.assertTrue(r.pool.be.pool().massKg() > poolBefore && r.exchange().state() == State.LEVEL, "the pool rose: " + r.exchange().state());
            near(h, r.poolY(), r.wellY(), 0.005, "one surface again");
            System.out.println("CHUTE SLOT: levelled in " + steps + " steps (" + steps / 20.0 + " s), first flow " + Math.round(expected)
                    + " kg/s at " + String.format(java.util.Locale.ROOT, "%.2f", head) + " m of head");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void aClosedGateHoldsTheLevelsApart(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 3, 4, 8, 0);
        try {
            r.gate(false);
            r.poolAt(r.top + 1.0);
            r.wellAt(r.slotBottom + 0.5);
            double pool = r.pool.be.pool().massKg();
            double vessel = r.vessel().getLiquidMassKg();
            for (int i = 0; i < 200; i++) {
                r.step();
            }
            h.assertTrue(r.exchange().state() == State.GATE_CLOSED, "closed: " + r.exchange().state());
            h.assertTrue(r.pool.be.pool().massKg() == pool && r.vessel().getLiquidMassKg() == vessel, "not a kilogram moved");
            // the slot (the gate's column) stands at the pool's level, the canal behind it at the well's
            h.assertTrue(r.at(0, r.top).getValue(TransferCanalBlock.FILL) == 15, "the slot is brim full with the pool: " + r.at(0, r.top));
            h.assertTrue(r.at(2, r.slotBottom).getValue(TransferCanalBlock.FILL) == 8 && !r.at(2, r.top).getValue(TransferCanalBlock.WATER),
                    "the canal on the well's side stands at the well: " + r.at(2, r.slotBottom) + " / " + r.at(2, r.top));
            r.gate(true);
            // the pool's own tick steps it
            r.pool.be.tickForTest(r.l.getGameTime());
            h.assertTrue(r.exchange().state() == State.TO_WELL && r.pool.be.pool().massKg() < pool, "opened, it runs: " + r.exchange().state());
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void waterBelowTheSillDoesNotMove(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 4, 4, 8, 0);
        try {
            double sill = r.sill();
            r.poolAt(sill - 0.1);
            r.wellAt(sill - 0.6);
            double pool = r.pool.be.pool().massKg();
            double vessel = r.vessel().getLiquidMassKg();
            for (int i = 0; i < 100; i++) {
                r.step();
            }
            h.assertTrue(r.exchange().state() == State.BELOW_SILL, "both below the sill: " + r.exchange().state());
            h.assertTrue(r.pool.be.pool().massKg() == pool && r.vessel().getLiquidMassKg() == vessel, "nothing moved");
            for (int c = 0; c < 3; c++) {
                h.assertTrue(!r.at(c, r.slotBottom).getValue(TransferCanalBlock.WATER), "the canal is drained, column " + c);
            }
            // the pool over the sill, the well under it: only the water over the sill goes
            r.poolAt(sill + 0.4);
            double total = r.total();
            r.step();
            h.assertTrue(r.exchange().state() == State.TO_WELL, "the water over the sill runs: " + r.exchange().state());
            for (int i = 0; i < 4000 && r.exchange().state() != State.BELOW_SILL; i++) {
                r.step();
            }
            h.assertTrue(r.exchange().state() == State.BELOW_SILL, "it stops at the sill: " + r.exchange().state());
            h.assertTrue(r.poolY() >= sill - 1.0e-9 && r.poolY() < sill + 0.002, "the pool stands at the sill: " + (r.poolY() - sill));
            h.assertTrue(r.wellY() < sill, "the well took it and is still under the sill: " + (r.wellY() - sill));
            near(h, r.total(), total, 1.0e-6, "mass");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void aDrainedCanalRefillsFromTheSideAboveItsFloor(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 5, 4, 8, 0);
        try {
            r.poolAt(r.top + 0.5);
            r.wellAt(r.top + 0.5);
            r.step();
            // a bucket drains the middle column
            for (int y = r.slotBottom; y <= r.top; y++) {
                BlockPos p = new BlockPos(r.columnsX[1], y, r.zc);
                r.l.setBlock(p, r.l.getBlockState(p).setValue(TransferCanalBlock.WATER, false).setValue(TransferCanalBlock.FILL, 0), 3);
            }
            r.step();
            h.assertTrue(r.at(1, r.slotBottom).getValue(TransferCanalBlock.WATER) && r.at(1, r.top).getValue(TransferCanalBlock.FILL) == 8,
                    "refilled to the connected level: " + r.at(1, r.top));
            // both sides under its floor: a bucket of water poured in runs out
            r.poolAt(r.sill() - 0.3);
            r.wellAt(r.sill() - 0.3);
            for (int y = r.slotBottom; y <= r.top; y++) {
                BlockPos p = new BlockPos(r.columnsX[1], y, r.zc);
                r.l.setBlock(p, r.l.getBlockState(p).setValue(TransferCanalBlock.WATER, true).setValue(TransferCanalBlock.FILL, 0), 3);
            }
            r.step();
            h.assertTrue(!r.at(1, r.slotBottom).getValue(TransferCanalBlock.WATER), "drained when both sides are under its floor");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void aSlotBelowTheSealRowIsNotJoinedToTheWell(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 6, 1, 8, 0);
        try {
            h.assertTrue(r.slotBottom < r.s.seal().sealRow(), "the slot's floor is under the seal: " + r.slotBottom + " vs " + r.s.seal().sealRow());
            h.assertTrue(r.s.seal().present() && r.s.seal().wellUsable(), "the canal in the wall does not open the well to the drywell: " + r.s.seal().english());
            r.poolAt(r.top + 1.0);
            double pool = r.pool.be.pool().massKg();
            r.step();
            h.assertTrue(r.exchange().state() == State.BELOW_SEAL && r.pool.be.pool().massKg() == pool, "refused, nothing moves: " + r.exchange().state());
            String report = String.join(" ", BwrText.english(r.pool.be.statusComponents()));
            h.assertTrue(report.contains("seal"), "and the pool says why: " + report);
        } finally {
            r.finish();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- save and reload

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void anExchangeSavedHalfWayCarriesOnAfterAReload(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 7, 4, 8, 0);
        try {
            r.poolAt(r.top + 1.0);
            r.wellAt(r.slotBottom + 0.5);
            double total = r.total();
            for (int i = 0; i < 20; i++) {
                r.step();
            }
            h.assertTrue(r.exchange().state() == State.TO_WELL, "running: " + r.exchange().state());
            double pool = r.pool.be.pool().massKg();
            double vessel = r.vessel().getLiquidMassKg();
            CompoundTag reactorTag = r.s.be.saveWithFullMetadata(r.l.registryAccess());
            CompoundTag poolTag = r.pool.be.saveWithFullMetadata(r.l.registryAccess());
            // the reactor, as after a restart
            BlockPos rp = r.s.be.getBlockPos();
            var rs = r.s.be.getBlockState();
            r.l.removeBlockEntity(rp);
            BlockEntity restored = BlockEntity.loadStatic(rp, rs, reactorTag, r.l.registryAccess());
            r.l.setBlockEntity(restored);
            r.s.be = (ReactorControllerBlockEntity) restored;
            var revalidate = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
            revalidate.setAccessible(true);
            revalidate.invoke(r.s.be, r.l);
            r.s.be.finishCavitySurvey();
            // the pool
            r.l.removeBlockEntity(r.pool.controller);
            var poolBe = (SpentFuelPoolBlockEntity) BlockEntity.loadStatic(r.pool.controller, r.l.getBlockState(r.pool.controller), poolTag, r.l.registryAccess());
            r.l.setBlockEntity(poolBe);
            poolBe.scanNow();
            r.pool.be = poolBe;
            h.assertTrue(r.s.be.isFormed() && poolBe.isFormed(), "both form again");
            h.assertTrue(poolBe.pool().massKg() == pool, "the pool's water is what was saved: " + (poolBe.pool().massKg() - pool));
            near(h, r.vessel().getLiquidMassKg(), vessel, 1.0e-6 * vessel, "the vessel's inventory is what was saved");
            r.step();
            h.assertTrue(r.exchange().state() == State.TO_WELL, "it runs on: " + r.exchange().state());
            for (int i = 0; i < 4000 && r.exchange().state() == State.TO_WELL; i++) {
                r.step();
            }
            h.assertTrue(r.exchange().state() == State.LEVEL, "and levels: " + r.exchange().state());
            near(h, r.total(), total, 1.0e-6 * total, "with the mass of before the save");
        } finally {
            r.finish();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------------------------------------------- the bridge

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void theBridgeCarriesFuelThroughTheSlot(GameTestHelper h) throws Exception {
        Rig r = new Rig(h, 8, 3, 15, 2);
        RefuellingBridgeBlockEntity bridge = null;
        try {
            var env = r.s.env();
            int railY = r.top + 1;
            for (int x = env.min().getX() - 2; x <= r.columnsX[0] + 8; x++) {
                for (int z : new int[]{r.zc - 5, r.zc + 5}) {
                    r.l.setBlock(new BlockPos(x, railY, z), BwrBlocks.REFUELLING_RAIL.get().defaultBlockState()
                            .setValue(RefuellingRailBlock.AXIS, Direction.Axis.X), 2);
                }
            }
            BlockPos console = new BlockPos(env.min().getX() - 2, railY, r.zc - 6);
            r.l.setBlock(console, BwrBlocks.REFUELLING_BRIDGE_CONSOLE.get().defaultBlockState(), 2);
            bridge = (RefuellingBridgeBlockEntity) r.l.getBlockEntity(console);
            r.s.be.internalOut(VesselInternal.DRYER, console);
            r.s.be.internalOut(VesselInternal.SEPARATOR, console);
            // flood the well to the pool's level and let the slot make them one
            r.poolAt(railY - 0.4);
            r.wellAt(railY - 0.4);
            r.step();
            BridgeSite site = bridge.refreshSite();
            h.assertTrue(site.ok() && site.reactor(r.l) != null && site.pool(r.l) != null, "the bridge sees both: " + english(site.problem));
            var chute = bridge.chute();
            h.assertTrue(chute.state() == BridgeSite.ChuteState.OPEN && chute.slot(), "an open slot chute: " + chute.state() + " slot " + chute.slot());
            near(h, chute.surfaceY(), r.poolY(), 1.0 / 16.0 + 1.0e-9, "its surface is the connected level");
            near(h, chute.floorY(), r.sill(), 1.0e-9, "its floor is the slot's sill");
            int slot = r.s.be.corePositions()[0];
            h.assertTrue(r.s.be.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0)), "load");
            // a shut gate in the slot refuses
            r.gate(false);
            bridge.refreshSite();
            h.assertTrue(bridge.chute().state() == BridgeSite.ChuteState.CLOSED, "the slot's gate closes the chute");
            Move move = new Move(Loc.core(slot), Loc.rack(r.pool.racks.get(0), 0));
            Component why = bridge.queue(move);
            h.assertTrue(why != null && english(why).contains("closed"), "refused for the gate: " + english(why));
            r.gate(true);
            bridge.refreshSite();
            why = bridge.queue(move);
            h.assertTrue(why == null, "queued: " + english(why));
            int n = 0;
            boolean throughSlot = false;
            while (bridge.busy() && n < 40_000) {
                if (bridge.phase() == Phase.TRANSIT) {
                    var tip = bridge.tip();
                    throughSlot |= Math.floor(tip.x()) == r.columnsX[0] && Math.floor(tip.z()) == r.zc && tip.y() < r.top;
                }
                bridge.scheduledTick(r.l);
                n++;
            }
            h.assertTrue(!bridge.busy() && bridge.phase() == Phase.IDLE, "the move finished: " + bridge.phase() + " " + english(bridge.haltMessage()));
            h.assertTrue(!r.pool.be.stackAt(r.pool.racks.get(0), 0).isEmpty() && !r.s.be.core().getCoreLoading().isOccupied(slot),
                    "the assembly is in the rack");
            h.assertTrue(throughSlot, "it went through the slot in the wall, under its top");
        } finally {
            if (bridge != null) {
                bridge.removalDrops();
                r.l.setBlock(bridge.getBlockPos(), Blocks.AIR.defaultBlockState(), 2);
            }
            r.finish();
        }
        h.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "bwrbridge", timeoutTicks = 400)
    public static void anOldStyleChuteIsLeftAsItWas(GameTestHelper h) throws Exception {
        RefuellingBridgeFixture f = RefuellingBridgeFixture.build(h.getLevel(), origin(9), 4);
        try {
            f.fillPool(7.0);
            f.bridge.refreshSite();
            h.assertTrue(f.bridge.chute().state() == BridgeSite.ChuteState.OPEN && !f.bridge.chute().slot(), "an old chute, no slot");
            BlockState before = f.level.getBlockState(f.canalNear);
            for (int i = 0; i < 60; i++) {
                f.poolBe().tickForTest(f.level.getGameTime() + i);
            }
            h.assertTrue(f.poolBe().canalExchange().state() == State.NO_SLOT, "the pool has no slot: " + f.poolBe().canalExchange().state());
            h.assertTrue(f.level.getBlockState(f.canalNear) == before && before.getValue(TransferCanalBlock.FILL) == 0,
                    "the canal's water is its own, as before: " + f.level.getBlockState(f.canalNear));
            near(h, f.bridge.chute().surfaceY(), f.canalNear.getY() + TransferCanalBlock.SURFACE_IN_BLOCK, 1.0e-9, "the old surface");
            int slot = f.corePosition(0);
            f.reactor.loadAssembly(slot, RefuellingBridgeFixture.fresh(30000.0));
            h.assertTrue(f.bridge.queue(new Move(Loc.core(slot), Loc.rack(f.pool.racks.get(0), 0))) == null, "queued");
            f.runUntilStill(40_000);
            h.assertTrue(!f.pool.be.stackAt(f.pool.racks.get(0), 0).isEmpty(), "and the old chute still carries fuel: " + f.bridge.phase());
        } finally {
            f.dispose();
        }
        h.succeed();
    }
}
