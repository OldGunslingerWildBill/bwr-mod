package dev.bwr.mod.isotope;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The RTG fuel list as data. {@link #RTG_FUEL} is an item data map, synced to clients (screens and tooltips read it):
 * {@code data/bwr/data_maps/item/rtg_fuel.json}. See RTG.md for the line that adds a fuel.
 */
public final class RtgFuels {

    private RtgFuels() {
    }

    public static final DataMapType<Item, RtgFuel> RTG_FUEL = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath("bwr", "rtg_fuel"), Registries.ITEM, RtgFuel.CODEC)
            .synced(RtgFuel.CODEC, false)
            .build();

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(RTG_FUEL);
    }

    /** The fuel entry of an item, or null if it is not RTG fuel. */
    public static RtgFuel of(ItemStack stack) {
        return stack.isEmpty() ? null : stack.getItemHolder().getData(RTG_FUEL);
    }

    /** True for an item with an entry in the list. */
    public static boolean isFuel(ItemStack stack) {
        return of(stack) != null;
    }
}
