package dev.bwr.mod.panel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.board.BoardBlueprintItem;
import dev.bwr.mod.panel.board.BoardPlan;
import dev.bwr.mod.panel.board.BoardTemplate;
import dev.bwr.mod.text.BwrText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The board blueprint's ghost: while a blueprint is held and the crosshair is on a block, every block of the board is
 * outlined where it would be placed (the shape of the panel block itself): green when the whole board fits, and when
 * something is in the way the blocked blocks are red and the others yellow. The outline is drawn through walls, like the
 * wiring highlight, and the action bar says what a click would do. Client only; the server decides again when the
 * player clicks.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BoardPreview {
    private BoardPreview() {
    }

    private static long drawn;
    private static String lastMessage = "";

    /** Frames in which an outline was drawn, for the opt-in client check. */
    public static long drawn() {
        return drawn;
    }

    /** The plan the held blueprint would place at the crosshair, or null. */
    public static BoardPlan currentPlan(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        ItemStack stack = held(minecraft);
        if (stack.isEmpty()) {
            return null;
        }
        BoardTemplate template = BoardBlueprintItem.template(stack);
        if (template == null) {
            return null;
        }
        UseOnContext context = new UseOnContext(minecraft.level, minecraft.player, InteractionHand.MAIN_HAND, stack, hit);
        BlockPos origin = BoardBlueprintItem.originFor(minecraft.level, hit.getBlockPos(), hit.getDirection(), context);
        Direction facing = BoardBlueprintItem.facingFor(minecraft.player.getDirection());
        return new BoardPlan(template, origin, facing);
    }

    private static ItemStack held(Minecraft minecraft) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = minecraft.player.getItemInHand(hand);
            if (stack.getItem() instanceof BoardBlueprintItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Whether the client sees this block free for the board (the server asks again, with the player's permissions). */
    public static boolean free(Minecraft minecraft, BoardPlan.Block block) {
        BlockPos pos = block.pos();
        return !minecraft.level.isOutsideBuildHeight(pos) && minecraft.level.getBlockState(pos).canBeReplaced()
                && minecraft.level.isUnobstructed(block.state(), pos, CollisionContext.of(minecraft.player));
    }

    /** The edges of a shape as lines (the vanilla outline code is private). */
    private static void edges(PoseStack pose, VertexConsumer lines, VoxelShape shape, double x, double y, double z,
                              float r, float g, float b) {
        PoseStack.Pose last = pose.last();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float dx = (float) (x2 - x1);
            float dy = (float) (y2 - y1);
            float dz = (float) (z2 - z1);
            float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (length == 0.0f) {
                return;
            }
            dx /= length;
            dy /= length;
            dz /= length;
            lines.addVertex(last.pose(), (float) (x1 + x), (float) (y1 + y), (float) (z1 + z)).setColor(r, g, b, 1.0f)
                    .setNormal(last, dx, dy, dz);
            lines.addVertex(last.pose(), (float) (x2 + x), (float) (y2 + y), (float) (z2 + z)).setColor(r, g, b, 1.0f)
                    .setNormal(last, dx, dy, dz);
        });
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BoardPlan plan = currentPlan(minecraft);
        if (plan == null) {
            lastMessage = "";
            return;
        }
        int blocked = 0;
        boolean[] free = new boolean[plan.blocks().size()];
        for (int i = 0; i < free.length; i++) {
            free[i] = free(minecraft, plan.blocks().get(i));
            if (!free[i]) {
                blocked++;
            }
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(LinkerHighlight.Types.THROUGH_WALLS);
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        for (int i = 0; i < free.length; i++) {
            BoardPlan.Block block = plan.blocks().get(i);
            VoxelShape shape = block.state().getShape(minecraft.level, block.pos());
            float r = free[i] ? (blocked == 0 ? 0.25f : 0.95f) : 1.0f;
            float g = free[i] ? (blocked == 0 ? 0.95f : 0.85f) : 0.15f;
            float b = free[i] ? (blocked == 0 ? 0.35f : 0.2f) : 0.15f;
            edges(pose, lines, shape, block.pos().getX() - camera.x, block.pos().getY() - camera.y,
                    block.pos().getZ() - camera.z, r, g, b);
        }
        buffers.endBatch(LinkerHighlight.Types.THROUGH_WALLS);
        drawn++;
        String message = plan.template().id() + "/" + blocked;
        if (!message.equals(lastMessage)) {
            lastMessage = message;
            minecraft.gui.setOverlayMessage(blocked == 0
                    ? BwrText.tr("message.bwr.board.preview_ok", "%s: fits. Right-click to place it here", plan.template().title())
                    : BwrText.tr("message.bwr.board.preview_blocked", "%s: %s block(s) in the way", plan.template().title(), blocked), false);
        }
    }
}
