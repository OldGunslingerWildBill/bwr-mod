package dev.bwr.mod.compat.cee;

import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import dev.bwr.mod.grid.GridTieBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Create: Electro Energetics (CEE) integration (optional). This package is the only code that names a CEE or Create type, and it is
 * reached only behind a {@code ModList.isLoaded("electroenergetics")} check (BwrMod, BwrBlocks; the check must not be in this class, whose
 * static initialiser names CEE), so a JVM without CEE never loads any of it. The Grid Tie is then a
 * {@link CeeGridTieBlock} with six isolated-phase-bus nodes, run by a {@link GridTieDevice} that bridges each pole while the breaker is closed.
 * {@code InWorldNodeMixin} (bwr.cee.mixins.json, applied only with CEE) lets a click on any cell of the breaker find those nodes.
 */
public final class CeeCompat {
    private static final DeferredRegister<SimulatedDeviceType<?>> DEVICES = DeferredRegister.create(CEERegistries.SIMULATED_DEVICE_TYPE, "bwr");
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<GridTieDevice>> GRID_TIE_DEVICE = DEVICES.register("grid_tie",
            () -> new SimulatedDeviceType<>(ResourceLocation.fromNamespaceAndPath("bwr", "grid_tie"), (type, level, pos, sd) -> new GridTieDevice(level, pos, sd, type)));

    private CeeCompat() {
    }

    /** Registers the device type; called from BwrMod only when CEE is loaded. */
    public static void register(IEventBus modBus) {
        DEVICES.register(modBus);
    }

    /** The Grid Tie block when CEE is present. Called only when CEE is loaded, from the block's registration supplier. */
    public static GridTieBlock newGridTie(BlockBehaviour.Properties properties) {
        return new CeeGridTieBlock(properties);
    }
}
