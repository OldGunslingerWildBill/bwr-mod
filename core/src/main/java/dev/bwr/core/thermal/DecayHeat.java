package dev.bwr.core.thermal;

/**
 * Fission product decay heat, as a sum of exponentials with tracked group
 * inventories. {@code SPEC.md} section 1.4.
 *
 * <h2>Why this is the most important class in the thermal package</h2>
 * Fission stops when the rods go in. Decay heat does not. Roughly seven percent
 * of a reactor's thermal output at steady state comes from the beta and gamma
 * decay of fission products already sitting in the fuel, and that inventory does
 * not know the reactor has been scrammed. A BWR/6 at 3579 MWt is still making
 * <b>237 MW</b> the instant the rods hit bottom, <b>219 MW</b> a second later,
 * <b>48 MW</b> an hour later and <b>19 MW</b> a day later. There is no rod
 * position, no boron concentration and no shutdown procedure that changes any of
 * those numbers.
 *
 * <p>This is the driver of every severe accident in {@code SPEC.md} section 8.
 * Only sustained cooling helps. <b>There is deliberately no way to switch this
 * off</b>, no method that zeroes it, and SCRAM does not touch it.
 *
 * <h2>The model</h2>
 * Each group is a lumped inventory of fission products with one decay constant:
 * <pre>
 *   dH_i/dt   = lambda_i * (a_i * P - H_i)
 *   P_decay   = sum_i H_i
 * </pre>
 * where {@code P} is fission power as a fraction of rated and {@code H_i} is in
 * the same fractional units. Two properties fall straight out of that form and
 * are the reason it is written this way rather than as a shutdown-time formula:
 *
 * <ul>
 *   <li><b>Buildup follows operating history.</b> Run at constant power and
 *       group {@code i} approaches {@code a_i * P} with time constant
 *       {@code 1/lambda_i}. A core that has been critical for ten minutes has
 *       its fast groups loaded and its slow groups nearly empty, so it has far
 *       less decay heat than one that has run a month. A freshly started reactor
 *       is genuinely safer to scram than one at end of cycle, and nothing had to
 *       be special-cased for that to be true.</li>
 *   <li><b>There is no "time since shutdown" anywhere.</b> Power history is a
 *       state, so a reactor that scrams, restarts, and scrams again gets the
 *       right answer without anyone tracking events.</li>
 * </ul>
 *
 * <p>Integration is backward Euler, unconditionally stable, exact at steady
 * state, and non-negative by construction:
 * <pre>
 *   H_i' = (H_i + dt * lambda_i * a_i * P) / (1 + dt * lambda_i)
 * </pre>
 *
 * <h2>The group set</h2>
 * Eighteen groups, decay constants half a decade apart from 1 /s down to 1e-9
 * /s, so the model is honest from a second after shutdown out to about four
 * months. Amplitudes were obtained by non-negative least squares against the
 * standard infinite-operation decay heat curve for U-235; the fit is tabulated
 * in {@link #AMPLITUDE_FRACTION_OF_RATED} below. Worst relative error is
 * <b>1.3%</b> anywhere between 0.1 s and 1e7 s.
 *
 * <pre>
 *   t after shutdown   target    model     error
 *   0 s                6.55%     6.62%     +1.1%
 *   1 s                6.20%     6.12%     -1.3%
 *   10 s               4.90%     4.84%     -1.2%
 *   100 s              3.40%     3.37%     -0.9%
 *   1000 s             2.05%     2.03%     -1.2%
 *   1 hour             1.35%     1.36%     +0.4%
 *   10000 s            1.00%     1.00%     -0.1%
 *   1e5 s (1.16 d)     0.500%    0.499%    -0.3%
 *   1e6 s (11.6 d)     0.235%    0.234%    -0.7%
 *   1e7 s (116 d)      0.095%    0.095%    -0.0%
 * </pre>
 *
 * <p>Pure Java, doubles, no Minecraft. Not thread safe.
 */
public final class DecayHeat {

    // ---------------------------------------------------------------
    // The fit
    // ---------------------------------------------------------------

    /** Group decay constants, per second. Half a decade apart, 1 down to 1e-9. */
    public static final double[] DECAY_CONSTANT_PER_SECOND = {
            1.000000e+00, 3.162278e-01, 1.000000e-01, 3.162278e-02, 1.000000e-02,
            3.162278e-03, 1.000000e-03, 3.162278e-04, 1.000000e-04, 3.162278e-05,
            1.000000e-05, 3.162278e-06, 1.000000e-06, 3.162278e-07, 1.000000e-07,
            3.162278e-08, 3.162278e-09, 1.000000e-09
    };

