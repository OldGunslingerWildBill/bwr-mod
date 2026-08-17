package dev.bwr.mod.reactor;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * <h2>It does not write the core</h2>
 * {@link ReactorControllerBlockEntity} sums every nozzle on its vessel and
 * writes one figure, exactly as {@code ReactorEccsBus} sums relief valves and
 * writes one. Two per-tick writers on one core scalar is the defect this
 * codebase keeps finding in itself, and a nozzle is not going to add another.
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
     * capacity scaled by stop position, by the ratio of upstream absolute
     * pressure to rated, and by the subcritical correction once the vessel comes
     * down within about a factor of two of the line.
     */
    public double flowKgPerS(double domePressurePsig, double downstreamPsia) {
        if (level != null) {
            lastPolledGameTime = level.getGameTime();
        }
        if (!(position > 0.0) || !steamLineAttached) {
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

        lastFlowKgPerS =
                CAPACITY_KG_PER_S * position * (upstreamPsia / ratedPsia) * subcritical;
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
     */
    public boolean isPartOfFormedReactor() {
        return level != null && level.getGameTime() - lastPolledGameTime <= POLL_STALE_TICKS;
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
     * Re-check what is welded to the nozzle, at most every
     * {@link #ATTACHMENT_RESCAN_TICKS}.
     *
     * <p>Breaking or placing a neighbour fires {@code neighborChanged} on this
     * block, which refreshes immediately, so the interval is only there to cover
     * the cases vanilla does not notify: a chunk loading with the line already
     * built, or a line block replaced by something that does not push updates.
     * Called by the controller because the controller is already walking its
     * nozzles; this block carries no ticker of its own.
     */
    public void refreshAttachmentPeriodically(Level level) {
        if (sinceAttachmentScan < Integer.MAX_VALUE) {
            sinceAttachmentScan++;
        }
        if (sinceAttachmentScan < ATTACHMENT_RESCAN_TICKS) {
            return;
        }
        sinceAttachmentScan = 0;
        refreshAttachment(level);
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
     * Whether a main steam line block sits on any face of the given position.
     *
     * <p>Static and public so {@link ReactorStructure} can say the same thing
     * about a nozzle it has just found in the shell without needing the block
     * entity to have ticked yet. One definition of "welded to a line", used by
     * the flow calculation and by the structure report alike.
     *
     * <p>What counts is anything that is a piece of main steam line: a
     * pressurised tube, an isolation valve body, a relief valve body, or the
     * turbine steam outlet itself. A relief valve counts because
     * {@code SPEC.md} section 6.6 mounts SRVs on the tubes off the reactor
     * outlet, so a nozzle with an SRV bolted straight to it is a legitimate — if
     * short — steam line.
     */
    public static boolean steamLineAttached(Level level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isSteamLineBlock(level.getBlockState(pos.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSteamLineBlock(BlockState state) {
        return state.is(BwrBlocks.PRESSURISED_TUBE.get())
                || state.is(BwrBlocks.MSIV.get())
                || state.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())
                || state.is(BwrBlocks.TURBINE_STEAM_OUTLET.get());
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
    }
}
