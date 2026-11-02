package dev.bwr.mod.containment;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Selection outline of the cells of a containment door (airlock, vault door).
 * <p>
 * A cell has a full-block outline so the door can be picked out and clicked from outside. A ray that starts inside a
 * block with a full outline hits that block at its own start point, so for an entity standing in a cell that can be
 * walked through the same outline would catch every click and selection of that entity's player: the door behind or in
 * front could not be reached, nor anything beyond it. Such a cell is therefore see-through for the entity that stands
 * in it ({@link #outline}). Collision is a different shape and is not touched.
 */
final class ContainmentDoorShapes {
    private ContainmentDoorShapes() {}

    /**
     * @param passable whether the cell does not block entities right now (an open leaf)
     * @return nothing while the context's own entity is inside the passable cell, the whole block otherwise
     */
    static VoxelShape outline(boolean passable, BlockPos pos, CollisionContext context) {
        if (passable && context instanceof EntityCollisionContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity != null && entity.getBoundingBox().intersects(new AABB(pos))) return Shapes.empty();
        }
        return Shapes.block();
    }
}
