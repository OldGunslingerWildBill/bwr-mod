package dev.bwr.mod.suppression;

import dev.bwr.mod.text.BwrText;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Shell-and-tube exchanger, four physically separate flanges: tube side (RHR) front/back, shell side (cooling water) left/right. */
public class RhrHeatExchangerBlock extends BaseEntityBlock implements ProcessAssembly {
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    public RhrHeatExchangerBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving) {
        super.onPlace(state,level,pos,old,moving);
        if(state!=old)dev.bwr.mod.piping.PipeTopology.changed(level,pos);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(RhrHeatExchangerBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new RhrHeatExchangerBlockEntity(p,s);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> t){return l.isClientSide()?null:dev.bwr.mod.TickProbe.wrap(createTickerHelper(t,BwrBlockEntities.RHR_HEAT_EXCHANGER.get(),RhrHeatExchangerBlockEntity::serverTick));}
    public static Direction primaryIn(BlockState s){return s.getValue(FACING);}
    public static Direction primaryOut(BlockState s){return primaryIn(s).getOpposite();}
    public static Direction coldIn(BlockState s){return primaryIn(s).getCounterClockWise();}
    public static Direction hotOut(BlockState s){return primaryIn(s).getClockWise();}
    @Override public BlockPos origin(BlockPos p,BlockState s){return p;}
    @Override public boolean hasPort(AssemblyPort role){return !role.isSteam();}
    @Override public BlockPos portPosition(BlockPos p,BlockState s,AssemblyPort role){return p;}
    @Override public Direction portFace(BlockState s,AssemblyPort role){return role==AssemblyPort.WATER_DISCHARGE?primaryOut(s):primaryIn(s);}
    @Override public AssemblyPort portAt(BlockState s,Direction face){return face==primaryIn(s)?AssemblyPort.WATER_SUCTION:face==primaryOut(s)?AssemblyPort.WATER_DISCHARGE:null;}
    @Override public boolean complete(Level l,BlockPos p,BlockState s){return l.isLoaded(p)&&l.getBlockState(p)==s;}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(!l.isClientSide()&&l.getBlockEntity(p) instanceof RhrHeatExchangerBlockEntity be&&player instanceof net.minecraft.server.level.ServerPlayer sp)
            dev.bwr.mod.gui.RhrHeatExchangerMenu.open(sp,be);
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.Item.TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag){
        lines.add(BwrText.tr(
                "status.bwr.rhr_heat_exchanger.pool_water_red_in_amber_out_use_an",
                "Tube side (RHR pool water): red IN / amber OUT. Use an RHR pump."));
        lines.add(BwrText.tr(
                "status.bwr.rhr_heat_exchanger.cooling_water_blue_in_cyan_out_separate_circuit",
                "Shell side (river cooling water): blue IN / cyan OUT. Separate circuit."));
        lines.add(BwrText.tr("status.bwr.rhr_heat_exchanger.up_to_27_1_mw_requires_both_water", "Up to 27.1 MW; requires both water circuits."));
        super.appendHoverText(stack,context,lines,flag);
    }
}
