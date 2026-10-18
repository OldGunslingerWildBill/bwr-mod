package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * The CC-independent seam of the CC port bindings (part 7). {@link CcPortSource} and {@code WiringOps} ask it which
 * machines of the CC wired network of a panel answer on a port; the answer comes from the CC-only helper
 * ({@code dev.bwr.mod.peripheral.CcPortNetwork}), which installs itself here when CC:Tweaked is present. Without it
 * every question has the empty answer, so nothing in the CC-less build ever names a CC type.
 */
public final class CcPortBridge {
    private CcPortBridge() {
    }

    /** What the CC side can answer. */
    public interface Network {
        /** The distinct machines on the CC wired network of {@code panel} whose port is {@code port}; never loads a chunk. */
        List<BlockEntity> machinesOnPort(PanelBlockEntity panel, int port);

        /**
         * The most machines, on any CC wired network that contains {@code machine}, that share the port of
         * {@code machine} (1 when it is alone on its port, 0 when it has no port or is on no network).
         */
        int machinesSharingPort(BlockEntity machine);

        /** A panel block entity is gone (removed or its chunk unloaded): forget it and leave its network. */
        void panelRemoved(PanelBlockEntity panel);
    }

    /** The machine on a port, or none with the number of machines that claim it (0 nothing, 2 or more a conflict). */
    public record Lookup(BlockEntity machine, int claimants) {
    }

    private static volatile Network network;

    /** Called by the CC support when CC:Tweaked is present. */
    public static void install(Network installed) {
        network = installed;
    }

    /** Whether CC port bindings can work at all (CC:Tweaked is installed and its support is set up). */
    public static boolean available() {
        return network != null;
    }

    public static List<BlockEntity> machinesOnPort(PanelBlockEntity panel, int port) {
        Network current = network;
        return current == null ? List.of() : current.machinesOnPort(panel, port);
    }

    /** The one machine on {@code port} of {@code panel}'s CC network; two machines on one port make it read nothing. */
    public static Lookup lookup(PanelBlockEntity panel, int port) {
        List<BlockEntity> machines = machinesOnPort(panel, port);
        return new Lookup(machines.size() == 1 ? machines.get(0) : null, machines.size());
    }

    /** Tells the CC side a panel block entity is gone. */
    public static void panelRemoved(PanelBlockEntity panel) {
        Network current = network;
        if (current != null) {
            current.panelRemoved(panel);
        }
    }

    /** How many machines share the port of {@code machine} on a CC network (see {@link Network#machinesSharingPort}). */
    public static int machinesSharingPort(BlockEntity machine) {
        Network current = network;
        return current == null ? 0 : current.machinesSharingPort(machine);
    }
}
