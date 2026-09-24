package dev.bwr.mod.eccs;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * The condensate storage tank block. Fill it from a fluid pipe or with a
 * bucket; emergency pumps set to tank suction drain it.
 */
public class CondensateStorageTankBlock extends BaseEntityBlock {

    public enum Port implements net.minecraft.util.StringRepresentable {NONE,NORTH,EAST,SOUTH,WEST;public String getSerializedName(){return name().toLowerCase(java.util.Locale.ROOT);}}
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty ASSEMBLED=net.minecraft.world.level.block.state.properties.BooleanProperty.create("assembled");
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty CONTROLLER=net.minecraft.world.level.block.state.properties.BooleanProperty.create("controller");
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Port> PORT=net.minecraft.world.level.block.state.properties.EnumProperty.create("port",Port.class);
    public static boolean acceptsWater(BlockState s,net.minecraft.core.Direction face){return !s.getValue(ASSEMBLED)||face!=null&&s.getValue(PORT)!=Port.NONE&&face.name().equals(s.getValue(PORT).name());}

    public static final MapCodec<CondensateStorageTankBlock> CODEC =
            simpleCodec(CondensateStorageTankBlock::new);

    public CondensateStorageTankBlock(Properties properties) {
        super(properties.noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(ASSEMBLED,false).setValue(CONTROLLER,true).setValue(PORT,Port.NONE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(ASSEMBLED)?RenderShape.ENTITYBLOCK_ANIMATED:RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CondensateStorageTankBlockEntity(pos, state);
    }
    @Override public void setPlacedBy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.LivingEntity who,ItemStack stack){
        super.setPlacedBy(level,pos,state,who,stack);
        if(who instanceof net.minecraft.server.level.ServerPlayer player)CondensateTankAssembly.tryAssemble(level,pos,player);
    }
    @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block,BlockState> b){b.add(ASSEMBLED,CONTROLLER,PORT);}
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s,net.minecraft.world.level.BlockGetter l,BlockPos p,net.minecraft.world.phys.shapes.CollisionContext c){
        if(s.getValue(ASSEMBLED)&&l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity be&&CondensateTankShape.valid(be.diameter,be.height))return CondensateTankShape.get(be.diameter,be.height).cells.getOrDefault(p.subtract(be.root()),net.minecraft.world.phys.shapes.Shapes.block());
        return net.minecraft.world.phys.shapes.Shapes.block();
    }
    @Override protected void onRemove(BlockState s,Level l,BlockPos p,BlockState next,boolean moving){
        boolean dismantle=!next.is(this)&&s.getValue(ASSEMBLED)&&!CondensateTankAssembly.EDITING.get();
        if(dismantle&&l instanceof net.minecraft.server.level.ServerLevel server&&l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part)
            CondensateTankDismantling.get(server).begin(server,part,p);
        // Bulk restoration removes unpaid collision cells too. Level.removeBlockEntity
        // performs the same unsafe two-block comparator scan as setChanged; these tank
        // cells have no comparator output, so remove their BE directly from the loaded chunk.
        if(CondensateTankAssembly.EDITING.get()&&!next.is(this))l.getChunkAt(p).removeBlockEntity(p);
        else super.onRemove(s,l,p,next,moving);
        l.invalidateCapabilities(p);
        if(dismantle&&l instanceof net.minecraft.server.level.ServerLevel server)CondensateTankDismantling.get(server).process(server);
    }
    @Override protected void tick(BlockState s,net.minecraft.server.level.ServerLevel l,BlockPos p,net.minecraft.util.RandomSource r){
        if(s.getValue(ASSEMBLED)&&l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part){
            if(!l.isLoaded(part.root())){l.scheduleTick(p,this,100);return;}
            if(part.owner()==null){
                CondensateTankDismantling.get(l).process(l);
                if(l.getBlockState(p).is(this)&&l.getBlockState(p).getValue(ASSEMBLED)){
                    // Legacy orphans have no remaining material ledger; the old controller may
                    // already have refunded them. Only a recorded dismantle restores casings.
                    l.setBlock(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),UPDATE_CLIENTS|UPDATE_KNOWN_SHAPE);
                }
                return;
            }
            if(s.getValue(CONTROLLER)){
                part.markStructureDirty();
                if(dev.bwr.mod.world.AssemblyAccess.allLoaded(l,p,s)&&!part.ready()){l.destroyBlock(p,true);return;}
            }
            l.scheduleTick(p,this,100);
        }
    }
    @Override protected java.util.List<ItemStack> getDrops(BlockState s,net.minecraft.world.level.storage.loot.LootParams.Builder params){
        if(!s.getValue(ASSEMBLED))return super.getDrops(s,params);
        var be=params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        if(!(be instanceof CondensateStorageTankBlockEntity part))return java.util.List.of();
        int count=part.getLevel() instanceof net.minecraft.server.level.ServerLevel server?CondensateTankDismantling.get(server).dropCount(part):part.breakCost;
        var result=new java.util.ArrayList<ItemStack>();while(count>0){int n=Math.min(64,count);result.add(new ItemStack(this,n));count-=n;}return result;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) dev.bwr.mod.gui.CondensateTankMenu.open(sp,pos);
        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof CondensateStorageTankBlockEntity be
                && FluidUtil.interactWithFluidHandler(player, hand, be.fluidHandler())) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }
}
