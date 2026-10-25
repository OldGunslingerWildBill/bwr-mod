package dev.bwr.mod.worldgen;

import com.mojang.serialization.MapCodec;
import dev.bwr.mod.BwrMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Registry entries of the uranium and thorium world generation. The data (what, where, how much) is in {@code data/bwr/}. */
public final class BwrWorldgen {

    private BwrWorldgen() {
    }

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, BwrMod.MOD_ID);
    public static final DeferredHolder<Feature<?>, UraniumVeinFeature> URANIUM_VEIN = FEATURES.register("uranium_vein", UraniumVeinFeature::new);

    public static final DeferredHolder<Feature<?>, UraniumOreFeature> URANIUM_ORE = FEATURES.register("uranium_ore", UraniumOreFeature::new);
    public static final DeferredHolder<Feature<?>, ThoriumVeinFeature> THORIUM_VEIN = FEATURES.register("thorium_vein", ThoriumVeinFeature::new);
    public static final DeferredHolder<Feature<?>, ThoriumOreFeature> THORIUM_ORE = FEATURES.register("thorium_ore", ThoriumOreFeature::new);

    public static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, BwrMod.MOD_ID);
    public static final DeferredHolder<MapCodec<? extends BiomeModifier>, MapCodec<UraniumBiomeModifier>> ADD_URANIUM_FEATURES =
            BIOME_MODIFIERS.register("add_uranium_features", () -> UraniumBiomeModifier.CODEC);
    public static final DeferredHolder<MapCodec<? extends BiomeModifier>, MapCodec<ThoriumBiomeModifier>> ADD_THORIUM_FEATURES =
            BIOME_MODIFIERS.register("add_thorium_features", () -> ThoriumBiomeModifier.CODEC);

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
        BIOME_MODIFIERS.register(modBus);
    }
}
