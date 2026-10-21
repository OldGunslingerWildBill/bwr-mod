package dev.bwr.mod.panel;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The panel wrench. All behaviour lives where the panel is: the client input
 * hook ({@code PanelClientInput}) opens the config screen or sends the remove
 * packet, and {@link PanelBlock} carries the same behaviour for the vanilla
 * right-click path. Right-click a component to configure it; sneak-right-click
 * to remove it (the item drops).
 */
public final class PanelWrenchItem extends Item {
    public PanelWrenchItem(Properties properties) {
        super(properties);
    }

    /** Sneak-click on a panel must reach the block: that is the remove gesture. */
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, net.minecraft.world.level.LevelReader level,
                                      net.minecraft.core.BlockPos pos, net.minecraft.world.entity.player.Player player) {
        return level.getBlockState(pos).getBlock() instanceof PanelBlock;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.bwr.panel_wrench.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.panel_wrench.remove").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.panel_wrench.finish").withStyle(ChatFormatting.GRAY));
    }

    /**
     * Sneak-click on trim, furniture or a panel block's body (anywhere a panel does not take the click itself): steps the
     * finish of the clicked piece. A panel's face is handled in {@link PanelBlock}: the same gesture on a bare spot.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (player == null || !player.isShiftKeyDown() || !PanelFinishes.hasFinish(level.getBlockState(pos))) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            PanelFinishes.cycle(level, pos, player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
