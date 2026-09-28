package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import java.util.*;

/** Keeps saved drive plumbing at construction cells while the formed housing mesh follows the core. */
public final class VesselDriveAccess {
    private static final class Index {
        final Map<BlockPos,List<BlockPos>> owners=new HashMap<>();
        final Map<Long,BlockPos> cells=new HashMap<>();
    }
    private static final Map<Level,Index> LEVELS=new WeakHashMap<>();
    public static synchronized void update(Level level,BlockPos owner,VesselAppearance.Envelope envelope) {
        if(level==null)return;
        var index=LEVELS.computeIfAbsent(level,k->new Index());
        var next=envelope==null?List.<BlockPos>of():envelope.driveCells();
        var old=index.owners.getOrDefault(owner,List.of());if(old.equals(next))return;
        for(var p:old)index.cells.remove(p.asLong(),owner);
        index.owners.remove(owner);if(next.isEmpty())return;
        index.owners.put(owner.immutable(),next);
        for(var p:next)index.cells.put(p.asLong(),owner.immutable());
    }
    public static synchronized boolean contains(BlockGetter world,BlockPos pos) {
        if(!(world instanceof Level level))return false;
        var index=LEVELS.get(level);if(index==null)return false;
        var owner=index.cells.get(pos.asLong());return owner!=null&&level.isLoaded(owner);
    }
    private VesselDriveAccess(){}
}
