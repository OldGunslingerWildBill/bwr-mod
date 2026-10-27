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
    public enum Kind { SLC, SLC_TANK, LPCS, RHR, HPCS, MOTOR_FEED, TURBINE_FEED, JET, RIP, RCP, HP_TURBINE, LP_TURBINE, GENERATOR }
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
        private final int[][] turned=new int[4][];
        /** Every cell's offset from the controller, already turned to {@code facing}, as x, y, z triples. */
        int[] turned(Direction facing) {
            int k=facing.get2DDataValue();
            int[] t=turned[k];
            if(t==null) {
                t=new int[count()*3];
                for(int i=0;i<count();i++) {
                    BlockPos o=TurbineAssemblyBlock.turn(offset(i),facing);
                    t[3*i]=o.getX();t[3*i+1]=o.getY();t[3*i+2]=o.getZ();
                }
                turned[k]=t;
            }
            return t;
        }
    }
    protected static Layout loadLayout(String id) { return new Layout(id); }
    protected Layout layout(BlockState state) { return model; }
    /** New placements use the current model; saved states may select an older footprint. */
    public BlockState placementState() { return defaultBlockState().setValue(ASSEMBLED,true); }
    public PumpAssemblyBlock(Properties properties, Kind kind) {
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));
        this.kind=kind;
        String id=switch(kind) {
            case SLC -> "slc_pump"; case SLC_TANK -> "slc_boron_tank"; case LPCS -> "lpcs_pump"; case RHR -> "rhr_pump"; case HPCS -> "hpcs_pump";
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
        // The answer is the same as asking every cell, one position at a time, whether it is loaded and ours. It is
        // asked of the cells' chunks instead (a footprint is in one to four of them) and their states are read from
        // those chunks directly, with offsets turned once per layout and facing instead of per cell and per call.
        // Panels, cables and the pump itself ask this many times a second; it used to cost tens of microseconds each.
        Layout layout=layout(s);
        int[] turned=layout.turned(s.getValue(FACING));
        int count=layout.count();
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        long chunkKey=Long.MIN_VALUE;
        boolean chunkLoaded=false;
        net.minecraft.world.level.chunk.LevelChunk chunk=null;
        for(int i=0;i<count;i++) {
            int x=root.getX()+turned[3*i],y=root.getY()+turned[3*i+1],z=root.getZ()+turned[3*i+2];
            cursor.set(x,y,z);
            if(level.isOutsideBuildHeight(cursor)) return false;
            long key=net.minecraft.world.level.ChunkPos.asLong(x>>4,z>>4);
            if(key!=chunkKey) {
                chunkKey=key;
                chunkLoaded=level.isLoaded(cursor);
                chunk=chunkLoaded?level.getChunkSource().getChunkNow(x>>4,z>>4):null;
            }
            if(!chunkLoaded) return false;
            // A chunk that is loaded by ticket but not finished (no chunk object yet) is read the old way, which waits.
            BlockState actual=chunk!=null?chunk.getBlockState(cursor):level.getBlockState(cursor);
            if(!owned(actual,s,i)) return false;
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
            case SLC,LPCS,RHR,HPCS -> new EccsPumpBlockEntity(pos,s);
            case MOTOR_FEED,TURBINE_FEED -> new FeedwaterPumpBlockEntity(pos,s);
            case RIP,RCP -> new RecirculationPumpBlockEntity(pos,s);
            case SLC_TANK -> new SlcTankBlockEntity(pos,s);
            case JET,HP_TURBINE,LP_TURBINE,GENERATOR -> null;
        };
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState s,BlockEntityType<T> type) {
        if(level.isClientSide() || s.getValue(CELL)!=controllerCell(s)) return null;
        return switch(kind) {
            case SLC,LPCS,RHR,HPCS -> dev.bwr.mod.TickProbe.wrap(createTickerHelper(type,BwrBlockEntities.ECCS_PUMP.get(),EccsPumpBlockEntity::serverTick));
            case MOTOR_FEED,TURBINE_FEED -> dev.bwr.mod.TickProbe.wrap(createTickerHelper(type,BwrBlockEntities.FEEDWATER_PUMP.get(),FeedwaterPumpBlockEntity::serverTick));
            case RIP,RCP -> dev.bwr.mod.TickProbe.wrap(createTickerHelper(type,BwrBlockEntities.RECIRCULATION_PUMP.get(),RecirculationPumpBlockEntity::serverTick));
            case SLC_TANK,JET,HP_TURBINE,LP_TURBINE,GENERATOR -> null;
        };
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState s=placementState().setValue(FACING,ctx.getHorizontalDirection().getOpposite());
        return fits(ctx.getLevel(),ctx.getPlayer(),ctx.getClickedPos(),s,false)?s:null;
    }
    /** Whether the full footprint of {@code s} rooted at {@code root} is free to build; {@code ownRoot}: the root cell is already ours. */
    private boolean fits(Level level,LivingEntity actor,BlockPos root,BlockState s,boolean ownRoot) {
        for(int i=0;i<cellCount(s);i++) {
            if(ownRoot && i==controllerCell(s)) continue;
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(level,actor,p)||!level.isLoaded(p) || level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p)
                    || !level.getBlockState(p).canBeReplaced() || !level.isUnobstructed(s.setValue(CELL,i),p,CollisionContext.empty())) return false;
        }
        return true;
    }
    @Override public void setPlacedBy(Level level,BlockPos root,BlockState s,LivingEntity placer,ItemStack stack) {
        if(!isFull(s) && !level.isClientSide()) {
            // A mod that places the block's default state, the one-cell compact form old saves keep, without asking
            // getStateForPlacement, and still calls this: build the full assembly, or give the item back. (Effortless 3.4.0
            // does ask, through BlockItem.getPlacementState; this is for any placer that does not.)
            BlockState full=placementState().setValue(FACING,placer!=null?placer.getDirection().getOpposite():s.getValue(FACING));
            if(!fits(level,placer,root,full,true)) { refuse(level,root,placer,stack); return; }
            level.setBlock(root,full,3);
            s=full;
        }
        if(!isFull(s) || s.getValue(CELL)!=controllerCell(s)) return;
        // Effortless's Array, Mirror and Radial patterns place a copy of the full state at a position whose footprint
        // getStateForPlacement never saw, and take the item once the block is down; so a refusal here gives it back too.
        for(int i=0;i<cellCount(s);i++) if(i!=controllerCell(s)) {
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!dev.bwr.mod.world.AssemblyAccess.permitted(level,placer,p) || !level.isLoaded(p) || !level.getBlockState(p).canBeReplaced()) { refuse(level,root,placer,stack); return; }
        }
        for(int i=0;i<cellCount(s);i++) if(i!=controllerCell(s)) {
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING)));
            if(!level.setBlock(p,s.setValue(CELL,i),3)) { refuse(level,root,placer,stack); return; }
        }
        for(int i=0;i<cellCount(s);i++) level.invalidateCapabilities(root.offset(TurbineAssemblyBlock.turn(cellOffset(s,i),s.getValue(FACING))));
        neighborChanged(s,level,root,this,root,false);
    }
    /**
     * The placement did not fit: the block goes and the placer gets the item back (vanilla and Effortless both take it after
     * setPlacedBy). Not through popResource, which the doTileDrops rule silences; into a player's inventory, since a building
     * mod reaches far. Nothing for a creative player, who was not charged.
     */
    private void refuse(Level level,BlockPos root,LivingEntity placer,ItemStack stack) { dev.bwr.mod.world.AssemblyAccess.refusePlacement(level,root,placer,stack); }
    @Override protected void onPlace(BlockState s,Level level,BlockPos pos,BlockState old,boolean moving) {
        super.onPlace(s,level,pos,old,moving);
        if(!level.isClientSide() && isFull(s)) level.scheduleTick(pos,this,20);
    }
    @Override protected void tick(BlockState s,ServerLevel level,BlockPos pos,RandomSource random) {
        long probe=dev.bwr.mod.TickProbe.start();
        try {
            BlockPos root=origin(pos,s);
            if(level.isLoaded(root) && !owned(level.getBlockState(root),s,controllerCell(s))) { level.removeBlock(pos,false); return; }
            // Every cell of an assembly has its own scheduled tick, so the whole-footprint check used to run once per
            // cell: N cells read N blocks each, N squared reads every two seconds per pump. The controller cell checks
            // the footprint once, as TurbineAssemblyBlock does; the others only check that the controller still owns them.
            if(s.getValue(CELL)==controllerCell(s) && dev.bwr.mod.world.AssemblyAccess.allLoaded(level,pos,s) && !complete(level,root,s)) { level.destroyBlock(root,true); return; }
            level.scheduleTick(pos,this,40);
        } finally {
            dev.bwr.mod.TickProbe.stop("PumpAssemblyBlock.tick (scheduled, every cell)", probe);
        }
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
        if(kind==Kind.SLC_TANK && player instanceof ServerPlayer sp) dev.bwr.mod.gui.ServiceMenu.open(sp,pos);
        else if (kind != Kind.JET && player instanceof ServerPlayer sp)
            dev.bwr.mod.gui.PumpControlMenu.open(sp, origin(pos,s), pos);
        return InteractionResult.CONSUME;
    }
    @Override protected BlockState rotate(BlockState s,Rotation r) { return s.setValue(FACING,r.rotate(s.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter level,BlockPos pos,CollisionContext c) {
        return isFull(s) ? layout(s).shapes[s.getValue(FACING).get2DDataValue()][s.getValue(CELL)] : Shapes.box(.05,.05,.05,.95,.95,.95);
    }
}
