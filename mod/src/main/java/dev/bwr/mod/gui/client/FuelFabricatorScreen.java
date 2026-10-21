package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.FuelFabricatorMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * The fuel fabricator: what the feed is doing, how far along the batch is, and
 * the slot the finished bundle lands in.
 *
 * <p>The enrichment readout is a running measurement of the pile of heavy metal
 * the machine has accumulated so far, not a setting. There is no dial, because
 * the player's Mekanism enrichment plumbing <i>is</i> the dial: feed the machine
 * more fissile material and the bundle that comes out says so.
 *
 * <p>Layout: the output slot sits where the menu puts it (the menu's slot positions are fixed); the batch card stands
 * beside the inventory tray rather than above it, so the machine is as tall as the tray.
 */
public class FuelFabricatorScreen extends BwrScreen<FuelFabricatorMenu> {

    private static final int LEFT_COLUMN = 164;

    public FuelFabricatorScreen(FuelFabricatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 368, 166);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    @Override
    protected UiElement buildUi() {
        // Where the output slot is, so the caption stops short of it: slot x less its well, a gap and the card padding.
        int slotX = menu.slots.isEmpty() ? 116 : menu.slots.get(0).x;
        int captionWidth = slotX - 1 - BwrStyle.SP1 - 2 * BwrStyle.SP2;
        int innerWidth = LEFT_COLUMN - 2 * BwrStyle.SP2;

        var outputRow = Layout.split(0);
        outputRow.add(Layout.note("The finished bundle appears in this slot.").body(), 1);
        outputRow.fixed(Layout.gap(0), innerWidth - captionWidth);
        var output = Layout.card("", outputRow);

        var left = Layout.column(BwrStyle.SP2, output, Layout.flex());

        var batch = Layout.card("Batch",
                Layout.row("Heavy metal", () -> String.format(Locale.ROOT, "%.1f / %.0f", menu.batchHeavyMetalKg, menu.batchTargetKg), "kg HM"),
                Layout.row("Fissile", () -> pct(menu.batchEnrichmentWeightFraction), "%")
                        .tip("This is measured from the heavy metal collected so far; it is not a setting. Feed in more fissile material to get a richer bundle."),
                Layout.row("Would make", () -> menu.wouldFabricate.isEmpty() ? "-" : menu.wouldFabricate)
                        .status(() -> menu.wouldFabricate.isEmpty() ? Status.NONE : Status.INFO),
                Layout.meter("Batch progress", menu::progress, null, () -> pct(menu.progress()) + "%").label("Progress"),
                Layout.row("Stored energy", () -> String.format(Locale.ROOT, "%,d", menu.energyStored), "FE")
                        .status(() -> menu.energyStored > 0 ? Status.NONE : Status.CAUTION),
                Layout.meter("Stored energy", () -> menu.energyCapacity > 0 ? (double) menu.energyStored / menu.energyCapacity : 0.0,
                        null, () -> ""),
                Layout.gap(BwrStyle.SP1),
                Layout.line(() -> menu.feedLines.isEmpty() ? "No chemical feed is attached." : menu.feedLines.get(0), Type.CAPTION));

        var split = Layout.split(BwrStyle.SP1);
        split.fixed(left, LEFT_COLUMN);
        split.add(Layout.column(0, batch).showIf(() -> menu.present), 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no fabricator here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                split.showIf(() -> menu.present));
    }
}
