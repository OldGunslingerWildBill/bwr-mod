package dev.bwr.mod.gui.client;

import dev.bwr.core.fuel.FuelType;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.FuelTypes;
import dev.bwr.mod.gui.FuelFabricatorMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
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
 * The fuel fabricator: the item route's four slots and its enrichment selector on the left, and on the right what
 * each route is doing and the stored energy.
 *
 * <p>The selector picks the assembly the item route makes (a target, with what it costs written under it). The
 * chemical route has no selector: its enrichment readout is a running measurement of the pile of heavy metal the
 * machine has accumulated so far, because the player's Mekanism plumbing <i>is</i> the dial there.
 *
 * <p>Layout: the left column holds the item route card, whose slot wells are the menu's own slots (the captions are
 * laid out to sit over them) and, below it, the inventory tray; the right column is two cards and the energy meter.
 * The panel is as tall as the menu's slot positions make it, 224 px, which still fits the smallest GUI size.
 */
public class FuelFabricatorScreen extends BwrScreen<FuelFabricatorMenu> {

    /**
     * 416 px, 48 more than the first version, all of it to the right column: the labels there ("Would make LEU 4.1% UO2
     * assembly", a feed line) were cut at 184 px. A smaller window still shrinks the panel (to 320 at the smallest GUI size).
     */
    private static final int PANEL_WIDTH = 416;
    private static final int LEFT_COLUMN = 164;
    /** The room a line of the item route card has: the column less the card's padding on both sides. */
    private static final int LEFT_TEXT = LEFT_COLUMN - 2 * BwrStyle.SP2;

    private BwrButton previous;
    private BwrButton pick;
    private BwrButton next;

    public FuelFabricatorScreen(FuelFabricatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PANEL_WIDTH, 224);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private int count() {
        return FuelItemRoute.SELECTOR.size();
    }

    private void step(int direction) {
        int index = Math.floorMod(menu.targetIndex + direction, count());
        int ladder = FuelItemRoute.LADDER.size();
        if (index < ladder) {
            menu.sendCommand(FuelFabricatorMenu.CMD_SELECT, index);
        } else {
            menu.sendCommand(FuelFabricatorMenu.CMD_SELECT_THORIUM, index - ladder);
        }
    }

    /** The assembly the selector is on: its fuel type, which the client has from the fuel sync, or null. */
    private FuelType targetType() {
        String name = FuelItemRoute.SELECTOR.get(Math.max(0, Math.min(count() - 1, menu.targetIndex)));
        FuelItemRoute.Cost cost = FuelItemRoute.cost(name);
        return FuelTypes.byNameOrPreset(cost != null ? cost.fuelType() : name);
    }

    private String targetLabel() {
        FuelType type = targetType();
        String percent = type == null ? "?" : String.format(Locale.ROOT, "%.2f %%", type.nominalEnrichmentWeightFraction() * 100.0);
        if (menu.targetIndex >= FuelItemRoute.LADDER.size()) {
            return "Th " + percent;
        }
        return menu.targetIndex >= FuelItemRoute.TARGETS.size() ? "MOX " + percent : percent;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (pick != null) {
            pick.setMessage(Component.literal(targetLabel()));
            pick.active = menu.present;
            previous.active = menu.present && menu.targetIndex > 0;
            next.active = menu.present && menu.targetIndex < count() - 1;
        }
    }

    private static String ingots(int units) {
        return String.format(Locale.ROOT, units % FuelItemRoute.UNITS_PER_INGOT == 0 ? "%.0f" : "%.1f",
                units / (double) FuelItemRoute.UNITS_PER_INGOT);
    }

    /** The first of the candidates that fits {@code width} px in caption type, else the last (which the line then cuts). */
    private static String firstThatFits(int width, String... candidates) {
        for (String candidate : candidates) {
            if (Ui.width(candidate, Type.CAPTION) <= width) {
                return candidate;
            }
        }
        return candidates[candidates.length - 1];
    }

    /**
     * What the selected assembly costs, in the column's 148 px. The comma wording fits every grade of the ladder (the 20 %
     * grade's three-digit "172" was the one that did not fit "Costs 172 uranium and 4 steel"); a datapack grade with a
     * four- or five-digit cost falls back to shorter wording rather than being cut.
     */
    private String costText() {
        if (!menu.hasCost) {
            return firstThatFits(LEFT_TEXT, "This enrichment cannot be made", "Cannot be made");
        }
        if (menu.thoriumTarget) {
            // thorium grades: the uranium is enriched to the driver inside the machine
            return firstThatFits(LEFT_TEXT,
                    "Costs " + menu.thoriumCost + " thorium, " + menu.costIngots + " uranium, " + menu.costSteel + " steel",
                    menu.thoriumCost + " Th, " + menu.costIngots + " U, " + menu.costSteel + " steel",
                    menu.thoriumCost + " Th, " + menu.costIngots + " U, " + menu.costSteel + " st");
        }
        return firstThatFits(LEFT_TEXT,
                "Costs " + menu.costIngots + " uranium, " + menu.costSteel + " steel",
                menu.costIngots + " uranium, " + menu.costSteel + " steel",
                menu.costIngots + " U, " + menu.costSteel + " steel");
    }

