package dev.bwr.mod.gui.client;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.gui.RtgMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * The RTG: the six capsule wells on the left (their captions sit over the slots the menu placed), and on the right what
 * they make: thermal watts, electric watts and FE per tick, the shortest half-life of what is loaded, the dose at a metre
 * and the energy waiting in the buffer.
 */
public class RtgScreen extends BwrScreen<RtgMenu> {

    private static final int LEFT_COLUMN = 164;

    private dev.bwr.mod.gui.client.style.BwrButton heat;

    public RtgScreen(RtgMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 224);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private String loadedLine() {
        return menu.capsules == 0 ? "Put isotope ingots in the wells"
                : String.format(Locale.ROOT, "%d of 6 wells loaded", menu.capsules);
    }

    private String halfLife() {
        double y = menu.shortestHalfLifeYears;
        if (menu.capsules == 0 || y <= 0.0) {
            return "-";
        }
        return y >= 100.0 ? String.format(Locale.ROOT, "%.0f", y) : String.format(Locale.ROOT, "%.1f", y);
    }

    @Override
    protected UiElement buildUi() {
        if (heat == null) {
            heat = dev.bwr.mod.gui.client.style.BwrButton.secondary("Heat only",
                    "Stops the electricity and nothing else. The thermocouples turn only 5 % of the heat into FE, so the RTG makes its full thermal power either way; "
                            + "with this on none of it leaves as FE (use it as a heater). Nothing switches it for you.",
                    b -> menu.sendCommand(RtgMenu.CMD_HEAT_ONLY, menu.heatOnly ? 0 : 1));
        }
        var loaded = Layout.line(this::loadedLine, Type.CAPTION)
                .tip("Each well takes one isotope ingot (or block, or a separator capsule): strontium-90, caesium-137, americium-241 or cobalt-60. "
                        + "Anything with an entry in the RTG fuel list is accepted.");
        var wells = Layout.card("Isotopes", loaded, Layout.gap(20), new WellCaptions(), Layout.gap(2), Layout.gap(18));
        var left = Layout.column(BwrStyle.SP2, wells, Layout.flex());

        var power = Layout.card("Output",
                Layout.row("Thermal", () -> String.format(Locale.ROOT, "%.1f", menu.thermalWatts), "W")
                        .tip("Heat the capsules give off: grams times watts per gram, less decay. The body always runs at this, loaded or not."),
                Layout.row("Electric", () -> String.format(Locale.ROOT, "%.2f", menu.electricWatts), "W")
                        .tip("The thermocouples turn 5 % of the heat into electricity."),
                Layout.row("Generating", () -> String.format(Locale.ROOT, "%.3f", menu.fePerTick), "FE/t")
                        .tip("Electric watts over 13.4 W per FE/t, the mod's one conversion, times the server's RTG output multiplier (1 unless changed)."),
                Layout.row("Half-life", this::halfLife, "years")
                        .tip("The shortest half-life of what is loaded. Output falls by half in that time; over a play session it is nothing.")).compact();

        var safety = Layout.card("Radiation and buffer",
                Layout.row("Dose at 1 m", () -> DoseModel.formatRate(menu.doseMsvPerH), "")
                        .tip("What a person a metre from the block reads, through its housing. Caesium-137 needs a wall."),
                Layout.meter("Buffer", () -> menu.bufferFe / 1000.0,
                        () -> menu.capsules == 0 ? Status.CAUTION : Status.NONE,
                        () -> String.format(Locale.ROOT, "%.0f FE", menu.bufferFe)).label("Energy")).compact();

        var split = Layout.split(BwrStyle.SP1);
        split.fixed(left, LEFT_COLUMN);
        split.add(Layout.column(BwrStyle.SP1, power, safety, Layout.controls(BwrStyle.SP1).add(heat, 1)), 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no RTG here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                split.showIf(() -> menu.present));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (heat != null) {
            heat.setMessage(Component.literal(menu.heatOnly ? "Heat only: ON" : "Heat only: off"));
            heat.active = menu.present;
        }
    }

    /** Numbers over the six wells, each centred on its slot. */
    private final class WellCaptions extends UiElement {

        @Override
        protected int measure(int width) {
            return 10;
        }

        private int centre(int i) {
            return leftPos + menu.getSlot(RtgMenu.FIRST_SLOT + i).x + 8;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            for (int i = 0; i < 6; i++) {
                Ui.textCentred(g, Integer.toString(i + 1), centre(i), y + 1, Type.CAPTION, BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public List<Component> tooltipAt(int mouseX, int mouseY) {
            return contains(mouseX, mouseY)
                    ? List.of(Component.literal("Six wells, one item each. Power is the sum of what is loaded."))
                    : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Six capsule wells");
        }
    }
}
