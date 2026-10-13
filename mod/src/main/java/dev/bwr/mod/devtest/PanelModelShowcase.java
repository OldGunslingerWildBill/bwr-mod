package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.RenderCache;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.KeySwitchData;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.PushbuttonData;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The baked-model part of the panel client check: seven small boards (five blocks wide, four high) that between
 * them show every (style, variant) of the style table in several states, so a run with the real models yields
 * screenshots of all of them, and a run without them shows the classic fallback in the same places.
 *
 * <ul>
 *   <li>0: control switches (GE target pistol, W-2 pistol, W-2 heavy duty) at CLOSE, NORMAL, OPEN and with the
 *       red or green lamp lit; 1 and 2: GE and Westinghouse selectors at several positions and counts;
 *       3: key switches off and on, and the fluted knob at 0, 50 and 100 percent (also recoloured);
 *       4: pushbuttons released, held and recoloured, and indicating lights off and lit;
 *       5: round meters at 0, 50 and 100 percent and with no signal; 6: edgewise meters, vertical and horizontal.</li>
 * </ul>
 * The check also confirms, from the client, that every component draws with a baked model exactly when the style
 * table says a loaded model exists for its choice.
 */
final class PanelModelShowcase {
    private PanelModelShowcase() {
    }

    static final int GROUPS = 7;
    private static final int COLS = 5;
    private static final int ROWS = 4;
    /** Top-left block of group 0 as seen from the north; further groups step west, columns run west. */
    private static final BlockPos CORNER = new BlockPos(5083, 127, 5100);
    /** The (type, style, variant) combinations placed, to prove the showcase covers the whole table. */
    private static final Set<String> SHOWN = new LinkedHashSet<>();

    /** Screenshot names, one per stage: the seven boards, the lights at night, then four close-ups to read the legends. */
    static final String[] NAMES = {"baked-control-switches", "baked-selectors-ge", "baked-selectors-westinghouse",
            "baked-keys-and-knobs", "baked-pushbuttons-and-lights", "baked-round-meters", "baked-edgewise-meters",
            "baked-night-lights", "baked-closeup-control-switch", "baked-closeup-selector-westinghouse",
            "baked-closeup-round-meter", "baked-closeup-edgewise-meters"};
    /** The group each stage shows. */
    private static final int[] GROUP_OF = {0, 1, 2, 3, 4, 5, 6, 4, 0, 2, 5, 6};
    /** The cell (within the group) the camera looks at, and how far away it stands in blocks. */
    private static final int[][] FOCUS = {{40, 32}, {40, 32}, {40, 32}, {40, 32}, {40, 32}, {40, 32}, {40, 32}, {40, 32},
            {17 + 6, 22 + 8}, {17 + 6, 2 + 7}, {20 + 8, 2 + 8}, {10 + 4, 2 + 12}};
    private static final double[] DISTANCE = {4.2, 4.2, 4.2, 4.2, 4.2, 4.2, 4.2, 4.2, 1.7, 1.5, 1.8, 2.6};

    static boolean night(int stage) {
        return stage == 7;
    }

    /** World x of a cell column of a group (columns run toward -X on a north facing board). */
    private static double worldX(int group, double cellX) {
        return CORNER.getX() - group * 6 + 1.0 - cellX / 16.0;
    }

    private static double worldY(double cellY) {
        return CORNER.getY() + 1.0 - cellY / 16.0;
    }

    static BlockPos block(int group, int col, int row) {
        return CORNER.west(group * 6 + col).below(row);
    }

    /** Where the camera stands (its feet) for a stage: facing the board from the north, eyes level with the focus. */
    static Vec3 camera(int stage) {
        return new Vec3(worldX(GROUP_OF[stage], FOCUS[stage][0]), worldY(FOCUS[stage][1]) - 1.62,
                CORNER.getZ() + 0.75 - DISTANCE[stage]);
    }

    static Vec3 target(int stage) {
        return new Vec3(worldX(GROUP_OF[stage], FOCUS[stage][0]), worldY(FOCUS[stage][1]), CORNER.getZ() + 0.75);
    }

    // ---- building (server)

