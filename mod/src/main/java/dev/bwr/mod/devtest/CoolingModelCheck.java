package dev.bwr.mod.devtest;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.cooling.client.CoolingRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
public final class CoolingModelCheck {
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void run(ModelEvent.BakingCompleted e){
        var fan=e.getModels().get(CoolingRenderer.FAN);check(fan!=null,"missing fan");int fanQuads=fan.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).size();check(fanQuads>50,"empty fan");
        int total=0;for(var d:Design.values()){
            var model=e.getModels().get(CoolingRenderer.model(d));check(model!=null&&model!=e.getModelManager().getMissingModel(),"missing body "+d);
            var quads=model.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null);check(quads.size()>3000,"incomplete mesh "+d);total+=quads.size();
            for(var q:quads)check(!q.getSprite().contents().name().getPath().equals("missingno"),"missing material "+d);
            var block=CoolingRuntimeCheck.block(d);var item=e.getModels().get(ModelResourceLocation.inventory(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)));check(item!=null&&item!=e.getModelManager().getMissingModel()&&!item.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).isEmpty(),"missing item "+d);
            var renderer=new CoolingRenderer(null);
            for(var facing:Direction.Plane.HORIZONTAL){
                var s=block.defaultBlockState().setValue(CoolingBlock.FACING,facing);var be=new CoolingBlockEntity(BlockPos.ZERO,s);var capture=new Capture(renderer.getRenderBoundingBox(be));
                renderer.render(be,0,new PoseStack(),type->{check(type==net.minecraft.client.renderer.Sheets.cutoutBlockSheet(),"wrong render sheet");return capture;},15728880,0);
                check(capture.count==4*(quads.size()+be.layout().rotors.size()*fanQuads),"missing/duplicate mesh "+d);check(capture.colors.size()>=3,"lost material colors "+d);
                var child=new CoolingBlockEntity(BlockPos.ZERO,s.setValue(CoolingBlock.CONTROLLER,false));int before=capture.count;renderer.render(child,0,new PoseStack(),type->capture,15728880,0);check(capture.count==before,"child renders full model");
            }
        }
        LogUtils.getLogger().info("COOLING MODEL CHECK PASS: {} body quads, five inventory models, {} fan instances and all rotations",total,CoolingLayout.get(Design.MECHANICAL).rotors.size());
    }
    private static class Capture implements VertexConsumer {
        final AABB bounds;int count;final java.util.Set<Integer> colors=new java.util.HashSet<>();Capture(AABB b){bounds=b.inflate(.003);}
        public VertexConsumer addVertex(float x,float y,float z){check(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z)&&bounds.contains(x,y,z),"vertex outside bounds "+x+","+y+","+z);count++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){colors.add((r<<16)|(g<<8)|b);return this;}
        public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
