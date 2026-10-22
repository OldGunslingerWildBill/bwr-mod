package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.fuel.FuelFabricatorBlockEntity;
import dev.bwr.mod.fuel.FuelItemRoute;
import dev.bwr.mod.fuel.Reprocessing;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * The computer faces of the reprocessing plant and of the fabricator's MOX route, through the same dynamic-peripheral
 * entry point a computer calls. These touch CC:Tweaked types, so they live here and not in a {@code @GameTestHolder}
 * class: a run without CC:Tweaked never loads this class ({@link ReprocessingTests#computerFaces} checks first).
 */
final class ReprocessingCcChecks {

    private ReprocessingCcChecks() {
    }

    static void run(GameTestHelper h) throws Exception {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(1200, 210, 1400);
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.REPROCESSING_PLANT.get().defaultBlockState(), 2);
        var plant = (ReprocessingPlantBlockEntity) l.getBlockEntity(p);
        var peripheral = l.getCapability(PeripheralCapability.get(), p, Direction.UP);
        h.assertTrue(peripheral != null && peripheral.getType().equals("bwr_reprocessing_plant"), "no bwr_reprocessing_plant face: " + peripheral);

        Map<?, ?> idle = status(peripheral);
        h.assertTrue(Boolean.FALSE.equals(idle.get("working")) && Boolean.FALSE.equals(idle.get("hasAssembly")) && num(idle, "energyFE") == 0, "idle status: " + idle);

        plant.input().setStackInSlot(0, FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, 45000.0, 1.0)));
        for (int i = 0; i < 50; i++) {
            plant.energy().receiveEnergy(10_000, false);
            ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), plant);
        }
        Map<?, ?> busy = status(peripheral);
        h.assertTrue(Boolean.TRUE.equals(busy.get("working")) && Boolean.TRUE.equals(busy.get("hasAssembly")) && Boolean.TRUE.equals(busy.get("accepted")), "working status: " + busy);
        h.assertTrue("leu".equals(busy.get("assemblyFuel")) && num(busy, "assemblyBurnupMwdPerTonne") == 45000.0, "assembly fields: " + busy);
        h.assertTrue(Math.abs(num(busy, "progress") - 50.0 / Reprocessing.WORK_TICKS) < 1e-9, "progress: " + busy.get("progress"));
        for (int i = 0; i < 160; i++) {
            plant.energy().receiveEnergy(10_000, false);
            ReprocessingPlantBlockEntity.serverTick(l, p, l.getBlockState(p), plant);
        }
        Map<?, ?> done = status(peripheral);
        h.assertTrue(num(done, "wasteOut") == 1 && num(done, "depletedUraniumOut") == 3 && num(done, "plutoniumOut") == 0, "outputs: " + done);
        h.assertTrue(Math.abs(num(done, "plutoniumHeldNuggets") - 0.265255) < 1e-9, "plutonium held: " + done.get("plutoniumHeldNuggets"));
        h.assertTrue(Math.abs(num(done, "depletedUraniumHeldIngots") - 7_051_256 / 9_000_000.0) < 1e-9, "depleted uranium held: " + done.get("depletedUraniumHeldIngots"));
        h.assertTrue(Math.abs(num(done, "wasteHeld") - 0.683489) < 1e-9, "waste held: " + done.get("wasteHeld"));
        // a fresh assembly shows as not accepted
        plant.input().setStackInSlot(0, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
        h.assertTrue(Boolean.FALSE.equals(status(peripheral).get("accepted")), "a fresh assembly shows as accepted");
        plant.removalDrops();
        l.removeBlock(p, false);

        // the fabricator reports its MOX stores
        BlockPos fp = new BlockPos(1200, 210, 1410);
        l.getChunk(fp);
        l.removeBlock(fp, false);
        l.setBlock(fp, BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(), 2);
        var fab = (FuelFabricatorBlockEntity) l.getBlockEntity(fp);
        var fabFace = l.getCapability(PeripheralCapability.get(), fp, Direction.UP);
        h.assertTrue(fabFace != null && fabFace.getType().equals("bwr_fuel_fabricator"), "no bwr_fuel_fabricator face");
        fab.setTarget("mox_standard");
        fab.uraniumIn().insertItem(0, new ItemStack(BwrItems.PLUTONIUM_NUGGET.get(), 2), false);
        fab.energy().receiveEnergy(10_000, false);
        FuelFabricatorBlockEntity.serverTick(l, fp, l.getBlockState(fp), fab);
        Map<?, ?> fabStatus = status(fabFace);
        h.assertTrue("mox_standard".equals(fabStatus.get("target")) && num(fabStatus, "plutoniumHeldNuggets") == 2 && num(fabStatus, "matrixHeldIngots") == 0, "fabricator status: " + fabStatus);
        h.assertTrue(FuelItemRoute.isMoxTarget(String.valueOf(fabStatus.get("target"))), "target is not a MOX target");
        fab.removalDrops();
        l.removeBlock(fp, false);
    }

    private static Map<?, ?> status(Object peripheral) throws Exception {
        Object[] result = PeripheralTestCalls.call(peripheral, "getStatus");
        return (Map<?, ?>) result[0];
    }

    private static double num(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new AssertionError(key + " is not a number: " + value + " in " + map);
        }
        return n.doubleValue();
    }
}
