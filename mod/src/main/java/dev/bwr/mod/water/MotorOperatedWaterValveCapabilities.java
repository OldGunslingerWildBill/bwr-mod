package dev.bwr.mod.water;

import dev.bwr.mod.registry.BwrBlocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The valve's motor takes FE on every face but its two flanges. */
@EventBusSubscriber(modid = "bwr")
public final class MotorOperatedWaterValveCapabilities {
    private MotorOperatedWaterValveCapabilities() {
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, dev.bwr.mod.registry.BwrBlockEntities.MOTOR_OPERATED_WATER_VALVE.get(),
                (valve, side) -> side != null && MotorOperatedWaterValveBlock.energyFace(valve.getBlockState(), side) ? valve.energy() : null);
    }
}
