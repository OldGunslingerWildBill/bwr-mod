package dev.bwr.mod.eccs;

/**
 * The single conversion between real shaft power and Forge Energy.
 *
 * <p>Derived rather than chosen, so the emergency pumps sit on the same
 * economic scale as everything else in the plant. A BWR/6 recirculation pump
 * motor is about 6.7 MW, and {@code RecirculationPumpBlockEntity} draws
 * 500 kFE/t at full speed. That fixes the exchange rate for every other motor
 * in the mod:
 *
 * <pre>13.4 W per FE/t</pre>
 *
 * <p>Everything downstream follows from the machines' real motor ratings
 * without anyone picking a balance number: HPCS asks for 194 kFE/t because a
 * real HPCS motor is 2.6 MW, and SLC asks for 3 kFE/t because a real SLC pump
 * is 40 kW. The consequence is the one {@code SPEC.md} section 9.1 wants — the
 * motor-driven systems are a serious electrical commitment and the
 * turbine-driven ones are free.
 */
public final class EccsPower {

    private EccsPower() {
    }

    /** Watts of shaft power one FE per tick buys. */
    public static final double WATTS_PER_FE_PER_TICK = 6.7e6 / 500_000.0;

    /** FE per tick a machine of a given electrical rating draws at full load. */
    public static int fePerTickFromWatts(double watts) {
        return (int) Math.ceil(Math.max(0.0, watts) / WATTS_PER_FE_PER_TICK);
    }

    /** Electrical power a given FE-per-tick supply represents, watts. */
    public static double wattsFromFePerTick(double fePerTick) {
        return Math.max(0.0, fePerTick) * WATTS_PER_FE_PER_TICK;
    }
}
