package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CondensateTankMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class CondensateTankScreen extends BwrScreen<CondensateTankMenu> {
    public CondensateTankScreen(CondensateTankMenu menu,Inventory inv,Component title){super(menu,inv,title,null,260,158);}
    @Override protected void renderBg(GuiGraphics g,float partial,int x,int y){g.fill(leftPos,topPos,leftPos+260,topPos+158,0xFF171D26);g.fill(leftPos,topPos,leftPos+260,topPos+23,0xFF293A4E);g.renderOutline(leftPos,topPos,260,158,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        readout(g,"Water",big(menu.stored)+" / "+big(menu.capacity)+" kg",12,34,248,TEXT_BRIGHT);
        bar(g,12,52,236,12,menu.capacity>0?menu.stored/menu.capacity:0,ACCENT);
        readout(g,"Stored temperature",num(menu.temperature,0)+" C",12,77,248,TEXT_BRIGHT);
        text(g,"Fill from water pipes or water buckets.",12,100,TEXT);
        text(g,"Connect to pump suction; select",12,116,TEXT_DIM);
        text(g,"Tank / piped water on the pump panel.",12,128,TEXT_DIM);
        text(g,"1 mB water = 1 kg in this mod.",12,143,TEXT_DIM);
    }
}
