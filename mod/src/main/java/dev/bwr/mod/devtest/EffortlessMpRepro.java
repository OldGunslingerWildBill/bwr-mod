package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Effortless reproduction on a dedicated server with a remote client ({@code -Dbwr.effortlessMp=server|client},
 * {@code -Dbwr.effortlessMp.dir=<shared folder>}). The server sets each scenario up and reads the world afterwards; the client
 * presses the keys. They hand over through files in the shared folder. Lines start with EFFORTLESS MP.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class EffortlessMpRepro {
    record S(String name, String mode, GameType game, boolean attack, int clicks, double standZ) {}

    static final List<S> SCENARIOS = List.of(
            new S("mp-vanilla-creative", "DISABLED", GameType.CREATIVE, false, 1, 5.6),
            new S("mp-single-creative", "SINGLE", GameType.CREATIVE, false, 1, 8.5),
            new S("mp-single-survival", "SINGLE", GameType.SURVIVAL, false, 1, 8.5),
            new S("mp-line-creative", "LINE", GameType.CREATIVE, false, 2, 8.5),
            new S("mp-line-survival", "LINE", GameType.SURVIVAL, false, 2, 8.5),
            new S("mp-single-creative-close", "SINGLE", GameType.CREATIVE, false, 1, 3.5));
    private static final BlockPos C = EffortlessReproSupport.C;
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final String MODE = System.getProperty("bwr.effortlessMp", "");

    private static Path dir() { return Path.of(System.getProperty("bwr.effortlessMp.dir")); }
    private static boolean has(String f) { return Files.exists(dir().resolve(f)); }
    private static void put(String f) {
        try { Files.createDirectories(dir()); Files.writeString(dir().resolve(f), "x"); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static EffortlessReproSupport.Scenario sc(S s) { return new EffortlessReproSupport.Scenario(s.name(), s.mode(), s.game(), s.attack(), s.clicks()); }

    // ------------------------------------------------------------------------------------------------ server
    private static int sAge, sIdx, sPhase, sWait;

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post e) {
        if (!"server".equals(MODE)) return;
        MinecraftServer server = e.getServer();
        try {
            if (server.getPlayerList().getPlayers().isEmpty()) return;
            if (++sAge < 100) return;
            if (sWait > 0) { sWait--; return; }
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            ServerLevel l = p.serverLevel();
            if (sIdx >= SCENARIOS.size()) {
                if (sPhase == 0) { put("done"); LOG.info("EFFORTLESS MP DONE"); sPhase = 1; sWait = 100; return; }
                server.halt(false);
                return;
            }
            S s = SCENARIOS.get(sIdx);
            switch (sPhase) {
                case 0 -> { p.setGameMode(GameType.CREATIVE); p.connection.teleport(C.getX() + 0.5, C.getY() + 1, C.getZ() + s.standZ(), 180f, 0f); sPhase = 1; sWait = 40; }
                case 1 -> {
                    for (var at : BlockPos.betweenClosed(C.offset(-20, 1, -20), C.offset(20, 12, 20)))
                        if (!l.getBlockState(at).isAir()) l.setBlock(at, Blocks.AIR.defaultBlockState(), 2 | 16);
                    for (var at : BlockPos.betweenClosed(C.offset(-20, 0, -20), C.offset(20, 0, 20)))
                        l.setBlock(at, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                    p.getInventory().clearContent();
                    p.setGameMode(s.game());
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.HP_TURBINE.get(), s.game() == GameType.SURVIVAL ? 8 : 1));
                    p.getAbilities().flying = false;
                    p.onUpdateAbilities();
                    p.connection.teleport(C.getX() + 0.5, C.getY() + 1, C.getZ() + s.standZ(), 180f, 0f);
                    sPhase = 2; sWait = 20;
                }
                case 2 -> { put("ready-" + sIdx); LOG.info("EFFORTLESS MP [{}] ready", s.name()); sPhase = 3; }
                case 3 -> { if (has("clicked-" + sIdx)) { sPhase = 4; sWait = 20; } }
                case 4 -> {
                    EffortlessReproSupport.report("server", sc(s), l);
                    int items = 0;
                    for (int i = 0; i < p.getInventory().getContainerSize(); i++)
                        if (p.getInventory().getItem(i).is(BwrItems.HP_TURBINE.get())) items += p.getInventory().getItem(i).getCount();
                    LOG.info("EFFORTLESS REPRO [{}] server inventory hp_turbine items={} (started with {})", s.name(), items, s.game() == GameType.SURVIVAL ? 8 : 1);
                    EffortlessReproSupport.lastEffortlessResult(sc(s), p);
                    put("scanned-" + sIdx);
                    sIdx++; sPhase = 0; sWait = 10;
                }
                default -> throw new IllegalStateException();
            }
        } catch (Throwable t) {
            LOG.error("EFFORTLESS MP FAILED on the server at {} phase {}", sIdx, sPhase, t);
            put("done");
            server.halt(false);
        }
    }

    // ------------------------------------------------------------------------------------------------ client
    @EventBusSubscriber(modid = BwrMod.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class Client {
        private static int age, idx, phase, wait, clicked;

        @SubscribeEvent
        public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post ignored) {
            if (!"client".equals(MODE)) return;
            Minecraft mc = Minecraft.getInstance();
            try {
                if (has("done")) { LOG.info("EFFORTLESS MP client sees done"); mc.stop(); return; }
                if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
                if (++age < 100) return;
                if (mc.screen != null) { mc.setScreen(null); return; }
                mc.options.pauseOnLostFocus = false;
                if (wait > 0) { wait--; return; }
                if (idx >= SCENARIOS.size()) return;
                S s = SCENARIOS.get(idx);
                switch (phase) {
                    case 0 -> { if (has("ready-" + idx)) { phase = 1; wait = 20; } }
                    case 1 -> {
                        Object r = EffortlessReproSupport.setMode(s.mode());
                        LOG.info("EFFORTLESS REPRO [{}] setStructure({}) -> {}", s.name(), s.mode(), r);
                        if (!Boolean.TRUE.equals(r)) { wait = 20; return; }
                        clicked = 0; phase = 2; wait = 10;
                    }
                    case 2 -> {
                        Vec3 target = clicked == 0
                                ? new Vec3(C.getX() + 0.5, C.getY() + 1, C.getZ() + (s.standZ() < 6 ? 0.95 : 0.5))
                                : new Vec3(C.getX() + 6.5, C.getY() + 1, C.getZ() + 0.5);
                        Vec3 d = target.subtract(mc.player.getEyePosition());
                        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
                        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
                        mc.player.setYRot(yaw); mc.player.setXRot(pitch); mc.player.yRotO = yaw; mc.player.xRotO = pitch; mc.player.yHeadRot = yaw;
                        phase = 3; wait = 3;
                    }
                    case 3 -> {
                        mc.gameRenderer.pick(1.0f);
                        LOG.info("EFFORTLESS REPRO [{}] click {} {} hit={} type={} eye={}", s.name(), clicked + 1, s.attack() ? "attack" : "use",
                                mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? b.getBlockPos().toShortString() + " " + b.getDirection() : mc.hitResult,
                                mc.hitResult == null ? null : mc.hitResult.getType(), mc.player.getEyePosition());
                        KeyMapping.click((s.attack() ? mc.options.keyAttack : mc.options.keyUse).getKey());
                        clicked++;
                        if (clicked < s.clicks()) { phase = 2; wait = 5; } else { phase = 4; wait = 60; }
                    }
                    case 4 -> {
                        EffortlessReproSupport.report("client", sc(s), mc.level);
                        put("clicked-" + idx);
                        phase = 5;
                    }
                    case 5 -> {
                        if (!has("scanned-" + idx)) return;
                        EffortlessReproSupport.report("client-later", sc(s), mc.level);
                        EffortlessReproSupport.setMode("DISABLED");
                        idx++; phase = 0; wait = 5;
                    }
                    default -> throw new IllegalStateException();
                }
            } catch (Throwable t) {
                LOG.error("EFFORTLESS MP FAILED on the client at {} phase {}", idx, phase, t);
                mc.stop();
            }
        }
    }
}
