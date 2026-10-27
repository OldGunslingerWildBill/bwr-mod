package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.CraneMenu;
import dev.bwr.mod.gui.PowerModuleMenu;
import dev.bwr.mod.gui.client.CraneScreen;
import dev.bwr.mod.gui.client.PowerModuleScreen;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.power.TurbineLoadItem;
import dev.bwr.mod.power.TurbineStandBlock;
import dev.bwr.mod.power.TurbineStandBlockEntity;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.CraneRunwayRailBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
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
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Opt-in pictures of the turbine hall (-PbwrTurbineHallCheck, TURBINE-MAINTENANCE.md): the building crane lifting an LP turbine's hood
 * off, the open casing with its blade sections, the blade stand stocked, a blade section on the hook, a turbine missile's hole through a
 * wall, and the LP's BLADES screen and the crane's pendant. A fresh flat world; every move is the block entities' own logic. Saves
 * 1920 x 1080 pictures under run/turbineModelCheck/turbine-hall-check.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TurbineHallClientCheck {
    private TurbineHallClientCheck() {
    }

    private static boolean started, requested;
    private static int age, stage, shown, joined;
    private static final BlockPos ROOT = new BlockPos(200, 100, 200);
    private static final BlockPos WALL_LP = ROOT.offset(0, 0, 40);
    private static PowerModuleBlockEntity lp;
    private static BuildingCraneBlockEntity crane;
    private static final String[] NAMES = {"crane-lifting-lp-hood", "open-lp-casing-blades", "lp-blade-stand-stocked", "blade-section-on-the-hook",
            "turbine-missile-hole", "gui-lp-blades", "gui-crane-turbine-hall"};
    /** x, y, z, yaw, pitch of the camera relative to the root for the five world pictures. */
    private static final double[][] CAMERA = {{12, 8, 15, 140, -8}, {7, 9, 7, 135, 42}, {9, 5, -15, 120, 18}, {12, 11, -14, 60, 22}, {0.5, 6, 61, 180, 10}};

    private static void runUntil(ServerLevel l, Phase phase, int extra) {
        int n = 0;
        while (crane.busy() && crane.phase() != phase && n++ < 60000) crane.scheduledTick(l);
        for (int i = 0; i < extra && crane.busy(); i++) crane.scheduledTick(l);
    }

    private static void finish(ServerLevel l) {
        int n = 0;
        while (crane.busy() && n++ < 60000) crane.scheduledTick(l);
    }

    private static void setup(ServerLevel l) {
        for (int x = 8; x <= 17; x++) for (int z = 8; z <= 18; z++) {
            l.setChunkForced(x, z, true);
            l.getChunk(x, z);
        }
        for (var p : BlockPos.betweenClosed(ROOT.offset(-24, -1, -16), ROOT.offset(24, -1, 56))) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        lp = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), ROOT, Direction.EAST);
        PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), ROOT.east(8), Direction.EAST);
        PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), ROOT.west(9), Direction.EAST);
        var tag = lp.saveWithoutMetadata(l.registryAccess());
        var blades = new net.minecraft.nbt.ListTag();
        blades.add(net.minecraft.nbt.DoubleTag.valueOf(0.72));
        blades.add(net.minecraft.nbt.DoubleTag.valueOf(0.31));
        tag.put("Blades", blades);
        lp.loadWithComponents(tag, l.registryAccess());
        int y = ROOT.getY() + 16;
        for (int x = -18; x <= 18; x++) {
            for (int z : new int[]{-12, 12}) {
                var p = new BlockPos(ROOT.getX() + x, y, ROOT.getZ() + z);
                l.setBlock(p, BwrBlocks.CRANE_RUNWAY_RAIL.get().defaultBlockState().setValue(CraneRunwayRailBlock.AXIS, Direction.Axis.X), 2);
                for (int d = 1; d <= 17; d += (x % 6 == 0 ? 1 : 100)) l.setBlock(p.below(d), Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
            }
        }
        var console = new BlockPos(ROOT.getX() - 19, y, ROOT.getZ() - 12);
        l.setBlock(console, BwrBlocks.BUILDING_CRANE_CONSOLE.get().defaultBlockState(), 2);
        crane = (BuildingCraneBlockEntity) l.getBlockEntity(console);
        l.setBlock(ROOT.offset(0, 0, 8), BwrBlocks.CASING_LAYDOWN_STAND.get().defaultBlockState().setValue(TurbineStandBlock.FACING, Direction.EAST), 3);
        l.setBlock(ROOT.offset(0, 0, -8), BwrBlocks.LP_BLADE_STAND.get().defaultBlockState().setValue(TurbineStandBlock.FACING, Direction.NORTH), 3);
        crane.refreshSite();
        // the missile's wall: a second LP with gray concrete beside it
        var second = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), WALL_LP, Direction.EAST);
        for (int dx = -6; dx <= 6; dx++) for (int dy = 0; dy <= 9; dy++) for (int dz = 6; dz <= 7; dz++)
            l.setBlock(WALL_LP.offset(dx, dy, dz), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
        second.rpm = 1500;
        PowerTrain.throwBlade(l, second, 0, 0.18);
        PowerTrain.throwBlade(l, second, 1, 0.05);
        l.setDayTime(6000);
        l.setWeatherParameters(6000, 0, false, false);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.turbineHallCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 6000) throw new IllegalStateException("Turbine hall client check timeout, stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(70);
            mc.createWorldOpenFlows().createFreshLevel("bwr-turbine-hall-check-" + System.currentTimeMillis(),
                    new LevelSettings("Turbine hall check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (++joined < 40) return;
        boolean world = stage < 5;
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
                player.closeContainer();
                switch (selected) {
                    case 0 -> {
                        setup(l);
                        if (crane.queueCover(true, null) != null) throw new IllegalStateException("COVER OFF refused: " + crane.queueCover(true, null));
                        runUntil(l, Phase.TRANSIT, 120);
                        crane.devHold(true);
                    }
                    case 1 -> {
                        crane.devHold(false);
                        finish(l);
                    }
                    case 2 -> {
                        var stand = (TurbineStandBlockEntity) l.getBlockEntity(ROOT.offset(0, 0, -8));
                        stand.items().insertItem(0, new ItemStack(BwrItems.LP_BLADE_SECTION.get()), false);
                    }
                    case 3 -> {
                        if (crane.queueBlades(true, null) != null) throw new IllegalStateException("BLADES OUT refused");
                        runUntil(l, Phase.TRANSIT, 160);
                        crane.devHold(true);
                    }
                    case 5 -> {
                        crane.devHold(false);
                        finish(l);
                        player.connection.teleport(ROOT.getX() + 4.5, ROOT.getY() + 3, ROOT.getZ() - 4.5, 0, 12);
                        PowerModuleMenu.open(player, ROOT, ROOT);
                    }
                    case 6 -> {
                        player.connection.teleport(crane.getBlockPos().getX() + 1.5, crane.getBlockPos().getY(), crane.getBlockPos().getZ() + 1.5, 135, 10);
                        CraneMenu.open(player, crane);
                    }
                    default -> {
                    }
                }
                if (selected < 5) {
                    var c = CAMERA[selected];
                    player.connection.teleport(ROOT.getX() + .5 + c[0], ROOT.getY() + c[1], ROOT.getZ() + .5 + c[2], (float) c[3], (float) c[4]);
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
        var cb = mc.level.getBlockEntity(new BlockPos(ROOT.getX() - 19, ROOT.getY() + 16, ROOT.getZ() - 12)) instanceof BuildingCraneBlockEntity b ? b : null;
        var clp = mc.level.getBlockEntity(ROOT) instanceof PowerModuleBlockEntity m ? m : null;
        if (world) {
            ready = mc.screen == null && cb != null && clp != null && switch (stage) {
                case 0 -> TurbineLoadItem.isHood(cb.carriedTurbine());
                case 1 -> clp.coverOff() && cb.carriedTurbine().isEmpty();
                case 2 -> mc.level.getBlockEntity(ROOT.offset(0, 0, -8)) instanceof TurbineStandBlockEntity s && s.count() == 1;
                case 3 -> TurbineLoadItem.isBladeSection(cb.carriedTurbine());
                default -> mc.level.getBlockState(WALL_LP.offset(0, 2, 6)).isAir() || hole(mc);
            };
        } else if (stage == 5) {
            ready = mc.screen instanceof PowerModuleScreen && mc.player.containerMenu instanceof PowerModuleMenu m && m.present && m.lp && m.coverOff;
        } else {
            ready = mc.screen instanceof CraneScreen && mc.player.containerMenu instanceof CraneMenu m && m.present && m.turbineFound;
        }
        if (!ready && age % 200 == 0) LogUtils.getLogger().info("TURBINE HALL CLIENT waiting: stage={} screen={}", stage, mc.screen == null ? "world" : mc.screen.getClass().getSimpleName());
        if (ready && shown == 2 && mc.screen instanceof PowerModuleScreen) {
            for (var c : mc.screen.children())
                if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals("BLADES")) b.onPress();
        }
        if (!ready || ++shown < 80) return;
        mc.options.hideGui = world;
        mc.getToasts().clear();
        if (shown < 85) return;
        try {
            var dir = mc.gameDirectory.toPath().resolve("turbine-hall-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("TURBINE HALL CLIENT CHECK: rendered {}", NAMES[stage]);
        mc.player.closeContainer();
        requested = false;
        mc.options.hideGui = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("TURBINE HALL CLIENT CHECK PASS: the hood lifted, the open casing, the stocked blade stand, a section on the hook, a missile's hole and the two screens");
            mc.stop();
        }
    }

    /** Any block of the missile's wall gone. */
    private static boolean hole(Minecraft mc) {
        for (int dx = -6; dx <= 6; dx++) for (int dy = 0; dy <= 9; dy++) for (int dz = 6; dz <= 7; dz++)
            if (mc.level.getBlockState(WALL_LP.offset(dx, dy, dz)).isAir()) return true;
        return false;
    }
}
