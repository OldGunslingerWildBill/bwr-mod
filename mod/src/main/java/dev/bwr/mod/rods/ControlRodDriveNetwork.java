package dev.bwr.mod.rods;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.kinetics.RodWorth;

/**
 * The bridge between the control rod drive blocks in the world and the rod
 * hydraulics inside {@link ReactorCore}. Pure Java; the multiblock controller
 * owns one of these and drives it around its call to {@code core.step()}.
 *
 * <h2>Who owns what, and why it is split this way</h2>
 * The <b>core</b> owns notch position, accumulator charge, scram insertion
 * speed and the pressure assist. The <b>drive blocks</b> own their electrical
 * supply, their water supply and their mechanical condition. This class owns the
 * operator's rod demands and the translation between the two halves. Nothing is
 * modelled twice, which matters because a second copy of the accumulator state
 * would eventually disagree with the first and the charged-accumulator count is
 * the one readout that must never lie.
 *
 * <h2>One authoritative demand array</h2>
 * {@link #preStep} rewrites {@code ReactorCore}'s rod demand from
 * {@link #getCommandedNotchIndices()} on every single tick, so
 * <b>{@code ReactorCore.setRodNotchDemand} / {@code setRodNotchLabelDemand} must
 * never be called from outside this class.</b> A demand written straight into
 * the core survives at most one tick before preStep reverts it, which is exactly
 * how the CC:Tweaked rod commands came to be silently dead while the identical
 * GUI buttons worked. Every control surface — the panel, the peripheral,
 * redstone, anything added later — routes through {@link #setNotchDemand},
 * {@link #setNotchLabelDemand}, {@link #withdraw} or {@link #insert}, the same
 * way both the slider and Lua write one authoritative {@code targetSpeedFraction}
 * on a recirculation pump.
 *
 * <h2>Order of operations</h2>
 * <pre>
 *   network.preStep(dtSeconds);   // supplies -&gt; core: demands, system flags, recharge rate
 *   core.step();                  // physics moves the rods
 *   network.postStep();           // core -&gt; blocks: notch positions, accumulator charges
 * </pre>
 * {@link #preStep} must run before {@code step()} because a drive that lost its
 * water this tick must not be allowed to move this tick.
 *
 * <h2>How a drive comes to be unable to move</h2>
 * The operator's demand for a rod is held <i>here</i>, not in the core, and it
 * is forwarded to the core only while that rod's drive
 * {@linkplain ControlRodDriveHardware#canPerformNormalMotion() can move}. A
 * drive with no power, no water or a seized mechanism has its normal motion
 * disabled, so the rod holds its physical position while the panel
 * goes on showing the demand the operator entered — which is exactly what a
 * control room sees when a drive will not answer.
 *
 * <p><b>Scram is deliberately not gated on any of that.</b> The scram path is
 * stored accumulator pressure, or reactor pressure once the accumulator is
 * spent, pushing the piston with no electrical or control involvement at all.
 * That is the fail-safe design in {@code SPEC.md} section 3.3: loss of the
 * control system drives rods <i>in</i>. So {@link #getScrammableRodCount()} is
 * computed from charge and dome pressure, and matches the core's own rule
 * exactly rather than second-guessing it.
 *
 * <h2>No protection logic lives here</h2>
 * {@link #scram()} is a bare actuator that fires unconditionally. There is no
 * setpoint, no interlock, no rod block, no withdrawal permissive and no
 * sequence-control enforcement anywhere in this class. Rod worth minimiser,
 * shutdown margin checks, and every scram channel the plant has are the
 * player's to write in Lua against the readouts published here.
 */
public final class ControlRodDriveNetwork {

    private ReactorCore core;

    private final ControlRodDriveHardware[] drives;
    private final int[] commandedNotchIndex;

    /**
     * Accumulator recharge rate with every drive supplied, fraction per second.
     * Defaults to the core's own plant-derived figure; the effective rate is
     * this scaled by the fraction of drives that actually have both supplies, so
     * a brownout across the drive bus slows recovery of scram capability instead
     * of stopping it dead.
     */
    private double baseAccumulatorRechargePerSecond =
            ReactorCore.DEFAULT_ACCUMULATOR_RECHARGE_PER_SECOND;

