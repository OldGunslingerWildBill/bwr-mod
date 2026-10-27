package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reproduction of a turbine placed through Effortless Structure ({@code -Dbwr.effortlessRepro=true}, Effortless in the run's mods/).
 * Drives the real client: sets an Effortless build mode, aims, presses the use key; Effortless traces, previews, sends its
 * PlayerBuildPacket, and its server side builds. Then logs what stands in the world, server and client side, and what Effortless's
 * last operation carried. Every line starts with EFFORTLESS REPRO. Run: put the Effortless jar in run/turbineModelCheck/mods (never
 * committed), then {@code :mod:runTurbineModelCheck -PbwrEffortlessRepro}. The dedicated-server version is EffortlessMpRepro.
 * Single and Line builds, creative and survival, a footprint without room, and Effortless undo/redo.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class EffortlessReproCheck {

    private static final List<EffortlessReproSupport.Scenario> SCENARIOS = List.of(
            new EffortlessReproSupport.Scenario("single-creative", "SINGLE", GameType.CREATIVE, false, 1),
            new EffortlessReproSupport.Scenario("single-survival", "SINGLE", GameType.SURVIVAL, false, 1),
            new EffortlessReproSupport.Scenario("line-creative", "LINE", GameType.CREATIVE, false, 2),
            new EffortlessReproSupport.Scenario("line-survival", "LINE", GameType.SURVIVAL, false, 2),
            new EffortlessReproSupport.Scenario("single-creative-attack", "SINGLE", GameType.CREATIVE, true, 1),
            new EffortlessReproSupport.Scenario("single-survival-blocked", "SINGLE", GameType.SURVIVAL, false, 1),
            new EffortlessReproSupport.Scenario("single-creative-undo", "SINGLE", GameType.CREATIVE, false, 1),
            new EffortlessReproSupport.Scenario("single-survival-undo", "SINGLE", GameType.SURVIVAL, false, 1),
            new EffortlessReproSupport.Scenario("line-creative-undo", "LINE", GameType.CREATIVE, false, 2));
    private static int undoStage;
    private static EffortlessReproSupport.Scenario pending;
    private static final BlockPos C = EffortlessReproSupport.C;
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();

    private static boolean started;
    private static int age, joined, scenario, phase, wait, clicked;
    private static volatile boolean serverDone;
    private static volatile Throwable failure;

    private EffortlessReproCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.effortlessRepro")) return;
        try {
            step();
        } catch (Throwable t) {
            LOG.error("EFFORTLESS REPRO FAILED in scenario {} phase {}", scenario, phase, t);
            Minecraft.getInstance().stop();
        }
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("server side failed", failure);
        if (++age > 20000) throw new IllegalStateException("timed out");
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            LOG.info("EFFORTLESS REPRO effortless loaded={} version={}", ModList.get().isLoaded("effortless"),
                    ModList.get().getModContainerById("effortless").map(c -> c.getModInfo().getVersion().toString()).orElse("-"));
            mc.options.pauseOnLostFocus = false;
            mc.createWorldOpenFlows().createFreshLevel("bwr-effortless-repro-" + System.currentTimeMillis(),
                    new LevelSettings("Effortless repro", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 60) return;
        if (mc.screen != null) { mc.setScreen(null); return; }
        if (wait > 0) { wait--; return; }
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        if (scenario >= SCENARIOS.size()) {
            LOG.info("EFFORTLESS REPRO DONE");
            mc.stop();
            return;
        }
        EffortlessReproSupport.Scenario s = pending != null && phase == 5 ? pending : SCENARIOS.get(scenario);
        if (phase < 5) pending = null;
        Vec3 eye = new Vec3(C.getX() + 0.5, C.getY() + 1 + 1.62, C.getZ() + 8.5);
        switch (phase) {
            case 0 -> { // teleport, then let chunks load
                serverDone = false;
                server.execute(() -> {
                    try {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        p.setGameMode(GameType.CREATIVE);
                        p.connection.teleport(eye.x, C.getY() + 1, eye.z, 180f, 0f);
                        serverDone = true;
                    } catch (Throwable t) { failure = t; }
                });
                phase = 1; wait = 40;
            }
            case 1 -> {
                if (!serverDone) return;
                serverDone = false;
                server.execute(() -> {
                    try {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        ServerLevel l = p.serverLevel();
                        for (var at : BlockPos.betweenClosed(C.offset(-20, 1, -20), C.offset(20, 12, 20)))
                            if (!l.getBlockState(at).isAir()) l.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
                        for (var at : BlockPos.betweenClosed(C.offset(-20, 0, -20), C.offset(20, 0, 20)))
                            l.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                        p.getInventory().clearContent();
                        p.setGameMode(s.game());
                        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.HP_TURBINE.get(), s.game() == GameType.SURVIVAL ? 8 : 1));
                        if (s.game() == GameType.SURVIVAL) { p.getAbilities().mayfly = true; }
                        p.getAbilities().flying = false;
                        p.onUpdateAbilities();
                        p.connection.teleport(eye.x, C.getY() + 1, eye.z, 180f, 0f);
                        if (s.name().contains("blocked")) l.setBlock(C.offset(2, 3, -3), Blocks.STONE.defaultBlockState(), 3);
                        serverDone = true;
                    } catch (Throwable t) { failure = t; }
                });
                phase = 2; wait = 30;
            }
            case 2 -> {
                if (!serverDone) return;
                Object result = EffortlessReproSupport.setMode(s.mode());
                LOG.info("EFFORTLESS REPRO [{}] setStructure({}) -> {}", s.name(), s.mode(), result);
                if (!Boolean.TRUE.equals(result)) { wait = 20; return; }
                clicked = 0;
                phase = 3; wait = 10;
            }
            case 3 -> {
                // aim: first click at C's top face; a line's second click 6 blocks east of it
                Vec3 target = clicked == 0 ? new Vec3(C.getX() + 0.5, C.getY() + 1, C.getZ() + 0.5) : new Vec3(C.getX() + 6.5, C.getY() + 1, C.getZ() + 0.5);
                Vec3 d = target.subtract(mc.player.getEyePosition());
                float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
                mc.player.setYRot(yaw); mc.player.setXRot(pitch);
                mc.player.yRotO = yaw; mc.player.xRotO = pitch;
                phase = 4; wait = 3;
            }
            case 4 -> {
                mc.gameRenderer.pick(1.0f);
                LOG.info("EFFORTLESS REPRO [{}] click {} {} hit={} eye={} yaw={} pitch={}", s.name(), clicked + 1, s.attack() ? "attack" : "use",
                        mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? b.getBlockPos() + " " + b.getDirection() : mc.hitResult,
                        mc.player.getEyePosition(), mc.player.getYRot(), mc.player.getXRot());
                if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh) {
                    BlockPos hp = bh.getBlockPos();
                    LOG.info("EFFORTLESS REPRO [{}] client block at hit {}: {}; server: {}", s.name(), hp.toShortString(), mc.level.getBlockState(hp),
                            mc.getSingleplayerServer().overworld().getBlockState(hp));
                }
                KeyMapping.click((s.attack() ? mc.options.keyAttack : mc.options.keyUse).getKey());
                clicked++;
                if (clicked < s.clicks()) { phase = 3; wait = 5; } else { phase = 5; wait = 60; }
            }
            case 5 -> {
                serverDone = false;
                server.execute(() -> {
                    try {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        EffortlessReproSupport.report("server", s, p.serverLevel());
                        int items = 0;
                        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
                            if (p.getInventory().getItem(i).is(BwrItems.HP_TURBINE.get())) items += p.getInventory().getItem(i).getCount();
                        LOG.info("EFFORTLESS REPRO [{}] server inventory hp_turbine items={} (started with {})", s.name(), items, s.game() == GameType.SURVIVAL ? 8 : 1);
                        EffortlessReproSupport.lastEffortlessResult(s, p);
                        serverDone = true;
                    } catch (Throwable t) { failure = t; }
                });
                EffortlessReproSupport.report("client", s, mc.level);
                phase = 6;
            }
            case 6 -> {
                if (!serverDone) return;
                if (s.name().contains("undo") && undoStage < 2) {
                    Object client = Class.forName("dev.huskuraft.effortless.EffortlessClient").getMethod("getInstance").invoke(null);
                    Object builder = client.getClass().getMethod("getStructureBuilder").invoke(client);
                    Object effPlayer = EffortlessReproSupport.call(EffortlessReproSupport.call(client, "getClient"), "getPlayer");
                    EffortlessReproSupport.call(builder, undoStage == 0 ? "undo" : "redo", effPlayer);
                    LOG.info("EFFORTLESS REPRO [{}] sent {}", s.name(), undoStage == 0 ? "undo" : "redo");
                    String base = SCENARIOS.get(scenario).name();
                    pending = new EffortlessReproSupport.Scenario(base + (undoStage == 0 ? "/after-undo" : "/after-redo"), s.mode(), s.game(), s.attack(), s.clicks());
                    undoStage++;
                    phase = 5; wait = 60;
                    return;
                }
                undoStage = 0;
                EffortlessReproSupport.setMode("DISABLED");
                scenario++;
                phase = 1; wait = 10;
            }
            default -> throw new IllegalStateException();
        }
    }

}
