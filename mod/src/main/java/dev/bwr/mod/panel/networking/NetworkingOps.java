package dev.bwr.mod.panel.networking;

import dev.bwr.mod.panel.LinkTools;
import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.signal.CommandSpec;
import dev.bwr.mod.signal.DeviceResolver;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.signal.SignalSpec;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * The validated server side of the Networking Wrench: machine names and the name push, and (with the screen) the
 * Networking screen. The C2S packets and the GameTests both come through here, so there is one set of rules: the
 * player holds the wrench, is in reach of the device and permitted on it, and every string is cleaned. Nothing here
 * ever loads a chunk, ticks, or commands a device.
 */
public final class NetworkingOps {
    private NetworkingOps() {
    }

    /** One line link of a component: the device it names, the signal or command key, and whether it commands. */
    public record LineLink(BlockPos device, String key, boolean command, int port) {
    }

    /**
     * The first line link of a component: its plant inputs by port, then its plant sinks by output and position. A
     * component bound to several machines takes its caption from this one. Bindings that are not line links (a CC
     * port, redstone, another component, a constant) are never a line link.
     */
    public static LineLink firstLineLink(PanelComponent component) {
        if (component.type() == null) {
            return null;
        }
        for (int i = 0; i < component.inputCount(); i++) {
            if (component.source(i) instanceof PlantSource plant) {
                return new LineLink(plant.device(), plant.key(), false, i);
            }
        }
        for (int i = 0; i < component.outputCount(); i++) {
            for (OutputSink sink : component.sinks(i)) {
                if (sink instanceof PlantSink plant) {
                    return new LineLink(plant.device(), plant.key(), true, i);
                }
            }
        }
        return null;
    }

    // ---- checks

    /**
     * The block entity that owns the device at {@code pos} when {@code player} may work on it: holding the wrench,
     * in reach, permitted, loaded and a signal provider. Null otherwise.
     */
    public static BlockEntity checkedDevice(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (LinkTools.heldWrench(player).isEmpty() || !PanelOps.inReach(player, pos) || !level.isLoaded(pos)
                || !AssemblyAccess.permitted(level, player, pos)) {
            return null;
        }
        BlockEntity owner = DeviceResolver.resolve(level, pos);
        return owner != null && SignalProviders.of(owner) != null ? owner : null;
    }

    // ---- machine name

    /**
     * Sets a machine's name and caption template (the Networking screen's Done). Returns whether the player was
     * allowed; the name and template are cleaned, an empty name clears it.
     */
    public static boolean edit(ServerPlayer player, BlockPos device, String name, String template) {
        BlockEntity owner = checkedDevice(player, device);
        if (owner == null) {
            return false;
        }
        MachineNames.setName(owner, name);
        MachineNames.setTemplate(owner, template);
        return true;
    }

    // ---- the name push

    /** What a name push did. */
    public record Pushed(boolean allowed, int renamed, int refused) {
    }

    /**
     * "Name linked components": every component on the loaded panels within {@link PanelConfig#linkRange()} of the
     * device whose first line link names this machine gets its caption from the machine's name and template. Panels
     * the player may not edit are left alone (counted as refused). CC-port bindings and every other kind of binding
     * are skipped, because only a line link names a machine unambiguously. Fires no sink and changes no signal.
     */
    public static Pushed nameLinked(ServerPlayer player, BlockPos device) {
        BlockEntity owner = checkedDevice(player, device);
        if (owner == null) {
            return new Pushed(false, 0, 0);
        }
        ServerLevel level = player.serverLevel();
        if (MachineNames.name(owner).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.bwr.networking.unnamed"), true);
            return new Pushed(true, 0, 0);
        }
        SignalProvider provider = SignalProviders.of(owner);
        int renamed = 0;
        int refused = 0;
        for (PanelBlockEntity panel : panelsNear(level, owner.getBlockPos(), PanelConfig.linkRange())) {
            boolean permitted = AssemblyAccess.permitted(level, player, panel.getBlockPos());
            boolean touched = false;
            for (PanelComponent component : panel.owned()) {
                LineLink link = firstLineLink(component);
                if (link == null || !sameOwner(level, link.device(), owner)) {
                    continue;
                }
                if (!permitted) {
                    refused++;
                    continue;
                }
                if (apply(owner, provider, component, link)) {
                    touched = true;
                }
                renamed++;
            }
            if (touched) {
                panel.syncFull();
            }
        }
        player.displayClientMessage(Component.translatable("message.bwr.networking.named", renamed, MachineNames.display(level,
                owner.getBlockPos())).append(refused == 0 ? Component.empty()
                : Component.translatable("message.bwr.networking.named_refused", refused)), true);
        return new Pushed(true, renamed, refused);
    }

