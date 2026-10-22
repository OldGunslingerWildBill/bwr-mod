package dev.bwr.core.accident;

import dev.bwr.core.Check;

/**
 * Energy-to-reach numbers behind the physical accident blasts. The same chain the world adapter runs, checked
 * without a world: burned hydrogen to TNT equivalent to overpressure to the blocks that give way.
 */
public final class BlastPhysicsTest {

    private BlastPhysicsTest() {
    }

    /** Rating of one block of the given blast resistance with the default wall calibration, psi. */
    private static double rating(double resistance) {
        return ContainmentWallStrength.psi(resistance);
    }

    public static void test01_kinneyGrahamFollowsHopkinsonScaling() {
        // Same scaled distance, any charge: same pressure.
        double a = BlastPhysics.overpressurePsi(1.0, 3.0);
        double b = BlastPhysics.overpressurePsi(1000.0, 30.0);
        Check.relative(a, b, 1.0e-9, "overpressure at equal scaled distance");
        // Known anchors of the curve, to the factor of two the fit is good for.
        Check.inRange(8.0, 40.0, BlastPhysics.overpressurePsi(1000.0, 20.0), "1 t TNT at 20 m, psi");
        Check.inRange(0.5, 3.0, BlastPhysics.overpressurePsi(1000.0, 100.0), "1 t TNT at 100 m, psi");
        // Monotone falling with range, rising with charge.
        double previous = Double.MAX_VALUE;
        for (double r = 1.0; r <= 80.0; r += 1.0) {
            double p = BlastPhysics.overpressurePsi(500.0, r);
            Check.isTrue(p < previous, "pressure must fall with range at %.0f m", r);
            previous = p;
        }
        Check.greaterThan(BlastPhysics.overpressurePsi(100.0, 10.0), BlastPhysics.overpressurePsi(400.0, 10.0),
                "more charge, more pressure");
        Check.exactly(0.0, BlastPhysics.overpressurePsi(0.0, 5.0), "no charge, no pressure");
        Check.finite(BlastPhysics.overpressurePsi(Double.NaN, Double.NaN), "NaN inputs stay finite");
    }

    public static void test02_rangeIsTheInverseOfPressure() {
        for (double w : new double[] {10.0, 100.0, 1000.0, 5000.0}) {
            for (double psi : new double[] {1.0, 3.0, 10.0, 50.0}) {
                double r = BlastPhysics.rangeForPsi(w, psi);
                if (r > 0.0) {
                    Check.relative(psi, BlastPhysics.overpressurePsi(w, r), 1.0e-6, "round trip " + w + " kg " + psi + " psi");
                }
            }
        }
        Check.exactly(0.0, BlastPhysics.rangeForPsi(0.0, 5.0), "no charge has no range");
        Check.isTrue(BlastPhysics.reachBlocks(1.0e9) <= BlastPhysics.MAX_REACH_BLOCKS, "reach is capped");
    }

    public static void test03_hydrogenMassScalesTheBlast() {
        // 52 kg is the 8% burn of an 8,000 m3 containment.
        double h2 = 52.0;
        double deflagration = BlastPhysics.deflagrationTntKg(h2);
        double detonation = BlastPhysics.detonationTntKg(h2);
        // 52 kg * 120 MJ/kg = 6.24 GJ; x0.10 = 624 MJ = 149 kg TNT; x0.50 = 3.12 GJ = 746 kg TNT.
        Check.relative(149.1, deflagration, 0.01, "deflagration TNT equivalent of 52 kg H2");
        Check.relative(745.7, detonation, 0.01, "detonation TNT equivalent of 52 kg H2");
        Check.relative(2.0 * deflagration, BlastPhysics.deflagrationTntKg(2.0 * h2), 1.0e-9, "linear in mass");
        Check.relative(5.0, detonation / deflagration, 1.0e-9, "detonation yield is five times the deflagration yield");
        double a = BlastPhysics.rangeForPsi(BlastPhysics.deflagrationTntKg(2.0), 3.2);
        double b = BlastPhysics.rangeForPsi(BlastPhysics.deflagrationTntKg(20.0), 3.2);
        Check.greaterThan(a, b, "more hydrogen reaches further");
        Check.note("hydrogen to blast: 52 kg H2 = %.0f kg TNT burning, %.0f kg TNT detonating; reach to 1 psi %.0f and %.0f blocks",
                deflagration, detonation, BlastPhysics.reachBlocks(deflagration), BlastPhysics.reachBlocks(detonation));
    }

