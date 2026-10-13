package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelGroup;
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
 * The validated server side of the panel linker: opening the binding screen and binding or unbinding one
 * port. The C2S packets and the GameTests both come through here, so there is one set of rules:
 * <ul>
 *   <li>the player holds a linker, is in reach of the panel block and is permitted on it;</li>
 *   <li>a plant binding needs the device the linker stores (never one the client names), in the player's own
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
        REMOVE_SINK;

        public static BindKind byId(int id) {
            BindKind[] all = values();
            return id >= 0 && id < all.length ? all[id] : null;
        }
    }

    public enum Result {
        OK, NOT_HOLDING_LINKER, OUT_OF_REACH, NOT_PERMITTED, NO_SUCH_COMPONENT, BAD_PORT, BAD_VALUE, NO_LINK,
        WRONG_DIMENSION, OUT_OF_RANGE, DEVICE_MISMATCH, NO_DEVICE, NO_SUCH_KEY, NOT_IN_GROUP, TOO_MANY
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
        ItemStack linker = PanelLinkerItem.held(player);
        if (linker.isEmpty()) {
            return Result.NOT_HOLDING_LINKER;
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
                source = new PlantSource(request.device(), request.key(), request.scale(), request.offset());
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
            case NONE -> component.clearSinks(port);
            case REMOVE_SINK -> {
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
    private static Result checkDevice(ServerPlayer player, ServerLevel level, ItemStack linker, BlockPos panel,
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
        if (PanelLinkerItem.held(player).isEmpty() || !PanelOps.inReach(player, ownerPos) || !level.isLoaded(ownerPos)
                || !AssemblyAccess.permitted(level, player, ownerPos)
                || !(level.getBlockEntity(ownerPos) instanceof PanelBlockEntity owner)) {
            return null;
        }
        PanelComponent component = owner.component(id);
        if (component == null || component.type() == null) {
            return null;
        }
        GlobalPos link = PanelLinkerItem.linked(PanelLinkerItem.held(player));
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
                                clip(spec.label(), PanelPayloads.MAX_LABEL), clip(spec.unit(), PanelPayloads.MAX_KEY)));
                    }
                }
                for (CommandSpec spec : provider.commands()) {
                    if (spec.kind() != Kind.TEXT && commands.size() < PanelPayloads.MAX_LISTED) {
                        commands.add(new PanelPayloads.CommandEntry(clip(spec.key(), PanelPayloads.MAX_KEY),
                                clip(spec.label(), PanelPayloads.MAX_LABEL), spec.kind() == Kind.PULSE,
                                spec.kind().ordinal(), spec.min(), spec.max()));
                    }
                }
            }
        }
        return new PanelPayloads.LinkerScreen(ownerPos, id, status, device, name, signals, commands);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
