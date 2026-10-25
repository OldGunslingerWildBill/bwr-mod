package dev.bwr.mod.isotope;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** An ingot of a reactor-bred heat-source isotope. Its numbers are the RTG fuel list's; carried, it is a gamma source. */
public class BredIsotopeItem extends Item {
    public BredIsotopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        BredIsotopeTooltip.append(stack, lines);
        super.appendHoverText(stack, context, lines, flag);
    }
}
