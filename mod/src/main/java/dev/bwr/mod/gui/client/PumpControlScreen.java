package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.PumpControlMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import java.util.Locale;

/** Compact manual controls. Readouts come from the server's pump, not client estimates. */
public class PumpControlScreen extends BwrScreen<PumpControlMenu> {
    private EditBox speed;
    private Button apply,run,control,suction,mode;
    private double displayedTarget=Double.NaN;
    public PumpControlScreen(PumpControlMenu menu,Inventory inv,Component title) {super(menu,inv,title,null,260,234);}
    @Override protected void init() {
        super.init();
        speed=addRenderableWidget(new EditBox(font,leftPos+90,topPos+132,64,20,Component.literal("Speed percent")));
        speed.setMaxLength(6);speed.setFilter(s->s.matches("[0-9]{0,3}(\\.[0-9]{0,1})?"));
        apply=button("Apply",164,132,84,()->applySpeed());
        run=button("Start",12,158,112,()->menu.sendCommand(PumpControlMenu.RUN,menu.running?0:1));
        control=button("Control: Panel",132,158,116,()->menu.sendCommand(PumpControlMenu.CONTROL,menu.recirculation?(menu.control==2?0:2):(menu.control+1)%3));
        suction=button("Suction",12,184,236,()->menu.sendCommand(PumpControlMenu.SUCTION,menu.poolSuction?0:1));
        mode=button("Mode",12,208,236,()->menu.sendCommand(PumpControlMenu.MODE,menu.poolCooling?0:1));
    }
    private Button button(String label,int x,int y,int w,Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(leftPos+x,topPos+y,w,20).build());
    }
    private Integer percent() {
        try {double v=Double.parseDouble(speed.getValue());return Double.isFinite(v) && v>=0 && v<=100?(int)Math.round(v*10):null;}catch(NumberFormatException e){return null;}
    }
    private void applySpeed() {Integer value=percent();if(value!=null)menu.sendCommand(PumpControlMenu.SPEED,value);}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(speed.isFocused() && (key==257 || key==335)){applySpeed();return true;}
        if(speed.isFocused() && key!=256)return speed.keyPressed(key,scan,modifiers);
        return super.keyPressed(key,scan,modifiers);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if(!speed.isFocused() && Double.compare(displayedTarget,menu.target)!=0) {
            speed.setValue(String.format(Locale.ROOT,"%.1f",menu.target*100));displayedTarget=menu.target;
        }
        speed.setEditable(menu.present && menu.control!=2);
        apply.active=menu.present && menu.control!=2 && percent()!=null;
        run.active=menu.present && menu.control!=2;run.setMessage(Component.literal(menu.running?"Stop":"Start"));
        control.active=menu.present;control.setMessage(Component.literal("Control: "+switch(menu.control){case 1->"Redstone";case 2->"Computer";default->"Panel";}));
        suction.visible=menu.eccs;suctions();
        mode.visible=menu.rhr;mode.active=menu.control!=2;mode.setMessage(Component.literal(menu.poolCooling?"Mode: Pool cooling":"Mode: Reactor injection"));
    }
    private void suctions() {
        suction.active=menu.present && menu.control!=2;
        suction.setMessage(Component.literal(menu.poolSuction?"Suction: Suppression pool":"Suction: Tank / piped water"));
    }
    @Override protected void renderBg(GuiGraphics g,float partial,int mx,int my) {
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF171D26);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+23,0xFF293A4E);
        g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xFF58708A);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y) {
        g.drawString(font,font.plainSubstrByWidth(title.getString(),240),10,8,TEXT_BRIGHT,false);
    }
    @Override protected void renderContent(GuiGraphics g,int mx,int my) {
        text(g,menu.present?menu.drive+" / "+(menu.running?"Running":"Stopped"):"Waiting for pump...",12,29,menu.running?GOOD:TEXT_DIM);
        readout(g,"Target / actual",pct(menu.target)+" / "+pct(menu.actual),12,43,248,TEXT_BRIGHT);
        readout(g,menu.recirculation?"Core-flow contribution":"Water flow",num(menu.flow,1)+" kg/s",12,57,248,TEXT_BRIGHT);
        readout(g,menu.recirculation?"Vessel pressure":"Discharge differential",num(menu.pressure,1)+" psi",12,71,248,TEXT_BRIGHT);
        readout(g,"Water temperature",Double.isFinite(menu.temperature)?num(menu.temperature,1)+" C":"Unavailable",12,85,248,TEXT_BRIGHT);
        readout(g,menu.turbine?"Drive":"Stored energy",menu.turbine?"Steam":big(menu.energy)+" FE",12,99,248,TEXT_BRIGHT);
        text(g,font.plainSubstrByWidth(menu.connection,236),12,115,TEXT_DIM);
        text(g,"Speed (%)",12,138,TEXT);
        if(!menu.eccs)text(g,menu.recirculation?"Speed determines circulation demand.":"Suction: tank / piped water",12,189,TEXT_DIM);
        if(!menu.rhr)text(g,menu.control==2?"Computer owns the controls.":menu.recirculation?"Flow is this pump's share of core circulation.":"Inlet buffer: "+big(menu.buffer)+" / 2,000 kg",12,215,TEXT_DIM);
    }
}
