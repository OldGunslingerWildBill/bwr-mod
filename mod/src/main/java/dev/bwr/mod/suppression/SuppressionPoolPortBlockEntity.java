package dev.bwr.mod.suppression;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No ticker: every access resolves the current, formed controller. */
public class SuppressionPoolPortBlockEntity extends BlockEntity {
    private BlockPos controller;
    public SuppressionPoolPortBlockEntity(BlockPos p,BlockState s){super(BwrBlockEntities.SUPPRESSION_POOL_PORT.get(),p,s);}
    public void bind(BlockPos p){if(!p.equals(controller)){controller=p.immutable();setChanged();}}
    public SuppressionPoolBlockEntity owner(){
        return level!=null&&!isRemoved()&&controller!=null&&level.isLoaded(controller)
                &&level.getBlockEntity(controller) instanceof SuppressionPoolBlockEntity pool&&pool.isFormed()&&pool.ownsPort(worldPosition)?pool:null;
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);if(controller!=null)t.putLong("Controller",controller.asLong());}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);controller=t.contains("Controller")?BlockPos.of(t.getLong("Controller")):null;}
}
