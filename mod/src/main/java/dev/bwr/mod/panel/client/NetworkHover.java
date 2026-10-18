package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.LinkTools;
import dev.bwr.mod.panel.NetworkingWrenchItem;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * What the Networking Wrench is pointing at: nothing, a device that reports signals, or a panel component. The
 * same value is what the holder shares with nearby players: a position and, for a component, its id.
 */
public record NetworkHover(int kind, BlockPos pos, int id) {
    public static final int NONE_KIND = 0;
    public static final int DEVICE = 1;
    public static final int COMPONENT = 2;
    public static final NetworkHover NONE = new NetworkHover(NONE_KIND, BlockPos.ZERO, 0);

    public boolean none() {
        return kind == NONE_KIND;
    }

    /** Whether the player has a Networking Wrench in either hand. */
    public static boolean holdingWrench(Minecraft minecraft) {
        return minecraft.player != null && !LinkTools.heldWrench(minecraft.player).isEmpty();
    }

    /**
     * What the crosshair is on. A panel face is a panel, never a device, so a component wins. Only meaningful while
     * the wrench is held; {@link #NONE} otherwise.
     */
    public static NetworkHover current(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null || !holdingWrench(minecraft)) {
            return NONE;
        }
        PanelBlockEntity.Claim claim = PanelClientInput.hoveredClaim(minecraft);
        if (claim != null) {
            return new NetworkHover(COMPONENT, claim.owner(), claim.id());
        }
        if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (!(minecraft.level.getBlockState(pos).getBlock() instanceof PanelBlock)
                    && NetworkingWrenchItem.providesSignals(minecraft.level, pos)) {
                return new NetworkHover(DEVICE, pos.immutable(), 0);
            }
        }
        return NONE;
    }
}
