package dev.bwr.core.nodal;

import dev.bwr.core.CoreConfig;
import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.core.thermal.PressureVessel;

import java.util.Arrays;

/**
 * Coarse-mesh nodal diffusion: the spatial half of the neutronics,
 * {@code SPEC.md} section 1.3 and build-order step 2.
 *
 * <h2>What it solves</h2>
 * One-group neutron diffusion over the mesh of {@link NodalMesh}, as an
 * eigenvalue problem:
 *
 * <pre>
 *   sum_faces A_f d_f (phi_c - phi_n)  +  Sigma_a,c V phi_c  =  (1/k) nu Sigma_f,c V phi_c
 * </pre>
 *
 * Each cell's flux depends on its own multiplication and on leakage to and from
 * its six neighbours, and the whole thing is relaxed by Gauss-Seidel sweeps
 * inside a power iteration on {@code k}. Every solve starts from the same
 * analytic guess and runs to the same tolerance, so it is the current
 * occupancy, rod pattern and void that decide the answer and nothing else.
 *
 * <p><b>Not ray tracing, not Monte Carlo.</b> Both are explicitly rejected by
 * the spec: too slow for a server tick, and statistically noisy in a way that
 * would make the reported power jitter visibly. This is a deterministic sparse
 * solve — the same inputs give bit-identical outputs, every time.
 *
 * <p>That last sentence is a load-bearing guarantee, not a boast. Flux shape
 * reaches total reactivity through the per-rod weights of
 * {@link FluxSolution#rodFluxWeights()}, so a solve that depended on the
 * sequence of solves before it would give a reactor that had been running and
 * the same reactor restored from a snapshot two different reactivities for the
 * same physical state. {@link #seedFluxFromGeometry()} records what that costs
 * and what the alternatives were measured to do.
 *
 * <h2>The timescale separation — the improved quasi-static method</h2>
 * This class is called at <b>1 Hz</b>, on
 * {@link CoreConfig#nodalSolveIntervalTicks}. Point kinetics runs every tick,
 * sub-stepped, on the {@code beta_eff} and the power weights this solve
 * produced.
 *
 * <p><b>Between solves, beta_eff and the shape weights are frozen, and they must
 * stay that way.</b> That is not an optimisation, it is the invariant that keeps
 * the model well-posed. Flux <i>shape</i> is driven by rod motion, burnup and
 * xenon, all of which move over seconds to hours; flux <i>level</i> is driven by
 * prompt neutrons on a 4e-5 s lifetime. Recomputing the shape inside the kinetics
 * sub-step would couple the fast level equation to a slow spatial one at
 * 20-microsecond resolution, reintroducing exactly the stiffness the implicit
 * integrator exists to avoid, and it would do it against inputs — void fraction,
 * rod position — that only have tick resolution in the first place. The result
 * would be a numerical oscillation that no reactor has. Anyone tempted to
 * "improve" the fidelity by solving more often should raise the solve rate, not
 * move the solve inside the sub-step loop.
 *
 * <h2>Beta is not geometric</h2>
 * This solver never computes a beta. It computes <i>where the fissions are</i>,
 * and hands that out as {@link FluxSolution#assemblyWeights()}. Beta is a nuclear
 * property of the fissioning isotope — a plutonium bundle has beta = 0.002099
 * wherever it is parked — so the core's effective beta is the fission-rate
 * weighted average of the assemblies' betas and falls out as a weighted sum
 * through {@link CoreLoading#effectiveBeta()}, or directly through
 * {@link FluxSolution#fissionWeightedAverage}. Getting this backwards — deriving
 * beta from geometry — is the classic error in this corner of the model.
 *
 * <h2>What it buys</h2>
 * A centre-to-periphery flux gradient, rod shadowing, loading patterns that
 * matter, local absorption depressing nearby flux, and a visible hot spot around
 * a stuck rod. All of it emergent from the same solve; none of it special-cased.
 *
 * <h2>What it refuses to do</h2>
 * It reports flux and fission share. It has no opinion about whether a peaking
 * factor is acceptable, does not move rods, and contains no setpoint of any
 * kind. Measurements and actuators, never judgements.
 *
 * <p>Allocation-light and not thread safe: every working array is allocated once
 * per mesh and reused on every solve. One instance per reactor, solved from the
 * server thread.
 */
public final class NodalFluxSolver implements CoreLoading.PowerWeightSource {

    // ---------------------------------------------------------------
    // Nuclear data. One-group, homogenised over the bundle.
    // ---------------------------------------------------------------

    /**
     * Macroscopic absorption cross-section of the fuel lattice, per cm. A typical
     * thermal LWR value; it sets the scale of everything else, since k-infinity
     * is defined against it as {@code nuSigma_f = k_inf * Sigma_a}.
     */
    public static final double DEFAULT_ABSORPTION_PER_CM = 0.02;

    /**
     * Migration area, cm^2. {@code M^2 = D / Sigma_a}, so this and the absorption
     * cross-section fix the diffusion coefficient at 1.2 cm. Migration length
     * comes out at 7.7 cm against a 15.24 cm bundle pitch, which is what makes
     * neighbour coupling strong enough to shadow but weak enough to keep a
     * gradient — a real LWR sits exactly there.
     */
    public static final double DEFAULT_MIGRATION_AREA_CM2 = 60.0;

    /**
     * Albedo of the water reflector surrounding the active core, dimensionless.
     * Returned current over outgoing current at the boundary: 0 is a black
     * absorber and 1 is a perfect mirror. A thick water reflector returns most of
     * what enters it, which is why peripheral bundles make real power instead of
     * sitting at the zero flux a bare-core Bessel shape would give them. At this
     * value the outermost bundles of a uniform core run at about a tenth of the
     * peak — the same order as the {@code REFLECTOR_EDGE_FLOOR} the analytic
     * stand-in in {@link CoreLoading} assumes.
     */
    public static final double DEFAULT_REFLECTOR_ALBEDO = 0.75;

    /**
     * k-infinity lost per unit of local void fraction. Steam is a poor moderator,
     * so a voided node multiplies worse than a flooded one, and that is what
     * tilts the axial power shape toward the bottom of a boiling core.
     *
     * <p><b>This is the shape effect of void only, and it is deliberately
     * smaller than the plant's void reactivity.</b> The scalar void reactivity
     * belongs to {@link dev.bwr.core.kinetics.ReactivityBalance} — one model
     * decides where the fissions are, the other decides how many, and neither
     * should be asked to do the other's job. Setting this to the full void worth
     * would double-count it, and it would also be wrong on its own terms: a
     * one-group k-infinity depression exaggerates the axial tilt badly compared
     * with the spectral treatment a real two-group solve gives, driving the top
     * of the core toward zero power in a way no operating BWR shows. Calibrated
     * instead against the axial shape: at a rated void distribution the core
     * peaks around a third of the height up with an axial peaking factor near
     * 1.8, which is a recognisable bottom-peaked BWR profile.
     */
    public static final double DEFAULT_VOID_KINF_DEPRESSION = 0.02;

