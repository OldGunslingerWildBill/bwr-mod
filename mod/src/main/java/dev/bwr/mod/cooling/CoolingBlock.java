package dev.bwr.mod.cooling;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
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

/** One placeable water machine. Only the center-base controller ticks or renders. */
public class CoolingBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {
    public enum Port implements StringRepresentable {
        NONE,INLET,OUTLET,MAKEUP,POWER;
        @Override public String getSerializedName(){return name().toLowerCase(Locale.ROOT);}
        public boolean water(){return this==INLET||this==OUTLET||this==MAKEUP;}
    }
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty CONTROLLER=BooleanProperty.create("controller");
    public static final BooleanProperty WATERLOGGED=BlockStateProperties.WATERLOGGED;
    public static final EnumProperty<Port> PORT=EnumProperty.create("port",Port.class);
    public static final MapCodec<CoolingBlock> CODEC=com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(i->i.group(propertiesCodec(),com.mojang.serialization.Codec.STRING.fieldOf("design").forGetter(b->b.design.name())).apply(i,(p,d)->new CoolingBlock(p,dev.bwr.core.turbine.CoolingWaterUnit.Design.valueOf(d))));
    public final dev.bwr.core.turbine.CoolingWaterUnit.Design design;
    public CoolingLayout layout(){return CoolingLayout.get(design);}
    private static final ThreadLocal<Boolean> REMOVING=ThreadLocal.withInitial(()->false);
    public CoolingBlock(Properties properties,dev.bwr.core.turbine.CoolingWaterUnit.Design design){
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));this.design=design;
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(CONTROLLER,true).setValue(PORT,Port.NONE).setValue(WATERLOGGED,false));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,CONTROLLER,PORT,WATERLOGGED);}
    @Override protected net.minecraft.world.level.material.FluidState getFluidState(BlockState s){
        return s.getValue(WATERLOGGED)?net.minecraft.world.level.material.Fluids.WATER.getSource(false):super.getFluidState(s);
    }
    @Override public boolean placeLiquid(LevelAccessor l,BlockPos p,BlockState s,net.minecraft.world.level.material.FluidState fluid){
        return design==dev.bwr.core.turbine.CoolingWaterUnit.Design.INTAKE&&SimpleWaterloggedBlock.super.placeLiquid(l,p,s,fluid);
    }
    @Override protected BlockState updateShape(BlockState s,Direction d,BlockState neighbor,LevelAccessor l,BlockPos p,BlockPos np){
        if(s.getValue(WATERLOGGED))l.scheduleTick(p,net.minecraft.world.level.material.Fluids.WATER,net.minecraft.world.level.material.Fluids.WATER.getTickDelay(l));
        return super.updateShape(s,d,neighbor,l,p,np);
    }
    @Override protected RenderShape getRenderShape(BlockState s){return s.getValue(CONTROLLER)?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.INVISIBLE;}
    public static Direction portFace(BlockState s){
        var block=(CoolingBlock)s.getBlock();
        var cell=block.layout().ports.stream().filter(c->c.role()==s.getValue(PORT)).findFirst().orElse(null);
        return cell==null?null:dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(cell.face(),s.getValue(FACING));
    }
    public static boolean acceptsWater(BlockState s,Direction side){return s.getBlock() instanceof CoolingBlock&&s.getValue(PORT).water()&&side==portFace(s);}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new CoolingBlockEntity(p,s);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> type){
        if(!s.getValue(CONTROLLER))return null;
        return createTickerHelper(type,BwrBlockEntities.COOLING.get(),l.isClientSide()?CoolingBlockEntity::clientTick:CoolingBlockEntity::serverTick);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx){
        var state=defaultBlockState().setValue(FACING,ctx.getHorizontalDirection().getOpposite())
                .setValue(WATERLOGGED,design==dev.bwr.core.turbine.CoolingWaterUnit.Design.INTAKE&&ctx.getLevel().getFluidState(ctx.getClickedPos()).is(net.minecraft.world.level.material.Fluids.WATER));
        var layout=layout();
        for(var cell:layout.cells){
            var p=layout.world(ctx.getClickedPos(),state.getValue(FACING),cell);
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(ctx.getLevel(),ctx.getPlayer(),p)||!ctx.getLevel().isLoaded(p)||ctx.getLevel().isOutsideBuildHeight(p)||!ctx.getLevel().getWorldBorder().isWithinBounds(p)
                    ||!ctx.getLevel().getBlockState(p).canBeReplaced()||!ctx.getLevel().isUnobstructed(null,cell.shape(state.getValue(FACING)).move(p.getX(),p.getY(),p.getZ())))return null;
        }
        return state;
    }
    public BlockState cellState(BlockState root,CoolingLayout.Cell cell){return root.setValue(CONTROLLER,cell.index()==layout().controllerIndex()).setValue(PORT,cell.role());}
    @Override public void setPlacedBy(Level l,BlockPos root,BlockState state,LivingEntity who,ItemStack stack){
        if(l.isClientSide()||!state.getValue(CONTROLLER)||!(l.getBlockEntity(root) instanceof CoolingBlockEntity owner))return;
        var layout=layout();
        // Recheck before any writes; a failed placement never removes another block.
        for(var cell:layout.cells){var p=layout.world(root,state.getValue(FACING),cell);
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(l,who,p)||!p.equals(root)&&(!l.isLoaded(p)||!l.getBlockState(p).canBeReplaced())){l.removeBlock(root,false);return;}}
        owner.forming=true;
        for(var cell:layout.cells){var p=layout.world(root,state.getValue(FACING),cell);if(p.equals(root))continue;
            var placed=cellState(state,cell).setValue(WATERLOGGED,design==dev.bwr.core.turbine.CoolingWaterUnit.Design.INTAKE&&l.getFluidState(p).is(net.minecraft.world.level.material.Fluids.WATER));
            if(!l.setBlock(p,placed,Block.UPDATE_CLIENTS)||!(l.getBlockEntity(p) instanceof CoolingBlockEntity part)){l.removeBlock(root,false);return;}
            part.bind(owner,cell.index());l.sendBlockUpdated(p,l.getBlockState(p),l.getBlockState(p),Block.UPDATE_CLIENTS);
        }
        owner.forming=false;owner.structureDirty=true;owner.setChanged();
        for(var port:layout.ports){var p=layout.world(root,state.getValue(FACING),port);l.invalidateCapabilities(p);l.updateNeighborsAt(p,this);}
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){
        if(l.getBlockEntity(p) instanceof CoolingBlockEntity be){var cell=be.layout().cell(be.cellIndex());if(cell!=null)return cell.shape(s.getValue(FACING));}
        return Shapes.block();
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(player instanceof ServerPlayer sp && l.getBlockEntity(p) instanceof CoolingBlockEntity cell && cell.owner()!=null)
            dev.bwr.mod.gui.CoolingMenu.open(sp,cell.owner().getBlockPos(),p);
        return InteractionResult.sidedSuccess(l.isClientSide());
    }
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected void tick(BlockState s,ServerLevel l,BlockPos p,RandomSource random){
        if(l.getBlockEntity(p) instanceof CoolingBlockEntity cell && !s.getValue(CONTROLLER)){
            if(!l.isLoaded(cell.root())){l.scheduleTick(p,this,100);return;}
            if(cell.owner()==null)l.removeBlock(p,false);
        }
    }
    @Override public BlockState playerWillDestroy(Level l,BlockPos p,BlockState s,Player player){
        if(!l.isClientSide()&&!s.getValue(CONTROLLER)&&!player.isCreative()&&player.hasCorrectToolForDrops(s)
                &&l.getBlockEntity(p) instanceof CoolingBlockEntity part && part.owner()!=null){
            var owner=part.owner();Block.dropResources(owner.getBlockState(),l,owner.getBlockPos(),owner,player,player.getMainHandItem());
        }
        return super.playerWillDestroy(l,p,s,player);
    }
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState replacement,boolean moving){
        CoolingBlockEntity owner=l.getBlockEntity(p) instanceof CoolingBlockEntity part?part.owner():null;
        super.onRemove(s,l,p,replacement,moving);l.invalidateCapabilities(p);
        if(l.isClientSide()||replacement.is(this)||REMOVING.get()||owner==null)return;
        REMOVING.set(true);
        try{for(var cell:owner.layout().cells){var other=owner.layout().world(owner.getBlockPos(),owner.getBlockState().getValue(FACING),cell);
            if(!other.equals(p)&&l.isLoaded(other)&&l.getBlockEntity(other) instanceof CoolingBlockEntity be&&be.matches(owner))l.removeBlock(other,false);
        }}finally{REMOVING.set(false);}
    }
}
