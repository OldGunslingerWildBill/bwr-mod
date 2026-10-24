package dev.bwr.core;

import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.SteamSeparation;
import dev.bwr.core.thermal.VesselBuild;
import dev.bwr.core.turbine.PowerTurbine;
import dev.bwr.core.turbine.SteamInventory;

/**
 * VESSEL-HEIGHT.md: what the height of a built vessel does to separation, the turbine, natural circulation, the steam
 * dome and the water inventory. The builds are what the mod's {@code VesselProfile} measures for a 17 x 17 footprint at
 * each player-counted height (envelope height + 1 - the mod GameTest {@code vesselheightbuildsmatchthecore} compares these
 * literals with the real geometry).
 */
public final class VesselHeightTest {

    private VesselHeightTest() {
    }

    static final VesselBuild H20 = new VesselBuild(0.207662306, 36.756228161, 41.646675467, 73.373019911);
    static final VesselBuild H28 = VesselBuild.REFERENCE;
    /** The first whole height at which the drawn separators reach Columbia's proportions. */
    static final VesselBuild H39 = new VesselBuild(1.0, 179.494485294, 203.044485294, 225.066071429);
    static final VesselBuild H43 = new VesselBuild(1.0, 245.408350840, 268.958350840, 225.066071429);
    static final VesselBuild H60 = new VesselBuild(1.0, 525.542279412, 549.092279412, 225.066071429);
    static final VesselBuild[] BUILDS = {H20, H28, H39, H43, H60};
    static final int[] HEIGHTS = {20, 28, 39, 43, 60};

    /** The normal operating level, physical, inches above vessel zero (instrument +30 in). */
    static final double NORMAL_VESSEL_IN = 560.0;

    /** A rated core, run long enough for the void swell to put the level where it stands at power (a fresh one has no voids yet and reads 22 in low), then given the build. */
    static ReactorCore ratedCore(VesselBuild build) {
        ReactorCore core = settledBareCore();
        core.applyVesselBuild(build);
        return core;
    }

    static ReactorCore settledBareCore() {
        ReactorCore core = NaturalCirculationTest.ratedCore();
        NaturalCirculationTest.runRegulated(core, 30, 0.25);
        return core;
    }

    // ---------------------------------------------------------------
    // 1. Separator and dryer vs scaled height
    // ---------------------------------------------------------------

    public static void test01_moistureIsDesignAtTrueScaleAndRisesAsTheStackIsSquashed() {
        Check.exactly(SteamSeparation.DESIGN_MOISTURE, SteamSeparation.moisture(1.0, NORMAL_VESSEL_IN),
                "moisture at true scale in the band");
        Check.exactly(0.0, SteamSeparation.excessMoisture(SteamSeparation.moisture(1.0, NORMAL_VESSEL_IN)),
                "excess moisture at true scale");
        double previous = SteamSeparation.moisture(1.0, NORMAL_VESSEL_IN);
        for (double r = 0.95; r >= 0.0; r -= 0.05) {
            double w = SteamSeparation.moisture(r, NORMAL_VESSEL_IN);
            Check.greaterThan(previous, w, "moisture rises as the separators are squashed, scale " + r);
            previous = w;
        }
        double reference = SteamSeparation.moisture(VesselBuild.REFERENCE.separatorRatio(), NORMAL_VESSEL_IN);
        Check.inRange(0.005, 0.012, reference, "the 17x17x28 reference's moisture");
        Check.inRange(0.03, 0.06, SteamSeparation.moisture(H20.separatorRatio(), NORMAL_VESSEL_IN), "a 20 high vessel");
        Check.inRange(0.09, 0.14, SteamSeparation.moisture(0.0, NORMAL_VESSEL_IN),
                "separators with no swirl run left keep their fixed vane stage");
        Check.note("moisture at true scale %.3f %%, at the reference (scale %.3f) %.3f %%, at scale 0.21 %.2f %%",
                100 * SteamSeparation.DESIGN_MOISTURE, VesselBuild.REFERENCE.separatorRatio(), 100 * reference,
                100 * SteamSeparation.moisture(H20.separatorRatio(), NORMAL_VESSEL_IN));
        // A better-than-true vessel is not better: the ratio is capped.
        Check.exactly(1.0, new VesselBuild(3.0, 100, 100, 100).separatorRatio(), "ratio cap");
    }

