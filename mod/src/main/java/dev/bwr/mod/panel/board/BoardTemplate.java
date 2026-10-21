package dev.bwr.mod.panel.board;

import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A premade control board: panel blocks and the components on them, as read from {@code data/<ns>/panel_board/<name>.json}
 * by {@link BoardTemplateParser}. A template is plain data and says nothing about wiring: everything on it is placed
 * unwired, and the player wires it with the Networking Wrench.
 *
 * <p>The geometry is in the operator's frame: {@code right} to the operator's right, {@code up}, and {@code forward}
 * toward the operator, all in blocks from the <i>origin</i> the board is placed at (the blueprint puts the origin at the
 * block in front of the clicked spot, so the board stands up to the player's right and away from them). The parts of a
 * frame that carry a mounting face are {@link Surface}s, each a plane of cells (one cell is 1/16 block) with its
 * top-left corner at cell (0, 0), x to the operator's right and y down, like the panel grid itself; a component or a
 * mimic path says which surface it is on and where on it.
 *
 * @param id          {@code <ns>:<file name>}
 * @param title       a short name for the blueprint tooltip
 * @param description what the board is and what it is modelled on
 * @param source      where the layout comes from (the research report, the plant)
 * @param order       blueprint cycling order (lower first, then by id)
 * @param frames      the panel blocks
 * @param components  every component, repeats already expanded
 * @param reserved    empty spaces kept for parts that do not exist yet
 * @param mimic       mimic tape paths
 * @param todo        comments for whoever extends the template
 */
public record BoardTemplate(ResourceLocation id, String title, String description, String source, int order,
                            List<Frame> frames, List<Comp> components, List<Reserved> reserved, List<Mimic> mimic,
                            List<String> todo) {

    /** The panel block styles a frame can be made of. */
    public enum Kind {
        /** One item per column: the bench, the board (two rows) and the tilted header. Surfaces: board, header, bench. */
        DUPLEX("duplex"),
        VERTICAL("vertical"),
        BENCHBOARD("benchboard"),
        CONSOLE("console"),
        BACK_PANEL("back_panel");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Kind byId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * A run of panel blocks. {@code (right, up, forward)} is the position of its first block from the origin: for a
     * duplex the bench of the left column, for the others the top left block ({@code rows} then run down, and on a
     * benchboard also toward the operator).
     */
    public record Frame(String id, Kind kind, int columns, int rows, int right, int up, int forward) {
        /** The mounting surfaces of this frame, in a fixed order. */
        public List<Surface> surfaces() {
            return switch (kind) {
                case DUPLEX -> List.of(
                        new Surface(id + ".board", this, "board", PanelType.DUPLEX_BOARD, columns, 2),
                        new Surface(id + ".header", this, "header", PanelType.DUPLEX_HEADER, columns, 1),
                        new Surface(id + ".bench", this, "bench", PanelType.DUPLEX_BENCH, columns, 1));
                case VERTICAL -> List.of(new Surface(id + ".face", this, "face", PanelType.VERTICAL, columns, rows));
                case BENCHBOARD -> List.of(new Surface(id + ".face", this, "face", PanelType.BENCHBOARD, columns, rows));
                case CONSOLE -> List.of(new Surface(id + ".face", this, "face", PanelType.CONSOLE, columns, 1));
                case BACK_PANEL -> List.of(new Surface(id + ".face", this, "face", PanelType.BACK_PANEL, columns, rows));
            };
        }
    }

    /** One mounting plane: {@code columns} x {@code rows} panel blocks of one style, {@code 16 x} that many cells. */
    public record Surface(String id, Frame frame, String part, PanelType panelType, int columns, int rows) {
        public int width() {
            return columns * PanelGeometry.GRID;
        }

        public int height() {
            return rows * PanelGeometry.GRID;
        }
    }

    /**
     * One component. {@code size} is its footprint in cells; {@code config} holds only the fields the template sets
     * (already converted to the NBT type of each field and range checked); {@code label} is empty for the automatic
     * {@code PREFIX-n} label and {@code caption} empty for none.
     */
    public record Comp(String surface, ComponentType type, int x, int y, int w, int h, CompoundTag config, String label,
                       String caption) {
    }

    /** A space kept empty for a part that does not exist yet ({@code forType}, for example {@code bwr:controller_station}). */
    public record Reserved(String surface, int x, int y, int w, int h, String forType, String note) {
    }

    /** A mimic path: points in cells of the surface (x, y pairs), and the style fields the template sets. */
    public record Mimic(String surface, int[] points, CompoundTag style) {
    }

    /** All the surfaces of all the frames. */
    public List<Surface> surfaces() {
        return frames.stream().flatMap(frame -> frame.surfaces().stream()).toList();
    }

    public Surface surface(String surfaceId) {
        for (Frame frame : frames) {
            for (Surface surface : frame.surfaces()) {
                if (surface.id().equals(surfaceId)) {
                    return surface;
                }
            }
        }
        return null;
    }
}
