package dev.bwr.mod.devtest;

import dan200.computercraft.api.network.wired.WiredElement;
import dan200.computercraft.api.network.wired.WiredElementCapability;
import dan200.computercraft.api.network.wired.WiredNetworkChange;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.panel.MachinePorts;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.wiring.CcPortBridge;
import dev.bwr.mod.peripheral.LivePeripheral;
import dev.bwr.mod.registry.BwrAttachments;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;

/**
 * Finding 7 of the audit on a real CC:Tweaked wired element: a machine that cannot be reached when the panel first asks
 * (here its block entity does not exist yet, as a multiblock part with no owner or a block in a chunk that has not loaded)
 * is left out of the answer, and that answer must not be kept; when the machine appears, with no change on the network and
 * no port edit (its port arrives with its saved data, which does not touch {@link MachinePorts#generation}), it is found.
 * References CC types, so callers stay behind {@code BwrMod.isComputerCraftPresent()}.
 */
final class AuditCcChecks {
    private AuditCcChecks() {
    }

    static void run(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos panelPos = new BlockPos(234_000, 110, 234_000);
        BlockPos meterPos = panelPos.east(5);
        int cx = panelPos.getX() >> 4;
        int cz = panelPos.getZ() >> 4;
        FixtureChunks.force(level, cx, cz, true);
        level.getChunk(cx, cz);
        try {
            level.setBlock(panelPos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, Direction.NORTH), 3);
            level.setBlock(meterPos, BwrBlocks.FE_METER.get().defaultBlockState(), 3);
            PanelBlockEntity panel = (PanelBlockEntity) level.getBlockEntity(panelPos);
            WiredElement element = level.getCapability(WiredElementCapability.get(), panelPos, Direction.NORTH);
            IPeripheral peripheral = level.getCapability(PeripheralCapability.get(), meterPos, Direction.NORTH);
            helper.assertTrue(panel != null && element != null && peripheral instanceof LivePeripheral live && live.isMachine(),
                    "fixture: the panel is a wired element and the meter a machine peripheral");
            // The meter is gone when the network is first asked about it.
            level.setBlock(meterPos, Blocks.AIR.defaultBlockState(), 3);
            Map<String, IPeripheral> added = Map.of("meter", peripheral);
            element.networkChanged(new WiredNetworkChange() {
                @Override
                public Map<String, IPeripheral> peripheralsRemoved() {
                    return Map.of();
                }

                @Override
                public Map<String, IPeripheral> peripheralsAdded() {
                    return added;
                }
            });
            helper.assertTrue(CcPortBridge.machinesOnPort(panel, 7).isEmpty(), "while the machine cannot be reached nothing answers on port 7");
            // It appears, with its saved port, and the network is not told anything.
            int generation = MachinePorts.generation();
            level.setBlock(meterPos, BwrBlocks.FE_METER.get().defaultBlockState(), 3);
            level.getBlockEntity(meterPos).setData(BwrAttachments.MACHINE_PORT.get(), 7);
            helper.assertTrue(MachinePorts.generation() == generation, "fixture: nothing bumped the port generation");
            var found = CcPortBridge.machinesOnPort(panel, 7);
            helper.assertTrue(found.size() == 1 && found.get(0).getBlockPos().equals(meterPos),
                    "the machine answers on port 7 as soon as it can be reached: " + found);
            // Once everything is reachable the answer is kept (a second ask finds the same machine without a rebuild).
            helper.assertTrue(CcPortBridge.machinesOnPort(panel, 7).equals(found), "and stays");
        } finally {
            level.setBlock(meterPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(panelPos, Blocks.AIR.defaultBlockState(), 3);
            FixtureChunks.force(level, cx, cz, false);
        }
        helper.succeed();
    }
}
