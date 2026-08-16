package dev.bwr.core.nodal;

/**
 * The result of one nodal solve: a flux map, the fission share of every
 * assembly, and the eigenvalue that came with them.
 *
 * <h2>This is a measurement, not a judgement</h2>
 * Nothing here decides that a peaking factor is excessive, that a hot spot needs
 * answering or that a rod should move. It reports what the flux is doing. The
 * player's Lua reads these numbers off the peripheral and forms its own opinion,
 * which is the whole design premise ({@code SPEC.md} section 9).
 *
 * <h2>Weights are fission-rate shares</h2>
 * {@link #assemblyWeights()} is each assembly's share of core fissions, which is
 * exactly the weighting {@link dev.bwr.core.fuel.CoreLoading} wants: effective
 * beta is the <b>fission-rate</b>-weighted average of the assemblies' betas.
 * Beta is a nuclear property of the fissioning isotope and does not vary with
 * position; what varies is flux, so the spatial part of beta lives entirely in
 * these weights. Recoverable energy per fission differs between fuels by a few
 * percent, so treating a fission share as a thermal power share carries that
 * much error in a mixed core — worth knowing, not worth a second weighting.
 *
 * <p>Immutable. Arrays are copied on the way in and on the way out.
 */
public final class FluxSolution {

    private final NodalMesh mesh;
    private final double[] nodeFlux;
    private final double[] nodeFissionRate;
    private final double[] assemblyWeights;
    private final double[] assemblyFlux;
    private final double[] axialShape;
    private final double[] rodFluxWeights;
    private final double multiplicationFactor;
    private final double nonLeakageProbability;
    private final int iterations;
    private final double eigenvalueResidual;
    private final double fluxResidual;

    FluxSolution(NodalMesh mesh,
                 double[] nodeFlux,
                 double[] nodeFissionRate,
                 double[] assemblyWeights,
                 double[] assemblyFlux,
                 double[] axialShape,
                 double[] rodFluxWeights,
                 double multiplicationFactor,
                 double nonLeakageProbability,
                 int iterations,
                 double eigenvalueResidual,
                 double fluxResidual) {
        this.mesh = mesh;
        this.nodeFlux = nodeFlux;
        this.nodeFissionRate = nodeFissionRate;
        this.assemblyWeights = assemblyWeights;
        this.assemblyFlux = assemblyFlux;
        this.axialShape = axialShape;
        this.rodFluxWeights = rodFluxWeights;
        this.multiplicationFactor = multiplicationFactor;
        this.nonLeakageProbability = nonLeakageProbability;
        this.iterations = iterations;
        this.eigenvalueResidual = eigenvalueResidual;
        this.fluxResidual = fluxResidual;
    }

    /** The mesh this was solved on. */
    public NodalMesh mesh() {
        return mesh;
    }

    // ---------------------------------------------------------------
    // The flux map
    // ---------------------------------------------------------------

    /**
     * Flux at one cell, normalised so the core-average cell flux is 1.0. Zero
     * for a lattice position holding no fuel.
     */
    public double flux(int latticePosition, int axialNode) {
        int node = mesh.nodeAt(latticePosition, axialNode);
        return node < 0 ? 0.0 : nodeFlux[node];
    }

    /** Copy of the whole flux map in mesh node order, core-average 1.0. */
    public double[] nodeFlux() {
        return nodeFlux.clone();
    }

    /**
     * Axially averaged flux per lattice position, core-average 1.0 over occupied
     * positions, zero where nothing is loaded. This is the radial flux map the
     * core display draws.
     */
    public double[] assemblyFlux() {
        return assemblyFlux.clone();
    }

    /** Axially averaged flux at one lattice position. */
    public double assemblyFlux(int latticePosition) {
        return assemblyFlux[latticePosition];
    }

    /**
     * Core-average axial fission shape, one entry per axial node, mean 1.0.
     * Bottom-peaked in a boiling core, because the voided upper half moderates
     * worse — the shape a BWR actually runs.
     */
    public double[] axialShape() {
        return axialShape.clone();
    }

    // ---------------------------------------------------------------
    // Fission distribution
    // ---------------------------------------------------------------

    /**
     * Share of core fissions occurring at each lattice position, summing to 1
     * over occupied positions. This is what
     * {@link dev.bwr.core.fuel.CoreLoading#powerWeights()} publishes.
     */
    public double[] assemblyWeights() {
        return assemblyWeights.clone();
    }

    /** Share of core fissions at one lattice position. */
    public double assemblyWeight(int latticePosition) {
        return assemblyWeights[latticePosition];
    }

    /** Fission rate at one cell, normalised so the whole core sums to 1.0. */
    public double nodeFissionShare(int latticePosition, int axialNode) {
        int node = mesh.nodeAt(latticePosition, axialNode);
        return node < 0 ? 0.0 : nodeFissionRate[node];
    }

