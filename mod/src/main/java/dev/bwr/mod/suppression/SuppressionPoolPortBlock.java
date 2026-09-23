package dev.bwr.mod.suppression;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Concrete wall penetration. Front face only; pump suction and water return are separate blocks. */
public class SuppressionPoolPortBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    public final boolean suction;
    public SuppressionPoolPortBlock(Properties p,boolean suction){super(p);this.suction=suction;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(p->new SuppressionPoolPortBlock(p,suction));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){var d=c.getClickedFace();return defaultBlockState().setValue(FACING,d.getAxis().isHorizontal()?d:c.getHorizontalDirection().getOpposite());}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new SuppressionPoolPortBlockEntity(p,s);}
    @Override protected void onPlace(BlockState s,Level l,BlockPos p,BlockState old,boolean moved){super.onPlace(s,l,p,old,moved);if(s.getBlock()!=old.getBlock())ConcreteBasin.changed(l,p,false);}
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState n,boolean moved){if(s.getBlock()!=n.getBlock())ConcreteBasin.changed(l,p,true);super.onRemove(s,l,p,n,moved);}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(!l.isClientSide()&&l.getBlockEntity(p) instanceof SuppressionPoolPortBlockEntity port
                &&player instanceof net.minecraft.server.level.ServerPlayer sp){
            var owner=port.owner();
            if(owner==null)player.displayClientMessage(net.minecraft.network.chat.Component.literal("Pool not formed. Complete and fill the concrete tub; check its controller for details."),true);
            else dev.bwr.mod.gui.SuppressionPoolMenu.open(sp,owner,p);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.Item.TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag){
        lines.add(net.minecraft.network.chat.Component.literal(suction?"Blue flange: water OUT to pump suction.":"Amber flange: water IN from the return loop."));
        lines.add(net.minecraft.network.chat.Component.literal("Place in a concrete basin wall, facing outward."));
        super.appendHoverText(stack,context,lines,flag);
    }
}
