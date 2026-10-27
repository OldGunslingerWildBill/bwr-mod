package dev.bwr.mod.refuelling;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The reactor building crane, one console block and the machine it stands for: an overhead bridge crane on two runs of
 * {@link CraneRunwayRailBlock} high on opposite walls of the reactor hall, a trolley across its girder and a hoist with a hook
 * block. It lifts four things for the reactor under it, one at a time: the vessel head (with its strongback, between the flange and a
 * {@link HeadStandBlock head stand}), the steam dryer and the separator assembly (between the vessel and the
 * {@link EquipmentPadBlockEntity equipment storage pads}). The refuelling bridge moves fuel only. The arithmetic (speeds, legs) is
 * {@link CraneMotion}; what the rails and the reactor are is {@link BridgeSite}.
 *
 * <h2>Nothing moves unless told to</h2>
 * Idle, this block entity has no ticker and nothing scheduled. A player (the console screen) or a program (the peripheral) queues a
 * move; the crane then ticks itself with a scheduled block tick, one a tick, only until the queue is empty or it halts. A halt
 * waits for a player: {@link #resume()} or {@link #abort()}. Nothing is resumed or retried by itself.
 *
 * <h2>The load is in exactly one place</h2>
 * The head leaves the flange (the reactor's {@code headLifted}) or its stand when the hook latches, hangs in this block entity,
 * and goes onto its stand or the flange ({@code headSeated}) when the hook releases. The same holds for the dryer and the
 * separators. Breaking the console sets a hanging dryer or separator assembly back in its vessel and lays a hanging head down.
 *
 * <h2>Chunks and saves</h2>
 * It never loads a chunk: if the chunk of the reactor it works with is not loaded it waits where it is. The state of a move
 * (phase, ticks done, the leg, the route, the queue, the load) is saved only while non-default; ticks elapse only while it ticks,
 * so a world closed mid-lift continues from the same place.
 */
public class BuildingCraneBlockEntity extends BlockEntity {

    public static final int MAX_QUEUE = 8;
    /** The lifting rig (the hook's ring, the spreader and four slings) hangs this far over the top of the lifting eyes of the dryer and the separators, m. */
    public static final double RIG_M = 1.3;

    // ---------------------------------------------------------------
    // Moves
    // ---------------------------------------------------------------

    public enum Kind { HEAD_SEAT, HEAD_STAND, HEAD_LAID, INTERNAL, PAD, HELD, PLUG, TURBINE_COVER, CASING_STAND, TURBINE_BLADES, BLADE_STAND, GATE, SHAFT_WRECK }

    /** A place: the head's seat on the flange, a head stand, the head laid down (older worlds), the dryer's or separators' seat, a pad, or the hook itself. */
    public record Loc(Kind kind, BlockPos at, int which) {
        public static final Loc SEAT = new Loc(Kind.HEAD_SEAT, BlockPos.ZERO, 0);
        public static final Loc LAID = new Loc(Kind.HEAD_LAID, BlockPos.ZERO, 0);
        public static final Loc HELD = new Loc(Kind.HELD, BlockPos.ZERO, 0);

        public static Loc stand(BlockPos stand) {
            return new Loc(Kind.HEAD_STAND, stand.immutable(), 0);
        }

        public static Loc internal(VesselInternal which) {
            return new Loc(Kind.INTERNAL, BlockPos.ZERO, which.ordinal());
        }

        public static Loc pad(BlockPos pad, VesselInternal which) {
            return new Loc(Kind.PAD, pad.immutable(), which.ordinal());
        }

        /** A shield plug section whose minimum corner is at {@code min} (where it stands, or where it is to be set down). */
        public static Loc plug(BlockPos min) {
            return new Loc(Kind.PLUG, min.immutable(), 0);
        }

        /** The hood of the LP turbine section whose controller is at {@code lp} (TURBINE-MAINTENANCE.md). */
        public static Loc cover(BlockPos lp) {
            return new Loc(Kind.TURBINE_COVER, lp.immutable(), 0);
        }

        public static Loc casing(BlockPos stand) {
            return new Loc(Kind.CASING_STAND, stand.immutable(), 0);
        }

        /** One flow's bladed rotor section in the LP turbine at {@code lp}: 0 is flow A (towards its facing), 1 flow B. */
        public static Loc blades(BlockPos lp, int flow) {
            return new Loc(Kind.TURBINE_BLADES, lp.immutable(), flow);
        }

        /** A saddle (0 or 1) of an LP Blade Storage Stand. */
        public static Loc bladeStand(BlockPos stand, int slot) {
            return new Loc(Kind.BLADE_STAND, stand.immutable(), slot);
        }

        /** The rotor half a shaft excursion left on the floor, the Turbine Shaft Wreck block at {@code wreck} (TURBINE-MAINTENANCE.md). */
        public static Loc shaftWreck(BlockPos wreck) {
            return new Loc(Kind.SHAFT_WRECK, wreck.immutable(), 0);
        }

        /** A canal gate whose bottom block is at {@code bottom} (in its canal column, or on its laydown); see {@link CanalGateLift}. */
        public static Loc gate(BlockPos bottom) {
            return new Loc(Kind.GATE, bottom.immutable(), 0);
        }

        public VesselInternal internal() {
            return VesselInternal.values()[Math.clamp(which, 0, VesselInternal.values().length - 1)];
        }

        public boolean isHead() {
            return kind == Kind.HEAD_SEAT || kind == Kind.HEAD_STAND || kind == Kind.HEAD_LAID;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("K", kind.name());
            if (kind == Kind.HEAD_STAND || kind == Kind.PAD || kind == Kind.PLUG || TurbineHallLifts.isTurbineLoc(this) || kind == Kind.GATE) {
                t.putLong("R", at.asLong());
            }
            if (kind == Kind.INTERNAL || kind == Kind.PAD || kind == Kind.TURBINE_BLADES || kind == Kind.BLADE_STAND) {
                t.putByte("C", (byte) which);
            }
            return t;
        }

        static Loc load(CompoundTag t) {
            Kind k;
            try {
                k = Kind.valueOf(t.getString("K"));
            } catch (IllegalArgumentException e) {
                k = Kind.HELD;
            }
            return switch (k) {
                case HEAD_SEAT -> SEAT;
                case HEAD_LAID -> LAID;
                case HEAD_STAND -> new Loc(Kind.HEAD_STAND, BlockPos.of(t.getLong("R")), 0);
                case INTERNAL -> new Loc(Kind.INTERNAL, BlockPos.ZERO, t.getByte("C"));
                case PAD -> new Loc(Kind.PAD, BlockPos.of(t.getLong("R")), t.getByte("C"));
                case PLUG -> new Loc(Kind.PLUG, BlockPos.of(t.getLong("R")), 0);
                case TURBINE_COVER, CASING_STAND -> new Loc(k, BlockPos.of(t.getLong("R")), 0);
                case TURBINE_BLADES, BLADE_STAND -> new Loc(k, BlockPos.of(t.getLong("R")), Math.clamp(t.getByte("C"), 0, 1));
                case GATE -> new Loc(Kind.GATE, BlockPos.of(t.getLong("R")), 0);
                case SHAFT_WRECK -> new Loc(Kind.SHAFT_WRECK, BlockPos.of(t.getLong("R")), 0);
                default -> HELD;
            };
        }

        public String describe() {
            return switch (kind) {
                case HEAD_SEAT -> "the head's seat on the vessel flange";
                case HEAD_STAND -> String.format(Locale.ROOT, "the head stand at %d,%d,%d", at.getX(), at.getY(), at.getZ());
                case HEAD_LAID -> "where the head lies";
                case INTERNAL -> "the " + internal().title + "'s seat in the vessel";
                case PAD -> String.format(Locale.ROOT, "the equipment pad at %d,%d,%d", at.getX(), at.getY(), at.getZ());
                case HELD -> "the hook";
                case PLUG -> String.format(Locale.ROOT, "the shield plug section at %d,%d,%d", at.getX(), at.getY(), at.getZ());
                case TURBINE_COVER -> String.format(Locale.ROOT, "the casing of the LP turbine at %d,%d,%d", at.getX(), at.getY(), at.getZ());
                case CASING_STAND -> String.format(Locale.ROOT, "the casing laydown stand at %d,%d,%d", at.getX(), at.getY(), at.getZ());
                case TURBINE_BLADES -> String.format(Locale.ROOT, "flow %s of the LP turbine at %d,%d,%d", which == 0 ? "A" : "B", at.getX(), at.getY(), at.getZ());
                case BLADE_STAND -> String.format(Locale.ROOT, "saddle %d of the LP blade stand at %d,%d,%d", which + 1, at.getX(), at.getY(), at.getZ());
                case GATE -> String.format(Locale.ROOT, "the canal gate at %d,%d,%d", at.getX(), at.getY(), at.getZ());
                case SHAFT_WRECK -> String.format(Locale.ROOT, "the turbine shaft wreck at %d,%d,%d", at.getX(), at.getY(), at.getZ());
            };
        }
    }

    public record Move(Loc from, Loc to) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.put("From", from.save());
            t.put("To", to.save());
            return t;
        }

        static Move load(CompoundTag t) {
            return new Move(Loc.load(t.getCompound("From")), Loc.load(t.getCompound("To")));
        }
    }

    /** The rails' geometry as the client's renderer needs it. */
    public record Geom(boolean alongX, int railY, int aLo, int aHi, int cLow, int cHigh) {
    }

    /**
     * A shield plug lift in progress: where the section belongs ({@code homeMin}, its minimum corner in the plug) and where it is set down
     * ({@code layMin}), its size in blocks, the pick point (the middle of its four anchors, relative to the minimum corner), the height of the
     * hook over the section's top, the four anchors (x, y, z in cells from the minimum corner, twelve numbers), its mass, its block count and
     * whether the move goes from home to the laydown ({@code fromHome}, PLUG OFF) or back (PLUG ON). Saved while a lift is under way.
     */
    public record PlugPlan(BlockPos homeMin, BlockPos layMin, int sx, int sy, int sz, double px, double pz, double rig, int[] anchors,
                           double massT, int blocks, boolean fromHome) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Home", homeMin.asLong());
            t.putLong("Lay", layMin.asLong());
            t.putIntArray("Size", new int[]{sx, sy, sz, blocks});
            t.putDouble("PX", px);
            t.putDouble("PZ", pz);
            t.putDouble("Rig", rig);
            t.putIntArray("Anchors", anchors);
            t.putDouble("Mass", massT);
            t.putBoolean("FromHome", fromHome);
            return t;
        }

        static PlugPlan load(CompoundTag t) {
            int[] size = t.getIntArray("Size");
            if (size.length < 4) {
                return null;
            }
            return new PlugPlan(BlockPos.of(t.getLong("Home")), BlockPos.of(t.getLong("Lay")), size[0], size[1], size[2], t.getDouble("PX"),
                    t.getDouble("PZ"), t.getDouble("Rig"), t.getIntArray("Anchors"), t.getDouble("Mass"), size[3], t.getBoolean("FromHome"));
        }

        /** The hook's height over the section's lowest block. */
        public double hookAboveBase() {
            return sy + rig;
        }

        BlockPos anchorAt(BlockPos min, int i) {
            return min.offset(anchors[3 * i], anchors[3 * i + 1], anchors[3 * i + 2]);
        }
    }

    /** A section set down on the laydown: where, where it came from, and an anchor to find it by. */
    public record Laid(BlockPos lay, BlockPos home, BlockPos seed) {
    }

    /** The most a section's hook may hang over its top, so a long slung section's slings stay steep. */
    public static final double RIG_ANGLE_FACTOR = 1.2;

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private Phase phase = Phase.IDLE;
    private int elapsed;
    private int duration = 1;
    private Point pos = null;          // where the current leg started (a settled position when idle)
    private Point legTo = null;
    private Move move;
    private final ArrayDeque<Move> queue = new ArrayDeque<>();
    /** The vessel head on the hook. */
    private boolean carriedHead;
    /** The dryer or the separator assembly on the hook (null: none). */
    @Nullable
    private VesselInternal carriedInternal;
    /** The size of the vessel the load belongs to (w, d, h, internal pumps); empty with nothing on the hook. */
    private int[] carriedVessel = new int[0];
    private Phase resumeAt = Phase.IDLE;
    private Component haltMessage;
    private final List<Point> route = new ArrayList<>();
    private int routeIndex;
    private boolean raisingForMove;
    private Geom geom;
    /** The shield plug lift in progress, or null. */
    @Nullable
    private PlugPlan plan;
    /** The entity that holds the section on the hook, or null. */
    @Nullable
    private UUID plugEntity;
    /** Sections set down on the laydown by PLUG OFF, oldest first; PLUG ON takes the newest. */
    private final List<Laid> laid = new ArrayList<>();
    /** The turbine hall load on the hook (TURBINE-MAINTENANCE.md): an LP hood or blade section, as its item; empty with none. */
    private net.minecraft.world.item.ItemStack carriedTurbine = net.minecraft.world.item.ItemStack.EMPTY;
    /** The canal gate lift (GATE OUT / GATE IN), a lift kind of its own. */
    private final CanalGateLift gates = new CanalGateLift();

    // transient
    private BridgeSite site;
    private int ticksRun;
    /** Standing still although busy: a chunk it needs is not loaded (or a development hold); sent to clients so they stop extrapolating. */
    private boolean paused;
    private boolean devHold;
    // client
    private long clientSyncTick;

    public BuildingCraneBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.BUILDING_CRANE.get(), pos, state);
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            if (busy()) {
                schedule(server);
            } else if (pos == null) {
                schedule(server); // one tick to survey the rails and take the rest position
            }
            if (holding()) {
                updateDose(server);
            }
        } else if (level != null) {
            clientSyncTick = level.getGameTime();
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            RadiationSources.clear(server, this);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel server) {
            RadiationSources.clear(server, this);
        }
        super.onChunkUnloaded();
    }

    private void schedule(ServerLevel server) {
        if (!server.getBlockTicks().hasScheduledTick(worldPosition, getBlockState().getBlock())) {
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    /**
     * A development hook for the client check's still pictures: holds a move where it is (true) or lets it go on (false).
     * Not saved, and nothing in the game calls it.
     */
    public void devHold(boolean on) {
        devHold = on;
        if (level instanceof ServerLevel server) {
            if (on != paused && !busy()) {
                paused = on;
            }
            schedule(server);
        }
    }

    /** A development hook: sets the resting hook position (the client check's pose for the crane over the hall). */
    public void devPlace(Point p) {
        if (!busy()) {
            pos = p;
            setChanged();
            sync();
        }
    }

    /** True while the crane is working (a scheduled tick each game tick); false idle or halted. */
    public boolean busy() {
        return phase != Phase.IDLE && phase != Phase.HALTED;
    }

    /** How many times the scheduled tick has run (tests: it must not move while idle). */
    public int ticksRun() {
        return ticksRun;
    }

    // ---------------------------------------------------------------
    // Reading the state
    // ---------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    public int queued() {
        return queue.size();
    }

    @Nullable
    public Move currentMove() {
        return move;
    }

    public List<Move> queuedMoves() {
        return List.copyOf(queue);
    }

    /** True while the vessel head hangs from the hook. */
    public boolean carriedHead() {
        return carriedHead;
    }

    /** The dryer or the separator assembly the hook holds, or null. */
    @Nullable
    public VesselInternal carriedInternal() {
        return carriedInternal;
    }

    /** The vessel the load belongs to (for drawing it), or null. */
    @Nullable
    public VesselProfile carriedProfile() {
        return !holding() || carriedVessel.length < 4 ? null
                : VesselProfile.of(carriedVessel[0], carriedVessel[1], carriedVessel[2], carriedVessel[3] != 0);
    }

    /** True while the hook holds the head, the dryer or the separator assembly, a shield plug section or a turbine hall load. */
    public boolean holding() {
        return carriedHead || carriedInternal != null || plugEntity != null || !carriedTurbine.isEmpty() || gates.holding();
    }

    // ---------------------------------------------------------------
    // The turbine hall (TURBINE-MAINTENANCE.md): COVER OFF / ON, BLADES OUT / IN
    // ---------------------------------------------------------------

    /** The LP hood or blade section on the hook (do not change it), or empty. */
    public net.minecraft.world.item.ItemStack carriedTurbine() {
        return carriedTurbine;
    }

    void setCarriedTurbine(net.minecraft.world.item.ItemStack s) {
        carriedTurbine = s == null ? net.minecraft.world.item.ItemStack.EMPTY : s;
        setChanged();
    }

    /** COVER OFF ({@code off}) or COVER ON, for the LP turbine with a block at {@code lp} or (null) the nearest under the crane. Null when queued. */
    @Nullable
    public Component queueCover(boolean off, @Nullable BlockPos lp) {
        return level instanceof ServerLevel ? TurbineHallLifts.queueCover(this, off, lp) : null;
    }

    /** BLADES OUT ({@code out}) or BLADES IN, for the LP turbine with a block at {@code lp} or (null) the nearest under the crane. Null when queued. */
    @Nullable
    public Component queueBlades(boolean out, @Nullable BlockPos lp) {
        return level instanceof ServerLevel ? TurbineHallLifts.queueBlades(this, out, lp) : null;
    }

    /** The LP turbine sections under the crane, nearest the hook first (a scan on demand). */
    public List<java.util.Map<String, Object>> turbineSections() {
        return TurbineHallLifts.survey(this);
    }

    /** The nearest LP under the crane in one line ("" with none). */
    public String turbineSummary() {
        return TurbineHallLifts.summary(this);
    }

    /** True while the crane works a turbine hall lift or holds its load (no reactor is needed for these). */
    private boolean turbineWork() {
        if (!carriedTurbine.isEmpty() || (move != null && TurbineHallLifts.isTurbineMove(this, move))) {
            return true;
        }
        for (Move m : queue) {
            if (TurbineHallLifts.isTurbineMove(this, m)) {
                return true;
            }
        }
        return false;
    }

    /** The shield plug lift in progress (the client draws its slings from this), or null. */
    @Nullable
    public PlugPlan plugPlan() {
        return plan;
    }

    /** True while a shield plug section hangs from the hook. */
    public boolean carriedPlug() {
        return plugEntity != null;
    }

    @Nullable
    public UUID plugEntityId() {
        return plugEntity;
    }

    /** How many sections stand on the laydown, taken off the plug and waiting for PLUG ON. */
    public int plugsLaid() {
        return laid.size();
    }

    public List<Laid> laidSections() {
        return List.copyOf(laid);
    }

    public boolean ownsPlugEntity(UUID id) {
        return id.equals(plugEntity) || gates.owns(id);
    }

    /** The canal gate lift: its plan, the gate on the hook, the gates laid down. */
    public CanalGateLift gateLift() {
        return gates;
    }

    /** True while a canal gate hangs from the hook. */
    public boolean carriedGate() {
        return gates.holding();
    }

    @Nullable
    public Component haltMessage() {
        return haltMessage;
    }

    @Nullable
    public Geom geom() {
        return geom;
    }

    @Nullable
    public BridgeSite site() {
        return site;
    }

    /** 0..1 progress through the current phase. */
    public double progress() {
        return phase == Phase.IDLE || phase == Phase.HALTED ? 0.0 : Math.min(1.0, elapsed / (double) Math.max(1, duration));
    }

    public int phaseTicksLeft() {
        return busy() ? Math.max(0, duration - elapsed) : 0;
    }

    /** The hook tip now: server from the leg, client from the synced leg and the clock. */
    @Nullable
    public Point tip(float partial) {
        if (pos == null) {
            return null;
        }
        if (!busy() || legTo == null) {
            return pos;
        }
        double e = elapsed;
        if (paused) {
            return BridgeMotion.lerp(pos, legTo, e, duration);
        }
        if (level != null && level.isClientSide()) {
            e += Math.max(0L, level.getGameTime() - clientSyncTick) + partial;
        } else {
            e += partial;
        }
        return BridgeMotion.lerp(pos, legTo, e, duration);
    }

    public Point tip() {
        return tip(0f);
    }

    public Phase shownPhase() {
        return phase;
    }

    // ---------------------------------------------------------------
    // Survey
    // ---------------------------------------------------------------

    /** Reads the rails and the reactor again. Cheap enough for a click or a once-a-second snapshot, never per tick. */
    public BridgeSite refreshSite() {
        if (level == null || level.isClientSide()) {
            return site;
        }
        BridgeSite s = BridgeSite.surveyCrane(level, worldPosition);
        site = s;
        if (s.ok()) {
            Geom g = new Geom(s.runwayAlongX(), s.railY, s.aLo, s.aHi, s.cLow, s.cHigh);
            boolean changed = !g.equals(geom);
            geom = g;
            if (pos == null || !s.reaches(pos.x(), pos.z())) {
                if (!busy() && phase != Phase.HALTED) {
                    pos = s.home();
                    changed = true;
                }
            }
            if (changed) {
                setChanged();
                sync();
            }
        }
        return s;
    }

    ReactorControllerBlockEntity reactor() {
        return site == null ? null : site.reactor(level);
    }

    @Nullable
    private EquipmentPadBlockEntity padAt(BlockPos p) {
        return level != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof EquipmentPadBlockEntity pad ? pad : null;
    }

    @Nullable
    private HeadStandBlockEntity standAt(BlockPos p) {
        return level != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof HeadStandBlockEntity stand ? stand : null;
    }

    // ---------------------------------------------------------------
    // The head stand
    // ---------------------------------------------------------------

    /**
     * Why a head stand cannot take this vessel's head, or null: it holds one already, the head (as wide as the vessel) would hang over
     * the rails or the walls of the crane's reach, or it would lie on the vessel itself.
     */
    @Nullable
    private Component standProblem(HeadStandBlockEntity stand, ReactorControllerBlockEntity r, @Nullable HeadStandBlockEntity ignore) {
        if (stand.holdsHead() && stand != ignore) {
            return BwrText.tr("status.bwr.building_crane.stand_taken", "That head stand already holds a head.");
        }
        var e = r.vesselEnvelope();
        if (e == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        BlockPos p = stand.getBlockPos();
        double sx = p.getX() + 0.5, sz = p.getZ() + 0.5;
        double hx = e.width() / 2.0, hz = e.depth() / 2.0;
        for (int i = 0; i < 4; i++) {
            double cx = sx + ((i & 1) == 0 ? -hx : hx) * 0.98;
            double cz = sz + ((i & 2) == 0 ? -hz : hz) * 0.98;
            if (!site.reaches(cx, cz)) {
                return BwrText.tr("status.bwr.building_crane.stand_out_of_reach",
                        "The head would hang over the edge of the crane's reach at the head stand at %s: it is %s blocks across. Put the stand further in from the rails.",
                        p.toShortString(), Math.max(e.width(), e.depth()));
            }
        }
        double vx = e.min().getX() + e.width() / 2.0, vz = e.min().getZ() + e.depth() / 2.0;
        if (Math.abs(sx - vx) < e.width() && Math.abs(sz - vz) < e.depth()) {
            return BwrText.tr("status.bwr.building_crane.stand_on_vessel",
                    "The head stand at %s is too close to the vessel: the head is %s blocks across and would lie on the well.", p.toShortString(), Math.max(e.width(), e.depth()));
        }
        return null;
    }

    /** The free head stand nearest the vessel that can take its head, or null. */
    @Nullable
    private BlockPos nearestFreeStand(ReactorControllerBlockEntity r) {
        var e = r.vesselEnvelope();
        if (e == null) {
            return null;
        }
        double vx = e.min().getX() + e.width() / 2.0, vz = e.min().getZ() + e.depth() / 2.0;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : HeadStandBlockEntity.stands(level)) {
            HeadStandBlockEntity stand = standAt(p);
            if (stand == null || !site.reaches(p.getX() + 0.5, p.getZ() + 0.5) || standProblem(stand, r, null) != null) {
                continue;
            }
            double d = Math.hypot(p.getX() + 0.5 - vx, p.getZ() + 0.5 - vz);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Why there is no usable head stand, for the message: the first problem found among the stands that reach, or "none placed". */
    private Component noStandMessage(ReactorControllerBlockEntity r) {
        for (BlockPos p : HeadStandBlockEntity.stands(level)) {
            HeadStandBlockEntity stand = standAt(p);
            if (stand != null && site.reaches(p.getX() + 0.5, p.getZ() + 0.5)) {
                Component why = standProblem(stand, r, null);
                if (why != null) {
                    return why;
                }
            }
        }
        return BwrText.tr("status.bwr.building_crane.no_free_stand",
                "No free head stand between the rails: place one on the refuel floor beside the well, with room for a head as wide as the vessel.");
    }

    // ---------------------------------------------------------------
    // Queueing
    // ---------------------------------------------------------------

    /** The vessel's profile at this reactor (for the assemblies' heights). */
    private static VesselProfile profileOf(ReactorControllerBlockEntity r) {
        return RefuellingBridgeBlockEntity.profileOf(r);
    }

    /** Why a move cannot be done now, or null if it can. Uses the current survey. */
    @Nullable
    private Component refuse(Move m) {
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.building_crane.not_surveyed", "The crane has not surveyed its rails yet.") : site.problem;
        }
        if (m.from().equals(m.to())) {
            return BwrText.tr("status.bwr.refuelling_bridge.same_place", "That move starts and ends in the same place.");
        }
        if (TurbineHallLifts.isTurbineMove(this, m)) {
            return TurbineHallLifts.refuse(this, m);
        }
        ReactorControllerBlockEntity r = reactor();
        if (r == null || r.vesselEnvelope() == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        Loc from = m.from(), to = m.to();
        if (isPlugMove(m)) {
            return refusePlug(m, r);
        }
        if (isGateMove(m)) {
            return gates.refuse(this, m, r);
        }
        boolean head = from.isHead() || to.isHead() || (from.kind() == Kind.HELD && carriedHead);
        return head ? refuseHead(m, r) : refuseInternal(m, r);
    }

    @Nullable
    private Component refuseHead(Move m, ReactorControllerBlockEntity r) {
        Loc from = m.from(), to = m.to();
        if ((!from.isHead() && from.kind() != Kind.HELD) || (!to.isHead() && to.kind() != Kind.HELD) || to.kind() == Kind.HEAD_LAID
                || to.kind() == Kind.HELD || (from.kind() == Kind.HELD && !carriedHead)) {
            return from.kind() == Kind.HELD && !carriedHead
                    ? BwrText.tr("status.bwr.building_crane.nothing_held", "The hook is not holding anything.")
                    : BwrText.tr("status.bwr.building_crane.bad_head_move", "The vessel head goes between the vessel flange and a head stand.");
        }
        if (from.kind() == Kind.HEAD_SEAT || to.kind() == Kind.HEAD_SEAT) {
            // the head goes up and down the well: no shield plug may stand over it
            var over = PlugSections.plugOverWell(level, r.vesselEnvelope(), site.railY);
            if (!over.isEmpty()) {
                return BwrText.tr("status.bwr.building_crane.plug_over_well",
                        "The head cannot move: a shield plug still covers the reactor well (%s plug blocks, the nearest at %s). Lift it off with PLUG OFF first.",
                        over.size(), over.get(0).toShortString());
            }
        }
        if (from.kind() == Kind.HEAD_SEAT) {
            Component why = r.headLiftBlocked();
            if (why != null) {
                return BwrText.tr("status.bwr.building_crane.cannot_lift_head", "The head cannot be lifted: %s.", why);
            }
        } else if (from.kind() == Kind.HEAD_STAND) {
            HeadStandBlockEntity stand = standAt(from.at());
            if (stand == null || !stand.holdsHead() || !stand.reactorPos().equals(r.getBlockPos())) {
                return BwrText.tr("status.bwr.building_crane.stand_empty", "That head stand does not hold this reactor's head.");
            }
        } else if (from.kind() == Kind.HEAD_LAID) {
            if (r.vesselState() != VesselState.REFUELING || r.headLocation() != null) {
                return BwrText.tr("status.bwr.building_crane.head_not_laid", "The head is not lying clear of the vessel.");
            }
        }
        if (to.kind() == Kind.HEAD_SEAT) {
            Component why = r.headSeatBlocked();
            if (why != null) {
                return BwrText.tr("status.bwr.building_crane.cannot_seat_head", "The head cannot be set on the vessel: %s.", why);
            }
        } else if (to.kind() == Kind.HEAD_STAND) {
            HeadStandBlockEntity stand = standAt(to.at());
            if (stand == null) {
                return BwrText.tr("status.bwr.building_crane.no_stand", "There is no head stand there.");
            }
            Component why = standProblem(stand, r, null);
            if (why != null) {
                return why;
            }
        }
        Point a = columnOf(from), b = columnOf(to);
        if (a == null || b == null || !site.reaches(a.x(), a.z()) || !site.reaches(b.x(), b.z())) {
            return BwrText.tr("status.bwr.building_crane.out_of_reach", "That position is outside the reach of the crane.");
        }
        // Hanging from the raised hook, the head's mating face must clear the studs standing in the flange before it moves sideways.
        VesselProfile vp = profileOf(r);
        double clear = r.vesselEnvelope().min().getY() + vp.flangeFace + vp.studTop() + 0.25;
        if (site.homeY() - vp.headLift() < clear) {
            return BwrText.tr("status.bwr.building_crane.rails_too_low_head",
                    "The runway is too low to lift the head clear of the vessel: it hangs %s blocks below the hook. Raise the rails to y %s or higher.",
                    String.format(Locale.ROOT, "%.1f", vp.headLift()), (int) Math.ceil(clear + vp.headLift() - CraneMotion.HOOK_HOME_ABOVE_RAIL_M));
        }
        return null;
    }

    @Nullable
    private Component refuseInternal(Move m, ReactorControllerBlockEntity r) {
        Loc from = m.from(), to = m.to();
        VesselInternal which = from.kind() == Kind.HELD ? carriedInternal : from.kind() == Kind.INTERNAL || from.kind() == Kind.PAD ? from.internal() : null;
        if (which == null || (to.kind() != Kind.INTERNAL && to.kind() != Kind.PAD) || to.internal() != which || from.kind() == to.kind()) {
            return BwrText.tr("status.bwr.building_crane.bad_internal_move", "The dryer and the separator assembly go between the vessel and an equipment pad.");
        }
        if (from.kind() == Kind.HELD && carriedInternal != which) {
            return BwrText.tr("status.bwr.building_crane.nothing_held", "The hook is not holding anything.");
        }
        if (from.kind() == Kind.INTERNAL) {
            Component why = r.internalLiftBlocked(which);
            if (why != null) {
                return BwrText.tr("status.bwr.refuelling_bridge.cannot_lift_internal", "The %s cannot be lifted out: %s.", which.title, why);
            }
        }
        if (to.kind() == Kind.INTERNAL && !(from.kind() == Kind.HELD && move != null)) {
            Component why = r.internalInstallBlocked(which);
            if (why != null) {
                return BwrText.tr("status.bwr.refuelling_bridge.cannot_set_internal", "The %s cannot be set back: %s.", which.title, why);
            }
        }
        if (from.kind() == Kind.PAD) {
            EquipmentPadBlockEntity pad = padAt(from.at());
            if (pad == null || pad.stored() != which || !pad.reactorPos().equals(r.getBlockPos())) {
                return BwrText.tr("status.bwr.refuelling_bridge.pad_empty", "That equipment pad does not hold this reactor's %s.", which.title);
            }
        }
        if (to.kind() == Kind.PAD) {
            EquipmentPadBlockEntity pad = padAt(to.at());
            if (pad == null) {
                return BwrText.tr("status.bwr.refuelling_bridge.no_pad", "There is no equipment pad there: place one on the floor of a pool of water the bridge reaches.");
            }
            if (pad.stored() != null) {
                return BwrText.tr("status.bwr.refuelling_bridge.pad_taken", "That equipment pad already holds the %s.", pad.stored().title);
            }
        }
        Point a = columnOf(from), b = columnOf(to);
        if (a == null || b == null || !site.reaches(a.x(), a.z()) || !site.reaches(b.x(), b.z())) {
            return BwrText.tr("status.bwr.building_crane.out_of_reach", "That position is outside the reach of the crane.");
        }
        // Hanging from the raised hook, the assembly must clear the vessel's lip before it moves sideways.
        VesselProfile vp = profileOf(r);
        double clear = r.vesselEnvelope().min().getY() + vp.rim + 0.25;
        double hanging = EquipmentPads.liftAboveBottom(which, vp) + RIG_M;
        if (site.homeY() - hanging < clear) {
            return BwrText.tr("status.bwr.building_crane.rails_too_low",
                    "The runway is too low to lift the %s clear of the vessel: it hangs %s blocks below the hook. Raise the rails to y %s or higher.",
                    which.title, String.format(Locale.ROOT, "%.1f", hanging), (int) Math.ceil(clear + hanging - CraneMotion.HOOK_HOME_ABOVE_RAIL_M));
        }
        return null;
    }

    /**
     * Queues lifting the head off the flange onto the nearest free head stand ({@code out}), or setting it back on the flange from the
     * stand that holds it. If the hook already holds the head (after an abort), the move starts from the hook.
     */
    @Nullable
    public Component queueHead(boolean out) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        refreshSite();
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.building_crane.not_surveyed", "The crane has not surveyed its rails yet.") : site.problem;
        }
        ReactorControllerBlockEntity r = reactor();
        if (r == null || r.vesselEnvelope() == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        Loc from;
        if (carriedHead && move == null) {
            from = Loc.HELD;
        } else if (out) {
            Component why = r.headLiftBlocked();
            if (why != null) {
                return BwrText.tr("status.bwr.building_crane.cannot_lift_head", "The head cannot be lifted: %s.", why);
            }
            from = Loc.SEAT;
        } else {
            BlockPos at = r.headLocation();
            if (r.vesselState() != VesselState.REFUELING) {
                return BwrText.tr("status.bwr.building_crane.cannot_seat_head", "The head cannot be set on the vessel: %s.", r.headSeatBlocked());
            }
            if (at == null) {
                from = Loc.LAID;
            } else if (standAt(at) != null && standAt(at).holdsHead()) {
                from = Loc.stand(at);
            } else {
                return BwrText.tr("status.bwr.building_crane.stand_empty", "That head stand does not hold this reactor's head.");
            }
        }
        Loc to;
        if (out) {
            BlockPos stand = nearestFreeStand(r);
            if (stand == null) {
                return noStandMessage(r);
            }
            to = Loc.stand(stand);
        } else {
            to = Loc.SEAT;
        }
        return queue(new Move(from, to));
    }

    /**
     * Queues lifting the dryer or the separator assembly out to the nearest free equipment pad ({@code out}), or setting it back in from
     * the pad that holds it. If the hook already holds it (after an abort), the move starts from the hook.
     */
    @Nullable
    public Component queueInternal(VesselInternal which, boolean out) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        refreshSite();
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.building_crane.not_surveyed", "The crane has not surveyed its rails yet.") : site.problem;
        }
        ReactorControllerBlockEntity r = reactor();
        if (r == null || r.vesselEnvelope() == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        Loc from;
        if (carriedInternal == which && move == null) {
            from = Loc.HELD;
        } else if (out) {
            from = Loc.internal(which);
        } else {
            BlockPos at = r.internalLocation(which);
            if (at == null || padAt(at) == null) {
                return r.internalInstalled(which)
                        ? BwrText.tr("status.bwr.reactor_controller.internal_already_in", "the %s is already in the vessel", which.title)
                        : BwrText.tr("status.bwr.refuelling_bridge.pad_empty", "That equipment pad does not hold this reactor's %s.", which.title);
            }
            from = Loc.pad(at, which);
        }
        if (out && from.kind() == Kind.INTERNAL) {
            Component why = r.internalLiftBlocked(which);
            if (why != null) {
                return BwrText.tr("status.bwr.refuelling_bridge.cannot_lift_internal", "The %s cannot be lifted out: %s.", which.title, why);
            }
        }
        Loc to;
        if (out) {
            Point c = columnOf(Loc.internal(which));
            BlockPos pad = c == null ? null : EquipmentPads.nearestFree(level, site, c.x(), c.z());
            if (pad == null) {
                return BwrText.tr("status.bwr.refuelling_bridge.no_free_pad",
                        "No free equipment pad between the rails: place one on the floor of a pool of water the bridge reaches.");
            }
            to = Loc.pad(pad, which);
        } else {
            to = Loc.internal(which);
        }
        return queue(new Move(from, to));
    }

    /** The column of a place (y unused). */
    @Nullable
    private Point columnOf(Loc loc) {
        switch (loc.kind()) {
            case HEAD_SEAT, HEAD_LAID, INTERNAL -> {
                ReactorControllerBlockEntity r = reactor();
                var e = r == null ? null : r.vesselEnvelope();
                return e == null ? null : new Point(e.min().getX() + e.width() / 2.0, 0, e.min().getZ() + e.depth() / 2.0);
            }
            case HEAD_STAND, PAD -> {
                return new Point(loc.at().getX() + 0.5, 0, loc.at().getZ() + 0.5);
            }
            case PLUG -> {
                return plan == null ? null : new Point(loc.at().getX() + plan.px(), 0, loc.at().getZ() + plan.pz());
            }
            case TURBINE_COVER, CASING_STAND, TURBINE_BLADES, BLADE_STAND, SHAFT_WRECK -> {
                return TurbineHallLifts.column(this, loc);
            }
            case GATE -> {
                return gates.column(loc);
            }
            default -> {
                return pos == null ? null : new Point(pos.x(), 0, pos.z());
            }
        }
    }

    /**
     * Queues a move. Returns null when it was queued, or the reason it was not. Nothing starts until this is called by a
     * player's click or a program's call.
     */
    @Nullable
    public Component queue(Move m) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        refreshSite();
        if (holding() && move == null && m.from().kind() != Kind.HELD) {
            return BwrText.tr("status.bwr.building_crane.holding", "The hook still holds a load: set it down first.");
        }
        if (plan != null && !isPlugMove(m)) {
            return BwrText.tr("status.bwr.building_crane.plug_busy", "The crane is busy with a shield plug section: set it down first.");
        }
        if (gates.plan() != null && !isGateMove(m)) {
            return BwrText.tr("status.bwr.canal_gate_lift.busy", "The crane is busy with a canal gate: set it down first.");
        }
        Component why = refuse(m);
        if (why != null) {
            return why;
        }
        if (queue.size() + (move != null ? 1 : 0) >= MAX_QUEUE) {
            return BwrText.tr("status.bwr.refuelling_bridge.queue_full", "The queue is full (%s moves).", MAX_QUEUE);
        }
        queue.addLast(m);
        setChanged();
        if (phase == Phase.IDLE || (phase == Phase.HALTED && move == null)) {
            startNext(server);
        } else {
            sync();
        }
        return null;
    }

    /** Stops the crane where it is and forgets the queue. A load on the hook stays on it. */
    public void abort() {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        if (busy()) {
            pos = tip();
        }
        queue.clear();
        move = null;
        if (plugEntity == null) {
            plan = null; // a plug lift stopped before the hook took the section has nothing to remember
        }
        gates.forgetPlanIfEmpty();
        route.clear();
        routeIndex = 0;
        raisingForMove = false;
        haltMessage = null;
        phase = Phase.IDLE;
        elapsed = 0;
        legTo = null;
        updateDose(server);
        setChanged();
        sync();
    }

    /** After a halt, try the step again. */
    public void resume() {
        if (!(level instanceof ServerLevel server) || phase != Phase.HALTED) {
            return;
        }
        Phase at = resumeAt;
        haltMessage = null;
        phase = Phase.IDLE;
        refreshSite();
        switch (at) {
            case LATCH -> afterLowered(server);
            case RELEASE -> finishRelease(server);
            default -> {
                move = null;
                startNext(server);
            }
        }
        setChanged();
        sync();
    }

    // ---------------------------------------------------------------
    // The move
    // ---------------------------------------------------------------

    private void begin(ServerLevel server, Phase next, Point to, int ticks) {
        phase = next;
        legTo = to;
        elapsed = 0;
        duration = Math.max(1, ticks);
        haltMessage = null;
        setChanged();
        sync();
        schedule(server);
    }

    private int ticks(Point a, Point b, boolean loaded) {
        return CraneMotion.legTicks(a, b, site.runwayAlongX(), loaded);
    }

    private void startNext(ServerLevel server) {
        while (!queue.isEmpty()) {
            Move next = queue.pollFirst();
            refreshSite();
            Component why = refuse(next);
            if (why != null) {
                notifyNearby(server, BwrText.tr("status.bwr.refuelling_bridge.move_dropped", "Move dropped: %s", why));
                continue;
            }
            move = next;
            if (next.from().kind() == Kind.HELD) {
                afterLowered(server); // already latched: plan the route from here
                return;
            }
            if (pos.y() < site.homeY() - 0.01) {
                raisingForMove = true;
                Point up = pos.withY(site.homeY());
                begin(server, Phase.RAISE_EMPTY, up, ticks(pos, up, false));
            } else {
                beginTravel(server);
            }
            return;
        }
        move = null;
        if (plugEntity == null) {
            plan = null;
        }
        gates.forgetPlanIfEmpty();
        phase = Phase.IDLE;
        legTo = null;
        elapsed = 0;
        setChanged();
        sync();
    }

    private void beginTravel(ServerLevel server) {
        Point col = columnOf(move.from());
        Point target = new Point(col.x(), site.homeY(), col.z());
        begin(server, Phase.TRAVEL, target, ticks(pos, target, false));
    }

    /** Where the hook tip is when the load sits on this place. */
    private BridgeMotion.Stop stopOf(Loc loc) {
        if (TurbineHallLifts.isTurbineLoc(loc)) {
            return TurbineHallLifts.stop(this, loc);
        }
        if (loc.kind() == Kind.HELD || reactor() == null) {
            return new BridgeMotion.Stop(pos.x(), pos.z(), pos.y(), Double.NaN);
        }
        ReactorControllerBlockEntity r = reactor();
        VesselProfile vp = carriedProfile() != null ? carriedProfile() : profileOf(r);
        double baseY = r.vesselEnvelope().min().getY();
        switch (loc.kind()) {
            case HEAD_SEAT -> {
                Point c = columnOf(loc);
                return new BridgeMotion.Stop(c.x(), c.z(), baseY + vp.flangeFace + vp.headLift(), Double.NaN);
            }
            case HEAD_STAND -> {
                return new BridgeMotion.Stop(loc.at().getX() + 0.5, loc.at().getZ() + 0.5,
                        loc.at().getY() + HeadStandBlockEntity.REST + vp.headLift(), Double.NaN);
            }
            case HEAD_LAID -> {
                // lying clear, as old worlds have it: the hook meets it at its rest height
                Point c = columnOf(loc);
                return new BridgeMotion.Stop(c.x(), c.z(), site.homeY(), Double.NaN);
            }
            case INTERNAL -> {
                Point c = columnOf(loc);
                double lift = loc.internal() == VesselInternal.DRYER ? vp.dryerLift() : vp.separatorLift();
                return new BridgeMotion.Stop(c.x(), c.z(), baseY + lift + RIG_M, Double.NaN);
            }
            case PAD -> {
                return new BridgeMotion.Stop(loc.at().getX() + 0.5, loc.at().getZ() + 0.5,
                        loc.at().getY() + EquipmentPadBlockEntity.TOP + EquipmentPads.liftAboveBottom(loc.internal(), vp) + RIG_M, Double.NaN);
            }
            case PLUG -> {
                Point c = columnOf(loc);
                return new BridgeMotion.Stop(c.x(), c.z(), loc.at().getY() + plan.hookAboveBase(), Double.NaN);
            }
            case GATE -> {
                return gates.stop(loc);
            }
            default -> {
                return new BridgeMotion.Stop(pos.x(), pos.z(), pos.y(), Double.NaN);
            }
        }
    }

    /** The hook is down on the load (or already holds it): check the move once more, then latch. */
    private void afterLowered(ServerLevel server) {
        site = BridgeSite.surveyCrane(level, worldPosition);
        Component why = refuse(new Move(move.from(), move.to()));
        // the source is still where it was (not yet latched), so a refusal is a real change
        if (why != null) {
            fault(server, Phase.LATCH, why);
            return;
        }
        // up to the hook's rest height, across at that height, down onto the destination
        Point dest = columnOf(move.to());
        route.clear();
        route.add(new Point(pos.x(), site.homeY(), pos.z()));
        route.add(new Point(dest.x(), site.homeY(), dest.z()));
        routeIndex = 1;
        if (move.from().kind() == Kind.HELD) {
            begin(server, Phase.RAISE_LOADED, route.get(0), ticks(pos, route.get(0), true));
            return;
        }
        begin(server, Phase.LATCH, pos, CraneMotion.LATCH_TICKS);
    }

    /** Stops with a message; a player decides ({@link #resume}, {@link #abort}). */
    private void fault(ServerLevel server, Phase resume, Component why) {
        phase = Phase.HALTED;
        resumeAt = resume;
        haltMessage = why;
        legTo = null;
        elapsed = 0;
        updateDose(server);
        notifyNearby(server, why);
        setChanged();
        sync();
    }

    private void notifyNearby(ServerLevel server, Component message) {
        for (var player : server.players()) {
            if (player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) < 24 * 24) {
                player.displayClientMessage(message, true);
            }
        }
    }

    /** Leg {@code i} of the loaded route: from waypoint i-1 to i. */
    private void beginLeg(ServerLevel server, int i) {
        routeIndex = i;
        if (i >= route.size()) {
            Point dest = route.get(route.size() - 1);
            Point seat = new Point(dest.x(), stopOf(move.to()).seatTopY(), dest.z());
            begin(server, Phase.LOWER_LOADED, seat, ticks(dest, seat, true));
            return;
        }
        Point start = route.get(i - 1);
        Point to = route.get(i);
        pos = start;
        begin(server, Phase.TRANSIT, to, ticks(start, to, true));
    }

    private void complete(ServerLevel server) {
        Point end = legTo;
        pos = end;
        switch (phase) {
            case RAISE_EMPTY -> {
                if (raisingForMove && move != null) {
                    raisingForMove = false;
                    beginTravel(server);
                } else {
                    move = null;
                    startNext(server);
                }
            }
            case TRAVEL -> {
                Point down = new Point(end.x(), stopOf(move.from()).seatTopY(), end.z());
                begin(server, Phase.LOWER_EMPTY, down, ticks(end, down, false));
            }
            case LOWER_EMPTY -> afterLowered(server);
            case LATCH -> latch(server);
            case RAISE_LOADED -> beginLeg(server, 1);
            case TRANSIT -> beginLeg(server, routeIndex + 1);
            case LOWER_LOADED -> begin(server, Phase.RELEASE, pos, CraneMotion.RELEASE_TICKS);
            case RELEASE -> finishRelease(server);
            default -> {
                phase = Phase.IDLE;
            }
        }
    }

    private void latch(ServerLevel server) {
        Loc from = move.from();
        if (TurbineHallLifts.isTurbineLoc(from)) {
            Component why = TurbineHallLifts.take(this, from);
            if (why != null) {
                move = null;
                queue.clear();
                fault(server, Phase.IDLE, why);
                return;
            }
            Point up = new Point(pos.x(), route.get(0).y(), pos.z());
            begin(server, Phase.RAISE_LOADED, up, ticks(pos, up, true));
            return;
        }
        ReactorControllerBlockEntity r = reactor();
        Component reason = BwrText.tr("status.bwr.building_crane.nothing_to_latch", "Stopped: the hook closed on nothing; the load is no longer there.");
        boolean taken = false;
        if (r != null) {
            VesselProfile vp = profileOf(r);
            switch (from.kind()) {
                case HEAD_SEAT -> {
                    Component why = r.headLiftBlocked();
                    if (why == null) {
                        r.headLifted(worldPosition);
                        carriedHead = true;
                        taken = true;
                    } else {
                        reason = BwrText.tr("status.bwr.building_crane.cannot_lift_head", "The head cannot be lifted: %s.", why);
                    }
                }
                case HEAD_STAND -> {
                    HeadStandBlockEntity stand = standAt(from.at());
                    if (stand != null && stand.holdsHead() && stand.reactorPos().equals(r.getBlockPos()) && stand.take()) {
                        r.headMoved(worldPosition);
                        carriedHead = true;
                        taken = true;
                    }
                }
                case HEAD_LAID -> {
                    if (r.vesselState() == VesselState.REFUELING && r.headLocation() == null) {
                        r.headMoved(worldPosition);
                        carriedHead = true;
                        taken = true;
                    }
                }
                case INTERNAL -> {
                    if (r.internalLiftBlocked(from.internal()) == null) {
                        r.internalOut(from.internal(), worldPosition);
                        carriedInternal = from.internal();
                        taken = true;
                    }
                }
                case PAD -> {
                    EquipmentPadBlockEntity pad = padAt(from.at());
                    if (pad != null && pad.stored() == from.internal() && pad.take() == from.internal()) {
                        r.internalOut(from.internal(), worldPosition);
                        carriedInternal = from.internal();
                        taken = true;
                    }
                }
                case PLUG -> {
                    Component why = takePlug(server, from.at());
                    if (why == null) {
                        taken = true;
                    } else {
                        reason = why;
                    }
                }
                case GATE -> {
                    Component why = gates.take(server, this, from.at(), pos);
                    if (why == null) {
                        taken = true;
                    } else {
                        reason = why;
                    }
                }
                default -> {
                }
            }
            if (taken) {
                carriedVessel = new int[]{vp.width, vp.depth, vp.height, vp.internalPumps ? 1 : 0};
            }
        }
        if (!taken) {
            move = null;
            queue.clear();
            fault(server, Phase.IDLE, reason);
            return;
        }
        Point up = new Point(pos.x(), route.get(0).y(), pos.z());
        begin(server, Phase.RAISE_LOADED, up, ticks(pos, up, true));
    }

    private void finishRelease(ServerLevel server) {
        Loc to = move.to();
        if (!carriedTurbine.isEmpty()) {
            Component why = TurbineHallLifts.put(this, to);
            if (why != null) {
                fault(server, Phase.RELEASE, why);
                return;
            }
            Point up = pos.withY(site.homeY());
            begin(server, Phase.RAISE_EMPTY, up, ticks(pos, up, false));
            return;
        }
        ReactorControllerBlockEntity r = reactor();
        boolean placed = false;
        if (plugEntity != null || gates.holding()) {
            Component why = plugEntity != null ? setDownPlug(server, to) : gates.setDown(server, this, to);
            if (why != null) {
                fault(server, Phase.RELEASE, why);
                return;
            }
            Point up = pos.withY(site.homeY());
            begin(server, Phase.RAISE_EMPTY, up, ticks(pos, up, false));
            return;
        }
        if (carriedHead) {
            VesselProfile vp = carriedProfile() != null ? carriedProfile() : r == null ? null : profileOf(r);
            if (r != null && to.kind() == Kind.HEAD_SEAT && r.headSeatBlocked() == null) {
                r.headSeated();
                placed = true;
            } else if (r != null && vp != null && to.kind() == Kind.HEAD_STAND) {
                HeadStandBlockEntity stand = standAt(to.at());
                if (stand != null && !stand.holdsHead()) {
                    stand.store(r, vp);
                    r.headMoved(to.at());
                    placed = true;
                }
            }
        } else if (carriedInternal != null) {
            VesselInternal which = carriedInternal;
            if (r != null && to.kind() == Kind.INTERNAL && to.internal() == which) {
                if (r.internalInstallBlocked(which) == null) {
                    r.internalIn(which);
                    placed = true;
                }
            } else if (r != null && to.kind() == Kind.PAD && to.internal() == which) {
                EquipmentPadBlockEntity pad = padAt(to.at());
                if (pad != null && pad.stored() == null) {
                    pad.store(which, r, carriedProfile() != null ? carriedProfile() : profileOf(r));
                    r.internalOut(which, to.at());
                    placed = true;
                }
            }
        }
        if (!placed) {
            fault(server, Phase.RELEASE, BwrText.tr("status.bwr.building_crane.cannot_release",
                    "Stopped: the load cannot be set down at %s (occupied, or the reactor is no longer ready). It stays on the hook.", to.describe()));
            return;
        }
        carriedHead = false;
        carriedInternal = null;
        carriedVessel = new int[0];
        updateDose(server);
        Point up = pos.withY(site.homeY());
        begin(server, Phase.RAISE_EMPTY, up, ticks(pos, up, false));
    }

    // ---------------------------------------------------------------
    // Shield plug sections (PLUG OFF / PLUG ON)
    // ---------------------------------------------------------------

    private static boolean isPlugMove(Move m) {
        return m.from().kind() == Kind.PLUG || m.to().kind() == Kind.PLUG;
    }

    private static boolean isGateMove(Move m) {
        return m.from().kind() == Kind.GATE || m.to().kind() == Kind.GATE;
    }

    /**
     * GATE OUT ({@code out}): lifts the canal gate (the one through {@code at}, or the nearest rigged one in reach) out of its canal column onto a
     * Gate Laydown Stand or the floor; GATE IN puts the last one laid down back. See {@link CanalGateLift}. Returns null when queued, or why not.
     */
    @Nullable
    public Component queueGate(boolean out, @Nullable BlockPos at) {
        if (!(level instanceof ServerLevel)) {
            return null;
        }
        refreshSite();
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.building_crane.not_surveyed", "The crane has not surveyed its rails yet.") : site.problem;
        }
        return gates.queue(this, out, at);
    }

    private CanalGateLift.Preview gatePreview;
    private long gatePreviewAt = Long.MIN_VALUE;

    /** The gate GATE OUT would take next, or the one on the hook; null when there is none. Cached for a second. */
    @Nullable
    public CanalGateLift.Preview gatePreview() {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        if (gatePreviewAt != Long.MIN_VALUE && server.getGameTime() - gatePreviewAt < 20 && server.getGameTime() >= gatePreviewAt && !gates.holding()) {
            return gatePreview;
        }
        gatePreviewAt = server.getGameTime();
        gatePreview = site == null || !site.ok() ? null : gates.preview(this);
        return gatePreview;
    }

    /** Every canal gate in the crane's reach (a scan on demand). */
    public List<CanalGateLift.Gate> canalGates() {
        return gates.gates(this);
    }

    private BlockPos plugSource(PlugPlan p) {
        return p.fromHome() ? p.homeMin() : p.layMin();
    }

    /** The plan for lifting {@code s} (standing with its minimum corner at {@code from}) to {@code to}. */
    private static PlugPlan planOf(PlugSections.Section s, BlockPos home, BlockPos lay, boolean fromHome) {
        double[] pick = s.pick();
        double px = pick[0] - s.min().getX(), pz = pick[1] - s.min().getZ();
        int[] anchors = new int[3 * s.anchors().size()];
        double spread = 0;
        for (int i = 0; i < s.anchors().size(); i++) {
            BlockPos a = s.anchors().get(i);
            anchors[3 * i] = a.getX() - s.min().getX();
            anchors[3 * i + 1] = a.getY() - s.min().getY();
            anchors[3 * i + 2] = a.getZ() - s.min().getZ();
            spread = Math.max(spread, Math.hypot(a.getX() + 0.5 - pick[0], a.getZ() + 0.5 - pick[1]));
        }
        double rig = Math.max(RIG_M, RIG_ANGLE_FACTOR * spread);
        return new PlugPlan(home.immutable(), lay.immutable(), s.sizeX(), s.sizeY(), s.sizeZ(), px, pz, rig, anchors, s.massT(), s.blockCount(), fromHome);
    }

    /** Why a surveyed section cannot be lifted by the four-point rig, or null: the number of anchors, and the crane's rating. */
    @Nullable
    private static Component sectionProblem(PlugSections.Section s) {
        if (!s.rigged()) {
            return BwrText.tr("status.bwr.plug.anchors",
                    "The shield plug section at %s needs 4 anchors, has %s. Bolt exactly four Crane Anchors on the tops of its blocks.",
                    s.min().toShortString(), s.anchors().size());
        }
        if (PlugLift.overCapacity(s.massT())) {
            int tier = s.tiers()[2] > 0 ? 3 : s.tiers()[1] > 0 ? 2 : 1;
            double each = PlugMass.tierTonnes(tier);
            return BwrText.tr("status.bwr.plug.over_capacity",
                    "The shield plug section at %s weighs %s t (%s blocks), over the crane's rated %s t. Mark a seam with the Plug Seam Marker to cut it into smaller sections (the crane takes about %s blocks of the heaviest tier in it, %s t each).",
                    s.min().toShortString(), String.format(Locale.ROOT, "%.1f", s.massT()), s.blockCount(),
                    String.format(Locale.ROOT, "%.0f", PlugLift.RATED_CAPACITY_T), PlugLift.maxBlocks(each), String.format(Locale.ROOT, "%.1f", each));
        }
        return null;
    }

    /** Why the runway is too low for this section (it hangs from the hook over its top), or null. */
    @Nullable
    private Component plugRailsProblem(PlugPlan p) {
        double top = Math.max(p.homeMin().getY(), p.layMin().getY()) + p.sy();
        double clear = top + 0.25;
        double hanging = p.hookAboveBase();
        if (site.homeY() - hanging < clear) {
            return BwrText.tr("status.bwr.building_crane.rails_too_low_plug",
                    "The runway is too low to carry the shield plug section clear of the floor: it hangs %s blocks below the hook. Raise the rails to y %s or higher.",
                    String.format(Locale.ROOT, "%.1f", hanging), (int) Math.ceil(clear + hanging - CraneMotion.HOOK_HOME_ABOVE_RAIL_M));
        }
        return null;
    }

    /** The squares of floor the laydown stays off: where a head lies on a head stand, as wide as the vessel (every stand in reach, holding the head or not). */
    List<PlugSections.Keep> keepClear(VesselAppearance.Envelope env) {
        List<PlugSections.Keep> keep = new ArrayList<>();
        int half = (int) Math.ceil(Math.max(env.width(), env.depth()) / 2.0);
        for (BlockPos p : HeadStandBlockEntity.stands(level)) {
            HeadStandBlockEntity stand = standAt(p);
            if (stand != null && site.reaches(p.getX() + 0.5, p.getZ() + 0.5)) {
                keep.add(new PlugSections.Keep(p.getX(), p.getZ(), half));
            }
        }
        return keep;
    }

    /**
     * PLUG OFF ({@code out}): takes the shield plug section the crane is told of (the one hooked by {@code anchor}, or the nearest to the well that has its
     * four anchors) off the plug and sets it down on the nearest clear spot of the refuel floor. PLUG ON: sets the section most recently laid down back
     * exactly where it came from. With a section already on the hook (after an abort) the lift continues to the laydown (out) or home (in).
     * Returns null when queued, or the reason it was not.
     */
    @Nullable
    public Component queuePlug(boolean out, @Nullable BlockPos anchor) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        refreshSite();
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.building_crane.not_surveyed", "The crane has not surveyed its rails yet.") : site.problem;
        }
        ReactorControllerBlockEntity r = reactor();
        VesselAppearance.Envelope env = r == null ? null : r.vesselEnvelope();
        if (r == null || env == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        if (plugEntity != null && plan != null && move == null) {
            return queue(new Move(Loc.HELD, Loc.plug(out ? plan.layMin() : plan.homeMin())));
        }
        if (holding()) {
            return BwrText.tr("status.bwr.building_crane.holding", "The hook still holds a load: set it down first.");
        }
        if (plan != null || move != null || !queue.isEmpty() || busy()) {
            return BwrText.tr("status.bwr.building_crane.plug_busy_now", "The crane is busy: wait for it to finish, or ABORT.");
        }
        if (out) {
            double wx = env.min().getX() + env.width() / 2.0, wz = env.min().getZ() + env.depth() / 2.0;
            PlugSections.Section chosen = null;
            if (anchor != null) {
                BlockState anchorState = dev.bwr.mod.world.LoadedWorld.state(level, anchor);
                if (anchorState == null) {
                    return PlugSections.unloadedProblem();
                }
                if (!(anchorState.getBlock() instanceof CraneAnchorBlock)) {
                    return BwrText.tr("status.bwr.plug.not_an_anchor", "There is no crane anchor at %s.", anchor.toShortString());
                }
                var sv = PlugSections.survey(level, anchor.below());
                if (sv.section() == null) {
                    return sv.problem();
                }
                chosen = sv.section();
            } else {
                var scan = PlugSections.scan(level, site, env.min().getY(), site.railY - 1, wx, wz);
                for (var s : scan.sections()) {
                    if (s.rigged()) {
                        chosen = s;
                        break;
                    }
                }
                if (chosen == null && !scan.sections().isEmpty()) {
                    chosen = scan.sections().get(0);
                }
                if (chosen == null) {
                    return scan.problem() != null ? scan.problem() : BwrText.tr("status.bwr.plug.no_anchors",
                            "The crane sees no shield plug section to lift: bolt four Crane Anchors on the top of a plug piece, between the rails.");
                }
            }
            if (!site.reaches(chosen.pick()[0], chosen.pick()[1])) {
                return BwrText.tr("status.bwr.building_crane.out_of_reach", "That position is outside the reach of the crane.");
            }
            Component why = sectionProblem(chosen);
            if (why != null) {
                return why;
            }
            Component[] reason = new Component[1];
            BlockPos delta = PlugSections.findLaydown(level, site, chosen, env, keepClear(env), reason);
            if (delta == null) {
                return reason[0];
            }
            PlugPlan p = planOf(chosen, chosen.min(), chosen.min().offset(delta), true);
            plan = p;
            Component q = plugRailsProblem(p);
            if (q == null) {
                q = queue(new Move(Loc.plug(p.homeMin()), Loc.plug(p.layMin())));
            }
            if (q != null) {
                plan = null;
            }
            return q;
        }
        // PLUG ON: the newest section on the laydown goes home
        if (laid.isEmpty()) {
            return BwrText.tr("status.bwr.plug.nothing_laid", "No shield plug section is set down: PLUG ON puts back what PLUG OFF took off.");
        }
        Laid rec = laid.get(laid.size() - 1);
        var sv = PlugSections.survey(level, rec.seed().below());
        if (sv.section() == null && sv.unloaded()) {
            // part of the section (or all of it) lies in a chunk that is not loaded: nothing is known, so the record stays and the player can retry
            return sv.problem();
        }
        if (sv.section() == null || !sv.section().min().equals(rec.lay())) {
            laid.remove(laid.size() - 1);
            setChanged();
            sync();
            return BwrText.tr("status.bwr.plug.laid_gone",
                    "The section that was set down at %s is no longer there as it was (it was broken or changed); its record is cleared. Press PLUG ON again for the one before it.",
                    rec.lay().toShortString());
        }
        Component why = sectionProblem(sv.section());
        if (why != null) {
            return why;
        }
        PlugPlan p = planOf(sv.section(), rec.home(), rec.lay(), false);
        plan = p;
        Component q = plugRailsProblem(p);
        if (q == null) {
            q = queue(new Move(Loc.plug(p.layMin()), Loc.plug(p.homeMin())));
        }
        if (q != null) {
            plan = null;
        }
        return q;
    }

    /** What PLUG OFF would take next (or what hangs on the hook): its size, anchors and mass against the rating, shown on the pendant and to computers before any lift. */
    public record PlugPreview(int blocks, int anchors, double massT, boolean rigged, boolean over, boolean carried, BlockPos min) {
        public String text() {
            return String.format(Locale.ROOT, "%d blocks, %.1f t of %.0f t, %d anchors%s", blocks, massT, PlugLift.RATED_CAPACITY_T, anchors,
                    carried ? ", on the hook" : over ? ", over the rating" : !rigged ? ", needs 4" : "");
        }
    }

    private PlugPreview preview;
    private long previewAt = Long.MIN_VALUE;

    /** The section PLUG OFF would take next, or the one on the hook; null when there is none. Cached for a second (a screen asks twice a second). */
    @Nullable
    public PlugPreview plugPreview() {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        if (plugEntity != null && plan != null) {
            return new PlugPreview(plan.blocks(), plan.anchors().length / 3, plan.massT(), true, PlugLift.overCapacity(plan.massT()), true, plan.homeMin());
        }
        if (previewAt != Long.MIN_VALUE && server.getGameTime() - previewAt < 20 && server.getGameTime() >= previewAt) {
            return preview;
        }
        previewAt = server.getGameTime();
        preview = null;
        if (site == null || !site.ok()) {
            return null;
        }
        var r = reactor();
        var env = r == null ? null : r.vesselEnvelope();
        if (env == null) {
            return null;
        }
        var list = plugSections();
        PlugSections.Section pick = null;
        for (var s : list) {
            if (s.rigged()) {
                pick = s;
                break;
            }
        }
        if (pick == null && !list.isEmpty()) {
            pick = list.get(0);
        }
        if (pick != null) {
            preview = new PlugPreview(pick.blockCount(), pick.anchors().size(), pick.massT(), pick.rigged(), PlugLift.overCapacity(pick.massT()), false, pick.min());
        }
        return preview;
    }

    /** Every anchored shield plug section in the crane's reach, nearest the well first (a scan on demand: a click, a computer's call, a screen's snapshot). */
    public List<PlugSections.Section> plugSections() {
        var r = site == null ? null : reactor();
        var env = r == null ? null : r.vesselEnvelope();
        if (level == null || site == null || !site.ok() || env == null) {
            return List.of();
        }
        double wx = env.min().getX() + env.width() / 2.0, wz = env.min().getZ() + env.depth() / 2.0;
        return PlugSections.scan(level, site, env.min().getY(), site.railY - 1, wx, wz).sections();
    }

    /** Surveys the section standing at {@code min} for the lift in the plan and checks it is the section the plan was made for. */
    @Nullable
    private Component surveyPlan(BlockPos min, @Nullable PlugSections.Section[] out) {
        var sv = PlugSections.survey(level, plan.anchorAt(min, 0).below());
        if (sv.section() == null) {
            return sv.problem();
        }
        var s = sv.section();
        if (!s.min().equals(min) || s.blockCount() != plan.blocks() || s.anchors().size() != plan.anchors().length / 3) {
            return BwrText.tr("status.bwr.plug.changed", "The shield plug section at %s is not as it was when the lift was queued.", min.toShortString());
        }
        Component why = sectionProblem(s);
        if (why != null) {
            return why;
        }
        if (out != null) {
            out[0] = s;
        }
        return null;
    }

    @Nullable
    private Component refusePlug(Move m, ReactorControllerBlockEntity r) {
        Loc from = m.from(), to = m.to();
        boolean held = from.kind() == Kind.HELD;
        if (plan == null || to.kind() != Kind.PLUG || (!held && from.kind() != Kind.PLUG)) {
            return BwrText.tr("status.bwr.building_crane.bad_plug_move", "A shield plug section goes between the plug and the laydown on the refuel floor.");
        }
        if (held && plugEntity == null) {
            return BwrText.tr("status.bwr.building_crane.nothing_held", "The hook is not holding anything.");
        }
        BlockPos dst = to.at();
        if (!dst.equals(plan.homeMin()) && !dst.equals(plan.layMin())) {
            return BwrText.tr("status.bwr.building_crane.bad_plug_move", "A shield plug section goes between the plug and the laydown on the refuel floor.");
        }
        VesselAppearance.Envelope env = r.vesselEnvelope();
        PlugSections.Section[] sec = new PlugSections.Section[1];
        List<BlockPos> blocks;
        Component why;
        if (!held) {
            if (!from.at().equals(plugSource(plan)) || dst.equals(from.at())) {
                return BwrText.tr("status.bwr.building_crane.bad_plug_move", "A shield plug section goes between the plug and the laydown on the refuel floor.");
            }
            why = surveyPlan(from.at(), sec);
            if (why != null) {
                return why;
            }
            blocks = sec[0].blocks();
            BlockPos delta = dst.subtract(from.at());
            if (!dst.equals(plan.homeMin())) {
                why = PlugSections.placeProblem(level, sec[0].cells(), sec[0].min().getY(), delta);
            } else {
                why = PlugSections.placeProblem(level, sec[0].cells(), Integer.MIN_VALUE, delta);
            }
            if (why != null) {
                return why;
            }
            if (PlugSections.coversWell(env, blocks, delta) && (!dst.equals(plan.homeMin()) || r.vesselState() == VesselState.REFUELING)) {
                return dst.equals(plan.homeMin())
                        ? BwrText.tr("status.bwr.plug.head_off", "The plug goes over the reactor well, and the head is off: set the head back on the vessel first.")
                        : BwrText.tr("status.bwr.plug.laydown_on_well", "That laydown is over the reactor well.");
            }
        } else {
            if (!(level instanceof ServerLevel server) || !(server.getEntity(plugEntity) instanceof PlugSectionEntity e)) {
                return null; // not loaded: the crane waits, and the set-down checks again
            }
            boolean home = dst.equals(plan.homeMin());
            why = e.placeProblem(level, dst, !home);
            if (why != null) {
                return why;
            }
            List<BlockPos> abs = new ArrayList<>();
            for (var c : e.cells()) {
                if (!(c.state().getBlock() instanceof CraneAnchorBlock)) {
                    abs.add(new BlockPos(c.x(), c.y(), c.z()));
                }
            }
            if (PlugSections.coversWell(env, abs, dst) && (!home || r.vesselState() == VesselState.REFUELING)) {
                return home ? BwrText.tr("status.bwr.plug.head_off", "The plug goes over the reactor well, and the head is off: set the head back on the vessel first.")
                        : BwrText.tr("status.bwr.plug.laydown_on_well", "That laydown is over the reactor well.");
            }
        }
        Point a = columnOf(from.kind() == Kind.HELD ? Loc.plug(plugSource(plan)) : from), b = columnOf(to);
        if (a == null || b == null || !site.reaches(a.x(), a.z()) || !site.reaches(b.x(), b.z())) {
            return BwrText.tr("status.bwr.building_crane.out_of_reach", "That position is outside the reach of the crane.");
        }
        return plugRailsProblem(plan);
    }

    /** The hook-on of a plug lift: the blocks leave the world and the section becomes the entity on the hook. Returns null, or why not. */
    @Nullable
    private Component takePlug(ServerLevel server, BlockPos min) {
        if (plan == null) {
            return BwrText.tr("status.bwr.plug.changed", "The shield plug section at %s is not as it was when the lift was queued.", min.toShortString());
        }
        PlugSections.Section[] sec = new PlugSections.Section[1];
        Component why = surveyPlan(min, sec);
        if (why != null) {
            return why;
        }
        net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(pos.x(), pos.y() - plan.hookAboveBase(), pos.z());
        PlugSectionEntity e = PlugSectionEntity.take(server, worldPosition, sec[0], at);
        if (e == null) {
            return BwrText.tr("status.bwr.plug.no_entity", "The shield plug section could not be hooked on: its place in the world would not take the load.");
        }
        plugEntity = e.getUUID();
        setChanged();
        return null;
    }

    /** The set-down of a plug lift: puts every block back exactly, removes the entity, and keeps the laydown book. Returns null, or why not. */
    @Nullable
    private Component setDownPlug(ServerLevel server, Loc to) {
        if (plan == null) {
            return BwrText.tr("status.bwr.building_crane.cannot_release", "Stopped: the load cannot be set down at %s (occupied, or the reactor is no longer ready). It stays on the hook.", to.describe());
        }
        if (!(server.getEntity(plugEntity) instanceof PlugSectionEntity e)) {
            return BwrText.tr("status.bwr.plug.entity_not_loaded", "Stopped: the section on the hook is in a chunk that is not loaded. It stays on the hook.");
        }
        BlockPos dst = to.at();
        boolean home = dst.equals(plan.homeMin());
        ReactorControllerBlockEntity r = reactor();
        VesselAppearance.Envelope env = r == null ? null : r.vesselEnvelope();
        if (home && env != null && r.vesselState() == VesselState.REFUELING) {
            List<BlockPos> abs = new ArrayList<>();
            for (var c : e.cells()) {
                if (!(c.state().getBlock() instanceof CraneAnchorBlock)) {
                    abs.add(new BlockPos(c.x(), c.y(), c.z()));
                }
            }
            if (PlugSections.coversWell(env, abs, dst)) {
                return BwrText.tr("status.bwr.plug.head_off", "The plug goes over the reactor well, and the head is off: set the head back on the vessel first.");
            }
        }
        Component why = e.settle(server, dst, !home);
        if (why != null) {
            return BwrText.tr("status.bwr.plug.cannot_set_down", "Stopped: %s The section stays on the hook.", why);
        }
        e.discard();
        if (!home) {
            // set down on the laydown: remember where it belongs
            laid.add(new Laid(dst.immutable(), plan.homeMin(), plan.anchorAt(dst, 0)));
        } else {
            laid.removeIf(l -> l.lay().equals(plan.layMin()));
        }
        plugEntity = null;
        plan = null;
        setChanged();
        return null;
    }

    /** Puts the hanging section's entity under the hook. */
    private void followPlug(ServerLevel server) {
        if (plugEntity == null || plan == null || pos == null) {
            return;
        }
        if (server.getEntity(plugEntity) instanceof PlugSectionEntity e) {
            Point t = tip();
            e.follow(t.x(), t.y() - plan.hookAboveBase(), t.z());
        }
    }

    /** True while the console's rails still make a runway (the section's entity checks once a second). */
    public boolean runwayStillStands() {
        return level != null && BridgeSite.surveyCrane(level, worldPosition).ok();
    }

    private void clearPlugState() {
        plugEntity = null;
        plan = null;
        move = null;
        queue.clear();
        route.clear();
        routeIndex = 0;
        raisingForMove = false;
        haltMessage = null;
        phase = Phase.IDLE;
        elapsed = 0;
        legTo = null;
        setChanged();
        sync();
    }

    /** The section's entity has put its blocks back (or dropped them) by itself and gone: the hook is empty. */
    public void plugReleased(ServerLevel server, UUID id) {
        if (gates.owns(id)) {
            gates.clear();
            clearPlugState();
            notifyNearby(server, BwrText.tr("status.bwr.canal_gate_lift.released", "The canal gate is no longer on the hook: its blocks were put back or dropped."));
            return;
        }
        if (!id.equals(plugEntity)) {
            return;
        }
        clearPlugState();
        notifyNearby(server, BwrText.tr("status.bwr.plug.released",
                "The shield plug section is no longer on the hook: it was put back where it was taken from, or its blocks dropped."));
    }

    /** The runway no longer stands under a hanging section: the entity has set it back or dropped it. */
    public void plugLost(ServerLevel server, UUID id) {
        if (gates.owns(id)) {
            gates.clear();
            clearPlugState();
            notifyNearby(server, BwrText.tr("status.bwr.canal_gate_lift.runway_lost", "The runway is broken: the canal gate's blocks were put back or dropped."));
            return;
        }
        if (!id.equals(plugEntity)) {
            return;
        }
        clearPlugState();
        notifyNearby(server, BwrText.tr("status.bwr.plug.runway_lost",
                "The runway is broken: the shield plug section was put back where it was taken from, or its blocks dropped."));
    }

    // ---------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------

    /** The scheduled block tick. Runs a tick at a time while a move is in progress, once to take the rest position, and never while idle. */
    public void scheduledTick(ServerLevel server) {
        ticksRun++;
        if (!busy()) {
            if (pos == null) {
                refreshSite();
            }
            return;
        }
        if (site == null) {
            refreshSite();
        }
        boolean wait = devHold || !worldReady(server);
        if (wait != paused) {
            paused = wait;
            sync();
        }
        if (wait) {
            schedule(server);
            return;
        }
        if (plugEntity != null && !(server.getEntity(plugEntity) instanceof PlugSectionEntity)) {
            // worldReady waits while the entity may merely be unloaded; so it is gone, and released its blocks as it went
            pos = tip();
            plugReleased(server, plugEntity);
            return;
        }
        if (gates.entityGone(server, tip())) {
            pos = tip();
            plugReleased(server, gates.entityId());
            return;
        }
        if (reactor() == null && !turbineWork()) {
            // the reactor under the crane is gone (broken, unformed): stop where it is; a load stays on the hook
            pos = tip();
            move = null;
            queue.clear();
            fault(server, Phase.IDLE, BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails."));
            return;
        }
        elapsed++;
        if (elapsed >= duration) {
            complete(server);
        }
        if (carriedInternal != null && busy()) {
            updateDose(server);
        }
        followPlug(server);
        gates.follow(server, tip());
        if (busy()) {
            setChanged();
            schedule(server);
        }
    }

    private boolean worldReady(ServerLevel server) {
        if (turbineWork()) {
            return TurbineHallLifts.ready(this, server, move);
        }
        if (site != null && site.reactorPos != null && !server.isLoaded(site.reactorPos)) {
            return false;
        }
        if (gates.holding() && !gates.worldReady(server, tip(), busy() && legTo != null ? BridgeMotion.lerp(pos, legTo, elapsed + 1, duration) : null)) {
            return false;
        }
        if (plugEntity != null && plan != null) {
            // the section's entity must be loaded, and the hook may only carry it into loaded chunks
            Point here = tip();
            BlockPos at = here == null ? worldPosition : BlockPos.containing(here.x(), here.y() - plan.hookAboveBase(), here.z());
            if (!(server.getEntity(plugEntity) instanceof PlugSectionEntity)) {
                // wait while its chunk or its entities are not loaded; with both loaded it is gone (handled by the tick)
                return server.isLoaded(at) && server.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(at));
            }
            if (busy() && legTo != null) {
                Point next = BridgeMotion.lerp(pos, legTo, elapsed + 1, duration);
                if (!server.isLoaded(BlockPos.containing(next.x(), next.y() - plan.hookAboveBase(), next.z()))
                        || !server.isLoaded(BlockPos.containing(next.x(), next.y(), next.z()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The dryer or the separator assembly in the air is a gamma point source at its middle; the head and an empty hook are none. */
    private void updateDose(ServerLevel server) {
        if (carriedInternal == null || pos == null) {
            RadiationSources.clear(server, this);
            return;
        }
        Point at = busy() ? tip() : pos;
        VesselProfile vp = carriedProfile();
        double middle = at.y() - RIG_M - (vp == null ? 2.0 : EquipmentPads.liftAboveBottom(carriedInternal, vp) / 2.0);
        // Over the vessel: the water the player sees (the drawn vessel and well). Elsewhere: the water standing in the pit of a pad.
        ReactorControllerBlockEntity r = reactor();
        var e = r == null ? null : r.vesselEnvelope();
        boolean overVessel = e != null && at.x() >= e.min().getX() && at.x() <= e.max().getX() + 1 && at.z() >= e.min().getZ() && at.z() <= e.max().getZ() + 1;
        double surface = overVessel ? r.drawnWaterSurfaceY() : Double.NaN;
        if (!Double.isFinite(surface)) {
            surface = waterSurface(server, at.x(), at.z(), at.y() + 1, middle - 1);
        }
        double cover = BridgeMotion.cover(surface, middle);
        RadiationSources.emit(server, this, new Vec3(at.x(), middle, at.z()), carriedInternal.gammaWatts, BridgeMotion.transmission(cover), true);
    }

    /** The top of the highest water block in a column between two heights (the pit an equipment pad stands in), or NaN. */
    private static double waterSurface(ServerLevel server, double x, double z, double fromY, double downToY) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = (int) Math.floor(fromY); y >= Math.floor(downToY) && y > server.getMinBuildHeight(); y--) {
            p.set(Math.floor(x), y, Math.floor(z));
            if (!server.isLoaded(p)) {
                return Double.NaN;
            }
            if (server.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                return y + 1.0;
            }
        }
        return Double.NaN;
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    /** A console broken under a hanging load: a dryer or separator assembly goes back in its vessel, a hanging head is laid down on the floor. */
    public void dropCarried(ServerLevel server) {
        if (!carriedTurbine.isEmpty()) {
            // a hood or a blade section goes back where it was taken from, or drops as its item: never lost
            TurbineHallLifts.release(this, server, move);
        }
        gates.dropCarried(server);
        if (plugEntity != null) {
            // a section on the hook goes back where it was taken from, or its blocks drop as items: never lost
            if (server.getEntity(plugEntity) instanceof PlugSectionEntity e) {
                e.releaseSafely(server, true);
                e.discard();
            }
            plugEntity = null;
            plan = null;
        }
        if (holding()) {
            ReactorControllerBlockEntity r = site == null ? null : site.reactor(server);
            if (r == null) {
                BridgeSite s = BridgeSite.surveyCrane(server, worldPosition);
                r = s.ok() ? s.reactor(server) : null;
            }
            if (r != null) {
                if (carriedInternal != null) {
                    r.internalIn(carriedInternal);
                }
                if (carriedHead) {
                    r.headMoved(null);
                }
            }
            carriedInternal = null;
            carriedHead = false;
            carriedVessel = new int[0];
        }
        RadiationSources.clear(server, this);
    }

    // ---------------------------------------------------------------
    // Description, for the screen, the computer and the tests
    // ---------------------------------------------------------------

    public String carriedSummary() {
        if (!carriedTurbine.isEmpty()) {
            return dev.bwr.mod.power.TurbineLoadItem.isHood(carriedTurbine)
                    ? String.format(Locale.ROOT, "an LP turbine hood (%.0f t)", dev.bwr.mod.power.TurbineLoadItem.massT(carriedTurbine))
                    : String.format(Locale.ROOT, "%s LP blade section (%.0f t, %.0f%% worn)", dev.bwr.mod.power.TurbineLoadItem.isGoodSection(carriedTurbine) ? "a" : "a damaged",
                    dev.bwr.mod.power.TurbineLoadItem.massT(carriedTurbine), 100 * dev.bwr.mod.power.TurbineLoadItem.condition(carriedTurbine));
        }
        if (gates.holding()) {
            return gates.summary();
        }
        if (plugEntity != null && plan != null) {
            return String.format(Locale.ROOT, "a shield plug section (%d blocks, %.1f t)", plan.blocks(), plan.massT());
        }
        if (carriedHead) {
            return "the vessel head";
        }
        return carriedInternal != null ? "the " + carriedInternal.title : "";
    }

    // ---------------------------------------------------------------
    // Persistence and sync
    // ---------------------------------------------------------------

    private static CompoundTag pointTag(Point p) {
        CompoundTag t = new CompoundTag();
        t.putDouble("X", p.x());
        t.putDouble("Y", p.y());
        t.putDouble("Z", p.z());
        return t;
    }

    private static Point pointOf(CompoundTag t) {
        return new Point(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (pos != null) {
            tag.put("Pos", pointTag(pos));
        }
        if (phase != Phase.IDLE) {
            tag.putString("Phase", phase.name());
            tag.putInt("Elapsed", elapsed);
            tag.putInt("Duration", duration);
            if (legTo != null) {
                tag.put("LegTo", pointTag(legTo));
            }
        }
        if (phase == Phase.HALTED) {
            tag.putString("ResumeAt", resumeAt.name());
            if (haltMessage != null) {
                tag.putString("Halt", Component.Serializer.toJson(haltMessage, registries));
            }
        }
        if (move != null) {
            tag.put("Move", move.save());
        }
        if (!queue.isEmpty()) {
            ListTag list = new ListTag();
            for (Move m : queue) {
                list.add(m.save());
            }
            tag.put("Queue", list);
        }
        if (!route.isEmpty()) {
            ListTag list = new ListTag();
            for (Point p : route) {
                list.add(pointTag(p));
            }
            tag.put("Route", list);
            tag.putInt("RouteIndex", routeIndex);
        }
        if (raisingForMove) {
            tag.putBoolean("RaisingForMove", true);
        }
        if (carriedHead) {
            tag.putBoolean("CarriedHead", true);
        }
        if (carriedInternal != null) {
            tag.putString("CarriedInternal", carriedInternal.id);
        }
        if (!carriedTurbine.isEmpty()) {
            tag.put("CarriedTurbine", carriedTurbine.save(registries));
        }
        if (holding()) {
            tag.putIntArray("CarriedVessel", carriedVessel);
        }
        if (plan != null) {
            tag.put("Plug", plan.save());
        }
        if (plugEntity != null) {
            tag.put("PlugEntity", NbtUtils.createUUID(plugEntity));
        }
        if (!laid.isEmpty()) {
            ListTag list = new ListTag();
            for (Laid l : laid) {
                CompoundTag t = new CompoundTag();
                t.putLong("Lay", l.lay().asLong());
                t.putLong("Home", l.home().asLong());
                t.putLong("Seed", l.seed().asLong());
                list.add(t);
            }
            tag.put("Laid", list);
        }
        gates.save(tag);
        if (geom != null) {
            CompoundTag g = new CompoundTag();
            g.putBoolean("AlongX", geom.alongX());
            g.putInt("RailY", geom.railY());
            g.putInt("ALo", geom.aLo());
            g.putInt("AHi", geom.aHi());
            g.putInt("CLow", geom.cLow());
            g.putInt("CHigh", geom.cHigh());
            tag.put("Geom", g);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pos = tag.contains("Pos", Tag.TAG_COMPOUND) ? pointOf(tag.getCompound("Pos")) : null;
        phase = tag.contains("Phase") ? Phase.byName(tag.getString("Phase")) : Phase.IDLE;
        elapsed = Math.max(0, tag.getInt("Elapsed"));
        duration = Math.max(1, tag.contains("Duration") ? tag.getInt("Duration") : 1);
        legTo = tag.contains("LegTo", Tag.TAG_COMPOUND) ? pointOf(tag.getCompound("LegTo")) : null;
        if (phase != Phase.IDLE && phase != Phase.HALTED && (pos == null || legTo == null)) {
            phase = Phase.IDLE; // a damaged save: stand still rather than move from nowhere
        }
        resumeAt = tag.contains("ResumeAt") ? Phase.byName(tag.getString("ResumeAt")) : Phase.IDLE;
        haltMessage = tag.contains("Halt") ? Component.Serializer.fromJson(tag.getString("Halt"), registries) : null;
        move = tag.contains("Move", Tag.TAG_COMPOUND) ? Move.load(tag.getCompound("Move")) : null;
        queue.clear();
        if (tag.contains("Queue", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Queue", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < MAX_QUEUE; i++) {
                queue.addLast(Move.load(list.getCompound(i)));
            }
        }
        route.clear();
        if (tag.contains("Route", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Route", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                route.add(pointOf(list.getCompound(i)));
            }
        }
        routeIndex = tag.getInt("RouteIndex");
        raisingForMove = tag.getBoolean("RaisingForMove");
        carriedHead = tag.getBoolean("CarriedHead");
        carriedInternal = tag.contains("CarriedInternal") ? VesselInternal.byId(tag.getString("CarriedInternal")) : null;
        carriedTurbine = tag.contains("CarriedTurbine", Tag.TAG_COMPOUND)
                ? net.minecraft.world.item.ItemStack.parseOptional(registries, tag.getCompound("CarriedTurbine")) : net.minecraft.world.item.ItemStack.EMPTY;
        carriedVessel = holding() ? tag.getIntArray("CarriedVessel") : new int[0];
        plan = tag.contains("Plug", Tag.TAG_COMPOUND) ? PlugPlan.load(tag.getCompound("Plug")) : null;
        plugEntity = plan != null && tag.contains("PlugEntity", Tag.TAG_INT_ARRAY) ? NbtUtils.loadUUID(tag.get("PlugEntity")) : null;
        laid.clear();
        if (tag.contains("Laid", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Laid", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                laid.add(new Laid(BlockPos.of(t.getLong("Lay")), BlockPos.of(t.getLong("Home")), BlockPos.of(t.getLong("Seed"))));
            }
        }
        gates.load(tag);
        paused = tag.getBoolean("Paused");
        if (tag.contains("Geom", Tag.TAG_COMPOUND)) {
            CompoundTag g = tag.getCompound("Geom");
            geom = new Geom(g.getBoolean("AlongX"), g.getInt("RailY"), g.getInt("ALo"), g.getInt("AHi"), g.getInt("CLow"), g.getInt("CHigh"));
        } else {
            geom = null;
        }
        if (level != null && level.isClientSide()) {
            clientSyncTick = level.getGameTime();
        }
        if (move == null && (phase != Phase.IDLE && phase != Phase.HALTED)) {
            phase = Phase.IDLE;
        }
        site = null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        if (paused && busy()) {
            tag.putBoolean("Paused", true);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }
}
