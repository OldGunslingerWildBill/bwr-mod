package dev.bwr.mod.refuelling;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

    public enum Kind { HEAD_SEAT, HEAD_STAND, HEAD_LAID, INTERNAL, PAD, HELD }

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

        public VesselInternal internal() {
            return VesselInternal.values()[Math.clamp(which, 0, VesselInternal.values().length - 1)];
        }

        public boolean isHead() {
            return kind == Kind.HEAD_SEAT || kind == Kind.HEAD_STAND || kind == Kind.HEAD_LAID;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("K", kind.name());
            if (kind == Kind.HEAD_STAND || kind == Kind.PAD) {
                t.putLong("R", at.asLong());
            }
            if (kind == Kind.INTERNAL || kind == Kind.PAD) {
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

    /** True while the hook holds the head, the dryer or the separator assembly. */
    public boolean holding() {
        return carriedHead || carriedInternal != null;
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

    private ReactorControllerBlockEntity reactor() {
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
        ReactorControllerBlockEntity r = reactor();
        if (r == null || r.vesselEnvelope() == null) {
            return BwrText.tr("status.bwr.building_crane.no_reactor", "No formed reactor lies between the crane's rails.");
        }
        Loc from = m.from(), to = m.to();
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
        ReactorControllerBlockEntity r = reactor();
        boolean placed = false;
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
        if (reactor() == null) {
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
        if (busy()) {
            setChanged();
            schedule(server);
        }
    }

    private boolean worldReady(ServerLevel server) {
        return site == null || site.reactorPos == null || server.isLoaded(site.reactorPos);
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
        RadiationSources.emit(server, this, new Vec3(at.x(), middle, at.z()), carriedInternal.gammaWatts, BridgeMotion.transmission(cover));
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
        if (holding()) {
            tag.putIntArray("CarriedVessel", carriedVessel);
        }
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
        carriedVessel = holding() ? tag.getIntArray("CarriedVessel") : new int[0];
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
