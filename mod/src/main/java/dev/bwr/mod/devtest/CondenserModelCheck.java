package dev.bwr.mod.devtest;

import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.condenser.client.CondenserRenderer;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Exercises the real single-mesh renderer in all rotations, including its frustum bounds. */
public final class CondenserModelCheck {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void run(ModelEvent.BakingCompleted event){
        var model=event.getModels().get(CondenserRenderer.MODEL);check(model!=null&&model!=event.getModelManager().getMissingModel(),"missing condenser body");
        var quads=model.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null);check(quads.size()>10000,"condenser body is incomplete");
        for(var q:quads)check(!q.getSprite().contents().name().getPath().equals("missingno"),"missing condenser material");
        var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(BwrBlocks.ARABELLE_CONDENSER.get());
        var item=event.getModels().get(ModelResourceLocation.inventory(id));check(item!=null&&item!=event.getModelManager().getMissingModel()&&!item.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).isEmpty(),"missing condenser inventory mesh");
        var renderer=new CondenserRenderer(null);
        for(Direction facing:Direction.Plane.HORIZONTAL){
            var state=BwrBlocks.ARABELLE_CONDENSER.get().defaultBlockState().setValue(CondenserBlock.FACING,facing);
            var be=new CondenserBlockEntity(BlockPos.ZERO,state);var capture=new Capture(renderer.getRenderBoundingBox(be));
            renderer.render(be,0,new PoseStack(),type->{check(type==net.minecraft.client.renderer.Sheets.cutoutBlockSheet(),"BER used terrain shader instead of atlas entity shader");return capture;},15728880,0);check(capture.count==quads.size()*4,"renderer omitted/duplicated mesh");
            check(capture.maxY>5.9&&capture.maxX-capture.minX>6.5&&capture.maxZ-capture.minZ>6.5,"renderer scaled condenser incorrectly");
            check(capture.colors.size()>=5&&capture.blueVertices>500,"renderer discarded Blender material colors");
            check(renderer.shouldRender(be,renderer.getRenderBoundingBox(be).getCenter()),"frustum rejects center of large model");
            var child=new CondenserBlockEntity(BlockPos.ZERO,state.setValue(CondenserBlock.CONTROLLER,false));int before=capture.count;
            renderer.render(child,0,new PoseStack(),type->capture,15728880,0);check(capture.count==before,"child duplicated full condenser mesh");
        }
        LogUtils.getLogger().info("CONDENSER MODEL CHECK PASS: {} quads, four rendered rotations, bounds and inventory",quads.size());
    }
    static final class Capture implements VertexConsumer {
        final AABB bounds;int count,blueVertices;final java.util.Set<Integer> colors=new java.util.HashSet<>();
        float minX=Float.POSITIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY,maxY=0;
        Capture(AABB bounds){this.bounds=bounds.inflate(.002);}
        @Override public VertexConsumer addVertex(float x,float y,float z){
            check(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z)&&bounds.contains(x,y,z),"rendered condenser vertex outside owned bounds: "+x+","+y+","+z);
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);maxY=Math.max(maxY,y);count++;return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a){colors.add((r<<16)|(g<<8)|b);if(b>r+20&&g>r+10)blueVertices++;return this;}
        @Override public VertexConsumer setUv(float u,float v){return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
}
