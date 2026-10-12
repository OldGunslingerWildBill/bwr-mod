package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A process crossing and an MSIV isolate the same steam path independently. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31IsolationTests {
    private Alpha31IsolationTests() {}

    @GameTest(template="empty", timeoutTicks=240)
    public static void msivAndLossOfActuatorPowerBothIsolateSteam(GameTestHelper helper) {
        var level = helper.getLevel();
        // This fixture is far from the test structure and is inspected after 160 ticks.
        BlockPos sleeve = new BlockPos(35208, 230, 35208);
        int cx = sleeve.getX() >> 4, cz = sleeve.getZ() >> 4;
        level.setChunkForced(cx, cz, true);
        level.getChunk(cx, cz);
        BlockPos source = sleeve.west(), msivPos = sleeve.east(), sink = sleeve.east(2);
        // The GameTest world can survive another Gradle run. Replacing an
        // identical block preserves its BE (including the MSIV's old FE).
        for (BlockPos pos : new BlockPos[] {source, sleeve, msivPos, sink})
            level.removeBlock(pos, false);
        level.setBlock(source, BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(), 3);
        level.setBlock(sleeve, BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING, Direction.EAST), 3);
        level.setBlock(msivPos, BwrBlocks.MSIV.get().defaultBlockState()
                .setValue(MainSteamIsolationValveBlock.FACING, Direction.EAST), 3);
        level.setBlock(sink, BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(), 3);
        var penetration = (ContainmentPenetrationBlockEntity) level.getBlockEntity(sleeve);
        var msiv = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(msivPos);
        helper.assertTrue(penetration.energy().receiveEnergy(8_000, false) == 8_000,
                "penetration actuator failed to accept one opening stroke of FE");
        var graph = PipeTopology.get(level, source, Direction.EAST, true, false);
        helper.assertTrue(graph.ends().stream().anyMatch(node -> node.pos().equals(sink)),
                "steam route did not cross the sleeve and MSIV");
        helper.runAfterDelay(80, () -> {
            helper.assertTrue(penetration.position() > .99, "penetration did not open in four seconds");
            double isolated = PipeTopology.routes(level, graph).stream()
                    .filter(route -> route.node().pos().equals(sink))
                    .mapToDouble(PipeTopology.Route::opening).max().orElse(-1);
            helper.assertTrue(isolated == 0, "unpowered MSIV did not isolate the open sleeve: " + isolated
                    + " msiv=" + msiv.getPosition() + " graph=" + graph.edges()
                    + " routes=" + PipeTopology.routes(level, graph));
            msiv.energy().receiveEnergy(20_000, false);
            msiv.tickValve(4.0);
            double open = PipeTopology.routes(level, PipeTopology.get(level, source, Direction.EAST, true, false)).stream()
                    .filter(route -> route.node().pos().equals(sink))
                    .mapToDouble(PipeTopology.Route::opening).max().orElse(-1);
            helper.assertTrue(open > .99, "powered MSIV did not admit steam through the open sleeve: " + open);
        });
        helper.runAfterDelay(160, () -> {
            helper.assertTrue(penetration.position() == 0,
                    "penetration failed to spring shut after its opening FE was exhausted: " + penetration.position());
            double isolated = PipeTopology.routes(level, PipeTopology.get(level, source, Direction.EAST, true, false)).stream()
                    .filter(route -> route.node().pos().equals(sink))
                    .mapToDouble(PipeTopology.Route::opening).max().orElse(-1);
            helper.assertTrue(isolated == 0, "unpowered sleeve passed steam: " + isolated);
            for (BlockPos pos : new BlockPos[] {source, sleeve, msivPos, sink})
                level.removeBlock(pos, false);
            level.setChunkForced(cx, cz, false);
            helper.succeed();
        });
    }
}
