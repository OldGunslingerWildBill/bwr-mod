package dev.bwr.mod.devtest;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.client.ConfigLayout;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The DX1000 and DX2000 are separate items of the one paperless recorder (the owner could not find them under
 * "Paperless Recorder"), each at one fixed size (the owner's call), and a removed DX2000 comes back as a DX2000. And the wrench
 * screen's rows scroll instead of running off the screen (a gauge's look selectors sat below the bottom edge).
 * No world fixture: pure item and layout checks.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RecorderItemAndScrollTests {
    private RecorderItemAndScrollTests() {
    }

    private static Footprint footprint(ItemStack stack) {
        return ComponentItem.footprint(stack);
    }

    @GameTest(template = "empty")
    public static void dx1000AndDx2000ItemsHaveOneFixedSizeEach(GameTestHelper helper) {
        ItemStack dx1000 = new ItemStack(BwrItems.PAPERLESS_RECORDER.get());
        ItemStack dx2000 = new ItemStack(BwrItems.PAPERLESS_RECORDER_DX2000.get());
        helper.assertTrue(footprint(dx1000).equals(Footprint.of(16, 16)), "a new DX1000 is one block: " + footprint(dx1000));
        helper.assertTrue(footprint(dx2000).equals(Footprint.of(32, 32)), "a new DX2000 is 2 x 2 blocks: " + footprint(dx2000));

        var player = helper.makeMockPlayer(GameType.CREATIVE);
        java.util.Set<Integer> seen1000 = new java.util.TreeSet<>();
        java.util.Set<Integer> seen2000 = new java.util.TreeSet<>();
        for (int i = 0; i < 8; i++) {
            seen1000.add(footprint(dx1000).w());
            seen2000.add(footprint(dx2000).w());
            ComponentItem.cycle(dx1000, player);
            ComponentItem.cycle(dx2000, player);
        }
        helper.assertTrue(seen1000.equals(java.util.Set.of(16)), "the DX1000 is always 16 cells: " + seen1000);
        helper.assertTrue(seen2000.equals(java.util.Set.of(32)), "the DX2000 is always 32 cells: " + seen2000);
        helper.assertTrue(!dx1000.getItem().use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND)
                .getResult().consumesAction(), "right-clicking the air does not change a fixed size");

        // A stack saved with a size that is not the item's (an old stack) snaps to the item's default.
        ItemStack odd = new ItemStack(BwrItems.PAPERLESS_RECORDER_DX2000.get());
        odd.set(BwrDataComponents.COMPONENT_VARIANT.get(), 0);
        helper.assertTrue(footprint(odd).equals(Footprint.of(32, 32)), "a DX2000 stack never places a DX1000 size");

        // A removed DX2000 drops the DX2000 item; a DX1000 the DX1000 item (a recorder placed at an older size
        // comes back at the fixed size).
        PanelComponent big = new PanelComponent(1, ComponentTypes.PAPERLESS_RECORDER, 0, 0, Footprint.of(40, 40), "R-2");
        ((PaperlessRecorderData) big.data()).style = RecorderStyle.DX2000;
        ItemStack dropped = PanelBlockEntity.stackFor(big);
        helper.assertTrue(dropped.is(BwrItems.PAPERLESS_RECORDER_DX2000.get()) && footprint(dropped).equals(Footprint.of(32, 32)),
                "an old 40-cell DX2000 drops a DX2000 item: " + dropped + " " + footprint(dropped));
        PanelComponent small = new PanelComponent(2, ComponentTypes.PAPERLESS_RECORDER, 0, 0, Footprint.of(20, 20), "R-1");
        ((PaperlessRecorderData) small.data()).style = RecorderStyle.DX1000;
        ItemStack droppedSmall = PanelBlockEntity.stackFor(small);
        helper.assertTrue(droppedSmall.is(BwrItems.PAPERLESS_RECORDER.get()) && footprint(droppedSmall).equals(Footprint.of(16, 16)),
                "an old 20-cell DX1000 drops a DX1000 item: " + droppedSmall);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrenchScreenRowsScrollInsteadOfRunningOffTheScreen(GameTestHelper helper) {
        // A gauge's screen: label, scale, units, damping, three bands of three, style, variant and an input: 18 rows.
        int screenWidth = 427;
        int screenHeight = 240; // 1280 x 720 at GUI scale 3
        ConfigLayout tall = new ConfigLayout(screenWidth, screenHeight, 18);
        helper.assertTrue(tall.scrollable && tall.maxScroll > 0, "18 rows do not fit 240 pixels: they scroll");
        helper.assertTrue(tall.panelBottom <= screenHeight && tall.buttonY + 20 <= screenHeight,
                "Done and Cancel stay on the screen: buttons at " + tall.buttonY + " of " + screenHeight);
        helper.assertTrue(tall.viewBottom <= tall.buttonY, "the rows' viewport ends above the buttons");
        // Scrolled to the end, the last row sits inside the viewport.
        int lastRowBottom = tall.rowY(17) + tall.rowHeight - tall.maxScroll;
        helper.assertTrue(lastRowBottom <= tall.viewBottom && lastRowBottom > tall.viewTop,
                "scrolled to the end, the last row (the gauge's look) is in view: " + lastRowBottom + " vs " + tall.viewBottom);

        ConfigLayout shortScreen = new ConfigLayout(screenWidth, 480, 6);
        helper.assertTrue(!shortScreen.scrollable && shortScreen.maxScroll == 0, "six rows on a tall screen do not scroll");
        helper.assertTrue(shortScreen.buttonY == shortScreen.rowY(6) + 6, "and the buttons sit right under the rows, as before");
        helper.succeed();
    }
}
