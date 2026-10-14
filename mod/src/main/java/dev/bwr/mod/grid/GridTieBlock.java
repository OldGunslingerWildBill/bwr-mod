package dev.bwr.mod.grid;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class GridTieBlock extends BaseEntityBlock {
    public static final MapCodec<GridTieBlock> CODEC=simpleCodec(GridTieBlock::new);
    public GridTieBlock(Properties properties){super(properties);}
    @Override protected MapCodec<GridTieBlock> codec(){return CODEC;}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new GridTieBlockEntity(pos,state);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override public void setPlacedBy(net.minecraft.world.level.Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.LivingEntity who,net.minecraft.world.item.ItemStack stack){
        super.setPlacedBy(level,pos,state,who,stack);GridItems.restore(level,pos,stack);
    }
    @Override protected java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,net.minecraft.world.level.storage.loot.LootParams.Builder params){return GridItems.retain(super.getDrops(state,params),params);}
}
