package dev.bwr.core.accident;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;

import java.util.List;

/**
 * SPEC 8.1 stages 4 to 7: cladding failure, melt and relocation, lower head
 * failure and core-concrete interaction, driven directly with prescribed
 * temperatures and levels so each mechanism is checked on its own.
 */
public final class CoreMeltProgressionTest {

    private CoreMeltProgressionTest() {
    }

    static final double PSIG = PhysicalConstants.RATED_DOME_PRESSURE_PSIG;
    static final double CAPACITY = 120.0;
    /** About 1% of rated thermal power: decay heat an hour or two after shutdown. */
    static final double DECAY_MW = 36.0;
    /** Collapsed level with the lower plenum dry: vessel bottom. */
    static final double DRY_LEVEL = -PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
    /** Collapsed level with the lower plenum flooded but the core uncovered. */
    static final double PLENUM_FLOODED_LEVEL = DRY_LEVEL + 150.0;

    static void hold(SevereAccident sa, double fuelC, double cladC, double pressure, double level,
                     double floorWater, double seconds, double dt) {
        int steps = (int) Math.round(seconds / dt);
        for (int i = 0; i < steps; i++) {
            sa.step(fuelC, cladC, CAPACITY, DECAY_MW, pressure, level, 1.0, floorWater, dt);
        }
    }

    /** Melt a given fraction of the core, then let it all slump into the lower plenum. */
    static SevereAccident relocated(double fraction, double level) {
        SevereAccident sa = new SevereAccident();
        double fuelMassFractionMJ = CAPACITY * 1000.0 / SevereAccident.FUEL_SPECIFIC_HEAT_KJ_PER_KG_C
                * SevereAccident.FUEL_LATENT_HEAT_KJ_PER_KG / 1000.0;
        double excessC = fraction * fuelMassFractionMJ / CAPACITY;
        sa.step(SevereAccident.MELT_ONSET_C + excessC, 1500, CAPACITY, DECAY_MW, PSIG, level, 1.0, 0, 0.05);
        // Five relocation time constants with the fuel held below the plateau.
        hold(sa, 2000, 1500, PSIG, level, 0, 5 * SevereAccident.RELOCATION_TIME_CONSTANT_S, 1.0);
        return sa;
    }

    public static void test01_claddingFailureIsPermanentAndReleasesTheGap() {
        SevereAccident sa = new SevereAccident();
        hold(sa, 700, 600, PSIG, 0, 0, 10, 0.05);
        Check.exactly(0.0, sa.getFailedCladFraction(), "failed cladding below burst onset");
        Check.isTrue(sa.drainEvents().isEmpty(), "no event below burst onset");

        hold(sa, 1000, 1000, PSIG, 0, 0, 10, 0.05);
        double failed = sa.getFailedCladFraction();
        Check.inRange(0.3, 0.9, failed, "failed cladding fraction at 1000 degC");
        Check.isTrue(sa.drainEvents().equals(List.of(SevereAccident.Event.CLADDING_FAILURE)),
                "exactly one cladding failure event");
        Check.relative(SevereAccident.GAP_RELEASE_FRACTION * failed, sa.getReleasedFraction(), 1e-9,
                "gap release proportional to failed rods");

        hold(sa, 290, 290, PSIG, 0, 0, 600, 0.5);
        Check.exactly(failed, sa.getFailedCladFraction(), "failed cladding after cooldown");
        Check.isTrue(sa.isPermanentDamage(), "cladding failure is permanent");
        Check.isTrue(sa.getStage() == SevereAccident.Stage.CLADDING_FAILURE,
                "stage stays at cladding failure after cooldown, got %s", sa.getStage());
        Check.isTrue(sa.drainEvents().isEmpty(), "no repeated event");
        Check.note("%.1f%% of rods failed at 1000 degC; %.2f%% of inventory released, retained after quench",
                100 * failed, 100 * sa.getReleasedFraction());
    }

