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
    /** Game ticks of a stroke: with power, and on the hand-wheel (see {@link DoorMotion}). */
    public static final int POWERED_TICKS=DoorMotion.VAULT_POWERED_TICKS, MANUAL_TICKS=DoorMotion.VAULT_MANUAL_TICKS;
    private State state=State.CLOSED_SEALED;
    private int energyFe;
    private long strokeStart,strokeEnd;
    private final DoorMotion.StrokeClock clientClock=new DoorMotion.StrokeClock();
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
    /** Stroke position: 0 closed and latched, 1 fully open. */
    public double position(float partial){
        if(level==null)return state==State.OPEN||state==State.DAMAGED_JAMMED?1:0;
        if(state==State.OPENING||state==State.CLOSING){
            double t=strokeFraction(partial);
            return state==State.OPENING?t:1-t;
        }
        return state==State.OPEN||state==State.DAMAGED_JAMMED?1:0;
    }
    private double strokeFraction(float partial){
        double duration=Math.max(1,strokeEnd-strokeStart),now=level.getGameTime()+partial;
        return level.isClientSide()?clientClock.fraction(strokeStart,duration,state,now):DoorMotion.clamp01((now-strokeStart)/duration);
    }
    /** Open fraction of the leaf swing, 0 shut to 1 wide. */
    public double swing(float partial){return DoorMotion.swing(position(partial),DoorMotion.VAULT_LATCH);}
    /** How far the latch dogs have slid out of their keepers: 0 engaged, 1 clear. */
    public double unlatched(float partial){return DoorMotion.unlatched(position(partial),DoorMotion.VAULT_LATCH);}
    /** Turns of the hand-wheel so far. */
    public double wheelTurns(float partial){return DoorMotion.wheelTurns(position(partial),DoorMotion.VAULT_LATCH);}
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
        strokeStart=level.getGameTime();strokeEnd=strokeStart+(powered?POWERED_TICKS:MANUAL_TICKS);
        // The seal breaks at once (the leaf is unlatched), but the opening stays solid until the leaf has swung clear.
        if(open)applyCells(false,true);
        finishStroke();
        changed();
        return true;
    }
    /**
     * Brings the cells' collision in line with the stroke and finishes it when it is due. Idempotent: it is called from the
     * scheduled tick, from {@link #state()}, and on load, and schedules the next point at which something changes.
     * Opening: solid until the leaf is half open, then clear. Closing: clear until the leaf is half shut, then solid.
     */
    public void finishStroke(){
        if(level==null||level.isClientSide()||state!=State.OPENING&&state!=State.CLOSING)return;
        long now=level.getGameTime();
        boolean close=state==State.CLOSING;
        if(now>=strokeEnd){
            state=close?State.CLOSED_SEALED:State.OPEN;
            applyCells(close?Boolean.TRUE:null,false);
            changed();
            return;
        }
        long gate=strokeStart+DoorMotion.gateTicks(strokeEnd-strokeStart,DoorMotion.VAULT_LATCH,close);
        boolean before=now<gate;
        applyCells(null,close?!before:before);
        ((ServerLevel)level).scheduleTick(worldPosition,getBlockState().getBlock(),(int)Math.max(1,Math.min(Integer.MAX_VALUE,(before?gate:strokeEnd)-now)));
    }
    public void jamBlownOpen(){
        if(level==null||level.isClientSide())return;
        state=State.DAMAGED_JAMMED;
        strokeStart=strokeEnd=level.getGameTime();
        applyCells(false,false);
        changed();
    }
    /** Sets the seal and/or the animation hold on every cell of the leaf; null leaves a value alone. */
    private void applyCells(Boolean sealed,Boolean held){
        var door=(ContainmentVaultDoorBlock)getBlockState().getBlock();
        for(int i=0;i<door.size()*door.size();i++) {
            BlockPos pos=door.cell(worldPosition,getBlockState().getValue(ContainmentVaultDoorBlock.FACING),i);
            if(!level.isLoaded(pos))continue;
            BlockState part=level.getBlockState(pos);
            if(!part.is(door)||part.getValue(ContainmentVaultDoorBlock.CELL)!=i)continue;
            BlockState next=part;
            if(sealed!=null)next=next.setValue(ContainmentVaultDoorBlock.SEALED,sealed);
            if(held!=null)next=next.setValue(ContainmentVaultDoorBlock.HELD,held);
            if(next!=part)level.setBlock(pos,next,Block.UPDATE_ALL);
        }
        if(sealed!=null)ContainmentControllerRegistry.changed(level,worldPosition);
    }
    private void changed(){
        setChanged();
        if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    @Override public void onLoad(){
        super.onLoad();
        if(level instanceof ServerLevel server&&(state==State.OPENING||state==State.CLOSING)){
            rescaleLegacyStroke(server.getGameTime());
            finishStroke();
        }
    }
    /**
     * A stroke saved before the doors were retimed carries the old duration. Keep how far along it was and let it finish in the
     * current time instead of running on for the old one (a hand-wheel stroke was a minute, a powered one ten seconds).
     */
    private void rescaleLegacyStroke(long now){
        long duration=strokeEnd-strokeStart;
        long target=duration==DoorMotion.LEGACY_POWERED_TICKS?POWERED_TICKS:duration==DoorMotion.LEGACY_MANUAL_TICKS?MANUAL_TICKS:duration;
        if(target==duration||duration<=0)return;
        double done=DoorMotion.clamp01((now-strokeStart)/(double)duration);
        strokeStart=now-Math.round(done*target);strokeEnd=strokeStart+target;
        setChanged();
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
