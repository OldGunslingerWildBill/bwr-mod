package dev.bwr.mod.reactor;

import net.minecraft.world.level.block.Block;

/**
 * Reactor pressure vessel shell.
 *
 * <p>Structural only — it carries no block entity and no state. The shell's job
 * is to be present in the right places when the controller validates, and to be
 * the thing that fails when the overpressure model picks a component to break.
 */
public class ReactorVesselBlock extends Block {

    public ReactorVesselBlock(Properties properties) {
        // The assembled skin is rendered by the controller. Invisible build
        // cells must not occlude nozzles or cast a rectangular self-shadow.
        super(properties.noOcclusion().dynamicShape());
    }

    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.BlockGetter world,net.minecraft.core.BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context) {
        return VesselHeadAccess.isOpen(world,pos)?net.minecraft.world.phys.shapes.Shapes.empty():net.minecraft.world.phys.shapes.Shapes.block();
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.BlockGetter world,net.minecraft.core.BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context) {
        return getShape(state,world,pos,context);
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getVisualShape(net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.level.BlockGetter world,net.minecraft.core.BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context) {
        return getShape(state,world,pos,context);
    }
}