    public static void test02_meltPlateauSpendsExcessHeatAsLatentHeat() {
        SevereAccident sa = new SevereAccident();
        double fuel = SevereAccident.MELT_ONSET_C + 100.0;
        sa.step(fuel, 1800, CAPACITY, DECAY_MW, PSIG, DRY_LEVEL, 1.0, 0, 0.05);
        double expectedMJ = 100.0 * CAPACITY;
        double latentPerFraction = CAPACITY * 1000.0 / SevereAccident.FUEL_SPECIFIC_HEAT_KJ_PER_KG_C
                * SevereAccident.FUEL_LATENT_HEAT_KJ_PER_KG / 1000.0;
        Check.relative(expectedMJ, sa.getFuelEnergyToRemoveMJ(), 1e-9, "latent heat taken from the fuel");
        Check.relative(expectedMJ / latentPerFraction, sa.getMeltedFraction(), 1e-9, "melted fraction");
        Check.isTrue(sa.drainEvents().contains(SevereAccident.Event.FUEL_MELT_ONSET), "melt onset event");
        Check.isTrue(sa.getStage() == SevereAccident.Stage.FUEL_MELT, "stage is fuel melt, got %s", sa.getStage());

        SevereAccident cold = new SevereAccident();
        cold.step(SevereAccident.MELT_ONSET_C - 1.0, 1800, CAPACITY, DECAY_MW, PSIG, DRY_LEVEL, 1.0, 0, 0.05);
        Check.exactly(0.0, cold.getMeltedFraction(), "nothing melts below the plateau");
        Check.note("100 degC over the plateau melts %.1f%% of the core", 100 * sa.getMeltedFraction());
    }

    public static void test03_moltenFuelRelocatesIntoTheLowerPlenum() {
        SevereAccident sa = relocated(0.3, DRY_LEVEL);
        Check.relative(0.3, sa.getMeltedFraction(), 1e-6, "melted fraction is retained");
        Check.lessThan(0.3 * 0.01, sa.getMoltenInCoreFraction(), "molten material left in the core");
        Check.greaterThan(0.29, sa.getLowerPlenumDebrisFraction(), "debris in the lower plenum");
        Check.relative(0.7, sa.getIntactFraction(), 1e-6, "intact fraction");
        var events = sa.drainEvents();
        Check.isTrue(events.contains(SevereAccident.Event.CORE_RELOCATION), "relocation event, got %s", events);
        Check.note("after 5 tau: %.2f%% still molten in core, %.1f%% in the lower plenum",
                100 * sa.getMoltenInCoreFraction(), 100 * sa.getLowerPlenumDebrisFraction());
    }

    public static void test04_floodedLowerPlenumRetainsTheDebris() {
        SevereAccident sa = relocated(0.3, PLENUM_FLOODED_LEVEL);
        hold(sa, 1500, 1500, PSIG, PLENUM_FLOODED_LEVEL, 0, 4 * 3600, 1.0);
        double tsat = dev.bwr.core.thermal.Saturation.temperatureCelsiusFromPsia(
                dev.bwr.core.thermal.Saturation.psiaFromPsig(PSIG));
        // Decay heat holds a wet bed a few tens of degrees above saturation, far below creep onset.
        Check.lessThan(tsat + 100.0, sa.getDebrisTemperatureC(), "flooded debris temperature");
        Check.isFalse(sa.isLowerHeadFailed(), "a flooded lower head does not fail");
        Check.lessThan(0.25, sa.getLowerHeadDamage(), "lower head creep with a flooded plenum");
        Check.greaterThan(0.0, sa.getDebrisHeatToVesselMW(), "debris decay heat goes to the water");
        Check.note("4 h flooded: debris %.0f degC (Tsat %.0f), head creep %.1f%%",
                sa.getDebrisTemperatureC(), tsat, 100 * sa.getLowerHeadDamage());
    }