    /**
     * How much the diffusion coefficient grows with void, as
     * {@code D = D0 / (1 - f * void)}. Fewer water molecules means longer mean
     * free paths, so a voided upper core leaks and smears more than a flooded
     * lower one.
     */
    public static final double DEFAULT_VOID_DIFFUSION_EXPANSION = 0.8;

    /**
     * Shape of the axial void profile above the boiling boundary:
     * {@code void = exit * u^exponent} with {@code u} the fractional height
     * through the boiling length. Below one because void climbs steeply as soon
     * as boiling starts and then saturates — a drift-flux curve in one number.
     */
    public static final double DEFAULT_AXIAL_VOID_EXPONENT = 0.5;

    // ---------------------------------------------------------------
    // Solver settings
    // ---------------------------------------------------------------

    /** Gauss-Seidel sweeps per power iteration. */
    public static final int DEFAULT_SWEEPS_PER_ITERATION = 2;

    /**
     * Successive over-relaxation factor for the sweeps. One is plain
     * Gauss-Seidel; above one each cell is pushed past its new value on the
     * grounds that it will have to travel further anyway, which for a smooth
     * diffusion field is right and cuts the sweep count by several times. Two
     * diverges.
     */
    public static final double DEFAULT_OVER_RELAXATION = 1.85;

    /**
     * How far above the eigenvalue the Wielandt shift sits, as a fraction.
     *
     * <p>An unshifted power iteration converges at the ratio between the second
     * eigenvalue and the first, and for a core this size those two are within a
     * few percent of each other — hundreds of iterations to settle. Shifting the
     * operator by a {@code k} just above the fundamental pulls that ratio down
     * hard. Small is fast; too small and the shifted operator stops being
     * diagonally dominant and the sweeps stop converging, so the shift is also
     * floored at the most reactive cell's own multiplication — see
     * {@link #shiftFloor}.
     */
    public static final double DEFAULT_SHIFT_MARGIN = 0.02;

    /** Cap on power iterations, so a pathological core cannot stall the tick. */
    public static final int DEFAULT_MAXIMUM_ITERATIONS = 100;

    /** Relative convergence tolerance on both the eigenvalue and the flux. */
    public static final double DEFAULT_TOLERANCE = 1.0e-5;

    // ---------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------

    /** Centimetres per inch, for the plant's inch-denominated active fuel height. */
    private static final double CM_PER_INCH = 2.54;

    private final CoreLoading loading;
    private final int axialNodes;

    private double absorptionPerCm = DEFAULT_ABSORPTION_PER_CM;
    private double migrationAreaCm2 = DEFAULT_MIGRATION_AREA_CM2;
    private double reflectorAlbedo = DEFAULT_REFLECTOR_ALBEDO;
    private double voidKInfDepression = DEFAULT_VOID_KINF_DEPRESSION;
    private double voidDiffusionExpansion = DEFAULT_VOID_DIFFUSION_EXPANSION;
    private double axialVoidExponent = DEFAULT_AXIAL_VOID_EXPONENT;
    private double rodAbsorptionPerCm;

    private int sweepsPerIteration = DEFAULT_SWEEPS_PER_ITERATION;
    private double overRelaxation = DEFAULT_OVER_RELAXATION;
    private double shiftMargin = DEFAULT_SHIFT_MARGIN;
    private int maximumIterations = DEFAULT_MAXIMUM_ITERATIONS;
    private double tolerance = DEFAULT_TOLERANCE;

    // ---------------------------------------------------------------
    // Inputs, pushed in by the reactor once per solve interval
    // ---------------------------------------------------------------

    private final double[] rodPositionNotches;
    private RodLatticeMap rodLatticeMap;
    /**
     * True when {@link #setRodLatticeMap} supplied the map in force, false when
     * it is the {@link RodLatticeMap#centreOutward} default.
     *
     * <p>This has to be tracked rather than inferred. The default depends on
     * <i>occupancy</i> — {@code centreOutward} skips 2x2 groups holding no fuel
     * — so it must be rebuilt whenever the loading pattern changes, while a
     * caller-supplied map must survive exactly that. Inferring "somebody gave me
     * this" from {@code rodLatticeMap.positionCount() != loading.positionCount()}
     * cannot distinguish the two, because both maps always have one entry per
     * lattice position and {@code CoreLoading.positionCount()} is fixed for the
     * life of the object. That inference is what silently froze an all-empty
     * default map over a core that was subsequently filled — see
     * {@link #ensureMesh()}.
     */
    private boolean rodLatticeMapIsExplicit;
    /** Set when the map or the mesh under it changed and the map needs rebuilding or recounting. */
    private boolean rodLatticeMapDirty = true;
    private double boilingBoundaryFraction = 1.0;
    private double exitVoidFraction;
    private double[] supplementalAbsorptionPerCm;

    // ---------------------------------------------------------------
    // Working state, allocated once per mesh
    // ---------------------------------------------------------------

    private NodalMesh mesh;
    private long meshSignature;

    private double[] flux;
    /**
     * The flux at the previous <b>outer iteration</b>, for the shape-convergence
     * test — not the previous solve, and not a starting point. Nothing may seed a
     * solve from a previous solve's answer; see {@link #seedFluxFromGeometry()}.
     */
    private double[] previousFlux;
    private double[] nuSigmaFVolume;
    /**
     * Absorption rate per unit flux in each cell, {@code Sigma_a * V}, including
     * rods and supplemental absorbers but <b>excluding</b> leakage.
     *
     * <p>Kept separately from {@link #removal} because {@code removal} has the
     * face couplings folded into it by {@link #buildCoupling()} and there is no
     * way to get the absorption back out afterwards. The whole-core neutron
     * balance needs the two apart: summing the diffusion equation over every cell
     * cancels the interior faces pairwise and leaves
     * {@code k = production / (absorption + boundary leakage)}, so
     * {@code absorption / (absorption + boundary leakage)} — the actual
     * non-leakage probability — is {@code k} divided by
     * {@code production / absorption}, and that divisor is this array's job.
     */
    private double[] absorptionVolume;
    private double[] removal;
    private double[] shiftedRemoval;
    private double[] coupling;
    /**
     * Neighbour index per face, with a boundary face pointing back at its own
     * node. Paired with a zero coupling there, that lets the sweep read six
     * neighbours unconditionally: no branch in the innermost loop of the whole
     * physics model, and a boundary cell contributes zero to itself.
     */
    private int[] sweepNeighbour;
    private double[] diffusion;
    private double[] fissionRate;
    private double[] assemblyAccumulator;
    private double[] axialAccumulator;
    private double[] rodAccumulator;
    private int[] rodPositionCount;

    // ---------------------------------------------------------------
    // The assembled problem as the last iterating solve saw it
    // ---------------------------------------------------------------

