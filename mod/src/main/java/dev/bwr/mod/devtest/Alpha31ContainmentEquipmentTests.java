package dev.bwr.mod.devtest;

import dev.bwr.mod.peripheral.ReactorPeripheral;
import dev.bwr.mod.containment.ContainmentEquipmentBlockEntity;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31ContainmentEquipmentTests {
    private Alpha31ContainmentEquipmentTests() {}

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void missingEquipmentCannotPurgeOrVent(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("CC-ABSENT SKIP: missingEquipmentCannotPurgeOrVent");
            helper.succeed(); return;
        }
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(45412, 190, 45412);
        var reactor = CompactCoreRegressionTests.build(level, origin, 11, 11, 8, false);
        helper.assertTrue(reactor.isFormed(), "equipment reference reactor did not form");
        var peripheral = new ReactorPeripheral(reactor);
        helper.assertTrue(!peripheral.setContainmentInerting(true), "CC accepted purge without a nitrogen supply");
        helper.assertTrue(!peripheral.setContainmentVent(true), "CC accepted vent without a wetwell valve");
        helper.assertTrue(!reactor.core().getContainment().isInertingCommanded(),
                "CC purged containment without a nitrogen supply");
        helper.assertTrue(!reactor.core().getContainment().isVentOpen(),
                "CC vented containment without a powered wetwell valve");
        for (int x = -1; x <= 11; x++) for (int z = -1; z <= 11; z++)
            for (int y = -2; y <= 8; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void ventMountsOnBothPoolDesignsAndNitrogenNeedsPower(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        for (int i = 0; i < 2; i++) {
            BlockPos origin = new BlockPos(45512 + i * 100, 190, 45512);
            var pool = i == 0 ? SuppressionBasinRegressionTests.build(level, origin)
                    : SuppressionTankRegressionTests.build(level, origin);
            helper.assertTrue(pool.isFormed() && pool.isEnclosedTank() == (i == 1),
                    "pool fixture has wrong design");
            BlockPos ventPos = origin.offset(2, 2, -1);
            level.removeBlock(ventPos, false);
            level.setBlock(ventPos, BwrBlocks.CONTAINMENT_VENT_VALVE.get().defaultBlockState(), 3);
            var vent = (ContainmentEquipmentBlockEntity) level.getBlockEntity(ventPos);
            helper.assertTrue(vent.mountedPool() == pool,
                    "wetwell vent cannot mount on " + (i == 0 ? "concrete basin" : "enclosed tank")
                            + "; registry=" + dev.bwr.mod.suppression.FormedSuppressionPoolRegistry.controllers(level).size()
                            + "; touches=" + pool.touchesConcreteBounds(ventPos)
                            + "; owner=" + (vent.mountedPool() == null ? "none" : vent.mountedPool().getBlockPos()));
            helper.assertTrue(!vent.supplied(), "unpowered wetwell vent reports supply");
            vent.energy().receiveEnergy(40, false);
            helper.assertTrue(vent.supplied(), "FE did not power the vent");
        }
        BlockPos nitrogenPos = new BlockPos(45712, 190, 45512);
        level.removeBlock(nitrogenPos, false);
        level.setBlock(nitrogenPos, BwrBlocks.CONTAINMENT_NITROGEN_SUPPLY.get().defaultBlockState(), 3);
        var nitrogen = (ContainmentEquipmentBlockEntity) level.getBlockEntity(nitrogenPos);
        helper.assertTrue(!nitrogen.supplied(), "empty nitrogen supply operated");
        nitrogen.energy().receiveEnergy(40, false);
        helper.assertTrue(!nitrogen.supplied(), "nitrogen supply operated without canister");
        helper.assertTrue(nitrogen.refill() && nitrogen.supplied(), "charged powered nitrogen supply did not operate");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void poweredVentDischargesFromBothLinkedPoolDesigns(GameTestHelper helper) throws Exception {
        var level = helper.getLevel();
        var vents = new ContainmentEquipmentBlockEntity[2];
        var rooms = new ContainmentControllerBlockEntity[2];
        for (int i = 0; i < 2; i++) {
            BlockPos origin = new BlockPos(46212 + i * 80, 190, 46212);
            for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 12) >> 4; cx++)
                for (int cz = (origin.getZ() - 3) >> 4; cz <= (origin.getZ() + 9) >> 4; cz++)
                    level.setChunkForced(cx, cz, true);
            var pool = i == 0 ? SuppressionBasinRegressionTests.build(level, origin)
                    : SuppressionTankRegressionTests.build(level, origin);
            for (int x = -3; x <= 11; x++) for (int z = -2; z <= 8; z++)
                for (int y = -2; y <= 6; y++) {
                    if (x == -3 || x == 11 || z == -2 || z == 8 || y == -2 || y == 6)
                        level.setBlock(origin.offset(x, y, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
                }
            BlockPos ventPos = origin.offset(2, 2, -1);
            level.removeBlock(ventPos, false);
            level.setBlock(ventPos, BwrBlocks.CONTAINMENT_VENT_VALVE.get().defaultBlockState()
                    .setValue(dev.bwr.mod.containment.ContainmentEquipmentBlock.FACING, Direction.NORTH), 3);
            var vent = (ContainmentEquipmentBlockEntity) level.getBlockEntity(ventPos);
            vent.energy().receiveEnergy(8_000, false);
            vent.setDemand(true);
            vents[i] = vent;
            BlockPos sleeve = origin.offset(2, 2, -2);
            level.setBlock(sleeve, BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState()
                    .setValue(ContainmentPenetrationBlock.FACING, Direction.NORTH), 3);
            var valve = (ContainmentPenetrationBlockEntity) level.getBlockEntity(sleeve);
            valve.energy().receiveEnergy(12_000, false);
            valve.setDemandOpen(true);
            BlockPos controllerPos = origin.offset(-2, 1, 3);
            level.removeBlock(controllerPos, false);
            level.setBlock(controllerPos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var room = (ContainmentControllerBlockEntity) level.getBlockEntity(controllerPos);
            room.markDirty();
            for (int t = 0; t < 64; t++) {
                ContainmentControllerBlockEntity.serverTick(level, controllerPos, room.getBlockState(), room);
                if (!room.isScanning() && room.strengthReady()) break;
            }
            rooms[i] = room;
            helper.assertTrue(room.isSealed() && room.linkedPools().contains(pool.getBlockPos()),
                    "vent pool was not linked to sealed containment: " + room.statusLines());
            helper.assertTrue(!vent.ready(room), "closed stack penetration allowed vent flow");
        }
        helper.runAfterDelay(100, () -> {
            for (int i = 0; i < 2; i++) {
                helper.assertTrue(vents[i].ready(rooms[i]) && vents[i].active(rooms[i]),
                        "powered vent failed on " + (i == 0 ? "concrete basin" : "enclosed tank"));
                BlockPos origin = new BlockPos(46212 + i * 80, 190, 46212);
                for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 12) >> 4; cx++)
                    for (int cz = (origin.getZ() - 3) >> 4; cz <= (origin.getZ() + 9) >> 4; cz++)
                        level.setChunkForced(cx, cz, false);
            }
            helper.succeed();
        });
    }
}
