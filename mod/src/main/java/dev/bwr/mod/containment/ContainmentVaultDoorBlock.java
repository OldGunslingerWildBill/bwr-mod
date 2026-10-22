package dev.bwr.mod.containment;

import dev.bwr.mod.text.BwrText;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
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

/** One item places a complete 3x3 or 4x4 vault opening in the wall plane. */
public final class ContainmentVaultDoorBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty CELL=IntegerProperty.create("cell",0,15);
    public static final BooleanProperty SEALED=BooleanProperty.create("sealed");
    /** The leaf is still across the opening while it swings: solid although the seal is already broken (or not yet made). */
    public static final BooleanProperty HELD=BooleanProperty.create("held");
    private static final ThreadLocal<Boolean> REMOVING=ThreadLocal.withInitial(()->false);
    private static final MapCodec<ContainmentVaultDoorBlock> CODEC3=simpleCodec(p->new ContainmentVaultDoorBlock(p,3));
    private static final MapCodec<ContainmentVaultDoorBlock> CODEC4=simpleCodec(p->new ContainmentVaultDoorBlock(p,4));
    private final int size;

    public ContainmentVaultDoorBlock(Properties properties,int size) {
        super(properties.noOcclusion());
        if(size!=3&&size!=4)throw new IllegalArgumentException("vault door size");
        this.size=size;
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH)
                .setValue(CELL,0).setValue(SEALED,true).setValue(HELD,false));
    }
    public int size(){return size;}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return size==3?CODEC3:CODEC4;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,CELL,SEALED,HELD);}
    @Override protected RenderShape getRenderShape(BlockState state){return state.getValue(CELL)==0?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.INVISIBLE;}
    @Override public ContainmentVaultDoorBlockEntity newBlockEntity(BlockPos pos,BlockState state){return state.getValue(CELL)==0?new ContainmentVaultDoorBlockEntity(pos,state):null;}
    public BlockPos root(BlockPos pos,BlockState state){
        int cell=state.getValue(CELL);
        return pos.relative(state.getValue(FACING).getClockWise(),-(cell%size)).below(cell/size);
    }
    public BlockPos cell(BlockPos root,Direction facing,int index){
        return root.relative(facing.getClockWise(),index%size).above(index/size);
    }
    public ContainmentVaultDoorBlockEntity owner(Level level,BlockPos pos,BlockState state){
        BlockPos root=root(pos,state);
        return level.isLoaded(root)&&level.getBlockEntity(root) instanceof ContainmentVaultDoorBlockEntity be?be:null;
    }
    public boolean canPlaceAt(Level level,Player player,BlockPos root,Direction facing){
        for(int i=0;i<size*size;i++) {
            BlockPos pos=cell(root,facing,i);
            if(!level.isLoaded(pos)||level.isOutsideBuildHeight(pos)||!level.getWorldBorder().isWithinBounds(pos)
                    ||!dev.bwr.mod.world.AssemblyAccess.permitted(level,player,pos)
                    ||!level.getBlockState(pos).canBeReplaced()
                    ||!level.isUnobstructed(null,Shapes.block().move(pos.getX(),pos.getY(),pos.getZ())))return false;
        }
        return true;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){
        Direction facing=context.getHorizontalDirection().getOpposite();
        return canPlaceAt(context.getLevel(),context.getPlayer(),context.getClickedPos(),facing)
                ?defaultBlockState().setValue(FACING,facing):null;
    }
    @Override public void setPlacedBy(Level level,BlockPos root,BlockState state,LivingEntity placer,ItemStack stack){
        if(level.isClientSide()||state.getValue(CELL)!=0)return;
        for(int i=1;i<size*size;i++) {
            BlockPos pos=cell(root,state.getValue(FACING),i);
            if(!level.isLoaded(pos)||!dev.bwr.mod.world.AssemblyAccess.permitted(level,placer,pos)
                    ||!level.getBlockState(pos).canBeReplaced()) {level.removeBlock(root,false);return;}
        }
        for(int i=1;i<size*size;i++)level.setBlock(cell(root,state.getValue(FACING),i),state.setValue(CELL,i),Block.UPDATE_ALL);
        ContainmentControllerRegistry.changed(level,root);
    }
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.block();}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return state.getValue(SEALED)||state.getValue(HELD)?Shapes.block():Shapes.empty();
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(level.isClientSide())return InteractionResult.SUCCESS;
        var owner=owner(level,pos,state);
        if(owner==null)return InteractionResult.PASS;
        boolean started=owner.handwheelToggle();
        if(player instanceof ServerPlayer)player.displayClientMessage((started ? BwrText.tr("status.bwr.containment_vault_door.vault_door_handwheel_engaged_60_s", "Vault door handwheel engaged (60 s)") : BwrText.tr("status.bwr.containment_vault_door.vault_door_jammed_or_already_moving", "Vault door jammed or already moving")),true);
        return InteractionResult.CONSUME;
    }
    @Override protected void neighborChanged(BlockState state,Level level,BlockPos pos,Block neighbour,BlockPos changed,boolean moving){
        super.neighborChanged(state,level,pos,neighbour,changed,moving);
        if(level.isClientSide())return;
        var owner=owner(level,pos,state);
        if(owner==null||owner.isComputerControlled())return;
        BlockPos root=root(pos,state);boolean signal=false;
        for(int i=0;i<size*size&&!signal;i++)signal=level.hasNeighborSignal(cell(root,state.getValue(FACING),i));
        owner.setOpen(signal,true);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(state.getValue(CELL)==0&&level.getBlockEntity(pos) instanceof ContainmentVaultDoorBlockEntity owner)owner.finishStroke();
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving){
        super.onPlace(state,level,pos,old,moving);
        if(!level.isClientSide()&&(!old.is(this)||old.getValue(SEALED)!=state.getValue(SEALED)))ContainmentControllerRegistry.changed(level,pos);
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
        BlockPos root=root(pos,state);
        REMOVING.set(true);
        try {
            for(int i=0;i<size*size;i++) {
                BlockPos other=cell(root,state.getValue(FACING),i);
                if(!other.equals(pos)&&level.isLoaded(other)) {
                    BlockState part=level.getBlockState(other);
                    if(part.is(this)&&part.getValue(CELL)==i&&part.getValue(FACING)==state.getValue(FACING))
                        level.removeBlock(other,false);
                }
            }
        } finally {REMOVING.set(false);}
    }
    @Override protected BlockState rotate(BlockState state,Rotation rotation){return state.setValue(FACING,rotation.rotate(state.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState state,Mirror mirror){return state.rotate(mirror.getRotation(state.getValue(FACING)));}
}
