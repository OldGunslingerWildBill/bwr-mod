package dev.bwr.mod.containment.client;

import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
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

/** Highlight the entire leaf footprint, including collisions and protection checks. */
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
        BlockPos far=door.cell(target,direction,door.size()*door.size()-1);
        AABB box=new AABB(Math.min(target.getX(),far.getX()),target.getY(),Math.min(target.getZ(),far.getZ()),
                Math.max(target.getX(),far.getX())+1,far.getY()+1,Math.max(target.getZ(),far.getZ())+1);
        var camera=event.getCamera().getPosition();
        var pose=event.getPoseStack();pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
        LevelRenderer.renderLineBox(pose,event.getMultiBufferSource().getBuffer(RenderType.lines()),box.inflate(.003),
                fits?.12f:1f,fits?1f:.12f,.18f,1f);
        pose.popPose();event.setCanceled(true);
    }
}
