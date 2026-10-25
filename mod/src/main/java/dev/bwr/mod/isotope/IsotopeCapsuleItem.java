package dev.bwr.mod.isotope;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

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
        BredIsotopeTooltip.append(stack, lines);
        super.appendHoverText(stack, context, lines, flag);
    }
}
