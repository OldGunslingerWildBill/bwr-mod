package dev.bwr.mod.devtest;

import dev.bwr.core.grid.Synchronizer;
import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.turbine.PowerTurbine;
import dev.bwr.core.turbine.ShaftDynamics;
import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.grid.GridTieBlockEntity;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.power.PowerTrain;
import dev.bwr.mod.power.TurbineEvents;
import dev.bwr.mod.power.TurbineLoadItem;
import dev.bwr.mod.power.TurbineMissile;
import dev.bwr.mod.power.TurbineStandBlock;
import dev.bwr.mod.power.TurbineStandBlockEntity;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.CraneRunwayRailBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The turbine hall (TURBINE-MAINTENANCE.md): the LP blade sections' damage causes (out-of-phase closing scaled by the angle, reverse
 * power heating the exhaust hood over time, wet-steam erosion as before, overspeed), the turbine missile (thrown at failure with the
 * shaft fast, through blocks by its energy, stopped by a chunk that is not loaded, none at standstill), the crane's COVER OFF / ON and
 * BLADES OUT / IN with their refusals and a save mid-lift, the stands as item handlers, and old saves. Each test builds an LP turbine and
 * a generator on one shaft at its own far-away origin and steps the real {@link PowerTrain} (and the real crane) a tick at a time.
 */
@GameTestHolder("bwrturbine")
@PrefixGameTestTemplate(false)
public final class TurbineMaintenanceTests {
    private TurbineMaintenanceTests() {
    }

    private static BlockPos at(int i) {
        return new BlockPos(64000 + 160 * i, 180, 64000);
    }

    /** An LP facing east at {@code root}, the generator on its shaft east of it; optionally a building crane over them with both stands. */
    static final class Hall {
        final ServerLevel l;
        final BlockPos root;
        final PowerModuleBlockEntity lp, gen;
        long t;
        BlockPos console, casing, blades, tiePos;
        BuildingCraneBlockEntity crane;
        GridTieBlockEntity tie;
        final List<Phase> phases = new ArrayList<>();

