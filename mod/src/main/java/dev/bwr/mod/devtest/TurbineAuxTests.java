package dev.bwr.mod.devtest;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Consumer;

/**
 * The turbine auxiliaries (TURBINE-AUX.md): lube oil and the shaft-driven pump, bearings, the rotor bow and the turning gear, the
 * generator cooling skid, the readouts, and the rule for trains that were there before any of it. Each test builds its own train at
 * its own far-away origin and steps the real {@link PowerTrain} (or {@link TurbineAuxTrain} with a given load) a tick at a time.
 * No CC types here: the computer half is {@link TurbineAuxCcChecks}.
 */
@GameTestHolder("bwraux")
@PrefixGameTestTemplate(false)
public final class TurbineAuxTests {
    private TurbineAuxTests() {}

    /** A generator facing east at {@code root}, an optional HP section behind it, and skids set against them. */
    static final class Fx {
        final ServerLevel l;
        final BlockPos root;
        final PowerModuleBlockEntity gen;
        PowerModuleBlockEntity hp;
        long t;
        Fx(ServerLevel l, BlockPos root) {
            this.l = l;
            this.root = root;
            t = l.getGameTime();
            clear();
            gen = PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), root, Direction.EAST);
            // A full output buffer: the generator makes no electricity out of the rotor, so the speed a test sets is the speed it keeps.
            edit(gen, t -> t.putDouble("Energy", PowerModuleBlockEntity.ENERGY_CAPACITY));
        }
        static AABB bounds(BlockPos root) { return new AABB(root.getX() - 16, root.getY() - 2, root.getZ() - 16, root.getX() + 12, root.getY() + 9, root.getZ() + 16); }
        void clear() {
            for (var p : BlockPos.betweenClosed(root.offset(-16, -1, -14), root.offset(11, 8, 14))) l.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        PowerModuleBlockEntity addHp() {
            hp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), root.offset(-9, 0, 0), Direction.EAST);
            return hp;
        }
        TurbineAuxBlockEntity skid(Block block, BlockPos pos, Direction facing) {
            l.setBlock(pos, block.defaultBlockState().setValue(TurbineAuxBlock.FACING, facing), 3);
            TurbineAuxBlock.attach(l, pos);
            return (TurbineAuxBlockEntity) l.getBlockEntity(pos);
        }
        TurbineAuxBlockEntity tank(double litres) {
            var tank = skid(BwrBlocks.MAIN_OIL_TANK.get(), root.relative(Direction.SOUTH, 3), Direction.NORTH);
            tank.addOil(litres);
            return tank;
        }
        BlockPos shaftPumpAt() {
            var b = gen.block();
            return b.shaft(root, gen.getBlockState(), true).relative(b.shaftFace(gen.getBlockState(), true));
        }
        TurbineAuxBlockEntity shaftPump() {
            return skid(BwrBlocks.SHAFT_OIL_PUMP.get(), shaftPumpAt(), gen.block().shaftFace(gen.getBlockState(), true));
        }
        PowerModuleBlockEntity placeBy(BlockPos at, net.minecraft.world.entity.player.Player player, ItemStack stack) {
            var b = BwrBlocks.HP_TURBINE.get();
            var s = b.placementState().setValue(dev.bwr.mod.eccs.PumpAssemblyBlock.FACING, Direction.EAST);
            l.setBlock(at, s, 3);
            b.setPlacedBy(l, at, s, player, stack);
            return (PowerModuleBlockEntity) l.getBlockEntity(at);
        }
        TurbineAuxBlockEntity cooling() { return skid(BwrBlocks.GENERATOR_COOLING_SKID.get(), root.relative(Direction.NORTH, 3), Direction.NORTH); }
        /** Steps the train a tick at a time. */
        void tick(PowerModuleBlockEntity m, int ticks) {
            for (int i = 0; i < ticks; i++) {
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(++t);
                PowerTrain.tick(m);
            }
        }
        void tick(PowerModuleBlockEntity m, int ticks, Runnable supply) {
            for (int i = 0; i < ticks; i++) {
                supply.run();
                tick(m, 1);
            }
        }
        /** Steps only the auxiliaries, at the given electrical load, topping up what the test keeps supplied. */
        void tickAux(PowerModuleBlockEntity m, int ticks, double electricMW, Runnable supply) {
            for (int i = 0; i < ticks; i++) {
                ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(++t);
                supply.run();
                m.electricMW = electricMW;
                TurbineAuxTrain.step(List.of(m), List.of(m), t);
            }
        }
        void edit(BlockEntity be, Consumer<CompoundTag> change) {
            var tag = be.saveWithoutMetadata(l.registryAccess());
            change.accept(tag);
            be.loadWithComponents(tag, l.registryAccess());
        }
        void finish(long time) {
            clear();
            ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
        }
    }

    @FunctionalInterface
    private interface Body { void run(Fx f) throws Exception; }

    private static void run(GameTestHelper h, BlockPos root, Body body) {
        var l = h.getLevel();
        EntityLookupProbe.runWhenLive(h, Fx.bounds(root), () -> {
            // The clock is restored to what it is now, when the body starts: the wait for the chunks has gone by on the real clock,
            // and putting back the time from before it would turn the level clock back by that many ticks for every other GameTest
            // of the batch (their delays and waits run on it, and items they dropped keep falling on the real ticks).
            long time = l.getGameTime();
            Fx f = null;
            try {
                f = new Fx(l, root);
                body.run(f);
                h.succeed();
            } finally {
                if (f != null) f.finish(time);
                else ((net.minecraft.world.level.storage.ServerLevelData) l.getLevelData()).setGameTime(time);
            }
        });
    }

    private static final BlockPos A = new BlockPos(78000, 245, 78000), B = new BlockPos(78100, 245, 78000), C = new BlockPos(78200, 245, 78000),
            D = new BlockPos(78300, 245, 78000), E = new BlockPos(78400, 245, 78000), F = new BlockPos(78500, 245, 78000),
            G = new BlockPos(78600, 245, 78000), H = new BlockPos(78700, 245, 78000), I = new BlockPos(78800, 245, 78000),
            J = new BlockPos(78900, 245, 78000), K = new BlockPos(79000, 245, 78000), L = new BlockPos(79100, 245, 78000);
    // Chunks the fixtures force are distinct per root: 100 blocks apart.

    // ------------------------------------------------------------------------------------------------------ lube oil

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theShaftPumpMakesOilOnlyWithSpeed(GameTestHelper h) {
        run(h, A, f -> {
            var tank = f.tank(10_000);
            var pump = f.shaftPump();
            h.assertTrue(tank.module() == f.gen && pump.module() == f.gen, "the tank and the shaft pump attach to the generator");
            double[] psig = new double[4];
            double[] rpms = {0, 450, 900, 1350};
            for (int i = 0; i < 4; i++) {
                f.gen.rpm = rpms[i];
                f.tick(f.gen, 3);
                psig[i] = f.gen.aux().oilPsig;
            }
            h.assertTrue(psig[0] == 0, "no oil at standstill: " + psig[0]);
            h.assertTrue(psig[1] > 0 && psig[1] < psig[2] && psig[2] < psig[3], "more speed, more oil: " + java.util.Arrays.toString(psig));
            h.assertTrue(psig[3] >= TurbineAux.OIL_PSIG_NOMINAL - 0.5, "full pressure near rated speed: " + psig[3]);
            // Take the pump away and the same speed makes no oil.
            f.l.removeBlock(pump.getBlockPos(), false);
            f.gen.rpm = 1350;
            f.tick(f.gen, 3);
            h.assertTrue(f.gen.aux().oilPsig == 0, "without the shaft pump a spinning shaft makes no oil: " + f.gen.aux().oilPsig);
            h.assertTrue(!f.gen.hasAux(pump.getBlockPos()), "the module forgot the removed pump");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void startupNeedsTheAcAuxiliaryPumpUntilNearSpeed(GameTestHelper h) {
        run(h, B, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.rpm = 450;
            f.tick(f.gen, 3);
            double shaftOnly = f.gen.aux().oilFilm;
            h.assertTrue(shaftOnly < 0.5, "at 30 % speed the shaft pump alone leaves a poor film: " + shaftOnly);
            // Switched on but with no power: nothing.
            tank.setAcPump(true);
            f.tick(f.gen, 3);
            h.assertTrue(!tank.acRunning() && f.gen.aux().oilFilm < 0.5, "an AC pump with no FE runs nothing");
            tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false);
            int before = tank.acFe();
            f.tick(f.gen, 3);
            h.assertTrue(tank.acRunning() && f.gen.aux().oilFilm >= 0.999, "with the AC pump fed the film holds: " + f.gen.aux().oilFilm);
            h.assertTrue(before - tank.acFe() == 3 * TurbineAuxBlockEntity.AC_PUMP_FE_PER_TICK, "it draws " + TurbineAuxBlockEntity.AC_PUMP_FE_PER_TICK + " FE a tick");
            // Near rated speed the shaft pump carries the shaft by itself.
            tank.setAcPump(false);
            f.gen.rpm = 1380;
            f.tick(f.gen, 3);
            h.assertTrue(f.gen.aux().oilFilm >= 0.999, "near rated speed the film holds on the shaft pump alone: " + f.gen.aux().oilFilm);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void noOilPressureWhileRollingDamagesTheBearings(GameTestHelper h) {
        run(h, C, f -> {
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tick(f.gen, 2400);
            h.assertTrue(f.gen.aux().lubeGoverned && f.gen.aux().oilPressureAlarm, "a new train with no oil system is governed and alarms");
            h.assertTrue(f.gen.bearingC > TurbineAux.BEARING_DAMAGE_C, "the bearing metal passed the damage temperature: " + f.gen.bearingC);
            h.assertTrue(f.gen.damage() > 0.1, "the shaft took bearing damage in the one damage counter: " + f.gen.damage());
            h.assertTrue(f.gen.aux().bearingAlarm, "and the bearing alarm reads");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void withOilTheSameTwoMinutesAtSpeedDoNothingToTheBearings(GameTestHelper h) {
        run(h, D, f -> {
            f.tank(10_000);
            f.shaftPump();
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tick(f.gen, 2400);
            var a = f.gen.aux();
            h.assertTrue(f.gen.damage() == 0 && f.gen.bearingC < TurbineAuxTrain.ALARM_BEARING_C && !a.bearingAlarm && !a.oilPressureAlarm && !a.oilLevelAlarm && !a.oilTempAlarm,
                    "with oil the bearing is fine: " + f.gen.bearingC + " / " + f.gen.damage());
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theDcPumpKeepsOilOnLossOfAcPower(GameTestHelper h) {
        run(h, E, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.rpm = 300;
            tank.setAcPump(true);
            tank.setDcPump(true);
            tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false);
            tank.dcEnergy.receiveEnergy(TurbineAuxBlockEntity.DC_CAPACITY_FE, false);
            f.tick(f.gen, 3);
            h.assertTrue(f.gen.aux().acRunning && f.gen.aux().dcRunning, "both pumps run");
            // Loss of AC power: the bus empties.
            f.edit(tank, t -> t.putInt("AcFe", 0));
            f.tick(f.gen, 3);
            h.assertTrue(!tank.acRunning() && tank.dcRunning(), "the AC pump stops, the DC pump runs");
            h.assertTrue(f.gen.aux().oilFilm >= 0.9 && f.gen.aux().oilPsig >= 10, "the oil film holds on the DC pump: " + f.gen.aux().oilFilm + " at " + f.gen.aux().oilPsig + " psig");
            int dc = tank.dcFe();
            f.tick(f.gen, 4);
            h.assertTrue(dc - tank.dcFe() == 4 * TurbineAuxBlockEntity.DC_PUMP_FE_PER_TICK, "it drew from the DC bus");
            // Nothing starts it for you: the same loss with the DC switch left off.
            tank.setDcPump(false);
            f.tick(f.gen, 3);
            h.assertTrue(f.gen.aux().oilFilm < 0.3, "with the DC pump not switched on the film is gone: " + f.gen.aux().oilFilm);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theOilCoolerNeedsCoolingWaterAndOilLeaksAndIsRefilledWithDrums(GameTestHelper h) {
        run(h, F, f -> {
            var tank = f.tank(10_000);
            f.shaftPump();
            f.gen.rpm = 1500;
            double cooledC;
            for (int i = 0; i < 2000; i++) {
                tank.coldInlet().fill(ThermalWater.atTemperature(5, 13), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                // A pipe carries the warmed water away; the last ticks leave some in the outlet to read.
                if (i < 1900) tank.hotOutlet().drain(5000, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                f.tick(f.gen, 1);
            }
            cooledC = tank.oilC();
            h.assertTrue(tank.coolerKw() > 100 && tank.hotKg() > 0, "the cooler takes heat into the water: " + tank.coolerKw() + " kW, " + tank.hotKg() + " kg warmed");
            h.assertTrue(tank.hotC() > tank.coldC(), "the water leaves warmer than it came");
            // The cooling water is cut off: the tank's own stock is emptied and none is supplied.
            f.edit(tank, t -> { t.putDouble("OilC", 25); t.remove("ColdKg"); t.remove("ColdH"); t.remove("HotKg"); t.remove("HotH"); });
            f.tick(f.gen, 2000);
            h.assertTrue(tank.oilC() > cooledC + 3, "with no cooling water the oil runs hotter: " + tank.oilC() + " against " + cooledC);
            // The oil leaks while pumped.
            double litres = tank.oilLitres();
            h.assertTrue(litres < 10_000, "the pumped tank leaks: " + litres);
            // Drums refill it, a whole drum at a time.
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var stack = new ItemStack(BwrItems.TURBINE_OIL_DRUM.get(), 2);
            var hit = new BlockHitResult(Vec3.atCenterOf(tank.getBlockPos()), Direction.UP, tank.getBlockPos(), false);
            tank.getBlockState().useItemOn(stack, f.l, player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(Math.abs(tank.oilLitres() - (litres + 1000)) < 1 && stack.getCount() == 1, "a drum adds 1,000 L: " + tank.oilLitres());
            f.edit(tank, t -> t.putDouble("OilL", TurbineAux.OIL_TANK_L - 500));
            tank.getBlockState().useItemOn(stack, f.l, player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(tank.oilLitres() == TurbineAux.OIL_TANK_L - 500 && stack.getCount() == 1, "a drum that does not fit is not spilled");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void coolingWaterTravelsThroughPipesIntoTheFlangesAndOutAgain(GameTestHelper h) {
        run(h, new BlockPos(79700, 245, 78000), f -> {
            var a = f.tank(10_000);
            f.shaftPump();
            f.gen.rpm = 1500;
            var at = a.getBlockPos();
            var pipes = BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get();
            // Supply pipe on the cold (left, west) flange; a pipe run from the hot (right, east) flange into a second tank's cold flange.
            f.l.setBlock(at.west(), pipes.defaultBlockState(), 3);
            for (int d = 1; d <= 5; d++) f.l.setBlock(at.east(d), pipes.defaultBlockState(), 3);
            // The second tank stands clear of the generator, so only water reaches it.
            var b = f.skid(BwrBlocks.MAIN_OIL_TANK.get(), at.east(6), Direction.NORTH);
            for (var p : new BlockPos[] {at.west(), at.east(1), at.east(2), at.east(3), at.east(4), at.east(5)}) f.l.setBlock(p, pipes.stateWithConnections(f.l, p), 3);
            var supply = dev.bwr.mod.water.WaterLineNetwork.inlet(f.l, at.west());
            int in = supply.fill(ThermalWater.atTemperature(40, 13), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            h.assertTrue(in == 40 && a.coldKg() == 40, "a pipe delivers cooling water to the cold flange: " + in + " / " + a.coldKg());
            f.tick(f.gen, 30);
            h.assertTrue(a.coldKg() < 40 && b.coldKg() > 0, "the warmed water leaves the hot flange through the pipes into the next flange: " + a.coldKg() + " / " + b.coldKg());
            h.assertTrue(b.coldC() > 13.5, "and it is warmer than it came: " + b.coldC());
            // The wrong flanges take nothing: the hot flange does not fill, the sides have no port.
            var east = a.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, at, Direction.EAST);
            var north = a.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, at, Direction.NORTH);
            h.assertTrue(east != null && east.fill(ThermalWater.atTemperature(10, 13), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE) == 0 && north == null,
                    "the hot flange takes no water and the sides have no port");
        });
    }

    // ------------------------------------------------------------------------------- turning gear, rotor bow

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aHotShaftWithoutTheTurningGearBowsAndTheGearRecoversIt(GameTestHelper h) {
        run(h, G, f -> {
            var hp = f.addHp();
            hp.markAuxRequired();
            var tank = f.tank(10_000);
            f.gen.rpm = 0;
            hp.rpm = 0;
            f.edit(hp, t -> t.putDouble("RotorC", 285));
            f.tick(f.gen, 6000);
            h.assertTrue(hp.bow() > 0.2, "five minutes hot at standstill with no gear: bow " + hp.bow());
            // The gear is the player's switch, and the cold rotor stays straight.
            double bowed = hp.bow();
            h.assertTrue(f.gen.setTurningGear(true), "engaged at standstill");
            f.edit(hp, t -> { t.putDouble("Bow", 0.8); t.putDouble("RotorC", 285); });
            tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false);
            int before = tank.acFe();
            f.tick(f.gen, 1);
            h.assertTrue(before - tank.acFe() == TurbineAuxTrain.GEAR_FE_PER_TICK, "the gear draws " + TurbineAuxTrain.GEAR_FE_PER_TICK + " FE a tick from the tank's AC bus: " + (before - tank.acFe()));
            f.tick(f.gen, 6000, () -> tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false));
            h.assertTrue(hp.bow() < 0.8 - 0.1, "five minutes on the gear straightens it: " + hp.bow());
            h.assertTrue(f.gen.rpm > 2.9 && f.gen.rpm < 3.1 && f.gen.aux().gearRolling, "the gear rolls the shaft at 3 rpm: " + f.gen.rpm);
            h.assertTrue(bowed > 0.2, "(control) the bow without it was " + bowed);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aBowedRotorShakesAndTheRollUpDamagesIt(GameTestHelper h) {
        run(h, H, f -> {
            var hp = f.addHp();
            hp.markAuxRequired();
            var tank = f.tank(10_000);
            f.shaftPump();
            tank.setAcPump(true);
            f.edit(hp, t -> t.putDouble("Bow", 1.0));
            double worst = 0;
            for (int rpm = 100; rpm <= 1400; rpm += 1) {
                tank.acEnergy.receiveEnergy(10_000, false);
                f.gen.rpm = rpm;
                hp.rpm = rpm;
                f.tick(f.gen, 1);
                worst = Math.max(worst, hp.vibration);
                // The tick's coupling and loss move the speed a hair; the roll-up here is set speed by speed.
            }
            h.assertTrue(worst > TurbineAuxTrain.ALARM_VIBRATION_MM_S, "a fully bowed rotor shakes through the critical band: " + worst + " mm/s");
            h.assertTrue(hp.damage() > 0.03, "the roll-up damaged it: " + hp.damage());
            // A straight rotor takes none.
            f.edit(hp, t -> { t.putDouble("Bow", 0.0); t.putDouble("ShaftDamage", 0.0); });
            for (int rpm = 100; rpm <= 1400; rpm += 1) {
                tank.acEnergy.receiveEnergy(10_000, false);
                f.gen.rpm = rpm;
                hp.rpm = rpm;
                f.tick(f.gen, 1);
            }
            h.assertTrue(hp.damage() == 0, "a straight rotor takes no damage rolling up: " + hp.damage());
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theTurningGearSwitchEngagesOnlyAtLowSpeedAndTheSignalSwitchWorks(GameTestHelper h) {
        run(h, I, f -> {
            var tank = f.tank(1000);
            f.gen.rpm = 100;
            h.assertTrue(!f.gen.setTurningGear(true) && !f.gen.turningGear(), "refused at 100 rpm");
            f.gen.rpm = 5;
            h.assertTrue(f.gen.setTurningGear(true) && f.gen.turningGear(), "accepted at 5 rpm");
            f.gen.rpm = 5;
            f.tick(f.gen, 2);
            h.assertTrue(!f.gen.aux().gearRolling && f.gen.rpm > 4.5, "with nothing on the tank's AC bus the gear does not turn the shaft: " + f.gen.rpm);
            tank.acEnergy.receiveEnergy(100_000, false);
            f.tick(f.gen, 2);
            h.assertTrue(f.gen.aux().gearRolling && Math.abs(f.gen.rpm - 3) < 0.01, "fed, the gear rolls a slow shaft at 3 rpm: " + f.gen.rpm);
            // Steam spins it past the overrun speed: the gear does nothing and draws nothing.
            f.gen.rpm = 400;
            int fe = tank.acFe();
            f.tick(f.gen, 2);
            h.assertTrue(!f.gen.aux().gearRolling && tank.acFe() == fe && f.gen.rpm > 390, "above the overrun speed it neither turns nor draws: " + f.gen.rpm);
            h.assertTrue(f.gen.turningGear(), "(the switch stays where the player left it)");
            h.assertTrue(f.gen.setTurningGear(false) && !f.gen.turningGear(), "off always works");
            // Panel / wrench signal: the same switch, the same refusal.
            var signals = SignalProviders.of(f.gen);
            h.assertTrue(signals != null && signals.signals().stream().anyMatch(s -> s.key().equals("turning_gear")), "turning_gear is a signal");
            f.gen.rpm = 100;
            h.assertTrue(!signals.command("turning_gear", 1) && !f.gen.turningGear(), "the signal command is refused at 100 rpm");
            f.gen.rpm = 2;
            h.assertTrue(signals.command("turning_gear", 1) && f.gen.turningGear(), "and engages the gear at 2 rpm");
            h.assertTrue(signals.read("turning_gear").orElse(-1) == 1, "reads back as 1");
            h.assertTrue(signals.command("turning_gear", 0) && !f.gen.turningGear(), "and releases it");
            // Nothing engages it by itself: a coasting generator below the speed with the switch off stays off.
            f.gen.rpm = 2;
            f.tick(f.gen, 40);
            h.assertTrue(!f.gen.turningGear() && !f.gen.aux().gearRolling, "no automatic engagement");
        });
    }

    // ------------------------------------------------------------------------------------ generator cooling

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void anUnpoweredGeneratorCoolingSkidLetsTheWindingRiseWithLoad(GameTestHelper h) {
        run(h, J, f -> {
            var skid = f.cooling();
            h.assertTrue(skid.module() == f.gen, "the cooling skid attaches to the generator");
            skid.addHydrogen(20);
            f.gen.rpm = 1500;
            Runnable water = () -> {
                skid.coldInlet().fill(ThermalWater.atTemperature(30, 13), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
                skid.hotOutlet().drain(5000, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            };
            f.edit(f.gen, t -> t.putDouble("WindingC", 102));
            f.edit(skid, t -> t.putDouble("CoolantC", 46));
            skid.setStatorPumps(true);
            // Powered: the supply is topped up every tick.
            f.tickAux(f.gen, 9000, 1500, () -> { water.run(); skid.acEnergy.receiveEnergy(100_000, false); });
            double powered = f.gen.windingC;
            h.assertTrue(powered < TurbineAuxTrain.ALARM_WINDING_C && f.gen.damage() == 0, "full cooling holds the winding at rated load: " + powered);
            // Unpowered: the same load and water, the pumps switched on but with no FE.
            f.edit(skid, t -> { t.putInt("AcFe", 0); t.putDouble("StatorFlow", 1); t.putDouble("CoolantC", 46); });
            f.edit(f.gen, t -> t.putDouble("WindingC", 102));
            f.tickAux(f.gen, 9000, 1500, water);
            h.assertTrue(f.gen.windingC > TurbineAux.WINDING_LIMIT_C, "with the stator pumps dead the winding passes its limit: " + f.gen.windingC);
            h.assertTrue(f.gen.damage() > 0 && f.gen.aux().windingAlarm, "insulation damage accrues and the alarm reads: " + f.gen.damage());
            h.assertTrue(f.gen.aux().statorFlow < 0.05, "the stator flow ran down: " + f.gen.aux().statorFlow);
            // The player's remedy: cut the load.
            f.edit(f.gen, t -> { t.putDouble("WindingC", 102); t.putDouble("ShaftDamage", 0); });
            f.edit(skid, t -> t.putDouble("CoolantC", 46));
            f.tickAux(f.gen, 9000, 900, water);
            h.assertTrue(f.gen.windingC < TurbineAux.WINDING_LIMIT_C && f.gen.damage() == 0, "at 60 % load even the dead stator pumps hold it: " + f.gen.windingC);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aNewGeneratorWithNoCoolingSkidOverheatsAtLoad(GameTestHelper h) {
        run(h, K, f -> {
            f.gen.markAuxRequired();
            f.gen.rpm = 1500;
            f.tickAux(f.gen, 6000, 1500, () -> { });
            h.assertTrue(f.gen.windingC > TurbineAux.WINDING_LIMIT_C && f.gen.damage() > 0, "no skid, no cooling: " + f.gen.windingC);
            h.assertTrue(f.gen.aux().genGoverned && f.gen.aux().h2Alarm && f.gen.aux().windingAlarm, "the alarms read");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void hydrogenLeaksAndCylindersRechargeIt(GameTestHelper h) {
        run(h, L, f -> {
            var skid = f.cooling();
            h.assertTrue(skid.h2Kg() == 0 && skid.h2Psig() == 0, "a new skid holds no hydrogen");
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var stack = new ItemStack(BwrItems.HYDROGEN_CYLINDER.get(), 5);
            var hit = new BlockHitResult(Vec3.atCenterOf(skid.getBlockPos()), Direction.UP, skid.getBlockPos(), false);
            for (int i = 0; i < 5; i++) skid.getBlockState().useItemOn(stack, f.l, player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(skid.h2Kg() == 20 && stack.getCount() == 1, "four cylinders fill 20 kg and the fifth is kept: " + skid.h2Kg());
            h.assertTrue(Math.abs(skid.h2Psig() - TurbineAux.H2_FULL_PSIG) < 1e-9, "a full charge is the design pressure");
            f.gen.rpm = 1500;
            f.tickAux(f.gen, 6000, 0, () -> { });
            h.assertTrue(skid.h2Kg() < 20 && skid.h2Kg() > 19, "the casing leaks slowly: " + skid.h2Kg());
        });
    }

    // ------------------------------------------------------------------------------- readouts, old saves, idle

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void theReadoutsAndSignalsReportTheAuxiliaries(GameTestHelper h) {
        run(h, new BlockPos(79200, 245, 78000), f -> {
            var hp = f.addHp();
            hp.markAuxRequired();
            f.gen.markAuxRequired();
            var tank = f.tank(10_000);
            f.shaftPump();
            var skid = f.cooling();
            skid.addHydrogen(20);
            f.gen.rpm = 900;
            hp.rpm = 900;
            tank.setAcPump(true);
            tank.acEnergy.receiveEnergy(TurbineAuxBlockEntity.AC_CAPACITY_FE, false);
            f.tick(hp, 3);
            var gs = SignalProviders.of(f.gen);
            var hs = SignalProviders.of(hp);
            String[] genKeys = {"aux_governed", "oil_pressure", "oil_temp", "oil_level", "oil_film", "bearing_temp", "vibration", "turning_gear", "turning_gear_rolling",
                    "winding_temp", "stator_flow", "h2_pressure", "coolant_temp", "oil_pressure_alarm", "oil_level_alarm", "oil_temp_alarm",
                    "bearing_alarm", "vibration_alarm", "winding_alarm", "h2_alarm"};
            for (String k : genKeys) h.assertTrue(gs.read(k).isPresent(), "the generator reads " + k);
            h.assertTrue(gs.read("oil_pressure").getAsDouble() > 15 && gs.read("oil_level").getAsDouble() > 0.4, "oil pressure and level read");
            h.assertTrue(Math.abs(gs.read("h2_pressure").getAsDouble() - 30) < 0.1, "hydrogen pressure reads");
            h.assertTrue(hs.read("rotor_temp").isPresent() && hs.read("rotor_bow").isPresent() && hs.read("winding_temp").isEmpty() && hs.read("turning_gear").isEmpty(),
                    "a turbine section has a rotor and no winding or gear");
            // Units the wrench's Measures setting sorts them by.
            var specs = gs.signals();
            java.util.function.Function<String, dev.bwr.mod.signal.Quantity> q = k -> dev.bwr.mod.signal.Quantity.of(specs.stream().filter(s -> s.key().equals(k)).findFirst().get());
            h.assertTrue(q.apply("oil_pressure") == dev.bwr.mod.signal.Quantity.PRESSURE && q.apply("bearing_temp") == dev.bwr.mod.signal.Quantity.TEMPERATURE
                    && q.apply("vibration") == dev.bwr.mod.signal.Quantity.VIBRATION && q.apply("oil_level") == dev.bwr.mod.signal.Quantity.LEVEL
                    && q.apply("h2_alarm") == dev.bwr.mod.signal.Quantity.BOOLEAN, "quantities: " + q.apply("oil_pressure") + " " + q.apply("vibration") + " " + q.apply("oil_level"));
            // The skids' own signals and switches.
            var ts = SignalProviders.of(tank);
            h.assertTrue(ts.read("ac_pump").getAsDouble() == 1 && ts.command("dc_pump", 1) && tank.dcPumpOn() && ts.command("dc_pump", 0) && !tank.dcPumpOn(), "the tank's pump switches work");
            var ss = SignalProviders.of(skid);
            h.assertTrue(ss.command("stator_pumps", 1) && skid.statorPumpsOn() && Math.abs(ss.read("h2_kg").getAsDouble() - 20) < 0.01, "the cooling skid's switch and hydrogen read");
            // An alarm reads as an alarm and trips nothing.
            f.gen.rpm = 1500;
            hp.rpm = 1500;
            tank.setAcPump(false);
            f.l.removeBlock(f.shaftPumpAt(), false);
            f.tick(f.gen, 5);
            h.assertTrue(gs.read("oil_pressure_alarm").getAsDouble() == 1 && f.gen.rpm > 1400, "low oil pressure alarms and the shaft is not tripped");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aComputerReadsTheAuxiliariesAndThrowsTheSwitches(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            h.succeed();
            return;
        }
        run(h, new BlockPos(79600, 245, 78000), f -> {
            f.gen.markAuxRequired();
            var tank = f.tank(10_000);
            f.shaftPump();
            var skid = f.cooling();
            f.gen.rpm = 100;
            f.tick(f.gen, 3);
            TurbineAuxCcChecks.verify(h, f, tank, skid);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void aTrainThatHadNoAuxiliariesRunsAsItAlwaysDid(GameTestHelper h) {
        run(h, new BlockPos(79300, 245, 78000), f -> {
            var hp = f.addHp();
            f.gen.rpm = 1500;
            hp.rpm = 1500;
            f.edit(hp, t -> t.putDouble("RotorC", 285));
            f.tick(f.gen, 4000);
            h.assertTrue(f.gen.damage() == 0 && hp.damage() == 0, "no damage from a train placed before the auxiliaries existed");
            h.assertTrue(!f.gen.aux().lubeGoverned && !f.gen.aux().bowGoverned && !f.gen.aux().genGoverned, "nothing governs it");
            h.assertTrue(f.gen.bearingC == TurbineAux.AMBIENT_C && f.gen.windingC == TurbineAux.AMBIENT_C && f.gen.vibration == 0, "its bearings, winding and vibration are untouched");
            var gs = SignalProviders.of(f.gen);
            h.assertTrue(gs.read("oil_pressure").isEmpty() && gs.read("winding_temp").isEmpty() && gs.read("vibration").isEmpty() && gs.read("aux_governed").getAsDouble() == 0,
                    "and its auxiliary readings are 'no signal'");
            // A saved legacy module carries none of the new keys.
            var tag = f.gen.saveWithoutMetadata(f.l.registryAccess());
            for (String k : new String[] {"AuxLinks", "AuxRequired", "TurningGear", "GearFe", "Bow", "BearingC", "WindingC", "RotorC"})
                h.assertTrue(!tag.contains(k), "a legacy generator saves no " + k);
            // Rotor heat is tracked, but a hot rotor of an ungoverned train never bows at rest.
            f.gen.rpm = 0;
            hp.rpm = 0;
            f.tick(f.gen, 4000);
            h.assertTrue(hp.bow() == 0 && hp.damage() == 0, "no bow on an ungoverned train");
            // The player builds ONE auxiliary: only that system governs.
            var tank = f.tank(10_000);
            f.gen.rpm = 1500;
            hp.rpm = 1500;
            f.tick(f.gen, 4);
            h.assertTrue(f.gen.aux().lubeGoverned && !f.gen.aux().genGoverned && !f.gen.aux().bowGoverned, "an oil tank brings in the oil system only");
            h.assertTrue(f.gen.aux().oilPsig <= 0, "and with no pump running it has no pressure");
            // A module placed by a player from a fresh item starts a train that needs them; a dismantled one carries its past.
            var player = h.makeMockPlayer(GameType.SURVIVAL);
            var block = BwrBlocks.HP_TURBINE.get();
            var none = f.placeBy(f.root.offset(0, 0, 10), null, new ItemStack(block));
            h.assertTrue(!none.auxRequired(), "(a module placed with no player is not new)");
            f.l.removeBlock(none.getBlockPos(), false);
            var fresh = f.placeBy(f.root.offset(0, 0, 10), player, new ItemStack(block));
            h.assertTrue(fresh.auxRequired(), "a module a player places from a fresh item needs the auxiliaries");
            var carried = new ItemStack(block);
            var data = new CompoundTag();
            var condition = new CompoundTag();
            condition.putDouble("ShaftDamage", 0.25);
            data.put("BwrPowerCondition", condition);
            carried.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
            var old = f.placeBy(f.root.offset(0, 0, -10), player, carried);
            h.assertTrue(!old.auxRequired() && old.damage() == 0.25, "a section picked up from an old plant stays an old section");
            var retained = fresh.retainedCondition();
            h.assertTrue(retained.getBoolean("AuxRequired"), "and a new section keeps its status through being picked up");
            f.l.removeBlock(fresh.getBlockPos(), false);
            f.l.removeBlock(old.getBlockPos(), false);
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void skidsSaveOnlyWhatIsNotDefaultAndKeepTheirLinksAcrossReloads(GameTestHelper h) {
        run(h, new BlockPos(79400, 245, 78000), f -> {
            var tank = f.tank(0);
            var empty = tank.saveWithoutMetadata(f.l.registryAccess());
            h.assertTrue(!empty.contains("OilL") && !empty.contains("AcOn") && !empty.contains("AcFe") && !empty.contains("ColdKg") && !empty.contains("OilC"),
                    "a new empty tank writes no inventory keys: " + empty);
            tank.addOil(7000);
            tank.setAcPump(true);
            tank.setDcPump(true);
            tank.acEnergy.receiveEnergy(5000, false);
            tank.coldInlet().fill(ThermalWater.atTemperature(100, 20), net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
            var tag = tank.saveWithFullMetadata(f.l.registryAccess());
            var state = tank.getBlockState();
            var copy = (TurbineAuxBlockEntity) BlockEntity.loadStatic(tank.getBlockPos(), state, tag, f.l.registryAccess());
            h.assertTrue(copy.oilLitres() == 7000 && copy.acPumpOn() && copy.dcPumpOn() && copy.acFe() == 5000 && copy.coldKg() == 100
                    && Math.abs(copy.coldC() - 20) < 0.5 && f.gen.getBlockPos().equals(copy.host()), "the tank round-trips: " + tag);
            // The module keeps its link to a skid that is in an unloaded chunk, and drops one that is gone.
            var far = new BlockPos(f.root.getX() + 5_000_000, f.root.getY(), f.root.getZ());
            var mt = f.gen.saveWithoutMetadata(f.l.registryAccess());
            var links = mt.getLongArray("AuxLinks");
            long[] more = java.util.Arrays.copyOf(links, links.length + 1);
            more[links.length] = far.asLong();
            mt.putLongArray("AuxLinks", more);
            f.gen.loadWithComponents(mt, f.l.registryAccess());
            h.assertTrue(f.gen.auxEntities().size() == 1 && f.gen.hasAux(far), "an unloaded skid is neither read nor forgotten (and no chunk was loaded)");
            h.assertTrue(!f.l.isLoaded(far), "the far chunk is still unloaded");
            f.l.removeBlock(tank.getBlockPos(), false);
            h.assertTrue(f.gen.auxEntities().isEmpty() && !f.gen.hasAux(tank.getBlockPos()), "a removed skid drops out");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwraux", timeoutTicks = 200)
    public static void nothingTicksWhileIdle(GameTestHelper h) {
        run(h, new BlockPos(79500, 245, 78000), f -> {
            for (var b : new Block[] {BwrBlocks.MAIN_OIL_TANK.get(), BwrBlocks.SHAFT_OIL_PUMP.get(), BwrBlocks.GENERATOR_COOLING_SKID.get()}) {
                var be = ((net.minecraft.world.level.block.EntityBlock) b).newBlockEntity(f.root.offset(0, 0, 10), b.defaultBlockState());
                h.assertTrue(((net.minecraft.world.level.block.EntityBlock) b).getTicker(f.l, b.defaultBlockState(), be.getType()) == null, "the " + b + " has no ticker");
            }
            // A skid with its pumps on but no train to step it draws and does nothing over 200 ticks.
            var lone = f.skid(BwrBlocks.MAIN_OIL_TANK.get(), f.root.offset(0, 0, 10), Direction.NORTH);
            lone.addOil(5000);
            lone.setAcPump(true);
            lone.setDcPump(true);
            lone.acEnergy.receiveEnergy(50_000, false);
            double oil = lone.oilLitres();
            int fe = lone.acFe();
            for (int i = 0; i < 200; i++) ((net.minecraft.world.level.storage.ServerLevelData) f.l.getLevelData()).setGameTime(++f.t);
            h.assertTrue(lone.module() == null && lone.acFe() == fe && lone.oilLitres() == oil, "an unattached skid does nothing");
            // A legacy train steps no skid and allocates no readout of its own: one shared empty readout.
            f.gen.rpm = 1500;
            f.tick(f.gen, 3);
            var first = f.gen.aux();
            f.tick(f.gen, 3);
            h.assertTrue(first == f.gen.aux(), "an ungoverned train reuses one readout");
        });
    }
}
