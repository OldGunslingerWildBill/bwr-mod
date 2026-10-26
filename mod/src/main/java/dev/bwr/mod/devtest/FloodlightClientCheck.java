package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.FacingBeamLampBlock;
import dev.bwr.mod.floodlight.HpsFlatPanelBlock;
import dev.bwr.mod.floodlight.XenonFloodlightBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in real-client pictures of the beam lamps ({@code -PbwrFloodlightClientCheck}): a closed, dark hall 40 x 12 x 40 (a
 * turbine-hall-like space with a pool, machinery and a gallery) lit only by four xenon floodlights on the walls, four xenon
 * high-bays and four HPS flat panels under the ceiling. Pictures of every lamp on from three places, then each type alone
 * from the same place, then all off, saved at 1920 x 1080 to {@code run/turbineModelCheck/floodlight-check/<vanilla|shaders>/}
 * (the folder says whether a shader pack was in use). Not shipped: the devtest package is excluded from the release jar.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class FloodlightClientCheck {
    /** The floor's top surface is at Y; the inside is x and z 1 to 40, y Y to Y + 11; the ceiling is Y + 12. */
    private static final int Y = 120;
    private static final int IN = 40;

    private record Stage(String name, boolean flood, boolean bay, boolean hps, Vec3 camera, Vec3 target) {
    }

    private static final Vec3 CORNER = new Vec3(3.5, Y + 9.0, 3.5), CORNER_TARGET = new Vec3(27.5, Y, 27.5);
    private static final Vec3 FLOOR = new Vec3(18.5, Y + 0.2, 2.5), FLOOR_TARGET = new Vec3(12.5, Y + 1.5, 30.5);
    private static final Vec3 CEILING = new Vec3(17.5, Y + 7.5, 14.5), CEILING_TARGET = new Vec3(15.0, Y + 11.0, 8.5);
    private static final Vec3 FLOODLIGHT = new Vec3(24.5, Y + 6.5, 7.5), FLOODLIGHT_TARGET = new Vec3(20.5, Y + 8.6, 1.5);
    private static final Vec3 GALLERY = new Vec3(38.5, Y + 6.0, 38.5), GALLERY_TARGET = new Vec3(12.5, Y + 1.0, 12.5);
    private static final Stage[] STAGES = {
            new Stage("room-all-lamps-corner", true, true, true, CORNER, CORNER_TARGET),
            new Stage("room-all-lamps-floor", true, true, true, FLOOR, FLOOR_TARGET),
            new Stage("room-all-lamps-gallery", true, true, true, GALLERY, GALLERY_TARGET),
            new Stage("closeup-high-bay-and-hps-panel", true, true, true, CEILING, CEILING_TARGET),
            new Stage("closeup-xenon-floodlight", true, true, true, FLOODLIGHT, FLOODLIGHT_TARGET),
            new Stage("room-xenon-floodlights-only", true, false, false, CORNER, CORNER_TARGET),
            new Stage("room-xenon-high-bays-only", false, true, false, CORNER, CORNER_TARGET),
            new Stage("room-hps-flat-panels-only", false, false, true, CORNER, CORNER_TARGET),
            new Stage("room-all-off", false, false, false, CORNER, CORNER_TARGET),
    };

    private static boolean started, requested, built;
    private static int age, joined, stage, shown;

    private FloodlightClientCheck() {}

    // The fixtures: a lamp, the cell its redstone block goes into (in the wall or the ceiling), and what that cell is when off.
    private record Fixture(BlockPos lamp, BlockPos attached, BlockPos lever, BlockState leverOff) {
    }

    private static final List<Fixture> FLOODS = new ArrayList<>(), BAYS = new ArrayList<>(), PANELS = new ArrayList<>();

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!Boolean.getBoolean("bwr.floodlightClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 6000) throw new IllegalStateException("Floodlight client check timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.enableVsync().set(false);
            mc.options.renderDistance().set(8);
            mc.createWorldOpenFlows().createFreshLevel("bwr-floodlight-check-" + System.currentTimeMillis(),
                    new LevelSettings("Floodlight check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                            WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 100) return;
        RenderEnvironment.log("FloodlightClientCheck");
        if (!requested) {
            requested = true;
            shown = 0;
            mc.setScreen(null);
            mc.options.hideGui = true;
            Stage st = STAGES[stage];
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                ServerLevel level = player.serverLevel();
                level.setDayTime(18000);
                level.setWeatherParameters(6000, 0, false, false);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                if (!built) {
                    built = true;
                    buildHall(level);
                }
                switchAll(level, FLOODS, st.flood());
                switchAll(level, BAYS, st.bay());
                switchAll(level, PANELS, st.hps());
                Vec3 delta = st.target().subtract(st.camera().add(0, player.getEyeHeight(), 0));
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.connection.teleport(st.camera().x, st.camera().y, st.camera().z, yaw, pitch);
            });
        }
        Stage st = STAGES[stage];
        // Keep the view on the stage's target (a mouse moving over the window would turn it otherwise).
        Vec3 d = st.target().subtract(st.camera().add(0, mc.player.getEyeHeight(), 0));
        mc.player.setYRot((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        mc.player.yRotO = mc.player.getYRot();
        mc.player.xRotO = mc.player.getXRot();
        boolean ready = mc.screen == null && Math.abs(mc.player.getY() - st.camera().y) < 0.5
                && mc.level.getBlockEntity(FLOODS.isEmpty() ? BlockPos.ZERO : FLOODS.get(0).lamp()) instanceof BeamLampBlockEntity lamp
                && lamp.lit() == st.flood();
        if (ready && ++shown > 120) {
            String kind = RenderEnvironment.shaderPackInUse() ? "shaders" : "vanilla";
            var dir = mc.gameDirectory.toPath().resolve("floodlight-check").resolve(kind);
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(st.name() + ".png"));
            LogUtils.getLogger().info("FLOODLIGHT CLIENT PASS {} {}", kind, st.name());
            if (++stage == STAGES.length) {
                LogUtils.getLogger().info("FLOODLIGHT CLIENT COMPLETE");
                mc.stop();
            } else {
                requested = false;
            }
        }
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** The hall: shell, floor pattern, a pool, machinery, a gallery, and the twelve lamps (all off). */
    private static void buildHall(ServerLevel level) {
        BlockState wall = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState ceiling = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int x = 0; x <= IN + 1; x++) {
            for (int z = 0; z <= IN + 1; z++) {
                for (int y = Y - 1; y <= Y + 12; y++) {
                    boolean shell = x == 0 || z == 0 || x == IN + 1 || z == IN + 1 || y == Y - 1 || y == Y + 12;
                    BlockState s;
                    if (!shell) {
                        s = Blocks.AIR.defaultBlockState();
                    } else if (y == Y - 1) {
                        s = (x / 4 + z / 4) % 2 == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
                    } else if (y == Y + 12) {
                        s = (x % 8 == 0 || z % 8 == 0) ? Blocks.GRAY_CONCRETE.defaultBlockState() : ceiling;
                    } else if (y == Y) {
                        s = Blocks.YELLOW_CONCRETE.defaultBlockState();
                    } else if (y == Y + 1) {
                        s = Blocks.BLACK_CONCRETE.defaultBlockState();
                    } else {
                        s = (x % 8 == 0 && z % 8 == 0) ? Blocks.GRAY_CONCRETE.defaultBlockState()
                                : (x % 8 == 0 || z % 8 == 0) && (x == 0 || x == IN + 1 || z == 0 || z == IN + 1) ? Blocks.GRAY_CONCRETE.defaultBlockState() : wall;
                    }
                    set(level, x, y, z, s);
                }
            }
        }
        // A pool, three deep, in the south-west quarter.
        for (int x = 4; x <= 15; x++) {
            for (int z = 24; z <= 36; z++) {
                boolean rim = x == 4 || x == 15 || z == 24 || z == 36;
                for (int y = Y - 3; y <= Y - 1; y++) {
                    set(level, x, y, z, rim ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
                }
                set(level, x, Y - 4, z, Blocks.WHITE_CONCRETE.defaultBlockState());
                if (rim) {
                    set(level, x, Y, z, Blocks.IRON_BARS.defaultBlockState());
                }
            }
        }
        // Machinery: two long casings (a turbine-generator train) and a row of tanks.
        for (int x = 22; x <= 37; x++) {
            for (int z = 26; z <= 30; z++) {
                for (int y = Y; y <= Y + 3; y++) {
                    boolean shellBlock = y == Y + 3 || z == 26 || z == 30 || x == 22 || x == 37;
                    if (shellBlock) {
                        set(level, x, y, z, (x % 5 == 0) ? Blocks.CYAN_TERRACOTTA.defaultBlockState() : Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState());
                    }
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            int cx = 25 + i * 4;
            for (int y = Y; y <= Y + 5; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) < 2 || y == Y + 5) {
                            set(level, cx + dx, y, 8 + dz, Blocks.IRON_BLOCK.defaultBlockState());
                        }
                    }
                }
            }
        }
        // A gallery along the east wall at Y + 5, with a railing.
        for (int z = 1; z <= IN; z++) {
            for (int x = 38; x <= 40; x++) {
                set(level, x, Y + 4, z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
            }
            set(level, 37, Y + 5, z, Blocks.IRON_BARS.defaultBlockState());
        }

        // Xenon floodlights on the four walls, high up on a bracket, aimed down 30 degrees into the hall.
        flood(level, new BlockPos(20, Y + 8, 1), Direction.SOUTH, new BlockPos(20, Y + 8, 0));
        flood(level, new BlockPos(21, Y + 8, IN), Direction.NORTH, new BlockPos(21, Y + 8, IN + 1));
        flood(level, new BlockPos(1, Y + 8, 21), Direction.EAST, new BlockPos(0, Y + 8, 21));
        flood(level, new BlockPos(IN, Y + 8, 20), Direction.WEST, new BlockPos(IN + 1, Y + 8, 20));
        // High-bays over the four quarters, HPS panels over the middle of each side.
        for (int[] p : new int[][] {{11, 11}, {30, 11}, {11, 30}, {30, 30}}) {
            fixed(level, BAYS, BwrBlocks.XENON_HIGH_BAY.get(), p[0], p[1]);
        }
        for (int[] p : new int[][] {{20, 6}, {21, 35}, {6, 21}, {35, 20}}) {
            fixed(level, PANELS, BwrBlocks.HPS_FLAT_PANEL.get(), p[0], p[1]);
        }
    }

    /** A floodlight on a bracket; {@code power} is the wall block behind it. */
    private static void flood(ServerLevel level, BlockPos lamp, Direction facing, BlockPos power) {
        set(level, lamp.getX(), lamp.getY() - 1, lamp.getZ(), Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlock(lamp, BwrBlocks.XENON_FLOODLIGHT.get().defaultBlockState().setValue(XenonFloodlightBlock.FACING, facing), 3);
        BeamLampBlockEntity be = (BeamLampBlockEntity) level.getBlockEntity(lamp);
        while (be.pitchDegrees() != -30) {
            be.stepAim(true);
        }
        // A lever on the outside of the wall block behind the lamp powers that block, and the block powers the lamp.
        Direction out = facing.getOpposite();
        FLOODS.add(new Fixture(lamp, power, power.relative(out), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                .setValue(net.minecraft.world.level.block.LeverBlock.FACING, out)));
    }

    private static void fixed(ServerLevel level, List<Fixture> into, Block block, int x, int z) {
        BlockPos lamp = new BlockPos(x, Y + 11, z);
        level.setBlock(lamp, block.defaultBlockState().setValue(FacingBeamLampBlock.FACING, Direction.DOWN), 3);
        BlockPos ceilingBlock = lamp.above();
        into.add(new Fixture(lamp, ceilingBlock, ceilingBlock.above(), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)));
    }

    /** Throws the lever outside the wall or on the ceiling behind each lamp of a group, as a player would. */
    private static void switchAll(ServerLevel level, List<Fixture> group, boolean on) {
        for (Fixture f : group) {
            level.setBlock(f.lever(), f.leverOff().setValue(net.minecraft.world.level.block.LeverBlock.POWERED, on), Block.UPDATE_ALL);
            level.updateNeighborsAt(f.lever(), Blocks.LEVER);
            level.updateNeighborsAt(f.attached(), Blocks.LEVER);
            if (level.getBlockEntity(f.lamp()) instanceof BeamLampBlockEntity be) {
                be.reconcile();
            }
            BlockState state = level.getBlockState(f.lamp());
            if (on && state.getBlock() instanceof HpsFlatPanelBlock panel) {
                // The pictures show a warmed-up panel: run its three warm-up ticks now instead of waiting 20 s.
                for (int i = 0; i < 3; i++) {
                    panel.tick(level.getBlockState(f.lamp()), level, f.lamp(), RandomSource.create(1));
                }
            }
        }
    }
}
