package dev.bwr.mod.panel.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelWrenchItem;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client input for operating panel controls (spec 2.1 inputs): a click sends
 * PRESS with the type's {@link ComponentType#clickValue} (which half, which
 * digit); a control that is {@link ComponentType#holdable()} stays pressed while
 * the use key is down (vanilla repeats the use trigger every few ticks while it
 * is held, which doubles as the keep-alive the server's fail-safe wants) and
 * sends RELEASE the tick it is let go; scrolling over a
 * {@link ComponentType#scrollable()} control sends TURN instead of changing
 * the hotbar slot. Placing components and the wrench are {@link PanelClientInput}'s.
 *
 * <p>The server validates everything again ({@code PanelOps.operate}); this only
 * works out what the player meant. The vanilla right-click path still works as
 * a fallback.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelClientOperate {
    private PanelClientOperate() {
    }

    /** The control under the crosshair, filled by {@link #aim}. */
    private static final class Aim {
        PanelBlockEntity owner;
        PanelComponent component;
        double cellX;
        double cellY;
    }

    private static final Aim AIM = new Aim();
    private static final double[] CELL = new double[2];

    /** The control currently held down, if any. */
    private static BlockPos heldOwner;
    private static int heldId;
    private static boolean useWasDown;

    private static boolean aim(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof PanelBlockEntity clicked)) {
            return false;
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 look = minecraft.player.getViewVector(1.0f);
        BlockPos pos = clicked.getBlockPos();
        PanelGeometry.Frame frame = PanelGeometry.frame(clicked.panelType(), clicked.facing());
        if (!PanelGeometry.rayToCell(frame, pos.getX(), pos.getY(), pos.getZ(), eye.x, eye.y, eye.z, look.x, look.y, look.z, CELL)) {
            return false;
        }
        double cu = CELL[0];
        double cv = CELL[1];
        if (cu < -0.5 || cv < -0.5 || cu > PanelGeometry.GRID + 0.5 || cv > PanelGeometry.GRID + 0.5) {
            return false;
        }
        int cx = Math.max(0, Math.min(PanelGeometry.GRID - 1, (int) Math.floor(cu)));
        int cy = Math.max(0, Math.min(PanelGeometry.GRID - 1, (int) Math.floor(cv)));
        PanelBlockEntity.Claim claim = clicked.claimAt(cx, cy);
        if (claim == null || !(minecraft.level.getBlockEntity(claim.owner()) instanceof PanelBlockEntity owner)) {
            return false;
        }
        PanelComponent component = owner.component(claim.id());
        if (component == null || component.type() == null || !component.type().isInteractive()) {
            return false;
        }
        double offsetX = 0;
        double offsetY = 0;
        PanelGroup group = owner.group();
        if (group != null && group.contains(pos)) {
            offsetX = (group.columnOf(pos) - group.columnOf(owner.getBlockPos())) * PanelGeometry.GRID;
            offsetY = (group.rowOf(pos) - group.rowOf(owner.getBlockPos())) * PanelGeometry.GRID;
        }
        AIM.owner = owner;
        AIM.component = component;
        AIM.cellX = offsetX + cu - component.x();
        AIM.cellY = offsetY + cv - component.y();
        return true;
    }

    private static void send(PanelBlockEntity owner, PanelComponent component, OperateAction action, double value) {
        PacketDistributor.sendToServer(new PanelPayloads.Operate(owner.getBlockPos(), component.id(), action.ordinal(), value));
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stack = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getItemInHand(event.getHand());
        if (stack.getItem() instanceof ComponentItem || stack.getItem() instanceof PanelWrenchItem
                || stack.getItem() instanceof dev.bwr.mod.panel.mimic.MimicTapeItem || !aim(minecraft)) {
            return;
        }
        ComponentType type = AIM.component.type();
        boolean repeat = useWasDown;
        if (AIM.component.data() instanceof dev.bwr.mod.panel.component.PaperlessRecorderData) {
            // A recorder's face is a row of keys: the click is resolved against the bezel's key boxes on this client.
            if (!repeat) {
                RecorderClientKeys.click(AIM.owner, AIM.component, AIM.cellX, AIM.cellY);
            }
            event.setSwingHand(!repeat);
            event.setCanceled(true);
            return;
        }
        if (!repeat || type.holdable()) {
            if (type.holdable() && heldOwner != null
                    && (!heldOwner.equals(AIM.owner.getBlockPos()) || heldId != AIM.component.id())) {
                // The crosshair moved from one held control to another with the key still down: let go of the first now,
                // or it would stay operated until the server's fail-safe.
                releaseHeld(minecraft);
            }
            // A real click, or the repeat of a held control (which refreshes the server's fail-safe).
            send(AIM.owner, AIM.component, OperateAction.PRESS, type.clickValue(AIM.component, AIM.cellX, AIM.cellY));
            if (type.holdable()) {
                heldOwner = AIM.owner.getBlockPos();
                heldId = AIM.component.id();
            }
        }
        event.setSwingHand(!repeat);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getScrollDeltaY() == 0.0 || !aim(minecraft) || !AIM.component.type().scrollable()) {
            return;
        }
        int notches = event.getScrollDeltaY() > 0 ? 1 : -1;
        ComponentType type = AIM.component.type();
        send(AIM.owner, AIM.component, OperateAction.TURN, type.scrollValue(AIM.component, AIM.cellX, AIM.cellY, notches));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean down = minecraft.options.keyUse.isDown();
        if (heldOwner != null && (!down || minecraft.screen != null || minecraft.level == null)) {
            releaseHeld(minecraft);
        }
        useWasDown = down;
    }

    /** Sends RELEASE for the control held down and forgets it. */
    private static void releaseHeld(Minecraft minecraft) {
        if (minecraft.level != null && minecraft.level.getBlockEntity(heldOwner) instanceof PanelBlockEntity owner) {
            PanelComponent component = owner.component(heldId);
            if (component != null) {
                send(owner, component, OperateAction.RELEASE, 0.0);
            }
        }
        heldOwner = null;
    }
}
