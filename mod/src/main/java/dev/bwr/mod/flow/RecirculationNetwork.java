package dev.bwr.mod.flow;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorStructure;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import dev.bwr.core.flow.RecirculationSizing;

/** Hardware capacity, separate from player speed commands. No automatic control. */
public final class RecirculationNetwork {
    private RecirculationNetwork() {}
    public record Flow(double fraction,double maximum,int pairedJets,int internalPumps,int unmatchedJets,int externalPumps) {}
    private record Survey(long time,List<BlockPos> jets) {}
    private static final Map<Level,Map<BlockPos,Survey>> CACHE=new WeakHashMap<>();

    /** Geometry/lifecycle changes discard memoised geometry, never registry membership. */
    public static void invalidateSurvey(Level level,BlockPos controller) {
        var surveys=CACHE.get(level);
        if(surveys!=null) surveys.remove(controller);
    }

    public static RecirculationSizing.Sizing sizing(ReactorStructure vessel) {
        if (vessel == null) return RecirculationSizing.forVolume(RecirculationSizing.BASE_VOLUME);
        var min = vessel.interiorMin(); var max = vessel.interiorMax();
        return RecirculationSizing.forDimensions(max.getX()-min.getX()+1,
                max.getY()-min.getY()+1, max.getZ()-min.getZ()+1);
    }

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
                || s.getValue(PumpAssemblyBlock.CELL)!=b.controllerCell(s) || !b.complete(level,pos,s)) return false;
        BlockPos min=vessel.interiorMin(),max=vessel.interiorMax();
        // The lower outlets open into the plenum at the bottom of the interior.
        if(pos.getY()<min.getY() || pos.getY()>min.getY()+1) return false;
        for(int i=0;i<b.cellCount(s);i++) {
            BlockPos p=pos.offset(TurbineAssemblyBlock.turn(b.cellOffset(s,i),s.getValue(PumpAssemblyBlock.FACING)));
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
        for(BlockPos p:BlockPos.betweenClosed(min,new BlockPos(max.getX(),Math.min(max.getY(),min.getY()+1),max.getZ())))
            if(installedJet(level,vessel,p)) found.add(p.immutable());
        map.put(controller.getBlockPos(),new Survey(level.getGameTime(),List.copyOf(found)));
        return found;
    }
    /** Controllers survey their own vessel once formed; the index stores positions only. */
    public static List<ReactorControllerBlockEntity> controllers(Level level) {
        return dev.bwr.mod.reactor.FormedReactorRegistry.controllers(level);
    }
    public static ReactorControllerBlockEntity connectedController(Level level,BlockPos pump) {
        return RecirculationCircuit.controller(level,pump);
    }
    public static Flow measure(Level level,ReactorControllerBlockEntity controller,Collection<BlockPos> pumps) {
        int count=0,internal=0;double jetCapacity=0,drive=0,flow=0,maximum=0;
        double required=sizing(controller.structure()).requiredJets();
        var contributions=new LinkedHashMap<RecirculationPumpBlockEntity,Double>();
        Map<Set<BlockPos>,BlockPos> installed=new HashMap<>();
        for(BlockPos root:jets(level,controller)) if(installedJet(level,controller.structure(),root))
            installed.put(footprint(level,root),root);
        Set<BlockPos> matched=new HashSet<>();
        var vessel=controller.structure();
        if(vessel!=null) for(BlockPos root:installed.values().stream().sorted().toList()) {
            if(matched.contains(root))continue;
            BlockPos partner=null;
            Set<BlockPos> ownFootprint=footprint(level,root);
            // Across the X wall or Z wall, preserving the along-wall row. Also
            // retain the older diagonal/180-degree arrangement for existing builds.
            for(int mirror=0;mirror<3 && partner==null;mirror++) {
                Set<BlockPos> opposite=new HashSet<>();
                for(BlockPos p:ownFootprint)opposite.add(new BlockPos(
                        mirror!=1?vessel.interiorMin().getX()+vessel.interiorMax().getX()-p.getX():p.getX(),
                        p.getY(),mirror!=0?vessel.interiorMin().getZ()+vessel.interiorMax().getZ()-p.getZ():p.getZ()));
                BlockPos candidate=installed.get(opposite);
                if(mirror<2 && (!oppositeWalls(ownFootprint,opposite,vessel,mirror)
                        || level.getBlockState(root).getValue(PumpAssemblyBlock.FACING).getAxis()
                        !=(mirror==0?net.minecraft.core.Direction.Axis.X:net.minecraft.core.Direction.Axis.Z)))continue;
                if(candidate!=null && !candidate.equals(root) && !matched.contains(candidate)
                        && level.getBlockState(root).getValue(PumpAssemblyBlock.FACING).getOpposite()
                        ==level.getBlockState(candidate).getValue(PumpAssemblyBlock.FACING))partner=candidate;
            }
            if(partner==null)continue;
            var a=level.getBlockState(root);var b=level.getBlockState(partner);
            if(a.getValue(PumpAssemblyBlock.FACING).getOpposite()!=b.getValue(PumpAssemblyBlock.FACING))continue;
            matched.add(root);matched.add(partner);count+=2;
            jetCapacity+=2*Math.min(a.getValue(JetPumpBlock.SIZE).flowMultiplier(),b.getValue(JetPumpBlock.SIZE).flowMultiplier());
        }
        List<RecirculationPumpBlockEntity> external=new ArrayList<>();
        for(BlockPos p:new LinkedHashSet<>(pumps)) if(level.isLoaded(p) && level.getBlockEntity(p) instanceof RecirculationPumpBlockEntity be) {
            be.reportCoreFlowKgPerS(0);
            if(installedRip(level,controller.structure(),p)) {
                double part=RecirculationSizing.INTERNAL_PUMP_UNITS*be.getActualSpeedFraction();
                flow+=part; maximum+=RecirculationSizing.INTERNAL_PUMP_UNITS;internal++;contributions.put(be,part);
            } else if(connectedController(level,p)==controller) {
                external.add(be);drive+=be.getActualSpeedFraction();
            }
        }
        double capacity=RecirculationSizing.externalCapacityUnits(jetCapacity,external.size());
        double delivered=RecirculationSizing.externalDeliveryUnits(jetCapacity,
                external.stream().mapToDouble(RecirculationPumpBlockEntity::getActualSpeedFraction).toArray());
        for(var be:external)contributions.put(be,drive>0?delivered*be.getActualSpeedFraction()/drive:0);
        flow+=delivered;maximum+=capacity;
        // More installed hardware cannot report more than the solver's rated-flow ceiling.
        double reportDivisor=Math.max(required,flow);
        contributions.forEach((pump,units)->pump.reportCoreFlowKgPerS(
                units/reportDivisor*required*RecirculationSizing.JET_FLOW_KG_PER_S));
        return new Flow(Math.min(1,flow/required),Math.min(1,maximum/required),count,internal,installed.size()-count,external.size());
    }
    private static Set<BlockPos> footprint(Level level,BlockPos root) {
        var state=level.getBlockState(root);var block=(JetPumpBlock)state.getBlock();
        Set<BlockPos> result=new HashSet<>();
        for(int i=0;i<block.cellCount(state);i++)result.add(root.offset(TurbineAssemblyBlock.turn(block.cellOffset(state,i),state.getValue(PumpAssemblyBlock.FACING))));
        return result;
    }
    private static boolean oppositeWalls(Set<BlockPos> a,Set<BlockPos> b,ReactorStructure vessel,int axis) {
        int min=axis==0?vessel.interiorMin().getX():vessel.interiorMin().getZ();
        int max=axis==0?vessel.interiorMax().getX():vessel.interiorMax().getZ();
        return (a.stream().allMatch(p->(axis==0?p.getX():p.getZ())<=min+1)
                && b.stream().allMatch(p->(axis==0?p.getX():p.getZ())>=max-1))
                || (b.stream().allMatch(p->(axis==0?p.getX():p.getZ())<=min+1)
                && a.stream().allMatch(p->(axis==0?p.getX():p.getZ())>=max-1));
    }
}
