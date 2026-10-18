package dev.bwr.mod.panel.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelWrenchItem;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client input for panels: placement with a ghost preview, and the wrench.
 *
 * <p>The right-click on a panel face is intercepted before vanilla sends it, so
 * the cell the player sees in the ghost is the cell that goes to the server (a
 * vanilla use packet would have the server re-derive it from a look direction a
 * tick old). The server still validates everything ({@link PanelOps}); the
 * vanilla path in {@link PanelBlock} remains as the fallback.
 *
 * <p>The ghost is green when {@link PanelGroup#checkPlacement} accepts the
 * footprint and red otherwise: the very same call the server makes.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelClientInput {
    private PanelClientInput() {
    }

    /** What the player is pointing at, computed at most once per frame. */
    private static final class Target {
        PanelBlockEntity block;
        final int[] cell = new int[2];
        boolean valid;
    }

    private static final Target TARGET = new Target();
    private static final int[] SCRATCH = new int[2];

    private static Target target(Minecraft minecraft) {
        TARGET.valid = false;
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return TARGET;
        }
        if (!(minecraft.level.getBlockEntity(hit.getBlockPos()) instanceof PanelBlockEntity be)) {
            return TARGET;
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0f);
        Vec3 look = minecraft.player.getViewVector(1.0f);
        if (PanelOps.cellAt(be, eye, look, SCRATCH)) {
            TARGET.block = be;
            TARGET.cell[0] = SCRATCH[0];
            TARGET.cell[1] = SCRATCH[1];
            TARGET.valid = true;
        }
        return TARGET;
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Target target = target(minecraft);
        if (!target.valid) {
            return;
        }
        ItemStack stack = minecraft.player.getItemInHand(event.getHand());
        if (stack.getItem() instanceof ComponentItem) {
            if (minecraft.player.isShiftKeyDown()) {
                return; // sneak-click cycles the footprint through the vanilla path
            }
            Footprint footprint = ComponentItem.footprint(stack);
            if (footprint != null) {
                var placement = PanelGroup.checkPlacement(minecraft.level, target.block.getBlockPos(),
                        target.cell[0], target.cell[1], footprint, ((ComponentItem) stack.getItem()).type().maxSpanBlocks());
                if (placement.ok()) {
                    PacketDistributor.sendToServer(new PanelPayloads.Place(target.block.getBlockPos(), target.cell[0],
                            target.cell[1], event.getHand() == InteractionHand.OFF_HAND));
                }
            }
            event.setCanceled(true);
            event.setSwingHand(true);
        } else if (stack.getItem() instanceof PanelLinkerItem || stack.getItem() instanceof dev.bwr.mod.panel.NetworkingWrenchItem) {
            // Right-click a component to wire it (the linker and the Networking Wrench do the same here); the server answers with the device lists for the screen.
            PanelBlockEntity.Claim claim = target.block.claimAt(target.cell[0], target.cell[1]);
            if (claim != null && !minecraft.player.isShiftKeyDown()) {
                PacketDistributor.sendToServer(new PanelPayloads.LinkerOpen(claim.owner(), claim.id()));
                event.setSwingHand(true);
            } else if (claim == null && !minecraft.player.isShiftKeyDown()) {
                // No component there: a mimic path under the crosshair is wired like one (its id is -localId).
                dev.bwr.mod.panel.mimic.MimicPath path = pathUnderCrosshair(minecraft);
                if (path != null) {
                    PacketDistributor.sendToServer(new PanelPayloads.LinkerOpen(target.block.getBlockPos(), -path.localId()));
                    event.setSwingHand(true);
                }
            }
            event.setCanceled(true);
        } else if (stack.getItem() instanceof PanelWrenchItem) {
            PanelBlockEntity.Claim claim = target.block.claimAt(target.cell[0], target.cell[1]);
            if (claim != null) {
                if (minecraft.player.isShiftKeyDown()) {
                    PacketDistributor.sendToServer(new PanelPayloads.Remove(claim.owner(), claim.id()));
                } else {
                    PanelClientHooks.openConfig(claim.owner(), claim.id());
                }
                event.setSwingHand(true);
            } else if (!minecraft.player.isShiftKeyDown()) {
                // No component there: the wrench opens the style screen of a mimic path under the crosshair.
                dev.bwr.mod.panel.mimic.MimicPath path = pathUnderCrosshair(minecraft);
                if (path != null) {
                    PanelClientHooks.openMimicConfig(target.block.getBlockPos(), path.localId());
                    event.setSwingHand(true);
                }
            }
            event.setCanceled(true);
        }
    }

    /** The mimic path under the crosshair on a panel face, or null. */
    private static dev.bwr.mod.panel.mimic.MimicPath pathUnderCrosshair(Minecraft minecraft) {
        MimicClientInput.Aim aim = MimicClientInput.aim(minecraft);
        return aim == null ? null : dev.bwr.mod.panel.mimic.MimicOps.pick(aim.block, aim.cu, aim.cv);
    }

    /** The claim (owner block and component id) under the crosshair on a panel face, or null. */
    public static PanelBlockEntity.Claim hoveredClaim(Minecraft minecraft) {
        Target target = target(minecraft);
        return target.valid ? target.block.claimAt(target.cell[0], target.cell[1]) : null;
    }

    /** The component under the crosshair on a panel face, or null (used by the linker's device outline). */
    public static PanelComponent hoveredComponent(Minecraft minecraft) {
        Target target = target(minecraft);
        if (!target.valid) {
            return null;
        }
        PanelBlockEntity.Claim claim = target.block.claimAt(target.cell[0], target.cell[1]);
        if (claim == null || !(minecraft.level.getBlockEntity(claim.owner()) instanceof PanelBlockEntity owner)) {
            return null;
        }
        return owner.component(claim.id());
    }

    // ---- ghost and wrench highlight

    private static BlockPos cachedBlock;
    private static int cachedX = -1;
    private static int cachedY = -1;
    private static int cachedVariant = -1;
    private static long cachedTick = Long.MIN_VALUE;
    private static Object cachedLevel;
    private static boolean cachedFits;

    @SubscribeEvent
    public static void highlight(RenderHighlightEvent.Block event) {
        Minecraft minecraft = Minecraft.getInstance();
        Target target = target(minecraft);
        if (!target.valid) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (!(held.getItem() instanceof ComponentItem) && !(held.getItem() instanceof PanelWrenchItem)
                && !(held.getItem() instanceof PanelLinkerItem) && !(held.getItem() instanceof dev.bwr.mod.panel.NetworkingWrenchItem)) {
            held = minecraft.player.getOffhandItem();
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        if (held.getItem() instanceof ComponentItem item && item.type() != null) {
            Footprint footprint = ComponentItem.footprint(held);
            BlockPos pos = target.block.getBlockPos();
            int variant = ComponentItem.variant(held, item.type());
            long tick = minecraft.level.getGameTime();
            if (cachedLevel != minecraft.level || !pos.equals(cachedBlock) || cachedX != target.cell[0]
                    || cachedY != target.cell[1] || cachedVariant != variant || cachedTick != tick) {
                cachedFits = PanelGroup.checkPlacement(minecraft.level, pos, target.cell[0], target.cell[1], footprint,
                        item.type().maxSpanBlocks()).ok();
                cachedLevel = minecraft.level;
                cachedBlock = pos.immutable();
                cachedX = target.cell[0];
                cachedY = target.cell[1];
                cachedVariant = variant;
                cachedTick = tick;
            }
            outline(event, pose, camera, target.block, target.cell[0], target.cell[1], footprint.w(), footprint.h(),
                    cachedFits ? 0.15f : 1.0f, cachedFits ? 1.0f : 0.15f, 0.2f);
            event.setCanceled(true);
        } else if (held.getItem() instanceof PanelLinkerItem || held.getItem() instanceof dev.bwr.mod.panel.NetworkingWrenchItem) {
            PanelBlockEntity.Claim claim = target.block.claimAt(target.cell[0], target.cell[1]);
            if (claim != null && minecraft.level.getBlockEntity(claim.owner()) instanceof PanelBlockEntity owner) {
                PanelComponent component = owner.component(claim.id());
                if (component != null) {
                    outline(event, pose, camera, owner, component.x(), component.y(), component.w(), component.h(),
                            0.3f, 0.9f, 1.0f);
                    event.setCanceled(true);
                }
            }
        } else if (held.getItem() instanceof PanelWrenchItem) {
            PanelBlockEntity.Claim claim = target.block.claimAt(target.cell[0], target.cell[1]);
            if (claim != null && minecraft.level.getBlockEntity(claim.owner()) instanceof PanelBlockEntity owner) {
                PanelComponent component = owner.component(claim.id());
                if (component != null) {
                    boolean sneaking = minecraft.player.isShiftKeyDown();
                    outline(event, pose, camera, owner, component.x(), component.y(), component.w(), component.h(),
                            sneaking ? 1.0f : 0.2f, sneaking ? 0.5f : 0.8f, sneaking ? 0.1f : 1.0f);
                    event.setCanceled(true);
                }
            }
        }
    }

    /** Draws a rectangle of grid cells on the face of {@code anchor}, extending across blocks if it is large. */
    private static void outline(RenderHighlightEvent.Block event, PoseStack pose, Vec3 camera, PanelBlockEntity anchor,
                                int cx, int cy, int w, int h, float r, float g, float b) {
        BlockPos pos = anchor.getBlockPos();
        PanelGeometry.Frame frame = PanelGeometry.frame(anchor.panelType(), anchor.facing());
        double lift = 0.4; // 1/16 block units above the face, so the lines do not z-fight with it
        float[] c = new float[12];
        double[][] corners = {{cx, cy}, {cx + w, cy}, {cx + w, cy + h}, {cx, cy + h}};
        for (int i = 0; i < 4; i++) {
            c[i * 3] = (float) (pos.getX() - camera.x + frame.x(corners[i][0], corners[i][1], lift));
            c[i * 3 + 1] = (float) (pos.getY() - camera.y + frame.y(corners[i][0], corners[i][1], lift));
            c[i * 3 + 2] = (float) (pos.getZ() - camera.z + frame.z(corners[i][0], corners[i][1], lift));
        }
        PoseStack.Pose last = pose.last();
        VertexConsumer fill = event.getMultiBufferSource().getBuffer(RenderType.debugQuads());
        for (int i = 0; i < 4; i++) {
            fill.addVertex(last, c[i * 3], c[i * 3 + 1], c[i * 3 + 2]).setColor(r, g, b, 0.28f);
        }
        VertexConsumer lines = event.getMultiBufferSource().getBuffer(RenderType.lines());
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float dx = c[j * 3] - c[i * 3];
            float dy = c[j * 3 + 1] - c[i * 3 + 1];
            float dz = c[j * 3 + 2] - c[i * 3 + 2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-6f) {
                continue;
            }
            lines.addVertex(last, c[i * 3], c[i * 3 + 1], c[i * 3 + 2]).setColor(r, g, b, 1.0f)
                    .setNormal(last, dx / len, dy / len, dz / len);
            lines.addVertex(last, c[j * 3], c[j * 3 + 1], c[j * 3 + 2]).setColor(r, g, b, 1.0f)
                    .setNormal(last, dx / len, dy / len, dz / len);
        }
    }
}
