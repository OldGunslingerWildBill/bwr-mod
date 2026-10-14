package dev.bwr.mod.grid;

import java.util.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Zero-inventory directional bridge. Guards cover capability lookup as well as transfer. */
public final class FeMeterBlockEntity extends GridDevice {
    private static final ThreadLocal<Set<UUID>> PATH=ThreadLocal.withInitial(LinkedHashSet::new);
    private String label="",units="MW";
    private Direction sampledDirection;
    public FeMeterBlockEntity(BlockPos p,BlockState s){super(BwrBlockEntities.FE_METER.get(),p,s);sampledDirection=direction();}
    public Direction direction(){return getBlockState().getValue(FeMeterBlock.FACING);}
    public String label(){return label;}public String units(){return units;}
    public boolean label(String value){if(value==null||value.length()>64)return false;label=value;setChanged();return true;}
    public boolean units(String value){if(!"MW".equals(value)&&!"FE/t".equals(value))return false;units=value;setChanged();return true;}
    public boolean direction(Direction face){
        if(face==null||!live())return false;
        if(direction()!=face){level.setBlock(worldPosition,getBlockState().setValue(FeMeterBlock.FACING,face),3);ledger.resetRates();sampledDirection=face;level.invalidateCapabilities(worldPosition);setChanged();}
        return true;
    }
    @Override public void tickDevice(){if(live()){if(sampledDirection!=direction()){ledger.resetRates();sampledDirection=direction();level.invalidateCapabilities(worldPosition);}ledger.beginTick(level.getGameTime());}}
    public IEnergyStorage capability(Direction face){
        if(face==null||face!=direction()&&face!=direction().getOpposite())return null;
        Direction acquired=direction();
        return new IEnergyStorage(){
            private boolean valid(){return live()&&direction()==acquired;}
            public int receiveEnergy(int n,boolean simulate){return valid()&&face==acquired.getOpposite()?transfer(n,simulate,true):0;}
            public int extractEnergy(int n,boolean simulate){return valid()&&face==acquired?transfer(n,simulate,false):0;}
            public int getEnergyStored(){return 0;}public int getMaxEnergyStored(){return 0;}
            public boolean canReceive(){return valid()&&face==acquired.getOpposite();}public boolean canExtract(){return valid()&&face==acquired;}
        };
    }
    private int transfer(int offered,boolean simulate,boolean push){
        if(offered<=0||!live())return 0;
        Set<UUID> path=PATH.get();if(path.size()>=64||!path.add(id))return 0;
        try {
            Direction step=push?direction():direction().getOpposite();var next=worldPosition.relative(step);
            if(!level.isLoaded(next))return 0;
            var endpoint=level.getCapability(Capabilities.EnergyStorage.BLOCK,next,step.getOpposite());
            if(endpoint==null)return 0;
            int n=Math.max(0,Math.min(offered,push?endpoint.receiveEnergy(offered,simulate):endpoint.extractEnergy(offered,simulate)));
            if(!simulate&&n>0){tickDevice();ledger.record(n);setChanged();}return n;
        } finally {path.remove(id);if(path.isEmpty())PATH.remove();}
    }
    @Override public CompoundTag retainedCondition(){var t=super.retainedCondition();t.putString("Label",label);t.putString("Units",units);return t;}
    @Override public void restoreCondition(CompoundTag t){super.restoreCondition(t);String v=t.getString("Label");label=v.substring(0,Math.min(64,v.length()));units="FE/t".equals(t.getString("Units"))?"FE/t":"MW";sampledDirection=direction();}
}
