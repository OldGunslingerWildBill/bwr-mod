package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.piping.PaintedPipeBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Only the material marked tint-index 0 takes the colour: the water pipe's paint and the steam pipe's bands (PIPES.md);
 * welds, flanges and cladding keep their metal. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PipeColors {
    /** Plant light blue: the water pipe's paint (PIPES.md); a little greyer than the old bright band blue. */
    private static final int WATER = 0x5A9FCC;
    private static final int STEAM = 0xE8A43B;
    /** Lube oil brown: the Lube Oil Pipe's paint (the oil pipe preview colour of PIPES.md). */
    private static final int OIL = 0x6F4A2E;
    private PipeColors() {}

    @SubscribeEvent public static void blocks(RegisterColorHandlersEvent.Block event) {
        event.register((state, world, pos, tint) -> tint == 0
                        ? 0xFF000000 | state.getValue(PaintedPipeBlock.PAINT).color(state.is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()) ? WATER
                                : state.is(BwrBlocks.LUBE_OIL_PIPE.get()) ? OIL : STEAM)
                        : -1,
                BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(), BwrBlocks.PRESSURISED_TUBE.get(), BwrBlocks.LUBE_OIL_PIPE.get());
    }

    @SubscribeEvent public static void items(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> tint == 0 ? 0xFF000000 | WATER : -1, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get());
        event.register((stack, tint) -> tint == 0 ? 0xFF000000 | STEAM : -1, BwrBlocks.PRESSURISED_TUBE.get());
        event.register((stack, tint) -> tint == 0 ? 0xFF000000 | OIL : -1, BwrBlocks.LUBE_OIL_PIPE.get());
    }
}
