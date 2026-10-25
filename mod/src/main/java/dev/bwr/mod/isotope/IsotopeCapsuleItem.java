package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Locale;

/**
 * A sealed isotope capsule: strontium-90, caesium-137 or americium-241 from the isotope separator. Its numbers are the
 * RTG fuel list's ({@link RtgFuels}), read when the tooltip is drawn, so a datapack that retunes a capsule retunes the
 * tooltip. Carried, it is a gamma source ({@link CarriedIsotopes}).
 */
public class IsotopeCapsuleItem extends Item {

    public IsotopeCapsuleItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        RtgFuel fuel = RtgFuels.of(stack);
        if (fuel == null) {
            return;
        }
        double thermal = fuel.thermalWattsPerItem();
        double electric = RadioisotopePower.electricWatts(thermal, RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
        lines.add(BwrText.tr("tooltip.bwr.capsule.contents", "%s g of %s, sealed. Fuel for an RTG.",
                String.format(Locale.ROOT, "%.0f", fuel.gramsPerItem()), fuel.isotope()));
        lines.add(BwrText.tr("tooltip.bwr.capsule.power", "%s W thermal, %s W electric in an RTG; half-life %s years",
                String.format(Locale.ROOT, "%.1f", thermal), String.format(Locale.ROOT, "%.2f", electric),
                String.format(Locale.ROOT, "%.1f", fuel.halfLifeYears())));
        double msv = CarriedIsotopes.doseRateAtOneMetre(stack);
        lines.add(BwrText.tr("tooltip.bwr.capsule.dose", "Carried: %s at 1 m. Keep it in an RTG.",
                dev.bwr.core.radiation.DoseModel.formatRate(msv)));
        super.appendHoverText(stack, context, lines, flag);
    }
}