    static double secondsToHeadFailure(double pressurePsig, double limitSeconds) {
        SevereAccident sa = relocated(0.3, DRY_LEVEL);
        sa.drainEvents();
        double dt = 1.0;
        for (double t = 0; t < limitSeconds; t += dt) {
            sa.step(1500, 1500, CAPACITY, DECAY_MW, pressurePsig, DRY_LEVEL, 1.0, 0, dt);
            if (sa.isLowerHeadFailed()) {
                Check.isTrue(sa.drainEvents().contains(SevereAccident.Event.LOWER_HEAD_FAILURE),
                        "lower head failure event");
                return t;
            }
        }
        return Double.NaN;
    }

    public static void test05_dryDebrisFailsTheLowerHeadAndPressureHastensIt() {
        double atPressure = secondsToHeadFailure(PSIG, 6 * 3600);
        double depressurised = secondsToHeadFailure(50.0, 6 * 3600);
        Check.finite(atPressure, "time to head failure at rated pressure");
        Check.finite(depressurised, "time to head failure depressurised");
        Check.inRange(15 * 60, 3 * 3600, atPressure, "head failure time at pressure");
        Check.greaterThan(atPressure * 1.5, depressurised, "depressurised head lasts longer");
        Check.note("dry 30%% debris bed fails the head after %.0f min at %.0f psig, %.0f min at 50 psig",
                atPressure / 60, PSIG, depressurised / 60);
    }

    static SevereAccident exVessel() {
        SevereAccident sa = relocated(0.3, DRY_LEVEL);
        double dt = 1.0;
        for (int i = 0; i < 6 * 3600 && !sa.isLowerHeadFailed(); i++) {
            sa.step(1500, 1500, CAPACITY, DECAY_MW, PSIG, DRY_LEVEL, 1.0, 0, dt);
        }
        Check.isTrue(sa.isLowerHeadFailed(), "head failed for the ex-vessel case");
        sa.drainEvents();
        return sa;
    }

    public static void test06_headFailureMovesDebrisOutAndWaterSlowsAblation() {
        SevereAccident dry = exVessel();
        Check.exactly(0.0, dry.getLowerPlenumDebrisFraction(), "lower plenum debris after head failure");
        Check.relative(0.3, dry.getExVesselFraction(), 1e-6, "corium outside the vessel");
        Check.isTrue(dry.getStage().number() >= SevereAccident.Stage.LOWER_HEAD_FAILURE.number(),
                "stage after head failure, got %s", dry.getStage());
        SevereAccident wet = exVessel();
        hold(dry, 300, 300, 0, DRY_LEVEL, 0, 3600, 1.0);
        hold(wet, 300, 300, 0, DRY_LEVEL, 50.0, 3600, 1.0);
        Check.greaterThan(0.0, dry.getAblationDepthM(), "dry corium ablates concrete");
        Check.isTrue(dry.getStage() == SevereAccident.Stage.CORE_CONCRETE_INTERACTION,
                "dry corium is core-concrete interaction, got %s", dry.getStage());
        Check.lessThan(dry.getAblationDepthM() * 0.25, wet.getAblationDepthM(), "flooded ablation depth");
        Check.greaterThan(wet.getReleasedFraction(), dry.getReleasedFraction(), "uncooled corium releases more");
        Check.greaterThan(0.0, dry.getConcreteGasMolPerS(), "ablation makes gas");
        Check.note("1 h of 30%% core on concrete: %.2f m dry, %.3f m with 50 kg/s of water",
                dry.getAblationDepthM(), wet.getAblationDepthM());
    }

