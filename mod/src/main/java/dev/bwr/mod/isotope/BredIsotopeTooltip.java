package dev.bwr.mod.isotope;

import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * The tooltip of a reactor-bred heat-source isotope (polonium-210, plutonium-238, actinium-227, thulium-170: an ingot or a
 * block of nine, BREEDING-TARGETS.md). Every number is the RTG fuel list's ({@link RtgFuels}), read when the tooltip is drawn.
 * Carried, the item is a gamma point source ({@link CarriedIsotopes}), whatever its class.
 */
public final class BredIsotopeTooltip {

    private BredIsotopeTooltip() {
    }

    /** A mass the way a person reads it: grams from 1 g up, milligrams below. */
    public static String mass(double grams) {
        if (grams >= 100.0) {
            return String.format(Locale.ROOT, "%.0f g", grams);
        }
        if (grams >= 1.0) {
            return String.format(Locale.ROOT, "%.1f g", grams);
        }
        return String.format(Locale.ROOT, "%.3g mg", grams * 1000.0);
    }

    /** A half-life the way a person reads it: days under two years, years above. */
    public static String halfLife(double years) {
        if (years <= 0.0) {
            return "-";
        }
        if (years < 2.0) {
            return String.format(Locale.ROOT, "%.0f days", years * 365.25);
        }
        return String.format(Locale.ROOT, "%.1f years", years);
    }

    public static void append(ItemStack stack, List<Component> lines) {
        RtgFuel fuel = RtgFuels.of(stack);
        if (fuel == null) {
            return;
        }
        double thermal = fuel.thermalWattsPerItem();
        double electric = RadioisotopePower.electricWatts(thermal, RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
        lines.add(BwrText.tr("tooltip.bwr.bred.contents", "%s of %s, bred in a reactor. Fuel for an RTG.",
                mass(fuel.gramsPerItem()), fuel.isotope()));
        lines.add(BwrText.tr("tooltip.bwr.bred.power", "%s W thermal, %s W electric in an RTG; half-life %s",
                String.format(Locale.ROOT, "%.3g", thermal), String.format(Locale.ROOT, "%.2g", electric), halfLife(fuel.halfLifeYears())));
        double msv = CarriedIsotopes.doseRateAtOneMetre(stack);
        lines.add(BwrText.tr("tooltip.bwr.bred.dose", "Carried: %s at 1 m. Keep it in an RTG.",
                dev.bwr.core.radiation.DoseModel.formatRate(msv)));
    }
}
