package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.stream.Collectors;

/** Review E0b: openings are indexed once per completed rating, never by scanning the wall each reactor tick. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31OpeningCacheTests {
    private Alpha31OpeningCacheTests() {}

    private static void tick(ServerLevel level, ContainmentControllerBlockEntity controller) {
        ContainmentControllerBlockEntity.serverTick(level,controller.getBlockPos(),controller.getBlockState(),controller);
    }

    private static void survey(ServerLevel level, ContainmentControllerBlockEntity controller) {
        controller.markDirty();
        for(int i=0;i<32;i++) {
            tick(level,controller);
            if(!controller.isScanning()&&controller.strengthReady())return;
        }
        throw new AssertionError("opening-cache room survey did not finish: "+controller.statusLines());
    }

    private static Set<BlockPos> brute(ServerLevel level, ContainmentControllerBlockEntity controller) {
        return controller.boundaryFaces().stream().map(ContainmentControllerBlockEntity.BoundaryFace::wall)
                .filter(pos->level.getBlockState(pos).getBlock() instanceof ContainmentPenetrationBlock)
                .collect(Collectors.toSet());
    }

    @GameTest(template="empty", timeoutTicks=500)
    public static void cachedOpeningsSurviveRecalculationAndTrackEdits(GameTestHelper h) {
        ServerLevel level=h.getLevel();
        BlockPos center=new BlockPos(41100,200,41100);
        BlockPos lo=center.offset(-13,-13,-13), hi=center.offset(14,14,14);
        for(int cx=lo.getX()>>4;cx<=hi.getX()>>4;cx++)
            for(int cz=lo.getZ()>>4;cz<=hi.getZ()>>4;cz++)level.setChunkForced(cx,cz,true);
        // Reset this far-coordinate fixture: GameTest server worlds persist between runs.
        for(int x=-13;x<=14;x++)for(int z=-13;z<=14;z++)for(int y=-13;y<=14;y++) {
            boolean wall=x==-13||x==14||y==-13||y==14||z==-13||z==14;
            level.setBlock(center.offset(x,y,z),(wall?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        level.setBlock(center,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        var controller=(ContainmentControllerBlockEntity)level.getBlockEntity(center);
        BlockPos first=center.offset(14,13,13), second=center.offset(-13,-12,-12);
        level.setBlock(first,BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.EAST),3);
        level.setBlock(second,BwrBlocks.CONTAINMENT_WATER_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.WEST),3);
        survey(level,controller);
        h.assertTrue(controller.boundaryFaces().size()>4_000,"fixture must span two rating budgets");
        h.assertTrue(Set.copyOf(controller.openings()).equals(brute(level,controller))
                        && controller.openings().size()==2,"cached openings differ from wall after placement");
        controller.markStrengthDirty();
        tick(level,controller);
        h.assertTrue(!controller.strengthReady(),"fixture strength refresh unexpectedly finished in one tick");
        h.assertTrue(Set.copyOf(controller.openings()).equals(brute(level,controller)),
                "openings disappeared during a strength refresh");
        level.setBlock(first,Blocks.STONE.defaultBlockState(),3);
        survey(level,controller);
        h.assertTrue(Set.copyOf(controller.openings()).equals(brute(level,controller))
                        && controller.openings().size()==1&&controller.openings().contains(second),
                "cached openings differ from wall after a penetration broke/replaced");
        for(int cx=lo.getX()>>4;cx<=hi.getX()>>4;cx++)
            for(int cz=lo.getZ()>>4;cz<=hi.getZ()>>4;cz++)level.setChunkForced(cx,cz,false);
        h.succeed();
    }
}
