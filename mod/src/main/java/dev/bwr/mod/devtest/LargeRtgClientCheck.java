package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.LargeRtgMenu;
import dev.bwr.mod.gui.client.LargeRtgScreen;
import dev.bwr.mod.isotope.LargeRtgBlock;
import dev.bwr.mod.isotope.LargeRtgBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
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
 * Opt-in pictures of the Large RTG (-PbwrLargeRtgClientCheck): it beside a small RTG on a flat stone floor, a close-up of its
 * front (hatch, placards, nameplate), its screen holding a mixed 1,000 kg, and the screen of a unit holding 1,000 kg of
 * cobalt-60 in heat-only mode. Saves 1920 x 1080 pictures under run/.../large-rtg-check and checks that the screens read the
 * running blocks.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class LargeRtgClientCheck {
    private LargeRtgClientCheck() {}

    private static boolean started, requested;
    private static int age, stage, shown, joined;
    private static final BlockPos ROOT = new BlockPos(200, 200, 200);
    /** The Large RTGs face south (their front toward a viewer standing south of them) and extend west and north of the root. */
    private static final BlockPos MIXED = ROOT.offset(4, 0, 1);
    private static final BlockPos COBALT = ROOT.offset(9, 0, 1);
    private static LargeRtgBlockEntity mixed, cobalt;
    private static final String[] NAMES = {"large-rtg-overview", "large-rtg-front", "gui-large-rtg-mixed", "gui-large-rtg-cobalt-heat-only", "large-rtg-side"};
    /** x, y, z, yaw, pitch of the camera relative to the root for the three pictures of the world. */
    private static final double[][] CAMERA = {{3.0, 1.6, 9.0, 180, 12}, {2.5, 1.4, 4.2, 180, 12}, {}, {}, {-3.5, 3.2, 5.5, 215, 22}};

    private static void load(LargeRtgBlockEntity be, net.minecraft.world.item.Item item, int count) {
        while (count > 0) {
            int n = Math.min(64, count);
            int took = be.loadFrom(n, new ItemStack(item, n));
            if (took <= 0) {
                throw new IllegalStateException("could not load " + item);
            }
            count -= took;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.largeRtgClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 6000) throw new IllegalStateException("Large RTG client check timeout, stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(65);
            mc.createWorldOpenFlows().createFreshLevel("bwr-large-rtg-check-" + System.currentTimeMillis(),
                    new LevelSettings("Large RTG check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (++joined < 40) return;
        boolean world = stage < 2 || stage == 4;
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
                    for (var p : BlockPos.betweenClosed(ROOT.offset(-8, -1, -8), ROOT.offset(16, -1, 10))) l.setBlock(p, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    l.setBlock(ROOT, BwrBlocks.RTG.get().defaultBlockState(), 3);
                    var small = (RtgBlockEntity) l.getBlockEntity(ROOT);
                    for (int i = 0; i < 6; i++) small.fuel().setStackInSlot(i, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get()));
                    for (var root : new BlockPos[] {MIXED, COBALT}) {
                        var stack = new ItemStack(BwrBlocks.LARGE_RTG.get());
                        // facing south: the player who placed it was looking north, so the front (hatch) faces south, toward the camera
                        ((net.minecraft.world.item.BlockItem) stack.getItem()).place(new DirectionalPlaceContext(l, root, Direction.NORTH, stack, Direction.UP));
                    }
                    mixed = (LargeRtgBlockEntity) l.getBlockEntity(MIXED);
                    cobalt = (LargeRtgBlockEntity) l.getBlockEntity(COBALT);
                    // a mixed 1,000 kg: 400 kg Sr-90 (177 blocks and 7 ingots), 300 kg Cs-137, 200 kg Am-241, 100 kg Co-60
                    load(mixed, BwrItems.STRONTIUM_90_BLOCK.get(), 177);
                    load(mixed, BwrItems.STRONTIUM_90_INGOT.get(), 7);
                    load(mixed, BwrItems.CAESIUM_137_INGOT.get(), 1200);
                    load(mixed, BwrItems.AMERICIUM_241_INGOT.get(), 800);
                    load(mixed, BwrItems.COBALT_60_INGOT.get(), 1000);
                    // 1,000 kg of cobalt-60, heat only
                    load(cobalt, BwrItems.COBALT_60_INGOT.get(), 10_000);
                    cobalt.setHeatOnly(true);
                    l.setDayTime(6000);
                    l.setWeatherParameters(6000, 0, false, false);
                }
                player.closeContainer();
                if (selected < 2 || selected == 4) {
                    var c = CAMERA[selected];
                    player.connection.teleport(ROOT.getX() + .5 + c[0], ROOT.getY() + c[1], ROOT.getZ() + .5 + c[2], (float) c[3], (float) c[4]);
                    return;
                }
                player.connection.teleport(MIXED.getX() + .5, MIXED.getY() + 4, MIXED.getZ() + 6.5, 180, 12);
                LargeRtgMenu.open(player, selected == 2 ? mixed : cobalt);
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
                    && mc.level.getBlockState(MIXED).is(BwrBlocks.LARGE_RTG.get()) && mc.level.getBlockState(MIXED).getValue(LargeRtgBlock.FACING) == Direction.SOUTH;
        } else {
            ready = mc.screen instanceof LargeRtgScreen && mc.player.containerMenu instanceof LargeRtgMenu m && m.present && m.items > 0 && m.heatOnly == (stage == 3);
        }
        if (!ready && age % 200 == 0) LogUtils.getLogger().info("LARGE RTG CLIENT waiting: stage={} screen={}", stage, mc.screen == null ? "world" : mc.screen.getClass().getSimpleName());
        if (!ready || ++shown < 80) return;
        mc.options.hideGui = world;
        mc.getToasts().clear();
        if (shown < 85) return;
        // The screens read the running blocks.
        if (mc.player.containerMenu instanceof LargeRtgMenu m) {
            double thermal = stage == 2 ? 400_000 * 0.92 + 300_000 * 0.42 + 200_000 * 0.1147 + 100_000 * 18.9 : 1_000_000 * 18.9;
            if (Math.abs(m.thermalWatts - thermal) > 1e-3 * thermal || Math.abs(m.massGrams - 1_000_000.0) > 1e-6 || m.doseMsvPerH < 0.0
                    || (stage == 3 && (m.electricWatts != 0.0 || m.fePerTick != 0.0)) || (stage == 2 && m.fePerTick <= 0.0)) {
                throw new IllegalStateException("The Large RTG screen does not read the block: " + m.thermalWatts + " W of " + thermal + ", " + m.massGrams + " g, "
                        + m.fePerTick + " FE/t, " + m.doseMsvPerH + " mSv/h, heat-only " + m.heatOnly);
            }
        }
        try {
            var dir = mc.gameDirectory.toPath().resolve("large-rtg-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("LARGE RTG CLIENT CHECK: rendered {}", NAMES[stage]);
        mc.player.closeContainer();
        requested = false;
        mc.options.hideGui = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("LARGE RTG CLIENT CHECK PASS: the Large RTG beside a small one, its front, and its screens reading the running blocks");
            mc.stop();
        }
    }
}
