package dev.bwr.mod.radiation;

import dev.bwr.mod.BwrMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Registers the survey meter's readout packet on its own registrar (the same pattern as the fuel type sync), so it adds
 * nothing to the menu or panel protocols. The handler only stores two numbers in {@link RadiationReadout}, which holds
 * no client-only class, so it is safe on a dedicated server where the packet is never received.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class RadiationNetwork {
    /** Bumped when the wire format of {@link ReadoutPayload} changes. */
    private static final String VERSION = "1";

    private RadiationNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(ReadoutPayload.TYPE, ReadoutPayload.STREAM_CODEC,
                RadiationNetwork::receive);
    }

    private static void receive(ReadoutPayload payload, IPayloadContext context) {
        RadiationReadout.accept(payload.doseRateMsvPerHour(), payload.personalDoseMsv());
    }
}
