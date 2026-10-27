package dev.bwr.mod.gui;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/**
 * The reactor building crane's pendant screen: what the crane is doing, where the head and the dryer and the separators are, and
 * the lifts. Every lift is a button a player presses (or a program's call); nothing here starts anything by itself.
 */
public class CraneMenu extends BwrMenu {

    /** a = 1 lifts the head to a stand, 0 sets it back on the vessel. */
    public static final int CMD_HEAD = 0;
    /** a = VesselInternal ordinal, b = 1 lifts it out to a pad, 0 sets it back in. */
    public static final int CMD_INTERNAL = 1;
    public static final int CMD_ABORT = 2;
    public static final int CMD_RESUME = 3;
    /** a = 1 takes the best-rigged shield plug section off to the laydown (PLUG OFF), 0 sets the last one back (PLUG ON). */
    public static final int CMD_PLUG = 4;
    /** a = 1 takes the nearest LP turbine's hood off to a casing laydown stand (COVER OFF), 0 sets a hood back (COVER ON). */
    public static final int CMD_COVER = 5;
    /** a = 1 lifts an open LP's worst blade section out to a blade stand (BLADES OUT), 0 sets a spare in (BLADES IN). */
    public static final int CMD_BLADES = 6;

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public boolean ready;
    public String problem = "";
    public boolean reactorFound;
    public String phase = "IDLE";
    public boolean busy;
    public boolean halted;
    public double progress;
    public int secondsLeft;
    public int queued;
    public String message = "";
    public String move = "";
    public String carried = "";
    public boolean headOff;
    public int studTotal;
    public int studsOff;
    public String headWhere = "";
    public int internalsOut;
    public double hookHeightM;
    public int spanBlocks;
    public int runwayBlocks;
    public int railY;
    /** Sections set down on the laydown (what PLUG ON would put back). */
    public int plugsLaid;
    /** A shield plug section hangs from the hook. */
    public boolean plugHeld;
    /** What PLUG OFF would take next: blocks, mass against the rating, anchors ("" when there is none), and whether that is a problem. */
    public String plugNext = "";
    public boolean plugNextBad;
    /** The turbine hall (TURBINE-MAINTENANCE.md): an LP turbine under the crane, the nearest one in a line, its cover and sections, and what kind of turbine load hangs (0 none, 1 hood, 2 blade section). */
    public boolean turbineFound;
    public String turbineLine = "";
    public boolean nearestCoverOff;
    public int nearestSectionsIn;
    public int turbineLoad;

