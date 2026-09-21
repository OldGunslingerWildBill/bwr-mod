package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.TurbineValveMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class TurbineValveScreen extends BwrScreen<TurbineValveMenu> {
    private EditBox percent;private Button apply,less,more,open,close;private boolean seeded;
    public TurbineValveScreen(TurbineValveMenu m,Inventory inv,Component title){super(m,inv,title,null,300,224);}
    @Override protected void init(){
        super.init();percent=addRenderableWidget(new EditBox(font,leftPos+113,topPos+125,58,20,Component.literal("Valve opening percent")));
        percent.setMaxLength(5);percent.setFilter(s->s.matches("[0-9]{0,3}(\\.[0-9]?)?"));
        apply=button("Apply",181,125,54,()->{Integer n=value();if(n!=null)menu.sendCommand(0,n);});
        less=button("-0.1",12,155,56,()->adjust(-1));more=button("+0.1",74,155,56,()->adjust(1));
        close=button("Close",146,155,64,()->menu.sendCommand(0,0));open=button("Open",216,155,72,()->menu.sendCommand(0,1000));
    }
    private Button button(String text,int x,int y,int width,Runnable action){return addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(leftPos+x,topPos+y,width,20).build());}
    private Integer value(){try{double p=Double.parseDouble(percent.getValue());return Double.isFinite(p)&&p>=0&&p<=100?(int)Math.round(p*10):null;}catch(NumberFormatException e){return null;}}
    private void adjust(int step){int p=Math.max(0,Math.min(1000,(int)Math.round(menu.target*1000)+step));percent.setValue(num(p/10.0,1));menu.sendCommand(0,p);}
    @Override protected void containerTick(){
        super.containerTick();if(menu.present&&!seeded){percent.setValue(num(menu.target*100,1));seeded=true;}
        percent.visible=apply.visible=less.visible=more.visible=!menu.stop;
        apply.active=menu.present&&value()!=null;less.active=more.active=close.active=open.active=menu.present;
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(percent.isFocused()&&key!=256){if(key==257){Integer n=value();if(n!=null)menu.sendCommand(0,n);return true;}return percent.keyPressed(key,scan,mods);}return super.keyPressed(key,scan,mods);
    }
    @Override protected void renderBg(GuiGraphics g,float p,int x,int y){g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);g.fill(leftPos,topPos,leftPos+imageWidth,topPos+24,0xFF293A4E);g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);}
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,10,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y){
        readout(g,"Requested opening",num(menu.target*100,1)+" %",12,36,288,TEXT_BRIGHT);
        readout(g,"Actual opening",num(menu.position*100,1)+" %",12,53,288,ACCENT);
        bar(g,12,73,276,10,menu.position,ACCENT);
        readout(g,"Turbine steam flow",num(menu.flow,2)+" kg/s",12,96,288,GOOD);
        text(g,menu.stop?"Stop valve: fully open or fully closed.":"Set opening (%)",12,132,TEXT);
        text(g,"Controls the steam supply through this line.",12,187,TEXT_DIM);
        text(g,"Connect pipes to the two opposing flanges.",12,202,TEXT_DIM);
    }
}
