package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.ServiceMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class ServiceScreen extends BwrScreen<ServiceMenu> {
    private Button toggle,division,redstone;
    public ServiceScreen(ServiceMenu m,Inventory inv,Component title){super(m,inv,title,null,330,240);}
    @Override protected void init(){super.init();
        toggle=addRenderableWidget(Button.builder(Component.literal("Open"),b->menu.sendCommand(1,0)).bounds(leftPos+12,topPos+206,92,20).build());
        division=addRenderableWidget(Button.builder(Component.literal("Division"),b->menu.sendCommand(2,0)).bounds(leftPos+112,topPos+206,92,20).build());
        redstone=addRenderableWidget(Button.builder(Component.literal("Use redstone"),b->menu.sendCommand(3,0)).bounds(leftPos+212,topPos+206,106,20).build());
    }
    @Override protected void containerTick(){super.containerTick();toggle.visible=menu.kind>1;division.visible=redstone.visible=menu.kind==2||menu.kind==3;toggle.setMessage(Component.literal(menu.active?"Close":"Open"));division.setMessage(Component.literal("Division: "+menu.division));}
    @Override protected void renderBg(GuiGraphics g,float p,int x,int y){g.fill(leftPos,topPos,leftPos+330,topPos+240,0xFF171D26);g.fill(leftPos,topPos,leftPos+330,topPos+23,0xFF293A4E);g.renderOutline(leftPos,topPos,330,240,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){int row=34;for(String line:menu.lines){text(g,line,12,row,TEXT);row+=20;}}
}
