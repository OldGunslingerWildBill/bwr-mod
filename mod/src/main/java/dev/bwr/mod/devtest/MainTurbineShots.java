package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
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

/**
 * Pictures of the main turbine train (HP, LP, generator on one shaft, steam pipes on the HP and LP ports) from several angles,
 * then the LP with its hood off. Test harness only, for model reviews:
 * {@code :mod:runTurbineModelCheck -PbwrMainTurbineShots=<label>} saves run/turbineModelCheck/main-turbine-shots/<label>/.
 * Fails if a port's first pipe does not join its flange face.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class MainTurbineShots {
    /** The LP's root; the HP's is 9 west, the generator's 8 east (all facing east, the shaft along x), as in the turbine hall check. */
    private static final BlockPos LP = new BlockPos(200, 120, 200);
    /** Name, then the point looked at and the camera, both relative to the LP root's corner. */
    private static final Object[][] VIEWS = {
            {"train-north-west", new double[]{-2, 2.5, .5}, new double[]{-17, 10, -14}},
            {"train-south-east", new double[]{-1, 2.5, .5}, new double[]{13, 9, 15}},
            {"train-north-side", new double[]{-1, 2.3, .5}, new double[]{-1, 4, -17}},
            {"hp-north-west", new double[]{-8.5, 2.4, .5}, new double[]{-15.5, 7.5, -6.5}},
            {"hp-south-high", new double[]{-8.5, 2.4, .5}, new double[]{-4.5, 9.5, 7.5}},
            {"lp-north-east", new double[]{.5, 2.4, .5}, new double[]{7.5, 7.5, -7.5}},
            {"lp-south-west", new double[]{.5, 2.4, .5}, new double[]{-6, 6.5, 8}},
            {"lp-open-high", new double[]{.5, 1.8, .5}, new double[]{5.5, 10, -6.5}},
            {"lp-open-side", new double[]{.5, 1.8, .5}, new double[]{.5, 8.5, -7}},
    };
    private static final int OPEN_FROM = 7;
    private static boolean started, built, requested;
    private static int age, joined, stage, shown;

    private MainTurbineShots() {}

    private static String label() { return System.getProperty("bwr.mainTurbineShots", ""); }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (label().isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (++age > 12000) throw new IllegalStateException("Main turbine shots timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(60);
            mc.createWorldOpenFlows().createFreshLevel("bwr-main-turbine-shots-" + System.currentTimeMillis(),
                    new LevelSettings("Main turbine shots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var view = VIEWS[stage];
        double[] t = (double[]) view[1], c = (double[]) view[2];
        Vec3 centre = new Vec3(LP.getX() + t[0], LP.getY() + t[1], LP.getZ() + t[2]);
        Vec3 camera = new Vec3(LP.getX() + c[0], LP.getY() + c[1], LP.getZ() + c[2]);
        var delta = centre.subtract(camera);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        if (!requested) {
            requested = true;
            shown = 0;
            boolean build = !built, open = stage == OPEN_FROM;
            built = true;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (build) build(l);
                if (open && l.getBlockEntity(LP) instanceof PowerModuleBlockEntity lp) lp.setCoverOff(true);
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
        if (++shown < (stage == 0 ? 220 : stage == OPEN_FROM ? 160 : 110)) return;
        var dir = mc.gameDirectory.toPath().resolve("main-turbine-shots").resolve(label());
        java.nio.file.Files.createDirectories(dir);
        var file = dir.resolve(view[0] + ".png");
        CheckShots.save(mc, file);
        LogUtils.getLogger().info("MAIN TURBINE SHOT: {}", file.toAbsolutePath());
        requested = false;
        if (++stage == VIEWS.length) {
            LogUtils.getLogger().info("MAIN TURBINE SHOTS PASS: {} pictures, every port's first pipe joined to its flange", stage);
            mc.options.hideGui = false;
            mc.stop();
        }
    }

    private static void build(ServerLevel l) {
        for (int x = 10; x <= 14; x++) for (int z = 10; z <= 14; z++) l.setChunkForced(x, z, true);
        for (var p : BlockPos.betweenClosed(LP.offset(-26, -1, -22), LP.offset(22, -1, 22))) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        var hp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), LP.west(9), Direction.EAST);
        var lp = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), LP, Direction.EAST);
        PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), LP.east(8), Direction.EAST);
        for (var m : new PowerModuleBlockEntity[]{hp, lp}) {
            var block = (PowerModuleBlock) m.getBlockState().getBlock();
            for (var role : AssemblyPort.values())
                if (block.hasPort(role)) pipes(l, block.portPosition(m.getBlockPos(), m.getBlockState(), role), block.portFace(m.getBlockState(), role), role);
        }
    }

    /** Two pipe segments outward from a port; the first must connect back to the machine face. */
    private static void pipes(ServerLevel l, BlockPos port, Direction face, AssemblyPort role) {
        for (int n = 1; n <= 2; n++) {
            var p = port.relative(face, n);
            l.setBlock(p, role.isSteam() ? BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l, p)
                    : BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l, p), 3);
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
