package dev.bwr.mod.fuel;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Data component types. Right now there is exactly one, and it is the reason
 * fuel management works at all.
 *
 * <p>{@link #FUEL_ASSEMBLY} is both {@code persistent} (it survives being
 * written to disk in a chest) and {@code networkSynchronized} (the client sees
 * it, so the tooltip can show burnup without a round trip). Both are required:
 * a bundle that loses its exposure when the chunk unloads, or that reads as
 * fresh on the client, would be worse than not tracking exposure at all.
 */
public final class BwrDataComponents {

    private BwrDataComponents() {
    }

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, BwrMod.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CoreInsertData>> CORE_INSERT =
            DATA_COMPONENTS.register("core_insert", () -> DataComponentType.<CoreInsertData>builder()
                    .persistent(CoreInsertData.CODEC).networkSynchronized(CoreInsertData.STREAM_CODEC).build());

    /** Specific enthalpy on ordinary water, kJ/kg; retained by BWR pipes and saved tanks. */
    public static final DeferredHolder<DataComponentType<?>,DataComponentType<Double>> WATER_ENTHALPY=
            DATA_COMPONENTS.register("water_enthalpy",()->DataComponentType.<Double>builder()
                    .persistent(com.mojang.serialization.Codec.doubleRange(0,5000))
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.DOUBLE).build());

    /** Burnup, enrichment, fuel type and remaining gadolinia, travelling with the itemstack. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FuelAssemblyData>>
            FUEL_ASSEMBLY = DATA_COMPONENTS.register("fuel_assembly",
            () -> DataComponentType.<FuelAssemblyData>builder()
                    .persistent(FuelAssemblyData.CODEC)
                    .networkSynchronized(FuelAssemblyData.STREAM_CODEC)
                    .build());
}
