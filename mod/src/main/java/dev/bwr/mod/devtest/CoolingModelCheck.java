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
        CoolingRenderer.testForceFull=true; // the full-mesh quad counts below must not depend on where the camera is (the machine render check bakes models with the camera far away)
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
        CoolingRenderer.testForceFull=false;
        // The distant stand-in for the hyperbolic shell: present, a small fraction of the full mesh, inside the tower's bounds, and chosen only far away.
        var lod=e.getModels().get(CoolingRenderer.lod(Design.NATURAL));check(lod!=null&&lod!=e.getModelManager().getMissingModel(),"missing natural-draft LOD");
        var lodQuads=lod.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null);
        var fullQuads=e.getModels().get(CoolingRenderer.model(Design.NATURAL)).getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null);
        check(lodQuads.size()>1000&&lodQuads.size()*6<fullQuads.size(),"LOD is not a much smaller mesh: "+lodQuads.size()+" vs "+fullQuads.size());
        for(var q:lodQuads)for(int v=0;v<4;v++){float x=Float.intBitsToFloat(q.getVertices()[v*8]),y=Float.intBitsToFloat(q.getVertices()[v*8+1]),z=Float.intBitsToFloat(q.getVertices()[v*8+2]);check(x>=-.003&&x<=25.003&&y>=-.003&&y<=36.003&&z>=-.003&&z<=25.003,"LOD vertex outside the tower bounds");}
        // Columbia's six round induced-draft towers each have six fans in a ring (photographs and the plant description); every pivot sits on that ring, six apart.
        var rotors=CoolingLayout.get(Design.MECHANICAL).rotors;check(rotors.size()==6,"round tower must have six fans, has "+rotors.size());
        var layoutCentre=CoolingLayout.get(Design.MECHANICAL).controller;for(var r:rotors)check(Math.abs(Math.hypot(r.x-layoutCentre.getX()-.5,r.z-layoutCentre.getZ()-.5)-4.9)<.01,"fan pivot off the ring");
        var natural=CoolingLayout.get(Design.NATURAL).bounds(BlockPos.ZERO,Direction.NORTH);
        check(!CoolingRenderer.useLod(Design.NATURAL,natural,natural.getCenter().add(30,0,0)),"LOD drawn up close");
        check(CoolingRenderer.useLod(Design.NATURAL,natural,natural.getCenter().add(CoolingRenderer.LOD_DISTANCE+natural.getXsize()/2+20,0,0)),"full shell drawn far away");
        check(!CoolingRenderer.useLod(Design.MECHANICAL,CoolingLayout.get(Design.MECHANICAL).bounds(BlockPos.ZERO,Direction.NORTH),new net.minecraft.world.phys.Vec3(1000,0,0)),"round tower got an LOD");
        LogUtils.getLogger().info("COOLING MODEL CHECK LOD: {} distant quads against {} full quads",lodQuads.size(),fullQuads.size());
        LogUtils.getLogger().info("COOLING MODEL CHECK PASS: {} body quads, five inventory models, {} fan instances and all rotations",total,CoolingLayout.get(Design.MECHANICAL).rotors.size());
    }
    private static class Capture implements VertexConsumer {
        final AABB bounds;int count;final java.util.Set<Integer> colors=new java.util.HashSet<>();Capture(AABB b){bounds=b.inflate(.003);}
        public VertexConsumer addVertex(float x,float y,float z){check(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z)&&bounds.contains(x,y,z),"vertex outside bounds "+x+","+y+","+z);count++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){colors.add((r<<16)|(g<<8)|b);return this;}
        public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
