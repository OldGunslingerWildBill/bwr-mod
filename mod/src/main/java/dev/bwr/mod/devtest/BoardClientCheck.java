package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.board.BoardBlueprintItem;
import dev.bwr.mod.panel.board.BoardPlacer;
import dev.bwr.mod.panel.board.BoardPlan;
import dev.bwr.mod.panel.board.BoardTemplate;
import dev.bwr.mod.panel.board.BoardTemplates;
import dev.bwr.mod.panel.client.BoardPreview;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
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

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Disposable real-client check of the premade control boards: places every shipped board in a fresh flat world with the
 * real placer (the way the board blueprint does), checks every component reached the client, and photographs each board
 * from the front and from a three-quarter view, plus close-ups of its busiest parts (1920 x 1080, supersampled, in
 * {@code run/turbineModelCheck/board-client-check/}). Also holds the board blueprint in the player's hand over the empty
 * floor beside the board and checks that its ghost was drawn. Opt-in ({@code -PbwrBoardClientCheck}); not shipped.
 *
 * <p>Each board stands on its own floor patch, 48 blocks apart, around x 6000 / z 6000, facing north: the operator
 * stands at smaller z, the board runs west from its origin.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BoardClientCheck {
    private BoardClientCheck() {
    }

    private static final int X0 = 6000;
    private static final int Z0 = 6000;
    private static final int FLOOR_Y = 125;
    private static final int SPACING = 48;
    private static final String STAGE_FILTER = System.getProperty("bwr.boardClientStages", "");

    /** One photograph: board index, name, camera, target. */
    private record Shot(int board, String name, Vec3 camera, Vec3 target) {
    }

    private static boolean started;
    private static boolean requested;
    private static boolean built;
    private static volatile Throwable failure;
    private static volatile Vec3 wantedCamera;
    private static int age;
    private static int joined;
    private static int stage;
    private static int shown;
    private static int captures;
    private static List<Shot> shots;
    private static List<BoardTemplate> boards;

    private static BlockPos origin(int board) {
        return new BlockPos(X0 - board * SPACING, FLOOR_Y + 1, Z0);
    }

    /** The block the ghost stages look at: the floor east of the first board. */
    private static BlockPos ghostOrigin() {
        return new BlockPos(X0 + 30, FLOOR_Y, Z0);
    }

    /** The photographs of a placed board: front, three-quarter, and four close-ups (left, centre left, centre right, right) of the face. */
    private static List<Shot> shotsFor(int index, BoardTemplate template) {
        BoardPlan plan = new BoardPlan(template, origin(index), Direction.NORTH);
        var bounds = plan.bounds();
        // Facing north: the operator's right is west (smaller x); the board occupies x from maxX down to minX.
        double right0 = bounds.maxX;
        double width = bounds.maxX - bounds.minX;
        double height = bounds.maxY - bounds.minY;
        double centreX = (bounds.minX + bounds.maxX) / 2.0;
        double faceZ = bounds.minZ + 1.0; // the face of the duplex board and of the back panels is at the back-row front
        double centreY = FLOOR_Y + 1 + Math.min(2.2, height * 0.5);
        double distance = Math.max(width / 2.0, height * 0.9) * 0.95 + 2.5;
        List<Shot> list = new ArrayList<>();
        String name = template.id().getPath();
        double bench = bounds.minZ; // the front blocks; the operator stands at smaller z
        list.add(new Shot(index, name + "-front", new Vec3(centreX, FLOOR_Y + 1 + 2.6, bench - distance),
                new Vec3(centreX, centreY, faceZ)));
        double wide = Math.max(width * 0.42, height * 1.1) + 2.5;
        list.add(new Shot(index, name + "-three-quarter", new Vec3(centreX + width * 0.38, FLOOR_Y + 1 + 3.0, bench - wide),
                new Vec3(centreX - width * 0.04, centreY - 0.3, faceZ)));
        list.add(new Shot(index, name + "-bench", new Vec3(centreX, FLOOR_Y + 1 + 3.4, bench - Math.max(3.0, width * 0.3)),
                new Vec3(centreX, FLOOR_Y + 1 + 0.9, bench + 0.4)));
        // close-ups: four windows along the board, from 1.8 blocks away
        for (int i = 0; i < 4; i++) {
            double x = right0 - width * (0.125 + 0.25 * i);
            list.add(new Shot(index, name + "-close-" + (i + 1), new Vec3(x, FLOOR_Y + 1 + 2.0, bench - 2.2),
                    new Vec3(x, FLOOR_Y + 1 + 1.6, faceZ)));
        }
        return list;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.boardClientCheck")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            throw new IllegalStateException("Board client check failed", failure);
        }
        if (++age > 9000) {
            throw new IllegalStateException("Board client check timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.renderDistance().set(12);
            mc.options.fov().set(70);
            mc.options.guiScale().set(2);
            mc.createWorldOpenFlows().createFreshLevel("bwr-board-client-" + System.currentTimeMillis(),
                    new LevelSettings("Board client check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 35) {
            return;
        }
        if (shots == null) {
            boards = BoardTemplates.ordered();
            if (boards.size() < 5) {
                throw new AssertionError("Fewer than five premade boards loaded: " + boards.size());
            }
            shots = new ArrayList<>();
            for (int i = 0; i < boards.size(); i++) {
                shots.addAll(shotsFor(i, boards.get(i)));
            }
            // the blueprint's ghost over the empty floor east of the first board: fits (green), and with a block in the way
            BlockPos ghost = ghostOrigin();
            Vec3 eye = new Vec3(ghost.getX() + 2.0, FLOOR_Y + 3.6, ghost.getZ() + 4.2); // within reach of the target; the camera is third person
            Vec3 look = new Vec3(ghost.getX() + 0.5, FLOOR_Y + 1.0, ghost.getZ() + 0.5);
            shots.add(new Shot(-1, "blueprint-ghost-fits", eye, look));
            shots.add(new Shot(-1, "blueprint-ghost-blocked", eye, look));
            LogUtils.getLogger().info("BOARD CLIENT CHECK: {} boards, {} photographs", boards.size(), shots.size());
        }
        if (!requested) {
            while (stage < shots.size() && !chosen(shots.get(stage).name())) {
                stage++;
            }
            if (stage == shots.size()) {
                LogUtils.getLogger().info("BOARD CLIENT CHECK PASS: {} boards, {} captures", boards.size(), captures);
                mc.stop();
                return;
            }
            requested = true;
            shown = 0;
            wantedCamera = null;
            mc.setScreen(null);
            mc.options.hideGui = true;
            Shot shot = shots.get(stage);
            mc.options.setCameraType(shot.board() < 0 ? net.minecraft.client.CameraType.THIRD_PERSON_BACK
                    : net.minecraft.client.CameraType.FIRST_PERSON);
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) {
                    throw new IllegalStateException("Board check player missing");
                }
                ServerLevel level = player.serverLevel();
                if (!built) {
                    try {
                        buildAll(level);
                        built = true;
                    } catch (Throwable bad) {
                        failure = bad;
                        return;
                    }
                }
                Vec3 camera = shot.camera().subtract(0, 1.62, 0);
                Vec3 delta = shot.target().subtract(shot.camera());
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(6000);
                level.setWeatherParameters(6000, 0, false, false);
                player.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                if (shot.board() < 0) {
                    BlockPos ghost = ghostOrigin();
                    BlockPos obstacle = new BlockPos(ghost.getX() + 2, FLOOR_Y + 2, ghost.getZ() - 1);
                    level.setBlock(obstacle, shot.name().endsWith("blocked") ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                    BoardTemplate rps = BoardTemplates.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bwr", "reactor_scram_rps"));
                    player.setItemInHand(InteractionHand.MAIN_HAND, BoardBlueprintItem.stackFor(rps.id()));
                }
                player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
                wantedCamera = camera;
            });
        }
        Vec3 wanted = wantedCamera;
        if (wanted == null || mc.player.position().distanceToSqr(wanted) > 0.25) {
            return;
        }
        if (++shown < 90) {
            return;
        }
        Shot shot = shots.get(stage);
        if (shot.board() < 0) {
            ghostCheck(mc, shot);
            return;
        }
        BoardTemplate template = boards.get(shot.board());
        BoardPlan plan = new BoardPlan(template, origin(shot.board()), Direction.NORTH);
        int components = 0;
        for (BlockPos pos : plan.panelPositions()) {
            if (mc.level.getBlockEntity(pos) instanceof PanelBlockEntity be) {
                components += be.owned().size();
            }
        }
        if (components != template.components().size()) {
            throw new AssertionError("Board " + template.id() + ": " + components + " of " + template.components().size()
                    + " components on the client");
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("board-client-check");
            Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve(shot.name() + ".png"));
        } catch (IOException ex) {
            throw new IllegalStateException("Board screenshot failed", ex);
        }
        LogUtils.getLogger().info("BOARD CLIENT CHECK: {} ({} components, {} panel blocks)", shot.name(), components, plan.panelPositions().size());
        captures++;
        requested = false;
        stage++;
    }

    private static long ghostDrawsAtStart = -1;

    /** The held blueprint draws its ghost at the crosshair; with a block in the way some blocks are not free. */
    private static void ghostCheck(Minecraft mc, Shot shot) {
        if (ghostDrawsAtStart < 0) {
            ghostDrawsAtStart = BoardPreview.drawn();
            shown = 60; // a few more frames of the ghost before the photograph
            return;
        }
        BoardPlan plan = BoardPreview.currentPlan(mc);
        if (plan == null) {
            throw new AssertionError(shot.name() + ": the held blueprint has no plan at the crosshair (hit " + mc.hitResult + ")");
        }
        int blocked = 0;
        for (BoardPlan.Block block : plan.blocks()) {
            if (!BoardPreview.free(mc, block)) {
                blocked++;
            }
        }
        boolean wantBlocked = shot.name().endsWith("blocked");
        if ((blocked > 0) != wantBlocked) {
            throw new AssertionError(shot.name() + ": " + blocked + " blocks in the way, expected " + (wantBlocked ? "some" : "none"));
        }
        if (BoardPreview.drawn() <= ghostDrawsAtStart + 10) {
            throw new AssertionError(shot.name() + ": the ghost was not drawn (" + BoardPreview.drawn() + " frames)");
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("board-client-check");
            Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve(shot.name() + ".png"));
        } catch (IOException ex) {
            throw new IllegalStateException("Board screenshot failed", ex);
        }
        LogUtils.getLogger().info("BOARD CLIENT CHECK: {} ({} of {} blocks in the way, {} ghost frames)", shot.name(), blocked,
                plan.blocks().size(), BoardPreview.drawn() - ghostDrawsAtStart);
        ghostDrawsAtStart = -1;
        captures++;
        requested = false;
        stage++;
    }

    private static boolean chosen(String name) {
        if (STAGE_FILTER.isBlank()) {
            return true;
        }
        for (String word : STAGE_FILTER.split(",")) {
            if (!word.isBlank() && name.contains(word.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Floor, and every board placed with the real placer (free, as in creative), with a few lamps lit so the photographs show the lenses. */
    private static void buildAll(ServerLevel level) {
        for (int i = 0; i < boards.size(); i++) {
            BlockPos origin = origin(i);
            for (int x = origin.getX() - 36; x <= origin.getX() + 6; x++) {
                for (int z = origin.getZ() - 12; z <= origin.getZ() + 8; z++) {
                    level.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 12; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            BoardPlacer.Outcome outcome = BoardPlacer.place(level, null, boards.get(i), origin, Direction.NORTH);
            if (!outcome.ok()) {
                throw new AssertionError("Board " + boards.get(i).id() + " was not placed: " + outcome.status() + " " + outcome.detail());
            }
            lightLamps(level, new BoardPlan(boards.get(i), origin, Direction.NORTH));
        }
        ghostFloor(level);
    }

    private static void ghostFloor(ServerLevel level) {
        BlockPos ghost = ghostOrigin();
        for (int x = ghost.getX() - 8; x <= ghost.getX() + 14; x++) {
            for (int z = ghost.getZ() - 8; z <= ghost.getZ() + 14; z++) {
                level.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 10; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** Powers some indicator lamps (every third) from a constant, only so the photographs show lit and dark lenses. */
    private static void lightLamps(ServerLevel level, BoardPlan plan) {
        int n = 0;
        for (BlockPos pos : plan.panelPositions()) {
            if (!(level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
                continue;
            }
            for (PanelComponent component : be.owned()) {
                if (component.data() instanceof LampData && n++ % 3 == 0) {
                    component.setSource(0, InputSources.constant(1.0));
                    be.resample(component, true);
                }
            }
            be.syncFull();
        }
    }
}