    static void build(ServerLevel level) {
        SHOWN.clear();
        for (int g = 0; g < GROUPS; g++) {
            for (int c = 0; c < COLS; c++) {
                for (int r = 0; r < ROWS; r++) {
                    level.setBlock(block(g, c, r), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                }
            }
        }
        controlSwitches(level);
        selectors(level, 1, PanelStyles.GE, new String[] {"oval", "knurled", "lever"});
        selectors(level, 2, PanelStyles.WESTINGHOUSE, new String[] {"oval", "round_notched", "mini_slim"});
        keysAndKnobs(level);
        pushbuttonsAndLights(level);
        roundMeters(level);
        edgewiseMeters(level);
        for (int g = 0; g < GROUPS; g++) {
            for (int c = 0; c < COLS; c++) {
                for (int r = 0; r < ROWS; r++) {
                    if (level.getBlockEntity(block(g, c, r)) instanceof PanelBlockEntity be) {
                        be.syncFull();
                    }
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (PanelStyles.Entry entry : PanelStyles.TABLE) {
            if (!SHOWN.contains(entry.type() + "/" + entry.style() + "/" + entry.variant())) {
                missing.add(entry.type() + "/" + entry.style() + "/" + entry.variant());
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("Baked-model showcase does not show every style table row: " + missing);
        }
    }

    private static PanelComponent put(ServerLevel level, int group, ComponentType type, int x, int y, int w, int h,
                                      String style, String variant) {
        BlockPos anchor = block(group, x / 16, y / 16);
        PanelComponent component = PanelOps.placeDirect(level, anchor, x % 16, y % 16, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Baked showcase placement refused " + type.id() + " " + w + "x" + h + " at group "
                    + group + " cell " + x + "," + y);
        }
        var data = component.data();
        if (data instanceof ControlSwitchData d) {
            d.style = style;
            d.variant = variant;
        } else if (data instanceof RotarySelectorData d) {
            d.style = style;
            d.variant = variant;
        } else if (data instanceof KeySwitchData d) {
            d.style = style;
            d.variant = variant;
        } else if (data instanceof PushbuttonData d) {
            d.style = style;
            d.variant = variant;
        } else if (data instanceof LampData d) {
            d.style = style;
            d.variant = variant;
        } else if (data instanceof SetpointKnobData d) {
            d.style = style;
            d.variant = variant;
        } else if (data instanceof GaugeData d) {
            d.style = style;
            d.variant = variant;
        }
        SHOWN.add(type.id().getPath() + "/" + style + "/" + variant);
        component.touch();
        return component;
    }

    private static PanelBlockEntity owner(ServerLevel level, int group, int x, int y) {
        return (PanelBlockEntity) level.getBlockEntity(block(group, x / 16, y / 16));
    }

    private static void controlSwitches(ServerLevel level) {
        String[][] rows = {{PanelStyles.GE, "pistol"}, {PanelStyles.WESTINGHOUSE, "pistol"}, {PanelStyles.WESTINGHOUSE, "pistol_hd"}};
        // CLOSE, NORMAL, OPEN held where they were put; then the red and the green lamp lit with the handle at NORMAL.
        int[] positions = {-1, 0, 1, 0, 0};
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < COLS; col++) {
                int x = 2 + col * 15;
                int y = 2 + row * 20;
                PanelComponent component = put(level, 0, ComponentTypes.CONTROL_SWITCH, x, y, 12, 16, rows[row][0], rows[row][1]);
                ControlSwitchData data = (ControlSwitchData) component.data();
                data.spring = false;
                data.position = positions[col];
                component.type().onLoaded(component);
                if (col >= 3) {
                    component.setSource(col - 3, InputSources.constant(1));
                    owner(level, 0, x, y).resample(component, true);
                }
            }
        }
    }

    private static void selectors(ServerLevel level, int group, String style, String[] variants) {
        // 3 positions at 1, 2, 3; 6 positions at the last; 2 positions at the second.
        int[][] shapes = {{3, 0}, {3, 1}, {3, 2}, {6, 5}, {2, 1}};
        for (int row = 0; row < variants.length; row++) {
            for (int col = 0; col < shapes.length; col++) {
                int x = 2 + col * 15;
                int y = 2 + row * 18;
                PanelComponent component = put(level, group, ComponentTypes.ROTARY_SELECTOR, x, y, 12, 14, style, variants[row]);
                RotarySelectorData data = (RotarySelectorData) component.data();
                data.positions = shapes[col][0];
                data.names = "OFF,AUTO,MAN,TEST,RUN,HOLD";
                data.position = shapes[col][1];
                component.syncPorts();
                component.type().onLoaded(component);
            }
        }
    }

    private static void keysAndKnobs(ServerLevel level) {
        String[][] keys = {{PanelStyles.GE, "key"}, {PanelStyles.WESTINGHOUSE, "key"}, {PanelStyles.WESTINGHOUSE, "tumbler"}};
        for (int row = 0; row < keys.length; row++) {
            for (int col = 0; col < 2; col++) {
                PanelComponent component = put(level, 3, ComponentTypes.KEY_SWITCH, 2 + col * 11, 2 + row * 14, 8, 10,
                        keys[row][0], keys[row][1]);
                KeySwitchData data = (KeySwitchData) component.data();
                data.on = col == 1;
                component.type().onLoaded(component);
            }
        }
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                PanelComponent component = put(level, 3, ComponentTypes.SETPOINT_KNOB, 28 + col * 16, 2 + row * 18, 12, 12,
                        PanelStyles.GE, "fluted");
                SetpointKnobData data = (SetpointKnobData) component.data();
                data.value = col * 50;
                if (row == 1) {
                    data.color = 0x30A050; // a deliberately chosen colour recolours the tinted parts
                }
                component.type().onLoaded(component);
            }
        }
    }

