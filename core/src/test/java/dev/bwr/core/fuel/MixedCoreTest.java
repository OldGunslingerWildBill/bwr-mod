package dev.bwr.core.fuel;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Mixed cores and end of cycle. SPEC 2.3.
 *
 * <p>Two claims are under test here and both are load-bearing for the whole
 * design:
 *
 * <ol>
 *   <li><b>Aggregation is flux-weighted.</b> Point kinetics is scalar, so a core
 *       holding five different fuels has to be reduced to one beta, one prompt
 *       lifetime, one Doppler coefficient. Weighting by fission rate rather than
 *       by count is what makes loading pattern a safety decision: a handful of
 *       low-beta bundles in the high-flux centre cost more control margin than
 *       three times as many parked on the periphery.</li>
 *   <li><b>End of cycle is a computed property of the fuel</b>, not a timer and
 *       not a setpoint — the core can no longer reach criticality with every rod
 *       withdrawn.</li>
 * </ol>
 */
public final class MixedCoreTest {

    private MixedCoreTest() {
    }

    /**
     * Effective beta against a hand-computed weighted average, with the flux
     * shape pinned so the arithmetic is exact rather than approximately right.
     *
     * <p>Installing a fixed weight source is the only way to test the
     * aggregation itself: the real weights come from an iterative nodal solve,
     * and a test that compared against numbers that solve produced would only be
     * checking that the solve is deterministic.
     */
    public static void test01_mixedCoreBetaIsTheFluxWeightedAverage() {
        CoreLoading loading = new CoreLoading(2);
        loading.load(0, new FuelAssembly(FuelType.LEU));
        loading.load(1, new FuelAssembly(FuelType.MOX));
        loading.load(2, new FuelAssembly(FuelType.PLUTONIUM));
        // Position 3 stays empty: an empty position must contribute nothing at
        // all, not a zero dragging the average down.
        final double[] weights = {0.60, 0.30, 0.10, 0.00};
        loading.setPowerWeightSource(l -> weights.clone());

        double expectedBeta = 0.60 * FuelType.LEU.beta()
                + 0.30 * FuelType.MOX.beta()
                + 0.10 * FuelType.PLUTONIUM.beta();
        double expectedLambda = 0.60 * FuelType.LEU.promptLifetimeSeconds()
                + 0.30 * FuelType.MOX.promptLifetimeSeconds()
                + 0.10 * FuelType.PLUTONIUM.promptLifetimeSeconds();
        double expectedDoppler = 0.60 * FuelType.LEU.dopplerCoeffPerC()
                + 0.30 * FuelType.MOX.dopplerCoeffPerC()
                + 0.10 * FuelType.PLUTONIUM.dopplerCoeffPerC();
        double expectedHeat = 0.60 * FuelType.LEU.heatPerFissionMeV()
                + 0.30 * FuelType.MOX.heatPerFissionMeV()
                + 0.10 * FuelType.PLUTONIUM.heatPerFissionMeV();
        double expectedKInf = 0.60 * loading.assemblyAt(0).kInf()
                + 0.30 * loading.assemblyAt(1).kInf()
                + 0.10 * loading.assemblyAt(2).kInf();

        Check.relative(expectedBeta, loading.effectiveBeta(), 1.0e-12, "flux-weighted beta");
        Check.relative(expectedLambda, loading.effectivePromptLifetimeSeconds(), 1.0e-12,
                "flux-weighted prompt lifetime");
        Check.relative(expectedDoppler, loading.effectiveDopplerCoeffPerC(), 1.0e-12,
                "flux-weighted Doppler coefficient");
        Check.relative(expectedHeat, loading.effectiveHeatPerFissionMeV(), 1.0e-12,
                "flux-weighted heat per fission");
        Check.relative(expectedKInf, loading.aggregateKInf(), 1.0e-12, "flux-weighted k-infinity");

        // The average must sit between the extremes and nowhere near the plain
        // arithmetic mean, which would be 0.004034 for these three fuels.
        double unweighted = (FuelType.LEU.beta() + FuelType.MOX.beta()
                + FuelType.PLUTONIUM.beta()) / 3.0;
        Check.inRange(FuelType.PLUTONIUM.beta(), FuelType.LEU.beta(), loading.effectiveBeta(),
                "beta lies between the fuels present");
        Check.greaterThan(0.0, Math.abs(loading.effectiveBeta() - unweighted),
                "weighted and unweighted differ");
        Check.note("60%% LEU / 30%% MOX / 10%% Pu by flux: beta_eff %.6f "
                        + "(unweighted mean would be %.6f)",
                loading.effectiveBeta(), unweighted);
        Check.note("Lambda_eff %.3e s, Doppler_eff %.3e /C, %.2f MeV/fission, k_inf %.4f",
                loading.effectivePromptLifetimeSeconds(), loading.effectiveDopplerCoeffPerC(),
                loading.effectiveHeatPerFissionMeV(), loading.aggregateKInf());

        // A single-fuel core is the degenerate case and must be exact, because
        // persistence is required to be bit-exact and the weights come from an
        // iterative solve whose last bits depend on its convergence history.
        CoreLoading uniform = new CoreLoading(2);
        for (int i = 0; i < 4; i++) {
            uniform.load(i, new FuelAssembly(FuelType.PLUTONIUM));
        }
        uniform.setPowerWeightSource(l -> new double[] {0.4, 0.3, 0.2, 0.1});
        Check.exactly(FuelType.PLUTONIUM.beta(), uniform.effectiveBeta(),
                "beta of a single-fuel core under lopsided weights");
    }