    /**
     * Fission-rate-weighted average of a per-assembly quantity — the weighted sum
     * that effective beta falls out of ({@code SPEC.md} section 1.3).
     *
     * <p>Feed it each assembly's beta and the answer is the core's effective
     * beta; feed it prompt lifetime, or Doppler coefficient, and the answer is
     * the aggregate the scalar kinetics runs on. Positions holding no fuel carry
     * zero weight and their entries are ignored.
     *
     * @param perPosition one value per lattice position
     * @param emptyValue  returned when the core has no fissions at all
     */
    public double fissionWeightedAverage(double[] perPosition, double emptyValue) {
        if (perPosition == null || perPosition.length != assemblyWeights.length) {
            throw new IllegalArgumentException("expected one value per lattice position ("
                    + assemblyWeights.length + "), got "
                    + (perPosition == null ? "null" : String.valueOf(perPosition.length)));
        }
        double sum = 0.0;
        double total = 0.0;
        for (int position = 0; position < perPosition.length; position++) {
            double weight = assemblyWeights[position];
            if (weight > 0.0) {
                sum += weight * perPosition[position];
                total += weight;
            }
        }
        return total > 0.0 ? sum / total : emptyValue;
    }

    /**
     * Highest assembly fission share divided by the average over loaded
     * assemblies — the radial peaking factor. A flat core reads 1.0; a stuck rod
     * or a fresh bundle in a burned core pushes it up. Reported, never acted on.
     */
    public double radialPeakingFactor() {
        double peak = 0.0;
        double total = 0.0;
        int loaded = 0;
        for (double weight : assemblyWeights) {
            if (weight > 0.0) {
                peak = Math.max(peak, weight);
                total += weight;
                loaded++;
            }
        }
        return (loaded > 0 && total > 0.0) ? peak / (total / loaded) : 0.0;
    }

    /** Highest axial fission share divided by the average — the axial peaking factor. */
    public double axialPeakingFactor() {
        double peak = 0.0;
        for (double value : axialShape) {
            peak = Math.max(peak, value);
        }
        return peak;
    }

    // ---------------------------------------------------------------
    // Rods
    // ---------------------------------------------------------------

    /**
     * Relative flux each control rod sits in, mean 1.0 over rods that shadow any
     * fuel. A rod in peak flux is worth several times a peripheral one, which is
     * what {@link dev.bwr.core.kinetics.RodWorth#setRodFluxWeights} exists to
     * consume.
     */
    public double[] rodFluxWeights() {
        return rodFluxWeights.clone();
    }

    // ---------------------------------------------------------------
    // Eigenvalue and convergence
    // ---------------------------------------------------------------

    /**
     * Effective multiplication factor of the solved core, including leakage,
     * rods and the void distribution.
     *
     * <p>A diagnostic here, not the reactivity the plant runs on:
     * {@link dev.bwr.core.kinetics.ReactivityBalance} owns reactivity and closes
     * it from fuel, rods, void, Doppler, xenon, pressure and boron. Publishing
     * this alongside that is deliberate — the two agreeing is a check on both.
     */
    public double multiplicationFactor() {
        return multiplicationFactor;
    }

    /**
     * Fraction of neutrons that do not leak out of the active core: absorption
     * over absorption plus boundary leakage, taken straight off the converged
     * balance as the solved eigenvalue divided by the k-infinity the solve
     * actually ran on. A smaller or more loosely-packed core leaks more, and
     * this is where that number comes from rather than a hand-set constant.
     *
     * <p><b>The divisor is the in-place k-infinity, including rods, supplemental
     * absorbers and the void depression — not the fuel's catalogue value.</b>
     * That distinction is the difference between a leakage probability and a
     * number that merely looks like one. Every absorber the solve saw is already
     * inside the eigenvalue, so dividing by the clean assembly k-infinity books
     * the absorber against leakage: the earlier form of this method reported
     * roughly 0.97 for a core with its rods out and roughly 0.80 for the same
     * core scrammed, on geometry that had not moved a centimetre. Rods do shift
     * this a little, because they redistribute flux and a core peaked further
     * from its boundary leaks slightly less, and that part is real.
     */
    public double nonLeakageProbability() {
        return nonLeakageProbability;
    }

    /** Outer power iterations this solve took. */
    public int iterations() {
        return iterations;
    }

    /** Relative change in the eigenvalue on the final iteration. */
    public double eigenvalueResidual() {
        return eigenvalueResidual;
    }

    /** Largest relative change in any cell's flux on the final sweep. */
    public double fluxResidual() {
        return fluxResidual;
    }

    @Override
    public String toString() {
        return String.format("FluxSolution[k=%.5f, P_nl=%.4f, radial peak %.3f, axial peak %.3f, "
                        + "%d iterations, residual %.2e]",
                multiplicationFactor, nonLeakageProbability, radialPeakingFactor(),
                axialPeakingFactor(), iterations, Math.max(eigenvalueResidual, fluxResidual));
    }
}
