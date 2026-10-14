package dev.bwr.mod.rods;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.kinetics.RodWorth;

/**
 * The per-rod indications of a full core display, as plain facts read from the core and the drive network: nothing
 * here decides anything, and no indication acts on the plant. Pure Java.
 *
 * <ul>
 *   <li><b>full in / full out</b>: the rod is at notch 00 or notch 48 (index 0 or 24).</li>
 *   <li><b>scram</b>: the scram signal is latched and this rod is not yet fully in (the core has no per-rod scram
 *       state, only the one latch).</li>
 *   <li><b>drift</b>: the rod is not at the notch demanded for it, nothing is driving it there, and no scram is
 *       forcing it. The core models no spontaneous rod motion, so a rod never moves without a demand and the
 *       real "moving without a command" cannot occur; what the model does have is the same thing seen from the
 *       other side, a rod that stays away from its demand because its drive has lost power or water or seized
 *       (the situation {@code rods_not_at_demand} counts while it persists). That is what lights DRIFT.</li>
 *   <li><b>accumulator trouble</b>: the accumulator holds less than a fraction of a full charge. The core has no
 *       notion of "low charge" (only a charged-or-empty count), so the threshold is a gameplay default,
 *       {@link #DEFAULT_ACCUMULATOR_FRACTION}, that a display component can set.</li>
 * </ul>
 */
public final class RodIndications {
    private RodIndications() {
    }

    /** Charge, as a fraction of full, below which an accumulator shows trouble unless a display says otherwise. */
    public static final double DEFAULT_ACCUMULATOR_FRACTION = 0.9;

    public static boolean fullIn(ReactorCore core, int rod) {
        return core.getRodNotchIndex(rod) == RodWorth.NOTCH_INDEX_FULLY_INSERTED
                && core.getRodPositionNotches(rod) == RodWorth.NOTCH_INDEX_FULLY_INSERTED;
    }

    public static boolean fullOut(ReactorCore core, int rod) {
        return core.getRodNotchIndex(rod) == RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN
                && core.getRodPositionNotches(rod) == RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN;
    }

    public static boolean scram(ReactorCore core, int rod) {
        return core.isScramActive() && core.getRodPositionNotches(rod) > RodWorth.NOTCH_INDEX_FULLY_INSERTED;
    }

    public static boolean drift(ReactorCore core, ControlRodDriveNetwork rods, int rod) {
        return rods != null && rods.isRodDrifting(rod);
    }

    public static boolean accumulatorTrouble(ReactorCore core, int rod, double fraction) {
        return core.getAccumulatorCharge(rod) < fraction;
    }
}
