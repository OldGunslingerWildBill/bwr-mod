package dev.bwr.mod.refuelling;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolLayout;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The water of a cattle chute cut as a slot through a spent fuel pool's wall and the reactor well's wall: with every gate in the row
 * open, the pool, the canal and the well are one body of water, and the water over the slot's floor (the <b>sill</b>) runs from the
 * higher surface to the lower ({@link CanalFlow}). Physics only: nothing here opens or closes a gate (a player's hand does, or a
 * program through the bridge's peripheral), and nothing acts on what it finds.
 *
 * <h2>Who steps it</h2>
 * The pool's controller, once a tick, from its own tick; only a pool whose survey found a slot in its wall
 * ({@link SpentFuelPoolLayout.Slot}) does anything here. No canal block ticks. From the slot it reads the row of canal stacks outward
 * (as many as the stack, a few dozen block reads) up to the first column that is not canal, which must be inside the footprint of a
 * formed reactor ({@link FormedReactorRegistry}); the well is that reactor's surveyed well ({@code CavitySeal}). A block in a chunk that
 * is not loaded, or a reactor that is not, moves nothing and changes no block ({@link State#WAITING}); no chunk is ever loaded for it.
 *
 * <h2>The water</h2>
 * The pool's surface is its own level; the well's is the reactor's ({@code ReactorControllerBlockEntity.wellSurfaceLocal}, the water
 * over the flange in the annulus and the well). What moves leaves one inventory and enters the other in whole kilograms, so the pool's
 * water and the vessel's {@code liquidMassKg} add up to the same before and after: from the pool through
 * {@code SpentFuelPool.restore}, into the vessel through {@code restoreLiquidMassKg}; from the well through
 * {@code PressureVessel.drainWellWaterKg} (never below the sill), into the pool through {@code receiveWaterKg} at the coolant's
 * temperature (at most 100 C). A step never takes more than brings the two surfaces level, never more than stands over the sill,
 * and never more than the receiving side has room for (the pool's rim, the well's top). The canal blocks' water follows: a column
 * holds water to the level of the side it is open to, and is drained when that side is below its floor (both sides, with the gates open).
 */
public final class CanalExchange {

    /** Most canal columns read from the slot toward the reactor (the bridge's widest span). */
    public static final int MAX_COLUMNS = BridgeSite.MAX_SPAN;
    /** Most canal blocks read up or down one column. */
    public static final int MAX_STACK = 32;
    /** Surfaces closer than this are level, and water less than this over the sill stays: nothing flows. */
    public static final double LEVEL_M = 0.001;

    /** What the last step found. */
    public enum State {
        NO_SLOT("no_slot", "No cattle chute slot in the pool's wall."),
        NO_ROW("no_row", "The canal from the slot does not reach a reactor: a row of canal stacks must run from the slot to the vessel's footprint."),
        NO_REACTOR("no_reactor", "The reactor at the end of the canal is not formed."),
        HEAD_ON("head_on", "The vessel head is on: the well is closed to the canal."),
        NO_WELL("no_well", "The reactor has no usable well where the canal meets it (no cavity seal, or the wall opens below the flange)."),
        BELOW_SEAL("below_seal", "The canal's floor is below the cavity seal row: build it higher, at or above the seal."),
        ABOVE_WELL("above_well", "The canal's floor is above the top of the well."),
        WAITING("waiting", "Waiting for chunks to load: nothing moves."),
        GATE_CLOSED("gate_closed", "A gate is closed: the pool and the well are apart."),
        BELOW_SILL("below_sill", "Both surfaces are below the canal's floor: nothing to pass."),
        LEVEL("level", "Open and level: the pool, the canal and the well stand at one surface."),
        TO_WELL("to_well", "Water runs from the pool into the well."),
        TO_POOL("to_pool", "Water runs from the well into the pool."),
        FULL("full", "The lower side is full (the pool's rim or the well's top): nothing more goes in.");

        private final String key;
        private final String english;

        State(String key, String english) {
            this.key = key;
            this.english = english;
        }

        public String key() {
            return key;
        }

        public Component text() {
            return BwrText.tr("status.bwr.canal_exchange.state." + key, english);
        }
    }

    /** One stacked column of the canal: its lowest and highest canal block, and whether a gate in it is closed. */
    public record Column(int x, int z, int bottomY, int topY, boolean closed) {
        public double floorY() {
            return bottomY + TransferCanalBlock.FLOOR_THICKNESS;
        }
    }

    private State state = State.NO_SLOT;
    private double flowKgPerS;
    private double poolSurfaceY = Double.NaN;
    private double wellSurfaceY = Double.NaN;
    private double sillY = Double.NaN;
    private double headM;
    private double carryKg;
    private double totalKg;
    @Nullable
    private BlockPos reactorPos;
    private List<Column> columns = List.of();

    public State state() {
        return state;
    }

    /** The flow on the last step, kg/s: positive from the pool into the well, negative the other way. */
    public double flowKgPerS() {
        return flowKgPerS;
    }

    public double poolSurfaceY() {
        return poolSurfaceY;
    }

    /** World y of the well's water over the flange; NaN when it holds none. */
    public double wellSurfaceY() {
        return wellSurfaceY;
    }

    public double sillY() {
        return sillY;
    }

    public double headM() {
        return headM;
    }

    /** Water passed since the pool loaded, kg: positive is net from the pool into the well. */
    public double totalKg() {
        return totalKg;
    }

    @Nullable
    public BlockPos reactorPos() {
        return reactorPos;
    }

    /** The canal's columns from the slot (first) to the one at the well's wall (last), as last found. */
    public List<Column> columns() {
        return columns;
    }

    private void idle(State why) {
        state = why;
        flowKgPerS = 0.0;
        headM = 0.0;
        carryKg = 0.0;
    }

    // ---------------------------------------------------------------
    // The row
    // ---------------------------------------------------------------

    /** The row read from one slot: its columns and the reactor at its end, or why there is none. */
    private record Row(@Nullable State problem, List<Column> columns, @Nullable ReactorControllerBlockEntity reactor, int endX, int endZ) {
    }

    @Nullable
    private static Column column(Level level, int x, int z, int seedY) {
        BlockPos seed = new BlockPos(x, seedY, z);
        if (!level.isLoaded(seed)) {
            return null;
        }
        int top = seedY;
        boolean closed = false;
        for (int n = 0; n < MAX_STACK; n++) {
            BlockPos p = new BlockPos(x, top + 1, z);
            if (!level.isLoaded(p) || !(level.getBlockState(p).getBlock() instanceof TransferCanalBlock)) {
                break;
            }
            top++;
        }
        int bottom = seedY;
        for (int n = 0; n < MAX_STACK; n++) {
            BlockPos p = new BlockPos(x, bottom - 1, z);
            if (!level.isLoaded(p) || !(level.getBlockState(p).getBlock() instanceof TransferCanalBlock)) {
                break;
            }
            bottom--;
        }
        for (int y = bottom; y <= top; y++) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.getBlock() instanceof CanalGateBlock && !s.getValue(CanalGateBlock.OPEN)) {
                closed = true;
            }
        }
        return new Column(x, z, bottom, top, closed);
    }

    private static boolean inFootprint(ReactorControllerBlockEntity r, int x, int z) {
        var e = r.vesselEnvelope();
        return e != null && x >= e.min().getX() && x <= e.max().getX() && z >= e.min().getZ() && z <= e.max().getZ();
    }

    private Row walk(Level level, SpentFuelPoolLayout.Slot slot) {
        List<Column> cols = new ArrayList<>();
        Column first = column(level, slot.x(), slot.z(), slot.bottomY());
        if (first == null) {
            return new Row(State.WAITING, List.of(), null, 0, 0);
        }
        // the slot itself is the pool's survey: from its lowest block to the rim
        cols.add(new Column(slot.x(), slot.z(), slot.bottomY(), slot.topY(), first.closed()));
        Direction out = slot.outward();
        for (int i = 1; i <= MAX_COLUMNS; i++) {
            int x = slot.x() + out.getStepX() * i;
            int z = slot.z() + out.getStepZ() * i;
            BlockPos at = new BlockPos(x, slot.bottomY(), z);
            if (!level.isLoaded(at)) {
                return new Row(State.WAITING, List.of(), null, 0, 0);
            }
            if (level.getBlockState(at).getBlock() instanceof TransferCanalBlock) {
                Column c = column(level, x, z, slot.bottomY());
                if (c == null) {
                    return new Row(State.WAITING, List.of(), null, 0, 0);
                }
                cols.add(c);
                continue;
            }
            // the end of the canal: the vessel's footprint, under the well
            ReactorControllerBlockEntity found = null;
            if (reactorPos != null && LoadedWorld.blockEntity(level, reactorPos) instanceof ReactorControllerBlockEntity r
                    && !r.isRemoved() && r.isFormed() && inFootprint(r, x, z)) {
                found = r;
            } else {
                for (ReactorControllerBlockEntity r : FormedReactorRegistry.controllers(level)) {
                    if (inFootprint(r, x, z)) {
                        found = r;
                        break;
                    }
                }
            }
            if (found == null) {
                return new Row(State.NO_ROW, List.copyOf(cols), null, x, z);
            }
            return new Row(null, List.copyOf(cols), found, x, z);
        }
        return new Row(State.NO_ROW, List.copyOf(cols), null, 0, 0);
    }

    // ---------------------------------------------------------------
    // The step
    // ---------------------------------------------------------------

    /**
     * One tick of the slot's water for {@code pool}, {@code dt} seconds. Returns whether water moved (the pool redraws its surface).
     * Called by the pool's controller only; tests call it to step the exchange alone.
     */
    public boolean step(Level level, SpentFuelPoolBlockEntity pool, double dt) {
        var layout = pool.layout();
        if (level == null || level.isClientSide() || !pool.isFormed() || layout == null || layout.slots().isEmpty()) {
            idle(State.NO_SLOT);
            columns = List.of();
            reactorPos = null;
            return false;
        }
        Row row = null;
        SpentFuelPoolLayout.Slot slot = null;
        for (SpentFuelPoolLayout.Slot s : layout.slots()) {
            Row r = walk(level, s);
            if (row == null || r.problem() == null) {
                row = r;
                slot = s;
            }
            if (r.problem() == null) {
                break;
            }
        }
        if (row.problem() == State.WAITING) {
            // keep the last answer: nothing moves and no block changes while part of it is not loaded
            flowKgPerS = 0.0;
            headM = 0.0;
            state = State.WAITING;
            return false;
        }
        columns = row.columns();
        if (row.problem() != null) {
            idle(row.problem());
            reactorPos = null;
            return false;
        }
        ReactorControllerBlockEntity r = row.reactor();
        reactorPos = r.getBlockPos().immutable();
        var core = r.core();
        var envelope = r.vesselEnvelope();
        var cavity = r.cavity();
        if (core == null || envelope == null || !r.isFormed()) {
            idle(State.NO_REACTOR);
            return false;
        }
        if (r.headSealed()) {
            idle(State.HEAD_ON);
            return false;
        }
        if (cavity == null || !cavity.present() || !cavity.wellUsable() || cavity.profile() == null || cavity.visual() == null
                || cavity.visual().well() == null) {
            idle(State.NO_WELL);
            return false;
        }
        int sillRow = Integer.MIN_VALUE;
        int topRow = Integer.MAX_VALUE;
        int firstClosed = -1;
        int lastClosed = -1;
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            sillRow = Math.max(sillRow, c.bottomY());
            topRow = Math.min(topRow, c.topY());
            if (c.closed()) {
                if (firstClosed < 0) {
                    firstClosed = i;
                }
                lastClosed = i;
            }
        }
        double sill = sillRow + TransferCanalBlock.FLOOR_THICKNESS;
        sillY = sill;
        if (sillRow < cavity.sealRow()) {
            idle(State.BELOW_SEAL);
            return false;
        }
        if (sillRow > cavity.wellTopRow()) {
            idle(State.ABOVE_WELL);
            return false;
        }
        if (!cavity.visual().well().cell(sillRow, row.endX(), row.endZ())) {
            idle(State.NO_WELL);
            return false;
        }
        var vessel = core.getPressureVessel();
        var profile = cavity.profile();
        var water = pool.pool();
        double area = Math.max(1, layout.footprintCells());
        double poolFloor = layout.min().getY() + 1;
        double rim = poolFloor + layout.heightCells();
        double poolY = poolFloor + water.levelM();
        double envY = envelope.min().getY();
        double wellWater = vessel.getWellWaterM3();
        double wellY = wellWater > 0 ? envY + r.wellSurfaceLocal() : Double.NEGATIVE_INFINITY;
        poolSurfaceY = poolY;
        wellSurfaceY = wellWater > 0 ? wellY : Double.NaN;

        boolean moved = false;
        if (firstClosed >= 0) {
            idle(State.GATE_CLOSED);
        } else {
            moved = flow(r, pool, dt, sill, topRow + 1.0, area, poolFloor, rim, poolY, wellY, envY);
        }
        if (moved) {
            poolY = poolFloor + water.levelM();
            wellWater = vessel.getWellWaterM3();
            wellY = wellWater > 0 ? envY + r.wellSurfaceLocal() : Double.NEGATIVE_INFINITY;
            poolSurfaceY = poolY;
            wellSurfaceY = wellWater > 0 ? wellY : Double.NaN;
            pool.setChanged();
            r.setChanged();
        }
        level(level, firstClosed, lastClosed, poolY, wellY);
        return moved;
    }

    /** The open slot: the law, bounded so it never overshoots the level, the sill or the room. */
    private boolean flow(ReactorControllerBlockEntity r, SpentFuelPoolBlockEntity pool, double dt, double sill, double canalTop,
                         double area, double poolFloor, double rim, double poolY, double wellY, double envY) {
        var vessel = r.core().getPressureVessel();
        var profile = r.cavity().profile();
        var water = pool.pool();
        double hi = Math.max(poolY, wellY);
        double lo = Math.min(poolY, wellY);
        if (!(hi > sill + LEVEL_M)) {
            idle(State.BELOW_SILL);
            return false;
        }
        double head = hi - Math.max(lo, sill);
        if (head < LEVEL_M) {
            idle(State.LEVEL);
            return false;
        }
        boolean fromPool = poolY >= wellY;
        double rho = Saturation.liquidDensityKgPerM3(vessel.getPressurePsia());
        double sillLocal = Math.max(sill - envY, profile.plateTop());
        double keepWellM3 = profile.waterUpTo(sillLocal);
        double wellWater = vessel.getWellWaterM3();
        double liquid = vessel.getLiquidMassKg();
        // mass over the sill of each side at a surface h (h at or over the sill)
        java.util.function.DoubleUnaryOperator poolOver = h -> 1000.0 * area * (Math.min(h, rim) - sill);
        java.util.function.DoubleUnaryOperator wellOver = h -> rho * (profile.waterUpTo(h - envY) - keepWellM3);
        java.util.function.DoubleUnaryOperator src = fromPool ? poolOver : wellOver;
        java.util.function.DoubleUnaryOperator dst = fromPool ? wellOver : poolOver;
        double srcNow = fromPool ? Math.min(water.massKg(), poolOver.applyAsDouble(poolY)) : Math.max(0.0, (wellWater - keepWellM3) * rho);
        double deficit;
        double dstNow;
        if (lo >= sill) {
            deficit = 0.0;
            dstNow = fromPool ? Math.max(0.0, (wellWater - keepWellM3) * rho) : poolOver.applyAsDouble(poolY);
        } else {
            dstNow = 0.0;
            deficit = fromPool ? Math.max(0.0, rho * (vessel.volumeAtFlangeM3() + keepWellM3) - liquid)
                    : 1000.0 * area * (sill - poolY);
        }
        // where the two surfaces would meet: what the source gives equals what the receiver takes
        double limit;
        double lossAtSill = srcNow;
        if (lossAtSill <= deficit - dstNow + dst.applyAsDouble(sill)) {
            limit = srcNow;
        } else {
            double a = Math.max(sill, lo);
            double b = hi;
            for (int i = 0; i < 48; i++) {
                double m = 0.5 * (a + b);
                double given = srcNow - src.applyAsDouble(m);
                double taken = deficit + dst.applyAsDouble(m) - dstNow;
                if (given > taken) {
                    a = m;
                } else {
                    b = m;
                }
            }
            limit = srcNow - src.applyAsDouble(0.5 * (a + b));
        }
        double room = fromPool ? vessel.getWellSpillThresholdKg() - liquid : water.spaceKg();
        // the slot's cross-section: its width by its height from the sill to the canal's top
        double depth = canalTop - sill;
        double rate = CanalFlow.kgPerS(head, depth);
        double wanted = rate * dt + carryKg;
        double bound = Math.min(Math.min(limit, srcNow), room);
        int whole = (int) Math.floor(Math.min(wanted, bound));
        headM = head;
        if (!(room >= 1.0)) {
            idle(State.FULL);
            headM = head;
            return false;
        }
        if (whole <= 0) {
            carryKg = wanted < bound ? wanted : 0.0;
            flowKgPerS = 0.0;
            state = srcNow < 1.0 ? State.BELOW_SILL : bound < 1.0 ? State.LEVEL : fromPool ? State.TO_WELL : State.TO_POOL;
            return false;
        }
        if (fromPool) {
            water.restore(water.massKg() - whole, water.temperatureC());
            vessel.restoreLiquidMassKg(liquid + whole);
        } else {
            double taken = vessel.drainWellWaterKg(whole, keepWellM3);
            double accepted = water.receiveWaterKg(taken, Math.min(100.0, r.core().getCoolantTemperatureC()));
            if (taken - accepted > 0.0) {
                vessel.restoreLiquidMassKg(vessel.getLiquidMassKg() + (taken - accepted));
            }
            whole = (int) Math.round(accepted);
        }
        carryKg = wanted <= bound ? wanted - Math.floor(Math.min(wanted, bound)) : 0.0;
        double signed = fromPool ? whole : -whole;
        flowKgPerS = signed / dt;
        totalKg += signed;
        state = fromPool ? State.TO_WELL : State.TO_POOL;
        return whole > 0;
    }

    // ---------------------------------------------------------------
    // The canal blocks' water
    // ---------------------------------------------------------------

    /**
     * The fill of one canal block for a water surface at world y {@code surface}: 0 for dry (the surface at or under its floor),
     * 1 to 15 for the surface in this block at fill/16 (15 also for a brim-full top block), 16 for full with the water going on into the
     * block above.
     */
    public static int fill(double surface, int y, boolean top, boolean floor) {
        double d = surface - y;
        double dry = (floor ? TransferCanalBlock.FLOOR_THICKNESS : 0.0) + 1.0 / 32.0;
        if (!(d > dry)) {
            return 0;
        }
        if (d >= 1.0 + 1.0 / 32.0 && !top) {
            return 16;
        }
        int n = (int) Math.round(d * 16.0);
        return Math.max(floor ? 3 : 1, Math.min(15, n));
    }

    /** Sets the canal's water to the side each column is open to; changes a block only when its water does. */
    private void level(Level level, int firstClosed, int lastClosed, double poolY, double wellY) {
        double open = Math.max(poolY, wellY);
        for (int i = 0; i < columns.size(); i++) {
            double surface;
            if (firstClosed < 0) {
                surface = open;
            } else if (i <= firstClosed) {
                surface = poolY;
            } else if (i > lastClosed) {
                surface = wellY;
            } else {
                continue; // between two closed gates: its own water, left as it is
            }
            Column c = columns.get(i);
            for (int y = c.bottomY(); y <= c.topY(); y++) {
                BlockPos p = new BlockPos(c.x(), y, c.z());
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof TransferCanalBlock)) {
                    continue;
                }
                int f = fill(surface, y, y == c.topY(), y == c.bottomY());
                BlockState next = s.setValue(TransferCanalBlock.FILL, f).setValue(TransferCanalBlock.WATER, f > 0);
                if (next != s) {
                    level.setBlock(p, next, 2);
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Reports
    // ---------------------------------------------------------------

    public List<Component> statusComponents() {
        List<Component> lines = new ArrayList<>();
        if (state == State.NO_SLOT) {
            return lines;
        }
        lines.add(BwrText.tr("status.bwr.canal_exchange.title", "Cattle chute slot: %s", state.text()));
        if (!Double.isNaN(sillY) && state != State.NO_ROW && state != State.WAITING) {
            lines.add(BwrText.tr("status.bwr.canal_exchange.numbers",
                    "Sill y %s | pool surface y %s | well surface %s | flow %s kg/s %s",
                    String.format(Locale.ROOT, "%.2f", sillY), String.format(Locale.ROOT, "%.2f", poolSurfaceY),
                    Double.isNaN(wellSurfaceY) ? "none over the flange" : "y " + String.format(Locale.ROOT, "%.2f", wellSurfaceY),
                    String.format(Locale.ROOT, "%,.0f", Math.abs(flowKgPerS)),
                    flowKgPerS > 0 ? "into the well" : flowKgPerS < 0 ? "into the pool" : ""));
        }
        return lines;
    }
}
