package dev.bwr.mod.suppression;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

/** Player-operated bottom drain. The owning pool performs transfers; no port ticker. */
public final class SuppressionPoolDrainBlock extends SuppressionPoolPortBlock {
    public static final BooleanProperty OPEN=BlockStateProperties.OPEN, POWERED=BlockStateProperties.POWERED;
    public SuppressionPoolDrainBlock(Properties p){super(p,true);registerDefaultState(defaultBlockState().setValue(OPEN,false).setValue(POWERED,false));}
    @Override protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec(){return simpleCodec(SuppressionPoolDrainBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){super.createBlockStateDefinition(b);b.add(OPEN,POWERED);}
    @Override protected void neighborChanged(BlockState s,Level l,BlockPos p,Block block,BlockPos other,boolean moved){
        super.neighborChanged(s,l,p,block,other,moved);
        boolean powered=l.hasNeighborSignal(p);
        if(!l.isClientSide()&&powered!=s.getValue(POWERED))l.setBlock(p,s.setValue(POWERED,powered).setValue(OPEN,powered),3);
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(player.isShiftKeyDown())return super.useWithoutItem(s,l,p,player,hit);
        if(!l.isClientSide()){
            l.setBlock(p,s.cycle(OPEN),3);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(s.getValue(OPEN)?"Pool drain closed":"Pool drain open"),true);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.Item.TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag){
        lines.add(net.minecraft.network.chat.Component.literal("Water drain OUT: click or redstone to open. Closed on placement."));
        lines.add(net.minecraft.network.chat.Component.literal("Drains to clear air/water or a connected water line, up to 1,000 kg/s."));
        lines.add(net.minecraft.network.chat.Component.literal("Can empty the tank below RHR suction level. Sneak-click: pool screen."));
    }
}
