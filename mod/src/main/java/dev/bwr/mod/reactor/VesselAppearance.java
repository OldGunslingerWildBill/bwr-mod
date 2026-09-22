package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Client-only appearance index, with no world writes, block entities or pipe ticking.
 * The rectangular construction boundary remains the interaction/collision boundary. */
public final class VesselAppearance {
    public record Envelope(BlockPos min, BlockPos max, List<BlockPos> ports) {
        public Envelope { ports = List.copyOf(ports); }
        public int width() { return max.getX()-min.getX()+1; }
        public int depth() { return max.getZ()-min.getZ()+1; }
        public int height() { return max.getY()-min.getY()+1; }
        public boolean shell(BlockPos p) {
            return p.getX()>=min.getX() && p.getX()<=max.getX()
                    && p.getY()>=min.getY() && p.getY()<=max.getY()
                    && p.getZ()>=min.getZ() && p.getZ()<=max.getZ()
                    && (p.getX()==min.getX() || p.getX()==max.getX()
                    || p.getY()==min.getY() || p.getY()==max.getY()
                    || p.getZ()==min.getZ() || p.getZ()==max.getZ());
        }
        public void write(CompoundTag tag) {
            tag.putLong("VisualMin",min.asLong());tag.putLong("VisualMax",max.asLong());
            tag.putLongArray("VisualPorts",ports.stream().mapToLong(BlockPos::asLong).toArray());
        }
    }
    private static final Map<Level, Map<BlockPos,Envelope>> CLIENTS = new WeakHashMap<>();
    private static synchronized Map<BlockPos,Envelope> index(Level level) {
        return CLIENTS.computeIfAbsent(level,k->new ConcurrentHashMap<>());
    }
    public static Envelope read(CompoundTag tag) {
        if(!tag.getBoolean("Formed") || !tag.contains("VisualMin") || !tag.contains("VisualMax"))return null;
        var min=BlockPos.of(tag.getLong("VisualMin"));var max=BlockPos.of(tag.getLong("VisualMax"));
        var e=new Envelope(min,max,Arrays.stream(tag.getLongArray("VisualPorts")).mapToObj(BlockPos::of).toList());
        return e.width()>=7 && e.width()<=23 && e.depth()>=7 && e.depth()<=23
                && e.height()>=10 && e.height()<=131 ? e : null;
    }
    public static void update(Level level,BlockPos owner,Envelope next) {
        if(level==null || !level.isClientSide())return;
        var map=index(level);var previous=next==null?map.remove(owner):map.put(owner.immutable(),next);
        if(Objects.equals(previous,next))return;
        dirty(level,previous);dirty(level,next);
    }
    public static boolean hides(Level level,BlockPos pos) {
        if(level==null || !level.isClientSide())return false;
        for(var e:index(level).values())if(e.shell(pos))return true;
        return false;
    }
    private static void dirty(Level level,Envelope e) {
        if(e==null)return;
        // One notification per section, on geometry changes only; never per frame/tick.
        for(int x=e.min().getX()>>4;x<=e.max().getX()>>4;x++)
            for(int y=e.min().getY()>>4;y<=e.max().getY()>>4;y++)
                for(int z=e.min().getZ()>>4;z<=e.max().getZ()>>4;z++) {
                    var p=new BlockPos(x*16+8,y*16+8,z*16+8);
                    if(level.isLoaded(p)) {var s=level.getBlockState(p);level.sendBlockUpdated(p,s,s,8);}
                }
    }
    private VesselAppearance() {}
}