    public static void test02_carryunderIsDesignAtTrueScaleAndRisesAsTheStackIsSquashed() {
        Check.exactly(SteamSeparation.DESIGN_CARRYUNDER, SteamSeparation.carryunder(1.0, NORMAL_VESSEL_IN),
                "carryunder at true scale in the band");
        double previous = SteamSeparation.carryunder(1.0, NORMAL_VESSEL_IN);
        for (double r = 0.8; r >= 0.0; r -= 0.2) {
            double c = SteamSeparation.carryunder(r, NORMAL_VESSEL_IN);
            Check.greaterThan(previous, c, "carryunder rises as the separators are squashed, scale " + r);
            previous = c;
        }
        Check.relative(SteamSeparation.SQUASHED_CARRYUNDER_MULTIPLE
                        * SteamSeparation.DESIGN_CARRYUNDER * Math.pow(
                        SteamSeparation.SQUASHED_CARRYUNDER_MULTIPLE, -SteamSeparation.FIXED_SEPARATION_FRACTION),
                SteamSeparation.carryunder(0.0, NORMAL_VESSEL_IN), 1e-12, "carryunder with no swirl run");
    }

    // ---------------------------------------------------------------
    // 2. The level band
    // ---------------------------------------------------------------

    public static void test03_highLevelGivesCarryoverAndLowLevelGivesCarryunder() {
        double r = 1.0;
        double inBandMoisture = SteamSeparation.moisture(r, NORMAL_VESSEL_IN);
        double inBandCarryunder = SteamSeparation.carryunder(r, NORMAL_VESSEL_IN);
        // Anywhere in the band, nothing changes.
        for (double level = SteamSeparation.L3_VESSEL_IN; level <= SteamSeparation.L8_VESSEL_IN; level += 5.5) {
            Check.exactly(inBandMoisture, SteamSeparation.moisture(r, level), "moisture in band at " + level);
            Check.exactly(inBandCarryunder, SteamSeparation.carryunder(r, level), "carryunder in band at " + level);
        }
        // High: carryover rises sharply, carryunder does not move.
        double l8plus12 = SteamSeparation.moisture(r, SteamSeparation.L8_VESSEL_IN + 12);
        double l8plus24 = SteamSeparation.moisture(r, SteamSeparation.L8_VESSEL_IN + 24);
        Check.greaterThan(5 * inBandMoisture, l8plus12, "moisture 12 in over L8");
        Check.greaterThan(5 * l8plus12, l8plus24, "and climbing");
        Check.inRange(0.03, 0.2, l8plus24, "24 in over L8 floods the separators");
        Check.exactly(inBandCarryunder, SteamSeparation.carryunder(r, SteamSeparation.L8_VESSEL_IN + 24),
                "high level does not carry steam under");
        Check.exactly(SteamSeparation.INLET_MOISTURE, SteamSeparation.moisture(r, SteamSeparation.STEAM_NOZZLE_VESSEL_IN + 10),
                "water at the steam nozzle is water in the steam line");
        // Low: carryunder rises, carryover does not move.
        double l3minus12 = SteamSeparation.carryunder(r, SteamSeparation.L3_VESSEL_IN - 12);
        Check.greaterThan(10 * inBandCarryunder, l3minus12, "carryunder 12 in under L3");
        Check.exactly(inBandMoisture, SteamSeparation.moisture(r, SteamSeparation.L3_VESSEL_IN - 12),
                "low level does not carry water over");
        Check.relative(SteamSeparation.MAX_CARRYUNDER, SteamSeparation.carryunder(r, 100), 1e-12, "ceiling");
        Check.note("true-scale vessel: moisture %.3f %% in band, %.2f %% at L8+12, %.1f %% at L8+24; carryunder %.2f %% in band, %.1f %% at L3-12",
                100 * inBandMoisture, 100 * l8plus12, 100 * l8plus24, 100 * inBandCarryunder, 100 * l3minus12);
    }

