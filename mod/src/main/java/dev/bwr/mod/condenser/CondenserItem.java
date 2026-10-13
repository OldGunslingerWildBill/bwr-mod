package dev.bwr.mod.condenser;

import dev.bwr.mod.text.BwrText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;
public final class CondenserItem extends BlockItem {
    public CondenserItem(CondenserBlock b,Properties p){super(b,p);}
    @Override public net.minecraft.world.InteractionResult place(net.minecraft.world.item.context.BlockPlaceContext context){
        return super.place(CondenserPlacement.align(context));
    }
    @Override public void appendHoverText(ItemStack s,TooltipContext c,List<Component> lines,TooltipFlag flag){
        super.appendHoverText(s,c,lines,flag);
        lines.add(BwrText.tr("status.bwr.condenser_item.7_wide_x_6_high_x_9_deep", "7 wide x 6 high x 9 deep; place center foundation.").withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.click_an_lp_turbine_s_underside_to_snap",
                "Click an LP turbine's underside to snap underneath it.").withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.green_outline_fits_red_outline_space_is_blocked",
                "Green outline: fits. Red outline: space is blocked.").withStyle(ChatFormatting.GREEN));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.front_upper_bypass_steam_in_front_lower_hot",
                "Front upper: bypass steam in. Front lower: hot water out.").withStyle(ChatFormatting.GOLD));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.rear_lower_cold_water_in_rear_horizontal_condensate",
                "Rear lower: cold water in. Rear horizontal: condensate out.").withStyle(ChatFormatting.AQUA));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.two_round_side_ports_hotwell_makeup_water_in",
                "Two round side ports: hotwell makeup water in.").withStyle(ChatFormatting.AQUA));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.snaps_across_the_lp_shaft_main_pipes_face",
                "Snaps across the LP shaft; main pipes face the hall sides.").withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr(
                "status.bwr.condenser_item.right_click_any_part_for_inventory_and_flow",
                "Right-click any part for inventory and flow information.").withStyle(ChatFormatting.GRAY));
    }
}
