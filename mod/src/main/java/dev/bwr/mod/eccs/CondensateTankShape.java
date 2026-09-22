package dev.bwr.mod.eccs;

import net.minecraft.core.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Cylinder occupancy and collision; dimensions are shared with the Blender renderer. */
public final class CondensateTankShape {
    public static final int MIN_DIAMETER=3,MAX_DIAMETER=15,MIN_HEIGHT=3,MAX_HEIGHT=24;
    private static final Map<Integer,CondensateTankShape> CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    public static boolean valid(int d,int h){return d>=MIN_DIAMETER&&d<=MAX_DIAMETER&&(d&1)==1&&h>=MIN_HEIGHT&&h<=MAX_HEIGHT;}
    public static CondensateTankShape get(int d,int h){if(!valid(d,h))throw new IllegalArgumentException("Invalid tank dimensions");return CACHE.computeIfAbsent(d*32+h,k->new CondensateTankShape(d,h));}
    public final int diameter,height,capacity;public final Map<BlockPos,VoxelShape> cells;public final Set<BlockPos> volume;
    private CondensateTankShape(int d,int h){
        diameter=d;height=h;capacity=(int)Math.floor(Math.PI*Math.pow(.47*d-.08,2)*(h-1.4)*1000);
        var shapes=new LinkedHashMap<BlockPos,VoxelShape>();var space=new HashSet<BlockPos>();int half=d/2;
        for(int x=-half;x<=half;x++)for(int z=-half;z<=half;z++){
            // A small regular grid clips collision to the circular footprint.
            VoxelShape disk=Shapes.empty(),wall=Shapes.empty();
            for(int sx=0;sx<8;sx++)for(int sz=0;sz<8;sz++){
                double X=x+sx/8.0-.5,Z=z+sz/8.0-.5,X1=X+.125,Z1=Z+.125;
                double nearest=Math.hypot(Math.max(0,Math.max(X,-X1)),Math.max(0,Math.max(Z,-Z1)));
                double farthest=Math.hypot(Math.max(Math.abs(X),Math.abs(X1)),Math.max(Math.abs(Z),Math.abs(Z1)));
                if(nearest<=.49*d)disk=Shapes.or(disk,Shapes.box(sx/8.0,0,sz/8.0,(sx+1)/8.0,1,(sz+1)/8.0));
                if(nearest<=.474*d&&farthest>=.457*d)wall=Shapes.or(wall,Shapes.box(sx/8.0,0,sz/8.0,(sx+1)/8.0,1,(sz+1)/8.0));
            }
            disk=disk.optimize();wall=wall.optimize();if(disk.isEmpty())continue;
            for(int y=0;y<h;y++){
                var p=new BlockPos(x,y,z);space.add(p);var shape=y==0||y>=h-2?disk:wall;
                if(!shape.isEmpty())shapes.put(p,shape);
            }
        }
        for(Direction face:Direction.Plane.HORIZONTAL)shapes.put(port(d,face),Shapes.block());
        // Ladder occupies one strip near the front-left side, inside the footprint.
        int lx=1,lz=-half;for(int y=1;y<h-1;y++)shapes.put(new BlockPos(lx,y,lz),Shapes.block());
        shapes.put(BlockPos.ZERO,Shapes.block());cells=Map.copyOf(shapes);volume=Set.copyOf(space);
    }
    public static BlockPos port(int diameter,Direction face){return BlockPos.ZERO.relative(face,diameter/2).above();}
    public CondensateStorageTankBlock.Port role(BlockPos offset){for(var d:Direction.Plane.HORIZONTAL)if(offset.equals(port(diameter,d)))return CondensateStorageTankBlock.Port.valueOf(d.name());return CondensateStorageTankBlock.Port.NONE;}
}
