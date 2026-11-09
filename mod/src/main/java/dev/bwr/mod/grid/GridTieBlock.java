package dev.bwr.mod.grid;

import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.world.AssemblyAccess;
import dev.bwr.mod.world.LoadedWorld;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.*;

/**
 * The Grid Tie: a 5 x 3 x 7 generator circuit breaker (art/models/generator_breaker, tools-export-gcb.py). One block entity on the
 * control cubicle's cell carries all behaviour; every cell routes to it. Grid Ties saved as one block (assembled=false) keep their cube.
 * Not final: with Create: Electro Energetics present the block is its subclass dev.bwr.mod.compat.cee.CeeGridTieBlock (the bus nodes).
 * Laid out like the MSIV (MainSteamIsolationValveBlock) and the pump assemblies (PumpAssemblyBlock): cell = x + 5 * (z + 7 * y).
 */
public class GridTieBlock extends BaseEntityBlock {
    public static final MapCodec<GridTieBlock> CODEC=simpleCodec(GridTieBlock::new);
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty CELL=IntegerProperty.create("cell",0,104);
    public static final BooleanProperty ASSEMBLED=BooleanProperty.create("assembled");
    /** The breaker is closed: selects the closed or open cell models. Kept in step with the block entity (GridTieBlockEntity.syncIndicator). */
    public static final BooleanProperty CLOSED=BooleanProperty.create("closed");
    private static final int W=5,H=3,D=7;
    public static final int CELLS=W*H*D;
    /** The cell that carries the block entity (the control cubicle), from the model manifest. */
    public static final int CONTROLLER;
    private static final VoxelShape[][] SHAPES=new VoxelShape[4][CELLS];
    private static final ThreadLocal<Boolean> REMOVING=ThreadLocal.withInitial(()->false);
    static {
        for(var row:SHAPES)java.util.Arrays.fill(row,Shapes.empty());
        int controller;
        try(var stream=GridTieBlock.class.getResourceAsStream("/data/bwr/pump_models/generator_breaker.json")){
            if(stream==null)throw new IllegalStateException("Missing generator breaker manifest");
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            if(json.get("width").getAsInt()!=W||json.get("height").getAsInt()!=H||json.get("depth").getAsInt()!=D)throw new IllegalStateException("Generator breaker manifest size");
            var c=json.getAsJsonArray("controller_cell");
            controller=c.get(0).getAsInt()+W*(c.get(2).getAsInt()+D*c.get(1).getAsInt());
            for(var entry:json.getAsJsonArray("cells")){
                var cell=entry.getAsJsonObject();int n=cell.get("index").getAsInt();var b=cell.getAsJsonArray("bounds");
                if(b.isEmpty())continue;
                double x=b.get(0).getAsDouble(),y=b.get(1).getAsDouble(),z=b.get(2).getAsDouble();
                double X=b.get(3).getAsDouble(),Y=b.get(4).getAsDouble(),Z=b.get(5).getAsDouble();
                SHAPES[Direction.NORTH.get2DDataValue()][n]=Shapes.box(x,y,z,X,Y,Z);
                SHAPES[Direction.EAST.get2DDataValue()][n]=Shapes.box(1-Z,y,x,1-z,Y,X);
                SHAPES[Direction.SOUTH.get2DDataValue()][n]=Shapes.box(1-X,y,1-Z,1-x,Y,1-z);
                SHAPES[Direction.WEST.get2DDataValue()][n]=Shapes.box(z,y,1-X,Z,Y,1-x);
            }
        } catch(java.io.IOException ex){throw new IllegalStateException("Cannot load the generator breaker model",ex);}
        CONTROLLER=controller;
    }

