package dev.bwr.mod.cooling;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;
public class CoolingItem extends BlockItem {
    public CoolingItem(CoolingBlock b,Properties p){super(b,p);}
    @Override public void appendHoverText(ItemStack s,TooltipContext c,List<Component> lines,TooltipFlag flag){
        super.appendHoverText(s,c,lines,flag);var b=(CoolingBlock)getBlock();var d=b.layout().size;
        lines.add(Component.literal(d.getX()+" wide x "+d.getY()+" high x "+d.getZ()+" deep; place center base.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Rated flow: "+(int)b.design.flow+" kg/s. Water pipes only.").withStyle(ChatFormatting.AQUA));
        if(b.design.tower)lines.add(Component.literal("Front: hot in. Rear: cold out. Left: makeup in.").withStyle(ChatFormatting.GRAY));
        else if(b.design.watts>0)lines.add(Component.literal("Lower front: suction. Rear: discharge. Right: FE.").withStyle(ChatFormatting.GRAY));
        else lines.add(Component.literal("Submerge: source water below and beside two outer sides.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Right-click for controls and readings.").withStyle(ChatFormatting.GRAY));
    }
}
