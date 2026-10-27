package dev.bwr.mod.power;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The skids' FE faces (AC bus, DC bus) and their two cooling-water flanges. */
@EventBusSubscriber(modid = "bwr")
public final class TurbineAuxCapabilities {
    private TurbineAuxCapabilities() {}

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        // The LP Blade Storage Stand stocks blade sections from hoppers and pipes on any face (TURBINE-MAINTENANCE.md); the casing cradle does not.
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BwrBlockEntities.TURBINE_STAND.get(),
                (be, side) -> be.kind() == TurbineStandBlock.Kind.BLADES ? be.items() : null);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BwrBlockEntities.TURBINE_AUX.get(), (be, side) -> {
            if (side == null) return null;
            var state = be.getBlockState();
            var block = (TurbineAuxBlock) state.getBlock();
            return block.dcFace(state, side) ? be.dcEnergy : block.acFace(state, side) ? be.acEnergy : null;
        });
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, BwrBlockEntities.TURBINE_AUX.get(), (be, side) -> {
            if (side == null) return null;
            var state = be.getBlockState();
            var block = (TurbineAuxBlock) state.getBlock();
            if (!block.hasWater()) return null;
            return side == block.coldInFace(state) ? be.coldInlet() : side == block.hotOutFace(state) ? be.hotOutlet() : null;
        });
    }
}
