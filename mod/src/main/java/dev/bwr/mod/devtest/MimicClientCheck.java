package dev.bwr.mod.devtest;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.MimicPreview;
import dev.bwr.mod.panel.client.PanelRenderer;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.MimicSymbolData;
import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.mimic.MimicDraft;
import dev.bwr.mod.panel.mimic.MimicMode;
import dev.bwr.mod.panel.mimic.MimicOps;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicStyle;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Opt-in real-client pictures of the mimic tape ({@code -PbwrMimicClientCheck}), written to
 * {@code run/turbineModelCheck/mimic-check/}:
 * <ul>
 *   <li>{@code wall-flow}: a feedwater flow diagram on a 3 x 2 vertical wall: a tank, a pump and valve symbols, arrows
 *       along the lines, a tee junction (and a crossing that gets no dot), a dashed bypass, a lamp over a line;</li>
 *   <li>{@code bench-flow}: the same on a benchboard slope;</li>
 *   <li>{@code bound-off}, {@code bound-on}, {@code bound-on-later}: a path bound to a valve's demand, dim while it is
 *       closed, coloured with moving arrows once it is open, and the same ten ticks later (the arrows have moved);</li>
 *   <li>{@code preview-draw}: the tape held with three points drawn, the ghost segment to the grid point under the
 *       cursor; {@code preview-erase}: the path under the cursor outlined.</li>
 * </ul>
 * It asserts that the baked buffers hold quads, that lit and dim differ, that the two moving-arrow frames differ and that
 * the preview drew. The pictures themselves are for a person to look at. Not shipped: the devtest package is excluded
 * from the release jar.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class MimicClientCheck {
    private static final String PROPERTY = "bwr.mimicClientCheck";
    private static final int Y = 110;
    private static final Direction FACING = Direction.NORTH;

    /** The two flow walls and the bound wall, as the block at the top left of each (as the operator sees it). */
    private static final BlockPos VERTICAL_WALL = new BlockPos(32, Y + 2, 30);
    private static final BlockPos BENCH_WALL = new BlockPos(72, Y + 2, 30);
    private static final BlockPos BOUND_WALL = new BlockPos(112, Y + 2, 30);
    private static final BlockPos PREVIEW_WALL = new BlockPos(152, Y + 2, 30);
    private static final BlockPos SHAPES_WALL = new BlockPos(192, Y + 2, 30);
    private static final BlockPos VALVE = new BlockPos(112, Y, 50);

    private record Ctx(ServerLevel level, ServerPlayer player) {
    }

    /** One picture: what to do on the server first, where to stand and look, what to wait for, what to check. */
    private record Step(String name, Consumer<Ctx> setup, PanelType type, BlockPos wall, double distance, double aimU,
                        double aimV, double eyeU, double eyeV, int settle, Predicate<Minecraft> ready,
                        Consumer<Minecraft> verify, String differsFrom) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final Map<String, NativeImage> SHOTS = new HashMap<>();
    private static boolean started, requested;
    private static int age, joined, stage, shown, requestedAge;

    private MimicClientCheck() {
    }

    // ---- scenes

    private static BlockPos at(BlockPos wall, PanelType type, int column, int row) {
        BlockPos pos = wall.relative(FACING.getCounterClockWise(), column);
        for (int i = 0; i < row; i++) {
            pos = type.nextRow(pos, FACING);
        }
        return pos;
    }

    private static void wall(ServerLevel level, PanelType type, BlockPos wall, int columns, int rows) {
        var block = switch (type) {
            case VERTICAL -> BwrBlocks.CONTROL_PANEL_VERTICAL.get();
            case BENCHBOARD -> BwrBlocks.CONTROL_PANEL_BENCHBOARD.get();
            case CONSOLE -> BwrBlocks.CONTROL_PANEL_CONSOLE.get();
        };
        // A floor and clear air around the wall, so the picture is of the wall.
        for (BlockPos p : BlockPos.betweenClosed(wall.offset(-columns - 6, -rows - 8, -12), wall.offset(8, rows + 6, 6))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(wall.offset(-columns - 6, -rows - 9, -14), wall.offset(8, -rows - 9, 8))) {
            level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                level.setBlock(at(wall, type, column, row), block.defaultBlockState().setValue(PanelBlock.FACING, FACING), 3);
            }
        }
    }

    private static PanelComponent symbol(ServerLevel level, BlockPos wall, PanelType type, int cu, int cv, String symbol,
                                         double state) {
        BlockPos block = at(wall, type, cu / 16, cv / 16);
        PanelComponent component = PanelOps.placeDirect(level, block, cu % 16, cv % 16, ComponentTypes.MIMIC_SYMBOL, Footprint.of(8, 8));
        if (component == null) {
            throw new AssertionError("mimic symbol did not fit at " + cu + "," + cv);
        }
        ((MimicSymbolData) component.data()).symbol = symbol;
        component.setSource(0, InputSources.constant(state));
        ((PanelBlockEntity) level.getBlockEntity(block)).resample(component, true);
        component.touch();
        return component;
    }

    private static void path(ServerLevel level, BlockPos wall, PanelType type, MimicStyle style, int... points) {
        MimicOps.Placed placed = MimicOps.place(level, null, wall, points, style);
        if (placed.result() != MimicOps.Result.OK) {
            throw new AssertionError("mimic path refused: " + placed.result() + " " + java.util.Arrays.toString(points));
        }
    }

    private static MimicStyle style(int color, int width, boolean dashed, int start, int end, int flow) {
        MimicStyle style = new MimicStyle();
        style.color = color;
        style.width = width;
        style.dashed = dashed;
        style.startEnd = start;
        style.endEnd = end;
        style.flowEvery = flow;
        return style;
    }

    /** The feedwater diagram on a 3 x 2 wall (48 x 32 cells). */
    private static void flowDiagram(ServerLevel level, PanelType type, BlockPos wall) {
        wall(level, type, wall, 3, 2);
        symbol(level, wall, type, 1, 5, MimicSymbolData.TANK, 0);
        symbol(level, wall, type, 11, 5, MimicSymbolData.PUMP, 1);
        symbol(level, wall, type, 25, 5, MimicSymbolData.VALVE, 1);
        symbol(level, wall, type, 21, 18, MimicSymbolData.VALVE, 0);
        PanelComponent plate = PanelOps.placeDirect(level, wall, 2, 0, ComponentTypes.NAMEPLATE, Footprint.of(24, 4));
        if (plate != null) {
            ((NameplateData) plate.data()).text = "FEEDWATER";
            plate.touch();
        }
        PanelComponent lamp = PanelOps.placeDirect(level, at(wall, type, 2, 0), 5, 10, ComponentTypes.LAMP, Footprint.of(4, 4));
        if (lamp != null) {
            lamp.setSource(0, InputSources.constant(1));
            ((PanelBlockEntity) level.getBlockEntity(at(wall, type, 2, 0))).resample(lamp, true);
            lamp.touch();
        }
        // The feedwater line runs under the tank, the pump and the valve, down the right and into the vessel.
        path(level, wall, type, style(0x2E7DE0, 3, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 16), 5, 9, 39, 9, 39, 24);
        // A branch ending on the line: a tee (the dot is at its first point). A thin line crossing it: no dot.
        path(level, wall, type, style(0x20B040, 2, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 0), 39, 16, 46, 16);
        path(level, wall, type, style(0xF0F0E0, 1, false, MimicStyle.END_NONE, MimicStyle.END_NONE, 0), 34, 2, 34, 30);
        // A dashed bypass that leaves the line at a second tee and goes round the lower valve.
        path(level, wall, type, style(0xFFB000, 2, true, MimicStyle.END_NONE, MimicStyle.END_ARROW, 0), 17, 9, 17, 22, 36, 22);
        // A condensate return with a dot at each end and arrows along it.
        path(level, wall, type, style(0xE02020, 2, false, MimicStyle.END_DOT, MimicStyle.END_DOT, 8), 3, 28, 31, 28);
    }

    /**
     * Every line shape on one 3 x 2 wall (owner's change 2026-10-16, like draw.io connectors): an elbow route with sharp
     * corners, the same rounded, a smooth curve through four points, and a straight path with rounded corners; arrow heads
     * of different sizes, and arrows with their own colour.
     */
    private static void shapesScene(ServerLevel level) {
        wall(level, PanelType.VERTICAL, SHAPES_WALL, 3, 2);
        symbol(level, SHAPES_WALL, PanelType.VERTICAL, 2, 3, MimicSymbolData.PUMP, 1);
        symbol(level, SHAPES_WALL, PanelType.VERTICAL, 38, 22, MimicSymbolData.TANK, 0);
        MimicStyle elbow = style(0x2E7DE0, 3, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 0);
        elbow.route = MimicStyle.ROUTE_ELBOW;
        path(level, SHAPES_WALL, PanelType.VERTICAL, elbow, 10, 7, 24, 14);
        MimicStyle roundedElbow = style(0x20B040, 2, false, MimicStyle.END_DOT, MimicStyle.END_ARROW, 0);
        roundedElbow.route = MimicStyle.ROUTE_ELBOW;
        roundedElbow.corners = MimicStyle.CORNERS_ROUNDED;
        roundedElbow.radius = 4;
        roundedElbow.arrowSize = 6;
        path(level, SHAPES_WALL, PanelType.VERTICAL, roundedElbow, 28, 4, 44, 14);
        MimicStyle curved = style(0xFFB000, 2, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 12);
        curved.corners = MimicStyle.CORNERS_CURVED;
        curved.arrowOwnColor = true;
        curved.arrowColor = 0xE02020;
        curved.arrowSize = 4;
        path(level, SHAPES_WALL, PanelType.VERTICAL, curved, 4, 26, 12, 18, 22, 28, 32, 20);
        MimicStyle roundedStraight = style(0xF0F0E0, 2, true, MimicStyle.END_ARROW, MimicStyle.END_ARROW, 0);
        roundedStraight.corners = MimicStyle.CORNERS_ROUNDED;
        roundedStraight.radius = 3;
        roundedStraight.arrowSize = 3;
        path(level, SHAPES_WALL, PanelType.VERTICAL, roundedStraight, 26, 30, 36, 18, 46, 30);
    }

    /** The scene of the bound path: a closed valve, a path lit by its demand, a still dashed path beside it. */
    private static void boundScene(ServerLevel level) {
        wall(level, PanelType.VERTICAL, BOUND_WALL, 3, 2);
        for (BlockPos p : BlockPos.betweenClosed(VALVE.offset(-3, -1, -3), VALVE.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(VALVE.offset(-3, -1, -3), VALVE.offset(3, -1, 3))) {
            level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        level.setBlock(VALVE, state, 3);
        block.setPlacedBy(level, VALVE, state, null, new ItemStack(block));
        var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(VALVE);
        valve.setDemandOpen(false);
        valve.tickValve(5);
        PanelComponent plate = PanelOps.placeDirect(level, BOUND_WALL, 2, 0, ComponentTypes.NAMEPLATE, Footprint.of(24, 4));
        if (plate != null) {
            ((NameplateData) plate.data()).text = "MSIV A";
            plate.touch();
        }
        symbol(level, BOUND_WALL, PanelType.VERTICAL, 20, 12, MimicSymbolData.VALVE, 0);
        path(level, BOUND_WALL, PanelType.VERTICAL, style(0x2E7DE0, 3, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 8), 3, 16, 44, 16);
        path(level, BOUND_WALL, PanelType.VERTICAL, style(0x20B040, 2, true, MimicStyle.END_NONE, MimicStyle.END_ARROW, 0), 3, 26, 44, 26);
        PanelBlockEntity anchor = (PanelBlockEntity) level.getBlockEntity(BOUND_WALL);
        MimicPath bound = anchor.mimicPaths().get(0);
        for (BlockPos pos : anchor.group().members()) {
            if (level.getBlockEntity(pos) instanceof PanelBlockEntity copy && copy.mimicByPathId(bound.pathId()) != null) {
                MimicPath piece = copy.mimicByPathId(bound.pathId());
                piece.setSource(new PlantSource(VALVE, "demand_open"));
                copy.resampleMimic(piece);
                copy.mimicChanged();
                copy.syncFull();
            }
        }
    }

    private static void openValve(ServerLevel level, boolean open) {
        ((MainSteamIsolationValveBlockEntity) level.getBlockEntity(VALVE)).setDemandOpen(open);
    }

    /** A wall with two finished paths, for the preview of the tape. */
    private static void previewScene(ServerLevel level) {
        wall(level, PanelType.VERTICAL, PREVIEW_WALL, 3, 2);
        path(level, PREVIEW_WALL, PanelType.VERTICAL, style(0x2E7DE0, 3, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 16), 4, 26, 44, 26);
        path(level, PREVIEW_WALL, PanelType.VERTICAL, style(0x20B040, 2, false, MimicStyle.END_NONE, MimicStyle.END_ARROW, 0), 8, 4, 8, 14, 28, 14);
    }

    /** The tape in the hand, in a mode, with a half drawn path on the preview wall. */
    private static void holdTape(ServerPlayer player, MimicMode mode, boolean withDraft) {
        ItemStack tape = new ItemStack(BwrItems.MIMIC_TAPE.get(), 8);
        MimicTapeItem.setMode(tape, mode);
        if (withDraft) {
            tape.set(BwrDataComponents.MIMIC_DRAFT.get(), new MimicDraft(PREVIEW_WALL, List.of(16, 6, 38, 6, 38, 19)));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, tape);
    }

    private static boolean wallReady(Minecraft mc, BlockPos wall, PanelType type, int paths) {
        return mc.level != null && mc.level.getBlockEntity(wall) instanceof PanelBlockEntity be && be.mimicCount() >= paths
                && mc.level.getBlockEntity(at(wall, type, 2, 1)) instanceof PanelBlockEntity;
    }

    private static boolean litIs(Minecraft mc, boolean lit) {
        return mc.level != null && mc.level.getBlockEntity(BOUND_WALL) instanceof PanelBlockEntity be
                && !be.mimicPaths().isEmpty() && be.mimicPaths().get(0).bound() && be.mimicPaths().get(0).lit() == lit;
    }

    private static void need(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Mimic client check: " + message);
        }
    }

    static {
        STEPS.add(new Step("wall-flow", ctx -> flowDiagram(ctx.level(), PanelType.VERTICAL, VERTICAL_WALL), PanelType.VERTICAL,
                VERTICAL_WALL, 3.0, 24, 16, 24, 16, 70, mc -> wallReady(mc, VERTICAL_WALL, PanelType.VERTICAL, 2),
                mc -> need(PanelRenderer.debugCachedQuads() > 200, "the flow wall's block buffers hold " + PanelRenderer.debugCachedQuads() + " quads"),
                null));
        STEPS.add(new Step("bench-flow", ctx -> flowDiagram(ctx.level(), PanelType.BENCHBOARD, BENCH_WALL), PanelType.BENCHBOARD,
                BENCH_WALL, 3.4, 24, 16, 24, 16, 70, mc -> wallReady(mc, BENCH_WALL, PanelType.BENCHBOARD, 2),
                mc -> need(PanelRenderer.debugCachedQuads() > 200, "the bench wall's block buffers hold quads"), null));
        STEPS.add(new Step("wall-shapes", ctx -> shapesScene(ctx.level()), PanelType.VERTICAL, SHAPES_WALL, 3.0, 24, 16, 24, 16, 70,
                mc -> wallReady(mc, SHAPES_WALL, PanelType.VERTICAL, 2),
                mc -> need(PanelRenderer.debugCachedQuads() > 200, "the shapes wall's block buffers hold " + PanelRenderer.debugCachedQuads() + " quads"),
                null));
        STEPS.add(new Step("bound-off", ctx -> boundScene(ctx.level()), PanelType.VERTICAL, BOUND_WALL, 3.0, 24, 16, 24, 16, 70,
                mc -> wallReady(mc, BOUND_WALL, PanelType.VERTICAL, 1) && litIs(mc, false), mc -> need(litIs(mc, false), "dim"), null));
        STEPS.add(new Step("bound-on", ctx -> openValve(ctx.level(), true), PanelType.VERTICAL, BOUND_WALL, 3.0, 24, 16, 24, 16, 30,
                mc -> litIs(mc, true), mc -> need(litIs(mc, true), "lit"), "bound-off"));
        STEPS.add(new Step("bound-on-later", ctx -> { }, PanelType.VERTICAL, BOUND_WALL, 3.0, 24, 16, 24, 16, 10,
                mc -> litIs(mc, true), mc -> need(litIs(mc, true), "still lit"), "bound-on"));
        STEPS.add(new Step("preview-draw", ctx -> {
            previewScene(ctx.level());
            holdTape(ctx.player(), MimicMode.DRAW, true);
        }, PanelType.VERTICAL, PREVIEW_WALL, 3.0, 45, 19, 24, 16, 70,
                mc -> wallReady(mc, PREVIEW_WALL, PanelType.VERTICAL, 1) && mc.player != null
                        && MimicTapeItem.draft(mc.player.getMainHandItem()) != null,
                mc -> need(MimicPreview.debugQuads() > 10, "the draw preview drew " + MimicPreview.debugQuads() + " quads"), null));
        STEPS.add(new Step("preview-erase", ctx -> holdTape(ctx.player(), MimicMode.ERASE, false), PanelType.VERTICAL, PREVIEW_WALL, 3.0,
                30, 26, 24, 16, 30, mc -> mc.player != null && MimicTapeItem.mode(mc.player.getMainHandItem()) == MimicMode.ERASE,
                mc -> need(MimicPreview.debugQuads() > 4, "the erase outline drew " + MimicPreview.debugQuads() + " quads"), null));
    }

    // ---- driver

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (++age > 600 + 450 * STEPS.size()) {
            throw new IllegalStateException("Mimic client check timed out at step " + stage + " (" + STEPS.get(stage).name() + ", requested=" + requested + ", shown=" + shown + ")");
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-mimic-check-" + System.currentTimeMillis(),
                    new LevelSettings("Mimic check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 100) {
            return;
        }
        RenderEnvironment.log("mimic-client");
        Step step = STEPS.get(stage);
        if (!requested) {
            requested = true;
            requestedAge = age;
            shown = 0;
            mc.setScreen(null);
            mc.options.hideGui = true;
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
              try {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                ServerLevel level = player.serverLevel();
                level.setDayTime(6000);
                level.setWeatherParameters(6000, 0, false, false);
                step.setup().accept(new Ctx(level, player));
                PanelGeometry.Frame frame = PanelGeometry.frame(step.type(), FACING);
                double distance = step.distance() * 16.0;
                Vec3 eye = new Vec3(step.wall().getX() + frame.x(step.eyeU(), step.eyeV(), distance),
                        step.wall().getY() + frame.y(step.eyeU(), step.eyeV(), distance),
                        step.wall().getZ() + frame.z(step.eyeU(), step.eyeV(), distance));
                Vec3 target = new Vec3(step.wall().getX() + frame.x(step.aimU(), step.aimV(), 0),
                        step.wall().getY() + frame.y(step.aimU(), step.aimV(), 0),
                        step.wall().getZ() + frame.z(step.aimU(), step.aimV(), 0));
                Vec3 delta = target.subtract(eye);
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.connection.teleport(eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
                LogUtils.getLogger().info("MIMIC CLIENT setup {} done, eye {} yaw {} pitch {}", step.name(), eye, yaw, pitch);
              } catch (Throwable t) {
                LogUtils.getLogger().error("MIMIC CLIENT setup of " + step.name() + " failed", t);
              }
            });
        }
        if (requestedAge == age - 30) {
            // A full state sent in the same tick as the block arrives before the block (the client has no block entity to
            // apply it to yet), so send every panel near the wall again once the blocks are there.
            var server = mc.getSingleplayerServer();
            BlockPos wall = step.wall();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                for (BlockPos p : BlockPos.betweenClosed(wall.offset(-6, -6, -1), wall.offset(1, 1, 3))) {
                    if (level.getBlockEntity(p) instanceof PanelBlockEntity be) {
                        be.syncFull();
                    }
                }
            });
        }
        if (!step.ready().test(mc) || mc.screen != null || ++shown <= step.settle()) {
            if (shown == 0 && age % 200 == 0) {
                LogUtils.getLogger().info("MIMIC CLIENT waiting for {}: block {} paths {} player {}", step.name(),
                        mc.level.getBlockState(step.wall()), mc.level.getBlockEntity(step.wall()) instanceof PanelBlockEntity be ? be.mimicCount() : -1, mc.player.position());
            }
            return;
        }
        Path directory = mc.gameDirectory.toPath().resolve("mimic-check");
        Files.createDirectories(directory);
        NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        shot.writeToFile(directory.resolve(step.name() + ".png"));
        step.verify().accept(mc);
        if (step.differsFrom() != null) {
            NativeImage other = SHOTS.get(step.differsFrom());
            int different = differing(shot, other);
            LogUtils.getLogger().info("MIMIC CLIENT {} differs from {} in {} pixels", step.name(), step.differsFrom(), different);
            need(different > 40, step.name() + " is not different from " + step.differsFrom() + " (" + different + " pixels)");
        }
        SHOTS.put(step.name(), shot);
        LogUtils.getLogger().info("MIMIC CLIENT PASS {} (block buffers: {} blocks, {} quads; preview quads {})", step.name(),
                PanelRenderer.debugCachedBlocks(), PanelRenderer.debugCachedQuads(), MimicPreview.debugQuads());
        requested = false;
        if (++stage == STEPS.size()) {
            LogUtils.getLogger().info("MIMIC CLIENT COMPLETE");
            mc.stop();
        }
    }

    private static int differing(NativeImage a, NativeImage b) {
        int count = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
                int p = a.getPixelRGBA(x, y);
                int q = b.getPixelRGBA(x, y);
                int d = Math.abs((p & 0xFF) - (q & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
                if (d > 40) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Scatters {@code count} paths over a {@code columns x rows} wall whose top-left block is {@code root}: the stress
     * layout of the frame time check. Deterministic: the same paths every run. Returns how many were placed.
     */
    public static int scatter(ServerLevel level, BlockPos root, int columns, int rows, int count) {
        long seed = 0x9E3779B97F4A7C15L;
        int width = columns * 16;
        int height = rows * 16;
        int placed = 0;
        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int x = 6 + (int) ((seed >>> 33) % (width - 12));
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int y = 6 + (int) ((seed >>> 33) % (height - 12));
            List<Integer> points = new ArrayList<>(List.of(x, y));
            boolean horizontal = (seed & 1) == 0;
            for (int k = 0; k < 3; k++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int length = 5 + (int) ((seed >>> 33) % 18);
                int sign = ((seed >>> 20) & 1) == 0 ? 1 : -1;
                if (horizontal) {
                    x = Math.max(4, Math.min(width - 4, x + sign * length));
                } else {
                    y = Math.max(4, Math.min(height - 4, y + sign * length));
                }
                if (x == points.get(points.size() - 2) && y == points.get(points.size() - 1)) {
                    x = Math.min(width - 4, x + 5);
                }
                points.add(x);
                points.add(y);
                horizontal = !horizontal;
            }
            int[] flat = points.stream().mapToInt(Integer::intValue).toArray();
            MimicStyle style = new MimicStyle();
            style.width = 1 + (int) ((seed >>> 40) % 4);
            style.color = new int[] {0x2E7DE0, 0xE02020, 0x20B040, 0xFFB000, 0xF0F0E0}[(int) ((seed >>> 44) % 5)];
            style.dashed = (seed >>> 48) % 5 == 0;
            style.flowEvery = new int[] {0, 0, 8, 16, 32}[(int) ((seed >>> 52) % 5)];
            style.startEnd = (seed >>> 56) % 4 == 0 ? MimicStyle.END_DOT : MimicStyle.END_NONE;
            if (MimicOps.place(level, null, root, flat, style).result() == MimicOps.Result.OK) {
                placed++;
            }
        }
        return placed;
    }
}
