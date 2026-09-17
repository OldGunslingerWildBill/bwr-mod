package dev.bwr.mod.eccs;

import net.neoforged.neoforge.energy.EnergyStorage;

/**
 * The Forge Energy face of a machine with a motor: accepts energy and never
 * gives it back.
 *
 * <p>Sized from the machine's real motor rating through {@link EccsPower}, so a
 * turbine-driven machine genuinely <i>refuses</i> electricity rather than
 * politely not needing it — a cable run to RCIC or to a turbine-driven feed
 * pump visibly does nothing, which is the correct lesson to teach.
 *
 * <p>The buffer holds one second of the nameplate draw. That is deliberate and
 * it is what makes a brownout look like a brownout: a machine whose supply
 * cannot sustain its rating runs down its buffer, then runs at whatever the
 * supply carries, rather than stopping dead the first tick it is short.
 *
 * <p>Extracted from {@code EccsPumpBlockEntity}, where it was a private nested
 * class, when the reactor feed pumps needed exactly the same face. One copy
 * on purpose: two would drift, and the second copy is always the one that
 * misses the fix.
 */
public final class MachineEnergy extends EnergyStorage {

    /**
     * @param fePerTick the machine's nameplate draw at full load, FE per tick.
     *                  Zero on a turbine drive, which is what makes it refuse
     *                  everything offered
     */
    public MachineEnergy(int fePerTick) {
        super(Math.max(1, fePerTick * 20), Math.max(0, fePerTick), 0);
    }

    /** Spend what the motor actually drew this tick. */
    public void drain(int amount) {
        this.energy = Math.max(0, this.energy - amount);
    }

    /** Restore the buffer from NBT, clamped to what this machine can hold. */
    public void setStored(int stored) {
        this.energy = Math.max(0, Math.min(this.capacity, stored));
    }
}
