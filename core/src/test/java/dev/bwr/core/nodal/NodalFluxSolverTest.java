package dev.bwr.core.nodal;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.kinetics.RodWorth;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The nodal flux solve: SPEC 1.3, build-order step 2.
 *
 * <p>These tests exist to prove the five things the spatial solve was built to
 * buy, each of which is invisible to a point-kinetics model and none of which is
 * special-cased anywhere in the solver:
 *
 * <ol>
 *   <li>A centre-to-periphery flux gradient, with the reflector keeping the edge
 *       off zero.</li>
 *   <li>Rod shadowing — an inserted rod depresses its own bundles, and depresses
 *       the bottom of them harder than the top, because it enters from the
 *       bottom.</li>
 *   <li>Loading patterns mattering: the same fuel inventory arranged two ways
 *       gives two different eigenvalues and two very different peaking factors.</li>
 *   <li>Local absorption depressing nearby flux — the tritium rod tradeoff.</li>
 *   <li>A stuck rod producing a local hot spot, which is the thing SLC boron
 *       exists to answer.</li>
 * </ol>
 *
 * <p>Plus the two properties that make it trustworthy rather than merely
 * suggestive: effective beta comes out as a fission-rate-weighted sum and not
 * from geometry, and the answer is converged, start-independent and unchanged by
 * the numerical acceleration used to reach it.
 */
public final class NodalFluxSolverTest {

    private NodalFluxSolverTest() {
    }

    /** Rated-ish void distribution: boiling starts a quarter up, 70% void at the exit. */
    private static final double BOILING_BOUNDARY = 0.25;
    private static final double EXIT_VOID = 0.70;

    private static CoreConfig config() {
        return new CoreConfig();
    }

    /** A full core of fresh LEU, the same loading {@code ReactorCore} builds by default. */
    private static CoreLoading uniformCore() {
        return ReactorCore.defaultCoreLoading(config(), FuelType.LEU);
    }

    private static NodalFluxSolver solverFor(CoreLoading loading) {
        NodalFluxSolver solver = new NodalFluxSolver(config(), loading);
        loading.setPowerWeightSource(solver);
        return solver;
    }

    private static int[] allRods(int notch) {
        int[] notches = new int[config().controlRodCount];
        Arrays.fill(notches, notch);
        return notches;
    }

    private static int centreIndex(CoreLoading loading) {
        int centre = (loading.latticeWidth() - 1) / 2;
        return loading.index(centre, centre);
    }

    /** The position diagonally opposite through the core centre. Same radius, other side. */
    private static int mirrorIndex(CoreLoading loading, int position) {
        int width = loading.latticeWidth();
        int column = position % width;
        int row = position / width;
        return (width - 1 - row) * width + (width - 1 - column);
    }

    // ---------------------------------------------------------------

    /**
     * A uniform core is centre-peaked, monotonically, and the reflector holds the
     * outermost bundles well off zero.
     *
     * <p>This is the one result the whole solve has to get right before any of
     * the others mean anything. A bare cylindrical core has a Bessel radial
     * profile with a peak-to-average of about 2.3; a reflected one is flatter
     * than that and does not go to zero at the edge, because neutrons that leak
     * out of the periphery come back.
     */
    public static void test01_fluxIsCentrePeakedAndTheReflectorHoldsUpTheEdge() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        solver.setRodNotchIndices(allRods(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN));
        FluxSolution flux = solver.solve();

        int width = loading.latticeWidth();
        int centre = (width - 1) / 2;
        double[] row = new double[width];
        for (int column = 0; column < width; column++) {
            row[column] = flux.assemblyFlux(loading.index(column, centre));
        }

        StringBuilder profile = new StringBuilder();
        for (int column = 0; column <= centre; column += 3) {
            profile.append(String.format(Locale.ROOT, "%.2f ", row[column]));
        }
        Check.note("radial flux, edge to centre: %s", profile.toString().trim());
        Check.note("k=%.5f, non-leakage %.4f, radial peaking %.3f, %d iterations, residual %.2e",
                flux.multiplicationFactor(), flux.nonLeakageProbability(),
                flux.radialPeakingFactor(), flux.iterations(),
                Math.max(flux.eigenvalueResidual(), flux.fluxResidual()));
        Check.note("centre %.4f, edge %.4f, edge is %.1f%% of centre",
                row[centre], row[0], 100.0 * row[0] / row[centre]);

