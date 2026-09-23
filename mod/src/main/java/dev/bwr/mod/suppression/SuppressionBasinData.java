package dev.bwr.mod.suppression;

import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.mod.reactor.ReactorStateNbt;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Basin inventory outlives its controller. Historical cells prevent rebuilding it for free water. */
public final class SuppressionBasinData extends SavedData {
    private static final Factory<SuppressionBasinData> FACTORY=new Factory<>(SuppressionBasinData::new,SuppressionBasinData::load);
    private final List<Entry> entries=new ArrayList<>();
    private final Map<Long,Entry> byCell=new HashMap<>();
    private static final class Entry {
        final LongOpenHashSet cells=new LongOpenHashSet();
        final SuppressionPool pool=new SuppressionPool();
        BlockPos owner;
    }
    public record Claim(SuppressionPool pool,String problem) {}
    public static SuppressionBasinData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY,"bwr_suppression_basins");
    }
    public Claim claim(ServerLevel level,BlockPos controller,LongOpenHashSet water,SuppressionPool legacy) {
        return claim(level,controller,water,legacy,water.size()*1000.0);
    }
    public Claim claim(ServerLevel level,BlockPos controller,LongOpenHashSet water,SuppressionPool legacy,double capacityKg) {
        Set<Entry> matches=Collections.newSetFromMap(new IdentityHashMap<>());
        for(long cell:water) { var e=byCell.get(cell);if(e!=null)matches.add(e); }
        if(matches.size()>1) return new Claim(legacy,"Separate the previously metered suppression basins before forming them; their inventories cannot be merged by moving a controller.");
        Entry entry;
        if(matches.isEmpty()) {
            entry=new Entry();entry.pool.fromArray(legacy.toArray());entries.add(entry);
        } else entry=matches.iterator().next();
        if(entry.owner!=null && !entry.owner.equals(controller)
                && (!level.isLoaded(entry.owner) || level.getBlockEntity(entry.owner) instanceof SuppressionPoolBlockEntity))
            return new Claim(entry.pool,"This basin already has a controller at "+entry.owner.toShortString()+". Remove it before assigning another controller.");
        entry.owner=controller.immutable();
        for(long cell:water) {entry.cells.add(cell);byCell.put(cell,entry);}
        entry.pool.resizeCapacityKeepingInventory(capacityKg);
        setDirty();return new Claim(entry.pool,null);
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries) {
        ListTag list=new ListTag();
        for(var e:entries) {
            CompoundTag t=new CompoundTag();t.putLongArray("Cells",e.cells.toLongArray());
            if(e.owner!=null)t.putLong("Owner",e.owner.asLong());
            ReactorStateNbt.putDoubles(t,"Pool",e.pool.toArray());list.add(t);
        }
        tag.put("Basins",list);return tag;
    }
    private static SuppressionBasinData load(CompoundTag tag,HolderLookup.Provider registries) {
        var data=new SuppressionBasinData();var list=tag.getList("Basins",10);
        for(int i=0;i<list.size();i++) {
            var t=list.getCompound(i);var e=new Entry();e.cells.addAll(new LongOpenHashSet(t.getLongArray("Cells")));
            e.owner=t.contains("Owner")?BlockPos.of(t.getLong("Owner")):null;
            e.pool.fromArray(ReactorStateNbt.getDoubles(t,"Pool"));data.entries.add(e);
            for(long cell:e.cells)data.byCell.put(cell,e);
        }
        return data;
    }
}
