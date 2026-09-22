package dev.bwr.mod.steam;

import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import dev.bwr.mod.registry.BwrBlockEntities;
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
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Three-block spring-return valve. Steam and the one actuator simulation live in the base. */
public class MainSteamIsolationValveBlock extends BaseEntityBlock implements SteamLinePort {
    public static final MapCodec<MainSteamIsolationValveBlock> CODEC = simpleCodec(MainSteamIsolationValveBlock::new);
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 2);
    private static final VoxelShape[][] SHAPES = loadShapes();
    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);

    public MainSteamIsolationValveBlock(Properties properties) {
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK));
        // Old saves have only OPEN. Keep their occupied volume and six connections intact.
        registerDefaultState(stateDefinition.any().setValue(OPEN, true).setValue(ASSEMBLED, false)
                .setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    private static VoxelShape[][] loadShapes() {
        var shapes = new VoxelShape[4][3];
        try (var stream = MainSteamIsolationValveBlock.class.getResourceAsStream("/data/bwr/pump_models/msiv.json")) {
            if (stream == null) throw new IllegalStateException("Missing MSIV model manifest");
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : json.getAsJsonArray("cells")) {
                var cell = entry.getAsJsonObject(); int part = cell.get("index").getAsInt();
                var b = cell.getAsJsonArray("bounds");
                double x=b.get(0).getAsDouble(), y=b.get(1).getAsDouble(), z=b.get(2).getAsDouble();
                double X=b.get(3).getAsDouble(), Y=b.get(4).getAsDouble(), Z=b.get(5).getAsDouble();
                shapes[Direction.NORTH.get2DDataValue()][part]=Shapes.box(x,y,z,X,Y,Z);
                shapes[Direction.EAST.get2DDataValue()][part]=Shapes.box(1-Z,y,x,1-z,Y,X);
                shapes[Direction.SOUTH.get2DDataValue()][part]=Shapes.box(1-X,y,1-Z,1-x,Y,1-z);
                shapes[Direction.WEST.get2DDataValue()][part]=Shapes.box(z,y,1-X,Z,Y,1-x);
            }
        } catch (java.io.IOException ex) { throw new IllegalStateException("Cannot load MSIV model", ex); }
        return shapes;
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(OPEN, ASSEMBLED, FACING, PART); }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    public BlockState placementState() { return defaultBlockState().setValue(ASSEMBLED, true); }
    public BlockPos origin(BlockPos p, BlockState s) { return p.below(s.getValue(PART)); }
    private boolean owned(BlockState actual, BlockState s, int part) {
        return actual.is(this) && actual.getValue(PART)==part && actual.getValue(ASSEMBLED)==s.getValue(ASSEMBLED)
                && actual.getValue(FACING)==s.getValue(FACING);
    }

    @Override public boolean acceptsSteamLineOn(BlockState s, Direction face) {
        return !s.getValue(ASSEMBLED) || s.getValue(PART)==0 && face.getAxis()==s.getValue(FACING).getAxis();
    }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) {
        return s.getValue(PART)==0 ? new MainSteamIsolationValveBlockEntity(p,s) : null;
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> type) {
        return l.isClientSide() || s.getValue(PART)!=0 ? null
                : createTickerHelper(type, BwrBlockEntities.MSIV.get(), MainSteamIsolationValveBlockEntity::serverTick);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        var s=placementState().setValue(FACING,ctx.getHorizontalDirection().getOpposite());
        for(int i=0;i<3;i++) {
            var p=ctx.getClickedPos().above(i);
            if(!ctx.getLevel().isLoaded(p) || ctx.getLevel().isOutsideBuildHeight(p) || !ctx.getLevel().getWorldBorder().isWithinBounds(p)
                    || !ctx.getLevel().getBlockState(p).canBeReplaced()
                    || !ctx.getLevel().isUnobstructed(s.setValue(PART,i),p,CollisionContext.empty())) return null;
        }
        return s;
    }
    @Override public void setPlacedBy(Level l, BlockPos p, BlockState s, LivingEntity who, ItemStack stack) {
        if(!s.getValue(ASSEMBLED) || s.getValue(PART)!=0) return;
        for(int i=1;i<3;i++) if(!l.isLoaded(p.above(i)) || !l.getBlockState(p.above(i)).canBeReplaced()) {
            l.removeBlock(p,false); return;
        }
        for(int i=1;i<3;i++) if(!l.setBlock(p.above(i),s.setValue(PART,i),Block.UPDATE_ALL)) {
            l.removeBlock(p,false); return;
        }
        neighborChanged(s,l,p,this,p,false);
    }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(ASSEMBLED) ? SHAPES[s.getValue(FACING).get2DDataValue()][s.getValue(PART)] : Shapes.block();
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING,r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }

    /** Redstone at any part reaches the base actuator; Lua retains explicit ownership. */
    @Override protected void neighborChanged(BlockState s, Level l, BlockPos p, Block neighbor, BlockPos changed, boolean moved) {
        if(l.isClientSide()) return;
        var root=origin(p,s);
        if(!l.isLoaded(root) || !owned(l.getBlockState(root),s,0)) return;
        if(l.getBlockEntity(root) instanceof MainSteamIsolationValveBlockEntity be && !be.isComputerControlled()) {
            boolean open=true;
            for(int i=0;i<(s.getValue(ASSEMBLED)?3:1);i++) if(l.hasNeighborSignal(root.above(i))) open=false;
            if(open!=be.isDemandOpen()) {
                be.setDemandOpen(open);
                l.setBlock(root,l.getBlockState(root).setValue(OPEN,open),Block.UPDATE_CLIENTS);
            }
        }
    }
    @Override protected void onPlace(BlockState s, Level l, BlockPos p, BlockState old, boolean moving) {
        super.onPlace(s,l,p,old,moving);
        if(!l.isClientSide() && s.getValue(ASSEMBLED)) l.scheduleTick(p,this,20);
    }
    @Override protected void tick(BlockState s, ServerLevel l, BlockPos p, RandomSource random) {
        var root=origin(p,s);
        for(int i=0;i<3;i++) if(l.isLoaded(root.above(i)) && !owned(l.getBlockState(root.above(i)),s,i)) {
            l.removeBlock(p,false); return;
        }
        l.scheduleTick(p,this,40);
    }
    @Override public BlockState playerWillDestroy(Level l, BlockPos p, BlockState s, Player player) {
        if(!l.isClientSide() && s.getValue(PART)!=0 && !player.isCreative() && player.hasCorrectToolForDrops(s)) {
            var root=origin(p,s);
            if(l.isLoaded(root) && owned(l.getBlockState(root),s,0))
                Block.dropResources(l.getBlockState(root),l,root,l.getBlockEntity(root),player,player.getMainHandItem());
        }
        return super.playerWillDestroy(l,p,s,player);
    }
    @Override protected void onRemove(BlockState s, Level l, BlockPos p, BlockState replacement, boolean moving) {
        super.onRemove(s,l,p,replacement,moving);
        if(l.isClientSide() || replacement.is(this) || !s.getValue(ASSEMBLED) || REMOVING.get()) return;
        REMOVING.set(true);
        try {
            var root=origin(p,s);
            for(int i=0;i<3;i++) if(!p.equals(root.above(i)) && l.isLoaded(root.above(i)) && owned(l.getBlockState(root.above(i)),s,i))
                l.removeBlock(root.above(i),false);
        } finally { REMOVING.set(false); }
    }
}
