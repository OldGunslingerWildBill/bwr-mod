package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.turbine.ShaftDynamics;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.core.turbine.TurbineFire;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.power.TurbineEvents;
import dev.bwr.mod.power.TurbineExcursion;
import dev.bwr.mod.power.TurbineFires;
import dev.bwr.mod.power.TurbineLoadItem;
import dev.bwr.mod.power.TurbineMissile;
import dev.bwr.mod.power.TurbineRebuildKitItem;
import dev.bwr.mod.power.TurbineWreckBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrecked turbines (TURBINE-MAINTENANCE.md): a disc burst bursting the casing open so it holds no steam, a thrown blade tearing a hole a
 * good hood patches, the shaft excursion (a burst rotor driven past 180 % breaking loose, the rotor half ploughing through blocks by the
 * protected path and coming to rest on the floor as a wreck block, the train split and wrecked), the wreck cleared by the crane or by
 * breaking, a wrecked LP rebuilt with the crane and a Turbine Rebuild Kit, turbine fires (a cut oil line, the generator's hydrogen)
 * and the saves. The same hall as {@link TurbineMaintenanceTests}: an LP facing east and a generator on its shaft, stepped a tick at a
 * time, at its own far-away origin.
 */
@GameTestHolder("bwrturbine")
@PrefixGameTestTemplate(false)
public final class TurbineWreckTests {
    private TurbineWreckTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(64000 + 160 * (20 + i), 180, 64000);
    }

    @FunctionalInterface
    private interface Body {
        void run(TurbineMaintenanceTests.Hall f) throws Exception;
    }

    private static void run(GameTestHelper h, BlockPos root, Body body) {
        var l = h.getLevel();
        EntityLookupProbe.runWhenLive(h, TurbineMaintenanceTests.Hall.bounds(root), () -> {
            long time = l.getGameTime();
            TurbineMaintenanceTests.Hall f = null;
            try {
                f = new TurbineMaintenanceTests.Hall(l, root);
                body.run(f);
                h.succeed();
            } finally {
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    private static String english(Component c) {
        return c == null ? "" : BwrText.english(c);
    }

    /** A smooth stone floor under the hall, one block under the turbines' floor, so what falls lands. */
    private static void floor(TurbineMaintenanceTests.Hall f) {
        for (var p : BlockPos.betweenClosed(f.root.offset(-20, -1, -14), f.root.offset(20, -1, 14))) {
            f.l.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2 | 16);
        }
    }

    private static void steamIn(TurbineMaintenanceTests.Hall f) {
        f.edit(f.lp, t -> {
            var steam = new CompoundTag();
            steam.putDouble("Mass", 50);
            steam.putDouble("Enthalpy", 2600);
            steam.putDouble("Pressure", 145);
            t.put("Inlet", steam);
        });
    }

    /** A Main Oil Tank against the LP's south side, with oil. */
    private static TurbineAuxBlockEntity tank(TurbineMaintenanceTests.Hall f, double litres) {
        BlockPos p = f.root.relative(Direction.SOUTH, 4);
        f.l.setBlock(p, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.NORTH), 3);
        TurbineAuxBlock.attach(f.l, p);
        var tank = (TurbineAuxBlockEntity) f.l.getBlockEntity(p);
        tank.addOil(litres);
        return tank;
    }

    /** Collects the excursion and fire events posted while a test runs. */
    public static final class Listener {
        final List<TurbineEvents.Excursion> excursions = new ArrayList<>();
        final List<TurbineEvents.Fire> fires = new ArrayList<>();

        @SubscribeEvent
        public void excursion(TurbineEvents.Excursion e) {
            excursions.add(e);
        }

        @SubscribeEvent
        public void fire(TurbineEvents.Fire e) {
            fires.add(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ the wrecked casing

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aDiscBurstBurstsTheCasingOpenAndItDoesNoWorkUntilRebuilt(GameTestHelper h) {
        run(h, at(0), f -> {
            CompoundTag fresh = f.lp.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(!fresh.contains("Wreck") && !fresh.contains("Fire") && !fresh.contains("Holes") && !fresh.contains("DiscBurst"),
                    "a sound section's save gains no new key");
            f.rpm(TurbineBlades.BURST_RPM + 10);
            f.tick(1);
            h.assertTrue(f.lp.wreck() == PowerModuleBlockEntity.WRECK_BURST && f.lp.wrecked() && f.lp.discBurst() && !f.lp.sheared(),
                    "the burst disc bursts the casing: " + f.lp.wreck());
            BlockPos top = f.root.above(4);
            h.assertTrue(f.l.getBlockState(top).getValue(PowerModuleBlock.OPEN) && f.l.getBlockState(top).getShape(f.l, top).isEmpty(),
                    "the hood cells are open: the burst casing is drawn torn and can be walked into");
            h.assertTrue(!f.lp.burning(), "a train with no oil system has no oil to burn");
            f.rpm(0);
            steamIn(f);
            f.tick(1);
            h.assertTrue(f.lp.flowKgPerS > 0 && f.lp.shaftMW == 0 && f.lp.status.contains("Wrecked"), "the steam vents and does no work: " + f.lp.status);
            CompoundTag tag = f.lp.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(tag.getByte("Wreck") == PowerModuleBlockEntity.WRECK_BURST && tag.getBoolean("DiscBurst"), "the wreck is saved");
            f.edit(f.lp, t -> {
            });
            h.assertTrue(f.lp.wrecked() && f.lp.discBurst(), "and loads");
            var s = SignalProviders.of(f.lp);
            h.assertTrue(s.read("wrecked").getAsDouble() == 1 && s.read("wreck").getAsDouble() == 2 && s.read("fire").getAsDouble() == 0, "the signals read it");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aThrownBladeTearsAHoleThatAGoodHoodPatches(GameTestHelper h) {
        run(h, at(1), f -> {
            f.crane(16);
            f.rpm(ShaftDynamics.RATED_RPM);
            PowerTrain.throwBlade(f.l, f.lp, 0, Math.PI / 2); // straight up, through the roof
            h.assertTrue(f.lp.wreck() == PowerModuleBlockEntity.WRECK_HOLED && Double.isFinite(f.lp.hole(0)) && !f.lp.wrecked(),
                    "the blade tore a hole and the casing still holds steam: " + f.lp.wreck());
            var exit = TurbineMissile.casingExit(f.lp.hole(0));
            h.assertTrue(exit != null && exit.hood() && Math.abs(exit.v() - 4.9) < 0.01 && exit.nv() > 0.99, "it left through the roof: " + exit);
            h.assertTrue(TurbineMissile.casingExit(-Math.PI / 2) == null, "a blade going straight down meets no wall (the exhaust opening)");
            f.rpm(0);
            h.assertTrue(f.crane.queueCover(true, null) == null, "COVER OFF queued");
            f.runCrane(40000);
            ItemStack hood = f.casingStand().items().getStackInSlot(0);
            h.assertTrue(TurbineLoadItem.isHood(hood) && !TurbineLoadItem.isGoodHood(hood) && !TurbineLoadItem.hoodBurst(hood)
                    && Double.isFinite(TurbineLoadItem.hoodHoles(hood)[0]), "the holed hood came off as a wrecked hood with its hole: " + hood);
            String why = english(f.crane.queueCover(false, null));
            h.assertTrue(why.contains("No LP turbine hood"), "a wrecked hood does not go back on: " + why);
            f.casingStand().items().extractItem(0, 1, false);
            f.casingStand().items().insertItem(0, new ItemStack(BwrItems.LP_TURBINE_HOOD.get()), false);
            h.assertTrue(f.crane.queueCover(false, null) == null, "COVER ON with a spare hood queued");
            f.runCrane(40000);
            h.assertTrue(!f.lp.coverOff() && f.lp.wreck() == PowerModuleBlockEntity.WRECK_NONE && !f.lp.anyHole(), "the new hood is on and the casing is whole");
        });
    }

    // ------------------------------------------------------------------------------------------------ the shaft excursion

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aBurstRotorDrivenPast180PercentBreaksLooseAndComesToRestOnTheFloor(GameTestHelper h) {
        run(h, at(2), f -> {
            var listener = new Listener();
            NeoForge.EVENT_BUS.register(listener);
            try {
                floor(f);
                f.l.setBlock(f.root.offset(8, 0, 3), BwrBlocks.GENERATOR_COOLING_SKID.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.NORTH), 3);
                TurbineAuxBlock.attach(f.l, f.root.offset(8, 0, 3));
                var cooling = (TurbineAuxBlockEntity) f.l.getBlockEntity(f.root.offset(8, 0, 3));
                cooling.addHydrogen(20);
                // bedrock across the floor 12 blocks south: the rotor half rolls that way and nothing breaks it
                for (int dx = -8; dx <= 8; dx++) {
                    for (int dy = 0; dy <= 1; dy++) f.l.setBlock(f.root.offset(dx, dy, 12), Blocks.BEDROCK.defaultBlockState(), 2);
                }
                f.rpm(TurbineBlades.BURST_RPM + 10);
                PowerTrain.burst(f.l, f.lp, 0, Math.PI / 2); // the fragment straight up, so the floor stays whole
                f.tick(1);
                h.assertTrue(f.lp.discBurst() && listener.excursions.isEmpty(), "a burst at 150 % does not break the rotor loose by itself");
                f.rpm(TurbineBlades.BURST_RPM + 200);
                f.tick(20);
                h.assertTrue(listener.excursions.isEmpty() && !f.lp.sheared(), "nor anywhere short of 180 %");
                f.rpm(TurbineBlades.EXCURSION_RPM + 20);
                f.tick(1);
                h.assertTrue(listener.excursions.size() == 1 && f.lp.sheared(), "past 180 % the burst rotor broke loose: " + listener.excursions.size());
                var e = listener.excursions.get(0);
                h.assertTrue(f.lp.bladeIn(0) != f.lp.bladeIn(1), "one flow's rotor half left the casing");
                h.assertTrue(PowerTrain.members(f.lp).size() == 1 && !PowerTrain.members(f.gen).contains(f.lp), "its coupling sheared: the LP is coupled to nothing");
                h.assertTrue(f.gen.shaftDamage() == 1 && f.lp.shaftDamage() == 1 && f.gen.rpm == 0 && f.lp.rpm == 0, "the train is wrecked and has ground to a stop");
                BlockPos w = e.wreck();
                h.assertTrue(w != null && f.l.getBlockState(w).is(BwrBlocks.TURBINE_SHAFT_WRECK.get()) && f.l.getBlockEntity(w) instanceof TurbineWreckBlockEntity,
                        "it came to rest as a shaft wreck: " + w);
                h.assertTrue(w.getY() == f.root.getY() && f.l.getBlockState(w.below()).is(Blocks.SMOOTH_STONE), "on the floor");
                h.assertTrue(w.getZ() > f.root.getZ() + 3 && w.getZ() < f.root.getZ() + 12, "beside the turbine, to the right of its facing, short of the bedrock: " + w.toShortString());
                h.assertTrue(e.blocksBroken() == 0 && f.l.getBlockState(f.root.offset(0, 0, 12)).is(Blocks.BEDROCK), "it breaks nothing unbreakable");
                h.assertTrue(f.gen.burning() && f.gen.fire() == TurbineFire.Kind.HYDROGEN && cooling.h2Kg() == 0,
                        "the generator's hydrogen escaped at its broken seals and burns: " + f.gen.fire());
                h.assertTrue(!f.lp.burning(), "the LP's train has no oil system: no oil fire");
                f.tick(20);
                h.assertTrue(f.gen.fireFuel() < 20 && f.gen.fireFuel() > 15, "the hydrogen burns down: " + f.gen.fireFuel());
                // the wreck saves its record and the LP which flow broke out
                CompoundTag lp = f.lp.saveWithoutMetadata(f.l.registryAccess());
                h.assertTrue(lp.getByte("Wreck") == PowerModuleBlockEntity.WRECK_SHAFT_OUT && (lp.getByte("BrokenFlow") & 1) == f.lp.brokenFlow(), "the LP's wreck is saved");
                h.assertTrue(((TurbineWreckBlockEntity) f.l.getBlockEntity(w)).source().equals(f.lp.getBlockPos()), "the wreck knows where it came from");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theRotorHalfBreaksBlocksByTheProtectedPathAndHurtsWhatItMeets(GameTestHelper h) {
        run(h, at(3), f -> {
            floor(f);
            // flow A's half rolls to the right of the east-facing LP (south) and out past its east end
            Vec3 seat = TurbineExcursion.seat(f.lp, 0), dir = TurbineExcursion.direction(f.lp, 0);
            h.assertTrue(dir.z > 0.9 && dir.x > 0.2 && Math.abs(dir.y) < 1e-9, "it rolls off across the floor, perpendicular to its axis: " + dir);
            var pig = EntityType.PIG.create(f.l);
            Vec3 p = seat.add(dir.scale(5));
            pig.moveTo(p.x, f.root.getY(), p.z);
            pig.setNoAi(true);
            f.l.addFreshEntity(pig);
            List<BlockPos> wall = new ArrayList<>();
            Vec3 w = seat.add(dir.scale(9));
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos b = new BlockPos((int) Math.floor(w.x) + dx, f.root.getY() + dy, (int) Math.floor(w.z));
                    f.l.setBlock(b, Blocks.STONE.defaultBlockState(), 2);
                    wall.add(b);
                }
            }
            // a protection mod keeps one block of it
            BlockPos kept = wall.get(7);
            Object guard = new Object() {
                @SubscribeEvent
                public void keep(BlockEvent.BreakEvent e) {
                    if (e.getPos().equals(kept)) e.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.register(guard);
            TurbineExcursion.Result r;
            try {
                f.rpm(TurbineBlades.EXCURSION_RPM + 20);
                r = PowerTrain.excursion(f.l, f.lp, 0);
            } finally {
                NeoForge.EVENT_BUS.unregister(guard);
            }
            int broken = 0;
            for (BlockPos b : wall) if (!f.l.getBlockState(b).is(Blocks.STONE)) broken++;
            h.assertTrue(broken >= 1 && broken == r.broken().size(), "it broke through the wall by its energy: " + broken + " " + r.stop());
            h.assertTrue(f.l.getBlockState(kept).is(Blocks.STONE), "never the block a protection mod keeps");
            h.assertTrue(r.entitiesHit() >= 1 && (!pig.isAlive() || pig.getHealth() < pig.getMaxHealth()), "the pig in its way was hit");
            h.assertTrue(r.wreck() != null && r.end().distanceTo(seat) < 17, "and it came to rest on the floor within about 16 m: " + r.end().distanceTo(seat));
            h.assertTrue(f.l.getBlockState(f.root).is(BwrBlocks.LP_TURBINE.get()), "its own casing is not broken");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aShaftWreckIsClearedByTheCraneOrByBreakingIt(GameTestHelper h) {
        run(h, at(4), f -> {
            f.crane(16);
            floor(f);
            BlockPos w = f.root.offset(-7, 0, 5), v = f.root.offset(-10, 0, -4);
            f.l.setBlock(w, BwrBlocks.TURBINE_SHAFT_WRECK.get().defaultBlockState(), 3);
            f.l.setBlock(v, BwrBlocks.TURBINE_SHAFT_WRECK.get().defaultBlockState(), 3);
            h.assertTrue(f.crane.queueBlades(true, w) == null, "BLADES OUT queued for the wreck: " + english(f.crane.queueBlades(true, w)));
            f.runCrane(40000);
            h.assertTrue(f.l.getBlockState(w).isAir() && f.crane.carriedTurbine().isEmpty(), "the wreck was lifted away: " + english(f.crane.haltMessage()));
            int damaged = 0;
            for (int i = 0; i < 2; i++) {
                ItemStack s = f.bladeStand().items().getStackInSlot(i);
                if (s.is(BwrItems.DAMAGED_LP_BLADE_SECTION.get()) && TurbineLoadItem.condition(s) == 1) damaged++;
            }
            h.assertTrue(damaged == 1, "it lies on a blade stand as a damaged section");
            h.assertTrue(f.crane.queueBlades(true, null) == null, "with no open LP, BLADES OUT finds the nearest wreck on its own");
            f.runCrane(40000);
            h.assertTrue(f.l.getBlockState(v).isAir() && f.bladeStand().count() == 2, "the second one too");
            BlockPos u = f.root.offset(-8, 0, -5);
            f.l.setBlock(u, BwrBlocks.TURBINE_SHAFT_WRECK.get().defaultBlockState(), 3);
            f.l.destroyBlock(u, true);
            h.assertTrue(f.l.getBlockState(u).isAir() && !f.l.getEntitiesOfClass(ItemEntity.class, new AABB(u).inflate(2),
                    e -> e.getItem().is(BwrItems.DAMAGED_LP_BLADE_SECTION.get())).isEmpty(), "broken up, a wreck drops a damaged section");
            h.assertTrue(f.l.getEntitiesOfClass(ItemEntity.class, new AABB(f.root).inflate(30), e -> !e.getItem().is(BwrItems.DAMAGED_LP_BLADE_SECTION.get())).isEmpty(),
                    "nothing else dropped");
        });
    }

    // ------------------------------------------------------------------------------------------------ repair

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void aBurstLpIsRebuiltWithTheCraneAndAKitAndWorksAgain(GameTestHelper h) {
        run(h, at(5), f -> {
            f.crane(16);
            f.rpm(TurbineBlades.BURST_RPM + 10);
            f.tick(1);
            h.assertTrue(f.lp.wrecked(), "the disc burst");
            f.rpm(0);
            var player = FakePlayerFactory.getMinecraft(f.l);
            var kit = new ItemStack(BwrItems.TURBINE_REBUILD_KIT.get(), 2);
            h.assertTrue(english(f.lp.rebuildProblem()).contains("hood off"), "an LP is rebuilt open: " + english(f.lp.rebuildProblem()));
            h.assertTrue(f.crane.queueCover(true, null) == null, "COVER OFF of the wrecked LP queued");
            f.runCrane(40000);
            ItemStack hood = f.casingStand().items().getStackInSlot(0);
            h.assertTrue(TurbineLoadItem.hoodBurst(hood) && !TurbineLoadItem.isGoodHood(hood) && f.lp.coverOff(), "the burst hood came off as a wrecked hood");
            h.assertTrue(english(f.lp.rebuildProblem()).contains("BLADES OUT"), "with the rotor out: " + english(f.lp.rebuildProblem()));
            for (int i = 0; i < 2; i++) {
                h.assertTrue(f.crane.queueBlades(true, null) == null, "BLADES OUT " + i + " queued");
                f.runCrane(40000);
            }
            h.assertTrue(!f.lp.bladeIn(0) && !f.lp.bladeIn(1) && f.bladeStand().count() == 2, "both stripped sections are on the stand");
            f.bladeStand().items().extractItem(0, 1, false);
            f.bladeStand().items().extractItem(1, 1, false);
            f.bladeStand().items().insertItem(0, new ItemStack(BwrItems.LP_BLADE_SECTION.get()), false);
            f.bladeStand().items().insertItem(1, new ItemStack(BwrItems.LP_BLADE_SECTION.get()), false);
            String why = english(f.crane.queueBlades(false, null));
            h.assertTrue(why.contains("wrecked") && !f.crane.busy() && f.bladeStand().count() == 2, "no section goes into a wrecked casing: " + why);
            TurbineRebuildKitItem.use(f.lp, player, kit);
            h.assertTrue(f.lp.wreck() == PowerModuleBlockEntity.WRECK_NONE && f.lp.shaftDamage() == 0 && !f.lp.discBurst() && kit.getCount() == 1,
                    "the kit rebuilt the casing, bearings and couplings and was used up: " + f.lp.wreck() + " " + kit.getCount());
            h.assertTrue(english(f.lp.rebuildProblem()).contains("nothing to rebuild"), "a sound section needs no kit");
            for (int i = 0; i < 2; i++) {
                h.assertTrue(f.crane.queueBlades(false, null) == null, "BLADES IN " + i + " queued: " + english(f.crane.queueBlades(false, null)));
                f.runCrane(40000);
            }
            h.assertTrue(f.lp.bladeIn(0) && f.lp.bladeIn(1) && f.lp.blade(0) == 0 && f.lp.blade(1) == 0, "two new sections are in");
            h.assertTrue(english(f.crane.queueCover(false, null)).contains("No LP turbine hood"), "the burst hood does not go back on");
            f.casingStand().items().extractItem(0, 1, false);
            f.casingStand().items().insertItem(0, new ItemStack(BwrItems.LP_TURBINE_HOOD.get()), false);
            h.assertTrue(f.crane.queueCover(false, null) == null, "COVER ON with a spare hood queued");
            f.runCrane(40000);
            h.assertTrue(!f.lp.coverOff() && f.lp.damage() == 0 && !f.lp.wrecked(), "the LP is whole again");
            BlockPos top = f.root.above(4);
            h.assertTrue(!f.l.getBlockState(top).getValue(PowerModuleBlock.OPEN), "its hood cells are closed");
            steamIn(f);
            f.tick(1);
            h.assertTrue(!f.lp.status.contains("vents") && !f.lp.status.contains("Wrecked"), "steam no longer vents from it: " + f.lp.status);
        });
    }

    // ------------------------------------------------------------------------------------------------ fires

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aDiscBurstCutsTheOilLinesAndTheFireBurnsUntilWaterPutsItOut(GameTestHelper h) {
        run(h, at(6), f -> {
            var listener = new Listener();
            NeoForge.EVENT_BUS.register(listener);
            try {
                floor(f);
                var tank = tank(f, 5000);
                f.rpm(TurbineBlades.BURST_RPM + 10);
                f.tick(1);
                h.assertTrue(f.lp.burning() && f.lp.fire() == TurbineFire.Kind.OIL && Math.abs(tank.oilLitres() - 4000) < 1,
                        "the fragment cut the oil lines and the oil caught: " + f.lp.fire() + " " + tank.oilLitres());
                h.assertTrue(listener.fires.size() == 1 && listener.fires.get(0).kind() == TurbineFire.Kind.OIL, "the fire event");
                f.rpm(0);
                f.tick(40);
                List<BlockPos> cells = TurbineFires.fireCells(f.lp);
                long lit = cells.stream().filter(p -> f.l.getBlockState(p).is(Blocks.FIRE)).count();
                h.assertTrue(lit >= 1, "real fire burns beside the bearings: " + lit + " of " + cells.size());
                h.assertTrue(f.lp.fireFuel() < 1000 && f.lp.fireFuel() > 990, "the pool burns down at 2 L/s: " + f.lp.fireFuel());
                CompoundTag tag = f.lp.saveWithoutMetadata(f.l.registryAccess());
                h.assertTrue("OIL".equals(tag.getString("Fire")) && tag.getDouble("FireFuel") > 0, "the fire is saved");
                var s = SignalProviders.of(f.lp);
                h.assertTrue(s.read("fire").getAsDouble() == 1 && s.read("fire_fuel").getAsDouble() > 0, "the fire signals read it");
                if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                    var status = new dev.bwr.mod.peripheral.PowerModulePeripheral(f.lp).getStatus();
                    h.assertTrue(Boolean.TRUE.equals(status.get("fire")) && "oil".equals(status.get("fireKind")) && "burst".equals(status.get("wreck")), "the computer reads it: " + status);
                }
                BlockPos wet = cells.get(0);
                f.l.setBlock(wet, Blocks.WATER.defaultBlockState(), 3);
                f.tick(20);
                h.assertTrue(!f.lp.burning() && f.lp.fireFuel() == 0, "water on the fire put it out");
                h.assertTrue(listener.fires.size() == 1, "one fire");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aFireCostsTheSectionItsConditionAndBurnsOutWithoutFuel(GameTestHelper h) {
        run(h, at(7), f -> {
            floor(f);
            tank(f, 300);
            f.rpm(ShaftDynamics.RATED_RPM);
            PowerTrain.throwBlade(f.l, f.lp, 1, -Math.PI / 2 + 0.2); // down through the lower casing, where the oil lines run
            h.assertTrue(f.lp.burning() && Math.abs(f.lp.fireFuel() - 300) < 1, "a blade thrown down cut a line: all 300 L of the tank's oil caught: " + f.lp.fireFuel());
            f.rpm(0);
            f.edit(f.lp, t -> t.putDouble("ShaftDamage", 0));
            f.tick(200);
            double d = f.lp.shaftDamage();
            h.assertTrue(Math.abs(d - 10 * TurbineFire.DAMAGE_PER_S) < 0.005, "ten seconds of fire cost the section 4 %: " + d);
            f.tick(20 * 150);
            h.assertTrue(!f.lp.burning(), "with nothing left to burn it went out: " + f.lp.fireFuel());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aBladeThrownSidewaysCutsNoOilLine(GameTestHelper h) {
        run(h, at(8), f -> {
            var tank = tank(f, 3000);
            f.rpm(ShaftDynamics.RATED_RPM);
            PowerTrain.throwBlade(f.l, f.lp, 0, 0.1);
            h.assertTrue(!f.lp.burning() && tank.oilLitres() == 3000, "a blade going out sideways through the hood leaves the oil lines whole");
        });
    }
}
