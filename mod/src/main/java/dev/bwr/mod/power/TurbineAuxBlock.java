package dev.bwr.mod.power;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * A turbine auxiliary skid, one block, set against the turbine-generator train it serves (TURBINE-AUX.md):
 * <ul>
 *   <li>{@link Kind#LUBE_OIL} the Main Oil Tank, against any section of the train: oil, an AC auxiliary pump (side and bottom faces), an
 *   emergency DC pump (top face) and the oil cooler (cooling water on its two flanges);</li>
 *   <li>{@link Kind#SHAFT_PUMP} the Shaft-Driven Main Oil Pump, bolted on the free shaft end of the generator;</li>
 *   <li>{@link Kind#GEN_COOLING} the Generator Cooling Skid, against the generator: stator water pumps, hydrogen, coolers;</li>
 *   <li>{@link Kind#OIL_FILTER} the Oil Filter Housing, against a Main Oil Tank (not against the train): one slot for an Oil Filter Cartridge.
 *   It has no host and no water or FE faces; the tank it stands against steps it (TURBINE-AUX.md).</li>
 * </ul>
 * Facing: the flanges of the tank and the cooling skid are on the left (cold in) and right (hot out) as seen from the front; the shaft
 * pump's coupling is on its back.
 */
public class TurbineAuxBlock extends BaseEntityBlock {
    public enum Kind { LUBE_OIL, SHAFT_PUMP, GEN_COOLING, OIL_FILTER }

    public static final MapCodec<TurbineAuxBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), Codec.STRING.fieldOf("kind").forGetter(b -> b.kind.name()))
            .apply(i, (p, k) -> new TurbineAuxBlock(p, Kind.valueOf(k))));
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private final Kind kind;

    public TurbineAuxBlock(Properties properties, Kind kind) {
        super(properties.noOcclusion());
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() { return kind; }
    @Override protected MapCodec<TurbineAuxBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return Shapes16.FULL; }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new TurbineAuxBlockEntity(p, s); }

    private static final class Shapes16 { static final VoxelShape FULL = Block.box(0, 0, 0, 16, 16, 16); }

    // ---- ports

    public boolean hasWater() { return kind == Kind.LUBE_OIL || kind == Kind.GEN_COOLING; }
    /** The flange cold cooling water comes in on: the left of the front. */
    public Direction coldInFace(BlockState s) { return s.getValue(FACING).getCounterClockWise(); }
    /** The flange the warmed water leaves on: the right of the front. */
    public Direction hotOutFace(BlockState s) { return s.getValue(FACING).getClockWise(); }

    public boolean acceptsWaterLineOn(BlockState s, Direction face) {
        return hasWater() && face != null && (face == coldInFace(s) || face == hotOutFace(s));
    }

    /**
     * The Oil Filter Housing's flow axis: oil comes in on the back (against the tank today, a Lube Oil Pipe later) and leaves on the front,
     * toward the turbine train. The model has a flange on each of the two faces.
     */
    public Direction oilInFace(BlockState s) { return s.getValue(FACING).getOpposite(); }
    public Direction oilOutFace(BlockState s) { return s.getValue(FACING); }

    /** The AC bus (tank pump, stator pumps): every face but the top and the two flanges. */
    public boolean acFace(BlockState s, Direction side) {
        return (kind == Kind.LUBE_OIL || kind == Kind.GEN_COOLING) && side != null && side != Direction.UP && side != coldInFace(s) && side != hotOutFace(s);
    }

    /** The DC bus of the tank's emergency pump: the top. */
    public boolean dcFace(BlockState s, Direction side) { return kind == Kind.LUBE_OIL && side == Direction.UP; }

    // ---- placement

    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        Direction facing = c.getHorizontalDirection().getOpposite();
        if (kind == Kind.SHAFT_PUMP) {
            // Against a generator's free shaft end the coupling (the back) faces the generator.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                var at = c.getClickedPos();
                if (generatorEndAt(c.getLevel(), at, d) != null) { facing = d.getOpposite(); break; }
            }
        }
        if (kind == Kind.OIL_FILTER) {
            // Back to a Main Oil Tank it stands against (the way a housing is bolted on); otherwise facing the player.
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (tankAt(c.getLevel(), c.getClickedPos().relative(d))) { facing = d.getOpposite(); break; }
            }
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    /** Whether {@code pos} holds a Main Oil Tank (never loads a chunk to look). */
    static boolean tankAt(Level level, BlockPos pos) {
        var s = dev.bwr.mod.world.LoadedWorld.state(level, pos);
        return s != null && s.getBlock() instanceof TurbineAuxBlock b && b.kind == Kind.LUBE_OIL;
    }

    /** The generator whose free shaft end is {@code pos}, looking from {@code pos} towards {@code d}; or null. */
    static PowerModuleBlockEntity generatorEndAt(Level level, BlockPos pos, Direction d) {
        BlockPos np = pos.relative(d);
        var s = dev.bwr.mod.world.LoadedWorld.state(level, np);
        if (s == null || !(s.getBlock() instanceof PowerModuleBlock b) || !b.generator()) return null;
        BlockPos root = b.origin(np, s);
        for (boolean front : new boolean[] {true, false})
            if (b.shaft(root, s, front).relative(b.shaftFace(s, front)).equals(pos) && b.shaftFace(s, front) == d.getOpposite())
                return b.controller(level, np, s) instanceof PowerModuleBlockEntity m ? m : null;
        return null;
    }

    /** The module this skid belongs against, if it is standing against one it may serve. */
    static PowerModuleBlockEntity find(Level level, BlockPos pos, Kind kind) {
        for (Direction d : Direction.values()) {
            if (kind == Kind.SHAFT_PUMP) {
                if (d.getAxis().isHorizontal() && generatorEndAt(level, pos, d) instanceof PowerModuleBlockEntity m) return m;
                continue;
            }
            BlockPos np = pos.relative(d);
            var s = dev.bwr.mod.world.LoadedWorld.state(level, np);
            if (s == null || !(s.getBlock() instanceof PowerModuleBlock b)) continue;
            if (kind == Kind.GEN_COOLING && !b.generator()) continue;
            if (b.controller(level, np, s) instanceof PowerModuleBlockEntity m) return m;
        }
        return null;
    }

    /** Attaches the skid at {@code pos} to the module it stands against, if it is not attached already. */
    public static void attach(Level level, BlockPos pos) {
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof TurbineAuxBlockEntity be)) return;
        if (be.kind() == Kind.OIL_FILTER) return; // a housing belongs to a tank, not to the train
        if (be.module() != null) return;
        var m = find(level, pos, be.kind());
        if (m != null) { m.attachAux(pos); be.setHost(m.getBlockPos()); }
        else if (be.host() != null && level.isLoaded(be.host())) be.setHost(null);
    }

    /** The block entity does not exist yet in onPlace, so a player's placement attaches here. */
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        attach(level, pos);
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (state == old) return;
        if (hasWater()) dev.bwr.mod.piping.PipeTopology.changed(level, pos);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!replacement.is(this) && level.getBlockEntity(pos) instanceof TurbineAuxBlockEntity be) {
            if (be.module() != null) be.module().detachAux(pos);
            if (kind == Kind.OIL_FILTER && !level.isClientSide()) be.dropCartridge();
        }
        super.onRemove(state, level, pos, replacement, moving);
        if (!replacement.is(this) && hasWater()) dev.bwr.mod.piping.PipeTopology.changed(level, pos);
    }

    /** A train section placed against a skid that was waiting for one picks it up. */
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos changed, boolean moving) {
        super.neighborChanged(state, level, pos, neighbor, changed, moving);
        attach(level, pos);
    }

    // ---- use

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp) {
            if (kind == Kind.OIL_FILTER) dev.bwr.mod.gui.OilFilterMenu.open(sp, pos);
            else dev.bwr.mod.gui.TurbineAuxMenu.open(sp, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                         InteractionHand hand, BlockHitResult hit) {
        if (kind == Kind.OIL_FILTER && stack.is(BwrItems.OIL_FILTER_CARTRIDGE.get())) {
            // A cartridge in hand goes straight into an empty slot; with one in already the screen is how it is changed.
            if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
            if (!(level.getBlockEntity(pos) instanceof TurbineAuxBlockEntity be)) return ItemInteractionResult.FAIL;
            if (be.hasCartridge()) {
                player.displayClientMessage(BwrText.tr("status.bwr.turbine_aux.filter_in", "There is a cartridge in the housing already. Open it to change it."), true);
                return ItemInteractionResult.CONSUME;
            }
            var one = stack.copyWithCount(1);
            be.cartridgeSlot().insertItem(0, one, false);
            if (!player.getAbilities().instabuild) stack.shrink(1);
            level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.8F);
            return ItemInteractionResult.CONSUME;
        }
        boolean oil = kind == Kind.LUBE_OIL && stack.is(BwrItems.TURBINE_OIL_DRUM.get());
        boolean h2 = kind == Kind.GEN_COOLING && stack.is(BwrItems.HYDROGEN_CYLINDER.get());
        if (!oil && !h2) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof TurbineAuxBlockEntity be)) return ItemInteractionResult.FAIL;
        // A drum or a cylinder is emptied whole: it is refused unless it fits, so no oil or gas is thrown away.
        boolean fits = oil ? be.oilLitres() + TurbineAuxBlockEntity.OIL_PER_DRUM_L <= dev.bwr.core.turbine.TurbineAux.OIL_TANK_L + 1e-9
                : be.h2Kg() + dev.bwr.core.turbine.TurbineAux.H2_CYLINDER_KG <= dev.bwr.core.turbine.TurbineAux.H2_FULL_KG + 1e-9;
        if (!fits) {
            player.displayClientMessage(BwrText.tr(oil ? "status.bwr.turbine_aux.tank_full" : "status.bwr.turbine_aux.h2_full",
                    oil ? "The tank has no room for a whole drum." : "The casing has no room for a whole cylinder."), true);
            return ItemInteractionResult.CONSUME;
        }
        if (oil) be.addOil(TurbineAuxBlockEntity.OIL_PER_DRUM_L); else be.addHydrogen(dev.bwr.core.turbine.TurbineAux.H2_CYLINDER_KG);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, oil ? 0.7F : 1.4F);
        return ItemInteractionResult.CONSUME;
    }

    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        switch (kind) {
            case LUBE_OIL -> {
                lines.add(BwrText.tr("tooltip.bwr.main_oil_tank.1", "Set against any section of the shaft train. Holds 20,000 L of turbine oil (right-click with Turbine Oil Drums)."));
                lines.add(BwrText.tr("tooltip.bwr.main_oil_tank.2", "AC auxiliary pump on the side and bottom faces, emergency DC pump on the top face (wire a battery). Cooling water on the two flanges."));
            }
            case OIL_FILTER -> {
                lines.add(BwrText.tr("tooltip.bwr.oil_filter_housing.1", "Bolts on a Main Oil Tank. A full-flow lube oil filter with a slot for one Oil Filter Cartridge."));
                lines.add(BwrText.tr("tooltip.bwr.oil_filter_housing.2", "A clean cartridge makes the tank's oil last ten times as long. With the slot empty, or the cartridge full, the oil goes round it."));
            }
            case SHAFT_PUMP -> lines.add(BwrText.tr("tooltip.bwr.shaft_oil_pump", "Bolts on the free shaft end of the generator, coupling toward it. Pumps oil in proportion to shaft speed."));
            case GEN_COOLING -> {
                lines.add(BwrText.tr("tooltip.bwr.generator_cooling_skid.1", "Set against the generator. Stator water pumps (FE on any face but the flanges), a hydrogen charge (Hydrogen Cylinders) and coolers on cooling water."));
                lines.add(BwrText.tr("tooltip.bwr.generator_cooling_skid.2", "Without cooling the winding heats with load; nothing trips. Cut the load."));
            }
        }
        super.appendHoverText(stack, context, lines, flag);
    }
}
