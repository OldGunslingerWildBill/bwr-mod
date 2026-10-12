package dev.bwr.mod.containment;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** One controller for the full vault leaf. No idle or per-cell ticker. */
public final class ContainmentVaultDoorBlockEntity extends BlockEntity {
    public enum State { CLOSED_SEALED, OPENING, OPEN, CLOSING, DAMAGED_JAMMED }
    private static final int FE_CAPACITY=20_000, FE_STROKE=8_000;
    private State state=State.CLOSED_SEALED;
    private int energyFe;
    private long strokeStart,strokeEnd;
    private boolean computerControlled;
    private final IEnergyStorage energy=new IEnergyStorage() {
        @Override public int receiveEnergy(int amount,boolean simulate){
            int accepted=Math.min(Math.max(0,amount),FE_CAPACITY-energyFe);
            if(!simulate&&accepted>0){energyFe+=accepted;setChanged();}
            return accepted;
        }
        @Override public int extractEnergy(int amount,boolean simulate){return 0;}
        @Override public int getEnergyStored(){return energyFe;}
        @Override public int getMaxEnergyStored(){return FE_CAPACITY;}
        @Override public boolean canExtract(){return false;}
        @Override public boolean canReceive(){return true;}
    };
    public ContainmentVaultDoorBlockEntity(BlockPos pos,BlockState state){
        super(BwrBlockEntities.CONTAINMENT_VAULT_DOOR.get(),pos,state);
    }
    public State state(){finishStroke();return state;}
    public IEnergyStorage energy(){return energy;}
    public boolean isComputerControlled(){return computerControlled;}
    public void setComputerControlled(boolean value){computerControlled=value;setChanged();}
    public double swing(float partial){
        if(level==null)return state==State.OPEN||state==State.DAMAGED_JAMMED?1:0;
        if(state==State.OPENING||state==State.CLOSING){
            double t=Math.clamp((level.getGameTime()+partial-strokeStart)/(double)Math.max(1,strokeEnd-strokeStart),0,1);
            return state==State.OPENING?t:1-t;
        }
        return state==State.OPEN||state==State.DAMAGED_JAMMED?1:0;
    }
    public boolean handwheelToggle(){
        finishStroke();
        return (state==State.CLOSED_SEALED||state==State.OPEN)&&setOpen(state==State.CLOSED_SEALED,false);
    }
    public boolean setOpen(boolean open,boolean powered){
        if(level==null||level.isClientSide())return false;
        finishStroke();
        if(state==State.DAMAGED_JAMMED)return false;
        if(open&&state==State.OPEN||!open&&state==State.CLOSED_SEALED)return true;
        if(state==State.OPENING||state==State.CLOSING)return false;
        if(powered&&energyFe<FE_STROKE)return false;
        if(powered)energyFe-=FE_STROKE;
        state=open?State.OPENING:State.CLOSING;
        strokeStart=level.getGameTime();strokeEnd=strokeStart+(powered?200:1200);
        if(open)setSealed(false);
        ((ServerLevel)level).scheduleTick(worldPosition,getBlockState().getBlock(),powered?200:1200);
        changed();
        return true;
    }
    public void finishStroke(){
        if(level==null||level.isClientSide()||state!=State.OPENING&&state!=State.CLOSING)return;
        long remaining=strokeEnd-level.getGameTime();
        if(remaining>0)return;
        boolean close=state==State.CLOSING;
        state=close?State.CLOSED_SEALED:State.OPEN;
        if(close)setSealed(true);
        changed();
    }
    public void jamBlownOpen(){
        if(level==null||level.isClientSide())return;
        state=State.DAMAGED_JAMMED;
        strokeStart=strokeEnd=level.getGameTime();
        setSealed(false);
        changed();
    }
    private void setSealed(boolean sealed){
        var door=(ContainmentVaultDoorBlock)getBlockState().getBlock();
        for(int i=0;i<door.size()*door.size();i++) {
            BlockPos pos=door.cell(worldPosition,getBlockState().getValue(ContainmentVaultDoorBlock.FACING),i);
            if(!level.isLoaded(pos))continue;
            BlockState part=level.getBlockState(pos);
            if(part.is(door)&&part.getValue(ContainmentVaultDoorBlock.CELL)==i
                    &&part.getValue(ContainmentVaultDoorBlock.SEALED)!=sealed)
                level.setBlock(pos,part.setValue(ContainmentVaultDoorBlock.SEALED,sealed),Block.UPDATE_ALL);
        }
        ContainmentControllerRegistry.changed(level,worldPosition);
    }
    private void changed(){
        setChanged();
        if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    @Override public void onLoad(){
        super.onLoad();
        if(level instanceof ServerLevel server&&(state==State.OPENING||state==State.CLOSING))
            server.scheduleTick(worldPosition,getBlockState().getBlock(),Math.max(1,(int)Math.min(Integer.MAX_VALUE,strokeEnd-server.getGameTime())));
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.saveAdditional(tag,registries);
        tag.putString("State",state.name());tag.putInt("EnergyFe",energyFe);
        tag.putLong("StrokeStart",strokeStart);tag.putLong("StrokeEnd",strokeEnd);
        tag.putBoolean("ComputerControlled",computerControlled);
    }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){
        super.loadAdditional(tag,registries);
        try{state=State.valueOf(tag.getString("State"));}catch(IllegalArgumentException error){state=State.CLOSED_SEALED;}
        energyFe=Math.clamp(tag.getInt("EnergyFe"),0,FE_CAPACITY);
        strokeStart=tag.getLong("StrokeStart");strokeEnd=tag.getLong("StrokeEnd");
        computerControlled=tag.getBoolean("ComputerControlled");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