    /**
     * The same bundles in different places give a different core. This is the
     * consequence of flux weighting that a player can actually act on, and it is
     * why the refuelling GUI shows a lattice rather than a count.
     */
    public static void test02_positionOfTheLowBetaFuelChangesTheCore() {
        int width = 15;
        int moxCount = 24;

        CoreLoading centre = uniformCore(width, FuelType.LEU);
        CoreLoading edge = uniformCore(width, FuelType.LEU);
        List<Integer> byRadius = positionsByRadius(width);
        for (int i = 0; i < moxCount; i++) {
            centre.load(byRadius.get(i), new FuelAssembly(FuelType.MOX));
            edge.load(byRadius.get(byRadius.size() - 1 - i), new FuelAssembly(FuelType.MOX));
        }

        double centreBeta = centre.effectiveBeta();
        double edgeBeta = edge.effectiveBeta();
        double allLeu = FuelType.LEU.beta();

        Check.exactly(moxCount, countOf(centre, FuelType.MOX), "MOX bundles, centre loading");
        Check.exactly(moxCount, countOf(edge, FuelType.MOX), "MOX bundles, edge loading");
        Check.lessThan(allLeu, centreBeta, "MOX anywhere lowers effective beta");
        Check.lessThan(allLeu, edgeBeta, "MOX anywhere lowers effective beta");
        Check.lessThan(edgeBeta, centreBeta,
                "the same MOX in the high-flux centre must cost more margin than on the periphery");

        double centreCost = allLeu - centreBeta;
        double edgeCost = allLeu - edgeBeta;
        Check.greaterThan(1.5, centreCost / edgeCost,
                "centre loading should cost substantially more than edge loading");
        Check.note("%d MOX in a %dx%d LEU core: centre beta_eff %.6f, edge %.6f, all-LEU %.6f",
                moxCount, width, width, centreBeta, edgeBeta, allLeu);
        Check.note("margin lost: %.3e in the centre vs %.3e at the edge - %.2fx for the same fuel",
                centreCost, edgeCost, centreCost / edgeCost);
    }

