package dev.bwr.mod.suppression.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.suppression.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.model.data.ModelData;

/** One controller renders the metered water and Blender-authored spray header components. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class SuppressionPoolRenderer implements BlockEntityRenderer<SuppressionPoolBlockEntity> {
    private static final String[] PARTS={"water_surface","spray_rail","spray_riser"};
    public static ModelResourceLocation model(String part){return ModelResourceLocation.standalone(BwrMod.id("block/suppression/"+part+"/body"));}
    public SuppressionPoolRenderer(BlockEntityRendererProvider.Context context){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){for(var part:PARTS)event.register(model(part));}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){event.registerBlockEntityRenderer(BwrBlockEntities.SUPPRESSION_POOL.get(),SuppressionPoolRenderer::new);}
    @Override public void render(SuppressionPoolBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(!be.visualFormed||be.visualMin==null||be.visualMax==null||be.getLevel()==null)return;
        var min=be.visualMin;var max=be.visualMax;var root=be.getBlockPos();var level=be.getLevel();
        light=LevelRenderer.getLightColor(level,new BlockPos(root.getX(),max.getY()+1,root.getZ()));
        pose.pushPose();pose.translate(-root.getX(),-root.getY(),-root.getZ());
        double depth=(max.getY()-min.getY()-1)*Math.min(1,be.pool().getLevelFraction());
        if(depth>0)part("water_surface",pose,buffers,light,overlay,min.getX()+1,min.getY()+1+depth,min.getZ()+1,max.getX()-min.getX()-1,depth,max.getZ()-min.getZ()-1);
        // A pipe inlet feeds its own riser and rail; multiple return ports support independent headers.
        for(long packed:be.visualReturns) {
            var p=BlockPos.of(packed);var state=level.getBlockState(p);
            if(!state.is(BwrBlocks.SUPPRESSION_POOL_RETURN.get()))continue;
            var face=state.getValue(SuppressionPoolPortBlock.FACING);var inside=p.relative(face.getOpposite());
            double top=max.getY()+1.4;
            part("spray_riser",pose,buffers,light,overlay,inside.getX(),p.getY()+.5,inside.getZ(),1,top-p.getY()-.5,1);
            // Short horizontal feed from the wall penetration to the riser.
            pose.pushPose();pose.translate(p.getX()+.5,p.getY()+.5,p.getZ()+.5);
            var d=face.getOpposite();pose.mulPose(Axis.YP.rotationDegrees(switch(d){case EAST->90;case WEST->270;case NORTH->180;default->0;}));
            pose.mulPose(Axis.XP.rotationDegrees(90));
            part("spray_riser",pose,buffers,light,overlay,-.5,0,-.5,1,1,1);pose.popPose();
            if(face.getAxis()==Direction.Axis.Z) {
                for(int x=min.getX()+1;x<max.getX();x++)part("spray_rail",pose,buffers,light,overlay,x,top,inside.getZ()+.5,1,1,1);
            } else {
                for(int z=min.getZ()+1;z<max.getZ();z++) {
                    pose.pushPose();pose.translate(inside.getX()+.5,top,z);pose.mulPose(Axis.YP.rotationDegrees(-90));
                    part("spray_rail",pose,buffers,light,overlay,0,0,0,1,1,1);pose.popPose();
                }
            }
        }
        pose.popPose();
    }
    private static void part(String name,PoseStack pose,MultiBufferSource buffers,int light,int overlay,double x,double y,double z,double sx,double sy,double sz) {
        pose.pushPose();pose.translate(x,y,z);pose.scale((float)sx,(float)sy,(float)sz);
        var baked=Minecraft.getInstance().getModelManager().getModel(model(name));var out=buffers.getBuffer(Sheets.cutoutBlockSheet());var random=RandomSource.create(42);
        for(int i=0;i<7;i++){random.setSeed(42);for(var q:baked.getQuads(null,i<6?Direction.values()[i]:null,random,ModelData.EMPTY,RenderType.solid()))out.putBulkData(pose.last(),q,new float[]{1,1,1,1},1,1,1,1,new int[]{light,light,light,light},overlay,true);}
        pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(SuppressionPoolBlockEntity be){return be.visualMin==null?new AABB(be.getBlockPos()):new AABB(Vec3.atLowerCornerOf(be.visualMin),Vec3.atLowerCornerOf(be.visualMax.offset(1,2,1)));}
    @Override public int getViewDistance(){return 128;}
    @Override public boolean shouldRender(SuppressionPoolBlockEntity be,Vec3 eye){return be.visualFormed&&getRenderBoundingBox(be).getCenter().distanceToSqr(eye)<128*128;}
}
