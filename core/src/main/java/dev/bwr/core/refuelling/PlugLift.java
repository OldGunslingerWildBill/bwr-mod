package dev.bwr.core.refuelling;

/**
 * The arithmetic of lifting a reactor well shield plug section with the reactor building crane (BUILDING-CRANE.md). Pure Java.
 *
 * <h2>Mass</h2>
 * A plug block is one cubic metre of heavy concrete: mass = blocks x density x 1 m3. The three tiers of the mod's shield plug
 * concrete get the densities of increasingly heavy mixes: ordinary heavy concrete with steel rebar (2.4 t/m3), concrete with
 * steel punchings and shot (2.9 t/m3) and the netherite-loaded ultra tier (3.5 t/m3, about the density of barytes or magnetite
 * heavy concrete). The anchors' steel (a few kilograms each) is not counted.
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
    /** Density of the three shield plug concrete tiers, t/m3. */
    public static final double DENSITY_TIER_1 = 2.4, DENSITY_TIER_2 = 2.9, DENSITY_TIER_3 = 3.5;

    /** Density of a tier (1..3), t/m3. */
    public static double density(int tier) {
        return switch (tier) {
            case 2 -> DENSITY_TIER_2;
            case 3 -> DENSITY_TIER_3;
            default -> DENSITY_TIER_1;
        };
    }

    /** Mass of a section of the given numbers of tier 1, 2 and 3 blocks, tonnes. */
    public static double massTonnes(int tier1, int tier2, int tier3) {
        return Math.max(0, tier1) * DENSITY_TIER_1 + Math.max(0, tier2) * DENSITY_TIER_2 + Math.max(0, tier3) * DENSITY_TIER_3;
    }

    /** True when a load of this mass is over the crane's rating. */
    public static boolean overCapacity(double tonnes) {
        return tonnes > RATED_CAPACITY_T + 1e-9;
    }

    /** The most blocks of one tier a section may have under the rating. */
    public static int maxBlocks(int tier) {
        return (int) Math.floor(RATED_CAPACITY_T / density(tier) + 1e-9);
    }

    /** Whether a count of anchors is the four-point rig. */
    public static boolean rigged(int anchors) {
        return anchors == ANCHORS_REQUIRED;
    }
}