    /** Client constructor. */
    public CraneMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public CraneMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.BUILDING_CRANE.get(), containerId, inventory, pos, BwrBlocks.BUILDING_CRANE_CONSOLE.get());
    }

    public static void open(ServerPlayer player, BuildingCraneBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new CraneMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.building_crane")),
                be.getBlockPos());
    }

    private BuildingCraneBlockEntity crane() {
        return blockEntity(BuildingCraneBlockEntity.class);
    }

    @Override
    protected int syncIntervalTicks() {
        return 10;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        BuildingCraneBlockEntity be = crane();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        BridgeSite site = be.refreshSite();
        boolean ok = site != null && site.ok();
        buf.writeBoolean(ok);
        if (!ok) {
            buf.writeUtf(site == null || site.problem == null ? "" : BwrText.english(site.problem), 256);
            return;
        }
        ReactorControllerBlockEntity r = site.reactor(be.getLevel());
        buf.writeBoolean(r != null);
        buf.writeUtf(be.phase().name(), 24);
        buf.writeBoolean(be.busy());
        buf.writeBoolean(be.phase() == dev.bwr.core.refuelling.BridgeMotion.Phase.HALTED);
        buf.writeDouble(be.progress());
        buf.writeVarInt((be.phaseTicksLeft() + 19) / 20);
        buf.writeVarInt(be.queued());
        buf.writeUtf(be.haltMessage() == null ? "" : BwrText.english(be.haltMessage()), 300);
        var current = be.currentMove();
        buf.writeUtf(current == null ? "" : current.from().describe() + " to " + current.to().describe(), 200);
        buf.writeUtf(be.carriedSummary(), 64);
        buf.writeBoolean(r != null && r.vesselState() == VesselState.REFUELING);
        buf.writeVarInt(r == null ? 0 : r.studTotal());
        buf.writeVarInt(r == null ? 0 : r.studsOff());
        BlockPos at = r == null ? null : r.headLocation();
        buf.writeUtf(r == null || r.vesselState() != VesselState.REFUELING ? "" : at == null ? "laid down" : at.equals(be.getBlockPos()) ? "on the hook" : "on its stand", 80);
        buf.writeVarInt(r == null ? 0 : r.internalsOutMask());
        var tip = be.tip();
        buf.writeDouble(tip == null ? 0.0 : tip.y() - site.railY);
        buf.writeVarInt(site.cHigh - site.cLow);
        buf.writeVarInt(site.aHi - site.aLo + 1);
        buf.writeVarInt(site.railY);
        buf.writeVarInt(be.plugsLaid());
        buf.writeBoolean(be.carriedPlug());
        var next = be.plugPreview();
        buf.writeUtf(next == null ? "" : next.text(), 120);
        buf.writeBoolean(next != null && (next.over() || !next.rigged()) && !next.carried());
        var sections = be.turbineSections();
        buf.writeBoolean(!sections.isEmpty());
        buf.writeUtf(be.turbineSummary(), 120);
        buf.writeBoolean(!sections.isEmpty() && Boolean.TRUE.equals(sections.get(0).get("coverOff")));
        buf.writeVarInt(sections.isEmpty() ? 3 : (Boolean.TRUE.equals(sections.get(0).get("sectionAIn")) ? 1 : 0) | (Boolean.TRUE.equals(sections.get(0).get("sectionBIn")) ? 2 : 0));
        var load = be.carriedTurbine();
        buf.writeVarInt(load.isEmpty() ? 0 : dev.bwr.mod.power.TurbineLoadItem.isHood(load) ? 1 : 2);
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        ready = buf.readBoolean();
        if (!ready) {
            problem = buf.readUtf(256);
            return;
        }
        reactorFound = buf.readBoolean();
        phase = buf.readUtf(24);
        busy = buf.readBoolean();
        halted = buf.readBoolean();
        progress = buf.readDouble();
        secondsLeft = buf.readVarInt();
        queued = buf.readVarInt();
        message = buf.readUtf(300);
        move = buf.readUtf(200);
        carried = buf.readUtf(64);
        headOff = buf.readBoolean();
        studTotal = buf.readVarInt();
        studsOff = buf.readVarInt();
        headWhere = buf.readUtf(80);
        internalsOut = buf.readVarInt();
        hookHeightM = buf.readDouble();
        spanBlocks = buf.readVarInt();
        runwayBlocks = buf.readVarInt();
        railY = buf.readVarInt();
        plugsLaid = buf.readVarInt();
        plugHeld = buf.readBoolean();
        plugNext = buf.readUtf(120);
        plugNextBad = buf.readBoolean();
        turbineFound = buf.readBoolean();
        turbineLine = buf.readUtf(120);
        nearestCoverOff = buf.readBoolean();
        nearestSectionsIn = buf.readVarInt();
        turbineLoad = buf.readVarInt();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        BuildingCraneBlockEntity be = crane();
        if (be == null) {
            return;
        }
        switch (command) {
            case CMD_HEAD -> {
                Component why = be.queueHead(a != 0);
                if (why != null) {
                    refuse(sender, why);
                } else {
                    sender.displayClientMessage(a != 0
                            ? BwrText.tr("status.bwr.building_crane.head_out_queued", "Queued: the crane lifts the head onto a head stand.")
                            : BwrText.tr("status.bwr.building_crane.head_in_queued", "Queued: the crane sets the head back on the vessel."), true);
                }
            }
            case CMD_INTERNAL -> {
                if (a < 0 || a >= VesselInternal.values().length) {
                    return;
                }
                var which = VesselInternal.values()[a];
                Component why = be.queueInternal(which, b != 0);
                if (why != null) {
                    refuse(sender, why);
                } else {
                    sender.displayClientMessage(BwrText.tr("status.bwr.building_crane.internal_queued", "Queued: the %s %s.", which.title,
                            b != 0 ? BwrText.tr("status.bwr.building_crane.goes_out", "goes out to a pad") : BwrText.tr("status.bwr.building_crane.goes_in", "goes back in the vessel")), true);
                }
            }
            case CMD_PLUG -> {
                Component why = be.queuePlug(a != 0, null);
                if (why != null) {
                    refuse(sender, why);
                } else {
                    sender.displayClientMessage(a != 0
                            ? BwrText.tr("status.bwr.building_crane.plug_out_queued", "Queued: the crane lifts a shield plug section off to the laydown.")
                            : BwrText.tr("status.bwr.building_crane.plug_in_queued", "Queued: the crane sets the shield plug section back where it came from."), true);
                }
            }
            case CMD_COVER -> {
                Component why = be.queueCover(a != 0, null);
                if (why != null) {
                    refuse(sender, why);
                } else {
                    sender.displayClientMessage(a != 0
                            ? BwrText.tr("status.bwr.turbine_crane.cover_off_queued", "Queued: the crane lifts the LP turbine's hood off onto a casing laydown stand.")
                            : BwrText.tr("status.bwr.turbine_crane.cover_on_queued", "Queued: the crane sets the hood back on the LP turbine."), true);
                }
            }
            case CMD_BLADES -> {
                Component why = be.queueBlades(a != 0, null);
                if (why != null) {
                    refuse(sender, why);
                } else {
                    sender.displayClientMessage(a != 0
                            ? BwrText.tr("status.bwr.turbine_crane.blades_out_queued", "Queued: the crane lifts the LP blade section out of the open casing onto a blade stand.")
                            : BwrText.tr("status.bwr.turbine_crane.blades_in_queued", "Queued: the crane sets a spare LP blade section into the open casing."), true);
                }
            }
            case CMD_ABORT -> be.abort();
            case CMD_RESUME -> be.resume();
            default -> {
                return;
            }
        }
        markSnapshotDirty();
    }
}
