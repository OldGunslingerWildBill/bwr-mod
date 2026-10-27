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
    /**
     * An LP turbine's upper casing half is off (the turbine hall crane lifted the hood): its upper cells draw nothing and are walked
     * through, and the open casing's blades are drawn by the section's renderer. Default false, so saved states are unchanged; only LP
     * cells are ever set open.
     */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty OPEN=net.minecraft.world.level.block.state.properties.BooleanProperty.create("open");
    /** Cells at or above this height over an LP's floor are the hood. */
    public static final int HOOD_FROM_Y=3;
    public PowerModuleBlock(Properties properties,Kind kind) { super(properties,kind); registerDefaultState(defaultBlockState().setValue(OPEN,false)); }
    @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block,BlockState> b) { super.createBlockStateDefinition(b); b.add(OPEN); }
    /** True for an LP cell that is part of the hood. */
    public boolean hoodCell(BlockState s){return kind()==Kind.LP_TURBINE&&isFull(s)&&cellOffset(s,s.getValue(CELL)).getY()>=HOOD_FROM_Y;}
    /** Marks every cell of the LP at {@code root} open or closed (the hood off or on). Loaded cells only; flags 2, no neighbour updates. */
    public static void setOpen(Level level,BlockPos root,BlockState s,boolean open){
        if(!(s.getBlock() instanceof PowerModuleBlock b)||b.kind()!=Kind.LP_TURBINE||!b.isFull(s))return;
        for(int i=0;i<b.cellCount(s);i++){
            BlockPos p=root.offset(TurbineAssemblyBlock.turn(b.cellOffset(s,i),s.getValue(FACING)));
            if(!level.isLoaded(p))continue;
            BlockState c=level.getBlockState(p);
            if(c.getBlock()==b&&c.getValue(OPEN)!=open)level.setBlock(p,c.setValue(OPEN,open),2);
        }
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s,net.minecraft.world.level.BlockGetter level,BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext c) {
        return s.getValue(OPEN)&&hoodCell(s)?net.minecraft.world.phys.shapes.Shapes.empty():super.getShape(s,level,pos,c);
    }
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
        return level.isClientSide()||s.getValue(CELL)!=controllerCell(s)?null:dev.bwr.mod.TickProbe.wrap(createTickerHelper(type,BwrBlockEntities.POWER_MODULE.get(),PowerModuleBlockEntity::serverTick));
    }
    @Override protected InteractionResult useWithoutItem(BlockState s,Level level,BlockPos pos,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer sp)dev.bwr.mod.gui.PowerModuleMenu.open(sp,origin(pos,s),pos);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state,Level level,BlockPos pos,Player player,net.minecraft.world.InteractionHand hand,BlockHitResult hit){
        if(kind()==Kind.LP_TURBINE && hit.getDirection()==Direction.DOWN
                && (stack.getItem() instanceof dev.bwr.mod.condenser.CondenserItem
                    ||hand==net.minecraft.world.InteractionHand.MAIN_HAND&&stack.isEmpty()
                    &&player.getOffhandItem().getItem() instanceof dev.bwr.mod.condenser.CondenserItem))
            return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return super.useItemOn(stack,state,level,pos,player,hand,hit);
    }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.LivingEntity placer,net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level,pos,state,placer,stack);
        if(level.getBlockEntity(pos) instanceof PowerModuleBlockEntity module){
            module.establishIdentity();
            var data=stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
            if(data.contains("BwrPowerCondition"))module.restoreCondition(data.getCompound("BwrPowerCondition"));
            // A module a player places from a fresh item starts a train that needs its auxiliaries; one that comes from a
            // dismantled module keeps whatever that one was, so an old plant moved piece by piece stays an old plant.
            else if(placer instanceof Player&&dev.bwr.mod.config.GameplayConfig.turbineAuxRequired())module.markAuxRequired();
            adoptSkids(level,origin(pos,level.getBlockState(pos)));
        }
    }
    /** Skids that were waiting for this module (their neighbour updates came before its controller existed) attach now. */
    private static void adoptSkids(Level level,BlockPos root){
        for(var p:BlockPos.betweenClosed(root.offset(-6,-1,-6),root.offset(6,6,6)))
            if(level.getBlockState(p).getBlock() instanceof TurbineAuxBlock)TurbineAuxBlock.attach(level,p.immutable());
    }
    @Override protected java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        var drops=super.getDrops(state,params);
        if(params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY) instanceof PowerModuleBlockEntity module)
            for(var stack:drops)if(stack.is(asItem())){
                var data=stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                data.put("BwrPowerCondition",module.retainedCondition());
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));
            }
        return drops;
    }
}
