package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControllerStationData;
import dev.bwr.mod.panel.component.ControllerStationType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.JoystickData;
import dev.bwr.mod.panel.component.LedRodDisplayData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SynchroscopeType;
import dev.bwr.mod.panel.component.TwoPenRecorderData;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The control hardware part of the panel client check: the manual/auto controller station, the synchroscope, the two pen
 * recorder, the joystick bypass switch and the LED rod pattern display, each at its default size (twice real), at its exact
 * real size and at a big size, operated: stations in MAN with RAISE held and in AUTO, pointers off zero, lamps lit, a
 * recorder with two traces, joysticks in every position, and an LED display with a rod pattern (read from a real formed reactor, built high above the floor).
 *
 * <p>Plan: a wall of 4 x 3 vertical panel blocks, the first at x 5516, y 128, z 5291 (columns run west, rows down), and a wall
 * of 3 x 4 (the first at y 129) for the LED displays, the first at x 5528; both face north on a floor at y 125. The area x 5500..5532, z 5280..5296 is
 * cleared first.
 */
final class PanelHardwareShowcase {
    private PanelHardwareShowcase() {
    }

    static final String[] NAMES = {"hardware-board", "hardware-station-default", "hardware-station-real", "hardware-instruments",
            "hardware-joysticks", "hardware-led", "hardware-station-man-out40", "hardware-station-auto-tracking",
            "hardware-sync-slow-fast-sync-tied", "hardware-station-button-legends", "hardware-sync-lamps-closeup"};
    private static final BlockPos WALL = new BlockPos(5516, 128, 5291);
    /** The controller check wall (see {@link #controllerCheck}): a block of two stations and a block of four synchroscopes, west of the first wall's gap. */
    private static final BlockPos CHECK_WALL = new BlockPos(5524, 128, 5291);
    private static final BlockPos LED_WALL = new BlockPos(5528, 129, 5291);
    private static final double[][] EYE = {{5515.0, 127.5, 5287.4}, {5516.73, 128.6, 5289.8}, {5516.73, 127.65, 5290.4},
            {5515.0, 128.3, 5288.7}, {5513.5, 128.0, 5288.9}, {5527.5, 128.0, 5286.4},
            // The controller check: the station block, the same with the AUTO station, the synchroscope block, the station's buttons, the lamps.
            {5524.81, 128.62, 5290.5}, {5524.375, 128.62, 5290.5}, {5523.5, 128.5, 5290.4}, {5524.81, 128.40, 5290.68}, {5523.81, 128.56, 5290.68}};
    private static final double[][] LOOK = {{5515.0, 127.5, 5292.0}, {5516.73, 128.6, 5292.0}, {5516.73, 127.65, 5292.0},
            {5515.0, 128.3, 5292.0}, {5513.5, 128.0, 5292.0}, {5527.5, 128.0, 5292.0},
            {5524.81, 128.62, 5292.0}, {5524.375, 128.62, 5292.0}, {5523.5, 128.5, 5292.0}, {5524.81, 128.40, 5292.0}, {5523.81, 128.56, 5292.0}};

    static Vec3 camera(int stage) {
        return new Vec3(EYE[stage][0], EYE[stage][1] - 1.62, EYE[stage][2]);
    }

    static Vec3 target(int stage) {
        return new Vec3(LOOK[stage][0], LOOK[stage][1], LOOK[stage][2]);
    }

    private static final List<BlockPos> PLACED = new ArrayList<>();
    private static int mounted;

    private static BlockPos block(BlockPos first, int col, int row) {
        return first.relative(Direction.NORTH.getCounterClockWise(), col).below(row);
    }

    private static PanelComponent put(ServerLevel level, BlockPos pos, int x, int y, ComponentType type, Footprint size) {
        PanelComponent component = PanelOps.placeDirect(level, pos, x, y, type, size);
        if (component == null) {
            throw new AssertionError("Hardware showcase: placement refused " + type.id() + " " + size + " at " + pos + " " + x + "," + y);
        }
        mounted++;
        return component;
    }

    private static PanelComponent defaultSize(ServerLevel level, BlockPos pos, int x, int y, ComponentType type) {
        return put(level, pos, x, y, type, type.footprints().get(type.realDefaultIndex()));
    }

    private static void bind(ServerLevel level, BlockPos pos, PanelComponent component, int input, double value) {
        component.setSource(input, InputSources.constant(value));
        ((PanelBlockEntity) level.getBlockEntity(pos)).resample(component, true);
    }

    private static PanelBlockEntity be(ServerLevel level, BlockPos pos) {
        return (PanelBlockEntity) level.getBlockEntity(pos);
    }

