package dev.bwr.mod.fuel;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.mod.BwrMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Ships the server's fuel registry to every client, on login and on
 * {@code /reload}. SPEC 2.1.
 *
 * <h2>Why</h2>
 * Datapacks are server state, so {@link FuelTypeLoader} only ever fills
 * {@link FuelTypes} on the server. Everything the physics does with a fuel type
 * happens there and is unaffected — but the fuel bundle tooltip does not: it
 * runs client-side and computes k-infinity, beta, gadolinia worth and prompt
 * lifetime from whatever table the client happens to hold. Without this class a
 * dedicated-server client holds {@link FuelType#presets()} forever and reads a
 * retuned fuel's numbers as the shipped defaults, silently, because the fuel
 * <i>name</i> still resolves. See {@link FuelTypeSyncPayload} for the full
 * argument.
 *
 * <p>The rule this obeys is the same one the rest of the mod does: the client
 * is shown measurements, and they have to be measurements of the reactor the
 * player is actually operating.
 *
 * <h2>Wiring</h2>
 * Registration and dispatch sit in one class on purpose: the packet type and
 * the only thing that ever sends it are then registered together or not at all.
 * They belong to different event buses, and no {@code bus()} is named on the
 * annotation because NeoForge routes by event type —
 * {@code RegisterPayloadHandlersEvent} is an {@code IModBusEvent} and
 * {@code OnDatapackSyncEvent} is not, so they go to the mod bus and the game
 * bus respectively. Naming a bus is deprecated for removal.
 *
 * <p>{@link OnDatapackSyncEvent} is the right trigger because it is fired both
 * when a player joins and after every {@code /reload}, which is exactly the set
 * of moments {@link FuelTypes} can have changed under a client. In single player
 * the integrated server shares these statics with the client, so the packet
 * re-installs an identical table and costs nothing.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class FuelTypeSync {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bumped when the wire format of {@link FuelTypeSyncPayload} changes incompatibly. */
    private static final String VERSION = "1";

    private FuelTypeSync() {
    }

    /** Mod bus. */
    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(
                FuelTypeSyncPayload.TYPE, FuelTypeSyncPayload.STREAM_CODEC, FuelTypeSync::install);
    }

    /**
     * Game bus, server side. {@code getRelevantPlayers()} is the joining player
     * on login and every player on {@code /reload}, which is precisely who needs
     * it.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        FuelTypeSyncPayload payload = FuelTypeSyncPayload.of(FuelTypes.all());
        event.getRelevantPlayers()
                .forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    /**
     * Client side. Decodes entry by entry and installs whatever survived.
     *
     * <p>Deliberately mirrors {@link FuelTypeLoader#apply}: one bad entry costs
     * that fuel and not the rest, and an empty result puts the compiled presets
     * back rather than leaving the client with no fuel types at all, which would
     * make every bundle in every inventory unresolvable. Doing the decode here
     * rather than inside the stream codec is what makes that possible — a throw
     * in the netty pipeline would drop the connection instead.
     *
     * <p>Nothing in here touches {@code context.player()}, so it is safe at the
     * point in the login sequence where the first of these arrives, and it needs
     * no client-only class.
     */
    private static void install(FuelTypeSyncPayload payload, IPayloadContext context) {
        List<FuelType> types = new ArrayList<>(payload.entries().size());
        int rejected = 0;
        for (FuelTypeSyncPayload.Entry entry : payload.entries()) {
            DataResult<FuelType> result = FuelTypeCodec.decode(entry.name(), entry.fields());
            if (result.error().isPresent()) {
                rejected++;
                LOGGER.error("Ignoring fuel type {} from the server: {}",
                        entry.name(), result.error().get().message());
                continue;
            }
            types.add(result.result().orElseThrow());
        }

        if (types.isEmpty()) {
            FuelTypes.resetToDefaults();
            LOGGER.warn("Server sent no usable fuel types ({} rejected); "
                    + "tooltips will show the {} built-in ones", rejected, FuelTypes.all().size());
            return;
        }

        FuelTypes.replaceAll(types);
    }
}
