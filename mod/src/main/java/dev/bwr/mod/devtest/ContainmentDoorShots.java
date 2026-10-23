package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
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
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/**
 * Development-only door pictures for the old/new comparison. {@code -PbwrContainmentDoorShots=<label>} (JVM property
 * {@code bwr.containmentDoorShots}) saves 1920 x 1080 pictures to {@code containment-door-shots/<label>/NN-name.png}:
 * each vault door closed, mid-swing and open, and the airlock pass (outer opens, outer open, outer closes, inner opens,
 * inner open). It waits on what the client block entities draw, not on tick counts, so the same stages run against any timing.
 * Stages that need the new door model (the latch dogs) only run when the label starts with "new".
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class ContainmentDoorShots {
    private static final BlockPos VAULT3 = new BlockPos(30, 121, 30);
    private static final BlockPos VAULT4 = new BlockPos(42, 121, 30);
    private static final BlockPos AIRLOCK = new BlockPos(55, 121, 30);
    private static final Vec3 V3_CAM = new Vec3(33.4, 123.9, 24.8), V3_AIM = new Vec3(31.5, 122.5, 30.5);
    private static final Vec3 V4_CAM = new Vec3(45.5, 124.6, 23.8), V4_AIM = new Vec3(43.5, 123.5, 30.5);
    private static final Vec3 AL_CAM = new Vec3(58.2, 124.2, 34.6), AL_AIM = new Vec3(55.5, 122.0, 31.4);
    private static final Vec3 PREVIEW_CAM = new Vec3(33.0, 121.5, 25.4), PREVIEW_AIM = new Vec3(33.0, 120.9, 30.0);
    private static final Vec3 V3_IN_CAM = new Vec3(29.5, 124.5, 37.0), V3_IN_AIM = new Vec3(31.5, 122.0, 31.0);

    private record Stage(String name, String action, Vec3 cam, Vec3 aim, int minWait, BooleanSupplier ready, boolean newOnly) {}

    private static Stage[] stages;
    private static boolean started, built, requested;
    private static int age, joined, stage, shown;

    private ContainmentDoorShots() {}

    private static ContainmentVaultDoorBlockEntity vault(BlockPos pos) {
        return Minecraft.getInstance().level.getBlockEntity(pos) instanceof ContainmentVaultDoorBlockEntity d ? d : null;
    }

    private static ContainmentAirlockBlockEntity airlock() {
        return Minecraft.getInstance().level.getBlockEntity(AIRLOCK) instanceof ContainmentAirlockBlockEntity d ? d : null;
    }

    private static boolean swingAtLeast(BlockPos pos, double value) {
        var door = vault(pos);
        return door != null && door.swing(0) >= value;
    }

    /** New doors only: the latch dogs are clear of their keepers and the leaf has not moved yet. */
    private static boolean unlatched(BlockPos pos) {
        var door = vault(pos);
        return door != null && door.unlatched(0) >= 0.97 && door.swing(0) < 0.02;
    }

    private static boolean innerAtLeast(double value) {
        var door = airlock();
        return door != null && door.innerSwing(0) >= value;
    }

    private static boolean outerAtLeast(double value) {
        var door = airlock();
        return door != null && door.outerSwing(0) >= value;
    }

    private static boolean outerAtMost(double value) {
        var door = airlock();
        return door != null && door.outerSwing(0) <= value;
    }

    private static Stage[] stages() {
        boolean fresh = System.getProperty("bwr.containmentDoorShots", "").startsWith("new");
        var list = new java.util.ArrayList<Stage>();
        list.add(new Stage("vault3-closed", null, V3_CAM, V3_AIM, 25, () -> true, false));
        list.add(new Stage("vault3-unlatched", "open3", V3_CAM, V3_AIM, 0, () -> unlatched(VAULT3), true));
        list.add(new Stage("vault3-mid", fresh ? null : "open3", V3_CAM, V3_AIM, 0, () -> swingAtLeast(VAULT3, 0.5), false));
        list.add(new Stage("vault3-open", null, V3_CAM, V3_AIM, 8, () -> swingAtLeast(VAULT3, 0.999), false));
        list.add(new Stage("vault3-open-inside", null, V3_IN_CAM, V3_IN_AIM, 25, () -> swingAtLeast(VAULT3, 0.999), false));
        list.add(new Stage("vault4-closed", "away", V4_CAM, V4_AIM, 25, () -> true, false));
        list.add(new Stage("vault4-unlatched", "open4", V4_CAM, V4_AIM, 0, () -> unlatched(VAULT4), true));
        list.add(new Stage("vault4-mid", fresh ? null : "open4", V4_CAM, V4_AIM, 0, () -> swingAtLeast(VAULT4, 0.5), false));
        list.add(new Stage("vault4-open", null, V4_CAM, V4_AIM, 8, () -> swingAtLeast(VAULT4, 0.999), false));
        list.add(new Stage("airlock-closed", "away", AL_CAM, AL_AIM, 25, () -> true, false));
        list.add(new Stage("airlock-1-outer-opening", "outer-open", AL_CAM, AL_AIM, 0, () -> outerAtLeast(0.5), false));
        list.add(new Stage("airlock-2-outer-open", null, AL_CAM, AL_AIM, 8, () -> outerAtLeast(0.999), false));
        list.add(new Stage("airlock-3-outer-closing", "outer-close", AL_CAM, AL_AIM, 0, () -> outerAtMost(0.5), false));
        list.add(new Stage("airlock-4-inner-opening", "inner-open", AL_CAM, AL_AIM, 0, () -> innerAtLeast(0.5), false));
        list.add(new Stage("airlock-5-inner-open", null, AL_CAM, AL_AIM, 8, () -> innerAtLeast(0.999), false));
        list.add(new Stage("vault-placement-preview", "hold-vault", PREVIEW_CAM, PREVIEW_AIM, 20, () -> true, false));
        list.add(new Stage("inventory-icons", "items", AL_CAM, AL_AIM, 12, () -> true, false));
        return list.stream().filter(s -> fresh || !s.newOnly()).toArray(Stage[]::new);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (System.getProperty("bwr.containmentDoorShots") == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 9000) throw new IllegalStateException("Containment door shots timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            stages = stages();
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.renderDistance().set(10);
            mc.createWorldOpenFlows().createFreshLevel("bwr-door-shots-" + System.currentTimeMillis(),
                    new LevelSettings("Containment door shots", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 35) return;
        Stage current = stages[stage];
        RenderEnvironment.log("containment-doors");
        if (!requested) {
            requested = true;
            shown = 0;
            mc.options.hideGui = !"hold-vault".equals(current.action());
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            if ("items".equals(current.action())) mc.setScreen(new ItemSheet());
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) throw new IllegalStateException("Door shots player missing");
                ServerLevel level = player.serverLevel();
                if (!built) {
                    build(level);
                    built = true;
                }
                act(level, player, current.action());
                Vec3 delta = current.aim().subtract(current.cam());
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                level.setDayTime(6_000);
                level.setWeatherParameters(6000, 0, false, false);
                player.connection.teleport(current.cam().x, current.cam().y, current.cam().z, yaw, pitch);
            });
        }
        if (++shown < Math.max(current.minWait(), 3)) return;
        if (!current.ready().getAsBoolean()) {
            if (shown > 900) throw new AssertionError("Door shot '" + current.name() + "' never became ready");
            return;
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("containment-door-shots")
                    .resolve(System.getProperty("bwr.containmentDoorShots"));
            Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve(String.format("%02d-%s.png", stage + 1, current.name())));
        } catch (IOException ex) {
            throw new IllegalStateException("Door screenshot failed", ex);
        }
        LogUtils.getLogger().info("CONTAINMENT DOOR SHOT: {} (client swing vault3={} vault4={})", current.name(),
                vault(VAULT3) == null ? -1 : vault(VAULT3).swing(0), vault(VAULT4) == null ? -1 : vault(VAULT4).swing(0));
        if ("items".equals(current.action())) mc.setScreen(null);
        requested = false;
        if (++stage == stages.length) {
            LogUtils.getLogger().info("CONTAINMENT DOOR SHOTS PASS: {} pictures", stages.length);
            mc.stop();
        }
    }

    /** The three door items, large, as the inventory draws them. */
    private static final class ItemSheet extends net.minecraft.client.gui.screens.Screen {
        ItemSheet() { super(net.minecraft.network.chat.Component.literal("Containment door items")); }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partial) {
            graphics.fill(0, 0, width, height, 0xFF6E7B86);
            var items = new net.minecraft.world.level.block.Block[] {BwrBlocks.CONTAINMENT_VAULT_DOOR.get(),
                    BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get(), BwrBlocks.CONTAINMENT_AIRLOCK.get()};
            float scale = width / 3f / 16f * 0.8f;
            for (int i = 0; i < items.length; i++) {
                graphics.pose().pushPose();
                graphics.pose().translate(width / 3f * i + width / 3f * 0.1f, height / 2f - 8 * scale, 0);
                graphics.pose().scale(scale, scale, 1);
                graphics.renderItem(new ItemStack(items[i]), 0, 0);
                graphics.pose().popPose();
            }
        }
    }

    private static void act(ServerLevel level, net.minecraft.server.level.ServerPlayer player, String action) {
        if (action == null) return;
        switch (action) {
            case "open3", "open4" -> {
                var door = (ContainmentVaultDoorBlockEntity) level.getBlockEntity(action.equals("open3") ? VAULT3 : VAULT4);
                door.energy().receiveEnergy(20_000, false);
                if (!door.setOpen(true, true)) throw new AssertionError("Vault swing did not start");
            }
            case "hold-vault" -> player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(BwrBlocks.CONTAINMENT_VAULT_DOOR.get()));
            case "outer-open" -> need(((ContainmentAirlockBlockEntity) level.getBlockEntity(AIRLOCK)).setOuterOpen(true, false), action);
            case "outer-close" -> need(((ContainmentAirlockBlockEntity) level.getBlockEntity(AIRLOCK)).setOuterOpen(false, false), action);
            case "inner-open" -> need(((ContainmentAirlockBlockEntity) level.getBlockEntity(AIRLOCK)).setInnerOpen(true, false), action);
            default -> { }
        }
    }

    private static void need(boolean ok, String action) {
        if (!ok) throw new AssertionError("Door shot action refused: " + action);
    }

    private static void build(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(25, 120, 24), new BlockPos(70, 120, 40)))
            level.setBlock(pos, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        placeVault(level, VAULT3, BwrBlocks.CONTAINMENT_VAULT_DOOR.get());
        placeVault(level, VAULT4, BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get());
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(28, 121, 30), new BlockPos(34, 125, 30)))
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(40, 121, 30), new BlockPos(47, 126, 30)))
            if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        var airlock = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var state = airlock.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.SOUTH);
        level.setBlock(AIRLOCK, state, 3);
        airlock.setPlacedBy(level, AIRLOCK, state, null, new ItemStack(airlock));
        // Backdrop walls, west of the airlock only, so the camera looking down the chamber from the
        // south-east sees both plates, both leaves and the chamber between them.
        for (int z : new int[] {30, 32})
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(51, 121, z), new BlockPos(54, 123, z)))
                level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(54, 121, 31), new BlockPos(54, 122, 31)))
            level.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
    }

    private static void placeVault(ServerLevel level, BlockPos root, ContainmentVaultDoorBlock door) {
        var state = door.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING, Direction.NORTH);
        level.setBlock(root, state, 3);
        door.setPlacedBy(level, root, state, null, new ItemStack(door));
        if (level.getBlockEntity(root) == null) throw new AssertionError("Vault placement failed at " + root);
    }
}