    static void build(ServerLevel level) {
        // Keep the walls and the reactor loaded for the whole check: an LED display of an unloaded reactor goes dark, and a chunk
        // that unloads and loads again would leave it so.
        for (int cx = 5500 >> 4; cx <= 5560 >> 4; cx++) {
            for (int cz = 5265 >> 4; cz <= 5300 >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        PLACED.clear();
        mounted = 0;
        for (int x = 5500; x <= 5532; x++) {
            for (int z = 5280; z <= 5296; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 133; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        var panel = BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 4; c++) {
                level.setBlock(block(WALL, c, r), panel, 3);
                PLACED.add(block(WALL, c, r));
            }
        }
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 3; c++) {
                level.setBlock(block(LED_WALL, c, r), panel, 3);
                PLACED.add(block(LED_WALL, c, r));
            }
        }
        stations(level);
        controllerCheck(level);
        instruments(level);
        joysticks(level);
        leds(level);
        for (BlockPos pos : PLACED) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                be.syncFull();
            }
        }
    }

    private static void station(ServerLevel level, BlockPos pos, PanelComponent c, boolean auto, double demand, double pv, Double setpoint,
                                double autoDemand, int held) {
        ControllerStationData data = (ControllerStationData) c.data();
        data.auto = auto;
        data.demand = demand;
        data.units = "MPa";
        data.min = 0;
        data.max = 10;
        data.spFraction = 0.62;
        c.type().onLoaded(c);
        bind(level, pos, c, ControllerStationType.IN_PV, pv);
        if (setpoint != null) {
            bind(level, pos, c, ControllerStationType.IN_SETPOINT, setpoint);
        }
        bind(level, pos, c, ControllerStationType.IN_AUTO_DEMAND, autoDemand);
        if (held > 0) {
            be(level, pos).setOutput(c, held == 1 ? ControllerStationType.OUT_RAISE : ControllerStationType.OUT_LOWER, 1.0);
        }
        c.touch();
    }

    /** Stations: default size (6 x 12) MAN with RAISE held and AUTO; exact real (3 x 6, 2 x 4); a big 8 x 16. */
    private static void stations(ServerLevel level) {
        BlockPos a = block(WALL, 0, 0);
        station(level, a, defaultSize(level, a, 0, 0, ComponentTypes.CONTROLLER_STATION), false, 63, 5.1, null, 70, 1);
        station(level, a, defaultSize(level, a, 7, 0, ComponentTypes.CONTROLLER_STATION), true, 30, 4.4, 6.0, 72, 0);
        BlockPos b = block(WALL, 0, 1);
        station(level, b, put(level, b, 0, 0, ComponentTypes.CONTROLLER_STATION, Footprint.of(3, 6)), false, 63, 5.1, null, 70, 2);
        station(level, b, put(level, b, 4, 0, ComponentTypes.CONTROLLER_STATION, Footprint.of(3, 6)), true, 30, 7.4, 6.0, 72, 0);
        station(level, b, put(level, b, 8, 0, ComponentTypes.CONTROLLER_STATION, Footprint.of(2, 4)), true, 55, 5.1, null, 88, 0);
        station(level, b, put(level, b, 11, 0, ComponentTypes.CONTROLLER_STATION, Footprint.of(2, 4)), false, 40, 3.0, 6.0, 70, 0);
        BlockPos big = block(WALL, 0, 2);
        station(level, big, put(level, big, 0, 0, ComponentTypes.CONTROLLER_STATION, Footprint.of(8, 16)), true, 45, 5.8, 6.2, 66, 0);
    }

    /**
     * The owner's controller and synchroscope check. A station block: the default size station in MAN with OUT at 40 % (PV 70,
     * the local setpoint at mid scale) and one in AUTO tracking (SP 62, PV 61.4, the bar showing a bound demand of 55). A
     * synchroscope block with four instruments at their default size: a slow generator (phase -70), a fast one (phase +70), in
     * phase (phase 2, the green mark: the dark lamp out, the bright lamp full) and tied (the breaker closed: no phase and no
     * slip, the pointer at SYNC behind its flag).
     */
    private static void controllerCheck(ServerLevel level) {
        BlockPos stations = block(CHECK_WALL, 0, 0);
        level.setBlock(stations, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
        PLACED.add(stations);
        PanelComponent man = defaultSize(level, stations, 0, 0, ComponentTypes.CONTROLLER_STATION);
        ((ControllerStationData) man.data()).demand = 40.0;
        man.type().onLoaded(man);
        bind(level, stations, man, ControllerStationType.IN_PV, 70.0);
        man.touch();
        PanelComponent auto = defaultSize(level, stations, 7, 0, ComponentTypes.CONTROLLER_STATION);
        ControllerStationData autoData = (ControllerStationData) auto.data();
        autoData.auto = true;
        autoData.demand = 40.0;
        autoData.spFraction = 0.62;
        auto.type().onLoaded(auto);
        bind(level, stations, auto, ControllerStationType.IN_PV, 61.4);
        bind(level, stations, auto, ControllerStationType.IN_AUTO_DEMAND, 55.0);
        auto.touch();
        // A real size station with nothing wired to it (no PV): the process variable pointer is replaced by its red no-signal flag.
        put(level, stations, 13, 0, ComponentTypes.CONTROLLER_STATION, Footprint.of(3, 6)).touch();
        BlockPos scopes = block(CHECK_WALL, 1, 0);
        level.setBlock(scopes, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
        PLACED.add(scopes);
        bind(level, scopes, defaultSize(level, scopes, 0, 0, ComponentTypes.SYNCHROSCOPE), SynchroscopeType.IN_PHASE, -70.0);
        bind(level, scopes, defaultSize(level, scopes, 7, 0, ComponentTypes.SYNCHROSCOPE), SynchroscopeType.IN_PHASE, 70.0);
        bind(level, scopes, defaultSize(level, scopes, 0, 8, ComponentTypes.SYNCHROSCOPE), SynchroscopeType.IN_PHASE, 2.0);
        defaultSize(level, scopes, 7, 8, ComponentTypes.SYNCHROSCOPE).touch();
    }

    private static void instruments(ServerLevel level) {
        BlockPos a = block(WALL, 1, 0);
        PanelComponent sync = defaultSize(level, a, 0, 0, ComponentTypes.SYNCHROSCOPE);
        bind(level, a, sync, SynchroscopeType.IN_PHASE, 38.0);
        PanelComponent sync2 = defaultSize(level, a, 8, 0, ComponentTypes.SYNCHROSCOPE);
        bind(level, a, sync2, SynchroscopeType.IN_PHASE, -128.0);
        bind(level, a, sync2, SynchroscopeType.IN_SLIP, -0.1);
        PanelComponent real = put(level, a, 0, 10, ComponentTypes.SYNCHROSCOPE, Footprint.of(3, 4));
        bind(level, a, real, SynchroscopeType.IN_PHASE, 95.0);
        PanelComponent real2 = put(level, a, 4, 10, ComponentTypes.SYNCHROSCOPE, Footprint.of(2, 3));
        bind(level, a, real2, SynchroscopeType.IN_PHASE, -20.0);
        BlockPos b = block(WALL, 1, 1);
        PanelComponent bigScope = put(level, b, 0, 0, ComponentTypes.SYNCHROSCOPE, Footprint.of(15, 20));
        bind(level, b, bigScope, SynchroscopeType.IN_PHASE, 150.0);
        BlockPos c = block(WALL, 2, 0);
        recorder(level, c, defaultSize(level, c, 0, 0, ComponentTypes.TWO_PEN_RECORDER));
        recorder(level, c, put(level, c, 7, 0, ComponentTypes.TWO_PEN_RECORDER, Footprint.of(3, 3)));
        recorder(level, c, put(level, c, 11, 0, ComponentTypes.TWO_PEN_RECORDER, Footprint.of(2, 2)));
        BlockPos d = block(WALL, 2, 1);
        recorder(level, d, put(level, d, 0, 0, ComponentTypes.TWO_PEN_RECORDER, Footprint.of(16, 16)));
    }

    /** Two traces: a slow wave on pen 1 and a ramp with steps on pen 2, as if recorded over the span. */
    private static void recorder(ServerLevel level, BlockPos pos, PanelComponent c) {
        TwoPenRecorderData data = (TwoPenRecorderData) c.data();
        data.spanSeconds = 60;
        data.min = 0;
        data.max = 100;
        data.min2 = 0;
        data.max2 = 10;
        long now = level.getGameTime();
        for (int k = 0; k <= 24; k++) {
            long t = now - (24 - k) * 50L;
            data.push(0, t, (float) (50 + 38 * Math.sin(k * 0.45)));
            data.push(1, t, (float) Math.min(9.0, 1.0 + (k / 4) * 1.4 + (k % 4 == 0 ? 0.0 : 0.0)));
        }
        bind(level, pos, c, 0, 50 + 38 * Math.sin(24 * 0.45));
        bind(level, pos, c, 1, 8.4);
        c.touch();
    }

    private static void joysticks(ServerLevel level) {
        BlockPos a = block(WALL, 3, 0);
        for (int p = 0; p < JoystickData.POSITIONS; p++) {
            PanelComponent j = defaultSize(level, a, p * 3, 0, ComponentTypes.JOYSTICK_SWITCH);
            ((JoystickData) j.data()).position = p;
            j.type().onLoaded(j);
            j.touch();
        }
        for (int p = 0; p < JoystickData.POSITIONS; p++) {
            PanelComponent j = put(level, a, p * 3, 4, ComponentTypes.JOYSTICK_SWITCH, Footprint.of(1, 1));
            ((JoystickData) j.data()).position = p;
            j.type().onLoaded(j);
            j.touch();
        }
        BlockPos b = block(WALL, 3, 1);
        PanelComponent big = put(level, b, 0, 0, ComponentTypes.JOYSTICK_SWITCH, Footprint.of(16, 16));
        ((JoystickData) big.data()).position = JoystickData.LEFT;
        big.type().onLoaded(big);
        big.touch();
    }

    /** The reactor behind the LED displays: a real formed 185 rod core, high above the floor and out of every picture. */
    private static final BlockPos REACTOR_MIN = new BlockPos(5538, 170, 5270);

    /**
     * The LED displays read a real reactor, as they do in play (an unbound display is dark again whenever its chunk loads, so a
     * made up snapshot would not last): the core is put in a recognisable state like the full core display check does, rods
     * spread over the whole stroke, a few drives starved so DRIFT shows, rod 88 selected.
     */
    private static void leds(ServerLevel level) {
        dev.bwr.mod.reactor.ReactorControllerBlockEntity reactor;
        try {
            reactor = CompactCoreRegressionTests.build(level, REACTOR_MIN, 15, 15, false);
        } catch (Exception e) {
            throw new AssertionError("Hardware showcase: the reactor could not be built", e);
        }
        if (!reactor.isFormed()) {
            throw new AssertionError("Hardware showcase: the reactor did not form: " + reactor.statusLines());
        }
        FcdTestSupport.supplyDrives(level, reactor);
        var rods = reactor.rods();
        var core = reactor.core();
        int[] pattern = {24, 24, 24, 24, 0, 0, 12, 8, 16, 4};
        for (int r = 0; r < core.getControlRodCount(); r++) {
            rods.setNotchDemand(r, pattern[(r * 7 + r / 15 * 3) % pattern.length]);
        }
        for (int i = 0; i < 1300; i++) {
            rods.preStep(0.05);
            core.step();
            rods.postStep();
        }
        for (int r : new int[] {17, 40, 41, 63, 100, 133, 150}) {
            if (level.getBlockEntity(reactor.structure().crdPositions().get(r)) instanceof dev.bwr.mod.rods.ControlRodDriveBlockEntity drive) {
                drive.hardware().setEnergyStoredFe(0.0);
                drive.hardware().setWaterStoredMb(0.0);
            }
            rods.setNotchDemand(r, Math.min(24, rods.getCommandedNotchIndex(r) + 1));
        }
        reactor.selectRod(88);
        BlockPos a = block(LED_WALL, 0, 0);
        led(level, a, put(level, a, 0, 0, ComponentTypes.LED_ROD_DISPLAY, ComponentTypes.LED_ROD_DISPLAY.footprints().get(
                ComponentTypes.LED_ROD_DISPLAY.realDefaultIndex())), reactor, "position", "ROD PATTERN");
        // Below the default one (rows 36 to 52 of the wall): the exact real size, as a position pattern and as a fault map.
        BlockPos below = block(LED_WALL, 0, 2);
        led(level, below, put(level, below, 0, 4, ComponentTypes.LED_ROD_DISPLAY, Footprint.of(17, 17)), reactor, "position", "");
        BlockPos beside = block(LED_WALL, 1, 2);
        led(level, beside, put(level, beside, 2, 4, ComponentTypes.LED_ROD_DISPLAY, Footprint.of(17, 17)), reactor, "fault", "");
    }

    private static void led(ServerLevel level, BlockPos pos, PanelComponent c, dev.bwr.mod.reactor.ReactorControllerBlockEntity reactor,
                            String mode, String caption) {
        LedRodDisplayData data = (LedRodDisplayData) c.data();
        data.mode = mode;
        data.setText(caption);
        c.setSource(0, new dev.bwr.mod.panel.wiring.PlantSource(reactor.getBlockPos(), "formed"));
        be(level, pos).resample(c, true);
        if (!data.snapshot().formed()) {
            throw new AssertionError("Hardware showcase: an LED display did not bind to the reactor");
        }
        c.touch();
    }

    /** Every component is on the client: "blocks, components". */
    static String verify(Minecraft mc, int stage) {
        int components = 0;
        int formedLeds = 0;
        for (BlockPos pos : PLACED) {
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                components += be.owned().size();
                for (PanelComponent c : be.owned()) {
                    if (c.data() instanceof LedRodDisplayData led && led.snapshot().formed()) {
                        formedLeds++;
                    }
                }
            }
        }
        if (formedLeds != 3) {
            throw new AssertionError("Hardware showcase " + NAMES[stage] + ": " + formedLeds + " of 3 LED displays show a rod pattern on the client");
        }
        if (components != mounted) {
            throw new AssertionError("Hardware showcase " + NAMES[stage] + ": " + components + " of " + mounted + " components on the client");
        }
        return PLACED.size() + " blocks, " + components + " components, " + formedLeds + " LED displays with a rod pattern";
    }
}
