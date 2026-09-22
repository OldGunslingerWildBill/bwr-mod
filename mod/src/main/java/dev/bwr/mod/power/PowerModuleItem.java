package dev.bwr.mod.power;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;

public final class PowerModuleItem extends BlockItem {
    public PowerModuleItem(PowerModuleBlock block,Properties properties){super(block,properties);}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        super.appendHoverText(stack,context,lines,flag);var b=(PowerModuleBlock)getBlock();
        lines.add(Component.literal(b.kind()==dev.bwr.mod.eccs.PumpAssemblyBlock.Kind.LP_TURBINE?"7 wide x 5 high x 7 long":"5 wide x 5 high x 9 long").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Join end shafts at the same height. Right-click for panel.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(b.generator()?"FE output: copper terminal on right; up to 1,500 MW":b.highPressure()?"Top: main steam in. Left: exhaust to LP turbine.":"Top: HP exhaust in. Left: return water to mechanical pipe.").withStyle(ChatFormatting.AQUA));
        if(!b.generator()&&!b.highPressure())lines.add(Component.literal("Place a condenser six blocks directly below, facing the same way.").withStyle(ChatFormatting.DARK_GRAY));
        if(!b.generator())lines.add(Component.literal("Control steam with an upstream stop/control valve.").withStyle(ChatFormatting.GRAY));
    }
}