    /**
     * Copies of exactly what {@link #powerIterate()} reads, kept so that a solve
     * of an unchanged problem can hand back the flux it already has instead of
     * re-deriving it.
     *
     * <p>These are the <i>outputs</i> of {@link #buildNodeData()} and
     * {@link #buildCoupling()} rather than the inputs those two read, and that is
     * deliberate. A list of inputs has to be maintained by hand — add a
     * cross-section, forget to add it here, and the memo starts serving a stale
     * flux, which is a silent physics fault of exactly the kind this class is
     * otherwise careful about. Comparing the assembled matrix instead cannot go
     * stale: anything that changes the answer has to change one of these arrays
     * to do it, because they are all the answer is computed from. The cost is
     * about 1.2 MB per reactor, allocated once per mesh like everything else here.
     */
    private double[] storedNuSigmaFVolume;
    private double[] storedRemoval;
    private double[] storedCoupling;
    private double storedShiftFloor;
    private long storedMeshSignature;
    private int storedSweepsPerIteration;
    private double storedOverRelaxation;
    private double storedShiftMargin;
    private int storedMaximumIterations;
    private double storedTolerance;
    /** False until an iterating solve has filled the arrays above. */
    private boolean assembledProblemStored;

    private double shiftFloor = 1.0;
    private double multiplicationFactor = 1.0;
    private double lastEigenvalueResidual;
    private double lastFluxResidual;
    private FluxSolution lastSolution;

    /** A solver for a core, at {@link NodalMesh#DEFAULT_AXIAL_NODES} axial nodes. */
    public NodalFluxSolver(CoreConfig config, CoreLoading loading) {
        this(config, loading, NodalMesh.DEFAULT_AXIAL_NODES);
    }

    /**
     * @param config     supplies the rod count and the total rod worth the rod
     *                   absorption cross-section is calibrated against
     * @param loading    the core to solve. Held by reference: the solver sees
     *                   shuffles, burnup and refuelling as they happen
     * @param axialNodes axial cells per assembly. One collapses the solve to a
     *                   radial-only problem, which is a legitimate cheap mode
     */
    public NodalFluxSolver(CoreConfig config, CoreLoading loading, int axialNodes) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (loading == null) {
            throw new IllegalArgumentException("loading must not be null");
        }
        if (axialNodes < 1) {
            throw new IllegalArgumentException("axialNodes must be at least 1, was " + axialNodes);
        }
        this.loading = loading;
        this.axialNodes = axialNodes;
        this.rodPositionNotches = new double[Math.max(1, config.controlRodCount)];
        // Rods default to fully withdrawn: a solver nobody has told about rod
        // positions should report the unrodded shape, not a shut-down core.
        Arrays.fill(rodPositionNotches, RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        this.rodAbsorptionPerCm = calibratedRodAbsorptionPerCm(config.totalRodWorth);
    }

    /**
     * Smeared absorber cross-section that reproduces a given total rod worth.
     *
     * <p>With every rod in, absorption per node rises from {@code Sigma_a} to
     * {@code Sigma_a + Sigma_rod} and multiplication falls by the ratio between
     * them, so a worth of {@code w} wants
     * {@code Sigma_rod = Sigma_a * w / (1 - w)}. That keeps the shadowing this
     * solve produces consistent in magnitude with the scalar worth
     * {@link RodWorth} charges for the same rods, without either one deriving
     * from the other.
     */
    private double calibratedRodAbsorptionPerCm(double totalRodWorthDkOverK) {
        double worth = Math.min(0.95, Math.abs(totalRodWorthDkOverK));
        return absorptionPerCm * worth / (1.0 - worth);
    }

    // ---------------------------------------------------------------
    // Inputs
    // ---------------------------------------------------------------

    /**
     * Where every control rod is, notch index, 0 fully inserted to 24 fully
     * withdrawn. Copied, not retained. Individual positions — never banks — are
     * what make shadowing and a stuck rod's hot spot appear.
     */
    public void setRodNotchIndices(int[] notchIndices) {
        setRodPositions(notchIndices==null ? null : java.util.Arrays.stream(notchIndices).asDoubleStream().toArray());
    }

    /** Physical absorber travel in notch units; evaluated only on a spatial refresh. */
    public void setRodPositions(double[] positions) {
        if(positions==null) { Arrays.fill(rodPositionNotches,RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);return; }
        for(int rod=0;rod<rodPositionNotches.length;rod++) {
            double p=rod<positions.length?positions[rod]:RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN;
            if(!Double.isFinite(p) || p<0 || p>RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN)
                throw new IllegalArgumentException("rod position out of range: "+p);
            rodPositionNotches[rod]=p;
        }
    }

    /** Rounded notch snapshot for existing callers; the solve uses fractional positions. */
    public int[] getRodNotchIndices() {
        return java.util.Arrays.stream(rodPositionNotches).mapToInt(p->(int)Math.round(p)).toArray();
    }
    public double[] getRodPositions() { return rodPositionNotches.clone(); }

    /**
     * Replace the rod-to-lattice mapping, or pass {@code null} to go back to the
     * {@link RodLatticeMap#centreOutward} default over the current loading.
     *
     * <p><b>A caller that has its own rod numbering must supply a map, and the
     * default is not a substitute.</b> The default assigns rod indices by
     * distance rank — rod 0 owns the centre-most 2x2 group — because with no
     * outside information that is the only ordering the loading itself implies.
     * Anything that generates rod hardware in a different order, the multiblock's
     * raster walk over structure validation being the case in hand, ends up with
     * its rod {@code i} and this solver's rod {@code i} pointing at different
     * parts of the core. The two index spaces are then a permutation of each
     * other, and the symptom is subtle: shadowing, peaking and rod flux weights
     * all still look plausible, they are simply attributed to the wrong rods, so
     * a stuck peripheral rod is modelled as a stuck central one. Supply the real
     * mapping here and the question does not arise.
     *
     * <p>An explicitly supplied map is retained across refuelling and re-meshing;
     * only the default is rebuilt when occupancy changes.
     *
     * @param map one entry per lattice position of this solver's core loading,
     *            covering exactly this core's rod count
     */
    public void setRodLatticeMap(RodLatticeMap map) {
        if (map != null && map.rodCount() != rodPositionNotches.length) {
            throw new IllegalArgumentException("rod lattice map covers " + map.rodCount()
                    + " rods, this core has " + rodPositionNotches.length);
        }
        // A map sized for a different lattice is not a smaller map, it is a
        // wrong one: rodAtPosition() answers -1 outside its own range, so the
        // mismatch would show up as rod shadowing that silently stops partway
        // across the core rather than as an error.
        if (map != null && map.positionCount() != loading.positionCount()) {
            throw new IllegalArgumentException("rod lattice map covers " + map.positionCount()
                    + " lattice positions, this core has " + loading.positionCount());
        }
        this.rodLatticeMap = map;
        this.rodLatticeMapIsExplicit = map != null;
        this.rodLatticeMapDirty = true;
    }

    /** True when the mapping in force came from {@link #setRodLatticeMap}, not from the default. */
    public boolean hasExplicitRodLatticeMap() {
        return rodLatticeMapIsExplicit;
    }

    /** The rod-to-lattice mapping in force, building the default if none was supplied. */
    public RodLatticeMap rodLatticeMap() {
        ensureMesh();
        return rodLatticeMap;
    }

