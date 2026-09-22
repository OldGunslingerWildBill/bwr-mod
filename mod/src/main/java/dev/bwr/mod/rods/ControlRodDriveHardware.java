package dev.bwr.mod.rods;

import dev.bwr.core.PhysicalConstants;

/**
 * The hardware half of one control rod drive: its energy buffer, its water
 * supply, and its mechanical condition. Pure Java, no Minecraft, so it is
 * testable on its own; {@code ControlRodDriveBlockEntity} is a thin shell around
 * an instance of this.
 *
 * <h2>Division of labour with {@code ReactorCore}</h2>
 * The <b>core</b> owns notch positions, accumulator charge and the hydraulics of
 * scram insertion. This class owns the two supplies the drive needs and the wear
 * it accumulates, and it hands the core one boolean per drive:
 * {@link #canPerformNormalMotion()}. Nothing is simulated twice.
 *
 * <p>A CRD needs <b>both</b> electrical power and a pumped demineralised water
 * supply ({@code SPEC.md} section 3.3, confirmed by [TTC 2.3.2]). Power runs the
 * directional control valves; water is the working fluid and also the continuous
 * purge flow into the vessel. Losing either stops normal rod motion. Losing
 * water additionally stops the accumulator recharging, which is the failure mode
 * worth surfacing: <b>scram capability decays even with the bus energised</b>,
 * and by the time it matters the panel has been telling you for an hour.
 *
 * <h2>Wear is a consequence, never a dice roll</h2>
 * {@link #health()} falls in exactly two situations, both of them the player's
 * doing (README principle 3):
 * <ul>
 *   <li><b>Dry stroking.</b> Commanding a drive to move with no water in the
 *       line grinds the collet. {@link #DRY_STROKE_HEALTH_LOSS_PER_SECOND} takes
 *       twenty minutes of continuous commanded-but-dry motion to seize a drive,
 *       which is long enough that it can only happen to someone who is not
 *       looking.</li>
 *   <li><b>Heat.</b> Above {@link PhysicalConstants#ZR_REACTION_ONSET_C} the core
 *       is destroying itself and the drive housings below it go with it.</li>
 * </ul>
 * Neither is random, both are visible on the readout while they are happening,
 * and {@link #repair(double)} undoes them.
 *
 * <p>Nothing in this class has a setpoint or makes a judgement. There is no
 * threshold at which it refuses to run, no alarm and no protective action; it
 * publishes charge, supplies and condition, and the player's Lua decides.
 */
public final class ControlRodDriveHardware {

    // ---------------------------------------------------------------
    // Electrical supply
    // ---------------------------------------------------------------

    /**
     * Energy buffer, FE. About three minutes of idle draw, so a drive rides
     * through a momentary supply interruption instead of dropping out on it.
     */
    public static final double DEFAULT_ENERGY_CAPACITY_FE = 20_000.0;

    /**
     * Standing draw with the accumulator full, FE per second — solenoids,
     * position indication, and the collet holding the index tube where it is.
     * 5 FE/t apiece, so a 177-drive core idles at about 885 FE/t.
     */
    public static final double IDLE_DRAW_FE_PER_SECOND = 100.0;

    /**
     * Additional draw while the accumulator is charging, FE per second. 40 FE/t
     * apiece; a whole core recharging after a scram pulls about 8 kFE/t on top
     * of idle, which is real load but an order of magnitude under one
     * recirculation pump. {@code SPEC.md} section 3.3: cheap individually, adds
     * up across 177 of them, and sharing a bus with the recirculation pumps is
     * how a brownout comes to slow rod motion.
     */
    public static final double CHARGING_DRAW_FE_PER_SECOND = 800.0;

    // ---------------------------------------------------------------
    // Water supply
    // ---------------------------------------------------------------

    /** Water buffer in the drive's supply line, millibuckets. */
    public static final double DEFAULT_WATER_CAPACITY_MB = 4_000.0;

    /**
     * Continuous purge flow through the drive and into the vessel, mB per
     * second. Real CRD hydraulics run demineralised water past the seals
     * permanently to keep reactor water out of the mechanism, so this is
     * consumed whether or not the rod is moving.
     */
    public static final double PURGE_MB_PER_SECOND = 2.0;

    /**
     * Additional water drawn while charging the accumulator, mB per second.
     * Charging is the drive doing hydraulic work; the water goes into the
     * accumulator, not through the purge.
     */
    public static final double CHARGING_MB_PER_SECOND = 30.0;

    // ---------------------------------------------------------------
    // Mechanical condition
    // ---------------------------------------------------------------

    /**
     * Condition lost per second while a drive is commanded to move with no
     * water in the line. 1/1200 per second — twenty minutes of continuous dry
     * stroking to seize one.
     */
    public static final double DRY_STROKE_HEALTH_LOSS_PER_SECOND = 1.0 / 1200.0;

