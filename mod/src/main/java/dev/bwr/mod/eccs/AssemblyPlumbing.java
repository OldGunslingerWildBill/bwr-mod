package dev.bwr.mod.eccs;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SteamLineNetwork;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Bounded, loaded-chunk-only routing from an individual process flange. */
public final class AssemblyPlumbing {
    private AssemblyPlumbing() {}
    public record Line(List<BlockPos> ends, Set<BlockPos> nodes, boolean valid, double opening) {}

    /** Per-controller routes. No inventories, block entities or flow allowances are cached. */
    public static final class Cache {
        /** New branches/previously unloaded frontiers are discovered within 0.25 seconds. */
        public static final int REFRESH_TICKS = 5;
        private final EnumMap<AssemblyPort, CachedLine> lines = new EnumMap<>(AssemblyPort.class);
        private long rebuilds;

        public Line trace(Level level, BlockPos root, BlockState state, AssemblyPort role) {
            long now = level.getGameTime();
            CachedLine previous = lines.get(role);
            if (previous != null && previous.level == level && previous.root.equals(root)
                    && previous.state == state && now >= previous.time && now - previous.time < REFRESH_TICKS
                    && previous.unchanged(level)) return previous.line;
            Map<BlockPos, Dependency> dependencies = new LinkedHashMap<>();
            Line line = AssemblyPlumbing.trace(level, root, state, role, dependencies);
            lines.put(role, new CachedLine(level, root.immutable(), state, now, line,
                    dependencies.values().toArray(Dependency[]::new)));
            rebuilds++;
            return line;
        }

        /** Diagnostic counter used by the dev-runtime regression/benchmark. */
        public long rebuildCount() { return rebuilds; }
    }

    private record CachedLine(Level level, BlockPos root, BlockState state, long time,
                              Line line, Dependency[] dependencies) {
        boolean unchanged(Level level) {
            for (Dependency dependency : dependencies) if (!dependency.unchanged(level)) return false;
            return true;
        }
    }

    private record Dependency(BlockPos pos, BlockState state, double valveOpening) {
        boolean unchanged(Level level) {
            return level.isLoaded(pos) && level.getBlockState(pos) == state
                    && Double.compare(valveOpening, AssemblyPlumbing.valveOpening(level, pos, state)) == 0;
        }
    }