    /**
     * The axial void distribution, from the thermal hydraulics.
     *
     * <p>Void below the boiling boundary is zero, and above it climbs to the exit
     * value. This is what tilts the axial flux shape toward the bottom of a
     * boiling core, and it is the reason a BWR's axial power shape is not a
     * symmetric cosine.
     *
     * @param boilingBoundaryFraction height fraction at which boiling starts, 0 to 1
     * @param exitVoidFraction        void fraction at the top of the active fuel, 0 to 1
     */
    public void setVoidProfile(double boilingBoundaryFraction, double exitVoidFraction) {
        this.boilingBoundaryFraction = clamp(boilingBoundaryFraction, 0.0, 1.0);
        this.exitVoidFraction = clamp(exitVoidFraction, 0.0, 0.99);
    }

    /**
     * Extra absorption at one lattice position, per cm, uniform over the height.
     *
     * <p>A tritium target rod ({@code SPEC.md} section 2.5) is mechanically a
     * neutron absorber: it eats flux, costs reactivity and depresses the power of
     * everything around it. So does a hafnium insert, and so does anything else a
     * player parks in the lattice. Additive with rod absorption.
     */
    public void setSupplementalAbsorptionPerCm(int latticePosition, double perCm) {
        if (latticePosition < 0 || latticePosition >= loading.positionCount()) {
            throw new IndexOutOfBoundsException("lattice position " + latticePosition
                    + " outside 0.." + (loading.positionCount() - 1));
        }
        if (!(perCm >= 0.0) || !Double.isFinite(perCm)) {
            throw new IllegalArgumentException(
                    "supplemental absorption must be finite and non-negative, got " + perCm);
        }
        if (supplementalAbsorptionPerCm == null) {
            supplementalAbsorptionPerCm = new double[loading.positionCount()];
        }
        supplementalAbsorptionPerCm[latticePosition] = perCm;
    }

    /** Extra absorption at one lattice position, per cm. */
    public double getSupplementalAbsorptionPerCm(int latticePosition) {
        if (latticePosition < 0 || latticePosition >= loading.positionCount()) {
            throw new IndexOutOfBoundsException("lattice position " + latticePosition
                    + " outside 0.." + (loading.positionCount() - 1));
        }
        return supplementalAbsorptionPerCm == null ? 0.0 : supplementalAbsorptionPerCm[latticePosition];
    }

    /** Remove every supplemental absorber. */
    public void clearSupplementalAbsorption() {
        supplementalAbsorptionPerCm = null;
    }

    /** Absorber cross-section a fully inserted control rod smears over its bundles, per cm. */
    public double getRodAbsorptionPerCm() {
        return rodAbsorptionPerCm;
    }

    /** @see #getRodAbsorptionPerCm() */
    public void setRodAbsorptionPerCm(double perCm) {
        if (!(perCm >= 0.0) || !Double.isFinite(perCm)) {
            throw new IllegalArgumentException(
                    "rod absorption must be finite and non-negative, got " + perCm);
        }
        this.rodAbsorptionPerCm = perCm;
    }

    // ---------------------------------------------------------------
    // Tunables
    // ---------------------------------------------------------------

    public double getMigrationAreaCm2() {
        return migrationAreaCm2;
    }

    /** Migration area, cm^2. Larger means longer-range coupling and a flatter core. */
    public void setMigrationAreaCm2(double value) {
        requirePositive(value, "migration area");
        this.migrationAreaCm2 = value;
    }

    public double getAbsorptionPerCm() {
        return absorptionPerCm;
    }

    /** Fuel lattice absorption cross-section, per cm. */
    public void setAbsorptionPerCm(double value) {
        requirePositive(value, "absorption cross-section");
        this.absorptionPerCm = value;
    }

    public double getReflectorAlbedo() {
        return reflectorAlbedo;
    }

    /** Reflector albedo, 0 for a black boundary to just under 1 for a mirror. */
    public void setReflectorAlbedo(double value) {
        if (!(value >= 0.0) || !(value < 1.0)) {
            throw new IllegalArgumentException("albedo must be in [0,1), got " + value);
        }
        this.reflectorAlbedo = value;
    }

    public double getVoidKInfDepression() {
        return voidKInfDepression;
    }

    /** @see #DEFAULT_VOID_KINF_DEPRESSION */
    public void setVoidKInfDepression(double value) {
        if (!(value >= 0.0) || !(value < 1.0)) {
            throw new IllegalArgumentException("void k-infinity depression must be in [0,1), got " + value);
        }
        this.voidKInfDepression = value;
    }

    public double getVoidDiffusionExpansion() {
        return voidDiffusionExpansion;
    }

    /** @see #DEFAULT_VOID_DIFFUSION_EXPANSION */
    public void setVoidDiffusionExpansion(double value) {
        if (!(value >= 0.0) || !(value < 1.0)) {
            throw new IllegalArgumentException("void diffusion expansion must be in [0,1), got " + value);
        }
        this.voidDiffusionExpansion = value;
    }

    public int getSweepsPerIteration() {
        return sweepsPerIteration;
    }

    /** Gauss-Seidel sweeps per power iteration. */
    public void setSweepsPerIteration(int sweeps) {
        if (sweeps < 1) {
            throw new IllegalArgumentException("sweeps per iteration must be at least 1, got " + sweeps);
        }
        this.sweepsPerIteration = sweeps;
    }

    public double getOverRelaxation() {
        return overRelaxation;
    }

    /** @see #DEFAULT_OVER_RELAXATION */
    public void setOverRelaxation(double factor) {
        if (!(factor > 0.0) || !(factor < 2.0)) {
            throw new IllegalArgumentException("over-relaxation must be in (0,2), got " + factor);
        }
        this.overRelaxation = factor;
    }

    public double getShiftMargin() {
        return shiftMargin;
    }

    /**
     * Wielandt shift margin. Larger is safer and slower; a very large value is
     * an unshifted power iteration, which is the reference the shifted answer
     * must agree with.
     *
     * @see #DEFAULT_SHIFT_MARGIN
     */
    public void setShiftMargin(double margin) {
        requirePositive(margin, "shift margin");
        this.shiftMargin = margin;
    }

    public int getMaximumIterations() {
        return maximumIterations;
    }

    /** Cap on power iterations per solve. */
    public void setMaximumIterations(int iterations) {
        if (iterations < 1) {
            throw new IllegalArgumentException("maximum iterations must be at least 1, got " + iterations);
        }
        this.maximumIterations = iterations;
    }

    public double getTolerance() {
        return tolerance;
    }

    /** Relative convergence tolerance on the eigenvalue and the flux. */
    public void setTolerance(double value) {
        requirePositive(value, "tolerance");
        this.tolerance = value;
    }

    /** Axial cells per assembly this solver was built with. */
    public int axialNodes() {
        return axialNodes;
    }

    /** The mesh currently in use, building it if the loading has changed. */
    public NodalMesh mesh() {
        ensureMesh();
        return mesh;
    }

    // ---------------------------------------------------------------
    // The solve
    // ---------------------------------------------------------------

