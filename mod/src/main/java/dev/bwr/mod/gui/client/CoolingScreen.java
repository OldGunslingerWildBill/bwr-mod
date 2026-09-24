package dev.bwr.mod.gui.client;
import dev.bwr.mod.gui.CoolingMenu;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
public class CoolingScreen extends BwrScreen<CoolingMenu> {
    private EditBox speed;private Button apply,stop;private boolean seeded;
    public CoolingScreen(CoolingMenu m,Inventory i,Component title){super(m,i,title,null,340,234);}
    @Override protected void init(){
        super.init();speed=addRenderableWidget(new EditBox(font,leftPos+117,topPos+174,60,20,Component.literal("Speed percent")));speed.setMaxLength(5);speed.setFilter(s->s.matches("[0-9]{0,3}(\\.[0-9]?)?"));
        apply=addRenderableWidget(Button.builder(Component.literal("Apply"),b->{var v=value();if(v!=null)menu.sendCommand(0,v);}).bounds(leftPos+187,topPos+174,62,20).build());
        stop=addRenderableWidget(Button.builder(Component.literal("Stop"),b->menu.sendCommand(0,0)).bounds(leftPos+259,topPos+174,68,20).build());
    }
    private Integer value(){try{double n=Double.parseDouble(speed.getValue());return Double.isFinite(n)&&n>=0&&n<=100?(int)Math.round(n*10):null;}catch(NumberFormatException e){return null;}}
    @Override protected void containerTick(){super.containerTick();if(menu.present&&!seeded){speed.setValue(num(menu.target*100,1));seeded=true;}speed.visible=apply.visible=stop.visible=menu.design.watts>0;apply.active=menu.present&&value()!=null;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(speed.isFocused()&&key!=256){if(key==257){var n=value();if(n!=null)menu.sendCommand(0,n);return true;}return speed.keyPressed(key,scan,mods);}return super.keyPressed(key,scan,mods);}
    @Override protected void renderBg(GuiGraphics g,float p,int x,int y){g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);g.fill(leftPos,topPos,leftPos+imageWidth,topPos+24,0xFF293A4E);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        text(g,!menu.ready?"Waiting for complete, loaded structure":menu.design==Design.INTAKE?(menu.submerged?"Submerged intake":"Intake requires source water"):menu.actual>0?"Operating":"Stopped / no power",12,30,TEXT);
        readout(g,menu.design.tower?"Hot-water inventory":"Inlet inventory",big(menu.input)+" kg",12,47,328,TEXT_BRIGHT);
        readout(g,menu.design.tower?"Cold basin":"Outlet inventory",big(menu.output)+" kg",12,63,328,ACCENT);
        readout(g,"Water processed",num(menu.flow,1)+" kg/s",12,79,328,TEXT_BRIGHT);
        readout(g,"Rated capacity",big(menu.design.flow)+" kg/s",12,95,328,TEXT_BRIGHT);
        if(menu.design.tower){
            readout(g,"Heat rejected",num(menu.heat,1)+" MW",12,111,328,TEXT_BRIGHT);
            readout(g,"Makeup demand",num(menu.loss,1)+" kg/s",12,127,328,TEXT_BRIGHT);
        }
        if(menu.design.watts>0)readout(g,menu.design.tower?"Fan":"Motor",num(menu.actual*100,1)+"%  |  "+big(menu.draw)+" FE/t",12,143,328,TEXT_BRIGHT);
        else text(g,menu.design==Design.INTAKE?"Passive screen - connect a makeup pump downstream.":"Natural draft - no motor or fan required.",12,143,TEXT_DIM);
        if(menu.design.watts>0)text(g,"Speed (%)",12,181,TEXT);
        text(g,"Water: "+num(menu.inputC,1)+" -> "+num(menu.outputC,1)+" C",12,206,TEXT_DIM);
        text(g,menu.design==Design.INTAKE?"Source water at two outer sides; lakebed mounting is OK.":menu.design.watts>0?"Connect water pipes to flanges; FE to electrical box.":"Connect water pipes to the three basin flanges.",12,221,TEXT_DIM);
    }
}
