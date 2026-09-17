package dev.bwr.mod.flow;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorStructure;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Hardware capacity, separate from player speed commands. No automatic control. */
public final class RecirculationNetwork {
    private RecirculationNetwork() {}
    // Gameplay balance: ten paired jets fed by two external pumps reach rated core flow.
    public static final double PAIRED_JET_CAPACITY=.1;
    public static final double EXTERNAL_PUMP_CAPACITY=.5;
    public static final double INTERNAL_PUMP_CAPACITY=.1;
    public record Flow(double fraction,double maximum,int pairedJets,int internalPumps) {}
    private record Survey(long time,List<BlockPos> jets) {}
    private static final Map<Level,Map<BlockPos,Survey>> CACHE=new WeakHashMap<>();

    /** RIP mounting plane crosses the bottom head, with the motor below the vessel. */
    public static boolean installedRip(Level level,ReactorStructure vessel,BlockPos pos) {
        if(vessel==null || !level.isLoaded(pos)) return false;
        BlockState s=level.getBlockState(pos);
        if(!(s.getBlock() instanceof PumpAssemblyBlock b) || b.kind()!=PumpAssemblyBlock.Kind.RIP
                || !b.isFull(s) || !b.complete(level,pos,s)) return false;
        BlockPos min=vessel.interiorMin(),max=vessel.interiorMax();
        if(pos.getY()!=min.getY()-1) return false;
        boolean wetEndInside=false;
        for(int i=0;i<b.cellCount();i++) {
            BlockPos p=pos.offset(TurbineAssemblyBlock.turn(b.cellOffset(i),s.getValue(PumpAssemblyBlock.FACING)));
            // The mounting flange may overlap the outer head rim. Requiring all
            // four columns inside would always occupy a required CRD column.
            if(p.getX()<min.getX()-1 || p.getX()>max.getX()+1 || p.getZ()<min.getZ()-1 || p.getZ()>max.getZ()+1) return false;
            if(p.getY()==min.getY() && p.getX()>=min.getX() && p.getX()<=max.getX()
                    && p.getZ()>=min.getZ() && p.getZ()<=max.getZ()) wetEndInside=true;
        }
        return wetEndInside;
    }
    public static boolean installedJet(Level level,ReactorStructure vessel,BlockPos pos) {
        if(vessel==null || !level.isLoaded(pos)) return false;
        BlockState s=level.getBlockState(pos);
        if(!(s.getBlock() instanceof JetPumpBlock b) || !b.isFull(s)
                || s.getValue(PumpAssemblyBlock.CELL)!=b.controllerCell() || !b.complete(level,pos,s)) return false;
        BlockPos min=vessel.interiorMin(),max=vessel.interiorMax();
        // The lower outlets open into the plenum at the bottom of the interior.
        if(pos.getY()!=min.getY()) return false;
        for(int i=0;i<b.cellCount();i++) {
            BlockPos p=pos.offset(TurbineAssemblyBlock.turn(b.cellOffset(i),s.getValue(PumpAssemblyBlock.FACING)));
            if(p.getX()<min.getX() || p.getX()>max.getX() || p.getY()>max.getY() || p.getZ()<min.getZ() || p.getZ()>max.getZ()) return false;
            if(Math.min(Math.min(p.getX()-min.getX(),max.getX()-p.getX()),Math.min(p.getZ()-min.getZ(),max.getZ()-p.getZ()))>1) return false;
        }
        return true;
    }
    private static List<BlockPos> jets(Level level,ReactorControllerBlockEntity controller) {
        var vessel=controller.structure();
        if(vessel==null) return List.of();
        var map=CACHE.computeIfAbsent(level,k -> new HashMap<>());
        var previous=map.get(controller.getBlockPos());
        if(previous!=null && level.getGameTime()-previous.time()<20) return previous.jets();
        List<BlockPos> found=new ArrayList<>();
        BlockPos min=vessel.interiorMin(),max=vessel.interiorMax();
        for(BlockPos p:BlockPos.betweenClosed(min,new BlockPos(max.getX(),min.getY(),max.getZ())))
            if(installedJet(level,vessel,p)) found.add(p.immutable());
        map.put(controller.getBlockPos(),new Survey(level.getGameTime(),List.copyOf(found)));
        return found;
    }
    private static final class Circuit {
        final Set<BlockPos> pumps=new HashSet<>();
        double capacity;
    }
    /** Controllers survey their own vessel once formed; the index stores positions only. */
    public static List<ReactorControllerBlockEntity> controllers(Level level) {
        var index=CACHE.get(level);
        if(index==null) return List.of();
        List<ReactorControllerBlockEntity> result=new ArrayList<>();
        var iterator=index.keySet().iterator();
        while(iterator.hasNext()) {
            BlockPos p=iterator.next();
            if(!level.isLoaded(p)) continue;
            if(level.getBlockEntity(p) instanceof ReactorControllerBlockEntity c && c.isFormed()) result.add(c);
            else iterator.remove();
        }
        return result;
    }
    public static ReactorControllerBlockEntity connectedController(Level level,BlockPos pump) {
        ReactorControllerBlockEntity found=null;
        for(var c:controllers(level)) {
            if(!c.isFormed()) continue;
            boolean connected=false;
            for(BlockPos root:jets(level,c)) if(installedJet(level,c.structure(),root)) {
                var line=AssemblyPlumbing.trace(level,root,level.getBlockState(root),AssemblyPort.WATER_SUCTION);
                if(line.valid() && line.ends().contains(pump)) { connected=true; break; }
            }
            if(connected) { if(found!=null) return null; found=c; }
        }
        return found;
    }
    public static Flow measure(Level level,ReactorControllerBlockEntity controller,Collection<BlockPos> pumps) {
        List<Circuit> circuits=new ArrayList<>();
        int count=0,internal=0;
        double flow=0,maximum=0;
        for(BlockPos root:jets(level,controller)) {
            if(!installedJet(level,controller.structure(),root)) continue;
            var s=level.getBlockState(root);
            var line=AssemblyPlumbing.trace(level,root,s,AssemblyPort.WATER_SUCTION);
            if(!line.valid()) continue;
            Circuit circuit=new Circuit();
            for(BlockPos p:line.ends()) if(pumps.contains(p) && level.isLoaded(p)
                    && level.getBlockState(p).is(BwrBlocks.RECIRCULATION_PUMP.get())
                    && level.getBlockEntity(p) instanceof RecirculationPumpBlockEntity be
                    && controller.getBlockPos().equals(be.getControllerPos())) circuit.pumps.add(p);
            if(circuit.pumps.isEmpty()) continue;
            count++;
            circuit.capacity=PAIRED_JET_CAPACITY*s.getValue(JetPumpBlock.SIZE).flowMultiplier()*line.opening();
            // A shared pump can supply its rating once, even if the line branches.
            var iterator=circuits.iterator();
            while(iterator.hasNext()) {
                Circuit old=iterator.next();
                if(!Collections.disjoint(old.pumps,circuit.pumps)) {
                    circuit.pumps.addAll(old.pumps); circuit.capacity+=old.capacity; iterator.remove();
                }
            }
            circuits.add(circuit);
        }
        for(Circuit c:circuits) {
            double speed=0;
            for(BlockPos p:c.pumps) speed+=((RecirculationPumpBlockEntity)level.getBlockEntity(p)).getActualSpeedFraction()*EXTERNAL_PUMP_CAPACITY;
            flow+=Math.min(c.capacity,speed);
            maximum+=Math.min(c.capacity,c.pumps.size()*EXTERNAL_PUMP_CAPACITY);
        }
        for(BlockPos p:pumps) if(installedRip(level,controller.structure(),p)
                && level.getBlockEntity(p) instanceof RecirculationPumpBlockEntity be) {
            flow+=INTERNAL_PUMP_CAPACITY*be.getActualSpeedFraction(); maximum+=INTERNAL_PUMP_CAPACITY; internal++;
        }
        return new Flow(Math.min(1,flow),Math.min(1,maximum),count,internal);
    }
}
