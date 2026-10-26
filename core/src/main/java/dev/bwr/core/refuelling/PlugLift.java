package dev.bwr.core.refuelling;

/**
 * The arithmetic of lifting a reactor well shield plug section with the reactor building crane (BUILDING-CRANE.md). Pure Java.
 *
 * <h2>Mass</h2>
 * A plug block is one cubic metre of heavy concrete: mass = blocks x density x 1 m3, and a section weighs the sum of its blocks
 * (and its anchors). The masses are <b>data</b> in the mod ({@code bwr:plug_mass}, a block data map a datapack can tune); these are
 * the defaults it ships and what a plug block with no entry weighs. Ordinary reinforced concrete is about 2.4 t/m3; heavyweight shielding
 * concrete (barite, magnetite, steel punchings) runs about 3.5 to 4.0 t/m3 (IAEA and ACI 304 figures). Tier 1 is ordinary heavily rebarred
 * concrete (2.4 t), tier 2 a heavyweight aggregate mix (3.2 t) and tier 3 the netherite-loaded top tier (4.0 t): the tier 3 figure is a
 * game value, the top of the heavyweight range. An anchor's steel is 0.05 t.
 *
 * <h2>Rating</h2>
 * Reactor building polar cranes are rated about 125 t for the main hook; the game's crane is rated {@value #RATED_CAPACITY_T} t.
 * A section over that is refused, and the refusal tells the player to cut the plug with a seam.
 */
public final class PlugLift {
    private PlugLift() {
    }

    /** The crane's rated load for a slung section, tonnes. */
    public static final double RATED_CAPACITY_T = 125.0;
    /** A section is lifted on a four-point rig: exactly this many anchors rig it. */
    public static final int ANCHORS_REQUIRED = 4;
    /** Default mass of one block (1 m3) of the three shield plug concrete tiers, t; the mod's data map {@code bwr:plug_mass} overrides these. */
    public static final double TONNES_TIER_1 = 2.4, TONNES_TIER_2 = 3.2, TONNES_TIER_3 = 4.0;
    /** Default mass of one crane anchor, t. */
    public static final double ANCHOR_TONNES = 0.05;

    /** Default mass of one block of a tier (1..3), t. */
    public static double defaultTonnes(int tier) {
        return switch (tier) {
            case 2 -> TONNES_TIER_2;
            case 3 -> TONNES_TIER_3;
            default -> TONNES_TIER_1;
        };
    }

    /** Mass of a section of the given numbers of tier 1, 2 and 3 blocks at the default masses, tonnes. */
    public static double massTonnes(int tier1, int tier2, int tier3) {
        return Math.max(0, tier1) * TONNES_TIER_1 + Math.max(0, tier2) * TONNES_TIER_2 + Math.max(0, tier3) * TONNES_TIER_3;
    }

    /** True when a load of this mass is over the crane's rating. */
    public static boolean overCapacity(double tonnes) {
        return tonnes > RATED_CAPACITY_T + 1e-9;
    }

    /** The most blocks of mass {@code tonnesPerBlock} a section may have under the rating. */
    public static int maxBlocks(double tonnesPerBlock) {
        return tonnesPerBlock <= 0 ? Integer.MAX_VALUE : (int) Math.floor(RATED_CAPACITY_T / tonnesPerBlock + 1e-9);
    }

    /** Whether a count of anchors is the four-point rig. */
    public static boolean rigged(int anchors) {
        return anchors == ANCHORS_REQUIRED;
    }
}
