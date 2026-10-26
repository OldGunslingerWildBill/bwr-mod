package dev.bwr.mod.accident;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Heavy concrete for shield plugs and head-catching slabs, in three tiers that are each stronger than Immersive
 * Engineering's reinforced concrete (blast resistance about 1,200, the same as obsidian; hardness about 1.0, both the
 * owner's figures). The blocks have no behaviour of their own: they stop a thrown vessel head the way every block does,
 * through their blast resistance and thickness (see {@link HeadMissileLauncher}), only with fewer blocks.
 *
 * <p>Ratings are {@code 5.8 * sqrt(blast resistance)} psi, as for every block. The containment wall rule caps one block at
 * 250 psi, which would flatten the two upper tiers, so the ratings are carried uncapped by the mod's own data pack
 * override ({@code data/bwr/bwr_containment/strength.json}).
 *
 * <h2>Seams</h2>
 * A plug is cut into separately liftable sections by <b>seams</b> (BUILDING-CRANE.md): a seam is a marked joint between two
 * adjacent plug blocks, stored as one boolean on each block for the face it is on ({@code seam_up}, {@code seam_north}, ...), so
 * it lives in the block state, survives every save and every lift exactly, and costs nothing while nothing is marked. All
 * seams are false by default, so old worlds and every placed block are unchanged. A seam changes nothing about blast resistance,
 * radiation shielding or mining; it only decides which blocks the crane takes together. It is drawn as a groove on the four
 * faces round the joint.
 */
public final class ShieldPlugConcreteBlock extends Block {
    /** Blast resistance of the three tiers. */
    public static final float RESISTANCE_1 = 1800.0f, RESISTANCE_2 = 3600.0f, RESISTANCE_3 = 7200.0f;
    /** Hardness (mining time) of the three tiers; Immersive Engineering's reinforced concrete is about 1.0. */
    public static final float HARDNESS_1 = 4.0f, HARDNESS_2 = 8.0f, HARDNESS_3 = 16.0f;

    public static final BooleanProperty SEAM_DOWN = BooleanProperty.create("seam_down");
    public static final BooleanProperty SEAM_UP = BooleanProperty.create("seam_up");
    public static final BooleanProperty SEAM_NORTH = BooleanProperty.create("seam_north");
    public static final BooleanProperty SEAM_SOUTH = BooleanProperty.create("seam_south");
    public static final BooleanProperty SEAM_WEST = BooleanProperty.create("seam_west");
    public static final BooleanProperty SEAM_EAST = BooleanProperty.create("seam_east");

    public ShieldPlugConcreteBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SEAM_DOWN, false).setValue(SEAM_UP, false).setValue(SEAM_NORTH, false)
                .setValue(SEAM_SOUTH, false).setValue(SEAM_WEST, false).setValue(SEAM_EAST, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SEAM_DOWN, SEAM_UP, SEAM_NORTH, SEAM_SOUTH, SEAM_WEST, SEAM_EAST);
    }

    /** The seam property of the joint on a side. */
    public static BooleanProperty seamProperty(Direction side) {
        return switch (side) {
            case DOWN -> SEAM_DOWN;
            case UP -> SEAM_UP;
            case NORTH -> SEAM_NORTH;
            case SOUTH -> SEAM_SOUTH;
            case WEST -> SEAM_WEST;
            case EAST -> SEAM_EAST;
        };
    }

    /** True when this block's joint on {@code side} is marked as a seam. */
    public static boolean seamed(BlockState state, Direction side) {
        return state.getBlock() instanceof ShieldPlugConcreteBlock && state.getValue(seamProperty(side));
    }

    /** True when any joint of this block is marked as a seam. */
    public static boolean hasSeam(BlockState state) {
        for (Direction d : Direction.values()) {
            if (seamed(state, d)) {
                return true;
            }
        }
        return false;
    }
}
