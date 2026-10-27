package dev.bwr.mod.power;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/**
 * The turbine hall's two stands (TURBINE-MAINTENANCE.md). A {@link Kind#BLADES LP Blade Storage Stand} is a rack of two saddles for LP
 * turbine blade sections, spares or damaged ones, stocked by hand, by hopper or pipe, or by the crane; it is the middle of a place two
 * sections wide (one each side of it, across its facing). A {@link Kind#CASING Casing Laydown Stand} is the steel cradle the turbine hall
 * crane sets an LP's hood on; it is the middle of a 7 x 7 place. Breaking a stand drops what it holds. Neither has a ticker.
 */
public class TurbineStandBlock extends BaseEntityBlock {
    public enum Kind { BLADES, CASING }

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<TurbineStandBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(propertiesCodec(),
            com.mojang.serialization.Codec.STRING.fieldOf("kind").forGetter(b -> b.kind.name())).apply(i, (p, k) -> new TurbineStandBlock(p, Kind.valueOf(k))));
    private static final VoxelShape BLADE_SHAPE = Block.box(0, 0, 0, 16, 6, 16);
    private static final VoxelShape CASING_SHAPE = Block.box(0, 0, 0, 16, 8, 16);
    private final Kind kind;

    public TurbineStandBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == Kind.BLADES ? BLADE_SHAPE : CASING_SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TurbineStandBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!level.isClientSide() && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof TurbineStandBlockEntity stand) {
            // never lost: whatever stands on it drops as items
            for (int i = 0; i < stand.items().getSlots(); i++) {
                ItemStack s = stand.items().getStackInSlot(i);
                if (!s.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, s.copy());
                }
            }
            stand.clearForRemoval();
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof TurbineLoadItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof TurbineStandBlockEntity stand) {
            ItemStack rest = stand.insert(stack.copyWithCount(1));
            if (rest.isEmpty()) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
            } else {
                player.displayClientMessage(kind == Kind.BLADES
                        ? BwrText.tr("status.bwr.turbine_stand.blades_refused", "This stand holds LP turbine blade sections, two at most.")
                        : BwrText.tr("status.bwr.turbine_stand.casing_refused", "This cradle holds one LP turbine hood."), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof TurbineStandBlockEntity stand) {
            if (player.isShiftKeyDown()) {
                ItemStack out = stand.extractLast();
                if (!out.isEmpty()) {
                    if (!player.getInventory().add(out)) {
                        player.drop(out, false);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
            player.displayClientMessage(describe(stand), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    private net.minecraft.network.chat.Component describe(TurbineStandBlockEntity stand) {
        if (kind == Kind.CASING) {
            ItemStack hood = stand.items().getStackInSlot(0);
            return hood.isEmpty()
                    ? BwrText.tr("status.bwr.turbine_stand.casing_empty", "Empty. The turbine hall crane sets an LP turbine's hood here (COVER OFF); keep 7 x 7 of floor round it clear, between the crane's rails.")
                    : BwrText.tr("status.bwr.turbine_stand.casing_holding", "Holding an LP turbine hood (%s t). COVER ON sets it back.",
                    String.format(Locale.ROOT, "%.0f", TurbineLoadItem.massT(hood)));
        }
        int spare = 0, damaged = 0;
        for (int i = 0; i < stand.items().getSlots(); i++) {
            ItemStack s = stand.items().getStackInSlot(i);
            if (TurbineLoadItem.isGoodSection(s)) spare++;
            else if (!s.isEmpty()) damaged++;
        }
        return BwrText.tr("status.bwr.turbine_stand.blades", "%s spare and %s damaged LP blade sections of 2. Sneak-click to take one off.", spare, damaged);
    }
}
