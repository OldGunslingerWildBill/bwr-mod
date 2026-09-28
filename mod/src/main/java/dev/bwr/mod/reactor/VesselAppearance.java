package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Client-only appearance index, with no world writes, block entities or pipe ticking.
 * The rectangular construction boundary remains the interaction/collision boundary. */
public final class VesselAppearance {
    public enum PortKind { LEGACY, CONTROLLER, WATER, STEAM }
    public record Sparger(BlockPos pos, CoreSpraySpargerBlock.Loop loop) {}
    public record Jet(BlockPos root, Direction facing, boolean narrow) {
        public List<BlockPos> cells() {
            var result=new ArrayList<BlockPos>();
            for(int y=0;y<6;y++)for(int x=0;x<(narrow?1:2);x++)
                result.add(root.offset(dev.bwr.mod.eccs.TurbineAssemblyBlock.turn(new BlockPos(x,y,0),facing)));
            return List.copyOf(result);
        }
    }
    public record Envelope(BlockPos min, BlockPos max, List<BlockPos> ports, List<Sparger> spargers, Map<BlockPos,PortKind> kinds,List<Jet> jets,List<BlockPos> driveCells) {
        public Envelope { ports = List.copyOf(ports); spargers = List.copyOf(spargers); kinds=Map.copyOf(kinds); jets=List.copyOf(jets); driveCells=List.copyOf(driveCells); }
        public Envelope(BlockPos min,BlockPos max,List<BlockPos> ports,List<Sparger> spargers,Map<BlockPos,PortKind> kinds,List<Jet> jets) {this(min,max,ports,spargers,kinds,jets,List.of());}
        public Envelope(BlockPos min,BlockPos max,List<BlockPos> ports,List<Sparger> spargers,Map<BlockPos,PortKind> kinds) { this(min,max,ports,spargers,kinds,List.of()); }
        public Envelope(BlockPos min, BlockPos max, List<BlockPos> ports, List<Sparger> spargers) { this(min,max,ports,spargers,Map.of()); }
        public Envelope(BlockPos min, BlockPos max, List<BlockPos> ports) { this(min,max,ports,List.of()); }
        public PortKind kind(BlockPos pos) { return kinds.getOrDefault(pos,PortKind.LEGACY); }
        public Direction face(BlockPos p) { return outwardFace(p,min,max); }
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
            tag.putIntArray("VisualPortKinds",ports.stream().mapToInt(p->kind(p).ordinal()).toArray());
            tag.putLongArray("VisualJetRoots",jets.stream().mapToLong(j->j.root().asLong()).toArray());
            tag.putIntArray("VisualJetStates",jets.stream().mapToInt(j->j.facing().get2DDataValue()+(j.narrow()?4:0)).toArray());
            tag.putLongArray("VisualDriveCells",driveCells.stream().mapToLong(BlockPos::asLong).toArray());
            for(var loop:CoreSpraySpargerBlock.Loop.values())tag.putLongArray("VisualSpargers_"+loop.getSerializedName(),
                    spargers.stream().filter(s->s.loop()==loop).mapToLong(s->s.pos().asLong()).toArray());
        }
    }
    private static final Map<Level, Map<BlockPos,Envelope>> CLIENTS = new WeakHashMap<>();
    private static synchronized Map<BlockPos,Envelope> index(Level level) {
        return CLIENTS.computeIfAbsent(level,k->new ConcurrentHashMap<>());
    }
    public static Envelope read(CompoundTag tag) {
        if(!tag.getBoolean("Formed") || !tag.contains("VisualMin") || !tag.contains("VisualMax"))return null;
        var min=BlockPos.of(tag.getLong("VisualMin"));var max=BlockPos.of(tag.getLong("VisualMax"));
        var spargers=new ArrayList<Sparger>();
        for(var loop:CoreSpraySpargerBlock.Loop.values())
            for(long value:tag.getLongArray("VisualSpargers_"+loop.getSerializedName())) {
                var p=BlockPos.of(value);
                if(spargers.size()<320 && p.getX()>min.getX() && p.getX()<max.getX()
                        && p.getZ()>min.getZ() && p.getZ()<max.getZ()
                        && p.getY()>min.getY() && p.getY()<max.getY()
                        && (p.getX()==min.getX()+1 || p.getX()==max.getX()-1
                        || p.getZ()==min.getZ()+1 || p.getZ()==max.getZ()-1))spargers.add(new Sparger(p,loop));
            }
        var ports=Arrays.stream(tag.getLongArray("VisualPorts")).mapToObj(BlockPos::of).toList();
        var codes=tag.getIntArray("VisualPortKinds");var kinds=new HashMap<BlockPos,PortKind>();
        for(int i=0;i<ports.size()&&i<codes.length;i++)
            if(codes[i]>0&&codes[i]<PortKind.values().length)kinds.put(ports.get(i),PortKind.values()[codes[i]]);
        var jets=new ArrayList<Jet>();var roots=tag.getLongArray("VisualJetRoots");var states=tag.getIntArray("VisualJetStates");
        var seen=new HashSet<BlockPos>();
        for(int i=0;i<Math.min(roots.length,states.length)&&jets.size()<4096;i++) {
            var root=BlockPos.of(roots[i]);int state=states[i];
            if(state<0||state>7||!seen.add(root))continue;
            var jet=new Jet(root,Direction.from2DDataValue(state&3),(state&4)!=0);
            if(root.getY()<min.getY()+1||root.getY()>=max.getY())continue;
            if(jet.cells().stream().allMatch(p->p.getX()>min.getX()&&p.getX()<max.getX()
                    &&p.getZ()>min.getZ()&&p.getZ()<max.getZ()&&p.getY()<max.getY()))jets.add(jet);
        }
        var drives=new ArrayList<BlockPos>();seen.clear();
        for(long value:tag.getLongArray("VisualDriveCells")) {
            var p=BlockPos.of(value);
            if(drives.size()<441 && p.getY()==min.getY()-1 && p.getX()>min.getX()&&p.getX()<max.getX()
                    &&p.getZ()>min.getZ()&&p.getZ()<max.getZ()&&seen.add(p))drives.add(p);
        }
        var e=new Envelope(min,max,ports,spargers,kinds,jets,drives);
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
    public static boolean hidesSparger(Level level,BlockPos pos) {
        if(level==null || !level.isClientSide())return false;
        for(var e:index(level).values())for(var s:e.spargers())if(s.pos().equals(pos))return true;
        return false;
    }
    public static boolean hidesInterface(Level level,BlockPos pos) {
        if(level==null || !level.isClientSide())return false;
        for(var e:index(level).values())if(e.kind(pos)!=PortKind.LEGACY)return true;
        return false;
    }
    public static boolean hidesJet(Level level,BlockPos pos) { return VesselJetAccess.contains(level,pos); }
    public static boolean hidesDrive(Level level,BlockPos pos) { return VesselDriveAccess.contains(level,pos); }
    public static Direction outwardFace(BlockPos p,BlockPos min,BlockPos max) {
        return p.getX()==min.getX()?Direction.WEST:p.getX()==max.getX()?Direction.EAST
                :p.getZ()==min.getZ()?Direction.NORTH:p.getZ()==max.getZ()?Direction.SOUTH
                :p.getY()==min.getY()?Direction.DOWN:Direction.UP;
    }
    private static void dirty(Level level,Envelope e) {
        if(e==null)return;
        // One notification per section, on geometry changes only; never per frame/tick.
        for(int x=e.min().getX()>>4;x<=e.max().getX()>>4;x++)
            for(int y=(e.min().getY()-1)>>4;y<=e.max().getY()>>4;y++)
                for(int z=e.min().getZ()>>4;z<=e.max().getZ()>>4;z++) {
                    var p=new BlockPos(x*16+8,y*16+8,z*16+8);
                    if(level.isLoaded(p)) {var s=level.getBlockState(p);level.sendBlockUpdated(p,s,s,8);}
                }
    }
    private VesselAppearance() {}
}
