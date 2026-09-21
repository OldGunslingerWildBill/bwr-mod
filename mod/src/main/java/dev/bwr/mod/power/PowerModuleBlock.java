package dev.bwr.mod.power;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A single placeable object with two coaxial shaft couplings and isolated process ports. */
public class PowerModuleBlock extends PumpAssemblyBlock {
    public static final MapCodec<PowerModuleBlock> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
            propertiesCodec(),Codec.STRING.fieldOf("kind").forGetter(b->b.kind().name()))
            .apply(i,(p,k)->new PowerModuleBlock(p,Kind.valueOf(k))));
    public PowerModuleBlock(Properties properties,Kind kind) { super(properties,kind); }
    @Override protected MapCodec<PowerModuleBlock> codec() { return CODEC; }
    public boolean generator() { return kind()==Kind.GENERATOR; }
    public boolean highPressure() { return kind()==Kind.HP_TURBINE; }
    public int halfLength() { return kind()==Kind.LP_TURBINE?3:4; }
    public BlockPos shaft(BlockPos root,BlockState s,boolean front) {
        return root.offset(TurbineAssemblyBlock.turn(new BlockPos(0,2,front?-halfLength():halfLength()),s.getValue(FACING)));
    }
    public Direction shaftFace(BlockState s,boolean front) { return front?s.getValue(FACING):s.getValue(FACING).getOpposite(); }
    public BlockPos terminal(BlockPos root,BlockState s) { return root.offset(TurbineAssemblyBlock.turn(new BlockPos(2,1,0),s.getValue(FACING))); }
    public Direction terminalFace(BlockState s) { return TurbineAssemblyBlock.turn(Direction.EAST,s.getValue(FACING)); }
    public boolean energyFace(BlockPos pos,BlockState s,Direction side) { return generator() && side==terminalFace(s) && pos.equals(terminal(origin(pos,s),s)); }
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState s) { return s.getValue(CELL)==controllerCell(s)?new PowerModuleBlockEntity(pos,s):null; }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level,BlockState s,BlockEntityType<T> type) {
        return level.isClientSide()||s.getValue(CELL)!=controllerCell(s)?null:createTickerHelper(type,BwrBlockEntities.POWER_MODULE.get(),PowerModuleBlockEntity::serverTick);
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer sp)dev.bwr.mod.gui.PowerModuleMenu.open(sp,origin(pos,s),pos);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
