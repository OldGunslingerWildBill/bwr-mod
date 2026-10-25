package dev.bwr.mod.reactor;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.BaseEntityBlock;

import java.util.List;

/**
 * The bottom head drain penetration: a small-bore vessel port in the lowest row of the side wall, the suction of the
 * reactor water cleanup pump below the recirculation outlets. It is a vessel water port like the others (so the formed
 * vessel finds it and the line-break code treats its line as pressure boundary), but it can only be a drain: no pump
 * discharges through it, and the water it passes is whatever the RWCU pump draws or the line breaks.
 */
public class RpvBottomDrainPortBlock extends RpvWaterInjectionPortBlock {
    public static final MapCodec<RpvBottomDrainPortBlock> CODEC = simpleCodec(RpvBottomDrainPortBlock::new);
    public RpvBottomDrainPortBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bwr.bottom_drain_port"));
    }
}
