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
 * Opt-in real-client pictures of the thorium ore ({@code -PbwrThoriumClientCheck}, see THORIUM.md): a normal generated
 * world (seed 20261022), a thorium super vein located in the highland group from the seed alone, its chunks generated, an
 * open cut dug down to the vein's mid-plane so the ribbon shows in the floor, a gallery driven along the vein (a cave
 * wall with the ore in it), and five 1920 x 1080 pictures: the cut from the air, the ore close up, the ore in the wall of
 * the gallery, the ore blocks beside iron's, and the items. Screenshots go to {@code run/turbineModelCheck/thorium-check/}.
 * Not shipped: the whole devtest package is excluded from the release jar.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class ThoriumClientCheck {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final String[] NAMES = {"thorium-vein-cutaway", "thorium-vein-closeup", "thorium-cave-wall", "thorium-ore-blocks", "thorium-items"};
    private static final int SHOW_Y = 200;

    private static boolean started, requested, prepared;
    private static int age, joined, stage, shown, waited;
    /** Set by the server thread. */
    private static volatile double[] pit, camera, target, floorAt;
    private static volatile String failure;
    private static volatile BlockPos probe;

    private ThoriumClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("bwr.thoriumClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 14_000) throw new IllegalStateException("Thorium client check timed out at stage " + stage);
        if (failure != null) throw new IllegalStateException(failure);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.options.renderDistance().set(8);
            mc.createWorldOpenFlows().createFreshLevel("bwr-thorium-check-" + System.currentTimeMillis(),
                    new LevelSettings("Thorium check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
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
                    LOG.error("thorium client check: prepare failed", t);
                    failure = "prepare failed: " + t;
                }
            });
            return;
        }
        if (pit == null) return;   // the server is still digging
        RenderEnvironment.log("thorium");
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
                    player.removeAllEffects();
                    if (current <= 1) {
                        cam = current == 0 ? camera : closeCamera();
                        tgt = current == 0 ? target : closeTarget();
                        probe = BlockPos.containing(floorAt[0], floorAt[1] - 1, floorAt[2]);
                    } else if (current == 2) {
                        cam = galleryCamera;
                        tgt = galleryTarget;
                        probe = BlockPos.containing(cam[0], cam[1] - 1, cam[2]);
                        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 1_000_000, 0, false, false));
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
                    LOG.error("thorium client check: stage failed", t);
                    failure = "stage " + current + " failed: " + t;
                }
            });
            return;
        }
        BlockPos p = probe;
        boolean ready = p != null && mc.screen == null && mc.level.hasChunkAt(p) && !mc.level.getBlockState(p).isAir() && mc.player.distanceToSqr(p.getX(), p.getY(), p.getZ()) < 150 * 150;
        if (ready && ++shown > 150) {
            var dir = mc.gameDirectory.toPath().resolve("thorium-check");
            Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
            LOG.info("THORIUM CLIENT PASS {}", NAMES[stage]);
            if (++stage == NAMES.length) {
                LOG.info("THORIUM CLIENT COMPLETE");
                mc.stop();
            } else {
                requested = false;
            }
        } else if (!ready && ++waited % 600 == 0) {
            LOG.info("thorium client check: waiting at stage {} (probe {}, chunk there {})", stage, p, p != null && mc.level.hasChunkAt(p));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // server side: find the vein, generate its chunks, dig the cut

    private static void prepare(ServerLevel level) {
        var found = find(level, "highland", 0.25, 14, 12, v -> dryLand(level, v));
        if (found == null) found = find(level, "highland", 0.25, 14, Integer.MIN_VALUE, v -> dryLand(level, v));
        if (found == null) found = find(level, "highland", 0.25, 14, Integer.MIN_VALUE, v -> true);
        if (found == null) {
            failure = "no highland thorium vein near the origin";
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
        LOG.info("thorium client check: vein of cell ({}, {}) middle {} {} {}; cut {}..{} x {}..{}", vein.cellX, vein.cellZ,
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
        LOG.info("thorium client check: {} blocks dug, {} fluid blocks sealed", dug, sealed);
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
        digGallery(level, line, to);
        pit = new double[] {x0, x1, z0, z1};
    }

    /** A gallery driven along the vein past the end of the cut: a cave with the ore in its wall. Sets the camera for picture 3. */
    private static void digGallery(ServerLevel level, double[][] line, int cutEnd) {
        boolean forward = line.length - 1 - cutEnd >= 11;
        int a = forward ? cutEnd + 3 : Math.max(0, line.length / 2 - 5) - 3;
        int b = forward ? Math.min(line.length - 2, a + 7) : Math.max(1, a - 7);
        int lo = Math.min(a, b), hi = Math.max(a, b);
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int i = lo; i <= hi; i++) {
            minX = Math.min(minX, line[i][0]);
            maxX = Math.max(maxX, line[i][0]);
            minZ = Math.min(minZ, line[i][2]);
            maxZ = Math.max(maxZ, line[i][2]);
        }
        int x0 = (int) minX - 6, x1 = (int) maxX + 6, z0 = (int) minZ - 6, z1 = (int) maxZ + 6;
        for (int cx = Math.floorDiv(x0, 16); cx <= Math.floorDiv(x1, 16); cx++) {
            for (int cz = Math.floorDiv(z0, 16); cz <= Math.floorDiv(z1, 16); cz++) {
                level.getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        int yLo = (int) Math.floor(Math.min(line[lo][1], line[hi][1])) - 8, yHi = (int) Math.ceil(Math.max(line[lo][1], line[hi][1])) + 10;
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = yLo; y <= yHi; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getFluidState(pos).isEmpty()) level.setBlock(pos, Blocks.STONE.defaultBlockState(), flags);
                }
            }
        }
        // a floor that follows the centre line, 5 wide and 4 high
        int carved = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int floor = floorHeight(line, lo, hi, x + 0.5, z + 0.5);
                double d = distanceToLine(line, lo, hi, x + 0.5, z + 0.5);
                if (d > 2.6) continue;
                for (int y = floor; y <= floor + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
                        carved++;
                    }
                }
                if (d > 1.6 && ((x + z) % 5 == 0)) {
                    level.setBlock(new BlockPos(x, floor, z), Blocks.TORCH.defaultBlockState(), flags);
                }
            }
        }
        LOG.info("thorium client check: gallery of {} segments, {} blocks carved", hi - lo, carved);
        double[] t = new double[] {line[hi][0] - line[lo][0], line[hi][2] - line[lo][2]};
        double l = Math.hypot(t[0], t[1]);
        t[0] /= l;
        t[1] /= l;
        int mid = (lo + hi) / 2;
        double fy = floorHeight(line, lo, hi, line[mid][0], line[mid][2]);
        galleryCamera = new double[] {line[mid][0] - t[0] * 5, fy + 0.1, line[mid][2] - t[1] * 5};
        galleryTarget = new double[] {line[mid][0] + t[0] * 7 - t[1] * 2.6, fy + 1.4, line[mid][2] + t[1] * 7 + t[0] * 2.6};
    }

    private static volatile double[] galleryCamera, galleryTarget;

    private static double distanceToLine(double[][] line, int from, int to, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = from; i < to; i++) {
            double ax = line[i][0], az = line[i][2], bx = line[i + 1][0], bz = line[i + 1][2];
            double dx = bx - ax, dz = bz - az;
            double t = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(x - (ax + t * dx), z - (az + t * dz)));
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------------
    // finding a thorium vein from the seed alone (the same method as UraniumVeinFinder, with the thorium groups)

    private static dev.bwr.mod.worldgen.UraniumVeins.Geometry geometry(ServerLevel level) {
        var holder = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.CONFIGURED_FEATURE,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bwr", "thorium_vein_highland")));
        return ((dev.bwr.mod.worldgen.UraniumVeinConfig) holder.value().config()).geometry();
    }

    private static boolean inGroup(ServerLevel level, String group, int x, int z) {
        var source = level.getChunkSource();
        var generator = source.getGenerator();
        var random = source.randomState();
        int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        var biome = generator.getBiomeSource().getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(y),
                net.minecraft.core.QuartPos.fromBlock(z), random.sampler());
        return biome.is(net.minecraft.tags.TagKey.create(Registries.BIOME, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bwr", "has_thorium/" + group)));
    }

    private static UraniumVeinFinder.Found find(ServerLevel level, String group, double maxRoll, int radiusCells, int minMiddleY,
                                                java.util.function.Predicate<dev.bwr.mod.worldgen.UraniumVeins.Vein> accept) {
        var geometry = geometry(level);
        UraniumVeinFinder.Found best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int cx = -radiusCells; cx <= radiusCells; cx++) {
            for (int cz = -radiusCells; cz <= radiusCells; cz++) {
                var vein = dev.bwr.mod.worldgen.UraniumVeins.vein(level.getSeed(), cx, cz, geometry);
                if (vein.roll >= maxRoll) continue;
                double[][] line = vein.centreLine;
                double[] a = line[line.length / 4], b = line[line.length / 2], c = line[line.length * 3 / 4];
                double distance = Math.hypot(b[0], b[2]);
                if (distance >= bestDistance || b[1] < minMiddleY || !accept.test(vein)) continue;
                if (inGroup(level, group, (int) a[0], (int) a[2]) && inGroup(level, group, (int) b[0], (int) b[2]) && inGroup(level, group, (int) c[0], (int) c[2])) {
                    best = new UraniumVeinFinder.Found(vein, group, Math.floorDiv((int) vein.minX, 16), Math.floorDiv((int) vein.minZ, 16),
                            Math.floorDiv((int) vein.maxX, 16), Math.floorDiv((int) vein.maxZ, 16), b);
                    bestDistance = distance;
                }
            }
        }
        return best;
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
        if (current == 3) {
            Block[] top = {Blocks.IRON_ORE, BwrBlocks.THORIUM_ORE.get(), BwrBlocks.RAW_THORIUM_BLOCK.get(), BwrBlocks.THORIUM_BLOCK.get(), BwrBlocks.MONAZITE_SAND.get()};
            Block[] bottom = {Blocks.DEEPSLATE_IRON_ORE, BwrBlocks.DEEPSLATE_THORIUM_ORE.get(), BwrBlocks.URANIUM_ORE.get(), BwrBlocks.DEEPSLATE_URANIUM_ORE.get(), Blocks.SAND};
            for (int i = 0; i < 5; i++) {
                level.setBlock(new BlockPos(x - 4 + i * 2, SHOW_Y + 1, z), top[i].defaultBlockState(), 2);
                level.setBlock(new BlockPos(x - 4 + i * 2, SHOW_Y, z), bottom[i].defaultBlockState(), 2);
            }
        } else {
            String[] row1 = {"bwr:raw_thorium", "bwr:thorium_ingot", "bwr:thorium_nugget", "bwr:rare_earth_concentrate"};
            String[] row2 = {"bwr:thorium_ore", "bwr:deepslate_thorium_ore", "bwr:monazite_sand", "bwr:raw_thorium_block"};
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
        return current == 3 ? new double[] {showX() + 0.5, SHOW_Y + 0.05, showZ() + 4.8} : new double[] {showX() + 0.5, SHOW_Y + 0.9, showZ() + 1.8};
    }

    private static double[] showTarget(int current) {
        return current == 3 ? new double[] {showX() + 0.5, SHOW_Y + 0.9, showZ() + 0.5} : new double[] {showX() + 0.5, SHOW_Y + 2.5, showZ() - 3.0};
    }
}
