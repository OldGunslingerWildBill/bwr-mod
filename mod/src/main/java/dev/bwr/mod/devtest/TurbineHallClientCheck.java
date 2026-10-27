package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.turbine.TurbineBlades;
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
 * wall, and the LP's BLADES screen and the crane's pendant; then a second train (HP, LP, generator, an oil tank and a hydrogen-cooled
 * generator) wrecked: the LP's disc burst, the burst rotor driven past 180 % until it broke loose and its rotor half lies on the floor,
 * the oil and hydrogen fires, the wrecked LP's screen, and the train rebuilt. A fresh flat world; every move is the block entities' own
 * logic. Saves 1920 x 1080 pictures under run/turbineModelCheck/turbine-hall-check.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TurbineHallClientCheck {
    private TurbineHallClientCheck() {
    }

    private static boolean started, requested;
    private static int age, stage, shown, joined;
    private static final BlockPos ROOT = new BlockPos(200, 100, 200);
    private static final BlockPos WALL_LP = ROOT.offset(0, 0, 40);
    /** The train that is wrecked: its LP's controller. */
    private static final BlockPos WRECK = ROOT.offset(60, 0, 30);
    private static PowerModuleBlockEntity lp, wlp, whp, wgen;
    private static BlockPos wreckAt;
    private static BuildingCraneBlockEntity crane;
    private static final String[] NAMES = {"crane-lifting-lp-hood", "open-lp-casing-blades", "lp-blade-stand-stocked", "blade-section-on-the-hook",
            "turbine-missile-hole", "gui-lp-blades", "gui-crane-turbine-hall", "wrecked-lp-burst", "shaft-excursion-rotor-on-the-floor",
            "turbine-fire", "gui-lp-wrecked-fire", "repaired-turbine-train"};
    /** x, y, z, yaw, pitch of the camera relative to the root for the world pictures (null for a screen). */
    private static final double[][] CAMERA = {{12, 8, 15, 140, -8}, {7, 9, 7, 135, 42}, {9, 5, -15, 120, 18}, {12, 11, -14, 60, 22}, {0.5, 6, 61, 180, 10},
            null, null, {70, 9, 41, 138, 30}, {73, 8, 51, 140, 24}, {53, 5, 21, -40, 16}, null, {70, 9, 41, 138, 30}};

    private static boolean world(int stage) {
        return CAMERA[stage] != null;
    }

    /** The second train, the one that is wrecked: an HP, the LP and the generator, an oil tank on the LP and a cooling skid with hydrogen on the generator. */
    private static void setupWreck(ServerLevel l) {
        for (var p : BlockPos.betweenClosed(WRECK.offset(-24, -1, -14), WRECK.offset(24, -1, 30))) l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        whp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), WRECK.west(8), Direction.EAST); // coupled to the LP
        wlp = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), WRECK, Direction.EAST);
        wgen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), WRECK.east(8), Direction.EAST);
        var tag = wgen.saveWithoutMetadata(l.registryAccess());
        tag.putDouble("Energy", PowerModuleBlockEntity.ENERGY_CAPACITY);
        wgen.loadWithComponents(tag, l.registryAccess());
        BlockPos tank = WRECK.north(4), skid = WRECK.east(8).north(3);
        l.setBlock(tank, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(dev.bwr.mod.power.TurbineAuxBlock.FACING, Direction.NORTH), 3);
        dev.bwr.mod.power.TurbineAuxBlock.attach(l, tank);
        ((dev.bwr.mod.power.TurbineAuxBlockEntity) l.getBlockEntity(tank)).addOil(12_000);
        l.setBlock(skid, BwrBlocks.GENERATOR_COOLING_SKID.get().defaultBlockState().setValue(dev.bwr.mod.power.TurbineAuxBlock.FACING, Direction.NORTH), 3);
        dev.bwr.mod.power.TurbineAuxBlock.attach(l, skid);
        ((dev.bwr.mod.power.TurbineAuxBlockEntity) l.getBlockEntity(skid)).addHydrogen(20);
        // a block wall the rotor half ploughs into
        for (int dx = -2; dx <= 5; dx++) for (int dy = 0; dy <= 1; dy++) l.setBlock(WRECK.offset(dx, dy, 13), Blocks.STONE_BRICKS.defaultBlockState(), 2);
    }

    private static void spin(double rpm) {
        for (var m : new PowerModuleBlockEntity[]{whp, wlp, wgen}) m.rpm = rpm;
    }

    /** The wrecked train rebuilt as a player would after the crane's lifts: the fires out, the sections and hood new, each section rebuilt with a kit. */
    private static void rebuild(ServerLevel l) {
        for (var m : new PowerModuleBlockEntity[]{whp, wlp, wgen}) {
            m.extinguish();
            for (BlockPos p : dev.bwr.mod.power.TurbineFires.fireCells(m)) if (l.getBlockState(p).is(Blocks.FIRE)) l.removeBlock(p, false);
        }
        spin(0);
        wlp.setCoverOff(true);
        for (int f = 0; f < 2; f++) wlp.takeBladeSection(f);
        for (var m : new PowerModuleBlockEntity[]{whp, wlp, wgen}) m.rebuild();
        for (int f = 0; f < 2; f++) wlp.installBladeSection(f, 0);
        wlp.setCoverOff(false);
        wlp.fitHood();
        if (wreckAt != null) l.removeBlock(wreckAt, false);
    }

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
        setupWreck(l);
        l.setDayTime(6000);
        l.setWeatherParameters(6000, 0, false, false);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.turbineHallCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 12000) throw new IllegalStateException("Turbine hall client check timeout, stage " + stage);
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
        boolean world = world(stage);
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
                    case 7 -> {
                        spin(TurbineBlades.BURST_RPM + 10);
                        PowerTrain.burst(l, wlp, 0, 1.25); // the disc fragment goes up and out across the hall
                    }
                    case 8 -> {
                        // the steam still admitted, the burst rotor driven on: past 180 % it breaks loose
                        spin(TurbineBlades.EXCURSION_RPM + 40);
                        var r = PowerTrain.excursion(l, wlp, 0);
                        wreckAt = r.wreck();
                    }
                    case 10 -> {
                        player.connection.teleport(WRECK.getX() - 4.5, WRECK.getY() + 1, WRECK.getZ() - 5.5, -45, 12);
                        PowerModuleMenu.open(player, WRECK, WRECK);
                    }
                    case 11 -> rebuild(l);
                    default -> {
                    }
                }
                if (world(selected)) {
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
        var cw = mc.level.getBlockEntity(WRECK) instanceof PowerModuleBlockEntity m ? m : null;
        if (world) {
            ready = mc.screen == null && cb != null && clp != null && switch (stage) {
                case 0 -> TurbineLoadItem.isHood(cb.carriedTurbine());
                case 1 -> clp.coverOff() && cb.carriedTurbine().isEmpty();
                case 2 -> mc.level.getBlockEntity(ROOT.offset(0, 0, -8)) instanceof TurbineStandBlockEntity s && s.count() == 1;
                case 3 -> TurbineLoadItem.isBladeSection(cb.carriedTurbine());
                case 4 -> mc.level.getBlockState(WALL_LP.offset(0, 2, 6)).isAir() || hole(mc);
                case 7 -> cw != null && cw.wreck() == PowerModuleBlockEntity.WRECK_BURST;
                case 8 -> cw != null && cw.sheared() && wreckAt != null && mc.level.getBlockEntity(wreckAt) instanceof dev.bwr.mod.power.TurbineWreckBlockEntity;
                case 9 -> cw != null && dev.bwr.mod.power.TurbineFires.fireCells(cw).stream().anyMatch(p -> mc.level.getBlockState(p).is(Blocks.FIRE));
                default -> cw != null && cw.wreck() == PowerModuleBlockEntity.WRECK_NONE && !cw.coverOff() && cw.bladeIn(0) && cw.bladeIn(1);
            };
        } else if (stage == 5) {
            ready = mc.screen instanceof PowerModuleScreen && mc.player.containerMenu instanceof PowerModuleMenu m && m.present && m.lp && m.coverOff;
        } else if (stage == 10) {
            ready = mc.screen instanceof PowerModuleScreen && mc.player.containerMenu instanceof PowerModuleMenu m && m.present && m.lp && m.wreck == 3 && m.fire != 0;
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
            LogUtils.getLogger().info("TURBINE HALL CLIENT CHECK PASS: the hood lifted, the open casing, the stocked blade stand, a section on the hook, a missile's hole, the two screens, "
                    + "a burst LP, a rotor half on the floor after a shaft excursion, the turbine fires, the wrecked LP's screen and the train rebuilt");
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
