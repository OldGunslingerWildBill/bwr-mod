package dev.bwr.mod.compat.cee;

import com.george_vi.electroenergetics.CEERegistries;
import com.george_vi.electroenergetics.devices.device.SimulatedDeviceType;
import dev.bwr.mod.grid.GridTieBlock;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Create: Electro Energetics (CEE) integration (optional). This package is the only code that names a CEE or Create type, and it is
 * reached only through {@link #present()} checks (BwrMod, BwrBlocks), so a JVM without CEE never loads any of it. The Grid Tie is then a
 * {@link CeeGridTieBlock} with six isolated-phase-bus nodes, run by a {@link GridTieDevice} that bridges each pole while the breaker is closed.
 * {@code InWorldNodeMixin} (bwr.cee.mixins.json, applied only with CEE) lets a click on any cell of the breaker find those nodes.
 */
public final class CeeCompat {
    public static final String MOD_ID = "electroenergetics";

    private static final DeferredRegister<SimulatedDeviceType<?>> DEVICES = DeferredRegister.create(CEERegistries.SIMULATED_DEVICE_TYPE, "bwr");
    public static final DeferredHolder<SimulatedDeviceType<?>, SimulatedDeviceType<GridTieDevice>> GRID_TIE_DEVICE = DEVICES.register("grid_tie",
            () -> new SimulatedDeviceType<>(ResourceLocation.fromNamespaceAndPath("bwr", "grid_tie"), (type, level, pos, sd) -> new GridTieDevice(level, pos, sd, type)));

    private CeeCompat() {
    }

    /** Whether CEE is installed. Safe without CEE: it names no CEE type. */
    public static boolean present() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /** Registers the device type; called from BwrMod only when {@link #present()}. */
    public static void register(IEventBus modBus) {
        DEVICES.register(modBus);
    }

    /** The Grid Tie block when CEE is present. Called only when {@link #present()}, from the block's registration supplier. */
    public static GridTieBlock newGridTie(BlockBehaviour.Properties properties) {
        return new CeeGridTieBlock(properties);
    }
}
