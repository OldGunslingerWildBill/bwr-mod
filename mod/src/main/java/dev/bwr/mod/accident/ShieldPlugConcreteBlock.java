package dev.bwr.mod.accident;

import net.minecraft.world.level.block.Block;

/**
 * Heavy concrete for shield plugs and head-catching slabs, in three tiers that are each stronger than Immersive
 * Engineering's reinforced concrete (blast resistance about 1,200, the same as obsidian; hardness about 1.0, both the
 * owner's figures). The blocks have no behaviour of their own: they stop a thrown vessel head the way every block does,
 * through their blast resistance and thickness (see {@link HeadMissileLauncher}), only with fewer blocks.
 *
 * <p>Ratings are {@code 5.8 * sqrt(blast resistance)} psi, as for every block. The containment wall rule caps one block at
 * 250 psi, which would flatten the two upper tiers, so the ratings are carried uncapped by the mod's own data pack
 * override ({@code data/bwr/bwr_containment/strength.json}).
 */
public final class ShieldPlugConcreteBlock extends Block {
    /** Blast resistance of the three tiers. */
    public static final float RESISTANCE_1 = 1800.0f, RESISTANCE_2 = 3600.0f, RESISTANCE_3 = 7200.0f;
    /** Hardness (mining time) of the three tiers; Immersive Engineering's reinforced concrete is about 1.0. */
    public static final float HARDNESS_1 = 4.0f, HARDNESS_2 = 8.0f, HARDNESS_3 = 16.0f;

    public ShieldPlugConcreteBlock(Properties properties) {
        super(properties);
    }
}
