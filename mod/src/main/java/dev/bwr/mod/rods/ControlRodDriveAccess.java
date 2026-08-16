package dev.bwr.mod.rods;

/**
 * What the rest of the mod is allowed to know about one control rod drive.
 *
 * <p>Implemented by {@code ControlRodDriveBlockEntity}, which is a Minecraft
 * block entity; this interface is deliberately pure Java so that
 * {@link ControlRodDriveNetwork} and the CC:Tweaked readout layer can be written
 * and tested without Minecraft on the classpath.
 *
 * <p>Everything here is a measurement of hardware condition. There is no
 * {@code isReadyToScram()} and there never will be — the numbers are published,
 * and the player's Lua decides what they mean. {@code SPEC.md} section 3.3 does
 * require one derived count, "how many rods will actually insert if I scram
 * right now", and that lives on {@link ControlRodDriveNetwork} where the
 * accumulator charge and the dome pressure that decide it are both in scope. It
 * is a count of hardware capability, not an opinion about whether the count is
 * enough.
 */
public interface ControlRodDriveAccess {

    /**
     * Which control rod this drive operates, as an index into the core's rod
     * arrays, or -1 if the drive has not been bound to a rod by structure
     * validation.
     */
    int rodIndex();

    /** Stored hydraulic charge, 0 to 1. A full charge buys exactly one scram. */
    double accumulatorCharge();

    /**
     * Whether this drive's electrical supply is meeting its draw. Required for
     * normal withdrawal and insertion, and for recharging the accumulator.
     */
    boolean powered();

    /**
     * Whether this drive has its demineralised water supply. Also required, and
     * the non-obvious one: without water the accumulator cannot recharge, so
     * scram capability decays even with the bus energised.
     */
    boolean waterSupplied();

    /**
     * Mechanical condition of the drive, 1.0 as built and 0.0 seized. Degrades
     * from neglect — dry-stroking a collet with no water supply, and cooking in
     * a core that has been allowed to overheat — never from a dice roll.
     */
    double health();

    /** True when the drive is seized and cannot perform normal rod motion. */
    boolean failed();

    /**
     * Whether the drive can index a notch under normal control right now: it
     * needs power, water and a working mechanism, all three.
     *
     * <p>Note what this does <b>not</b> govern. Scram is a separate hydraulic
     * path driven by stored accumulator pressure or by reactor pressure, so a
     * drive that cannot inch a notch under normal control will still slam in on
     * a scram if it has charge or if the vessel is up. That is the fail-safe
     * design ({@code SPEC.md} section 3.3) and it is why the scram readout is
     * computed from charge and pressure rather than from this flag.
     */
    boolean canPerformNormalMotion();

    /** Energy buffered in the drive, FE. */
    double energyStoredFe();

    /** Energy buffer size, FE. */
    double energyCapacityFe();

    /** Draw at this instant, FE per tick. Higher while the accumulator is charging. */
    double energyDrawFePerTick();

    /** Water buffered in the drive's supply line, millibuckets. */
    double waterStoredMb();

    /** Water buffer size, millibuckets. */
    double waterCapacityMb();

    /** Notch position of the rod this drive operates, index 0..24 mirrored from the core. */
    int notchIndex();

    /** Notch position as the Full Core Display label, 00..48 in steps of two. */
    int notchLabel();
}