    public static void test04_theBandReadoutFollowsTheCoreLevel() {
        ReactorCore core = ratedCore(H28);
        double normal = core.getSeparatorBandPosition();
        Check.inRange(0.3, 0.7, normal, "normal level stands mid-band");
        Check.relative(SteamSeparation.bandPosition(560.0), normal, 0.05, "the normal two-phase level, +30 in, is 560 in");
        PressureVessel v = core.getPressureVessel();
        v.setCollapsedLevelIn(v.getCollapsedLevelIn() + 40);
        Check.greaterThan(1.0, core.getSeparatorBandPosition(), "overfilled: above the band");
        Check.greaterThan(5 * SteamSeparation.moisture(H28.separatorRatio(), NORMAL_VESSEL_IN),
                core.getSteamMoistureFraction(), "the core reports the carryover of the high level");
        v.setCollapsedLevelIn(-60);
        Check.lessThan(0.0, core.getSeparatorBandPosition(), "drawn down: below the band");
        Check.greaterThan(0.03, core.getCarryunderFraction(), "the core reports the carryunder of the low level");
    }

    // ---------------------------------------------------------------
    // 3. Wet steam at the turbine
    // ---------------------------------------------------------------

    /** Work of one tick of rated flow through the HP then the LP turbine, kJ, for steam of this moisture. */
    static double trainWorkKJ(double moisture, double[] erosionOut) {
        double psia = 1015;
        double kg = 1941.0 / 20.0;
        SteamInventory hpIn = new SteamInventory(200);
        SteamInventory cross = new SteamInventory(400);
        SteamInventory condenser = new SteamInventory(400);
        hpIn.offer(new SteamInventory.Packet(kg, Saturation.vapourEnthalpyKJPerKg(psia), psia, moisture));
        double work = 0;
        double wear = 0;
        var hp = PowerTurbine.expand(PowerTurbine.Stage.HP, hpIn, cross, kg, Double.MAX_VALUE,
                PowerTurbine.outletPressure(PowerTurbine.Stage.HP, psia));
        wear += PowerTurbine.erosionIncrement(hp.mass(), moisture);
        work += hp.workKJ();
        var lp = PowerTurbine.expand(PowerTurbine.Stage.LP, cross, condenser, kg, Double.MAX_VALUE, 1.07);
        wear += PowerTurbine.erosionIncrement(lp.mass(), cross.moisture());
        work += lp.workKJ();
        if (erosionOut != null) {
            erosionOut[0] = wear;
        }
        return work;
    }

    public static void test05_wetSteamLowersTurbineWorkAndWearsTheBlades() {
        double[] wearDry = new double[1];
        double dry = trainWorkKJ(0.0, wearDry);
        double[] wearDesign = new double[1];
        double design = trainWorkKJ(SteamSeparation.DESIGN_MOISTURE, wearDesign);
        Check.exactly(dry, design, "design moisture is what the rated work already contains");
        Check.exactly(0.0, wearDesign[0], "no wear at design moisture");
        double previousWork = design;
        double previousWear = 0;
        for (double w : new double[] {0.005, 0.01, 0.02, 0.05}) {
            double[] wear = new double[1];
            double work = trainWorkKJ(w, wear);
            Check.lessThan(previousWork, work, "work at " + w + " moisture");
            Check.greaterThan(previousWear, wear[0], "wear at " + w + " moisture");
            Check.relative(design * PowerTurbine.moistureWorkFactor(w), work, 1e-9,
                    "the work follows 1 - 0.77 (w - 0.1 %)");
            previousWork = work;
            previousWear = wear[0];
        }
        // The loss is the thermodynamic one: 0.77 per unit of moisture over design.
        double one = trainWorkKJ(0.011, null);
        Check.relative(1 - 0.77 * 0.01, one / design, 1e-9, "1 % moisture over design costs 0.77 % of the work");
        // Wear accumulates: a rated hour at the reference's moisture adds a visible fraction, and at 20 high far more.
        double refW = SteamSeparation.moisture(VesselBuild.REFERENCE.separatorRatio(), NORMAL_VESSEL_IN);
        double w20 = SteamSeparation.moisture(H20.separatorRatio(), NORMAL_VESSEL_IN);
        double perHourReference = PowerTurbine.erosionIncrement(1941.0 * 3600, refW);
        double perHour20 = PowerTurbine.erosionIncrement(1941.0 * 3600, w20);
        Check.exactly(0.0, PowerTurbine.erosionIncrement(1941.0 * 3600, SteamSeparation.DESIGN_MOISTURE),
                "a true-scale vessel wears nothing");
        Check.inRange(0.004, 0.012, perHourReference, "blade wear per rated hour at the reference");
        Check.greaterThan(4 * perHourReference, perHour20, "a 20 high vessel wears faster");
        double ticks = 0;
        for (int i = 0; i < 20 * 3600; i++) {
            ticks += PowerTurbine.erosionIncrement(1941.0 / 20, refW);
        }
        Check.relative(perHourReference, ticks, 1e-9, "per-tick wear adds up to the hourly figure");
        Check.note("rated train, 1941 kg/s: dry work %.0f MW; reference moisture %.2f %% costs %.2f %%; wear %.2f %%/h at the reference, %.1f %%/h at 20 high",
                dry * 20 / 1000, 100 * refW, 100 * (1 - PowerTurbine.moistureWorkFactor(refW)),
                100 * perHourReference, 100 * perHour20);
    }

