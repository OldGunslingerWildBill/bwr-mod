package dev.bwr.mod.panel;

import dev.bwr.mod.registry.BwrAttachments;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The CC port of a machine (part 7): a number from 0000 to 9999 that a player sets by hand (on the machine's
 * Networking screen or with {@code setPort(n)} from Lua) so a panel can pick the machine on a CC wired network by port.
 * It is saved as one int only when set, on the block entity that {@code DeviceResolver} returns, so an old save has no
 * port. A machine with no port cannot be reached through CC. Nothing assigns a port automatically.
 */
public final class MachinePorts {
    private MachinePorts() {
    }

    public static final int MAX_PORT = 9999;
    /** What {@link #get} returns for a machine with no port. */
    public static final int NONE = -1;

    private static volatile int generation;

    /** Changes whenever any machine's port changes, so a cached port map knows to rebuild. */
    public static int generation() {
        return generation;
    }

    public static boolean valid(int port) {
        return port >= 0 && port <= MAX_PORT;
    }

    /** The machine's port, or {@link #NONE}. */
    public static int get(BlockEntity owner) {
        return owner != null && owner.hasData(BwrAttachments.MACHINE_PORT.get())
                ? owner.getData(BwrAttachments.MACHINE_PORT.get()) : NONE;
    }

    /** Sets the port; returns false (and changes nothing) for a number outside 0 to 9999. */
    public static boolean set(BlockEntity owner, int port) {
        if (!valid(port)) {
            return false;
        }
        if (get(owner) != port) {
            owner.setData(BwrAttachments.MACHINE_PORT.get(), port);
            owner.setChanged();
            generation++;
        }
        return true;
    }

    /** Gives the machine no port again. */
    public static void clear(BlockEntity owner) {
        if (get(owner) != NONE) {
            owner.setData(BwrAttachments.MACHINE_PORT.get(), NONE);
            owner.setChanged();
            generation++;
        }
    }

    /** The port as four digits ("0042"). */
    public static String format(int port) {
        return String.format(java.util.Locale.ROOT, "%04d", port);
    }
}
