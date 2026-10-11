package dev.bwr.mod.containment;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.steam.SteamLinePort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import java.util.List;

/** A one-block, straight-through containment boundary. The service cannot change after placement. */
public final class ContainmentPenetrationBlock extends BaseEntityBlock implements SteamLinePort {
    public enum Service { STEAM, WATER, PASS_THROUGH, POWER, DATA }
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty RUPTURED = BooleanProperty.create("ruptured");
    private final Service service;

    public ContainmentPenetrationBlock(Properties properties, Service service) {
        super(properties);
        this.service = service;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(RUPTURED, false));
    }

    public Service service() { return service; }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                          List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal(ContainmentPenetrationRatings.nameplate(service)));
        super.appendHoverText(stack, context, lines, flag);
    }
    public boolean hasValve() { return service == Service.STEAM || service == Service.WATER || service == Service.PASS_THROUGH; }
    public static boolean axial(BlockState state, Direction side) {
        return side != null && side.getAxis() == state.getValue(FACING).getAxis();
    }
    @Override public boolean acceptsSteamLineOn(BlockState state, Direction side) {
        return service == Service.STEAM && !state.getValue(RUPTURED) && axial(state, side);
    }
    public boolean acceptsWaterLineOn(BlockState state, Direction side) {
        return service == Service.WATER && !state.getValue(RUPTURED) && axial(state, side);
    }
    @Override protected boolean isSignalSource(BlockState state) { return service == Service.DATA && !state.getValue(RUPTURED); }
    @Override protected int getSignal(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, Direction side) {
        if (!isSignalSource(state) || !axial(state, side) || !(level instanceof Level world)) return 0;
        BlockPos source = pos.relative(side.getOpposite());
        if (!world.isLoaded(source) || world.getBlockState(source).getBlock() instanceof ContainmentPenetrationBlock) return 0;
        return world.getSignal(source, side);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new ContainmentPenetrationBlock(properties, service));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, RUPTURED);
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ContainmentPenetrationBlockEntity(pos, state);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                          Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity penetration)
            for (String line : penetration.statusLines())
                player.displayClientMessage(Component.literal(line), false);
        return InteractionResult.CONSUME;
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                              Block neighbor, BlockPos changed, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, changed, moving);
        if (!level.isClientSide() && hasValve()
                && level.getBlockEntity(pos) instanceof ContainmentPenetrationBlockEntity penetration
                && !penetration.isComputerControlled())
            penetration.setDemandOpen(!level.hasNeighborSignal(pos));
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!old.is(this) || old.getValue(FACING) != state.getValue(FACING)
                || old.getValue(RUPTURED) != state.getValue(RUPTURED)) {
            dev.bwr.mod.piping.PipeTopology.changed(level, pos);
            ContainmentControllerRegistry.changed(level, pos);
        }
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        super.onRemove(state, level, pos, replacement, moving);
        if (!replacement.is(this)) {
            dev.bwr.mod.piping.PipeTopology.changed(level, pos);
            ContainmentControllerRegistry.changed(level, pos);
        }
    }
}