    /**
     * Re-solve the flux shape and return it. Call once per
     * {@link CoreConfig#nodalSolveIntervalTicks}, and read the answer as often as
     * you like in between — see the class comment on why the shape must stay
     * frozen between solves.
     *
     * <p><b>This is a pure function of the solver's inputs.</b> The same
     * occupancy, burnup, rod pattern, void profile and supplemental absorption
     * give a bit-identical answer no matter what this instance solved before —
     * see {@link #seedFluxFromGeometry()} for why that matters and what it costs.
     * The only thing carried between solves is the answer itself, and only to be
     * handed straight back when the problem has not moved at all.
     */
    public FluxSolution solve() {
        ensureMesh();
        int nodeCount = mesh.nodeCount();
        if (nodeCount == 0) {
            assembledProblemStored = false;
            lastSolution = emptySolution();
            return lastSolution;
        }

        buildNodeData();
        buildCoupling();

        // Nothing about the problem moved, so neither did the answer. Hand back
        // the flux already in hand rather than re-deriving it: this is the memo
        // of a pure function, not a warm start, and it is the reason a core
        // nobody is touching costs nothing at all to keep solving at 1 Hz.
        if (assembledProblemIsUnmoved()) {
            lastSolution = collect(0);
            return lastSolution;
        }
        rememberAssembledProblem();

        // Every solve starts from the same analytic guess and the same
        // eigenvalue guess. That is what makes the answer a function of the
        // inputs alone.
        seedFluxFromGeometry();
        multiplicationFactor = 1.0;

        int iterations = powerIterate();
        lastSolution = collect(iterations);
        return lastSolution;
    }

