package dev.bwr.mod.gui.client;
import dev.bwr.mod.gui.CondensateTankMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Tank dimensions come from the player's completed shell. */
public class CondensateTankScreen extends BwrScreen<CondensateTankMenu> {
    public CondensateTankScreen(CondensateTankMenu menu,Inventory inv,Component title){super(menu,inv,title,null,340,234);}
    @Override protected void renderBg(GuiGraphics g,float p,int x,int y){g.fill(leftPos,topPos,leftPos+340,topPos+234,0xFF171D26);g.fill(leftPos,topPos,leftPos+340,topPos+23,0xFF293A4E);g.renderOutline(leftPos,topPos,340,234,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        text(g,menu.assembled?(menu.ready?"Assembled cylindrical tank":"Tank incomplete / chunks unloaded"):"Build a complete shell to form a tank.",12,31,TEXT);
        readout(g,"Water",big(menu.stored)+" / "+big(menu.capacity)+" kg",12,49,328,TEXT_BRIGHT);
        bar(g,12,65,316,10,menu.capacity>0?menu.stored/menu.capacity:0,ACCENT);
        readout(g,"Stored water temperature",num(menu.temperature,0)+" C",12,84,328,TEXT_BRIGHT);
        if(menu.assembled){
            readout(g,"Diameter / height",menu.diameter+" / "+menu.height+" blocks",12,110,328,TEXT_BRIGHT);
            readout(g,"Construction blocks",big(menu.blocks),12,130,328,TEXT_BRIGHT);
            text(g,"Dismantle and rebuild the shell to change its size.",12,159,TEXT_DIM);
        }else{
            text(g,"Square base: odd width 3-15 blocks",12,110,TEXT_BRIGHT);
            text(g,"Height: 3-24 blocks",12,130,TEXT_BRIGHT);
            g.drawWordWrap(font,Component.literal("Build the floor, four walls and roof from tank blocks. Leave the interior empty. The final block forms the tank automatically."),leftPos+12,topPos+153,316,TEXT_DIM);
        }
        g.drawWordWrap(font,Component.literal("Water pipes connect to four side flanges, one block above the base."),leftPos+12,topPos+208,316,TEXT_DIM);
    }
}
