package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.RecirculationPumpMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Recirculation pump control, {@code SPEC.md} section 4.2.
 *
 * <p>The slider commands a target; the pump ramps to it through a three second
 * spin-up and an eleven second coastdown, so the actual speed lags the demand
 * and the screen shows both. When the pump cannot get there on the energy it is
 * receiving, the mismatch is spelled out — "commanded 80% / limited to 50%
 * (power)" — rather than the slider silently doing nothing.
 *
 * <p>The CC toggle decides who writes the demand. While a computer holds it the
 * slider is greyed out and follows what Lua commanded, which removes the
 * confusing case where a player drags a control that is being overwritten
 * underneath them.
 */
public class RecirculationPumpScreen extends BwrScreen<RecirculationPumpMenu> {

    public static final ResourceLocation TEXTURE = BwrMod.id("textures/gui/recirculation_pump.png");

    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    private FractionSlider slider;
    private Button controlToggle;

    public RecirculationPumpScreen(RecirculationPumpMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        slider = addRenderableWidget(new FractionSlider(leftPos + 8, topPos + 82, 160, 20,
                "SPEED DEMAND", menu.targetSpeedFraction,
                f -> menu.sendCommand(RecirculationPumpMenu.CMD_SET_TARGET, toPerMille(f))));
        controlToggle = addRenderableWidget(Button.builder(Component.literal("CONTROL: PANEL"),
                        b -> menu.sendCommand(RecirculationPumpMenu.CMD_SET_COMPUTER_CONTROL,
                                menu.computerControlled ? 0 : 1))
                .bounds(leftPos + 8, topPos + 106, 160, 20).build());
    }

    private static int toPerMille(double fraction) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * 1000.0);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // The demand is one field with two writers. Whoever does not hold it
        // follows it, so the two never show different numbers.
        //
        // "Whoever does not hold it" includes the player who has not touched
        // the slider yet, which is why this is not gated on computerControlled
        // alone. init() seeds the slider from menu.targetSpeedFraction, but on
        // the client that field is still 0.0 at that moment: the packet
        // listener builds the menu and calls init() straight away, and the
        // first snapshot cannot arrive until the server's next
        // broadcastChanges(). So a panel-controlled pump running at 100% opened
        // with its slider reading 0%, and one click anywhere on the track sent
        // that 0% straight back and dropped core flow. follow() suppresses the
        // echo packet, so following here can never command anything; it stops
        // once the player takes the widget, which is what isFocused() means
        // after a click.
        slider.active = !menu.computerControlled;
        if (menu.computerControlled || !slider.isFocused()) {
            slider.follow(menu.targetSpeedFraction);
        }
        controlToggle.setMessage(Component.literal(
                menu.computerControlled ? "CONTROL: COMPUTER" : "CONTROL: PANEL"));
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT_BRIGHT, false);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.present) {
            text(graphics, "No pump here.", 10, 22, ALARM);
            return;
        }

        int y = 20;
        readout(graphics, "COMMANDED", pct(menu.targetSpeedFraction), 10, y, 166, TEXT_BRIGHT);
        readout(graphics, "ACTUAL", pct(menu.actualSpeedFraction), 10, y += 10, 166,
                menu.actualSpeedFraction > 0.0 ? GOOD : TEXT_DIM);
        readout(graphics, "ACHIEVABLE ON POWER", pct(menu.maxAchievableSpeedFraction),
                10, y += 10, 166, menu.powerLimited ? WARN : TEXT_BRIGHT);
        readout(graphics, "ENERGY", String.format(Locale.ROOT, "%,.0f / %,.0f FE",
                menu.energyStoredFe, menu.energyCapacityFe), 10, y += 10, 166, TEXT_BRIGHT);
        readout(graphics, "REACTOR", menu.attachedToReactor ? "attached" : "not attached",
                10, y += 10, 166, menu.attachedToReactor ? GOOD : WARN);

        bar(graphics, 10, y + 12, 156, 6, menu.actualSpeedFraction, ACCENT);

        // The mismatch, spelled out exactly as the spec asks for it.
        String mismatch = menu.powerLimited
                ? String.format(Locale.ROOT, "commanded %.0f%% / limited to %.0f%% (power)",
                menu.targetSpeedFraction * 100.0, menu.maxAchievableSpeedFraction * 100.0)
                : String.format(Locale.ROOT, "commanded %.0f%%, ramping, actual %.0f%%",
                menu.targetSpeedFraction * 100.0, menu.actualSpeedFraction * 100.0);
        text(graphics, mismatch, 10, 70, menu.powerLimited ? WARN : TEXT);

        text(graphics, menu.computerControlled
                        ? "A computer holds the demand; the slider follows it."
                        : "The panel holds the demand.",
                10, 132, TEXT_DIM);
        text(graphics, String.format(Locale.ROOT,
                        "Full speed draws %,.0f FE/t; below 1,000 FE/t it stops.",
                        RecirculationPumpMenu.maxDrawFePerTick()),
                10, 142, TEXT_DIM);
    }
}
