package dev.bwr.mod.eccs;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Runs an actuator command on the server thread, wherever it was called from.
 *
 * <h2>Why this exists</h2>
 * Every {@code @LuaFunction} in {@code dev.bwr.mod.peripheral} is declared
 * without {@code mainThread = true}, so CC:Tweaked invokes it on a computer
 * thread while the server thread is somewhere inside its own tick. A command
 * such as {@code pump.start()} therefore lands on a block entity concurrently
 * with the tick that is reading it, and two separate things go wrong:
 *
 * <ul>
 *   <li>The plain field writes have no happens-before edge with the tick
 *       thread's reads, so a command can be observed torn or not at all — an
 *       ADS {@code setOpen(true)} that the tick thread never sees is an ADS
 *       that does not fire.</li>
 *   <li>Far worse, every actuator ends in {@link BlockEntity#setChanged()},
 *       which calls {@code Level.blockEntityChanged} and
 *       {@code Level.updateNeighbourForOutputSignal}. The second of those
 *       dispatches neighbour updates into surrounding blocks. Running that off
 *       the tick thread mutates chunk and block-state structures the server is
 *       using at the same moment.</li>
 * </ul>
 *
 * <p>Marshalling here rather than annotating the peripheral methods keeps the
 * guarantee with the hardware instead of with one particular caller: redstone,
 * the GUI menus and Lua all reach the same setters, and only one of those three
 * arrives on the wrong thread. Server-thread callers run inline and are
 * unaffected — there is no extra tick of latency for a lever.
 *
 * <p>It lives in this package because the rest of the cross-machine plumbing
 * ({@link EccsNetwork}, {@link ReactorEccsBus}) does, not because it has
 * anything to do with emergency cooling.
 *
 * <p>This is not control logic. It decides nothing and looks at no plant
 * parameter; it only decides <i>which thread</i> a command the player already
 * gave is carried out on.
 */
public final class PlantActuators {

    private PlantActuators() {
    }

    /**
     * Carry out an actuator command on the server thread.
     *
     * <p>Inline when already there, queued onto the server's task queue when
     * not. A command queued from a computer thread takes effect at the top of
     * the next server tick, which is the same latency a real solenoid has and
     * is indistinguishable from the redstone path.
     */
    public static void run(BlockEntity blockEntity, Runnable command) {
        Level level = blockEntity.getLevel();
        MinecraftServer server = level != null ? level.getServer() : null;
        if (server != null && !server.isSameThread()) {
            server.execute(command);
            return;
        }
        command.run();
    }
}