    /** The most recent solve, or a fresh one if there has not been a solve yet. */
    public FluxSolution lastSolution() {
        return lastSolution == null ? solve() : lastSolution;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This is the hook {@link CoreLoading#setPowerWeightSource} exists for.
     * {@code CoreLoading} caches what it gets back and only asks again after
     * something invalidates the cache, so the reactor's once-per-second
     * invalidation is what sets the solve rate.
     */
    @Override
    public double[] weightsFor(CoreLoading loading) {
        if (loading != this.loading) {
            throw new IllegalStateException(
                    "this solver was built for a different core loading; build one per core");
        }
        return solve().assemblyWeights();
    }

    // ---------------------------------------------------------------
    // Mesh and working arrays
    // ---------------------------------------------------------------

    /**
     * Rebuild the mesh and the rod map if anything they depend on has moved.
     *
     * <p>The mesh signature covers occupancy and geometry, so a bundle loaded,
     * removed or shuffled invalidates both. <b>The default rod map depends on
     * occupancy too</b> — {@link RodLatticeMap#centreOutward} discards 2x2 groups
     * that hold no fuel, and ranks what is left by radius — so it has to be
     * rebuilt on exactly the same trigger. It used to be guarded by
     * {@code rodLatticeMap.positionCount() != loading.positionCount()}, which is
     * false forever after the first build because both sides are
     * {@code latticeWidth * latticeWidth}. The consequence was not subtle in a
     * running world: the multiblock's first solve happens on an <i>empty</i>
     * core, {@code centreOutward} finds no non-empty groups, every entry of the
     * map is -1, and that map is then frozen for the life of the block entity.
     * Load 441 bundles behind it and {@code rodAbsorptionAt} still returns zero
     * everywhere, so rod shadowing, the stuck-rod hot spot and rod-dependent
     * beta_eff are all silently absent no matter where the rods are. Track
     * ownership of the map instead of trying to infer it from its shape.
     */
    private void ensureMesh() {
        long signature = NodalMesh.signatureOf(loading, axialNodes,
                NodalMesh.DEFAULT_ASSEMBLY_PITCH_CM, activeHeightCm());
        if (mesh == null || signature != meshSignature) {
            mesh = NodalMesh.forLoading(loading, axialNodes);
            meshSignature = signature;
            allocate();
            seedFluxFromGeometry();
            multiplicationFactor = 1.0;
            // allocate() gives rodPositionCount a fresh array of zeros, so the
            // recount below is required even when the map itself is unchanged.
            rodLatticeMapDirty = true;
        }
        if (rodLatticeMapDirty || rodLatticeMap == null) {
            if (!rodLatticeMapIsExplicit) {
                rodLatticeMap = RodLatticeMap.centreOutward(loading, rodPositionNotches.length);
            }
            rodLatticeMapDirty = false;
            countRodPositions();
        }
    }

    private static double activeHeightCm() {
        return PressureVessel.ACTIVE_FUEL_HEIGHT_IN * CM_PER_INCH;
    }

    private void allocate() {
        int nodes = mesh.nodeCount();
        flux = new double[nodes];
        previousFlux = new double[nodes];
        nuSigmaFVolume = new double[nodes];
        absorptionVolume = new double[nodes];
        removal = new double[nodes];
        shiftedRemoval = new double[nodes];
        diffusion = new double[nodes];
        fissionRate = new double[nodes];
        coupling = new double[nodes * NodalMesh.FACES];
        sweepNeighbour = new int[nodes * NodalMesh.FACES];
        storedNuSigmaFVolume = new double[nodes];
        storedRemoval = new double[nodes];
        storedCoupling = new double[nodes * NodalMesh.FACES];
        assembledProblemStored = false;
        for (int node = 0; node < nodes; node++) {
            for (int face = 0; face < NodalMesh.FACES; face++) {
                int neighbour = mesh.neighbour(node, face);
                sweepNeighbour[node * NodalMesh.FACES + face] = neighbour >= 0 ? neighbour : node;
            }
        }
        assemblyAccumulator = new double[mesh.positionCount()];
        axialAccumulator = new double[mesh.axialNodes()];
        rodAccumulator = new double[rodPositionNotches.length];
        rodPositionCount = new int[rodPositionNotches.length];
    }

    private void countRodPositions() {
        Arrays.fill(rodPositionCount, 0);
        for (int rod = 0; rod < rodPositionNotches.length; rod++) {
            rodPositionCount[rod] = rodLatticeMap.positionCountOfRod(rod);
        }
    }

    /**
     * Initial guess: the analytic radial shape {@link CoreLoading} already
     * publishes, times a cosine in height. It depends on the mesh and on nothing
     * else, so it is the same guess on every solve.
     *
     * <h2>Why this is not a warm start, and must not become one again</h2>
     * Starting instead from the previous second's converged flux costs about a
     * third of the iterations when the shape has barely moved, and it was written
     * that way originally for exactly that reason. It is still wrong, because it
     * makes {@link #solve()} a function of the solver's <i>history</i> rather than
     * of its inputs: a core that has been running carries a long chain of warm
     * starts, a core just restored from a snapshot carries none, and the two
     * settle on answers that differ by up to the convergence tolerance. Once the
     * per-rod flux weights out of here are wired into
     * {@link dev.bwr.core.kinetics.RodWorth}, that difference reaches total
     * reactivity, and a saved-and-reloaded reactor no longer resumes on the
     * trajectory it was suspended on. Chunk-reload drift is precisely the failure
     * the round-trip acceptance test exists to catch, and it asserts bit-for-bit
     * equality because that is the only standard a persistence round trip can be
     * held to.
     *
     * <p>The obvious repair — keep the warm start and iterate far below the
     * tolerance until the shape stops moving — was measured and does not work.
     * The iteration has no bitwise fixed point: driven to a tolerance of 1e-15 it
     * never converges at all, and after 4000 iterations from two different
     * histories 590 of 961 assembly weights still disagree in their last bits,
     * for a hundredfold increase in cost. Floating point leaves the iterates
     * wandering inside a ball a few ulps across, and which point of that ball
     * they wander to is exactly the history dependence being chased out. Quantising
     * the warm start so that nearby histories round to the same guess fails for
     * the same reason in a subtler way: it works until two histories straddle a
     * quantisation boundary, and "usually identical" is not what a snapshot needs.
     *
     * <p>So the price of a reactor that resumes where it left off is a cold solve
     * every second — measured at about 25 ms against 7 ms warm, on a shape that
     * moves. A core whose problem has not changed at all still costs nothing, via
     * the memo in {@link #solve()}.
     */
    private void seedFluxFromGeometry() {
        for (int node = 0; node < mesh.nodeCount(); node++) {
            int position = mesh.positionOfNode(node);
            double height = mesh.axialCentreHeightFraction(mesh.axialOfNode(node));
            double axial = Math.sin(Math.PI * height);
            flux[node] = Math.max(1.0e-6, loading.geometricFluxShape(position) * axial);
        }
    }

    /**
     * True when the problem just assembled is bit-for-bit the one the last
     * iterating solve ran on, so that its converged flux — still sitting in
     * {@link #flux} — is already the answer.
     *
     * <p>The comparison is on raw bits rather than on {@code ==}, so a value that
     * has merely changed sign of zero counts as a change and the solve is redone.
     * Erring that way is free; erring the other way would serve a stale flux.
     *
     * <p>The mesh signature is in here because it stands in for the geometry that
     * {@link #powerIterate()} reads without going through these arrays — the
     * neighbour table and the analytic seed — and the five iteration settings are
     * in here because they change the answer without changing the matrix.
     */
    private boolean assembledProblemIsUnmoved() {
        if (!assembledProblemStored || lastSolution == null) {
            return false;
        }
        if (storedMeshSignature != meshSignature
                || storedSweepsPerIteration != sweepsPerIteration
                || storedMaximumIterations != maximumIterations
                || bitsDiffer(storedOverRelaxation, overRelaxation)
                || bitsDiffer(storedShiftMargin, shiftMargin)
                || bitsDiffer(storedTolerance, tolerance)
                || bitsDiffer(storedShiftFloor, shiftFloor)) {
            return false;
        }
        int nodes = mesh.nodeCount();
        for (int node = 0; node < nodes; node++) {
            if (bitsDiffer(storedNuSigmaFVolume[node], nuSigmaFVolume[node])
                    || bitsDiffer(storedRemoval[node], removal[node])) {
                return false;
            }
        }
        int faces = nodes * NodalMesh.FACES;
        for (int face = 0; face < faces; face++) {
            if (bitsDiffer(storedCoupling[face], coupling[face])) {
                return false;
            }
        }
        return true;
    }

    /** Keep the assembled problem, so the next solve can tell whether it moved. */
    private void rememberAssembledProblem() {
        int nodes = mesh.nodeCount();
        System.arraycopy(nuSigmaFVolume, 0, storedNuSigmaFVolume, 0, nodes);
        System.arraycopy(removal, 0, storedRemoval, 0, nodes);
        System.arraycopy(coupling, 0, storedCoupling, 0, nodes * NodalMesh.FACES);
        storedShiftFloor = shiftFloor;
        storedMeshSignature = meshSignature;
        storedSweepsPerIteration = sweepsPerIteration;
        storedOverRelaxation = overRelaxation;
        storedShiftMargin = shiftMargin;
        storedMaximumIterations = maximumIterations;
        storedTolerance = tolerance;
        assembledProblemStored = true;
    }

    private static boolean bitsDiffer(double a, double b) {
        return Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b);
    }

    // ---------------------------------------------------------------
    // Cross-sections
    // ---------------------------------------------------------------

    private void buildNodeData() {
        double volume = mesh.nodeVolumeCm3();
        double baseDiffusion = migrationAreaCm2 * absorptionPerCm;
        int axialCount = mesh.axialNodes();

        for (int node = 0; node < mesh.nodeCount(); node++) {
            int position = mesh.positionOfNode(node);
            int axial = mesh.axialOfNode(node);
            FuelAssembly assembly = loading.assemblyAt(position);
            double kInf = assembly == null ? 0.0 : assembly.kInf();

            double voidFraction = voidAt(mesh.axialCentreHeightFraction(axial));
            double moderated = Math.max(0.0, kInf * (1.0 - voidKInfDepression * voidFraction));

            double absorption = absorptionPerCm
                    + (loading.insertAt(position) == null ? 0.0 : absorptionPerCm * loading.insertAt(position).kind().absorptionRatio)
                    + rodAbsorptionAt(position, axial, axialCount)
                    + (supplementalAbsorptionPerCm == null ? 0.0 : supplementalAbsorptionPerCm[position]);

            nuSigmaFVolume[node] = moderated * absorptionPerCm * volume;
            absorptionVolume[node] = absorption * volume;
            removal[node] = absorptionVolume[node];
            diffusion[node] = baseDiffusion / (1.0 - voidDiffusionExpansion * voidFraction);
        }
    }

    /** Local void fraction at a height fraction above the bottom of active fuel. */
    private double voidAt(double heightFraction) {
        if (exitVoidFraction <= 0.0 || heightFraction <= boilingBoundaryFraction) {
            return 0.0;
        }
        double boilingLength = 1.0 - boilingBoundaryFraction;
        if (boilingLength <= 0.0) {
            return 0.0;
        }
        double through = (heightFraction - boilingBoundaryFraction) / boilingLength;
        return exitVoidFraction * Math.pow(clamp(through, 0.0, 1.0), axialVoidExponent);
    }

    /**
     * Absorber cross-section a control rod contributes to one cell, per cm.
     *
     * <p>Rods enter from the bottom, so a rod at notch {@code k} occupies the
     * lower {@code (24-k)/24} of the core and shadows only the cells it actually
     * reaches. That is what makes a partially inserted rod depress the bottom of
     * its bundles while the top of the same bundles keeps burning — and it is why
     * the axial shape of a rodded core is top-peaked while a clean one is
     * bottom-peaked.
     */
    private double rodAbsorptionAt(int position, int axial, int axialCount) {
        int rod = rodLatticeMap.rodAtPosition(position);
        if (rod < 0 || rod >= rodPositionNotches.length || rodAbsorptionPerCm <= 0.0) {
            return 0.0;
        }
        double insertion = 1.0-rodPositionNotches[rod]/RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN;
        double bottom = (double) axial / axialCount;
        double covered = clamp((insertion - bottom) * axialCount, 0.0, 1.0);
        return rodAbsorptionPerCm * covered;
    }

    // ---------------------------------------------------------------
    // Coupling coefficients
    // ---------------------------------------------------------------

    /**
     * Face couplings, and the removal term they add to.
     *
     * <p>Interior faces get the harmonic mean of the two diffusion coefficients,
     * which is the finite-difference form that stays right when neighbouring
     * nodes differ — as a flooded node and a voided one do. Boundary faces get
     * the albedo condition: eliminating the surface flux between Fick's law and
     * {@code J = beta * phi_s} leaves a single coupling
     * {@code 1 / (1/beta + h/2D)}. That is the reflector, and it is why the edge
     * of the core sits at a fraction of the peak rather than at zero.
     */
    private void buildCoupling() {
        double albedoCurrent = (1.0 - reflectorAlbedo) / (2.0 * (1.0 + reflectorAlbedo));
        double floor = 0.0;
        for (int node = 0; node < mesh.nodeCount(); node++) {
            double own = diffusion[node];
            double interior = 0.0;
            double leakage = 0.0;
            for (int face = 0; face < NodalMesh.FACES; face++) {
                double area = mesh.faceAreaCm2(face);
                double spacing = mesh.faceSpacingCm(face);
                int neighbour = mesh.neighbour(node, face);
                double conductance;
                boolean interiorFace = neighbour >= 0;
                if (interiorFace) {
                    double other = diffusion[neighbour];
                    conductance = 2.0 * own * other / ((own + other) * spacing);
                } else {
                    conductance = 1.0 / (1.0 / albedoCurrent + spacing / (2.0 * own));
                }
                double faceCoupling = area * conductance;
                // Zero at a boundary face: the leakage out of it belongs in the
                // removal term, and nothing flows back in through it.
                coupling[node * NodalMesh.FACES + face] = interiorFace ? faceCoupling : 0.0;
                leakage += faceCoupling;
                if (interiorFace) {
                    interior += faceCoupling;
                }
            }
            removal[node] += leakage;

            // The largest shift this cell tolerates. Subtracting its own fission
            // source at 1/k_shift must leave the row still able to out-remove
            // what its neighbours push into it — otherwise the shifted matrix
            // stops being diagonally dominant and the sweeps walk away. What
            // falls out is the cell's own multiplication against absorption and
            // boundary leakage alone, which is a physical statement: you may not
            // shift past the point where a cell is self-multiplying.
            double margin = removal[node] - interior;
            if (margin > 0.0) {
                floor = Math.max(floor, nuSigmaFVolume[node] / margin);
            }
        }
        shiftFloor = floor;
    }

    // ---------------------------------------------------------------
    // Power iteration
    // ---------------------------------------------------------------

    /**
     * Outer power iteration on {@code k}, with over-relaxed Gauss-Seidel sweeps
     * inside and a Wielandt shift on the outside.
     *
     * <p>Gauss-Seidel rather than Jacobi because it uses each cell's freshly
     * updated neighbours within the same sweep, converging in roughly half the
     * sweeps for no extra storage. The flux is renormalised every iteration so
     * the total fission source stays at one, which keeps the numbers in a fixed
     * range no matter how far the eigenvalue drifts from unity.
     *
     * <p>The shift is what makes this fast enough to run on a game tick. A plain
     * power iteration converges at the ratio of the second eigenvalue to the
     * first, and on a core seven hundred bundles across the first two spatial
     * modes sit within a few percent of each other — hundreds of iterations, and
     * a visible hitch. Solving the shifted problem
     * {@code (A - S/k_s) phi = (1/k - 1/k_s) S phi} instead leaves the
     * eigenvectors alone and pulls the eigenvalue separation apart, so the same
     * answer arrives in tens of iterations. The shape is identical; only the path
     * to it is shorter.
     *
     * <p>Convergence is measured on the <b>normalised shape</b> between outer
     * iterations, not on the change within a sweep. Over-relaxation makes the
     * within-sweep change oscillate even while the shape is settling
     * monotonically, so the within-sweep number is the wrong thing to test.
     *
     * @return outer iterations performed
     */
    private int powerIterate() {
        int nodes = mesh.nodeCount();
        double sourceTotal = fissionTotal();
        if (!(sourceTotal > 0.0)) {
            // Nothing multiplies anywhere — a core of completely spent fuel, or
            // one held down hard enough that k_inf has reached zero. There is no
            // eigenvalue to find; report a flat shape and let the callers divide
            // decay heat evenly.
            Arrays.fill(flux, 1.0);
            multiplicationFactor = 0.0;
            lastEigenvalueResidual = 0.0;
            lastFluxResidual = 0.0;
            return 0;
        }
        scaleFlux(1.0 / sourceTotal);

        // The flux and the eigenvalue this starts from were both established by
        // solve(), from the inputs and nothing else. Nothing here may reach back
        // to a previous solve for a starting point — see seedFluxFromGeometry().

        double eigenvalueResidual = 0.0;
        double fluxResidual = 0.0;
        int iteration = 0;
        double relaxation = overRelaxation;
        for (; iteration < maximumIterations; iteration++) {
            // The shift rides just above whichever is larger: the eigenvalue as
            // it currently stands, or the most reactive cell's own multiplication.
            double shift = (1.0 + shiftMargin) * Math.max(multiplicationFactor, shiftFloor);
            double inverseShift = 1.0 / shift;
            double lambda = 1.0 / multiplicationFactor - inverseShift;

            // Fission source held fixed across the inner sweeps: that is what
            // makes the inner problem linear and the outer iteration a power
            // method on the shifted fission operator.
            for (int node = 0; node < nodes; node++) {
                fissionRate[node] = nuSigmaFVolume[node] * flux[node] * lambda;
                shiftedRemoval[node] = removal[node] - nuSigmaFVolume[node] * inverseShift;
            }
            double before = fissionTotal();
            if (!(before > 0.0)) {
                break;
            }
            System.arraycopy(flux, 0, previousFlux, 0, nodes);

            // Hoisted into locals: this is the innermost loop of the entire
            // physics model and the JIT keeps these in registers.
            final double[] phi = flux;
            final double[] link = coupling;
            final int[] near = sweepNeighbour;
            final double[] source = fissionRate;
            final double[] denominator = shiftedRemoval;
            for (int sweep = 0; sweep < sweepsPerIteration; sweep++) {
                for (int node = 0; node < nodes; node++) {
                    int base = node * NodalMesh.FACES;
                    double sum = source[node]
                            + link[base] * phi[near[base]]
                            + link[base + 1] * phi[near[base + 1]]
                            + link[base + 2] * phi[near[base + 2]]
                            + link[base + 3] * phi[near[base + 3]]
                            + link[base + 4] * phi[near[base + 4]]
                            + link[base + 5] * phi[near[base + 5]];
                    double next = phi[node] + relaxation * (sum / denominator[node] - phi[node]);
                    // Over-relaxation can undershoot in a strongly absorbing cell.
                    // Flux is a particle density; clamp rather than let a negative
                    // one propagate into its neighbours.
                    phi[node] = next > 0.0 ? next : 0.0;
                }
            }

            double after = fissionTotal();
            double updated = 1.0 / (lambda * before / after + inverseShift);
            eigenvalueResidual = Math.abs(updated - multiplicationFactor)
                    / (updated > 0.0 ? updated : 1.0);
            multiplicationFactor = updated;
            scaleFlux(1.0 / after);

            // Shape convergence, measured against the core-average flux rather
            // than cell by cell — a corner cell with almost no flux in it has
            // nothing to be relatively wrong about, and letting it set the
            // criterion would mean converging the least interesting part of the
            // core to the tightest standard.
            double fluxSum = 0.0;
            for (int node = 0; node < nodes; node++) {
                fluxSum += flux[node];
            }
            fluxResidual = 0.0;
            if (fluxSum > 0.0) {
                double perAverage = nodes / fluxSum;
                for (int node = 0; node < nodes; node++) {
                    double change = Math.abs(flux[node] - previousFlux[node]) * perAverage;
                    if (change > fluxResidual) {
                        fluxResidual = change;
                    }
                }
            }

            if (eigenvalueResidual < tolerance && fluxResidual < tolerance) {
                iteration++;
                break;
            }
        }
        lastEigenvalueResidual = eigenvalueResidual;
        lastFluxResidual = fluxResidual;
        return iteration;
    }

    private double fissionTotal() {
        double total = 0.0;
        for (int node = 0; node < mesh.nodeCount(); node++) {
            total += nuSigmaFVolume[node] * flux[node];
        }
        return total;
    }

    private void scaleFlux(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            return;
        }
        for (int node = 0; node < flux.length; node++) {
            flux[node] *= factor;
        }
    }