    public static void test06_moistureSurvivesTheInventoryMixAndTake() {
        SteamInventory inv = new SteamInventory(100);
        inv.offer(new SteamInventory.Packet(10, 2770, 1015, 0.02));
        inv.offer(new SteamInventory.Packet(30, 2770, 1015, 0.0));
        Check.relative(0.005, inv.moisture(), 1e-12, "mixed moisture is mass-weighted");
        var taken = inv.take(20);
        Check.relative(0.005, taken.moisture(), 1e-12, "a take carries the mixture's moisture");
        Check.relative(0.005, inv.moisture(), 1e-12, "and leaves it unchanged");
        inv.take(1000);
        Check.exactly(0.0, inv.moisture(), "empty inventory");
    }

    // ---------------------------------------------------------------
    // 4. Natural circulation vs the chimney
    // ---------------------------------------------------------------

    public static void test07_naturalCirculationIsUnchangedAtTheReferenceBuild() {
        ReactorCore bare = settledBareCore();
        ReactorCore reference = ratedCore(VesselBuild.REFERENCE);
        Check.exactly(1.0, reference.getChimneyFactor(), "chimney factor at the reference");
        Check.exactly(1.0, reference.carryunderDriveFactor(), "carryunder head factor at the reference");
        Check.exactly(bare.naturalCirculationFlowFraction(), reference.naturalCirculationFlowFraction(),
                "natural circulation at the reference build, bit for bit");
        // And through a regulated two-pump trip, the whole settled answer.
        bare.setRecirculationFlowFraction(0.0);
        reference.setRecirculationFlowFraction(0.0);
        NaturalCirculationTest.runRegulated(bare, 120, 0.25);
        NaturalCirculationTest.runRegulated(reference, 120, 0.25);
        Check.exactly(bare.getCoreFlowFraction(), reference.getCoreFlowFraction(), "two-pump-trip flow after 2 min");
        Check.exactly(bare.getTotalPowerFractionOfRated(), reference.getTotalPowerFractionOfRated(),
                "two-pump-trip power after 2 min");
    }

    public static void test08_naturalCirculationGrowsWithTheChimneyUpToACap() {
        double previous = 0;
        double[] f = new double[BUILDS.length];
        double raw = settledBareCore().naturalCirculationFlowFraction();
        for (int i = 0; i < BUILDS.length; i++) {
            ReactorCore core = ratedCore(BUILDS[i]);
            f[i] = core.naturalCirculationFlowFraction();
            Check.greaterThan(previous, f[i], "natural circulation at " + HEIGHTS[i] + " high");
            Check.relative(raw * SteamSeparation.chimneyFactor(BUILDS[i].chimneyRatio()) * core.carryunderDriveFactor(),
                    f[i], 1e-12, "the flow is the calibrated flow times the chimney and the carryunder head, " + HEIGHTS[i]);
            previous = f[i];
        }
        double base = f[1];
        Check.exactly(raw, base, "the reference is the calibrated flow");
        Check.lessThan(base * 1.0 / Math.sqrt(1 - SteamSeparation.FIXED_LOOP_RESISTANCE) * 1.06, f[4],
                "the cap: friction along the chimney stops the gain");
        double huge = SteamSeparation.chimneyFactor(1e9);
        Check.relative(1 / Math.sqrt(1 - SteamSeparation.FIXED_LOOP_RESISTANCE), huge, 1e-6, "asymptote");
        Check.exactly(0.0, SteamSeparation.chimneyFactor(0.0), "no chimney, no head");
        Check.relative(Math.sqrt(0.5 / (0.5 + 0.25)), SteamSeparation.chimneyFactor(0.5), 1e-12, "half a chimney");
        Check.note("natural circulation at rated power vs height 20/28/39/43/60: %.1f / %.1f / %.1f / %.1f / %.1f %% of rated flow",
                100 * f[0], 100 * f[1], 100 * f[2], 100 * f[3], 100 * f[4]);
    }

