package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.accident.CoriumBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
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

import java.io.IOException;
import java.nio.file.Files;

/** Disposable client showcase of alpha.31 hardware. Only active with the explicit dev-run flag. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class ContainmentClientCheck {
    private static final BlockPos VAULT3 = new BlockPos(30, 121, 30);
    private static final BlockPos VAULT4 = new BlockPos(42, 121, 30);
    private static final BlockPos AIRLOCK = new BlockPos(55, 121, 30);
    private static final String[] NAMES = {"vaults-closed", "vault-3-mid-swing", "vault-4-mid-swing",
            "airlock-closed", "airlock-open", "penetrations", "corium-hot-cold"};
    private static boolean started, built, requested;
    private static int age, joined, stage, shown;

    private ContainmentClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.containmentClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 2200) throw new IllegalStateException("Containment client check timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.createWorldOpenFlows().createFreshLevel("bwr-containment-client-" + System.currentTimeMillis(),
                    new LevelSettings("Containment client check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 35) return;
        if (!requested) {
            requested = true;
            shown = 0;
            mc.options.hideGui = true;
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Client check player missing");
                var level = player.serverLevel();
                if (!built) {
                    build(level);
                    built = true;
                }
                if (selected == 1) {
                    var door = (ContainmentVaultDoorBlockEntity) level.getBlockEntity(VAULT3);
                    door.energy().receiveEnergy(20_000, false);
                    if (!door.setOpen(true, true)) throw new AssertionError("Vault swing did not start");
                }
                if (selected == 2) {
                    var door = (ContainmentVaultDoorBlockEntity) level.getBlockEntity(VAULT4);
                    door.energy().receiveEnergy(20_000, false);
                    if (!door.setOpen(true, true)) throw new AssertionError("Large vault swing did not start");
                }
                if (selected == 4) {
                    var airlock = (ContainmentAirlockBlockEntity) level.getBlockEntity(AIRLOCK);
                    if (!airlock.setInnerOpen(true, false)) throw new AssertionError("Airlock did not open");
                }
                Vec3 camera = switch (selected) {
                    case 0 -> new Vec3(37, 125, 18);
                    case 1 -> new Vec3(34, 124, 22);
                    case 2 -> new Vec3(43, 124, 22);
                    case 3, 4 -> new Vec3(55, 123, 22);
                    case 5 -> new Vec3(75, 124, 23);
                    default -> new Vec3(92, 125, 22);
                };
                Vec3 target = switch (selected) {
                    case 0 -> new Vec3(38, 123, 30);
                    case 1 -> Vec3.atCenterOf(VAULT3.above());
                    case 2 -> Vec3.atCenterOf(VAULT4.above());
                    case 3, 4 -> Vec3.atCenterOf(AIRLOCK.above());
                    case 5 -> new Vec3(75, 122, 30);
                    default -> new Vec3(92, 121, 30);
                };
                Vec3 delta = target.subtract(camera);
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(selected == 6 ? 18_000 : 6_000);
                level.setWeatherParameters(6000, 0, false, false);
                player.connection.teleport(camera.x, camera.y, camera.z, yaw, pitch);
            });
        }
        if (++shown < (stage == 1 || stage == 2 ? 75 : 95)) return;
        if (stage == 1 || stage == 2) {
            BlockPos doorPos = stage == 1 ? VAULT3 : VAULT4;
            if (!(mc.level.getBlockEntity(doorPos) instanceof ContainmentVaultDoorBlockEntity door)
                    || door.swing(0) <= 0.05 || door.swing(0) >= 0.95) {
                throw new AssertionError("Vault door was not mid-swing in the client at " + doorPos);
            }
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("containment-client-check");
            Files.createDirectories(directory);
            try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                shot.writeToFile(directory.resolve(NAMES[stage] + ".png"));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Containment screenshot failed", ex);
        }
        LogUtils.getLogger().info("CONTAINMENT CLIENT CHECK: {}", NAMES[stage]);
        requested = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("CONTAINMENT CLIENT CHECK PASS: {} captures", NAMES.length);
            mc.stop();
        }
    }

    private static void build(net.minecraft.server.level.ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(25, 120, 24), new BlockPos(97, 120, 36)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        placeVault(level, VAULT3, BwrBlocks.CONTAINMENT_VAULT_DOOR.get());
        placeVault(level, VAULT4, BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get());
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(28, 121, 30), new BlockPos(34, 125, 30)))
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(40, 121, 30), new BlockPos(47, 126, 30)))
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        var airlock = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var airlockState = airlock.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.SOUTH);
        level.setBlock(AIRLOCK, airlockState, 3);
        airlock.setPlacedBy(level, AIRLOCK, airlockState, null, new ItemStack(airlock));
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(53, 121, 30), new BlockPos(57, 123, 30)))
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        var sleeves = new ContainmentPenetrationBlock[] {
                BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get(), BwrBlocks.CONTAINMENT_WATER_PENETRATION.get(),
                BwrBlocks.CONTAINMENT_POWER_PENETRATION.get(), BwrBlocks.CONTAINMENT_DATA_PENETRATION.get(),
                BwrBlocks.CONTAINMENT_PASS_THROUGH.get()};
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(67, 121, 30), new BlockPos(83, 124, 30)))
            level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        for (int i = 0; i < sleeves.length; i++) {
            var sleeve = sleeves[i];
            level.setBlock(new BlockPos(69 + 3 * i, 122, 30),
                    sleeve.defaultBlockState().setValue(ContainmentPenetrationBlock.FACING, Direction.SOUTH), 3);
        }
        for (int x = 89; x <= 95; x++) for (int z = 28; z <= 32; z++) {
            boolean hot = x <= 92;
            level.setBlock(new BlockPos(x, 121, z),
                    BwrBlocks.CORIUM.get().defaultBlockState().setValue(CoriumBlock.HOT, hot), 3);
        }
    }

    private static void placeVault(net.minecraft.server.level.ServerLevel level, BlockPos root,
                                   ContainmentVaultDoorBlock door) {
        var state = door.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING, Direction.NORTH);
        level.setBlock(root, state, 3);
        door.setPlacedBy(level, root, state, null, new ItemStack(door));
        if (level.getBlockEntity(root) == null) throw new AssertionError("Vault placement failed at " + root);
    }
}