    public GridTieBlock(Properties properties){
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));
        // Old saves have none of the properties: the defaults are the single cube they were placed as.
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(CELL,CONTROLLER).setValue(ASSEMBLED,false).setValue(CLOSED,false));
    }
    @Override protected MapCodec<GridTieBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING,CELL,ASSEMBLED,CLOSED);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    /** New placements are the full model. */
    public BlockState placementState(){return defaultBlockState().setValue(ASSEMBLED,true);}

    private static BlockPos offset(int cell){return new BlockPos(cell%W-CONTROLLER%W,cell/(W*D)-CONTROLLER/(W*D),(cell/W)%D-(CONTROLLER/W)%D);}
    public static BlockPos cellPos(BlockPos root,Direction facing,int cell){return root.offset(TurbineAssemblyBlock.turn(offset(cell),facing));}
    public BlockPos origin(BlockPos pos,BlockState s){
        return s.getValue(ASSEMBLED)?pos.subtract(TurbineAssemblyBlock.turn(offset(s.getValue(CELL)),s.getValue(FACING))):pos;
    }
    /** Every cell of the tie that {@code s} (any of its cells, or a saved single block) belongs to. */
    public List<BlockPos> cells(BlockPos pos,BlockState s){
        var root=origin(pos,s);var out=new ArrayList<BlockPos>();
        if(!s.getValue(ASSEMBLED)){out.add(root);return out;}
        for(int i=0;i<CELLS;i++)out.add(cellPos(root,s.getValue(FACING),i));
        return out;
    }
    private boolean owned(BlockState actual,BlockState s,int cell){
        return actual.is(this)&&actual.getValue(ASSEMBLED)==s.getValue(ASSEMBLED)&&actual.getValue(CELL)==cell&&actual.getValue(FACING)==s.getValue(FACING);
    }
    /** The block entity that runs the tie this cell belongs to, or null if it is not loaded or no longer ours. Never loads a chunk. */
    public GridTieBlockEntity controller(Level level,BlockPos pos,BlockState s){
        var root=origin(pos,s);
        if(s.getValue(ASSEMBLED)){
            var rootState=LoadedWorld.state(level,root);
            if(rootState==null||!owned(rootState,s,CONTROLLER))return null;
        }
        return LoadedWorld.blockEntity(level,root) instanceof GridTieBlockEntity tie&&!tie.isRemoved()?tie:null;
    }
    private boolean complete(Level level,BlockPos root,BlockState s){
        for(int i=0;i<CELLS;i++){
            var actual=LoadedWorld.state(level,cellPos(root,s.getValue(FACING),i));
            if(actual==null||!owned(actual,s,i))return false;
        }
        return true;
    }
    /**
     * Sets the closed flag on every loaded cell that is ours and differs, with a client update only (no neighbour updates).
     * Returns false if a cell was not loaded, so the caller tries again later.
     */
    boolean showClosed(Level level,BlockPos root,BlockState s,boolean closed){
        boolean all=true;
        for(int i=0;i<CELLS;i++){
            var p=cellPos(root,s.getValue(FACING),i);var actual=LoadedWorld.state(level,p);
            if(actual==null){all=false;continue;}
            if(owned(actual,s,i)&&actual.getValue(CLOSED)!=closed)level.setBlock(p,actual.setValue(CLOSED,closed),Block.UPDATE_CLIENTS);
        }
        return all;
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){
        return !state.getValue(ASSEMBLED)||state.getValue(CELL)==CONTROLLER?new GridTieBlockEntity(pos,state):null;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx){
        var s=placementState().setValue(FACING,ctx.getHorizontalDirection().getOpposite());
        if(fits(ctx.getLevel(),ctx.getPlayer(),ctx.getClickedPos(),s,false))return s;
        if(!ctx.getLevel().isClientSide()&&ctx.getPlayer()!=null)
            ctx.getPlayer().displayClientMessage(dev.bwr.mod.text.BwrText.tr("status.bwr.pump_assembly.no_room_for_the_full_assembly","Not enough room for the full assembly here"),true);
        return null;
    }
    /** Whether all 105 cells of {@code s} rooted at {@code root} are free to build; {@code ownRoot}: the root cell is already ours. */
    private boolean fits(Level level,LivingEntity actor,BlockPos root,BlockState s,boolean ownRoot){
        for(int i=0;i<CELLS;i++){
            if(ownRoot&&i==CONTROLLER)continue;
            var p=cellPos(root,s.getValue(FACING),i);
            if(!AssemblyAccess.permitted(level,actor,p)||!level.isLoaded(p)||level.isOutsideBuildHeight(p)||!level.getWorldBorder().isWithinBounds(p)
                    ||!level.getBlockState(p).canBeReplaced()||!level.isUnobstructed(s.setValue(CELL,i),p,CollisionContext.empty()))return false;
        }
        return true;
    }
    @Override public void setPlacedBy(Level level,BlockPos root,BlockState s,LivingEntity who,ItemStack stack){
        if(!s.getValue(ASSEMBLED)&&!level.isClientSide()){
            // A placer that sets the default state (the single block old saves keep) without asking getStateForPlacement: build the
            // full tie facing away from it, or give the item back.
            var full=placementState().setValue(FACING,who!=null?who.getDirection().getOpposite():s.getValue(FACING));
            if(!fits(level,who,root,full,true)){AssemblyAccess.refusePlacement(level,root,who,stack);return;}
            level.setBlock(root,full,3);
            s=full;
        }
        if(s.getValue(ASSEMBLED)&&s.getValue(CELL)==CONTROLLER){
            for(int i=0;i<CELLS;i++)if(i!=CONTROLLER){
                var p=cellPos(root,s.getValue(FACING),i);
                if(!AssemblyAccess.permitted(level,who,p)||!level.isLoaded(p)||!level.getBlockState(p).canBeReplaced()){AssemblyAccess.refusePlacement(level,root,who,stack);return;}
            }
            for(int i=0;i<CELLS;i++)if(i!=CONTROLLER&&!level.setBlock(cellPos(root,s.getValue(FACING),i),s.setValue(CELL,i),3)){AssemblyAccess.refusePlacement(level,root,who,stack);return;}
            for(int i=0;i<CELLS;i++)level.invalidateCapabilities(cellPos(root,s.getValue(FACING),i));
        }
        super.setPlacedBy(level,root,s,who,stack);
        if(s.getValue(CELL)==CONTROLLER)GridItems.restore(level,root,stack);
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext c){
        return s.getValue(ASSEMBLED)?SHAPES[s.getValue(FACING).get2DDataValue()][s.getValue(CELL)]:Shapes.block();
    }
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}

    @Override protected void onPlace(BlockState s,Level level,BlockPos pos,BlockState old,boolean moving){
        super.onPlace(s,level,pos,old,moving);
        if(!old.is(this))level.invalidateCapabilities(pos);
        if(!level.isClientSide()&&s.getValue(ASSEMBLED))level.scheduleTick(pos,this,20);
    }
    /** Unload recovery: every cell checks that the controller still owns it, the controller that the whole footprint is still there. */
    @Override protected void tick(BlockState s,ServerLevel level,BlockPos pos,RandomSource random){
        var root=origin(pos,s);var rootState=LoadedWorld.state(level,root);
        if(rootState!=null&&!owned(rootState,s,CONTROLLER)){level.removeBlock(pos,false);return;}
        if(s.getValue(CELL)==CONTROLLER&&AssemblyAccess.allLoaded(level,pos,s)&&!complete(level,root,s)){level.destroyBlock(root,true);return;}
        level.scheduleTick(pos,this,40);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState s,Player player){
        if(!level.isClientSide()&&s.getValue(ASSEMBLED)&&s.getValue(CELL)!=CONTROLLER&&!player.isCreative()&&player.hasCorrectToolForDrops(s)){
            var root=origin(pos,s);var rootState=LoadedWorld.state(level,root);
            if(rootState!=null&&owned(rootState,s,CONTROLLER))Block.dropResources(rootState,level,root,level.getBlockEntity(root),player,player.getMainHandItem());
        }
        return super.playerWillDestroy(level,pos,s,player);
    }
    @Override protected void onRemove(BlockState s,Level level,BlockPos pos,BlockState replacement,boolean moving){
        super.onRemove(s,level,pos,replacement,moving);
        // Child cells have no block entity, so NeoForge cannot invalidate their forwarded capabilities for us.
        if(!level.isClientSide()&&!replacement.is(this))level.invalidateCapabilities(pos);
        if(replacement.is(this)||level.isClientSide()||!s.getValue(ASSEMBLED)||REMOVING.get())return;
        REMOVING.set(true);
        try{
            var root=origin(pos,s);
            for(int i=0;i<CELLS;i++){
                var p=cellPos(root,s.getValue(FACING),i);var actual=LoadedWorld.state(level,p);
                if(!p.equals(pos)&&actual!=null&&owned(actual,s,i))level.removeBlock(p,false);
            }
        }finally{REMOVING.set(false);}
    }
    @Override protected java.util.List<ItemStack> getDrops(BlockState state,net.minecraft.world.level.storage.loot.LootParams.Builder params){return GridItems.retain(super.getDrops(state,params),params);}
}
