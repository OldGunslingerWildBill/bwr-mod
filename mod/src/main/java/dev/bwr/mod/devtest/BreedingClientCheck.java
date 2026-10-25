package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.core.fuel.CoreInsert;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.CoreInsertData;
import dev.bwr.mod.fuel.SpecialtyRodItem;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.gui.RtgMenu;
import dev.bwr.mod.gui.client.RefuellingScreen;
import dev.bwr.mod.gui.client.RtgScreen;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
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
 * Opt-in pictures of the reactor-bred isotopes (-PbwrBreedingClientCheck, BREEDING-TARGETS.md): the four blocks in the world, every
 * new item and rod in a chest screen, an RTG screen reading six polonium-210 ingots, and a real core's refuelling screen with
 * the six new target rods part way through their irradiation. 1920 x 1080 pictures under run/.../breeding-check.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BreedingClientCheck {
    private BreedingClientCheck() {}

    private static boolean started, requested;
    private static int age, stage, shown, joined;
    private static final BlockPos ROOT = new BlockPos(200, 200, 200);
    private static final BlockPos MIN = new BlockPos(6000, 200, 6000);
    private static final String[] NAMES = {"blocks-world", "items-chest", "gui-rtg-polonium", "gui-refuelling-targets"};

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.breedingClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (++age > 4000) throw new IllegalStateException("Breeding client check timeout, stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(65);
            mc.createWorldOpenFlows().createFreshLevel("bwr-breeding-check-" + System.currentTimeMillis(),
                    new LevelSettings("Breeding check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (++joined < 40) return;
        boolean world = stage == 0;
        if (!requested) {
            requested = true;
            shown = 0;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            int selected = stage;
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.closeContainer();
                player.getAbilities().flying = true;
                player.setNoGravity(true);
                player.onUpdateAbilities();
                try {
                    switch (selected) {
                        case 0 -> {
                            for (int x = 8; x <= 17; x++) for (int z = 8; z <= 17; z++) { l.setChunkForced(x, z, true); l.getChunk(x, z); }
                            for (var p : BlockPos.betweenClosed(ROOT.offset(-8, -1, -8), ROOT.offset(10, -1, 8))) l.setBlock(p, net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                            var blocks = new net.minecraft.world.level.block.Block[] {BwrBlocks.POLONIUM_210_BLOCK.get(), BwrBlocks.PLUTONIUM_238_BLOCK.get(), BwrBlocks.ACTINIUM_227_BLOCK.get(), BwrBlocks.THULIUM_170_BLOCK.get()};
                            for (int i = 0; i < blocks.length; i++) l.setBlock(ROOT.offset(i, 0, 0), blocks[i].defaultBlockState(), 3);
                            l.setBlock(ROOT.offset(0, 1, 0), BwrBlocks.POLONIUM_210_BLOCK.get().defaultBlockState(), 3);
                            l.setBlock(ROOT.offset(3, 1, 0), BwrBlocks.THULIUM_170_BLOCK.get().defaultBlockState(), 3);
                            l.setDayTime(6000);
                            l.setWeatherParameters(6000, 0, false, false);
                            player.connection.teleport(ROOT.getX() + 2.0, ROOT.getY() + 1.6, ROOT.getZ() + 5.5, 180, 12);
                        }
                        case 1 -> {
                            var chest = ROOT.offset(0, 0, -4);
                            l.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
                            var be = (net.minecraft.world.level.block.entity.ChestBlockEntity) l.getBlockEntity(chest);
                            net.minecraft.world.item.Item[] items = {
                                    BwrItems.BISMUTH_NUGGET.get(), BwrItems.POLONIUM_210_INGOT.get(), BwrItems.POLONIUM_210_BLOCK.get(),
                                    BwrItems.URANIUM_233_INGOT.get(), BwrItems.NEPTUNIUM_237_INGOT.get(),
                                    BwrItems.PLUTONIUM_238_INGOT.get(), BwrItems.PLUTONIUM_238_BLOCK.get(),
                                    BwrItems.ACTINIUM_227_INGOT.get(), BwrItems.ACTINIUM_227_BLOCK.get(),
                                    BwrItems.THULIUM_170_INGOT.get(), BwrItems.THULIUM_170_BLOCK.get()};
                            for (int i = 0; i < items.length; i++) be.setItem(i, new ItemStack(items[i]));
                            CoreInsert.Kind[] kinds = {CoreInsert.Kind.BISMUTH_TARGET, CoreInsert.Kind.THORIUM_TARGET, CoreInsert.Kind.URANIUM_TARGET,
                                    CoreInsert.Kind.NEPTUNIUM_TARGET, CoreInsert.Kind.RADIUM_TARGET, CoreInsert.Kind.THULIUM_TARGET};
                            for (int i = 0; i < kinds.length; i++) {
                                be.setItem(18 + i, SpecialtyRodItem.stack(new CoreInsertData(kinds[i], kinds[i].exposureSeconds * (0.15 + 0.15 * i))));
                            }
                            be.setItem(26, new ItemStack(BwrItems.IRRADIATION_CASING.get()));
                            player.openMenu(be);
                        }
                        case 2 -> {
                            l.setBlock(ROOT.offset(0, 0, 6), BwrBlocks.RTG.get().defaultBlockState(), 3);
                            var rtg = (RtgBlockEntity) l.getBlockEntity(ROOT.offset(0, 0, 6));
                            for (int i = 0; i < 6; i++) rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.POLONIUM_210_INGOT.get()));
                            player.connection.teleport(ROOT.getX() + .5, ROOT.getY() + 3, ROOT.getZ() + 11.5, 180, 12);
                            RtgMenu.open(player, rtg);
                        }
                        default -> {
                            var be = CompactCoreRegressionTests.build(l, MIN, 5, 5, 8, false);
                            if (!be.isFormed()) throw new AssertionError(be.statusLines());
                            CoreInsert.Kind[] kinds = {CoreInsert.Kind.BISMUTH_TARGET, CoreInsert.Kind.THORIUM_TARGET, CoreInsert.Kind.URANIUM_TARGET,
                                    CoreInsert.Kind.NEPTUNIUM_TARGET, CoreInsert.Kind.RADIUM_TARGET, CoreInsert.Kind.THULIUM_TARGET};
                            int[] positions = be.corePositions();
                            int rod = 0;
                            for (int i = 0; i < positions.length; i++) {
                                if (i % 4 == 2 && rod < kinds.length) {
                                    var kind = kinds[rod];
                                    be.loadAssembly(positions[i], SpecialtyRodItem.stack(new CoreInsertData(kind, kind.exposureSeconds * (0.1 + 0.16 * rod))));
                                    rod++;
                                } else {
                                    be.loadAssembly(positions[i], new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
                                }
                            }
                            be.setVesselState(VesselState.REFUELING);
                            var root = MIN.offset(-1, 3, 2);
                            player.connection.teleport(root.getX() - 2, root.getY(), root.getZ(), -90, 0);
                            RefuellingMenu.open(player, (ReactorControllerBlockEntity) l.getBlockEntity(root));
                        }
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        boolean ready;
        if (world) {
            ready = mc.screen == null && mc.level.getBlockState(ROOT).is(BwrBlocks.POLONIUM_210_BLOCK.get());
        } else if (stage == 1) {
            ready = mc.screen instanceof ContainerScreen;
        } else if (stage == 2) {
            ready = mc.screen instanceof RtgScreen && mc.player.containerMenu instanceof RtgMenu m && m.present && m.capsules == 6;
        } else {
            ready = mc.screen instanceof RefuellingScreen && mc.player.containerMenu instanceof RefuellingMenu m && m.map != null && m.map.coreSlotCount > 0;
        }
        if (!ready && age % 200 == 0) LogUtils.getLogger().info("BREEDING CLIENT waiting: stage={} screen={}", stage, mc.screen == null ? "world" : mc.screen.getClass().getSimpleName());
        if (!ready || ++shown < 80) return;
        mc.options.hideGui = world;
        mc.getToasts().clear();
        if (shown < 85) return;
        if (stage == 2 && mc.player.containerMenu instanceof RtgMenu m) {
            double expected = 6 * dev.bwr.core.fuel.BreedingTargets.PO210_INGOT_GRAMS * 140.0;
            if (Math.abs(m.thermalWatts - expected) > 0.05 * expected || m.fePerTick <= 0.0) {
                throw new IllegalStateException("The RTG screen does not read six polonium ingots: " + m.thermalWatts + " W, expected " + expected);
            }
        }
        if (stage == 3 && mc.player.containerMenu instanceof RefuellingMenu m) {
            int inserts = 0;
            for (int i = 0; i < m.map.coreSlotCount; i++) {
                if (m.map.isInsert(i)) {
                    inserts++;
                    if (!(m.map.insertProgress[i] > 0.05 && m.map.insertProgress[i] < 1.0)) {
                        throw new IllegalStateException("A target in the map shows progress " + m.map.insertProgress[i]);
                    }
                }
            }
            if (inserts != 6) throw new IllegalStateException("The refuelling map shows " + inserts + " targets, not 6");
        }
        try {
            var dir = mc.gameDirectory.toPath().resolve("breeding-check");
            java.nio.file.Files.createDirectories(dir);
            CheckShots.save(mc, dir.resolve(NAMES[stage] + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        LogUtils.getLogger().info("BREEDING CLIENT CHECK: rendered {}", NAMES[stage]);
        mc.player.closeContainer();
        requested = false;
        mc.options.hideGui = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("BREEDING CLIENT CHECK PASS: the new blocks, items and rods in the world and in their screens");
            mc.stop();
        }
    }
}
