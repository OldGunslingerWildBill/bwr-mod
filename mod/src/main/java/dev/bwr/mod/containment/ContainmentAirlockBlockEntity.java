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

/** Interlock decisions live on the single root, and are saved across unloads. */
public final class ContainmentAirlockBlockEntity extends BlockEntity {
    private boolean innerOpen,outerOpen,override,computerControlled,damaged;
    private boolean redstoneHigh,nextOuter;
    /** Hold on a leaf's cells while it is drawn moving: 0 none, 1 solid although open (opening), 2 clear although closed (closing). */
    private int innerHold,outerHold;
    private long innerClear,outerClear;
    private boolean seeded;
    // The authoritative interlock state above changes at once and is saved. What a leaf LOOKS like is a timeline that follows it:
    // the server keeps one only to know when the leaf has swung clear (collision), the client to draw it. Nothing here is saved.
    private final Leaf inner=new Leaf(),outer=new Leaf();
    /** Position 0 (shut and latched) to 1 (fully open) as a function of time; a leaf that is told to turn round does so from where it is. */
    private static final class Leaf {
        double from,to,start;
        double position(double now){
            double distance=Math.abs(to-from);
            if(distance==0)return to;
            return from+Math.signum(to-from)*Math.min(distance,Math.max(0,(now-start)/DoorMotion.AIRLOCK_LEAF_TICKS));
        }
        void settle(boolean open,double now){from=to=open?1:0;start=now;}
        void aim(boolean open,double now,double delay){from=position(now);to=open?1:0;start=now+delay;}
    }
    public ContainmentAirlockBlockEntity(BlockPos pos,BlockState state){super(BwrBlockEntities.CONTAINMENT_AIRLOCK.get(),pos,state);}
    public boolean innerOpen(){return innerOpen;}
    public boolean outerOpen(){return outerOpen;}
    private double now(float partial){return level==null?0:level.getGameTime()+partial;}
    /** How far the inner leaf's swing has got, 0 shut to 1 wide. */
    public float innerSwing(float partial){return (float)DoorMotion.swing(innerPosition(partial),DoorMotion.AIRLOCK_LATCH);}
    public float outerSwing(float partial){return (float)DoorMotion.swing(outerPosition(partial),DoorMotion.AIRLOCK_LATCH);}
    /** Travel of each leaf: 0 shut and latched, 1 fully open. The first part of it turns the wheel, the rest swings the leaf. */
    public double innerPosition(float partial){double now=now(partial);follow(now);return inner.position(now);}
    public double outerPosition(float partial){double now=now(partial);follow(now);return outer.position(now);}
    /** The client has no command to act on: it notices that a leaf's state differs from its drawn target and starts moving it. */
    private void follow(double now){if(level!=null&&level.isClientSide())retarget(now);}
    /** Starts the drawn motion of every leaf whose state changed. A closing leaf goes first, an opening one waits for it. */
    private void retarget(double now){
        boolean innerChanged=(innerOpen?1:0)!=inner.to,outerChanged=(outerOpen?1:0)!=outer.to;
        if(!innerChanged&&!outerChanged)return;
        if(innerChanged&&!innerOpen)begin(inner,true,false,now,0);
        if(outerChanged&&!outerOpen)begin(outer,false,false,now,0);
        if(innerChanged&&innerOpen)begin(inner,true,true,now,outer.to==0?outer.position(now)*DoorMotion.AIRLOCK_LEAF_TICKS:0);
        if(outerChanged&&outerOpen)begin(outer,false,true,now,inner.to==0?inner.position(now)*DoorMotion.AIRLOCK_LEAF_TICKS:0);
    }
    private void begin(Leaf leaf,boolean isInner,boolean open,double now,double delay){
        double x=leaf.position(now);
        leaf.aim(open,now,delay);
        if(level==null||level.isClientSide())return;
        double clear=DoorMotion.clearX(DoorMotion.AIRLOCK_LATCH);
        int hold=0;long until=0;
        if(open&&(x<clear||delay>0)){hold=1;until=(long)Math.ceil(now+delay+Math.max(0,clear-x)*DoorMotion.AIRLOCK_LEAF_TICKS);}
        else if(!open&&x>=clear){hold=2;until=(long)Math.ceil(now+(x-clear)*DoorMotion.AIRLOCK_LEAF_TICKS);}
        if(isInner){innerHold=hold;innerClear=until;}else{outerHold=hold;outerClear=until;}
        if(hold!=0)((ServerLevel)level).scheduleTick(worldPosition,getBlockState().getBlock(),1);
    }
    /** Scheduled tick, one a tick only while a leaf is still being held: lets go of the cells' collision when the leaf has swung clear. */
    public void settle(){
        if(level==null||level.isClientSide())return;
        long now=level.getGameTime();
        boolean released=false;
        if(innerHold!=0&&now>=innerClear){innerHold=0;released=true;}
        if(outerHold!=0&&now>=outerClear){outerHold=0;released=true;}
        if(released)writeCells();
        if(innerHold!=0||outerHold!=0)((ServerLevel)level).scheduleTick(worldPosition,getBlockState().getBlock(),1);
    }
    /** What the collision hold of each leaf is and until when (game tick); for test failure messages. */
    public String holds(){return "inner "+innerHold+" until "+innerClear+", outer "+outerHold+" until "+outerClear;}
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
        retarget(level.getGameTime());
        writeCells();
        ContainmentControllerRegistry.changed(level,worldPosition);
        changed();
    }
    private void writeCells(){
        var block=(ContainmentAirlockBlock)getBlockState().getBlock();
        for(int i=0;i<6;i++){
            BlockPos pos=block.cell(worldPosition,getBlockState().getValue(ContainmentAirlockBlock.FACING),i);
            if(!level.isLoaded(pos))continue;
            BlockState cell=level.getBlockState(pos);
            if(cell.is(block)&&cell.getValue(ContainmentAirlockBlock.CELL)==i){
                BlockState updated=cell.setValue(ContainmentAirlockBlock.INNER_OPEN,innerOpen)
                        .setValue(ContainmentAirlockBlock.OUTER_OPEN,outerOpen)
                        .setValue(ContainmentAirlockBlock.INNER_MOVE,innerHold)
                        .setValue(ContainmentAirlockBlock.OUTER_MOVE,outerHold);
                if(updated!=cell)level.setBlock(pos,updated,Block.UPDATE_ALL);
            }
        }
    }
    @Override public void onLoad(){
        super.onLoad();
        // A hold saved in the cells by a world that was closed mid-move has no timer any more: let go of it on the next tick.
        // (onLoad also runs when a chunk that was placed into before it finished loading comes up; a hold with a live timer stays.)
        if(!(level instanceof ServerLevel server))return;
        int savedInner=getBlockState().getValue(ContainmentAirlockBlock.INNER_MOVE),savedOuter=getBlockState().getValue(ContainmentAirlockBlock.OUTER_MOVE);
        if(innerHold==0&&savedInner!=0){innerHold=savedInner;innerClear=server.getGameTime();}
        if(outerHold==0&&savedOuter!=0){outerHold=savedOuter;outerClear=server.getGameTime();}
        if(innerHold!=0||outerHold!=0)server.scheduleTick(worldPosition,getBlockState().getBlock(),1);
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
        // First load: the leaves are where their saved state says, nothing is moving. Later packets (client) animate the change.
        if(!seeded){seeded=true;inner.settle(innerOpen,0);outer.settle(outerOpen,0);}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
