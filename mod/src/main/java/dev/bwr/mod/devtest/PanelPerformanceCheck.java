package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.PanelRenderer;
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
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Same camera for every stage: an empty baseline, then a loaded 16x8 wall of vertical panel blocks fully
 * populated with a realistic mix of instruments (see {@link #LAYOUTS}: control switches, selectors, key switches,
 * pushbuttons, lamps, knobs, round meters and edgewise meters), once with every component in the classic look and
 * once with the baked Blender models; the same two walls are also measured from beyond the 24-block full-detail
 * range. Select stages with {@code -PbwrPanelPerformanceStages=empty,classic,baked,classic-far,baked-far}
 * (default: those five); {@code annunciators-off} and {@code annunciators-flash} (C2) are the same wall with 50 annunciator
 * windows, normal and then all flashing, and are chosen by name. The frame time of each is logged as {@code PANEL PERFORMANCE}.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelPerformanceCheck {
    /**
     * Stage name, whether it has a wall, the look of its components, how far the camera stands from the wall, and
     * whether 25 of the wall's blocks carry two annunciator windows each in place of their usual layout
     * ({@link #ANN_OFF}: all 50 windows normal; {@link #ANN_FLASH}: all 50 in unacknowledged alarm, flashing).
     */
    private record Stage(String name, boolean wall, String style, int distance, int annunciators, int mimicPaths, int plant) {
        Stage(String name, boolean wall, String style, int distance) {
            this(name, wall, style, distance, 0, 0, 0);
        }

        Stage(String name, boolean wall, String style, int distance, int annunciators) {
            this(name, wall, style, distance, annunciators, 0, 0);
        }

        Stage(String name, boolean wall, String style, int distance, int annunciators, int mimicPaths) {
            this(name, wall, style, distance, annunciators, mimicPaths, 0);
        }
    }

    private static final int ANN_OFF = 1, ANN_FLASH = 2;
    /** A wall of eight plant computers (4 x 3 blocks each) with a dense program: standing still, and with every number changing once a second. */
    private static final int PLANT_IDLE = 1, PLANT_UPDATING = 2;

    private static final List<Stage> ALL = List.of(
            new Stage("empty", false, null, 15),
            new Stage("classic", true, PanelStyles.CLASSIC, 15),
            new Stage("baked", true, null, 15),
            new Stage("classic-far", true, PanelStyles.CLASSIC, 36),
            new Stage("baked-far", true, null, 36),
            // C2: the same wall with 50 annunciator windows, normal and then all flashing (not in the default list below)
            new Stage("annunciators-off", true, null, 15, ANN_OFF),
            new Stage("annunciators-flash", true, null, 15, ANN_FLASH),
            // The same wall as "baked" with 100 mimic tape paths drawn on it (not in the default list): compare in pairs,
            // baked, mimic, baked, mimic (see TESTING.md, "Mimic tape").
            new Stage("mimic", true, null, 15, 0, 100),
            // The plant computer: eight displays, each with 8 numbers, 2 trends, 6 tiles, 2 soft keys (not in the default list):
            // compare with "empty", and "plant-idle" with "plant-updating" for the cost of a change.
            new Stage("plant-idle", true, null, 15, 0, 0, PLANT_IDLE),
            new Stage("plant-updating", true, null, 15, 0, 0, PLANT_UPDATING));

    /** One component of a block layout: type, cells, look (style null: the one named for the layout), live value. */
    private record Spec(ComponentType type, int x, int y, int w, int h, String style, String variant, double value) {
    }

    private static Spec spec(ComponentType type, int x, int y, int w, int h, String style, String variant, double value) {
        return new Spec(type, x, y, w, h, style, variant, value);
    }

    private static final String GE = PanelStyles.GE;
    private static final String W2 = PanelStyles.WESTINGHOUSE;
    private static final ComponentType LAMP = ComponentTypes.LAMP;
    private static final ComponentType PB = ComponentTypes.PUSHBUTTON;
    private static final ComponentType EV = ComponentTypes.EDGEWISE_GAUGE_VERTICAL;
    private static final ComponentType EH = ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL;

    /** Eight block layouts, repeated across the wall: about 5.5 components a block, a dense panel. */
    private static final List<List<Spec>> LAYOUTS = List.of(
            // breaker bay: a GE and a Westinghouse control switch over a row of four indicating lights
            List.of(spec(ComponentTypes.CONTROL_SWITCH, 0, 0, 8, 12, GE, "pistol", -1),
                    spec(ComponentTypes.CONTROL_SWITCH, 8, 0, 8, 12, W2, "pistol", 1),
                    spec(LAMP, 0, 12, 4, 4, GE, "round", 1), spec(LAMP, 4, 12, 4, 4, GE, "round_plate", 0),
                    spec(LAMP, 8, 12, 4, 4, GE, "et16", 1), spec(LAMP, 12, 12, 4, 4, GE, "square", 1)),
            // round meter and a column and a row of lights
            List.of(spec(ComponentTypes.DIAL_GAUGE, 0, 0, 12, 12, GE, "standard", 68),
                    spec(LAMP, 12, 0, 4, 4, GE, "round", 1), spec(LAMP, 12, 4, 4, 4, GE, "round_plate", 1),
                    spec(LAMP, 12, 8, 4, 4, GE, "et16", 0), spec(LAMP, 0, 12, 4, 4, GE, "round", 0),
                    spec(LAMP, 4, 12, 4, 4, GE, "round", 1), spec(LAMP, 8, 12, 4, 4, GE, "square", 1),
                    spec(LAMP, 12, 12, 4, 4, GE, "et16", 1)),
            // two vertical edgewise meters and a column of lights
            List.of(spec(EV, 0, 0, 6, 16, GE, "standard", 68), spec(EV, 6, 0, 6, 16, GE, "red_pointer", 35),
                    spec(LAMP, 12, 0, 4, 4, GE, "round", 1), spec(LAMP, 12, 4, 4, 4, GE, "round", 0),
                    spec(LAMP, 12, 8, 4, 4, GE, "round_plate", 1), spec(LAMP, 12, 12, 4, 4, GE, "et16", 1)),
            // two horizontal edgewise meters over a row of lights
            List.of(spec(EH, 0, 0, 16, 6, GE, "standard", 68), spec(EH, 0, 6, 16, 6, GE, "red_pointer", 80),
                    spec(LAMP, 0, 12, 4, 4, GE, "round", 1), spec(LAMP, 4, 12, 4, 4, GE, "round", 0),
                    spec(LAMP, 8, 12, 4, 4, GE, "round_plate", 1), spec(LAMP, 12, 12, 4, 4, GE, "square", 0)),
            // GE selectors over two pushbuttons
            List.of(spec(ComponentTypes.ROTARY_SELECTOR, 0, 0, 8, 10, GE, "oval", 1),
                    spec(ComponentTypes.ROTARY_SELECTOR, 8, 0, 8, 10, GE, "knurled", 2),
                    spec(PB, 0, 10, 6, 6, GE, "flush", 0), spec(PB, 8, 10, 6, 6, GE, "mushroom", 0)),
            // Westinghouse selectors over a row of lights
            List.of(spec(ComponentTypes.ROTARY_SELECTOR, 0, 0, 8, 10, W2, "oval", 2),
                    spec(ComponentTypes.ROTARY_SELECTOR, 8, 0, 8, 10, W2, "round_notched", 0),
                    spec(LAMP, 0, 12, 4, 4, GE, "round", 1), spec(LAMP, 4, 12, 4, 4, GE, "round_plate", 1),
                    spec(LAMP, 8, 12, 4, 4, GE, "et16", 0), spec(LAMP, 12, 12, 4, 4, GE, "square", 1)),
            // four pushbuttons
            List.of(spec(PB, 0, 0, 8, 8, GE, "flush", 0), spec(PB, 8, 0, 8, 8, GE, "extended", 0),
                    spec(PB, 0, 8, 8, 8, GE, "illuminated", 1), spec(PB, 8, 8, 8, 8, GE, "guarded", 0)),
            // two key switches over two setpoint knobs
            List.of(spec(ComponentTypes.KEY_SWITCH, 0, 0, 6, 8, GE, "key", 1),
                    spec(ComponentTypes.KEY_SWITCH, 8, 0, 6, 8, W2, "key", 0),
                    spec(ComponentTypes.SETPOINT_KNOB, 0, 8, 8, 8, GE, "fluted", 30),
                    spec(ComponentTypes.SETPOINT_KNOB, 8, 8, 8, 8, GE, "fluted", 70)));

    private static final String OPTION = System.getProperty("bwr.panelPerformanceStages", "");
    private static final List<Stage> STAGES = chosen();
    private static boolean started, requested;
    private static int age, joined, stage;
    private static long began, lastFrame;
    private static final ArrayList<Double> FRAME_MS = new ArrayList<>();

    private PanelPerformanceCheck() {}

    private static List<Stage> chosen() {
        if (OPTION.isBlank()) return ALL.subList(0, 5);
        List<Stage> picked = new ArrayList<>();
        for (String name : OPTION.split(",")) {
            Stage found = null;
            for (Stage candidate : ALL) if (candidate.name().equals(name.trim())) found = candidate;
            if (found == null) throw new IllegalArgumentException("Unknown panel performance stage '" + name + "'");
            picked.add(found);
        }
        return picked;
    }

    /**
     * World x/z offset of the wall of stage {@code index}: each stage builds in its own area, thousands of blocks
     * from where the player stands, so the wall arrives with its chunks rather than as block updates.
     */
    private static int offset(int index) {
        return (index + 1) * 5000;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.panelPerformanceCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 400 + 900 * STAGES.size()) throw new IllegalStateException("Panel benchmark timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.options.renderDistance().set(8);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-panel-performance-" + System.currentTimeMillis(),
                    new LevelSettings("Panel benchmark", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        RenderEnvironment.log("panel-performance");
        if (stage >= STAGES.size()) return; // the last stage asked the client to stop; ticks may still arrive
        Stage current = STAGES.get(stage);
        if (!requested) {
            requested = true;
            lastFrame = 0;
            FRAME_MS.clear();
            began = System.nanoTime();
            int offset = offset(stage);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var level = player.serverLevel();
                for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(10 + offset, 117, offset),
                        new BlockPos(38 + offset, 117, 45 + offset)))
                    level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                if (current.plant() > 0) buildPlant(level, new BlockPos(32 + offset, 125, 40 + offset));
                else if (current.wall()) build(level, new BlockPos(32 + offset, 125, 40 + offset), current.style(), current.annunciators());
                if (current.mimicPaths() > 0) {
                    BlockPos root = new BlockPos(32 + offset, 125, 40 + offset);
                    int placed = MimicClientCheck.scatter(level, root, 16, 8, current.mimicPaths());
                    for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
                        if (level.getBlockEntity(root.relative(Direction.WEST, col).below(row)) instanceof PanelBlockEntity be) be.syncFull();
                    }
                    LogUtils.getLogger().info("PANEL PERFORMANCE mimic paths placed: {}", placed);
                }
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(6000);
                // The near stages keep every block inside the 24-block full-detail range; the far ones sit outside it.
                player.connection.teleport(24 + offset, 124, 40 + offset - current.distance(), 0, 0);
            });
            mc.options.hideGui = true;
        }
        if (current.plant() == PLANT_UPDATING && age % 20 == 0 && System.nanoTime() - began > 2_000_000_000L) {
            // Every number of every display moves once a second, as a program that redraws its values would.
            int step = age / 20;
            mc.getSingleplayerServer().execute(() -> updatePlant(step));
        }
        if (System.nanoTime() - began < 11_000_000_000L) return;
        if (FRAME_MS.size() < 100) throw new AssertionError("Too few frames for panel benchmark: " + FRAME_MS.size());
        Collections.sort(FRAME_MS);
        double sum = 0;
        for (double ms : FRAME_MS) sum += ms;
        int n = FRAME_MS.size();
        double mean = sum / n;
        LogUtils.getLogger().info("PANEL PERFORMANCE stage={} frames={} meanMs={} p50Ms={} p95Ms={} fps={} active={}",
                current.name(), n, mean, FRAME_MS.get(n / 2), FRAME_MS.get((int) (n * .95)), 1000 / mean, mc.isWindowActive());
        LogUtils.getLogger().info("PANEL PERFORMANCE stage={} gpu cache: {} blocks hold {} quads, {} buffers built so far in {} ms",
                current.name(), PanelRenderer.debugCachedBlocks(), PanelRenderer.debugCachedQuads(), PanelRenderer.debugRebuilds(),
                PanelRenderer.debugRebuildMillis());
        if (current.wall() && !Boolean.getBoolean("bwr.panelImmediate") && PanelRenderer.debugCachedBlocks() == 0) {
            // A wall that is drawn must be drawn from block buffers, shader pack or not: without them the baked wall costs
            // 30 to 40 ms a frame (it did under every shader pack, which switched the cache off).
            throw new AssertionError("Panel wall stage " + current.name() + " was drawn without block buffers (shader pack in use: "
                    + dev.bwr.mod.client.VisualEffectsMode.shaderPackInUse() + ")");
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("panel-performance");
            Files.createDirectories(directory);
            CheckShots.save(mc,directory.resolve(current.name() + ".png"));
        } catch (IOException ex) {
            throw new IllegalStateException("Panel benchmark screenshot failed", ex);
        }
        requested = false;
        if (++stage == STAGES.size()) {
            LogUtils.getLogger().info("PANEL PERFORMANCE COMPLETE");
            mc.stop();
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.panelPerformanceCheck") || !requested) return;
        long now = System.nanoTime();
        if (now - began > 3_000_000_000L && lastFrame != 0) {
            double delta = (now - lastFrame) / 1_000_000.0;
            if (delta > 0 && delta < 500) FRAME_MS.add(delta);
        }
        lastFrame = now;
    }

    private static final List<dev.bwr.mod.panel.component.PlantComputerData> PLANT_DISPLAYS = new ArrayList<>();

    /** Eight plant computers on a 16 x 6 block wall: the program of {@code PlantComputerTests.richProgram} on page 1. */
    private static void buildPlant(net.minecraft.server.level.ServerLevel level, BlockPos root) {
        PLANT_DISPLAYS.clear();
        for (int row = 0; row < 6; row++) for (int col = 0; col < 16; col++) {
            level.setBlock(root.relative(Direction.WEST, col).below(row), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        }
        for (int j = 0; j < 2; j++) for (int i = 0; i < 4; i++) {
            BlockPos anchor = root.relative(Direction.WEST, 4 * i).below(3 * j);
            PanelComponent display = PanelOps.placeDirect(level, anchor, 0, 0, ComponentTypes.PLANT_COMPUTER, Footprint.of(64, 48));
            if (display == null) throw new AssertionError("Panel benchmark plant computer placement failed at " + anchor);
            var data = (dev.bwr.mod.panel.component.PlantComputerData) display.data();
            PlantComputerTests.richProgram(data);
            dev.bwr.mod.panel.plant.PlantApi.setPage(data, 1);
            PLANT_DISPLAYS.add(data);
        }
        for (int row = 0; row < 6; row++) for (int col = 0; col < 16; col++) {
            if (level.getBlockEntity(root.relative(Direction.WEST, col).below(row)) instanceof PanelBlockEntity be) be.syncFull();
        }
        LogUtils.getLogger().info("PANEL PERFORMANCE plant wall built: {} plant computers, {} elements each", PLANT_DISPLAYS.size(),
                PLANT_DISPLAYS.get(0).program().elementCount());
    }

    private static void updatePlant(int step) {
        for (int i = 0; i < PLANT_DISPLAYS.size(); i++) {
            var data = PLANT_DISPLAYS.get(i);
            try {
                dev.bwr.mod.panel.plant.PlantApi.value(data, 1, "pw", 60.0 + (step * 7 + i * 3) % 50 + 0.1 * (step % 10));
                dev.bwr.mod.panel.plant.PlantApi.value(data, 1, "lv", -50.0 + (step * 11 + i) % 90);
                dev.bwr.mod.panel.plant.PlantApi.push(data, 1, "tr", 800.0 + 300.0 * Math.sin(step * 0.3 + i));
            } catch (dev.bwr.mod.panel.plant.PlantState.Rejected e) {
                throw new IllegalStateException("Panel benchmark plant update refused: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Fills a 16 x 8 block wall facing north whose top-right block (as the operator sees it) is {@code root} with
     * the block layouts, every component in {@code styleOverride} when given, else in the look its layout names.
     */
    private static void build(net.minecraft.server.level.ServerLevel level, BlockPos root, String styleOverride, int annunciators) {
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            var pos = root.relative(Direction.WEST, col).below(row);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        }
        int placed = 0;
        int windows = 0;
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            var pos = root.relative(Direction.WEST, col).below(row);
            if (annunciators != 0 && (col + row * 16) % 5 == 0 && windows < 50) {
                // two 12 x 8 windows stacked in this block, 25 blocks, 50 windows
                for (int k = 0; k < 2; k++) {
                    PanelComponent window = PanelOps.placeDirect(level, pos, 0, 8 * k, ComponentTypes.ANNUNCIATOR, Footprint.of(12, 8));
                    if (window == null) throw new AssertionError("Panel benchmark annunciator placement failed at " + pos);
                    var data = (dev.bwr.mod.panel.component.AnnunciatorData) window.data();
                    data.line1 = "HI PRESS";
                    data.line2 = "RPV " + (windows + 1);
                    data.color = windows % 3 == 0 ? 0xFFB000 : windows % 3 == 1 ? 0xE03020 : 0xF0F0F0;
                    window.setSource(0, InputSources.constant(annunciators == ANN_FLASH ? 1.0 : 0.0));
                    ((PanelBlockEntity) level.getBlockEntity(pos)).resample(window, true);
                    window.touch();
                    windows++;
                    placed++;
                }
                continue;
            }
            for (Spec spec : LAYOUTS.get((col + row * 3) % LAYOUTS.size())) {
                PanelComponent component = PanelOps.placeDirect(level, pos, spec.x(), spec.y(), spec.type(),
                        Footprint.of(spec.w(), spec.h()));
                if (component == null)
                    throw new AssertionError("Panel benchmark placement failed at " + pos + " for " + spec.type().id());
                PanelModelShowcase.look(component, styleOverride != null ? styleOverride : spec.style(), spec.variant());
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
                    ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
                }
                component.touch();
                placed++;
            }
        }
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            if (level.getBlockEntity(root.relative(Direction.WEST, col).below(row)) instanceof PanelBlockEntity be) be.syncFull();
        }
        LogUtils.getLogger().info("PANEL PERFORMANCE wall built: {} components in 128 blocks, style={}, {} annunciator windows ({})", placed,
                styleOverride == null ? "baked" : styleOverride, windows, annunciators == ANN_FLASH ? "flashing" : annunciators == ANN_OFF ? "normal" : "none");
    }
}