    public static void test04_deflagrationBreaksGlassAndWoodButNotStoneOrConcrete() {
        // The 8% burn: AICC peak for 8% hydrogen is ratio 1 + 8.5 * 0.08 = 1.68 of 14.7 psia, about 10 psig.
        double aicc = (1.0 + ContainmentAtmosphere.AICC_PRESSURE_SLOPE * 0.08) * BlastPhysics.ATMOSPHERE_PSI
                - BlastPhysics.ATMOSPHERE_PSI;
        Check.inRange(9.5, 10.5, aicc, "AICC peak of an 8% burn, psig");
        double w = BlastPhysics.deflagrationTntKg(52.0);
        double glass = rating(0.3), plank = rating(3.0), concrete = rating(1.8), stone = rating(6.0),
                deepslate = rating(6.0), steel = rating(1200.0);
        // Close to the ignition point the room is at its AICC peak.
        double near = BlastPhysics.deflagrationPsi(w, aicc, 3.0);
        Check.relative(aicc, near, 1.0e-9, "near the flame the load is the room's AICC peak");
        Check.isTrue(near > glass, "glass (%.1f psi) must break at %.1f psi", glass, near);
        Check.isTrue(near > BlastPhysics.effectiveRating(rating(0.0)), "torches, carpets and signs go");
        Check.isTrue(near < stone && near < deepslate, "stone (%.1f psi) must hold at %.1f psi", stone, near);
        Check.isTrue(near < steel, "vessel steel holds");
        // The burst concrete of this game is only 1.8 resistance: 7.8 psi. It fails at 10 psig, which is why the
        // adapter reads the rating from the survey table rather than assuming "concrete" is strong.
        Check.inRange(7.0, 8.5, concrete, "vanilla concrete rating, psi");
        Check.inRange(near - 1.0, near + 1.0, plank, "an oak plank is at the margin of the burn pressure");
        // Reach: glass breaks further out than the room is wide, stone never does.
        double glassReach = BlastPhysics.rangeForPsi(w, glass);
        Check.greaterThan(10.0, glassReach, "glass breaks at least 10 blocks from the flame");
        Check.isTrue(BlastPhysics.deflagrationPsi(w, aicc, 1.0) < stone, "stone holds at any range");
        Check.note("deflagration of 52 kg H2: load %.1f psig; glass (%.1f psi) breaks out to %.0f blocks, planks (%.1f psi) to %.0f, "
                        + "stone (%.1f psi) never", near, glass, glassReach, plank, BlastPhysics.rangeForPsi(w, plank), stone);
    }

    public static void test05_detonationBreaksMoreAndScalesWithMass() {
        double aicc = 10.0;
        double peak = BlastPhysics.detonationPeakPsig(aicc);
        // 2 x 24.7 psia - 14.7 = 34.7 psig.
        Check.relative(34.7, peak, 0.01, "detonation wall load, psig");
        double w = BlastPhysics.detonationTntKg(52.0);
        double stone = rating(6.0), machine = rating(60.0), steel = rating(1200.0);
        Check.isTrue(BlastPhysics.detonationPsi(w, aicc, 4.0) > stone, "stone breaks inside a detonation");
        Check.isTrue(BlastPhysics.detonationPsi(w, aicc, 4.0) < machine, "machinery (%.1f psi) survives the room load", machine);
        Check.isTrue(BlastPhysics.detonationPsi(w, aicc, 4.0) < steel, "vessel steel holds");
        Check.greaterThan(BlastPhysics.rangeForPsi(BlastPhysics.deflagrationTntKg(52.0), stone),
                BlastPhysics.rangeForPsi(w, stone), "a detonation reaches stone, a deflagration does not");
    }

    public static void test06_breachRadiusFollowsExcessPressureAndEnergy() {
        double rating = 25.0;
        double w = BlastPhysics.detonationTntKg(85.0);
        // No excess, no hole beyond the single column.
        Check.exactly(0.0, BlastPhysics.breachRadiusBlocks(w, 25.0, rating), "load equal to rating opens no extra radius");
        Check.exactly(0.0, BlastPhysics.breachRadiusBlocks(w, 10.0, rating), "load below rating opens no extra radius");
        double small = BlastPhysics.breachRadiusBlocks(w, 40.0, rating);
        double large = BlastPhysics.breachRadiusBlocks(w, 100.0, rating);
        Check.greaterThan(small, large, "more excess pressure, bigger hole");
        Check.greaterThan(BlastPhysics.breachRadiusBlocks(w / 8.0, 100.0, rating),
                BlastPhysics.breachRadiusBlocks(w, 100.0, rating), "more energy, bigger hole");
        Check.isTrue(BlastPhysics.breachRadiusBlocks(1.0e9, 1.0e9, rating) <= BlastPhysics.MAX_BREACH_RADIUS_BLOCKS,
                "hole radius is capped");
        // Round trip with the load profile: at the hole's edge the load equals the rating.
        double edge = BlastPhysics.breachRadiusBlocks(w, 40.0, rating);
        Check.relative(rating, BlastPhysics.breachLoadAt(40.0, BlastPhysics.breachScaleBlocks(w), edge), 1.0e-9,
                "load at the edge of the hole is the rating");
        // A sudden failure at exactly the rating still opens a small hole.
        double sudden = BlastPhysics.breachLoadPsi(rating, rating);
        Check.relative(31.25, sudden, 1.0e-9, "sudden failure load factor");
        Check.greaterThan(0.0, BlastPhysics.breachRadiusBlocks(BlastPhysics.containmentBurstTntKg(rating, 8000.0), sudden, rating),
                "an overpressure failure opens more than one column");
        Check.note("breach: 85 kg H2 detonation = %.0f kg TNT, scale %.1f blocks; vs a 25 psi wall: 40 psi opens r=%.1f, 100 psi opens r=%.1f; "
                        + "overpressure burst at 25 psig in 8,000 m3 = %.0f kg TNT, r=%.1f",
                w, BlastPhysics.breachScaleBlocks(w), small, large, BlastPhysics.containmentBurstTntKg(rating, 8000.0),
                BlastPhysics.breachRadiusBlocks(BlastPhysics.containmentBurstTntKg(rating, 8000.0), sudden, rating));
    }

