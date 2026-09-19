package dev.bwr.mod.flow;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** DVSS-inspired exterior; the original id, speed state and compact-save ports survive. */
public class RecirculationPumpBlock extends PumpAssemblyBlock {
    // Missing on existing saves: preserve the original 3x6x3 footprint and ports.
    public static final BooleanProperty ENLARGED=BooleanProperty.create("enlarged");
    public static final MapCodec<RecirculationPumpBlock> CODEC=simpleCodec(RecirculationPumpBlock::new);
    private final Layout legacy;
    public RecirculationPumpBlock(Properties properties) {
        super(properties,Kind.RCP);
        legacy=loadLayout("recirculation_pump_legacy");
        var saved=defaultBlockState().setValue(ENLARGED,false);
        registerDefaultState(saved.setValue(CELL,controllerCell(saved)));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) {
        super.createBlockStateDefinition(b);b.add(ENLARGED);
    }
    @Override protected Layout layout(BlockState s) { return s.getValue(ENLARGED)?super.layout(s):legacy; }
    @Override public BlockState placementState() {
        return super.placementState().setValue(ENLARGED,true).setValue(CELL,controllerCell());
    }
    @Override protected boolean owned(BlockState actual,BlockState s,int cell) {
        return super.owned(actual,s,cell) && actual.getValue(ENLARGED)==s.getValue(ENLARGED);
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public AssemblyPort portAt(BlockState state,Direction face) {
        if(isFull(state)) return super.portAt(state,face);
        var front=state.getValue(FACING);
        return face==front?AssemblyPort.WATER_DISCHARGE:face==front.getOpposite()?AssemblyPort.WATER_SUCTION:null;
    }
    public AssemblyPort waterPortAt(BlockState state,Direction face) { return portAt(state,face); }
    @Override public void appendHoverText(net.minecraft.world.item.ItemStack stack,net.minecraft.world.item.Item.TooltipContext context,
            java.util.List<net.minecraft.network.chat.Component> lines,net.minecraft.world.item.TooltipFlag flag) {
        for(String key:new String[]{"size","suction","discharge","drive"})lines.add(net.minecraft.network.chat.Component.translatable("tooltip.bwr.dvss."+key));
    }
    @Override public BlockPos portPosition(BlockPos root,BlockState state,AssemblyPort role) {
        return isFull(state)?super.portPosition(root,state,role):root;
    }
    @Override public Direction portFace(BlockState state,AssemblyPort role) {
        return isFull(state)?super.portFace(state,role):role==AssemblyPort.WATER_SUCTION?state.getValue(FACING).getOpposite():state.getValue(FACING);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving) {
        if(!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof RecirculationPumpBlockEntity pump
                && pump.getControllerPos()!=null && level.isLoaded(pump.getControllerPos())
                && level.getBlockEntity(pump.getControllerPos()) instanceof ReactorControllerBlockEntity owner) owner.removePump(pos);
        super.onRemove(state,level,pos,replacement,moving);
    }
}
