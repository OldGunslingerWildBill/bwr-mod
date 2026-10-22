package dev.bwr.mod.reactor;

import dev.bwr.core.boundary.*;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import java.util.*;

/**
 * Event-invalidated nozzle connections, persistent pressure-boundary breaks and world effects.
 *
 * <p>Two kinds of break. A <b>nozzle break</b> is the block directly outside a vessel port: a pipe removed from an
 * established connection, or the pipe an overpressure failure destroyed. A <b>line break</b> is any other pipe or valve
 * removed from an established recirculation, feedwater or main steam line between the vessel and the pump or machine
 * the line ends at. It drains the vessel through the port that line connects to, with that port's opening size and
 * elevation, as long as every route to it from the vessel is open: a closed MSIV, steam valve, relief valve or
 * containment penetration valve between the break and the vessel isolates it, and opening the valve starts the leak.
 * Putting the pipe back seals either kind; a port whose component has no other open break is then repaired (new pipe,
 * no accumulated stress). The vessel head is not a pipe and stays broken.
 *
 * <p>Nothing here walks a network on a steady tick. Nozzles are rescanned after a capability invalidation at the
 * nozzle face; each port's line is re-evaluated only when {@link PipeTopology} has replaced the port's cached graph
 * (an edit, a chunk availability change). A plant with no line break reads one field per port per tick. With a break,
 * the valves of that port's graph are re-read through the memoized route walk to see whether one has closed or opened.
 */
public final class ReactorBoundaryEffects {
    private int applied;
    private boolean dirty = true;
    private final Map<BlockPos,BoundaryComponent> leaks = new LinkedHashMap<>();
    private final Set<BlockPos> inletLeaks = new HashSet<>();
    private final Set<BlockPos> connected = new HashSet<>();
    /** Broken ports whose outside block survived the break: they seal after that block is taken off and put back. */
    private final Set<BlockPos> heldLeaks = new HashSet<>();
    /** Nozzle openings read from a save written before a reattached pipe could seal one. */
    private boolean legacyLeaks;
    private final Map<BlockPos,LineBreak> lineBreaks = new LinkedHashMap<>();
    /** Per port: the established line cells last seen (persisted), to tell a removed pipe from one never laid. */
    private final Map<BlockPos,Set<BlockPos>> lineCells = new HashMap<>();
    private final Map<BlockPos,PortLine> portLines = new LinkedHashMap<>();
    private final Map<BlockPos,Character> letters = new HashMap<>();
    private final Set<BlockPos> inletPorts = new HashSet<>();
    private final Set<BlockPos> outsides = new HashSet<>();
    private final Map<BlockPos,Direction> faces = new HashMap<>();
    private final ArrayDeque<Sealed> sealed = new ArrayDeque<>();
    /** Components with a break sealed this tick; reused, so an intact plant allocates nothing for it. */
    private final EnumSet<BoundaryComponent> repaired = EnumSet.noneOf(BoundaryComponent.class);
    private PortLine[] lineArray = new PortLine[0];
    private boolean openingsCleared;
    // Listeners are weakly held by NeoForge; retain them only while this controller lives.
    private final List<ICapabilityInvalidationListener> watchers = new ArrayList<>();
    public static final int HEAD = 1 << BoundaryComponent.REACTOR_VESSEL_HEAD.ordinal();
    /** Line cells remembered per port; a line is at most one capped pipe graph. */
    private static final int MAX_LINE_CELLS = 1024;
    /** How long a sealed break stays listed on the screen and in Lua, in ticks. */
    public static final long SEALED_SHOWN_TICKS = 1200;
    private static long evaluations;

    /** How many times any reactor re-evaluated a port's line since the server started (diagnostics and tests). */
    public static long lineEvaluations() { return evaluations; }

