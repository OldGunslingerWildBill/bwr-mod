package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Pictures of the three turbine-driven pumps (feed pump turbine, RCIC, HPCI) in a real world, each with short steam and
 * water pipes on all four ports, from three angles. Test harness only:
 * {@code :mod:runTurbineModelCheck -PbwrTurbinePumpShots=<label>} saves run/turbineModelCheck/turbine-pump-shots/<label>/.
 * The check also fails if a port's first pipe does not connect to its flange face.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class TurbinePumpShots {
    private static final BlockPos TFP = new BlockPos(200, 120, 200), RCIC = new BlockPos(200, 120, 214), HPCI = new BlockPos(200, 120, 228);
    private static final String[] MACHINES = {"rfpt", "rcic", "hpci"};
    private static final String[] VIEWS = {"nw", "se", "n"};
    private static boolean started, built, requested;
    private static int age, joined, stage, shown;

    private TurbinePumpShots() {}

    private static String label() { return System.getProperty("bwr.turbinePumpShots", ""); }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (label().isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (++age > 9000) throw new IllegalStateException("Turbine pump shots timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-turbine-pump-shots-" + System.currentTimeMillis(),
                    new LevelSettings("Turbine pump shots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        int machine = stage / VIEWS.length, view = stage % VIEWS.length;
        Vec3 centre = switch (machine) {
            case 0 -> new Vec3(TFP.getX() + .5, TFP.getY() + 1.4, TFP.getZ() + .5);
            case 1 -> new Vec3(RCIC.getX() + 2.5, RCIC.getY() + 1.6, RCIC.getZ() + 2.5);
            default -> new Vec3(HPCI.getX() + 3.5, HPCI.getY() + 1.9, HPCI.getZ() + 2.5);
        };
        double span = machine == 0 ? 9 : machine == 1 ? 5.5 : 7.5;
        Vec3 dir = switch (view) {
            case 0 -> new Vec3(-.75, .62, -1.0);
            case 1 -> new Vec3(.85, .5, 1.0);
            default -> new Vec3(.25, .2, -1.0);
        };
        Vec3 camera = centre.add(dir.normalize().scale(span * 1.25));
        var delta = centre.subtract(camera);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        if (!requested) {
            requested = true;
            shown = 0;
            boolean build = !built;
            built = true;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (build) {
                    for (int x = 11; x <= 13; x++) for (int z = 11; z <= 15; z++) l.setChunkForced(x, z, true);
                    for (var p : BlockPos.betweenClosed(185, 119, 190, 222, 119, 242)) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                    var tfp = BwrBlocks.TURBINE_FEED_PUMP.get();
                    var ts = tfp.placementState().setValue(PumpAssemblyBlock.FACING, Direction.NORTH);
                    l.setBlock(TFP, ts, 3);
                    tfp.setPlacedBy(l, TFP, ts, null, new ItemStack(tfp));
                    if (!tfp.complete(l, TFP, ts)) throw new IllegalStateException("Feed pump turbine did not assemble");
                    for (var role : AssemblyPort.values()) if (tfp.hasPort(role)) pipes(l, tfp.portPosition(TFP, ts, role), tfp.portFace(ts, role), role);
                    for (var pair : new Object[][]{{BwrBlocks.RCIC_TWL.get(), RCIC}, {BwrBlocks.HPCI_TURBINE.get(), HPCI}}) {
                        var block = (TurbineAssemblyBlock) pair[0];
                        var root = (BlockPos) pair[1];
                        var state = block.placementState();
                        l.setBlock(root, state, 3);
                        block.setPlacedBy(l, root, state, null, new ItemStack(block));
                        for (var role : AssemblyPort.values()) if (block.hasPort(role)) pipes(l, block.portPosition(root, state, role), block.portFace(state, role), role);
                    }
                }
                l.setDayTime(6000);
                l.setWeatherParameters(6000, 0, false, false);
                player.connection.teleport(camera.x, camera.y - player.getEyeHeight(), camera.z, yaw, pitch);
            });
        }
        mc.player.getAbilities().flying = true;
        mc.player.setPos(camera.x, camera.y - mc.player.getEyeHeight(), camera.z);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.options.hideGui = true;
        mc.getToasts().clear();
        if (++shown < (stage == 0 ? 200 : 110)) return;
        var dir0 = mc.gameDirectory.toPath().resolve("turbine-pump-shots").resolve(label());
        java.nio.file.Files.createDirectories(dir0);
        var file = dir0.resolve(MACHINES[machine] + "-" + VIEWS[view] + ".png");
        CheckShots.save(mc, file);
        LogUtils.getLogger().info("TURBINE PUMP SHOT: {}", file.toAbsolutePath());
        requested = false;
        if (++stage == MACHINES.length * VIEWS.length) {
            LogUtils.getLogger().info("TURBINE PUMP SHOTS PASS: {} pictures, every port's first pipe joined to its flange", stage);
            mc.options.hideGui = false;
            mc.stop();
        }
    }

    /** Two pipe segments outward from a port; the first must connect back to the machine face. */
    private static void pipes(ServerLevel l, BlockPos port, Direction face, AssemblyPort role) {
        for (int n = 1; n <= 2; n++) {
            var p = port.relative(face, n);
            BlockState pipe = role.isSteam() ? BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l, p)
                    : BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, p);
            l.setBlock(p, pipe, 3);
        }
        var first = l.getBlockState(port.relative(face));
        var property = switch (face.getOpposite()) {
            case DOWN -> net.minecraft.world.level.block.PipeBlock.DOWN;
            case UP -> net.minecraft.world.level.block.PipeBlock.UP;
            case NORTH -> net.minecraft.world.level.block.PipeBlock.NORTH;
            case SOUTH -> net.minecraft.world.level.block.PipeBlock.SOUTH;
            case WEST -> net.minecraft.world.level.block.PipeBlock.WEST;
            case EAST -> net.minecraft.world.level.block.PipeBlock.EAST;
        };
        if (!first.hasProperty(property) || !first.getValue(property))
            throw new IllegalStateException("Pipe does not join the " + role + " flange at " + port + " " + face + ": " + first);
    }
}
