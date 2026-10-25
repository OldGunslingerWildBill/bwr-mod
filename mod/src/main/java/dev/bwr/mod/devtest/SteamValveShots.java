package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.AdsReliefValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.SafetyReliefValveBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
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
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Pictures of the main steam valves in a real world with their steam pipes attached: a closed and an open MSIV side by
 * side, and a closed and an open safety relief valve side by side, four angles each. Test harness only:
 * {@code :mod:runTurbineModelCheck -PbwrSteamValveShots=<label>} saves run/turbineModelCheck/steam-valve-shots/<label>/.
 * The check fails if a valve's first pipe does not join its flange face, or if a valve is not in the state it is shown in.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class SteamValveShots {
    private static final BlockPos MSIV_SHUT = new BlockPos(197, 120, 197), MSIV_OPEN = new BlockPos(200, 120, 197);
    private static final BlockPos SRV_SHUT = new BlockPos(198, 122, 202), SRV_OPEN = new BlockPos(198, 122, 205);
    private static final String[] MACHINES = {"msiv", "srv"};
    private static final String[] VIEWS = {"nw", "se", "low", "close"};
    private static boolean started, built, requested;
    private static int age, joined, stage, shown;

    private SteamValveShots() {}

    private static String label() { return System.getProperty("bwr.steamValveShots", ""); }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (label().isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (++age > 9000) throw new IllegalStateException("Steam valve shots timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(50);
            mc.createWorldOpenFlows().createFreshLevel("bwr-steam-valve-shots-" + System.currentTimeMillis(),
                    new LevelSettings("Steam valve shots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        int machine = stage / VIEWS.length, view = stage % VIEWS.length;
        Vec3 centre;
        double span;
        if (view == 3) {
            // Close look at the open valve, from the north-east.
            centre = machine == 0 ? new Vec3(MSIV_OPEN.getX() + .5, MSIV_OPEN.getY() + 1.7, MSIV_OPEN.getZ() + .5)
                    : new Vec3(SRV_OPEN.getX() + .5, SRV_OPEN.getY() + .5, SRV_OPEN.getZ() + .5);
            span = machine == 0 ? 3.0 : 1.7;
        } else {
            centre = machine == 0 ? new Vec3(199.0, 121.5, 197.5) : new Vec3(198.3, 122.1, 204.0);
            span = machine == 0 ? 5.0 : 4.2;
        }
        Vec3 dir = switch (view) {
            case 0 -> new Vec3(-.75, .62, -1.0);
            case 1 -> new Vec3(.85, .5, 1.0);
            case 2 -> machine == 0 ? new Vec3(-1.0, .12, -.25) : new Vec3(1.0, .12, .3);
            default -> new Vec3(1.0, .45, -.8);
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
                if (build) build(l);
                // Keep the open MSIV's actuator charged; holding open costs FE every tick.
                if (l.getBlockEntity(MSIV_OPEN) instanceof MainSteamIsolationValveBlockEntity be)
                    be.energy().receiveEnergy(MainSteamIsolationValveBlockEntity.ENERGY_CAPACITY_FE, false);
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
        if (++shown < (stage == 0 ? 220 : 110)) return;
        if (stage == 0) verifyStates(mc);
        var dir0 = mc.gameDirectory.toPath().resolve("steam-valve-shots").resolve(label());
        java.nio.file.Files.createDirectories(dir0);
        var file = dir0.resolve(MACHINES[machine] + "-" + VIEWS[view] + ".png");
        CheckShots.save(mc, file);
        LogUtils.getLogger().info("STEAM VALVE SHOT: {}", file.toAbsolutePath());
        requested = false;
        if (++stage == MACHINES.length * VIEWS.length) {
            LogUtils.getLogger().info("STEAM VALVE SHOTS PASS: {} pictures, every valve port's first pipe joined to its flange", stage);
            mc.options.hideGui = false;
            mc.stop();
        }
    }

    private static void build(ServerLevel l) {
        // Everything stands in one chunk (192..207): in a fresh flat world the neighbouring chunks came out unlit at
        // this height in the first run, which made the pictures useless.
        for (int x = 11; x <= 13; x++) for (int z = 11; z <= 13; z++) l.setChunkForced(x, z, true);
        for (var p : BlockPos.betweenClosed(192, 119, 192, 207, 119, 207)) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        var msiv = BwrBlocks.MSIV.get();
        for (var root : new BlockPos[]{MSIV_SHUT, MSIV_OPEN}) {
            var state = msiv.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
            // Redstone under the base commands the closed valve shut; the other one is commanded open and charged.
            if (root.equals(MSIV_SHUT)) l.setBlock(root.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            l.setBlock(root, state, 3);
            msiv.setPlacedBy(l, root, state, null, new ItemStack(msiv));
            if (root.equals(MSIV_OPEN) && l.getBlockEntity(root) instanceof MainSteamIsolationValveBlockEntity be) {
                be.energy().receiveEnergy(MainSteamIsolationValveBlockEntity.ENERGY_CAPACITY_FE, false);
                be.setDemandOpen(true);
            }
            for (var face : new Direction[]{Direction.NORTH, Direction.SOUTH}) pipes(l, root, face);
        }
        var srv = BwrBlocks.ADS_RELIEF_VALVE.get();
        for (var root : new BlockPos[]{SRV_SHUT, SRV_OPEN}) {
            l.setBlock(root, srv.defaultBlockState().setValue(AdsReliefValveBlock.FACING, Direction.NORTH), 3);
            for (var face : new Direction[]{Direction.WEST, Direction.DOWN}) pipes(l, root, face);
            if (root.equals(SRV_OPEN) && l.getBlockEntity(root) instanceof SafetyReliefValveBlockEntity be) {
                be.setComputerControlled(true);
                be.setOpen(true);
            }
        }
    }

    private static void verifyStates(Minecraft mc) {
        var l = mc.level;
        for (int part = 0; part < 3; part++) {
            if (!l.getBlockState(MSIV_SHUT.above(part)).is(BwrBlocks.MSIV.get()) || !l.getBlockState(MSIV_OPEN.above(part)).is(BwrBlocks.MSIV.get()))
                throw new IllegalStateException("MSIV did not assemble, part " + part);
        }
        if (l.getBlockState(MSIV_SHUT).getValue(MainSteamIsolationValveBlock.OPEN) || !l.getBlockState(MSIV_OPEN).getValue(MainSteamIsolationValveBlock.OPEN))
            throw new IllegalStateException("MSIV pair not shown shut/open: " + l.getBlockState(MSIV_SHUT) + " / " + l.getBlockState(MSIV_OPEN));
        if (l.getBlockState(SRV_SHUT).getValue(SafetyReliefValveBlock.OPEN) || !l.getBlockState(SRV_OPEN).getValue(SafetyReliefValveBlock.OPEN))
            throw new IllegalStateException("Relief valve pair not shown shut/open");
        LogUtils.getLogger().info("STEAM VALVE SHOTS: MSIV {} / {}; relief valve {} / {}", l.getBlockState(MSIV_SHUT.above(2)),
                l.getBlockState(MSIV_OPEN.above(2)), l.getBlockState(SRV_SHUT), l.getBlockState(SRV_OPEN));
    }

    /** Two steam tube segments outward from a valve face; the first must connect back to the valve. */
    private static void pipes(ServerLevel l, BlockPos valve, Direction face) {
        for (int n = 1; n <= 2; n++) {
            var p = valve.relative(face, n);
            l.setBlock(p, BwrBlocks.PRESSURISED_TUBE.get().stateWithConnections(l, p), 3);
        }
        var first = l.getBlockState(valve.relative(face));
        var property = PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite());
        if (!first.hasProperty(property) || !first.getValue(property))
            throw new IllegalStateException("Pipe does not join the valve flange at " + valve + " " + face + ": " + first);
    }
}
