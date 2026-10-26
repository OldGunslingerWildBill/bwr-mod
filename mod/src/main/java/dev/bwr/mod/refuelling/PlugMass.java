package dev.bwr.mod.refuelling;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.mod.accident.ShieldPlugConcreteBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * What a block weighs for the reactor building crane (BUILDING-CRANE.md, "Capacity"): the block data map {@code bwr:plug_mass}
 * ({@code data/<ns>/data_maps/block/plug_mass.json}), the mass in tonnes of one block (one cubic metre), so a datapack can tune a tier or weigh
 * another block. The mod's own file gives the three shield plug concrete tiers 2.4, 3.2 and 4.0 t and the crane anchor 0.05 t. A shield plug
 * block with no entry weighs its tier's default from {@link PlugLift}; any other block weighs nothing (it is never part of a section).
 */
public final class PlugMass {
    private PlugMass() {
    }

    /** One entry of the data map: tonnes for one block. */
    public record Mass(double tonnes) {
        public static final Codec<Mass> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.doubleRange(0.0, 1000.0).fieldOf("tonnes").forGetter(Mass::tonnes)).apply(i, Mass::new));
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("bwr", "plug_mass");

    /** Server side only (the crane's survey runs on the server; the pendant is sent the sum), so it is not synced. */
    public static final DataMapType<Block, Mass> MASS = DataMapType.builder(ID, Registries.BLOCK, Mass.CODEC).build();

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(MASS);
    }

    /** The mass of one block in tonnes: the data map's, or the tier's default for a plug block with no entry, or 0 for anything else. */
    public static double tonnes(BlockState state) {
        Mass m = state.getBlockHolder().getData(MASS);
        if (m != null) {
            return m.tonnes();
        }
        int tier = PlugSections.tierOf(state);
        return tier == 0 ? 0.0 : PlugLift.defaultTonnes(tier);
    }

    /** The mass of one block of a tier (1..3) as the data says it, for messages. */
    public static double tierTonnes(int tier) {
        Block block = switch (tier) {
            case 3 -> dev.bwr.mod.registry.BwrBlocks.ULTRA_REINFORCED_SHIELD_PLUG_CONCRETE.get();
            case 2 -> dev.bwr.mod.registry.BwrBlocks.HEAVILY_REINFORCED_SHIELD_PLUG_CONCRETE.get();
            default -> dev.bwr.mod.registry.BwrBlocks.SHIELD_PLUG_CONCRETE.get();
        };
        return tonnes(block.defaultBlockState());
    }

    /** True for a block that carries a mass entry or is a plug block (used by tests). */
    public static boolean weighs(BlockState state) {
        return state.getBlock() instanceof ShieldPlugConcreteBlock || state.getBlockHolder().getData(MASS) != null;
    }
}
