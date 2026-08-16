package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.FuelFabricatorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 */
public class FuelFabricatorScreen extends BwrScreen<FuelFabricatorMenu> {

    public static final ResourceLocation TEXTURE = BwrMod.id("textures/gui/fuel_fabricator.png");

    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;

    public FuelFabricatorScreen(FuelFabricatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, WIDTH, HEIGHT);
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.present) {
            text(graphics, "No fabricator here.", 10, 22, ALARM);
            return;
        }

        int y = 20;
        readout(graphics, "BATCH", String.format(Locale.ROOT, "%.1f / %.0f kg HM",
                menu.batchHeavyMetalKg, menu.batchTargetKg), 10, y, 112, TEXT_BRIGHT);
        readout(graphics, "FISSILE", pct(menu.batchEnrichmentWeightFraction),
                10, y += 10, 112, TEXT_BRIGHT);
        readout(graphics, "WOULD MAKE", menu.wouldFabricate.isEmpty() ? "-" : menu.wouldFabricate,
                10, y += 10, 112, menu.wouldFabricate.isEmpty() ? TEXT_DIM : ACCENT);

        // One line of feed status. The chemical side is Mekanism's; if it is not
        // installed this says so, and the machine sits there doing nothing.
        String feed = menu.feedLines.isEmpty() ? "No chemical feed attached." : menu.feedLines.get(0);
        text(graphics, feed.length() > 44 ? feed.substring(0, 44) : feed, 10, y + 10, TEXT_DIM);

        bar(graphics, 10, 64, 100, 8, menu.progress(), ACCENT);
        text(graphics, pct(menu.progress()), 114, 65, TEXT_DIM);

        bar(graphics, 10, 74, 100, 6,
                menu.energyCapacity > 0 ? (double) menu.energyStored / menu.energyCapacity : 0.0,
                menu.energyStored > 0 ? GOOD : WARN);
        text(graphics, String.format(Locale.ROOT, "%,d FE", menu.energyStored), 114, 74, TEXT_DIM);
    }
}