    public static void test07_basematMeltThroughIsAnEventAndStops() {
        SevereAccident sa = exVessel();
        double dt = 5.0;
        double t = 0;
        while (!sa.isBasematFailed() && t < 48 * 3600) {
            sa.step(300, 300, CAPACITY, DECAY_MW / 0.3, 0, DRY_LEVEL, 1.0, 0, dt);
            t += dt;
        }
        Check.isTrue(sa.isBasematFailed(), "basemat penetrated within 48 h of uncooled MCCI");
        Check.isTrue(sa.drainEvents().contains(SevereAccident.Event.BASEMAT_MELT_THROUGH), "melt-through event");
        Check.exactly(SevereAccident.BASEMAT_THICKNESS_M, sa.getAblationDepthM(), "ablation capped at the basemat");
        hold(sa, 300, 300, 0, DRY_LEVEL, 0, 600, 5.0);
        Check.isTrue(sa.drainEvents().isEmpty(), "melt-through fires once");
        Check.note("uncooled corium reaches through %.1f m of basemat after %.1f h",
                SevereAccident.BASEMAT_THICKNESS_M, t / 3600);
    }

    public static void test08_snapshotRoundTripsAndNeverReplaysEvents() {
        SevereAccident sa = exVessel();
        hold(sa, 300, 300, 0, DRY_LEVEL, 0, 600, 1.0);
        double[] snapshot = sa.toArray();
        SevereAccident restored = new SevereAccident();
        restored.fromArray(snapshot);
        Check.arraysExactly(snapshot, restored.toArray(), "severe accident snapshot");
        Check.isTrue(restored.drainEvents().isEmpty(), "restored state queues no events");
        Check.isTrue(restored.getStage() == SevereAccident.Stage.CORE_CONCRETE_INTERACTION,
                "restored stage, got %s", restored.getStage());
        restored.step(300, 300, CAPACITY, DECAY_MW, 0, DRY_LEVEL, 1.0, 0, 1.0);
        Check.isTrue(restored.drainEvents().isEmpty(), "first step after restore replays nothing");

        SevereAccident legacy = new SevereAccident();
        legacy.fromArray(new double[3]);
        Check.exactly(0.0, legacy.getMeltedFraction(), "short snapshot ignored");
    }

    public static void test09_adversarialInputStaysFiniteAndMonotonic() {
        SevereAccident sa = new SevereAccident();
        java.util.Random random = new java.util.Random(19790328L);
        double released = 0, melted = 0, head = 0, ablation = 0;
        double[] wild = {Double.NaN, Double.POSITIVE_INFINITY, -1e9, 0, 1e9};
        for (int i = 0; i < 100_000; i++) {
            double fuel = random.nextInt(10) == 0 ? wild[random.nextInt(wild.length)] : random.nextDouble() * 3500;
            double clad = random.nextInt(10) == 0 ? wild[random.nextInt(wild.length)] : random.nextDouble() * 2500;
            double level = random.nextDouble() * 800 - 600;
            sa.step(fuel, clad, CAPACITY, random.nextDouble() * 200, random.nextDouble() * 1200,
                    level, random.nextDouble(), random.nextDouble() * 100, random.nextInt(50) == 0 ? -1 : 0.25);
            Check.isTrue(sa.getReleasedFraction() >= released, "release decreased at step %d", i);
            Check.isTrue(sa.getMeltedFraction() >= melted - 1e-12, "melt decreased at step %d", i);
            Check.isTrue(sa.getLowerHeadDamage() >= head, "head damage decreased at step %d", i);
            Check.isTrue(sa.getAblationDepthM() >= ablation, "ablation decreased at step %d", i);
            released = sa.getReleasedFraction();
            melted = sa.getMeltedFraction();
            head = sa.getLowerHeadDamage();
            ablation = sa.getAblationDepthM();
            Check.isTrue(Double.isFinite(sa.getDebrisTemperatureC()), "debris temperature finite at step %d", i);
            Check.isTrue(melted <= 1.0 + 1e-9, "melted fraction above one at step %d", i);
        }
        Check.note("100,000 hostile steps: melted %.1f%%, head %.2f, ablation %.2f m, released %.1f%%",
                100 * melted, head, ablation, 100 * released);
    }
}
