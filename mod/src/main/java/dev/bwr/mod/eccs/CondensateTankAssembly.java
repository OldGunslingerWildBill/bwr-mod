package dev.bwr.mod.eccs;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import java.util.*;
import static dev.bwr.mod.eccs.CondensateStorageTankBlock.*;

/** Automatic box-shell formation; preflight precedes inventory or world edits. */
public final class CondensateTankAssembly {
    static final ThreadLocal<Boolean> EDITING=ThreadLocal.withInitial(()->false);
    /** Called after each player placement. Only a complete connected box is converted. */
    public static boolean tryAssemble(net.minecraft.world.level.Level l,BlockPos placed,ServerPlayer player){
        if(l.isClientSide()||EDITING.get())return false;
        var block=l.getBlockState(placed).getBlock();
        if(!(block instanceof CondensateStorageTankBlock)||l.getBlockState(placed).getValue(ASSEMBLED))return false;
        Set<BlockPos> found=new HashSet<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();queue.add(placed);found.add(placed);
        int minX=placed.getX(),maxX=minX,minY=placed.getY(),maxY=minY,minZ=placed.getZ(),maxZ=minZ;
        while(!queue.isEmpty()){
            var p=queue.remove();
            minX=Math.min(minX,p.getX());maxX=Math.max(maxX,p.getX());minY=Math.min(minY,p.getY());maxY=Math.max(maxY,p.getY());minZ=Math.min(minZ,p.getZ());maxZ=Math.max(maxZ,p.getZ());
            if(maxX-minX>=15||maxZ-minZ>=15||maxY-minY>=24||found.size()>5400)return false;
            for(var face:Direction.values()){
                var next=p.relative(face);if(!l.isLoaded(next))return false;
                var state=l.getBlockState(next);
                if(state.is(block)&&!state.getValue(ASSEMBLED)&&found.add(next))queue.add(next);
            }
        }
        int d=maxX-minX+1,h=maxY-minY+1;
        if(maxZ-minZ+1!=d||!CondensateTankShape.valid(d,h))return false;
        var shape=CondensateTankShape.get(d,h);var root=new BlockPos(minX+d/2,minY,minZ+d/2);
        double water=0;
        for(var p:BlockPos.betweenClosed(minX,minY,minZ,maxX,maxY,maxZ)){
            if(!l.isLoaded(p)||!dev.bwr.mod.world.AssemblyAccess.permitted(l,player,p))return false;
            boolean boundary=p.getX()==minX||p.getX()==maxX||p.getY()==minY||p.getY()==maxY||p.getZ()==minZ||p.getZ()==maxZ;
            if(boundary&&!found.contains(p))return false;
            if(found.contains(p)){
                if(!(l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity tank)||tank.assembled())return false;
                water+=tank.availableWaterKg();
            }else if(!l.getBlockState(p).isAir())return false;
        }
        if(water>shape.capacity)return false; // Never silently discard water during formation.
        for(var entry:shape.cells.entrySet())if(!found.contains(root.offset(entry.getKey()))
                &&!l.isUnobstructed(null,entry.getValue().move(root.getX()+entry.getKey().getX(),root.getY()+entry.getKey().getY(),root.getZ()+entry.getKey().getZ())))return false;
        var owner=(CondensateStorageTankBlockEntity)l.getBlockEntity(root);
        EDITING.set(true);
        try{
            owner.configure(d,h,found.size());owner.restoreAvailableWater(water);
            for(var p:found)if(!shape.cells.containsKey(p.subtract(root)))l.removeBlock(p,false);
            for(var off:shape.cells.keySet()){
                var p=root.offset(off);var state=block.defaultBlockState().setValue(ASSEMBLED,true).setValue(CONTROLLER,off.equals(BlockPos.ZERO)).setValue(PORT,shape.role(off));
                l.setBlock(p,state,Block.UPDATE_CLIENTS);
                var part=(CondensateStorageTankBlockEntity)l.getBlockEntity(p);part.bind(owner);
                l.invalidateCapabilities(p);l.sendBlockUpdated(p,state,state,Block.UPDATE_CLIENTS);
            }
            owner.markStructureDirty();l.scheduleTick(root,block,20);
            for(var face:Direction.Plane.HORIZONTAL)l.updateNeighborsAt(root.offset(CondensateTankShape.port(d,face)),block);
        }finally{EDITING.set(false);}
        return true;
    }
    public static String resize(CondensateStorageTankBlockEntity owner,ServerPlayer player,int d,int h){
        if(!CondensateTankShape.valid(d,h))return "Diameter: odd 3-15; height: 3-24 blocks.";
        var l=owner.getLevel();if(l==null||owner.owner()!=owner)return "Tank controller is unavailable.";
        var shape=CondensateTankShape.get(d,h);var block=owner.getBlockState().getBlock();var root=owner.getBlockPos();
        if(owner.storedKg()>shape.capacity)return "Drain water first or choose a larger tank (capacity "+shape.capacity+" kg).";
        var old=owner.assembled()?CondensateTankShape.get(owner.diameter,owner.height).cells.keySet():Set.of(BlockPos.ZERO);
        for(var off:old){
            var p=root.offset(off);
            if(!l.isLoaded(p))return "Load the entire existing tank before resizing.";
            if(!l.mayInteract(player,p))return "You cannot resize protected tank parts.";
        }
        for(var off:shape.volume){var p=root.offset(off);
            if(!l.isLoaded(p)||l.isOutsideBuildHeight(p)||!l.getWorldBorder().isWithinBounds(p))return "Tank needs loaded space inside the world limits.";
            if(!player.mayBuild()||!l.mayInteract(player,p)||!player.mayUseItemAt(p,Direction.UP,new ItemStack(block)))return "You cannot build here.";
            boolean own=l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part&&part.belongsTo(owner);
            if(!own&&!l.getBlockState(p).canBeReplaced())return "Tank space is obstructed at "+p.toShortString()+".";
        }
        int needed=shape.cells.size()-owner.paidBlocks;
        if(!player.isCreative()&&count(player,block.asItem())<needed)return "Need "+needed+" additional Condensate Storage Tank blocks.";
        // Check players/entities against new shell cells; retain the hollow interior.
        for(var e:shape.cells.entrySet())if(!old.contains(e.getKey())&&!l.isUnobstructed(null,e.getValue().move(root.getX()+e.getKey().getX(),root.getY()+e.getKey().getY(),root.getZ()+e.getKey().getZ())))return "Move entities away from the new tank shell.";
        EDITING.set(true);
        try{
            for(var off:old)if(!shape.cells.containsKey(off)){var p=root.offset(off);if(l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part&&part.belongsTo(owner))l.removeBlock(p,false);}
            owner.configure(d,h,shape.cells.size());
            for(var off:shape.cells.keySet()){
                var p=root.offset(off);var s=block.defaultBlockState().setValue(ASSEMBLED,true).setValue(CONTROLLER,off.equals(BlockPos.ZERO)).setValue(PORT,shape.role(off));
                l.setBlock(p,s,Block.UPDATE_CLIENTS);
                if(l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part){part.bind(owner);l.invalidateCapabilities(p);l.sendBlockUpdated(p,s,s,Block.UPDATE_CLIENTS);}
            }
            owner.markStructureDirty();
            l.scheduleTick(root,block,20);
            if(!player.isCreative()){if(needed>0)take(player,block.asItem(),needed);else if(needed<0)give(player,block.asItem(),-needed);}
            for(var face:Direction.Plane.HORIZONTAL)l.updateNeighborsAt(root.offset(CondensateTankShape.port(d,face)),block);
        }finally{EDITING.set(false);}
        return "Assembled "+d+" x "+h+" cylindrical tank; "+shape.capacity+" kg capacity.";
    }
    private static int count(ServerPlayer p,net.minecraft.world.item.Item item){int n=0;for(int i=0;i<p.getInventory().getContainerSize();i++){var s=p.getInventory().getItem(i);if(s.is(item))n+=s.getCount();}return n;}
    private static void take(ServerPlayer p,net.minecraft.world.item.Item item,int n){for(int i=0;i<p.getInventory().getContainerSize()&&n>0;i++){var s=p.getInventory().getItem(i);if(s.is(item)){int k=Math.min(s.getCount(),n);s.shrink(k);n-=k;}}p.getInventory().setChanged();}
    private static void give(ServerPlayer p,net.minecraft.world.item.Item item,int n){while(n>0){var s=new ItemStack(item,Math.min(64,n));n-=s.getCount();if(!p.getInventory().add(s))p.drop(s,false);}p.getInventory().setChanged();}
    static void remove(CondensateStorageTankBlockEntity owner,BlockPos removed){
        if(EDITING.get()||!owner.assembled())return;var l=owner.getLevel();EDITING.set(true);
        try{for(var off:CondensateTankShape.get(owner.diameter,owner.height).cells.keySet()){var p=owner.getBlockPos().offset(off);if(!p.equals(removed)&&l.isLoaded(p)&&l.getBlockEntity(p) instanceof CondensateStorageTankBlockEntity part&&part.belongsTo(owner))l.removeBlock(p,false);}}
        finally{EDITING.set(false);}
    }
}
