package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;
import java.util.Map;

/**
 * The computer face of the spent fuel pool through the same dynamic-peripheral entry point a computer calls. These
 * touch CC:Tweaked types, so they live here and not in a {@code @GameTestHolder} class:
 * {@link SpentFuelPoolTests#theComputerFaceReadsTheSameNumbers} checks that CC:Tweaked is loaded first.
 */
final class SpentFuelPoolCcChecks {

    private SpentFuelPoolCcChecks() {
    }

    private static final long T0 = 50_000_000L;

    static void run(GameTestHelper h, BlockPos at) throws Exception {
        SpentFuelPoolFixture f = SpentFuelPoolFixture.build(h.getLevel(), at, 9, 9, 8, 4);
        try {
            var face = f.level.getCapability(PeripheralCapability.get(), f.controller, Direction.EAST);
            h.assertTrue(face != null && face.getType().equals("bwr_spent_fuel_pool"), "no bwr_spent_fuel_pool face: " + face);
            h.assertTrue(Boolean.TRUE.equals(PeripheralTestCalls.call(face, "isFormed")[0]), "isFormed");

            f.level.getCapability(Capabilities.FluidHandler.BLOCK, f.makeup, Direction.NORTH).fill(
                    dev.bwr.mod.water.ThermalWater.atTemperature(300_000, 31.0), IFluidHandler.FluidAction.EXECUTE);
            f.fill(5, 45000.0, 7200, T0);
            f.be.simulateSeconds(10, T0);
            var v = f.be.view();
            Map<?, ?> status = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            h.assertTrue(near(status, "waterKg", v.massKg()) && near(status, "capacityKg", v.capacityKg()) && near(status, "levelM", v.levelM())
                            && near(status, "temperatureC", v.temperatureC()) && near(status, "decayHeatKW", v.decayW() / 1000.0)
                            && near(status, "waterAboveFuelM", v.waterAboveFuelM()), "status differs from the block entity: " + status);
            h.assertTrue(num(status, "assemblies") == 5 && num(status, "cells") == 16 && num(status, "racks") == 4, "counts: " + status);
            h.assertTrue(num(status, "secondsToBoil") > 0 && num(status, "secondsToUncover") > num(status, "secondsToBoil"), "times: " + status);
            h.assertTrue(Boolean.FALSE.equals(status.get("coolingEnabled")) && Boolean.FALSE.equals(status.get("coolingRunning")) && Boolean.FALSE.equals(status.get("boiling")),
                    "pump flags: " + status);

            List<?> racks = (List<?>) PeripheralTestCalls.call(face, "getRacks")[0];
            h.assertTrue(racks.size() == 4, "racks " + racks.size());
            double sum = 0.0;
            for (Object o : racks) {
                sum += num((Map<?, ?>) o, "decayHeatKW");
            }
            h.assertTrue(Math.abs(sum - v.decayW() / 1000.0) < 1e-6, "rack heat sums to the total: " + sum + " against " + v.decayW() / 1000.0);

            // a range of racks, for a pool of thousands: racks 2 and 3, and the assemblies in them
            List<?> page = (List<?>) PeripheralTestCalls.call(face, "getRacks", 2, 2)[0];
            h.assertTrue(page.size() == 2 && num((Map<?, ?>) page.get(0), "index") == 2 && num((Map<?, ?>) page.get(1), "index") == 3, "getRacks(2, 2): " + page);
            List<?> pageAssemblies = (List<?>) PeripheralTestCalls.call(face, "getAssemblies", 2, 2)[0];
            for (Object o : pageAssemblies) {
                double rack = num((Map<?, ?>) o, "rack");
                h.assertTrue(rack == 2 || rack == 3, "getAssemblies(2, 2) gave an assembly of rack " + rack);
            }

            List<?> assemblies = (List<?>) PeripheralTestCalls.call(face, "getAssemblies")[0];
            h.assertTrue(assemblies.size() == 5, "assemblies " + assemblies.size());
            Map<?, ?> first = (Map<?, ?>) assemblies.get(0);
            h.assertTrue(num(first, "burnupMwdPerTonne") == 45000.0 && Boolean.TRUE.equals(first.get("dischargeRecorded")), "assembly: " + first);
            // the stamps are relative to a virtual game time, far ahead of the level's own, so here the assemblies read as just discharged
            h.assertTrue("leu".equals(first.get("fuel")) && num(first, "decayHeatKW") > 0.0 && num(first, "coolingSeconds") >= 0.0
                            && num(first, "rack") == 1 && num(first, "cell") >= 1, "assembly fields: " + first);

            // the only thing a program can change is the pump, and only because it asked
            PeripheralTestCalls.call(face, "setCooling", true);
            h.assertTrue(f.be.coolingEnabled() && Boolean.TRUE.equals(PeripheralTestCalls.call(face, "getCooling")[0]), "setCooling(true)");
            PeripheralTestCalls.call(face, "setCooling", false);
            h.assertTrue(!f.be.coolingEnabled(), "setCooling(false)");

            // an unformed pool answers with the reason and nothing else
            f.level.setBlock(f.min.offset(8, 3, 4), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            f.be.scanNow();
            Map<?, ?> broken = (Map<?, ?>) PeripheralTestCalls.call(face, "getStatus")[0];
            h.assertTrue(Boolean.FALSE.equals(broken.get("formed")) && String.valueOf(broken.get("problem")).contains("Complete the floor") && !broken.containsKey("waterKg"),
                    "unformed status: " + broken);
        } finally {
            f.dispose();
        }
        // the block is a registered peripheral on its own
        h.assertTrue(BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get() != null, "block");
    }

    private static double num(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new AssertionError(key + " is not a number: " + value + " in " + map);
        }
        return n.doubleValue();
    }

    private static boolean near(Map<?, ?> map, String key, double expected) {
        return Math.abs(num(map, key) - expected) <= 1e-6 * Math.max(1.0, Math.abs(expected));
    }
}
