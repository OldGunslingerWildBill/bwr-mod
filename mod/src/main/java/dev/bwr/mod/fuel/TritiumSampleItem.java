package dev.bwr.mod.fuel;

import dev.bwr.mod.text.BwrText;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;

/** Plain sealed item; optional Mekanism processing is supplied by a conditional recipe. */
public final class TritiumSampleItem extends Item {
    public TritiumSampleItem(Properties properties) { super(properties); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(BwrText.tr(
                "status.bwr.tritium_sample_item.mekanism_chemical_oxidizer_10_000_mb_tritium_per",
                "Mekanism Chemical Oxidizer: 10,000 mB tritium per sample.").withStyle(ChatFormatting.AQUA));
        lines.add(BwrText.tr(
                "status.bwr.tritium_sample_item.samples_per_completed_rod_mb_total",
                "%s samples per completed rod: %s mB total.",
                String.format(java.util.Locale.ROOT, "%,d", dev.bwr.mod.config.GameplayConfig.tritiumSamplesPerRod()),
                String.format(java.util.Locale.ROOT, "%,d", 10_000L * dev.bwr.mod.config.GameplayConfig.tritiumSamplesPerRod())).withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr("status.bwr.tritium_sample_item.requires_mekanism_mekanism_generators", "Requires Mekanism + Mekanism Generators.").withStyle(ChatFormatting.GRAY));
    }
}
