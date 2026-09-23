package dev.bwr.mod.suppression;

import net.minecraft.core.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Concrete panels with a coping course on exposed top faces. No block entity or ticking. */
public class SuppressionPoolWallBlock extends Block {
    public static final BooleanProperty RIM=BooleanProperty.create("rim");
    public SuppressionPoolWallBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(RIM,true));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(RIM);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(RIM,!ConcreteBasin.shell(c.getLevel().getBlockState(c.getClickedPos().above())));}
    @Override protected BlockState updateShape(BlockState s,Direction d,BlockState n,LevelAccessor l,BlockPos p,BlockPos q){return d==Direction.UP?s.setValue(RIM,!ConcreteBasin.shell(n)):s;}
    @Override protected void onPlace(BlockState s,Level l,BlockPos p,BlockState old,boolean moved){super.onPlace(s,l,p,old,moved);if(s.getBlock()!=old.getBlock())ConcreteBasin.changed(l,p,false);}
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState n,boolean moved){if(s.getBlock()!=n.getBlock())ConcreteBasin.changed(l,p,true);super.onRemove(s,l,p,n,moved);}
}
