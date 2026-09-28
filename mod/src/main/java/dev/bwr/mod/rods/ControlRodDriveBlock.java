package dev.bwr.mod.rods;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One control rod drive, mounted on the vessel bottom head directly beneath its
 * own rod — {@code SPEC.md} section 3.3.
 *
 * <p>One drive per rod, never PWR-style ganged banks. A missing drive stops the
 * multiblock forming and the error names the coordinate.
 *
 * <p>Drives tick themselves rather than being ticked by the controller, and
 * deliberately so: a drive in an unformed or half-built structure still spends
 * its supplies and still loses its charge, which is the whole point of the
 * mechanic. Without this ticker nothing ever advanced
 * {@code ControlRodDriveHardware}, so {@code powered} and {@code waterSupplied}
 * stayed at their field initialisers of true forever and the entire CRD supply
 * and wear model — SPEC 3.3's central mechanic — had no effect in a world.
 */
public class ControlRodDriveBlock extends BaseEntityBlock {

    public static final MapCodec<ControlRodDriveBlock> CODEC =
            simpleCodec(ControlRodDriveBlock::new);

    public ControlRodDriveBlock(Properties properties) {
        super(properties.dynamicShape());
    }

    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE=net.minecraft.world.phys.shapes.Shapes.or(
            net.minecraft.world.phys.shapes.Shapes.box(.265,.30,.265,.735,1,.735),
            net.minecraft.world.phys.shapes.Shapes.box(.135,.19,.135,.865,.37,.865),
            net.minecraft.world.phys.shapes.Shapes.box(.35,.02,.10,.65,.24,.65),
            net.minecraft.world.phys.shapes.Shapes.box(0,.38,.38,1,.62,.62));
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,net.minecraft.world.level.BlockGetter level,BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context){
        return dev.bwr.mod.reactor.VesselDriveAccess.contains(level,pos)&&!context.isHoldingItem(asItem())
                ?net.minecraft.world.phys.shapes.Shapes.empty():SHAPE;
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state,net.minecraft.world.level.BlockGetter level,BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context){
        return dev.bwr.mod.reactor.VesselDriveAccess.contains(level,pos)?net.minecraft.world.phys.shapes.Shapes.empty():SHAPE;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ControlRodDriveBlockEntity(pos, state);
    }

    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){
        super.onPlace(state,level,pos,old,moving);ControlRodDriveSupplies.changed(level,pos);
        dev.bwr.mod.reactor.FormedReactorRegistry.internalsChanged(level,pos);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moving){
        super.onRemove(state,level,pos,next,moving);ControlRodDriveSupplies.changed(level,pos);
        dev.bwr.mod.reactor.FormedReactorRegistry.internalsChanged(level,pos);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // Supplies and wear are server-authoritative, like everything else.
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BwrBlockEntities.CONTROL_ROD_DRIVE.get(),
                ControlRodDriveBlockEntity::serverTick);
    }

    /**
     * Report this drive's condition, the way every other machine block in the
     * mod does on a plain right-click.
     *
     * <p>It is the only way to find out which drive is the dry one. The panel
     * counts operable, powered and water-supplied drives across the whole core,
     * so a player sees "174 of 177" and then has 177 blocks under the vessel and
     * no way to tell them apart. See
     * {@code ControlRodDriveBlockEntity.statusLines()}.
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide()) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ControlRodDriveBlockEntity be) {
            for (String line : be.statusLines()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(line), false);
            }
        }
        return net.minecraft.world.InteractionResult.CONSUME;
    }
}
