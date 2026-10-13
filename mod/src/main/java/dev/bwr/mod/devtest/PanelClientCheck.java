package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.BindScreen;
import dev.bwr.mod.panel.client.ConfigScreen;
import dev.bwr.mod.panel.client.PanelClientInput;
import dev.bwr.mod.panel.client.LinkerHighlight;
import dev.bwr.mod.panel.client.PanelRenderContext;
import dev.bwr.mod.panel.component.*;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Disposable real-client panel showcase. It does not run in ordinary play or ship in the release jar. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PanelClientCheck {
    private static final int SHOWCASE_OFFSET = 5000;
    private static final BlockPos BOARD = new BlockPos(5100, 127, 5100);
    private static final BlockPos BENCH = new BlockPos(5098, 120, 5094);
    private static final BlockPos MSIV = new BlockPos(5098, 126, 5101);
    /** The first stage of the baked-model boards (see {@link PanelModelShowcase}); the stages before it are the original wall. */
    private static final int BAKED_FIRST = 9;
    /** The first stage of the sloped boards (see {@link PanelSlopeShowcase}): benchboard and console groups after the vertical boards. */
    private static final int SLOPE_FIRST = BAKED_FIRST + PanelModelShowcase.NAMES.length;
    private static final String[] NAMES = names();

    private static String[] names() {
        String[] original = {"board-day-wide", "board-controls-close", "board-annunciators",
                "board-lod-28m", "board-night-lamps", "wrench-config", "linker-binding", "linker-outline", "board-night-close"};
        String[] all = new String[BAKED_FIRST + PanelModelShowcase.NAMES.length + PanelSlopeShowcase.NAMES.length];
        System.arraycopy(original, 0, all, 0, original.length);
        System.arraycopy(PanelModelShowcase.NAMES, 0, all, BAKED_FIRST, PanelModelShowcase.NAMES.length);
        System.arraycopy(PanelSlopeShowcase.NAMES, 0, all, BAKED_FIRST + PanelModelShowcase.NAMES.length,
                PanelSlopeShowcase.NAMES.length);
        return all;
    }
    private static boolean started, built, requested;
    private static volatile Throwable buildFailure;
    private static volatile Vec3 heldCamera;
    private static volatile float heldYaw, heldPitch;
    private static int age, joined, stage, shown, captures;
    private static BlockPos switchOwner;
    private static int switchId;
    private static int placedTypes;

    private PanelClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.panelClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (buildFailure != null) throw new IllegalStateException("Panel showcase build failed", buildFailure);
        if (++age > 4200) throw new IllegalStateException("Panel client check timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.options.fov().set(60);
            mc.options.guiScale().set(2);
            // Stand-in models for the ids no resource provides yet (a real model with the same id always wins).
            if (Boolean.getBoolean("bwr.panelFixtureModels")) PanelModelFixtures.install();
            mc.createWorldOpenFlows().createFreshLevel("bwr-panel-client-" + System.currentTimeMillis(),
                    new LevelSettings("Panel client check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 35) return;
        if (!requested) {
            if (stage == 0) {
                // The light a glowing element is drawn with: full bright, and the world light for everything else.
                if (PanelRenderContext.packedLight(true, 0x00A000A0) != LightTexture.FULL_BRIGHT
                        || PanelRenderContext.packedLight(false, 0x00A000A0) != 0x00A000A0)
                    throw new AssertionError("PanelRenderContext.packedLight: emissive must be full bright, others unchanged");
            }
            requested = true;
            shown = 0;
            mc.setScreen(null);
            int selected = stage;
            mc.options.hideGui = selected < 5 || selected == 7 || selected == 8 || selected >= BAKED_FIRST;
            mc.options.fov().set(selected == 7 ? 90 : 60);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Panel check player missing");
                var level = player.serverLevel();
                if (!built) {
                    try {
                        build(level);
                        PanelModelShowcase.build(level);
                        PanelSlopeShowcase.build(level);
                        built = true;
                    } catch (Throwable failure) {
                        buildFailure = failure;
                        return;
                    }
                }
                Vec3 camera = selected >= SLOPE_FIRST ? PanelSlopeShowcase.camera(selected - SLOPE_FIRST)
                        : selected >= BAKED_FIRST ? PanelModelShowcase.camera(selected - BAKED_FIRST) : (switch (selected) {
                    case 0 -> new Vec3(93, 125, 79);
                    case 1, 5, 6, 8 -> new Vec3(94, 125, 92);
                    case 2 -> new Vec3(91, 121, 94);
                    case 7 -> new Vec3(96, 126, 97);
                    case 3 -> new Vec3(93, 126, 71); // 29 blocks from the board, beyond the 24-block LOD cutoff
                    default -> new Vec3(93, 125, 81);
                }).add(SHOWCASE_OFFSET, 0, SHOWCASE_OFFSET);
                Vec3 target = selected >= SLOPE_FIRST ? PanelSlopeShowcase.target(selected - SLOPE_FIRST)
                        : selected >= BAKED_FIRST ? PanelModelShowcase.target(selected - BAKED_FIRST) : (selected == 7 ? new Vec3(96.375, 127.5, 100.75)
                        : selected == 2 ? new Vec3(94, 120.5, 100)
                        : selected == 1 || selected == 5 || selected == 6 || selected == 8
                        ? new Vec3(94, 125, 100) : new Vec3(93, 123, 100))
                        .add(SHOWCASE_OFFSET, 0, SHOWCASE_OFFSET);
                Vec3 delta = target.subtract(camera.add(0, player.getEyeHeight(), 0));
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                if (selected == 7) {
                    heldCamera = camera;
                    heldYaw = yaw;
                    heldPitch = pitch;
                } else heldCamera = null;
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(selected == 4 || selected == 8 || selected >= BAKED_FIRST && selected < SLOPE_FIRST && PanelModelShowcase.night(selected - BAKED_FIRST)
                        ? 18_000 : 6_000);
                level.setWeatherParameters(6000, 0, false, false);
                if (selected == 7) {
                    var linker = new ItemStack(BwrItems.PANEL_LINKER.get());
                    linker.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), MSIV));
                    player.setItemInHand(InteractionHand.MAIN_HAND, linker);
                } else player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
            });
        }
        if (stage == 7 && heldCamera != null) {
            mc.player.setPos(heldCamera.x, heldCamera.y, heldCamera.z);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setYRot(heldYaw);
            mc.player.setXRot(heldPitch);
            mc.player.yRotO = heldYaw;
            mc.player.xRotO = heldPitch;
        }
        if (++shown == 70 && (stage == 5 || stage == 6)) {
            if (!(mc.level.getBlockEntity(switchOwner) instanceof PanelBlockEntity be)
                    || be.component(switchId) == null) throw new AssertionError("Switch was not synchronized for UI preview");
            var component = be.component(switchId);
            if (stage == 5) mc.setScreen(new ConfigScreen(be, component));
            else mc.setScreen(new BindScreen(be, component, new PanelPayloads.LinkerScreen(
                    switchOwner, switchId, PanelPayloads.LINK_OK, MSIV, "MSIV",
                    List.of(new PanelPayloads.SignalEntry("position", "Valve position", "fraction open")),
                    List.of(new PanelPayloads.CommandEntry("open", "Open / close", false)))));
        }
        if (shown < 105) return;
        if ((stage == 5 || stage == 6) && mc.screen == null)
            throw new AssertionError("The wrench / linker screen was closed before its screenshot (stage " + stage + ")");
        PanelComponent hovered = stage == 7 ? PanelClientInput.hoveredComponent(mc) : null;
        if (stage == 7 && hovered == null) {
            String detail = "hit=" + mc.hitResult;
            if (mc.hitResult instanceof BlockHitResult hit) {
                var entity = mc.level.getBlockEntity(hit.getBlockPos());
                int[] cell = new int[2];
                boolean mapped = entity instanceof PanelBlockEntity panel && PanelOps.cellAt(panel,
                        mc.player.getEyePosition(1.0f), mc.player.getViewVector(1.0f), cell);
                detail = "block=" + hit.getBlockPos() + " location=" + hit.getLocation()
                        + " entity=" + entity + " mapped=" + mapped + " cell=" + cell[0] + "," + cell[1];
            }
            throw new AssertionError("Linker camera is not aimed at a panel component: " + detail
                    + " eye=" + mc.player.getEyePosition(1.0f) + " look=" + mc.player.getViewVector(1.0f));
        }
        if (stage == 7 && (hovered.id() != switchId
                || !(hovered.source(0) instanceof PlantSource)
                || hovered.sinks(1).isEmpty()
                || !(mc.player.getMainHandItem().getItem() instanceof PanelLinkerItem))) {
            throw new AssertionError("Linker outline inputs missing: hovered=" + hovered.id()
                    + " expected=" + switchId + " source=" + hovered.source(0)
                    + " sinks=" + hovered.sinks(1) + " item=" + mc.player.getMainHandItem());
        }
        if (stage == 7 && LinkerHighlight.debugDraws() == 0) {
            throw new AssertionError("Bound-device outline was never submitted to the renderer");
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("panel-client-check");
            Files.createDirectories(directory);
            if (stage >= BAKED_FIRST) {
                // Every component must draw baked exactly when a loaded model exists for its look.
                LogUtils.getLogger().info("PANEL CLIENT CHECK baked models {}: {} components drawn from baked models ({} loaded)",
                        NAMES[stage], stage >= SLOPE_FIRST ? PanelSlopeShowcase.verify(mc, stage - SLOPE_FIRST)
                                : PanelModelShowcase.verify(mc, stage - BAKED_FIRST), dev.bwr.mod.panel.model.PanelModels.loadedCount());
            }
            try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                if (stage == 7) {
                    int orangePixels = 0;
                    for (int y = 0; y < shot.getHeight(); y++) for (int x = 0; x < shot.getWidth(); x++) {
                        int abgr = shot.getPixelRGBA(x, y);
                        if ((abgr & 0xFFFFFF) == 0x3399FF) orangePixels++;
                    }
                    if (orangePixels < 100) throw new AssertionError(
                            "Linker outline was submitted but is not visible in the framebuffer: " + orangePixels);
                }
                if (stage == 8) {
                    // Lit lamps, tiles and segments must be full-bright against the night world: count saturated
                    // red and amber pixels that a world-lit (about 30 %) element could not reach.
                    int red = 0, amber = 0;
                    for (int y = 0; y < shot.getHeight(); y++) for (int x = 0; x < shot.getWidth(); x++) {
                        int abgr = shot.getPixelRGBA(x, y);
                        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
                        if (r >= 215 && g <= 45 && b <= 45) red++;                  // lamp red 0xE02020 at full strength
                        if (r >= 240 && g >= 160 && g <= 190 && b <= 12) amber++;   // segment amber 0xFFB000
                    }
                    LogUtils.getLogger().info("PANEL CLIENT CHECK night glow: red={} amber={}", red, amber);
                    if (red < 100 || amber < 300) throw new AssertionError(
                            "Lit lamps or segments are not full-bright at night: red=" + red + " amber=" + amber);
                }
                shot.writeToFile(directory.resolve(NAMES[stage] + ".png"));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Panel screenshot failed", ex);
        }
        LogUtils.getLogger().info("PANEL CLIENT CHECK: {}", NAMES[stage]);
        captures++;
        requested = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("PANEL CLIENT CHECK PASS: {} component types, {} captures", placedTypes, captures);
            mc.stop();
        }
    }

    private static void build(net.minecraft.server.level.ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(5080, 118, 5084), new BlockPos(5115, 118, 5110)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            BlockPos pos = blockAt(col, row);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        }
        for (int row = 0; row < 2; row++) for (int col = 0; col < 8; col++) {
            BlockPos pos = BENCH.relative(Direction.WEST, col).below(row).relative(Direction.NORTH, row);
            level.setBlock(pos, BwrBlocks.CONTROL_PANEL_BENCHBOARD.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
        }
        var msivBlock = BwrBlocks.MSIV.get();
        var state = msivBlock.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        level.setBlock(MSIV, state, 3);
        msivBlock.setPlacedBy(level, MSIV, state, null, new ItemStack(msivBlock));
        var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(MSIV);
        if (valve == null) throw new AssertionError("Showcase MSIV did not place");
        valve.energy().receiveEnergy(20_000, false);
        valve.setDemandOpen(true);
        for (int i = 0; i < 90; i++) valve.tickValve(.05);

        boolean[][] occupied = new boolean[256][128];
        PanelComponent graphic = place(level, 0, 0, ComponentTypes.GRAPHIC_DISPLAY, Footprint.of(64, 64), occupied);
        var pixels = (GraphicDisplayData) graphic.data();
        pixels.fill(0, 0, pixels.width(), pixels.height(), 1);
        pixels.drawText(5, 5, "BWR UNIT 1", 14);
        pixels.drawText(5, 17, "CONTROL ROOM", 10);
        for (int i = 0; i < 8; i++) pixels.fill(6, 39 + i * 7, 20 + i * 8, 3, i % 2 == 0 ? 3 : 12);
        PanelComponent secondary = place(level, 8, 70, ComponentTypes.GRAPHIC_DISPLAY,
                Footprint.of(16, 16), occupied);
        ((GraphicDisplayData) secondary.data()).drawText(1, 2, "CORE", 14);
        for (int i = 0; i < 3; i++) {
            PanelComponent annunciator = place(level, 70 + i * 20, 108, ComponentTypes.ANNUNCIATOR,
                    Footprint.of(16, 12), occupied);
            AnnunciatorData lamp = (AnnunciatorData) annunciator.data();
            lamp.line1 = i == 0 ? "ALARM" : i == 1 ? "ACK" : "CLEAR";
            lamp.line2 = "PUMP TRIP";
            if (i < 2) lamp.logic.update(true);
            if (i == 1) lamp.logic.acknowledge();
        }

        PanelComponent sw = place(level, 70, 2, ComponentTypes.CONTROL_SWITCH, Footprint.of(8, 12), occupied);
        switchOwner = blockAt(70 / 16, 0);
        switchId = sw.id();
        sw.setLabel("MSIV-A");
        sw.addSink(1, new PlantSink(MSIV, "open", 1, PlantSink.Mode.MOMENTARY, 1));
        sw.addSink(2, new PlantSink(MSIV, "open", 1, PlantSink.Mode.MOMENTARY, 0));
        sw.setSource(0, new PlantSource(MSIV, "position"));
        PanelComponent seven = place(level, 82, 2, ComponentTypes.SEVEN_SEGMENT_DISPLAY, Footprint.of(20, 8), occupied);
        seven.setLabel("MSIV-POS");
        seven.setSource(0, new PlantSource(MSIV, "position", 100, 0));
        ((SegmentDisplayData) seven.data()).format = "{0:5.1f}";
        PanelComponent edgewise = place(level, 106, 2, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, Footprint.of(6, 16), occupied);
        edgewise.setLabel("MSIV-STROKE");
        edgewise.setSource(0, new PlantSource(MSIV, "position"));
        place(level, 118, 10, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12), occupied).setLabel("DIAL-X-BLOCK");

        // Reserve a deliberately large, four-block-spanning visual and then fit both a small and large
        // footprint of every catalogue type into the remaining 12 blocks of width.
        List<ComponentType> all = new ArrayList<>(ComponentTypes.all());
        for (ComponentType type : all) {
            placedTypes++;
            if (type == ComponentTypes.GRAPHIC_DISPLAY) continue;
            var sizes = type.footprints();
            if (!fit(level, type, sizes.get(0), 28, occupied)) {
                throw new AssertionError("No showcase space for " + type.id() + " " + sizes.get(0));
            }
            // The largest option that still fits the rest of the board (the biggest ones are 4 blocks long).
            for (int i = sizes.size() - 1; i >= 1; i--) if (fit(level, type, sizes.get(i), 70, occupied)) break;
        }
        // The desk is a separate connected group and tests the sloped face geometry.
        PanelOps.placeDirect(level, BENCH, 1, 2, ComponentTypes.NAMEPLATE, Footprint.of(12, 4));
        PanelOps.placeDirect(level, BENCH.relative(Direction.WEST, 2), 4, 4, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
        PanelOps.placeDirect(level, BENCH.relative(Direction.WEST, 4), 1, 1, ComponentTypes.ANNUNCIATOR, Footprint.of(12, 8));
        for (int row = 0; row < 8; row++) for (int col = 0; col < 16; col++) {
            if (level.getBlockEntity(blockAt(col, row)) instanceof PanelBlockEntity be) {
                PanelSampler.track(be);
                be.sampleLive();
                be.syncFull();
            }
        }
        if (!Double.isFinite(seven.input(0)) || seven.input(0) <= 0
                || !Double.isFinite(edgewise.input(0)) || edgewise.input(0) <= 0) {
            throw new AssertionError("MSIV did not deliver a live position to the panel instruments: segment="
                    + seven.input(0) + " edgewise=" + edgewise.input(0));
        }
    }

    private static boolean fit(net.minecraft.server.level.ServerLevel level, ComponentType type, Footprint size,
                               int firstY, boolean[][] occupied) {
        for (int y = firstY; y + size.h() <= 128; y++) for (int x = 66; x + size.w() <= 256; x++) {
            if (!free(occupied, x, y, size.w(), size.h())) continue;
            if ((x % 16 + size.w() + 15) / 16 > 4 || (y % 16 + size.h() + 15) / 16 > 4) continue; // spans five blocks
            var component = place(level, x, y, type, size, occupied);
            if (component != null) {
                previewValue(level, blockAt(x / 16, y / 16), component);
                return true;
            }
        }
        return false;
    }

    private static void previewValue(net.minecraft.server.level.ServerLevel level, BlockPos owner, PanelComponent component) {
        var data = component.data();
        if (data instanceof NameplateData plate) plate.setText(component.label());
        if (data instanceof SegmentDisplayData digits) digits.setText("98.6");
        if (data instanceof LcdData lcd) lcd.setText("BWR 1  POWER 98%");
        if (data instanceof GraphicDisplayData graphic) graphic.drawText(2, 2, "SYSTEM READY", 14);
        if (data instanceof AnnunciatorData ann) {
            ann.line1 = "PUMP";
            ann.line2 = "TRIP";
            if (component.id() % 3 != 0) {
                ann.logic.update(true);
                if (component.id() % 3 == 1) ann.logic.acknowledge();
            }
        }
        var entity = (PanelBlockEntity) level.getBlockEntity(owner);
        if (component.inputCount() > 0 && !(data instanceof AnnunciatorData)) {
            // A constant source, so the value survives re-sampling and the full sync: lamps lit, gauges at 68 %.
            component.setSource(0, InputSources.constant(68));
            entity.resample(component, true);
        }
        entity.syncFull();
    }

    private static PanelComponent place(net.minecraft.server.level.ServerLevel level, int x, int y,
                                        ComponentType type, Footprint size, boolean[][] occupied) {
        if (!free(occupied, x, y, size.w(), size.h())) return null;
        BlockPos anchor = blockAt(x / 16, y / 16);
        PanelComponent component = PanelOps.placeDirect(level, anchor, x % 16, y % 16, type, size);
        if (component == null) throw new AssertionError("Showcase placement refused " + type.id() + " at " + x + "," + y);
        for (int xx = x; xx < Math.min(256, x + size.w() + 2); xx++)
            for (int yy = y; yy < Math.min(128, y + size.h() + 2); yy++) occupied[xx][yy] = true;
        return component;
    }

    private static boolean free(boolean[][] occupied, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > 256 || y + h > 128) return false;
        for (int xx = Math.max(0, x - 2); xx < Math.min(256, x + w + 2); xx++)
            for (int yy = Math.max(0, y - 2); yy < Math.min(128, y + h + 2); yy++) if (occupied[xx][yy]) return false;
        return true;
    }

    private static BlockPos blockAt(int col, int row) {
        return BOARD.relative(Direction.WEST, col).below(row);
    }
}
