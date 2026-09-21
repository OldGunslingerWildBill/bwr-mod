package dev.bwr.mod.eccs;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.steam.SteamLinePort;
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
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One item and one simulation per full-sized pump, including the hanging RIP. */
public class PumpAssemblyBlock extends BaseEntityBlock implements SteamLinePort, ProcessAssembly {
    public enum Kind { LPCS, RHR, HPCS, MOTOR_FEED, TURBINE_FEED, JET, RIP, RCP, HP_TURBINE, LP_TURBINE, GENERATOR }
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty CELL = IntegerProperty.create("cell",0,255);
    // Missing property on an old save intentionally loads a compact model in its original cell.
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");
    public static final MapCodec<PumpAssemblyBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), Codec.STRING.fieldOf("kind").forGetter(b -> b.kind.name())
    ).apply(i,(p,k) -> new PumpAssemblyBlock(p,Kind.valueOf(k))));
    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);
    private final Kind kind;
    private final Layout model;
    public record Port(AssemblyPort role, BlockPos cell, Direction face) {}
    protected static final class Layout {
        final int width,height,depth,controller;
        final List<Port> ports = new ArrayList<>();
        final VoxelShape[][] shapes = new VoxelShape[4][256];
        Layout(String id) {
            for(var row:shapes) Arrays.fill(row,Shapes.empty());
            try(var stream=PumpAssemblyBlock.class.getResourceAsStream("/data/bwr/pump_models/"+id+".json")) {
                if(stream==null) throw new IllegalStateException("Missing pump manifest "+id);
                var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
                width=json.get("width").getAsInt(); height=json.get("height").getAsInt(); depth=json.get("depth").getAsInt();
                var c=json.getAsJsonArray("controller_cell");
                controller=c.get(0).getAsInt()+width*(c.get(2).getAsInt()+depth*c.get(1).getAsInt());
                for(var e:json.getAsJsonArray("game_ports")) {
                    var p=e.getAsJsonObject(); var a=p.getAsJsonArray("cell");
                    ports.add(new Port(AssemblyPort.valueOf(p.get("role").getAsString()),
                            new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()).subtract(grid(controller)),
                            Direction.byName(p.get("face").getAsString())));
                }
                for(var e:json.getAsJsonArray("cells")) {
                    var c0=e.getAsJsonObject(); int n=c0.get("index").getAsInt(); var a=c0.getAsJsonArray("bounds");
                    if(a.isEmpty()) continue;
                    double x0=a.get(0).getAsDouble(),y0=a.get(1).getAsDouble(),z0=a.get(2).getAsDouble();
                    double x1=a.get(3).getAsDouble(),y1=a.get(4).getAsDouble(),z1=a.get(5).getAsDouble();
                    shapes[Direction.NORTH.get2DDataValue()][n]=Shapes.box(x0,y0,z0,x1,y1,z1);
                    shapes[Direction.EAST.get2DDataValue()][n]=Shapes.box(1-z1,y0,x0,1-z0,y1,x1);
                    shapes[Direction.SOUTH.get2DDataValue()][n]=Shapes.box(1-x1,y0,1-z1,1-x0,y1,1-z0);
                    shapes[Direction.WEST.get2DDataValue()][n]=Shapes.box(z0,y0,1-x1,z1,y1,1-x0);
                }
            } catch(java.io.IOException ex) { throw new IllegalStateException("Cannot load "+id,ex); }
        }
        BlockPos grid(int cell) { return new BlockPos(cell%width,cell/(width*depth),(cell/width)%depth); }
        BlockPos offset(int cell) { return grid(cell).subtract(grid(controller)); }
        int count() { return width*height*depth; }
    }
    protected static Layout loadLayout(String id) { return new Layout(id); }
    protected Layout layout(BlockState state) { return model; }
    /** New placements use the current model; saved states may select an older footprint. */
    public BlockState placementState() { return defaultBlockState().setValue(ASSEMBLED,true); }
    public PumpAssemblyBlock(Properties properties, Kind kind) {
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));
        this.kind=kind;
        String id=switch(kind) {
            case LPCS -> "lpcs_pump"; case RHR -> "rhr_pump"; case HPCS -> "hpcs_pump";
            case MOTOR_FEED -> "motor_feed_pump"; case TURBINE_FEED -> "turbine_feed_pump";
            case JET -> "jet_pump"; case RIP -> "rip_pump"; case RCP -> "recirculation_pump";
            case HP_TURBINE -> "hp_turbine"; case LP_TURBINE -> "lp_turbine"; case GENERATOR -> "nuclear_generator";
        };
        model=loadLayout(id);
        registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(CELL,model.controller).setValue(ASSEMBLED,false));
    }
    public Kind kind() { return kind; }
    public int controllerCell() { return model.controller; }
    public int controllerCell(BlockState s) { return layout(s).controller; }
    public int cellCount() { return model.count(); }
    public int cellCount(BlockState s) { return layout(s).count(); }
    public BlockPos cellOffset(int cell) { return model.offset(cell); }
    public BlockPos cellOffset(BlockState s,int cell) { return layout(s).offset(cell); }
    public List<Port> ports() { return List.copyOf(model.ports); }
    public List<Port> ports(BlockState s) { return List.copyOf(layout(s).ports); }
    public boolean isFull(BlockState s) { return s.getValue(ASSEMBLED); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(FACING,CELL,ASSEMBLED); }
    @Override public BlockPos origin(BlockPos pos,BlockState s) { return isFull(s) ? pos.subtract(TurbineAssemblyBlock.turn(cellOffset(s,s.getValue(CELL)),s.getValue(FACING))) : pos; }
    @Override public boolean hasPort(AssemblyPort role) { return model.ports.stream().anyMatch(p -> p.role()==role); }
    @Override public BlockPos portPosition(BlockPos root,BlockState s,AssemblyPort role) {
        return root.offset(TurbineAssemblyBlock.turn(layout(s).ports.stream().filter(p -> p.role()==role).findFirst().orElseThrow().cell(),s.getValue(FACING)));
    }
    @Override public Direction portFace(BlockState s,AssemblyPort role) {
        return TurbineAssemblyBlock.turn(layout(s).ports.stream().filter(p -> p.role()==role).findFirst().orElseThrow().face(),s.getValue(FACING));
    }
    @Override public AssemblyPort portAt(BlockState s,Direction face) {
        if(!isFull(s)) return null;
        for(var p:layout(s).ports) if(p.cell().equals(cellOffset(s,s.getValue(CELL))) && TurbineAssemblyBlock.turn(p.face(),s.getValue(FACING))==face) return p.role();
        return null;
    }
    @Override public boolean acceptsSteamLineOn(BlockState s,Direction d) { var port=portAt(s,d); return port!=null && port.isSteam(); }
    @Override public boolean complete(Level level,BlockPos root,BlockState s) {
        if(!isFull(s)) return true;
        for(int i=0;i<cellCount(s);i++) {
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!level.isLoaded(p) || !owned(level.getBlockState(p),s,i)) return false;
        }
        return true;
    }
    protected boolean owned(BlockState actual,BlockState s,int i) {
        return actual.is(this) && actual.getValue(CELL)==i && actual.getValue(FACING)==s.getValue(FACING) && actual.getValue(ASSEMBLED)==s.getValue(ASSEMBLED);
    }
    public BlockEntity controller(Level level,BlockPos pos,BlockState s) {
        BlockPos root=origin(pos,s);
        return level.isLoaded(root) && owned(level.getBlockState(root),s,controllerCell(s)) ? level.getBlockEntity(root) : null;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState s) {
        if(s.getValue(CELL)!=controllerCell(s)) return null;
        return switch(kind) {
            case LPCS,RHR,HPCS -> new EccsPumpBlockEntity(pos,s);
            case MOTOR_FEED,TURBINE_FEED -> new FeedwaterPumpBlockEntity(pos,s);
            case RIP,RCP -> new RecirculationPumpBlockEntity(pos,s);
            case JET,HP_TURBINE,LP_TURBINE,GENERATOR -> null;
        };
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState s,BlockEntityType<T> type) {
        if(level.isClientSide() || s.getValue(CELL)!=controllerCell(s)) return null;
        return switch(kind) {
            case LPCS,RHR,HPCS -> createTickerHelper(type,BwrBlockEntities.ECCS_PUMP.get(),EccsPumpBlockEntity::serverTick);
            case MOTOR_FEED,TURBINE_FEED -> createTickerHelper(type,BwrBlockEntities.FEEDWATER_PUMP.get(),FeedwaterPumpBlockEntity::serverTick);
            case RIP,RCP -> createTickerHelper(type,BwrBlockEntities.RECIRCULATION_PUMP.get(),RecirculationPumpBlockEntity::serverTick);
            case JET,HP_TURBINE,LP_TURBINE,GENERATOR -> null;
        };
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s=placementState().setValue(FACING,ctx.getHorizontalDirection().getOpposite());
        for(int i=0;i<cellCount(s);i++) {
            BlockPos p=ctx.getClickedPos().offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!ctx.getLevel().isLoaded(p) || ctx.getLevel().isOutsideBuildHeight(p) || !ctx.getLevel().getWorldBorder().isWithinBounds(p)
                    || !ctx.getLevel().getBlockState(p).canBeReplaced() || !ctx.getLevel().isUnobstructed(s.setValue(CELL,i),p,CollisionContext.empty())) return null;
        }
        return s;
    }
    @Override public void setPlacedBy(Level level,BlockPos root,BlockState s,LivingEntity placer,ItemStack stack) {
        if(!isFull(s) || s.getValue(CELL)!=controllerCell(s)) return;
        for(int i=0;i<cellCount(s);i++) if(i!=controllerCell(s)) {
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!level.isLoaded(p) || !level.getBlockState(p).canBeReplaced()) { level.removeBlock(root,false); return; }
        }
        for(int i=0;i<cellCount(s);i++) if(i!=controllerCell(s)) {
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!level.setBlock(p,s.setValue(CELL,i),3)) { level.removeBlock(root,false); return; }
        }
        for(int i=0;i<cellCount(s);i++) level.invalidateCapabilities(root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING))));
        neighborChanged(s,level,root,this,root,false);
    }
    @Override protected void onPlace(BlockState s,Level level,BlockPos pos,BlockState old,boolean moving) {
        super.onPlace(s,level,pos,old,moving);
        if(!level.isClientSide() && isFull(s)) level.scheduleTick(pos,this,20);
    }
    @Override protected void tick(BlockState s,ServerLevel level,BlockPos pos,RandomSource random) {
        BlockPos root=origin(pos,s);
        if(level.isLoaded(root) && !owned(level.getBlockState(root),s,controllerCell(s))) { level.removeBlock(pos,false); return; }
        level.scheduleTick(pos,this,40);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState s,Player player) {
        if(!level.isClientSide() && s.getValue(CELL)!=controllerCell(s) && !player.isCreative() && player.hasCorrectToolForDrops(s)) {
            BlockPos root=origin(pos,s);
            if(level.isLoaded(root) && owned(level.getBlockState(root),s,controllerCell(s))) Block.dropResources(level.getBlockState(root),level,root,level.getBlockEntity(root),player,player.getMainHandItem());
        }
        return super.playerWillDestroy(level,pos,s,player);
    }
    @Override protected void onRemove(BlockState s,Level level,BlockPos pos,BlockState replacement,boolean moving) {
        super.onRemove(s,level,pos,replacement,moving);
        // Child cells have no block entity, so NeoForge cannot invalidate their forwarded capabilities for us.
        if(!level.isClientSide() && !s.equals(replacement)) level.invalidateCapabilities(pos);
        if(replacement.is(this) || level.isClientSide() || !isFull(s) || REMOVING.get()) return;
        REMOVING.set(true);
        try {
            BlockPos root=origin(pos,s);
            for(int i=0;i<cellCount(s);i++) {
                BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
                if(!p.equals(pos) && level.isLoaded(p) && owned(level.getBlockState(p),s,i)) level.removeBlock(p,false);
            }
        } finally { REMOVING.set(false); }
    }
    @Override protected void neighborChanged(BlockState s,Level level,BlockPos pos,Block block,BlockPos changed,boolean moving) {
        if(level.isClientSide()) return;
        BlockEntity be=controller(level,pos,s);
        if(be instanceof EccsPumpBlockEntity p) { p.markBindingDirty(); if(!p.isComputerControlled() && !p.isPanelControlled()) p.setRunning(level.hasNeighborSignal(be.getBlockPos())); }
        if(be instanceof FeedwaterPumpBlockEntity p) { p.markBindingDirty(); if(!p.isComputerControlled() && !p.isPanelControlled()) p.setRunning(level.hasNeighborSignal(be.getBlockPos())); }
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(level.isClientSide()) return InteractionResult.SUCCESS;
        if (kind != Kind.JET && player instanceof ServerPlayer sp)
            dev.bwr.mod.gui.PumpControlMenu.open(sp, origin(pos,s), pos);
        return InteractionResult.CONSUME;
    }
    @Override protected BlockState rotate(BlockState s,Rotation r) { return s.setValue(FACING,r.rotate(s.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext c) {
        return isFull(s) ? layout(s).shapes[s.getValue(FACING).get2DDataValue()][s.getValue(CELL)] : Shapes.box(.05,.05,.05,.95,.95,.95);
    }
}
