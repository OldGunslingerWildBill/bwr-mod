package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.PowerModuleMenu;
import dev.bwr.mod.eccs.EccsPower;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class PowerModuleScreen extends BwrScreen<PowerModuleMenu> {
    public PowerModuleScreen(PowerModuleMenu m,Inventory inv,Component title){super(m,inv,title,null,300,238);}
    @Override protected void renderBg(GuiGraphics g,float p,int x,int y){g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);g.fill(leftPos,topPos,leftPos+imageWidth,topPos+24,0xFF293A4E);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        text(g,menu.hpCount+" HP  /  "+menu.lpCount+" LP  /  "+menu.generatorCount+" generator(s)",12,32,ACCENT);
        readout(g,"Shaft speed",num(menu.rpm,0)+" rpm",12,49,288,TEXT_BRIGHT);
        readout(g,"Train shaft power",num(menu.trainMW,2)+" MW",12,65,288,TEXT_BRIGHT);
        if(menu.generator){
            readout(g,"Electrical output",num(menu.electricMW,2)+" / 1,500 MW",12,81,288,GOOD);
            readout(g,"Generation",big(menu.electricMW*1e6/EccsPower.WATTS_PER_FE_PER_TICK)+" FE/t",12,97,288,TEXT_BRIGHT);
            readout(g,"Stored energy",big(menu.energy)+" FE",12,113,288,TEXT_BRIGHT);
            bar(g,12,134,276,10,menu.energy/2e9,ACCENT);
            text(g,"FE output: copper terminal on right side",12,161,TEXT);
            text(g,"Join the end shafts at the same height.",12,187,TEXT_DIM);
        }else{
            readout(g,"This section",num(menu.shaftMW,2)+" MW / "+num(menu.flow,1)+" kg/s",12,81,288,TEXT_BRIGHT);
            readout(g,"Steam in / out",num(menu.inletP,1)+" / "+num(menu.outletP,1)+" psia",12,97,288,TEXT_BRIGHT);
            readout(g,"In / exhaust temperature",num(menu.inletC,1)+" / "+num(menu.outletC,1)+" C",12,113,288,TEXT_BRIGHT);
            if(!menu.hp&&menu.water>0)readout(g,"Legacy water awaiting transfer",big(menu.water)+" kg",12,129,288,TEXT_BRIGHT);
            text(g,menu.hp?"Top: steam in   Left: HP exhaust":"Top: steam in   Bottom: condenser",12,151,TEXT_DIM);
            text(g,"Steam is controlled by the upstream valve.",12,182,TEXT);
            text(g,"HP exhaust supplies the LP sections.",12,196,TEXT_DIM);
        }
        text(g,font.plainSubstrByWidth(menu.status,276),12,213,TEXT_BRIGHT);
        text(g,menu.generator?"1 FE/t = 13.4 W  |  98.5% conversion":menu.hp?"Pipe HP exhaust to LP inlets.":"Condenser: six blocks below, same facing",12,228,TEXT_DIM);
    }
}
