package dev.bwr.mod.refuelling;

import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.BridgeMotion.Point;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselProfile;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
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
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The refuelling bridge, one console block and the machine it stands for: a gantry on two runs of rail, a trolley across
 * it and a telescoping mast with a grapple, moving ONE fuel assembly at a time between a position of a formed reactor's
 * core (head off) and a cell of a spent fuel pool's racks, through the cattle chute. The arithmetic (speeds, legs, water
 * cover, the interlock) is {@link BridgeMotion}; what the rails, reactor, pool and chute are is {@link BridgeSite}.
 *
 * <h2>Nothing moves unless told to</h2>
 * Idle, this block entity has no ticker and nothing scheduled. A player (the console screen) or a program (the
 * peripheral) queues a move; the bridge then ticks itself with a scheduled block tick, one a tick, only until the queue is
 * empty or it halts. A halt (a closed gate, the hoist-limit interlock, a destination that is no longer free) waits for a
 * player: {@link #resume()} or {@link #abort()}. Nothing is resumed or retried by itself.
 *
 * <h2>Fuel is never duplicated or lost</h2>
 * The assembly leaves its seat through the existing paths ({@code ReactorControllerBlockEntity.unloadAssembly}, which
 * stamps the discharge time; {@code SpentFuelPoolBlockEntity.extractFrom}) only when the grapple has latched, is carried
 * in this block entity's {@link #carried} stack (saved with it), and goes in through {@code loadAssembly} or
 * {@code insertAt} only when the grapple has released. Breaking the console drops what it carries.
 *
 * <h2>Chunks and saves</h2>
 * It never loads a chunk: if the chunk of the reactor or the pool it works with is not loaded it waits where it is. The
 * state of a move (phase, ticks done, the leg, the route, the queue, the carried assembly) is saved; ticks elapse only
 * while it ticks, so a world closed mid-move continues from the same place. Old worlds have none of these keys.
 *
 * <h2>The steam dryer and the separator assembly</h2>
 * The same machine lifts the two removable assemblies out of the open, flooded vessel to an {@link EquipmentPadBlockEntity
 * equipment pad} and back (dryer out first, separators in first; the reactor says what is allowed). Such a move goes up out of
 * the water to the mast's rest height, across in the air and down onto the pad: the dose spikes while it is lifted. While the
 * bridge holds one, the reactor records the console as where it is; breaking the console sets it back in its vessel.
 *
 * <h2>Radiation</h2>
 * While an assembly is carried, it is a point source registered with {@link RadiationSources}: its decay heat's gamma,
 * through the water over its handle (zero cover in air). Nothing is registered when nothing is carried.
 */
public class RefuellingBridgeBlockEntity extends BlockEntity {

    public static final int MAX_QUEUE = 16;
    /**
     * The hoist limit of this machine: the bridge will not lift an assembly higher than leaves this much water over its
     * handle unless the player overrides it, m. A limit on the machine, set against the water the physics reports; it is
     * not plant protection and nothing else reads it.
     */
    public static final double MIN_COVER_M = 1.5;
    private static final double EPS = 1.0e-6;

    // ---------------------------------------------------------------
    // Moves
    // ---------------------------------------------------------------

    public enum Kind { CORE, RACK, HELD, INTERNAL, PAD }

    /** A place: a lattice position of the core, a cell of a rack, or the grapple itself. */
    public record Loc(Kind kind, int lattice, BlockPos rack, int cell) {
        public static Loc core(int latticePosition) {
            return new Loc(Kind.CORE, latticePosition, BlockPos.ZERO, 0);
        }

        public static Loc rack(BlockPos rack, int cell) {
            return new Loc(Kind.RACK, 0, rack.immutable(), cell);
        }

        public static final Loc HELD = new Loc(Kind.HELD, 0, BlockPos.ZERO, 0);

        /** The vessel's seat of the dryer or the separator assembly. */
        public static Loc internal(VesselInternal which) {
            return new Loc(Kind.INTERNAL, 0, BlockPos.ZERO, which.ordinal());
        }

        /** An equipment pad, for (or holding) the dryer or the separator assembly. */
        public static Loc pad(BlockPos pad, VesselInternal which) {
            return new Loc(Kind.PAD, 0, pad.immutable(), which.ordinal());
        }

        /** Which assembly an INTERNAL or PAD place is about. */
        public VesselInternal internal() {
            return VesselInternal.values()[Math.clamp(cell, 0, VesselInternal.values().length - 1)];
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("K", kind.name());
            if (kind == Kind.CORE) {
                t.putInt("L", lattice);
            } else if (kind == Kind.RACK || kind == Kind.PAD) {
                t.putLong("R", rack.asLong());
                t.putByte("C", (byte) cell);
            } else if (kind == Kind.INTERNAL) {
                t.putByte("C", (byte) cell);
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
                case CORE -> core(t.getInt("L"));
                case RACK -> rack(BlockPos.of(t.getLong("R")), t.getByte("C"));
                case INTERNAL -> new Loc(Kind.INTERNAL, 0, BlockPos.ZERO, t.getByte("C"));
                case PAD -> new Loc(Kind.PAD, 0, BlockPos.of(t.getLong("R")), t.getByte("C"));
                default -> HELD;
            };
        }

        public String describe() {
            return switch (kind) {
                case CORE -> "core position " + lattice;
                case RACK -> String.format(Locale.ROOT, "rack %d,%d,%d cell %d", rack.getX(), rack.getY(), rack.getZ(), cell + 1);
                case HELD -> "the grapple";
                case INTERNAL -> "the " + internal().title + "'s seat in the vessel";
                case PAD -> String.format(Locale.ROOT, "the equipment pad at %d,%d,%d", rack.getX(), rack.getY(), rack.getZ());
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
    private ItemStack carried = ItemStack.EMPTY;
    /** The dryer or the separator assembly on the hoist (null: none), and the size of the vessel it belongs to (w, d, h, internal pumps). */
    @Nullable
    private VesselInternal carriedInternal;
    private int[] carriedVessel = new int[0];
    private boolean override;
    private Phase resumeAt = Phase.IDLE;
    private Component haltMessage;
    private final List<Point> route = new ArrayList<>();
    private int routeIndex;
    private boolean raisingForMove;
    private Geom geom;

    // transient
    private BridgeSite site;
    private BridgeSite.Chute chute = BridgeSite.Chute.NONE;
    private double carriedWatts;
    private double carriedCover;
    private long wattsAt = Long.MIN_VALUE;
    private int ticksRun;
    /** Standing still although busy: a chunk it needs is not loaded (or a development hold); sent to clients so they stop extrapolating. */
    private boolean paused;
    private boolean devHold;
    /** What the picture of the carried assembly needs, set when a phase begins: its Cherenkov strength (0..15) and the surface it hangs under. */
    private int glow;
    private double surfaceSeen = Double.NaN;
    // client
    private long clientSyncTick;

    public RefuellingBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.REFUELLING_BRIDGE.get(), pos, state);
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

    /** A development hook: sets the resting grapple position (the client check's pose for the bridge over the chute). */
    public void devPlace(Point p) {
        if (!busy()) {
            pos = p;
            setChanged();
            sync();
        }
    }

    /** True while the bridge is working (a scheduled tick each game tick); false idle or halted. */
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

    public ItemStack carried() {
        return carried;
    }

    /** The dryer or the separator assembly the hoist holds, or null. */
    @Nullable
    public VesselInternal carriedInternal() {
        return carriedInternal;
    }

    /** The vessel the carried assembly belongs to (for drawing it), or null. */
    @Nullable
    public VesselProfile carriedProfile() {
        return carriedInternal == null || carriedVessel.length < 4 ? null
                : VesselProfile.of(carriedVessel[0], carriedVessel[1], carriedVessel[2], carriedVessel[3] != 0);
    }

    /** True while the grapple holds a fuel assembly or the hoist the dryer or the separator assembly. */
    public boolean holding() {
        return !carried.isEmpty() || carriedInternal != null;
    }

    public boolean override() {
        return override;
    }

    public void setOverride(boolean on) {
        if (override != on) {
            override = on;
            setChanged();
            sync();
        }
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

    public BridgeSite.Chute chute() {
        return chute;
    }

    public double carriedCoverM() {
        return carriedCover;
    }

    /** 0..1 progress through the current phase. */
    public double progress() {
        return phase == Phase.IDLE || phase == Phase.HALTED ? 0.0 : Math.min(1.0, elapsed / (double) Math.max(1, duration));
    }

    public int phaseTicksLeft() {
        return busy() ? Math.max(0, duration - elapsed) : 0;
    }

    /** The grapple tip now: server from the leg, client from the synced leg and the clock. */
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

    /** The phase the client should show. */
    public Phase shownPhase() {
        return phase;
    }

    // ---------------------------------------------------------------
    // Survey
    // ---------------------------------------------------------------

    /** Reads the rails, the reactor, the pool and the chute again. Cheap enough for a click or a once-a-second snapshot, never per tick. */
    public BridgeSite refreshSite() {
        if (level == null || level.isClientSide()) {
            return site;
        }
        BridgeSite s = BridgeSite.survey(level, worldPosition);
        site = s;
        chute = s.ok() ? s.chute(level) : BridgeSite.Chute.NONE;
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

    // ---------------------------------------------------------------
    // Queueing
    // ---------------------------------------------------------------

    private ReactorControllerBlockEntity reactor() {
        return site == null ? null : site.reactor(level);
    }

    private SpentFuelPoolBlockEntity pool() {
        return site == null ? null : site.pool(level);
    }

    private boolean isRack(SpentFuelPoolBlockEntity p, BlockPos rack) {
        return p.layout().racks().contains(rack);
    }

    /** Why a move cannot be done now, or null if it can. Uses the current survey. */
    @Nullable
    private Component refuse(Move m) {
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.refuelling_bridge.not_surveyed", "The bridge has not surveyed its rails yet.") : site.problem;
        }
        if (m.from().equals(m.to())) {
            return BwrText.tr("status.bwr.refuelling_bridge.same_place", "That move starts and ends in the same place.");
        }
        if (internalMove(m)) {
            // The reactor building crane lifts the head, the dryer and the separators; the bridge moves fuel only.
            // A load already on the hoist (a world saved mid-lift in alpha.53) can still be set down.
            if (m.from().kind() != Kind.HELD) {
                return craneMessage();
            }
            return refuseInternal(m);
        }
        ReactorControllerBlockEntity r = reactor();
        SpentFuelPoolBlockEntity p = pool();
        boolean needsCore = m.from().kind() == Kind.CORE || m.to().kind() == Kind.CORE;
        boolean needsPool = m.from().kind() == Kind.RACK || m.to().kind() == Kind.RACK;
        if (needsCore) {
            if (r == null) {
                return BwrText.tr("status.bwr.refuelling_bridge.no_reactor", "No formed reactor lies between the bridge's rails.");
            }
            Component blocked = r.refuellingBlockedMessage();
            if (blocked != null) {
                return BwrText.tr("status.bwr.refuelling_bridge.refuelling_blocked", "Refuelling is not possible: %s.", blocked);
            }
        }
        if (needsPool && p == null) {
            return BwrText.tr("status.bwr.refuelling_bridge.no_pool", "No formed spent fuel pool lies between the bridge's rails.");
        }
        // source
        boolean sourceIsAssembly = true;
        switch (m.from().kind()) {
            case CORE -> {
                if (!validLattice(r, m.from().lattice()) || !r.core().getCoreLoading().isOccupied(m.from().lattice())) {
                    return BwrText.tr("status.bwr.refuelling_bridge.source_empty", "Nothing is in that core position.");
                }
                sourceIsAssembly = r.core().getCoreLoading().assemblyAt(m.from().lattice()) != null;
            }
            case RACK -> {
                if (!isRack(p, m.from().rack()) || m.from().cell() < 0 || m.from().cell() > 3
                        || p.stackAt(m.from().rack(), m.from().cell()).isEmpty()) {
                    return BwrText.tr("status.bwr.refuelling_bridge.source_cell_empty", "That rack cell holds no assembly.");
                }
            }
            case HELD -> {
                if (carried.isEmpty()) {
                    return BwrText.tr("status.bwr.refuelling_bridge.nothing_held", "The grapple is not holding anything.");
                }
                sourceIsAssembly = carried.is(BwrItems.FUEL_ASSEMBLY.get());
            }
        }
        // destination
        switch (m.to().kind()) {
            case CORE -> {
                if (!validLattice(r, m.to().lattice()) || r.core().getCoreLoading().isOccupied(m.to().lattice())) {
                    return BwrText.tr("status.bwr.refuelling_bridge.destination_taken", "That core position is already occupied.");
                }
            }
            case RACK -> {
                if (!isRack(p, m.to().rack()) || m.to().cell() < 0 || m.to().cell() > 3
                        || !p.stackAt(m.to().rack(), m.to().cell()).isEmpty()) {
                    return BwrText.tr("status.bwr.refuelling_bridge.destination_cell_taken", "That rack cell is already occupied.");
                }
                if (!sourceIsAssembly) {
                    return BwrText.tr("status.bwr.refuelling_bridge.not_an_assembly", "The pool racks take fuel assemblies only.");
                }
            }
            case HELD -> {
                return BwrText.tr("status.bwr.refuelling_bridge.bad_destination", "A move must end in the core or a rack.");
            }
        }
        // reach
        Point from = columnOf(m.from());
        Point to = columnOf(m.to());
        if (from == null || to == null || !site.reaches(from.x(), from.z()) || !site.reaches(to.x(), to.z())) {
            return BwrText.tr("status.bwr.refuelling_bridge.out_of_reach", "That position is outside the reach of the bridge.");
        }
        // the chute
        BridgeSite.Body a = site.bodyAt(level, from.x(), from.z(), chute);
        BridgeSite.Body b = site.bodyAt(level, to.x(), to.z(), chute);
        if (a == BridgeSite.Body.NONE || b == BridgeSite.Body.NONE || a == BridgeSite.Body.CHUTE || b == BridgeSite.Body.CHUTE) {
            return BwrText.tr("status.bwr.refuelling_bridge.out_of_reach", "That position is outside the reach of the bridge.");
        }
        if (a != b) {
            switch (chute.state()) {
                case ABSENT -> {
                    return BwrText.tr("status.bwr.refuelling_bridge.no_chute",
                            "There is no cattle chute between the cavity and the pool: a row of transfer canal blocks must join the reactor shell to the pool wall.");
                }
                case CLOSED -> {
                    return BwrText.tr("status.bwr.refuelling_bridge.gate_closed_refusal",
                            "The gate at %s is closed. Open it to move fuel between the cavity and the pool.",
                            chute.closedGate() == null ? "?" : chute.closedGate().toShortString());
                }
                default -> {
                }
            }
        }
        return null;
    }

    /** A move of the dryer or the separator assembly rather than of fuel. */
    private boolean internalMove(Move m) {
        Kind a = m.from().kind(), b = m.to().kind();
        return a == Kind.INTERNAL || a == Kind.PAD || b == Kind.INTERNAL || b == Kind.PAD || (a == Kind.HELD && carriedInternal != null);
    }

    /** Why the bridge will not lift the dryer or the separator assembly: the crane does. */
    public static Component craneMessage() {
        return BwrText.tr("status.bwr.refuelling_bridge.use_the_crane",
                "The refuelling bridge moves fuel only. The reactor building crane lifts the vessel head, the steam dryer and the separator assembly.");
    }

    /** The vessel's profile at this reactor (for the assemblies' heights). */
    static VesselProfile profileOf(ReactorControllerBlockEntity r) {
        var e = r.vesselEnvelope();
        var s = r.structure();
        boolean rip = s != null && r.coreLayoutVersion() != 1 && !s.internalPumpPositions().isEmpty();
        return VesselProfile.of(e, rip);
    }

    @Nullable
    private EquipmentPadBlockEntity padAt(BlockPos p) {
        return level != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof EquipmentPadBlockEntity pad ? pad : null;
    }

    @Nullable
    private Component refuseInternal(Move m) {
        ReactorControllerBlockEntity r = reactor();
        if (r == null || r.vesselEnvelope() == null) {
            return BwrText.tr("status.bwr.refuelling_bridge.no_reactor", "No formed reactor lies between the bridge's rails.");
        }
        Loc from = m.from(), to = m.to();
        VesselInternal which = from.kind() == Kind.HELD ? carriedInternal : from.kind() == Kind.CORE || from.kind() == Kind.RACK ? null : from.internal();
        if (which == null || (to.kind() != Kind.INTERNAL && to.kind() != Kind.PAD) || (to.kind() == Kind.INTERNAL || to.kind() == Kind.PAD) && to.internal() != which
                || from.kind() == to.kind()) {
            return BwrText.tr("status.bwr.refuelling_bridge.bad_internal_move", "The dryer and the separator assembly go between the vessel and an equipment pad.");
        }
        if (from.kind() == Kind.HELD && carriedInternal != which) {
            return BwrText.tr("status.bwr.refuelling_bridge.nothing_held", "The grapple is not holding anything.");
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
            EquipmentPadBlockEntity pad = padAt(from.rack());
            if (pad == null || pad.stored() != which || !pad.reactorPos().equals(r.getBlockPos())) {
                return BwrText.tr("status.bwr.refuelling_bridge.pad_empty", "That equipment pad does not hold this reactor's %s.", which.title);
            }
        }
        if (to.kind() == Kind.PAD) {
            EquipmentPadBlockEntity pad = padAt(to.rack());
            if (pad == null) {
                return BwrText.tr("status.bwr.refuelling_bridge.no_pad", "There is no equipment pad there: place one on the floor of a pool of water the bridge reaches.");
            }
            if (pad.stored() != null) {
                return BwrText.tr("status.bwr.refuelling_bridge.pad_taken", "That equipment pad already holds the %s.", pad.stored().title);
            }
        }
        Point a = columnOf(from), b = columnOf(to);
        if (a == null || b == null || !site.reaches(a.x(), a.z()) || !site.reaches(b.x(), b.z())) {
            return BwrText.tr("status.bwr.refuelling_bridge.out_of_reach", "That position is outside the reach of the bridge.");
        }
        if (from.kind() == Kind.HELD) {
            return null; // already hanging from the hoist: it only has to be set down
        }
        // Hanging from the retracted hoist, the assembly must clear the vessel's lip before it moves sideways.
        VesselProfile vp = profileOf(r);
        double clear = r.vesselEnvelope().min().getY() + vp.rim + 0.25;
        double hanging = EquipmentPads.liftAboveBottom(which, vp);
        if (site.homeY() - hanging < clear) {
            return BwrText.tr("status.bwr.refuelling_bridge.rails_too_low",
                    "The rails are too low to lift the %s clear of the vessel: it hangs %s blocks below the hoist. Raise the rails to y %s or higher.",
                    which.title, String.format(Locale.ROOT, "%.1f", hanging), (int) Math.ceil(clear + hanging - BridgeMotion.MAST_HOME_ABOVE_RAIL_M));
        }
        return null;
    }

    /**
     * Queues lifting the dryer or the separator assembly out to the nearest free equipment pad ({@code out}), or setting it back
     * in from the pad that holds it. If the hoist already holds it (after an abort), the move starts from the hoist.
     */
    @Nullable
    public Component queueInternal(VesselInternal which, boolean out) {
        if (level == null || level.isClientSide()) {
            return null;
        }
        if (!(carriedInternal == which && move == null)) {
            return craneMessage();
        }
        refreshSite();
        if (site == null || !site.ok()) {
            return site == null ? BwrText.tr("status.bwr.refuelling_bridge.not_surveyed", "The bridge has not surveyed its rails yet.") : site.problem;
        }
        ReactorControllerBlockEntity r = reactor();
        if (r == null || r.vesselEnvelope() == null) {
            return BwrText.tr("status.bwr.refuelling_bridge.no_reactor", "No formed reactor lies between the bridge's rails.");
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

    private static boolean validLattice(ReactorControllerBlockEntity r, int lattice) {
        for (int p : r.corePositions()) {
            if (p == lattice) {
                return true;
            }
        }
        return false;
    }

    /** The column of a place (y unused). */
    @Nullable
    private Point columnOf(Loc loc) {
        switch (loc.kind()) {
            case CORE -> {
                ReactorControllerBlockEntity r = reactor();
                double[] c = r == null ? null : BridgeSite.coreColumn(r, loc.lattice());
                return c == null ? null : new Point(c[0], 0, c[1]);
            }
            case RACK -> {
                double[] c = BridgeSite.cellColumn(loc.rack(), loc.cell());
                return new Point(c[0], 0, c[1]);
            }
            case INTERNAL -> {
                ReactorControllerBlockEntity r = reactor();
                var e = r == null ? null : r.vesselEnvelope();
                return e == null ? null : new Point(e.min().getX() + e.width() / 2.0, 0, e.min().getZ() + e.depth() / 2.0);
            }
            case PAD -> {
                return new Point(loc.rack().getX() + 0.5, 0, loc.rack().getZ() + 0.5);
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
            return BwrText.tr("status.bwr.refuelling_bridge.holding", "The grapple still holds an assembly: set it down first.");
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

    /** Stops the bridge where it is and forgets the queue. A carried assembly stays on the grapple. */
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

    /** After a halt, try the step again (a gate opened, an override set, a cell freed). */
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
            case TRANSIT -> beginLeg(server, routeIndex);
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
                begin(server, Phase.RAISE_EMPTY, up, BridgeMotion.legTicks(pos, up, site.runwayAlongX(), false));
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
        begin(server, Phase.TRAVEL, target, BridgeMotion.legTicks(pos, target, site.runwayAlongX(), false));
    }

    private BridgeMotion.Stop stopOf(Loc loc) {
        switch (loc.kind()) {
            case CORE -> {
                ReactorControllerBlockEntity r = reactor();
                double[] c = BridgeSite.coreColumn(r, loc.lattice());
                return new BridgeMotion.Stop(c[0], c[1], BridgeSite.coreTopY(r), BridgeSite.cavitySurfaceY(r));
            }
            case RACK -> {
                SpentFuelPoolBlockEntity p = pool();
                double[] c = BridgeSite.cellColumn(loc.rack(), loc.cell());
                return new BridgeMotion.Stop(c[0], c[1], BridgeSite.rackSeatY(loc.rack()), BridgeSite.poolSurfaceY(p));
            }
            case INTERNAL -> {
                // the lifting eyes of the assembly in its seat
                ReactorControllerBlockEntity r = reactor();
                VesselProfile vp = profileOf(r);
                Point c = columnOf(loc);
                double lift = loc.internal() == VesselInternal.DRYER ? vp.dryerLift() : vp.separatorLift();
                return new BridgeMotion.Stop(c.x(), c.z(), r.vesselEnvelope().min().getY() + lift, BridgeSite.cavitySurfaceY(r));
            }
            case PAD -> {
                // the lifting eyes of the assembly standing on the pad
                ReactorControllerBlockEntity r = reactor();
                VesselProfile vp = carriedProfile() != null ? carriedProfile() : profileOf(r);
                return new BridgeMotion.Stop(loc.rack().getX() + 0.5, loc.rack().getZ() + 0.5,
                        loc.rack().getY() + EquipmentPadBlockEntity.TOP + EquipmentPads.liftAboveBottom(loc.internal(), vp), Double.NaN);
            }
            default -> {
                Point at = pos;
                return new BridgeMotion.Stop(at.x(), at.z(), at.y() - BridgeMotion.lift(override), site.surfaceAt(level, at.x(), at.z(), chute));
            }
        }
    }

    /** The grapple is down on the handle (or already holds it): check the gate and the hoist limit, then latch. */
    private void afterLowered(ServerLevel server) {
        site = BridgeSite.survey(level, worldPosition);
        chute = site.ok() ? site.chute(level) : chute;
        Component why = refuse(new Move(move.from(), move.to()));
        // the source is still occupied (not yet latched), so a refusal other than a closed gate is a real change
        if (why != null) {
            fault(server, Phase.LATCH, why);
            return;
        }
        if (internalMove(move)) {
            // up out of the water to the mast's rest height, across in the air, down onto the destination: no hoist limit, the dose spikes
            Point dest = columnOf(move.to());
            route.clear();
            route.add(new Point(pos.x(), site.homeY(), pos.z()));
            route.add(new Point(dest.x(), site.homeY(), dest.z()));
            routeIndex = 1;
            if (move.from().kind() == Kind.HELD) {
                begin(server, Phase.RAISE_LOADED, route.get(0), BridgeMotion.legTicks(pos, route.get(0), site.runwayAlongX(), true));
                return;
            }
            begin(server, Phase.LATCH, pos, BridgeMotion.LATCH_TICKS);
            return;
        }
        BridgeMotion.Stop from = stopOf(move.from());
        BridgeMotion.Stop to = stopOf(move.to());
        boolean crosses = site.bodyAt(level, from.x(), from.z(), chute) != site.bodyAt(level, to.x(), to.z(), chute);
        BridgeMotion.Chute throughChute = crosses ? site.motionChute(chute) : null;
        BridgeMotion.Route r = BridgeMotion.route(from, to, throughChute, override, site.homeY());
        if (crosses && BridgeMotion.chuteFloorMarginM(r, throughChute) < 0.0) {
            fault(server, Phase.LATCH, BwrText.tr("status.bwr.refuelling_bridge.chute_shallow",
                    "Stopped: the cattle chute is too shallow. A 4.5 m assembly would hang through its floor; stack the canal blocks deeper or fill the cavity and the pool higher."));
            return;
        }
        if (!override && r.minCoverM() < MIN_COVER_M - 1e-9) {
            Component msg = crosses && chute.state() == BridgeSite.ChuteState.DRAINED
                    ? BwrText.tr("status.bwr.refuelling_bridge.drained_halt",
                    "Hoist limit: the transfer canal is drained and nothing would shield the assembly. Refill it, or override the interlock.")
                    : BwrText.tr("status.bwr.refuelling_bridge.hoist_limit",
                    "Hoist limit: lifting would leave only %s m of water over the assembly (minimum %s m). Add water, or override the interlock.",
                    String.format(Locale.ROOT, "%.1f", r.minCoverM()), String.format(Locale.ROOT, "%.1f", MIN_COVER_M));
            fault(server, Phase.LATCH, msg);
            return;
        }
        route.clear();
        for (BridgeMotion.Waypoint w : r.waypoints()) {
            route.add(w.at());
        }
        routeIndex = 1;
        if (move.from().kind() == Kind.HELD) {
            // already latched: go straight on from the point the grapple is at
            beginLeg(server, 1);
            return;
        }
        begin(server, Phase.LATCH, pos, BridgeMotion.LATCH_TICKS);
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
            begin(server, Phase.LOWER_LOADED, seat, BridgeMotion.legTicks(dest, seat, site.runwayAlongX(), true));
            return;
        }
        if (i >= 1 && route.size() == 4) {
            // the legs that go into and through the chute need the gate open and the canal still there
            chute = site.chute(level);
            if (chute.state() == BridgeSite.ChuteState.CLOSED) {
                fault(server, Phase.TRANSIT, BwrText.tr("status.bwr.refuelling_bridge.gate_closed_halt",
                        "Stopped: the gate at %s is closed. Open it, then resume.", chute.closedGate() == null ? "?" : chute.closedGate().toShortString()));
                return;
            }
            if (chute.state() == BridgeSite.ChuteState.ABSENT) {
                fault(server, Phase.TRANSIT, BwrText.tr("status.bwr.refuelling_bridge.no_chute_halt", "Stopped: the cattle chute is gone."));
                return;
            }
        }
        Point start = route.get(i - 1);
        Point to = route.get(i);
        pos = start;
        begin(server, Phase.TRANSIT, to, BridgeMotion.legTicks(start, to, site.runwayAlongX(), true));
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
                begin(server, Phase.LOWER_EMPTY, down, BridgeMotion.legTicks(end, down, site.runwayAlongX(), false));
            }
            case LOWER_EMPTY -> afterLowered(server);
            case LATCH -> latch(server);
            case RAISE_LOADED -> beginLeg(server, 1);
            case TRANSIT -> beginLeg(server, routeIndex + 1);
            case LOWER_LOADED -> begin(server, Phase.RELEASE, pos, BridgeMotion.RELEASE_TICKS);
            case RELEASE -> finishRelease(server);
            default -> {
                phase = Phase.IDLE;
            }
        }
    }

    private void latch(ServerLevel server) {
        Loc from = move.from();
        if (from.kind() == Kind.INTERNAL || from.kind() == Kind.PAD) {
            ReactorControllerBlockEntity r = reactor();
            VesselInternal which = from.internal();
            boolean taken = false;
            if (r != null && from.kind() == Kind.INTERNAL && r.internalLiftBlocked(which) == null) {
                taken = true;
            } else if (r != null && from.kind() == Kind.PAD) {
                EquipmentPadBlockEntity pad = padAt(from.rack());
                taken = pad != null && pad.stored() == which && pad.take() == which;
            }
            if (!taken) {
                move = null;
                queue.clear();
                fault(server, Phase.IDLE, BwrText.tr("status.bwr.refuelling_bridge.nothing_to_latch",
                        "Stopped: the grapple closed on nothing; the assembly is no longer there."));
                return;
            }
            VesselProfile vp = profileOf(r);
            r.internalOut(which, worldPosition);
            carriedInternal = which;
            carriedVessel = new int[]{vp.width, vp.depth, vp.height, vp.internalPumps ? 1 : 0};
            Point up = new Point(pos.x(), route.get(0).y(), pos.z());
            begin(server, Phase.RAISE_LOADED, up, BridgeMotion.legTicks(pos, up, site.runwayAlongX(), true));
            return;
        }
        ItemStack taken = ItemStack.EMPTY;
        if (from.kind() == Kind.CORE) {
            ReactorControllerBlockEntity r = reactor();
            if (r != null && r.refuellingBlockedMessage() == null) {
                taken = r.unloadAssembly(from.lattice());
            }
        } else if (from.kind() == Kind.RACK) {
            SpentFuelPoolBlockEntity p = pool();
            if (p != null) {
                taken = p.extractFrom(from.rack(), from.cell());
            }
        }
        if (taken.isEmpty()) {
            Component why = BwrText.tr("status.bwr.refuelling_bridge.nothing_to_latch", "Stopped: the grapple closed on nothing; the assembly is no longer there.");
            move = null;
            queue.clear();
            fault(server, Phase.IDLE, why);
            return;
        }
        carried = taken;
        wattsAt = Long.MIN_VALUE;
        Point up = new Point(pos.x(), route.get(0).y(), pos.z());
        begin(server, Phase.RAISE_LOADED, up, BridgeMotion.legTicks(pos, up, site.runwayAlongX(), true));
    }

    private void finishRelease(ServerLevel server) {
        Loc to = move.to();
        if (carriedInternal != null) {
            ReactorControllerBlockEntity r = reactor();
            VesselInternal which = carriedInternal;
            boolean placed = false;
            if (r != null && to.kind() == Kind.INTERNAL && to.internal() == which) {
                Component why = r.internalInstallBlocked(which);
                if (why == null) {
                    r.internalIn(which);
                    placed = true;
                }
            } else if (r != null && to.kind() == Kind.PAD && to.internal() == which) {
                EquipmentPadBlockEntity pad = padAt(to.rack());
                if (pad != null && pad.stored() == null) {
                    pad.store(which, r, carriedProfile() != null ? carriedProfile() : profileOf(r));
                    r.internalOut(which, to.rack());
                    placed = true;
                }
            }
            if (!placed) {
                fault(server, Phase.RELEASE, BwrText.tr("status.bwr.refuelling_bridge.cannot_release",
                        "Stopped: the assembly cannot be set down at %s (occupied, or the reactor or pool is no longer ready). It stays on the grapple.", to.describe()));
                return;
            }
            carriedInternal = null;
            carriedVessel = new int[0];
            updateDose(server);
            Point up = pos.withY(site.homeY());
            begin(server, Phase.RAISE_EMPTY, up, BridgeMotion.legTicks(pos, up, site.runwayAlongX(), false));
            return;
        }
        boolean placed = false;
        if (to.kind() == Kind.CORE) {
            ReactorControllerBlockEntity r = reactor();
            placed = r != null && r.refuellingBlockedMessage() == null && r.loadAssembly(to.lattice(), carried);
        } else if (to.kind() == Kind.RACK) {
            SpentFuelPoolBlockEntity p = pool();
            placed = p != null && p.stackAt(to.rack(), to.cell()).isEmpty() && p.insertAt(to.rack(), to.cell(), carried.copyWithCount(1));
        }
        if (!placed) {
            fault(server, Phase.RELEASE, BwrText.tr("status.bwr.refuelling_bridge.cannot_release",
                    "Stopped: the assembly cannot be set down at %s (occupied, or the reactor or pool is no longer ready). It stays on the grapple.", to.describe()));
            return;
        }
        carried = ItemStack.EMPTY;
        updateDose(server);
        Point up = pos.withY(site.homeY());
        begin(server, Phase.RAISE_EMPTY, up, BridgeMotion.legTicks(pos, up, site.runwayAlongX(), false));
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
        elapsed++;
        if (elapsed >= duration) {
            complete(server);
        }
        if (holding() && busy()) {
            if (server.getGameTime() % 20 == 0 && site != null && site.ok()) {
                chute = site.chute(server);
            }
            updateDose(server);
        }
        if (busy()) {
            setChanged();
            schedule(server);
        }
    }

    private boolean worldReady(ServerLevel server) {
        return site == null || ((site.reactorPos == null || server.isLoaded(site.reactorPos)) && (site.poolPos == null || server.isLoaded(site.poolPos)));
    }

    private void updateDose(ServerLevel server) {
        if (carriedInternal != null && pos != null) {
            // the dryer or the separator assembly: a point at its middle, below the hoist, behind the water over that point
            Point at = busy() ? tip() : pos;
            VesselProfile vp = carriedProfile();
            double middle = at.y() - (vp == null ? 2.0 : EquipmentPads.liftAboveBottom(carriedInternal, vp) / 2.0);
            // Over the vessel: the water the player sees (the drawn vessel and well), so the assembly is out of the water when it
            // hangs above that surface. Elsewhere: the pit an equipment pad stands in, or the pool's own surface.
            ReactorControllerBlockEntity r = reactor();
            var e = r == null ? null : r.vesselEnvelope();
            boolean overVessel = e != null && at.x() >= e.min().getX() && at.x() <= e.max().getX() + 1 && at.z() >= e.min().getZ() && at.z() <= e.max().getZ() + 1;
            double surface = overVessel ? r.drawnWaterSurfaceY() : site != null && site.ok() ? site.surfaceAt(server, at.x(), at.z(), chute) : Double.NaN;
            if (!Double.isFinite(surface)) {
                surface = waterSurface(server, at.x(), at.z(), at.y() + 1, middle - 1);
            }
            carriedCover = BridgeMotion.cover(surface, middle);
            carriedWatts = carriedInternal.gammaWatts;
            RadiationSources.emit(server, this, new Vec3(at.x(), middle, at.z()), carriedInternal.gammaWatts, BridgeMotion.transmission(carriedCover));
            return;
        }
        if (carried.isEmpty() || pos == null) {
            RadiationSources.clear(server, this);
            carriedCover = 0.0;
            carriedWatts = 0.0;
            return;
        }
        Point at = busy() ? tip() : pos;
        long now = server.getGameTime();
        if (now - wattsAt >= 20 || wattsAt == Long.MIN_VALUE) {
            carriedWatts = SpentFuelHeat.decayWatts(carried, now);
            wattsAt = now;
        }
        double surface = site != null && site.ok() ? site.surfaceAt(server, at.x(), at.z(), chute) : Double.NaN;
        carriedCover = BridgeMotion.cover(surface, at.y());
        RadiationSources.emit(server, this, new Vec3(at.x(), at.y(), at.z()), BridgeMotion.gammaWatts(carriedWatts),
                BridgeMotion.transmission(carriedCover));
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

    private void refreshGlow() {
        glow = 0;
        surfaceSeen = Double.NaN;
        if (carried.isEmpty() || !(level instanceof ServerLevel server) || pos == null) {
            return;
        }
        double fraction = SpentFuelHeat.decayWatts(carried, server.getGameTime()) / dev.bwr.core.thermal.AssemblyDecayHeat.REFERENCE_OPERATING_POWER_W;
        glow = (int) Math.round(15.0 * dev.bwr.mod.reactor.CherenkovAppearance.strength(fraction, true, true, 12.0));
        Point at = tip();
        surfaceSeen = site != null && site.ok() ? site.surfaceAt(server, at.x(), at.z(), chute) : Double.NaN;
    }

    /** The glow strength the client draws on the carried assembly (0 when none, or when it hangs in air). */
    public int glow() {
        return glow;
    }

    /** The surface of the water the carried assembly was under when the last phase began; NaN if none. */
    public double surfaceSeen() {
        return surfaceSeen;
    }

    private void sync() {
        if (level != null && !level.isClientSide()) {
            refreshGlow();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    /** What a broken console hands back: the assembly on the grapple, if any. */
    public List<ItemStack> removalDrops() {
        List<ItemStack> drops = new ArrayList<>();
        if (!carried.isEmpty()) {
            drops.add(carried);
            carried = ItemStack.EMPTY;
        }
        return drops;
    }

    public void dropCarried(ServerLevel server) {
        if (carriedInternal != null) {
            // A console broken under a hanging dryer or separator assembly: it is set back in its vessel rather than lost.
            ReactorControllerBlockEntity r = site == null ? null : site.reactor(server);
            if (r == null) {
                BridgeSite s = BridgeSite.survey(server, worldPosition);
                r = s.ok() ? s.reactor(server) : null;
            }
            if (r != null) {
                r.internalIn(carriedInternal);
            }
            carriedInternal = null;
            carriedVessel = new int[0];
        }
        for (ItemStack drop : removalDrops()) {
            Containers.dropItemStack(server, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, drop);
        }
        RadiationSources.clear(server, this);
    }

    // ---------------------------------------------------------------
    // Description, for the screen, the computer and the tests
    // ---------------------------------------------------------------

    public String carriedSummary() {
        if (carriedInternal != null) {
            return "the " + carriedInternal.title;
        }
        if (carried.isEmpty()) {
            return "";
        }
        var data = FuelAssemblies.dataOf(carried);
        return String.format(Locale.ROOT, "%s, burnup %,.0f MWd/t", data.fuelTypeName(), data.burnupMwdPerTonne());
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
        if (!carried.isEmpty()) {
            tag.put("Carried", carried.save(registries));
        }
        if (carriedInternal != null) {
            tag.putString("CarriedInternal", carriedInternal.id);
            tag.putIntArray("CarriedVessel", carriedVessel);
        }
        if (override) {
            tag.putBoolean("Override", true);
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
        carried = tag.contains("Carried") ? ItemStack.parse(registries, tag.get("Carried")).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        carriedInternal = tag.contains("CarriedInternal") ? VesselInternal.byId(tag.getString("CarriedInternal")) : null;
        carriedVessel = carriedInternal == null ? new int[0] : tag.getIntArray("CarriedVessel");
        override = tag.getBoolean("Override");
        paused = tag.getBoolean("Paused");
        glow = tag.getInt("Glow");
        surfaceSeen = tag.contains("Surface") ? tag.getDouble("Surface") : Double.NaN;
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
        if (glow > 0 && !carried.isEmpty()) {
            tag.putInt("Glow", glow);
            if (!Double.isNaN(surfaceSeen)) {
                tag.putDouble("Surface", surfaceSeen);
            }
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
