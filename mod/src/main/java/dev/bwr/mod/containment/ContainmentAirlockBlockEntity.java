package dev.bwr.mod.containment;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Interlock decisions live on the single root, and are saved across unloads. */
public final class ContainmentAirlockBlockEntity extends BlockEntity {
    private boolean innerOpen,outerOpen,override,computerControlled,damaged;
    private boolean redstoneHigh,nextOuter;
    public ContainmentAirlockBlockEntity(BlockPos pos,BlockState state){super(BwrBlockEntities.CONTAINMENT_AIRLOCK.get(),pos,state);}
    public boolean innerOpen(){return innerOpen;}
    public boolean outerOpen(){return outerOpen;}
    public boolean override(){return override;}
    public boolean damaged(){return damaged;}
    public boolean isComputerControlled(){return computerControlled;}
    public void setComputerControlled(boolean value){computerControlled=value;changed();}
    public boolean setInnerOpen(boolean value,boolean force){
        if(level==null||level.isClientSide()||damaged||value&&outerOpen&&!force)return false;
        if(value!=innerOpen){if(!value)nextOuter=true;innerOpen=value;syncCells();}return true;
    }
    public boolean setOuterOpen(boolean value,boolean force){
        if(level==null||level.isClientSide()||damaged||value&&innerOpen&&!force)return false;
        if(value!=outerOpen){if(!value)nextOuter=false;outerOpen=value;syncCells();}return true;
    }
    /** A rising redstone edge takes one safe step through the personnel passage. */
    public void redstoneSignal(boolean high){
        if(level==null||level.isClientSide())return;
        boolean changedSignal=high!=redstoneHigh;
        boolean rising=high&&!redstoneHigh;
        redstoneHigh=high;
        if(!rising||computerControlled||damaged){if(changedSignal)changed();return;}
        if(innerOpen&&outerOpen){
            innerOpen=false;outerOpen=false;nextOuter=false;syncCells();
        }else if(innerOpen)setInnerOpen(false,false);
        else if(outerOpen)setOuterOpen(false,false);
        else if(nextOuter)setOuterOpen(true,false);
        else setInnerOpen(true,false);
        changed();
    }
    /** Explicit computer override unseals both leaves. Releasing it leaves their positions unchanged. */
    public void setOverride(boolean forced){
        if(level==null||level.isClientSide())return;
        if(damaged)return;
        if(override==forced&&(!forced||innerOpen&&outerOpen))return;
        override=forced;
        if(forced){innerOpen=true;outerOpen=true;syncCells();}
        else changed();
    }
    public void jamBlownOpen(){
        if(level==null||level.isClientSide())return;
        damaged=true;override=true;innerOpen=true;outerOpen=true;syncCells();
    }
    private void syncCells(){
        var block=(ContainmentAirlockBlock)getBlockState().getBlock();
        for(int i=0;i<6;i++){
            BlockPos pos=block.cell(worldPosition,getBlockState().getValue(ContainmentAirlockBlock.FACING),i);
            if(!level.isLoaded(pos))continue;
            BlockState cell=level.getBlockState(pos);
            if(cell.is(block)&&cell.getValue(ContainmentAirlockBlock.CELL)==i){
                BlockState updated=cell.setValue(ContainmentAirlockBlock.INNER_OPEN,innerOpen)
                        .setValue(ContainmentAirlockBlock.OUTER_OPEN,outerOpen);
                if(updated!=cell)level.setBlock(pos,updated,Block.UPDATE_ALL);
            }
        }
        ContainmentControllerRegistry.changed(level,worldPosition);
        changed();
    }
    private void changed(){setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);
        tag.putBoolean("InnerOpen",innerOpen);tag.putBoolean("OuterOpen",outerOpen);
        tag.putBoolean("Override",override);tag.putBoolean("ComputerControlled",computerControlled);
        tag.putBoolean("Damaged",damaged);
        tag.putBoolean("RedstoneHigh",redstoneHigh);tag.putBoolean("NextOuter",nextOuter);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);
        innerOpen=tag.getBoolean("InnerOpen");outerOpen=tag.getBoolean("OuterOpen");
        override=tag.getBoolean("Override");computerControlled=tag.getBoolean("ComputerControlled");
        damaged=tag.getBoolean("Damaged");
        redstoneHigh=tag.getBoolean("RedstoneHigh");nextOuter=tag.getBoolean("NextOuter");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
