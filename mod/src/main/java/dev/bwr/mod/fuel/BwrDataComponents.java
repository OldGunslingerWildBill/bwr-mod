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

    /** How full an Oil Filter Cartridge is, 0 (clean, and not stored) to 1 (spent): what a cartridge taken out of a filter housing carries. */
    public static final DeferredHolder<DataComponentType<?>,DataComponentType<Double>> OIL_FILTER_LOAD=
            DATA_COMPONENTS.register("oil_filter_load",()->DataComponentType.<Double>builder()
                    .persistent(com.mojang.serialization.Codec.doubleRange(0,1))
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.DOUBLE).build());

    /** Which of a control panel component type's footprints an item stack places (index into the type's list). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> COMPONENT_VARIANT =
            DATA_COMPONENTS.register("component_variant", () -> DataComponentType.<Integer>builder()
                    .persistent(com.mojang.serialization.Codec.intRange(0, 63))
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());

    /** The mode of a mimic tape: 0 draw (the default, not stored), 1 erase, 2 edit. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MIMIC_MODE =
            DATA_COMPONENTS.register("mimic_mode", () -> DataComponentType.<Integer>builder()
                    .persistent(com.mojang.serialization.Codec.intRange(0, 2))
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());

    /** The path a mimic tape is drawing: the panel block of its first point and the points so far. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.bwr.mod.panel.mimic.MimicDraft>> MIMIC_DRAFT =
            DATA_COMPONENTS.register("mimic_draft", () -> DataComponentType.<dev.bwr.mod.panel.mimic.MimicDraft>builder()
                    .persistent(dev.bwr.mod.panel.mimic.MimicDraft.CODEC)
                    .networkSynchronized(dev.bwr.mod.panel.mimic.MimicDraft.STREAM_CODEC).build());

    /** The plant device a panel linker has stored (dimension and absolute position). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.minecraft.core.GlobalPos>> LINKED_DEVICE =
            DATA_COMPONENTS.register("linked_device", () -> DataComponentType.<net.minecraft.core.GlobalPos>builder()
                    .persistent(net.minecraft.core.GlobalPos.CODEC)
                    .networkSynchronized(net.minecraft.core.GlobalPos.STREAM_CODEC).build());

    /** Burnup, enrichment, fuel type and remaining gadolinia, travelling with the itemstack. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<FuelAssemblyData>>
            FUEL_ASSEMBLY = DATA_COMPONENTS.register("fuel_assembly",
            () -> DataComponentType.<FuelAssemblyData>builder()
                    .persistent(FuelAssemblyData.CODEC)
                    .networkSynchronized(FuelAssemblyData.STREAM_CODEC)
                    .build());

    /**
     * The game tick (the level's game time) at which a fuel assembly was taken out of a core. It is a separate
     * component from {@link FuelAssemblyData} on purpose: the bundle's exposure record is compared whole by the
     * refuelling code and its tests, and a discharge stamp is not part of what a bundle <i>is</i>. Absent on a
     * bundle that never left a core under this version, which every reader treats as long-cooled
     * ({@link SpentFuelHeat}).
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> DISCHARGE_TIME =
            DATA_COMPONENTS.register("discharge_time", () -> DataComponentType.<Long>builder()
                    .persistent(com.mojang.serialization.Codec.LONG.validate(
                            t -> t >= 0L ? com.mojang.serialization.DataResult.success(t)
                                    : com.mojang.serialization.DataResult.error(() -> "negative discharge time")))
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG).build());

    /** The premade board a board blueprint places (a {@code panel_board} template id); none stored means the first board. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<net.minecraft.resources.ResourceLocation>> BOARD_TEMPLATE =
            DATA_COMPONENTS.register("board_template", () -> DataComponentType.<net.minecraft.resources.ResourceLocation>builder()
                    .persistent(net.minecraft.resources.ResourceLocation.CODEC)
                    .networkSynchronized(net.minecraft.resources.ResourceLocation.STREAM_CODEC).build());
}
