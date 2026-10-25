package dev.bwr.mod.gui.client;

import dev.bwr.core.isotope.IsotopeExtraction;
import dev.bwr.core.fuel.SpentFuel;
import dev.bwr.mod.gui.IsotopeSeparatorMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import dev.bwr.mod.isotope.Isotopes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * The isotope separator: the waste slot and the four product slots on the left (captions over the slots the menu placed),
 * and on the right what one waste item yields, what the machine holds in fractions, progress and stored energy.
 */
public class IsotopeSeparatorScreen extends BwrScreen<IsotopeSeparatorMenu> {

    private static final int LEFT_COLUMN = 164;
    private static final IsotopeExtraction.Split PER_ITEM = IsotopeExtraction.split(SpentFuel.GRAINS_PER_NINTH);

    public IsotopeSeparatorScreen(IsotopeSeparatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 224);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private static String grams(long grains) {
        return String.format(Locale.ROOT, "%.1f", IsotopeExtraction.grams(grains));
    }

    @Override
    protected UiElement buildUi() {
        var line = Layout.line(() -> "Fission product waste in; capsules and stripped waste out", Type.CAPTION)
                .tip("Takes fission-product waste from the reprocessing plant. Stripped waste cannot be put back in: its isotopes are gone.");
        var slots = Layout.card("Waste", line, Layout.gap(20), new SlotCaptions(), Layout.gap(2), Layout.gap(18));
        var left = Layout.column(BwrStyle.SP2, slots, Layout.flex());

        var perItem = Layout.card("One waste item yields",
                Layout.row("Sr-90", () -> grams(PER_ITEM.strontium()), "g")
                        .tip("Strontium-90 with its yttrium-90: 0.92 W per gram. A capsule is 250 g."),
                Layout.row("Cs-137", () -> grams(PER_ITEM.caesium()), "g")
                        .tip("Caesium-137 with barium-137m: strong gamma, 0.42 W per gram when the gamma is absorbed. A capsule is 250 g."),
                Layout.row("Am-241", () -> grams(PER_ITEM.americium()), "g")
                        .tip("Americium-241, grown in from plutonium-241: 0.115 W per gram, a half-life of 432 years. A capsule is 250 g."),
                Layout.row("Stripped", () -> grams(PER_ITEM.stripped()), "g")
                        .tip("What is left of the 5 kg item: the same mass, minus the three isotopes.")).compact();

        var holding = Layout.card("Held in the machine",
                Layout.row("Sr-90", () -> grams(menu.strontiumGrains), "g"),
                Layout.row("Cs-137", () -> grams(menu.caesiumGrains), "g"),
                Layout.row("Am-241", () -> grams(menu.americiumGrains), "g"),
                Layout.meter("Progress", menu::progress, null, () -> String.format(Locale.ROOT, "%.1f%%", 100.0 * menu.progress())).label("Progress"),
                Layout.meter("Stored energy", () -> menu.energyCapacity > 0 ? (double) menu.energyStored / menu.energyCapacity : 0.0,
                        () -> menu.energyStored > 0 ? Status.NONE : Status.CAUTION,
                        () -> String.format(Locale.ROOT, "%,d FE", menu.energyStored)).label("Energy")).compact();

        var split = Layout.split(BwrStyle.SP1);
        split.fixed(left, LEFT_COLUMN);
        split.add(Layout.column(BwrStyle.SP1, perItem, holding), 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no isotope separator here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                split.showIf(() -> menu.present));
    }

    private final class SlotCaptions extends UiElement {
        private static final String[] NAMES = {"Waste", "Sr-90", "Cs-137", "Am-241", "Stripped"};
        private static final String[] HELP = {
                "Fission-product waste, from the reprocessing plant. Hoppers and pipes may fill this slot.",
                "Strontium-90 capsules, 250 g each: the best RTG fuel here, 0.92 W/g and a 28.9 year half-life.",
                "Caesium-137 capsules: dangerous to carry, 0.42 W/g in an RTG that absorbs the gamma.",
                "Americium-241 capsules: weak, long-lived heat, and the Am-Be startup source's isotope.",
                "Stripped waste: the mass the isotopes were taken from. It cannot be separated again."};

        @Override
        protected int measure(int width) {
            return 10;
        }

        private int centre(int i) {
            return leftPos + menu.getSlot(i).x + 8;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY) {
            for (int i = 0; i < NAMES.length; i++) {
                Ui.textCentred(g, NAMES[i], centre(i), y + 1, Type.CAPTION, BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public List<Component> tooltipAt(int mouseX, int mouseY) {
            if (!contains(mouseX, mouseY)) {
                return List.of();
            }
            for (int i = 0; i < NAMES.length; i++) {
                if (Math.abs(mouseX - centre(i)) <= 14) {
                    return List.of(Component.literal(HELP[i]));
                }
            }
            return List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Slots: waste in; strontium, caesium, americium capsules and stripped waste out");
        }
    }
}