    /**
     * Group amplitudes as a fraction of rated thermal power, parallel to
     * {@link #DECAY_CONSTANT_PER_SECOND}. {@code a_i} is the decay heat group
     * {@code i} contributes after running long enough to saturate it at rated
     * power, so the sum is the shutdown decay heat of an infinitely irradiated
     * core: <b>6.62%</b>.
     */
    public static final double[] AMPLITUDE_FRACTION_OF_RATED = {
            3.589967e-03, 5.762620e-03, 1.012860e-02, 3.512920e-03, 1.248593e-02,
            2.269778e-03, 1.029857e-02, 4.884507e-03, 4.169741e-03, 2.291083e-03,
            2.395802e-03, 1.099082e-03, 1.290915e-03, 6.978351e-04, 6.145799e-04,
            2.003087e-04, 5.781987e-05, 4.971152e-04
    };

    /** Number of exponential groups. */
    public static final int GROUP_COUNT = DECAY_CONSTANT_PER_SECOND.length;

    /**
     * Decay heat immediately after shutdown from an infinitely long run at
     * rated power, fraction of rated: the sum of every amplitude, 0.0662.
     */
    public static final double SATURATED_FRACTION_OF_RATED = sumAmplitudes();

    private static double sumAmplitudes() {
        double sum = 0.0;
        for (double a : AMPLITUDE_FRACTION_OF_RATED) {
            sum += a;
        }
        return sum;
    }

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private final double[] groupInventoryFractionOfRated = new double[GROUP_COUNT];
    private double totalFractionOfRated;

    /** A core with no irradiation history at all: no fission products, no decay heat. */
    public DecayHeat() {
    }

    /**
     * A core that has been running at this power long enough for every group to
     * saturate — the bounding case, and the right initial condition for a
     * reactor that has been at power for months.
     */
    public static DecayHeat saturatedAt(double powerFractionOfRated) {
        DecayHeat decayHeat = new DecayHeat();
        decayHeat.setToSaturatedInventory(powerFractionOfRated);
        return decayHeat;
    }

    /**
     * A core that has been running at this power for a finite time:
     * {@code H_i = a_i * P * (1 - exp(-lambda_i * T))}.
     *
     * <p>This is where operating history shows up as a number. Ten minutes at
     * rated power leaves about 3.7% decay heat at shutdown; a month leaves
     * about 6.5%.
     *
     * @param powerFractionOfRated fission power held during the run, fraction of rated
     * @param operatingSeconds     how long it was held
     */
    public static DecayHeat afterOperatingFor(double powerFractionOfRated, double operatingSeconds) {
        DecayHeat decayHeat = new DecayHeat();
        decayHeat.setToOperatingHistory(powerFractionOfRated, operatingSeconds);
        return decayHeat;
    }

    // ---------------------------------------------------------------
    // Integration
    // ---------------------------------------------------------------

    /**
     * Advance every group inventory one step and return the new decay heat.
     *
     * <p>Cheap enough to call every tick: eighteen divides. The time constants
     * span nine decades but backward Euler does not care, so there is no
     * sub-stepping and no coarse-step buffering here.
     *
     * @param fissionPowerFractionOfRated fission power only, fraction of rated.
     *        <b>Do not pass total power.</b> Feeding decay heat back into its own
     *        source term would make the inventory chase itself.
     * @param dtSeconds step length, seconds; non-positive is a no-op
     * @return decay heat as a fraction of rated thermal power
     */
    public double step(double fissionPowerFractionOfRated, double dtSeconds) {
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return totalFractionOfRated;
        }
        double power = (Double.isFinite(fissionPowerFractionOfRated) && fissionPowerFractionOfRated > 0.0)
                ? fissionPowerFractionOfRated : 0.0;

