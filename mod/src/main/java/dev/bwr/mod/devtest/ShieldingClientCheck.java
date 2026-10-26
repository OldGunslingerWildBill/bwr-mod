package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.radiation.RadiationReadout;
import dev.bwr.mod.radiation.client.RadiationClient;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
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

import java.nio.file.Files;

/**
 * Opt-in pictures of block radiation shielding (-PbwrShieldingClientCheck, through run-mc.ps1): a closed cask of lead blocks (one block
 * thick, 7 x 5 x 7) around an RTG loaded with six caesium-137 capsules, the survey meter held outside it and inside it, the HUD showing
 * the reading. Saves two 1920 x 1080 pictures under run/turbineModelCheck/shielding-check and checks the readings: tenths of a mSv/h and
 * more inside, nothing measurable outside. Not shipped: the devtest package is stripped from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class ShieldingClientCheck {
    private ShieldingClientCheck() {
    }

    private static final String[] NAMES = {"shielding-cask-outside", "shielding-cask-inside"};
    private static final BlockPos ROOT = new BlockPos(300, 200, 300);
    /** Camera feet position and the point looked at, per stage. */
    private static final Vec3[] STAND = {new Vec3(ROOT.getX() + 3.6, ROOT.getY() + 1.0, ROOT.getZ() + 9.4), new Vec3(ROOT.getX() + 0.5, ROOT.getY() + 1.0, ROOT.getZ() + 2.6)};
    private static final Vec3[] LOOK = {new Vec3(ROOT.getX() + 0.5, ROOT.getY() + 2.0, ROOT.getZ() + 0.5), new Vec3(ROOT.getX() + 0.5, ROOT.getY() + 1.4, ROOT.getZ() + 0.5)};

    private static boolean started, built, requested, standing;
    private static volatile Throwable failure;
    private static volatile Vec3 aim;
    private static int age, joined, stage, shown;
    private static final double[] readings = new double[NAMES.length];

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.shieldingClientCheck")) return;
        try {
            step();
        } catch (Throwable t) {
            LogUtils.getLogger().error("SHIELDING CLIENT CHECK FAILED at stage {}", stage < NAMES.length ? NAMES[stage] : "end", t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("Shielding showcase build failed", failure);
        if (++age > 4000) throw new IllegalStateException("Shielding client check timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.renderDistance().set(8);
            mc.options.fov().set(70);
            mc.options.guiScale().set(2);
            mc.createWorldOpenFlows().createFreshLevel("bwr-shielding-check-" + System.currentTimeMillis(),
                    new LevelSettings("Shielding check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        RenderEnvironment.log("shielding");
        if (!requested) {
            if (stage == NAMES.length) {
                LogUtils.getLogger().info("SHIELDING CLIENT CHECK PASS: {} captures, the meter reads {} inside the lead cask and {} outside it",
                        stage, DoseModel.formatRate(readings[1]), DoseModel.formatRate(readings[0]));
                mc.stop();
                return;
            }
            requested = true;
            standing = false;
            shown = 0;
            mc.setScreen(null);
            mc.options.hideGui = false;
            int current = stage;
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(uuid);
                    ServerLevel l = player.serverLevel();
                    if (!built) {
                        build(l);
                        built = true;
                    }
                    Vec3 stand = STAND[current];
                    Vec3 delta = LOOK[current].subtract(stand.add(0, player.getEyeHeight(), 0));
                    float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                    float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                    aim = new Vec3(yaw, pitch, 0);
                    player.setNoGravity(true);
                    player.getAbilities().flying = true;
                    player.onUpdateAbilities();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.SURVEY_METER.get()));
                    player.connection.teleport(stand.x, stand.y, stand.z, yaw, pitch);
                    standing = true;
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }
        if (!standing || aim == null) return;
        Vec3 stand = STAND[stage];
        mc.player.setPos(stand.x, stand.y, stand.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setYRot((float) aim.x);
        mc.player.setXRot((float) aim.y);
        mc.player.yRotO = (float) aim.x;
        mc.player.xRotO = (float) aim.y;
        if (mc.player.position().distanceToSqr(stand) > 0.25) return;
        if (++shown < 100) return;
        if (!RadiationClient.showing()) throw new AssertionError("The survey meter readout is not showing at " + NAMES[stage]);
        double rate = RadiationReadout.doseRate();
        readings[stage] = rate;
        if (stage == 0 && !(rate < 1.0e-20)) throw new AssertionError("Outside one block of lead the meter reads " + rate + " mSv/h");
        if (stage == 1 && !(rate > 0.1)) throw new AssertionError("Inside the cask beside the RTG the meter reads only " + rate + " mSv/h");
        LogUtils.getLogger().info("SHIELDING CLIENT CHECK {}: the meter reads {} ({} mSv/h)", NAMES[stage], DoseModel.formatRate(rate), rate);
        try {
            var dir = mc.gameDirectory.toPath().resolve("shielding-check");
            Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("SHIELDING CLIENT CHECK: rendered {}", NAMES[stage]);
        requested = false;
        stage++;
    }

    private static void build(ServerLevel l) {
        for (int cx = (ROOT.getX() >> 4) - 2; cx <= (ROOT.getX() >> 4) + 2; cx++) {
            for (int cz = (ROOT.getZ() >> 4) - 2; cz <= (ROOT.getZ() >> 4) + 2; cz++) {
                l.setChunkForced(cx, cz, true);
                l.getChunk(cx, cz);
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(ROOT.offset(-10, -1, -10), ROOT.offset(10, -1, 14))) {
            l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
        }
        // a closed cask of lead blocks, one thick: outer 7 x 5 x 7, the RTG on its floor in the middle, two lanterns to see by
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (Math.abs(x) == 3 || Math.abs(z) == 3 || y == 0 || y == 4) {
                        l.setBlock(ROOT.offset(x, y, z), BwrBlocks.LEAD_BLOCK.get().defaultBlockState(), 3);
                    }
                }
            }
        }
        BlockPos rtgPos = ROOT.above();
        l.setBlock(rtgPos, BwrBlocks.RTG.get().defaultBlockState(), 3);
        RtgBlockEntity rtg = (RtgBlockEntity) l.getBlockEntity(rtgPos);
        for (int i = 0; i < 6; i++) rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
        l.setBlock(ROOT.offset(-2, 1, -2), Blocks.LANTERN.defaultBlockState(), 3);
        l.setBlock(ROOT.offset(2, 1, -2), Blocks.LANTERN.defaultBlockState(), 3);
        l.setBlock(ROOT.offset(-2, 1, 2), Blocks.LANTERN.defaultBlockState(), 3);
        l.setDayTime(6000);
        l.setWeatherParameters(6000, 0, false, false);
    }
}
