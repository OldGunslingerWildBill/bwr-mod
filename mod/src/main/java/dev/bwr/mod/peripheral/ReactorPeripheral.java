package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.boundary.BoundaryComponent;
import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.instrument.PeriodMeter;
import dev.bwr.mod.damage.BoundaryDamageReadout;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.rods.ControlRodDriveNetwork;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The CC:Tweaked peripheral for a reactor.
 *
 * <h2>This is the entire product surface for control</h2>
 * Players write every scram channel, every setpoint, every protection scheme
 * and every emergency-system actuation sequence themselves, in Lua, from
 * scratch. No reference implementation ships with this mod. So if a measurement
 * is not exposed here, that control function is <b>literally unbuildable</b> —
 * which is why this class errs heavily toward publishing more than seems
 * necessary.
 *
 * <h2>Measurements, never judgements</h2>
 * Every getter returns a number or a state. None of them return an opinion.
 * There is no {@code isHighPressure()}, no {@code shouldScram()}, no
 * {@code isSafe()}. {@link #scram()} exists, but it is a bare actuator with no
 * condition checking whatsoever: it fires when the player's code calls it, and
 * the mod never calls it.
 *
 * <h2>Errors are Lua errors</h2>
 * Anything that can fail throws {@link LuaException} and nothing else. A player
 * can therefore write {@code local ok, err = pcall(reactor.getPressure)} and get
 * {@code false, "reactor is not formed"} rather than the useless
 * {@code "Java Exception Thrown: java.lang.IllegalStateException"} CC produces
 * for an unchecked throwable. That matters more here than in most mods: a vessel
 * block can be broken while a control program is running, so <i>every</i>
 * reading on this interface can start failing at any moment and the program has
 * to be able to tell why.
 *
 * <h2>Everything here runs on the server thread</h2>
 * <b>Every method that touches the block entity or the physics is declared
 * {@code @LuaFunction(mainThread = true)} and it must stay that way.</b> CC
 * invokes a plain {@code @LuaFunction} on the computer thread; {@link ReactorCore}
 * says in its own class javadoc that it is "not thread safe. One instance per
 * reactor, stepped from the server thread", and {@code NodalFluxSolver} says the
 * same about the working arrays it reuses across solves. Reading the core
 * off-thread races {@code core.step()} — a composite readout like
 * {@link #getStatus()} would mix pre-step and post-step values from inside one
 * tick, and {@link #getAggregateKInfinity()} can re-enter the nodal solve
 * concurrently with the server's own on shared arrays. Writing it off-thread is
 * worse: with no happens-before edge the write may never be published to the tick
 * thread at all, so a scram or a boron demand can simply be lost.
 *
 * <p>{@code mainThread = true} is CC's own mechanism for exactly this. The
 * generated wrapper queues the call as a main-thread task and yields the Lua
 * coroutine until the server tick runs it, so every method below executes on the
 * tick thread, sees a consistent core, and needs no lock, no volatile and no
 * snapshot. The cost is one tick of latency per call, which is why the bulk
 * readouts ({@link #getStatus()}, {@link #getReactivity()},
 * {@link #getRodPositions()}) exist — a control loop should pull one table rather
 * than thirty scalars.
 *
 * <p>The one exception is {@link #getTopOfActiveFuel()}, whose whole body is a
 * compile-time constant. Anything added here that reads or writes plant state
 * must be {@code mainThread = true}.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 */
public class ReactorPeripheral implements IPeripheral {

    private final ReactorControllerBlockEntity be;

    public ReactorPeripheral(ReactorControllerBlockEntity be) {
        this.be = be;
    }

    @LuaFunction(mainThread = true)
    public double getRecirculationCapacity() { return be.getRecirculationCapacityFraction(); }

    @LuaFunction(mainThread = true)
    public int getConnectedJetPairs() { return be.getConnectedJetPairs(); }

    @LuaFunction(mainThread = true)
    public int getInstalledInternalPumps() { return be.getInstalledInternalPumps(); }

    /** Read-only volume-derived targets for normal jets and full-speed external drives. */
    @LuaFunction(mainThread = true)
    public java.util.Map<String,Object> getRecirculationSizing() {
        var info=dev.bwr.mod.gui.ReactorConfigurationInfo.capture(be);
        return java.util.Map.of("interiorVolumeBlocks",info.interiorVolume(),
                "requiredJetAssemblies",info.requiredJets(),"requiredExternalPumps",info.requiredExternalPumps(),
                "jetAssembliesPerPump",dev.bwr.core.flow.RecirculationSizing.JETS_PER_EXTERNAL_PUMP,
                "requiredFlowKgPerS",info.requiredFlowKgPerS(),"availableFlowKgPerS",info.flowCeilingKgPerS(),
                "matchedJetAssemblies",info.matchedJetAssemblies(),"unmatchedJetAssemblies",info.unmatchedJetAssemblies(),
                "externalPumps",info.externalPumps(),"internalPumps",info.internalPumps());
    }

    @Override
    public String getType() {
        return "bwr_reactor";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ReactorPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    /**
     * The physics instance, or a Lua-visible error if this controller has never
     * assembled a vessel.
     *
     * <p>{@link LuaException} and not {@code IllegalStateException}: CC turns an
     * unchecked Java exception into the string "Java Exception Thrown:
     * java.lang.IllegalStateException", which tells a player nothing and cannot
     * usefully be matched on. A {@code LuaException} arrives as a plain Lua
     * error, so {@code pcall(reactor.getPressure)} yields
     * {@code false, "reactor is not formed"} and a control program can branch on
     * it. Every reading on this interface can hit this, because a vessel block
     * can be broken at any moment.
     */
    private ReactorCore core() throws LuaException {
        ReactorCore c = be.core();
        if (c == null) {
            throw new LuaException("reactor is not formed");
        }
        return c;
    }

    /**
     * The control rod drive network, or a Lua-visible error.
     *
     * <p><b>Rod demands live here, not in the core.</b>
     * {@code ControlRodDriveNetwork.preStep} runs immediately before
     * {@code core.step()} and unconditionally rewrites every element of the
     * core's demand array from its own {@code commandedNotchIndex[]} — that is
     * how a drive with no power or no water gets its demand pinned to wherever
     * the rod already is. Anything that writes {@code ReactorCore}'s rod demand
     * directly is therefore erased before the physics ever reads it. Command the
     * network, exactly as {@code ReactorPanelMenu} does.
     */
    private ControlRodDriveNetwork rods() throws LuaException {
        ControlRodDriveNetwork r = be.rods();
        if (r == null) {
            throw new LuaException("reactor is not formed");
        }
        return r;
    }

    /**
     * Reject a non-finite argument with a Lua error that names it.
     *
     * <p>Lua has one number type and produces NaN and infinity trivially —
     * {@code 0/0}, {@code math.huge - math.huge}, or any unguarded division
     * inside a control loop. The physics sanitises silently
     * ({@code ReactorCore.nonNegative} turns NaN into 0.0), so without this a
     * control program with a divide-by-zero in it would watch its demand quietly
     * become zero with no error anywhere and nothing to debug against. Failing
     * the call is the only way the player finds out, and it is a judgement about
     * the <i>argument</i>, never about the plant.
     */
    private static double finite(String what, double value) throws LuaException {
        if (!Double.isFinite(value)) {
            throw new LuaException(what + " must be a finite number, got " + value);
        }
        return value;
    }

    /**
     * Convert a Lua-facing rod number — 1-based, the way the Full Core Display
     * numbers them — into an array index, refusing anything out of range.
     * Without this, {@code getRodPosition(0)} reached the physics as index -1
     * and came back as an ArrayIndexOutOfBoundsException.
     */
    private int rodIndex(int rod) throws LuaException {
        int count = core().getControlRodCount();
        if (rod < 1 || rod > count) {
            throw new LuaException("no rod " + rod + "; this core has " + count
                    + " control rods, numbered 1 to " + count);
        }
        return rod - 1;
    }

    /**
     * Validate an instrument channel index. Channels are 0-based, matching the
     * physics arrays. Out of range is an error rather than a clamp, because
     * silently reading a different detector than the one asked for is exactly
     * the kind of lie this interface must never tell.
     */
    private int channel(String kind, int channel, int count) throws LuaException {
        if (channel < 0 || channel >= count) {
            throw new LuaException("no " + kind + " channel " + channel
                    + "; there are " + count + ", numbered 0 to " + (count - 1));
        }
        return channel;
    }

    // =================================================================
    // Neutronics
    // =================================================================

    @LuaFunction(mainThread = true)
    public final boolean isFormed() {
        return be.isFormed();
    }

    @LuaFunction(mainThread = true)
    public final double getNeutronPower() throws LuaException {
        return core().getNeutronPowerFraction();
    }

    @LuaFunction(mainThread = true)
    public final double getDecayHeat() throws LuaException {
        return core().getDecayHeatFraction();
    }

    @LuaFunction(mainThread = true)
    public final double getTotalPower() throws LuaException {
        return core().getTotalPowerFractionOfRated();
    }

    @LuaFunction(mainThread = true)
    public final double getThermalPowerMW() throws LuaException {
        return core().getTotalPowerFractionOfRated() * PhysicalConstants.RATED_THERMAL_MW;
    }

    /**
     * Reactor period in seconds, derived from a detector signal, not from true
     * power. Source range channel 1, which is the channel this method has
     * always read and goes on reading so that existing programs keep the
     * meaning they were written against.
     *
     * <p><b>It is only usable below about 0.7% power, and above roughly 38% it
     * reads {@code math.huge} for ever.</b> That is not a bug in this method,
     * it is the SRM: the chamber is a paralyzable pulse counter, so its
     * indication peaks at 0.66% rated and then rolls back <i>down</i> toward
     * zero, and a period meter differentiating a falling indication on a
     * climbing core reads a negative period. Once the indicated rate drops
     * under the log amplifier's floor the meter parks at centre scale and
     * indicates an infinite period on a reactor at full power. Both are
     * faithful reporting of a channel that is off its range.
     *
     * <p>So this is the startup instrument, and it is one channel of four.
     * Above the source range the meter that means anything is the one watching
     * a correctly ranged IRM — {@link #getIrmPeriod(int)}. Use
     * {@link #getPeriods()} to pull every channel at once and cross-check them,
     * which is the actual skill this instrumentation exists to demand.
     */
    @LuaFunction(mainThread = true)
    public final double getPeriod() throws LuaException {
        return core().getSourceRangePeriodSeconds(1);
    }

    /**
     * The reactivity breakdown by component, in delta-k/k. A player debugging
     * their own control program needs to see <i>why</i> reactivity moved, not
     * just that it did.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getReactivity() throws LuaException {
        Map<String, Object> m = new HashMap<>();
        ReactorCore c = core();
        m.put("total", c.getReactivityDkOverK());
        m.put("rods", c.getReactivityBalance().getRodsDkOverK());
        m.put("voidTerm", c.getReactivityBalance().getVoidDkOverK());
        m.put("doppler", c.getReactivityBalance().getDopplerDkOverK());
        m.put("xenon", c.getReactivityBalance().getXenonDkOverK());
        m.put("boron", c.getReactivityBalance().getBoronDkOverK());
        m.put("betaEff", c.getBetaEffective());
        m.put("pressure", c.getReactivityBalance().getPressureDkOverK());
        m.put("fuel", c.getReactivityBalance().getFuelDkOverK());
        m.put("dollars", c.getReactivityDollars());
        return m;
    }

    // =================================================================
    // Nuclear instrumentation
    // =================================================================

    /**
     * Source range counts per second.
     *
     * <p>Note that a saturated detector and a dead detector both read zero: the
     * pulse counter is paralyzable, so past its peak the indicated rate falls
     * back toward nothing. Distinguishing the two is the operator's problem,
     * which is what the second channel is for.
     */
    @LuaFunction(mainThread = true)
    public final double getSrmCounts(int channel) throws LuaException {
        return core().getSourceRangeCountsPerSecond(
                channel("source range", channel, ReactorCore.SOURCE_RANGE_CHANNELS));
    }

    @LuaFunction(mainThread = true)
    public final double getIrmReading(int channel) throws LuaException {
        return core().getIntermediateRangeDivisions(
                channel("intermediate range", channel, ReactorCore.INTERMEDIATE_RANGE_CHANNELS));
    }

    @LuaFunction(mainThread = true)
    public final int getIrmRange(int channel) throws LuaException {
        return core().getIntermediateRangeMonitor(
                channel("intermediate range", channel, ReactorCore.INTERMEDIATE_RANGE_CHANNELS))
                .getRange();
    }

    /** Manual ranging is mandatory. Nothing in this mod auto-ranges an IRM. */
    @LuaFunction(mainThread = true)
    public final void setIrmRange(int channel, int range) throws LuaException {
        core().setIntermediateRangeMonitorRange(
                channel("intermediate range", channel, ReactorCore.INTERMEDIATE_RANGE_CHANNELS),
                range);
    }

    @LuaFunction(mainThread = true)
    public final double getAprmPercent() throws LuaException {
        return core().getAveragePowerRangePercent(1);
    }

    // -----------------------------------------------------------------
    // Period meters
    //
    // One per range instrument — four on the SRMs, eight on the IRMs — which
    // is what a BWR control room has, and until now eleven of the twelve were
    // unreachable from Lua. The one that was reachable, through getPeriod(),
    // is on an SRM, and an SRM has rolled over and stopped indicating long
    // before the plant reaches a power anyone operates at. A player was
    // therefore asked to fly a startup on period with no period.
    //
    // These are indications, not conclusions. Nothing here says what a period
    // means, what is short, or what to do about one. A short-period alarm, a
    // rod block, a period trip: all of those are chosen numbers, all of them
    // are Lua's, and the cross-check between channels — which is the entire
    // reason there are twelve meters and not one — is a judgement only the
    // player is in a position to make.
    // -----------------------------------------------------------------

    /**
     * The period meter watching one source range channel.
     *
     * <p>Meters are numbered with their channels, 0-based, matching
     * {@link #getSrmCounts(int)} rather than the 1-based rod numbering.
     */
    private PeriodMeter srmPeriodMeter(int channel) throws LuaException {
        return core().getSourceRangePeriodMeter(
                channel("source range", channel, ReactorCore.SOURCE_RANGE_CHANNELS));
    }

    /** The period meter watching one intermediate range channel. */
    private PeriodMeter irmPeriodMeter(int channel) throws LuaException {
        return core().getIntermediateRangePeriodMeter(
                channel("intermediate range", channel, ReactorCore.INTERMEDIATE_RANGE_CHANNELS));
    }

    /**
     * Indicated period from one source range channel, seconds.
     *
     * <p>Positive while that channel's indication is rising, negative while it
     * is falling, and {@code math.huge} when it is steady — steady flux really
     * is an infinite period and the centre of a real meter's scale, so
     * {@code math.huge} is a reading here and not an error. It is also what a
     * dead channel and a rolled-over channel both indicate, which is why
     * {@link #getSrmCounts(int)} and the other channels exist.
     */
    @LuaFunction(mainThread = true)
    public final double getSrmPeriod(int channel) throws LuaException {
        return srmPeriodMeter(channel).getPeriodSeconds();
    }

    /**
     * Indicated period from one intermediate range channel, seconds.
     *
     * <p>This is the instrument a startup is actually flown on once the SRMs
     * have rolled over, and it is only as good as the range the operator has
     * selected: an IRM pegged at the top of its range has a constant
     * indication, so its period meter reads {@code math.huge} while power
     * doubles every few seconds. Nothing auto-ranges an IRM in this mod — see
     * {@link #setIrmRange(int, int)} — so keeping the channel on scale is part
     * of the job, and {@link #getIrmReading(int)} is how a program checks.
     */
    @LuaFunction(mainThread = true)
    public final double getIrmPeriod(int channel) throws LuaException {
        return irmPeriodMeter(channel).getPeriodSeconds();
    }

    /**
     * Meter deflection of one source range period meter, {@code 1/T} per
     * second.
     *
     * <p>The same measurement as {@link #getSrmPeriod(int)} and the more
     * useful one to compute with: it is zero at steady flux instead of
     * infinite, and it stays finite and continuous straight through the centre
     * of the scale where the period changes sign. A control program that
     * differentiates, averages or plots a period wants this; a display that
     * shows the operator a number in seconds wants the other.
     */
    @LuaFunction(mainThread = true)
    public final double getSrmInversePeriod(int channel) throws LuaException {
        return srmPeriodMeter(channel).getInversePeriodPerSecond();
    }

    /** Meter deflection of one intermediate range period meter, {@code 1/T} per second. */
    @LuaFunction(mainThread = true)
    public final double getIrmInversePeriod(int channel) throws LuaException {
        return irmPeriodMeter(channel).getInversePeriodPerSecond();
    }

    /**
     * Startup rate from one source range channel, decades per minute — the
     * same indication as the period, in the unit startup procedures are
     * written in. Finite everywhere, and zero at steady flux.
     */
    @LuaFunction(mainThread = true)
    public final double getSrmStartupRate(int channel) throws LuaException {
        return srmPeriodMeter(channel).getStartupRateDecadesPerMinute();
    }

    /** Startup rate from one intermediate range channel, decades per minute. */
    @LuaFunction(mainThread = true)
    public final double getIrmStartupRate(int channel) throws LuaException {
        return irmPeriodMeter(channel).getStartupRateDecadesPerMinute();
    }

    /**
     * Where one source range period meter's pointer is:
     * {@code "ONSCALE"}, {@code "UPSCALE"} or {@code "INOPERATIVE"}.
     *
     * <p>A fact about the movement, not about the core. {@code UPSCALE} means
     * the period is shorter than the scale can show in <i>either</i> direction,
     * because both ends of this movement are its extremes; {@code INOPERATIVE}
     * means the channel it is selected to has lost high voltage. An infinite
     * period is {@code ONSCALE} — it is the centre line.
     */
    @LuaFunction(mainThread = true)
    public final String getSrmPeriodMeterStatus(int channel) throws LuaException {
        return srmPeriodMeter(channel).getStatus().name();
    }

    /** Where one intermediate range period meter's pointer is. */
    @LuaFunction(mainThread = true)
    public final String getIrmPeriodMeterStatus(int channel) throws LuaException {
        return irmPeriodMeter(channel).getStatus().name();
    }

    /**
     * Every period meter in the plant in one call, so a program can cross-check
     * channels without paying a server tick of latency per reading.
     *
     * <p>Two sub-tables, {@code srm} and {@code irm}, keyed by channel number
     * from 0. Each entry carries {@code period} (seconds, {@code math.huge} at
     * steady flux), {@code inversePeriod} ({@code 1/T} per second, finite
     * everywhere), {@code startupRate} (decades per minute), {@code status} and
     * {@code designation} — the meter's panel identifier, e.g.
     * {@code "PERIOD IRM C"}, so a program can label what it is showing the way
     * the panel does.
     *
     * <p>The channels disagreeing is the interesting case and the reason this
     * returns all twelve rather than a plant period. Two SRMs past rollover
     * reading a strong negative period while four IRMs on the right range read
     * a steady +45 s is a core doing exactly one thing and six instruments
     * describing it honestly. Working out which of them to believe is the
     * operator's job, so nothing here votes, averages or picks.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getPeriods() throws LuaException {
        ReactorCore c = core();
        Map<Integer, Object> srm = new HashMap<>();
        for (int i = 0; i < ReactorCore.SOURCE_RANGE_CHANNELS; i++) {
            srm.put(i, periodMeterTable(c.getSourceRangePeriodMeter(i)));
        }
        Map<Integer, Object> irm = new HashMap<>();
        for (int i = 0; i < ReactorCore.INTERMEDIATE_RANGE_CHANNELS; i++) {
            irm.put(i, periodMeterTable(c.getIntermediateRangePeriodMeter(i)));
        }
        Map<String, Object> m = new HashMap<>();
        m.put("srm", srm);
        m.put("irm", irm);
        return m;
    }

    /** One period meter as a Lua table. See {@link #getPeriods()}. */
    private static Map<String, Object> periodMeterTable(PeriodMeter meter) {
        Map<String, Object> m = new HashMap<>();
        m.put("period", meter.getPeriodSeconds());
        m.put("inversePeriod", meter.getInversePeriodPerSecond());
        m.put("startupRate", meter.getStartupRateDecadesPerMinute());
        m.put("status", meter.getStatus().name());
        m.put("designation", meter.getDesignation());
        return m;
    }

    // =================================================================
    // Control rods
    // =================================================================

    /** Stable drive IDs and world coordinates; fuel slots use the GUI's lattice indices. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getCoreLayout() throws LuaException {
        if (!be.isFormed()) throw new LuaException("reactor is not formed");
        var structure = be.structure();
        Map<Integer, Object> drives = new HashMap<>();
        var mapping = structure.rodLatticeMap(structure.latticeWidth());
        for (int r = 0; r < structure.controlRodCount(); r++) {
            var position = structure.crdPositions().get(r);
            Map<Integer, Integer> fuel = new HashMap<>();
            int[] slots = mapping.positionsOfRod(r);
            for (int i = 0; i < slots.length; i++) fuel.put(i+1, slots[i]);
            drives.put(r+1, Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ(), "fuelSlots", fuel));
        }
        Map<Integer, Integer> slots = new HashMap<>();
        int[] positions = be.corePositions();
        for (int i = 0; i < positions.length; i++) slots.put(i+1, positions[i]);
        return Map.of("version", be.coreLayoutVersion(), "assemblies", positions.length,
                "latticeWidth", structure.latticeWidth(), "drives", drives, "fuelSlots", slots);
    }

    /** Notch positions as the Full Core Display reads them: 00 to 48, in twos. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Integer> getRodPositions() throws LuaException {
        ReactorCore c = core();
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < c.getControlRodCount(); i++) {
            m.put(i + 1, c.getRodNotchLabel(i));
        }
        return m;
    }

    @LuaFunction(mainThread = true)
    public final int getRodPosition(int rod) throws LuaException {
        return core().getRodNotchLabel(rodIndex(rod));
    }

    /**
     * Command one rod to a notch position, 00 to 48 in twos.
     *
     * <p>Goes through the drive network and <b>not</b> through
     * {@code ReactorCore.setRodNotchLabelDemand}. See {@link #rods()}: the
     * network rewrites the core's demand array from its own commanded pattern on
     * every tick immediately before the physics reads it, so a demand written
     * straight into the core is destroyed before it moves anything. This is the
     * same call {@code ReactorPanelMenu} makes for the panel, so the panel and
     * Lua now share one authority over rod demand instead of one of them
     * silently losing.
     */
    @LuaFunction(mainThread = true)
    public final void setRodPosition(int rod, int notchLabel) throws LuaException {
        int index = rodIndex(rod);
        try {
            rods().setNotchLabelDemand(index, notchLabel);
        } catch (IllegalArgumentException e) {
            // The network rejects a label that is not one of the 25 detents.
            throw new LuaException("bad rod position " + notchLabel + ": " + e.getMessage());
        }
    }

    /**
     * The notch position demanded for one rod, 00 to 48.
     *
     * <p>Distinct from {@link #getRodPosition(int)}, which is where the rod
     * actually is. A rod whose drive has lost power or water sits still while
     * this goes on reading the demand the operator entered — which is exactly
     * what a control room sees when a drive will not answer, and the only way a
     * program can tell "still travelling" from "not moving".
     */
    @LuaFunction(mainThread = true)
    public final int getRodDemand(int rod) throws LuaException {
        return rods().getCommandedNotchLabel(rodIndex(rod));
    }

    /** The whole demanded pattern, rod number to notch label. */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Integer> getRodDemands() throws LuaException {
        ControlRodDriveNetwork r = rods();
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < r.getControlRodCount(); i++) {
            m.put(i + 1, r.getCommandedNotchLabel(i));
        }
        return m;
    }

    /**
     * Rods sitting somewhere other than the notch demanded for them. Non-zero
     * during normal rod motion, and non-zero and <i>staying</i> non-zero when a
     * drive is not answering. A count, not a verdict.
     */
    @LuaFunction(mainThread = true)
    public final int getRodsNotAtDemand() throws LuaException {
        return rods().getRodsNotAtDemandCount();
    }

    @LuaFunction(mainThread = true)
    public final int getRodCount() throws LuaException {
        return core().getControlRodCount();
    }

    /**
     * How many rods will actually insert if scrammed right now.
     * {@code SPEC.md} section 3.3 calls this out as a required readout. It is a
     * count of hardware capability, not an opinion about whether it is enough.
     */
    @LuaFunction(mainThread = true)
    public final int getChargedAccumulators() throws LuaException {
        return core().getChargedAccumulatorCount();
    }

    @LuaFunction(mainThread = true)
    public final double getAccumulatorCharge(int rod) throws LuaException {
        return core().getAccumulatorCharge(rodIndex(rod));
    }

    @LuaFunction(mainThread = true)
    public final boolean isCrdPowered() throws LuaException {
        return core().isCrdPowered();
    }

    @LuaFunction(mainThread = true)
    public final boolean isCrdWaterSupplied() throws LuaException {
        return core().isCrdWaterSupplied();
    }

    /**
     * Insert all rods from stored accumulator pressure.
     *
     * <p>Unconditional and immediate. It performs no checks of any kind, because
     * deciding when to scram is the whole job this mod leaves to the player.
     *
     * <p>Fired through the drive network when there is one, for the same reason
     * {@link #setRodPosition} is: the network holds the commanded pattern, and
     * driving it to fully-inserted here is what stops {@link #resetScram()} from
     * immediately withdrawing every rod back to whatever pattern was standing
     * when the scram fired. This mirrors {@code ReactorPanelMenu} exactly.
     */
    @LuaFunction(mainThread = true)
    public final void scram() throws LuaException {
        ControlRodDriveNetwork r = be.rods();
        if (r != null) {
            r.scram();
        } else {
            core().scram();
        }
    }

    @LuaFunction(mainThread = true)
    public final void resetScram() throws LuaException {
        ControlRodDriveNetwork r = be.rods();
        if (r != null) {
            r.resetScram();
        } else {
            core().resetScram();
        }
    }

    @LuaFunction(mainThread = true)
    public final boolean isScramActive() throws LuaException {
        return core().isScramActive();
    }

    // =================================================================
    // Fuel (SPEC 2.1, 2.3)
    // =================================================================

    /** Flux-weighted k-infinity of the fuel actually loaded, dimensionless. */
    @LuaFunction(mainThread = true)
    public final double getAggregateKInfinity() throws LuaException {
        return core().getAggregateKInfinity();
    }

    /**
     * Multiplication factor of the loaded fuel with every rod withdrawn, cold
     * and clean. Excludes rods, voids, Doppler, xenon and boron — this is what
     * is left in the fuel, not what the plant is doing right now.
     */
    @LuaFunction(mainThread = true)
    public final double getKEffectiveAllRodsOut() throws LuaException {
        return core().getKEffectiveAllRodsOut();
    }

    /**
     * End of cycle: the fuel can no longer reach criticality with every rod
     * withdrawn.
     *
     * <p>A measurement of the fuel, not an instruction and not a trip. An
     * operating core loses margin to xenon, voids and fuel temperature long
     * before this reads true, so a program that waits for it has already been
     * fighting for reactivity for a while. Watching
     * {@link #getKEffectiveAllRodsOut()} fall towards 1 and deciding when to
     * take the outage is the player's job, as always.
     */
    @LuaFunction(mainThread = true)
    public final boolean isEndOfCycle() throws LuaException {
        return core().isEndOfCycle();
    }

    /** Core-average exposure, MWd per tonne of heavy metal. Mass-weighted. */
    @LuaFunction(mainThread = true)
    public final double getAverageBurnup() throws LuaException {
        return core().getAverageBurnupMwdPerTonne();
    }

    /** Highest exposure in the core, MWd/tonne — the bundle a shuffle moves first. */
    @LuaFunction(mainThread = true)
    public final double getPeakBurnup() throws LuaException {
        return core().getCoreLoading().peakBurnupMwdPerTonne();
    }

    /** How many lattice positions hold an assembly. */
    @LuaFunction(mainThread = true)
    public final int getLoadedAssemblyCount() throws LuaException {
        return core().getCoreLoading().loadedAssemblyCount();
    }

    // =================================================================
    // Thermal hydraulics
    // =================================================================

    @LuaFunction(mainThread = true)
    public final double getPressure() throws LuaException {
        return core().getPressurePsig();
    }

    @LuaFunction(mainThread = true)
    public final double getCoolantTemperature() throws LuaException {
        return core().getCoolantTemperatureC();
    }

    @LuaFunction(mainThread = true)
    public final double getFuelTemperature() throws LuaException {
        return core().getFuelTemperatureC();
    }

    @LuaFunction(mainThread = true)
    public final double getCladTemperature() throws LuaException {
        return core().getCladTemperatureC();
    }

    /** Peak cladding temperature ever reached. Monotonic; drives quench branching. */
    @LuaFunction(mainThread = true)
    public final double getPeakCladTemperature() throws LuaException {
        return core().getPeakCladTemperatureC();
    }

    /** Cumulative zirconium oxidation, 0..1. Monotonic. */
    @LuaFunction(mainThread = true)
    public final double getOxidationFraction() throws LuaException {
        return core().getOxidationFraction();
    }

    /**
     * Hydrogen produced by the Zr-water reaction so far, kilograms.
     *
     * <p>A plain cumulative mass, reported whether or not anyone is watching.
     * Nothing here says what number is dangerous, nothing inerts a containment
     * and nothing vents: 4% by volume is the deflagration limit in air and
     * deciding what to do about that is the whole point of writing the control
     * program yourself.
     */
    @LuaFunction(mainThread = true)
    public final double getHydrogenKg() throws LuaException {
        return core().getHydrogenGeneratedKg();
    }

    @LuaFunction(mainThread = true)
    public final double getVoidFraction() throws LuaException {
        return core().getVoidFraction();
    }

    /** Indicated vessel level, inches on the instrument-zero scale. */
    @LuaFunction(mainThread = true)
    public final double getWaterLevel() throws LuaException {
        return core().getIndicatedLevelIn();
    }

    /**
     * Top of active fuel on the same scale, so a player can compute their own
     * margin.
     *
     * <p>The one method on this interface that is not {@code mainThread = true}:
     * its entire body is a compile-time constant, so there is nothing for the
     * server tick to be consistent with.
     */
    @LuaFunction
    public final double getTopOfActiveFuel() {
        return PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN;
    }

    @LuaFunction(mainThread = true)
    public final double getCoreFlow() throws LuaException {
        return core().getCoreFlowFraction();
    }

    /** Commands connected recirculation motors toward a requested fraction of rated core flow.
     * Actual delivery remains limited by electrical supply, motor speed, and installed jets. */
    @LuaFunction(mainThread = true)
    public final void setRecirculationFlow(double fraction) throws LuaException {
        core();
        be.setRecirculationDemand(finite("recirculation flow fraction", fraction));
    }

    /**
     * The recirculation flow fraction the core has been told to hold, as
     * distinct from {@link #getCoreFlow()}, which is where the flow has actually
     * lagged to. Comparing the two against what
     * {@link #setRecirculationFlow(double)} was given is how a program discovers
     * that the pump blocks are the ones driving this channel.
     */
    @LuaFunction(mainThread = true)
    public final double getRecirculationFlowDemand() throws LuaException {
        return core().getRecirculationFlowFractionDemand();
    }

    @LuaFunction(mainThread = true)
    public final double getSteamGeneration() throws LuaException {
        return core().getSteamGenerationKgPerS();
    }

    // =================================================================
    // Steam and feedwater
    // =================================================================

    /**
     * Steam leaving the vessel through the RPV main steam nozzles, kg/s — the
     * total the controller summed on its last tick.
     *
     * <p>The nozzles are the principal steam path out of a vessel, and until now
     * their total was computed every tick, written into the core's discharge
     * scalar and quoted in the controller's status text without ever being
     * published to Lua. A program running pressure on the nozzles had to poll
     * every {@code bwr_rpv_steam_outlet} peripheral and add them up itself, at a
     * tick of latency each, to learn a number the controller already had.
     *
     * <p>This is only the nozzle path. It does not include the relief valves
     * ({@code bwr_safety_relief_valve}), the turbine outlet
     * ({@link #setTurbineSteamFlow(double)} or {@code bwr_turbine_steam_outlet}),
     * boundary breaks ({@link #getBreakSteamFlow()}), or an open vessel head —
     * the controller folds the last of those into the same core channel, and
     * separating the two is not something the vessel model can do.
     */
    @LuaFunction(mainThread = true)
    public final double getSteamOutletFlow() throws LuaException {
        // core() only to make an unformed reactor fail the way every other
        // reading here does. The controller stops refreshing this figure the
        // moment the vessel comes apart, so quoting it on an unformed plant
        // would be quoting whatever the plant was doing when it broke.
        core();
        return be.steamOutletFlowKgPerS();
    }

    @LuaFunction(mainThread = true)
    public final void setTurbineSteamFlow(double kgPerS) throws LuaException {
        core().setTurbineSteamFlowKgPerS(finite("turbine steam flow", kgPerS));
    }

    @LuaFunction(mainThread = true)
    public final void setBypassSteamFlow(double kgPerS) throws LuaException {
        core().setBypassSteamFlowKgPerS(finite("bypass steam flow", kgPerS));
    }

    /**
     * Command feedwater flow directly, kg/s.
     *
     * <h2>Read this before wondering why your write did nothing</h2>
     * On a plant with <b>no feed pumps built</b>, this is the feedwater system:
     * the number written here is what the vessel receives, and it holds. That is
     * how the plant behaved before {@code SPEC.md} section 15's hardware existed,
     * and it goes on behaving that way for players who have not built any.
     *
     * <p>The moment a {@code bwr:motor_feed_pump} or {@code bwr:turbine_feed_pump}
     * is within reach of this reactor, the pumps own the channel and overwrite
     * this every tick, exactly as the ECCS machines own the injection channel.
     * That is the right answer — what reaches the vessel should be what the pumps
     * actually delivered, against the head they were actually working into — but
     * it means a feedwater manoeuvre that has to <i>hold</i> is written against
     * the pump peripherals and not against this method. Command them, do not race
     * them.
     *
     * <p>Feedwater temperature moves with the pumps too, and is not settable from
     * here at all: the heater string is driven by total plant feedwater flow, so
     * it is computed where that total is known.
     */
    @LuaFunction(mainThread = true)
    public final void setFeedwaterFlow(double kgPerS) throws LuaException {
        core().setFeedwaterFlowKgPerS(finite("feedwater flow", kgPerS));
    }

    @LuaFunction(mainThread = true)
    public final double getFeedwaterFlow() throws LuaException {
        return core().getFeedwaterFlowKgPerS();
    }

    /**
     * Feedwater that actually reaches the vessel, kg/s. Differs from
     * {@link #getFeedwaterFlow()} — which is what the pumps were told to do —
     * once the feedwater line has broken. A control program comparing the two
     * is the only way to notice that failure early, and writing that comparison
     * is the player's job.
     */
    @LuaFunction(mainThread = true)
    public final double getDeliveredFeedwaterFlow() throws LuaException {
        return core().getDeliveredFeedwaterFlowKgPerS();
    }

    // =================================================================
    // Pressure boundary condition — SPEC section 7
    //
    // The overpressure damage model is the only thing in this plant that
    // happens without the player asking, so it is published in full. Every
    // value is a fact about metal: life used, seconds spent over design
    // pressure, the current per-second failure rate, what is already broken.
    // None of them is an opinion, nothing in the mod reacts to any of them,
    // and there is deliberately no threshold anywhere in this surface. Writing
    // the alarm is Lua's job, which is exactly why the raw stress fraction is
    // exposed instead of a traffic light.
    // =================================================================

    /** The whole damage model as one table. See {@code BoundaryDamageReadout}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getBoundaryDamage() throws LuaException {
        return BoundaryDamageReadout.toLua(core().getBoundaryStress());
    }

    /**
     * Fraction of one component's fatigue life used up, where 1.0 means it can
     * begin to fail. Key is the lowercase component name, e.g.
     * {@code "recirculation_line"}.
     */
    @LuaFunction(mainThread = true)
    public final double getBoundaryStress(String component) throws LuaException {
        BoundaryStress boundary = core().getBoundaryStress();
        for (BoundaryComponent c : boundary.getPlantConfiguration().exposedComponents()) {
            if (BoundaryDamageReadout.key(c).equalsIgnoreCase(component)) {
                return boundary.getStressFraction(c);
            }
        }
        throw new LuaException("no such pressure boundary component on this plant: "
                + component);
    }

    /** Total per-second probability rate that something in the boundary lets go. */
    @LuaFunction(mainThread = true)
    public final double getBoundaryFailureRate() throws LuaException {
        return core().getBoundaryStress().getFailureRatePerSecond();
    }

    /** Steam leaving through boundary breaks, kg/s. Zero on an intact plant. */
    @LuaFunction(mainThread = true)
    public final double getBreakSteamFlow() throws LuaException {
        return core().getBreakSteamFlowKgPerS();
    }

    /** Water leaving through boundary breaks, kg/s. Zero on an intact plant. */
    @LuaFunction(mainThread = true)
    public final double getBreakLiquidFlow() throws LuaException {
        return core().getBreakLiquidFlowKgPerS();
    }

    // =================================================================
    // Emergency systems
    // =================================================================

    @LuaFunction(mainThread = true)
    public final void setInjectionFlow(double kgPerS) throws LuaException {
        core().setInjectionFlowKgPerS(finite("injection flow", kgPerS));
    }

    @LuaFunction(mainThread = true)
    public final double getInjectionFlow() throws LuaException {
        return core().getInjectionFlowKgPerS();
    }

    /**
     * Core spray. Distinct from injection: spray cools uncovered fuel directly,
     * whereas injection has to refill the vessel first.
     */
    @LuaFunction(mainThread = true)
    public final void setCoreSprayFlow(double kgPerS) throws LuaException {
        core().setCoreSprayFlowKgPerS(finite("core spray flow", kgPerS));
    }

    /** Spray capacity available, scaled by how complete the sparger rings are. */
    @LuaFunction(mainThread = true)
    public final double getSprayRingCompleteness() {
        return be.sprayRingCompleteness();
    }

    @LuaFunction(mainThread = true)
    public final void setBoronInjection(double ppmPerMinute) throws LuaException {
        core().setBoronInjectionPpmPerMinute(finite("boron injection rate", ppmPerMinute));
    }

    @LuaFunction(mainThread = true)
    public final double getBoronPpm() throws LuaException {
        return core().getBoronPpm();
    }

    // =================================================================
    // Plant state
    // =================================================================

    @LuaFunction(mainThread = true)
    public final String getVesselState() {
        return be.vesselState().getSerializedName();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() throws LuaException {
        Map<String, Object> m = new HashMap<>();
        m.put("formed", be.isFormed());
        if (!be.isFormed()) {
            return m;
        }
        ReactorCore c = core();
        m.put("power", c.getTotalPowerFractionOfRated());
        m.put("thermalMW", c.getTotalPowerFractionOfRated() * PhysicalConstants.RATED_THERMAL_MW);
        m.put("pressure", c.getPressurePsig());
        m.put("level", c.getIndicatedLevelIn());
        m.put("flow", c.getCoreFlowFraction());
        m.put("voidFraction", c.getVoidFraction());
        m.put("fuelTemp", c.getFuelTemperatureC());
        m.put("cladTemp", c.getCladTemperatureC());
        m.put("peakCladTemp", c.getPeakCladTemperatureC());
        m.put("oxidation", c.getOxidationFraction());
        m.put("hydrogenKg", c.getHydrogenGeneratedKg());
        m.put("boron", c.getBoronPpm());
        m.put("kInfinity", c.getAggregateKInfinity());
        m.put("kEffAllRodsOut", c.getKEffectiveAllRodsOut());
        m.put("endOfCycle", c.isEndOfCycle());
        m.put("averageBurnup", c.getAverageBurnupMwdPerTonne());
        m.put("chargedAccumulators", c.getChargedAccumulatorCount());
        m.put("rodCount", c.getControlRodCount());
        m.put("scram", c.isScramActive());
        m.put("vessel", be.vesselState().getSerializedName());
        return m;
    }
}
