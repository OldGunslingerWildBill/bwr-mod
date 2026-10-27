package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.peripheral.ContainmentPeripheral;
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
public final class Alpha31ContainmentPeripheralTests {
    private Alpha31ContainmentPeripheralTests() {}

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void roomPeripheralExposesOpeningsAndPoweredIsolation(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("CC-ABSENT SKIP: roomPeripheralExposesOpeningsAndPoweredIsolation");
            helper.succeed(); return;
        }
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(46012, 190, 46012);
        forceRoom(level, origin, true); // the peripheral never loads a chunk to look, so the fixture keeps its own loaded
        try {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
            for (int y = -4; y <= 4; y++) {
                boolean wall = Math.abs(x) == 4 || Math.abs(z) == 4 || Math.abs(y) == 4;
                level.setBlock(origin.offset(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
            }
        BlockPos sleeve = origin.offset(4, 0, 0);
        level.setBlock(sleeve, BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING, Direction.EAST), 3);
        level.setBlock(origin, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        var room = (ContainmentControllerBlockEntity) level.getBlockEntity(origin);
        room.markDirty();
        for (int i = 0; i < 32; i++) {
            ContainmentControllerBlockEntity.serverTick(level, origin, room.getBlockState(), room);
            if (!room.isScanning() && room.strengthReady()) break;
        }
        var cc = new ContainmentPeripheral(room);
        helper.assertTrue(cc.isSealed() && cc.getFreeVolume() > 0 && cc.getLeak() == null,
                "containment CC room geometry readout incorrect");
        helper.assertTrue(cc.getOpenings().size() == 1
                        && cc.getOpenings().getFirst().get("service").equals("steam")
                        && cc.getOpenings().getFirst().containsKey("nameplate"),
                "containment CC opening metadata missing");
        helper.assertTrue(cc.isIsolated() && !cc.setIsolation(sleeve.getX(), sleeve.getY(), sleeve.getZ(), false),
                "unpowered containment CC isolation command succeeded");
        var valve = (ContainmentPenetrationBlockEntity) level.getBlockEntity(sleeve);
        valve.energy().receiveEnergy(8_000, false);
        helper.assertTrue(cc.setIsolation(sleeve.getX(), sleeve.getY(), sleeve.getZ(), false)
                        && cc.getWeakestSpot() != null && cc.getFailurePressure() > 0,
                "powered containment CC isolation or weakest-spot readout failed");
        helper.assertTrue(cc.getLinkedReactors().isEmpty() && cc.getBypassFlow() == 0.0,
                "unlinked containment CC invented reactor flow");
        helper.succeed();
        } finally {
            forceRoom(level, origin, false);
        }
    }

    /**
     * Power is needed to open a spring-return penetration valve, never to close it: with the power gone a program's close is
     * recorded, so the valve does not reopen when the power comes back.
     */
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void aProgramCanCloseAnUnpoweredValveAndItStaysClosedWhenPowerReturns(GameTestHelper helper) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
            System.out.println("CC-ABSENT SKIP: aProgramCanCloseAnUnpoweredValveAndItStaysClosedWhenPowerReturns");
            helper.succeed(); return;
        }
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(46212, 190, 46012);
        forceRoom(level, origin, true); // the peripheral never loads a chunk to look, so the fixture keeps its own loaded
        try {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
            for (int y = -4; y <= 4; y++) {
                boolean wall = Math.abs(x) == 4 || Math.abs(z) == 4 || Math.abs(y) == 4;
                level.setBlock(origin.offset(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
            }
        BlockPos sleeve = origin.offset(4, 0, 0);
        level.setBlock(sleeve, BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING, Direction.EAST), 3);
        level.setBlock(origin, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
        var room = (ContainmentControllerBlockEntity) level.getBlockEntity(origin);
        room.markDirty();
        for (int i = 0; i < 32; i++) {
            ContainmentControllerBlockEntity.serverTick(level, origin, room.getBlockState(), room);
            if (!room.isScanning() && room.strengthReady()) break;
        }
        var cc = new ContainmentPeripheral(room);
        var valve = (ContainmentPenetrationBlockEntity) level.getBlockEntity(sleeve);
        helper.assertTrue(!valve.hasPowerToOpen() && valve.demandOpen(), "the valve starts unpowered with the open demand it has by default");
        helper.assertTrue(cc.setIsolation(sleeve.getX(), sleeve.getY(), sleeve.getZ(), true),
                "closing an unpowered valve from a program was refused");
        helper.assertTrue(!valve.demandOpen() && valve.isComputerControlled(), "the close demand was not recorded");
        helper.assertTrue(!cc.setIsolation(sleeve.getX(), sleeve.getY(), sleeve.getZ(), false) && !valve.demandOpen(),
                "opening without power is still refused and changes nothing");
        valve.energy().receiveEnergy(8_000, false);
        helper.assertTrue(valve.hasPowerToOpen() && !valve.demandOpen() && cc.isIsolated(),
                "the power came back and the valve is still held closed by the demand");
        helper.assertTrue(cc.setIsolation(sleeve.getX(), sleeve.getY(), sleeve.getZ(), false) && valve.demandOpen(), "with power it opens on request");
        helper.succeed();
        } finally {
            forceRoom(level, origin, false);
        }
    }

    private static void forceRoom(net.minecraft.server.level.ServerLevel level, BlockPos origin, boolean on) {
        for (int cx = (origin.getX() - 4) >> 4; cx <= (origin.getX() + 4) >> 4; cx++)
            for (int cz = (origin.getZ() - 4) >> 4; cz <= (origin.getZ() + 4) >> 4; cz++)
                FixtureChunks.force(level, cx, cz, on);
    }
}
