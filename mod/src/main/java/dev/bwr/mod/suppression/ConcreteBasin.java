package dev.bwr.mod.suppression;

import dev.bwr.mod.eccs.AssemblyPlumbing;
import dev.bwr.mod.registry.BwrBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import java.util.*;

/** Player-built concrete tank; existing open basins remain compatible. Surveys never alter blocks. */
public final class ConcreteBasin {
    public record Layout(BlockPos min,BlockPos max,LongOpenHashSet cells,LongOpenHashSet water,
                         Set<BlockPos> ports,boolean enclosed,String problem) {
        public int volume() { return cells.size(); }
    }
    public static boolean shell(BlockState s) {
        return s.is(BwrBlocks.SUPPRESSION_POOL_WALL.get())||s.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get())
                ||s.getBlock() instanceof SuppressionPoolPortBlock||s.getBlock() instanceof SuppressionPoolSteamPortBlock;
    }
    public static void changed(Level level,BlockPos at,boolean broken) {
        if(level.isClientSide())return;
        for(var pool:AssemblyPlumbing.nearby(level,at,SuppressionPoolBlockEntity.class)) {
            if(pool.isConcreteBasin()&&!pool.touchesConcreteBounds(at))continue;
            if(broken&&pool.isConcreteBasin())pool.invalidateConcrete();else pool.markStructureDirty();
        }
    }
    public static Layout inspect(Level level,BlockPos controller,boolean required,boolean wasEnclosed) {
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();var ports=new HashSet<BlockPos>();
        queue.add(controller);seen.add(controller);
        BlockPos min=controller,max=controller;boolean missing=false;
        while(!queue.isEmpty()) {
            var p=queue.remove();var s=level.getBlockState(p);
            if(s.getBlock() instanceof SuppressionPoolPortBlock||s.getBlock() instanceof SuppressionPoolSteamPortBlock)ports.add(p);
            min=new BlockPos(Math.min(min.getX(),p.getX()),Math.min(min.getY(),p.getY()),Math.min(min.getZ(),p.getZ()));
            max=new BlockPos(Math.max(max.getX(),p.getX()),Math.max(max.getY(),p.getY()),Math.max(max.getZ(),p.getZ()));
            if(seen.size()>8192)return failure(min,max,ports,"Concrete basin shell is too large.");
            for(Direction d:Direction.values()) {
                var n=p.relative(d);if(!level.isLoaded(n)){missing=true;continue;}
                if(shell(level.getBlockState(n))&&seen.add(n))queue.add(n);
            }
        }
        // Adding a dedicated water port opts an old dug basin into concrete-shell validation.
        if(ports.isEmpty()&&!required)return null;
        if(missing)return failure(min,max,ports,"Concrete basin is waiting for its chunks to load.");
        int w=max.getX()-min.getX()+1,h=max.getY()-min.getY()+1,d=max.getZ()-min.getZ()+1;
        if(w<5||w>25||d<5||d>25||h<4||h>16)return failure(min,max,ports,"Build a concrete tank: width/depth 5-25 and height 4-16 blocks.");
        boolean enclosed=wasEnclosed||ports.stream().anyMatch(p->level.getBlockState(p).getBlock() instanceof SuppressionPoolSteamPortBlock);
        for(int x=min.getX()+1;x<max.getX();x++)for(int z=min.getZ()+1;z<max.getZ();z++)
            if(shell(level.getBlockState(new BlockPos(x,max.getY(),z))))enclosed=true;
        var cells=new LongOpenHashSet();var water=new LongOpenHashSet();int controllers=0;
        for(var p:BlockPos.betweenClosed(min,max)) {
            if(!level.isLoaded(p))return failure(min,max,ports,"Concrete basin is waiting for its chunks to load.");
            var s=level.getBlockState(p);
            boolean wall=p.getX()==min.getX()||p.getX()==max.getX()||p.getZ()==min.getZ()||p.getZ()==max.getZ();
            if(wall||p.getY()==min.getY()||enclosed&&p.getY()==max.getY()) {
                if(!shell(s))return failure(min,max,ports,"Complete the concrete floor, four walls and roof; missing shell at "+p.toShortString());
                if(s.is(BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get()))controllers++;
                if(s.getBlock() instanceof SuppressionPoolPortBlock||s.getBlock() instanceof SuppressionPoolSteamPortBlock) {
                    Direction face=s.getValue(SuppressionPoolPortBlock.FACING);var n=p.relative(face);
                    if(!wall||p.getY()==min.getY()||p.getY()==max.getY()
                            ||n.getX()>=min.getX()&&n.getX()<=max.getX()&&n.getZ()>=min.getZ()&&n.getZ()<=max.getZ())
                        return failure(min,max,ports,"Ports must face outward on a side wall, below the roof and above the floor.");
                }
            } else {
                boolean source=level.getFluidState(p).is(Fluids.WATER)&&level.getFluidState(p).isSource();
                boolean hardware=s.is(BwrBlocks.SUPPRESSION_POOL_QUENCHER.get())||s.is(BwrBlocks.PRESSURISED_TUBE.get());
                if(!s.isAir()&&!source&&!hardware)return failure(min,max,ports,"Clear the basin interior at "+p.toShortString());
                if(p.getY()<max.getY()) {cells.add(p.asLong());if(source)water.add(p.asLong());}
            }
        }
        if(controllers!=1)return failure(min,max,ports,"Use exactly one controller in the concrete basin shell.");
        if(cells.size()<64)return failure(min,max,ports,"Concrete basin needs at least 64 interior water spaces below the rim.");
        return new Layout(min,max,cells,water,Set.copyOf(ports),enclosed,null);
    }
    private static Layout failure(BlockPos min,BlockPos max,Set<BlockPos> ports,String error) {
        return new Layout(min,max,new LongOpenHashSet(),new LongOpenHashSet(),Set.copyOf(ports),false,error);
    }
}
