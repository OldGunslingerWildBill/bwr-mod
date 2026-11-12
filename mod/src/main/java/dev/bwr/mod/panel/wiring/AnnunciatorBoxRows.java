package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorType;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.AnnunciatorBox;
import dev.bwr.mod.panel.model.AnnunciatorBoxList;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.signal.DeviceResolver;
import dev.bwr.mod.world.AssemblyAccess;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rows of the annunciator box list: the windows of one box (windows joined into a box, in any style) with what
 * each is wired to, read from the panels as they are. Both sides use it: the client builds its screen from the panels
 * it already holds, the server only adds what a client cannot know, the names of the machines the windows read
 * ({@link #names}). It reads loaded blocks only and never loads a chunk.
 */
public final class AnnunciatorBoxRows {
    /**
     * One window of a box.
     *
     * @param block  the panel block that owns the window
     * @param row    its row in the box, from 0
     * @param column its column, from 0
     * @param wiring what its input is wired to
     */
    public record Row(PanelBlockEntity block, PanelComponent component, AnnunciatorData data, int row, int column,
                      AnnunciatorBoxList.Wiring wiring) {
        public BlockPos owner() {
            return block.getBlockPos();
        }

        public int id() {
            return component.id();
        }

        /** The light's number as its box prints it, {@code 3-2}. */
        public String number() {
            return (row + 1) + "-" + (column + 1);
        }

        /** The legend on one line; empty when the window has none. */
        public String legend() {
            return AnnunciatorBoxList.legend(data.line1, data.line2, data.line3, data.line4);
        }

        public InputSource source() {
            return component.source(0);
        }

        /** What the light shows now. */
        public AnnunciatorBoxList.Show show() {
            return AnnunciatorBoxList.show(data.logic.state(), !Double.isNaN(component.input(0)));
        }
    }

    private AnnunciatorBoxRows() {
    }

    /**
     * The windows of the box that component {@code id} of {@code block} is in, in reading order, at most
     * {@link AnnunciatorBoxList#MAX_ROWS}. Empty when that component is not an annunciator window (or is gone).
     */
    public static List<Row> rows(PanelBlockEntity block, int id) {
        List<Row> rows = new ArrayList<>();
        Level level = block == null ? null : block.getLevel();
        PanelComponent asked = level == null ? null : block.component(id);
        if (asked == null || !(asked.type() instanceof AnnunciatorType) || !(asked.data() instanceof AnnunciatorData)) {
            return rows;
        }
        List<PanelBlockEntity> owners = new ArrayList<>();
        List<PanelComponent> windows = new ArrayList<>();
        List<AnnunciatorBox.Rect> rects = new ArrayList<>();
        PanelGroup group = block.group();
        if (group == null) {
            collect(block, 0, 0, owners, windows, rects);
        } else {
            for (BlockPos pos : group.members()) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof PanelBlockEntity member) {
                    collect(member, group.columnOf(pos), group.rowOf(pos), owners, windows, rects);
                }
            }
        }
        int index = -1;
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i) == asked) {
                index = i;
                break;
            }
        }
        for (AnnunciatorBoxList.Light light : AnnunciatorBoxList.lights(rects.toArray(new AnnunciatorBox.Rect[0]), index,
                AnnunciatorBoxList.MAX_ROWS)) {
            PanelComponent window = windows.get(light.index());
            rows.add(new Row(owners.get(light.index()), window, (AnnunciatorData) window.data(), light.row(), light.column(),
                    wiring(window.source(0))));
        }
        return rows;
    }

    private static void collect(PanelBlockEntity member, int column, int row, List<PanelBlockEntity> owners,
                                List<PanelComponent> windows, List<AnnunciatorBox.Rect> rects) {
        for (PanelComponent c : member.owned()) {
            if (c.type() instanceof AnnunciatorType && c.data() instanceof AnnunciatorData) {
                owners.add(member);
                windows.add(c);
                rects.add(new AnnunciatorBox.Rect(column * PanelGeometry.GRID + c.x(), row * PanelGeometry.GRID + c.y(), c.w(), c.h()));
            }
        }
    }

    /** What a source is, for the list's filters and counts. A machine read through a CC network port is a plant signal. */
    public static AnnunciatorBoxList.Wiring wiring(InputSource source) {
        if (source == null || source == InputSources.NONE || InputSources.KIND_NONE.equals(source.kind())) {
            return AnnunciatorBoxList.Wiring.NONE;
        }
        if (source instanceof PlantSource || source instanceof CcPortSource) {
            return AnnunciatorBoxList.Wiring.PLANT;
        }
        if (source instanceof CcSource) {
            return AnnunciatorBoxList.Wiring.COMPUTER;
        }
        if (source instanceof RedstoneSource) {
            return AnnunciatorBoxList.Wiring.REDSTONE;
        }
        if (source instanceof ComponentSource) {
            return AnnunciatorBoxList.Wiring.COMPONENT;
        }
        if (InputSources.KIND_CONSTANT.equals(source.kind())) {
            return AnnunciatorBoxList.Wiring.CONSTANT;
        }
        return AnnunciatorBoxList.Wiring.OTHER;
    }

    /** The box's name: the first one that is not empty among its windows, in reading order; empty when none has one. */
    public static String boxName(List<Row> rows) {
        for (Row row : rows) {
            if (!row.data().boxName.isEmpty()) {
                return row.data().boxName;
            }
        }
        return "";
    }

    /** The header counts of a box. */
    public static AnnunciatorBoxList.Counts counts(List<Row> rows) {
        List<AnnunciatorBoxList.Wiring> wirings = new ArrayList<>(rows.size());
        List<AnnunciatorBoxList.Show> shows = new ArrayList<>(rows.size());
        for (Row row : rows) {
            wirings.add(row.wiring());
            shows.add(row.show());
        }
        return AnnunciatorBoxList.counts(wirings, shows);
    }

    /** The rows that pass a filter, in order. */
    public static List<Row> filter(List<Row> rows, AnnunciatorBoxList.Filter filter) {
        List<Row> kept = new ArrayList<>();
        for (Row row : rows) {
            if (AnnunciatorBoxList.matches(filter, row.wiring(), row.show())) {
                kept.add(row);
            }
        }
        return kept;
    }

    /**
     * Server: the names of the machines the windows of a box read, for the box list screen of {@code player}; null when
     * the player may not have that screen. The rule is the wrench screen's: allowed to edit, the Panel Wrench in hand,
     * the window's panel block within reach, loaded and permitted. Of the box's other windows only those in blocks the
     * player is permitted for are looked at, and a machine is named only when it is loaded now (nothing is loaded for
     * this) and permitted for the player. At most {@code cap} machines are named, never more than
     * {@link PanelPayloads#MAX_BOX_DEVICES}.
     */
    public static PanelPayloads.BoxNames names(ServerPlayer player, BlockPos pos, int id, int cap) {
        ServerLevel level = player.serverLevel();
        if (id < 0 || !AssemblyAccess.mayEdit(player) || PanelOps.heldWrench(player).isEmpty() || !PanelOps.inReach(player, pos)
                || !level.isLoaded(pos) || !AssemblyAccess.permitted(level, player, pos)
                || !(level.getBlockEntity(pos) instanceof PanelBlockEntity block)) {
            return null;
        }
        List<Row> rows = rows(block, id);
        if (rows.isEmpty()) {
            return null;
        }
        int limit = Math.max(0, Math.min(cap, PanelPayloads.MAX_BOX_DEVICES));
        Set<BlockPos> seen = new LinkedHashSet<>();
        List<PanelPayloads.DeviceName> names = new ArrayList<>();
        for (Row row : rows) {
            if (names.size() >= limit) {
                break;
            }
            if (!(row.source() instanceof PlantSource plant) || !seen.add(plant.device())
                    || !AssemblyAccess.permitted(level, player, row.owner())) {
                continue;
            }
            BlockPos device = plant.device();
            if (!LoadedWorld.available(level, device) || !AssemblyAccess.permitted(level, player, device)) {
                continue;
            }
            BlockEntity machine = DeviceResolver.resolve(level, device);
            if (machine == null) {
                continue;
            }
            String name = clip(MachineNames.name(machine), PanelPayloads.MAX_BOX_NAME);
            String blockKey = clip(level.getBlockState(machine.getBlockPos()).getBlock().getDescriptionId(), PanelPayloads.MAX_LABEL);
            names.add(new PanelPayloads.DeviceName(device.immutable(), name, blockKey));
        }
        return new PanelPayloads.BoxNames(pos.immutable(), id, names);
    }

    private static String clip(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max);
    }
}