    /**
     * End of cycle triggers when the fuel runs out, and not before. The
     * threshold is derived here from the fuel constants rather than copied from
     * the model, so this fails if either the k-infinity law or the non-leakage
     * treatment changes underneath it.
     */
    public static void test03_endOfCycleTriggersWhenTheFuelIsSpent() {
        CoreLoading loading = uniformCore(9, FuelType.LEU);
        double nonLeakage = loading.nonLeakageProbability();

        Check.isFalse(loading.isEndOfCycle(), "a fresh LEU core is not at end of cycle");
        Check.greaterThan(1.0, loading.kEffAllRodsOut(), "fresh k_eff all rods out");
        double freshKEff = loading.kEffAllRodsOut();
        double freshExcess = loading.excessReactivityAllRodsOutDkK();

        // k_inf(B) = base - depletion*B once the gadolinia is gone (which it is,
        // well before end of cycle). End of cycle is k_inf * P_nl = 1.
        double kInfAtCritical = 1.0 / nonLeakage;
        double expectedBurnup = (FuelType.LEU.kInfBase() - kInfAtCritical)
                / FuelType.LEU.depletionKInfPerMwdPerTonne();

        burnEveryAssembly(loading, expectedBurnup - 200.0);
        Check.isFalse(loading.isEndOfCycle(),
                "200 MWd/t short of the computed threshold, k_eff=%.6f", loading.kEffAllRodsOut());
        Check.greaterThan(1.0, loading.kEffAllRodsOut(), "k_eff just before end of cycle");

        burnEveryAssembly(loading, 400.0);
        Check.isTrue(loading.isEndOfCycle(),
                "200 MWd/t past the computed threshold, k_eff=%.6f", loading.kEffAllRodsOut());
        Check.lessThan(1.0, loading.kEffAllRodsOut(), "k_eff just after end of cycle");
        Check.lessThan(0.0, loading.excessReactivityAllRodsOutDkK(),
                "excess reactivity is negative past end of cycle");

        Check.note("fresh LEU: k_eff(ARO) %.4f, excess %.4f dk/k", freshKEff, freshExcess);
        Check.note("end of cycle at %.0f MWd/t (P_nl %.2f, k_inf must fall to %.4f); "
                        + "bracketed at %.0f -> not spent, %.0f -> spent",
                expectedBurnup, nonLeakage, kInfAtCritical,
                expectedBurnup - 200.0, expectedBurnup + 200.0);

        // The gadolinia has to be long gone by then, or the arithmetic above is
        // measuring the wrong thing.
        Check.exactly(0.0, loading.assemblyAt(0).gadoliniaRemainingFraction(),
                "gadolinia remaining at end of cycle");
    }

    /**
     * End of cycle in a mixed core is decided by the flux-weighted aggregate, so
     * a handful of fresh bundles in the centre can hold up a core that is spent
     * on average. This is the mechanic that makes shuffling worth the trouble.
     */
    public static void test04_endOfCycleInAMixedCoreFollowsTheFluxWeightedAggregate() {
        int width = 11;
        int freshCount = 40;
        double spentBurnup = 34_000.0;

        CoreLoading spent = uniformCore(width, FuelType.LEU);
        burnEveryAssembly(spent, spentBurnup);
        Check.isTrue(spent.isEndOfCycle(), "a core burned to %.0f MWd/t is spent", spentBurnup);

        // Identical inventory in both: the same 40 fresh bundles among the same
        // spent ones. Only where they sit differs.
        List<Integer> byRadius = positionsByRadius(width);
        CoreLoading freshInCentre = uniformCore(width, FuelType.LEU);
        CoreLoading freshAtEdge = uniformCore(width, FuelType.LEU);
        burnEveryAssembly(freshInCentre, spentBurnup);
        burnEveryAssembly(freshAtEdge, spentBurnup);
        for (int i = 0; i < freshCount; i++) {
            freshInCentre.load(byRadius.get(i), new FuelAssembly(FuelType.LEU));
            freshAtEdge.load(byRadius.get(byRadius.size() - 1 - i), new FuelAssembly(FuelType.LEU));
        }

        double centreK = freshInCentre.kEffAllRodsOut();
        double edgeK = freshAtEdge.kEffAllRodsOut();
        Check.greaterThan(edgeK, centreK,
                "fresh fuel in the high-flux centre must lift k_eff more than at the edge");
        Check.isFalse(freshInCentre.isEndOfCycle(),
                "%d fresh bundles in the centre bring the core back critical, k_eff=%.4f",
                freshCount, centreK);
        Check.isTrue(freshAtEdge.isEndOfCycle(),
                "the same %d bundles on the periphery do not, k_eff=%.4f", freshCount, edgeK);

        Check.note("%dx%d core burned to %.0f MWd/t: k_eff(ARO) %.4f, spent",
                width, width, spentBurnup, spent.kEffAllRodsOut());
        Check.note("+%d fresh bundles in the centre: %.4f (critical) - at the edge: %.4f (not)",
                freshCount, centreK, edgeK);
    }

