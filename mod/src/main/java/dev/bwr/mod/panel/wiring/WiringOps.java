package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.LinkTools;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.signal.CommandSpec;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.signal.SignalSpec;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The validated server side of the panel linker and the Networking Wrench: opening the binding screen and binding or unbinding one
 * port. The C2S packets and the GameTests both come through here, so there is one set of rules:
 * <ul>
 *   <li>the player holds a linker or a Networking Wrench ({@link LinkTools#held}), is in reach of the panel block and is permitted on it;</li>
 *   <li>a plant binding needs the device the tool stores (never one the client names), in the player's own
 *       dimension, within {@link PanelConfig#linkRange()} of the panel block, permitted for the player, loaded,
 *       with a signal (input) or command (output) of that key;</li>
 *   <li>a component binding needs a component of the same group, with that output, other than the target;</li>
 *   <li>every number from the client is range-checked.</li>
 * </ul>
 * Nothing here ever loads a chunk.
 */
public final class WiringOps {
    private WiringOps() {
    }

    /** What to bind. The ordinal travels in {@link PanelPayloads.Bind}. */
    public enum BindKind {
        /** Clears an input to "no signal", or removes every sink of an output. */
        NONE,
        PLANT,
        COMPONENT,
        REDSTONE,
        CC,
        /** Removes one sink (its position is {@code refOutput}) of an output. */
        REMOVE_SINK,
        /**
         * A signal of the machine that answers on a CC port: the port travels in {@code refOutput} and the signal in
         * {@code key}. Not line linking: no device position is involved.
         */
        CC_PORT;

        public static BindKind byId(int id) {
            BindKind[] all = values();
            return id >= 0 && id < all.length ? all[id] : null;
        }
    }

    public enum Result {
        OK, NOT_HOLDING_LINKER, OUT_OF_REACH, NOT_PERMITTED, NO_SUCH_COMPONENT, BAD_PORT, BAD_VALUE, NO_LINK,
        WRONG_DIMENSION, OUT_OF_RANGE, DEVICE_MISMATCH, NO_DEVICE, NO_SUCH_KEY, NOT_IN_GROUP, TOO_MANY,
        /** The component type refused the device (a core display bound to a non-reactor, or too small for the core). */
        REFUSED,
        /** The gauge is set to measure one quantity and the signal is another (a level gauge offered a kW signal). */
        MEASURES_MISMATCH,
        /** CC port bindings are unavailable (CC:Tweaked is not installed). */
        NO_CC,
        /** No machine answers on that port of the panel's CC network. */
        PORT_EMPTY,
        /** Two or more machines answer on that port of the panel's CC network; nothing is guessed. */
        PORT_CONFLICT
    }

    /** A bind result with, for {@link Result#REFUSED}, the reason the component type gave. */
    public record Outcome(Result result, net.minecraft.network.chat.Component detail) {
    }

    /** The reason of the most recent {@link Result#REFUSED}; read right after {@link #bind} on the same thread. */
    private static net.minecraft.network.chat.Component lastRefusal;

    /** {@link #bind} that also returns the reason for a refusal by the component type. */
    public static Outcome bindDetailed(ServerPlayer player, PanelPayloads.Bind request) {
        lastRefusal = null;
        Result result = bind(player, request);
        net.minecraft.network.chat.Component detail = result == Result.REFUSED || result == Result.MEASURES_MISMATCH
                || result == Result.PORT_CONFLICT ? lastRefusal : null;
        lastRefusal = null;
        return new Outcome(result, detail);
    }

    /** Most sinks one output may fan out to. */
    public static final int MAX_SINKS = 8;
    private static final double MAX_SCALE = 1.0e6;

    // ---- binding

    public static Result bind(ServerPlayer player, PanelPayloads.Bind request) {
        ServerLevel level = player.serverLevel();
        BindKind kind = BindKind.byId(request.kind());
        if (kind == null) {
            return Result.BAD_VALUE;
        }
        ItemStack linker = LinkTools.held(player);
        if (linker.isEmpty()) {
            return Result.NOT_HOLDING_LINKER;
        }
        if (!AssemblyAccess.mayEdit(player)) {
            return Result.NOT_PERMITTED;
        }
        BlockPos ownerPos = request.pos();
        if (!PanelOps.inReach(player, ownerPos) || !level.isLoaded(ownerPos)) {
            return Result.OUT_OF_REACH;
        }
        if (!AssemblyAccess.permitted(level, player, ownerPos)) {
            return Result.NOT_PERMITTED;
        }
        if (!(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return Result.NO_SUCH_COMPONENT;
        }
        if (request.id() < 0) {
            // A negative id addresses a mimic path on the block (-localId), which has a single plant input.
            return dev.bwr.mod.panel.mimic.MimicOps.bind(player, level, linker, owner, request);
        }
        PanelComponent component = owner.component(request.id());
        if (component == null || component.type() == null) {
            return Result.NO_SUCH_COMPONENT;
        }
        int ports = request.output() ? component.outputCount() : component.inputCount();
        if (request.port() < 0 || request.port() >= ports) {
            return Result.BAD_PORT;
        }
        Result result = request.output() ? bindOutput(player, level, linker, owner, component, kind, request)
                : bindInput(player, level, linker, owner, component, kind, request);
        if (result == Result.OK) {
            owner.updateRedstoneOutput();
            owner.syncFull();
            if (request.output()) dev.bwr.mod.advancement.PlantMilestones.panelChanged(owner);
        }
        return result;
    }

    private static Result bindInput(ServerPlayer player, ServerLevel level, ItemStack linker, PanelBlockEntity owner,
                                    PanelComponent component, BindKind kind, PanelPayloads.Bind request) {
        InputSource source;
        switch (kind) {
            case NONE -> source = InputSources.NONE;
            case REDSTONE -> source = RedstoneSource.INSTANCE;
            case CC -> source = component.source(request.port()) instanceof CcSource existing ? existing : new CcSource();
            case PLANT -> {
                // displayed = raw * scale + offset; the same bounds as an output scale, and a finite offset.
                if (!validScale(request.scale()) || !Double.isFinite(request.offset())
                        || Math.abs(request.offset()) > MAX_SCALE) {
                    return Result.BAD_VALUE;
                }
                Result checked = checkDevice(player, level, linker, owner.getBlockPos(), request, false);
                if (checked != Result.OK) {
                    return checked;
                }
                SignalSpec bound = specOf(level, request.device(), request.key());
                if (bound != null && measuresMismatch(component, request.port(), bound)) {
                    return Result.MEASURES_MISMATCH;
                }
                net.minecraft.network.chat.Component refusal = component.type().refuseBinding(level, component,
                        request.port(), request.device());
                if (refusal != null) {
                    lastRefusal = refusal;
                    return Result.REFUSED;
                }
                double scale = bound == null ? request.scale()
                        : PowerUnitsOps.scaleForBind(component, bound, request.scale(), request.offset());
                source = new PlantSource(request.device(), request.key(), scale, request.offset());
            }
            case CC_PORT -> {
                if (!validScale(request.scale()) || !Double.isFinite(request.offset())
                        || Math.abs(request.offset()) > MAX_SCALE) {
                    return Result.BAD_VALUE;
                }
                int port = request.refOutput();
                if (!dev.bwr.mod.panel.MachinePorts.valid(port)) {
                    return Result.BAD_VALUE;
                }
                if (!CcPortBridge.available()) {
                    return Result.NO_CC;
                }
                CcPortBridge.Lookup found = CcPortBridge.lookup(owner, port);
                if (found.machine() == null) {
                    if (found.claimants() == 0) {
                        return Result.PORT_EMPTY;
                    }
                    lastRefusal = dev.bwr.mod.text.BwrText.tr("message.bwr.panel_bind.port_conflict",
                            "Port %s is used by %s machines", dev.bwr.mod.panel.MachinePorts.format(port), found.claimants());
                    return Result.PORT_CONFLICT;
                }
                net.minecraft.world.level.block.entity.BlockEntity machine = found.machine();
                if (machine.getLevel() != level) {
                    return Result.WRONG_DIMENSION;
                }
                long range = PanelConfig.linkRange();
                if (owner.getBlockPos().distSqr(machine.getBlockPos()) > range * range) {
                    return Result.OUT_OF_RANGE;
                }
                if (!AssemblyAccess.permitted(level, player, machine.getBlockPos())) {
                    return Result.NOT_PERMITTED;
                }
                SignalProvider provider = SignalProviders.of(machine);
                SignalSpec spec = null;
                if (provider != null) {
                    for (SignalSpec candidate : provider.signals()) {
                        if (candidate.key().equals(request.key()) && candidate.kind() != Kind.TEXT) {
                            spec = candidate;
                        }
                    }
                }
                if (spec == null) {
                    return Result.NO_SUCH_KEY;
                }
                if (measuresMismatch(component, request.port(), spec)) {
                    return Result.MEASURES_MISMATCH;
                }
                net.minecraft.network.chat.Component refusal = component.type().refuseBinding(level, component,
                        request.port(), machine.getBlockPos());
                if (refusal != null) {
                    lastRefusal = refusal;
                    return Result.REFUSED;
                }
                double scale = PowerUnitsOps.scaleForBind(component, spec, request.scale(), request.offset());
                source = new CcPortSource(port, request.key(), scale, request.offset());
            }
            case COMPONENT -> {
                BlockPos refOwner = request.refOwner();
                PanelGroup group = owner.group();
                if (group == null || !group.contains(refOwner)) {
                    return Result.NOT_IN_GROUP;
                }
                if (!level.isLoaded(refOwner) || !(level.getBlockEntity(refOwner) instanceof PanelBlockEntity refBlock)) {
                    return Result.NOT_IN_GROUP;
                }
                PanelComponent referenced = refBlock.component(request.refId());
                if (referenced == null || referenced.type() == null || referenced == component) {
                    return Result.NO_SUCH_COMPONENT;
                }
                if (request.refOutput() < 0 || request.refOutput() >= referenced.outputCount()) {
                    return Result.BAD_PORT;
                }
                source = new ComponentSource(refOwner, request.refId(), request.refOutput());
            }
            default -> {
                return Result.BAD_VALUE;
            }
        }
        component.setSource(request.port(), source);
        owner.resample(component, true);
        return Result.OK;
    }

    private static Result bindOutput(ServerPlayer player, ServerLevel level, ItemStack linker, PanelBlockEntity owner,
                                     PanelComponent component, BindKind kind, PanelPayloads.Bind request) {
        int port = request.port();
        switch (kind) {
            case NONE -> {
                owner.releaseHeld(component); // a held control lets go before its sinks vanish, or the RELEASE reaches nothing
                component.clearSinks(port);
            }
            case REMOVE_SINK -> {
                if (request.refOutput() >= 0 && request.refOutput() < component.sinks(port).size()) {
                    owner.releaseHeld(component);
                }
                if (!component.removeSink(port, request.refOutput())) {
                    return Result.BAD_PORT;
                }
            }
            case CC -> {
                // CC events are not a stored sink: every operated output raises bwr_panel_input.
            }
            case REDSTONE -> {
                if (!validScale(request.scale())) {
                    return Result.BAD_VALUE;
                }
                List<OutputSink> existing = new ArrayList<>(component.sinks(port));
                for (int i = existing.size() - 1; i >= 0; i--) {
                    if (existing.get(i) instanceof RedstoneSink) {
                        component.removeSink(port, i);
                    }
                }
                component.addSink(port, new RedstoneSink(request.scale()));
            }
            case PLANT -> {
                PlantSink.Mode mode = PlantSink.Mode.byId(request.sinkMode());
                if (mode == null) {
                    return Result.BAD_VALUE;
                }
                // A momentary sink ignores the scale, so only a following one needs a usable scale.
                if (mode == PlantSink.Mode.FOLLOW && !validScale(request.scale()) || !Double.isFinite(request.fixed())) {
                    return Result.BAD_VALUE;
                }
                if (component.sinks(port).size() >= MAX_SINKS) {
                    return Result.TOO_MANY;
                }
                Result checked = checkDevice(player, level, linker, owner.getBlockPos(), request, true);
                if (checked != Result.OK) {
                    return checked;
                }
                if (mode == PlantSink.Mode.MOMENTARY && !fixedInRange(level, request)) {
                    return Result.BAD_VALUE;
                }
                component.addSink(port, mode == PlantSink.Mode.MOMENTARY
                        ? new PlantSink(request.device(), request.key(), 1.0, mode, request.fixed())
                        : new PlantSink(request.device(), request.key(), request.scale()));
            }
            default -> {
                return Result.BAD_VALUE;
            }
        }
        return Result.OK;
    }

    /**
     * The signal an input source reads: the signal of a line link ({@link PlantSource}) or of the machine on a CC
     * port ({@link CcPortSource}), or null for any other source or when it cannot be found now (unloaded, no machine,
     * a port conflict). Never loads a chunk.
     */
    public static SignalSpec boundSpec(ServerLevel level, PanelBlockEntity panel, dev.bwr.mod.panel.component.InputSource source) {
        if (source instanceof PlantSource plant) {
            return specOf(level, plant.device(), plant.key());
        }
        if (source instanceof CcPortSource cc && CcPortBridge.available()) {
            net.minecraft.world.level.block.entity.BlockEntity machine = CcPortBridge.lookup(panel, cc.port()).machine();
            if (machine != null && !machine.isRemoved() && machine.getLevel() == level && level.isLoaded(machine.getBlockPos())) {
                SignalProvider provider = SignalProviders.of(machine);
                if (provider != null) {
                    for (SignalSpec spec : provider.signals()) {
                        if (spec.key().equals(cc.key())) {
                            return spec;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** The scale of a plant or CC port source (1 for anything else). */
    public static double scaleOf(dev.bwr.mod.panel.component.InputSource source) {
        return source instanceof PlantSource plant ? plant.scale() : source instanceof CcPortSource cc ? cc.scale() : 1.0;
    }

    /** The offset of a plant or CC port source (0 for anything else). */
    public static double offsetOf(dev.bwr.mod.panel.component.InputSource source) {
        return source instanceof PlantSource plant ? plant.offset() : source instanceof CcPortSource cc ? cc.offset() : 0.0;
    }

    /** A copy of a plant or CC port source with another scale; any other source as it was. */
    public static dev.bwr.mod.panel.component.InputSource rescaled(dev.bwr.mod.panel.component.InputSource source, double scale) {
        if (source instanceof PlantSource plant) {
            return new PlantSource(plant.device(), plant.key(), scale, plant.offset());
        }
        if (source instanceof CcPortSource cc) {
            return new CcPortSource(cc.port(), cc.key(), scale, cc.offset());
        }
        return source;
    }

    /** The signal {@code key} of the device at {@code device}, or null. */
    public static SignalSpec specOf(ServerLevel level, BlockPos device, String key) {
        SignalProvider provider = SignalProviders.at(level, device);
        if (provider != null) {
            for (SignalSpec spec : provider.signals()) {
                if (spec.key().equals(key)) {
                    return spec;
                }
            }
        }
        return null;
    }

    /**
     * Whether binding {@code signal} to input {@code port} of {@code component} breaks its {@code Measures} setting (a
     * gauge set to LEVEL offered a POWER signal); sets the reason the player is shown. A component that measures ANY
     * (the default) never mismatches.
     */
    private static boolean measuresMismatch(PanelComponent component, int port, SignalSpec signal) {
        dev.bwr.mod.signal.Quantity wanted = component.data().measures(port);
        dev.bwr.mod.signal.Quantity actual = dev.bwr.mod.signal.Quantity.of(signal);
        if (wanted == null || wanted == actual) {
            return false;
        }
        lastRefusal = dev.bwr.mod.text.BwrText.tr("message.bwr.panel_bind.measures_mismatch",
                "This gauge measures %s; %s is %s", wanted.name(), signal.label(), actual.name());
        return true;
    }

    private static boolean validScale(double scale) {
        return Double.isFinite(scale) && scale != 0.0 && Math.abs(scale) <= MAX_SCALE;
    }

    /** Whether a momentary sink's fixed value lies in the range of the (already checked) command. */
    private static boolean fixedInRange(ServerLevel level, PanelPayloads.Bind request) {
        SignalProvider provider = SignalProviders.at(level, request.device());
        if (provider == null) {
            return false;
        }
        for (CommandSpec spec : provider.commands()) {
            if (spec.key().equals(request.key())) {
                return request.fixed() >= spec.min() && request.fixed() <= spec.max();
            }
        }
        return false;
    }

    /** Shared checks for a plant binding: the linker's device, dimension, range, permission, and the key. */
    public static Result checkDevice(ServerPlayer player, ServerLevel level, ItemStack linker, BlockPos panel,
                                      PanelPayloads.Bind request, boolean command) {
        GlobalPos link = PanelLinkerItem.linked(linker);
        if (link == null) {
            return Result.NO_LINK;
        }
        if (!link.dimension().equals(level.dimension())) {
            return Result.WRONG_DIMENSION;
        }
        if (!link.pos().equals(request.device())) {
            return Result.DEVICE_MISMATCH;
        }
        long range = PanelConfig.linkRange();
        if (panel.distSqr(link.pos()) > range * range) {
            return Result.OUT_OF_RANGE;
        }
        if (!AssemblyAccess.permitted(level, player, link.pos())) {
            return Result.NOT_PERMITTED;
        }
        SignalProvider provider = SignalProviders.at(level, link.pos());
        if (provider == null) {
            return Result.NO_DEVICE;
        }
        if (command) {
            for (CommandSpec spec : provider.commands()) {
                if (spec.key().equals(request.key()) && spec.kind() != Kind.TEXT) {
                    return Result.OK;
                }
            }
        } else {
            for (SignalSpec spec : provider.signals()) {
                if (spec.key().equals(request.key()) && spec.kind() != Kind.TEXT) {
                    return Result.OK;
                }
            }
        }
        return Result.NO_SUCH_KEY;
    }

    // ---- binding screen

    /**
     * Opens the binding screen for a component: sends the linked device's signals and commands (when the
     * linker's device is bindable) or the reason it is not.
     */
    public static boolean openScreen(ServerPlayer player, BlockPos ownerPos, int id) {
        PanelPayloads.LinkerScreen screen = screenFor(player, ownerPos, id);
        if (screen == null) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, screen);
        return true;
    }

    /** The screen contents {@link #openScreen} would send, or null when the player may not open it. */
    public static PanelPayloads.LinkerScreen screenFor(ServerPlayer player, BlockPos ownerPos, int id) {
        ServerLevel level = player.serverLevel();
        if (!AssemblyAccess.mayEdit(player) || LinkTools.held(player).isEmpty() || !PanelOps.inReach(player, ownerPos)
                || !level.isLoaded(ownerPos) || !AssemblyAccess.permitted(level, player, ownerPos)
                || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return null;
        }
        if (id < 0) {
            if (owner.mimicPath(-id) == null) {
                return null;
            }
        } else {
            PanelComponent component = owner.component(id);
            if (component == null || component.type() == null) {
                return null;
            }
        }
        GlobalPos link = PanelLinkerItem.linked(LinkTools.held(player));
        int status = PanelPayloads.LINK_OK;
        BlockPos device = BlockPos.ZERO;
        String name = "";
        List<PanelPayloads.SignalEntry> signals = new ArrayList<>();
        List<PanelPayloads.CommandEntry> commands = new ArrayList<>();
        if (link == null) {
            status = PanelPayloads.LINK_NONE;
        } else {
            device = link.pos();
            long range = PanelConfig.linkRange();
            SignalProvider provider = null;
            if (!link.dimension().equals(level.dimension())) {
                status = PanelPayloads.LINK_WRONG_DIMENSION;
            } else if (ownerPos.distSqr(device) > range * range) {
                status = PanelPayloads.LINK_OUT_OF_RANGE;
            } else if ((provider = SignalProviders.at(level, device)) == null) {
                status = PanelPayloads.LINK_MISSING;
            }
            if (status == PanelPayloads.LINK_OK) {
                name = level.getBlockState(device).getBlock().getDescriptionId();
                for (SignalSpec spec : provider.signals()) {
                    if (spec.kind() != Kind.TEXT && signals.size() < PanelPayloads.MAX_LISTED) {
                        signals.add(new PanelPayloads.SignalEntry(clip(spec.key(), PanelPayloads.MAX_KEY),
                                clip(spec.label(), PanelPayloads.MAX_LABEL), clip(spec.unit(), PanelPayloads.MAX_KEY),
                                dev.bwr.mod.signal.Quantity.of(spec).ordinal()));
                    }
                }
                var deviceEntity = dev.bwr.mod.signal.DeviceResolver.resolve(level, device);
                for (CommandSpec spec : provider.commands()) {
                    if (spec.kind() != Kind.TEXT && commands.size() < PanelPayloads.MAX_LISTED) {
                        commands.add(new PanelPayloads.CommandEntry(clip(spec.key(), PanelPayloads.MAX_KEY),
                                clip(spec.label(), PanelPayloads.MAX_LABEL), spec.kind() == Kind.PULSE,
                                spec.kind().ordinal(), spec.min(), shownMax(deviceEntity, spec)));
                    }
                }
            }
        }
        String machineName = "";
        if (status == PanelPayloads.LINK_OK) {
            machineName = dev.bwr.mod.panel.MachineNames.name(dev.bwr.mod.signal.DeviceResolver.resolve(level, device));
        }
        return new PanelPayloads.LinkerScreen(ownerPos, id, status, device, name, signals, commands, machineName);
    }

    /**
     * The top of a command's range as the binding screen shows it: the declared one, narrowed to what the device accepts
     * right now. A grid tie declares its demand commands up to 48,000 MW (the engineering bound) but refuses anything over
     * the server's {@code gridTieMaxMW} (1,500 by default), so a screen that offered 48,000 would start a 0 to 100 percent
     * station at 480 MW a percent and the tie would refuse everything above 3 percent.
     */
    public static double shownMax(net.minecraft.world.level.block.entity.BlockEntity device, CommandSpec spec) {
        if (device instanceof dev.bwr.mod.grid.GridTieBlockEntity && spec.kind() == Kind.NUMBER && switch (spec.key()) {
            case "fixed_mw", "remote_mw", "curve_min_mw", "curve_max_mw" -> true;
            default -> false;
        }) {
            return Math.min(spec.max(), dev.bwr.mod.grid.GridConfig.maximumMW());
        }
        return spec.max();
    }

    /**
     * The binding screen contents for the CC network mode: the signals of the machine that answers on {@code port} of
     * the CC network of the panel (status {@link PanelPayloads#LINK_OK}), or why there is none: no machine on the port
     * ({@link PanelPayloads#LINK_PORT_EMPTY}) or two or more ({@link PanelPayloads#LINK_PORT_CONFLICT}, with their number
     * in {@code machineName}). The same checks as {@link #screenFor}: a wiring tool in hand, in reach, permitted. Null
     * when the player may not open it.
     */
    public static PanelPayloads.LinkerScreen ccPortScreenFor(ServerPlayer player, BlockPos ownerPos, int id, int port) {
        ServerLevel level = player.serverLevel();
        if (!AssemblyAccess.mayEdit(player) || LinkTools.held(player).isEmpty() || !PanelOps.inReach(player, ownerPos)
                || !level.isLoaded(ownerPos) || !AssemblyAccess.permitted(level, player, ownerPos)
                || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return null;
        }
        PanelComponent component = id < 0 ? null : owner.component(id);
        if (id < 0 || component == null || component.type() == null) {
            return null;
        }
        List<PanelPayloads.SignalEntry> signals = new ArrayList<>();
        int status = PanelPayloads.LINK_PORT_EMPTY;
        BlockPos device = BlockPos.ZERO;
        String name = "";
        String machineName = "";
        if (dev.bwr.mod.panel.MachinePorts.valid(port) && CcPortBridge.available()) {
            CcPortBridge.Lookup found = CcPortBridge.lookup(owner, port);
            if (found.machine() == null) {
                if (found.claimants() > 0) {
                    status = PanelPayloads.LINK_PORT_CONFLICT;
                    machineName = Integer.toString(found.claimants());
                }
            } else {
                net.minecraft.world.level.block.entity.BlockEntity machine = found.machine();
                long range = PanelConfig.linkRange();
                device = machine.getBlockPos();
                SignalProvider provider = SignalProviders.of(machine);
                if (machine.getLevel() != level) {
                    status = PanelPayloads.LINK_WRONG_DIMENSION;
                } else if (ownerPos.distSqr(device) > range * range) {
                    status = PanelPayloads.LINK_OUT_OF_RANGE;
                } else if (provider == null) {
                    status = PanelPayloads.LINK_MISSING;
                } else {
                    status = PanelPayloads.LINK_OK;
                    name = machine.getBlockState().getBlock().getDescriptionId();
                    machineName = dev.bwr.mod.panel.MachineNames.name(machine);
                    for (SignalSpec spec : provider.signals()) {
                        if (spec.kind() != Kind.TEXT && signals.size() < PanelPayloads.MAX_LISTED) {
                            signals.add(new PanelPayloads.SignalEntry(clip(spec.key(), PanelPayloads.MAX_KEY),
                                    clip(spec.label(), PanelPayloads.MAX_LABEL), clip(spec.unit(), PanelPayloads.MAX_KEY),
                                    dev.bwr.mod.signal.Quantity.of(spec).ordinal()));
                        }
                    }
                }
            }
        }
        return new PanelPayloads.LinkerScreen(ownerPos, id, status, device, name, signals, List.of(), machineName, port);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
