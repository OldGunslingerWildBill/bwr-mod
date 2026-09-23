package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.SuppressionPoolMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Suppression pool panel — {@code SPEC.md} section 12.
 *
 * <p>Temperature and subcooling say how close the pool is to being unable to
 * condense; condensation effectiveness says how well it is doing right now; and
 * remaining heat capacity in megajoules says how much longer it can keep doing
 * it. That last number is the one that turns an extended transient into a
 * decision, because it is finite and it is visibly falling.
 *
 * <p>RHR is a slider. It does not come on by itself at any temperature, and
 * there is no limit line on this screen that anything acts on.
 */
public class SuppressionPoolScreen extends BwrScreen<SuppressionPoolMenu> {

    public static final ResourceLocation TEXTURE = BwrMod.id("textures/gui/suppression_pool.png");

    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    private FractionSlider rhr;

    public SuppressionPoolScreen(SuppressionPoolMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        rhr = addRenderableWidget(new FractionSlider(leftPos + 8, topPos + 114, 160, 20,
                "RHR DUTY", menu.rhrDuty,
                f -> menu.sendCommand(SuppressionPoolMenu.CMD_SET_RHR_DUTY,
                        (int) Math.round(f * 1000.0))));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        rhr.visible=!menu.concrete;rhr.active=!menu.concrete;
        // The duty is persisted in NBT and survives a reload, so the slider has
        // to be told what it already is. init() cannot do it: the client's copy
        // of menu.rhrDuty is still 0.0 when the screen is built, and the first
        // snapshot only arrives on the server's next broadcastChanges(). That
        // left a pool with RHR at 80% showing "RHR DUTY 0%" over a line reading
        // "RHR removing 24.0 MW", and one click on the track would have sent
        // the 0% the slider was showing. follow() suppresses the echo packet,
        // and stops once the player takes the widget.
        if (!rhr.isFocused()) {
            rhr.follow(menu.rhrDuty);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT_BRIGHT, false);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.present) {
            text(graphics, "No pool controller here.", 10, 22, ALARM);
            return;
        }
        if (!menu.formed) {
            text(graphics, "Pool not formed.", 10, 22, ALARM);
            text(graphics, "Complete and fill the basin.", 10, 34, TEXT);
            text(graphics, "Sneak-click for details.", 10, 44, TEXT);
            return;
        }

        int y = 20;
        readout(graphics, "TEMPERATURE", num(menu.temperatureC, 2) + " C", 10, y, 166,
                menu.boiling ? ALARM : TEXT_BRIGHT);
        readout(graphics, "SATURATION", num(menu.saturationTemperatureC, 2) + " C",
                10, y += 10, 166, TEXT_BRIGHT);
        readout(graphics, "SUBCOOLING", num(menu.subcoolingC, 2) + " C", 10, y += 10, 166,
                menu.subcoolingC < 10.0 ? WARN : GOOD);
        readout(graphics, "CONDENSING AT", pct(menu.condensationEffectiveness),
                10, y += 10, 166,
                menu.condensationEffectiveness < 0.5 ? ALARM
                        : menu.condensationEffectiveness < 0.9 ? WARN : GOOD);
        readout(graphics, "CAPACITY LEFT", big(menu.remainingHeatCapacityMJ) + " MJ",
                10, y += 10, 166, TEXT_BRIGHT);
        readout(graphics, "HEAT IN", big(menu.cumulativeHeatInputMJ) + " MJ",
                10, y += 10, 166, TEXT_DIM);
        readout(graphics, "RHR REMOVED", big(menu.cumulativeRhrRemovedMJ) + " MJ",
                10, y += 10, 166, TEXT_DIM);
        readout(graphics, "UNCONDENSED", num(menu.uncondensedSteamKgPerS, 2) + " kg/s",
                10, y += 10, 166, menu.uncondensedSteamKgPerS > 0.0 ? WARN : TEXT_DIM);
        readout(graphics, "POOL", String.format(Locale.ROOT, "%d blocks, %d SRV",
                menu.waterBlocks, menu.dischargingValves), 10, y += 10, 166, TEXT_BRIGHT);

        // Capacity remaining as a bar, against the capacity of a cold full pool.
        double coldCapacityMJ = Math.max(1.0, menu.massKg * 4.186e-3 * 68.0);
        bar(graphics, 10, 138, 156, 6,
                menu.remainingHeatCapacityMJ / coldCapacityMJ,
                menu.remainingHeatCapacityMJ / coldCapacityMJ < 0.2 ? ALARM : ACCENT);

        if(menu.concrete) {
            text(graphics,"RHR -> exchanger -> pool",10,116,TEXT_DIM);
            text(graphics,"Separate cooling circuit",10,126,TEXT_DIM);
            text(graphics,String.format(Locale.ROOT,"Cooling: %.2f MW",menu.physicalCoolingMW),10,148,TEXT_DIM);
        } else text(graphics, String.format(Locale.ROOT, "RHR removing %.1f MW of %.0f MW installed",
                menu.rhrDutyMW(), menu.rhrCapacityMW), 10, 148, TEXT_DIM);

        if (menu.boiling) {
            text(graphics, "POOL BOILING - steam is passing through to containment",
                    10, 106, ALARM);
        }
    }
}
