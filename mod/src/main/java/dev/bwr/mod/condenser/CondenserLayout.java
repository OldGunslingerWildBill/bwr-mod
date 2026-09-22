package dev.bwr.mod.condenser;

import com.google.gson.JsonParser;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import net.minecraft.core.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Blender-authored sparse occupancy: no giant blockstate table or duplicated mesh. */
public final class CondenserLayout {
    public static final CondenserLayout INSTANCE=new CondenserLayout("layout.json");
    public static final CondenserLayout LEGACY=new CondenserLayout("layout_legacy.json");
    public record Cell(int index,BlockPos local,CondenserBlock.Port role,VoxelShape[] shapes) {
        public VoxelShape shape(Direction facing){return shapes[facing.get2DDataValue()];}
    }
    public final BlockPos size,controller;
    public final List<Cell> cells,ports;
    private final Map<Integer,Cell> byIndex;
    private CondenserLayout(String resource){
        try(var stream=getClass().getResourceAsStream("/data/bwr/condenser/"+resource)){
            if(stream==null)throw new IllegalStateException("Missing condenser layout");
            var json=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            size=pos(json.getAsJsonArray("size"));controller=pos(json.getAsJsonArray("controller"));
            var entries=new LinkedHashMap<Integer,Cell>();
            for(var e:json.getAsJsonArray("cells")){
                var c=e.getAsJsonObject();var a=c.getAsJsonArray("bounds");
                double x=a.get(0).getAsDouble(),y=a.get(1).getAsDouble(),z=a.get(2).getAsDouble();
                double X=a.get(3).getAsDouble(),Y=a.get(4).getAsDouble(),Z=a.get(5).getAsDouble();
                var shapes=new VoxelShape[4];
                shapes[Direction.NORTH.get2DDataValue()]=Shapes.box(x,y,z,X,Y,Z);
                shapes[Direction.EAST.get2DDataValue()]=Shapes.box(1-Z,y,x,1-z,Y,X);
                shapes[Direction.SOUTH.get2DDataValue()]=Shapes.box(1-X,y,1-Z,1-x,Y,1-z);
                shapes[Direction.WEST.get2DDataValue()]=Shapes.box(z,y,1-X,Z,Y,1-x);
                int index=c.get("index").getAsInt();entries.put(index,new Cell(index,pos(c.getAsJsonArray("cell")),CondenserBlock.Port.valueOf(c.get("role").getAsString()),shapes));
            }
            byIndex=Map.copyOf(entries);cells=List.copyOf(entries.values());ports=cells.stream().filter(c->c.role()!=CondenserBlock.Port.NONE).toList();
        }catch(java.io.IOException e){throw new IllegalStateException("Cannot load condenser layout",e);}
    }
    private static BlockPos pos(com.google.gson.JsonArray a){return new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());}
    public int controllerIndex(){return controller.getX()+size.getX()*(controller.getZ()+size.getZ()*controller.getY());}
    public Cell cell(int index){return byIndex.get(index);}
    public BlockPos world(BlockPos root,Direction facing,Cell c){return root.offset(TurbineAssemblyBlock.turn(c.local().subtract(controller),facing));}
    public AABB bounds(BlockPos root,Direction facing){
        var a=root.offset(TurbineAssemblyBlock.turn(controller.multiply(-1),facing));
        var b=root.offset(TurbineAssemblyBlock.turn(size.offset(-1,-1,-1).subtract(controller),facing));
        return new AABB(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ()),
                Math.max(a.getX(),b.getX())+1,Math.max(a.getY(),b.getY())+1,Math.max(a.getZ(),b.getZ())+1);
    }
}
