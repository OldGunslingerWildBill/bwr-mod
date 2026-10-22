package dev.bwr.mod.containment.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
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
 * Root-only GPU geometry; only pose matrices change as the door moves. A stroke first turns the hand-wheel and slides the
 * latch dogs out of their keepers in the frame, then swings the leaf (with its dogs and wheel) about the hinge pin.
 */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class VaultDoorRenderer implements BlockEntityRenderer<ContainmentVaultDoorBlockEntity> {
    public static final ModelResourceLocation FRAME3=model("vault_3_frame"), LEAF3=model("vault_3_leaf");
    public static final ModelResourceLocation FRAME4=model("vault_4_frame"), LEAF4=model("vault_4_leaf");
    /** Latch dogs (right edge, top edge, bottom edge) and the hand-wheel, per door size: index 0 is 3x3, index 1 is 4x4. */
    public static final ModelResourceLocation[] DOGS_RIGHT={model("vault_3_dogs_right"),model("vault_4_dogs_right")};
    public static final ModelResourceLocation[] DOGS_TOP={model("vault_3_dogs_top"),model("vault_4_dogs_top")};
    public static final ModelResourceLocation[] DOGS_BOTTOM={model("vault_3_dogs_bottom"),model("vault_4_dogs_bottom")};
    public static final ModelResourceLocation[] WHEEL={model("vault_3_wheel"),model("vault_4_wheel")};
    private static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath("bwr","block/containment/"+name));}
    public VaultDoorRenderer(BlockEntityRendererProvider.Context ignored){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){
        event.register(FRAME3);event.register(LEAF3);event.register(FRAME4);event.register(LEAF4);
        for(int i=0;i<2;i++){event.register(DOGS_RIGHT[i]);event.register(DOGS_TOP[i]);event.register(DOGS_BOTTOM[i]);event.register(WHEEL[i]);}
    }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event){
        event.registerBlockEntityRenderer(BwrBlockEntities.CONTAINMENT_VAULT_DOOR.get(),VaultDoorRenderer::new);
    }
    @Override public void render(ContainmentVaultDoorBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        int size=((ContainmentVaultDoorBlock)be.getBlockState().getBlock()).size();
        int index=size==3?0:1;
        int angle=switch(be.getBlockState().getValue(ContainmentVaultDoorBlock.FACING)){
            case EAST->90;case SOUTH->180;case WEST->270;default->0;};
        double x=be.position(partial);
        double swing=DoorMotion.swing(x,DoorMotion.VAULT_LATCH),unlatched=DoorMotion.unlatched(x,DoorMotion.VAULT_LATCH);
        double retract=unlatched*DoorMotion.VAULT_DOG_TRAVEL;
        pose.pushPose();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5,0,-.5);
        MachineMeshCache.model(size==3?FRAME3:FRAME4,pose,buffers,light,overlay);
        // Status lamp column on the frame: red open, amber moving, green shut and latched.
        double lampY=DoorMotion.vaultLampY(size);
        DoorLens.draw(DoorLens.forPosition(x),pose,buffers,overlay,size-.085,lampY+DoorLens.rowOffset(x,.14),.05,false);
        pose.pushPose();pose.translate(DoorMotion.VAULT_PIVOT_X,0,DoorMotion.VAULT_PIVOT_Z);
        pose.mulPose(Axis.YP.rotationDegrees((float)(-82*swing)));
        pose.translate(-DoorMotion.VAULT_PIVOT_X,0,-DoorMotion.VAULT_PIVOT_Z);
        MachineMeshCache.model(size==3?LEAF3:LEAF4,pose,buffers,light,overlay);
        // The dogs slide toward the middle of the leaf, each edge along its own axis.
        slide(DOGS_RIGHT[index],-retract,0,pose,buffers,light,overlay);
        slide(DOGS_TOP[index],0,-retract,pose,buffers,light,overlay);
        slide(DOGS_BOTTOM[index],0,retract,pose,buffers,light,overlay);
        pose.pushPose();pose.translate(size/2.0,size/2.0,DoorMotion.VAULT_WHEEL_Z);
        pose.mulPose(Axis.ZP.rotationDegrees((float)(-360*DoorMotion.wheelTurns(x,DoorMotion.VAULT_LATCH))));
        MachineMeshCache.model(WHEEL[index],pose,buffers,light,overlay);
        pose.popPose();
        pose.popPose();pose.popPose();
    }
    private static void slide(ModelResourceLocation id,double dx,double dy,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(dx,dy,0);
        MachineMeshCache.model(id,pose,buffers,light,overlay);
        pose.popPose();
    }
    @Override public AABB getRenderBoundingBox(ContainmentVaultDoorBlockEntity be){
        var pos=be.getBlockPos();int size=((ContainmentVaultDoorBlock)be.getBlockState().getBlock()).size();
        return new AABB(pos).inflate(size+1,size+1,size+1);
    }
    @Override public int getViewDistance(){return 96;}
    @Override public boolean shouldRenderOffScreen(ContainmentVaultDoorBlockEntity be){return true;}
    @Override public boolean shouldRender(ContainmentVaultDoorBlockEntity be,Vec3 camera){
        return MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
