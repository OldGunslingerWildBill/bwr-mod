package dev.bwr.mod.isotope;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The item of an isotope storage block (nine ingots): its numbers are the RTG fuel list's, read when the tooltip is drawn.
 * Carried, it is a gamma source through the same data-map path as the ingot ({@link CarriedIsotopes}); placed, it is inert
 * storage, like the same ingots in a chest.
 */
public class IsotopeBlockItem extends BlockItem {

    public IsotopeBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        IsotopeCapsuleItem.appendIsotopeTooltip(stack, lines);
        super.appendHoverText(stack, context, lines, flag);
    }
}