    // ---------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------

    private FluxSolution collect(int iterations) {
        int nodes = mesh.nodeCount();
        int positions = mesh.positionCount();
        int axialCount = mesh.axialNodes();

        // Fission share per cell, summing to 1 over the core.
        double fissionTotal = 0.0;
        for (int node = 0; node < nodes; node++) {
            fissionRate[node] = nuSigmaFVolume[node] * flux[node];
            fissionTotal += fissionRate[node];
        }
        double[] nodeFissionShare = new double[nodes];
        if (fissionTotal > 0.0) {
            for (int node = 0; node < nodes; node++) {
                nodeFissionShare[node] = fissionRate[node] / fissionTotal;
            }
        }

        // Flux normalised to a core-average cell flux of 1.
        double fluxTotal = 0.0;
        for (int node = 0; node < nodes; node++) {
            fluxTotal += flux[node];
        }
        double fluxScale = fluxTotal > 0.0 ? nodes / fluxTotal : 0.0;
        double[] nodeFlux = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            nodeFlux[node] = flux[node] * fluxScale;
        }

        double[] assemblyWeights = new double[positions];
        double[] assemblyFlux = new double[positions];
        Arrays.fill(assemblyAccumulator, 0.0);
        Arrays.fill(axialAccumulator, 0.0);
        for (int node = 0; node < nodes; node++) {
            int position = mesh.positionOfNode(node);
            assemblyWeights[position] += nodeFissionShare[node];
            assemblyAccumulator[position] += nodeFlux[node];
            axialAccumulator[mesh.axialOfNode(node)] += nodeFissionShare[node];
        }
        for (int position = 0; position < positions; position++) {
            assemblyFlux[position] = assemblyAccumulator[position] / axialCount;
        }

