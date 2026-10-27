package dev.bwr.mod.refuelling;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolRegistry;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The reactor building crane's canal gate lift (GATE OUT / GATE IN): a self-contained lift kind the crane delegates to. A real plant's
 * transfer canal is closed by steel gates, tall plugs that drop into guides in the slot; with the reactor flooded to the pool's level a
 * gate is hooked by its lifting eyes, pulled straight up out of the slot and laid in a rack beside the pool, and the pool, the canal and
 * the well are one body of water. Here a gate is the vertical stack of {@link CanalGatePlugBlock}s in one canal column.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>GATE OUT</b> takes the gate in the crane's reach nearest the middle of the well whose lifting eyes are clear (nothing on top of it),
 *   that stands in a canal (not on a stand), and sets it on the nearest free {@link GateLaydownStandBlock}, or, with none, on the clear
 *   floor nearest it (the shield plug's laydown rule, at the height of the floor round the top of the slot). The column it leaves is open
 *   canal: the same canal block with its water, floor and top.</li>
 *   <li><b>GATE IN</b> takes the gate most recently laid down back to exactly where it came from, if that column is still open canal (or air).</li>
 *   <li>A gate weighs its blocks' masses (the block data map {@code bwr:plug_mass}; the mod's entry is {@value #DEFAULT_TONNES_PER_BLOCK} t a block, a
 *   welded stainless gate 0.75 m wide), so a whole gate is a few tonnes, far within the crane's {@value PlugLift#RATED_CAPACITY_T} t; over the
 *   rating is refused.</li>
 *   <li><b>No pull against a head.</b> A gate holding back water cannot be pulled: the seals are loaded, and the water would rush through. The
 *   pool's surface and the well's (each down to the slot's floor at the lowest) must be within {@value #MAX_HEAD_M} m; otherwise the lift is
 *   refused with both levels. Checked when queued and again when the hook closes.</li>
 * </ul>
 * Nothing moves by itself: every lift is a button on the pendant or a computer's call. While it hangs, the gate is a
 * {@link PlugSectionEntity} (every block state kept), exactly as a shield plug section is; it is saved with its chunk, the crane keeps only
 * its id, the plan and the laydown book, so a world saved mid-lift carries on, and nothing is ever lost or duplicated.
 */
public final class CanalGateLift {

    /** The default mass of one block of gate, tonnes: the data map entry's value (a stainless gate 0.75 m wide, a metre tall, about 0.6 t). */
    public static final double DEFAULT_TONNES_PER_BLOCK = 0.6;
    /** The most water a gate may hold back to be pulled, metres. */
    public static final double MAX_HEAD_M = 0.3;
    /** The two-leg sling from the hook to the gate's eyes, m. */
    public static final double RIG_M = 1.3;
    /** The most blocks of one gate (one canal column). */
    public static final int MAX_HEIGHT = CanalExchange.MAX_STACK;
    /** How far above the top of the slot the laydown may be looked for, and below it (the refuel floor round the slot). */
    public static final int LAYDOWN_DY_MIN = -4, LAYDOWN_DY_MAX = 8;

    /** A gate as it stands: its bottom block, its height, the canal's axis, its mass, and what rigs it. */
    public record Gate(BlockPos bottom, int height, Direction.Axis axis, double massT, @Nullable BlockPos coveredBy, boolean onStand) {
        public BlockPos top() {
            return bottom.above(height - 1);
        }

        /** True when the lifting eyes on its top are clear to be hooked. */
        public boolean rigged() {
            return coveredBy == null;
        }

        public List<BlockPos> blocks() {
            List<BlockPos> out = new ArrayList<>(height);
            for (int i = 0; i < height; i++) {
                out.add(bottom.above(i));
            }
            return out;
        }

        /** The gate as a section the crane's laydown and entity code take. */
        PlugSections.Section section() {
            return section(bottom);
        }

        PlugSections.Section section(BlockPos at) {
            List<BlockPos> out = new ArrayList<>(height);
            for (int i = 0; i < height; i++) {
                out.add(at.above(i));
            }
            return new PlugSections.Section(at, at.above(height - 1), List.copyOf(out), List.of(), new int[3], massT);
        }
    }

    /** A gate lift in progress: where the gate belongs ({@code home}, its bottom block in the canal), where it is laid down, its height and mass. */
    public record Plan(BlockPos home, BlockPos lay, int height, double massT, boolean fromHome, boolean alongX) {
        public double hookAboveBase() {
            return height + RIG_M;
        }

        public BlockPos source() {
            return fromHome ? home : lay;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Home", home.asLong());
            t.putLong("Lay", lay.asLong());
            t.putInt("Height", height);
            t.putDouble("Mass", massT);
            t.putBoolean("FromHome", fromHome);
            t.putBoolean("AlongX", alongX);
            return t;
        }

        @Nullable
        static Plan load(CompoundTag t) {
            int h = t.getInt("Height");
            if (h <= 0 || h > MAX_HEIGHT) {
                return null;
            }
            return new Plan(BlockPos.of(t.getLong("Home")), BlockPos.of(t.getLong("Lay")), h, t.getDouble("Mass"), t.getBoolean("FromHome"), t.getBoolean("AlongX"));
        }
    }

    /** A gate laid down by GATE OUT: where it is, and where it belongs. */
    public record Laid(BlockPos lay, BlockPos home) {
    }

    /** A survey: the gate, or why there is none. */
    public record Survey(@Nullable Gate gate, @Nullable Component problem, boolean unloaded) {
        public Survey(@Nullable Gate gate, @Nullable Component problem) {
            this(gate, problem, false);
        }
    }

    @Nullable
    private Plan plan;
    @Nullable
    private UUID entity;
    private final List<Laid> laid = new ArrayList<>();

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    @Nullable
    public Plan plan() {
        return plan;
    }

    public boolean holding() {
        return entity != null;
    }

    public boolean owns(UUID id) {
        return id.equals(entity);
    }

    @Nullable
    public UUID entityId() {
        return entity;
    }

    public List<Laid> laid() {
        return List.copyOf(laid);
    }

    /** A lift stopped before the hook took the gate has nothing to remember. */
    void forgetPlanIfEmpty() {
        if (entity == null) {
            plan = null;
        }
    }

    void clear() {
        entity = null;
        plan = null;
    }

    public String summary() {
        return plan == null ? "a canal gate" : String.format(Locale.ROOT, "a canal gate (%d blocks, %.1f t)", plan.height(), plan.massT());
    }

    // ---------------------------------------------------------------
    // The gate's blocks
    // ---------------------------------------------------------------

    public static boolean isGate(BlockState s) {
        return s.getBlock() instanceof CanalGatePlugBlock;
    }

    /** The mass of one gate block, tonnes: the data map's entry, or {@link #DEFAULT_TONNES_PER_BLOCK}. */
    public static double tonnes(BlockState s) {
        PlugMass.Mass m = s.getBlockHolder().getData(PlugMass.MASS);
        return m != null ? m.tonnes() : DEFAULT_TONNES_PER_BLOCK;
    }

    /** The whole gate through any of its blocks. Reads only loaded blocks. */
    public static Survey survey(Level level, BlockPos any) {
        if (!level.isLoaded(any)) {
            return new Survey(null, unloaded(), true);
        }
        if (!isGate(level.getBlockState(any))) {
            return new Survey(null, BwrText.tr("status.bwr.canal_gate_lift.not_gate", "There is no canal gate plug at %s.", any.toShortString()));
        }
        BlockPos bottom = any;
        for (int n = 0; n < MAX_HEIGHT; n++) {
            BlockPos b = bottom.below();
            if (!level.isLoaded(b)) {
                return new Survey(null, unloaded(), true);
            }
            if (!isGate(level.getBlockState(b))) {
                break;
            }
            bottom = b;
        }
        int height = 1;
        double mass = tonnes(level.getBlockState(bottom));
        while (height < MAX_HEIGHT) {
            BlockPos u = bottom.above(height);
            if (!level.isLoaded(u)) {
                return new Survey(null, unloaded(), true);
            }
            BlockState s = level.getBlockState(u);
            if (!isGate(s)) {
                break;
            }
            mass += tonnes(s);
            height++;
        }
        BlockPos over = bottom.above(height);
        BlockPos covered = !level.isLoaded(over) || level.getBlockState(over).isAir() ? null : over.immutable();
        BlockPos under = bottom.below();
        boolean onStand = level.isLoaded(under) && level.getBlockState(under).getBlock() instanceof GateLaydownStandBlock;
        Direction.Axis axis = level.getBlockState(bottom).getValue(TransferCanalBlock.AXIS);
        return new Survey(new Gate(bottom.immutable(), height, axis, mass, covered, onStand), null);
    }

    private static Component unloaded() {
        return BwrText.tr("status.bwr.canal_gate_lift.unloaded", "Part of the gate lies in a chunk that is not loaded; the crane does not load chunks.");
    }

    /** Every gate and every laydown stand in the crane's reach, between two heights. */
    record Scan(List<Gate> gates, List<BlockPos> stands, @Nullable Component problem) {
    }

    static Scan scan(Level level, BridgeSite site, int yLo, int yHi) {
        List<Gate> gates = new ArrayList<>();
        List<BlockPos> stands = new ArrayList<>();
        Component problem = null;
        int lo = Math.min(Math.min(site.aLo, site.cLow), Math.min(site.aHi, site.cHigh)) - 1;
        int hi = Math.max(Math.max(site.aLo, site.cLow), Math.max(site.aHi, site.cHigh)) + 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = lo; x <= hi; x++) {
            for (int z = lo; z <= hi; z++) {
                if (!site.reaches(x + 0.5, z + 0.5)) {
                    continue;
                }
                p.set(x, yLo, z);
                if (!level.isLoaded(p)) {
                    continue;
                }
                boolean below = false;
                for (int y = yLo; y <= yHi; y++) {
                    p.set(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.getBlock() instanceof GateLaydownStandBlock) {
                        stands.add(p.immutable());
                    }
                    boolean gate = isGate(s);
                    if (gate && !below) {
                        Survey sv = survey(level, p.immutable());
                        if (sv.gate() != null) {
                            gates.add(sv.gate());
                        } else if (problem == null) {
                            problem = sv.problem();
                        }
                    }
                    below = gate;
                }
            }
        }
        return new Scan(gates, stands, problem);
    }

    // ---------------------------------------------------------------
    // The water on either side
    // ---------------------------------------------------------------

    /**
     * The water a gate in a slot's canal holds back, m: the higher of the pool's and the well's surfaces less the lower (each counted down to the
     * slot's floor at the lowest). 0 for a gate that no slot's canal includes (an old chute couples no water), or when both are under the floor.
     * {@code out} receives the pool's surface, the well's (NaN when it has none over its flange) and the sill.
     */
    public static double headAcross(Level level, Gate gate, @Nullable double[] out) {
        for (SpentFuelPoolBlockEntity pool : SpentFuelPoolRegistry.controllers(level)) {
            var exchange = pool.canalExchange();
            var layout = pool.layout();
            if (layout == null || !pool.isFormed()) {
                continue;
            }
            boolean in = false;
            int sillRow = Integer.MIN_VALUE;
            for (var c : exchange.columns()) {
                sillRow = Math.max(sillRow, c.bottomY());
                in |= c.x() == gate.bottom().getX() && c.z() == gate.bottom().getZ();
            }
            if (!in) {
                continue;
            }
            double sill = sillRow + TransferCanalBlock.FLOOR_THICKNESS;
            double poolY = layout.min().getY() + 1 + pool.pool().levelM();
            double wellY = Double.NaN;
            BlockPos rp = exchange.reactorPos();
            if (rp != null && dev.bwr.mod.world.LoadedWorld.blockEntity(level, rp) instanceof ReactorControllerBlockEntity r && r.isFormed()
                    && r.core() != null && r.vesselEnvelope() != null && !r.headSealed()
                    && r.cavity() != null && r.cavity().wellUsable() && r.core().getPressureVessel().getWellWaterM3() > 0) {
                wellY = r.vesselEnvelope().min().getY() + r.wellSurfaceLocal();
            }
            if (out != null && out.length >= 3) {
                out[0] = poolY;
                out[1] = wellY;
                out[2] = sill;
            }
            double w = Double.isNaN(wellY) ? Double.NEGATIVE_INFINITY : wellY;
            double hi = Math.max(poolY, w);
            if (!(hi > sill)) {
                return 0.0;
            }
            return hi - Math.max(Math.min(poolY, w), sill);
        }
        return 0.0;
    }

    @Nullable
    static Component headProblem(Level level, Gate gate) {
        double[] at = new double[3];
        double head = headAcross(level, gate, at);
        if (head <= MAX_HEAD_M + 1.0e-9) {
            return null;
        }
        return BwrText.tr("status.bwr.canal_gate_lift.head",
                "The gate at %s holds back %s m of water: the pool stands at y %s and the well at %s (the slot's floor at y %s). A gate is pulled only with both sides within %s m: bring the well to the pool's level first.",
                gate.bottom().toShortString(), String.format(Locale.ROOT, "%.2f", head), String.format(Locale.ROOT, "%.2f", at[0]),
                Double.isNaN(at[1]) ? BwrText.tr("status.bwr.canal_gate_lift.well_dry", "no water over the flange")
                        : Component.literal("y " + String.format(Locale.ROOT, "%.2f", at[1])),
                String.format(Locale.ROOT, "%.2f", at[2]), String.format(Locale.ROOT, "%.1f", MAX_HEAD_M));
    }

    /** Why a gate cannot be lifted at all (eyes covered, over the rating), or null. */
    @Nullable
    public static Component gateProblem(Gate g) {
        if (!g.rigged()) {
            return BwrText.tr("status.bwr.canal_gate_lift.covered",
                    "The lifting eyes on top of the gate at %s are covered (a block at %s): clear the top of the gate so the crane can hook it.",
                    g.top().toShortString(), g.coveredBy().toShortString());
        }
        return weightProblem(g.massT(), g.height(), g.bottom());
    }

    /** Over the crane's rating, or null. */
    @Nullable
    public static Component weightProblem(double massT, int blocks, BlockPos at) {
        if (PlugLift.overCapacity(massT)) {
            return BwrText.tr("status.bwr.canal_gate_lift.over_capacity", "The gate at %s weighs %s t (%s blocks), over the crane's rated %s t.",
                    at.toShortString(), String.format(Locale.ROOT, "%.1f", massT), blocks, String.format(Locale.ROOT, "%.0f", PlugLift.RATED_CAPACITY_T));
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Where it goes
    // ---------------------------------------------------------------

    /** Why the gate cannot go back into its column at {@code home} (every cell open canal or air, loaded), or null. */
    @Nullable
    static Component homeProblem(Level level, BlockPos home, int height) {
        for (int i = 0; i < height; i++) {
            BlockPos p = home.above(i);
            if (!level.isLoaded(p)) {
                return BwrText.tr("status.bwr.plug.place_unloaded", "The place at %s is in a chunk that is not loaded; the crane does not load chunks.", p.toShortString());
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && (!(s.getBlock() instanceof TransferCanalBlock) || isGate(s) || s.getBlock() instanceof CanalGateBlock)) {
                return BwrText.tr("status.bwr.canal_gate_lift.home_blocked",
                        "The gate's place in the canal is not open: there is a block at %s. The crane puts a gate back only into open canal (or air).", p.toShortString());
            }
        }
        return null;
    }

    /** Why a free stand cannot take the gate (the cells over it air, loaded, off the well), or null. */
    @Nullable
    private static Component standProblem(Level level, BlockPos stand, Gate g, @Nullable VesselAppearance.Envelope env) {
        PlugSections.Section s = g.section(stand.above());
        if (env != null && PlugSections.coversWell(env, s.blocks(), BlockPos.ZERO)) {
            return BwrText.tr("status.bwr.plug.laydown_on_well", "That laydown is over the reactor well.");
        }
        return PlugSections.placeProblem(level, s.blocks(), stand.above().getY(), BlockPos.ZERO);
    }

    /**
     * The laydown for a gate: the nearest free stand in the crane's reach, or the clear floor nearest the gate at the height of the floor round the
     * top of its slot. Returns the bottom block of the laid gate, or null with the reason in {@code why[0]}.
     */
    @Nullable
    static BlockPos findLaydown(Level level, BridgeSite site, Gate g, List<BlockPos> stands, @Nullable VesselAppearance.Envelope env,
                                List<PlugSections.Keep> keep, Component[] why) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        Component first = null;
        for (BlockPos stand : stands) {
            if (!site.reaches(stand.getX() + 0.5, stand.getZ() + 0.5)) {
                continue;
            }
            Component p = standProblem(level, stand, g, env);
            if (p != null) {
                first = first == null ? p : first;
                continue;
            }
            double d = Math.hypot(stand.getX() - g.bottom().getX(), stand.getZ() - g.bottom().getZ());
            if (d < bestD) {
                bestD = d;
                best = stand.above();
            }
        }
        if (best != null) {
            return best;
        }
        // no stand: the floor round the slot, searched as the shield plug's laydown is, from a gate standing on the top of its own slot
        PlugSections.Section raised = g.section(g.bottom().above(g.height()));
        BlockPos delta = PlugSections.findLaydown(level, site, raised, env, keep, why);
        if (delta == null) {
            if (first != null) {
                why[0] = BwrText.tr("status.bwr.canal_gate_lift.no_laydown", "No Gate Laydown Stand can take the gate (%s), and %s", first, why[0]);
            }
            return null;
        }
        return raised.min().offset(delta);
    }

    /** Why the runway is too low to lift the gate clear of the top of its slot and its laydown, or null. */
    @Nullable
    static Component railsProblem(BridgeSite site, Plan p) {
        double top = Math.max(p.home().getY(), p.lay().getY()) + p.height();
        double clear = top + 0.25;
        double hanging = p.hookAboveBase();
        if (site.homeY() - hanging < clear) {
            return BwrText.tr("status.bwr.canal_gate_lift.rails_too_low",
                    "The runway is too low to lift the gate clear of the canal: it hangs %s blocks below the hook. Raise the rails to y %s or higher.",
                    String.format(Locale.ROOT, "%.1f", hanging), (int) Math.ceil(clear + hanging - CraneMotion.HOOK_HOME_ABOVE_RAIL_M));
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Queueing
    // ---------------------------------------------------------------

    /** The gate GATE OUT would take: rigged first, nearest the well; null with none in reach. */
    @Nullable
    static Gate nextOut(Scan scan, List<Laid> laid, double wx, double wz) {
        List<Gate> list = new ArrayList<>();
        for (Gate g : scan.gates()) {
            if (g.onStand() || laid.stream().anyMatch(l -> l.lay().equals(g.bottom()))) {
                continue;
            }
            list.add(g);
        }
        list.sort(Comparator.<Gate>comparingDouble(g -> Math.hypot(g.bottom().getX() + 0.5 - wx, g.bottom().getZ() + 0.5 - wz))
                .thenComparingInt(g -> g.bottom().getY()));
        for (Gate g : list) {
            if (g.rigged()) {
                return g;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * GATE OUT ({@code out}): the gate the crane is told of ({@code at}, any of its blocks) or the nearest rigged one in reach, to a stand or the
     * floor. GATE IN: the gate most recently laid down back to its place in the canal. With a gate already on the hook (after an abort) the lift
     * continues to the laydown (out) or home (in). Returns null when queued, or the reason it was not.
     */
    @Nullable
    Component queue(BuildingCraneBlockEntity crane, boolean out, @Nullable BlockPos at) {
        Level level = crane.getLevel();
        BridgeSite site = crane.site();
        ReactorControllerBlockEntity r = crane.reactor();
        VesselAppearance.Envelope env = r == null ? null : r.vesselEnvelope();
        if (r == null || env == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        if (entity == null && crane.currentMove() == null && !crane.busy()) {
            plan = null; // a lift that stopped before the hook took the gate (a halt at the hook-on) has nothing to remember
        }
        if (entity != null && plan != null && crane.currentMove() == null) {
            return crane.queue(new BuildingCraneBlockEntity.Move(BuildingCraneBlockEntity.Loc.HELD,
                    BuildingCraneBlockEntity.Loc.gate(out ? plan.lay() : plan.home())));
        }
        if (crane.holding()) {
            return BwrText.tr("status.bwr.building_crane.holding", "The hook still holds a load: set it down first.");
        }
        if (plan != null || crane.currentMove() != null || crane.queued() > 0 || crane.busy() || crane.plugPlan() != null) {
            return BwrText.tr("status.bwr.building_crane.plug_busy_now", "The crane is busy: wait for it to finish, or ABORT.");
        }
        Scan scan = scan(level, site, env.min().getY(), site.railY - 1);
        if (out) {
            Gate g;
            if (at != null) {
                Survey sv = survey(level, at);
                if (sv.gate() == null) {
                    return sv.problem();
                }
                g = sv.gate();
            } else {
                double wx = env.min().getX() + env.width() / 2.0, wz = env.min().getZ() + env.depth() / 2.0;
                g = nextOut(scan, laid, wx, wz);
                if (g == null) {
                    return scan.problem() != null ? scan.problem() : BwrText.tr("status.bwr.canal_gate_lift.none",
                            "The crane sees no canal gate to lift: build a Canal Gate Plug stack in a canal column (from the rim down) between the rails.");
                }
            }
            if (!site.reaches(g.bottom().getX() + 0.5, g.bottom().getZ() + 0.5)) {
                return BwrText.tr("status.bwr.building_crane.out_of_reach", "That position is outside the reach of the crane.");
            }
            Component why = gateProblem(g);
            if (why == null) {
                why = headProblem(level, g);
            }
            if (why != null) {
                return why;
            }
            Component[] reason = new Component[1];
            BlockPos lay = findLaydown(level, site, g, scan.stands(), env, crane.keepClear(env), reason);
            if (lay == null) {
                return reason[0];
            }
            Plan p = new Plan(g.bottom(), lay, g.height(), g.massT(), true, g.axis() == Direction.Axis.X);
            Component q = railsProblem(site, p);
            if (q != null) {
                return q;
            }
            plan = p;
            q = crane.queue(new BuildingCraneBlockEntity.Move(BuildingCraneBlockEntity.Loc.gate(p.home()), BuildingCraneBlockEntity.Loc.gate(p.lay())));
            if (q != null) {
                plan = null;
            }
            return q;
        }
        if (laid.isEmpty()) {
            return BwrText.tr("status.bwr.canal_gate_lift.nothing_laid", "No canal gate is laid down: GATE IN puts back what GATE OUT took out.");
        }
        Laid rec = laid.get(laid.size() - 1);
        Survey sv = survey(level, rec.lay());
        if (sv.gate() == null && sv.unloaded()) {
            return sv.problem();
        }
        if (sv.gate() == null || !sv.gate().bottom().equals(rec.lay())) {
            laid.remove(laid.size() - 1);
            crane.setChanged();
            return BwrText.tr("status.bwr.canal_gate_lift.laid_gone",
                    "The gate that was laid down at %s is no longer there as it was; its record is cleared. Press GATE IN again for the one before it.",
                    rec.lay().toShortString());
        }
        Gate g = sv.gate();
        Component why = weightProblem(g.massT(), g.height(), g.bottom());
        if (why == null) {
            why = homeProblem(level, rec.home(), g.height());
        }
        if (why != null) {
            return why;
        }
        Plan p = new Plan(rec.home(), rec.lay(), g.height(), g.massT(), false, g.axis() == Direction.Axis.X);
        Component q = railsProblem(site, p);
        if (q != null) {
            return q;
        }
        plan = p;
        q = crane.queue(new BuildingCraneBlockEntity.Move(BuildingCraneBlockEntity.Loc.gate(p.lay()), BuildingCraneBlockEntity.Loc.gate(p.home())));
        if (q != null) {
            plan = null;
        }
        return q;
    }

    /** What GATE OUT would take next (or what hangs): for the pendant and the computers. */
    public record Preview(int blocks, double massT, boolean rigged, boolean over, double headM, boolean carried, BlockPos bottom) {
        public String text() {
            return String.format(Locale.ROOT, "%d blocks, %.1f t%s", blocks, massT,
                    carried ? ", hooked" : !rigged ? ", eyes covered" : over ? ", over the rating"
                            : headM > MAX_HEAD_M ? String.format(Locale.ROOT, ", %.2f m of head", headM) : ", ready");
        }

        public boolean bad() {
            return !carried && (!rigged || over || headM > MAX_HEAD_M);
        }
    }

    @Nullable
    Preview preview(BuildingCraneBlockEntity crane) {
        if (entity != null && plan != null) {
            return new Preview(plan.height(), plan.massT(), true, PlugLift.overCapacity(plan.massT()), 0, true, plan.home());
        }
        Level level = crane.getLevel();
        BridgeSite site = crane.site();
        ReactorControllerBlockEntity r = site == null || !site.ok() ? null : crane.reactor();
        VesselAppearance.Envelope env = r == null ? null : r.vesselEnvelope();
        if (env == null) {
            return null;
        }
        Scan scan = scan(level, site, env.min().getY(), site.railY - 1);
        Gate g = nextOut(scan, laid, env.min().getX() + env.width() / 2.0, env.min().getZ() + env.depth() / 2.0);
        return g == null ? null : new Preview(g.height(), g.massT(), g.rigged(), PlugLift.overCapacity(g.massT()), headAcross(level, g, null), false, g.bottom());
    }

    /** Every gate in the crane's reach (in the canal and laid down), for a computer. */
    List<Gate> gates(BuildingCraneBlockEntity crane) {
        BridgeSite site = crane.site();
        ReactorControllerBlockEntity r = site == null || !site.ok() ? null : crane.reactor();
        VesselAppearance.Envelope env = r == null ? null : r.vesselEnvelope();
        return env == null ? List.of() : scan(crane.getLevel(), site, env.min().getY(), site.railY - 1).gates();
    }

    // ---------------------------------------------------------------
    // The crane's move
    // ---------------------------------------------------------------

    @Nullable
    Point column(BuildingCraneBlockEntity.Loc loc) {
        return new Point(loc.at().getX() + 0.5, 0, loc.at().getZ() + 0.5);
    }

    BridgeMotion.Stop stop(BuildingCraneBlockEntity.Loc loc) {
        double hang = plan == null ? RIG_M + 1 : plan.hookAboveBase();
        return new BridgeMotion.Stop(loc.at().getX() + 0.5, loc.at().getZ() + 0.5, loc.at().getY() + hang, Double.NaN);
    }

    /** Why the move cannot be made now, or null; the crane calls it when the move is queued, started and before the hook closes. */
    @Nullable
    Component refuse(BuildingCraneBlockEntity crane, BuildingCraneBlockEntity.Move m, ReactorControllerBlockEntity r) {
        Level level = crane.getLevel();
        var from = m.from();
        var to = m.to();
        boolean held = from.kind() == BuildingCraneBlockEntity.Kind.HELD;
        Component bad = BwrText.tr("status.bwr.canal_gate_lift.bad_move", "A canal gate goes between its place in the canal and its laydown.");
        if (plan == null || to.kind() != BuildingCraneBlockEntity.Kind.GATE || (!held && from.kind() != BuildingCraneBlockEntity.Kind.GATE)) {
            return bad;
        }
        if (held && entity == null) {
            return BwrText.tr("status.bwr.building_crane.nothing_held", "The hook is not holding anything.");
        }
        BlockPos dst = to.at();
        boolean home = dst.equals(plan.home());
        if (!home && !dst.equals(plan.lay())) {
            return bad;
        }
        if (!held) {
            if (!from.at().equals(plan.source()) || dst.equals(from.at())) {
                return bad;
            }
            Survey sv = survey(level, from.at());
            if (sv.gate() == null) {
                return sv.problem();
            }
            Gate g = sv.gate();
            if (!g.bottom().equals(from.at()) || g.height() != plan.height()) {
                return BwrText.tr("status.bwr.canal_gate_lift.changed", "The gate at %s is not as it was when the lift was queued.", from.at().toShortString());
            }
            Component why = gateProblem(g);
            if (why == null && plan.fromHome()) {
                why = headProblem(level, g);
            }
            if (why == null) {
                why = home ? homeProblem(level, dst, g.height())
                        : PlugSections.placeProblem(level, g.section(dst).blocks(), dst.getY(), BlockPos.ZERO);
            }
            if (why != null) {
                return why;
            }
        } else if (level instanceof ServerLevel server && server.getEntity(entity) instanceof PlugSectionEntity e) {
            Component why = home ? homeProblem(level, dst, plan.height()) : e.placeProblem(level, dst, true);
            if (why != null) {
                return why;
            }
        }
        BridgeSite site = crane.site();
        Point a = column(held ? BuildingCraneBlockEntity.Loc.gate(plan.source()) : from), b = column(to);
        if (!site.reaches(a.x(), a.z()) || !site.reaches(b.x(), b.z())) {
            return BwrText.tr("status.bwr.building_crane.out_of_reach", "That position is outside the reach of the crane.");
        }
        return railsProblem(site, plan);
    }

    /** The hook-on: the gate's blocks leave the world into the entity on the hook; a gate taken out of the canal leaves the canal open behind it. */
    @Nullable
    Component take(ServerLevel server, BuildingCraneBlockEntity crane, BlockPos at, Point hook) {
        if (plan == null) {
            return BwrText.tr("status.bwr.canal_gate_lift.changed", "The gate at %s is not as it was when the lift was queued.", at.toShortString());
        }
        Survey sv = survey(server, at);
        if (sv.gate() == null) {
            return sv.problem();
        }
        Gate g = sv.gate();
        if (!g.bottom().equals(at) || g.height() != plan.height()) {
            return BwrText.tr("status.bwr.canal_gate_lift.changed", "The gate at %s is not as it was when the lift was queued.", at.toShortString());
        }
        Component why = gateProblem(g);
        if (why == null && plan.fromHome()) {
            why = headProblem(server, g);
        }
        if (why != null) {
            return why;
        }
        List<BlockState> states = new ArrayList<>();
        for (BlockPos p : g.blocks()) {
            BlockState st = server.getBlockState(p);
            states.add(st);
            // out of the canal the gate is dry: the entity (and the clients it is sent to) carry no canal water
            server.setBlock(p, st.setValue(TransferCanalBlock.WATER, false).setValue(TransferCanalBlock.FILL, 0), Block.UPDATE_CLIENTS);
        }
        PlugSectionEntity e = PlugSectionEntity.take(server, crane.getBlockPos(), g.section(), new Vec3(hook.x(), hook.y() - plan.hookAboveBase(), hook.z()));
        if (e == null) {
            return BwrText.tr("status.bwr.canal_gate_lift.no_entity", "The gate could not be hooked on: its place in the world would not take the load.");
        }
        if (plan.fromHome()) {
            // the guides stay: the column is open canal, with the water, floor and top the gate's blocks had
            List<BlockPos> blocks = g.blocks();
            for (int i = 0; i < blocks.size(); i++) {
                server.setBlock(blocks.get(i), openCanal(states.get(i)), Block.UPDATE_ALL);
            }
        }
        entity = e.getUUID();
        crane.setChanged();
        return null;
    }

    /** Out of the canal the gate is dry: its blocks carry no canal water (the exchange sets it again once the gate is back in the canal). */
    private static void dry(PlugSectionEntity e) {
        var cells = e.cells();
        for (int i = 0; i < cells.size(); i++) {
            var c = cells.get(i);
            if (c.state().getBlock() instanceof TransferCanalBlock) {
                cells.set(i, new PlugSectionEntity.Cell(c.x(), c.y(), c.z(),
                        c.state().setValue(TransferCanalBlock.WATER, false).setValue(TransferCanalBlock.FILL, 0)));
            }
        }
    }

    /** The open canal block a gate block leaves behind. */
    static BlockState openCanal(BlockState gate) {
        BlockState c = BwrBlocks.TRANSFER_CANAL.get().defaultBlockState();
        if (gate.getBlock() instanceof TransferCanalBlock) {
            c = c.setValue(TransferCanalBlock.AXIS, gate.getValue(TransferCanalBlock.AXIS)).setValue(TransferCanalBlock.WATER, gate.getValue(TransferCanalBlock.WATER))
                    .setValue(TransferCanalBlock.FLOOR, gate.getValue(TransferCanalBlock.FLOOR)).setValue(TransferCanalBlock.TOP, gate.getValue(TransferCanalBlock.TOP))
                    .setValue(TransferCanalBlock.FILL, gate.getValue(TransferCanalBlock.FILL));
        }
        return c;
    }

    /** The set-down: home into the canal (the open canal blocks there give way), or onto the laydown. Returns null, or why not. */
    @Nullable
    Component setDown(ServerLevel server, BuildingCraneBlockEntity crane, BuildingCraneBlockEntity.Loc to) {
        if (plan == null || entity == null) {
            return BwrText.tr("status.bwr.building_crane.cannot_release", "Stopped: the load cannot be set down at %s (occupied, or the reactor is no longer ready). It stays on the hook.", to.describe());
        }
        if (!(server.getEntity(entity) instanceof PlugSectionEntity e)) {
            return BwrText.tr("status.bwr.canal_gate_lift.entity_not_loaded", "Stopped: the gate on the hook is in a chunk that is not loaded. It stays on the hook.");
        }
        BlockPos dst = to.at();
        boolean home = dst.equals(plan.home());
        Component why = home ? putHome(server, e, dst) : layDown(server, e, dst);
        if (why != null) {
            return BwrText.tr("status.bwr.canal_gate_lift.cannot_set_down", "Stopped: %s The gate stays on the hook.", why);
        }
        e.discard();
        if (home) {
            laid.removeIf(l -> l.lay().equals(plan.lay()));
        } else {
            laid.add(new Laid(dst.immutable(), plan.home()));
        }
        entity = null;
        plan = null;
        crane.setChanged();
        return null;
    }

    /** Back into the canal: the open canal blocks give way to the gate, state for state as it was taken. */
    @Nullable
    private static Component putHome(ServerLevel server, PlugSectionEntity e, BlockPos home) {
        Component why = homeProblem(server, home, e.sizeY());
        if (why != null) {
            return why;
        }
        List<BlockState> before = new ArrayList<>();
        for (int i = 0; i < e.sizeY(); i++) {
            BlockPos p = home.above(i);
            before.add(server.getBlockState(p));
        }
        for (int i = 0; i < e.sizeY(); i++) {
            server.setBlock(home.above(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        why = e.settle(server, home, false);
        if (why != null) {
            for (int i = 0; i < before.size(); i++) {
                server.setBlock(home.above(i), before.get(i), Block.UPDATE_ALL);
            }
        }
        return why;
    }

    /** Onto the laydown: the gate stands dry. */
    @Nullable
    private static Component layDown(ServerLevel server, PlugSectionEntity e, BlockPos lay) {
        dry(e);
        return e.settle(server, lay, true);
    }

    /** Puts the hanging gate under the hook. */
    void follow(ServerLevel server, @Nullable Point tip) {
        if (entity == null || plan == null || tip == null) {
            return;
        }
        if (server.getEntity(entity) instanceof PlugSectionEntity e) {
            e.follow(tip.x(), tip.y() - plan.hookAboveBase(), tip.z());
        }
    }

    /** True when the gate's entity is gone although its chunk and entities are loaded (it put its blocks back or dropped them by itself). */
    boolean entityGone(ServerLevel server, @Nullable Point tip) {
        if (entity == null || plan == null || server.getEntity(entity) instanceof PlugSectionEntity) {
            return false;
        }
        BlockPos at = tip == null ? BlockPos.ZERO : BlockPos.containing(tip.x(), tip.y() - plan.hookAboveBase(), tip.z());
        return server.isLoaded(at) && server.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(at));
    }

    /** False while the crane must wait: the gate's entity is not loaded, or the next step of the hook would carry it into an unloaded chunk. */
    boolean worldReady(ServerLevel server, @Nullable Point tip, @Nullable Point next) {
        if (entity == null || plan == null) {
            return true;
        }
        if (!(server.getEntity(entity) instanceof PlugSectionEntity)) {
            BlockPos at = tip == null ? BlockPos.ZERO : BlockPos.containing(tip.x(), tip.y() - plan.hookAboveBase(), tip.z());
            return server.isLoaded(at) && server.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(at));
        }
        if (next != null) {
            return server.isLoaded(BlockPos.containing(next.x(), next.y() - plan.hookAboveBase(), next.z()))
                    && server.isLoaded(BlockPos.containing(next.x(), next.y(), next.z()));
        }
        return true;
    }

    /** The console is broken under a hanging gate: back into its canal column if that is still open, otherwise its blocks drop. Never lost. */
    void dropCarried(ServerLevel server) {
        if (entity == null) {
            return;
        }
        if (server.getEntity(entity) instanceof PlugSectionEntity e) {
            boolean back = plan != null && putHome(server, e, plan.home()) == null;
            if (!back) {
                e.releaseSafely(server, true);
            }
            e.discard();
        }
        entity = null;
        plan = null;
    }

    // ---------------------------------------------------------------
    // Saves
    // ---------------------------------------------------------------

    void save(CompoundTag tag) {
        if (plan == null && entity == null && laid.isEmpty()) {
            return;
        }
        CompoundTag t = new CompoundTag();
        if (plan != null) {
            t.put("Plan", plan.save());
        }
        if (entity != null) {
            t.put("Entity", NbtUtils.createUUID(entity));
        }
        if (!laid.isEmpty()) {
            ListTag list = new ListTag();
            for (Laid l : laid) {
                CompoundTag e = new CompoundTag();
                e.putLong("Lay", l.lay().asLong());
                e.putLong("Home", l.home().asLong());
                list.add(e);
            }
            t.put("Laid", list);
        }
        tag.put("Gate", t);
    }

    void load(CompoundTag tag) {
        plan = null;
        entity = null;
        laid.clear();
        if (!tag.contains("Gate", Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag t = tag.getCompound("Gate");
        plan = t.contains("Plan", Tag.TAG_COMPOUND) ? Plan.load(t.getCompound("Plan")) : null;
        entity = plan != null && t.contains("Entity", Tag.TAG_INT_ARRAY) ? NbtUtils.loadUUID(t.get("Entity")) : null;
        ListTag list = t.getList("Laid", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            laid.add(new Laid(BlockPos.of(e.getLong("Lay")), BlockPos.of(e.getLong("Home"))));
        }
    }
}
