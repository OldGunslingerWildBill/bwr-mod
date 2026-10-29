package dev.bwr.mod.power;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.text.BwrText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Locale;

/**
 * The element of an Oil Filter Housing (TURBINE-AUX.md). A new one carries nothing and stacks; one taken out of a housing carries how
 * full it is in the {@code bwr:oil_filter_load} component (0 clean, 1 spent), so it can be put back where it left off and a spent one
 * is told by its bar and its tooltip. A cartridge is never cleaned: changing it is the only reset.
 */
public final class OilFilterCartridgeItem extends Item {
    private final int grade;

    /** The standard cartridge, as it always was. */
    public OilFilterCartridgeItem(Properties properties) { this(properties, TurbineAux.GRADE_STANDARD); }

    /** A cartridge of a grade: {@link TurbineAux#GRADE_COARSE} (high flow), {@link TurbineAux#GRADE_STANDARD} or {@link TurbineAux#GRADE_FINE} (high filtration, limited flow). */
    public OilFilterCartridgeItem(Properties properties, int grade) { super(properties); this.grade = TurbineAux.clampGrade(grade); }

    public int grade() { return grade; }

    /** Whether a stack is a cartridge of any grade. */
    public static boolean isCartridge(ItemStack stack) { return stack.getItem() instanceof OilFilterCartridgeItem; }

    /** The grade of a stack: standard for anything that is not a cartridge. */
    public static int gradeOf(ItemStack stack) { return stack.getItem() instanceof OilFilterCartridgeItem c ? c.grade : TurbineAux.GRADE_STANDARD; }

    /** How full the cartridge is, 0 to 1: a stack without the component is new. */
    public static double load(ItemStack stack) {
        Double v = stack.get(BwrDataComponents.OIL_FILTER_LOAD.get());
        return v == null || !Double.isFinite(v) ? 0 : Mth.clamp(v, 0, 1);
    }

    /** Records how full the cartridge is; a clean one carries no component at all, so it stacks with new ones. */
    public static void setLoad(ItemStack stack, double load) {
        if (load > 0) stack.set(BwrDataComponents.OIL_FILTER_LOAD.get(), Mth.clamp(load, 0, 1));
        else stack.remove(BwrDataComponents.OIL_FILTER_LOAD.get());
    }

    private static String gradeLine(int grade) {
        return switch (grade) {
            case TurbineAux.GRADE_COARSE -> "Coarse: passes plenty of oil, catches only the big debris. Put it ahead of a fine one.";
            case TurbineAux.GRADE_FINE -> "Fine: catches nearly everything, but passes only 60 percent of a train's oil. Two side by side feed a whole train.";
            default -> "Standard: the general-purpose element.";
        };
    }

    @Override public boolean isBarVisible(ItemStack stack) { return load(stack) > 0; }

    /** The bar shows what is left in it, green when new to red when spent. */
    @Override public int getBarWidth(ItemStack stack) { return Math.round(13 * (1 - (float) load(stack))); }

    @Override public int getBarColor(ItemStack stack) { return Mth.hsvToRgb((1 - (float) load(stack)) / 3F, 0.85F, 1F); }

    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        double load = load(stack);
        lines.add(BwrText.tr("tooltip.bwr.oil_filter_cartridge.grade." + TurbineAux.GRADE_NAMES[grade], gradeLine(grade)).withStyle(ChatFormatting.AQUA));
        if (load <= 0) {
            lines.add(BwrText.tr("tooltip.bwr.oil_filter_cartridge.new", "New.").withStyle(ChatFormatting.GRAY));
        } else if (TurbineAux.filterBypassOpen(load)) {
            lines.add(BwrText.tr("tooltip.bwr.oil_filter_cartridge.spent", "Spent: oil goes round it.").withStyle(ChatFormatting.RED));
        } else {
            lines.add(BwrText.tr("tooltip.bwr.oil_filter_cartridge.used", "Used: %s left, %s psid across it.",
                    String.format(Locale.ROOT, "%.0f %%", 100 * (1 - load)), String.format(Locale.ROOT, "%.1f", TurbineAux.filterDpPsi(load, grade))).withStyle(ChatFormatting.YELLOW));
        }
        lines.add(BwrText.tr("tooltip.bwr.oil_filter_cartridge.1", "Goes in the slot of an Oil Filter Housing against a Main Oil Tank. A clean one cuts the oil's loss to a tenth.").withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr("tooltip.bwr.oil_filter_cartridge.2", "It loads up with use, faster with hot oil. When it is full the oil goes round it. It cannot be cleaned.").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, lines, flag);
    }
}
