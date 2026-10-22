package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PlantComputerData;
import dev.bwr.mod.panel.plant.PlantApi;
import dev.bwr.mod.panel.plant.PlantElement;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plant computer part of the panel client check: each display is a 4 x 3 block (64 x 48 cell) plant computer on its own
 * wall, photographed straight on from 3.5 blocks.
 *
 * <ul>
 *   <li>{@code plant-default-overview}, {@code -trends}, {@code -flows}: the built-in pages, bound to a real formed reactor
 *       staged at 80 percent power and run through a feedwater upset, so the trends are that reactor's own history (the
 *       trend page is given the history by running the very sampler the display uses, one point a simulated second);</li>
 *   <li>{@code plant-spds-safety}, {@code -trends}, {@code -values}: the SPDS program of {@code docs/lua/spds.lua}, its
 *       pages defined with the same {@link PlantApi} calls the {@code bwr_panel} peripheral makes for a program (the
 *       element tables are the script's), the safety function tiles in a mixed state and the trends carrying that history;</li>
 *   <li>{@code plant-no-program}: a display nothing has drawn on;</li>
 *   <li>{@code plant-spds-fine-text}: the SPDS safety page at 50 percent Text size, a 62 x 30 character grid.</li>
 * </ul>
 * The real Lua script is run on a real CC computer by {@code runLuaExamplesCheck}; this stage is about what the screen looks like.
 *
 * <p>Plan: the displays in a row, the first at x 5800 (columns run west, 6 blocks apart), y 130 (rows run down), z 5300, facing
 * north, on a floor at y 125; a reactor high above at 5830, 170, 5270.
 */
final class PanelPlantShowcase {
    private PanelPlantShowcase() {
    }

    static final String[] NAMES = {"plant-default-overview", "plant-default-trends", "plant-default-flows", "plant-spds-safety",
            "plant-spds-trends", "plant-spds-values", "plant-no-program", "plant-spds-fine-text"};
    private static final BlockPos FIRST = new BlockPos(5800, 130, 5300);
    private static final BlockPos REACTOR_MIN = new BlockPos(5830, 170, 5270);
    private static final int SPACING = 6;
    private static final List<BlockPos> ANCHORS = new ArrayList<>();
    private static final List<PanelComponent> DISPLAYS = new ArrayList<>();

    private static BlockPos anchor(int index) {
        return FIRST.relative(Direction.WEST, index * SPACING);
    }

    /** The camera's feet: the eye is at the middle of the display, 3.5 blocks in front of it (the player's eye is 1.62 above). */
    static Vec3 camera(int stage) {
        BlockPos a = anchor(stage);
        return new Vec3(a.getX() - 1.0, 129.5 - 1.62, a.getZ() - 3.5);
    }

    static Vec3 target(int stage) {
        BlockPos a = anchor(stage);
        return new Vec3(a.getX() - 1.0, 129.5, a.getZ() + 0.5);
    }

    private static final String[] FUNCTIONS = {"REACTIVITY", "CORE COOLING", "HEAT SINK", "RPV INTEGRITY", "CONTAINMENT", "INVENTORY"};
    private static final double[] POWER = new double[60];
    private static final double[] LEVEL = new double[60];
    private static final double[] PRESSURE = new double[60];
    private static final double[] CONTAINMENT = new double[60];

    static void build(ServerLevel level) {
        for (int cx = 5750 >> 4; cx <= 5850 >> 4; cx++) {
            for (int cz = 5260 >> 4; cz <= 5310 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        ANCHORS.clear();
        DISPLAYS.clear();
        for (int x = 5752; x <= 5806; x++) {
            for (int z = 5290; z <= 5306; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 134; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        ReactorControllerBlockEntity reactor = reactor(level);
        var panel = BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH);
        for (int i = 0; i < NAMES.length; i++) {
            BlockPos first = anchor(i);
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) {
                    level.setBlock(first.relative(Direction.WEST, c).below(r), panel, 3);
                }
            }
            PanelComponent display = PanelOps.placeDirect(level, first, 0, 0, ComponentTypes.PLANT_COMPUTER, Footprint.of(64, 48));
            if (display == null) {
                throw new AssertionError("Plant showcase: the display " + NAMES[i] + " was refused");
            }
            display.setLabel("PC-" + (i + 1));
            ANCHORS.add(first);
            DISPLAYS.add(display);
        }
        builtin(level, reactor, 0, "overview");
        builtin(level, reactor, 1, "trends");
        builtin(level, reactor, 2, "flows");
        spds(3, 1, 100);
        spds(4, 2, 100);
        spds(5, 3, 100);
        spds(7, 1, 50);
        // (display 6, plant-no-program, is left empty)
        for (BlockPos first : ANCHORS) {
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) {
                    if (level.getBlockEntity(first.relative(Direction.WEST, c).below(r)) instanceof PanelBlockEntity be) {
                        be.syncFull();
                    }
                }
            }
        }
    }

    // ---- the plant

    /** One tick of the stand-in turbine and feedwater pump, then of the core; {@code feed} scales the feedwater. */
    private static void plantStep(ReactorCore core, double feed, double steam) {
        core.setRecirculationFlowFraction(0.0);
        double rise = Saturation.vapourEnthalpyKJPerKg(core.getPressurePsia())
                - core.getPressureVessel().getFeedwaterEnthalpyKJPerKg();
        double flow = core.getThermalPowerMW() * 1000.0 / rise;
        core.setTurbineSteamFlowKgPerS(flow * steam);
        core.setFeedwaterFlowKgPerS(flow * feed);
        core.step();
    }

    /** A real formed reactor at 80 percent power with a feedwater upset behind it; its history fills {@link #POWER} and the others. */
    private static ReactorControllerBlockEntity reactor(ServerLevel level) {
        ReactorControllerBlockEntity reactor;
        try {
            reactor = CompactCoreRegressionTests.build(level, REACTOR_MIN, 15, 15, false);
        } catch (Exception e) {
            throw new AssertionError("Plant showcase: the reactor could not be built", e);
        }
        if (!reactor.isFormed()) {
            throw new AssertionError("Plant showcase: the reactor did not form: " + reactor.statusLines());
        }
        FcdTestSupport.supplyDrives(level, reactor);
        for (int position : reactor.corePositions()) {
            reactor.loadAssembly(position, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
        }
        ReactorCore core = reactor.core();
        core.initialiseAtTotalPowerFraction(0.8);
        reactor.rods().setCore(core);
        for (int i = 0; i < 2400; i++) {
            plantStep(core, 1.0, 1.0);
        }
        var signals = dev.bwr.mod.signal.SignalProviders.of(reactor);
        for (int point = 0; point < POWER.length; point++) {
            // a feedwater upset in the middle of the history: 12 percent short for 15 points, then restored (a bigger one collapses
            // this fixture's plant: it has no recirculation pumps)
            double steam = 1.0;
            double feed = point >= 22 && point < 37 ? 0.88 : 1.0;
            for (int i = 0; i < 20; i++) {
                plantStep(core, feed, steam);
            }
            POWER[point] = signals.read("power").orElse(0.0) * 100.0;
            LEVEL[point] = signals.read("level").orElse(0.0);
            PRESSURE[point] = signals.read("pressure").orElse(0.0);
            CONTAINMENT[point] = signals.read("containment_pressure").orElse(0.0);
        }
        LogUtils.getLogger().info("PANEL CLIENT CHECK plant reactor: power {} %, level {} in, pressure {} psig, containment {} psig",
                POWER[POWER.length - 1], LEVEL[LEVEL.length - 1], PRESSURE[PRESSURE.length - 1], CONTAINMENT[CONTAINMENT.length - 1]);
        return reactor;
    }

    // ---- the built-in pages

    private static void builtin(ServerLevel level, ReactorControllerBlockEntity reactor, int index, String mode) {
        PanelComponent display = DISPLAYS.get(index);
        BlockPos anchor = ANCHORS.get(index);
        PlantComputerData data = (PlantComputerData) display.data();
        data.mode = mode;
        data.trendSeconds = 1;
        display.setSource(0, new PlantSource(reactor.getBlockPos(), "formed"));
        PanelBlockEntity owner = (PanelBlockEntity) level.getBlockEntity(anchor);
        owner.resample(display, true);
        if (!data.builtin().element(0, "st").text.startsWith("SCRAM")) {
            throw new AssertionError("Plant showcase: the built-in page did not bind to the reactor: " + data.builtin().element(0, "st").text);
        }
        // the history a display would have collected at one point a second, a trend step of one second
        if (mode.equals("trends")) {
            var readings = PlantComputerData.readings(reactor);
            int n = POWER.length;
            for (int p = 0; p < n; p++) {
                float[] values = {(float) POWER[p], (float) PRESSURE[p], (float) LEVEL[p], readings.read("core_flow") * 100.0f,
                        (float) CONTAINMENT[p], readings.read("steam_outlet_flow")};
                for (int t = 0; t < values.length; t++) {
                    if (data.builtin().element(1, "t" + t) != null) {
                        data.builtin().push(1, "t" + t, values[t]);
                    }
                }
            }
        }
        data.builtin().markAllChanged();
        owner.syncFull();
    }

    // ---- the SPDS program

    private static Map<String, Object> spec(Object... keyValues) {
        return PlantComputerTests.spec(keyValues);
    }

    /**
     * The pages of docs/lua/spds.lua, in the script's own element tables, with the safety functions in a mixed state (heat sink
     * amber, containment red, inventory amber) and the history of the reactor in the trends. {@code page} is the page shown.
     */
    private static void spds(int display, int page, int textSize) {
        PlantComputerData data = (PlantComputerData) DISPLAYS.get(display).data();
        data.textSize = textSize;
        int cols = data.layout().cols();
        int rows = data.layout().rows();
        double taf = dev.bwr.core.PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN;
        PlantApi.clear(data);
        PlantApi.title(data, 1, "SAFETY FUNCTIONS");
        int tileW = cols / 3;
        int tileH = rows >= 15 ? 3 : 2;
        String[] states = {"ok", "ok", "warn", "ok", "alarm", "warn"};
        for (int i = 1; i <= 6; i++) {
            int column = (i - 1) % 3;
            int line = (i - 1) / 3;
            PlantApi.set(data, 1, "csf" + i, spec("type", "tile", "col", 1 + column * tileW, "row", 2 + line * tileH,
                    "w", column == 2 ? cols - 2 * tileW : tileW, "h", tileH, "text", FUNCTIONS[i - 1], "state", states[i - 1]));
        }
        int messageRow = 2 + 2 * tileH;
        PlantApi.set(data, 1, "msg", spec("type", "text", "col", 1, "row", messageRow, "text", "CONTAINMENT: PRESSURE HIGH", "color", "red"));
        int trendRow = messageRow + 1;
        PlantApi.set(data, 1, "pwr1", spec("type", "trend", "col", 1, "row", trendRow, "w", cols, "h", rows - trendRow, "text", "POWER",
                "unit", "%", "min", 0.0, "max", 120.0, "decimals", 1, "color", "cyan"));
        keys(data, 1);
        PlantApi.title(data, 2, "TRENDS");
        int halfW = cols / 2;
        int halfH = (rows - 2) / 2;
        Object[][] trends = {{"pwr", "POWER", "%", 0.0, 120.0, 1}, {"lvl", "LEVEL", "in", -200.0, 100.0, 1},
                {"prs", "PRESSURE", "psig", 0.0, 1400.0, 0}, {"cnt", "CONT PRESS", "psig", 0.0, 60.0, 1}};
        for (int i = 0; i < trends.length; i++) {
            int column = i % 2;
            int line = i / 2;
            PlantApi.set(data, 2, (String) trends[i][0], spec("type", "trend", "col", 1 + column * halfW, "row", 2 + line * halfH,
                    "w", column == 1 ? cols - halfW : halfW, "h", halfH, "text", trends[i][1], "unit", trends[i][2], "min", trends[i][3],
                    "max", trends[i][4], "decimals", (double) (int) trends[i][5], "color", "cyan"));
        }
        keys(data, 2);
        PlantApi.title(data, 3, "VALUES");
        double lastPower = POWER[POWER.length - 1];
        double levelNow = LEVEL[LEVEL.length - 1];
        PlantApi.set(data, 3, "power", spec("type", "field", "col", 1, "row", 2, "w", cols, "text", "POWER", "unit", "%", "warnHigh", 105.0, "alarmHigh", 120.0, "value", lastPower));
        PlantApi.set(data, 3, "neutron", spec("type", "field", "col", 1, "row", 3, "w", cols, "text", "NEUTRON POWER", "unit", "%", "warnHigh", 105.0, "alarmHigh", 120.0, "value", lastPower - 6.5));
        PlantApi.set(data, 3, "level", spec("type", "bar", "col", 1, "row", 4, "w", cols, "text", "LEVEL", "unit", "in", "min", -200.0, "max", 100.0,
                "warnLow", taf + 120, "alarmLow", taf + 20, "color", "cyan", "value", LEVEL[LEVEL.length - 1]));
        PlantApi.set(data, 3, "pressure", spec("type", "bar", "col", 1, "row", 5, "w", cols, "text", "PRESSURE", "unit", "psig", "min", 0.0, "max", 1400.0, "decimals", 0.0,
                "color", "cyan", "warnHigh", 1100.0, "alarmHigh", 1250.0, "value", PRESSURE[PRESSURE.length - 1]));
        PlantApi.set(data, 3, "clad", spec("type", "field", "col", 1, "row", 6, "w", cols, "text", "CLAD TEMP", "unit", "C", "decimals", 0.0, "warnHigh", 800.0, "alarmHigh", 1204.0, "value", 311.0));
        PlantApi.set(data, 3, "cont", spec("type", "field", "col", 1, "row", 7, "w", cols, "text", "CONT PRESS", "unit", "psig", "warnHigh", 2.0, "alarmHigh", 15.0, "value", 17.2));
        PlantApi.set(data, 3, "h2", spec("type", "field", "col", 1, "row", 8, "w", cols, "text", "CONT H2", "unit", "%", "warnHigh", 4.0, "alarmHigh", 6.0, "value", 1.3));
        PlantApi.set(data, 3, "cool", spec("type", "field", "col", 1, "row", 9, "w", cols, "text", "COOLDOWN", "unit", "C/h", "warnHigh", 55.6, "value", 12.0));
        keys(data, 3);
        for (int p = 0; p < POWER.length; p++) {
            data.setClock(100_000L + p * 100L); // the rate limit refills between the program's frames
            PlantApi.push(data, 1, "pwr1", POWER[p]);
            PlantApi.push(data, 2, "pwr", POWER[p]);
            PlantApi.push(data, 2, "lvl", LEVEL[p]);
            PlantApi.push(data, 2, "prs", PRESSURE[p]);
            PlantApi.push(data, 2, "cnt", CONTAINMENT[p] + 14.0 * p / POWER.length);
        }
        PlantApi.setPage(data, page);
    }

    private static void keys(PlantComputerData data, int page) {
        PlantApi.set(data, page, "k1", spec("type", "key", "key", 1, "text", "SAFETY"));
        PlantApi.set(data, page, "k2", spec("type", "key", "key", 2, "text", "TRENDS"));
        PlantApi.set(data, page, "k3", spec("type", "key", "key", 3, "text", "VALUES"));
    }

    /** Every display is on the client with its content: "displays, elements". */
    static String verify(Minecraft mc, int stage) {
        int elements = 0;
        int shown = 0;
        for (int i = 0; i < ANCHORS.size(); i++) {
            if (mc.level.getBlockEntity(ANCHORS.get(i)) instanceof PanelBlockEntity be && be.component(DISPLAYS.get(i).id()) != null
                    && be.component(DISPLAYS.get(i).id()).data() instanceof PlantComputerData data) {
                shown++;
                for (PlantElement e : data.shown().all()) {
                    elements++;
                }
                if (i == 6 ? !data.shown().isEmpty() : data.shown().isEmpty()) {
                    throw new AssertionError("Plant showcase " + NAMES[stage] + ": display " + (i + 1) + " has "
                            + data.shown().elementCount() + " elements on the client");
                }
            }
        }
        if (shown != NAMES.length) {
            throw new AssertionError("Plant showcase " + NAMES[stage] + ": " + shown + " of " + NAMES.length + " displays on the client");
        }
        return shown + " displays, " + elements + " elements";
    }
}
