package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.piping.PaintedPipeBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Only the Blender material marked tint-index 0 receives dye; steel stays metallic. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PipeColors {
    private static final int WATER = 0x269FDE;
    private static final int STEAM = 0xE8A43B;
    private PipeColors() {}

    @SubscribeEvent public static void blocks(RegisterColorHandlersEvent.Block event) {
        event.register((state, world, pos, tint) -> tint == 0
                        ? 0xFF000000 | state.getValue(PaintedPipeBlock.PAINT).color(state.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()) ? WATER : STEAM)
                        : -1,
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(), BwrBlocks.PRESSURISED_TUBE.get());
    }

    @SubscribeEvent public static void items(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? 0xFF000000 | WATER : -1, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get());
        event.register((stack, tint) -> tint == 0 ? 0xFF000000 | STEAM : -1, BwrBlocks.PRESSURISED_TUBE.get());
    }
}