    /**
     * "Use machine name" on one component: the same caption, for the component's first line link, when it has one.
     * Returns whether the component was named.
     */
    public static boolean useMachineName(ServerPlayer player, BlockPos panelPos, int id) {
        ServerLevel level = player.serverLevel();
        if (LinkTools.heldWrench(player).isEmpty() || !PanelOps.inReach(player, panelPos) || !level.isLoaded(panelPos)
                || !AssemblyAccess.permitted(level, player, panelPos)
                || !(level.getBlockEntity(panelPos) instanceof PanelBlockEntity panel)) {
            return false;
        }
        PanelComponent component = panel.component(id);
        LineLink link = component == null ? null : firstLineLink(component);
        if (link == null || !level.isLoaded(link.device())) {
            return false;
        }
        BlockEntity owner = DeviceResolver.resolve(level, link.device());
        if (owner == null || SignalProviders.of(owner) == null) {
            return false;
        }
        if (MachineNames.name(owner).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.bwr.networking.unnamed"), true);
            return false;
        }
        boolean changed = apply(owner, SignalProviders.of(owner), component, link);
        if (changed) {
            panel.syncFull();
        }
        player.displayClientMessage(Component.translatable("message.bwr.networking.named", 1,
                MachineNames.display(level, owner.getBlockPos())), true);
        return true;
    }

    /** Sets {@code component}'s caption from {@code owner}'s name and template; returns whether it changed. */
    private static boolean apply(BlockEntity owner, SignalProvider provider, PanelComponent component, LineLink link) {
        String label = "";
        String unit = "";
        if (provider != null) {
            if (link.command()) {
                for (CommandSpec spec : provider.commands()) {
                    if (spec.key().equals(link.key())) {
                        label = spec.label();
                    }
                }
            } else {
                for (SignalSpec spec : provider.signals()) {
                    if (spec.key().equals(link.key())) {
                        label = spec.label();
                        unit = spec.unit();
                    }
                }
            }
        }
        return component.setCaption(MachineNames.caption(owner, label, unit));
    }

    /** Whether the device at {@code pos} resolves to {@code owner}. Never loads a chunk. */
    private static boolean sameOwner(ServerLevel level, BlockPos pos, BlockEntity owner) {
        return level.isLoaded(pos) && DeviceResolver.resolve(level, pos) == owner;
    }