    private int steelLoaded() {
        return menu.getSlot(FuelFabricatorMenu.SLOT_STEEL).getItem().getCount();
    }

    @Override
    protected UiElement buildUi() {
        if (pick == null) {
            previous = BwrButton.secondary("<", "Select the next lower enrichment.", b -> step(-1));
            pick = BwrButton.secondary(targetLabel(), "The enrichment of the assembly the item route makes. Click to step up to the next one, or use the arrows.",
                    b -> step(menu.targetIndex + 1 >= count() ? -(count() - 1) : 1));
            next = BwrButton.secondary(">", "Select the next higher enrichment.", b -> step(1));
        }

        var selector = Layout.controls(BwrStyle.SP1).fixed(previous, 24).add(pick, 1).fixed(next, 24);
        var cost = Layout.line(this::costText, Type.CAPTION)
                .tip(() -> menu.thoriumTarget
                        ? List.of(Component.literal("One thorium assembly takes " + menu.thoriumCost + " thorium ingots, " + menu.costIngots + " natural uranium ingots and "
                                        + menu.costSteel + " steel ingots, and returns " + menu.costTails + " depleted uranium."),
                                Component.literal("Thorium is fertile, not fissile: the uranium is the fissile driver, enriched to 19.75 % inside the machine (the same mass balance as the uranium grades) and sized to the fuel's own driver fraction. Raw thorium counts as an ingot."))
                        : menu.hasCost
                        ? List.of(Component.literal("One assembly takes " + menu.costIngots + " uranium ingots and " + menu.costSteel
                                        + " steel ingots and returns " + menu.costTails + " depleted uranium."),
                                Component.literal("The uranium grows with enrichment: it is the feed an enrichment plant needs for that product."))
                        : List.of(Component.literal("This fuel's enrichment is below natural uranium, which no enrichment plant can make.")));
        // MOX grades: the uranium cost line does not apply, this one does
        cost.showIf(() -> !menu.moxTarget);
        // MOX grades, one line (the card has room for the one line the uranium cost uses): held / needed
        var moxCost = Layout.line(() -> firstThatFits(LEFT_TEXT,
                        "Pu " + menu.moxPlutoniumHeld + " / " + menu.moxPlutonium + ", DU " + menu.moxMatrixHeld + " / " + menu.moxMatrix + ", steel " + menu.costSteel,
                        "Pu " + menu.moxPlutoniumHeld + "/" + menu.moxPlutonium + ", DU " + menu.moxMatrixHeld + "/" + menu.moxMatrix + ", steel " + menu.costSteel), Type.CAPTION)
                .tip(() -> List.of(Component.literal("One MOX assembly takes " + menu.moxPlutonium + " plutonium nuggets (nine to an ingot), "
                                + menu.moxMatrix + " depleted uranium ingots as the matrix and " + menu.costSteel + " steel ingots, and returns nothing."),
                        Component.literal("Held by the machine so far / needed: plutonium " + menu.moxPlutoniumHeld + " / " + menu.moxPlutonium
                                + ", depleted uranium " + menu.moxMatrixHeld + " / " + menu.moxMatrix + ". The plutonium is the fuel's own enrichment; the matrix is the rest of the four ingots of heavy metal. The input slot takes plutonium and depleted uranium on a MOX grade.")))
                .showIf(() -> menu.moxTarget);
        var item = Layout.card("Item route",
                selector,
                Layout.gap(2),
                cost,
                moxCost,
                Layout.gap(2),
                new SlotCaptions(),
                Layout.gap(2),
                Layout.gap(18));
        var left = Layout.column(BwrStyle.SP2, item, Layout.flex());

        var assembly = Layout.card("Item assembly",
                Layout.row("Uranium in", () -> ingots(menu.uraniumHeldUnits) + " / " + menu.costIngots, "ingots").dense()
                        .tip("The machine takes uranium out of its slot until it holds enough for one assembly.")
                        .showIf(() -> !menu.moxTarget),
                Layout.row("Thorium in", () -> menu.thoriumHeld + " / " + menu.thoriumCost, "ingots").dense()
                        .tip("The machine takes thorium ingots (or raw thorium) out of the same slot until it holds enough for one assembly.")
                        .showIf(() -> menu.thoriumTarget),
                Layout.row("Steel in", () -> steelLoaded() + " / " + menu.costSteel, "ingots").dense(),
                Layout.row("Depleted U held", () -> String.valueOf(menu.tailsHeld), "ingots").dense()
                        .tip("What did not become enriched product. It comes out of the depleted uranium slot, a stack at a time."),
                Layout.meter("Item progress", () -> menu.costWorkTicks > 0 ? menu.workTicks / (double) menu.costWorkTicks : 0.0, null,
                        () -> menu.costWorkTicks > 0 ? pct(menu.workTicks / (double) menu.costWorkTicks) + "%" : "0.0%").label("Progress")).compact();

        var batch = Layout.card("Chemical batch",
                Layout.row("Heavy metal", () -> String.format(Locale.ROOT, "%.1f / %.0f", menu.batchHeavyMetalKg, menu.batchTargetKg), "kg HM").dense(),
                Layout.row("Fissile", () -> pct(menu.batchEnrichmentWeightFraction), "%").dense()
                        .tip("This is measured from the heavy metal collected so far; it is not a setting. Feed in more fissile material to get a richer bundle."),
                Layout.row("Would make", () -> menu.wouldFabricate.isEmpty() ? "-" : menu.wouldFabricate).dense()
                        .status(() -> menu.wouldFabricate.isEmpty() ? Status.NONE : Status.INFO),
                Layout.meter("Batch progress", menu::progress, null, () -> pct(menu.progress()) + "%").label("Progress"),
                Layout.note(() -> menu.feedLines.isEmpty() ? "No chemical feed is attached." : menu.feedLines.get(0)).maxLines(2)).compact();

        var energy = Layout.card("",
                Layout.meter("Stored energy", () -> menu.energyCapacity > 0 ? (double) menu.energyStored / menu.energyCapacity : 0.0,
                        () -> menu.energyStored > 0 ? Status.NONE : Status.CAUTION,
                        () -> String.format(Locale.ROOT, "%,d FE", menu.energyStored)).label("Energy")).compact();

        var split = Layout.split(BwrStyle.SP1);
        split.fixed(left, LEFT_COLUMN);
        split.add(Layout.column(BwrStyle.SP1, assembly, batch, energy), 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no fabricator here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                split.showIf(() -> menu.present));
    }