        double[] axialShape = new double[axialCount];
        for (int axial = 0; axial < axialCount; axial++) {
            axialShape[axial] = axialAccumulator[axial] * axialCount;
        }

        // Per-rod flux weight: the flux the rod itself sits in, mean 1 over the
        // rods that shadow fuel at all.
        double[] rodFluxWeights = new double[rodPositionNotches.length];
        Arrays.fill(rodAccumulator, 0.0);
        for (int node = 0; node < nodes; node++) {
            int rod = rodLatticeMap.rodAtPosition(mesh.positionOfNode(node));
            if (rod >= 0) {
                rodAccumulator[rod] += nodeFlux[node];
            }
        }
        double rodTotal = 0.0;
        int rodsWithFuel = 0;
        for (int rod = 0; rod < rodFluxWeights.length; rod++) {
            int owned = rodPositionCount[rod];
            if (owned > 0) {
                rodFluxWeights[rod] = rodAccumulator[rod] / (owned * (double) axialCount);
                rodTotal += rodFluxWeights[rod];
                rodsWithFuel++;
            }
        }
        if (rodsWithFuel > 0 && rodTotal > 0.0) {
            double scale = rodsWithFuel / rodTotal;
            for (int rod = 0; rod < rodFluxWeights.length; rod++) {
                rodFluxWeights[rod] = rodFluxWeights[rod] > 0.0 ? rodFluxWeights[rod] * scale : 1.0;
            }
        } else {
            Arrays.fill(rodFluxWeights, 1.0);
        }

        // Non-leakage falls out of the solve: the eigenvalue over the
        // k-infinity the solve actually ran on.
        //
        // Summing the nodal balance over the whole core cancels every interior
        // face, because the harmonic-mean coupling is symmetric, and leaves
        //     k = production / (absorption + boundary leakage)
        // so the fraction of neutrons that do not leak is
        //     P_nl = absorption / (absorption + boundary leakage) = k / (P/A)
        // with P and A the flux-weighted production and absorption totals below.
        //
        // The divisor must be the in-place k-infinity, not the fuel's catalogue
        // k-infinity. Dividing by the raw assembly value instead charges the rod
        // absorber and the void k-infinity depression — both of which are already
        // inside the eigenvalue — to leakage, which is not what leakage is:
        // scramming a core changes nothing about its geometry, and the old form
        // nonetheless reported an 18% jump in leakage when it happened.
        double productionTotal = 0.0;
        double absorptionTotal = 0.0;
        for (int node = 0; node < nodes; node++) {
            productionTotal += nuSigmaFVolume[node] * flux[node];
            absorptionTotal += absorptionVolume[node] * flux[node];
        }
        double kInfInPlace = absorptionTotal > 0.0 ? productionTotal / absorptionTotal : 0.0;
        double nonLeakage = kInfInPlace > 0.0 ? multiplicationFactor / kInfInPlace : 0.0;

        return new FluxSolution(mesh, nodeFlux, nodeFissionShare, assemblyWeights, assemblyFlux,
                axialShape, rodFluxWeights, multiplicationFactor, nonLeakage,
                iterations, lastEigenvalueResidual, lastFluxResidual);
    }

    private FluxSolution emptySolution() {
        return new FluxSolution(mesh, new double[0], new double[0],
                new double[mesh.positionCount()], new double[mesh.positionCount()],
                new double[mesh.axialNodes()], uniform(rodPositionNotches.length),
                0.0, 0.0, 0, 0.0, 0.0);
    }

    private static double[] uniform(int length) {
        double[] values = new double[length];
        Arrays.fill(values, 1.0);
        return values;
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : Math.min(value, high);
    }

    private static void requirePositive(double value, String what) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(what + " must be finite and positive, got " + value);
        }
    }

    @Override
    public String toString() {
        return String.format("NodalFluxSolver[%s, %d rods, %s]",
                mesh == null ? "unmeshed" : mesh.toString(), rodPositionNotches.length,
                lastSolution == null ? "unsolved" : lastSolution.toString());
    }
}