    public static void test09_carryunderThinsTheDowncomerAndTheInletSubcooling() {
        ReactorCore reference = ratedCore(VesselBuild.REFERENCE);
        NaturalCirculationTest.runRegulated(reference, 2, 0.25);
        double referenceSubcooling = reference.getVoidModel().getEquilibriumSubcoolingKJPerKg();
        Check.exactly(0.0, reference.getVoidModel().getSubcoolingAdjustmentKJPerKg(),
                "the reference build takes nothing off the calibrated subcooling");
        ReactorCore bare = settledBareCore();
        NaturalCirculationTest.runRegulated(bare, 2, 0.25);
        Check.exactly(bare.getVoidModel().getEquilibriumSubcoolingKJPerKg(), referenceSubcooling,
                "reference subcooling equals the bare core's");

        ReactorCore squashed = ratedCore(H20);
        NaturalCirculationTest.runRegulated(squashed, 2, 0.25);
        Check.lessThan(referenceSubcooling - 3.0, squashed.getVoidModel().getEquilibriumSubcoolingKJPerKg(),
                "a 20 high vessel arrives at the core with less subcooling");
        ReactorCore tall = ratedCore(H43);
        NaturalCirculationTest.runRegulated(tall, 2, 0.25);
        Check.greaterThan(referenceSubcooling + 1.0, tall.getVoidModel().getEquilibriumSubcoolingKJPerKg(),
                "a true-scale vessel arrives with more");
        // Low level carries steam under: the subcooling goes.
        ReactorCore low = ratedCore(H43);
        low.getPressureVessel().setCollapsedLevelIn(-60);
        NaturalCirculationTest.run(low, 5, 0.25);
        Check.lessThan(tall.getVoidModel().getEquilibriumSubcoolingKJPerKg() - 20.0,
                low.getVoidModel().getEquilibriumSubcoolingKJPerKg(), "a drawn-down vessel loses its subcooling");
        // The downcomer head: carryunder lowers natural circulation.
        Check.lessThan(1.0, squashed.carryunderDriveFactor(), "carryunder takes head off the loop, 20 high");
        Check.greaterThan(1.0, tall.carryunderDriveFactor(), "and less of it adds head, true scale");
        Check.note("equilibrium inlet subcooling at rated power: 20 high %.1f, reference %.1f, true scale %.1f kJ/kg; drawn-down true scale %.1f",
                squashed.getVoidModel().getEquilibriumSubcoolingKJPerKg(), referenceSubcooling,
                tall.getVoidModel().getEquilibriumSubcoolingKJPerKg(), low.getVoidModel().getEquilibriumSubcoolingKJPerKg());
    }

    // ---------------------------------------------------------------
    // 5. Dome, inventory, heat-up
    // ---------------------------------------------------------------

    public static void test10_aSmallDomeSwingsMoreThanALargeOne() {
        double[] swing = new double[3];
        double[] dome = {60, 180, 400};
        double[] capacity = new double[3];
        for (int i = 0; i < 3; i++) {
            CoreConfig cfg = new CoreConfig();
            cfg.steamDomeVolumeM3 = dome[i];
            PressureVessel v = new PressureVessel(cfg);
            v.initialiseToNormalLevel();
            double p0 = v.getPressurePsig();
            // A turbine trip's worth of imbalance: steam made, nowhere to go, for two seconds.
            v.step(3579.0, 0.37, 0.0, 0.0, 0.0, 2.0);
            swing[i] = v.getPressurePsig() - p0;
            capacity[i] = v.getPressureCapacityKgPerPsi();
        }
        Check.greaterThan(swing[1], swing[0], "60 m3 dome swings more than 180 m3");
        Check.greaterThan(swing[2], swing[1], "180 m3 dome swings more than 400 m3");
        Check.relative(capacity[1] / capacity[0], swing[0] / swing[1], 0.03, "swing goes as the inverse of the pressure capacity");
        // The water, not the dome, is most of the capacity: document by measurement.
        double domeShare = 180 * Saturation.vapourDensitySlopeKgPerM3PerPsi(Saturation.psiaFromPsig(1025)) / capacity[1];
        Check.inRange(0.08, 0.2, domeShare, "the dome's share of the pressure capacity at rated");
        Check.note("2 s of full-power steam with no outlet: dome 60 / 180 / 400 m3 swings %.1f / %.1f / %.1f psi (dome is %.0f %% of the capacity at 180)",
                swing[0], swing[1], swing[2], 100 * domeShare);
    }

