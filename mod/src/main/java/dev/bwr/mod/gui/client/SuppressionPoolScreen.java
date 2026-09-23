package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.SuppressionPoolMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** Operator-selected filling/spray and measured pool condition. */
public class SuppressionPoolScreen extends BwrScreen<SuppressionPoolMenu> {
    public static final ResourceLocation TEXTURE=BwrMod.id("textures/gui/suppression_pool.png");
    private FractionSlider rhr;
    private Button fill,spray;
    public SuppressionPoolScreen(SuppressionPoolMenu menu,Inventory inventory,Component title) {
        super(menu,inventory,title,TEXTURE,310,246);
    }
    @Override protected void init() {
        super.init();
        fill=addRenderableWidget(Button.builder(Component.literal("Regular fill"),b->menu.sendCommand(SuppressionPoolMenu.CMD_FILL_MODE,0)).bounds(leftPos+12,topPos+191,140,20).build());
        spray=addRenderableWidget(Button.builder(Component.literal("Over-pool spray"),b->menu.sendCommand(SuppressionPoolMenu.CMD_FILL_MODE,1)).bounds(leftPos+158,topPos+191,140,20).build());
        rhr=addRenderableWidget(new FractionSlider(leftPos+12,topPos+191,286,20,"RHR DUTY",menu.rhrDuty,f->menu.sendCommand(SuppressionPoolMenu.CMD_SET_RHR_DUTY,(int)Math.round(f*1000))));
        follow();
    }
    private void follow() {
        fill.visible=spray.visible=menu.concrete;rhr.visible=!menu.concrete;
        fill.active=menu.formed&&menu.sprayMode;spray.active=menu.formed&&!menu.sprayMode;
        rhr.active=menu.formed&&!menu.concrete;if(!rhr.isFocused())rhr.follow(menu.rhrDuty);
    }
    @Override protected void containerTick(){super.containerTick();follow();}
    @Override protected void renderBg(GuiGraphics g,float partial,int x,int y) {
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff171e27);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+22,0xff293a4e);
        g.renderOutline(leftPos,topPos,imageWidth,imageHeight,0xff61738a);
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y){g.drawString(font,title,12,7,TEXT_BRIGHT,false);}
    @Override protected void renderContent(GuiGraphics g,int x,int y) {
        if(!menu.present){text(g,"No pool controller here.",12,32,ALARM);return;}
        if(!menu.formed){
            text(g,"Basin shell incomplete.",12,32,ALARM);
            text(g,"Complete the concrete floor and four walls.",12,48,TEXT);
            text(g,"Leave the top open; fit outward water ports.",12,62,TEXT);
            text(g,"Sneak-click the controller for build details.",12,82,TEXT_DIM);return;
        }
        readout(g,"Water inventory",big(menu.massKg)+" / "+big(menu.capacityKg)+" kg",12,32,298,TEXT_BRIGHT);
        bar(g,12,46,286,7,menu.massKg/Math.max(1,menu.capacityKg),ACCENT);
        readout(g,"Water temperature",num(menu.temperatureC,1)+" C",12,61,298,menu.boiling?WARN:TEXT_BRIGHT);
        readout(g,"Subcooling to saturation",num(menu.subcoolingC,1)+" C",12,75,298,TEXT_BRIGHT);
        readout(g,"Bulk condensation",pct(menu.condensationEffectiveness),12,89,298,TEXT_BRIGHT);
        readout(g,"Steam escaping bulk + spray",num(menu.uncondensedSteamKgPerS,2)+" kg/s",12,103,298,menu.uncondensedSteamKgPerS>0?WARN:TEXT_BRIGHT);
        readout(g,"Physical RHR cooling",num(menu.rhrDutyMW(),2)+" MW",12,117,298,TEXT_BRIGHT);
        readout(g,"Spray header inventory",big(menu.sprayHeaderKg)+" kg",12,131,298,ACCENT);
        readout(g,"Water spraying / steam captured",num(menu.sprayFlow,1)+" / "+num(menu.sprayCondensed,1)+" kg/s",12,145,298,ACCENT);
        readout(g,"Natural cooling",num(menu.passiveCoolingMW * 1000,1)+" kW",12,159,298,TEXT_BRIGHT);
        text(g,menu.concrete?"Inlet mode: "+(menu.sprayMode?"SPRAY":"REGULAR FILL"):"Legacy dug pool",12,172,TEXT_BRIGHT);
        text(g,menu.massKg<=0?"Empty: pump water into the amber return port.":"Spray uses supplied water; heat stays in the pool.",12,220,menu.massKg<=0?WARN:TEXT_DIM);
        text(g,"Leave room for spray water and condensed steam.",12,233,TEXT_DIM);
    }
}