    /**
     * @param core the reactor whose rods these drives operate; may be null until
     *             the multiblock forms
     * @param controlRodCount number of control rods, and therefore of drives
     */
    public ControlRodDriveNetwork(ReactorCore core, int controlRodCount) {
        if (controlRodCount < 1) {
            throw new IllegalArgumentException("a core needs at least one control rod, got "
                    + controlRodCount);
        }
        this.drives = new ControlRodDriveHardware[controlRodCount];
        this.commandedNotchIndex = new int[controlRodCount];
        // Never leave the demand array zero-filled, because 0 is
        // NOTCH_INDEX_FULLY_INSERTED, not "no demand". A fresh network whose
        // demands are all zero drives every rod to the bottom on the first
        // preStep, so simply constructing one used to shut the reactor down —
        // on formation, and again on every chunk reload. setCore() seeds the
        // demand from wherever the rods actually are, which is the only
        // starting demand that moves nothing.
        setCore(core);
    }

    // ---------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------

    /** The core these drives operate on, or null before the multiblock forms. */
    public ReactorCore getCore() {
        return core;
    }

    /**
     * Point the network at a core. Called when the multiblock forms or when a
     * restored core replaces the one built at construction; the commanded
     * pattern is re-synchronised to wherever the new core's rods actually are,
     * so forming a structure never moves a rod on its own.
     *
     * <p><b>Call this after restoring a saved state, not before.</b>
     * {@code ReactorCore.fromState} puts the rods back where they were, and the
     * demand this network holds is what {@link #preStep} pushes into the core
     * every tick — so a network that was synchronised against the pre-restore
     * positions immediately drives the rods away from the pattern the player
     * left. The reactor then shuts itself down with no scram signal to explain
     * it, which is both a lost plant and a violation of the design rule: the
     * mod does not move rods the player did not ask it to move.
     */
    public void setCore(ReactorCore core) {
        this.core = core;
        if (core != null) {
            for (int r = 0; r < drives.length && r < core.getControlRodCount(); r++) {
                commandedNotchIndex[r] = core.getRodNotchIndex(r);
            }
        }
    }

    /**
     * Restore a saved demand pattern, clamping each entry into range and
     * ignoring an array that does not match this network's rod count.
     *
     * <p>Rod demand has no component of its own in {@code ReactorState}, so
     * without this a withdrawal that was in flight when the chunk unloaded comes
     * back collapsed onto the notch the rods had reached: the plant resumes with
     * the drives stopped mid-stroke and nothing on the panel to say why. This is
     * the operator's standing demand being handed back to them, not a decision
     * about where the rods ought to be.
     *
     * @param notchIndices one demand per rod, as {@link #getCommandedNotchIndices()} returns
     * @return true if the pattern was applied
     */
    public boolean restoreCommandedNotchIndices(int[] notchIndices) {
        if (notchIndices == null || notchIndices.length != commandedNotchIndex.length) {
            return false;
        }
        for (int r = 0; r < commandedNotchIndex.length; r++) {
            commandedNotchIndex[r] = Math.max(RodWorth.NOTCH_INDEX_FULLY_INSERTED,
                    Math.min(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN, notchIndices[r]));
        }
        return true;
    }

    /** Number of control rod drive positions this network expects. */
    public int getControlRodCount() {
        return drives.length;
    }

    /**
     * Attach one drive's hardware to a rod position. Structure validation knows
     * the lattice and does the binding; {@code SPEC.md} section 3.3 requires a
     * drive under every rod for the multiblock to form at all, so a null slot
     * here means the structure is incomplete rather than that the rod is
     * driverless by design.
     *
     * @param rodIndex rod this drive operates, 0 based
     * @param hardware the drive, or null to unbind
     */
    public void bind(int rodIndex, ControlRodDriveHardware hardware) {
        checkRodIndex(rodIndex);
        drives[rodIndex] = hardware;
        if (hardware != null) {
            hardware.setRodIndex(rodIndex);
        }
    }

    /** Drop every binding. Used when the multiblock breaks. */
    public void unbindAll() {
        for (int r = 0; r < drives.length; r++) {
            drives[r] = null;
        }
    }

    /** The drive bound to a rod position, or null if none is bound. */
    public ControlRodDriveHardware getDrive(int rodIndex) {
        checkRodIndex(rodIndex);
        return drives[rodIndex];
    }

    /** True when every rod position has a drive bound to it. */
    public boolean isFullyBound() {
        for (ControlRodDriveHardware drive : drives) {
            if (drive == null) {
                return false;
            }
        }
        return true;
    }