    /** The four captions over the slot wells, each centred on its slot: where the menu put them, so they cannot drift. */
    private final class SlotCaptions extends UiElement {
        private static final String[] NAMES = {"Uranium", "Steel", "Fuel", "Depleted"};
        private static final String[] HELP = {
                "Uranium ingots, raw uranium or Mekanism yellow cake, from any mod that tags them as uranium. Hoppers and pipes may fill this slot.",
                "Steel ingots: the cladding and structure of the assembly.",
                "The finished fuel assembly. The machine stops until it is taken.",
                "Depleted uranium: the uranium that did not become enriched product. Take it out; the machine stops when it holds 512."};
        private static final int[] SLOT = {FuelFabricatorMenu.SLOT_URANIUM, FuelFabricatorMenu.SLOT_STEEL,
                FuelFabricatorMenu.SLOT_ASSEMBLY, FuelFabricatorMenu.SLOT_TAILS};

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
                Ui.textCentred(g, i == 0 && menu.moxTarget ? "Pu, DU" : i == 0 && menu.thoriumTarget ? "U, Th" : NAMES[i], centre(i), y + 1, Type.CAPTION, BwrStyle.TEXT_SECONDARY);
            }
        }

        @Override
        public List<Component> tooltipAt(int mouseX, int mouseY) {
            if (!contains(mouseX, mouseY)) {
                return List.of();
            }
            for (int i = 0; i < NAMES.length; i++) {
                if (Math.abs(mouseX - centre(i)) <= 18) {
                    return List.of(Component.literal(i == 0 && menu.moxTarget
                            ? "Plutonium nuggets or ingots and depleted uranium ingots, for the MOX grades. Hoppers and pipes may fill this slot."
                            : i == 0 && menu.thoriumTarget
                            ? "Uranium (for the driver) and thorium ingots or raw thorium, for the thorium grades. Hoppers and pipes may fill this slot."
                            : HELP[i]));
                }
            }
            return List.of();
        }

        @Override
        public void narrate(List<String> out) {
            out.add("Slots: uranium in, steel in, assembly out, depleted uranium out");
        }
    }
}