    public static void test07_headFailureEnergyAndCone() {
        double dome = 180.0;
        double flashKg = 0.30 * dev.bwr.core.boundary.BoundaryStress.RATED_CORE_FLOW_KG_PER_S;
        double e1400 = BlastPhysics.headFailureEnergyJ(1400.0, dome, flashKg);
        double e1025 = BlastPhysics.headFailureEnergyJ(1025.0, dome, flashKg);
        Check.inRange(2.0e9, 1.2e10, e1400, "stored energy at head failure pressure, J");
        Check.greaterThan(e1025, e1400, "higher pressure stores more energy");
        double w = BlastPhysics.headFailureTntKg(1400.0, dome, flashKg);
        double stone = rating(6.0), plank = rating(3.0), concrete = rating(1.8), steel = rating(1200.0);
        double stoneReach = BlastPhysics.rangeForPsi(w, stone);
        double concreteReach = BlastPhysics.rangeForPsi(w, concrete);
        Check.greaterThan(10.0, stoneReach, "the burst breaks stone above the vessel for at least 10 blocks");
        Check.greaterThan(stoneReach, concreteReach, "weaker concrete goes further out than stone");
        Check.isTrue(BlastPhysics.overpressurePsi(w, 3.0) > steel * 0.5, "close to the head even heavy steel is loaded");
        Check.isTrue(BlastPhysics.reachBlocks(w) <= BlastPhysics.MAX_REACH_BLOCKS, "sweep is bounded");
        Check.note("head failure at 1,400 psig, %.0f m3 dome: %.2f GJ stored, %.0f kg TNT blast; stone (%.1f psi) breaks to %.0f blocks, "
                        + "concrete (%.1f psi) to %.0f, planks (%.1f psi) to %.0f, glass to %.0f",
                dome, e1400 / 1.0e9, w, stone, stoneReach, concrete, concreteReach, plank, BlastPhysics.rangeForPsi(w, plank),
                BlastPhysics.rangeForPsi(w, rating(0.3)));
    }

    public static void test08_pipeWhipIsBoundedToTwoBlocks() {
        Check.exactly(1.0, BlastPhysics.pipeWhipRadiusBlocks(100.0), "low pressure whip radius");
        Check.exactly(2.0, BlastPhysics.pipeWhipRadiusBlocks(1300.0), "full pressure whip radius");
        Check.exactly(2.0, BlastPhysics.pipeWhipRadiusBlocks(1.0e9), "radius never exceeds two blocks");
        double mid = BlastPhysics.pipeWhipRadiusBlocks(800.0);
        Check.inRange(1.4, 1.6, mid, "radius scales with pressure");
        // At 1,000 psig the jet breaks stone at 2 blocks but not vessel steel.
        Check.isTrue(BlastPhysics.jetPsi(1000.0, 2.0) > rating(6.0), "jet breaks stone at two blocks");
        Check.isTrue(BlastPhysics.jetPsi(1000.0, 2.0) < rating(1200.0), "jet does not break steel at two blocks");
        Check.isTrue(BlastPhysics.jetPsi(1000.0, 1.0) > BlastPhysics.jetPsi(1000.0, 2.0), "jet falls with distance");
        Check.note("pipe whip: %.2f blocks at 800 psig, %.2f at 1,300; jet at 1,000 psig is %.0f psi at 1 block, %.0f at 2",
                mid, BlastPhysics.pipeWhipRadiusBlocks(1300.0), BlastPhysics.jetPsi(1000.0, 1.0), BlastPhysics.jetPsi(1000.0, 2.0));
    }

    public static void test09_lowerHeadFailureBlastScalesWithDebris() {
        double small = BlastPhysics.lowerHeadTntKg(0.1, 1.0);
        double large = BlastPhysics.lowerHeadTntKg(0.6, 1.0);
        Check.relative(6.0, large / small, 1.0e-9, "linear in the debris fraction");
        Check.exactly(0.0, BlastPhysics.lowerHeadTntKg(0.0, 1.0), "no debris, no blast");
        Check.isTrue(BlastPhysics.lowerHeadTntKg(5.0, 1.0) == BlastPhysics.lowerHeadTntKg(1.0, 1.0), "fraction is clamped");
        double w = BlastPhysics.lowerHeadTntKg(0.3, 1.0);
        Check.note("lower head failure with 30%% of the core as debris: %.0f kg TNT, stone breaks to %.1f blocks",
                w, BlastPhysics.rangeForPsi(w, rating(6.0)));
    }
}
