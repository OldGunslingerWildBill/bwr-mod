package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.RhrHeatExchangerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class RhrHeatExchangerScreen extends BwrScreen<RhrHeatExchangerMenu> {
    public RhrHeatExchangerScreen(RhrHeatExchangerMenu m,Inventory i,Component t){super(m,i,t,null,320,218);}
    @Override protected void renderBg(GuiGraphics g,float partial,int x,int y){g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);g.fill(leftPos,topPos,leftPos+imageWidth,topPos+23,0xFF293A4E);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        text(g,menu.present&&menu.connected?"Pool return connected":"Connect primary outlet to the pool return",12,32,TEXT);
        readout(g,"Pool-water flow",num(menu.primaryFlow,1)+" kg/s",12,53,308,TEXT_BRIGHT);
        readout(g,"Cooling-water flow",num(menu.secondaryFlow,1)+" kg/s",12,71,308,ACCENT);
        readout(g,"Heat transferred",num(menu.heat,2)+" MW",12,89,308,TEXT_BRIGHT);
        readout(g,"Cold / hot inventory",big(menu.cold)+" / "+big(menu.hot)+" kg",12,107,308,TEXT_BRIGHT);
        readout(g,"Cooling water heated to",menu.secondaryFlow>0?num(menu.hotTemperature,1)+" C":"No flow",12,125,308,ACCENT);
        readout(g,"Pool-water return",menu.primaryFlow>0?num(menu.primaryOut,1)+" C":"No flow",12,143,308,TEXT_BRIGHT);
        text(g,"Pool: red IN / amber OUT. Cooling: blue IN / cyan OUT.",12,169,TEXT_DIM);
        text(g,"Supply cooling water and drain the heated outlet.",12,183,TEXT_DIM);
        readout(g,"Cooling-water inlet",num(menu.coldTemperature,1)+" C",12,197,308,TEXT_DIM);
    }
}
