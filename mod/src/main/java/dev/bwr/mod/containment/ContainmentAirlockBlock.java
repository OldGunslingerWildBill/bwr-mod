package dev.bwr.mod.containment;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Two interlocked leaves and the two-cell connecting chamber, placed as one item. */
public final class ContainmentAirlockBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty CELL=IntegerProperty.create("cell",0,5);
    public static final BooleanProperty INNER_OPEN=BooleanProperty.create("inner_open");
    public static final BooleanProperty OUTER_OPEN=BooleanProperty.create("outer_open");
    private static final ThreadLocal<Boolean> REMOVING=ThreadLocal.withInitial(()->false);
    private static final MapCodec<ContainmentAirlockBlock> CODEC=simpleCodec(ContainmentAirlockBlock::new);
    public ContainmentAirlockBlock(Properties properties){
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.SOUTH).setValue(CELL,0)
                .setValue(INNER_OPEN,false).setValue(OUTER_OPEN,false));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,CELL,INNER_OPEN,OUTER_OPEN);}
    @Override protected RenderShape getRenderShape(BlockState state){return state.getValue(CELL)==0?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.INVISIBLE;}
    @Override public ContainmentAirlockBlockEntity newBlockEntity(BlockPos pos,BlockState state){return state.getValue(CELL)==0?new ContainmentAirlockBlockEntity(pos,state):null;}
    public BlockPos cell(BlockPos root,Direction facing,int index){return root.relative(facing,index/2).above(index%2);}
    public BlockPos root(BlockPos pos,BlockState state){int i=state.getValue(CELL);return pos.relative(state.getValue(FACING).getOpposite(),i/2).below(i%2);}
    public ContainmentAirlockBlockEntity owner(Level level,BlockPos pos,BlockState state){
        BlockPos root=root(pos,state);
        return level.isLoaded(root)&&level.getBlockEntity(root) instanceof ContainmentAirlockBlockEntity be?be:null;
    }
    public boolean canPlaceAt(Level level,Player player,BlockPos root,Direction facing){
        for(int i=0;i<6;i++){
            BlockPos pos=cell(root,facing,i);
            if(!level.isLoaded(pos)||level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos)
                    ||!dev.bwr.mod.world.AssemblyAccess.permitted(level,player,pos)
                    ||!level.getBlockState(pos).canBeReplaced()
                    ||!level.isUnobstructed(null,Shapes.block().move(pos.getX(),pos.getY(),pos.getZ())))return false;
        }
        return true;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){
        Direction facing=context.getHorizontalDirection();
        return canPlaceAt(context.getLevel(),context.getPlayer(),context.getClickedPos(),facing)
                ?defaultBlockState().setValue(FACING,facing):null;
    }
    @Override public void setPlacedBy(Level level,BlockPos root,BlockState state,LivingEntity placer,ItemStack stack){
        if(level.isClientSide()||state.getValue(CELL)!=0)return;
        for(int i=1;i<6;i++){
            BlockPos pos=cell(root,state.getValue(FACING),i);
            if(!level.isLoaded(pos)||!dev.bwr.mod.world.AssemblyAccess.permitted(level,placer,pos)
                    ||!level.getBlockState(pos).canBeReplaced()) {level.removeBlock(root,false);return;}
        }
        for(int i=1;i<6;i++)level.setBlock(cell(root,state.getValue(FACING),i),state.setValue(CELL,i),Block.UPDATE_ALL);
        ContainmentControllerRegistry.changed(level,root);
    }
    public boolean seals(BlockState state){
        int depth=state.getValue(CELL)/2;
        return depth==0?!state.getValue(INNER_OPEN):depth==2&&!state.getValue(OUTER_OPEN);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return seals(state)?Shapes.block():Shapes.empty();
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.block();}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(level.isClientSide())return InteractionResult.SUCCESS;
        var owner=owner(level,pos,state);if(owner==null)return InteractionResult.PASS;
        int depth=state.getValue(CELL)/2;
        boolean ok=depth==0?owner.setInnerOpen(!owner.innerOpen(),false)
                :depth==2&&owner.setOuterOpen(!owner.outerOpen(),false);
        if(player instanceof ServerPlayer server&&depth!=1)
            server.displayClientMessage(net.minecraft.network.chat.Component.literal(ok?"Airlock leaf moved":"Interlock: close the other leaf first"),true);
        return depth==1?InteractionResult.PASS:InteractionResult.CONSUME;
    }
    @Override protected void neighborChanged(BlockState state,Level level,BlockPos pos,Block neighbour,BlockPos changed,boolean moving){
        super.neighborChanged(state,level,pos,neighbour,changed,moving);
        if(level.isClientSide())return;
        var owner=owner(level,pos,state);if(owner==null)return;
        BlockPos root=root(pos,state);boolean signal=false;
        for(int i=0;i<6&&!signal;i++)signal=level.hasNeighborSignal(cell(root,state.getValue(FACING),i));
        owner.redstoneSignal(signal);
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){
        super.onPlace(state,level,pos,old,moving);
        if(!level.isClientSide()&&(!old.is(this)||old.getValue(INNER_OPEN)!=state.getValue(INNER_OPEN)
                ||old.getValue(OUTER_OPEN)!=state.getValue(OUTER_OPEN)))ContainmentControllerRegistry.changed(level,pos);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(!level.isClientSide()&&state.getValue(CELL)!=0&&!player.isCreative()&&player.hasCorrectToolForDrops(state))
            Block.popResource(level,pos,new ItemStack(this));
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving){
        super.onRemove(state,level,pos,replacement,moving);
        if(level.isClientSide()||replacement.is(this)||REMOVING.get())return;
        ContainmentControllerRegistry.changed(level,pos);
        BlockPos root=root(pos,state);REMOVING.set(true);
        try{
            for(int i=0;i<6;i++){
                BlockPos other=cell(root,state.getValue(FACING),i);
                if(other.equals(pos)||!level.isLoaded(other))continue;
                BlockState part=level.getBlockState(other);
                if(part.is(this)&&part.getValue(CELL)==i&&part.getValue(FACING)==state.getValue(FACING))level.removeBlock(other,false);
            }
        }finally{REMOVING.set(false);}
    }
}
