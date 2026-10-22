package dev.bwr.mod.gui.client;

import dev.bwr.core.fuel.SpentFuel;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.gui.ReprocessingPlantMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * The reprocessing plant: the spent assembly slot and the three product slots on the left, and on the right what the
 * assembly in the slot will yield, what the plant holds in fractions, progress and the stored energy.
 *
 * <p>Layout, as on the fabricator it is styled after: the left column holds the card whose slot wells are the menu's
 * own slots (the captions are laid out to sit over them) and, below it, the inventory tray; the right column is two
 * cards and the energy meter. The panel is as tall as the menu's slot positions make it, 224 px, which fits 320 x 240.
 */
public class ReprocessingPlantScreen extends BwrScreen<ReprocessingPlantMenu> {

    private static final int LEFT_COLUMN = 164;

    public ReprocessingPlantScreen(ReprocessingPlantMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 224);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private static String number(long grains, long unit) {
        return String.format(Locale.ROOT, "%.2f", grains / (double) unit);
    }

    private static String held(long grains, long unit) {
        return String.format(Locale.ROOT, "%.3f", grains / (double) unit);
    }

    /** One line about what is in the input slot. */
    private String inputLine() {
        ItemStack held = menu.assembly();
        if (held.isEmpty()) {
            return "Put a spent assembly in the first slot";
        }
        FuelAssemblyData data = FuelAssemblies.dataOf(held);
        if (!Reprocessing.accepts(held)) {
            return "This assembly is refused";
        }
        return String.format(Locale.ROOT, "%s, burnup %.1f GWd/t", data.fuelTypeName(), data.burnupMwdPerTonne() / 1000.0);
    }

    @Override
    protected UiElement buildUi() {
        var input = Layout.line(this::inputLine, Type.CAPTION)
                .tip("A fresh assembly (no burnup) is refused, and so is a thorium one: this plant makes plutonium and uranium.");
        var slots = Layout.card("Spent fuel",
                input,
                Layout.gap(20),
                new SlotCaptions(),
                Layout.gap(2),
                Layout.gap(18));
        var left = Layout.column(BwrStyle.SP2, slots, Layout.flex());

        var expected = Layout.card("This assembly yields",
                Layout.row("Plutonium", () -> menu.expectedSplit() == null ? "-" : number(menu.expectedSplit().plutonium(), Reprocessing.GRAINS_PER_NUGGET), "nuggets")
                        .tip("Plutonium nuggets (nine to an ingot). The plant keeps the fractions and releases a nugget when it holds one."),
                Layout.row("Depleted U", () -> menu.expectedSplit() == null ? "-" : number(menu.expectedSplit().uranium(), Reprocessing.GRAINS_PER_INGOT), "ingots")
                        .tip("The uranium that is left, returned as depleted uranium ingots."),
                Layout.row("Waste", () -> menu.expectedSplit() == null ? "-" : number(menu.expectedSplit().fissionProducts(), Reprocessing.GRAINS_PER_NUGGET), "items")
                        .tip("The fissioned mass, as fission-product waste. Nothing uses it."),
                Layout.row("Heavy metal", () -> menu.expectedSplit() == null ? "-" : "4.00", "ingots")
                        .tip("The three add up to the four ingots of heavy metal in an assembly; nothing is made or lost.")).compact();

        var holding = Layout.card("Held in the plant",
                Layout.row("Plutonium", () -> held(menu.plutoniumGrains, Reprocessing.GRAINS_PER_NUGGET), "nuggets")
                        .tip("Fractions are kept from one assembly to the next; a whole nugget moves to its slot."),
                Layout.row("Depleted U", () -> held(menu.uraniumGrains, Reprocessing.GRAINS_PER_INGOT), "ingots"),
                Layout.row("Waste", () -> held(menu.wasteGrains, Reprocessing.GRAINS_PER_NUGGET), "items"),
                Layout.meter("Progress", menu::progress, null, () -> String.format(Locale.ROOT, "%.1f%%", 100.0 * menu.progress())).label("Progress"),
                Layout.meter("Stored energy", () -> menu.energyCapacity > 0 ? (double) menu.energyStored / menu.energyCapacity : 0.0,
                        () -> menu.energyStored > 0 ? Status.NONE : Status.CAUTION,
                        () -> String.format(Locale.ROOT, "%,d FE", menu.energyStored)).label("Energy")).compact();

        var split = Layout.split(BwrStyle.SP1);
        split.fixed(left, LEFT_COLUMN);
        split.add(Layout.column(BwrStyle.SP1, expected, holding), 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no reprocessing plant here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                split.showIf(() -> menu.present));
    }

    /** The four captions over the slot wells, each centred on its slot: where the menu put them, so they cannot drift. */
    private final class SlotCaptions extends UiElement {
        private static final String[] NAMES = {"Spent", "Pu", "Dep. U", "Waste"};
        private static final String[] HELP = {
                "A spent fuel assembly, from the reactor or taken out of it: one with burnup. Hoppers and pipes may fill this slot; a fresh assembly is refused.",
                "Plutonium nuggets, nine to an ingot. The fabricator's MOX route takes them.",
                "Depleted uranium ingots: the uranium that is left, whatever its enrichment. Blankets and the MOX matrix are made from it.",
                "Fission-product waste. Nothing uses it and it cannot be crafted."};
        private static final int[] SLOT = {ReprocessingPlantMenu.SLOT_INPUT, ReprocessingPlantMenu.SLOT_PLUTONIUM,
                ReprocessingPlantMenu.SLOT_URANIUM, ReprocessingPlantMenu.SLOT_WASTE};

        @Override
        protected int measure(int width) {
            return 10;
        }

        private int centre(int i) {
            return leftPos + menu.getSlot(SLOT[i]).x + 8;
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
                if (Math.abs(mouseX - centre(i)) <= 18) {
                    return List.of(Component.literal(HELP[i]));
                }
            }
            return List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Slots: spent assembly in, plutonium out, depleted uranium out, waste out");
        }
    }
}
