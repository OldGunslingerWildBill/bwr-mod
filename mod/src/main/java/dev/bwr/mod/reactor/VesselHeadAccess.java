package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import java.util.*;

/** Collision lookup shared by client and server. Rebuilt only when a head or envelope changes.
 * Construction blocks remain saved for validation; an open head has no physical roof. */
public final class VesselHeadAccess {
    private record Access(BlockPos min, BlockPos max, Set<Long> cells) {}
    private static final class Index {
        final Map<BlockPos,Access> owners=new HashMap<>();
        final Map<Long,BlockPos> cells=new HashMap<>();
    }
    private static final Map<Level,Index> LEVELS=new WeakHashMap<>();
    public static synchronized void update(Level level,BlockPos owner,VesselAppearance.Envelope e,boolean open) {
        if(level==null)return;
        var index=LEVELS.computeIfAbsent(level,k->new Index());
        var old=index.owners.get(owner);
        if(open&&e!=null&&old!=null&&old.min.equals(e.min())&&old.max.equals(e.max()))return;
        if(old!=null){old.cells.forEach(p->index.cells.remove(p,owner));index.owners.remove(owner);}
        if(!open||e==null)return;
        var cells=new HashSet<Long>();
        // Side construction cells above the removed crown must disappear too.
        int bottom=Math.min(e.max().getY(),e.min().getY()+(int)Math.ceil(VesselWaterGeometry.rim(e)+.36));
        for(int y=bottom;y<=e.max().getY();y++)
            for(int x=e.min().getX();x<=e.max().getX();x++)
                for(int z=e.min().getZ();z<=e.max().getZ();z++) {
                    var p=new BlockPos(x,y,z);
                    if(e.shell(p)){cells.add(p.asLong());index.cells.put(p.asLong(),owner.immutable());}
                }
        index.owners.put(owner.immutable(),new Access(e.min(),e.max(),Set.copyOf(cells)));
    }
    public static synchronized boolean isOpen(BlockGetter world,BlockPos pos) {
        if(!(world instanceof Level level))return false;
        var index=LEVELS.get(level);if(index==null)return false;
        var owner=index.cells.get(pos.asLong());
        return owner!=null&&level.isLoaded(owner);
    }
    private VesselHeadAccess(){}
}
