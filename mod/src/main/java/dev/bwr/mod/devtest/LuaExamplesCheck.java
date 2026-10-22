package dev.bwr.mod.devtest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.eccs.SuctionSource;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GraphicDisplayData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PlantComputerData;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.plant.PlantLayout;
import dev.bwr.mod.panel.plant.PlantState;
import dev.bwr.mod.panel.display.LcdFont;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.rods.ControlRodDriveBlockEntity;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Opt-in real CC computer execution of the actual docs/lua bytes. No hand-written
 * peripheral mock or alternate Lua interpreter is involved. A registered ServerComputer
 * owns direct side attachments; no computer block ticker competes for them. Cable topology and plant calibration are
 * outside this test. Devtest classes are excluded from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class LuaExamplesCheck {
    private static final String PROPERTY = "bwr.luaExamplesCheck";
    private static final String TOKEN = UUID.randomUUID().toString();
    private static final Path OUTPUT = Path.of("lua-examples-check", "results.json");
    private static final List<String> FILES = List.of("startup.lua", "backup_scram.lua", "eccs_start.lua",
            "containment_isolation.lua", "panel_display.lua", "spds.lua");
    private record Scenario(String name, String file, String error, String... args) { }
    private static final List<Scenario> DEFAULT_SCENARIOS = List.of(
            new Scenario("startup-motion", "startup.lua", null, "left", ".2", "1", "2", "15", ".05"),
            new Scenario("startup-invalid-notch", "startup.lua", "target_notch", "left", ".2", "1", "3", "2"),
            new Scenario("startup-active-scram", "startup.lua", "scram is active", "left", ".2", "1", "2", "2"),
            new Scenario("startup-no-drive-supply", "startup.lua", "timed out", "left", ".2", "1", "2", ".2", ".05"),
            new Scenario("startup-missing", "startup.lua", "peripheral unavailable", "missing", ".2", "1", "2", "2"),
            new Scenario("backup-trigger", "backup_scram.lua", null, "left", "1000000", "1000000", "1000000", ".05", "1"),
            new Scenario("backup-no-trigger", "backup_scram.lua", null, "left", "1000000", "1000000", "-1000000", ".05", "2"),
            new Scenario("backup-active-scram", "backup_scram.lua", null, "left", "1000000", "1000000", "-1000000", ".05", "1"),
            new Scenario("backup-unformed", "backup_scram.lua", "not formed", "left", "1000000", "1000000", "-1000000", ".05", "1"),
            new Scenario("backup-missing", "backup_scram.lua", "peripheral unavailable", "missing", "1000000", "1000000", "-1000000", ".05", "1"),
            new Scenario("eccs-rhr-command", "eccs_start.lua", null, "left", "pool", ".35", ".6"),
            new Scenario("eccs-rhr-pending-scan", "eccs_start.lua", null, "left", "pool", ".35", ".6"),
            new Scenario("eccs-invalid-flow", "eccs_start.lua", "Flow fraction", "left", "pool", "2", ".6"),
            new Scenario("eccs-missing", "eccs_start.lua", "No peripheral named", "missing", "pool", ".35", ".6"),
            new Scenario("isolation-closure", "containment_isolation.lua", null, "--timeout", "15", "left", "right"),
            new Scenario("isolation-preflight-missing", "containment_isolation.lua", "No peripheral named", "left", "missing"),
            new Scenario("isolation-refused", "containment_isolation.lua", "close refused", "left", "right"),
            new Scenario("isolation-timeout", "containment_isolation.lua", "timed out", "--timeout", "0", "left"),
            new Scenario("panel-two-frames", "panel_display.lua", null, "left", "right", "BWR-STATUS", "2", ".05"),
            new Scenario("panel-unformed-two-frames", "panel_display.lua", null, "left", "right", "BWR-STATUS", "2", ".05"),
            new Scenario("panel-missing-label", "panel_display.lua", "No panel component labelled", "left", "right", "ABSENT", "once"),
            new Scenario("panel-invalid-interval", "panel_display.lua", "Interval", "left", "right", "BWR-STATUS", "once", "bad"),
            new Scenario("panel-missing-reactor", "panel_display.lua", "No peripheral named", "left", "missing", "BWR-STATUS", "once"),
            new Scenario("spds-two-frames", "spds.lua", null, "left", "right", "SPDS", "2", ".05"),
            new Scenario("spds-unformed", "spds.lua", null, "left", "right", "SPDS", "once"),
            new Scenario("spds-touch", "spds.lua", null, "left", "right", "SPDS", "12", ".2"),
            new Scenario("spds-builtin-display", "spds.lua", "built-in page", "left", "right", "SPDS", "once"),
            new Scenario("spds-missing-label", "spds.lua", "No plant computer labelled", "left", "right", "ABSENT", "once"),
            new Scenario("spds-small-display", "spds.lua", "at least 24 x 12", "left", "right", "SPDS", "once")
    );
    private static final List<Scenario> SCENARIOS = scenarios();
    private static List<Scenario> scenarios() {
        int repeats = Integer.getInteger("bwr.luaEccsRepeats", 0);
        if (repeats == 0) return DEFAULT_SCENARIOS;
        if (repeats < 1 || repeats > 100) throw new IllegalArgumentException("bwr.luaEccsRepeats must be 1..100");
        String name = Boolean.getBoolean("bwr.luaEccsPendingScan") ? "eccs-rhr-pending-scan" : "eccs-rhr-command";
        Scenario eccs = DEFAULT_SCENARIOS.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
        return java.util.Collections.nCopies(repeats, eccs);
    }
    private static final Map<String, byte[]> sources = new LinkedHashMap<>();
    private static final Set<Long> releasedChunks = new LinkedHashSet<>();
    private static final JsonObject report = new JsonObject();
    private static final JsonArray results = new JsonArray();
    private static int ticks, index = -1, phase, phaseTicks;
    private static int drainTicks;
    private static boolean draining;
    private static Throwable shutdownFailure;
    private static volatile boolean finished;
    private static Fixture fixture;
    private static JsonObject luaResult;

    // DedicatedServer may return process exit 0 after a crash. Keep failure armed
    // until every script/readback and cleanup completes, matching the existing harness.
    private static final Thread FAILURE_EXIT = new Thread(() -> {
        System.err.println("LUA EXAMPLES CHECK did not complete successfully; forcing exit 1");
        System.err.flush();
        try { Thread.sleep(750); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        Runtime.getRuntime().halt(1);
    }, "bwr-lua-examples-exit-status");
    static {
        if (Boolean.getBoolean(PROPERTY)) Runtime.getRuntime().addShutdownHook(FAILURE_EXIT);
    }
    private LuaExamplesCheck() { }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean(PROPERTY) || finished) return;
        MinecraftServer server = event.getServer();
        if (draining) {
            if (++drainTicks < 100) return;
            try {
                for (long chunk : releasedChunks) require(!server.overworld().getForcedChunks().contains(chunk),
                        "fixture retained a forced chunk " + new ChunkPos(chunk));
                var chunks = server.overworld().getChunkSource();
                LogUtils.getLogger().info("LUA EXAMPLES DRAIN: {} ticks, {} released chunks, {} pending tasks, light work={}",
                        drainTicks, releasedChunks.size(), chunks.getPendingTasksCount(), chunks.getLightEngine().hasLightWork());
                // A flushing save also drains unload futures synchronously. Let normal
                // ticks settle them first, then persist without a forced flush here.
                server.saveEverything(true, false, true);
            } catch (Exception | AssertionError failure) {
                if (shutdownFailure == null) shutdownFailure = failure;
                else shutdownFailure.addSuppressed(failure);
            }
            completeShutdown(server, shutdownFailure);
            return;
        }
        try {
            if (++ticks > 15_000) throw new AssertionError("Lua examples overall timeout");
            if (ticks < 20) return;
            if (sources.isEmpty()) initialize();
            if (fixture == null) {
                if (++index == SCENARIOS.size()) {
                    finish(server, null);
                    return;
                }
                fixture = new Fixture(server.overworld(), index, SCENARIOS.get(index));
                fixture.build();
                phase = phaseTicks = 0;
            }
            fixture.supply();
            if (++phaseTicks > 900) throw new AssertionError("Scenario timed out: " + fixture.scenario.name());
            if (phase == 0) {
                if (phaseTicks < 20) return;
                if (fixture.msiv != null && (fixture.msiv.getPosition() < 1 || fixture.penetration.position() < 1)) return;
                fixture.computer.getClass().getMethod("turnOn").invoke(fixture.computer);
                phase = 1;
                phaseTicks = 0;
            } else if (phase == 1) {
                // Attach only after the actual runtime has booted and its
                // startup wrapper is waiting for the expected peripheral types.
                if (!TOKEN.equals(fixture.read("l5-boot-ready"))) return;
                fixture.start();
                phase = 2;
                phaseTicks = 0;
            } else if (phase == 2) {
                // spds-touch: a player touches soft key 2 while the program runs; it must show its second page.
                if (fixture.scenario.name().equals("spds-touch") && phaseTicks == 150) fixture.touch();
                // The Lua filesystem and server tick run independently. The marker
                // is written only after the entire JSON result has been closed.
                if (!TOKEN.equals(fixture.read("l5-result-ready"))) return;
                String result = fixture.read("l5-result.json");
                require(result != null, "Lua completion marker has no result");
                luaResult = JsonParser.parseString(result).getAsJsonObject();
                require(luaResult.has("token") && TOKEN.equals(luaResult.get("token").getAsString()), "stale Lua result token");
                require(luaResult.has("ok") && luaResult.get("ok").getAsBoolean(),
                        "Lua scenario failed: " + luaResult);
                phase = 3;
                phaseTicks = 0;
            } else if (phase == 3) {
                // Refusal/timeout intentionally leave accepted close demands applied.
                if (phaseTicks < 100 && fixture.scenario.name().startsWith("isolation")
                        && !fixture.scenario.name().equals("isolation-preflight-missing")) return;
                JsonObject readback = fixture.verify();
                JsonObject result = new JsonObject();
                result.addProperty("scenario", fixture.scenario.name());
                result.addProperty("script", fixture.scenario.file());
                result.addProperty("sha256", sha256(sources.get(fixture.scenario.file())));
                result.addProperty("status", "PASS");
                result.add("lua", luaResult);
                result.add("readback", readback);
                results.add(result);
                LogUtils.getLogger().info("LUA EXAMPLES PASS {}: {}", fixture.scenario.name(), readback);
                fixture.close();
                fixture = null;
                writeReport("RUNNING", null);
            }
        } catch (Exception | AssertionError failure) {
            finish(server, failure);
        }
    }

    private static void initialize() throws Exception {
        require(BwrMod.isComputerCraftPresent(), "CC:Tweaked must be present for actual Lua execution");
        String directory = System.getProperty("bwr.luaExamplesDirectory");
        require(directory != null && !directory.isBlank(), "bwr.luaExamplesDirectory was not configured");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        JsonObject files = new JsonObject();
        for (String file : FILES) {
            byte[] bytes = Files.readAllBytes(root.resolve(file));
            require(bytes.length > 0, "empty Lua example " + file);
            sources.put(file, bytes);
            files.addProperty(file, sha256(bytes));
            LogUtils.getLogger().info("LUA EXAMPLES SOURCE {} sha256={}", root.resolve(file), sha256(bytes));
        }
        report.addProperty("run_token", TOKEN);
        report.addProperty("source_directory", root.toString());
        report.addProperty("boundary", "Real registered CC ServerComputer and actual registered capability peripherals; direct side attachment, no cable-topology or plant-calibration claim");
        report.addProperty("expected_scenarios", SCENARIOS.size());
        report.add("scripts", files);
        report.add("scenarios", results);
        writeReport("RUNNING", null);
    }

    private static void finish(MinecraftServer server, Throwable failure) {
        if (finished || draining) return;
        try {
            if (failure != null && fixture != null) fixture.logTerminal();
            if (fixture != null) fixture.close();
        } catch (Exception | AssertionError cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        } finally {
            fixture = null;
        }
        // Keep normal server ticks alive after releasing the last fixture. Immediate
        // stop can spin vanilla's always-true unload loop before save futures settle.
        shutdownFailure = failure;
        draining = true;
        drainTicks = 0;
        try { writeReport("DRAINING", failure); }
        catch (IOException outputFailure) {
            if (shutdownFailure == null) shutdownFailure = outputFailure;
            else shutdownFailure.addSuppressed(outputFailure);
        }
        LogUtils.getLogger().info("LUA EXAMPLES: {} scenarios complete; draining fixture cleanup for 100 normal ticks", results.size());
    }

    private static void completeShutdown(MinecraftServer server, Throwable failure) {
        try { writeReport(failure == null ? "PASS" : "FAIL", failure); }
        catch (IOException outputFailure) {
            if (failure == null) failure = outputFailure;
            else failure.addSuppressed(outputFailure);
        }
        finished = true;
        if (failure == null) {
            Runtime.getRuntime().removeShutdownHook(FAILURE_EXIT);
            LogUtils.getLogger().info("LUA EXAMPLES CHECK COMPLETE: {} scenarios, {} actual scripts, cleanup complete", results.size(), sources.size());
        } else {
            LogUtils.getLogger().error("LUA EXAMPLES CHECK FAILED after {} scenarios", results.size(), failure);
        }
        server.halt(false);
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (!Boolean.getBoolean(PROPERTY) || finished || fixture == null) return;
        try { fixture.close(); }
        catch (Exception | AssertionError failure) { LogUtils.getLogger().error("Interrupted Lua fixture cleanup failed", failure); }
        finally { fixture = null; }
    }

    private static void writeReport(String status, Throwable failure) throws IOException {
        report.addProperty("status", status);
        report.addProperty("completed_scenarios", results.size());
        if (failure != null) report.addProperty("failure", failure.toString());
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final BlockPos origin;
        final Scenario scenario;
        final Map<String, BlockPos> attachments = new LinkedHashMap<>();
        final Map<String, String> types = new LinkedHashMap<>();
        Object computer, mount, scanRuntime;
        net.minecraft.world.level.block.entity.BlockEntity scanComputer;
        ReactorControllerBlockEntity reactor;
        EccsPumpBlockEntity pump;
        MainSteamIsolationValveBlockEntity msiv;
        ContainmentPenetrationBlockEntity penetration;
        GraphicDisplayData graphic;
        PlantComputerData plant;
        PanelComponent plantComponent;
        PanelBlockEntity plantOwner;
        boolean closed;

        Fixture(ServerLevel level, int slot, Scenario scenario) {
            this.level = level;
            this.scenario = scenario;
            origin = new BlockPos(83_000 + slot * 64, 190, 83_000);
        }

        void build() throws Exception {
            hold(true);
            clear(); // Required even on the first run: the dedicated test world is reused.
            if (scenario.file().equals("startup.lua") || scenario.file().equals("backup_scram.lua")
                    || scenario.file().equals("panel_display.lua") || scenario.file().equals("spds.lua")) buildReactor();
            switch (scenario.file()) {
                case "startup.lua", "backup_scram.lua" -> attach("left", reactor.getBlockPos(), "bwr_reactor");
                case "eccs_start.lua" -> {
                    BlockPos pos = origin.offset(12, 0, 0);
                    level.setBlock(pos, BwrBlocks.RHR_PUMP.get().defaultBlockState(), 3);
                    pump = (EccsPumpBlockEntity) level.getBlockEntity(pos);
                    require(pump != null, "RHR fixture entity missing");
                    pump.setComputerControlled(true);
                    pump.setRunning(false);
                    pump.setMode(EccsPumpBlockEntity.Mode.POOL_COOLING);
                    pump.setSuctionSource(SuctionSource.CONDENSATE_TANK);
                    pump.setFlowDemandFraction(.1);
                    pump.setSpeedDemandFraction(.15);
                    attach("left", pos, "bwr_rhr");
                }
                case "containment_isolation.lua" -> {
                    BlockPos pos = origin.offset(12, 0, 0), other = origin.offset(16, 0, 0);
                    level.setBlock(pos, BwrBlocks.MSIV.get().defaultBlockState(), 3);
                    msiv = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(pos);
                    msiv.setComputerControlled(true);
                    msiv.setDemandOpen(true);
                    level.setBlock(other, (scenario.name().equals("isolation-refused")
                            ? BwrBlocks.CONTAINMENT_POWER_PENETRATION.get()
                            : BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get()).defaultBlockState(), 3);
                    penetration = (ContainmentPenetrationBlockEntity) level.getBlockEntity(other);
                    penetration.setComputerControlled(true);
                    penetration.setDemandOpen(true);
                    attach("left", pos, "bwr_msiv");
                    attach("right", other, "bwr_containment_penetration");
                }
                case "panel_display.lua" -> {
                    BlockPos panel = origin.offset(12, 4, 0);
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++)
                        level.setBlock(panel.offset(-x, -y, 0), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                                .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                    var component = PanelOps.placeDirect(level, panel, 0, 0,
                            ComponentTypes.GRAPHIC_DISPLAY, Footprint.of(32, 32));
                    require(component != null, "2x2 panel display fixture did not fit");
                    component.setLabel("BWR-STATUS");
                    graphic = (GraphicDisplayData) component.data();
                    require(graphic.width() == 64 && graphic.height() == 64, "unexpected display fixture size");
                    attach("left", panel, "bwr_panel");
                    attach("right", reactor.getBlockPos(), "bwr_reactor");
                }
                case "spds.lua" -> {
                    // A plant computer on a 4 x 3 block panel wall (a 2 x 2 wall and a 32 x 24 one for the small display).
                    boolean small = scenario.name().equals("spds-small-display");
                    BlockPos panel = origin.offset(12, 4, 0);
                    for (int x = 0; x < (small ? 2 : 4); x++) for (int y = 0; y < (small ? 2 : 3); y++)
                        level.setBlock(panel.offset(-x, -y, 0), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                                .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                    plantComponent = PanelOps.placeDirect(level, panel, 0, 0, ComponentTypes.PLANT_COMPUTER,
                            small ? Footprint.of(32, 24) : Footprint.of(64, 48));
                    require(plantComponent != null, "plant computer fixture did not fit");
                    plantComponent.setLabel("SPDS");
                    plant = (PlantComputerData) plantComponent.data();
                    plantOwner = (PanelBlockEntity) level.getBlockEntity(panel);
                    if (scenario.name().equals("spds-builtin-display")) plant.mode = "overview";
                    attach("left", panel, "bwr_panel");
                    attach("right", reactor.getBlockPos(), "bwr_reactor");
                }
                default -> throw new AssertionError("unknown script fixture");
            }
            BlockPos computerPos = origin.offset(22, 0, 0);
            // Direct attachments have one owner: a real registered CC runtime. A physical
            // computer block owns its adjacent faces and can replace direct bindings on
            // any later neighbor scan (even after the Lua boot marker was written).
            Class<?> runtimeClass = Class.forName("dan200.computercraft.shared.computer.core.ServerComputer");
            Class<?> familyClass = Class.forName("dan200.computercraft.shared.computer.core.ComputerFamily");
            Object normal = familyClass.getField("NORMAL").get(null);
            int id = (int) Class.forName("dan200.computercraft.api.ComputerCraftAPI")
                    .getMethod("createUniqueNumberedSaveDir", MinecraftServer.class, String.class)
                    .invoke(null, level.getServer(), "computer");
            Object properties = runtimeClass.getMethod("properties", int.class, familyClass).invoke(null, id, normal);
            computer = runtimeClass.getConstructor(ServerLevel.class, BlockPos.class, properties.getClass())
                    .newInstance(level, computerPos, properties);
            computer.getClass().getMethod("register").invoke(computer);
            if (scenario.name().equals("eccs-rhr-pending-scan")) {
                // Exercise the original competing owner at the same position. Its real
                // native scan must never remove this fixture's runtime attachments.
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("computercraft", "computer_normal"));
                require(block != Blocks.AIR, "real CC computer block is absent");
                level.setBlock(computerPos, block.defaultBlockState(), 3);
                scanComputer = level.getBlockEntity(computerPos);
                require(scanComputer != null, "scan-regression computer block entity missing");
                scanRuntime = scanComputer.getClass().getMethod("createServerComputer").invoke(scanComputer);
            }
            mount = computer.getClass().getMethod("createRootMount").invoke(computer);
            write("example.lua", sources.get(scenario.file()));
            write("startup.lua", wrapper().getBytes(StandardCharsets.UTF_8));
        }

        void buildReactor() throws Exception {
            boolean unformed = scenario.name().equals("backup-unformed") || scenario.name().equals("panel-unformed-two-frames")
                    || scenario.name().equals("spds-unformed");
            if (unformed) {
                level.setBlock(origin, BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(), 3);
                reactor = (ReactorControllerBlockEntity) level.getBlockEntity(origin);
                require(reactor != null && !reactor.isFormed(), "unformed fixture unexpectedly formed");
            } else {
                reactor = CompactCoreRegressionTests.build(level, origin, 5, 5, false);
                require(reactor.isFormed(), "reactor fixture did not form: " + reactor.statusLines());
                require(reactor.setVesselState(VesselState.SHUTDOWN) == null, "could not close fixture vessel head");
                if (scenario.name().endsWith("active-scram")) reactor.rods().scram();
            }
        }

        void attach(String side, BlockPos pos, String type) {
            attachments.put(side, pos.immutable());
            types.put(side, type);
        }

        void supply() throws Exception {
            // The server registry executes CC normally; the harness owns the runtime's lifetime.
            computer.getClass().getMethod("keepAlive").invoke(computer);
            // Keep invalidating the separate native owner throughout the real Lua calls.
            if (scanComputer != null) for (Direction direction : Direction.values())
                scanComputer.getClass().getMethod("neighbourShapeChanged", Direction.class).invoke(scanComputer, direction);
            if (reactor != null && reactor.isFormed()) {
                boolean powered = !scenario.name().equals("startup-no-drive-supply");
                for (BlockPos pos : reactor.structure().crdPositions()) {
                    var drive = (ControlRodDriveBlockEntity) level.getBlockEntity(pos);
                    require(drive != null, "CRD fixture disappeared");
                    drive.hardware().setEnergyStoredFe(powered ? 20_000 : 0);
                    drive.hardware().setWaterStoredMb(powered ? 4_000 : 0);
                }
            }
            if (msiv != null) msiv.energy().receiveEnergy(Integer.MAX_VALUE, false);
            if (penetration != null) penetration.energy().receiveEnergy(Integer.MAX_VALUE, false);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void start() throws Exception {
            if (scanComputer != null) for (Direction direction : Direction.values())
                scanComputer.getClass().getMethod("neighbourShapeChanged", Direction.class).invoke(scanComputer, direction);
            var capability = (BlockCapability<Object, Direction>) Class.forName("dan200.computercraft.api.peripheral.PeripheralCapability")
                    .getMethod("get").invoke(null);
            Class sideClass = Class.forName("dan200.computercraft.core.computer.ComputerSide");
            Class<?> peripheralClass = Class.forName("dan200.computercraft.api.peripheral.IPeripheral");
            var setter = computer.getClass().getMethod("setPeripheral", sideClass, peripheralClass);
            Object expectedLeft = null;
            for (var attachment : attachments.entrySet()) {
                Object actual = level.getCapability(capability, attachment.getValue(), Direction.NORTH);
                require(actual != null, "actual peripheral capability missing at " + attachment.getValue());
                String type = (String) peripheralClass.getMethod("getType").invoke(actual);
                require(type.equals(types.get(attachment.getKey())), "incorrect actual peripheral type " + type);
                Object side = Enum.valueOf(sideClass, attachment.getKey().toUpperCase(java.util.Locale.ROOT));
                setter.invoke(computer, side, actual);
                if (attachment.getKey().equals("left")) expectedLeft = actual;
            }
            if (scanComputer != null) {
                var pos = scanComputer.getBlockPos();
                var state = level.getBlockState(pos);
                net.minecraft.world.level.block.entity.BlockEntityTicker ticker = state.getTicker(level, scanComputer.getType());
                require(ticker != null, "scan-regression computer has no real ticker");
                ticker.tick(level, pos, state, scanComputer);
                Object side = Enum.valueOf(sideClass, "LEFT");
                Object retained = computer.getClass().getMethod("getPeripheral", sideClass).invoke(computer, side);
                require(retained != null && retained == expectedLeft, "pending native neighbor scan erased direct ECCS attachment");
            }
            if (msiv != null) require(msiv.getPosition() == 1 && msiv.isDemandOpen()
                    && penetration.position() == 1 && penetration.demandOpen(), "valves were not fully open before isolation test");
            LogUtils.getLogger().info("LUA EXAMPLES START {} using real {} with {}", scenario.name(), scenario.file(), types);
        }

        String wrapper() {
            JsonArray args = new JsonArray();
            for (String arg : scenario.args()) args.add(arg);
            String expected = scenario.error() == null ? "nil" : new GsonBuilder().create().toJson(scenario.error());
            JsonObject expectedTypes = new JsonObject();
            types.forEach(expectedTypes::addProperty);
            String witness = scenario.name().equals("panel-two-frames") || scenario.name().equals("panel-unformed-two-frames")
                    ? "local p=assert(peripheral.wrap('left')); assert(p.drawText('BWR-STATUS',2,2,'BWR LIVE',0)==false, 'same-pixel write did not return changed=false')" : "";
            return """
                    local result={token='%s'}
                    local ok,detail=xpcall(function()
                      local ready=assert(fs.open('l5-boot-ready','w'));ready.write(result.token);ready.close()
                      local types=textutils.unserializeJSON([==[%s]==])
                      local deadline=os.clock()+10
                      while true do
                        local attached=true
                        for name,kind in pairs(types) do
                          if not peripheral.isPresent(name) or not peripheral.hasType(name,kind) then attached=false end
                        end
                        if attached then break end
                        assert(os.clock()<deadline,'real peripheral attachment timed out')
                        sleep(.05)
                      end
                      local args=textutils.unserializeJSON([==[%s]==])
                      local program,compileError=loadfile('example.lua',nil,_ENV)
                      assert(program,compileError)
                      local ran,problem=pcall(program,table.unpack(args))
                      result.program_success=ran
                      result.program_message=tostring(problem)
                      local expected=%s
                      if expected then
                        assert(not ran,'program unexpectedly succeeded')
                        assert(tostring(problem):find(expected,1,true),'wrong failure: '..tostring(problem))
                      else
                        assert(ran,'program failed: '..tostring(problem))
                      end
                      %s
                    end,debug.traceback)
                    result.ok=ok
                    if not ok then result.failure=tostring(detail) end
                    local f=assert(fs.open('l5-result.json','w'))
                    f.write(textutils.serializeJSON(result));f.close()
                    local done=assert(fs.open('l5-result-ready','w'));done.write(result.token);done.close()
                    """.formatted(TOKEN, expectedTypes, args, expected, witness);
        }

        JsonObject verify() {
            JsonObject out = new JsonObject();
            String name = scenario.name();
            if (reactor != null) {
                out.addProperty("formed", reactor.isFormed());
                if (reactor.isFormed()) {
                    int demand = reactor.rods().getCommandedNotchLabel(0), position = reactor.rods().getNotchLabel(0);
                    boolean scram = reactor.core().isScramActive();
                    out.addProperty("selected_rod_demand", demand);
                    out.addProperty("selected_rod_position", position);
                    out.addProperty("scram", scram);
                    out.addProperty("measured_core_flow_fraction", reactor.core().getCoreFlowFraction());
                    if (name.equals("startup-motion")) require(demand == 2 && position == 2 && !scram, "startup did not move actual selected rod");
                    else if (name.equals("startup-no-drive-supply")) require(demand == 2 && position == 0 && !scram, "timeout did not retain demand with stalled physical rod");
                    else require(demand == 0 && position == 0, "non-motion script changed a rod demand/position");
                    if (name.equals("backup-trigger") || name.endsWith("active-scram")) require(scram, "scram latch not set/retained");
                    else require(!scram, "script unexpectedly scrammed the fixture");
                }
            }
            if (pump != null) {
                out.addProperty("running", pump.isRunning());
                if (scanComputer != null) out.addProperty("pending_scan_attachment_survived", true);
                out.addProperty("flow_demand_fraction", pump.getFlowDemandFraction());
                out.addProperty("speed_demand_fraction", pump.pump().getSpeedDemandFraction());
                out.addProperty("mode", pump.getMode().name());
                out.addProperty("suction", pump.getSuctionSource().getSerializedName());
                out.addProperty("delivered_flow_kg_s", pump.getDeliveredFlowKgPerS());
                if (name.equals("eccs-rhr-command") || name.equals("eccs-rhr-pending-scan")) require(pump.isRunning() && pump.getMode() == EccsPumpBlockEntity.Mode.INJECTION
                        && pump.getSuctionSource() == SuctionSource.SUPPRESSION_POOL && Math.abs(pump.getFlowDemandFraction() - .35) < 1e-9
                        && Math.abs(pump.pump().getSpeedDemandFraction() - .6) < 1e-9, "ECCS commands/readbacks differ");
                else require(!pump.isRunning() && pump.getMode() == EccsPumpBlockEntity.Mode.POOL_COOLING
                        && pump.getSuctionSource() == SuctionSource.CONDENSATE_TANK && pump.getFlowDemandFraction() == .1
                        && pump.pump().getSpeedDemandFraction() == .15, "failed ECCS preflight changed commands");
            }
            if (msiv != null) {
                out.addProperty("msiv_demand_open", msiv.isDemandOpen());
                out.addProperty("msiv_position", msiv.getPosition());
                out.addProperty("penetration_demand_open", penetration.demandOpen());
                out.addProperty("penetration_position", penetration.position());
                if (name.equals("isolation-preflight-missing")) require(msiv.isDemandOpen() && msiv.getPosition() > .9 && penetration.demandOpen(), "isolation preflight partially closed an earlier valve");
                else require(!msiv.isDemandOpen() && msiv.getPosition() == 0, "accepted MSIV close did not persist/finish");
                if (name.equals("isolation-closure")) require(!penetration.demandOpen() && penetration.isIsolated(), "penetration did not close");
                if (name.equals("isolation-refused")) require(penetration.demandOpen() && penetration.position() == 1, "non-valved service was changed despite refusal");
            }
            if (plant != null) verifyPlant(out);
            if (graphic != null) {
                int lit = 0;
                for (int y = 0; y < graphic.height(); y++) for (int x = 0; x < graphic.width(); x++)
                    if (graphic.getPixel(x, y) != GraphicDisplayData.BACKGROUND) lit++;
                out.addProperty("display_nonbackground_pixels", lit);
                out.addProperty("display_version", graphic.version());
                if (scenario.error() == null) {
                    require(lit > 20, "successful panel script drew no meaningful display");
                    String telemetry = reactor.isFormed() ? "P 0.0%" : "UNFORMED";
                    expectTextRow(12, telemetry, 0);
                    out.addProperty("display_row_2", telemetry);
                    if (reactor.isFormed()) {
                        expectTextRow(42, "SCRAM NO", 5);
                        out.addProperty("display_row_5", "SCRAM NO");
                    }
                } else require(lit == 0, "failed panel preflight changed pixels");
            }
            return out;
        }

        /** A player touches soft key 2 of the plant computer, as the client's click would. */
        void touch() {
            PlantLayout layout = plant.layout();
            double click = plantComponent.type().clickValue(plantComponent, layout.left() + (layout.keyStart(2) + 0.5) * layout.cellW(),
                    layout.top() + (layout.rows() - 0.5) * layout.cellH());
            require(plantComponent.type().operate(new dev.bwr.mod.panel.component.ComponentType.OperateContext(level, null, plantOwner, plantComponent),
                    OperateAction.PRESS, click), "the soft key was not there to touch");
        }

        /** What the plant computer shows after the program: its pages, the safety function tiles and trends. */
        void verifyPlant(JsonObject out) {
            PlantState program = plant.program();
            String name = scenario.name();
            out.addProperty("plant_pages", program.definedPages());
            out.addProperty("plant_elements", program.elementCount());
            out.addProperty("plant_page_shown", program.selected() + 1);
            if (scenario.error() != null) {
                require(program.isEmpty(), "a refused SPDS program drew something: " + program.elementCount() + " elements");
                return;
            }
            require(program.definedPages() == 3 && program.page(0).title.equals("SAFETY FUNCTIONS"), "the SPDS has its three pages");
            String[] tiles = new String[6];
            for (int i = 1; i <= 6; i++) {
                var tile = program.element(0, "csf" + i);
                require(tile != null, "tile csf" + i + " is missing");
                tiles[i - 1] = dev.bwr.mod.panel.plant.PlantColors.STATES.get(tile.state);
            }
            out.addProperty("plant_tiles", String.join(",", tiles));
            if (name.equals("spds-unformed")) {
                for (String state : tiles) require(state.equals("off"), "an unformed reactor leaves every tile off: " + String.join(",", tiles));
                require(program.element(0, "msg").text.equals("REACTOR NOT FORMED"), "and says so: " + program.element(0, "msg").text);
                require(program.element(0, "pwr1").count == 0 && program.element(2, "power").valueText().equals("---"), "no trend points and no numbers");
                return;
            }
            // A cold, shut down, empty-containment fixture: reactivity, heat sink, RPV integrity and containment are green;
            // the two level based functions follow the fixture's own indicated level by the script's published limits.
            double level = reactor.core().getIndicatedLevelIn();
            double taf = dev.bwr.core.PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN;
            out.addProperty("plant_indicated_level_in", level);
            require(tiles[0].equals("ok") && tiles[2].equals("ok") && tiles[3].equals("ok") && tiles[4].equals("ok"),
                    "reactivity, heat sink, RPV integrity and containment: " + String.join(",", tiles));
            require(tiles[1].equals(level < taf ? "alarm" : "ok"), "core cooling follows the level: " + tiles[1] + " at " + level);
            double margin = level - taf;
            require(tiles[5].equals(margin < 20 ? "alarm" : margin < 120 ? "warn" : "ok"), "inventory follows the level: " + tiles[5] + " at " + level);
            int frames = name.equals("spds-two-frames") ? 2 : name.equals("spds-touch") ? 12 : 1;
            require(program.element(0, "pwr1").count == frames && program.element(1, "lvl").count == frames
                    && program.element(1, "prs").count == frames, "one trend point a frame: " + program.element(0, "pwr1").count);
            require(!program.element(2, "pressure").valueText().equals("---") && !program.element(2, "level").valueText().equals("---"),
                    "page 3 shows numbers");
            require(!program.element(0, "msg").text.equals("WAITING FOR DATA"), "the message line was written: " + program.element(0, "msg").text);
            if (name.equals("spds-touch")) {
                require(program.selected() == 1, "touching soft key 2 showed page 2, the page shown is " + (program.selected() + 1));
            } else {
                require(program.selected() == 0, "page 1 stays shown");
            }
            // the state a client would be sent is the state the program built
            PlantState client = new PlantState();
            require(client.loadFull(program.encodeFull()) && java.util.Arrays.equals(client.encodeFull(), program.encodeFull()),
                    "the program's pages encode and decode");
        }

        void expectTextRow(int top, String text, int color) {
            // Check a stable, independently known fixture reading, including the
            // blank pixels: a title alone or a different value cannot satisfy this.
            for (int y = 0; y < LcdFont.HEIGHT; y++) for (int x = 2; x < graphic.width() - 2; x++) {
                int cell = (x - 2) / LcdFont.ADVANCE, column = (x - 2) % LcdFont.ADVANCE;
                boolean ink = cell < text.length() && column < LcdFont.WIDTH && LcdFont.pixel(text.charAt(cell), column, y);
                int expected = ink ? color : GraphicDisplayData.BACKGROUND;
                require(graphic.getPixel(x, top + y) == expected,
                        "display row did not render '" + text + "' at " + x + "," + (top + y));
            }
        }

        SeekableByteChannel open(String path, Set<StandardOpenOption> options) throws Exception {
            try { return (SeekableByteChannel) mount.getClass().getMethod("openFile", String.class, Set.class).invoke(mount, path, options); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof IOException io) throw io;
                throw failure;
            }
        }
        void write(String path, byte[] bytes) throws Exception {
            try (var channel = open(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        }
        String read(String path) throws Exception {
            if (!(boolean) mount.getClass().getMethod("exists", String.class).invoke(mount, path)) return null;
            try (var channel = open(path, Set.of(StandardOpenOption.READ)); var output = new ByteArrayOutputStream()) {
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                int read;
                while ((read = channel.read(buffer)) > 0) {
                    output.write(buffer.array(), 0, read);
                    buffer.clear();
                    require(output.size() <= 65_536, "unbounded Lua result file");
                }
                return output.toString(StandardCharsets.UTF_8);
            }
        }
        void logTerminal() {
            if (computer == null) return;
            try {
                Object state = computer.getClass().getMethod("getTerminalState").invoke(computer);
                Object terminal = state.getClass().getMethod("create").invoke(state);
                int height = (int) terminal.getClass().getMethod("getHeight").invoke(terminal);
                for (int y = 0; y < height; y++) LogUtils.getLogger().error("LUA TERMINAL {}: {}", y,
                        terminal.getClass().getMethod("getLine", int.class).invoke(terminal, y));
            } catch (ReflectiveOperationException failure) { LogUtils.getLogger().error("Lua terminal read failed", failure); }
        }
        void hold(boolean forced) {
            for (int x = (origin.getX() - 3) >> 4; x <= (origin.getX() + 24) >> 4; x++)
                for (int z = (origin.getZ() - 3) >> 4; z <= (origin.getZ() + 10) >> 4; z++) {
                    level.setChunkForced(x, z, forced);
                    if (forced) level.getChunk(x, z);
                    else releasedChunks.add(ChunkPos.asLong(x, z));
                }
        }
        void clear() {
            // Remove the computer first so no queued Lua can act while fixture blocks disappear.
            level.setBlock(origin.offset(22, 0, 0), Blocks.AIR.defaultBlockState(), 2);
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-3, -3, -3), origin.offset(24, 12, 10)))
                if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                if (computer != null) computer.getClass().getMethod("close").invoke(computer);
            } finally {
                try {
                    if (scanRuntime != null && scanRuntime != computer) scanRuntime.getClass().getMethod("close").invoke(scanRuntime);
                } finally {
                    try { clear(); }
                    finally { hold(false); }
                }
            }
        }
    }
}
