package dev.bwr.mod.reactor;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.steam.SteamLineNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * An RPV main steam nozzle — the penetration through which steam leaves the
 * pressure vessel, and the block the main steam line is welded to.
 *
 * <h2>Why this block had to exist</h2>
 * Before it there was no vessel penetration at all. The shell predicate in
 * {@link ReactorStructure#isShell} accepted vessel, controller, sparger, tube,
 * recirculation pump and jet pump, and nothing on that list carries steam out;
 * putting a turbine steam outlet in the wall instead produced "gap in the
 * reactor vessel shell" and the plant refused to form. So the only ways to
 * remove steam from a vessel were a CC:Tweaked computer writing
 * {@code reactor.setTurbineSteamFlow}, a relief valve discharging into a
 * suppression pool that had to be built and validated first, or a turbine steam
 * outlet with Mekanism installed <i>and</i> a turbine actually draining its
 * buffer. A player with none of those three had a vessel that could only ever
 * gain steam. That is the gap the first playtest found, in those words: "there's
 * no way to get steam out of the reactor".
 *
 * <h2>What it is: hardware, and only hardware</h2>
 * A nozzle with a stop valve on it. It has one actuator — the stop position,
 * 0 shut to 1 fully open — and it is moved by the player's hand, by an analogue
 * redstone signal, or by Lua. <b>Nothing in this class ever moves it.</b> There
 * is no pressure regulator here, no setpoint, and no condition under which the
 * nozzle opens or shuts itself. Hold it wide open on a cold vessel and the plant
 * depressurises; hold it shut at power and pressure climbs until the metal gives
 * up. Both are the player's doing.
 *
 * <h2>Flow is choked, and it is a differential</h2>
 * Same physics as {@code SafetyReliefValveBlockEntity}, because it is the same
 * situation: a fixed throat passing saturated steam from a vessel at a thousand
 * psi into a line at roughly atmospheric. Below the critical pressure ratio the
 * throat is sonic and mass flow goes linearly with <i>upstream absolute
 * pressure</i>, which is the form real nozzle and relief sizing uses; above it
 * the standard subcritical correction takes the flow smoothly to zero as the
 * vessel equalises with the line. A nozzle left open at the end of a blowdown
 * stops passing steam because there is no differential left, not because
 * anything decided it should stop.
 *
 * <h2>A nozzle with nothing welded to it passes nothing</h2>
 * This is the same structural requirement the relief valves carry — an SRV that
 * vents to air suppresses nothing and is reported as useless rather than
 * silently working. A main steam nozzle discharging into open world is a hole
 * with no pipe on it, so the flow is zero until a steam line block sits on one
 * of its faces. That is a fact about what has been built, not a permissive: the
 * nozzle does not ask what the reactor is doing, and it will happily blow the
 * vessel down through a single tube stub if the player opens it.
 *
 * <h2>What is in the line downstream is part of the hardware</h2>
 * The nozzle is the first valve in a series of them. An MSIV further along the
 * same line is a second restriction in the same pipe, and a shut one dead-heads
 * the line, so this nozzle passes what the <i>tightest</i> valve between it and
 * the turbine hall allows — see {@link #lineOpenFraction}. That is what makes
 * {@code SPEC.md} section 6.3's headline transient emerge instead of being
 * described: shut the MSIVs on a plant at power and the vessel really is
 * isolated, pressure climbs, voids collapse and power surges, with nothing
 * anywhere deciding that any of it should happen. Before this the MSIVs
 * throttled only the turbine steam outlet's own separate draw, so closing every
 * valve in the plant left the nozzles wide open and the vessel unisolated.
 *
 * <p>It is emphatically not a permissive. Nothing here refuses to open, and the
 * nozzle's own stop valve goes exactly where it is put. A shut valve downstream
 * is a pipe with no way through it, which is a fact about plumbing.
 *
 * <h2>It does not write the core</h2>
 * {@link ReactorControllerBlockEntity} sums every nozzle on its vessel and
 * writes one figure, exactly as {@code ReactorEccsBus} sums relief valves and
 * writes one. Two per-tick writers on one core scalar is the defect this
 * codebase keeps finding in itself, and a nozzle is not going to add another.
 *
 * <h2>The turbine outlet is downstream of this, not beside it</h2>
 * Steam this nozzle passes has already left the vessel by the time anything
 * downstream sees it. A {@code TurbineSteamOutletBlockEntity} on the far end of
 * the line therefore <b>claims a share of that departing steam</b> through
 * {@link #claimFlowKgPerS} rather than drawing on the vessel a second time; see
 * {@code TurbineSteamOutletBlockEntity} for the whole argument and for what
 * happens to an outlet with no nozzle upstream of it. Whatever no outlet claims
 * is lost down the line to the condenser, which is the honest consequence of a
 * model with no header volume in it: matching the nozzle's stop position to the
 * turbine's demand is the player's control loop, exactly as matching rod
 * position to power is.
 */
public class RpvSteamOutletBlockEntity extends BlockEntity {

    /**
     * Main steam lines a BWR/6 is built with, and therefore how many nozzles it
     * takes to pass rated steam flow.
     *
     * <p>Four, which is what the real plant has: four 26-inch nozzles on the
     * vessel, four lines through the drywell, four MSIV pairs. It is the number
     * that decides whether the steam plant is a build or a formality — one
     * nozzle holds a quarter-power reactor and no more, so a player who wants
     * full power has to run four penetrations and four lines out of the
     * containment, which is exactly the shape of the real thing.
     */
    public static final int MAIN_STEAM_LINES = 4;

    /**
     * Rated steam flow for this plant, kg/s. About 1940.
     *
     * <p>Derived here from {@link PhysicalConstants#RATED_STEAM_FLOW_LB_PER_HR}
     * rather than borrowed from the turbine steam outlet, so that the vessel-side
     * hardware in this package does not depend on the Mekanism-side hardware in
     * another one. The two agree because they are computed from the same
     * constant.
     */
    public static final double RATED_STEAM_FLOW_KG_PER_S =
            PhysicalConstants.RATED_STEAM_FLOW_LB_PER_HR * 0.45359237 / 3600.0;

    /**
     * Steam one fully open nozzle passes at rated dome pressure, kg/s. About
     * 485 — a quarter of rated flow, per {@link #MAIN_STEAM_LINES}.
     */
    public static final double CAPACITY_KG_PER_S = RATED_STEAM_FLOW_KG_PER_S / MAIN_STEAM_LINES;

    /**
     * Downstream-to-upstream absolute pressure ratio below which the throat is
     * choked, for steam: {@code (2 / (gamma + 1)) ^ (gamma / (gamma - 1))} with
     * gamma = 1.3.
     *
     * <p>The same number the relief valves use, for the same reason and with the
     * same derivation. It is stated again here rather than imported so that a
     * vessel block does not reach into the steam package for a property of
     * saturated steam; if one of the two is ever refitted with a better gas
     * model, the other should be looked at deliberately rather than dragged
     * along.
     */
    public static final double CRITICAL_PRESSURE_RATIO = 0.5457;

    /** Ticks between re-checks of what is welded to the nozzle. */
    private static final int ATTACHMENT_RESCAN_TICKS = 20;

    /**
     * Ticks between full walks of the steam line off this nozzle.
     *
     * <p>Slower than the face check because it costs more and answers a question
     * that changes less often: which valves are <i>in</i> the line only moves
     * when somebody places or breaks a block, whereas how far open those valves
     * are is read live on every tick. Forty ticks is the same cadence
     * {@code TurbineSteamOutletBlockEntity} rebinds on, deliberately — the two
     * are surveying the same pipework from opposite ends.
     */
    private static final int LINE_SURVEY_TICKS = 40;

    /** How stale a poll may be before the readout stops claiming to be live. */
    private static final int POLL_STALE_TICKS = 4;

    // Volatile because a CC:Tweaked computer thread may read them; the matching
    // writes are marshalled onto the server thread by PlantActuators.

    /** The actuator: stop valve position, 0 shut to 1 fully open. */
    private volatile double position;

    /** When true the redstone input is ignored and Lua owns the position. */
    private volatile boolean computerControlled;

    /** Steam this nozzle passed on the last tick the controller asked, kg/s. */
    private volatile double lastFlowKgPerS;

    /** Whether a main steam line block is welded to any face. */
    private volatile boolean steamLineAttached;

    /**
     * Game time at which a controller last asked this nozzle for a flow, or
     * {@link Long#MIN_VALUE} if one never has.
     *
     * <p>Only the controller of a <i>formed</i> reactor walks its nozzles, so
     * this doubles as the answer to "is this block actually part of a working
     * multiblock". Without it a nozzle whose vessel had since been broken open
     * went on quoting the last flow it ever passed, which is the most misleading
     * possible thing for a readout to say to a player who is standing there
     * trying to work out why their plant stopped.
     */
    private volatile long lastPolledGameTime = Long.MIN_VALUE;

    /**
     * The redstone signal this nozzle last acted on, or -1 before it has acted
     * on one. See {@link #acceptRedstoneSignal(int)} for why the level is not
     * enough on its own.
     */
    private int lastRedstoneSignal = -1;

    private int sinceAttachmentScan = ATTACHMENT_RESCAN_TICKS;
    private int sinceLineSurvey = LINE_SURVEY_TICKS;

    /**
     * Isolation valves in the line off this nozzle, refound on the survey timer
     * and read live every tick. Positions only, never block entities: a cached
     * {@link BlockEntity} outlives the block it belongs to and keeps answering
     * after it has been broken.
     */
    private final List<BlockPos> lineIsolationValves = new ArrayList<>();

    /** Turbine steam outlets the line off this nozzle actually reaches. */
    private final List<BlockPos> lineTurbineOutlets = new ArrayList<>();

    /** True when the last survey ran into {@link SteamLineNetwork#MAX_LINE_BLOCKS}. */
    private volatile boolean lineTruncated;

    /** Pieces of steam line the last survey walked from this nozzle. */
    private volatile int lineBlockCount;

    /** How far open the tightest valve in the line was on the last flow figure. */
    private volatile double lastLineOpenFraction = 1.0;

    /**
     * The controller that last polled this nozzle, so that hardware downstream
     * can find the reactor by following the pipe rather than by looking for a
     * controller in a box around itself.
     *
     * <p>Not persisted. It is re-established on the first tick of the first
     * formed reactor that owns this nozzle, and a saved value would only ever be
     * an opportunity to point at a controller that is no longer there.
     */
    private volatile BlockPos controllerPos;

    /**
     * The tick a downstream claim ledger belongs to, and how much of this
     * nozzle's flow has been claimed on it. See {@link #claimFlowKgPerS}.
     *
     * <p>Server thread only. Every caller is inside a block entity tick.
     */
    private long claimGameTime = Long.MIN_VALUE;
    private double claimedKgPerS;

    public RpvSteamOutletBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RPV_STEAM_OUTLET.get(), pos, state);
    }

    // -----------------------------------------------------------------
    // The actuator: the player's, never the mod's
    // -----------------------------------------------------------------

    /** Stop valve position, 0 shut to 1 fully open. */
    public double getPosition() {
        return position;
    }

    /**
     * Move the stop valve. Called from the player's hand, from redstone and from
     * Lua; never from physics. Out-of-range and non-finite values are clamped
     * because a valve cannot be more than open or less than shut, not because
     * either extreme would be dangerous.
     */
    public void setPosition(double fraction) {
        // Marshalled onto the server thread: a peripheral would call this from a
        // CC computer thread and setChanged() dispatches neighbour updates. See
        // PlantActuators.
        PlantActuators.run(this, () -> {
            this.position = Double.isFinite(fraction)
                    ? Math.max(0.0, Math.min(1.0, fraction))
                    : 0.0;
            setChanged();
        });
    }

    public boolean isComputerControlled() {
        return computerControlled;
    }

    public void setComputerControlled(boolean computerControlled) {
        PlantActuators.run(this, () -> {
            this.computerControlled = computerControlled;
            setChanged();
        });
    }

    /**
     * Take an analogue redstone signal, 0..15, as a stop valve position.
     *
     * <p><b>On change, not on level.</b> A hand on the nozzle and a lever beside
     * it are two writers of one position, and pushing the redstone figure in on
     * every neighbour update makes the hand useless: open the nozzle by hand
     * beside an unpowered lever, place any block next to it, and the resulting
     * neighbour update rewrites the position to signal-zero — shut — with no
     * message and no indication. That is the failure
     * {@code TurbineSteamOutletPeripheral.setFlow} documents against Lua, and
     * {@code ReactorControllerBlockEntity.gatherPumpFlow} documents against the
     * recirculation pumps. The resolution is the same one in both places:
     * redstone counts as a writer only when it has something new to say, and
     * between changes whoever wrote last stands.
     *
     * @param signal redstone strength 0..15
     */
    public void acceptRedstoneSignal(int signal) {
        int clamped = Math.max(0, Math.min(15, signal));
        if (clamped == lastRedstoneSignal) {
            return;
        }
        lastRedstoneSignal = clamped;
        setPosition(clamped / 15.0);
    }

    // -----------------------------------------------------------------
    // Flow
    // -----------------------------------------------------------------

    /**
     * Steam this nozzle is passing right now against atmospheric back-pressure,
     * kg/s.
     *
     * <p>Convenience over {@link #flowKgPerS(double, double)} for callers that
     * have no containment pressure to quote. Containment is not modelled yet
     * ({@code SPEC.md} section 16) and a condenser sits below atmospheric
     * anyway, so atmospheric is the conservative stand-in; the two-argument form
     * is the one that keeps being right when a containment model arrives.
     */
    public double flowKgPerS(double domePressurePsig) {
        return flowKgPerS(domePressurePsig, PhysicalConstants.ATMOSPHERIC_PSI);
    }

    /**
     * Steam this nozzle is passing right now, kg/s, against a stated downstream
     * pressure.
     *
     * <p>A shut nozzle, or one with no steam line welded to it, passes nothing.
     * Everything else is the choked-flow relation described on the class: rated
     * capacity scaled by stop position, by how far open the tightest valve in
     * the line downstream is, by the ratio of upstream absolute pressure to
     * rated, and by the subcritical correction once the vessel comes down within
     * about a factor of two of the line.
     *
     * <p><b>The claim ledger is deliberately not reset here.</b> Block entity
     * tick order is whatever order the chunks happen to hold them in, so a
     * turbine outlet can perfectly well tick before its controller does; if this
     * method cleared the ledger, an outlet that ticked before the controller and
     * a second outlet that ticked after it would each get a full allocation and
     * between them hand Mekanism more steam than ever left the vessel. Resetting
     * on first claim of a new tick — which is what {@link #claimFlowKgPerS}
     * does — costs at worst one tick of lag on the figure being shared out, on a
     * quantity that moves on a several-second time constant, and it can never
     * over-issue.
     */
    public double flowKgPerS(double domePressurePsig, double downstreamPsia) {
        if (level != null) {
            lastPolledGameTime = level.getGameTime();
        }
        double lineOpen = lineOpenFraction();
        lastLineOpenFraction = lineOpen;
        if (!(position > 0.0) || !steamLineAttached || !(lineOpen > 0.0)) {
            lastFlowKgPerS = 0.0;
            return 0.0;
        }
        double ratedPsia = Saturation.psiaFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
        double upstreamPsia = Saturation.psiaFromPsig(domePressurePsig);
        double downstream = Math.max(0.0, downstreamPsia);
        if (!(upstreamPsia > downstream)) {
            lastFlowKgPerS = 0.0;
            return 0.0;
        }

        double ratio = downstream / upstreamPsia;
        double subcritical = 1.0;
        if (ratio > CRITICAL_PRESSURE_RATIO) {
            // Fraction of the choked rate still passing as the throat unchokes.
            // Meets 1.0 at the critical ratio and reaches 0.0 at equalisation,
            // so there is no step anywhere.
            double x = (ratio - CRITICAL_PRESSURE_RATIO) / (1.0 - CRITICAL_PRESSURE_RATIO);
            subcritical = Math.sqrt(Math.max(0.0, 1.0 - x * x));
        }

        double flow = CAPACITY_KG_PER_S * position * lineOpen
                * (upstreamPsia / ratedPsia) * subcritical;
        // A non-finite dome pressure would otherwise be handed straight to the
        // vessel model as a steam sink and take the whole plant to NaN in one
        // tick. Nothing in the mod produces one, but this method is public and
        // its argument comes from a caller rather than from a constant.
        lastFlowKgPerS = Double.isFinite(flow) ? Math.max(0.0, flow) : 0.0;
        return lastFlowKgPerS;
    }

    /** Steam passed on the last tick the controller asked for a figure, kg/s. */
    public double getLastFlowKgPerS() {
        return lastFlowKgPerS;
    }

    /**
     * True when a formed reactor's controller has asked this nozzle for a flow
     * within the last few ticks — which is the only way a nozzle in a working
     * vessel shell can be asked at all, and therefore the honest test for "this
     * block is part of a reactor".
     *
     * <h2>The sentinel has to be tested, not subtracted</h2>
     * This was {@code level.getGameTime() - lastPolledGameTime <= POLL_STALE_TICKS}
     * with {@code lastPolledGameTime} starting at {@link Long#MIN_VALUE}, and
     * that subtraction <b>overflows</b>: for any ordinary game time the result
     * wraps to a huge negative number, which is comfortably less than four. So
     * the never-polled case — the exact case the field was added for — answered
     * <i>true</i>, permanently and for every nozzle. A nozzle sitting in a vessel
     * that had been broken open reported itself part of a formed reactor and went
     * on quoting the last flow it ever passed, which is the single most
     * misleading thing this readout could say to a player standing there trying
     * to work out why their plant stopped.
     */
    public boolean isPartOfFormedReactor() {
        if (level == null || lastPolledGameTime == Long.MIN_VALUE) {
            return false;
        }
        long since = level.getGameTime() - lastPolledGameTime;
        return since >= 0 && since <= POLL_STALE_TICKS;
    }

    /**
     * Take a share of the steam this nozzle is passing, kg/s.
     *
     * <p>The steam has already left the vessel — the controller removed it on
     * this tick's {@link #flowKgPerS} call — so this is not a second draw on the
     * plant, it is bookkeeping about where the departing steam ends up. The same
     * shape as {@code SafetyReliefValveBlockEntity.claimCondensation} and for the
     * same reason: several consumers can see one piece of hardware, and the
     * steam has to be counted once however many of them ask.
     *
     * <p>First come, first served, and no consumer can take more than is
     * flowing. Whatever nobody claims is lost down the line to the condenser;
     * that is the price of a model with no header volume in it, and it is
     * visible in the readouts on both ends rather than hidden.
     *
     * <p>Refused outright when this nozzle is not part of a formed reactor that
     * has polled it this tick or last. Without that test a consumer would go on
     * claiming against a stale {@code lastFlowKgPerS} after the vessel had been
     * broken open or its chunk unloaded, which is steam appearing from nothing.
     *
     * @param gameTime  the tick the claim is for
     * @param wantKgPerS how much the caller would take if it were there
     * @return how much it actually gets, never negative and never NaN
     */
    public double claimFlowKgPerS(long gameTime, double wantKgPerS) {
        if (!(wantKgPerS > 0.0) || !isPartOfFormedReactor()) {
            return 0.0;
        }
        if (claimGameTime != gameTime) {
            claimGameTime = gameTime;
            claimedKgPerS = 0.0;
        }
        double available = lastFlowKgPerS - claimedKgPerS;
        if (!(available > 0.0)) {
            return 0.0;
        }
        double taken = Math.min(available, wantKgPerS);
        claimedKgPerS += taken;
        return taken;
    }

    /**
     * Note which controller owns this nozzle, so that hardware on the far end of
     * the steam line can find the reactor by following the pipe.
     *
     * <p>Called once a tick by the controller that is already walking its
     * nozzles. It is not an actuator and touches nothing but a field, so it does
     * not go through {@code PlantActuators} and does not mark the chunk dirty —
     * doing either would be twenty writes a second per nozzle for a value that
     * is not saved.
     */
    public void noteController(BlockPos pos) {
        this.controllerPos = pos == null ? null : pos.immutable();
    }

    /** The controller that last polled this nozzle, or null. */
    public BlockPos getControllerPos() {
        return controllerPos;
    }

    /**
     * What this nozzle would pass fully open at the given dome pressure, kg/s.
     * A measurement of the hardware, published so a player sizing a steam plant
     * can work out how many nozzles they need without reading this file.
     */
    public double capacityKgPerS(double domePressurePsig) {
        double ratedPsia = Saturation.psiaFromPsig(PhysicalConstants.RATED_DOME_PRESSURE_PSIG);
        double upstreamPsia = Saturation.psiaFromPsig(domePressurePsig);
        return CAPACITY_KG_PER_S * Math.max(0.0, upstreamPsia / ratedPsia);
    }

    // -----------------------------------------------------------------
    // What is welded to the nozzle
    // -----------------------------------------------------------------

    /** True when a main steam line block sits on one of this nozzle's faces. */
    public boolean isSteamLineAttached() {
        return steamLineAttached;
    }

    /**
     * Re-check what is welded to the nozzle, and every so often what the line it
     * is welded to actually reaches.
     *
     * <p>Two cadences, because the two questions cost different amounts and
     * change at different rates. Whether there is a pipe on the flange is six
     * block lookups and is re-asked every {@link #ATTACHMENT_RESCAN_TICKS};
     * where that pipe goes is a walk of the whole line and is re-asked every
     * {@link #LINE_SURVEY_TICKS}. Breaking or placing a neighbour fires
     * {@code neighborChanged} on this block, which refreshes the cheap answer
     * immediately, so the intervals only cover the cases vanilla does not
     * notify: a chunk loading with the line already built, or a block further
     * down the line changing where nothing tells this nozzle about it.
     *
     * <p>Called by the controller because the controller is already walking its
     * nozzles; this block carries no ticker of its own.
     */
    public void refreshAttachmentPeriodically(Level level) {
        if (sinceAttachmentScan < Integer.MAX_VALUE) {
            sinceAttachmentScan++;
        }
        if (sinceAttachmentScan >= ATTACHMENT_RESCAN_TICKS) {
            sinceAttachmentScan = 0;
            refreshAttachment(level);
        }
        if (sinceLineSurvey < Integer.MAX_VALUE) {
            sinceLineSurvey++;
        }
        if (sinceLineSurvey >= LINE_SURVEY_TICKS) {
            sinceLineSurvey = 0;
            refreshLine(level);
        }
    }

    /** Re-check what is welded to the nozzle right now. */
    public void refreshAttachment(Level level) {
        boolean found = steamLineAttached(level, getBlockPos());
        if (found != steamLineAttached) {
            steamLineAttached = found;
            setChanged();
        }
    }

    /**
     * Walk the line off this nozzle and cache what is in it.
     *
     * <p>Positions only. A cached block entity outlives the block it belongs to
     * — that is the stale-reference defect this codebase keeps finding — so the
     * valves are refound from their positions on every tick that reads them.
     */
    public void refreshLine(Level level) {
        SteamLineNetwork.Survey survey = SteamLineNetwork.survey(level, getBlockPos());
        lineIsolationValves.clear();
        lineIsolationValves.addAll(survey.isolationValves());
        lineTurbineOutlets.clear();
        lineTurbineOutlets.addAll(survey.turbineOutlets());
        lineTruncated = survey.truncated();
        lineBlockCount = survey.lineBlocks();
    }

    /**
     * How far open the tightest isolation valve in the line downstream is, 0..1.
     *
     * <p>The <i>most closed</i> valve governs, not the product of them all —
     * valves in series are one restriction each in the same pipe, and to the
     * accuracy this model works at the tightest one sets the flow. The same rule
     * {@code TurbineSteamOutletBlockEntity.isolationValveOpenFraction} uses, and
     * deliberately the same words, because it is the same pipe seen from the
     * other end.
     *
     * <p>No valve in the line at all means an unisolable line, which is exactly
     * what a plant built without MSIVs has. That is not a permissive either: it
     * is the absence of a piece of hardware the player chose not to build.
     *
     * <p>A valve whose chunk is not loaded is skipped rather than dropped, for
     * the reason {@code ReactorControllerBlockEntity.gatherPumpFlow} spells out
     * at length — {@code getBlockEntity} answers null for an unloaded chunk
     * exactly as it does for a broken block, and treating the two the same makes
     * a valve across a chunk border deregister itself for good.
     */
    private double lineOpenFraction() {
        if (level == null || lineIsolationValves.isEmpty()) {
            return 1.0;
        }
        double fraction = 1.0;
        var it = lineIsolationValves.iterator();
        while (it.hasNext()) {
            BlockPos p = it.next();
            if (!level.isLoaded(p)) {
                continue;
            }
            if (!(level.getBlockEntity(p)
                    instanceof dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity valve)) {
                it.remove(); // the valve was broken or replaced
                continue;
            }
            double open = valve.getPosition();
            // A stroke read out of a hand-edited save could be anything at all,
            // and a NaN here would propagate straight into the vessel's steam
            // balance. Clamp rather than trust.
            fraction = Math.min(fraction,
                    Double.isFinite(open) ? Math.max(0.0, Math.min(1.0, open)) : 0.0);
        }
        return fraction;
    }

    // The three below are the readout's data source and, when somebody wants
    // them there, the natural additions to RpvSteamOutletPeripheral.getStatus:
    // "how isolated is my steam path" and "does anything downstream actually
    // take the steam" are exactly the questions a Lua pressure loop asks. They
    // are consumed by statusLines() today; nothing here is speculative surface.

    /** Isolation valves this nozzle has found in the line off it. */
    public int getLineIsolationValveCount() {
        return lineIsolationValves.size();
    }

    /** Turbine steam outlets the line off this nozzle reaches. */
    public int getLineTurbineOutletCount() {
        return lineTurbineOutlets.size();
    }

    /** Pieces of steam line walked from this nozzle on the last survey. */
    public int getLineBlockCount() {
        return lineBlockCount;
    }

    /** How far open the tightest valve in the line was on the last flow figure. */
    public double getLineOpenFraction() {
        return lastLineOpenFraction;
    }

    /** True when the line is longer than one survey walks. */
    public boolean isLineTruncated() {
        return lineTruncated;
    }

    /**
     * Whether a main steam line block sits on any face of the given position.
     *
     * <p>Static and public so {@link ReactorStructure} can say the same thing
     * about a nozzle it has just found in the shell without needing the block
     * entity to have ticked yet. One definition of "welded to a line", used by
     * the flow calculation and by the structure report alike.
     *
     * <p>Delegated to {@link SteamLineNetwork#isLineWeldedTo}, which applies the
     * same rule {@link dev.bwr.mod.steam.PressurisedTubeBlock} draws an arm for:
     * the {@code SteamLinePort} interface, with the {@code #bwr:steam_line} block
     * tag as the data-driven fallback. This used to be a hard-coded list of four
     * blocks, which meant a pack author who added a header to the tag got a tube
     * that visibly connected to the nozzle and a nozzle that reported no steam
     * line on any face and passed nothing. Two definitions of "welded to a line"
     * in one mod is one too many.
     */
    public static boolean steamLineAttached(Level level, BlockPos pos) {
        return SteamLineNetwork.isLineWeldedTo(level, pos);
    }

    // -----------------------------------------------------------------
    // Readout
    // -----------------------------------------------------------------

    /** Lines shown when a player right-clicks the nozzle. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        boolean live = isPartOfFormedReactor();
        out.add(String.format("RPV steam outlet: stop valve %.0f%% open, passing %.1f kg/s",
                position * 100.0, live ? lastFlowKgPerS : 0.0));
        if (!live) {
            out.add("Not part of a formed reactor. A nozzle only carries steam when it is"
                    + " built into a reactor vessel shell that has actually assembled;"
                    + " right-click the controller while sneaking to see what is missing.");
        }
        if (!steamLineAttached) {
            out.add("No main steam line on any face, so this nozzle passes nothing."
                    + " Weld a pressurised steam tube, an MSIV, an SRV or a turbine steam"
                    + " outlet to it.");
        } else {
            // Where the pipe goes, which is the thing a player cannot see from
            // outside and the thing that decides where the steam ends up.
            out.add(String.format(
                    "Line downstream: %d block(s), %d isolation valve(s) with the tightest"
                            + " %.0f%% open, %d turbine steam outlet(s) on the far end",
                    getLineBlockCount(), getLineIsolationValveCount(),
                    getLineOpenFraction() * 100.0, getLineTurbineOutletCount()));
            if (getLineTurbineOutletCount() == 0) {
                out.add("Nothing on this line takes the steam, so all of it is lost to the"
                        + " condenser. Run the line to a turbine steam outlet to put it"
                        + " through a turbine.");
            }
            if (isLineTruncated()) {
                out.add("The steam line is longer than " + SteamLineNetwork.MAX_LINE_BLOCKS
                        + " blocks; only the first " + SteamLineNetwork.MAX_LINE_BLOCKS
                        + " were surveyed, so valves and outlets past that are not counted.");
            }
        }
        out.add(String.format(
                "Rated %.0f kg/s fully open at %.0f psig; %d nozzles pass rated steam flow",
                CAPACITY_KG_PER_S, PhysicalConstants.RATED_DOME_PRESSURE_PSIG, MAIN_STEAM_LINES));
        if (computerControlled) {
            out.add("Under computer control; the redstone input is ignored.");
        }
        return out;
    }

    // -----------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("Position", position);
        tag.putBoolean("ComputerControlled", computerControlled);
        // Saved so that a lever which has not moved across a chunk reload does
        // not count as a change and slam a hand-set nozzle shut on load. See
        // acceptRedstoneSignal.
        tag.putInt("LastRedstoneSignal", lastRedstoneSignal);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        double saved = tag.getDouble("Position");
        position = Double.isFinite(saved) ? Math.max(0.0, Math.min(1.0, saved)) : 0.0;
        computerControlled = tag.getBoolean("ComputerControlled");
        lastRedstoneSignal = tag.contains("LastRedstoneSignal")
                ? tag.getInt("LastRedstoneSignal") : -1;
        // Deliberately not restored: what is welded to the nozzle is a fact
        // about the world, and the world has not been asked yet. The first
        // refresh answers it, and until then the nozzle passes nothing, which is
        // the safe direction to be wrong in for one tick.
        steamLineAttached = false;
        sinceAttachmentScan = ATTACHMENT_RESCAN_TICKS;
        // Likewise the line. Both counters are set so that the first poll after
        // load surveys immediately rather than a second later.
        lineIsolationValves.clear();
        lineTurbineOutlets.clear();
        lineTruncated = false;
        lastLineOpenFraction = 1.0;
        sinceLineSurvey = LINE_SURVEY_TICKS;
        // A nozzle that has not been polled is not part of a formed reactor, and
        // a claim ledger from before the world was saved belongs to nobody.
        lastPolledGameTime = Long.MIN_VALUE;
        lastFlowKgPerS = 0.0;
        claimGameTime = Long.MIN_VALUE;
        claimedKgPerS = 0.0;
        controllerPos = null;
    }
}
