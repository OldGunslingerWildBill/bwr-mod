package dev.bwr.mod.isotope;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** The block of nine ingots of a reactor-bred isotope: nine times the ingot's mass, heat and dose, from the same list. */
public class BredIsotopeBlockItem extends BlockItem {
    public BredIsotopeBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        BredIsotopeTooltip.append(stack, lines);
        super.appendHoverText(stack, context, lines, flag);
    }
}
