package dev.bwr.mod.containment.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.DoorMotion;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Two D-shaped hatches in bulkhead plates, 2.5 blocks of chamber apart. Each hatch turns its hand-wheel (both faces), then swings
 * into the chamber; the equalising valve's wheel turns with the hatches; a lamp column on each face of each plate shows red when
 * that hatch is open, amber while it moves and green when it is shut and latched.
 */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class AirlockRenderer implements BlockEntityRenderer<ContainmentAirlockBlockEntity> {
    private static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bwr","block/containment/"+name));}
    public static final ModelResourceLocation FRAME=model("airlock_frame"),INNER=model("airlock_inner_leaf"),OUTER=model("airlock_outer_leaf");
    public static final ModelResourceLocation WHEEL=model("airlock_wheel"),VALVE_WHEEL=model("airlock_valve_wheel");
    public AirlockRenderer(BlockEntityRendererProvider.Context ignored){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){
        event.register(FRAME);event.register(INNER);event.register(OUTER);event.register(WHEEL);event.register(VALVE_WHEEL);
    }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){
        event.registerBlockEntityRenderer(BwrBlockEntities.CONTAINMENT_AIRLOCK.get(),AirlockRenderer::new);
    }
    @Override public void render(ContainmentAirlockBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        int angle=switch(be.getBlockState().getValue(ContainmentAirlockBlock.FACING)){
            case WEST->90;case NORTH->180;case EAST->270;default->0;};
        double innerX=be.innerPosition(partial),outerX=be.outerPosition(partial);
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5,0,-.5);
        MachineMeshCache.model(FRAME,pose,buffers,light,overlay);
        leaf(INNER,0,innerX,pose,buffers,light,overlay);
        leaf(OUTER,1,outerX,pose,buffers,light,overlay);
        // The equalising valve's wheel (axis along x) turns while either hatch moves.
        var valve=DoorMotion.AIRLOCK_VALVE;
        pose.pushPose();pose.translate(valve[0],valve[1],valve[2]);pose.mulPose(Axis.YP.rotationDegrees(90));
        pose.mulPose(Axis.ZP.rotationDegrees((float)(540*(innerX+outerX))));
        MachineMeshCache.model(VALVE_WHEEL,pose,buffers,light,overlay);
        pose.popPose();
        // Lamp columns, both faces of both plates. The chamber face looks along the leaf's own swing direction.
        for(int plate=0;plate<2;plate++){
            double x=plate==0?innerX:outerX,zc=DoorMotion.AIRLOCK_FACE_Z[plate];
            int toward=plate==0?1:-1;
            double y=DoorMotion.AIRLOCK_LAMP_Y+DoorLens.rowOffset(x,.15);
            ModelResourceLocation lens=DoorLens.forPosition(x);
            DoorLens.draw(lens,pose,buffers,overlay,DoorMotion.AIRLOCK_LAMP_X,y,zc+toward*.036,toward>0);
            DoorLens.draw(lens,pose,buffers,overlay,DoorMotion.AIRLOCK_LAMP_X,y,zc-toward*.236,toward<0);
        }
        if(be.override())DoorLens.draw(DoorLens.AMBER,pose,buffers,overlay,.065,1.55,DoorMotion.AIRLOCK_FACE_Z[0]-.236,false);
        pose.popPose();
    }
    /** One hatch: it swings about its hinge pin into the chamber, carrying a wheel on each face. */
    private static void leaf(ModelResourceLocation id,int plate,double x,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        double swing=DoorMotion.swing(x,DoorMotion.AIRLOCK_LATCH);
        double zc=DoorMotion.AIRLOCK_FACE_Z[plate];
        int toward=plate==0?1:-1;
        double pivotZ=zc+toward*.08;
        pose.pushPose();pose.translate(DoorMotion.AIRLOCK_PIVOT_X,0,pivotZ);
        pose.mulPose(Axis.YP.rotationDegrees((float)(-toward*82*swing)));
        pose.translate(-DoorMotion.AIRLOCK_PIVOT_X,0,-pivotZ);
        MachineMeshCache.model(id,pose,buffers,light,overlay);
        float turn=(float)(360*DoorMotion.wheelTurns(x,DoorMotion.AIRLOCK_LATCH));
        wheel(zc+toward*.24,turn,pose,buffers,light,overlay);
        wheel(zc-toward*.14,-turn,pose,buffers,light,overlay);
        pose.popPose();
    }
    private static void wheel(double z,float degrees,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(DoorMotion.AIRLOCK_WHEEL_X,DoorMotion.AIRLOCK_WHEEL_Y,z);
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        MachineMeshCache.model(WHEEL,pose,buffers,light,overlay);
        pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(ContainmentAirlockBlockEntity be){return new AABB(be.getBlockPos()).inflate(4);}
    @Override public int getViewDistance(){return 96;}
    @Override public boolean shouldRenderOffScreen(ContainmentAirlockBlockEntity be){return true;}
    @Override public boolean shouldRender(ContainmentAirlockBlockEntity be,Vec3 camera){
        return MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