    private static void pushbuttonsAndLights(ServerLevel level) {
        String[] buttons = {"flush", "extended", "mushroom", "guarded", "illuminated"};
        for (int row = 0; row < buttons.length; row++) {
            for (int col = 0; col < 3; col++) {
                int x = 2 + col * 10;
                int y = 2 + row * 11;
                PanelComponent component = put(level, 4, ComponentTypes.PUSHBUTTON, x, y, 8, 8, PanelStyles.GE, buttons[row]);
                PushbuttonData data = (PushbuttonData) component.data();
                if (col == 2) {
                    data.color = 0x2060E0; // recoloured cap
                }
                owner(level, 4, x, y).setOutput(component, 0, col == 1 ? 1.0 : 0.0);
            }
        }
        String[] lights = {"round", "round_plate", "et16", "square"};
        int[] colours = {LampData.DEFAULT_COLOR, LampData.DEFAULT_COLOR, 0x30D060, 0xFFB000};
        double[] states = {0.0, 1.0, 1.0, 1.0};
        for (int row = 0; row < lights.length; row++) {
            for (int col = 0; col < colours.length; col++) {
                int x = 36 + col * 10;
                int y = 2 + row * 11;
                PanelComponent component = put(level, 4, ComponentTypes.LAMP, x, y, 8, 8, PanelStyles.GE, lights[row]);
                LampData data = (LampData) component.data();
                data.color = colours[col];
                component.setSource(0, InputSources.constant(states[col]));
                owner(level, 4, x, y).resample(component, true);
            }
        }
    }

    private static void roundMeters(ServerLevel level) {
        String[] variants = {"standard", "red_pointer"};
        for (int row = 0; row < variants.length; row++) {
            for (int col = 0; col < 4; col++) {
                int x = 2 + col * 19;
                int y = 2 + row * 20;
                PanelComponent component = put(level, 5, ComponentTypes.DIAL_GAUGE, x, y, 16, 16, PanelStyles.GE, variants[row]);
                GaugeData data = (GaugeData) component.data();
                data.min = 0;
                data.max = 100;
                data.units = "MPa";
                if (col < 3) {
                    component.setSource(0, InputSources.constant(col * 50));
                    owner(level, 5, x, y).resample(component, true);
                }
            }
        }
    }

    private static void edgewiseMeters(ServerLevel level) {
        String[] variants = {"standard", "red_pointer"};
        for (int row = 0; row < variants.length; row++) {
            for (int col = 0; col < 3; col++) {
                int x = 2 + (row * 3 + col) * 10;
                PanelComponent component = put(level, 6, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, x, 2, 8, 24, PanelStyles.GE, variants[row]);
                GaugeData data = (GaugeData) component.data();
                data.min = 0;
                data.max = 100;
                data.units = "psi";
                component.setSource(0, InputSources.constant(col * 50));
                owner(level, 6, x, 2).resample(component, true);
            }
        }
        for (int row = 0; row < variants.length; row++) {
            for (int col = 0; col < 3; col++) {
                int x = 2 + col * 26;
                int y = 30 + row * 10;
                PanelComponent component = put(level, 6, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, x, y, 24, 8, PanelStyles.GE, variants[row]);
                GaugeData data = (GaugeData) component.data();
                data.min = 0;
                data.max = 100;
                data.units = "psi";
                component.setSource(0, InputSources.constant(col * 50));
                owner(level, 6, x, y).resample(component, true);
            }
        }
    }

    // ---- checking (client)

    /**
     * Confirms every component of the stage's board draws with a baked model exactly when the style table maps its
     * choice to a model that is loaded, and returns "baked/total" for the log. Throws if any disagree.
     */
    static String verify(Minecraft mc, int stage) {
        int group = GROUP_OF[stage];
        int baked = 0;
        int total = 0;
        List<String> wrong = new ArrayList<>();
        for (int c = 0; c < COLS; c++) {
            for (int r = 0; r < ROWS; r++) {
                if (!(mc.level.getBlockEntity(block(group, c, r)) instanceof PanelBlockEntity be)) {
                    throw new AssertionError("Baked showcase block missing on the client: group " + group + " " + c + "," + r);
                }
                for (PanelComponent component : be.owned()) {
                    var tag = component.data().toTag();
                    String type = component.type().id().getPath();
                    boolean expected = PanelStyles.resolve(type, tag.getString("Style"), tag.getString("Variant"), PanelModels::has) != null;
                    boolean actual = RenderCache.of(component).usesBaked();
                    total++;
                    if (actual) {
                        baked++;
                    }
                    if (expected != actual) {
                        wrong.add(type + "/" + tag.getString("Style") + "/" + tag.getString("Variant") + " expected "
                                + (expected ? "baked" : "classic") + " drew " + (actual ? "baked" : "classic"));
                    }
                }
            }
        }
        if (total == 0 || !wrong.isEmpty()) {
            throw new AssertionError("Baked showcase " + NAMES[stage] + ": " + total + " components; " + wrong);
        }
        return baked + "/" + total;
    }
}
