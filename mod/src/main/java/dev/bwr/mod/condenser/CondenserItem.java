package dev.bwr.mod.condenser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;
public final class CondenserItem extends BlockItem {
    public CondenserItem(CondenserBlock b,Properties p){super(b,p);}
    @Override public void appendHoverText(ItemStack s,TooltipContext c,List<Component> lines,TooltipFlag flag){
        super.appendHoverText(s,c,lines,flag);
        lines.add(Component.literal("7 wide x 6 high x 7 deep; place center foundation.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Place one LP turbine directly above, 6 blocks higher.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Front upper: bypass steam in. Front lower: hot water out.").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Rear lower: cold water in. Rear horizontal: condensate out.").withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal("Right-click any part for inventory and flow information.").withStyle(ChatFormatting.GRAY));
    }
}