    /** Rod positions with no drive bound. Structure validation reports these by coordinate. */
    public int getUnboundDriveCount() {
        int count = 0;
        for (ControlRodDriveHardware drive : drives) {
            if (drive == null) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------

    /**
     * Push supply condition into the core: per-rod demands, the system-level
     * power and water flags, and the accumulator recharge rate. Call once per
     * tick immediately before {@code core.step()}.
     *
     * @param dtSeconds step length, seconds — for symmetry with the core's clock;
     *                  the drives' own supplies are metered by their block
     *                  entities, which tick independently of the multiblock
     */
    public void preStep(double dtSeconds) {
        ReactorCore c = core;
        if (c == null) {
            return;
        }
        int rods = Math.min(drives.length, c.getControlRodCount());
        double cladTemperatureC = c.getCladTemperatureC();
        boolean scramActive = c.isScramActive();

        int operable = 0;
        for (int r = 0; r < rods; r++) {
            ControlRodDriveHardware drive = drives[r];

            if (drive == null) {
                // Nothing under this rod. It cannot be driven, and the core is
                // told to leave it where it is.
                c.setRodNormalMotionAvailable(r,false);
                continue;
            }

            drive.setEnvironmentCladTemperatureC(cladTemperatureC);
            boolean moving = scramActive
                    ? c.getRodPositionNotches(r) != RodWorth.NOTCH_INDEX_FULLY_INSERTED
                    : commandedNotchIndex[r] != c.getRodPositionNotches(r);
            drive.setMotionCommanded(moving);

            if (drive.canPerformNormalMotion()) {
                operable++;
                c.setRodNormalMotionAvailable(r,true);
                c.setRodNotchDemand(r, commandedNotchIndex[r]);
            } else {
                c.setRodNormalMotionAvailable(r,false);
            }
        }

        // The core's power and water flags are system-level: they gate all
        // normal motion and all recharging. Per-drive outages are already
        // handled above by holding each unavailable drive, so the system is "up" as long as
        // something on it is up. The recharge rate then carries the degradation
        // — a bus browning out across the drives recovers scram capability
        // proportionally more slowly, which is the balance knob SPEC 3.3 names.
        boolean anyOperable = operable > 0;
        c.setCrdPowered(anyOperable);
        c.setCrdWaterSupplied(anyOperable);
        c.setAccumulatorRechargePerSecond(
                baseAccumulatorRechargePerSecond * (rods > 0 ? (double) operable / rods : 0.0));
    }

    /**
     * Pull rod position and accumulator charge back out of the core and into the
     * drive blocks, so the panel, the block model and the peripheral all read
     * the same numbers the physics just produced. Call once per tick immediately
     * after {@code core.step()}.
     */
    public void postStep() {
        ReactorCore c = core;
        if (c == null) {
            return;
        }
        int rods = Math.min(drives.length, c.getControlRodCount());
        for (int r = 0; r < rods; r++) {
            ControlRodDriveHardware drive = drives[r];
            if (drive == null) {
                continue;
            }
            drive.setNotchIndex(c.getRodNotchIndex(r));
            drive.setAccumulatorCharge(c.getAccumulatorCharge(r));
        }
        if (c.isScramActive()) {
            // A scram overrides demand in the core. Follow it here too, so that
            // clearing the scram signal does not immediately drive every rod
            // back out to the pattern that was standing when it fired.
            for (int r = 0; r < rods; r++) {
                commandedNotchIndex[r] = RodWorth.NOTCH_INDEX_FULLY_INSERTED;
            }
        }
    }

    // ---------------------------------------------------------------
    // Actuators
    // ---------------------------------------------------------------

    /**
     * <b>SCRAM.</b> Unconditional. Fires the accumulators and drives every rod
     * that can move to the bottom, and checks absolutely nothing first — not
     * power, not period, not pressure, not level, not whether the core is
     * already shut down.
     *
     * <p>Deciding when to call this is the entire content of the player's
     * protection system, written in Lua. The deliberate absence of any condition
     * here is the project.
     */
    public void scram() {
        for (int r = 0; r < commandedNotchIndex.length; r++) {
            commandedNotchIndex[r] = RodWorth.NOTCH_INDEX_FULLY_INSERTED;
        }
        ReactorCore c = core;
        if (c != null) {
            c.scram();
        }
    }

    /**
     * Clear the scram signal so the drives answer withdrawal demands again. As
     * bare as {@link #scram()}: it forms no opinion about whether clearing it is
     * a sensible thing to be doing.
     */
    public void resetScram() {
        ReactorCore c = core;
        if (c != null) {
            c.resetScram();
        }
    }

    /** True while the scram signal is latched in. */
    public boolean isScramActive() {
        ReactorCore c = core;
        return c != null && c.isScramActive();
    }

    /**
     * Command one rod to a notch position by index.
     *
     * @param rodIndex   rod, 0 based
     * @param notchIndex 0 fully inserted to 24 fully withdrawn
     */
    public void setNotchDemand(int rodIndex, int notchIndex) {
        checkRodIndex(rodIndex);
        if (notchIndex < RodWorth.NOTCH_INDEX_FULLY_INSERTED
                || notchIndex > RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN) {
            throw new IllegalArgumentException("notch index out of range 0.."
                    + RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN + ": " + notchIndex);
        }
        commandedNotchIndex[rodIndex] = notchIndex;
    }

    /**
     * Command one rod by its Full Core Display label — 00 fully inserted to 48
     * fully withdrawn, in steps of two. This is the numbering an operator
     * actually reads and types, so it is the one the peripheral leads with.
     *
     * @throws IllegalArgumentException if the label is odd or out of range
     */
    public void setNotchLabelDemand(int rodIndex, int notchLabel) {
        setNotchDemand(rodIndex, RodWorth.notchIndexFromLabel(notchLabel));
    }

    /** Command every rod to the same notch index. The drives are still individual. */
    public void setAllNotchDemand(int notchIndex) {
        for (int r = 0; r < commandedNotchIndex.length; r++) {
            setNotchDemand(r, notchIndex);
        }
    }

    /**
     * Withdraw one rod by a number of notches, clamping at fully withdrawn.
     *
     * <p>The step count is clamped <b>before</b> it is added, not only after.
     * The obvious form, {@code position + Math.max(0, notches)} clamped
     * afterwards, overflows to a large negative number for a step count near
     * {@link Integer#MAX_VALUE}, and {@code Math.min} then happily accepts it —
     * so a hostile or merely buggy packet would write a negative notch index
     * into the demand array and take the server tick down on the next
     * {@code setRodNotchDemand}. The GUI bounds its own input; this bound is
     * here so that every caller is safe, including the ones not written yet.
     *
     * @return the notch index now demanded
     */
    public int withdraw(int rodIndex, int notches) {
        checkRodIndex(rodIndex);
        int steps = Math.min(Math.max(0, notches), RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        int target = Math.min(commandedNotchIndex[rodIndex] + steps,
                RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        commandedNotchIndex[rodIndex] = target;
        return target;
    }

    /**
     * Insert one rod by a number of notches, clamping at fully inserted.
     *
     * <p>The step count is clamped before it is subtracted, for the same reason
     * {@link #withdraw} clamps before it adds.
     *
     * @return the notch index now demanded
     */
    public int insert(int rodIndex, int notches) {
        checkRodIndex(rodIndex);
        int steps = Math.min(Math.max(0, notches), RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        int target = Math.max(commandedNotchIndex[rodIndex] - steps,
                RodWorth.NOTCH_INDEX_FULLY_INSERTED);
        commandedNotchIndex[rodIndex] = target;
        return target;
    }

    /**
     * Accumulator recharge rate with every drive supplied, fraction of full
     * charge per second. {@code SPEC.md} section 3.3 calls this the main balance
     * knob: slow enough that repeated scram-restart cycles cost real time, fast
     * enough that normal operation never gates on it.
     */
    public double getBaseAccumulatorRechargePerSecond() {
        return baseAccumulatorRechargePerSecond;
    }

    /** @see #getBaseAccumulatorRechargePerSecond() */
    public void setBaseAccumulatorRechargePerSecond(double rate) {
        if (rate >= 0.0 && Double.isFinite(rate)) {
            this.baseAccumulatorRechargePerSecond = rate;
        }
    }

    // ---------------------------------------------------------------
    // Measurements
    // ---------------------------------------------------------------

    /** Notch index the operator has demanded for one rod, 0..24. */
    public int getCommandedNotchIndex(int rodIndex) {
        checkRodIndex(rodIndex);
        return commandedNotchIndex[rodIndex];
    }

    /** Notch label the operator has demanded for one rod, 00..48. */
    public int getCommandedNotchLabel(int rodIndex) {
        return RodWorth.notchLabel(getCommandedNotchIndex(rodIndex));
    }

    /** Copy of the demanded pattern, notch indices. */
    public int[] getCommandedNotchIndices() {
        return commandedNotchIndex.clone();
    }

    /** Where a rod actually is, notch index 0..24, or 0 if there is no core. */
    public int getNotchIndex(int rodIndex) {
        checkRodIndex(rodIndex);
        ReactorCore c = core;
        return (c == null) ? 0 : c.getRodNotchIndex(rodIndex);
    }

    /** Where a rod actually is, Full Core Display label 00..48. */
    public int getNotchLabel(int rodIndex) {
        return RodWorth.notchLabel(getNotchIndex(rodIndex));
    }

    /** One drive's accumulator charge, 0 to 1. */
    public double getAccumulatorCharge(int rodIndex) {
        checkRodIndex(rodIndex);
        ReactorCore c = core;
        return (c == null) ? 0.0 : c.getAccumulatorCharge(rodIndex);
    }

    /**
     * Drives holding any accumulator charge at all. This is the raw count of
     * armed accumulators; {@link #getScrammableRodCount()} is the number that
     * answers the question the panel is actually asking.
     */
    public int getChargedAccumulatorCount() {
        ReactorCore c = core;
        return (c == null) ? 0 : c.getChargedAccumulatorCount();
    }

    /**
     * <b>How many rods will actually insert if a scram is fired right now</b> —
     * the readout {@code SPEC.md} section 3.3 requires on the panel.
     *
     * <p>A rod inserts on a scram if its accumulator holds charge, or, once that
     * charge is spent, if the vessel is above
     * {@link ReactorCore#SCRAM_PRESSURE_ASSIST_PSIG} so that reactor pressure
     * alone can push the piston. That is the core's own rule, reproduced here
     * rather than approximated, because a count that disagrees with what the
     * hardware then does is worse than no count.
     *
     * <p>It is a count of hardware capability. It carries no opinion about
     * whether the count is adequate, and there is no threshold anywhere in this
     * class that acts on it. Whether 176 of 177 is a problem is for the player's
     * Lua to decide.
     */
    public int getScrammableRodCount() {
        ReactorCore c = core;
        if (c == null) {
            return 0;
        }
        boolean pressureAssist = c.getPressurePsig() >= ReactorCore.SCRAM_PRESSURE_ASSIST_PSIG;
        if (pressureAssist) {
            return c.getControlRodCount();
        }
        return c.getChargedAccumulatorCount();
    }

    /** Drives that could index a notch under normal control right now. */
    public int getOperableDriveCount() {
        int count = 0;
        for (ControlRodDriveHardware drive : drives) {
            if (drive != null && drive.canPerformNormalMotion()) {
                count++;
            }
        }
        return count;
    }

    /** Drives whose electrical supply is meeting their draw. */
    public int getPoweredDriveCount() {
        int count = 0;
        for (ControlRodDriveHardware drive : drives) {
            if (drive != null && drive.isPowered()) {
                count++;
            }
        }
        return count;
    }

    /** Drives with their demineralised water supply. */
    public int getWaterSuppliedDriveCount() {
        int count = 0;
        for (ControlRodDriveHardware drive : drives) {
            if (drive != null && drive.isWaterSupplied()) {
                count++;
            }
        }
        return count;
    }

    /** Drives whose mechanism has seized. */
    public int getFailedDriveCount() {
        int count = 0;
        for (ControlRodDriveHardware drive : drives) {
            if (drive != null && drive.isFailed()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rods sitting somewhere other than the notch demanded for them. Non-zero
     * during normal rod motion, and non-zero and <i>staying</i> non-zero when a
     * drive is not answering.
     */
    public int getRodsNotAtDemandCount() {
        ReactorCore c = core;
        if (c == null) {
            return 0;
        }
        int rods = Math.min(drives.length, c.getControlRodCount());
        int count = 0;
        for (int r = 0; r < rods; r++) {
            if (c.getRodNotchIndex(r) != commandedNotchIndex[r]) {
                count++;
            }
        }
        return count;
    }

    private void checkRodIndex(int rodIndex) {
        if (rodIndex < 0 || rodIndex >= drives.length) {
            throw new IllegalArgumentException(
                    "rod index out of range 0.." + (drives.length - 1) + ": " + rodIndex);
        }
    }
}
