package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import java.util.*;

/** Formed visual assemblies replace the construction columns, without moving saved blocks.
 * Client and server agree on collision. Updated on structure/lifecycle changes only. */
public final class VesselJetAccess {
    private static final class Index {
        final Map<BlockPos,List<VesselAppearance.Jet>> owners=new HashMap<>();
        final Map<Long,BlockPos> cells=new HashMap<>();
    }
    private static final Map<Level,Index> LEVELS=new WeakHashMap<>();
    public static synchronized void update(Level level,BlockPos owner,VesselAppearance.Envelope e) {
        if(level==null)return;
        var index=LEVELS.computeIfAbsent(level,k->new Index());
        var next=e==null?List.<VesselAppearance.Jet>of():e.jets();
        var old=index.owners.getOrDefault(owner,List.of());
        if(old.equals(next))return;
        for(var jet:old)for(var p:jet.cells())index.cells.remove(p.asLong(),owner);
        index.owners.remove(owner);
        if(next.isEmpty())return;
        index.owners.put(owner.immutable(),next);
        for(var jet:next)for(var p:jet.cells())index.cells.put(p.asLong(),owner.immutable());
    }
    public static synchronized boolean contains(BlockGetter world,BlockPos pos) {
        if(!(world instanceof Level level))return false;
        var index=LEVELS.get(level);if(index==null)return false;
        var owner=index.cells.get(pos.asLong());return owner!=null&&level.isLoaded(owner);
    }
    private VesselJetAccess(){}
}