    /** The loaded panel blocks within {@code range} blocks of {@code center}; only chunks that are already loaded. */
    public static List<PanelBlockEntity> panelsNear(ServerLevel level, BlockPos center, int range) {
        List<PanelBlockEntity> found = new ArrayList<>();
        double limit = (double) range * range;
        ChunkPos low = new ChunkPos(center.offset(-range, 0, -range));
        ChunkPos high = new ChunkPos(center.offset(range, 0, range));
        for (int cx = low.x; cx <= high.x; cx++) {
            for (int cz = low.z; cz <= high.z; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof PanelBlockEntity panel && !panel.isRemoved()
                            && panel.getBlockPos().distSqr(center) <= limit) {
                        found.add(panel);
                    }
                }
            }
        }
        return found;
    }

    // ---- the Networking screen (part 4)

    /** Opens the Networking screen of the device at {@code pos} for a player holding the wrench. */
    public static boolean openScreen(ServerPlayer player, BlockPos pos) {
        NetworkingScreenPayloads.ScreenData data = screenFor(player, pos);
        if (data == null) {
            return false;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, data);
        return true;
    }

    /**
     * The screen contents {@link #openScreen} would send, or null when the player may not open it (the same checks as
     * binding: the wrench in hand, in reach, permitted, loaded, a signal provider). Every signal and command of the
     * machine is listed, with its quantity and how many components of the loaded panels within the link range read or
     * command it through a line link; those components are listed as the machine's links.
     */
    public static NetworkingScreenPayloads.ScreenData screenFor(ServerPlayer player, BlockPos pos) {
        BlockEntity owner = checkedDevice(player, pos);
        if (owner == null) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        SignalProvider provider = SignalProviders.of(owner);
        java.util.Map<String, Integer> readers = new java.util.HashMap<>();
        java.util.Map<String, Integer> users = new java.util.HashMap<>();
        List<NetworkingScreenPayloads.NetLink> links = new ArrayList<>();
        for (PanelBlockEntity panel : panelsNear(level, owner.getBlockPos(), PanelConfig.linkRange())) {
            for (PanelComponent component : panel.owned()) {
                if (component.type() == null) {
                    continue;
                }
                for (int i = 0; i < component.inputCount(); i++) {
                    if (component.source(i) instanceof PlantSource plant && sameOwner(level, plant.device(), owner)) {
                        readers.merge(plant.key(), 1, Integer::sum);
                        addLink(links, panel, component, false, i, 0, plant.key(), provider);
                    }
                }
                for (int i = 0; i < component.outputCount(); i++) {
                    List<OutputSink> sinks = component.sinks(i);
                    for (int k = 0; k < sinks.size(); k++) {
                        if (sinks.get(k) instanceof PlantSink plant && sameOwner(level, plant.device(), owner)) {
                            users.merge(plant.key(), 1, Integer::sum);
                            addLink(links, panel, component, true, i, k, plant.key(), provider);
                        }
                    }
                }
            }
        }
        List<NetworkingScreenPayloads.NetSignal> signals = new ArrayList<>();
        for (SignalSpec spec : provider.signals()) {
            if (signals.size() < NetworkingScreenPayloads.MAX_LISTED) {
                signals.add(new NetworkingScreenPayloads.NetSignal(clip(spec.key(), 64), clip(spec.label(), 96),
                        clip(spec.unit(), 64), dev.bwr.mod.signal.Quantity.of(spec).ordinal(), readers.getOrDefault(spec.key(), 0)));
            }
        }
        List<NetworkingScreenPayloads.NetCommand> commands = new ArrayList<>();
        for (CommandSpec spec : provider.commands()) {
            if (commands.size() < NetworkingScreenPayloads.MAX_LISTED) {
                commands.add(new NetworkingScreenPayloads.NetCommand(clip(spec.key(), 64), clip(spec.label(), 96),
                        spec.kind().ordinal(), users.getOrDefault(spec.key(), 0)));
            }
        }
        return new NetworkingScreenPayloads.ScreenData(owner.getBlockPos(), owner.getBlockState().getBlock().getDescriptionId(),
                MachineNames.name(owner), MachineNames.template(owner), -1, 0, false, signals, commands, links);
    }

    private static void addLink(List<NetworkingScreenPayloads.NetLink> links, PanelBlockEntity panel, PanelComponent component,
                                boolean command, int port, int sink, String key, SignalProvider provider) {
        if (links.size() >= NetworkingScreenPayloads.MAX_LINKS) {
            return;
        }
        String label = key;
        if (command) {
            for (CommandSpec spec : provider.commands()) {
                if (spec.key().equals(key)) {
                    label = spec.label();
                }
            }
        } else {
            for (SignalSpec spec : provider.signals()) {
                if (spec.key().equals(key)) {
                    label = spec.label();
                }
            }
        }
        links.add(new NetworkingScreenPayloads.NetLink(panel.getBlockPos(), component.id(), clip(component.label(), 96),
                clip(component.caption(), 96), command, port, sink, clip(key, 64), clip(label, 96)));
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Seconds-ish limit: a player may ask for values at most this often (ticks); a faster ask is dropped. */
    private static final int MIN_VALUE_TICKS = 10;
    private static final java.util.Map<java.util.UUID, Long> LAST_VALUES = new java.util.HashMap<>();

    /**
     * The live values of the device's signals as text, in the order {@link #screenFor} listed them, or null when the
     * player may not ask or asked too soon. Numbers are shown with their unit; a signal with no value shows a dash.
     */
    public static List<String> valuesFor(ServerPlayer player, BlockPos pos) {
        BlockEntity owner = checkedDevice(player, pos);
        if (owner == null) {
            return null;
        }
        long now = player.serverLevel().getGameTime();
        Long last = LAST_VALUES.get(player.getUUID());
        if (last != null && now >= last && now - last < MIN_VALUE_TICKS) {
            return null;
        }
        LAST_VALUES.put(player.getUUID(), now);
        SignalProvider provider = SignalProviders.of(owner);
        List<String> values = new ArrayList<>();
        for (SignalSpec spec : provider.signals()) {
            if (values.size() >= NetworkingScreenPayloads.MAX_LISTED) {
                break;
            }
            values.add(clip(formatValue(provider, spec), 90));
        }
        return values;
    }

    /** A signal's value as text for the screen. */
    public static String formatValue(SignalProvider provider, SignalSpec spec) {
        if (spec.kind() == dev.bwr.mod.signal.Kind.TEXT) {
            return provider.readText(spec.key()).orElse("-");
        }
        java.util.OptionalDouble value = provider.read(spec.key());
        if (value.isEmpty()) {
            return "-";
        }
        double v = value.getAsDouble();
        if (spec.kind() == dev.bwr.mod.signal.Kind.BOOLEAN) {
            return v >= 0.5 ? "on" : "off";
        }
        String number = v == Math.rint(v) && Math.abs(v) < 1.0e9 ? Long.toString((long) v)
                : String.format(java.util.Locale.ROOT, Math.abs(v) >= 1000 ? "%.1f" : "%.3f", v);
        return spec.unit().isEmpty() ? number : number + " " + spec.unit();
    }

    /** Forgets a player's throttle state (they left). */
    public static void forget(java.util.UUID player) {
        LAST_VALUES.remove(player);
    }
}
