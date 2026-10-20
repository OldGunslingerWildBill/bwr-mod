package dev.bwr.mod.panel.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelFinish;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import java.util.List;

/**
 * Colours the painted materials of every block that has a {@link PanelFinish} (panel bodies, trim, furniture): the
 * models carry a tint index on their paint, bezel and top materials and the finish of the block (or of the item's state
 * component) says what colour that is. A block is picked up by having the property, so a new piece needs no entry here.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelFinishColors {
    private PanelFinishColors() {
    }

    /** Every block of this mod with a finish property. */
    public static List<Block> finishBlocks() {
        return BuiltInRegistries.BLOCK.stream()
                .filter(block -> BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(BwrMod.MOD_ID))
                .filter(block -> block.getStateDefinition().getProperties().contains(PanelFinish.FINISH))
                .toList();
    }

    private static int argb(int rgb) {
        return rgb < 0 ? -1 : 0xFF000000 | rgb;
    }

    @SubscribeEvent
    public static void blocks(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> argb(PanelFinish.of(state).tint(tint)),
                finishBlocks().toArray(new Block[0]));
    }

    @SubscribeEvent
    public static void items(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> argb(PanelFinish.of(stack).tint(tint)),
                finishBlocks().stream().map(Block::asItem).toArray(Item[]::new));
    }
}
