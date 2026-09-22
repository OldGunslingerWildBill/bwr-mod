package dev.bwr.mod.eccs;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.eccs.EccsDesign;
import dev.bwr.mod.steam.SteamLinePort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;

/** One placeable item reserves the CAD assembly's volume; only cell zero owns physics. */
public final class TurbineAssemblyBlock extends EccsPumpBlock implements SteamLinePort, ProcessAssembly {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final IntegerProperty CELL = IntegerProperty.create("cell", 0, 59);
    public static final MapCodec<TurbineAssemblyBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), Codec.BOOL.fieldOf("hpci").forGetter(b -> b.hpci)
    ).apply(i, TurbineAssemblyBlock::new));
    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);
    private final boolean hpci;
    private final EnumMap<AssemblyPort, BlockPos> ports = new EnumMap<>(AssemblyPort.class);
    private volatile VoxelShape[][] shapes;

    public TurbineAssemblyBlock(Properties properties, boolean hpci) {
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK), hpci ? EccsDesign.HPCI : EccsDesign.RCIC);
        this.hpci = hpci;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CELL, 0));
        if (hpci) {
            ports.put(AssemblyPort.STEAM_INLET, new BlockPos(2, 4, 1));
            ports.put(AssemblyPort.STEAM_EXHAUST, new BlockPos(0, 1, 1));
            // Grid adapters for the associated HPCI pump; not nozzles claimed to exist in the turbine STEP.
            ports.put(AssemblyPort.WATER_SUCTION, new BlockPos(3, 0, 0));
            ports.put(AssemblyPort.WATER_DISCHARGE, new BlockPos(3, 0, 2));
        } else {
            ports.put(AssemblyPort.STEAM_INLET, new BlockPos(0, 2, 1));
            ports.put(AssemblyPort.STEAM_EXHAUST, new BlockPos(1, 1, 1));
            ports.put(AssemblyPort.WATER_SUCTION, new BlockPos(2, 1, 1));
            ports.put(AssemblyPort.WATER_DISCHARGE, new BlockPos(2, 2, 1));
        }
    }

    @Override protected MapCodec<TurbineAssemblyBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, CELL); }
    public boolean isHpci() { return hpci; }
    public int width() { return hpci ? 4 : 3; }
    public int depth() { return hpci ? 3 : 2; }
    public int height() { return hpci ? 5 : 3; }
    public int cellCount() { return width() * depth() * height(); }
    public BlockPos cellOffset(int cell) { return new BlockPos(cell % width(), cell / (width() * depth()), (cell / width()) % depth()); }
    public static BlockPos turn(BlockPos p, Direction facing) {
        return switch (facing) {
            case EAST -> new BlockPos(-p.getZ(), p.getY(), p.getX());
            case SOUTH -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
            case WEST -> new BlockPos(p.getZ(), p.getY(), -p.getX());
            default -> p;
        };
    }
    public static Direction turn(Direction d, Direction facing) {
        if (d.getAxis().isVertical()) return d;
        return switch (facing) { case EAST -> d.getClockWise(); case SOUTH -> d.getOpposite(); case WEST -> d.getCounterClockWise(); default -> d; };
    }
    public BlockPos origin(BlockPos pos, BlockState state) { return pos.subtract(turn(cellOffset(state.getValue(CELL)), state.getValue(FACING))); }
    public boolean hasPort(AssemblyPort port) { return ports.containsKey(port); }
    public BlockPos portPosition(BlockPos root, BlockState state, AssemblyPort port) {
        if (!hasPort(port)) throw new IllegalArgumentException("No " + port + " on this exterior");
        return root.offset(turn(ports.get(port), state.getValue(FACING)));
    }
    public Direction portFace(BlockState state, AssemblyPort port) {
        Direction d = switch (port) {
            case STEAM_INLET -> Direction.UP;
            case WATER_DISCHARGE -> hpci ? Direction.EAST : Direction.UP;
            case WATER_SUCTION -> Direction.EAST;
            case STEAM_EXHAUST -> hpci ? Direction.WEST : Direction.SOUTH;
        };
        return turn(d, state.getValue(FACING));
    }
    public AssemblyPort portAt(BlockState state, Direction face) {
        BlockPos local = cellOffset(state.getValue(CELL));
        for (var e : ports.entrySet()) if (e.getValue().equals(local) && portFace(state, e.getKey()) == face) return e.getKey();
        return null;
    }
    @Override public boolean acceptsSteamLineOn(BlockState state, Direction face) { var port = portAt(state, face); return port != null && port.isSteam(); }
    public boolean complete(Level level, BlockPos root, BlockState state) {
        for (int i = 0; i < cellCount(); i++) {
            BlockPos p = root.offset(turn(cellOffset(i), state.getValue(FACING)));
            if (!level.isLoaded(p)) return false;
            BlockState actual = level.getBlockState(p);
            if (!actual.is(this) || actual.getValue(CELL) != i || actual.getValue(FACING) != state.getValue(FACING)) return false;
        }
        return true;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(CELL) == 0 ? super.newBlockEntity(pos, state) : null;
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return state.getValue(CELL) == 0 ? super.getTicker(level, state, type) : null;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        Level level = ctx.getLevel();
        for (int i = 0; i < cellCount(); i++) {
            BlockPos p = ctx.getClickedPos().offset(turn(cellOffset(i), state.getValue(FACING)));
            if (!dev.bwr.mod.world.AssemblyAccess.permitted(level,ctx.getPlayer(),p) || !level.isLoaded(p) || !level.getWorldBorder().isWithinBounds(p) || level.isOutsideBuildHeight(p)
                    || !level.getBlockState(p).canBeReplaced() || !level.isUnobstructed(state.setValue(CELL, i), p, CollisionContext.empty())) return null;
        }
        return state;
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (state.getValue(CELL) != 0) return;
        for (int i = 1; i < cellCount(); i++) {
            BlockPos p = pos.offset(turn(cellOffset(i), state.getValue(FACING)));
            if (!dev.bwr.mod.world.AssemblyAccess.permitted(level,placer,p) || !level.isLoaded(p) || !level.getBlockState(p).canBeReplaced()) {
                level.removeBlock(pos, false);
                return;
            }
        }
        for (int i = 1; i < cellCount(); i++) {
            BlockPos p = pos.offset(turn(cellOffset(i), state.getValue(FACING)));
            if (!level.setBlock(p, state.setValue(CELL, i), 3)) { level.removeBlock(pos, false); return; }
        }
        super.setPlacedBy(level, pos, state, placer, stack);
        for(int i=0;i<cellCount();i++) level.invalidateCapabilities(pos.offset(turn(cellOffset(i),state.getValue(FACING))));
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide()) level.scheduleTick(pos, this, 20);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos root = origin(pos, state);
        if (level.isLoaded(root)) {
            BlockState owner = level.getBlockState(root);
            if (!owner.is(this) || owner.getValue(CELL) != 0 || owner.getValue(FACING) != state.getValue(FACING)) {
                level.removeBlock(pos, false); return;
            }
        }
        // Saved scheduled ticks clean orphan parts after their chunk is loaded again.
        if(dev.bwr.mod.world.AssemblyAccess.allLoaded(level,pos,state) && !complete(level,root,state)) { level.destroyBlock(root,true); return; }
        level.scheduleTick(pos, this, 40);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && state.getValue(CELL) != 0 && !player.isCreative() && player.hasCorrectToolForDrops(state)) {
            BlockPos root = origin(pos, state);
            if (level.isLoaded(root) && level.getBlockState(root).is(this))
                Block.dropResources(level.getBlockState(root), level, root, level.getBlockEntity(root), player, player.getMainHandItem());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        super.onRemove(state, level, pos, replacement, moving);
        if(!level.isClientSide() && !state.equals(replacement)) level.invalidateCapabilities(pos);
        if (replacement.is(this) || level.isClientSide() || REMOVING.get()) return;
        REMOVING.set(true);
        try {
            BlockPos root = origin(pos, state);
            for (int i = 0; i < cellCount(); i++) {
                BlockPos p = root.offset(turn(cellOffset(i), state.getValue(FACING)));
                if (p.equals(pos) || !level.isLoaded(p)) continue;
                BlockState part = level.getBlockState(p);
                if (part.is(this) && part.getValue(CELL) == i && part.getValue(FACING) == state.getValue(FACING)) level.removeBlock(p, false);
            }
        } finally { REMOVING.set(false); }
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos changed, boolean moving) {
        if (level.isClientSide()) return;
        BlockPos root = origin(pos, state);
        if (!level.isLoaded(root) || !(level.getBlockEntity(root) instanceof EccsPumpBlockEntity pump)) return;
        pump.markBindingDirty();
        if (state.getValue(CELL) == 0 && !pump.isComputerControlled() && !pump.isPanelControlled()) pump.setRunning(level.hasNeighborSignal(root));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof net.minecraft.server.level.ServerPlayer sp)
            dev.bwr.mod.gui.PumpControlMenu.open(sp, origin(pos,state), pos);
        return InteractionResult.CONSUME;
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }

    /** Precomputed per-cell CAD envelopes keep collisions inside the occupied block. */
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (shapes == null) loadShapes();
        return shapes[state.getValue(FACING).get2DDataValue()][state.getValue(CELL)];
    }
    private synchronized void loadShapes() {
        if (shapes != null) return;
        VoxelShape[][] loaded = new VoxelShape[4][60];
        for (var row : loaded) java.util.Arrays.fill(row, Shapes.empty());
        String id = hpci ? "hpci_turbine" : "rcic_twl";
        try (var stream = TurbineAssemblyBlock.class.getResourceAsStream("/data/bwr/turbine_models/" + id + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing turbine collision manifest: " + id);
            var cells = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("cells");
            for (var element : cells) {
                var cell = element.getAsJsonObject(); int index = cell.get("index").getAsInt();
                var a = cell.getAsJsonArray("bounds"); if (a.size() == 0) continue;
                double x0=a.get(0).getAsDouble(), y0=a.get(1).getAsDouble(), z0=a.get(2).getAsDouble();
                double x1=a.get(3).getAsDouble(), y1=a.get(4).getAsDouble(), z1=a.get(5).getAsDouble();
                loaded[Direction.NORTH.get2DDataValue()][index] = Shapes.box(x0,y0,z0,x1,y1,z1);
                loaded[Direction.EAST.get2DDataValue()][index] = Shapes.box(1-z1,y0,x0,1-z0,y1,x1);
                loaded[Direction.SOUTH.get2DDataValue()][index] = Shapes.box(1-x1,y0,1-z1,1-x0,y1,1-z0);
                loaded[Direction.WEST.get2DDataValue()][index] = Shapes.box(z0,y0,1-x1,z1,y1,1-x0);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read turbine collision manifest",e); }
        shapes = loaded;
    }
}
