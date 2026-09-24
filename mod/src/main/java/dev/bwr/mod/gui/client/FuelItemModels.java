package dev.bwr.mod.gui.client;

import dev.bwr.core.fuel.FuelType;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.*;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Tiny pre-rendered PNGs, including in the creative menu; no live mesh rendering. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class FuelItemModels {
    private static final java.util.List<String> NAMES = FuelType.presets().stream().map(FuelType::name).toList();
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(BwrItems.FUEL_ASSEMBLY.get(), BwrMod.id("fuel_variant"),
                    (stack, level, entity, seed) -> Math.max(0, NAMES.indexOf(FuelAssemblies.dataOf(stack).fuelTypeName())));
            ItemProperties.register(BwrItems.SPECIALTY_ROD.get(), BwrMod.id("rod_variant"),
                    (stack, level, entity, seed) -> SpecialtyRodItem.data(stack).kind().ordinal());
        });
    }
}
