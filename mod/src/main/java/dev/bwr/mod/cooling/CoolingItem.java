package dev.bwr.mod.cooling;

import dev.bwr.mod.text.BwrText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.List;
public class CoolingItem extends BlockItem {
    public CoolingItem(CoolingBlock b,Properties p){super(b,p);}
    @Override public void appendHoverText(ItemStack s,TooltipContext c,List<Component> lines,TooltipFlag flag){
        super.appendHoverText(s,c,lines,flag);var b=(CoolingBlock)getBlock();var d=b.layout().size;
        lines.add(BwrText.tr(
                "status.bwr.cooling_item.wide_x_high_x_deep_place_center_base",
                "%s wide x %s high x %s deep; place center base.",
                d.getX(),
                d.getY(),
                d.getZ()).withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr(
                "status.bwr.cooling_item.rated_flow_kg_s_water_pipes_only",
                "Rated flow: %s kg/s. Water pipes only.",
                (int)b.design.flow).withStyle(ChatFormatting.AQUA));
        if(b.design.tower)lines.add(BwrText.tr("status.bwr.cooling_item.front_hot_in_rear_cold_out_left_makeup", "Front: hot in. Rear: cold out. Left: makeup in.").withStyle(ChatFormatting.GRAY));
        else if(b.design.watts>0)lines.add(BwrText.tr(
                "status.bwr.cooling_item.lower_front_suction_rear_discharge_right_fe",
                "Lower front: suction. Rear: discharge. Right: FE.").withStyle(ChatFormatting.GRAY));
        else lines.add(BwrText.tr(
                "status.bwr.cooling_item.submerge_source_water_below_and_beside_two_outer",
                "Submerge: source water below and beside two outer sides.").withStyle(ChatFormatting.GRAY));
        lines.add(BwrText.tr("status.bwr.cooling_item.right_click_for_controls_and_readings", "Right-click for controls and readings.").withStyle(ChatFormatting.GRAY));
    }
}
