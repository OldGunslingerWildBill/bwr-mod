package dev.bwr.mod.reactor;

import dev.bwr.core.boundary.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.sounds.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import java.util.*;

/** Event-invalidated nozzle connections, persistent vessel-side breaks and world effects. */
public final class ReactorBoundaryEffects {
    private int applied;
    private boolean dirty = true;
    private final Map<BlockPos,BoundaryComponent> leaks = new LinkedHashMap<>();
    private final Set<BlockPos> inletLeaks = new HashSet<>();
    private final Set<BlockPos> connected = new HashSet<>();
    // Listeners are weakly held by NeoForge; retain them only while this controller lives.
    private final List<ICapabilityInvalidationListener> watchers = new ArrayList<>();
    public static final int HEAD = 1 << BoundaryComponent.REACTOR_VESSEL_HEAD.ordinal();
    public void invalidate() { dirty = true; }
    public static int mask(dev.bwr.core.ReactorCore core) {
        int mask=0; for(var c:core.getBoundaryStress().getBrokenComponents()) mask|=1<<c.ordinal(); return mask;
    }
    private static BoundaryComponent component(net.minecraft.world.level.block.state.BlockState s) {
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
    public void apply(ReactorControllerBlockEntity reactor, VesselAppearance.Envelope e) {
        if(!(reactor.getLevel() instanceof ServerLevel l) || e==null || reactor.core()==null) return;
        var boundary=reactor.core().getBoundaryStress();
        int broken=mask(reactor.core()); applied &= broken;
        leaks.entrySet().removeIf(entry->!boundary.isBroken(entry.getValue()));
        inletLeaks.retainAll(leaks.keySet());
        if(dirty) {
            dirty=false; watchers.clear(); connected.retainAll(e.ports());
            for(var p:e.ports()) {
                var outside=p.relative(e.face(p));
                if(!l.isLoaded(p) || !l.isLoaded(outside)) { dirty=true; continue; }
                var c=component(l.getBlockState(p)); if(c==null) continue;
                ICapabilityInvalidationListener watcher=()->{dirty=true;return false;};
                watchers.add(watcher); l.registerCapabilityListener(outside,watcher);
                if(attached(l,p,e,c)) connected.add(p.immutable());
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
        }
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
                    l.playSound(null,p,SoundEvents.GENERIC_EXPLODE.value(),SoundSource.BLOCKS,5f,1.2f);
                }
            }
            applied|=bit; reactor.setChanged();
        }
        var openings=new ArrayList<BoundaryOpening>();
        for(var entry:leaks.entrySet()) {
            var p=entry.getKey(); var c=entry.getValue();
            double elevation=VesselWaterGeometry.levelAtHeight(e,p.getY()+.5-e.min().getY());
            // Inlet is the larger lower-vessel break. An outlet is the medium break.
            boolean inlet=inletLeaks.contains(p);
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
            openings.add(new BoundaryOpening(c,elevation,liquid*BoundaryStress.RATED_CORE_FLOW_KG_PER_S,
                    steam*BoundaryStress.RATED_STEAM_FLOW_KG_PER_S));
        }
        boundary.setOpenings(openings);
    }
    public void write(CompoundTag tag) {
        tag.putInt("AppliedBoundaryFailures",applied);
        tag.putLongArray("BoundaryInletLeaks",inletLeaks.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("BoundaryConnectedPorts",connected.stream().mapToLong(BlockPos::asLong).toArray());
        var entries=new ListTag();
        leaks.forEach((p,c)->{var t=new CompoundTag();t.putLong("pos",p.asLong());t.putString("component",c.name());entries.add(t);});
        tag.put("BoundaryOpenings",entries);
    }
    public void read(CompoundTag tag) {
        applied=tag.getInt("AppliedBoundaryFailures"); leaks.clear(); connected.clear(); inletLeaks.clear(); dirty=true;
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
    }
    public long[] leakPositions() { return leaks.keySet().stream().mapToLong(BlockPos::asLong).toArray(); }
}
