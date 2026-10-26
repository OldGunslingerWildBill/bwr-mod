package dev.bwr.mod.refuelling;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.gui.CraneMenu;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The crane's pendant console: stands beside a run of {@link CraneRunwayRailBlock}s and is the crane's controls. Right-click opens the
 * pendant screen; sneak-click prints what the crane sees (rails, reactor, head stands, pads) and what it is doing. It has no ticker: a
 * moving crane is ticked by scheduled block ticks that exist only while a lift is in progress ({@link BuildingCraneBlockEntity#scheduledTick}).
 */
public class BuildingCraneConsoleBlock extends BaseEntityBlock {

    public static final MapCodec<BuildingCraneConsoleBlock> CODEC = simpleCodec(BuildingCraneConsoleBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

    public BuildingCraneConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BuildingCraneBlockEntity(pos, state);
    }

    /** The crane's own pace: scheduled by the block entity while it works, never otherwise. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof BuildingCraneBlockEntity be) {
            be.scheduledTick(level);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof BuildingCraneBlockEntity be) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.CONSUME;
        }
        BridgeSite site = be.refreshSite();
        if (player.isShiftKeyDown()) {
            for (Component line : report(level, be, site)) {
                player.displayClientMessage(line, false);
            }
            return InteractionResult.CONSUME;
        }
        if (!site.ok()) {
            player.displayClientMessage(site.problem, true);
            return InteractionResult.CONSUME;
        }
        CraneMenu.open(sp, be);
        return InteractionResult.CONSUME;
    }

    static List<Component> report(Level level, BuildingCraneBlockEntity be, BridgeSite site) {
        List<Component> lines = new ArrayList<>();
        if (!site.ok()) {
            lines.add(site.problem);
            return lines;
        }
        lines.add(BwrText.tr("status.bwr.building_crane.report_rails",
                "Runway: %s blocks long along %s, %s apart, at y %s.", site.aHi - site.aLo + 1, site.axis.getName(), site.cHigh - site.cLow, site.railY));
        lines.add(site.reactorPos == null
                ? BwrText.tr("status.bwr.building_crane.report_no_reactor", "Reactor: none between the rails.")
                : BwrText.tr("status.bwr.building_crane.report_reactor", "Reactor: controller at %s.", site.reactorPos.toShortString()));
        int stands = 0;
        int pads = 0;
        for (BlockPos p : HeadStandBlockEntity.stands(level)) {
            if (site.reaches(p.getX() + 0.5, p.getZ() + 0.5)) {
                stands++;
            }
        }
        for (BlockPos p : EquipmentPadBlockEntity.pads(level)) {
            if (site.reaches(p.getX() + 0.5, p.getZ() + 0.5)) {
                pads++;
            }
        }
        lines.add(BwrText.tr("status.bwr.building_crane.report_places", "Head stands in reach: %s. Equipment pads in reach: %s.", stands, pads));
        int laid = be.plugsLaid();
        if (laid > 0) {
            lines.add(BwrText.tr("status.bwr.building_crane.report_plugs", "Shield plug sections set down on the refuel floor: %s.", laid));
        }
        lines.add(BwrText.tr("status.bwr.building_crane.report_state", "Crane: %s, %s queued%s.",
                be.phase().name().toLowerCase(Locale.ROOT), be.queued(), be.holding() ? ", holding " + be.carriedSummary() : ""));
        ReactorControllerBlockEntity r = site.reactor(level);
        if (r != null) {
            lines.add(r.headSummary());
        }
        return lines;
    }

    /** Breaking the console under a load sets a dryer or separator assembly back in its vessel and lays a hanging head down. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (state.getBlock() != next.getBlock() && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof BuildingCraneBlockEntity be) {
            be.dropCarried(server);
        }
        super.onRemove(state, level, pos, next, moved);
    }
}
