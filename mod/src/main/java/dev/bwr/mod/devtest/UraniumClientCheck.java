package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;

/**
 * Opt-in real-client pictures of the uranium ore ({@code -PbwrUraniumClientCheck}, see URANIUM-ORE.md): a normal generated
 * world (seed 20261022), a super vein located in the arid group from the seed alone, its chunks generated, an open cut dug
 * down to the vein's mid-plane so the ribbon shows in the floor, and four 1920 x 1080 pictures: the cut from the air, the
 * ore close up, the ore blocks beside iron's, and the items. Screenshots go to {@code run/turbineModelCheck/uranium-check/}.
 * Not shipped: the whole devtest package is excluded from the release jar.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class UraniumClientCheck {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final String[] NAMES = {"uranium-vein-cutaway", "uranium-vein-closeup", "uranium-ore-blocks", "uranium-items"};
    private static final int SHOW_Y = 200;

    private static boolean started, requested, prepared;
    private static int age, joined, stage, shown, waited;
    /** Set by the server thread. */
    private static volatile double[] pit, camera, target, floorAt;
    private static volatile String failure;
    private static volatile BlockPos probe;

    private UraniumClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("bwr.uraniumClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 14_000) throw new IllegalStateException("Uranium client check timed out at stage " + stage);
        if (failure != null) throw new IllegalStateException(failure);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.options.renderDistance().set(8);
            mc.createWorldOpenFlows().createFreshLevel("bwr-uranium-check-" + System.currentTimeMillis(),
                    new LevelSettings("Uranium check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(20261022L, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 100) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (!prepared) {
            prepared = true;
            mc.setScreen(null);
            mc.options.hideGui = true;
            server.execute(() -> {
                try {
                    prepare(server.overworld());
                } catch (Throwable t) {
                    LOG.error("uranium client check: prepare failed", t);
                    failure = "prepare failed: " + t;
                }
            });
            return;
        }
        if (pit == null) return;   // the server is still digging
        if (!requested) {
            requested = true;
            shown = 0;
            waited = 0;
            int current = stage;
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(uuid);
                    var level = player.serverLevel();
                    level.setDayTime(6000);
                    level.setWeatherParameters(6000, 0, false, false);
                    double[] cam, tgt;
                    if (current <= 1) {
                        cam = current == 0 ? camera : closeCamera();
                        tgt = current == 0 ? target : closeTarget();
                        probe = BlockPos.containing(floorAt[0], floorAt[1] - 1, floorAt[2]);
                    } else {
                        buildShowcase(level, current);
                        cam = showCamera(current);
                        tgt = showTarget(current);
                        probe = new BlockPos(showX(), SHOW_Y - 1, showZ() + 3);
                    }
                    double eyeY = cam[1] + player.getEyeHeight();
                    double dx = tgt[0] - cam[0], dy = tgt[1] - eyeY, dz = tgt[2] - cam[2];
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.connection.teleport(cam[0], cam[1], cam[2], yaw, pitch);
                } catch (Throwable t) {
                    LOG.error("uranium client check: stage failed", t);
                    failure = "stage " + current + " failed: " + t;
                }
            });
            return;
        }
        BlockPos p = probe;
        boolean ready = p != null && mc.screen == null && mc.level.hasChunkAt(p) && !mc.level.getBlockState(p).isAir() && mc.player.distanceToSqr(p.getX(), p.getY(), p.getZ()) < 150 * 150;
        if (ready && ++shown > 150) {
            var dir = mc.gameDirectory.toPath().resolve("uranium-check");
            Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
            LOG.info("URANIUM CLIENT PASS {}", NAMES[stage]);
            if (++stage == NAMES.length) {
                LOG.info("URANIUM CLIENT COMPLETE");
                mc.stop();
            } else {
                requested = false;
            }
        } else if (!ready && ++waited % 600 == 0) {
            LOG.info("uranium client check: waiting at stage {} (probe {}, chunk there {})", stage, p, p != null && mc.level.hasChunkAt(p));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // server side: find the vein, generate its chunks, dig the cut

    private static void prepare(ServerLevel level) {
        var found = UraniumVeinFinder.find(level, "arid", 0.5, 14, 12, v -> dryLand(level, v));
        if (found == null) found = UraniumVeinFinder.find(level, "arid", 0.5, 14, Integer.MIN_VALUE, v -> dryLand(level, v));
        if (found == null) found = UraniumVeinFinder.find(level, "arid", 0.5, 14);
        if (found == null) {
            failure = "no arid vein near the origin";
            return;
        }
        var vein = found.vein();
        double[][] line = vein.centreLine;
        int mid = line.length / 2;
        int from = Math.max(0, mid - 5), to = Math.min(line.length - 1, mid + 5);
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int i = from; i <= to; i++) {
            minX = Math.min(minX, line[i][0]);
            maxX = Math.max(maxX, line[i][0]);
            minZ = Math.min(minZ, line[i][2]);
            maxZ = Math.max(maxZ, line[i][2]);
        }
        int x0 = (int) minX - 14, x1 = (int) maxX + 14, z0 = (int) minZ - 14, z1 = (int) maxZ + 14;
        LOG.info("uranium client check: vein of cell ({}, {}) middle {} {} {}; cut {}..{} x {}..{}", vein.cellX, vein.cellZ,
                (int) line[mid][0], (int) line[mid][1], (int) line[mid][2], x0, x1, z0, z1);
        for (int cx = Math.floorDiv(x0, 16) - 1; cx <= Math.floorDiv(x1, 16) + 1; cx++) {
            for (int cz = Math.floorDiv(z0, 16) - 1; cz <= Math.floorDiv(z1, 16) + 1; cz++) {
                level.getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        // the floor follows the vein: in each column, the height of the nearest point of the centre line
        int dug = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int floor = floorHeight(line, from, to, x + 0.5, z + 0.5);
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = top; y > floor; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                        dug++;
                    }
                }
            }
        }
        // seal the cut: no water or lava may be left in the walls and floor, or it flows into the hole
        int sealed = 0;
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                int floor = floorHeight(line, from, to, Math.max(x0, Math.min(x1, x)) + 0.5, Math.max(z0, Math.min(z1, z)) + 0.5);
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                for (int y = top; y >= floor - 2; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    if (!level.getFluidState(pos).isEmpty()) {
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                        sealed++;
                    } else if (!state.isAir()) {
                        // the picture is about the vein: every other ore and every patch of other rock in the floor and the walls of the cut
                        // becomes plain stone, so the vein's own calcite, tuff and uranium are the only things in it that differ
                        Block replacement = plain(state, y > floor);
                        if (replacement != null) {
                            level.setBlock(pos, replacement.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                        }
                    }
                }
            }
        }
        LOG.info("uranium client check: {} blocks dug, {} fluid blocks sealed", dug, sealed);
        double cx = line[mid][0], cz = line[mid][2];
        int fy = floorHeight(line, from, to, cx, cz);
        double[] t = tangent(line, mid);
        floorAt = new double[] {cx, fy + 1, cz};
        // from the air: back along the vein, high, a little to the side
        double sx = -t[1], sz = t[0];
        // inside the open cut (clamped into it), high above the floor, so the view is not blocked by rock
        double camX = Math.max(x0 + 3, Math.min(x1 - 3, cx - t[0] * 46 + sx * 8)), camZ = Math.max(z0 + 3, Math.min(z1 - 3, cz - t[1] * 46 + sz * 8));
        camera = new double[] {camX, fy + 34, camZ};
        target = new double[] {cx + t[0] * 4, fy - 2, cz + t[1] * 4};
        pitCentre = new double[] {cx, fy, cz};
        tangentAtMiddle = t;
        pit = new double[] {x0, x1, z0, z1};
    }

    private static volatile double[] pitCentre, tangentAtMiddle;

    /** What a block of the cut's floor ({@code above} false) or wall ({@code above} true) is hidden as, or null to keep it. */
    private static Block plain(net.minecraft.world.level.block.state.BlockState state, boolean above) {
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();
        boolean deep = path.startsWith("deepslate_");
        boolean ours = id.getNamespace().equals("bwr");
        if (ours) {
            return above ? Blocks.STONE : null;      // the vein is only ever at or under the floor; blobs in the wall are hidden too
        }
        if (path.endsWith("_ore") || path.startsWith("raw_")) return deep ? Blocks.DEEPSLATE : Blocks.STONE;
        if (state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.INFESTED_STONE)) return Blocks.STONE;
        if (above && (state.is(Blocks.CALCITE) || state.is(Blocks.TUFF) || state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)
                || state.is(Blocks.SMOOTH_BASALT) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN))) return Blocks.STONE;
        if (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.SMOOTH_BASALT)) return Blocks.STONE;
        return null;
    }

    /** True when the middle of a vein lies under dry land: the surface over a grid of points above it is well above sea level. */
    private static boolean dryLand(ServerLevel level, dev.bwr.mod.worldgen.UraniumVeins.Vein vein) {
        double[][] line = vein.centreLine;
        for (int i = Math.max(0, line.length / 2 - 6); i <= Math.min(line.length - 1, line.length / 2 + 6); i += 3) {
            for (int dx = -16; dx <= 16; dx += 16) {
                for (int dz = -16; dz <= 16; dz += 16) {
                    int x = (int) line[i][0] + dx, z = (int) line[i][2] + dz;
                    int h = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
                    if (h < 66) return false;
                }
            }
        }
        return true;
    }

    private static double[] closeCamera() {
        double[] c = pitCentre, t = tangentAtMiddle;
        return new double[] {c[0] - t[0] * 15 - t[1] * 5, c[1] + 11, c[2] - t[1] * 15 + t[0] * 5};
    }

    private static double[] closeTarget() {
        double[] c = pitCentre, t = tangentAtMiddle;
        return new double[] {c[0] + t[0] * 6, c[1], c[2] + t[1] * 6};
    }

    private static double[] tangent(double[][] line, int i) {
        double dx = line[Math.min(line.length - 1, i + 2)][0] - line[Math.max(0, i - 2)][0];
        double dz = line[Math.min(line.length - 1, i + 2)][2] - line[Math.max(0, i - 2)][2];
        double l = Math.hypot(dx, dz);
        return new double[] {dx / l, dz / l};
    }

    /** The y of the vein's centre line at the point of it nearest to (x, z). */
    private static int floorHeight(double[][] line, int from, int to, double x, double z) {
        double best = Double.MAX_VALUE, y = line[from][1];
        for (int i = from; i < to; i++) {
            double ax = line[i][0], az = line[i][2], bx = line[i + 1][0], bz = line[i + 1][2];
            double dx = bx - ax, dz = bz - az;
            double t = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)));
            double d = Math.hypot(x - (ax + t * dx), z - (az + t * dz));
            if (d < best) {
                best = d;
                y = line[i][1] + t * (line[i + 1][1] - line[i][1]);
            }
        }
        return (int) Math.floor(y);
    }

    // ------------------------------------------------------------------------------------------------------------
    // the display of blocks and items, in the air beside the cut

    private static int showX() {
        return (int) pitCentre[0] + 90;
    }

    private static int showZ() {
        return (int) pitCentre[2];
    }

    private static void buildShowcase(ServerLevel level, int current) {
        int x = showX(), z = showZ();
        for (int cx = Math.floorDiv(x - 12, 16); cx <= Math.floorDiv(x + 12, 16); cx++) {
            for (int cz = Math.floorDiv(z - 8, 16); cz <= Math.floorDiv(z + 8, 16); cz++) {
                level.getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        // a floor, and a back wall of smooth stone four blocks behind the row (to the north)
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                level.setBlock(new BlockPos(x + dx, SHOW_Y - 1, z + dz), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int dy = 0; dy <= 6; dy++) {
                    level.setBlock(new BlockPos(x + dx, SHOW_Y + dy, z + dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            for (int dy = 0; dy <= 5; dy++) {
                level.setBlock(new BlockPos(x + dx, SHOW_Y + dy, z - 4), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
            }
        }
        if (current == 2) {
            Block[] top = {Blocks.IRON_ORE, BwrBlocks.URANIUM_ORE.get(), BwrBlocks.RAW_URANIUM_BLOCK.get(), BwrBlocks.URANIUM_BLOCK.get()};
            Block[] bottom = {Blocks.DEEPSLATE_IRON_ORE, BwrBlocks.DEEPSLATE_URANIUM_ORE.get(), Blocks.RAW_IRON_BLOCK, Blocks.IRON_BLOCK};
            for (int i = 0; i < 4; i++) {
                level.setBlock(new BlockPos(x - 3 + i * 2, SHOW_Y + 1, z), top[i].defaultBlockState(), 2);
                level.setBlock(new BlockPos(x - 3 + i * 2, SHOW_Y, z), bottom[i].defaultBlockState(), 2);
            }
        } else {
            String[] row1 = {"bwr:raw_uranium", "bwr:uranium_ingot", "bwr:depleted_uranium", "bwr:plutonium_ingot"};
            String[] row2 = {"bwr:uranium_ore", "bwr:deepslate_uranium_ore", "bwr:raw_uranium_block", "bwr:uranium_block"};
            for (int i = 0; i < 4; i++) {
                icon(level, x - 3 + i * 2 + 0.5, SHOW_Y + 3.6, z - 2.4, row1[i]);
                icon(level, x - 3 + i * 2 + 0.5, SHOW_Y + 1.5, z - 2.4, row2[i]);
            }
        }
    }

    /** An item shown the way the inventory shows it, 1.8 blocks across, always facing the camera. */
    private static void icon(ServerLevel level, double x, double y, double z, String item) {
        var server = level.getServer();
        String command = String.format(java.util.Locale.ROOT,
                "summon minecraft:item_display %.2f %.2f %.2f {item:{id:\"%s\",count:1},item_display:\"gui\",billboard:\"center\","
                        + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[1.8f,1.8f,1.8f]},"
                        + "brightness:{sky:15,block:15}}", x, y, z, item);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(), command);
    }

    private static double[] showCamera(int current) {
        return current == 2 ? new double[] {showX() + 0.5, SHOW_Y + 0.05, showZ() + 4.4} : new double[] {showX() + 0.5, SHOW_Y + 0.9, showZ() + 1.8};
    }

    private static double[] showTarget(int current) {
        return current == 2 ? new double[] {showX() + 0.5, SHOW_Y + 0.9, showZ() + 0.5} : new double[] {showX() + 0.5, SHOW_Y + 2.5, showZ() - 3.0};
    }
}