        for (int column = 1; column <= centre; column++) {
            Check.greaterThan(row[column - 1], row[column],
                    "flux at column " + column + " must exceed column " + (column - 1));
        }
        Check.greaterThan(3.0, row[centre] / row[0], "centre-to-edge flux ratio");
        Check.greaterThan(0.05, row[0], "edge flux, which the reflector must keep off zero");
        Check.relative(row[0], row[width - 1], 1.0e-3, "flux symmetry across the core");
        Check.inRange(1.5, 2.5, flux.radialPeakingFactor(),
                "radial peaking of a uniform unrodded core, against the bare-core 2.32");
        Check.inRange(0.90, 1.0, flux.nonLeakageProbability(), "non-leakage probability");
    }

    /**
     * An inserted rod is visible in the flux map: its own bundles are dimmed
     * sharply, the bundle at the same radius on the other side of the core is
     * not, and the bottom of the rodded bundles is dimmed harder than the top
     * because the rod enters from the bottom.
     *
     * <p><b>The rod also tips the whole radial shape away from itself</b>, by
     * more than the arithmetic of four bundles out of seven hundred would
     * suggest, and that is not an artefact — the solves here are converged to a
     * residual four orders below where they are read. A core this size is one
     * fundamental-mode wavelength across, so a local reactivity perturbation
     * excites the first azimuthal harmonic and the harmonic is amplified by the
     * small eigenvalue separation between it and the fundamental. One-group
     * perturbation theory puts that amplification near a hundred for this
     * geometry, which is what the numbers below show. It is also why a real BWR
     * is described as loosely coupled, why rod patterns are chosen symmetric, and
     * why the plant is covered in local power range monitors instead of trusting
     * a core average.
     */
    public static void test02_rodInsertionShadowsItsOwnBundlesFromTheBottomUp() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        int[] notches = allRods(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN);
        solver.setRodNotchIndices(notches);
        FluxSolution clean = solver.solve();

        int rod = 20;
        int position = solver.rodLatticeMap().positionOfRod(rod, 0);
        int mirror = mirrorIndex(loading, position);
        double cleanHere = clean.assemblyFlux(position);
        double cleanThere = clean.assemblyFlux(mirror);

        notches[rod] = RodWorth.NOTCH_INDEX_FULLY_INSERTED;
        solver.setRodNotchIndices(notches);
        FluxSolution rodded = solver.solve();
        double roddedHere = rodded.assemblyFlux(position);
        double roddedThere = rodded.assemblyFlux(mirror);

        Check.note("one rod of %d inserted at position %d", loading.loadedAssemblyCount(), position);
        Check.note("its bundle: flux %.4f -> %.4f (%+.1f%%)", cleanHere, roddedHere,
                100.0 * (roddedHere / cleanHere - 1.0));
        Check.note("mirror bundle, same radius, unrodded: %.4f -> %.4f (%+.1f%%)",
                cleanThere, roddedThere, 100.0 * (roddedThere / cleanThere - 1.0));

        Check.relative(cleanThere, cleanHere, 0.02,
                "the two comparison bundles must start at the same flux");
        Check.lessThan(0.75 * cleanHere, roddedHere, "flux in the rodded bundle");
        Check.greaterThan(cleanThere, roddedThere,
                "the bundle at the same radius on the far side must not be dimmed: the shadow "
                        + "belongs to the rod, not to the radius");
        Check.greaterThan(2.0, roddedThere / roddedHere,
                "shadowed against unshadowed at equal radius");

        // Half insertion: the rod occupies the lower half of the core, so the
        // lower half of its bundles is depressed and the upper half is not.
        notches[rod] = RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN / 2;
        solver.setRodNotchIndices(notches);
        FluxSolution half = solver.solve();
        int axialNodes = half.mesh().axialNodes();
        int low = axialNodes / 6;
        int high = axialNodes - 1 - axialNodes / 6;
        double cleanRatio = clean.flux(position, low) / clean.flux(position, high);
        double halfRatio = half.flux(position, low) / half.flux(position, high);

        Check.note("half-inserted rod, bottom/top flux ratio in its bundle: %.3f clean -> %.3f rodded",
                cleanRatio, halfRatio);
        Check.lessThan(0.7 * cleanRatio, halfRatio,
                "a bottom-entry rod must shadow the bottom of its bundle harder than the top");
    }

    /**
     * A scrammed core with one rod stuck out has a hot spot exactly where the
     * stuck rod is. This is the partial-scram failure mode of SPEC 3.3, and the
     * reason SLC boron exists: the rods that did insert cannot answer a local
     * peak that is being driven by the one that did not.
     */
    public static void test03_aStuckRodLeavesALocalHotSpot() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        solver.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);

        int[] notches = allRods(RodWorth.NOTCH_INDEX_FULLY_INSERTED);
        solver.setRodNotchIndices(notches);
        FluxSolution scrammed = solver.solve();
        double scrammedPeak = scrammed.radialPeakingFactor();

        int stuck = 3;
        notches[stuck] = RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN;
        solver.setRodNotchIndices(notches);
        FluxSolution partial = solver.solve();

        int position = solver.rodLatticeMap().positionOfRod(stuck, 0);
        double share = partial.assemblyWeight(position);
        double average = 1.0 / loading.loadedAssemblyCount();

        Check.note("full scram: k=%.5f, radial peaking %.3f", scrammed.multiplicationFactor(),
                scrammedPeak);
        Check.note("one rod of %d stuck out: k=%.5f, radial peaking %.3f",
                config().controlRodCount, partial.multiplicationFactor(),
                partial.radialPeakingFactor());
        Check.note("the stuck rod's bundle carries %.2fx the core average power", share / average);

        Check.greaterThan(3.0, share / average, "local power peak at the stuck rod");
        Check.greaterThan(scrammedPeak + 1.0, partial.radialPeakingFactor(),
                "one stuck rod must be visible in the peaking factor");
        Check.greaterThan(scrammed.multiplicationFactor(), partial.multiplicationFactor(),
                "a rod left out must leave the core more reactive than a complete scram");
    }

    /**
     * A local absorber — a tritium target rod, SPEC 2.5 — eats flux where it sits,
     * and the depression is deepest at the absorber and recovers monotonically
     * with distance from it.
     *
     * <p>That gradient is the whole tradeoff. Tritium production scales with the
     * flux the rod sits in, and the rod suppresses the very flux it is trying to
     * use, so a player choosing between power and tritium is choosing against a
     * real diminishing return rather than a configured penalty.
     */
    public static void test04_localAbsorptionDepressesNearbyFlux() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        solver.setRodNotchIndices(allRods(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN));
        FluxSolution clean = solver.solve();

        int centre = (loading.latticeWidth() - 1) / 2;
        int column = centre - 4;
        int target = loading.index(column, centre);

        solver.setSupplementalAbsorptionPerCm(target, 0.5 * solver.getAbsorptionPerCm());
        FluxSolution absorbed = solver.solve();

        // Walk outward from the absorber toward the periphery, so that the
        // recovery being measured is distance from the absorber and not the
        // core's own radial shape, which the ratio divides out anyway.
        int steps = 5;
        double[] ratio = new double[steps];
        StringBuilder profile = new StringBuilder();
        for (int step = 0; step < steps; step++) {
            int position = loading.index(column - step, centre);
            ratio[step] = absorbed.assemblyFlux(position) / clean.assemblyFlux(position);
            profile.append(String.format(Locale.ROOT, "%.3f ", ratio[step]));
        }

        Check.note("absorber at one position, %.4f /cm on top of %.4f /cm of fuel absorption",
                0.5 * solver.getAbsorptionPerCm(), solver.getAbsorptionPerCm());
        Check.note("flux relative to the clean core, 0 to %d bundles away: %s", steps - 1,
                profile.toString().trim());
        Check.note("k %.5f -> %.5f: one absorber costs the whole core reactivity",
                clean.multiplicationFactor(), absorbed.multiplicationFactor());

        Check.lessThan(0.75, ratio[0], "flux where the absorber sits");
        for (int step = 1; step < steps; step++) {
            Check.greaterThan(ratio[step - 1], ratio[step],
                    "flux must recover with distance: bundle " + step + " against " + (step - 1));
        }
        Check.lessThan(clean.multiplicationFactor(), absorbed.multiplicationFactor(),
                "an absorber must cost reactivity");
    }

    /**
     * The same fuel inventory, arranged two ways, is two different reactors.
     *
     * <p>Both cores hold exactly the same bundles — half fresh, half at 25
     * GWd/tonne — and differ only in whether the fresh fuel is in the middle or
     * around the outside. Fresh fuel in the centre sits in the high-importance
     * region and buys more reactivity; fresh fuel on the periphery buys less but
     * flattens the power distribution, which is why real plants shuffle. Nothing
     * in the solver knows about either strategy.
     */
    public static void test05_loadingPatternChangesReactivityAndPeaking() {
        double burned = 25000.0;
        CoreLoading freshCentre = new CoreLoading(ReactorCore.DEFAULT_LATTICE_WIDTH);
        CoreLoading freshEdge = new CoreLoading(ReactorCore.DEFAULT_LATTICE_WIDTH);
        int width = ReactorCore.DEFAULT_LATTICE_WIDTH;
        double centre = (width - 1) / 2.0;
        int inner = 0;
        int outer = 0;
        for (int position = 0; position < width * width; position++) {
            double x = (position % width) - centre;
            double y = (position / width) - centre;
            double radius = Math.hypot(x, y);
            if (radius > 15.4) {
                continue;
            }
            boolean isInner = radius < 10.9;
            if (isInner) {
                inner++;
            } else {
                outer++;
            }
            freshCentre.load(position, assembly(FuelType.LEU, isInner ? 0.0 : burned));
            freshEdge.load(position, assembly(FuelType.LEU, isInner ? burned : 0.0));
        }

        FluxSolution centreLoaded = solverFor(freshCentre).solve();
        FluxSolution edgeLoaded = solverFor(freshEdge).solve();

        Check.note("%d inner and %d outer positions, identical inventory both ways", inner, outer);
        Check.note("fresh in the centre: k=%.5f, radial peaking %.3f, centre bundle %.2fx average",
                centreLoaded.multiplicationFactor(), centreLoaded.radialPeakingFactor(),
                centreLoaded.assemblyWeight(centreIndex(freshCentre)) * freshCentre.loadedAssemblyCount());
        Check.note("fresh at the edge:   k=%.5f, radial peaking %.3f, centre bundle %.2fx average",
                edgeLoaded.multiplicationFactor(), edgeLoaded.radialPeakingFactor(),
                edgeLoaded.assemblyWeight(centreIndex(freshEdge)) * freshEdge.loadedAssemblyCount());

        Check.greaterThan(edgeLoaded.multiplicationFactor() + 0.01, centreLoaded.multiplicationFactor(),
                "fresh fuel in the high-importance centre must be worth more reactivity");
        Check.greaterThan(1.4 * edgeLoaded.radialPeakingFactor(), centreLoaded.radialPeakingFactor(),
                "and must peak the power distribution much harder");
    }

    /**
     * Effective beta is the fission-rate-weighted average of the assemblies'
     * betas — a weighted sum falling out of the solve, not a property of
     * geometry.
     *
     * <p>The demonstration that this is the right way round: sixty-nine MOX
     * bundles in the high-flux centre drag core beta further down than two
     * hundred and twenty of the same bundles parked on the periphery. Three times
     * the plutonium, less than half the effect, because what matters is where the
     * fissions are.
     */
    public static void test06_effectiveBetaIsAFissionRateWeightedSum() {
        CoreLoading moxCentre = mixedCore(true);
        CoreLoading moxEdge = mixedCore(false);
        NodalFluxSolver centreSolver = solverFor(moxCentre);
        NodalFluxSolver edgeSolver = solverFor(moxEdge);

        long centreCount = moxCentre.loadedAssemblies().stream()
                .filter(a -> a.fuelType() == FuelType.MOX).count();
        long edgeCount = moxEdge.loadedAssemblies().stream()
                .filter(a -> a.fuelType() == FuelType.MOX).count();

        double centreBeta = moxCentre.effectiveBeta();
        double edgeBeta = moxEdge.effectiveBeta();

        // The same number, computed straight off the flux solution as a weighted
        // sum over the lattice. If these two disagree, something has started
        // deriving beta from something other than the fission distribution.
        double[] betaByPosition = new double[moxCentre.positionCount()];
        for (int position = 0; position < betaByPosition.length; position++) {
            FuelAssembly assembly = moxCentre.assemblyAt(position);
            betaByPosition[position] = assembly == null ? 0.0 : assembly.beta();
        }
        double fromSolution = centreSolver.lastSolution()
                .fissionWeightedAverage(betaByPosition, CoreLoading.EMPTY_CORE_BETA);

        Check.note("LEU beta %.6f, MOX beta %.6f", FuelType.LEU.beta(), FuelType.MOX.beta());
        Check.note("%d MOX bundles in the centre: beta_eff %.6f", centreCount, centreBeta);
        Check.note("%d MOX bundles at the edge:   beta_eff %.6f", edgeCount, edgeBeta);
        Check.note("weighted sum straight off the flux map: %.9f", fromSolution);
        Check.note("edge solve k=%.5f, centre solve k=%.5f",
                edgeSolver.lastSolution().multiplicationFactor(),
                centreSolver.lastSolution().multiplicationFactor());

        Check.relative(centreBeta, fromSolution, 1.0e-12,
                "beta from CoreLoading against the same weighted sum over the flux map");
        Check.inRange(FuelType.MOX.beta(), FuelType.LEU.beta(), centreBeta,
                "core beta must lie between the two fuels' betas");
        Check.greaterThan(2.5 * centreCount, edgeCount,
                "the edge case must hold far more MOX for the comparison to mean anything");
        Check.lessThan(edgeBeta, centreBeta,
                "MOX in the high-flux centre must depress beta more than three times as much "
                        + "MOX on the periphery");
    }

    /**
     * The answer is converged and does not depend on how the solver got there.
     *
     * <p>Two solvers are driven to the same final state by different routes — one
     * cold from the analytic guess, one warm from a fully rodded core — and must
     * agree. Tightening the tolerance by two orders of magnitude must then move
     * nothing, which is what converged means.
     */
    public static void test07_theSolveConvergesIndependentlyOfItsStartingPoint() {
        int[] target = allRods(14);
        target[9] = RodWorth.NOTCH_INDEX_FULLY_INSERTED;

        CoreLoading coldCore = uniformCore();
        NodalFluxSolver cold = solverFor(coldCore);
        cold.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        cold.setRodNotchIndices(target);
        FluxSolution coldSolution = cold.solve();

        CoreLoading warmCore = uniformCore();
        NodalFluxSolver warm = solverFor(warmCore);
        warm.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        warm.setRodNotchIndices(allRods(RodWorth.NOTCH_INDEX_FULLY_INSERTED));
        warm.solve();
        warm.setRodNotchIndices(allRods(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN));
        warm.solve();
        warm.setRodNotchIndices(target);
        FluxSolution warmSolution = warm.solve();

        CoreLoading tightCore = uniformCore();
        NodalFluxSolver tight = solverFor(tightCore);
        tight.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        tight.setRodNotchIndices(target);
        tight.setTolerance(cold.getTolerance() / 100.0);
        tight.setMaximumIterations(4000);
        FluxSolution tightSolution = tight.solve();

        double routeDifference = worstWeightDifference(coldSolution, warmSolution);
        double toleranceDifference = worstWeightDifference(coldSolution, tightSolution);

        Check.note("cold from the analytic guess: %d iterations, k=%.8f",
                coldSolution.iterations(), coldSolution.multiplicationFactor());
        Check.note("warm through a scram and a full withdrawal: %d iterations, k=%.8f",
                warmSolution.iterations(), warmSolution.multiplicationFactor());
        Check.note("tolerance %.0e, %d iterations, k=%.8f", tight.getTolerance(),
                tightSolution.iterations(), tightSolution.multiplicationFactor());
        Check.note("worst relative weight difference: %.2e by route, %.2e by tolerance",
                routeDifference, toleranceDifference);

        Check.lessThan(cold.getMaximumIterations(), coldSolution.iterations(),
                "the cold solve must converge inside its iteration budget");
        Check.lessThan(cold.getTolerance(), coldSolution.fluxResidual(), "converged flux residual");
        Check.lessThan(cold.getTolerance(), coldSolution.eigenvalueResidual(),
                "converged eigenvalue residual");
        Check.relative(coldSolution.multiplicationFactor(), warmSolution.multiplicationFactor(),
                1.0e-6, "eigenvalue must not depend on the route taken to it");
        Check.lessThan(1.0e-3, routeDifference, "power weights must not depend on the route");
        Check.lessThan(1.0e-3, toleranceDifference,
                "a hundredfold tighter tolerance must not move the answer");

        // And a re-solve of an unchanged core is nearly free, which is what makes
        // the 1 Hz cadence affordable.
        FluxSolution again = cold.solve();
        Check.note("re-solving an unchanged core: %d iterations", again.iterations());
        Check.lessThan(6, again.iterations(), "iterations to re-solve an unchanged core");
    }

    /**
     * The Wielandt shift is an accelerator, not a model.
     *
     * <p>It changes the eigenvalue separation of the iteration matrix and leaves
     * the eigenvectors alone, so the shifted solve and an unshifted one must
     * agree on both the eigenvalue and the shape. If they ever disagree, the
     * shift has been implemented as physics by accident.
     */
    public static void test08_theEigenvalueShiftDoesNotChangeTheAnswer() {
        CoreLoading shiftedCore = uniformCore();
        NodalFluxSolver shifted = solverFor(shiftedCore);
        shifted.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        shifted.setRodNotchIndices(allRods(18));
        shifted.setTolerance(1.0e-7);
        shifted.setMaximumIterations(4000);
        long shiftedNanos = System.nanoTime();
        FluxSolution shiftedSolution = shifted.solve();
        shiftedNanos = System.nanoTime() - shiftedNanos;

        CoreLoading plainCore = uniformCore();
        NodalFluxSolver plain = solverFor(plainCore);
        plain.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        plain.setRodNotchIndices(allRods(18));
        plain.setTolerance(1.0e-7);
        plain.setMaximumIterations(20000);
        plain.setShiftMargin(1.0e6); // so far above the eigenvalue that it does nothing
        long plainNanos = System.nanoTime();
        FluxSolution plainSolution = plain.solve();
        plainNanos = System.nanoTime() - plainNanos;

        Check.note("shifted:   %4d iterations, %6.1f ms, k=%.9f", shiftedSolution.iterations(),
                shiftedNanos / 1.0e6, shiftedSolution.multiplicationFactor());
        Check.note("unshifted: %4d iterations, %6.1f ms, k=%.9f", plainSolution.iterations(),
                plainNanos / 1.0e6, plainSolution.multiplicationFactor());
        Check.note("worst relative weight difference %.2e; speedup %.1fx",
                worstWeightDifference(shiftedSolution, plainSolution),
                plainNanos / (double) Math.max(1L, shiftedNanos));

        Check.relative(plainSolution.multiplicationFactor(), shiftedSolution.multiplicationFactor(),
                1.0e-6, "eigenvalue, shifted against unshifted");
        Check.lessThan(1.0e-3, worstWeightDifference(shiftedSolution, plainSolution),
                "power weights, shifted against unshifted");
        Check.lessThan(plainSolution.iterations(), shiftedSolution.iterations(),
                "the shift must actually accelerate the iteration");
    }

    /**
     * A boiling core is bottom-peaked, and a flooded one is not.
     *
     * <p>Void has to be pushed in from the thermal hydraulics for this to happen
     * — the solver has no idea the core is a BWR. Give it a uniform moderator and
     * it produces the symmetric cosine of a textbook bare core; give it a boiling
     * boundary a quarter of the way up and the shape tips toward the flooded
     * bottom, which is what a real BWR axial power trace looks like.
     */
    public static void test09_voidTipsTheAxialShapeTowardTheBottomOfTheCore() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        solver.setRodNotchIndices(allRods(RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN));

        FluxSolution flooded = solver.solve();
        double[] floodedShape = flooded.axialShape();
        int floodedPeak = indexOfPeak(floodedShape);

        solver.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        FluxSolution boiling = solver.solve();
        double[] boilingShape = boiling.axialShape();
        int boilingPeak = indexOfPeak(boilingShape);

        int nodes = floodedShape.length;
        Check.note("flooded: peak at node %d of %d, peaking factor %.3f, symmetric to %.2e",
                floodedPeak, nodes, flooded.axialPeakingFactor(),
                Math.abs(floodedShape[0] / floodedShape[nodes - 1] - 1.0));
        Check.note("boiling: peak at node %d of %d, peaking factor %.3f, bottom %.3f top %.3f",
                boilingPeak, nodes, boiling.axialPeakingFactor(),
                boilingShape[0], boilingShape[nodes - 1]);

        Check.absolute((nodes - 1) / 2.0, floodedPeak, 0.51,
                "a flooded core must peak at mid-height");
        Check.relative(floodedShape[0], floodedShape[nodes - 1], 1.0e-3,
                "a flooded core must be axially symmetric");
        Check.lessThan((nodes - 1) / 2.0, boilingPeak, "a boiling core must peak below mid-height");
        Check.greaterThan(boilingShape[nodes - 1], boilingShape[0],
                "the flooded bottom must out-produce the voided top");
        Check.greaterThan(flooded.axialPeakingFactor(), boiling.axialPeakingFactor(),
                "void must sharpen the axial shape");
    }

    /**
     * The timescale separation, at the level of the assembled reactor: the flux
     * shape and effective beta change once per
     * {@link CoreConfig#nodalSolveIntervalTicks} ticks and are frozen constants
     * in between, even while the rods are moving.
     *
     * <p>This is the improved quasi-static method and it is the reason the model
     * is not stiff. Power level answers a rod within a tick; power shape answers
     * it at the next spatial solve. The test scrams a mixed core — so that moving
     * rods really do move beta — and requires the frozen quantities to be
     * bit-identical within an interval and different across one.
     */
    public static void test10_shapeAndBetaAreFrozenBetweenSpatialSolves() {
        CoreConfig config = config();
        CoreLoading loading = mixedCore(true);
        ReactorCore core = new ReactorCore(config, loading);
        core.setBurnupEnabled(false);
        core.initialiseAtTotalPowerFraction(1.0);

        int interval = config.nodalSolveIntervalTicks;
        int ticks = 4 * interval;
        double[] frozenWeights = loading.powerWeights();
        double frozenBeta = core.getBetaEffective();
        double betaAtStart = frozenBeta;
        double powerAtStart = core.getTotalPowerFractionOfRated();

        core.scram();
        List<Integer> solveTicks = new ArrayList<>();
        int shortestInterval = Integer.MAX_VALUE;
        int previousSolveTick = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            core.step();
            double[] weights = loading.powerWeights();
            double beta = core.getBetaEffective();
            boolean shapeMoved = !Arrays.equals(weights, frozenWeights);
            boolean betaMoved = beta != frozenBeta;
            if (betaMoved && !shapeMoved) {
                Check.fail("beta_eff moved at tick %d without the shape moving; beta must come "
                        + "from the fission distribution and nothing else", tick);
            }
            if (shapeMoved) {
                shortestInterval = Math.min(shortestInterval, tick - previousSolveTick);
                previousSolveTick = tick;
                solveTicks.add(tick);
                frozenWeights = weights;
                frozenBeta = beta;
            }
        }

        double betaAtEnd = core.getBetaEffective();
        Check.note("solve interval %d ticks (%.2f s); the shape moved on ticks %s of %d",
                interval, interval * config.tickSeconds, solveTicks, ticks);
        Check.note("shortest run of frozen ticks: %d", shortestInterval);
        Check.note("beta_eff %.6f -> %.6f as the rods went into a mixed core",
                betaAtStart, betaAtEnd);
        Check.note("power %.4f -> %.4f over the same %.1f s", powerAtStart,
                core.getTotalPowerFractionOfRated(), ticks * config.tickSeconds);

        Check.greaterThan(1, solveTicks.size(),
                "the shape must actually be re-solved more than once during a scram");
        Check.greaterThan(interval - 2, shortestInterval,
                "the shape must stay frozen for a whole solve interval");
        Check.lessThan(interval + 2, shortestInterval,
                "and must not stay frozen for longer than one");
        Check.isFalse(betaAtStart == betaAtEnd,
                "beta_eff must follow the shape when a mixed core is rodded");
        Check.lessThan(0.5 * powerAtStart, core.getTotalPowerFractionOfRated(),
                "and the scram must still have taken the power down");
    }

    /**
     * The solve fits in the time it has.
     *
     * <p>It runs at 1 Hz on the server thread, so the number that matters is not
     * the cold solve but the one after a second of rod motion, warm-started from
     * a shape that has barely moved. The bounds here are loose because they are
     * timings on unknown hardware; the recorded numbers are the point.
     */
    public static void test11_aSolveFitsInsideItsSecond() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        solver.setVoidProfile(BOILING_BOUNDARY, EXIT_VOID);
        int[] notches = allRods(12);
        solver.setRodNotchIndices(notches);

        long coldNanos = System.nanoTime();
        FluxSolution cold = solver.solve();
        coldNanos = System.nanoTime() - coldNanos;

        long worstNanos = 0L;
        long totalNanos = 0L;
        int worstIterations = 0;
        int steps = 24;
        for (int step = 0; step < steps; step++) {
            // One rod indexes one notch, which is about what a second of drive
            // motion buys and therefore what a real solve sees.
            notches[step % notches.length] = 12 + (step % 5);
            solver.setRodNotchIndices(notches);
            long nanos = System.nanoTime();
            FluxSolution solution = solver.solve();
            nanos = System.nanoTime() - nanos;
            totalNanos += nanos;
            if (nanos > worstNanos) {
                worstNanos = nanos;
                worstIterations = solution.iterations();
            }
        }

        Check.note("%d cells, %d lattice positions x %d axial nodes",
                solver.mesh().nodeCount(), loading.loadedAssemblyCount(),
                solver.mesh().axialNodes());
        Check.note("cold solve %.1f ms (%d iterations)", coldNanos / 1.0e6, cold.iterations());
        Check.note("%d warm solves after a rod move: mean %.2f ms, worst %.2f ms (%d iterations)",
                steps, totalNanos / 1.0e6 / steps, worstNanos / 1.0e6, worstIterations);

        Check.lessThan(1000.0, coldNanos / 1.0e6, "cold solve, milliseconds");
        Check.lessThan(200.0, worstNanos / 1.0e6, "worst warm solve, milliseconds");
    }

    /**
     * The design rule, inside the nodal package: measurements and actuators,
     * never judgements.
     *
     * <p>Same scan as {@code ReactorCoreTickTest.test07}, applied to the four
     * classes that arrived with the spatial solve. A flux map is exactly where a
     * convenience method deciding that a peaking factor is unacceptable would be
     * tempting, and it is exactly where it must not exist — that judgement is the
     * player's, written in Lua, against the numbers these classes report.
     */
    public static void test12_noProtectionLogicInTheSpatialSolve() {
        Class<?>[] classes = {
                NodalFluxSolver.class, FluxSolution.class, NodalMesh.class, RodLatticeMap.class,
        };
        List<String> bannedWords = List.of(
                "high", "low", "trip", "trips", "tripped", "permissive", "permissives",
                "setpoint", "setpoints", "interlock", "interlocks", "alarm", "alarms",
                "unsafe", "acceptable", "violation");
        List<String> bannedNames = List.of(
                "shouldscram", "checktrips", "autoscram", "needsscram", "issafe", "isunsafe",
                "ishighpressure", "islowlevel", "checklimits", "enforcelimits",
                "protectionsystem", "safetysystem");

        List<String> offences = new ArrayList<>();
        int scanned = 0;
        for (Class<?> type : classes) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                scanned++;
                String flat = method.getName().toLowerCase(Locale.ROOT);
                for (String banned : bannedNames) {
                    if (flat.contains(banned)) {
                        offences.add(type.getSimpleName() + "." + method.getName()
                                + " (name '" + banned + "')");
                    }
                }
                for (String word : splitCamelCase(method.getName())) {
                    if (bannedWords.contains(word)) {
                        offences.add(type.getSimpleName() + "." + method.getName()
                                + " (word '" + word + "')");
                    }
                }
            }
        }

        Check.note("scanned %d methods across %d nodal classes", scanned, classes.length);
        for (String offence : offences) {
            Check.note("OFFENCE %s", offence);
        }
        Check.isTrue(offences.isEmpty(),
                "the spatial solve must expose measurements, never judgements: %s", offences);
    }

    /**
     * Mesh and rod-map bookkeeping, which everything above quietly assumes:
     * every loaded bundle is meshed and every empty position is not, neighbour
     * relations are reciprocal, no bundle answers to two rods, and the weights
     * are a probability distribution over the loaded positions.
     */
    public static void test13_theMeshAndRodMapCoverTheCoreExactlyOnce() {
        CoreLoading loading = uniformCore();
        NodalFluxSolver solver = solverFor(loading);
        NodalMesh mesh = solver.mesh();
        FluxSolution flux = solver.solve();

        Check.exactly(loading.loadedAssemblyCount() * mesh.axialNodes(), mesh.nodeCount(),
                "mesh cells");

        for (int node = 0; node < mesh.nodeCount(); node++) {
            for (int face = 0; face < NodalMesh.FACES; face++) {
                int neighbour = mesh.neighbour(node, face);
                if (neighbour < 0) {
                    continue;
                }
                int back = mesh.neighbour(neighbour, opposite(face));
                Check.exactly(node, back, "neighbour relation across face " + face + " must be mutual");
            }
        }

        RodLatticeMap map = solver.rodLatticeMap();
        int shadowed = 0;
        int[] owner = new int[loading.positionCount()];
        Arrays.fill(owner, -1);
        for (int rod = 0; rod < map.rodCount(); rod++) {
            for (int ordinal = 0; ordinal < map.positionCountOfRod(rod); ordinal++) {
                int position = map.positionOfRod(rod, ordinal);
                Check.exactly(-1, owner[position],
                        "position " + position + " must belong to at most one rod");
                Check.isTrue(loading.isOccupied(position),
                        "rod %d claims empty position %d", rod, position);
                owner[position] = rod;
                shadowed++;
            }
        }

        double total = 0.0;
        for (int position = 0; position < loading.positionCount(); position++) {
            double weight = flux.assemblyWeight(position);
            Check.finiteAndNonNegative(weight, "weight at position " + position);
            if (!loading.isOccupied(position)) {
                Check.exactly(0.0, weight, "weight at empty position " + position);
            }
            total += weight;
        }

        Check.note("%d assemblies, %d cells, %d rods shadowing %d assemblies (%.0f%%)",
                loading.loadedAssemblyCount(), mesh.nodeCount(), map.rodCount(), shadowed,
                100.0 * shadowed / loading.loadedAssemblyCount());
        Check.note("node %.2f x %.2f cm, volume %.0f cm3, weights sum to %.15f",
                mesh.pitchCm(), mesh.nodeHeightCm(), mesh.nodeVolumeCm3(), total);
        Check.relative(1.0, total, 1.0e-12, "power weights must sum to one over the core");
    }

    /**
     * A small core solves too. The relaxation factor is tuned for a full-size
     * lattice, and an over-relaxed sweep on a mesh a few cells across is where a
     * badly chosen one would show up as an oscillation instead of an answer.
     */
    public static void test14_asmallCoreSolvesAndStaysSymmetric() {
        CoreConfig config = config();
        config.controlRodCount = 4;
        CoreLoading loading = new CoreLoading(5);
        for (int position = 0; position < 25; position++) {
            loading.load(position, new FuelAssembly(FuelType.LEU));
        }
        NodalFluxSolver solver = new NodalFluxSolver(config, loading, 5);
        loading.setPowerWeightSource(solver);
        FluxSolution flux = solver.solve();

        double centre = flux.assemblyFlux(loading.index(2, 2));
        double edge = flux.assemblyFlux(loading.index(0, 2));
        double corner = flux.assemblyFlux(loading.index(0, 0));

        Check.note("5x5x5 core: %d cells, %d iterations, k=%.5f, non-leakage %.4f",
                solver.mesh().nodeCount(), flux.iterations(), flux.multiplicationFactor(),
                flux.nonLeakageProbability());
        Check.note("centre %.3f, edge %.3f, corner %.3f, radial peaking %.3f",
                centre, edge, corner, flux.radialPeakingFactor());

        Check.lessThan(solver.getMaximumIterations(), flux.iterations(),
                "a small core must converge inside the iteration budget");
        Check.greaterThan(edge, centre, "centre above edge");
        Check.greaterThan(corner, edge, "edge above corner");
        Check.relative(edge, flux.assemblyFlux(loading.index(4, 2)), 1.0e-3, "left-right symmetry");
        Check.relative(edge, flux.assemblyFlux(loading.index(2, 0)), 1.0e-3, "up-down symmetry");
        Check.lessThan(1.0, flux.nonLeakageProbability(),
                "a small core must leak more than a large one");
        Check.greaterThan(0.0, flux.nonLeakageProbability(), "non-leakage must be positive");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static FuelAssembly assembly(FuelType type, double burnupMwdPerTonne) {
        FuelAssembly assembly = new FuelAssembly(type);
        assembly.setBurnupMwdPerTonne(burnupMwdPerTonne);
        assembly.setGadoliniaRemainingFraction(burnupMwdPerTonne > 0.0 ? 0.0 : 1.0);
        return assembly;
    }

    /**
     * A core of LEU with MOX either in the middle or around the outside. The edge
     * ring is deliberately much larger than the central patch, so that the beta
     * comparison is not simply counting bundles.
     */
    private static CoreLoading mixedCore(boolean moxInTheCentre) {
        int width = ReactorCore.DEFAULT_LATTICE_WIDTH;
        CoreLoading loading = new CoreLoading(width);
        double centre = (width - 1) / 2.0;
        for (int position = 0; position < width * width; position++) {
            double x = (position % width) - centre;
            double y = (position / width) - centre;
            double radius = Math.hypot(x, y);
            if (radius > 15.4) {
                continue;
            }
            boolean mox = moxInTheCentre ? radius < 5.0 : radius > 13.0;
            loading.load(position, new FuelAssembly(mox ? FuelType.MOX : FuelType.LEU));
        }
        return loading;
    }

    private static double worstWeightDifference(FluxSolution a, FluxSolution b) {
        double[] first = a.assemblyWeights();
        double[] second = b.assemblyWeights();
        double worst = 0.0;
        for (int i = 0; i < first.length; i++) {
            if (first[i] > 0.0) {
                worst = Math.max(worst, Math.abs(first[i] - second[i]) / first[i]);
            }
        }
        return worst;
    }

    private static int indexOfPeak(double[] values) {
        int peak = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[peak]) {
                peak = i;
            }
        }
        return peak;
    }

    private static int opposite(int face) {
        return (face % 2 == 0) ? face + 1 : face - 1;
    }

    private static List<String> splitCamelCase(String name) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && current.length() > 0) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
                current.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return words;
    }
}