        Hall(ServerLevel l, BlockPos root) {
            this.l = l;
            this.root = root;
            t = l.getGameTime();
            clear();
            lp = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), root, Direction.EAST);
            gen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), root.east(8), Direction.EAST);
            edit(gen, tag -> tag.putDouble("Energy", PowerModuleBlockEntity.ENERGY_CAPACITY));
        }

        static AABB bounds(BlockPos root) {
            return new AABB(root.getX() - 30, root.getY() - 9, root.getZ() - 16, root.getX() + 22, root.getY() + 22, root.getZ() + 16);
        }

        void clear() {
            for (var p : BlockPos.betweenClosed(root.offset(-29, -8, -15), root.offset(21, 21, 15))) {
                if (!l.getBlockState(p).isAir()) l.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
            for (var e : l.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(40))) e.discard();
        }

        /** The crane over the hall: rails along x at y root+railDy, z root-12 and root+12; a casing stand south of the LP, a blade stand north. */
        Hall crane(int railDy) {
            int y = root.getY() + railDy;
            for (int x = -14; x <= 16; x++) {
                for (int z : new int[]{-12, 12}) {
                    l.setBlock(new BlockPos(root.getX() + x, y, root.getZ() + z), BwrBlocks.CRANE_RUNWAY_RAIL.get().defaultBlockState()
                            .setValue(CraneRunwayRailBlock.AXIS, Direction.Axis.X), 2);
                }
            }
            console = new BlockPos(root.getX() - 15, y, root.getZ() - 12);
            l.setBlock(console, BwrBlocks.BUILDING_CRANE_CONSOLE.get().defaultBlockState(), 2);
            crane = (BuildingCraneBlockEntity) l.getBlockEntity(console);
            casing = root.offset(0, 0, 8);
            l.setBlock(casing, BwrBlocks.CASING_LAYDOWN_STAND.get().defaultBlockState().setValue(TurbineStandBlock.FACING, Direction.EAST), 3);
            blades = root.offset(0, 0, -8);
            l.setBlock(blades, BwrBlocks.LP_BLADE_STAND.get().defaultBlockState().setValue(TurbineStandBlock.FACING, Direction.NORTH), 3);
            crane.refreshSite();
            return this;
        }

        TurbineStandBlockEntity casingStand() {
            return (TurbineStandBlockEntity) l.getBlockEntity(casing);
        }

        TurbineStandBlockEntity bladeStand() {
            return (TurbineStandBlockEntity) l.getBlockEntity(blades);
        }

        /** A grid tie bound to the generator, demand nothing. */
        GridTieBlockEntity tie() {
            tiePos = root.offset(4, 0, 6);
            l.setBlock(tiePos, BwrBlocks.GRID_TIE.get().defaultBlockState(), 3);
            tie = (GridTieBlockEntity) l.getBlockEntity(tiePos);
            if (!tie.bindGenerator(gen.getBlockPos())) throw new IllegalStateException("tie did not bind");
            return tie;
        }

        void setPhase(double degrees) {
            var tag = tie.saveWithoutMetadata(l.registryAccess());
            tag.putDouble("Phase", degrees);
            tie.loadWithComponents(tag, l.registryAccess());
        }

        void tick(int ticks) {
            for (int i = 0; i < ticks; i++) {
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(++t);
                PowerTrain.tick(gen);
                if (tie != null) tie.tickDevice();
            }
        }

        void edit(BlockEntity be, Consumer<CompoundTag> change) {
            var tag = be.saveWithoutMetadata(l.registryAccess());
            change.accept(tag);
            be.loadWithComponents(tag, l.registryAccess());
        }

        void rpm(double rpm) {
            for (var m : List.of(lp, gen)) edit(m, tag -> tag.putDouble("RPM", rpm));
        }

        void blades(double a, double b) {
            edit(lp, tag -> {
                var list = new ListTag();
                list.add(DoubleTag.valueOf(a));
                list.add(DoubleTag.valueOf(b));
                tag.put("Blades", list);
            });
        }

        int runCrane(int limit) {
            int n = 0;
            while (crane.busy() && n < limit) {
                if (phases.isEmpty() || phases.get(phases.size() - 1) != crane.phase()) phases.add(crane.phase());
                crane.scheduledTick(l);
                n++;
            }
            return n;
        }

        int runCraneUntil(Phase phase, int limit) {
            int n = 0;
            while (crane.busy() && crane.phase() != phase && n < limit) {
                crane.scheduledTick(l);
                n++;
            }
            return n;
        }

        void finish(long time) {
            if (crane != null) {
                try {
                    crane.abort();
                } catch (RuntimeException ignored) {
                }
            }
            clear();
            ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(Hall f) throws Exception;
    }

    private static void run(GameTestHelper h, BlockPos root, Body body) {
        run(h, root, Hall.bounds(root), body);
    }

    private static void run(GameTestHelper h, BlockPos root, AABB bounds, Body body) {
        var l = h.getLevel();
        long time = l.getGameTime();
        EntityLookupProbe.runWhenLive(h, bounds, () -> {
            Hall f = null;
            try {
                f = new Hall(l, root);
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

    /** Collects the turbine events posted while a test runs. */
    public static final class Listener {
        final List<TurbineEvents.Missile> missiles = new ArrayList<>();
        final List<TurbineEvents.Vibration> vibrations = new ArrayList<>();

        @SubscribeEvent
        public void missile(TurbineEvents.Missile e) {
            missiles.add(e);
        }

        @SubscribeEvent
        public void vibration(TurbineEvents.Vibration e) {
            vibrations.add(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ damage causes

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void anOutOfPhaseCloseDamagesTheTrainScaledByTheAngle(GameTestHelper h) {
        run(h, at(0), f -> {
            f.tie();
            double[] angles = {5, 30, 60, 90, 120};
            double lastBlade = -1, lastShaft = -1;
            for (double angle : angles) {
                f.rpm(ShaftDynamics.RATED_RPM);
                f.blades(0, 0);
                f.edit(f.lp, t -> t.putDouble("ShaftDamage", 0));
                f.edit(f.gen, t -> t.putDouble("ShaftDamage", 0));
                f.tie.tripBreaker();
                f.setPhase(angle);
                double slip = Synchronizer.slip(f.gen.rpm);
                h.assertTrue(f.tie.closeBreaker(), "the close is allowed at " + angle + " degrees");
                double pu = TurbineBlades.closingTorquePu(angle, slip);
                double blade = f.lp.blade(0), shaft = f.lp.shaftDamage();
                h.assertTrue(Math.abs(blade - TurbineBlades.closingBladeDamage(pu)) < 1e-9 && f.lp.blade(1) == blade,
                        "both LP blade sections take the law's damage at " + angle + " deg: " + blade);
                h.assertTrue(Math.abs(shaft - TurbineBlades.closingShaftDamage(pu)) < 1e-9, "the LP shaft and couplings too: " + shaft);
                h.assertTrue(Math.abs(f.gen.damage() - Synchronizer.closingDamage(angle, slip)) < 1e-9, "the generator keeps the grid tie's own law");
                h.assertTrue(f.lp.hasCloseShock() && Math.abs(f.lp.closeAngle() - angle) < 1e-9 && Math.abs(f.lp.closeTorquePu() - pu) < 1e-9,
                        "the close is recorded on the section");
                h.assertTrue(blade >= lastBlade && shaft >= lastShaft, "more angle, no less damage");
                if (angle <= 30) h.assertTrue(blade == 0 && shaft == 0, "a close near the window costs the turbine nothing at " + angle);
                if (angle >= 90) h.assertTrue(blade > 0.2, "a close at " + angle + " degrees hurts the blades: " + blade);
                lastBlade = blade;
                lastShaft = shaft;
            }
            h.assertTrue(f.lp.bladesThrownCount() == 0, "120 degrees damages but throws nothing by itself");
            var tag = f.lp.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(tag.contains("CloseAngle") && tag.contains("ClosePu"), "the last close is saved");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void reversePowerMotorsTheTrainAndWindageHeatsTheBladesOverTime(GameTestHelper h) {
        run(h, at(1), f -> {
            f.tie();
            f.rpm(ShaftDynamics.RATED_RPM);
            f.setPhase(0);
            h.assertTrue(f.tie.closeBreaker() && !f.tie.lastCloseFault(), "an in-phase close");
            double[] hood = new double[6];
            for (int minute = 0; minute < 6; minute++) {
                f.tick(1200);
                hood[minute] = f.lp.hoodC;
                h.assertTrue(f.lp.motoring && f.gen.motoring && f.lp.reverseMW > 0, "the grid drives the train with no steam: minute " + minute);
                h.assertTrue(Math.abs(f.gen.rpm - ShaftDynamics.RATED_RPM) < 0.01, "held in step at synchronous speed: " + f.gen.rpm);
                h.assertTrue(f.gen.electricMW == 0 && f.gen.status.startsWith("Motoring"), "the generator delivers nothing; its status says so: " + f.gen.status);
                if (minute > 0) h.assertTrue(hood[minute] > hood[minute - 1] || hood[minute] > 160, "the hood keeps heating: " + hood[minute]);
            }
            h.assertTrue(hood[0] > TurbineAux.AMBIENT_C + 30 && hood[1] > TurbineBlades.HOOD_DAMAGE_ONSET_C, "the hood passes 120 C within two minutes: " + hood[0] + " " + hood[1]);
            double after6 = f.lp.blade(0);
            h.assertTrue(after6 > 0.3 && f.lp.blade(1) == after6, "six minutes of motoring cost the blades: " + after6);
            double reverse = f.gen.reverseMW;
            double expected = ShaftDynamics.energyKJ(f.lp.shaftInertia() + f.gen.shaftInertia(), ShaftDynamics.RATED_RPM) / ShaftDynamics.H_SECONDS * .005 / 1000;
            h.assertTrue(Math.abs(reverse - expected) < 1e-6, "the reverse power is the train's windage and friction: " + reverse + " MW vs " + expected);
            // nothing opened the breaker; the player does
            h.assertTrue(f.tie.closed(), "the breaker is still closed: nothing acted");
            f.tie.tripBreaker();
            f.tick(40);
            h.assertTrue(!f.lp.motoring && f.lp.reverseMW == 0 && f.gen.rpm < ShaftDynamics.RATED_RPM, "the breaker open: no reverse power, the shaft coasts");
            double peak = f.lp.hoodC;
            f.tick(1200);
            h.assertTrue(f.lp.hoodC < peak - 50, "without windage the hood cools: " + f.lp.hoodC);
            // the same train with the breaker open from the start never motors
            var g = new Hall(f.l, f.root.offset(0, 0, 0));
            g.rpm(ShaftDynamics.RATED_RPM);
            g.tick(1200);
            h.assertTrue(!g.lp.motoring && g.lp.hoodC < TurbineAux.AMBIENT_C + 0.01 && g.lp.blade(0) == 0, "no grid, no motoring, no heat");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void wetSteamErodesTheLpBladeSectionsByTheSameLaw(GameTestHelper h) {
        run(h, at(2), f -> {
            var c = PowerModuleRuntimeCheck.attachCondenser(f.lp);
            double moisture = 0.03, expected = 0;
            f.rpm(1000); // under 110 % speed all the way, so overspeed adds nothing

            for (int i = 0; i < 40; i++) {
                f.edit(f.lp, t -> {
                    var steam = new CompoundTag();
                    steam.putDouble("Mass", 50);
                    steam.putDouble("Enthalpy", 2600);
                    steam.putDouble("Pressure", 145);
                    steam.putDouble("Moisture", moisture);
                    t.put("Inlet", steam);
                });
                c.plant().steam.take(c.plant().steam.mass());
                f.tick(1);
                expected += PowerTurbine.erosionIncrement(f.lp.flowKgPerS / 20, moisture);
            }
            h.assertTrue(expected > 0 && f.lp.flowKgPerS > 0, "steam went through: " + f.lp.flowKgPerS);
            h.assertTrue(Math.abs(f.lp.blade(0) - expected) < 1e-12 && Math.abs(f.lp.blade(1) - expected) < 1e-12,
                    "both sections wear by the existing erosion law: " + f.lp.blade(0) + " vs " + expected);
            h.assertTrue(f.lp.shaftDamage() == 0 && Math.abs(f.lp.damage() - expected) < 1e-12, "the section's condition reads the wear; the shaft has none");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void anOldLpSaveKeepsItsWearAsBladeWear(GameTestHelper h) {
        run(h, at(3), f -> {
            f.edit(f.lp, t -> {
                t.remove("Blades");
                t.putDouble("ShaftDamage", 0.4);
            });
            h.assertTrue(Math.abs(f.lp.blade(0) - 0.4) < 1e-12 && f.lp.blade(1) == f.lp.blade(0) && f.lp.shaftDamage() == 0 && Math.abs(f.lp.damage() - 0.4) < 1e-12,
                    "an old LP's wear becomes the wear of its blade sections");
            h.assertTrue(f.lp.saveWithoutMetadata(f.l.registryAccess()).contains("Blades"), "and is saved the new way");
            f.edit(f.lp, t -> {
                t.remove("Blades");
                t.putDouble("ShaftDamage", 1.0);
            });
            h.assertTrue(f.lp.blade(0) == 0.95, "an old failed LP reads as blades about to fail");
            f.rpm(ShaftDynamics.RATED_RPM);
            f.tick(20);
            h.assertTrue(f.lp.bladesThrownCount() == 0, "and throws nothing the moment it loads");
            var gen = f.gen.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(!gen.contains("Blades") && !gen.contains("CoverOff"), "a generator writes no blade keys");
        });
    }

    // ------------------------------------------------------------------------------------------------ missiles

    /** A shell of stone 6 to 7.5 blocks round the wheel of flow A, in the plane of the wheel, so a missile in any direction meets it. */
    private static List<BlockPos> shell(Hall f) {
        Vec3 w = TurbineMissile.wheel(f.lp, 0);
        List<BlockPos> out = new ArrayList<>();
        int x = (int) Math.floor(w.x);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos p = new BlockPos(x + dx, (int) Math.floor(w.y) + dy, (int) Math.floor(w.z) + dz);
                    double r = Math.hypot(p.getY() + 0.5 - w.y, p.getZ() + 0.5 - w.z);
                    if (r >= 6 && r <= 7.5 && f.l.getBlockState(p).isAir()) {
                        f.l.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    private static int standing(Hall f, List<BlockPos> blocks) {
        int n = 0;
        for (BlockPos p : blocks) if (f.l.getBlockState(p).is(Blocks.STONE)) n++;
        return n;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aFailedSectionThrowsABladeWhileTheShaftIsFastAndItBreaksBlocksByItsEnergy(GameTestHelper h) {
        run(h, at(4), f -> {
            var listener = new Listener();
            NeoForge.EVENT_BUS.register(listener);
            try {
                List<BlockPos> stone = shell(f);
                f.rpm(ShaftDynamics.RATED_RPM);
                f.blades(1.0, 0.2);
                f.tick(1);
                h.assertTrue(f.lp.bladeThrown(0) && !f.lp.bladeThrown(1), "the failed section A threw a blade; B did not");
                h.assertTrue(listener.missiles.size() == 1, "one missile event: " + listener.missiles.size());
                var m = listener.missiles.get(0);
                double energy = TurbineBlades.missileEnergyJ(TurbineBlades.Missile.BLADE, m.rpm());
                h.assertTrue(m.kind() == TurbineBlades.Missile.BLADE && Math.abs(m.energyJ() - energy) < 1 && energy > 3e6,
                        "a blade of " + m.massKg() + " kg at " + m.speedMps() + " m/s, " + m.energyJ() / 1e6 + " MJ");
                h.assertTrue(Math.abs(m.direction().x) < 1e-9, "it flies in the plane of the wheel, across the shaft: " + m.direction());
                int broken = stone.size() - standing(f, stone);
                h.assertTrue(broken >= 1 && broken == m.blocksBroken() && broken <= 6, "it broke through the stone by its energy: " + broken + " blocks");
                double first = Double.MAX_VALUE;
                for (BlockPos p : stone) {
                    if (!f.l.getBlockState(p).is(Blocks.STONE)) {
                        Vec3 c = Vec3.atCenterOf(p).subtract(m.origin());
                        double along = c.dot(m.direction());
                        double off = c.subtract(m.direction().scale(along)).length();
                        h.assertTrue(along > 0 && off < 1.6, "every hole is on the missile's line: " + p.toShortString() + " off " + off);
                        first = Math.min(first, along);
                    }
                }
                h.assertTrue(first > 5 && first < 8, "the first hole is where the shell is: " + first);
                h.assertTrue(f.lp.damage() == 1 && f.lp.vibration > 30, "the section is wrecked and the train shakes: " + f.lp.vibration);
                f.tick(40);
                h.assertTrue(listener.missiles.size() == 1, "a section throws once");
                h.assertTrue(!listener.vibrations.isEmpty() && listener.vibrations.get(listener.vibrations.size() - 1).mmPerS() > 30,
                        "the vibration hook reports the imbalance");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void noMissileFromAFailedSectionAtStandstillOrUnderHalfSpeed(GameTestHelper h) {
        run(h, at(5), f -> {
            List<BlockPos> stone = shell(f);
            f.blades(1.0, 1.0);
            f.rpm(0);
            f.tick(40);
            f.rpm(0.45 * ShaftDynamics.RATED_RPM);
            f.tick(40);
            h.assertTrue(f.lp.bladesThrownCount() == 0 && standing(f, stone) == stone.size(), "a failed section at standstill or under half speed throws nothing");
            f.rpm(0.55 * ShaftDynamics.RATED_RPM);
            f.tick(1);
            h.assertTrue(f.lp.bladesThrownCount() == 2, "past half speed both failed sections let go");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aMissileStopsAtAChunkThatIsNotLoadedAndHurtsWhatItMeets(GameTestHelper h) {
        BlockPos root = at(6);
        // only the chunks up to 10 blocks south of the LP are loaded; the missile goes south (angle 0, to the right of an east-facing LP)
        AABB bounds = new AABB(root.getX() - 30, root.getY() - 2, root.getZ() - 16, root.getX() + 22, root.getY() + 22, root.getZ() + 10);
        run(h, root, bounds, f -> {
            var pig = EntityType.PIG.create(f.l);
            Vec3 w = TurbineMissile.wheel(f.lp, 0);
            pig.moveTo(w.x, w.y - 0.4, w.z + 5);
            pig.setNoAi(true);
            f.l.addFreshEntity(pig);
            f.rpm(ShaftDynamics.RATED_RPM);
            // a forced chunk keeps a border of loaded chunks round it: the first chunk south that is not loaded at all
            int beyond = (int) Math.floor(w.z) >> 4;
            while (f.l.hasChunk((int) Math.floor(w.x) >> 4, beyond) && beyond < ((int) Math.floor(w.z) >> 4) + 8) beyond++;
            ChunkPos next = new ChunkPos((int) Math.floor(w.x) >> 4, beyond);
            h.assertTrue(!f.l.hasChunk(next.x, next.z) && (beyond << 4) - w.z < TurbineMissile.RANGE_BLOCKS, "a chunk south within range is not loaded: " + beyond);
            var r = PowerTrain.throwBlade(f.l, f.lp, 0, 0.0);
            h.assertTrue(r.stop() == TurbineMissile.Stop.UNLOADED, "it stopped at the unloaded chunk: " + r.stop() + " at " + r.end());
            h.assertTrue(Math.floor(r.end().z) >= root.getZ() + 10 && ((int) Math.floor(r.end().z) >> 4) == beyond, "right at its edge: " + r.end());
            h.assertTrue(!f.l.hasChunk(next.x, next.z), "and no chunk was loaded");
            h.assertTrue(r.entitiesHit() >= 1 && (!pig.isAlive() || pig.getHealth() < pig.getMaxHealth()), "the pig on its line was hit");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aDiscBurstsAt150PercentSpeedAndWrecksTheSection(GameTestHelper h) {
        run(h, at(7), f -> {
            List<BlockPos> stone = shell(f);
            f.rpm(TurbineBlades.BURST_RPM + 10);
            f.tick(1);
            h.assertTrue(f.lp.bladesThrownCount() == 2 && f.lp.shaftDamage() == 1, "a disc burst wrecks the section");
            int broken = stone.size() - standing(f, stone);
            h.assertTrue(broken >= 2, "a disc fragment goes through more of the shell than a blade: " + broken);
        });
    }

    // ------------------------------------------------------------------------------------------------ the crane

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void coverOffIsRefusedWithReasons(GameTestHelper h) {
        run(h, at(8), f -> {
            f.crane(16);
            f.rpm(100);
            String why = english(f.crane.queueCover(true, null));
            h.assertTrue(why.contains("turning"), "a turning shaft: " + why);
            f.rpm(0);
            h.assertTrue(f.gen.setTurningGear(true), "the gear engages at standstill");
            why = english(f.crane.queueCover(true, null));
            h.assertTrue(why.contains("turning gear"), "the turning gear on: " + why);
            f.gen.setTurningGear(false);
            f.edit(f.lp, t -> {
                var steam = new CompoundTag();
                steam.putDouble("Mass", 50);
                steam.putDouble("Enthalpy", 2600);
                steam.putDouble("Pressure", 145);
                t.put("Inlet", steam);
            });
            why = english(f.crane.queueCover(true, null));
            h.assertTrue(why.contains("Steam is in the casing"), "steam in the casing: " + why);
            f.edit(f.lp, t -> t.remove("Inlet"));
            f.edit(f.lp, t -> t.putDouble("RotorC", 160));
            why = english(f.crane.queueCover(true, null));
            h.assertTrue(why.contains("too hot"), "a hot casing: " + why);
            f.edit(f.lp, t -> t.putDouble("RotorC", 30));
            double mass = GameplayConfig.TURBINE_HOOD_MASS_T.get();
            try {
                GameplayConfig.TURBINE_HOOD_MASS_T.set(140.0);
                why = english(f.crane.queueCover(true, null));
                h.assertTrue(why.contains("over the crane's rated 125 t"), "an overweight hood: " + why);
            } finally {
                GameplayConfig.TURBINE_HOOD_MASS_T.set(mass);
            }
            // a second LP outside the rails is not under the crane
            var far = PowerModuleRuntimeCheck.place(f.l, BwrBlocks.LP_TURBINE.get(), f.root.offset(-22, 0, 0), Direction.EAST);
            why = english(f.crane.queueCover(true, far.getBlockPos()));
            h.assertTrue(why.contains("not under the crane"), "not under the crane: " + why);
            why = english(f.crane.queueBlades(true, null));
            h.assertTrue(why.contains("COVER OFF first"), "no blades out with the cover on: " + why);
            h.assertTrue(!f.crane.busy() && !f.lp.coverOff() && f.casingStand().count() == 0, "nothing moved");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void coverOffIsRefusedUnderRailsTooLow(GameTestHelper h) {
        run(h, at(9), f -> {
            f.crane(8);
            String why = english(f.crane.queueCover(true, null));
            h.assertTrue(why.contains("too low") && why.contains("Raise the rails"), "rails too low: " + why);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void theCraneLiftsTheHoodOffAndBackAndAnOpenCasingVentsItsSteam(GameTestHelper h) {
        run(h, at(10), f -> {
            f.crane(16);
            h.assertTrue(f.crane.queueCover(true, null) == null, "COVER OFF queued");
            boolean sawHood = false;
            int n = 0;
            while (f.crane.busy() && n++ < 40000) {
                f.crane.scheduledTick(f.l);
                if (f.crane.phase() == Phase.TRANSIT) {
                    sawHood |= TurbineLoadItem.isHood(f.crane.carriedTurbine()) && f.lp.coverOff() && f.crane.carriedSummary().contains("hood");
                }
            }
            h.assertTrue(sawHood, "the hood hung from the hook in transit, the LP open");
            h.assertTrue(f.crane.phase() == Phase.IDLE && f.crane.carriedTurbine().isEmpty(), "the crane finished: " + f.crane.phase() + " " + english(f.crane.haltMessage()));
            h.assertTrue(f.lp.coverOff() && TurbineLoadItem.isHood(f.casingStand().items().getStackInSlot(0)), "the hood is on the casing stand");
            BlockPos top = f.root.above(4);
            h.assertTrue(f.l.getBlockState(top).getValue(PowerModuleBlock.OPEN) && f.l.getBlockState(top).getShape(f.l, top).isEmpty(), "the hood's cells are open");
            // steam admitted to the open casing vents: no work, nothing to the condenser
            f.edit(f.lp, t -> {
                var steam = new CompoundTag();
                steam.putDouble("Mass", 50);
                steam.putDouble("Enthalpy", 2600);
                steam.putDouble("Pressure", 145);
                t.put("Inlet", steam);
            });
            f.tick(1);
            h.assertTrue(f.lp.flowKgPerS > 0 && f.lp.shaftMW == 0 && f.lp.status.contains("vents"), "the steam vents: " + f.lp.status);
            h.assertTrue(f.crane.queueCover(false, null) == null, "COVER ON queued");
            f.runCrane(40000);
            h.assertTrue(!f.lp.coverOff() && f.casingStand().count() == 0 && !f.l.getBlockState(top).getValue(PowerModuleBlock.OPEN), "the hood is back on");
            h.assertTrue(f.l.getEntitiesOfClass(ItemEntity.class, new AABB(f.root).inflate(30)).isEmpty(), "nothing dropped");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void bladeSectionsAreLiftedOutAndInByTheCraneAndANewSectionResetsTheDamage(GameTestHelper h) {
        run(h, at(11), f -> {
            f.crane(16);
            f.blades(0.8, 0.3);
            f.bladeStand().items().insertItem(0, new ItemStack(BwrItems.LP_BLADE_SECTION.get()), false);
            h.assertTrue(f.crane.queueCover(true, null) == null, "COVER OFF queued");
            f.runCrane(40000);
            double mass = GameplayConfig.LP_BLADE_SECTION_MASS_T.get();
            try {
                GameplayConfig.LP_BLADE_SECTION_MASS_T.set(130.0);
                String why = english(f.crane.queueBlades(true, null));
                h.assertTrue(why.contains("over the crane's rated"), "an overweight section: " + why);
            } finally {
                GameplayConfig.LP_BLADE_SECTION_MASS_T.set(mass);
            }
            f.rpm(5);
            h.assertTrue(english(f.crane.queueBlades(true, null)).contains("turning"), "not with the shaft turning");
            f.rpm(0);
            h.assertTrue(f.crane.queueBlades(true, null) == null, "BLADES OUT queued");
            f.runCrane(40000);
            h.assertTrue(!f.lp.bladeIn(0) && f.lp.bladeIn(1), "the worse section (A) is out");
            ItemStack out = f.bladeStand().items().getStackInSlot(1);
            h.assertTrue(out.is(BwrItems.DAMAGED_LP_BLADE_SECTION.get()) && Math.abs(TurbineLoadItem.condition(out) - 0.8) < 1e-9,
                    "it came out as a damaged section with its wear: " + out);
            h.assertTrue(english(f.crane.queueCover(false, null)).contains("BLADES IN"), "no cover on with a section out");
            h.assertTrue(f.crane.queueBlades(false, null) == null, "BLADES IN queued");
            f.runCrane(40000);
            h.assertTrue(f.lp.bladeIn(0) && f.lp.blade(0) == 0 && Math.abs(f.lp.blade(1) - 0.3) < 1e-12, "the spare is in: section A as new, B untouched");
            h.assertTrue(f.bladeStand().items().getStackInSlot(0).isEmpty() && f.bladeStand().items().getStackInSlot(1).is(BwrItems.DAMAGED_LP_BLADE_SECTION.get()),
                    "the stand holds the damaged one");
            String why = english(f.crane.queueBlades(false, null));
            h.assertTrue(!why.isEmpty(), "no flow is empty now: " + why);
            h.assertTrue(f.crane.queueCover(false, null) == null, "COVER ON queued");
            f.runCrane(40000);
            h.assertTrue(!f.lp.coverOff() && Math.abs(f.lp.damage() - 0.3) < 1e-12, "closed again, its condition the worse remaining section");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aHoodHangingFromTheHookSavesAndLoadsOnceAndABrokenConsolePutsItBack(GameTestHelper h) {
        run(h, at(12), f -> {
            f.crane(16);
            var regs = f.l.registryAccess();
            h.assertTrue(f.crane.queueCover(true, null) == null, "COVER OFF queued");
            f.runCraneUntil(Phase.TRANSIT, 40000);
            for (int i = 0; i < 100; i++) f.crane.scheduledTick(f.l);
            h.assertTrue(f.crane.phase() == Phase.TRANSIT && TurbineLoadItem.isHood(f.crane.carriedTurbine()), "in transit with the hood");
            double progress = f.crane.progress();
            CompoundTag tag = f.crane.saveWithoutMetadata(regs);
            h.assertTrue(tag.contains("CarriedTurbine"), "the hood is in the crane's save");
            f.l.removeBlockEntity(f.console);
            var again = new BuildingCraneBlockEntity(f.console, f.l.getBlockState(f.console));
            again.setLevel(f.l);
            again.loadWithComponents(tag, regs);
            f.l.setBlockEntity(again);
            f.crane = again;
            h.assertTrue(TurbineLoadItem.isHood(again.carriedTurbine()) && Math.abs(again.progress() - progress) < 1e-9 && f.lp.coverOff()
                    && f.casingStand().count() == 0, "it loads on the hook, where it was, once");
            f.runCrane(40000);
            h.assertTrue(f.casingStand().count() == 1 && again.carriedTurbine().isEmpty(), "and finishes on the stand");
            h.assertTrue(f.l.getEntitiesOfClass(ItemEntity.class, new AABB(f.root).inflate(30)).isEmpty(), "no hood dropped or duplicated");
            // lift it again and break the console in transit: the hood goes back on its turbine
            h.assertTrue(again.queueCover(false, null) == null, "COVER ON queued");
            f.runCraneUntil(Phase.TRANSIT, 40000);
            h.assertTrue(TurbineLoadItem.isHood(again.carriedTurbine()), "the hood hangs");
            f.l.destroyBlock(f.console, false);
            h.assertTrue(!f.lp.coverOff() || f.casingStand().count() == 1 || !f.l.getEntitiesOfClass(ItemEntity.class, new AABB(f.root).inflate(30),
                    e -> TurbineLoadItem.isHood(e.getItem())).isEmpty(), "the hood is back where it came from, or dropped: never lost");
            f.crane = null;
        });
    }

    // ------------------------------------------------------------------------------------------------ the stands

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theBladeStandIsAnItemHandlerAndDropsWhatItHolds(GameTestHelper h) {
        run(h, at(13), f -> {
            BlockPos stand = f.root.offset(0, 0, -9);
            f.l.setBlock(stand, BwrBlocks.LP_BLADE_STAND.get().defaultBlockState(), 3);
            var items = f.l.getCapability(Capabilities.ItemHandler.BLOCK, stand, Direction.UP);
            h.assertTrue(items != null && items.getSlots() == 2, "a two-saddle item handler");
            h.assertTrue(items.insertItem(0, new ItemStack(Items.IRON_INGOT), false).getCount() == 1, "it takes no iron");
            h.assertTrue(items.insertItem(0, new ItemStack(BwrItems.LP_BLADE_SECTION.get(), 2), false).getCount() == 1, "one section per saddle");
            h.assertTrue(items.insertItem(1, TurbineLoadItem.section(0.7), false).isEmpty(), "a damaged one too");
            h.assertTrue(items.insertItem(1, new ItemStack(BwrItems.LP_BLADE_SECTION.get()), false).getCount() == 1, "full");
            var be = (TurbineStandBlockEntity) f.l.getBlockEntity(stand);
            h.assertTrue(be.count() == 2 && be.getUpdateTag(f.l.registryAccess()).contains("Items"), "it holds two, synced for drawing");
            h.assertTrue(items.extractItem(1, 1, false).is(BwrItems.DAMAGED_LP_BLADE_SECTION.get()), "a hopper takes the damaged one off");
            items.insertItem(1, TurbineLoadItem.section(0.02), false);
            BlockPos casing = f.root.offset(8, 0, -9);
            f.l.setBlock(casing, BwrBlocks.CASING_LAYDOWN_STAND.get().defaultBlockState(), 3);
            h.assertTrue(f.l.getCapability(Capabilities.ItemHandler.BLOCK, casing, Direction.UP) == null, "the casing cradle is not an item handler");
            f.l.destroyBlock(stand, true);
            var drops = f.l.getEntitiesOfClass(ItemEntity.class, new AABB(stand).inflate(3), e -> TurbineLoadItem.isBladeSection(e.getItem()));
            h.assertTrue(drops.size() == 2, "breaking it drops both sections: " + drops.size());
            h.assertTrue(TurbineLoadItem.isGoodSection(TurbineLoadItem.section(0.02)) && !TurbineLoadItem.isGoodSection(TurbineLoadItem.section(0.05)),
                    "a section under 5 % wear is a spare, over it a damaged one");
        });
    }
}
