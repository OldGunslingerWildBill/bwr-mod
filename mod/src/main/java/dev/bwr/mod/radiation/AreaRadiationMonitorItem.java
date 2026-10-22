package dev.bwr.mod.radiation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** The monitor's item: a block item with a tooltip that says what it does and how to wire it. */
public final class AreaRadiationMonitorItem extends BlockItem {
    public AreaRadiationMonitorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.bwr.radiation_monitor.mount").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.radiation_monitor.lamp").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.radiation_monitor.use").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bwr.radiation_monitor.wire").withStyle(ChatFormatting.DARK_GRAY));
    }
}
