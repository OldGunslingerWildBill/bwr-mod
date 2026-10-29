package dev.bwr.mod.piping;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import java.util.*;

/**
 * Geometry is surveyed only after an edit or chunk/capability invalidation.
 * Continuous pipe runs collapse into junctions: live routing visits valves and
 * machine faces, never every pipe. No inventory, flow allowance or BE is cached.
 */
@EventBusSubscriber(modid="bwr")
public final class PipeTopology {
    private PipeTopology() {}
    public enum Kind { START, JUNCTION, VALVE, END }
    public record Node(BlockPos pos, Direction face, Kind kind, BlockState state) {}
    public record Graph(Node start, Map<Node,List<Node>> edges, Set<BlockPos> cells, boolean truncated, RouteMemo memo) {
        public Graph(Node start, Map<Node,List<Node>> edges, Set<BlockPos> cells, boolean truncated) {
            this(start,edges,cells,truncated,new RouteMemo());
        }
        public List<Node> neighbours(Node n) { return edges.getOrDefault(n,List.of()); }
        public List<Node> ends() { return edges.keySet().stream().filter(n->n.kind==Kind.END).toList(); }
    }
    private record Key(BlockPos start, Direction out, boolean steam, boolean foreign) {}
    private static final Map<Level,LinkedHashMap<Key,Entry>> CACHE=new WeakHashMap<>();
    private static long builds;
    private static final class Entry {
        boolean dirty;
        Graph graph;
        final Set<Long> chunks=new HashSet<>();
        // NeoForge retains listeners weakly. Keep one strong reference while this entry is live.
        final ICapabilityInvalidationListener listener=()->{stale();return false;};
        void stale(){dirty=true;if(graph!=null)graph.memo.stale=true;}
    }
    public static long buildCount(){return builds;}
    /**
     * Whether {@code graph} is still the cached answer for its start: false once an edit, a capability invalidation,
     * a chunk availability change or cache eviction made it out of date. One field read, no lookup and no world read,
     * so a holder can poll it every tick and call {@link #get} only after it turns false.
     */
    public static boolean current(Graph graph){return graph!=null&&!graph.memo.stale;}
    /** The graph currently cached for this start, or null: never builds, never reads the world (for tests and diagnostics). */
    public static Graph cached(Level level,BlockPos start,Direction out,boolean steam,boolean foreign) {
        var cache=CACHE.get(level);
        var entry=cache==null?null:cache.get(new Key(start.immutable(),out,steam,foreign));
        return entry==null||entry.dirty?null:entry.graph;
    }
    public static Graph get(Level level,BlockPos start,Direction out,boolean steam,boolean foreign) {
        var key=new Key(start.immutable(),out,steam,foreign);
        var cache=CACHE.computeIfAbsent(level,l->new LinkedHashMap<>(32,.75f,true));
        var entry=cache.get(key);
        if(entry!=null&&!entry.dirty)return entry.graph;
        entry=new Entry();entry.graph=build(level,key,entry);builds++;
        var replaced=cache.put(key,entry);if(replaced!=null)replaced.stale();
        if(cache.size()>512){var eldest=cache.keySet().iterator().next();cache.remove(eldest).stale();}
        return entry.graph;
    }
    private static Graph build(Level level,Key key,Entry entry) {
        Set<BlockPos> watched=new HashSet<>(),conduits=new HashSet<>();
        Map<Node,Set<Node>> raw=new LinkedHashMap<>();
        var initial=new Node(key.start,null,Kind.START,level.isLoaded(key.start)?level.getBlockState(key.start):null);
        raw.put(initial,new LinkedHashSet<>());
        var q=new ArrayDeque<Node>();q.add(initial);conduits.add(key.start);
        watch(level,key.start,watched,entry);
        boolean truncated=false;
        while(!q.isEmpty()&&!truncated) {
            var current=q.remove();if(current.state==null)continue;
            for(Direction d:Direction.values()) {
                if(current.equals(initial)&&key.out!=null&&key.out!=d)continue;
                if(!accepts(level,current.pos,current.state,d,key,false))continue;
                var pos=current.pos.relative(d);watch(level,pos,watched,entry);
                if(!level.isLoaded(pos)||pos.equals(key.start))continue;
                var s=level.getBlockState(pos);
                if(!accepts(level,pos,s,d.getOpposite(),key,true))continue;
                boolean pipe=s.is(key.steam?BwrBlocks.PRESSURISED_TUBE.get():BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get());
                boolean penetration=s.getBlock() instanceof dev.bwr.mod.containment.ContainmentPenetrationBlock b
                        && (key.steam ? b.service()==dev.bwr.mod.containment.ContainmentPenetrationBlock.Service.STEAM
                                      : b.service()==dev.bwr.mod.containment.ContainmentPenetrationBlock.Service.WATER);
                boolean motorValve=!key.steam&&s.getBlock() instanceof dev.bwr.mod.water.MotorOperatedWaterValveBlock;
                boolean valve=penetration || motorValve || key.steam&&(s.is(BwrBlocks.MSIV.get())
                        ||s.getBlock() instanceof dev.bwr.mod.steam.SafetyReliefValveBlock
                        ||s.getBlock() instanceof TurbineValveBlock);
                var next=new Node(pos,pipe||valve?null:d.getOpposite(),pipe?Kind.JUNCTION:valve?Kind.VALVE:Kind.END,s);
                join(raw,current,next);
                if((pipe||valve)&&conduits.add(pos)) {
                    if(conduits.size()>SteamLineNetwork.MAX_LINE_BLOCKS){truncated=true;break;}
                    q.add(next);
                }
            }
        }
        // Union connected plain-pipe cells. A junction represents the complete run.
        Map<Node,Node> compact=new HashMap<>();
        for(var n:raw.keySet()) {
            if(n.kind!=Kind.JUNCTION){compact.put(n,n);continue;}
            if(compact.containsKey(n))continue;
            var run=new ArrayDeque<Node>();run.add(n);compact.put(n,n);
            while(!run.isEmpty())for(var neighbour:raw.get(run.remove()))
                if(neighbour.kind==Kind.JUNCTION&&compact.putIfAbsent(neighbour,n)==null)run.add(neighbour);
        }
        Map<Node,Set<Node>> connected=new LinkedHashMap<>();
        for(var e:raw.entrySet()) {
            var a=compact.get(e.getKey());connected.computeIfAbsent(a,n->new LinkedHashSet<>());
            for(var target:e.getValue()){var b=compact.get(target);if(!a.equals(b))join(connected,a,b);}
        }
        Map<Node,List<Node>> result=new LinkedHashMap<>();connected.forEach((n,adjacent)->result.put(n,List.copyOf(adjacent)));
        return new Graph(initial,Collections.unmodifiableMap(result),Set.copyOf(conduits),truncated);
    }
    private static void join(Map<Node,Set<Node>> map,Node a,Node b) {
        map.computeIfAbsent(a,n->new LinkedHashSet<>()).add(b);map.computeIfAbsent(b,n->new LinkedHashSet<>()).add(a);
    }
    private static boolean accepts(Level level,BlockPos p,BlockState s,Direction side,Key key,boolean destination) {
        return key.steam?SteamLineNetwork.acceptsLineOn(s,side):WaterLineNetwork.acceptsLineOn(s,side)
                ||destination&&key.foreign&&WaterLineNetwork.connectsTo(level,p,side);
    }
    private static void watch(Level level,BlockPos pos,Set<BlockPos> watched,Entry entry) {
        entry.chunks.add(net.minecraft.world.level.ChunkPos.asLong(pos));
        if(watched.add(pos)&&level instanceof ServerLevel server)server.registerCapabilityListener(pos,entry.listener);
    }
    /** Structural notifications, including additions at previously empty frontiers. */
    public static void changed(Level level,BlockPos pos) {if(!level.isClientSide())level.invalidateCapabilities(pos);}
    @SubscribeEvent public static void availability(ChunkTicketLevelUpdatedEvent event) {
        boolean oldFull=ChunkLevel.fullStatus(event.getOldTicketLevel()).isOrAfter(FullChunkStatus.FULL);
        boolean newFull=ChunkLevel.fullStatus(event.getNewTicketLevel()).isOrAfter(FullChunkStatus.FULL);
        if(oldFull==newFull)return;
        var level=event.getLevel();var chunk=new net.minecraft.world.level.ChunkPos(event.getChunkPos());
        availabilityChanged(level,chunk);
        // A LIGHT-retained LevelChunk can become FULL without another ChunkEvent.Load.
        // Ticket events precede future replacement: attach after scheduling has finished,
        // then invalidate once accessibility completes. No polling or forced chunk loads.
        if(newFull&&event.getChunkHolder()!=null) {
            var server=level.getServer();var holder=event.getChunkHolder();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(),()->
                    holder.getFullChunkFuture().thenRun(()->server.execute(()->availabilityChanged(level,chunk)))));
        }
    }
    private static void availabilityChanged(Level level,net.minecraft.world.level.ChunkPos chunk) {
        OilLineNetwork.availabilityChanged(level,chunk);
        var cache=CACHE.get(level);if(cache==null)return;
        // Only our routes change; do not detach unrelated energy/fluid/modem capabilities.
        for(var entry:cache.values())if(entry.chunks.contains(chunk.toLong()))entry.stale();
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof Level level){OilLineNetwork.unloaded(level);var gone=CACHE.remove(level);if(gone!=null)gone.values().forEach(Entry::stale);}
    }

    /**
     * One reachable node, the narrowest opening on the way to it, and the valves on that way: the steam turbine valves, and the
     * motor-operated water valves (a water route through at least one is a controlled route).
     */
    public record Route(Node node,double opening,List<TurbineValveBlockEntity> valves,List<dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity> waterValves) {
        public Route(Node node,double opening,List<TurbineValveBlockEntity> valves){this(node,opening,valves,List.of());}
    }

    /**
     * The last answer of {@link #routes} for one graph, and the valve readings it was computed from.
     *
     * <p>The route walk is a function of two things: the graph, which is immutable, and what the valves in it say right
     * now (whether each is loaded, which block entity stands there, how far it is open). Pumps, nozzle claims and
     * recirculation circuits ask for the routes of the same graph on every tick, and the valves almost never move, so
     * the walk (a map, a queue and a record per node, every call) was repeated for an answer that had not changed.
     * This keeps the answer and re-reads only the valves, which is a handful of lookups; when every reading is the one
     * the answer was computed from, the answer is returned as it was. Any difference recomputes it, so the result is
     * always exactly what a fresh walk would give.
     */
    public static final class RouteMemo {
        private volatile boolean stale;
        private Node[] valves;
        private volatile Answer plain;
        private volatile Answer withoutRelief;

        private Node[] valves(Graph graph) {
            Node[] found=valves;
            if(found==null) {
                var list=new ArrayList<Node>();
                for(var node:graph.edges.keySet())if(node.kind==Kind.VALVE)list.add(node);
                found=list.toArray(new Node[0]);valves=found;
            }
            return found;
        }
    }
    /** One walk's result with the readings of every valve of the graph at that moment. */
    private record Answer(boolean[] available, java.lang.ref.WeakReference<net.minecraft.world.level.block.entity.BlockEntity>[] entities,
                          long[] state, List<Route> routes) {
        boolean matches(Level level,Node[] valves) {
            for(int i=0;i<valves.length;i++) {
                boolean now=dev.bwr.mod.world.LoadedWorld.available(level,valves[i].pos);
                if(now!=available[i])return false;
                var be=now?dev.bwr.mod.world.LoadedWorld.blockEntity(level,valves[i].pos):null;
                if((entities[i]==null?null:entities[i].get())!=be)return false;
                if(stateOf(be)!=state[i])return false;
            }
            return true;
        }
    }
    /** Everything a valve contributes to a walk, as one number: its stroke, or open/closed for a relief valve. */
    private static long stateOf(net.minecraft.world.level.block.entity.BlockEntity be) {
        if(be instanceof MainSteamIsolationValveBlockEntity v)return Double.doubleToLongBits(v.getPosition());
        if(be instanceof dev.bwr.mod.containment.ContainmentPenetrationBlockEntity v)return Double.doubleToLongBits(v.position());
        if(be instanceof SafetyReliefValveBlockEntity v)return v.isOpen()?1L:0L;
        if(be instanceof TurbineValveBlockEntity v)return Double.doubleToLongBits(v.position());
        if(be instanceof dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity v)return Double.doubleToLongBits(v.position());
        return 0L;
    }
    @SuppressWarnings("unchecked")
    private static Answer read(Level level,Node[] valves,List<Route> routes) {
        var available=new boolean[valves.length];
        var entities=(java.lang.ref.WeakReference<net.minecraft.world.level.block.entity.BlockEntity>[])new java.lang.ref.WeakReference[valves.length];
        var state=new long[valves.length];
        for(int i=0;i<valves.length;i++) {
            available[i]=dev.bwr.mod.world.LoadedWorld.available(level,valves[i].pos);
            var be=available[i]?dev.bwr.mod.world.LoadedWorld.blockEntity(level,valves[i].pos):null;
            entities[i]=be==null?null:new java.lang.ref.WeakReference<>(be);
            state[i]=stateOf(be);
        }
        return new Answer(available,entities,state,routes);
    }
    /** Live valve settings are evaluated on the compressed graph, including zero-opening paths. */
    public static List<Route> routes(Level level,Graph graph) {
        return routes(level,graph,false);
    }
    /** Relief valves own their discharge allocation; passive sinks must not pull through them again. */
    public static List<Route> routes(Level level,Graph graph,boolean excludeRelief) {
        if(graph.truncated)return List.of();
        var memo=graph.memo;var valves=memo.valves(graph);
        var known=excludeRelief?memo.withoutRelief:memo.plain;
        if(known!=null&&known.matches(level,valves))return known.routes;
        // The valves are read first and the walk reads them again a moment later, on the same thread with nothing
        // in between that could move one, so the readings stored with the answer are the ones it used.
        var readings=read(level,valves,null);
        var computed=walk(level,graph,excludeRelief);
        var answer=new Answer(readings.available,readings.entities,readings.state,computed);
        if(excludeRelief)memo.withoutRelief=answer;else memo.plain=answer;
        return computed;
    }
    private static List<Route> walk(Level level,Graph graph,boolean excludeRelief) {
        Map<Node,Route> best=new LinkedHashMap<>();var queue=new ArrayDeque<Route>();
        var first=new Route(graph.start,1,List.of(),List.of());best.put(graph.start,first);queue.add(first);
        while(!queue.isEmpty()) {
            var current=queue.remove();
            for(var next:graph.neighbours(current.node)) {
                double opening=current.opening;var valves=current.valves;var waterValves=current.waterValves;
                if(next.kind==Kind.VALVE) {
                    if(!dev.bwr.mod.world.LoadedWorld.available(level,next.pos))continue;
                    var be=dev.bwr.mod.world.LoadedWorld.blockEntity(level,next.pos);
                    if(excludeRelief&&be instanceof SafetyReliefValveBlockEntity)continue;
                    if(be instanceof MainSteamIsolationValveBlockEntity v)opening=Math.min(opening,v.getPosition());
                    if(be instanceof dev.bwr.mod.containment.ContainmentPenetrationBlockEntity v)
                        opening=Math.min(opening,v.position());
                    if(be instanceof SafetyReliefValveBlockEntity v&&!v.isOpen())opening=0;
                    if(be instanceof TurbineValveBlockEntity v){opening=Math.min(opening,v.position());var copy=new ArrayList<>(valves);copy.add(v);valves=List.copyOf(copy);}
                    if(be instanceof dev.bwr.mod.water.MotorOperatedWaterValveBlockEntity v){opening=Math.min(opening,v.position());var copy=new ArrayList<>(waterValves);copy.add(v);waterValves=List.copyOf(copy);}
                }
                var previous=best.get(next);if(previous!=null&&previous.opening>=opening)continue;
                var route=new Route(next,opening,valves,waterValves);best.put(next,route);
                if(next.kind!=Kind.END)queue.add(route);
            }
        }
        return List.copyOf(best.values());
    }
}