        double sum = 0.0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            double lambda = DECAY_CONSTANT_PER_SECOND[i];
            double next = (groupInventoryFractionOfRated[i]
                    + dtSeconds * lambda * AMPLITUDE_FRACTION_OF_RATED[i] * power)
                    / (1.0 + dtSeconds * lambda);
            groupInventoryFractionOfRated[i] = next;
            sum += next;
        }
        totalFractionOfRated = sum;
        return sum;
    }

    // ---------------------------------------------------------------
    // Readouts
    // ---------------------------------------------------------------

    /** Decay heat as a fraction of rated thermal power. Additive to fission power. */
    public double getFractionOfRated() {
        return totalFractionOfRated;
    }

    /** Decay heat in megawatts for a core of the given rating. */
    public double getThermalMW(double ratedThermalMW) {
        return totalFractionOfRated * ratedThermalMW;
    }

    /** Copy of the eighteen group inventories, for persistence and CSV dumps. */
    public double[] getGroupInventories() {
        return groupInventoryFractionOfRated.clone();
    }

    /** One group inventory, fraction of rated. */
    public double getGroupInventory(int group) {
        return groupInventoryFractionOfRated[group];
    }

    /**
     * How saturated the inventory is against an indefinite run at this power,
     * 0..1 — a plain measure of irradiation history. At 1.0 a scram delivers the
     * full 6.6%; at 0.5 it delivers about half that.
     *
     * <p>Returns zero for a non-positive reference power, since a core that has
     * never run has no history to be a fraction of.
     */
    public double saturationFraction(double referencePowerFractionOfRated) {
        if (!(referencePowerFractionOfRated > 0.0) || !Double.isFinite(referencePowerFractionOfRated)) {
            return 0.0;
        }
        return totalFractionOfRated / (SATURATED_FRACTION_OF_RATED * referencePowerFractionOfRated);
    }

    /**
     * What the decay heat would be {@code secondsAhead} from now if fission
     * power went to zero this instant, fraction of rated. Evaluated from the
     * current inventories, so it already accounts for operating history.
     *
     * <p>A projection, not a prediction of what will happen — it assumes a
     * scram that has not occurred and no further fission. Useful for a player
     * sizing decay heat removal from Lua, and for tests.
     */
    public double projectedFractionOfRated(double secondsAhead) {
        if (!(secondsAhead > 0.0) || !Double.isFinite(secondsAhead)) {
            return totalFractionOfRated;
        }
        double sum = 0.0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            sum += groupInventoryFractionOfRated[i]
                    * Math.exp(-DECAY_CONSTANT_PER_SECOND[i] * secondsAhead);
        }
        return sum;
    }

    /**
     * Total heat still to be released by the current inventory if fission never
     * resumes, in megawatt-seconds for a core of the given rating:
     * {@code sum_i H_i / lambda_i}.
     *
     * <p>Integrated to infinity, so at rated saturation it is a barely
     * meaningful 1.9e9 MW-s — most of it dribbling out over decades from the
     * slowest groups. The number that matters is the near-term one, and it is
     * arresting enough: the <b>first hour</b> alone releases 2.4e5 MW-s, which
     * would boil <b>162 tonnes</b> of water at rated pressure. The vessel holds
     * 220. The first day releases ten times that. Use
     * {@link #projectedFractionOfRated} over the window you care about rather
     * than this total.
     */
    public double remainingEnergyMWSeconds(double ratedThermalMW) {
        double sum = 0.0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            sum += groupInventoryFractionOfRated[i] / DECAY_CONSTANT_PER_SECOND[i];
        }
        return sum * ratedThermalMW;
    }

    // ---------------------------------------------------------------
    // Initialisation and persistence
    // ---------------------------------------------------------------

    /** Load every group to its saturated inventory for this power. @see #saturatedAt */
    public void setToSaturatedInventory(double powerFractionOfRated) {
        setToOperatingHistory(powerFractionOfRated, Double.POSITIVE_INFINITY);
    }

    /**
     * Load every group to the inventory it would have after running at this
     * power for this long: {@code a_i * P * (1 - exp(-lambda_i * T))}.
     *
     * @param powerFractionOfRated fission power, fraction of rated
     * @param operatingSeconds     run length; infinite gives full saturation
     */
    public void setToOperatingHistory(double powerFractionOfRated, double operatingSeconds) {
        double power = (Double.isFinite(powerFractionOfRated) && powerFractionOfRated > 0.0)
                ? powerFractionOfRated : 0.0;
        double seconds = (operatingSeconds > 0.0) ? operatingSeconds : 0.0;

        double sum = 0.0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            double saturated = AMPLITUDE_FRACTION_OF_RATED[i] * power;
            double filled = Double.isInfinite(seconds)
                    ? saturated
                    : saturated * (1.0 - Math.exp(-DECAY_CONSTANT_PER_SECOND[i] * seconds));
            groupInventoryFractionOfRated[i] = filled;
            sum += filled;
        }
        totalFractionOfRated = sum;
    }

    /**
     * Restore persisted group inventories verbatim, for world load.
     *
     * @param inventories exactly {@link #GROUP_COUNT} values, copied in;
     *                    negatives are floored at zero
     */
    public void restoreGroupInventories(double[] inventories) {
        if (inventories == null || inventories.length != GROUP_COUNT) {
            throw new IllegalArgumentException(
                    "expected " + GROUP_COUNT + " decay heat group inventories");
        }
        double sum = 0.0;
        for (int i = 0; i < GROUP_COUNT; i++) {
            double value = (Double.isFinite(inventories[i]) && inventories[i] > 0.0) ? inventories[i] : 0.0;
            groupInventoryFractionOfRated[i] = value;
            sum += value;
        }
        totalFractionOfRated = sum;
    }

    /**
     * Empty every group — a core that has never been irradiated, or one that has
     * been defuelled.
     *
     * <p>This is not an off switch. It discards the fission product inventory,
     * which is a statement about what is physically in the fuel, and it is only
     * correct for a fresh or empty core. Calling it on an operated core deletes
     * the accident.
     */
    public void clearInventory() {
        java.util.Arrays.fill(groupInventoryFractionOfRated, 0.0);
        totalFractionOfRated = 0.0;
    }

    @Override
    public String toString() {
        return String.format("DecayHeat[%.4f%% of rated, %.3e MW-s remaining at 3579 MWt]",
                100.0 * totalFractionOfRated, remainingEnergyMWSeconds(3579.0));
    }
}
