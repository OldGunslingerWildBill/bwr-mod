package dev.bwr.core.refuelling;

import dev.bwr.core.Check;

/** The mass of a shield plug section and the crane's rating. */
public final class PlugLiftTest {

    private PlugLiftTest() {
    }

    public static void test01_aSectionWeighsItsBlocksTimesTheirDensity() {
        Check.relative(24.0, PlugLift.massTonnes(10, 0, 0), 1e-9, "ten tier 1 blocks are 24 t");
        Check.relative(29.0, PlugLift.massTonnes(0, 10, 0), 1e-9, "ten tier 2 blocks are 29 t");
        Check.relative(35.0, PlugLift.massTonnes(0, 0, 10), 1e-9, "ten tier 3 blocks are 35 t");
        Check.relative(24.0 + 29.0 + 35.0, PlugLift.massTonnes(10, 10, 10), 1e-9, "tiers add");
        Check.exactly(0.0, PlugLift.massTonnes(-3, 0, 0), "no negative blocks");
        Check.isTrue(PlugLift.DENSITY_TIER_1 < PlugLift.DENSITY_TIER_2 && PlugLift.DENSITY_TIER_2 < PlugLift.DENSITY_TIER_3, "the stronger the tier the heavier");
        Check.isTrue(PlugLift.DENSITY_TIER_1 >= 2.4 && PlugLift.DENSITY_TIER_3 <= 3.5, "between 2.4 and 3.5 t/m3");
    }

    public static void test02_theCraneRefusesWhatIsOverItsRating() {
        Check.exactly(52, PlugLift.maxBlocks(1), "52 tier 1 blocks are 124.8 t");
        Check.exactly(43, PlugLift.maxBlocks(2), "43 tier 2 blocks are 124.7 t");
        Check.exactly(35, PlugLift.maxBlocks(3), "35 tier 3 blocks are 122.5 t");
        Check.isFalse(PlugLift.overCapacity(PlugLift.massTonnes(52, 0, 0)), "52 tier 1 blocks lift");
        Check.isTrue(PlugLift.overCapacity(PlugLift.massTonnes(53, 0, 0)), "53 do not: 127.2 t");
        Check.isFalse(PlugLift.overCapacity(PlugLift.RATED_CAPACITY_T), "exactly the rating lifts");
        Check.isTrue(PlugLift.overCapacity(PlugLift.RATED_CAPACITY_T + 0.1), "a hundred kilograms over does not");
    }

    public static void test03_aSectionIsRiggedOnExactlyFourAnchors() {
        Check.isTrue(PlugLift.rigged(4), "four anchors rig it");
        Check.isFalse(PlugLift.rigged(3), "three do not");
        Check.isFalse(PlugLift.rigged(0), "none do not");
        Check.isFalse(PlugLift.rigged(5), "a fifth is more than the rig takes");
    }
}
