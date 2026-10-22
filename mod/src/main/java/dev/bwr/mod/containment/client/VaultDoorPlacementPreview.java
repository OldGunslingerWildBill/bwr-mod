package dev.bwr.mod.containment.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.DoorMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * Highlight the entire leaf footprint, including collisions and protection checks, and show how the door will sit: the
 * hand-wheel ring on the outside face, the hinge pin, and the sweep of the leaf into the room, in the same block-local frame
 * the door renderer uses (so they always agree with the model).
 */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class VaultDoorPlacementPreview {
    private static Object world;
    private static BlockPos root;
    private static Direction facing;
    private static long tick=Long.MIN_VALUE;
    private static boolean fits;
    @SubscribeEvent public static void highlight(RenderHighlightEvent.Block event){
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        var held=mc.player.getMainHandItem();
        if(!(held.getItem() instanceof BlockItem item&&item.getBlock() instanceof ContainmentVaultDoorBlock))held=mc.player.getOffhandItem();
        if(!(held.getItem() instanceof BlockItem item&&item.getBlock() instanceof ContainmentVaultDoorBlock door))return;
        BlockPos target=event.getTarget().getBlockPos().relative(event.getTarget().getDirection());
        Direction direction=mc.player.getDirection().getOpposite();
        if(world!=mc.level||!target.equals(root)||direction!=facing||tick!=mc.level.getGameTime()){
            fits=door.canPlaceAt(mc.level,mc.player,target,direction);
            world=mc.level;root=target;facing=direction;tick=mc.level.getGameTime();
        }
        int size=door.size();
        int angle=switch(direction){case EAST->90;case SOUTH->180;case WEST->270;default->0;};
        var camera=event.getCamera().getPosition();
        var pose=event.getPoseStack();pose.pushPose();
        pose.translate(target.getX()-camera.x,target.getY()-camera.y,target.getZ()-camera.z);
        pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-angle));pose.translate(-.5,0,-.5);
        var lines=event.getMultiBufferSource().getBuffer(RenderType.lines());
        float r=fits?.12f:1f,g=fits?1f:.12f,b=.18f;
        LevelRenderer.renderLineBox(pose,lines,new AABB(0,0,0,size,size,1).inflate(.003),r,g,b,1f);
        // Outside face: the hand-wheel ring.
        ring(lines,pose,size/2.0,size/2.0,.02,DoorMotion.vaultWheelRadius(size),r,g,b);
        // Hinge pin, and the sweep of the leaf's free edge into the room, along the floor and under the lintel.
        double px=DoorMotion.VAULT_PIVOT_X,pz=DoorMotion.VAULT_PIVOT_Z,reach=size-.33-px;
        line(lines,pose,px,.3,pz,px,size-.3,pz,.35f,.65f,1f);
        for(double y:new double[]{.02,size-.02}){
            double lastX=px+reach,lastZ=pz;
            for(int i=1;i<=16;i++){
                double phi=Math.toRadians(82.0*i/16);
                double x=px+reach*Math.cos(phi),z=pz+reach*Math.sin(phi);
                line(lines,pose,lastX,y,lastZ,x,y,z,.35f,.65f,1f);
                lastX=x;lastZ=z;
            }
        }
        double endX=px+reach*Math.cos(Math.toRadians(82)),endZ=pz+reach*Math.sin(Math.toRadians(82));
        line(lines,pose,endX,.02,endZ,endX,size-.02,endZ,.35f,.65f,1f);
        pose.popPose();event.setCanceled(true);
    }
    private static void ring(VertexConsumer lines,PoseStack pose,double cx,double cy,double z,double radius,float r,float g,float b){
        double lastX=cx+radius,lastY=cy;
        for(int i=1;i<=24;i++){
            double t=Math.PI*2*i/24,x=cx+radius*Math.cos(t),y=cy+radius*Math.sin(t);
            line(lines,pose,lastX,lastY,z,x,y,z,r,g,b);
            lastX=x;lastY=y;
        }
    }
    private static void line(VertexConsumer lines,PoseStack pose,double x0,double y0,double z0,double x1,double y1,double z1,float r,float g,float b){
        var last=pose.last();
        float nx=(float)(x1-x0),ny=(float)(y1-y0),nz=(float)(z1-z0);
        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(length<1e-6f)return;
        nx/=length;ny/=length;nz/=length;
        lines.addVertex(last,(float)x0,(float)y0,(float)z0).setColor(r,g,b,1f).setNormal(last,nx,ny,nz);
        lines.addVertex(last,(float)x1,(float)y1,(float)z1).setColor(r,g,b,1f).setNormal(last,nx,ny,nz);
    }
}
