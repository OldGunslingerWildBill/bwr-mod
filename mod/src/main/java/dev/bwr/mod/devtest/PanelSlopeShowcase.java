package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.RenderCache;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.KeySwitchData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.component.SetpointKnobData;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The sloped-panel part of the panel client check: a benchboard group (45 degrees, six blocks wide and three
 * deep) and a console group (15 degrees, six blocks wide) carrying baked switches, selectors, key switches,
 * knobs, round meters, edgewise meters, pushbuttons and lights, photographed from straight in front of the face
 * (looking along its normal, where a dial must look round and every model must look as it does on a vertical
 * board) and from where an operator would stand. Models keep their proportions on the slope when this looks
 * right; see {@code PanelSlopeTests} for the numbers.
 */
final class PanelSlopeShowcase {
    private PanelSlopeShowcase() {
    }

    private static final Direction FACING = Direction.NORTH;
    /** Top-left block of the benchboard group (back, left as the operator sees it); rows step one down and one north. */
    private static final BlockPos BENCH = new BlockPos(5099, 126, 5140);
    private static final int BENCH_COLS = 6;
    private static final int BENCH_ROWS = 3;
    private static final BlockPos CONSOLE = new BlockPos(5099, 126, 5160);
    private static final int CONSOLE_COLS = 6;

    /** One stage per photograph: bench then console, each from in front of the face, then from an operator's stance. */
    static final String[] NAMES = {"slope-bench-switches", "slope-bench-meters", "slope-bench-operator",
            "slope-bench-classic", "slope-console-switches", "slope-console-meters", "slope-console-operator"};
    private static final PanelType[] TYPE_OF = {PanelType.BENCHBOARD, PanelType.BENCHBOARD, PanelType.BENCHBOARD,
            PanelType.BENCHBOARD, PanelType.CONSOLE, PanelType.CONSOLE, PanelType.CONSOLE};
    /** The cell of the group the camera looks at, and how far (blocks) it stands from it along the face normal. */
    private static final int[][] FOCUS = {{38, 14}, {40, 32}, {48, 24}, {80, 24}, {30, 8}, {64, 8}, {48, 8}};
    private static final double[] DISTANCE = {2.4, 3.0, 4.0, 3.0, 1.9, 1.9, 3.0};
    /** Whether the stage looks along the face normal (true) or from a standing operator's eye (false). */
    private static final boolean[] FRONTAL = {true, true, false, true, true, true, false};

    private static BlockPos corner(PanelType type) {
        return type == PanelType.BENCHBOARD ? BENCH : CONSOLE;
    }

    /** Block-space point of a cell of the group (continuous across blocks) at elevation {@code cz}. */
    private static Vec3 point(PanelType type, double cu, double cv, double cz) {
        PanelGeometry.Frame frame = PanelGeometry.frame(type, FACING);
        BlockPos origin = corner(type);
        return new Vec3(origin.getX() + frame.x(cu, cv, cz), origin.getY() + frame.y(cu, cv, cz),
                origin.getZ() + frame.z(cu, cv, cz));
    }

    private static Vec3 eye(int stage) {
        PanelType type = TYPE_OF[stage];
        PanelGeometry.Frame frame = PanelGeometry.frame(type, FACING);
        Vec3 target = point(type, FOCUS[stage][0], FOCUS[stage][1], 0.0);
        if (FRONTAL[stage]) {
            return target.add(frame.nx * DISTANCE[stage], frame.ny * DISTANCE[stage], frame.nz * DISTANCE[stage]);
        }
        // Standing in front of the desk, the eyes above and back from the middle of the group.
        return target.add(0.0, DISTANCE[stage] * 0.55, -DISTANCE[stage]);
    }

    /** Where the camera stands (its feet) for a stage. */
    static Vec3 camera(int stage) {
        return eye(stage).subtract(0.0, 1.62, 0.0);
    }

    static Vec3 target(int stage) {
        return point(TYPE_OF[stage], FOCUS[stage][0], FOCUS[stage][1], 0.0);
    }

    // ---- building (server)

    private record Spec(ComponentType type, int x, int y, int w, int h, String style, String variant, double value) {
    }

    private static Spec spec(ComponentType type, int x, int y, int w, int h, String style, String variant, double value) {
        return new Spec(type, x, y, w, h, style, variant, value);
    }

    private static final String GE = PanelStyles.GE;
    private static final String W2 = PanelStyles.WESTINGHOUSE;

