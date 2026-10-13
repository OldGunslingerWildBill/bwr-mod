package dev.bwr.mod.power;

import dev.bwr.mod.text.BwrText;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;

public final class PowerModuleItem extends BlockItem {
    public PowerModuleItem(PowerModuleBlock block,Properties properties){super(block,properties);}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        super.appendHoverText(stack,context,lines,flag);var b=(PowerModuleBlock)getBlock();
        lines.add((b.kind()==dev.bwr.mod.eccs.PumpAssemblyBlock.Kind.LP_TURBINE ? BwrText.tr("status.bwr.power_module_item.7_wide_x_5_high_x_7_long", "7 wide x 5 high x 7 long") : BwrText.tr("status.bwr.power_module_item.5_wide_x_5_high_x_9_long", "5 wide x 5 high x 9 long")).withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr(
                "status.bwr.power_module_item.join_end_shafts_at_the_same_height_right",
                "Join end shafts at the same height. Right-click for panel.").withStyle(ChatFormatting.GRAY));
        lines.add((b.generator() ? BwrText.tr(
                "status.bwr.power_module_item.fe_output_copper_terminal_on_right_up_to",
                "FE output: copper terminal on right; up to 1,500 MW") : (b.highPressure() ? BwrText.tr(
                "status.bwr.power_module_item.top_main_steam_in_left_exhaust_to_lp",
                "Top: main steam in. Left: exhaust to LP turbine.") : BwrText.tr(
                "status.bwr.power_module_item.top_hp_exhaust_in_left_return_water_to",
                "Top: HP exhaust in. Left: return water to mechanical pipe."))).withStyle(ChatFormatting.AQUA));
        if(!b.generator()&&!b.highPressure())lines.add(BwrText.tr(
                "status.bwr.power_module_item.place_a_condenser_six_blocks_directly_below_facing",
                "Place a condenser six blocks directly below, facing the same way.").withStyle(ChatFormatting.DARK_GRAY));
        if(!b.generator())lines.add(BwrText.tr(
                "status.bwr.power_module_item.control_steam_with_an_upstream_stop_control_valve",
                "Control steam with an upstream stop/control valve.").withStyle(ChatFormatting.GRAY));
    }
}
