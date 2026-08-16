package dev.bwr.mod.fuel;

import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;
import net.minecraft.world.item.ItemStack;

/**
 * The bridge between the itemstack and {@link FuelAssembly}.
 *
 * <p>Every number a player is shown, and every number the core is loaded with,
 * goes through here — so there is exactly one place where item data becomes
 * physics, and no opportunity for the two to drift apart. Nothing in this class
 * computes reactivity itself; it builds a {@link FuelAssembly} and asks it.
 *
 * <p>{@link #load(ItemStack)} and {@link #store(ItemStack, FuelAssembly)} are
 * the pair that makes shuffling work. The refuelling GUI reads a stack into a
 * core position with {@code load}, the reactor burns it in place, and on unload
 * the accumulated exposure is written straight back onto the itemstack with
 * {@code store}. Nothing about the position is recorded anywhere.
 */
public final class FuelAssemblies {

    private FuelAssemblies() {
    }

    /**
     * Ceiling on the forward exposure scan in
     * {@link #exposureRemainingMwdPerTonne}. Well past the discharge burnup of
     * any real fuel; a bundle still above k-infinity 1 here is reported as
     * having at least this much left rather than being scanned forever.
     */
    public static final double EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE = 200_000.0;

    private static final double EXPOSURE_SCAN_STEP_MWD_PER_TONNE = 1_000.0;

    // -----------------------------------------------------------------
    // Item <-> component
    // -----------------------------------------------------------------

    /**
     * The bundle data on a stack. Falls back to a fresh LEU bundle so a stack
     * that somehow lost its component behaves as an unused assembly rather than
     * throwing in the middle of a tooltip or a refuelling.
     */
    public static FuelAssemblyData dataOf(ItemStack stack) {
        FuelAssemblyData data = stack.get(BwrDataComponents.FUEL_ASSEMBLY.get());
        return data != null ? data : FuelAssemblyData.fresh(FuelTypes.fallback());
    }

    public static void setData(ItemStack stack, FuelAssemblyData data) {
        stack.set(BwrDataComponents.FUEL_ASSEMBLY.get(), data);
    }

    // -----------------------------------------------------------------
    // Component <-> physics
    // -----------------------------------------------------------------

    /** Rebuilds the physics object from stored item data, exposure and all. */
    public static FuelAssembly toCore(FuelAssemblyData data) {
        FuelType type = FuelTypes.byNameOrFallback(data.fuelTypeName());
        return FuelAssembly.restore(type,
                data.enrichmentWeightFraction(),
                data.heavyMetalMassKg(),
                data.burnupMwdPerTonne(),
                data.gadoliniaRemainingFraction());
    }

    /** Rebuilds the physics object straight off an itemstack. */
    public static FuelAssembly load(ItemStack stack) {
        return toCore(dataOf(stack));
    }

    /**
     * Writes a burned assembly's exposure back onto the itemstack. This is the
     * write half of fuel shuffling: the bundle leaves the core carrying what
     * happened to it.
     */
    public static void store(ItemStack stack, FuelAssembly assembly) {
        setData(stack, FuelAssemblyData.of(assembly));
    }

    // -----------------------------------------------------------------
    // Derived figures for the tooltip and the refuelling GUI
    // -----------------------------------------------------------------

    /**
     * Exposure this bundle can still take before its own k-infinity falls to 1,
     * MWd/tonne. Zero once it is already below.
     *
     * <p>This is a property of the fuel, computed by asking {@link FuelAssembly}
     * what its k-infinity would be at a series of future exposures — the same
     * definition of end of cycle that
     * {@link dev.bwr.core.fuel.CoreLoading#isEndOfCycle()} uses. It is
     * emphatically <b>not</b> advice about when to refuel: an operating core
     * loses reactivity to xenon, voids and fuel temperature that this figure
     * knows nothing about, and it says nothing about the rest of the core, which
     * is what actually determines whether the plant can stay critical. Deciding
     * when to shut down is the player's problem.
     *
     * <p>Scanned forward rather than solved because k-infinity is not monotonic
     * in burnup: a gadded bundle gets <i>better</i> for the first several
     * thousand MWd/tonne as the poison burns out, and a thorium bundle improves
     * for far longer than that as U-233 breeds in.
     */
    public static double exposureRemainingMwdPerTonne(FuelAssembly assembly) {
        if (assembly.kInf() <= 1.0) {
            return 0.0;
        }
        FuelType type = assembly.fuelType();
        double start = assembly.burnupMwdPerTonne();
        double previous = 0.0;
        for (double ahead = EXPOSURE_SCAN_STEP_MWD_PER_TONNE;
             ahead <= EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE;
             ahead += EXPOSURE_SCAN_STEP_MWD_PER_TONNE) {
            double k = kInfAt(assembly, type, start + ahead);
            if (k <= 1.0) {
                // Linear interpolation across the step that crossed.
                double before = kInfAt(assembly, type, start + previous);
                double span = before - k;
                double within = span > 0.0
                        ? (before - 1.0) / span * EXPOSURE_SCAN_STEP_MWD_PER_TONNE
                        : 0.0;
                return previous + within;
            }
            previous = ahead;
        }
        return EXPOSURE_SCAN_LIMIT_MWD_PER_TONNE;
    }

    /**
     * Gadolinia burns with exposure, so a hypothetical future k-infinity has to
     * carry the poison state that goes with that exposure. Reproduced here by
     * replaying the burn on a throwaway copy rather than by duplicating the
     * burnout law, which lives in {@link FuelAssembly}.
     */
    private static double kInfAt(FuelAssembly assembly, FuelType type, double futureBurnup) {
        FuelAssembly probe = FuelAssembly.restore(type,
                assembly.enrichmentWeightFraction(),
                assembly.heavyMetalMassKg(),
                assembly.burnupMwdPerTonne(),
                assembly.gadoliniaRemainingFraction());
        probe.accumulateBurnupMwdPerTonne(futureBurnup - assembly.burnupMwdPerTonne());
        return probe.kInf();
    }

    /**
     * Thermal energy this bundle has already produced, MWd. Exposure times mass:
     * the number that tells a player what a bundle actually did for them.
     */
    public static double energyProducedMwd(FuelAssembly assembly) {
        return assembly.burnupMwdPerTonne() * assembly.heavyMetalMassTonnes();
    }
}