    /**
     * Condition lost per second while cladding temperature is above the
     * zirconium reaction onset. 1/600 per second: if the core is oxidising, the
     * drives have ten minutes.
     */
    public static final double OVERHEAT_HEALTH_LOSS_PER_SECOND = 1.0 / 600.0;

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private int rodIndex = -1;

    private double energyCapacityFe = DEFAULT_ENERGY_CAPACITY_FE;
    private double energyStoredFe = DEFAULT_ENERGY_CAPACITY_FE;
    private double waterCapacityMb = DEFAULT_WATER_CAPACITY_MB;
    private double waterStoredMb = DEFAULT_WATER_CAPACITY_MB;

    private double health = 1.0;

    private boolean powered = true;
    private boolean waterSupplied = true;

    /** Mirrored from the core each tick, for readout and for the wear model. */
    private double accumulatorCharge = 1.0;
    private int notchIndex = 0;
    private boolean motionCommanded = false;
    private double environmentCladTemperatureC = 0.0;

    private double lastEnergyDrawFePerTick;
    private double lastWaterDrawMbPerTick;

    public ControlRodDriveHardware() {
    }

    /** Adjacent manifold sections equalise supplies; this never creates either inventory. */
    public void shareSuppliesWith(ControlRodDriveHardware other) {
        double energy = energyStoredFe + other.energyStoredFe;
        double energyHere = energy * energyCapacityFe / (energyCapacityFe + other.energyCapacityFe);
        energyStoredFe = energyHere;
        other.energyStoredFe = energy - energyHere;
        double water = waterStoredMb + other.waterStoredMb;
        double waterHere = water * waterCapacityMb / (waterCapacityMb + other.waterCapacityMb);
        waterStoredMb = waterHere;
        other.waterStoredMb = water - waterHere;
    }

    // ---------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------

    /**
     * Advance the drive's own hardware one step: pay for the supplies it is
     * using, and accumulate any wear it has earned.
     *
     * <p>Call this once per tick per drive, from the block entity's server
     * ticker, whether or not the multiblock is formed — a drive sitting in an
     * unformed structure still needs its supplies to stay charged, and that is
     * the point of the mechanic.
     *
     * @param dtSeconds step length, seconds
     */
    public void tickHardware(double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return;
        }

        boolean charging = accumulatorCharge < 1.0;

        double energyDemand = (IDLE_DRAW_FE_PER_SECOND
                + (charging ? CHARGING_DRAW_FE_PER_SECOND : 0.0)) * dtSeconds;
        if (energyStoredFe >= energyDemand) {
            energyStoredFe -= energyDemand;
            powered = true;
        } else {
            energyStoredFe = 0.0;
            powered = false;
        }
        lastEnergyDrawFePerTick = energyDemand;

        double waterDemand = (PURGE_MB_PER_SECOND
                + (charging ? CHARGING_MB_PER_SECOND : 0.0)) * dtSeconds;
        if (waterStoredMb >= waterDemand) {
            waterStoredMb -= waterDemand;
            waterSupplied = true;
        } else {
            waterStoredMb = 0.0;
            waterSupplied = false;
        }
        lastWaterDrawMbPerTick = waterDemand;

