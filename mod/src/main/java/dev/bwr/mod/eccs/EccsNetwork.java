package dev.bwr.mod.eccs;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Finds the {@link ReactorEccsBus} for a reactor, and drives every bus once per
 * server tick.
 *
 * <h2>Why the totals are applied from here and not from the machines</h2>
 * If each pump wrote its own total into the core, then a pump that stopped
 * ticking — broken, or in a chunk that unloaded while the reactor stayed
 * loaded — would leave its last contribution latched into the vessel forever,
 * quietly injecting water that no machine is supplying. Applying from a server
 * tick handler means the bus is evaluated even when nothing is reporting, so a
 * contribution that has gone stale actually goes to zero.
 *
 * <p>This is bookkeeping, not control. Nothing here starts, stops or throttles
 * anything, and nothing here looks at a reactor parameter.
 */
public final class EccsNetwork {

    private EccsNetwork() {
    }

    private static final Map<GlobalPos, ReactorEccsBus> BUSES = new HashMap<>();

    private static boolean listenerRegistered;

    /**
     * Attach the server-tick driver, once per JVM.
     *
     * <p>Called from the constructor of every ECCS block entity rather than
     * from mod setup, so that an install where nobody has built an emergency
     * system never registers a handler at all. Idempotent, and only ever
     * reached from the server thread.
     */
    public static void ensureListenerRegistered() {
        if (listenerRegistered) {
            return;
        }
        listenerRegistered = true;
        NeoForge.EVENT_BUS.addListener(EccsNetwork::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(EccsNetwork::onServerStopped);
    }

    /** The bus for one reactor, created on demand. */
    public static ReactorEccsBus busFor(Level level, BlockPos reactorPos) {
        ensureListenerRegistered();
        return BUSES.computeIfAbsent(GlobalPos.of(level.dimension(), reactorPos.immutable()),
                k -> new ReactorEccsBus());
    }

    /** The bus for one reactor if it already exists, else null. Creates nothing. */
    public static ReactorEccsBus existingBusFor(Level level, BlockPos reactorPos) {
        return BUSES.get(GlobalPos.of(level.dimension(), reactorPos.immutable()));
    }

    /** Drop one machine's contribution from its reactor's bus. */
    public static void withdraw(Level level, BlockPos reactorPos, BlockPos machinePos) {
        ReactorEccsBus bus = existingBusFor(level, reactorPos);
        if (bus != null) {
            bus.withdraw(machinePos);
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long gameTime = server.overworld().getGameTime();

        Iterator<Map.Entry<GlobalPos, ReactorEccsBus>> it = BUSES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GlobalPos, ReactorEccsBus> entry = it.next();
            GlobalPos key = entry.getKey();
            ReactorEccsBus bus = entry.getValue();

            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                it.remove();
                continue;
            }
            // Never force a chunk to load in order to inject into it. A reactor
            // in an unloaded chunk is frozen anyway (SPEC section 11), so there
            // is nothing to apply the totals to.
            if (!level.isLoaded(key.pos())) {
                continue;
            }
            if (level.getBlockEntity(key.pos()) instanceof ReactorControllerBlockEntity reactor) {
                bus.applyTo(reactor.core(), level.getGameTime());
            } else {
                it.remove();
                continue;
            }
            if (bus.isEmpty()) {
                it.remove();
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        BUSES.clear();
    }
}