    private static double valveOpening(Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof dev.bwr.mod.steam.TurbineValveBlock
                && level.getBlockEntity(pos) instanceof dev.bwr.mod.steam.TurbineValveBlockEntity valve) return valve.position();
        if (state.is(BwrBlocks.MSIV.get())
                && level.getBlockEntity(pos) instanceof MainSteamIsolationValveBlockEntity valve) return valve.getPosition();
        return 1;
    }

    private static void remember(Map<BlockPos, Dependency> dependencies, Level level, BlockPos pos, BlockState state) {
        if (dependencies != null) dependencies.computeIfAbsent(pos,
                p -> new Dependency(p.immutable(), state, valveOpening(level, p, state)));
    }
    public static boolean isWaterEndpoint(BlockState state) {
        return state.getBlock() instanceof dev.bwr.mod.reactor.RpvWaterInjectionPortBlock
                || state.getBlock() instanceof dev.bwr.mod.suppression.SuppressionPoolPortBlock
                || state.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock
                || state.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get())
                || state.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get());
    }
    private static boolean conduit(BlockState s, AssemblyPort role) {
        return role.isSteam() ? s.is(BwrBlocks.PRESSURISED_TUBE.get()) || s.is(BwrBlocks.MSIV.get()) || s.getBlock() instanceof dev.bwr.mod.steam.TurbineValveBlock
                : s.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get());
    }
    private static boolean accepts(BlockState s, Direction face, AssemblyPort role) {
        return role.isSteam() ? SteamLineNetwork.acceptsLineOn(s, face)
                : dev.bwr.mod.water.WaterLineNetwork.acceptsLineOn(s, face);
    }
    public static Line trace(Level level, BlockPos root, BlockState state, AssemblyPort role) {
        return trace(level, root, state, role, null);
    }

    private static Line trace(Level level, BlockPos root, BlockState state, AssemblyPort role,
                              Map<BlockPos, Dependency> dependencies) {
        ProcessAssembly block=(ProcessAssembly)state.getBlock();
        boolean jet=state.getBlock() instanceof dev.bwr.mod.flow.JetPumpBlock;
        if (!block.hasPort(role)) return new Line(List.of(),Set.of(),false,0);
        BlockPos start=block.portPosition(root,state,role);
        if(!level.isLoaded(start))return new Line(List.of(),Set.of(),false,0);
        remember(dependencies, level, start, level.getBlockState(start));
        Direction outward=block.portFace(state,role);
        Set<BlockPos> seen=new HashSet<>();
        Set<BlockPos> ends=new LinkedHashSet<>();
        Map<BlockPos,Double> best=new HashMap<>();best.put(start,1.0);
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        seen.add(start); queue.add(start);
        boolean valid=true;
        while(!queue.isEmpty()) {
            BlockPos here=queue.remove();
            BlockState current=level.getBlockState(here);
            for(Direction d:Direction.values()) {
                if(here.equals(start) && d!=outward) continue;
                BlockPos next=here.relative(d);
                if(!level.isLoaded(next)) continue;
                BlockState s=level.getBlockState(next);
                if(!accepts(current,d,role)
                        || !accepts(s,d.getOpposite(),role)) continue;
                // Watch closed valves too: opening one must not wait for the periodic survey.
                remember(dependencies, level, next, s);
                double opening=best.get(here);
                if(level.getBlockEntity(next) instanceof dev.bwr.mod.steam.TurbineValveBlockEntity valve)opening=Math.min(opening,valve.position());
                if(level.getBlockEntity(next) instanceof MainSteamIsolationValveBlockEntity valve)opening=Math.min(opening,valve.getPosition());
                if(opening<=0||best.getOrDefault(next,-1.0)>=opening)continue;
                best.put(next,opening);
                seen.add(next);
                if(seen.size()>SteamLineNetwork.MAX_LINE_BLOCKS) return new Line(List.of(),Set.copyOf(seen),false,0);
                if(s.getBlock() instanceof dev.bwr.mod.suppression.RhrHeatExchangerBlock) {
                    if(next.equals(start))continue;
                    if(role==AssemblyPort.WATER_DISCHARGE&&d.getOpposite()==dev.bwr.mod.suppression.RhrHeatExchangerBlock.primaryIn(s))ends.add(next);
                    else valid=false;
                    continue;
                }
                if(s.getBlock() instanceof ProcessAssembly assembly) {
                    // A tee feeding like ports is allowed. Joining incompatible roles is not.
                    if(assembly.portAt(s,d.getOpposite())!=role || (s.getBlock() instanceof dev.bwr.mod.flow.JetPumpBlock)!=jet) valid=false;
                    continue;
                }
                if(conduit(s,role) || (role.isSteam() && s.is(BwrBlocks.SAFETY_RELIEF_VALVE.get()))) {
                    queue.add(next); continue;
                }
                boolean accepted=switch(role) {
                    case STEAM_INLET -> s.is(BwrBlocks.RPV_STEAM_OUTLET.get());
                    case STEAM_EXHAUST -> s.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get()) || s.is(BwrBlocks.TURBINE_STEAM_OUTLET.get());
                    case WATER_SUCTION -> jet ? s.is(BwrBlocks.RECIRCULATION_PUMP.get()) : s.is(BwrBlocks.CONDENSATE_STORAGE_TANK.get()) || s.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get()) || s.is(BwrBlocks.SUPPRESSION_POOL_SUCTION.get());
                    case WATER_DISCHARGE -> s.is(BwrBlocks.RPV_WATER_INJECTION_PORT.get()) || s.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get()) || s.is(BwrBlocks.SUPPRESSION_POOL_RETURN.get());
                };
                if(accepted) ends.add(next);
                else if(s.is(BwrBlocks.RECIRCULATION_PUMP.get()) || isWaterEndpoint(s) || s.is(BwrBlocks.RPV_STEAM_OUTLET.get())
                        || s.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get())
                        || s.is(BwrBlocks.SAFETY_RELIEF_VALVE.get())
                        || (s.is(BwrBlocks.TURBINE_STEAM_OUTLET.get()) && role!=AssemblyPort.STEAM_INLET)) valid=false;
            }
        }
        double opening=ends.stream().mapToDouble(best::get).max().orElse(role.isSteam()?0:1);
        return new Line(List.copyOf(ends),Set.copyOf(seen),valid,opening);
    }
    public static <T> T endpoint(Level level,Line line,Class<T> type) {
        if(!line.valid()) return null;
        T found=null;
        for(BlockPos p:line.ends()) {
            if(!level.isLoaded(p))return null;
            Object candidate=level.getBlockEntity(p);
            if(candidate instanceof dev.bwr.mod.suppression.SuppressionPoolPortBlockEntity port)candidate=port.owner();
            if(!type.isInstance(candidate))return null;
            T next=type.cast(candidate);
            if(next instanceof CondensateStorageTankBlockEntity tank){var owner=tank.owner();if(owner==null||!owner.ready())return null;next=type.cast(owner);}
            if(found!=null && found!=next) return null;
            found=next;
        }
        return found;
    }
    /** Several injection nozzles on one vessel are legal; two recipient vessels are ambiguous. */
    public static ReactorControllerBlockEntity waterReceiver(Level level, Line line) {
        if (!line.valid()) return null;
        ReactorControllerBlockEntity found = null;
        for (BlockPos p : line.ends()) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof dev.bwr.mod.reactor.RpvWaterInjectionPortBlockEntity port)) return null;
            if (level.getBlockState(p).getBlock() instanceof dev.bwr.mod.reactor.RecirculationPortBlock) return null;
            var next = port.controller();
            if (next == null || found != null && found != next) return null;
            found = next;
        }
        return found;
    }
    public static List<RpvSteamOutletBlockEntity> nozzles(Level level,Line line) {
        List<RpvSteamOutletBlockEntity> result=new ArrayList<>();
        if(line.valid()) for(BlockPos p:line.ends())
            if(level.isLoaded(p) && level.getBlockEntity(p) instanceof RpvSteamOutletBlockEntity n
                    && n.isPartOfFormedReactor() && n.getPosition()>0) result.add(n);
        return result;
    }
    public static ReactorControllerBlockEntity source(Level level,List<RpvSteamOutletBlockEntity> nozzles) {
        ReactorControllerBlockEntity found=null;
        for(var n:nozzles) {
            BlockPos p=n.getControllerPos();
            if(p!=null && level.isLoaded(p) && level.getBlockEntity(p) instanceof ReactorControllerBlockEntity c && c.isFormed()) {
                if(found!=null && found!=c) return null;
                found=c;
            }
        }
        return found;
    }
    /** Inspect only block entities in already loaded chunks, never a cube of block reads. */
    public static <T> List<T> nearby(Level level,BlockPos at,Class<T> type) {
        List<T> result=new ArrayList<>();
        if(!(level instanceof ServerLevel server)) return result;
        for(int x=(at.getX()-24)>>4; x<=(at.getX()+24)>>4; x++)
            for(int z=(at.getZ()-24)>>4; z<=(at.getZ()+24)>>4; z++) {
                var chunk=server.getChunkSource().getChunkNow(x,z);
                if(chunk==null) continue;
                for(BlockEntity be:chunk.getBlockEntities().values()) {
                    BlockPos p=be.getBlockPos();
                    if(type.isInstance(be) && Math.abs(p.getX()-at.getX())<=24
                            && Math.abs(p.getY()-at.getY())<=24 && Math.abs(p.getZ()-at.getZ())<=24) result.add(type.cast(be));
                }
            }
        return result;
    }
    public static SuppressionPoolBlockEntity exhaustPool(Level level,Line line) {
        if(!line.valid()) return null;
        SuppressionPoolBlockEntity found=null;
        for(BlockPos q:line.ends()) for(var pool:nearby(level,q,SuppressionPoolBlockEntity.class))
            if(pool.ownsQuencher(q)) {
                if(found!=null && found!=pool) return null;
                found=pool;
            }
        return found;
    }
}
