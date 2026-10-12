package dev.bwr.mod.containment;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Wall rule shared by room survey and, later, outward strength scans. */
public final class ContainmentWall {
    private ContainmentWall() {}

    public static final TagKey<Block> FORCED_WALL = TagKey.create(Registries.BLOCK, BwrMod.id("containment_wall"));
    public static final TagKey<Block> LEAKY = TagKey.create(Registries.BLOCK, BwrMod.id("containment_leaky"));

    public static boolean isWall(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ContainmentVaultDoorBlock)
            return state.getValue(ContainmentVaultDoorBlock.SEALED);
        if (state.getBlock() instanceof ContainmentAirlockBlock airlock)
            return airlock.seals(state);
        if (state.getBlock() instanceof ContainmentPenetrationBlock
                && state.getValue(ContainmentPenetrationBlock.RUPTURED)) return false;
        if (state.is(FORCED_WALL)) return true;
        if (state.is(LEAKY) || state.isAir() || !state.getFluidState().isEmpty()
                || state.canBeReplaced()) return false;
        return !Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos), Shapes.block(), BooleanOp.NOT_SAME);
    }
}
