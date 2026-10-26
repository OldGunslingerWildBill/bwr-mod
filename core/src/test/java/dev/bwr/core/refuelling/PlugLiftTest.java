package dev.bwr.core.refuelling;

import dev.bwr.core.Check;

/** The mass of a shield plug section and the crane's rating. */
public final class PlugLiftTest {

    private PlugLiftTest() {
    }

    public static void test01_aSectionWeighsItsBlocksTimesTheirMass() {
        Check.relative(24.0, PlugLift.massTonnes(10, 0, 0), 1e-9, "ten tier 1 blocks are 24 t");
        Check.relative(32.0, PlugLift.massTonnes(0, 10, 0), 1e-9, "ten tier 2 blocks are 32 t");
        Check.relative(40.0, PlugLift.massTonnes(0, 0, 10), 1e-9, "ten tier 3 blocks are 40 t");
        Check.relative(24.0 + 32.0 + 40.0, PlugLift.massTonnes(10, 10, 10), 1e-9, "tiers add");
        Check.exactly(0.0, PlugLift.massTonnes(-3, 0, 0), "no negative blocks");
        Check.isTrue(PlugLift.TONNES_TIER_1 < PlugLift.TONNES_TIER_2 && PlugLift.TONNES_TIER_2 < PlugLift.TONNES_TIER_3, "the stronger the tier the heavier");
        Check.isTrue(PlugLift.TONNES_TIER_1 >= 2.4 && PlugLift.TONNES_TIER_3 <= 4.0, "from ordinary concrete to the top of the heavyweight range");
        Check.exactly(PlugLift.TONNES_TIER_2, PlugLift.defaultTonnes(2), "the tier's default");
    }

    public static void test02_theCraneRefusesWhatIsOverItsRating() {
        Check.exactly(52, PlugLift.maxBlocks(PlugLift.TONNES_TIER_1), "52 tier 1 blocks are 124.8 t");
        Check.exactly(39, PlugLift.maxBlocks(PlugLift.TONNES_TIER_2), "39 tier 2 blocks are 124.8 t");
        Check.exactly(31, PlugLift.maxBlocks(PlugLift.TONNES_TIER_3), "31 tier 3 blocks are 124 t");
        Check.isFalse(PlugLift.overCapacity(PlugLift.massTonnes(52, 0, 0)), "52 tier 1 blocks lift");
        Check.isTrue(PlugLift.overCapacity(PlugLift.massTonnes(53, 0, 0)), "53 do not: 127.2 t");
        Check.isFalse(PlugLift.overCapacity(PlugLift.massTonnes(0, 0, 31)), "31 tier 3 blocks lift");
        Check.isTrue(PlugLift.overCapacity(PlugLift.massTonnes(0, 0, 32)), "32 do not: 128 t");
        Check.isFalse(PlugLift.overCapacity(PlugLift.RATED_CAPACITY_T), "exactly the rating lifts");
        Check.isTrue(PlugLift.overCapacity(PlugLift.RATED_CAPACITY_T + 0.1), "a hundred kilograms over does not");
        Check.exactly(Integer.MAX_VALUE, PlugLift.maxBlocks(0.0), "a weightless block has no limit");
    }

    public static void test03_aSectionIsRiggedOnExactlyFourAnchors() {
        Check.isTrue(PlugLift.rigged(4), "four anchors rig it");
        Check.isFalse(PlugLift.rigged(3), "three do not");
        Check.isFalse(PlugLift.rigged(0), "none do not");
        Check.isFalse(PlugLift.rigged(5), "a fifth is more than the rig takes");
    }
}
