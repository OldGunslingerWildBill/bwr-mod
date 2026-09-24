package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CondenserMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class CondenserScreen extends BwrScreen<CondenserMenu> {
    public CondenserScreen(CondenserMenu menu,Inventory inv,Component title){super(menu,inv,title,null,330,240);}
    @Override protected void renderBg(GuiGraphics g,float partial,int x,int y){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+23,0xFF293A4E);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        text(g,menu.present&&menu.ready?"Surface condenser":"Waiting for complete, loaded condenser",12,32,TEXT);
        readout(g,"Cold cooling water",big(menu.cold)+" kg",12,53,298,ACCENT);
        readout(g,"Hot cooling water",big(menu.hot)+" kg",12,70,298,TEXT_BRIGHT);
        readout(g,"Hotwell / condensate",big(menu.condensate)+" kg",12,87,298,TEXT_BRIGHT);
        readout(g,"Buffered steam",num(menu.steam,1)+" kg",12,104,298,TEXT_BRIGHT);
        readout(g,"Steam condensed",num(menu.steamRate,1)+" kg/s",12,123,298,TEXT_BRIGHT);
        readout(g,"Cooling-water flow",big(menu.coolingRate)+" kg/s",12,140,298,TEXT_BRIGHT);
        readout(g,"Heat rejected",num(menu.rejectedMW,1)+" MW",12,157,298,TEXT_BRIGHT);
        text(g,"Cooling water: "+num(menu.coldC,1)+" -> "+num(menu.hotC,1)+" C",12,180,TEXT_DIM);
        text(g,"Hotwell: "+num(menu.condensateC,1)+" C",12,194,TEXT_DIM);
        text(g,"Backpressure: "+num(menu.pressure,2)+" psia | "+num(menu.vacuum,1)+" inHg vacuum",12,208,TEXT_DIM);
        text(g,"Round side ports: hotwell makeup water in",12,224,TEXT_DIM);
    }
}