    public static void test11_theBuildScalesTheDomeAndTheInventoryAtTheSameLevel() {
        ReactorCore reference = NaturalCirculationTest.ratedCore();
        double dome0 = reference.getPressureVessel().getSteamDomeVolumeM3();
        double mass0 = reference.getLiquidMassKg();
        double level0 = reference.getCollapsedLevelIn();
        double indicated0 = reference.getIndicatedLevelIn();
        reference.applyVesselBuild(H43);
        Check.relative(dome0 * H43.domeRatio(), reference.getPressureVessel().getSteamDomeVolumeM3(), 1e-12, "dome");
        Check.relative(mass0 * H43.waterMassFactor(), reference.getLiquidMassKg(), 1e-12, "water");
        Check.relative(level0, reference.getCollapsedLevelIn(), 1e-9, "collapsed level does not move");
        Check.relative(indicated0, reference.getIndicatedLevelIn(), 1e-9, "indicated level does not move");
        reference.applyVesselBuild(null);
        Check.relative(dome0, reference.getPressureVessel().getSteamDomeVolumeM3(), 1e-12, "dome back");
        Check.relative(mass0, reference.getLiquidMassKg(), 1e-9, "water back");
        Check.exactly(1.0, reference.getPressureVessel().getInventoryScale(), "inventory scale back");
        // A reference build changes nothing at all.
        ReactorCore same = NaturalCirculationTest.ratedCore();
        same.applyVesselBuild(VesselBuild.REFERENCE);
        Check.exactly(mass0, same.getLiquidMassKg(), "reference water");
        Check.exactly(dome0, same.getPressureVessel().getSteamDomeVolumeM3(), "reference dome");
        Check.inRange(1.7, 1.9, H60.waterMassFactor(), "a 60 high vessel holds well over a third more water");
        Check.inRange(0.8, 0.95, H20.waterMassFactor(), "and a 20 high one less");
        Check.exactly(1.0, H28.waterMassFactor(), "reference water factor");
        Check.exactly(1.0, H28.domeRatio(), "reference dome factor");
        Check.exactly(1.0, H28.chimneyRatio(), "reference chimney factor");
        Check.exactly(H43.domeRatio(), H60.domeRatio(), "above true scale the extra height is water column, not steam space");
    }

    /** Minutes for 100 MW into a cold vessel (no steam out) to reach 1000 psig. */
    static double heatUpMinutes(VesselBuild build) {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.applyVesselBuild(build);
        PressureVessel v = core.getPressureVessel();
        v.initialiseCold();
        double seconds = 0;
        while (v.getPressurePsig() < 1000.0 && seconds < 20000) {
            v.step(100.0, 0.0, 0.0, 0.0, 0.0, 1.0);
            seconds += 1.0;
        }
        return seconds / 60.0;
    }

    public static void test12_heatUpTimeScalesWithTheWaterMass() {
        double reference = heatUpMinutes(VesselBuild.REFERENCE);
        Check.inRange(35, 50, reference, "cold to 1000 psig at 100 MW, reference, minutes");
        double tall = heatUpMinutes(H60);
        double short20 = heatUpMinutes(H20);
        Check.relative(H60.waterMassFactor(), tall / reference, 0.04, "heat-up time follows the water mass, 60 high");
        Check.relative(H20.waterMassFactor(), short20 / reference, 0.04, "heat-up time follows the water mass, 20 high");
        Check.note("cold to 1000 psig at 100 MW: 20 high %.1f min, reference %.1f, 60 high %.1f (water %.2f / 1 / %.2f)",
                short20, reference, tall, H20.waterMassFactor(), H60.waterMassFactor());
    }

    /** Water between the normal level and the top of active fuel, tonnes. */
    static double inventoryToUncoverTonnes(VesselBuild build) {
        ReactorCore core = NaturalCirculationTest.ratedCore();
        core.applyVesselBuild(build);
        PressureVessel v = core.getPressureVessel();
        double normal = v.getLiquidMassKg();
        v.setCollapsedLevelIn(PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN);
        double atTaf = v.getLiquidMassKg();
        return (normal - atTaf) / 1000.0;
    }

