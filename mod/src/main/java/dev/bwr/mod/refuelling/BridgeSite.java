package dev.bwr.mod.refuelling;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.fuel.AbwrShroudGeometry;
import dev.bwr.core.fuel.RipCorePacking;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.reactor.FormedReactorRegistry;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselCoreAppearance;
import dev.bwr.mod.reactor.VesselInternalsGeometry;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolLayout;
import dev.bwr.mod.spentfuel.SpentFuelPoolRegistry;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * What a refuelling bridge console can see from where it stands, found on demand (a player's click, a computer's call,
 * a GUI snapshot once a second, the start of a move) and never every tick: the rails, the reactor and the pool that lie
 * between them, and the cattle chute joining the two. Read only; a survey never loads a chunk.
 *
 * <h2>The rails</h2>
 * The console stands beside a rail block ({@link RefuellingRailBlock}). Rails have an axis; the run of rails of the same
 * axis and height through that one is the first rail, the nearest parallel run (within {@value #MAX_SPAN} blocks) that
 * overlaps it by at least two blocks is the second. The bridge rides the overlap along the axis ("a"), the trolley rides the
 * bridge between the rails ("c"). The bridge reaches the columns inside both.
 *
 * <h2>Reactor, pool and chute</h2>
 * A formed reactor and a formed pool whose interiors lie inside that reach are the bridge's cavity and pool (the nearest of
 * each to the console). The cattle chute is a straight row of {@link TransferCanalBlock}s (gates among them) that fills
 * every column between the reactor's shell and the pool's shell along the rails, at the height of the rails or up to four blocks
 * below them, in a row that both interiors share. Its water is the canal blocks' own {@code water} flag: a drained chute
 * has none.
 *
 * <h2>Water surfaces</h2>
 * The pool's is its own level ({@code min.y + 1 + levelM}, as the pool draws it). The cavity's is the top of the drawn core
 * plus the metres of water the reactor model has above the top of active fuel ({@code (collapsed level - TAF) x 0.0254}, the
 * same number {@code RadiationField} uses for an open head). The reactor model's level includes the reactor well: water raised over the
 * vessel flange into a sealed well adds its rise to the cover metre for metre (see {@code CavitySeal}). The chute's is the lower of
 * the two, or none when drained.
 * An old chute (the row between the walls, the pool's lining and the well's wall left solid) couples nothing: an open gate is a path, not a
 * pipe. A chute whose row runs on through a slot cut in the pool's wall ({@link SpentFuelPoolLayout.Slot}) is one body of water with the
 * pool and the well when its gates are open: the pool's controller passes the water through it ({@link CanalExchange}) and sets the canal
 * blocks' water to the connected level, which is what this survey then reads as the chute's surface. The slot's column is part of the
 * chute: its gates count, its floor (the sill) is the chute's floor at the highest. See REFUELLING-BRIDGE.md.
 */
public final class BridgeSite {
    public static final int MAX_SPAN = 40;
    public static final int MAX_RUN = 96;
    /** Most canal blocks counted in one stacked column of the chute. */
    public static final int MAX_DEPTH = 24;

    /** What lies under a column. */
    public enum Body { NONE, CAVITY, POOL, CHUTE }

    public enum ChuteState { ABSENT, OPEN, CLOSED, DRAINED }

    /**
     * The cattle chute: where it is, and what state its gates and water are in. {@code entry}/{@code exit} are columns on the
     * shells. {@code surfaceY} is the water surface of its lowest-topped column and {@code floorY} the top of the floor of its
     * highest-floored one (world Y): the chute is a trench with a level of its own.
     */
    public record Chute(ChuteState state, int row, int gapLo, int gapHi, double entryA, double exitA, @Nullable BlockPos closedGate,
                        List<BlockPos> gates, double surfaceY, double floorY, boolean slot) {
        public static final Chute NONE = new Chute(ChuteState.ABSENT, 0, 0, -1, 0, 0, null, List.of(), Double.NaN, Double.NaN, false);

        /** An old-style chute: the row between the walls, both walls solid. */
        public Chute(ChuteState state, int row, int gapLo, int gapHi, double entryA, double exitA, @Nullable BlockPos closedGate,
                     List<BlockPos> gates, double surfaceY, double floorY) {
            this(state, row, gapLo, gapHi, entryA, exitA, closedGate, gates, surfaceY, floorY, false);
        }
    }

    /** What one stacked column of canal blocks says: added into the row's totals. */
    private static final class Reading {
        boolean wet = true;
        BlockPos closed;
        final List<BlockPos> gates = new ArrayList<>();
        double surface = Double.MAX_VALUE;
        double floor = -Double.MAX_VALUE;

        /**
         * Reads the stack from {@code top} down. An old column's water is its flags (every block wet, the surface 13/16 up the top
         * block). A column whose water a slot sets ({@link TransferCanalBlock#FILL} above 0 in some block, {@link CanalExchange})
         * holds water over its floor when its lowest block does, with the surface where the fill puts it. False if a block is not loaded.
         */
        boolean column(Level level, int x, int z, int top) {
            int bottom = top;
            boolean allWet = true;
            boolean levelled = false;
            boolean bottomWet = false;
            double levelSurface = -Double.MAX_VALUE;
            for (int y = top; y > top - MAX_DEPTH; y--) {
                BlockPos at = new BlockPos(x, y, z);
                if (!level.isLoaded(at)) {
                    return false;
                }
                BlockState st = level.getBlockState(at);
                if (!(st.getBlock() instanceof TransferCanalBlock)) {
                    break;
                }
                bottom = y;
                boolean w = st.getValue(TransferCanalBlock.WATER);
                allWet &= w;
                bottomWet = w;
                int fill = st.getValue(TransferCanalBlock.FILL);
                if (fill > 0) {
                    levelled = true;
                    if (w) {
                        levelSurface = Math.max(levelSurface, y + (fill >= 16 ? 1.0 : fill / 16.0));
                    }
                }
                if (st.getBlock() instanceof CanalGateBlock) {
                    gates.add(at);
                    if (!st.getValue(CanalGateBlock.OPEN) && closed == null) {
                        closed = at;
                    }
                }
            }
            boolean columnWet = levelled ? bottomWet && levelSurface > -Double.MAX_VALUE : allWet;
            wet &= columnWet;
            surface = Math.min(surface, levelled ? (columnWet ? levelSurface : bottom + TransferCanalBlock.FLOOR_THICKNESS)
                    : top + TransferCanalBlock.SURFACE_IN_BLOCK);
            floor = Math.max(floor, bottom + TransferCanalBlock.FLOOR_THICKNESS);
            return true;
        }
    }

    @Nullable
    public final Component problem;
    public final Direction.Axis axis;
    public final int railY;
    public final int aLo;
    public final int aHi;
    public final int cLow;
    public final int cHigh;
    @Nullable
    public final BlockPos reactorPos;
    @Nullable
    public final BlockPos poolPos;
    /** True for the reactor building crane's runway (the hook rests at {@code CraneMotion.HOOK_HOME_ABOVE_RAIL_M}), false for the refuelling bridge's. */
    public final boolean crane;

    private BridgeSite(@Nullable Component problem, Direction.Axis axis, int railY, int aLo, int aHi, int cLow, int cHigh,
                       @Nullable BlockPos reactorPos, @Nullable BlockPos poolPos) {
        this(problem, axis, railY, aLo, aHi, cLow, cHigh, reactorPos, poolPos, false);
    }

    private BridgeSite(@Nullable Component problem, Direction.Axis axis, int railY, int aLo, int aHi, int cLow, int cHigh,
                       @Nullable BlockPos reactorPos, @Nullable BlockPos poolPos, boolean crane) {
        this.crane = crane;
        this.problem = problem;
        this.axis = axis;
        this.railY = railY;
        this.aLo = aLo;
        this.aHi = aHi;
        this.cLow = cLow;
        this.cHigh = cHigh;
        this.reactorPos = reactorPos;
        this.poolPos = poolPos;
    }

    private static BridgeSite failure(Component problem) {
        return new BridgeSite(problem, Direction.Axis.X, 0, 0, 0, 0, 0, null, null);
    }

    public boolean ok() {
        return problem == null;
    }

    public boolean runwayAlongX() {
        return axis == Direction.Axis.X;
    }

    // ---------------------------------------------------------------
    // Coordinates
    // ---------------------------------------------------------------

    /** The coordinate along the rails of a world point. */
    public double a(double x, double z) {
        return axis == Direction.Axis.X ? x : z;
    }

    /** The coordinate across the rails (the trolley's). */
    public double c(double x, double z) {
        return axis == Direction.Axis.X ? z : x;
    }

    public double x(double a, double c) {
        return axis == Direction.Axis.X ? a : c;
    }

    public double z(double a, double c) {
        return axis == Direction.Axis.X ? c : a;
    }

    /** Whether a column is inside what the bridge can reach. */
    public boolean reaches(double x, double z) {
        double a = a(x, z);
        double c = c(x, z);
        return a >= aLo && a <= aHi + 1 && c >= cLow + 1 && c <= cHigh;
    }

    /** The rest position: the grapple retracted over the first end of the runway. */
    public BridgeMotion.Point home() {
        double a = aLo + 0.5;
        double c = cLow + 1.5;
        return new BridgeMotion.Point(x(a, c), homeY(), z(a, c));
    }

    public double homeY() {
        return railY + (crane ? dev.bwr.core.refuelling.CraneMotion.HOOK_HOME_ABOVE_RAIL_M : BridgeMotion.MAST_HOME_ABOVE_RAIL_M);
    }

    // ---------------------------------------------------------------
    // The survey
    // ---------------------------------------------------------------

    private static boolean isRail(Level level, BlockPos p, Direction.Axis axis, boolean crane) {
        if (!level.isLoaded(p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        return crane ? s.getBlock() instanceof CraneRunwayRailBlock && s.getValue(CraneRunwayRailBlock.AXIS) == axis
                : s.getBlock() instanceof RefuellingRailBlock && s.getValue(RefuellingRailBlock.AXIS) == axis;
    }

    /** {lo, hi} of the run of rails of {@code axis} through {@code from}, in the coordinate along the axis. */
    private static int[] run(Level level, BlockPos from, Direction.Axis axis, boolean crane) {
        Direction plus = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        int lo = 0;
        int hi = 0;
        while (hi < MAX_RUN && isRail(level, from.relative(plus, hi + 1), axis, crane)) {
            hi++;
        }
        while (-lo < MAX_RUN && isRail(level, from.relative(plus, lo - 1), axis, crane)) {
            lo--;
        }
        int base = axis == Direction.Axis.X ? from.getX() : from.getZ();
        return new int[]{base + lo, base + hi};
    }

    public static BridgeSite survey(Level level, BlockPos console) {
        return survey(level, console, false);
    }

    /**
     * What a reactor building crane console can see: two runs of {@link CraneRunwayRailBlock} (the same rules as the bridge's rails)
     * and the formed reactor between them. A crane needs no pool; the head stand and the equipment pads are found when a move is queued.
     */
    public static BridgeSite surveyCrane(Level level, BlockPos console) {
        return survey(level, console, true);
    }

    private static BridgeSite survey(Level level, BlockPos console, boolean crane) {
        BlockPos rail = null;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP}) {
            BlockPos p = console.relative(d);
            if (level.isLoaded(p) && (crane ? level.getBlockState(p).getBlock() instanceof CraneRunwayRailBlock
                    : level.getBlockState(p).getBlock() instanceof RefuellingRailBlock)) {
                rail = p;
                break;
            }
        }
        if (rail == null) {
            return failure(crane
                    ? BwrText.tr("status.bwr.building_crane.no_rail",
                    "Stand the console beside a crane runway rail. The crane rides two parallel runs of rails high on opposite walls.")
                    : BwrText.tr("status.bwr.refuelling_bridge.no_rail",
                    "Stand the console beside a refuelling rail. The bridge rides two parallel runs of rails."));
        }
        Direction.Axis axis = level.getBlockState(rail).getValue(BlockStateProperties.HORIZONTAL_AXIS);
        Direction.Axis cross = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        int[] first = run(level, rail, axis, crane);
        int bestStep = Integer.MAX_VALUE;
        int[] bestRun = null;
        int bestSign = 0;
        for (int step = 1; step <= MAX_SPAN && bestRun == null; step++) {
            for (int sign : new int[]{1, -1}) {
                BlockPos p = rail.relative(Direction.get(sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, cross), step);
                if (isRail(level, p, axis, crane)) {
                    int[] second = run(level, p, axis, crane);
                    if (Math.min(first[1], second[1]) - Math.max(first[0], second[0]) >= 1) {
                        bestRun = second;
                        bestStep = step;
                        bestSign = sign;
                        break;
                    }
                }
            }
        }
        if (bestRun == null) {
            return failure(crane
                    ? BwrText.tr("status.bwr.building_crane.no_second_rail",
                    "The crane needs a second run of runway rails parallel to this one, within %s blocks, overlapping it by two blocks or more.", MAX_SPAN)
                    : BwrText.tr("status.bwr.refuelling_bridge.no_second_rail",
                    "The bridge needs a second run of rails parallel to this one, within %s blocks, overlapping it by two blocks or more.", MAX_SPAN));
        }
        int railC = cross == Direction.Axis.X ? rail.getX() : rail.getZ();
        int otherC = railC + bestSign * bestStep;
        int lo = Math.max(first[0], bestRun[0]);
        int hi = Math.min(first[1], bestRun[1]);
        int cLow = Math.min(railC, otherC);
        int cHigh = Math.max(railC, otherC);
        BridgeSite shell = new BridgeSite(null, axis, rail.getY(), lo, hi, cLow, cHigh, null, null, crane);
        BlockPos reactor = null;
        double bestR = Double.MAX_VALUE;
        for (ReactorControllerBlockEntity r : FormedReactorRegistry.controllers(level)) {
            var s = r.structure();
            if (s == null) {
                continue;
            }
            if (shell.reaches(s.interiorMin().getX(), s.interiorMin().getZ()) && shell.reaches(s.interiorMax().getX() + 1, s.interiorMax().getZ() + 1)) {
                double d = r.getBlockPos().distSqr(console);
                if (d < bestR) {
                    bestR = d;
                    reactor = r.getBlockPos();
                }
            }
        }
        BlockPos pool = null;
        double bestP = Double.MAX_VALUE;
        for (SpentFuelPoolBlockEntity p : crane ? List.<SpentFuelPoolBlockEntity>of() : SpentFuelPoolRegistry.controllers(level)) {
            var l = p.isFormed() ? p.layout() : null;
            if (l == null) {
                continue;
            }
            if (shell.reaches(l.min().getX() + 1, l.min().getZ() + 1) && shell.reaches(l.max().getX(), l.max().getZ())) {
                double d = p.getBlockPos().distSqr(console);
                if (d < bestP) {
                    bestP = d;
                    pool = p.getBlockPos();
                }
            }
        }
        return new BridgeSite(null, axis, rail.getY(), lo, hi, cLow, cHigh, reactor, pool, crane);
    }

    // ---------------------------------------------------------------
    // The reactor and the pool
    // ---------------------------------------------------------------

    @Nullable
    public ReactorControllerBlockEntity reactor(Level level) {
        return reactorPos != null && level.isLoaded(reactorPos) && level.getBlockEntity(reactorPos) instanceof ReactorControllerBlockEntity r
                && r.isFormed() && !r.isRemoved() ? r : null;
    }

    @Nullable
    public SpentFuelPoolBlockEntity pool(Level level) {
        return poolPos != null && level.isLoaded(poolPos) && level.getBlockEntity(poolPos) instanceof SpentFuelPoolBlockEntity p
                && p.isFormed() && !p.isRemoved() ? p : null;
    }

    /** Y of the top of the drawn core, where a seated assembly's handle is. */
    public static double coreTopY(ReactorControllerBlockEntity r) {
        VesselAppearance.Envelope e = r.vesselEnvelope();
        if (e == null) {
            return r.structure().topOfActiveFuelY() + 1.0;
        }
        return e.min().getY() + VesselInternalsGeometry.coreTop(e);
    }

    /**
     * Metres of water the reactor model has above the top of active fuel. Read from the vessel's collapsed level, which above the flange is the
     * level of the flooded well (the well's cross-section replaces the vessel's there), so a flooded well satisfies the hoist limit.
     */
    public static double cavityWaterM(ReactorControllerBlockEntity r) {
        return Math.max(0.0, (r.core().getCollapsedLevelIn() - PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN) * 0.0254);
    }

    public static double cavitySurfaceY(ReactorControllerBlockEntity r) {
        return coreTopY(r) + cavityWaterM(r);
    }

    public static double poolSurfaceY(SpentFuelPoolBlockEntity p) {
        return p.layout().min().getY() + 1 + p.pool().levelM();
    }

    public static double rackSeatY(BlockPos rack) {
        return rack.getY() + SpentFuelPoolLayout.RACK_HEIGHT;
    }

    /** The centre of cell {@code cell} of the rack at {@code rack}: x, z. */
    public static double[] cellColumn(BlockPos rack, int cell) {
        return new double[]{rack.getX() + 0.25 + 0.5 * (cell & 1), rack.getZ() + 0.25 + 0.5 * (cell >> 1)};
    }

    /** Where the drawn core puts lattice slot {@code slot}: x, z (the same layout the renderer uses, on the server). */
    @Nullable
    public static double[] coreColumn(ReactorControllerBlockEntity r, int slot) {
        VesselAppearance.Envelope e = r.vesselEnvelope();
        VesselCoreAppearance core = VesselCoreAppearance.capture(r);
        if (e == null || core == null) {
            return null;
        }
        double[] pitch = corePitch(e, core);
        int w = core.latticeWidth();
        double cx = e.min().getX() + e.width() / 2.0;
        double cz = e.min().getZ() + e.depth() / 2.0;
        return new double[]{cx + (slot % w + 0.5 - w / 2.0) * pitch[0], cz + (slot / w + 0.5 - w / 2.0) * pitch[1]};
    }

    /** Port of the client's {@code ReactorCoreGeometry.layout} pitch (that class is client only): the spacing of lattice squares, blocks. */
    static double[] corePitch(VesselAppearance.Envelope e, VesselCoreAppearance core) {
        if (core.internalPumps()) {
            var packed = RipCorePacking.layout(e.width() - 2, e.depth() - 2);
            return new double[]{(float) packed.pitchX(), (float) packed.pitchZ()};
        }
        float nx = e.width() - 2;
        float nz = e.depth() - 2;
        int lw = core.latticeWidth();
        if (core.layoutVersion() == 1) {
            float extent = 1;
            for (var c : core.cells()) {
                extent = Math.max(extent, (float) Math.hypot(c.slot() % lw + .5 - lw / 2.0, c.slot() / lw + .5 - lw / 2.0) + .71f);
            }
            nx = nz = extent;
        }
        float px = .35f * e.width() / nx;
        float pz = .35f * e.depth() / nz;
        float radial = 0;
        for (var c : core.cells()) {
            radial = Math.max(radial, (float) Math.hypot((Math.abs(c.slot() % lw + .5 - lw / 2.0) + .5) * px / e.width(),
                    (Math.abs(c.slot() / lw + .5 - lw / 2.0) + .5) * pz / e.depth()));
        }
        float fit = radial == 0 ? 1 : (core.internalPumps() ? (float) AbwrShroudGeometry.FUEL_RADIUS : .362f) / radial;
        fit = Math.min(1, fit);
        return new double[]{px * fit, pz * fit};
    }

    // ---------------------------------------------------------------
    // The chute
    // ---------------------------------------------------------------

    /** The chute between this site's reactor and pool, read from the blocks now. */
    public Chute chute(Level level) {
        Chute none = Chute.NONE;
        ReactorControllerBlockEntity r = reactor(level);
        SpentFuelPoolBlockEntity p = pool(level);
        if (r == null || p == null) {
            return none;
        }
        var s = r.structure();
        var l = p.layout();
        int rLo = (int) a(s.interiorMin().getX(), s.interiorMin().getZ());
        int rHi = (int) a(s.interiorMax().getX(), s.interiorMax().getZ());
        int pLo = (int) a(l.min().getX() + 1, l.min().getZ() + 1);
        int pHi = (int) a(l.max().getX() - 1, l.max().getZ() - 1);
        int gapLo;
        int gapHi;
        double entryA;
        double exitA;
        if (rHi + 1 < pLo - 1) {
            gapLo = rHi + 2;
            gapHi = pLo - 2;
            entryA = rHi + 1.5;
            exitA = pLo - 0.5;
        } else if (pHi + 1 < rLo - 1) {
            gapLo = pHi + 2;
            gapHi = rLo - 2;
            entryA = rLo - 0.5;
            exitA = pHi + 1.5;
        } else {
            return none;
        }
        if (gapLo > gapHi) {
            return none;
        }
        int rcLo = (int) c(s.interiorMin().getX(), s.interiorMin().getZ());
        int rcHi = (int) c(s.interiorMax().getX(), s.interiorMax().getZ());
        int pcLo = (int) c(l.min().getX() + 1, l.min().getZ() + 1);
        int pcHi = (int) c(l.max().getX() - 1, l.max().getZ() - 1);
        int lo = Math.max(rcLo, pcLo);
        int hi = Math.min(rcHi, pcHi);
        if (lo > hi) {
            return none;
        }
        double centre = (lo + hi) / 2.0;
        List<Integer> rows = new ArrayList<>();
        for (int row = lo; row <= hi; row++) {
            rows.add(row);
        }
        rows.sort((u, v) -> Double.compare(Math.abs(u - centre), Math.abs(v - centre)));
        // the pool's wall column on the chute's side: a slot there is part of the chute (the old chute leaves it lining)
        int wallA = rHi + 1 < pLo - 1 ? pLo - 1 : pHi + 1;
        for (int row : rows) {
            boolean complete = true;
            Reading reading = new Reading();
            for (int a = gapLo; a <= gapHi && complete; a++) {
                int top = Integer.MIN_VALUE;
                for (int y = railY - 1; y >= railY - 4 && top == Integer.MIN_VALUE; y--) {
                    BlockPos at = new BlockPos((int) x(a, row), y, (int) z(a, row));
                    if (!level.isLoaded(at)) {
                        complete = false;
                        break;
                    }
                    if (level.getBlockState(at).getBlock() instanceof TransferCanalBlock) {
                        top = y;
                    }
                }
                if (top == Integer.MIN_VALUE) {
                    complete = false;
                    break;
                }
                // the trench: the canal blocks stacked down from the top
                if (!reading.column(level, (int) x(a, row), (int) z(a, row), top)) {
                    complete = false;
                }
            }
            if (complete) {
                int sx = (int) x(wallA, row);
                int sz = (int) z(wallA, row);
                boolean slot = l.slotAt(sx, sz) != null;
                if (slot && !reading.column(level, sx, sz, l.max().getY())) {
                    continue;
                }
                ChuteState state = reading.closed != null ? ChuteState.CLOSED : !reading.wet ? ChuteState.DRAINED : ChuteState.OPEN;
                return new Chute(state, row, gapLo, gapHi, entryA, exitA, reading.closed, List.copyOf(reading.gates), reading.surface,
                        reading.floor, slot);
            }
        }
        return none;
    }

    /** The chute as the route planner wants it (columns), or null without one. */
    @Nullable
    public BridgeMotion.Chute motionChute(Chute chute) {
        if (chute.state() == ChuteState.ABSENT) {
            return null;
        }
        double c = chute.row() + 0.5;
        return new BridgeMotion.Chute(x(chute.entryA(), c), z(chute.entryA(), c), x(chute.exitA(), c), z(chute.exitA(), c),
                chute.state() != ChuteState.DRAINED, chute.surfaceY(), chute.floorY());
    }

    // ---------------------------------------------------------------
    // Bodies of water
    // ---------------------------------------------------------------

    /** Which body of water a column lies over (shell columns count with their body). */
    public Body bodyAt(Level level, double x, double z, Chute chute) {
        ReactorControllerBlockEntity r = reactor(level);
        if (r != null) {
            var s = r.structure();
            if (x >= s.interiorMin().getX() - 1 && x <= s.interiorMax().getX() + 2 && z >= s.interiorMin().getZ() - 1 && z <= s.interiorMax().getZ() + 2) {
                return Body.CAVITY;
            }
        }
        SpentFuelPoolBlockEntity p = pool(level);
        if (p != null) {
            var l = p.layout();
            if (x >= l.min().getX() && x <= l.max().getX() + 1 && z >= l.min().getZ() && z <= l.max().getZ() + 1) {
                return Body.POOL;
            }
        }
        if (r != null && p != null) {
            if (chute.state() != ChuteState.ABSENT) {
                double a = a(x, z);
                double c = c(x, z);
                if (a >= chute.gapLo() && a <= chute.gapHi() + 1 && c >= chute.row() && c <= chute.row() + 1) {
                    return Body.CHUTE;
                }
            }
        }
        return Body.NONE;
    }

    /** The surface of the water over a column; {@code NaN} where there is none. */
    public double surfaceAt(Level level, double x, double z, Chute chute) {
        switch (bodyAt(level, x, z, chute)) {
            case CAVITY:
                return cavitySurfaceY(reactor(level));
            case POOL:
                return poolSurfaceY(pool(level));
            case CHUTE:
                if (chute.state() == ChuteState.DRAINED) {
                    return Double.NaN;
                }
                return chute.surfaceY();
            default:
                return Double.NaN;
        }
    }
}