    /**
     * The benchboard group: switches along the top, then meters, pushbuttons and lights (80 x 48 cells), and in the
     * last column the classic procedural look of a dial, a pushbutton and a switch for comparison (their physical proportions now match the vertical board, with the spare row length centered around them).
     */
    private static List<Spec> benchSpecs() {
        List<Spec> specs = new ArrayList<>();
        specs.add(spec(ComponentTypes.CONTROL_SWITCH, 2, 0, 12, 16, GE, "pistol", -1));
        specs.add(spec(ComponentTypes.CONTROL_SWITCH, 16, 0, 12, 16, W2, "pistol_hd", 1));
        specs.add(spec(ComponentTypes.ROTARY_SELECTOR, 30, 2, 8, 10, GE, "oval", 1));
        specs.add(spec(ComponentTypes.ROTARY_SELECTOR, 40, 2, 8, 10, W2, "round_notched", 2));
        specs.add(spec(ComponentTypes.KEY_SWITCH, 50, 2, 6, 8, GE, "key", 1));
        specs.add(spec(ComponentTypes.KEY_SWITCH, 58, 2, 6, 8, W2, "tumbler", 1));
        specs.add(spec(ComponentTypes.SETPOINT_KNOB, 66, 2, 8, 8, GE, "fluted", 70));
        specs.add(spec(ComponentTypes.DIAL_GAUGE, 2, 18, 16, 16, GE, "standard", 68));
        specs.add(spec(ComponentTypes.DIAL_GAUGE, 20, 18, 16, 16, GE, "red_pointer", 30));
        specs.add(spec(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 40, 18, 6, 24, GE, "standard", 68));
        specs.add(spec(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 48, 18, 6, 24, GE, "red_pointer", 35));
        specs.add(spec(ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 56, 18, 24, 6, GE, "standard", 60));
        specs.add(spec(ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 56, 26, 24, 6, GE, "red_pointer", 80));
        specs.add(spec(ComponentTypes.PUSHBUTTON, 56, 34, 8, 8, GE, "flush", 0));
        specs.add(spec(ComponentTypes.PUSHBUTTON, 66, 34, 8, 8, GE, "mushroom", 0));
        for (int i = 0; i < 6; i++) {
            specs.add(spec(ComponentTypes.LAMP, 2 + i * 6, 38, 4, 4, GE, i % 2 == 0 ? "round" : "square", i % 3 == 2 ? 0 : 1));
        }
        String classic = PanelStyles.CLASSIC;
        specs.add(spec(ComponentTypes.PUSHBUTTON, 82, 2, 8, 8, classic, "flush", 0));
        specs.add(spec(ComponentTypes.CONTROL_SWITCH, 82, 12, 8, 12, classic, "pistol", 1));
        specs.add(spec(ComponentTypes.DIAL_GAUGE, 80, 28, 16, 16, classic, "standard", 68));
        return specs;
    }

    /** The console group: everything in one 16 cell deep row (96 x 16 cells). */
    private static List<Spec> consoleSpecs() {
        List<Spec> specs = new ArrayList<>();
        specs.add(spec(ComponentTypes.CONTROL_SWITCH, 2, 0, 12, 16, GE, "pistol", 1));
        specs.add(spec(ComponentTypes.CONTROL_SWITCH, 16, 2, 8, 12, W2, "pistol", -1));
        specs.add(spec(ComponentTypes.ROTARY_SELECTOR, 26, 2, 8, 10, GE, "knurled", 1));
        specs.add(spec(ComponentTypes.DIAL_GAUGE, 36, 0, 16, 16, GE, "standard", 68));
        specs.add(spec(ComponentTypes.DIAL_GAUGE, 54, 0, 16, 16, GE, "red_pointer", 30));
        specs.add(spec(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 72, 0, 6, 16, GE, "standard", 68));
        specs.add(spec(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 80, 0, 6, 16, GE, "red_pointer", 35));
        specs.add(spec(ComponentTypes.PUSHBUTTON, 88, 0, 6, 6, GE, "flush", 0));
        specs.add(spec(ComponentTypes.PUSHBUTTON, 88, 8, 6, 6, GE, "mushroom", 0));
        return specs;
    }

