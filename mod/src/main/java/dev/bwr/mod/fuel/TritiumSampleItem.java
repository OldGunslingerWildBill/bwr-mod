package dev.bwr.mod.fuel;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;

/** Plain sealed item; optional Mekanism processing is supplied by a conditional recipe. */
public final class TritiumSampleItem extends Item {
    public TritiumSampleItem(Properties properties) { super(properties); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Mekanism Chemical Oxidizer: 10,000 mB tritium per sample.").withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal(String.format(Locale.ROOT, "%,d samples per completed rod: %,d mB total.",
                SpecialtyRodItem.TRITIUM_SAMPLES_PER_ROD, 10_000L * SpecialtyRodItem.TRITIUM_SAMPLES_PER_ROD)).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Requires Mekanism + Mekanism Generators.").withStyle(ChatFormatting.GRAY));
    }
}