    /**
     * Thorium starts weak because Th-232 is not fissile, then gets better as
     * U-233 breeds in, then eventually runs down like anything else (SPEC 2.2).
     * No special case anywhere makes that happen — it is one exponential term in
     * the burnup penalty.
     */
    public static void test05_thoriumImprovesBeforeItEndsItsCycle() {
        FuelAssembly thorium = new FuelAssembly(FuelType.THORIUM);
        FuelAssembly leu = new FuelAssembly(FuelType.LEU);

        double freshThorium = thorium.kInf();
        Check.lessThan(leu.kInf(), freshThorium, "fresh thorium starts weaker than fresh LEU");

        // A full thorium core can technically go critical all-rods-out, but with
        // so little excess that xenon alone would take it subcritical — which is
        // "needs a fissile driver" stated as a number rather than as a rule.
        CoreLoading thoriumCore = uniformCore(9, FuelType.THORIUM);
        CoreLoading leuCore = uniformCore(9, FuelType.LEU);
        Check.lessThan(0.03, thoriumCore.excessReactivityAllRodsOutDkK(),
                "a bare thorium core must have very little excess reactivity");
        Check.greaterThan(3.0, leuCore.excessReactivityAllRodsOutDkK()
                / thoriumCore.excessReactivityAllRodsOutDkK(),
                "LEU must start with several times thorium's excess");

        double peak = freshThorium;
        double peakAt = 0.0;
        double endOfCycleAt = Double.NaN;
        double previous = freshThorium;
        for (double burnup = 500.0; burnup <= 60_000.0; burnup += 500.0) {
            thorium.setBurnupMwdPerTonne(burnup);
            double k = thorium.kInf();
            if (k > peak) {
                peak = k;
                peakAt = burnup;
            }
            if (Double.isNaN(endOfCycleAt) && k * CoreLoading.DEFAULT_NON_LEAKAGE_PROBABILITY <= 1.0
                    && burnup > peakAt) {
                endOfCycleAt = burnup;
            }
            previous = k;
        }

        Check.greaterThan(freshThorium, peak, "thorium must get better before it gets worse");
        Check.greaterThan(0.0, peakAt, "the improvement must peak at a positive burnup");
        Check.finite(endOfCycleAt, "thorium reaches end of cycle within the scan");
        Check.lessThan(peak, previous, "thorium is worse at the end of the scan than at its peak");

        Check.note("thorium k_inf: fresh %.4f, peaks %.4f at %.0f MWd/t, "
                        + "end of cycle at %.0f MWd/t, %.4f at 60000",
                freshThorium, peak, peakAt, endOfCycleAt, previous);
        Check.note("bare-core excess reactivity: thorium %.4f dk/k vs LEU %.4f dk/k",
                thoriumCore.excessReactivityAllRodsOutDkK(),
                leuCore.excessReactivityAllRodsOutDkK());
    }

    /**
     * {@code ReactorCore} forwards end of cycle as a measurement, so a control
     * program can watch the fuel run down without reaching into the loading.
     * Nothing acts on it: it is a number on a panel, and planning the outage is
     * the player's job.
     */
    public static void test06_reactorCoreForwardsEndOfCycleAsAMeasurement() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        CoreLoading loading = core.getCoreLoading();

        Check.exactly(loading.kEffAllRodsOut(), core.getKEffectiveAllRodsOut(),
                "ReactorCore.getKEffectiveAllRodsOut agrees with the loading");
        Check.isFalse(core.isEndOfCycle(), "the default fresh core is not at end of cycle");
        double fresh = core.getKEffectiveAllRodsOut();

        burnEveryAssembly(loading, 40_000.0);
        Check.isTrue(core.isEndOfCycle(), "the same core burned to 40 GWd/t is");
        Check.lessThan(1.0, core.getKEffectiveAllRodsOut(), "k_eff all rods out when spent");
        Check.note("default %d-bundle core: k_eff(ARO) %.4f fresh -> %.4f at 40 GWd/t",
                loading.loadedAssemblyCount(), fresh, core.getKEffectiveAllRodsOut());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static CoreLoading uniformCore(int width, FuelType type) {
        CoreLoading loading = new CoreLoading(width);
        for (int i = 0; i < loading.positionCount(); i++) {
            loading.load(i, new FuelAssembly(type));
        }
        return loading;
    }

    private static void burnEveryAssembly(CoreLoading loading, double deltaMwdPerTonne) {
        for (int i = 0; i < loading.positionCount(); i++) {
            FuelAssembly assembly = loading.assemblyAt(i);
            if (assembly != null) {
                assembly.accumulateBurnupMwdPerTonne(deltaMwdPerTonne);
            }
        }
        loading.invalidateWeights();
    }

    private static int countOf(CoreLoading loading, FuelType type) {
        int count = 0;
        for (FuelAssembly assembly : loading.loadedAssemblies()) {
            if (assembly.fuelType() == type) {
                count++;
            }
        }
        return count;
    }

    /** Lattice positions ordered from the centre outward. */
    private static List<Integer> positionsByRadius(int width) {
        double centre = (width - 1) / 2.0;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < width * width; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> {
            double x = (i % width) - centre;
            double y = (i / width) - centre;
            return x * x + y * y;
        }));
        return order;
    }
}