    private static final class LineBreak {
        final BlockPos cell, port;
        final BoundaryComponent component;
        final boolean inlet;
        boolean live;
        Direction jet = Direction.UP;
        List<PipeTopology.Node> anchors = List.of();
        LineBreak(BlockPos cell, BlockPos port, BoundaryComponent component, boolean inlet) {
            this.cell = cell; this.port = port; this.component = component; this.inlet = inlet;
        }
    }
    private static final class PortLine {
        final BlockPos port, outside;
        final BoundaryComponent component;
        final boolean steam;
        PipeTopology.Graph graph;
        Set<BlockPos> reached = Set.of();
        boolean partial = true;
        PortLine(BlockPos port, BlockPos outside, BoundaryComponent component) {
            this.port = port; this.outside = outside; this.component = component;
            this.steam = component == BoundaryComponent.MAIN_STEAM_LINE;
        }
    }
    /** A break that was sealed, for the screen and Lua. */
    public record Sealed(BoundaryComponent component, Component label, BlockPos at, long gameTime) {}
    /** One open or isolated break. {@code nozzle} is the block outside the port; otherwise a pipe further along the line. */
    public record BreakInfo(BoundaryComponent component, Component label, BlockPos port, BlockPos at, boolean nozzle, boolean open) {}

    public void invalidate() { dirty = true; }
    public static int mask(dev.bwr.core.ReactorCore core) {
        int mask=0; for(var c:core.getBoundaryStress().getBrokenComponents()) mask|=1<<c.ordinal(); return mask;
    }
    private static BoundaryComponent component(BlockState s) {
        if(s.is(BwrBlocks.RPV_STEAM_OUTLET.get())) return BoundaryComponent.MAIN_STEAM_LINE;
        if(s.is(BwrBlocks.RECIRCULATION_INLET.get()) || s.is(BwrBlocks.RECIRCULATION_OUTLET.get())) return BoundaryComponent.RECIRCULATION_LINE;
        if(s.is(BwrBlocks.RPV_WATER_INJECTION_PORT.get())) return BoundaryComponent.FEEDWATER_LINE;
        return null;
    }
    private static boolean attached(ServerLevel l, BlockPos p, VesselAppearance.Envelope e, BoundaryComponent c) {
        var face=e.face(p); var s=l.getBlockState(p.relative(face));
        return c==BoundaryComponent.MAIN_STEAM_LINE
                ? dev.bwr.mod.steam.SteamLineNetwork.acceptsLineOn(s,face.getOpposite())
                : dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s,face.getOpposite());
    }
    private static boolean accepts(BlockState s, Direction face, boolean steam) {
        return steam ? dev.bwr.mod.steam.SteamLineNetwork.acceptsLineOn(s,face) : dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s,face);
    }
    /**
     * A piece of line that {@link PipeTopology} walks through: pipe or valve body. A ruptured containment penetration
     * still counts as present (containment models its leak); anything else in the place of a line cell is a gap.
     */
    private static boolean conduit(BlockState s, boolean steam) {
        if(s.getBlock() instanceof dev.bwr.mod.containment.ContainmentPenetrationBlock b)
            return b.service()==(steam ? dev.bwr.mod.containment.ContainmentPenetrationBlock.Service.STEAM
                                       : dev.bwr.mod.containment.ContainmentPenetrationBlock.Service.WATER);
        if(!steam) return s.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get());
        return s.is(BwrBlocks.PRESSURISED_TUBE.get()) || s.is(BwrBlocks.MSIV.get())
                || s.getBlock() instanceof dev.bwr.mod.steam.SafetyReliefValveBlock
                || s.getBlock() instanceof dev.bwr.mod.steam.TurbineValveBlock;
    }
    /** Line cells beside {@code gap} that are joined to the vessel and open toward it. */
    private static List<BlockPos> anchorCells(ServerLevel l, BlockPos gap, Set<BlockPos> reached, boolean steam) {
        List<BlockPos> out=null;
        for(var d:Direction.values()) {
            var n=gap.relative(d);
            if(!reached.contains(n) || !loaded(l,n) || !accepts(l.getBlockState(n),d.getOpposite(),steam)) continue;
            if(out==null) out=new ArrayList<>(2);
            out.add(n);
        }
        return out==null?List.of():out;
    }
    /** Whether the chunk is there right now; never waits for a chunk on its way or loads one. */
    private static boolean loaded(ServerLevel l, BlockPos p) { return dev.bwr.mod.world.LoadedWorld.available(l,p); }
    private static boolean conduitBeside(ServerLevel l, BlockPos gap, boolean steam) {
        for(var d:Direction.values()) {
            var n=gap.relative(d);
            if(!loaded(l,n) || conduit(l.getBlockState(n),steam)) return true; // unknown counts as present
        }
        return false;
    }

    public void apply(ReactorControllerBlockEntity reactor, VesselAppearance.Envelope e) {
        if(!(reactor.getLevel() instanceof ServerLevel l) || e==null || reactor.core()==null) return;
        var boundary=reactor.core().getBoundaryStress();
        int broken=mask(reactor.core()); applied &= broken;
        leaks.entrySet().removeIf(entry->!boundary.isBroken(entry.getValue()));
        inletLeaks.retainAll(leaks.keySet());
        heldLeaks.retainAll(leaks.keySet());
        // A live line break the core no longer counts was cleared by a core reset, like a nozzle opening.
        lineBreaks.values().removeIf(b->b.live && !boundary.isBroken(b.component));
        var repaired=this.repaired; repaired.clear();
        if(dirty) {
            dirty=false; watchers.clear(); connected.retainAll(e.ports());
            refreshPorts(l,e);
            boolean complete=true;
            for(var p:e.ports()) {
                var outside=p.relative(e.face(p));
                if(!l.isLoaded(p) || !l.isLoaded(outside)) { dirty=true; complete=false; continue; }
                var c=component(l.getBlockState(p)); if(c==null) continue;
                ICapabilityInvalidationListener watcher=()->{dirty=true;return false;};
                watchers.add(watcher); l.registerCapabilityListener(outside,watcher);
                boolean joined=attached(l,p,e,c);
                if(leaks.containsKey(p)) {
                    if(!joined) heldLeaks.remove(p);
                    else if(legacyLeaks) heldLeaks.add(p.immutable());
                    else if(!heldLeaks.contains(p)) {
                        // A line reattached to a broken port closes that opening.
                        leaks.remove(p); inletLeaks.remove(p); connected.add(p.immutable());
                        repaired.add(c); seal(l,reactor,p,outside,c); reactor.setChanged();
                    }
                    continue;
                }
                if(joined) connected.add(p.immutable());
                else if(connected.remove(p)) {
                    // Never-connected construction ports are not new breaks. A pipe
                    // removed from an established connection is, even at low pressure.
                    if(boundary.getPlantConfiguration().exposes(c)) {
                        leaks.put(p.immutable(),c);
                        if(l.getBlockState(p).is(BwrBlocks.RECIRCULATION_INLET.get())) inletLeaks.add(p.immutable());
                        boundary.forceFailure(c,reactor.core().getPressurePsig());
                        applied |= 1<<c.ordinal(); reactor.setChanged();
                    }
                }
            }
            if(complete) legacyLeaks=false;
        }
        lines(l,reactor,boundary,repaired);
        broken=mask(reactor.core());
        for(var c:BoundaryComponent.values()) {
            int bit=1<<c.ordinal(); if((broken&bit)==0 || (applied&bit)!=0) continue;
            if(c==BoundaryComponent.REACTOR_VESSEL_HEAD) {
                l.playSound(null,reactor.getBlockPos(),SoundEvents.GENERIC_EXPLODE.value(),SoundSource.BLOCKS,16f,.65f);
            } else {
                if(e.ports().stream().anyMatch(p->!l.isLoaded(p)||!l.isLoaded(p.relative(e.face(p))))) continue;
                var ports=e.ports().stream().filter(p->component(l.getBlockState(p))==c)
                        .sorted(Comparator.<BlockPos>comparingInt(p->connected.contains(p)?0:1)
                                .thenComparingInt(BlockPos::getY).thenComparingLong(BlockPos::asLong)).toList();
                if(!ports.isEmpty()) {
                    var p=ports.getFirst(); var outside=p.relative(e.face(p)); var s=l.getBlockState(outside);
                    leaks.put(p.immutable(),c); connected.remove(p);
                    if(l.getBlockState(p).is(BwrBlocks.RECIRCULATION_INLET.get())) inletLeaks.add(p.immutable());
                    if(s.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()) || s.is(BwrBlocks.PRESSURISED_TUBE.get()))
                        l.destroyBlock(outside,true);
                    else if(attached(l,p,e,c)) heldLeaks.add(p.immutable());
                    l.playSound(null,p,SoundEvents.GENERIC_EXPLODE.value(),SoundSource.BLOCKS,5f,1.2f);
                }
            }
            applied|=bit; reactor.setChanged();
        }
        // An intact plant with nothing sealed this tick has nothing to reconcile.
        if(!leaks.isEmpty() || !lineBreaks.isEmpty() || !repaired.isEmpty()) reconcile(reactor,boundary,e,repaired);
        if(leaks.isEmpty() && lineBreaks.isEmpty()) {
            // Nothing open: hand the core an empty list once, not a new one every tick.
            if(!openingsCleared) { boundary.setOpenings(List.of()); openingsCleared=true; }
            return;
        }
        openingsCleared=false;
        var openings=new ArrayList<BoundaryOpening>();
        for(var entry:leaks.entrySet())
            openings.add(opening(e,entry.getKey(),entry.getValue(),inletLeaks.contains(entry.getKey())));
        for(var b:lineBreaks.values())
            if(b.live && e.ports().contains(b.port)) openings.add(opening(e,b.port,b.component,b.inlet));
        boundary.setOpenings(openings);
    }

    /**
     * Keeps each component's broken flag equal to "has an open break": a break that opened sets it, the last break
     * sealed repairs the component (new pipe, no stress), and breaks that are all behind closed valves clear the flag
     * but keep the stress. A failure the world has not yet acted on ({@code applied} unset) is left alone.
     */
    private void reconcile(ReactorControllerBlockEntity reactor, BoundaryStress boundary, VesselAppearance.Envelope e,
                           Set<BoundaryComponent> repaired) {
        for(var c:BoundaryComponent.values()) {
            if(c.isTerminal() || !boundary.getPlantConfiguration().exposes(c)) continue;
            int live=0,total=0;
            for(var v:leaks.values()) if(v==c) { live++; total++; }
            for(var b:lineBreaks.values()) if(b.component==c) { total++; if(b.live && e.ports().contains(b.port)) live++; }
            int bit=1<<c.ordinal();
            boolean acted=(applied&bit)!=0;
            if(live>0) {
                if(!boundary.isBroken(c)) { boundary.forceFailure(c,reactor.core().getPressurePsig()); applied|=bit; reactor.setChanged(); }
            } else if(total>0) {
                if(boundary.isBroken(c) && acted) { boundary.isolate(c); reactor.setChanged(); }
            } else if(repaired.contains(c) && (!boundary.isBroken(c) || acted)) {
                // The last break of this component was sealed: new pipe, no accumulated stress.
                boundary.repair(c); reactor.setChanged();
            }
        }
    }

    /** The opening a break at port {@code p} drains through, wherever along its line the break is. */
    public static BoundaryOpening opening(VesselAppearance.Envelope e, BlockPos p, BoundaryComponent c, boolean inlet) {
        double elevation=VesselWaterGeometry.levelAtHeight(e,p.getY()+.5-e.min().getY());
        // Inlet is the larger lower-vessel break. An outlet is the medium break.
        double liquid=switch(c) {
            case RECIRCULATION_LINE -> inlet?.20:.075;
            case FEEDWATER_LINE -> .02;
            default -> 0;
        };
        double steam=switch(c) {
            case RECIRCULATION_LINE -> inlet?1.5:.7;
            case FEEDWATER_LINE -> .4;
            default -> 1;
        };
        return new BoundaryOpening(c,elevation,liquid*BoundaryStress.RATED_CORE_FLOW_KG_PER_S,
                steam*BoundaryStress.RATED_STEAM_FLOW_KG_PER_S);
    }

    /** Port lines, labels and nozzle faces, after a nozzle rescan (an edit at a nozzle or a vessel revalidation). */
    private void refreshPorts(ServerLevel l, VesselAppearance.Envelope e) {
        outsides.clear(); letters.clear(); inletPorts.clear(); faces.clear();
        Map<String,List<BlockPos>> kinds=new HashMap<>();
        var keep=new HashSet<BlockPos>();
        for(var p:e.ports()) {
            outsides.add(p.relative(e.face(p)).immutable()); faces.put(p.immutable(),e.face(p));
            if(!l.isLoaded(p)) { if(portLines.containsKey(p)) keep.add(p); continue; }
            var s=l.getBlockState(p); var c=component(s); if(c==null) continue;
            boolean inlet=s.is(BwrBlocks.RECIRCULATION_INLET.get());
            if(inlet) inletPorts.add(p.immutable());
            kinds.computeIfAbsent(c.name()+(inlet?"/in":""),k->new ArrayList<>()).add(p.immutable());
            var line=portLines.get(p);
            var outside=p.relative(e.face(p)).immutable();
            if(line==null || line.component!=c || !line.outside.equals(outside))
                portLines.put(p.immutable(),new PortLine(p.immutable(),outside,c));
            keep.add(p);
        }
        portLines.keySet().retainAll(keep);
        lineArray=portLines.values().toArray(new PortLine[0]);
        lineCells.keySet().retainAll(e.ports());
        for(var list:kinds.values()) {
            list.sort(Comparator.comparingLong(BlockPos::asLong));
            for(int i=0;i<list.size();i++) letters.put(list.get(i),(char)('A'+Math.min(25,i)));
        }
    }

    /** Re-evaluates each port's line when its cached graph was replaced, and re-reads valves for ports with breaks. */
    private void lines(ServerLevel l, ReactorControllerBlockEntity reactor, BoundaryStress boundary, Set<BoundaryComponent> repaired) {
        for(var line:lineArray) {
            if(!boundary.getPlantConfiguration().exposes(line.component)) continue;
            if(!PipeTopology.current(line.graph)) evaluate(l,reactor,line,repaired);
            if(!lineBreaks.isEmpty()) live(l,line);
        }
    }

    private void evaluate(ServerLevel l, ReactorControllerBlockEntity reactor, PortLine line, Set<BoundaryComponent> repaired) {
        evaluations++;
        var g=PipeTopology.get(l,line.port,null,line.steam,false);
        line.graph=g;
        if(!loaded(l,line.port) || g.truncated()) { line.partial=true; line.reached=Set.of(); return; }
        var old=lineCells.getOrDefault(line.port,Set.of());
        var reached=new HashSet<>(g.cells()); reached.remove(line.port);
        boolean partial=false;
        scan:
        for(var cell:reached) for(var d:Direction.values()) if(!loaded(l,cell.relative(d))) { partial=true; break scan; }
        if(!partial) for(var cell:old) if(!loaded(l,cell)) { partial=true; break; }
        boolean changed=false;
        // A cell of the established line that is no longer pipe, beside the part still joined to the vessel, is a break.
        for(var cell:old) {
            if(reached.contains(cell) || outsides.contains(cell) || lineBreaks.containsKey(cell) || !loaded(l,cell)) continue;
            if(conduit(l.getBlockState(cell),line.steam) || anchorCells(l,cell,reached,line.steam).isEmpty()) continue;
            boolean inlet=inletPorts.contains(line.port);
            lineBreaks.put(cell,new LineBreak(cell,line.port,line.component,inlet));
            changed=true;
        }
        // Pipe put back in a gap seals it. A gap whose whole surroundings were taken down is no longer a line.
        for(var it=lineBreaks.values().iterator();it.hasNext();) {
            var b=it.next();
            if(!b.port.equals(line.port) || !loaded(l,b.cell)) continue;
            var s=l.getBlockState(b.cell);
            if(conduit(s,line.steam)) {
                if(!reached.contains(b.cell) && !anchorCells(l,b.cell,reached,line.steam).isEmpty()) continue; // not joined yet
                it.remove(); repaired.add(b.component); changed=true;
                seal(l,reactor,b.port,b.cell,b.component);
            } else if(!partial && !conduitBeside(l,b.cell,line.steam)) { it.remove(); changed=true; }
        }
        var established=established(l,g,reached,line);
        var memory=new HashSet<BlockPos>(established);
        for(var cell:old) {
            if(memory.size()>=MAX_LINE_CELLS) break;
            if(memory.contains(cell) || lineBreaks.containsKey(cell) || outsides.contains(cell)) continue;
            if(!loaded(l,cell)) { memory.add(cell); continue; }       // deferred: decided when its chunk is back
            if(reached.contains(cell)) { if(partial) memory.add(cell); continue; } // a dead end now, unless its end is unloaded
            // Cut off beyond a break or an unloaded gap: still part of the line, or a gap in it, until it is all gone.
            if(conduit(l.getBlockState(cell),line.steam) || conduitBeside(l,cell,line.steam)) memory.add(cell);
        }
        if(!memory.equals(old)) changed=true;
        if(memory.isEmpty()) lineCells.remove(line.port); else lineCells.put(line.port,memory);
        line.reached=reached; line.partial=partial;
        boolean any=false;
        for(var b:lineBreaks.values()) if(b.port.equals(line.port)) { any=true; break; }
        if(any) {
            var nodes=cellNodes(g,reached,line.steam,l);
            for(var b:lineBreaks.values()) {
                if(!b.port.equals(line.port)) continue;
                var cells=anchorCells(l,b.cell,reached,line.steam);
                var anchors=new ArrayList<PipeTopology.Node>(cells.size());
                for(var cell:cells) { var n=nodes.get(cell); if(n!=null) anchors.add(n); }
                b.anchors=List.copyOf(anchors);
                if(!cells.isEmpty()) {
                    var from=cells.getFirst();
                    b.jet=Direction.getNearest(b.cell.getX()-from.getX(),b.cell.getY()-from.getY(),b.cell.getZ()-from.getZ());
                }
            }
        }
        if(changed) reactor.setChanged();
    }

    /**
     * The reached cells on a path between the vessel and a line end (a pump or other machine face) or a recorded
     * break. Dead-end stubs prune away: a pipe that never reached anything is construction, not pressure boundary.
     */
    private Set<BlockPos> established(ServerLevel l, PipeTopology.Graph g, Set<BlockPos> reached, PortLine line) {
        Map<BlockPos,Integer> degree=new HashMap<>();
        Map<BlockPos,BlockState> states=new HashMap<>();
        for(var cell:reached) states.put(cell,l.getBlockState(cell));
        for(var cell:reached) {
            int n=0; var s=states.get(cell);
            for(var d:Direction.values()) {
                var other=cell.relative(d); var t=states.get(other);
                if(t!=null && accepts(s,d,line.steam) && accepts(t,d.getOpposite(),line.steam)) n++;
            }
            degree.put(cell,n);
        }
        var termini=new ArrayList<BlockPos>();
        termini.add(line.outside);
        for(var end:g.ends()) if(end.face()!=null) termini.add(end.pos().relative(end.face()));
        for(var b:lineBreaks.values()) if(b.port.equals(line.port)) termini.addAll(anchorCells(l,b.cell,reached,line.steam));
        for(var t:termini) degree.computeIfPresent(t,(k,v)->v+1);
        var queue=new ArrayDeque<BlockPos>();
        for(var entry:degree.entrySet()) if(entry.getValue()<=1) queue.add(entry.getKey());
        var removed=new HashSet<BlockPos>();
        while(!queue.isEmpty()) {
            var cell=queue.remove();
            if(!removed.add(cell)) continue;
            var s=states.get(cell);
            for(var d:Direction.values()) {
                var other=cell.relative(d); var t=states.get(other);
                if(t==null || removed.contains(other) || !accepts(s,d,line.steam) || !accepts(t,d.getOpposite(),line.steam)) continue;
                int left=degree.merge(other,-1,Integer::sum);
                if(left<=1) queue.add(other);
            }
        }
        var out=new HashSet<BlockPos>(reached); out.removeAll(removed);
        return out;
    }

    /** Which compressed graph node each reached cell belongs to: valves are their own node, pipe runs their junction. */
    private static Map<BlockPos,PipeTopology.Node> cellNodes(PipeTopology.Graph g, Set<BlockPos> reached, boolean steam, ServerLevel l) {
        Map<BlockPos,PipeTopology.Node> out=new HashMap<>();
        var pipe=steam?BwrBlocks.PRESSURISED_TUBE.get():BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get();
        for(var node:g.edges().keySet()) {
            if(node.kind()==PipeTopology.Kind.VALVE) out.put(node.pos(),node);
            if(node.kind()!=PipeTopology.Kind.JUNCTION || out.containsKey(node.pos())) continue;
            var run=new ArrayDeque<BlockPos>(); run.add(node.pos()); out.put(node.pos(),node);
            while(!run.isEmpty()) {
                var cell=run.remove();
                for(var d:Direction.values()) {
                    var next=cell.relative(d);
                    if(!reached.contains(next) || out.containsKey(next) || !l.getBlockState(next).is(pipe)) continue;
                    out.put(next.immutable(),node); run.add(next);
                }
            }
        }
        return out;
    }

    /** Open or isolated: is there an open route from the vessel to a pipe beside the gap? Deferred while unknown. */
    private void live(ServerLevel l, PortLine line) {
        List<PipeTopology.Route> routes=null;
        for(var b:lineBreaks.values()) {
            if(!b.port.equals(line.port) || !loaded(l,b.cell)) continue;
            if(b.anchors.isEmpty()) { if(!line.partial) b.live=false; continue; }
            if(routes==null) routes=line.graph==null?List.of():PipeTopology.routes(l,line.graph);
            double best=-1;
            for(var route:routes) if(b.anchors.contains(route.node())) best=Math.max(best,route.opening());
            if(best<0) {
                if(line.partial || valveUnavailable(l,line.graph)) continue;
                b.live=false;
            } else b.live=best>0;
        }
    }
    private static boolean valveUnavailable(ServerLevel l, PipeTopology.Graph g) {
        if(g==null) return true;
        for(var node:g.edges().keySet())
            if(node.kind()==PipeTopology.Kind.VALVE && !dev.bwr.mod.world.LoadedWorld.available(l,node.pos())) return true;
        return false;
    }

    private Component label(BlockPos port, BoundaryComponent c) {
        String letter=String.valueOf(letters.getOrDefault(port,'?'));
        return switch(c) {
            case RECIRCULATION_LINE -> inletPorts.contains(port)
                    ? BwrText.tr("status.bwr.boundary_break.recirculation_discharge","Recirculation loop %s discharge",letter)
                    : BwrText.tr("status.bwr.boundary_break.recirculation_suction","Recirculation loop %s suction",letter);
            case FEEDWATER_LINE -> BwrText.tr("status.bwr.boundary_break.feedwater","Feedwater line %s",letter);
            case MAIN_STEAM_LINE -> BwrText.tr("status.bwr.boundary_break.main_steam","Main steam line %s",letter);
            default -> dev.bwr.mod.text.StatusNames.boundary(c);
        };
    }

    private void seal(ServerLevel l, ReactorControllerBlockEntity reactor, BlockPos port, BlockPos at, BoundaryComponent c) {
        var label=label(port,c);
        sealed.addLast(new Sealed(c,label,at.immutable(),l.getGameTime()));
        while(sealed.size()>4) sealed.removeFirst();
        var player=PipePlacements.placer(l,at);
        if(player==null) {
            double best=32*32;
            for(var candidate:l.players()) {
                double d=candidate.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5);
                if(d<best) { best=d; player=candidate; }
            }
        }
        if(player!=null) player.displayClientMessage(
                BwrText.tr("message.bwr.boundary_break.sealed","%s break sealed",label),true);
    }

    /** Open and isolated breaks, nozzle breaks first. */
    public List<BreakInfo> breaks() {
        var out=new ArrayList<BreakInfo>();
        leaks.forEach((p,c)->out.add(new BreakInfo(c,label(p,c),p,p.relative(faceOf(p)),true,true)));
        for(var b:lineBreaks.values()) out.add(new BreakInfo(b.component,label(b.port,b.component),b.port,b.cell,false,b.live));
        return out;
    }
    private Direction faceOf(BlockPos port) { return faces.getOrDefault(port,Direction.UP); }
    /** Breaks sealed within the last {@link #SEALED_SHOWN_TICKS}, oldest first. */
    public List<Sealed> recentlySealed(long now) {
        var out=new ArrayList<Sealed>();
        for(var s:sealed) if(now-s.gameTime()>=0 && now-s.gameTime()<SEALED_SHOWN_TICKS) out.add(s);
        return out;
    }

    public void write(CompoundTag tag) {
        tag.putInt("AppliedBoundaryFailures",applied);
        tag.putLongArray("BoundaryInletLeaks",inletLeaks.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("BoundaryConnectedPorts",connected.stream().mapToLong(BlockPos::asLong).toArray());
        var entries=new ListTag();
        leaks.forEach((p,c)->{var t=new CompoundTag();t.putLong("pos",p.asLong());t.putString("component",c.name());entries.add(t);});
        tag.put("BoundaryOpenings",entries);
        // New keys only when they hold something; an intact plant saves exactly as before.
        if(!leaks.isEmpty()) tag.putBoolean("BoundaryOpeningsSealable",true);
        if(!heldLeaks.isEmpty()) tag.putLongArray("BoundaryHeldOpenings",heldLeaks.stream().mapToLong(BlockPos::asLong).toArray());
        if(!lineBreaks.isEmpty()) {
            var list=new ListTag();
            for(var b:lineBreaks.values()) {
                var t=new CompoundTag();
                t.putLong("pos",b.cell.asLong()); t.putLong("port",b.port.asLong());
                t.putString("component",b.component.name());
                if(b.inlet) t.putBoolean("inlet",true);
                if(b.live) t.putBoolean("open",true);
                list.add(t);
            }
            tag.put("BoundaryLineBreaks",list);
        }
        if(!lineCells.isEmpty()) {
            var list=new ListTag();
            lineCells.forEach((port,cells)->{
                var t=new CompoundTag(); t.putLong("port",port.asLong());
                t.putLongArray("cells",cells.stream().mapToLong(BlockPos::asLong).toArray()); list.add(t);
            });
            tag.put("BoundaryLines",list);
        }
    }
    public void read(CompoundTag tag) {
        applied=tag.getInt("AppliedBoundaryFailures"); leaks.clear(); connected.clear(); inletLeaks.clear(); dirty=true;
        heldLeaks.clear(); lineBreaks.clear(); lineCells.clear(); portLines.clear(); lineArray=new PortLine[0]; openingsCleared=false;
        for(long p:tag.getLongArray("BoundaryInletLeaks")) inletLeaks.add(BlockPos.of(p));
        for(long p:tag.getLongArray("BoundaryConnectedPorts")) connected.add(BlockPos.of(p));
        if(tag.contains("BoundaryOpenings",Tag.TAG_LIST)) {
            var list=tag.getList("BoundaryOpenings",Tag.TAG_COMPOUND);
            for(int i=0;i<Math.min(512,list.size());i++) {
                var t=list.getCompound(i);
                try { leaks.put(BlockPos.of(t.getLong("pos")),BoundaryComponent.valueOf(t.getString("component"))); }
                catch(IllegalArgumentException ignored) { }
            }
        } else for(var c:BoundaryComponent.values()) if(tag.contains("BoundaryLeak_"+c.name()))
            leaks.put(BlockPos.of(tag.getLong("BoundaryLeak_"+c.name())),c);
        // Saved before a reattached pipe could seal a port: a pipe already back there was put back under the old
        // rule, so it must come off and go back on once more to seal it.
        legacyLeaks=!leaks.isEmpty() && !tag.getBoolean("BoundaryOpeningsSealable");
        for(long p:tag.getLongArray("BoundaryHeldOpenings")) heldLeaks.add(BlockPos.of(p));
        var breaks=tag.getList("BoundaryLineBreaks",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(512,breaks.size());i++) {
            var t=breaks.getCompound(i);
            try {
                var b=new LineBreak(BlockPos.of(t.getLong("pos")),BlockPos.of(t.getLong("port")),
                        BoundaryComponent.valueOf(t.getString("component")),t.getBoolean("inlet"));
                b.live=t.getBoolean("open"); lineBreaks.put(b.cell,b);
            } catch(IllegalArgumentException ignored) { }
        }
        var lines=tag.getList("BoundaryLines",Tag.TAG_COMPOUND);
        for(int i=0;i<Math.min(64,lines.size());i++) {
            var t=lines.getCompound(i); var cells=new HashSet<BlockPos>();
            for(long p:t.getLongArray("cells")) { if(cells.size()>=MAX_LINE_CELLS) break; cells.add(BlockPos.of(p)); }
            if(!cells.isEmpty()) lineCells.put(BlockPos.of(t.getLong("port")),cells);
        }
    }
    /** How many cells of a port's line are remembered as established (tests and diagnostics). */
    public int lineCellCount(BlockPos port) { var cells=lineCells.get(port); return cells==null?0:cells.size(); }
    public long[] leakPositions() { return leaks.keySet().stream().mapToLong(BlockPos::asLong).toArray(); }
    /** Open line breaks for the plume: the gap's position. */
    public long[] lineBreakPositions() {
        return lineBreaks.values().stream().filter(b->b.live).mapToLong(b->b.cell.asLong()).toArray();
    }
    /** The direction each open line break's plume leaves in, matching {@link #lineBreakPositions()}. */
    public byte[] lineBreakJets() {
        var live=lineBreaks.values().stream().filter(b->b.live).toList();
        var out=new byte[live.size()];
        for(int i=0;i<out.length;i++) out[i]=(byte)live.get(i).jet.get3DDataValue();
        return out;
    }
}
