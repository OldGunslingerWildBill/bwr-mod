package dev.bwr.mod.suppression;

import dev.bwr.mod.text.BwrText;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Shell-and-tube RHR heat exchanger, four physically separate flanges: tube side (RHR water) red in / amber out, shell side
 * (cooling water) blue in / cyan out.
 *
 * <p>Two forms share the id {@code bwr:rhr_heat_exchanger}:
 * <ul>
 *   <li>{@code assembled=false}: the single block every exchanger placed before alpha.55 is. A saved state has no
 *   {@code assembled} or {@code cell} property, so it loads as this default state, keeps its alpha.54 model, its four flange
 *   faces (tube side on the facing axis, shell in counter-clockwise, shell out clockwise) and its block entity, and is never
 *   expanded through its neighbours;</li>
 *   <li>{@code assembled=true}: the full-size vertical unit, {@link #LAYOUT} (3 x 8 x 3), that one item now places. The
 *   controller cell at the centre of the base holds the one block entity; every other cell addresses it. The flanges are on
 *   four specific cells, one face each, centred on that block face.</li>
 * </ul>
 * Picking up a single block and placing the item again builds the full-size unit, as the modern pumps do.
 */
public class RhrHeatExchangerBlock extends BaseEntityBlock implements ProcessAssembly {
    public enum HxPort { TUBE_IN, TUBE_OUT, SHELL_IN, SHELL_OUT }
    public static final DirectionProperty FACING=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING; // the same property as HorizontalDirectionalBlock.FACING
    // A state saved without these properties loads with their defaults: the single block, in its own (controller) cell.
    public static final BooleanProperty ASSEMBLED=BooleanProperty.create("assembled");
    public static final IntegerProperty CELL=IntegerProperty.create("cell",0,71);
    public static final ExchangerLayout LAYOUT=new ExchangerLayout("rhr_heat_exchanger");
    private static final ThreadLocal<Boolean> REMOVING=ThreadLocal.withInitial(()->false);
    private static final VoxelShape SINGLE=Shapes.block();

    public RhrHeatExchangerBlock(Properties p){
        super(p.pushReaction(PushReaction.BLOCK));
        if(LAYOUT.count()!=CELL.getPossibleValues().size())throw new IllegalStateException("Exchanger cell property does not match its layout");
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(ASSEMBLED,false).setValue(CELL,LAYOUT.controller));
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moving) {
        super.onPlace(state,level,pos,old,moving);
        if(state!=old)dev.bwr.mod.piping.PipeTopology.changed(level,pos);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return simpleCodec(RhrHeatExchangerBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING,ASSEMBLED,CELL);}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.MODEL;}

    // ---- form and cells

    public static boolean isAssembled(BlockState s){return s.getValue(ASSEMBLED);}
    /** True for the one cell that carries the block entity: a single block, or the assembly's controller cell. */
    public static boolean isController(BlockState s){return !isAssembled(s)||s.getValue(CELL)==LAYOUT.controller;}
    /** The full-size unit a new placement builds, controller cell, facing north. */
    public BlockState placementState(){return defaultBlockState().setValue(ASSEMBLED,true).setValue(CELL,LAYOUT.controller);}
    /** World position of {@code cell} of the assembly whose controller is at {@code root}. */
    public static BlockPos cellPos(BlockPos root,BlockState s,int cell){return LAYOUT.world(root,s.getValue(FACING),cell);}
    private static boolean owned(BlockState actual,BlockState s,int cell){
        return actual.getBlock() instanceof RhrHeatExchangerBlock&&actual.getValue(ASSEMBLED)&&actual.getValue(CELL)==cell&&actual.getValue(FACING)==s.getValue(FACING);
    }
    /** The controller's block entity of the assembly {@code pos} belongs to (the block itself for a single block), or null. */
    public static RhrHeatExchangerBlockEntity controller(Level level,BlockPos pos,BlockState s){
        BlockPos root=rootOf(pos,s);
        if(!level.isLoaded(root))return null;
        if(isAssembled(s)&&!owned(level.getBlockState(root),s,LAYOUT.controller))return null;
        return level.getBlockEntity(root) instanceof RhrHeatExchangerBlockEntity be&&!be.isRemoved()?be:null;
    }
    /** The controller (or single block) position of the exchanger {@code pos} belongs to. */
    public static BlockPos rootOf(BlockPos pos,BlockState s){
        return isAssembled(s)?pos.subtract(dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(LAYOUT.offset(s.getValue(CELL)),s.getValue(FACING))):pos;
    }

    // ---- ports

    /** The flange on {@code face} of this cell, or null. A single block keeps its alpha.54 faces. */
    public static HxPort portOf(BlockState s,Direction face){
        if(face==null||!face.getAxis().isHorizontal())return null;
        if(!isAssembled(s)){
            if(face==primaryIn(s))return HxPort.TUBE_IN;
            if(face==primaryOut(s))return HxPort.TUBE_OUT;
            if(face==coldIn(s))return HxPort.SHELL_IN;
            if(face==hotOut(s))return HxPort.SHELL_OUT;
            return null;
        }
        BlockPos here=LAYOUT.offset(s.getValue(CELL));
        for(var p:LAYOUT.ports)
            if(p.cell().equals(here)&&dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(p.face(),s.getValue(FACING))==face)return p.role();
        return null;
    }
    /** World cell carrying {@code role}, for the controller (or single block) at {@code root}. */
    public static BlockPos portCell(BlockPos root,BlockState s,HxPort role){
        return isAssembled(s)?root.offset(dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(LAYOUT.port(role).cell(),s.getValue(FACING))):root;
    }
    /** Outward face of {@code role}. The faces are the same in both forms; only the cells differ. */
    public static Direction portFace(BlockState s,HxPort role){
        return switch(role){case TUBE_IN->primaryIn(s);case TUBE_OUT->primaryOut(s);case SHELL_IN->coldIn(s);case SHELL_OUT->hotOut(s);};
    }
    public static Direction primaryIn(BlockState s){return s.getValue(FACING);}
    public static Direction primaryOut(BlockState s){return primaryIn(s).getOpposite();}
    public static Direction coldIn(BlockState s){return primaryIn(s).getCounterClockWise();}
    public static Direction hotOut(BlockState s){return primaryIn(s).getClockWise();}
    /** A water pipe may join this cell on {@code face}: any horizontal face of a single block (as before), a flange face of the assembly. */
    public static boolean acceptsWaterLineOn(BlockState s,Direction face){
        return isAssembled(s)?portOf(s,face)!=null:face.getAxis().isHorizontal();
    }

    // ---- ProcessAssembly: the tube side is the RHR circuit the pump traces

    @Override public BlockPos origin(BlockPos p,BlockState s){return rootOf(p,s);}
    @Override public boolean hasPort(AssemblyPort role){return !role.isSteam();}
    @Override public BlockPos portPosition(BlockPos p,BlockState s,AssemblyPort role){return portCell(p,s,role==AssemblyPort.WATER_DISCHARGE?HxPort.TUBE_OUT:HxPort.TUBE_IN);}
    @Override public Direction portFace(BlockState s,AssemblyPort role){return role==AssemblyPort.WATER_DISCHARGE?primaryOut(s):primaryIn(s);}
    @Override public AssemblyPort portAt(BlockState s,Direction face){
        var port=portOf(s,face);
        return port==HxPort.TUBE_IN?AssemblyPort.WATER_SUCTION:port==HxPort.TUBE_OUT?AssemblyPort.WATER_DISCHARGE:null;
    }
    @Override public boolean complete(Level l,BlockPos root,BlockState s){
        if(!isAssembled(s))return l.isLoaded(root)&&l.getBlockState(root)==s;
        // Read through each cell's chunk (a footprint is in one to four), never loading one.
        int[] turned=LAYOUT.turned(s.getValue(FACING));
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        for(int i=0;i<LAYOUT.count();i++){
            cursor.set(root.getX()+turned[3*i],root.getY()+turned[3*i+1],root.getZ()+turned[3*i+2]);
            if(l.isOutsideBuildHeight(cursor)||!l.isLoaded(cursor)||!owned(l.getBlockState(cursor),s,i))return false;
        }
        return true;
    }

    // ---- block entity: only the controller (or the single block) has one

    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return isController(s)?new RhrHeatExchangerBlockEntity(p,s):null;}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> t){
        return l.isClientSide()||!isController(s)?null:dev.bwr.mod.TickProbe.wrap(createTickerHelper(t,BwrBlockEntities.RHR_HEAT_EXCHANGER.get(),RhrHeatExchangerBlockEntity::serverTick));
    }

    // ---- placement and removal

    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        BlockState s=placementState().setValue(FACING,c.getHorizontalDirection().getOpposite());
        Level level=c.getLevel();
        for(int i=0;i<LAYOUT.count();i++){
            BlockPos p=cellPos(c.getClickedPos(),s,i);
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(level,c.getPlayer(),p)||!level.isLoaded(p)||level.isOutsideBuildHeight(p)
                    ||!level.getWorldBorder().isWithinBounds(p)||!level.getBlockState(p).canBeReplaced()
                    ||!level.isUnobstructed(s.setValue(CELL,i),p,CollisionContext.empty()))return null;
        }
        return s;
    }
    @Override public void setPlacedBy(Level level,BlockPos root,BlockState s,LivingEntity placer,ItemStack stack){
        super.setPlacedBy(level,root,s,placer,stack);
        if(level.isClientSide()||!isAssembled(s)||s.getValue(CELL)!=LAYOUT.controller)return;
        for(int i=0;i<LAYOUT.count();i++)if(i!=LAYOUT.controller){
            BlockPos p=cellPos(root,s,i);
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(level,placer,p)||!level.isLoaded(p)||!level.getBlockState(p).canBeReplaced()){level.removeBlock(root,false);return;}
        }
        for(int i=0;i<LAYOUT.count();i++)if(i!=LAYOUT.controller){
            if(!level.setBlock(cellPos(root,s,i),s.setValue(CELL,i),3)){level.removeBlock(root,false);return;}
        }
        for(int i=0;i<LAYOUT.count();i++)level.invalidateCapabilities(cellPos(root,s,i));
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState s,Player player){
        // A part cell hands out the controller's one drop, so breaking any cell returns the item.
        if(!level.isClientSide()&&isAssembled(s)&&!isController(s)&&!player.isCreative()&&player.hasCorrectToolForDrops(s)){
            BlockPos root=origin(pos,s);
            if(level.isLoaded(root)&&owned(level.getBlockState(root),s,LAYOUT.controller))
                Block.dropResources(level.getBlockState(root),level,root,level.getBlockEntity(root),player,player.getMainHandItem());
        }
        return super.playerWillDestroy(level,pos,s,player);
    }
    @Override protected void onRemove(BlockState s,Level level,BlockPos pos,BlockState replacement,boolean moving){
        super.onRemove(s,level,pos,replacement,moving);
        // Part cells have no block entity, so NeoForge cannot invalidate their forwarded capabilities for us.
        if(!level.isClientSide()&&!s.equals(replacement))level.invalidateCapabilities(pos);
        if(replacement.is(this)||level.isClientSide()||!isAssembled(s)||REMOVING.get())return;
        REMOVING.set(true);
        try{
            BlockPos root=origin(pos,s);
            for(int i=0;i<LAYOUT.count();i++){
                BlockPos p=cellPos(root,s,i);
                if(!p.equals(pos)&&level.isLoaded(p)&&owned(level.getBlockState(p),s,i))level.removeBlock(p,false);
            }
        }finally{REMOVING.set(false);}
    }
    /** A part cell whose controller is gone (for example after /setblock) removes itself; nothing else changes. */
    @Override protected void neighborChanged(BlockState s,Level level,BlockPos pos,Block block,BlockPos changed,boolean moving){
        super.neighborChanged(s,level,pos,block,changed,moving);
        if(level.isClientSide()||!isAssembled(s)||isController(s)||REMOVING.get())return;
        BlockPos root=origin(pos,s);
        if(level.isLoaded(root)&&!owned(level.getBlockState(root),s,LAYOUT.controller))level.removeBlock(pos,false);
    }
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext c){
        return isAssembled(s)?LAYOUT.shape(s.getValue(FACING),s.getValue(CELL)):SINGLE;
    }

    // ---- use

    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player player,BlockHitResult hit){
        if(!l.isClientSide()&&player instanceof ServerPlayer sp){
            var be=controller(l,p,s);
            if(be!=null)dev.bwr.mod.gui.RhrHeatExchangerMenu.open(sp,be);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.Item.TooltipContext context,java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag){
        lines.add(BwrText.tr(
                "status.bwr.rhr_heat_exchanger.pool_water_red_in_amber_out_use_an",
                "Tube side (RHR pool water): red IN / amber OUT. Use an RHR pump."));
        lines.add(BwrText.tr(
                "status.bwr.rhr_heat_exchanger.cooling_water_blue_in_cyan_out_separate_circuit",
                "Shell side (river cooling water): blue IN / cyan OUT. Separate circuit."));
        lines.add(BwrText.tr("status.bwr.rhr_heat_exchanger.up_to_27_1_mw_requires_both_water", "Up to 27.1 MW; requires both water circuits."));
        lines.add(BwrText.tr("status.bwr.rhr_heat_exchanger.full_size_footprint",
                "Places a full-size vertical unit, 3 wide x 8 high x 3 deep, centred on the clicked block. Leave it all clear."));
        super.appendHoverText(stack,context,lines,flag);
    }
}
