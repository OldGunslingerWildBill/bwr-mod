package dev.bwr.mod.power;

import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Locale;

/**
 * The turbine hall's crane loads as items (TURBINE-MAINTENANCE.md): an LP turbine blade section (new or with a little wear), a
 * damaged one, an LP turbine's hood (its upper casing half) and a wrecked hood (one a blade tore through, or a burst one). An item is the
 * one place such a load is while it is not in a turbine: on a stand, on the crane's hook, or dropped. A blade section carries its
 * condition; a hood the LP it came off and that LP's facing, a wrecked hood also whether it burst and where blades tore through it.
 */
public class TurbineLoadItem extends Item {
    public enum Kind { BLADE_SECTION, DAMAGED_BLADE_SECTION, HOOD, WRECKED_HOOD }

    private static final String CONDITION = "BladeCondition", ORIGIN = "Origin", FACING = "Facing", BURST = "Burst", HOLES = "Holes";
    private final Kind kind;

    public TurbineLoadItem(Properties properties, Kind kind) {
        super(properties.stacksTo(1));
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static boolean isBladeSection(ItemStack s) {
        return s.getItem() instanceof TurbineLoadItem t && (t.kind == Kind.BLADE_SECTION || t.kind == Kind.DAMAGED_BLADE_SECTION);
    }

    /** A section that may go into a turbine: a new or lightly worn section, not a damaged one. */
    public static boolean isGoodSection(ItemStack s) {
        return s.getItem() instanceof TurbineLoadItem t && t.kind == Kind.BLADE_SECTION;
    }

    /** A hood, whole or wrecked: the crane carries it and a casing laydown stand holds it the same. */
    public static boolean isHood(ItemStack s) {
        return s.getItem() instanceof TurbineLoadItem t && (t.kind == Kind.HOOD || t.kind == Kind.WRECKED_HOOD);
    }

    /** A hood that may go back on a turbine: not a wrecked one. */
    public static boolean isGoodHood(ItemStack s) {
        return s.getItem() instanceof TurbineLoadItem t && t.kind == Kind.HOOD;
    }

    /** A wrecked hood that burst (drawn torn open), not one a blade only tore through. */
    public static boolean hoodBurst(ItemStack s) {
        return s.getItem() instanceof TurbineLoadItem t && t.kind == Kind.WRECKED_HOOD && data(s).getBoolean(BURST);
    }

    /** The release angles of the blades that tore through a wrecked hood, per flow (NaN for none). */
    public static double[] hoodHoles(ItemStack s) {
        double[] out = {Double.NaN, Double.NaN};
        var l = data(s).getList(HOLES, 6);
        for (int i = 0; i < out.length && i < l.size(); i++) {
            double a = l.getDouble(i);
            out[i] = Double.isFinite(a) ? a : Double.NaN;
        }
        return out;
    }

    private static CompoundTag data(ItemStack s) {
        return s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    /** The condition a blade section carries, 0 new to 1 failed (a damaged section without a figure counts as failed). */
    public static double condition(ItemStack s) {
        var t = data(s);
        if (t.contains(CONDITION)) {
            double d = t.getDouble(CONDITION);
            return Double.isFinite(d) ? Math.max(0, Math.min(1, d)) : 1;
        }
        return s.getItem() instanceof TurbineLoadItem i && i.kind == Kind.DAMAGED_BLADE_SECTION ? 1 : 0;
    }

    /** The item a blade section of this condition comes out of a casing as: a spare under {@link TurbineBlades#DAMAGED_SECTION}, a damaged section above. */
    public static ItemStack section(double condition) {
        boolean damaged = condition >= TurbineBlades.DAMAGED_SECTION;
        ItemStack s = new ItemStack(damaged ? BwrItems.DAMAGED_LP_BLADE_SECTION.get() : BwrItems.LP_BLADE_SECTION.get());
        if (condition > 0) {
            var t = new CompoundTag();
            t.putDouble(CONDITION, Math.max(0, Math.min(1, condition)));
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        }
        return s;
    }

    /** The hood of the LP at {@code origin}, facing {@code facing}. */
    public static ItemStack hood(BlockPos origin, Direction facing) {
        ItemStack s = new ItemStack(BwrItems.LP_TURBINE_HOOD.get());
        var t = new CompoundTag();
        t.putLong(ORIGIN, origin.asLong());
        t.putString(FACING, facing.getName());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** The hood of a wrecked LP: burst, or with the holes blades tore through it (release angles per flow, NaN for none). */
    public static ItemStack wreckedHood(BlockPos origin, Direction facing, boolean burst, double[] holes) {
        ItemStack s = new ItemStack(BwrItems.WRECKED_LP_TURBINE_HOOD.get());
        var t = new CompoundTag();
        t.putLong(ORIGIN, origin.asLong());
        t.putString(FACING, facing.getName());
        if (burst) {
            t.putBoolean(BURST, true);
        }
        var l = new net.minecraft.nbt.ListTag();
        for (double a : holes) {
            l.add(net.minecraft.nbt.DoubleTag.valueOf(a));
        }
        t.put(HOLES, l);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** The LP a hood came off, or null. */
    public static BlockPos hoodOrigin(ItemStack s) {
        var t = data(s);
        return t.contains(ORIGIN) ? BlockPos.of(t.getLong(ORIGIN)) : null;
    }

    /** The facing of the LP a hood came off (how it is drawn), or the fallback. */
    public static Direction hoodFacing(ItemStack s, Direction fallback) {
        var d = Direction.byName(data(s).getString(FACING));
        return d == null || d.getAxis().isVertical() ? fallback : d;
    }

    /** The load's mass, t (the server options {@code turbineHoodMassT} and {@code lpBladeSectionMassT}). */
    public static double massT(ItemStack s) {
        return isHood(s) ? dev.bwr.mod.config.GameplayConfig.turbineHoodMassT() : dev.bwr.mod.config.GameplayConfig.lpBladeSectionMassT();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String mass = String.format(Locale.ROOT, "%.0f t", massT(stack));
        switch (kind) {
            case HOOD -> lines.add(Component.translatable("tooltip.bwr.lp_turbine_hood", mass).withStyle(ChatFormatting.GRAY));
            case WRECKED_HOOD -> lines.add(Component.translatable("tooltip.bwr.wrecked_lp_turbine_hood", mass).withStyle(ChatFormatting.GRAY));
            default -> lines.add(Component.translatable("tooltip.bwr.lp_blade_section", String.format(Locale.ROOT, "%.0f%%", 100 * condition(stack)), mass)
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
