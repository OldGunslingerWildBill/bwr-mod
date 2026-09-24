package dev.bwr.mod.eccs;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Paid casings survive dismantling, including parts in chunks which are currently unloaded. */
@EventBusSubscriber(modid="bwr")
public final class CondensateTankDismantling extends SavedData {
    private static final Factory<CondensateTankDismantling> FACTORY=new Factory<>(CondensateTankDismantling::new,CondensateTankDismantling::load);
    private final Map<UUID,LinkedHashMap<BlockPos,Integer>> pending=new LinkedHashMap<>();
    public static CondensateTankDismantling get(ServerLevel level){return level.getDataStorage().computeIfAbsent(FACTORY,"bwr_condensate_dismantling");}

    /** Called while the old block entity and its material accounting still exist. */
    public void begin(ServerLevel level,CondensateStorageTankBlockEntity part,BlockPos broken){
        var id=part.assemblyId();var existing=pending.get(id);
        if(existing!=null){part.breakCost=existing.getOrDefault(broken,0);existing.remove(broken);setDirty();return;}
        var owner=part.owner();part.breakCost=1;
        if(owner==null||!owner.assembled()||!CondensateTankShape.valid(owner.diameter,owner.height))return;
        var cells=new ArrayList<BlockPos>();int missing=1;
        for(var off:CondensateTankShape.get(owner.diameter,owner.height).cells.keySet()){
            var p=owner.getBlockPos().offset(off);if(p.equals(broken))continue;
            if(level.isLoaded(p)&&(!(level.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity member)||!member.assemblyId().equals(id))){missing++;continue;}
            cells.add(p);
        }
        cells.sort(BlockPos::compareTo);
        int remaining=Math.max(0,owner.paidBlocks-missing);
        part.breakCost=owner.paidBlocks>0?1:0;
        var plan=new LinkedHashMap<BlockPos,Integer>();
        for(int i=0;i<cells.size();i++)plan.put(cells.get(i),remaining/cells.size()+(i<remaining%cells.size()?1:0));
        pending.put(id,plan);setDirty();
    }

    public int dropCount(CondensateStorageTankBlockEntity part){
        var plan=pending.get(part.assemblyId());
        return plan==null?part.breakCost:plan.getOrDefault(part.getBlockPos(),part.breakCost);
    }

    /** Event work, not a tank ticker. Only pending dismantles are revisited, and no chunk is loaded. */
    public void process(ServerLevel level){
        if(pending.isEmpty()||CondensateTankAssembly.EDITING.get())return;
        CondensateTankAssembly.EDITING.set(true);
        try{
            var plans=pending.entrySet().iterator();
            while(plans.hasNext()){
                var plan=plans.next();var cells=plan.getValue().entrySet().iterator();
                int refund=0;BlockPos refundAt=null;
                while(cells.hasNext()){
                    var entry=cells.next();var p=entry.getKey();if(!level.isLoaded(p))continue;
                    int cost=entry.getValue();
                    if(level.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part&&part.assembled()&&part.assemblyId().equals(plan.getKey())){
                        var block=part.getBlockState().getBlock();
                        if(cost>0){
                            // Shape propagation reads neighbouring chunks even with UPDATE_CLIENTS alone.
                            // The casing state is known; keep deferred parts genuinely unloaded.
                            part.makeStandalone();var state=block.defaultBlockState();level.setBlock(p,state,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
                            level.invalidateCapabilities(p);level.sendBlockUpdated(p,state,state,Block.UPDATE_CLIENTS);
                            refund+=cost-1;
                        }else level.setBlock(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE); // Unpaid collision cell.
                    }else refund+=cost; // Never overwrite a foreign block occupying an old shell cell.
                    refundAt=p;cells.remove();setDirty();
                }
                if(refundAt!=null)while(refund>0){int n=Math.min(64,refund);Block.popResource(level,refundAt,new ItemStack(dev.bwr.mod.registry.BwrBlocks.CONDENSATE_STORAGE_TANK.get(),n));refund-=n;}
                if(plan.getValue().isEmpty()){plans.remove();setDirty();}
            }
        }finally{CondensateTankAssembly.EDITING.set(false);}
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        for(var level:event.getServer().getAllLevels())if(level.getGameTime()%20==0)get(level).process(level);
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        var plans=new ListTag();for(var entry:pending.entrySet()){
            var t=new CompoundTag();t.putUUID("Assembly",entry.getKey());var cells=new ListTag();
            entry.getValue().forEach((p,c)->{var cell=new CompoundTag();cell.putLong("Pos",p.asLong());cell.putInt("Cost",c);cells.add(cell);});
            t.put("Cells",cells);plans.add(t);
        }tag.put("Plans",plans);return tag;
    }
    private static CondensateTankDismantling load(CompoundTag tag,HolderLookup.Provider registries){
        var data=new CondensateTankDismantling();for(var value:tag.getList("Plans",Tag.TAG_COMPOUND)){
            var t=(CompoundTag)value;if(!t.hasUUID("Assembly"))continue;var cells=new LinkedHashMap<BlockPos,Integer>();
            for(var value2:t.getList("Cells",Tag.TAG_COMPOUND)){var c=(CompoundTag)value2;cells.put(BlockPos.of(c.getLong("Pos")),Math.clamp(c.getInt("Cost"),0,10000));}
            if(!cells.isEmpty())data.pending.put(t.getUUID("Assembly"),cells);
        }return data;
    }
}
