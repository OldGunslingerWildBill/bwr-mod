package dev.bwr.mod.suppression;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.steam.SteamLinePort;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Steam-only wall nozzle feeding an internal submerged sparger. No port or pipe ticker. */
public final class SuppressionPoolSteamPortBlock extends BaseEntityBlock implements SteamLinePort {
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    public SuppressionPoolSteamPortBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(SuppressionPoolSteamPortBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){var d=c.getClickedFace();return defaultBlockState().setValue(FACING,d.getAxis().isHorizontal()?d:c.getHorizontalDirection().getOpposite());}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new SuppressionPoolPortBlockEntity(p,s);}
    @Override public boolean acceptsSteamLineOn(BlockState s,Direction face){return s.getValue(FACING)==face;}
    @Override protected void onPlace(BlockState s,Level l,BlockPos p,BlockState old,boolean moved){super.onPlace(s,l,p,old,moved);dev.bwr.mod.piping.PipeTopology.changed(l,p);if(s.getBlock()!=old.getBlock())ConcreteBasin.changed(l,p,false);}
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState n,boolean moved){if(s.getBlock()!=n.getBlock())ConcreteBasin.changed(l,p,true);super.onRemove(s,l,p,n,moved);}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player who,BlockHitResult hit){
        if(who instanceof net.minecraft.server.level.ServerPlayer player&&l.getBlockEntity(p) instanceof SuppressionPoolPortBlockEntity port){
            var pool=port.owner();
            if(pool!=null)dev.bwr.mod.gui.SuppressionPoolMenu.open(player,pool,p);
            else player.displayClientMessage(net.minecraft.network.chat.Component.literal("Complete the suppression tank floor, walls and roof with one controller."),true);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(ItemStack stack,Item.TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,TooltipFlag flag){
        lines.add(net.minecraft.network.chat.Component.literal("Steam IN: outward-facing wall flange on an enclosed suppression tank."));
        lines.add(net.minecraft.network.chat.Component.literal("Connect High-Pressure Steam Pipe or Mekanism Pressurized Tube. Pump water into the tank first."));
    }
    public static SuppressionPoolBlockEntity owner(Level level,BlockPos p){
        if(!level.isLoaded(p)||!level.getBlockState(p).is(dev.bwr.mod.registry.BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get()))return null;
        return level.getBlockEntity(p) instanceof SuppressionPoolPortBlockEntity part?part.owner():null;
    }
}
