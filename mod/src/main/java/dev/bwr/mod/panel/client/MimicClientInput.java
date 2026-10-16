package dev.bwr.mod.panel.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.mimic.MimicPayloads;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client input for the mimic tape: a click on a panel face sends the exact cursor in cells (the server snaps it and
 * decides, see {@code MimicOps}); the air and other blocks go the vanilla way and finish the path being drawn; sneak
 * and scroll switch the mode (draw, erase, edit) instead of the hotbar slot.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class MimicClientInput {
    private MimicClientInput() {
    }

    /** Where the crosshair is on a panel face: the block and the cursor in cells from its top-left corner. */
    static final class Aim {
        PanelBlockEntity block;
        double cu;
        double cv;
    }

    private static final Aim AIM = new Aim();
    private static final double[] CELL = new double[2];

    /** The panel face under the crosshair, or null. The returned object is reused: read it at once. */
    static Aim aim(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof PanelBlockEntity block)) {
            return null;
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 look = minecraft.player.getViewVector(1.0f);
        BlockPos pos = block.getBlockPos();
        PanelGeometry.Frame frame = PanelGeometry.frame(block.panelType(), block.facing());
        if (!PanelGeometry.rayToCell(frame, pos.getX(), pos.getY(), pos.getZ(), eye.x, eye.y, eye.z, look.x, look.y,
                look.z, CELL)) {
            return null;
        }
        if (CELL[0] < -0.5 || CELL[1] < -0.5 || CELL[0] > PanelGeometry.GRID + 0.5 || CELL[1] > PanelGeometry.GRID + 0.5) {
            return null;
        }
        AIM.block = block;
        AIM.cu = CELL[0];
        AIM.cv = CELL[1];
        return AIM;
    }

    /** The tape in the hand, if any (the hand is also reported by {@link #tapeHand}). */
    static ItemStack tape(Minecraft minecraft) {
        if (minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        return MimicTapeItem.held(minecraft.player);
    }

    static boolean tapeInOffHand(Minecraft minecraft) {
        return minecraft.player != null && !(minecraft.player.getMainHandItem().getItem() instanceof MimicTapeItem);
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.getItemInHand(event.getHand()).getItem() instanceof MimicTapeItem)) {
            return;
        }
        Aim aim = aim(minecraft);
        if (aim == null) {
            return; // the air or another block: the vanilla use finishes the path being drawn
        }
        PacketDistributor.sendToServer(new MimicPayloads.Use(aim.block.getBlockPos(), aim.cu, aim.cv,
                event.getHand() == InteractionHand.OFF_HAND));
        event.setSwingHand(true);
        event.setCanceled(true);
    }

    /** Sneak-scroll with the tape switches draw, erase and edit instead of the hotbar. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || event.getScrollDeltaY() == 0.0
                || !minecraft.player.isShiftKeyDown() || tape(minecraft).isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new MimicPayloads.Mode(event.getScrollDeltaY() > 0 ? 1 : -1, tapeInOffHand(minecraft)));
        event.setCanceled(true);
    }
}
