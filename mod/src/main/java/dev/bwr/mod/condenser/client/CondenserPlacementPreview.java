package dev.bwr.mod.condenser.client;

import dev.bwr.mod.condenser.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/** A cheap box preview, never another copy of the condenser mesh. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class CondenserPlacementPreview {
    private static Object cachedLevel;
    private static CondenserPlacement.Attachment cachedAttachment;
    private static long checkedTick=Long.MIN_VALUE;
    private static boolean fits;
    @SubscribeEvent public static void highlight(RenderHighlightEvent.Block event){
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null)return;
        if(!(mc.player.getMainHandItem().getItem() instanceof CondenserItem)
                && !(mc.player.getMainHandItem().isEmpty()&&mc.player.getOffhandItem().getItem() instanceof CondenserItem))return;
        var target=CondenserPlacement.attachment(mc.level,event.getTarget());
        if(target==null)return;
        // Collision and permission checks run at most once per game tick while
        // aiming at the same attachment, irrespective of rendering frame rate.
        if(cachedLevel!=mc.level||!target.equals(cachedAttachment)||checkedTick!=mc.level.getGameTime()){
            fits=target.complete(mc.level)&&CondenserBlock.canPlaceAt(mc.level,mc.player,target.root(),target.facing());
            cachedLevel=mc.level;cachedAttachment=target;checkedTick=mc.level.getGameTime();
        }
        var pose=event.getPoseStack();var camera=event.getCamera().getPosition();
        pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
        LevelRenderer.renderLineBox(pose,event.getMultiBufferSource().getBuffer(RenderType.lines()),
                CondenserLayout.INSTANCE.bounds(target.root(),target.facing()).inflate(.003),
                fits?.12f:1f,fits?1f:.12f,.18f,1f);
        pose.popPose();event.setCanceled(true);
    }
}
