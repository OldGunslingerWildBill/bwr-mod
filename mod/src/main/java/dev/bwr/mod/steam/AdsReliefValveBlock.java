package dev.bwr.mod.steam;

import net.minecraft.core.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

/** A side steam inlet, downward relief outlet, and separate pneumatic actuator. */
public final class AdsReliefValveBlock extends SafetyReliefValveBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public AdsReliefValveBlock(Properties p){super(p.noOcclusion());registerDefaultState(defaultBlockState().setValue(FACING,Direction.NORTH));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){super.createBlockStateDefinition(b);b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override public boolean acceptsSteamLineOn(BlockState s,Direction d){return d==Direction.DOWN||d==s.getValue(FACING).getCounterClockWise();}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player who,BlockHitResult hit){
        if(who instanceof net.minecraft.server.level.ServerPlayer sp)dev.bwr.mod.gui.ServiceMenu.open(sp,p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
}
