package dev.bwr.core.fuel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Which assemblies are loaded, where they sit, and the aggregate constants the
 * kinetics needs. SPEC 1.3 and 2.3.
 *
 * <p>The core is a square lattice with a position index of
 * {@code row * latticeWidth + column}. Positions may be empty — a real core is
 * a circle inscribed in that square, and the corners simply hold nothing. The
 * refuelling GUI is the same grid (SPEC 10), so the indexing is shared.
 *
 * <p><b>Aggregation is flux-weighted, and the weights come from a replaceable
 * source.</b> Point kinetics is scalar: it wants one k-infinity, one beta, one
 * prompt lifetime, one Doppler coefficient. Producing those from a mixed core
 * means weighting each assembly by how much of the fission is happening in it.
 * {@link #RADIAL_SHAPE} is the analytic stand-in: a centre-peaked radial profile
 * times each assembly's own k-infinity, with no neighbour coupling, no rod
 * shadowing and no axial detail. It is the default for a bare
 * {@code CoreLoading} and the initial guess for the real thing.
 *
 * <p>The real thing is {@code dev.bwr.core.nodal.NodalFluxSolver}, the coarse-mesh
 * nodal diffusion solve of SPEC 1.3. It implements {@link PowerWeightSource},
 * {@code ReactorCore} hands it to {@link #setPowerWeightSource} at construction,
 * and nothing else here changed to accommodate it — which was the point of the
 * seam.
 *
 * <p><b>On beta.</b> Beta is a nuclear property of the fissioning isotope, not
 * a geometric one. A plutonium assembly has beta = 0.002099 wherever you put
 * it. What varies across the core is <i>flux</i>, so the core's effective beta
 * is the fission-rate-weighted average of the assemblies' betas — which means a
 * handful of MOX bundles in the high-flux centre drag effective beta down much
 * further than the same bundles parked on the periphery. That is a real effect
 * and it is why loading pattern is a safety decision, not a cosmetic one.
 *
 * <p>Zero Minecraft here. Positions are ints, assemblies are plain objects.
 */
public final class CoreLoading {

    /**
     * Core-average non-leakage probability, dimensionless. k_eff = k_inf x P_nl.
     * A BWR/6-sized core leaks a few percent of its neutrons out of the active
     * region; a smaller multiblock leaks more, so this is settable.
     */
    public static final double DEFAULT_NON_LEAKAGE_PROBABILITY = 0.97;

    /**
     * Flux at the outer edge of the active core as a fraction of the peak, from
     * reflector return. A bare-core cosine/Bessel shape goes to zero at the
     * boundary; a reflected core does not, and peripheral bundles really do
     * make power.
     */
    public static final double REFLECTOR_EDGE_FLOOR = 0.15;

    /** First zero of the Bessel function J0 — the radial buckling of a bare cylinder. */
    private static final double BESSEL_J0_FIRST_ZERO = 2.404825557695773;

    /**
     * Fallbacks for a core with nothing loaded in it. An empty core cannot
     * fission at all, so these values are not physics — they exist only to keep
     * the kinetics equations well-formed (no division by zero, no NaN beta)
     * when a player is mid-refuel with the vessel head off. They are the U-235
     * total delayed fraction and a typical thermal prompt lifetime.
     */
    public static final double EMPTY_CORE_BETA = 0.006502;
    public static final double EMPTY_CORE_PROMPT_LIFETIME_SECONDS = 4.0e-5;
    private static final double EMPTY_CORE_DOPPLER_PER_C = -1.6e-5;

    /**
     * Recoverable energy per fission the plant's rated thermal power is quoted
     * against, MeV — U-235's figure. Public because it is the denominator of
     * {@link #heatPerFissionScaleFactor()}, which is how a core loaded with
     * something else is meant to reach the thermal power path.
     */
    public static final double EMPTY_CORE_HEAT_PER_FISSION_MEV = 202.5;

    /**
     * Supplies the per-position share of core fission power.
     *
     * <p>Implementations return one weight per lattice position, zero at empty
     * positions, summing to 1 over the occupied ones. The default is a static
     * radial shape; the installed replacement in a running reactor is the 1 Hz
     * nodal diffusion solve, which additionally sees rod positions and the void
     * distribution and produces shadowing and hot spots. Anything that consumes
     * weights should go through {@link CoreLoading#powerWeights()} so the swap is
     * invisible to it.
     */
    @FunctionalInterface
    public interface PowerWeightSource {
        double[] weightsFor(CoreLoading loading);
    }

    /**
     * The stand-in shape: centre-peaked Bessel radial profile times each
     * assembly's k-infinity, so a fresh bundle makes more power than a burned
     * one at the same radius. No neighbour coupling — that is what the nodal
     * solve is for.
     */
    public static final PowerWeightSource RADIAL_SHAPE = CoreLoading::computeRadialWeights;

    private final int latticeWidth;
    private final FuelAssembly[] positions;

    private double nonLeakageProbability = DEFAULT_NON_LEAKAGE_PROBABILITY;

    /**
     * volatile for the same reason {@link #cachedWeights} is: it is read inside
     * the weight computation, which any thread may enter, and a source installed
     * on one thread has to be fully constructed before another can call it.
     */
    private volatile PowerWeightSource powerWeightSource = RADIAL_SHAPE;

    /**
     * Serialises the lazy weight computation.
     *
     * <p><b>This exists because the installed weight source is not thread safe
     * and the getter that drives it is reachable from a CC:Tweaked computer
     * thread.</b> In a running reactor the source is {@code NodalFluxSolver},
     * whose own javadoc says every working array is allocated once per mesh and
     * reused on every solve. The Lua path
     * {@code ReactorPeripheral.getKEffectiveAllRodsOut()} ->
     * {@code CoreLoading.kEffAllRodsOut()} -> {@code aggregateKInf()} ->
     * {@link #powerWeights()} reaches the solve on the computer thread, and the
     * reactor invalidates the cache once per nodal interval, so there is a window
     * every single second in which both threads would enter the same solve and
     * interleave their Gauss-Seidel sweeps over the same {@code flux} array. The
     * returned weights become beta_eff, the prompt lifetime, the Doppler
     * coefficient and the burnup distribution, so the corruption would be silent
     * and would destroy reproducibility; and if a bundle were loaded in the same
     * window one thread would reallocate the mesh under the other's indices.
     *
     * <p>One monitor, taken by one class, with nothing else acquired inside it —
     * so there is no lock ordering to get wrong and no deadlock to have. It is
     * also reentrant, which matters because the compute runs with the monitor
     * held and a source is free to call back into this object. The server tick
     * can be made to wait behind a computer-thread solve, but that wait is
     * bounded by the solver's own iteration cap and is the correct trade against
     * running the two solves on top of each other.
     */
    private final Object weightLock = new Object();

    /**
     * Last computed weights, or null when they need recomputing.
     *
     * <p><b>volatile, and that is load bearing.</b> It is written by whichever
     * thread completes a compute and read by both, and the array it points at
     * must be fully built and normalised before any other thread can see it. The
     * volatile write in {@link #currentWeights()} is what publishes it safely;
     * without it a reader could observe the reference before the element writes.
     * Never assign a partly built array to this field.
     */
    private volatile double[] cachedWeights;

    public CoreLoading(int latticeWidth) {
        if (latticeWidth < 1) {
            throw new IllegalArgumentException("latticeWidth must be at least 1, was " + latticeWidth);
        }
        this.latticeWidth = latticeWidth;
        this.positions = new FuelAssembly[latticeWidth * latticeWidth];
        this.inserts = new CoreInsert[positions.length];
    }

    private final CoreInsert[] inserts;
    public CoreInsert insertAt(int index) { checkIndex(index); return inserts[index]; }
    public void loadInsert(int index, CoreInsert insert) {
        checkIndex(index);
        if (positions[index] != null) throw new IllegalStateException("Position contains fuel");
        if (inserts[index] != null && insert != null) throw new IllegalStateException("Position contains an insert");
        inserts[index] = insert;
        invalidateWeights();
    }
    public CoreInsert unloadInsert(int index) {
        CoreInsert result = insertAt(index); inserts[index] = null; invalidateWeights(); return result;
    }
    public int insertCount() { int n = 0; for (var insert : inserts) if (insert != null) n++; return n; }
    public double installedSourcePerSecond() {
        double total = 0; for (var insert : inserts) if (insert != null) total += insert.sourcePerSecond(); return total;
    }
    /** Fixed inserts enter the bulk balance as a geometric flux-weighted absorption increment. */
    public double insertAbsorptionRatio() {
        double absorber = 0, fuel = 0;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] != null) fuel += geometricFluxShape(i);
            if (inserts[i] != null) absorber += geometricFluxShape(i) * inserts[i].kind().absorptionRatio;
        }
        return fuel > 0 ? absorber / fuel : 0;
    }

    // ---------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------

    public int latticeWidth() {
        return latticeWidth;
    }

    /** Total lattice positions, occupied or not. */
    public int positionCount() {
        return positions.length;
    }

    public int index(int column, int row) {
        checkCoordinate(column, "column");
        checkCoordinate(row, "row");
        return row * latticeWidth + column;
    }

    public int columnOf(int index) {
        checkIndex(index);
        return index % latticeWidth;
    }

    public int rowOf(int index) {
        checkIndex(index);
        return index / latticeWidth;
    }

    // ---------------------------------------------------------------
    // Loading and shuffling
    // ---------------------------------------------------------------

    /**
     * Puts an assembly in a position.
     *
     * @param assembly the assembly, or null to empty the position
     * @return whatever was there before, or null
     */
    public FuelAssembly load(int index, FuelAssembly assembly) {
        checkIndex(index);
        if (assembly != null && inserts[index] != null) throw new IllegalStateException("Position contains an insert");
        FuelAssembly previous = positions[index];
        positions[index] = assembly;
        invalidateWeights();
        return previous;
    }

    /** Removes and returns the assembly at a position, or null if it was empty. */
    public FuelAssembly unload(int index) {
        return load(index, null);
    }

    public FuelAssembly assemblyAt(int index) {
        checkIndex(index);
        return positions[index];
    }

    public boolean isOccupied(int index) {
        return assemblyAt(index) != null || inserts[index] != null;
    }

    /**
     * Exchanges two positions. This is fuel shuffling: exposure travels with
     * the assembly, so a bundle moved from the centre to the periphery keeps
     * its burnup and simply burns slower from now on.
     */
    public void swap(int indexA, int indexB) {
        checkIndex(indexA);
        checkIndex(indexB);
        FuelAssembly temp = positions[indexA];
        positions[indexA] = positions[indexB];
        positions[indexB] = temp;
        CoreInsert other = inserts[indexA]; inserts[indexA] = inserts[indexB]; inserts[indexB] = other;
        invalidateWeights();
    }

    /** Empties every position. */
    public void unloadAll() {
        Arrays.fill(positions, null);
        Arrays.fill(inserts, null);
        invalidateWeights();
    }

    /** Number of positions holding an assembly. */
    public int loadedAssemblyCount() {
        int count = 0;
        for (FuelAssembly assembly : positions) {
            if (assembly != null) {
                count++;
            }
        }
        return count;
    }

    /** Positional view, nulls included, in index order. Unmodifiable. */
    public List<FuelAssembly> positions() {
        return Collections.unmodifiableList(Arrays.asList(positions));
    }

    /** Just the loaded assemblies, in index order. */
    public List<FuelAssembly> loadedAssemblies() {
        List<FuelAssembly> loaded = new ArrayList<>();
        for (FuelAssembly assembly : positions) {
            if (assembly != null) {
                loaded.add(assembly);
            }
        }
        return Collections.unmodifiableList(loaded);
    }

    // ---------------------------------------------------------------
    // Power weights
    // ---------------------------------------------------------------

    /** Replaces the weight source — this is where the nodal solve plugs in. */
    public void setPowerWeightSource(PowerWeightSource source) {
        this.powerWeightSource = Objects.requireNonNull(source, "powerWeightSource");
        invalidateWeights();
    }

    public PowerWeightSource powerWeightSource() {
        return powerWeightSource;
    }

    /**
     * Discards cached weights. Called automatically when the loading changes;
     * call it by hand after mutating an assembly in place, or once per nodal
     * re-solve interval.
     *
     * <p>Takes the same monitor as the compute so an invalidation cannot be lost
     * by landing in the middle of one and being overwritten by its result.
     */
    public void invalidateWeights() {
        synchronized (weightLock) {
            cachedWeights = null;
        }
    }

    /**
     * Discard the cached weights and immediately recompute them, on this thread.
     *
     * <p>The explicit, server-side form of what {@link #powerWeights()} otherwise
     * does lazily. Call it from wherever owns the nodal re-solve interval —
     * {@code ReactorCore.refreshNodalShape()} — instead of the
     * invalidate-and-let-the-next-reader-solve pattern, and the solve stops
     * happening on whichever thread happens to ask first.
     *
     * @return the freshly computed weights, one per lattice position
     */
    public double[] refreshWeights() {
        synchronized (weightLock) {
            cachedWeights = null;
            return currentWeights().clone();
        }
    }

    /**
     * Per-position share of core fission power, summing to 1 over occupied
     * positions and zero everywhere else. Cached until invalidated.
     */
    public double[] powerWeights() {
        return currentWeights().clone();
    }

    public double powerWeight(int index) {
        checkIndex(index);
        return currentWeights()[index];
    }

    /**
     * The live weights array, computing it if the cache is cold. Never handed
     * out directly — callers get a clone — and never null.
     *
     * <p>The uncontended path is a single volatile read and no monitor at all,
     * which is what keeps the once-per-tick readers cheap. Only a cold cache
     * takes the lock, and the second read inside it is what stops two threads
     * that both saw null from both running the solve: the loser gets the
     * winner's array, so the answer does not depend on which thread arrived
     * first. That determinism is required — persistence is bit-exact (SPEC 11)
     * and an iterative solve's last bits depend on its convergence history.
     */
    private double[] currentWeights() {
        double[] weights = cachedWeights;
        if (weights != null) {
            return weights;
        }
        synchronized (weightLock) {
            weights = cachedWeights;
            if (weights == null) {
                double[] raw = powerWeightSource.weightsFor(this);
                if (raw == null || raw.length != positions.length) {
                    throw new IllegalStateException("power weight source returned "
                            + (raw == null ? "null" : raw.length + " weights")
                            + ", expected " + positions.length);
                }
                // Published only once fully built and normalised: the volatile
                // write is the happens-before edge that makes the element writes
                // visible to every later reader.
                weights = normalise(raw);
                cachedWeights = weights;
            }
            return weights;
        }
    }

    /** Thermal power produced at each position for a given core total, MW. */
    public double[] assemblyThermalMW(double coreThermalMW) {
        double[] weights = powerWeights();
        for (int i = 0; i < weights.length; i++) {
            weights[i] *= coreThermalMW;
        }
        return weights;
    }

    /**
     * Geometric flux shape at a position, peak 1.0 at the centre falling to
     * {@value #REFLECTOR_EDGE_FLOOR} at the edge of the active core. Exposed so
     * a nodal solver can use it as an initial guess.
     */
    public double geometricFluxShape(int index) {
        checkIndex(index);
        double centre = (latticeWidth - 1) / 2.0;
        double x = columnOf(index) - centre;
        double y = rowOf(index) - centre;
        double coreRadius = Math.max(latticeWidth / 2.0, 1.0e-9);
        double relative = Math.min(Math.hypot(x, y) / coreRadius, 1.0);
        double bare = besselJ0(BESSEL_J0_FIRST_ZERO * relative);
        return REFLECTOR_EDGE_FLOOR + (1.0 - REFLECTOR_EDGE_FLOOR) * Math.max(bare, 0.0);
    }

    private double[] computeRadialWeights() {
        double[] weights = new double[positions.length];
        for (int i = 0; i < positions.length; i++) {
            FuelAssembly assembly = positions[i];
            if (assembly == null) {
                continue;
            }
            weights[i] = geometricFluxShape(i) * assembly.kInf();
        }
        return weights;
    }

    /**
     * Scales weights to sum to 1 over occupied positions. Falls back to an even
     * split when the source produces nothing usable — a core of completely
     * spent fuel still has to divide its decay heat somewhere.
     */
    private double[] normalise(double[] raw) {
        double[] weights = new double[raw.length];
        double total = 0.0;
        for (int i = 0; i < raw.length; i++) {
            double w = positions[i] == null ? 0.0 : Math.max(0.0, raw[i]);
            if (Double.isNaN(w) || Double.isInfinite(w)) {
                w = 0.0;
            }
            weights[i] = w;
            total += w;
        }
        if (total > 0.0) {
            for (int i = 0; i < weights.length; i++) {
                weights[i] /= total;
            }
            return weights;
        }
        int occupied = loadedAssemblyCount();
        if (occupied == 0) {
            return weights;
        }
        double even = 1.0 / occupied;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = positions[i] == null ? 0.0 : even;
        }
        return weights;
    }

    // ---------------------------------------------------------------
    // Aggregates for the kinetics
    // ---------------------------------------------------------------

    /** Flux-weighted core k-infinity, dimensionless. */
    public double aggregateKInf() {
        return weightedAverage(FuelAssembly::kInf, 0.0);
    }

    /**
     * Fission-rate-weighted effective delayed neutron fraction, dimensionless.
     * See the class note on beta: the isotope owns beta, the geometry owns the
     * weighting.
     */
    public double effectiveBeta() {
        return weightedAverage(FuelAssembly::beta, EMPTY_CORE_BETA);
    }

    /** Flux-weighted effective prompt neutron lifetime, seconds. */
    public double effectivePromptLifetimeSeconds() {
        return weightedAverage(FuelAssembly::promptLifetimeSeconds, EMPTY_CORE_PROMPT_LIFETIME_SECONDS);
    }

    /** Flux-weighted Doppler coefficient, dk/k per degree C. Negative. */
    public double effectiveDopplerCoeffPerC() {
        return weightedAverage(FuelAssembly::dopplerCoeffPerC, EMPTY_CORE_DOPPLER_PER_C);
    }

    /**
     * Flux-weighted recoverable energy per fission, MeV.
     *
     * <p>SPEC 2.1's parameter table lists {@code heat_per_fission_mev} as
     * controlling thermal output and {@link FuelTypeSpec#REQUIRED_FIELDS} refuses
     * an entry that omits it. It reaches the thermal path as a ratio —
     * {@link #heatPerFissionScaleFactor()} — which {@code ReactorCore} multiplies
     * into the fission rate at the one point that rate becomes heat. A pack author
     * doubling this figure doubles the megawatts a given flux produces, and
     * therefore the steam, the fuel temperature, the void and the burnup rate;
     * the neutron instruments go on reading flux, because that is what they
     * measure.
     */
    public double effectiveHeatPerFissionMeV() {
        return weightedAverage(FuelAssembly::heatPerFissionMeV, EMPTY_CORE_HEAT_PER_FISSION_MEV);
    }

    /**
     * Recoverable energy per fission of the loaded core relative to the
     * {@value #EMPTY_CORE_HEAT_PER_FISSION_MEV} MeV the plant's rated thermal
     * power is quoted against, dimensionless. Exactly 1.0 for a core of ordinary
     * uranium fuel.
     *
     * <p>This is the factor SPEC 2.1 promises when it says
     * {@code heat_per_fission_mev} controls thermal output: at a given fission
     * rate a core of fuel releasing more energy per fission makes proportionally
     * more heat. {@code ReactorCore.getNeutronPowerFraction()} and
     * {@code getDecayHeatFraction()} multiply by it and nothing else does, so fuel
     * temperature, void, the vessel energy balance and burnup all follow from it
     * without any of them needing to know it exists.
     *
     * <p>Published as a plain ratio rather than applied here because
     * {@code CoreLoading} owns the fuel, not the plant's power rating, and
     * because a scale factor is the smallest thing that can be handed across that
     * boundary.
     *
     * <p>Exactly 1.0, bit for bit, whenever every loaded assembly quotes the
     * reference figure — {@code x / x} is exact — and that includes an empty core,
     * which falls back to the reference. Both shipped uranium fuels do quote it,
     * so no default loading moves.
     */
    public double heatPerFissionScaleFactor() {
        double reference = EMPTY_CORE_HEAT_PER_FISSION_MEV;
        double effective = effectiveHeatPerFissionMeV();
        if (!(effective > 0.0) || !Double.isFinite(effective)) {
            return 1.0;
        }
        return effective / reference;
    }

    private interface AssemblyProperty {
        double of(FuelAssembly assembly);
    }

    private double weightedAverage(AssemblyProperty property, double emptyCoreValue) {
        if (loadedAssemblyCount() == 0) {
            return emptyCoreValue;
        }

        // A property with the same value in every loaded assembly has that value
        // as its weighted average under any weighting whatsoever, so it is
        // returned directly. That is not an optimisation. Summing and dividing
        // instead would leave the answer depending on the weights in the last
        // bits of the mantissa, and the weights come from an iterative spatial
        // solve whose final bits depend on its convergence history — how many
        // sweeps it took, what shape it warm-started from. A single-fuel core
        // would then report a slightly different k-infinity, and therefore a
        // slightly different reactivity, after a chunk reload than before one,
        // purely because the solver arrived by a different route. Persistence
        // here is required to be bit-exact (SPEC 11), so the dependence has to
        // not exist rather than merely be small.
        double uniform = 0.0;
        boolean seen = false;
        boolean identical = true;
        for (FuelAssembly assembly : positions) {
            if (assembly == null) {
                continue;
            }
            double value = property.of(assembly);
            if (!seen) {
                uniform = value;
                seen = true;
            } else if (Double.compare(value, uniform) != 0) {
                identical = false;
                break;
            }
        }
        if (identical) {
            return uniform;
        }

        double[] weights = powerWeights();
        double sum = 0.0;
        double weightTotal = 0.0;
        for (int i = 0; i < positions.length; i++) {
            FuelAssembly assembly = positions[i];
            if (assembly == null || weights[i] <= 0.0) {
                continue;
            }
            sum += weights[i] * property.of(assembly);
            weightTotal += weights[i];
        }
        return weightTotal > 0.0 ? sum / weightTotal : emptyCoreValue;
    }

    // ---------------------------------------------------------------
    // Inventory
    // ---------------------------------------------------------------

    /** Total heavy metal loaded, tonnes. */
    public double totalHeavyMetalTonnes() {
        double total = 0.0;
        for (FuelAssembly assembly : positions) {
            if (assembly != null) {
                total += assembly.heavyMetalMassTonnes();
            }
        }
        return total;
    }

    /**
     * Core-average burnup, MWd/tonne — mass-weighted, not flux-weighted,
     * because this is an inventory figure rather than a reactivity one.
     */
    public double averageBurnupMwdPerTonne() {
        double mass = 0.0;
        double exposure = 0.0;
        for (FuelAssembly assembly : positions) {
            if (assembly != null) {
                double tonnes = assembly.heavyMetalMassTonnes();
                mass += tonnes;
                exposure += tonnes * assembly.burnupMwdPerTonne();
            }
        }
        return mass > 0.0 ? exposure / mass : 0.0;
    }

    /** Highest burnup in the core, MWd/tonne — the bundle a shuffle should move first. */
    public double peakBurnupMwdPerTonne() {
        double peak = 0.0;
        for (FuelAssembly assembly : positions) {
            if (assembly != null) {
                peak = Math.max(peak, assembly.burnupMwdPerTonne());
            }
        }
        return peak;
    }

    /**
     * Burns the core for a slice of time, distributing exposure by power
     * weight. Central bundles accumulate faster, which is what gives a core a
     * burnup gradient and makes shuffling worth doing.
     *
     * @param coreThermalMW total core thermal power, MW
     * @param seconds       elapsed time, seconds
     */
    public void advanceBurnup(double coreThermalMW, double seconds) {
        if (!(coreThermalMW > 0.0) || !(seconds > 0.0) || loadedAssemblyCount() == 0) {
            return;
        }
        double[] weights = powerWeights();
        for (int i = 0; i < positions.length; i++) {
            FuelAssembly assembly = positions[i];
            if (assembly != null && weights[i] > 0.0) {
                assembly.accumulateBurnup(weights[i] * coreThermalMW, seconds);
            }
        }
        invalidateWeights();
    }

    // ---------------------------------------------------------------
    // Criticality and end of cycle
    // ---------------------------------------------------------------

    public double nonLeakageProbability() {
        return nonLeakageProbability;
    }

    public void setNonLeakageProbability(double value) {
        if (!(value > 0.0) || value > 1.0) {
            throw new IllegalArgumentException("nonLeakageProbability must be in (0,1], was " + value);
        }
        this.nonLeakageProbability = value;
        invalidateWeights();
    }

    /**
     * Effective multiplication factor of the fuel alone with every rod
     * withdrawn: k_inf x non-leakage, cold and clean.
     *
     * <p>Deliberately excludes rods, voids, Doppler, xenon and boron. Those are
     * reactivity terms the reactor model adds on top; this is the question
     * "does the fuel still have anything left in it".
     */
    public double kEffAllRodsOut() {
        return aggregateKInf() * nonLeakageProbability / (1.0 + insertAbsorptionRatio());
    }

    /**
     * Excess reactivity of the fuel with every rod withdrawn, dk/k. Negative
     * once the core can no longer be made critical on fuel alone.
     */
    public double excessReactivityAllRodsOutDkK() {
        double kEff = kEffAllRodsOut();
        return kEff > 0.0 ? (kEff - 1.0) / kEff : -1.0;
    }

    /**
     * End of cycle: the core can no longer reach criticality with all rods
     * withdrawn (SPEC 2.3).
     *
     * <p>This is a computed physical property of the loaded fuel, not a
     * setpoint and not a judgement about whether the plant should shut down.
     * Operating at power will hit this wall earlier than the number suggests,
     * because xenon, voids and fuel temperature each subtract reactivity that
     * this figure does not include — noticing that and planning an outage is
     * the player's job.
     */
    public boolean isEndOfCycle() {
        return kEffAllRodsOut() <= 1.0;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Bessel function of the first kind, order zero, by power series. Only ever
     * called with arguments in [0, 2.405] where the series converges in a
     * handful of terms.
     */
    private static double besselJ0(double x) {
        double halfSquared = 0.25 * x * x;
        double term = 1.0;
        double sum = 1.0;
        for (int k = 1; k <= 12; k++) {
            term *= -halfSquared / (k * (double) k);
            sum += term;
            if (Math.abs(term) < 1.0e-15) {
                break;
            }
        }
        return sum;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= positions.length) {
            throw new IndexOutOfBoundsException(
                    "lattice position " + index + " outside 0.." + (positions.length - 1));
        }
    }

    private void checkCoordinate(int value, String what) {
        if (value < 0 || value >= latticeWidth) {
            throw new IndexOutOfBoundsException(
                    what + " " + value + " outside 0.." + (latticeWidth - 1));
        }
    }

    @Override
    public String toString() {
        return String.format(
                "CoreLoading[%dx%d, %d loaded, k_inf=%.4f, k_eff(ARO)=%.4f, beta_eff=%.6f, burnup=%.0f MWd/t]",
                latticeWidth, latticeWidth, loadedAssemblyCount(), aggregateKInf(),
                kEffAllRodsOut(), effectiveBeta(), averageBurnupMwdPerTonne());
    }
}
