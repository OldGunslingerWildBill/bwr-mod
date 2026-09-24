package dev.bwr.mod.condenser;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.steam.SteamLinePort;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.util.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import java.util.Locale;

/** One placeable closed condenser. Only the foundation controller ticks or renders. */
public class CondenserBlock extends BaseEntityBlock implements SteamLinePort {
    public enum Port implements StringRepresentable {
        NONE,BYPASS,COLD,HOT,CONDENSATE,MAKEUP;
        @Override public String getSerializedName(){return name().toLowerCase(Locale.ROOT);}
        public boolean water(){return this==COLD||this==HOT||this==CONDENSATE||this==MAKEUP;}
        public boolean inlet(){return this==COLD||this==MAKEUP;}
    }
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty CONTROLLER=BooleanProperty.create("controller");
    public static final EnumProperty<Port> PORT=EnumProperty.create("port",Port.class);
    public static final MapCodec<CondenserBlock> CODEC=simpleCodec(CondenserBlock::new);
    private static final ThreadLocal<Boolean> REMOVING=ThreadLocal.withInitial(()->false);
    public CondenserBlock(Properties properties){
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(CONTROLLER,true).setValue(PORT,Port.NONE));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,CONTROLLER,PORT);}
    @Override protected RenderShape getRenderShape(BlockState s){return s.getValue(CONTROLLER)?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.INVISIBLE;}
    public static Direction portFace(BlockState s){return switch(s.getValue(PORT)){
        case BYPASS->s.getValue(FACING);case CONDENSATE->s.getValue(FACING).getOpposite();
        case MAKEUP->s.getValue(FACING).getCounterClockWise();default->Direction.DOWN;};}
    @Override public boolean acceptsSteamLineOn(BlockState s,Direction side){return s.getValue(PORT)==Port.BYPASS&&side==portFace(s);}
    public static boolean acceptsWater(BlockState s,Direction side){return s.getBlock() instanceof CondenserBlock&&s.getValue(PORT).water()&&side==portFace(s);}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new CondenserBlockEntity(p,s);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> type){
        return l.isClientSide()||!s.getValue(CONTROLLER)?null:createTickerHelper(type,BwrBlockEntities.CONDENSER.get(),CondenserBlockEntity::serverTick);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx){
        ctx=CondenserPlacement.align(ctx);
        if(!CondenserPlacement.anchorReady(ctx))return null;
        var state=defaultBlockState().setValue(FACING,ctx.getHorizontalDirection().getOpposite());
        return canPlaceAt(ctx.getLevel(),ctx.getPlayer(),ctx.getClickedPos(),state.getValue(FACING))?state:null;
    }
    public static boolean canPlaceAt(Level level,Player player,BlockPos root,Direction facing){
        // BlockItem also checks the controller block before its BE exists,
        // when its fallback collision shape is a full cube. Match that check
        // so the preview cannot promise placement through an entity at root.
        if(!level.isUnobstructed(null,Shapes.block().move(root.getX(),root.getY(),root.getZ())))return false;
        var layout=CondenserLayout.INSTANCE;
        for(var cell:layout.cells){
            var p=layout.world(root,facing,cell);
            if(!level.isLoaded(p)||!dev.bwr.mod.world.AssemblyAccess.permitted(level,player,p)||level.isOutsideBuildHeight(p)||!level.getWorldBorder().isWithinBounds(p)
                    ||!level.getBlockState(p).canBeReplaced()||!level.isUnobstructed(null,cell.shape(facing).move(p.getX(),p.getY(),p.getZ())))return false;
        }
        return true;
    }
    public BlockState cellState(BlockState root,CondenserLayout.Cell cell){return root.setValue(CONTROLLER,cell.index()==CondenserLayout.INSTANCE.controllerIndex()).setValue(PORT,cell.role());}
    @Override public void setPlacedBy(Level l,BlockPos root,BlockState state,LivingEntity who,ItemStack stack){
        if(l.isClientSide()||!state.getValue(CONTROLLER)||!(l.getBlockEntity(root) instanceof CondenserBlockEntity owner))return;
        var layout=CondenserLayout.INSTANCE;
        // Recheck before any writes; a failed placement never removes another block.
        for(var cell:layout.cells){var p=layout.world(root,state.getValue(FACING),cell);
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(l,who,p)||!p.equals(root)&&(!l.isLoaded(p)||!l.getBlockState(p).canBeReplaced())){l.removeBlock(root,false);return;}}
        owner.forming=true;
        for(var cell:layout.cells){var p=layout.world(root,state.getValue(FACING),cell);if(p.equals(root))continue;
            if(!l.setBlock(p,cellState(state,cell),Block.UPDATE_CLIENTS)||!(l.getBlockEntity(p) instanceof CondenserBlockEntity part)){l.removeBlock(root,false);return;}
            part.bind(owner,cell.index());l.sendBlockUpdated(p,l.getBlockState(p),l.getBlockState(p),Block.UPDATE_CLIENTS);
        }
        owner.forming=false;owner.structureDirty=true;owner.setChanged();
        for(var port:layout.ports){var p=layout.world(root,state.getValue(FACING),port);l.invalidateCapabilities(p);l.updateNeighborsAt(p,this);}
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        if(l.getBlockEntity(p) instanceof CondenserBlockEntity be){var cell=be.layout().cell(be.cellIndex());if(cell!=null)return cell.shape(s.getValue(FACING));}
        return Shapes.block();
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(player instanceof ServerPlayer sp && l.getBlockEntity(p) instanceof CondenserBlockEntity cell && cell.owner()!=null)
            dev.bwr.mod.gui.CondenserMenu.open(sp,cell.owner().getBlockPos(),p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected void tick(BlockState s,ServerLevel l,BlockPos p,RandomSource random){
        if(l.getBlockEntity(p) instanceof CondenserBlockEntity cell && !s.getValue(CONTROLLER)){
            if(!l.isLoaded(cell.root())){l.scheduleTick(p,this,100);return;}
            if(cell.owner()==null)l.removeBlock(p,false);
        }
    }
    @Override public BlockState playerWillDestroy(Level l,BlockPos p,BlockState s,Player player){
        if(!l.isClientSide()&&!s.getValue(CONTROLLER)&&!player.isCreative()&&player.hasCorrectToolForDrops(s)
                &&l.getBlockEntity(p) instanceof CondenserBlockEntity part && part.owner()!=null){
            var owner=part.owner();Block.dropResources(owner.getBlockState(),l,owner.getBlockPos(),owner,player,player.getMainHandItem());
        }
        return super.playerWillDestroy(l,p,s,player);
    }
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState replacement,boolean moving){
        CondenserBlockEntity owner=l.getBlockEntity(p) instanceof CondenserBlockEntity part?part.owner():null;
        super.onRemove(s,l,p,replacement,moving);l.invalidateCapabilities(p);
        if(l.isClientSide()||replacement.is(this)||REMOVING.get()||owner==null)return;
        REMOVING.set(true);
        try{for(var cell:owner.layout().cells){var other=owner.layout().world(owner.getBlockPos(),owner.getBlockState().getValue(FACING),cell);
            if(!other.equals(p)&&l.isLoaded(other)&&l.getBlockEntity(other) instanceof CondenserBlockEntity be&&be.matches(owner))l.removeBlock(other,false);
        }}finally{REMOVING.set(false);}
    }
}
