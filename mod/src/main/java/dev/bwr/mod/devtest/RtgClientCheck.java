package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.IsotopeSeparatorMenu;
import dev.bwr.mod.gui.RtgMenu;
import dev.bwr.mod.gui.client.IsotopeSeparatorScreen;
import dev.bwr.mod.gui.client.RtgScreen;
import dev.bwr.mod.isotope.IsotopeSeparatorBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Opt-in pictures of the RTG and the isotope separator (-PbwrRtgClientCheck): both in the world on a flat stone floor, a
 * close-up of the RTG, the RTG's screen reading six strontium-90 capsules, and the separator's screen part way through a
 * batch of waste. Saves 1920 x 1080 pictures under run/.../rtg-check and checks that the screens read the running machines.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RtgClientCheck {
    private RtgClientCheck() {}

    private static boolean started, requested;
    private static int age, stage, shown, joined;
    private static final BlockPos ROOT = new BlockPos(200, 200, 200);
    private static RtgBlockEntity rtg, cesium;
    private static IsotopeSeparatorBlockEntity separator;
    private static final String[] NAMES = {"rtg-overview", "rtg-closeup", "gui-rtg", "gui-isotope-separator", "gui-rtg-mixed"};
    /** x, y, z, yaw, pitch of the camera relative to the root for the two pictures of the world. */
    private static final double[][] CAMERA = {{0.5, 0.4, 6.5, 180, 14}, {0.0, 0.0, 2.3, 180, 26}};

    /** The test supplies what a plant would: FE for the separator, so it is part way through a batch. */
    @SubscribeEvent
    public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.rtgClientCheck") || separator == null || separator.isRemoved()) return;
        separator.energy().receiveEnergy(4_000, false);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.rtgClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 4000) throw new IllegalStateException("RTG client check timeout, stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(65);
            mc.createWorldOpenFlows().createFreshLevel("bwr-rtg-check-" + System.currentTimeMillis(),
                    new LevelSettings("RTG check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (++joined < 40) return;
        boolean world = stage < 2;
        if (!requested) {
            requested = true;
            shown = 0;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            int selected = stage;
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.getAbilities().flying = true;
                player.setNoGravity(true);
                player.onUpdateAbilities();
                if (selected == 0) {
                    for (int x = 8; x <= 17; x++) for (int z = 8; z <= 17; z++) { l.setChunkForced(x, z, true); l.getChunk(x, z); }
                    for (var p : BlockPos.betweenClosed(ROOT.offset(-8, -1, -8), ROOT.offset(10, -1, 8))) l.setBlock(p, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    l.setBlock(ROOT, BwrBlocks.RTG.get().defaultBlockState(), 3);
                    rtg = (RtgBlockEntity) l.getBlockEntity(ROOT);
                    for (int i = 0; i < 6; i++) rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
                    var cs = ROOT.offset(-2, 0, 0);
                    l.setBlock(cs, BwrBlocks.RTG.get().defaultBlockState(), 3);
                    cesium = (RtgBlockEntity) l.getBlockEntity(cs);
                    for (int i = 0; i < 3; i++) cesium.fuel().setStackInSlot(i, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
                    for (int i = 3; i < 5; i++) cesium.fuel().setStackInSlot(i, new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get()));
                    var sp = ROOT.offset(3, 0, 0);
                    l.setBlock(sp, BwrBlocks.ISOTOPE_SEPARATOR.get().defaultBlockState(), 3);
                    separator = (IsotopeSeparatorBlockEntity) l.getBlockEntity(sp);
                    separator.items().setStackInSlot(0, new ItemStack(BwrItems.FISSION_PRODUCT_WASTE.get(), 9));
                    separator.items().setStackInSlot(IsotopeSeparatorBlockEntity.STRIPPED, new ItemStack(BwrItems.STRIPPED_WASTE.get(), 22));
                    separator.items().setStackInSlot(IsotopeSeparatorBlockEntity.STRONTIUM, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get(), 3));
                    separator.items().setStackInSlot(IsotopeSeparatorBlockEntity.CAESIUM, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get(), 5));
                    separator.items().setStackInSlot(IsotopeSeparatorBlockEntity.AMERICIUM, new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get(), 2));
                    l.setDayTime(6000);
                    l.setWeatherParameters(6000, 0, false, false);
                }
                player.closeContainer();
                if (selected < 2) {
                    var c = CAMERA[selected];
                    player.connection.teleport(ROOT.getX() + .5 + c[0], ROOT.getY() + c[1], ROOT.getZ() + .5 + c[2], (float) c[3], (float) c[4]);
                    return;
                }
                player.connection.teleport(ROOT.getX() + .5, ROOT.getY() + 3, ROOT.getZ() + 5.5, 180, 12);
                switch (selected) {
                    case 2 -> RtgMenu.open(player, rtg);
                    case 3 -> IsotopeSeparatorMenu.open(player, separator);
                    default -> RtgMenu.open(player, cesium);
                }
            });
        }
        if (world && requested && mc.screen == null) {
            var c = CAMERA[stage];
            mc.player.getAbilities().flying = true;
            mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            mc.player.setPos(ROOT.getX() + .5 + c[0], ROOT.getY() + c[1], ROOT.getZ() + .5 + c[2]);
            mc.player.setYRot((float) c[3]);
            mc.player.setXRot((float) c[4]);
        }
        boolean ready;
        if (world) {
            ready = mc.screen == null && mc.level.getBlockState(ROOT).is(BwrBlocks.RTG.get())
                    && mc.level.getBlockState(ROOT.offset(3, 0, 0)).is(BwrBlocks.ISOTOPE_SEPARATOR.get());
        } else if (stage == 2 || stage == 4) {
            ready = mc.screen instanceof RtgScreen && mc.player.containerMenu instanceof RtgMenu m && m.present && m.capsules == (stage == 2 ? 6 : 5);
        } else {
            ready = mc.screen instanceof IsotopeSeparatorScreen && mc.player.containerMenu instanceof IsotopeSeparatorMenu m && m.present && m.workTicks > 0
                    && m.strontiumGrains > 0;
        }
        if (!ready && age % 200 == 0) LogUtils.getLogger().info("RTG CLIENT waiting: stage={} screen={}", stage, mc.screen == null ? "world" : mc.screen.getClass().getSimpleName());
        if (!ready || ++shown < 80) return;
        mc.options.hideGui = world;
        mc.getToasts().clear();
        if (shown < 85) return;
        // The screens read the running machines.
        if (mc.player.containerMenu instanceof RtgMenu m && stage == 2
                && (Math.abs(m.thermalWatts - 1380.0) > 0.01 || Math.abs(m.fePerTick - 69.0 / 13.4) > 1e-3 || m.doseMsvPerH <= 0.0)) {
            throw new IllegalStateException("The RTG screen does not read the RTG: " + m.thermalWatts + " W, " + m.fePerTick + " FE/t, " + m.doseMsvPerH + " mSv/h");
        }
        if (mc.player.containerMenu instanceof IsotopeSeparatorMenu m && (m.strontiumGrains <= 0 || m.energyStored <= 0)) {
            throw new IllegalStateException("The separator screen does not read the separator");
        }
        try {
            var dir = mc.gameDirectory.toPath().resolve("rtg-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("RTG CLIENT CHECK: rendered {}", NAMES[stage]);
        mc.player.closeContainer();
        requested = false;
        mc.options.hideGui = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("RTG CLIENT CHECK PASS: the RTG and the isotope separator in the world, and their screens reading the running machines");
            mc.stop();
        }
    }
}
