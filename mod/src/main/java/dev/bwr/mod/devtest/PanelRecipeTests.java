package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The panel key is unstackable, so crafting it must never create an invalid oversized stack. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelRecipeTests {
    private PanelRecipeTests() {}

    @GameTest(template = "empty")
    public static void panelKeyRecipeMakesOneUnstackableKey(GameTestHelper helper) {
        var recipe = helper.getLevel().getRecipeManager().byKey(
                ResourceLocation.fromNamespaceAndPath("bwr", "panel_key"));
        helper.assertTrue(recipe.isPresent(), "panel key recipe is missing");
        var result = recipe.get().value().getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(result.is(BwrItems.PANEL_KEY.get()), "panel key recipe yields the wrong item");
        helper.assertTrue(result.getCount() == 1 && result.getCount() <= result.getMaxStackSize(),
                "panel key recipe yields an invalid stack");
        helper.succeed();
    }
}