        if (motionCommanded && !waterSupplied) {
            health -= DRY_STROKE_HEALTH_LOSS_PER_SECOND * dtSeconds;
        }
        if (environmentCladTemperatureC > PhysicalConstants.ZR_REACTION_ONSET_C) {
            health -= OVERHEAT_HEALTH_LOSS_PER_SECOND * dtSeconds;
        }
        if (health < 0.0) {
            health = 0.0;
        }
    }

    // ---------------------------------------------------------------
    // Condition
    // ---------------------------------------------------------------

    /** @see ControlRodDriveAccess#powered() */
    public boolean isPowered() {
        return powered;
    }

    /** @see ControlRodDriveAccess#waterSupplied() */
    public boolean isWaterSupplied() {
        return waterSupplied;
    }

    /** @see ControlRodDriveAccess#health() */
    public double getHealth() {
        return health;
    }

    /** Set mechanical condition directly, 0 to 1. For persistence and for a damage model. */
    public void setHealth(double health) {
        if (!Double.isFinite(health)) {
            return;
        }
        this.health = Math.max(0.0, Math.min(1.0, health));
    }

    /** True when the mechanism is seized. */
    public boolean isFailed() {
        return health <= 0.0;
    }

    /**
     * Power, water and a working mechanism, all three. This gates normal notch
     * motion only — see {@link ControlRodDriveAccess#canPerformNormalMotion()}
     * for why scram is deliberately not gated on it.
     */
    public boolean canPerformNormalMotion() {
        return powered && waterSupplied && !isFailed();
    }

    /**
     * Restore mechanical condition. Maintenance is the only way health goes back
     * up; it does not heal on its own, because the wear that produced it was
     * something the player did.
     *
     * @param amount condition to restore, 0 to 1
     */
    public void repair(double amount) {
        if (!(amount > 0.0) || !Double.isFinite(amount)) {
            return;
        }
        health = Math.min(1.0, health + amount);
    }

    // ---------------------------------------------------------------
    // Supplies
    // ---------------------------------------------------------------

    public double getEnergyStoredFe() {
        return energyStoredFe;
    }

    public double getEnergyCapacityFe() {
        return energyCapacityFe;
    }

    public void setEnergyCapacityFe(double capacityFe) {
        if (capacityFe > 0.0 && Double.isFinite(capacityFe)) {
            this.energyCapacityFe = capacityFe;
            this.energyStoredFe = Math.min(this.energyStoredFe, capacityFe);
        }
    }

    /** Set the buffer contents directly. Persistence and tests. */
    public void setEnergyStoredFe(double storedFe) {
        if (!Double.isFinite(storedFe)) {
            return;
        }
        this.energyStoredFe = Math.max(0.0, Math.min(energyCapacityFe, storedFe));
    }

    /**
     * Accept energy into the buffer.
     *
     * @param offeredFe energy offered, FE
     * @param simulate  true to report what would be accepted without accepting it
     * @return energy accepted, FE
     */
    public double receiveEnergyFe(double offeredFe, boolean simulate) {
        if (!(offeredFe > 0.0) || !Double.isFinite(offeredFe)) {
            return 0.0;
        }
        double accepted = Math.min(offeredFe, energyCapacityFe - energyStoredFe);
        if (accepted <= 0.0) {
            return 0.0;
        }
        if (!simulate) {
            energyStoredFe += accepted;
        }
        return accepted;
    }

    public double getWaterStoredMb() {
        return waterStoredMb;
    }

    public double getWaterCapacityMb() {
        return waterCapacityMb;
    }

    public void setWaterCapacityMb(double capacityMb) {
        if (capacityMb > 0.0 && Double.isFinite(capacityMb)) {
            this.waterCapacityMb = capacityMb;
            this.waterStoredMb = Math.min(this.waterStoredMb, capacityMb);
        }
    }

    /** Set the water line contents directly. Persistence and tests. */
    public void setWaterStoredMb(double storedMb) {
        if (!Double.isFinite(storedMb)) {
            return;
        }
        this.waterStoredMb = Math.max(0.0, Math.min(waterCapacityMb, storedMb));
    }

    /**
     * Accept water into the supply line.
     *
     * @param offeredMb water offered, millibuckets
     * @param simulate  true to report what would be accepted without accepting it
     * @return water accepted, millibuckets
     */
    public double receiveWaterMb(double offeredMb, boolean simulate) {
        if (!(offeredMb > 0.0) || !Double.isFinite(offeredMb)) {
            return 0.0;
        }
        double accepted = Math.min(offeredMb, waterCapacityMb - waterStoredMb);
        if (accepted <= 0.0) {
            return 0.0;
        }
        if (!simulate) {
            waterStoredMb += accepted;
        }
        return accepted;
    }

    /** Energy the drive drew on the most recent step, FE. */
    public double getLastEnergyDrawFe() {
        return lastEnergyDrawFePerTick;
    }

    /** Water the drive drew on the most recent step, millibuckets. */
    public double getLastWaterDrawMb() {
        return lastWaterDrawMbPerTick;
    }

    // ---------------------------------------------------------------
    // Mirrored core state and environment
    // ---------------------------------------------------------------

    /** @see ControlRodDriveAccess#rodIndex() */
    public int getRodIndex() {
        return rodIndex;
    }

    /** Bind this drive to a rod. Done by structure validation, which knows the lattice. */
    public void setRodIndex(int rodIndex) {
        this.rodIndex = rodIndex;
    }

    public double getAccumulatorCharge() {
        return accumulatorCharge;
    }

    /** Mirrored from the core, which owns accumulator physics. */
    public void setAccumulatorCharge(double accumulatorCharge) {
        if (!Double.isFinite(accumulatorCharge)) {
            return;
        }
        this.accumulatorCharge = Math.max(0.0, Math.min(1.0, accumulatorCharge));
    }

    public int getNotchIndex() {
        return notchIndex;
    }

    /** Mirrored from the core, which owns rod position. */
    public void setNotchIndex(int notchIndex) {
        this.notchIndex = notchIndex;
    }

    /** Whether the drive is currently being asked to index a notch. Drives the wear model. */
    public boolean isMotionCommanded() {
        return motionCommanded;
    }

    /** @see #isMotionCommanded() */
    public void setMotionCommanded(boolean motionCommanded) {
        this.motionCommanded = motionCommanded;
    }

    /** Cladding temperature the drive housing is exposed to, degrees C. */
    public double getEnvironmentCladTemperatureC() {
        return environmentCladTemperatureC;
    }

    /** @see #getEnvironmentCladTemperatureC() */
    public void setEnvironmentCladTemperatureC(double temperatureC) {
        if (Double.isFinite(temperatureC)) {
            this.environmentCladTemperatureC = temperatureC;
        }
    }
}
