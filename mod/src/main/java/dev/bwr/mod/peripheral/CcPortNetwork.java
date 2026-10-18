package dev.bwr.mod.peripheral;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.network.wired.WiredElement;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import dan200.computercraft.api.network.wired.WiredNetworkChange;
import dan200.computercraft.api.network.wired.WiredNode;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.panel.MachinePorts;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.wiring.CcPortBridge;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.signal.DeviceResolver;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * CC port bindings (part 7), CC side. A panel block exposes a {@link WiredElement} through CC's own
 * {@link WiredElementCapability}, so a wired modem or network cable next to the panel joins the panel to its network
 * with CC's public API ({@link ComputerCraftAPI#createWiredNodeForElement}); nothing of CC is reached into. CC tells the
 * element which peripherals appear and disappear on that network ({@link WiredElement#networkChanged}), and from those
 * this class keeps the machines of the network and, per port, the machines that answer on it. That map is rebuilt only
 * after CC reports a change or a machine's port changes ({@link MachinePorts#generation}), never per sample.
 *
 * <p>Two machines on one port are a conflict: the panel reads nothing from that port. Nothing is guessed, nothing is
 * assigned, and nothing here ever loads a chunk.
 */
public final class CcPortNetwork implements CcPortBridge.Network {
    private static final CcPortNetwork INSTANCE = new CcPortNetwork();
    private static final Map<PanelBlockEntity, PanelElement> ELEMENTS = Collections.synchronizedMap(new WeakHashMap<>());

    private CcPortNetwork() {
    }

    /** Sets the CC side up: the panel's wired element capability and the bridge the CC-less code asks through. */
    public static void install(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(WiredElementCapability.get(), BwrBlockEntities.PANEL.get(),
                (panel, side) -> elementFor(panel));
        CcPortBridge.install(INSTANCE);
    }

    private static PanelElement elementFor(PanelBlockEntity panel) {
        synchronized (ELEMENTS) {
            return ELEMENTS.computeIfAbsent(panel, PanelElement::new);
        }
    }

    // ---- the panel's element

    /** One panel as a node of a CC wired network: it listens to which peripherals the network has. */
    private static final class PanelElement implements WiredElement {
        private final PanelBlockEntity panel;
        private WiredNode node;
        private final Map<String, IPeripheral> remote = new LinkedHashMap<>();
        private int version;
        private int cachedVersion = -1;
        private int cachedPorts = -1;
        private Map<Integer, List<BlockEntity>> byPort = Map.of();
        private List<BlockEntity> machines = List.of();

        PanelElement(PanelBlockEntity panel) {
            this.panel = panel;
        }

        @Override
        public synchronized WiredNode getNode() {
            if (node == null) {
                node = ComputerCraftAPI.createWiredNodeForElement(this);
            }
            return node;
        }

        @Override
        public Level getLevel() {
            return panel.getLevel();
        }

        @Override
        public Vec3 getPosition() {
            BlockPos pos = panel.getBlockPos();
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }

        @Override
        public String getSenderID() {
            return "bwr_panel_" + panel.getBlockPos().asLong();
        }

        @Override
        public synchronized void networkChanged(WiredNetworkChange change) {
            remote.keySet().removeAll(change.peripheralsRemoved().keySet());
            remote.putAll(change.peripheralsAdded());
            version++;
        }

        /** The panel is gone (removed or its chunk unloaded): leave the network. */
        synchronized void detach() {
            if (node != null) {
                node.remove();
                node = null;
            }
            remote.clear();
            version++;
        }

        /** The machines of this network, each once, however many faces or modems expose it. */
        synchronized List<BlockEntity> machines() {
            refresh();
            return machines;
        }

        synchronized List<BlockEntity> onPort(int port) {
            refresh();
            return byPort.getOrDefault(port, List.of());
        }

        private void refresh() {
            int ports = MachinePorts.generation();
            if (cachedVersion == version && cachedPorts == ports) {
                return;
            }
            cachedVersion = version;
            cachedPorts = ports;
            Map<BlockEntity, Boolean> seen = new IdentityHashMap<>();
            List<BlockEntity> all = new ArrayList<>();
            Map<Integer, List<BlockEntity>> grouped = new HashMap<>();
            for (IPeripheral peripheral : remote.values()) {
                if (!(peripheral instanceof LivePeripheral live) || !live.isMachine()) {
                    continue;
                }
                Level level = live.level();
                if (level == null || !level.isLoaded(live.anchor())) {
                    continue;
                }
                BlockEntity owner = DeviceResolver.resolve(level, live.anchor());
                if (owner == null || SignalProviders.of(owner) == null || seen.put(owner, Boolean.TRUE) != null) {
                    continue;
                }
                all.add(owner);
                int port = MachinePorts.get(owner);
                if (port != MachinePorts.NONE) {
                    grouped.computeIfAbsent(port, p -> new ArrayList<>()).add(owner);
                }
            }
            machines = List.copyOf(all);
            byPort = grouped;
        }
    }

    // ---- CcPortBridge.Network

    @Override
    public List<BlockEntity> machinesOnPort(PanelBlockEntity panel, int port) {
        PanelElement element = ELEMENTS.get(panel);
        return element == null || !MachinePorts.valid(port) ? List.of() : element.onPort(port);
    }

    @Override
    public int machinesSharingPort(BlockEntity machine) {
        int port = MachinePorts.get(machine);
        if (port == MachinePorts.NONE) {
            return 0;
        }
        int most = 0;
        List<PanelElement> elements;
        synchronized (ELEMENTS) {
            elements = new ArrayList<>(ELEMENTS.values());
        }
        for (PanelElement element : elements) {
            if (element.machines().contains(machine)) {
                most = Math.max(most, element.onPort(port).size());
            }
        }
        return most;
    }

    @Override
    public void panelRemoved(PanelBlockEntity panel) {
        PanelElement element;
        synchronized (ELEMENTS) {
            element = ELEMENTS.remove(panel);
        }
        if (element != null) {
            element.detach();
        }
    }
}
