package dev.bwr.mod.grid;

import java.util.UUID;
import dev.bwr.core.grid.EnergyLedger;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;

public abstract class GridDevice extends BlockEntity {
    protected UUID id=UUID.randomUUID();
    protected final EnergyLedger ledger=new EnergyLedger();
    protected GridDevice(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    public UUID deviceId(){return id;}
    public EnergyLedger ledger(){return ledger;}
    public boolean live(){return level!=null&&!level.isClientSide()&&!isRemoved()&&level.isLoaded(worldPosition)&&level.getBlockEntity(worldPosition)==this;}
    public abstract void tickDevice();
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide())GridService.loaded(this);}
    @Override public void setRemoved(){GridService.unloaded(this);super.setRemoved();}
    @Override public void onChunkUnloaded(){GridService.unloaded(this);super.onChunkUnloaded();}
    public CompoundTag retainedCondition(){var t=new CompoundTag();t.putLong("LifetimeFE",ledger.totalFE());t.putBoolean("Saturated",ledger.saturated());return t;}
    public void restoreCondition(CompoundTag t){ledger.restore(t.getLong("LifetimeFE"),t.getBoolean("Saturated"));setChanged();}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);t.putInt("GridVersion",1);t.putUUID("DeviceId",id);t.put("Condition",retainedCondition());}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);if(t.hasUUID("DeviceId"))id=t.getUUID("DeviceId");restoreCondition(t.getCompound("Condition"));}
}
