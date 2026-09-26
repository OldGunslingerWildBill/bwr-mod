package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.TerryTurbineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.Locale;

/** Terry RCIC/HPCI panel, with live steam and water readouts and a flange map. */
public final class TerryTurbineScreen extends BwrScreen<TerryTurbineMenu> {
    private EditBox speed;
    private Button apply,run,control,suction;
    private double displayedTarget=Double.NaN;
    private static final int[] COLORS={WARN,TEXT,ACCENT,0xFF61C9B0};
    private static final String[] LABELS={"Steam admission","Steam exhaust","Water suction","Water discharge"};
    public TerryTurbineScreen(TerryTurbineMenu menu,Inventory inv,Component title) {super(menu,inv,title,null,384,300);}
    @Override protected void init() {
        super.init();
        speed=addRenderableWidget(new EditBox(font,leftPos+114,topPos+224,70,20,Component.literal("Shaft speed percent")));
        speed.setMaxLength(5);speed.setFilter(s->s.matches("[0-9]{0,3}(\\.[0-9]{0,1})?"));
        apply=button("Apply speed",192,224,180,this::applySpeed);
        run=button("Start",12,249,172,()->menu.sendCommand(TerryTurbineMenu.RUN,menu.running?0:1));
        control=button("Control: Panel",192,249,180,()->menu.sendCommand(TerryTurbineMenu.CONTROL,(menu.control+1)%3));
        suction=button("Suction",12,274,360,()->menu.sendCommand(TerryTurbineMenu.SUCTION,menu.poolSuction?0:1));
    }
    private Button button(String label,int x,int y,int w,Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(leftPos+x,topPos+y,w,20).build());
    }
    private Integer percent() {
        try {double v=Double.parseDouble(speed.getValue());return Double.isFinite(v)&&v>=0&&v<=100?(int)Math.round(v*10):null;}
        catch(NumberFormatException e){return null;}
    }
    private void applySpeed() {Integer p=percent();if(p!=null)menu.sendCommand(TerryTurbineMenu.SPEED,p);}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(speed.isFocused()&&(key==257||key==335)){applySpeed();return true;}
        if(speed.isFocused()&&key!=256)return speed.keyPressed(key,scan,modifiers);
        return super.keyPressed(key,scan,modifiers);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if(!speed.isFocused()&&Double.compare(displayedTarget,menu.target)!=0) {
            speed.setValue(String.format(Locale.ROOT,"%.1f",menu.target*100));displayedTarget=menu.target;
        }
        boolean manual=menu.present&&menu.control!=2;
        speed.setEditable(manual);apply.active=manual&&percent()!=null;run.active=manual;control.active=menu.present;suction.active=manual;
        run.setMessage(Component.literal(menu.running?"Stop turbine":"Start turbine"));
        control.setMessage(Component.literal("Control: "+switch(menu.control){case 1->"Redstone";case 2->"Computer";default->"Panel";}));
        suction.setMessage(Component.literal(menu.poolSuction?"Water source: Suppression pool":"Water source: Condensate tank / piped water"));
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+24,0xFF293A4E);
        g.fill(leftPos+8,topPos+73,leftPos+187,topPos+137,0xFF202832);
        g.fill(leftPos+197,topPos+73,leftPos+376,topPos+137,0xFF202832);
        g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y) {g.drawString(font,font.plainSubstrByWidth(title.getString(),360),12,8,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int mx,int my) {
        text(g,!menu.present?"Waiting for turbine...":menu.running?"Steam drive | Run command active":"Steam drive | Stopped",12,32,menu.running?GOOD:TEXT_DIM);
        readout(g,"Shaft speed / target",pct(menu.actual)+" / "+pct(menu.target),12,46,372,TEXT_BRIGHT);
        bar(g,12,60,360,6,menu.actual,ACCENT);
        text(g,"STEAM CIRCUIT",14,78,WARN);text(g,"WATER CIRCUIT",203,78,ACCENT);
        readout(g,"Admission",num(menu.inletPressure,1)+" psig",14,94,181,TEXT_BRIGHT);
        readout(g,"Exhaust",num(menu.exhaustPressure,1)+" psig",14,108,181,TEXT_BRIGHT);
        readout(g,"Steam used",num(menu.steamFlow,2)+" kg/s",14,122,181,TEXT_BRIGHT);
        readout(g,"Delivered",num(menu.flow,1)+" kg/s",203,94,370,TEXT_BRIGHT);
        readout(g,"Water load",num(menu.pressure,1)+" psi",203,108,370,TEXT_BRIGHT);
        readout(g,"Inlet water",Double.isFinite(menu.temperature)?num(menu.temperature,1)+" C":"Unavailable",203,122,370,TEXT_BRIGHT);
        text(g,"CONNECTIONS / WORLD COORDINATES",12,145,TEXT_DIM);
        for(int i=0;i<4;i++) {
            int y=160+i*14;
            text(g,LABELS[i],12,y,COLORS[i]);
            text(g,font.plainSubstrByWidth(menu.portLocations[i],180),116,y,TEXT_DIM);
            String state=menu.portReady[i]?(i==2?"Water ready":"Linked"):(i==2?"Dry / shut":"No route");
            text(g,state,372-font.width(state),y,menu.portReady[i]?GOOD:TEXT_DIM);
        }
        text(g,"Shaft speed (%)",12,230,TEXT);
        if(!menu.present)return;
        if(mx>=leftPos+116&&mx<leftPos+296&&my>=topPos+160&&my<topPos+216) {
            int i=(my-topPos-160)/14;
            g.renderTooltip(font,Component.literal(menu.portLocations[i]+" | "+(i<2?"High-pressure steam pipe":i==2?"Water pipe or Mekanism mechanical pipe":"High-pressure water pipe")),mx,my);
        }
    }
}
