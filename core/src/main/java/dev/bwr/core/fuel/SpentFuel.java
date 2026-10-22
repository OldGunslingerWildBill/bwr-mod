package dev.bwr.core.fuel;

/**
 * What is in a spent fuel assembly, split the way a reprocessing plant splits it:
 * fission products, plutonium and uranium. Pure arithmetic on the assembly's own
 * stored state (burnup, enrichment, fuel type) and the three numbers the core
 * already uses for it (depletion, breeding gain, the enrichment response); there
 * is no table of fuels here.
 *
 * <h2>The model</h2>
 * An assembly is {@value #INGOTS_PER_ASSEMBLY} ingot-units of heavy metal, as in
 * the fuel fabricator. With {@code B} the burnup, {@code e} the assembly's
 * enrichment (initial fissile fraction), all as fractions of the heavy metal:
 *
 * <ol>
 *   <li><b>Fission products.</b> {@code phi = G * B}: the heavy metal that was
 *       fissioned. {@code G} follows from the fuel's own heat per fission (an
 *       energy per fission and the mass of 235 u per atom): about 1.04 g of
 *       heavy metal per MWd. This is exact bookkeeping, the mass leaves the heavy
 *       metal and becomes fission products.</li>
 *   <li><b>Fissile remaining.</b> {@code f =} {@link FuelAssembly#fissileEquivalentWeightFraction()}:
 *       the mod's net fissile balance after depletion (the fuel's
 *       {@code depletion_rate}) and breeding ({@code conversion_gain_k_inf}),
 *       read back through the same enrichment response the core uses.</li>
 *   <li><b>Plutonium bred.</b> Every fission destroys a fissile atom, so what
 *       was destroyed ({@code phi}) equals what was there at the start
 *       ({@code e}) plus what was bred ({@code b}) less what is left ({@code f}):
 *       {@code b = phi + f - e}. The k-infinity curve flattens at high enrichment
 *       and then cannot see captures, so {@code b} is never less than
 *       {@link #MIN_CONVERSION} of the fertile share of what was fissioned,
 *       {@code MIN_CONVERSION * (1 - e) * phi} (a 20 % assembly still makes some).</li>
 *   <li><b>Plutonium left.</b> The uranium-235 and the plutonium (the initial
 *       plutonium of a MOX or plutonium fuel, plus {@code b}) are burned at the
 *       same relative rate, so the same fraction {@code f / (e + b)} of each
 *       survives: {@code p = (e_Pu + b) * f / (e + b)}, where {@code e_Pu} is
 *       {@code e} for a fuel that starts as plutonium and 0 for a uranium fuel.</li>
 *   <li><b>Uranium.</b> What is left: {@code 1 - phi - p}. All of it goes back as
 *       depleted uranium whatever its assay (see the plant's documentation).</li>
 * </ol>
 *
 * <h2>Why fixed point</h2>
 * The three amounts are returned as whole numbers of grains, a millionth of a
 * ninth of an ingot, summing exactly to {@link #ASSEMBLY_GRAINS}: the uranium is
 * what the other two leave, so nothing is created or lost to rounding. A machine
 * that keeps the fractions carries them in these units from assembly to assembly.
 * A ninth of an ingot is a nugget, which is the item the plant counts plutonium
 * and waste in.
 */
public final class SpentFuel {

    private SpentFuel() {
    }

    /** Ingot-units of heavy metal in one assembly: the fabricator's model. */
    public static final int INGOTS_PER_ASSEMBLY = 4;

    /** Nuggets (and waste items) in an ingot. */
    public static final int NINTHS_PER_INGOT = 9;

    /** Fixed-point grains in a ninth of an ingot, one nugget. */
    public static final long GRAINS_PER_NINTH = 1_000_000L;

    /** Grains in an ingot. */
    public static final long GRAINS_PER_INGOT = GRAINS_PER_NINTH * NINTHS_PER_INGOT;

    /** Grains of heavy metal in one assembly. */
    public static final long ASSEMBLY_GRAINS = GRAINS_PER_INGOT * INGOTS_PER_ASSEMBLY;

    /**
     * Least plutonium bred per fertile fission, see the class comment. Real
     * light-water lattices convert about 0.5 to 0.6 of the fissile they burn at
     * LEU and still about 0.2 at 20 %; the figure is the low end, used only where
     * the net balance cannot see the captures.
     */
    public static final double MIN_CONVERSION = 0.15;

    /** Joules in a megawatt-day. */
    private static final double JOULES_PER_MWD = 86_400.0 * 1.0e6;

    /** Atomic mass unit, kg. */
    private static final double ATOMIC_MASS_UNIT_KG = 1.66053906660e-27;

    /** Mass of a fissioned atom, in atomic mass units: 235 for every fuel (a plutonium atom is 1.7 % heavier). */
    private static final double FISSIONED_ATOM_MASS_U = 235.0;

    private static final double KG_PER_TONNE = 1000.0;

    /**
     * Fraction of the heavy metal fissioned per MWd/t of burnup for this fuel:
     * energy per MWd over energy per fission, times the mass per atom, per tonne.
     * About 1.04e-6 (1.04 g per MWd) at 202.5 MeV.
     */
    public static double fissionedFractionPerMwdPerTonne(FuelType type) {
        double fissionsPerMwd = JOULES_PER_MWD / type.heatPerFissionJoules();
        double kgPerMwd = fissionsPerMwd * FISSIONED_ATOM_MASS_U * ATOMIC_MASS_UNIT_KG;
        return kgPerMwd / KG_PER_TONNE;
    }

    /**
     * The three fractions of the heavy metal, summing to 1.
     *
     * @param fissionProducts the heavy metal that was fissioned
     * @param plutonium       the plutonium left
     * @param uranium         the uranium left
     */
    public record Inventory(double fissionProducts, double plutonium, double uranium) {
    }

    /**
     * @param plutoniumStart true for a fuel whose initial fissile content is plutonium (mixed oxide, plutonium fuel),
     *                       false for a uranium fuel
     */
    public static Inventory inventory(FuelAssembly assembly, boolean plutoniumStart) {
        double e = assembly.enrichmentWeightFraction();
        double burnup = assembly.burnupMwdPerTonne();
        if (!(burnup > 0.0)) {
            return new Inventory(0.0, 0.0, 1.0);
        }
        double phi = Math.min(1.0, fissionedFractionPerMwdPerTonne(assembly.fuelType()) * burnup);
        double f = assembly.fissileEquivalentWeightFraction();
        double bred = Math.max(phi + f - e, MIN_CONVERSION * (1.0 - e) * phi);
        double survive = f / (e + bred);
        double pool = (plutoniumStart ? e : 0.0) + bred;
        double p = Math.max(0.0, Math.min(pool * survive, 1.0 - phi));
        return new Inventory(phi, p, 1.0 - phi - p);
    }

    /** The same split in whole grains, summing to exactly {@link #ASSEMBLY_GRAINS}. */
    public record Split(long fissionProducts, long plutonium, long uranium) {

        public long total() {
            return fissionProducts + plutonium + uranium;
        }
    }

    public static Split split(FuelAssembly assembly, boolean plutoniumStart) {
        Inventory inventory = inventory(assembly, plutoniumStart);
        long waste = Math.min(ASSEMBLY_GRAINS, (long) Math.floor(inventory.fissionProducts() * ASSEMBLY_GRAINS));
        long plutonium = Math.min(ASSEMBLY_GRAINS - waste, (long) Math.floor(inventory.plutonium() * ASSEMBLY_GRAINS));
        return new Split(waste, plutonium, ASSEMBLY_GRAINS - waste - plutonium);
    }
}