    public static void test13_aTallerVesselHoldsMoreWaterAboveTheCore() {
        double[] t = new double[BUILDS.length];
        for (int i = 0; i < BUILDS.length; i++) {
            t[i] = inventoryToUncoverTonnes(BUILDS[i]);
            if (i > 0) {
                Check.greaterThan(t[i - 1], t[i], "water to lose before uncovery, " + HEIGHTS[i] + " high");
            }
        }
        Check.relative(H60.waterMassFactor(), t[4] / t[1], 1e-6, "inventory above the fuel scales with the water factor");
        Check.note("water between the normal level and the top of the fuel (tonnes) for heights 20/28/39/43/60: %.0f / %.0f / %.0f / %.0f / %.0f",
                t[0], t[1], t[2], t[3], t[4]);
    }

    public static void test14_stackedEnergyFollowsTheInventory() {
        // Stored energy above 100 degC in the water: what a blowdown, a containment pressurisation or a head missile draws on.
        double[] e = new double[2];
        VesselBuild[] pair = {H28, H60};
        for (int i = 0; i < 2; i++) {
            ReactorCore core = NaturalCirculationTest.ratedCore();
            core.applyVesselBuild(pair[i]);
            PressureVessel v = core.getPressureVessel();
            e[i] = v.getLiquidMassKg() * (Saturation.liquidEnthalpyKJPerKg(v.getPressurePsia())
                    - Saturation.liquidEnthalpyKJPerKg(Saturation.psiaFromPsig(0))) / 1e6;
            Check.relative(core.getPressureVessel().getOccupiedVolumeM3(),
                    v.getSteamDomeVolumeM3() + v.getLiquidMassKg() / Saturation.liquidDensityKgPerM3(v.getPressurePsia()),
                    1e-12, "the volume a rupture blows down is dome plus water");
        }
        Check.relative(H60.waterMassFactor(), e[1] / e[0], 1e-6, "stored sensible energy follows the water");
        Check.note("sensible energy of the water above atmospheric: reference %.0f GJ, 60 high %.0f GJ", e[0], e[1]);
    }

    public static void test15_theCoreInletSeesAHydrostaticRise() {
        ReactorCore reference = ratedCore(H28);
        ReactorCore tall = ratedCore(H60);
        double psi = reference.getCoreInletHydrostaticRisePsi();
        Check.inRange(5.0, 9.0, psi, "pressure the water adds at the foot of the fuel, reference, psi");
        Check.inRange(0.3, 0.7, reference.getCoreInletSaturationRiseK(), "saturation rise at the foot of the core, K");
        Check.greaterThan(2.0 * psi, tall.getCoreInletHydrostaticRisePsi(), "a 60 high vessel stands a taller column over the core");
        Check.note("hydrostatic rise at the foot of the fuel: reference %.1f psi (%.2f K), 60 high %.1f psi (%.2f K)",
                psi, reference.getCoreInletSaturationRiseK(), tall.getCoreInletHydrostaticRisePsi(),
                tall.getCoreInletSaturationRiseK());
    }

    // ---------------------------------------------------------------
    // The table in VESSEL-HEIGHT.md
    // ---------------------------------------------------------------

    public static void test16_heightTable() {
        Check.note("height | separator scale | moisture %% | carryunder %% | NC flow after two-pump trip %% | tonnes to uncover | heat-up min | rated MWe");
        for (int i = 0; i < BUILDS.length; i++) {
            VesselBuild b = BUILDS[i];
            double w = SteamSeparation.moisture(b.separatorRatio(), NORMAL_VESSEL_IN);
            double cu = SteamSeparation.carryunder(b.separatorRatio(), NORMAL_VESSEL_IN);
            ReactorCore core = ratedCore(b);
            core.setRecirculationFlowFraction(0.0);
            NaturalCirculationTest.runRegulated(core, 240, 0.25);
            double nc = core.getCoreFlowFraction();
            double mwe = trainWorkKJ(w, null) * 20 / 1000 * PowerTurbine.GENERATOR_EFFICIENCY;
            Check.note("TABLE %d | %.3f | %.2f | %.2f | %.1f | %.0f | %.1f | %.0f",
                    HEIGHTS[i], b.separatorRatio(), 100 * w, 100 * cu, 100 * nc, inventoryToUncoverTonnes(b),
                    heatUpMinutes(b), mwe);
            Check.inRange(0.05, 0.6, nc, "two-pump-trip flow at " + HEIGHTS[i] + " high");
        }
    }
}