    static void build(ServerLevel level) {
        for (int c = 0; c < BENCH_COLS; c++) {
            for (int r = 0; r < BENCH_ROWS; r++) {
                level.setBlock(benchBlock(c, r), BwrBlocks.CONTROL_PANEL_BENCHBOARD.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, FACING), 3);
            }
        }
        for (int c = 0; c < CONSOLE_COLS; c++) {
            level.setBlock(consoleBlock(c), BwrBlocks.CONTROL_PANEL_CONSOLE.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, FACING), 3);
        }
        place(level, benchSpecs(), PanelType.BENCHBOARD);
        place(level, consoleSpecs(), PanelType.CONSOLE);
        for (BlockPos pos : blocks(PanelType.BENCHBOARD)) {
            ((PanelBlockEntity) level.getBlockEntity(pos)).syncFull();
        }
        for (BlockPos pos : blocks(PanelType.CONSOLE)) {
            ((PanelBlockEntity) level.getBlockEntity(pos)).syncFull();
        }
    }

    private static BlockPos benchBlock(int col, int row) {
        return BENCH.west(col).below(row).north(row);
    }

    private static BlockPos consoleBlock(int col) {
        return CONSOLE.west(col);
    }

    private static List<BlockPos> blocks(PanelType type) {
        List<BlockPos> all = new ArrayList<>();
        if (type == PanelType.BENCHBOARD) {
            for (int c = 0; c < BENCH_COLS; c++) {
                for (int r = 0; r < BENCH_ROWS; r++) {
                    all.add(benchBlock(c, r));
                }
            }
        } else {
            for (int c = 0; c < CONSOLE_COLS; c++) {
                all.add(consoleBlock(c));
            }
        }
        return all;
    }

    private static BlockPos blockAt(PanelType type, int cellX, int cellY) {
        return type == PanelType.BENCHBOARD ? benchBlock(cellX / 16, cellY / 16) : consoleBlock(cellX / 16);
    }

    private static void place(ServerLevel level, List<Spec> specs, PanelType type) {
        for (Spec spec : specs) {
            BlockPos anchor = blockAt(type, spec.x(), spec.y());
            PanelComponent component = PanelOps.placeDirect(level, anchor, spec.x() % 16, spec.y() % 16, spec.type(),
                    Footprint.of(spec.w(), spec.h()));
            if (component == null) {
                throw new AssertionError("Slope showcase placement refused " + spec.type().id() + " " + spec.w() + "x" + spec.h()
                        + " at " + type + " cell " + spec.x() + "," + spec.y());
            }
            PanelModelShowcase.look(component, spec.style(), spec.variant());
            var data = component.data();
            if (data instanceof ControlSwitchData d) {
                d.spring = false;
                d.position = (int) spec.value();
                component.type().onLoaded(component);
            } else if (data instanceof RotarySelectorData d) {
                d.position = (int) spec.value();
                component.type().onLoaded(component);
            } else if (data instanceof KeySwitchData d) {
                d.on = spec.value() > 0.5;
                component.type().onLoaded(component);
            } else if (data instanceof SetpointKnobData d) {
                d.value = spec.value();
                component.type().onLoaded(component);
            } else if (component.inputCount() > 0) {
                component.setSource(0, InputSources.constant(spec.value()));
                ((PanelBlockEntity) level.getBlockEntity(anchor)).resample(component, true);
            }
            component.touch();
        }
    }

    // ---- checking (client)

    /**
     * Confirms every component of the stage's group draws with a baked model exactly when the style table maps
     * its choice to a loaded model, and returns "baked/total" for the log. Throws if any disagree.
     */
    static String verify(Minecraft mc, int stage) {
        PanelType type = TYPE_OF[stage];
        int baked = 0;
        int total = 0;
        List<String> wrong = new ArrayList<>();
        for (BlockPos pos : blocks(type)) {
            if (!(mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
                throw new AssertionError("Slope showcase block missing on the client: " + pos);
            }
            for (PanelComponent component : be.owned()) {
                var tag = component.data().toTag();
                String name = component.type().id().getPath();
                boolean expected = PanelStyles.resolve(name, tag.getString("Style"), tag.getString("Variant"), PanelModels::has) != null;
                boolean actual = RenderCache.of(component).usesBaked();
                total++;
                if (actual) {
                    baked++;
                }
                if (expected != actual) {
                    wrong.add(name + "/" + tag.getString("Style") + "/" + tag.getString("Variant") + " expected "
                            + (expected ? "baked" : "classic") + " drew " + (actual ? "baked" : "classic"));
                }
            }
        }
        if (total == 0 || !wrong.isEmpty()) {
            throw new AssertionError("Slope showcase " + NAMES[stage] + ": " + total + " components; " + wrong);
        }
        return baked + "/" + total;
    }
}
