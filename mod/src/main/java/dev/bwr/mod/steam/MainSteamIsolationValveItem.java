package dev.bwr.mod.steam;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;

public final class MainSteamIsolationValveItem extends BlockItem {
    public MainSteamIsolationValveItem(MainSteamIsolationValveBlock block, Properties properties) { super(block,properties); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack,context,lines,flag);
        lines.add(Component.translatable("tooltip.bwr.msiv.size").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.bwr.msiv.ports").withStyle(ChatFormatting.AQUA));
        lines.add(Component.translatable("tooltip.bwr.msiv.control").withStyle(ChatFormatting.GRAY));
    }
}
