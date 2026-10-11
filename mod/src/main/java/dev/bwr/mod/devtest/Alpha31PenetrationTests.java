package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationRatings;
import dev.bwr.mod.containment.ContainmentWall;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.SteamLineNetwork;
import dev.bwr.mod.water.WaterLineNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** New alpha.31 wall crossings; no existing GameTest is changed. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class Alpha31PenetrationTests {
    private Alpha31PenetrationTests() {}

    private static ContainmentControllerBlockEntity box(ServerLevel level, BlockPos center) {
        for (int x=-3; x<=3; x++) for (int y=-3; y<=3; y++) for (int z=-3; z<=3; z++) {
            BlockPos pos=center.offset(x,y,z);
            level.getChunk(pos.getX()>>4,pos.getZ()>>4);
            level.setBlock(pos,(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))>1
                    ?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState(),2);
        }
        level.setBlock(center,BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(),3);
        return (ContainmentControllerBlockEntity)level.getBlockEntity(center);
    }
    private static void survey(ServerLevel level, ContainmentControllerBlockEntity controller) {
        controller.markDirty();
        for (int i=0;i<32;i++) {
            ContainmentControllerBlockEntity.serverTick(level,controller.getBlockPos(),
                    controller.getBlockState(),controller);
            if (!controller.isScanning() && controller.strengthReady()) return;
        }
        throw new AssertionError("containment penetration survey exceeded 32 ticks: "+controller.statusLines());
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void steamSleeveSealsStrokesAndRuptures(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        BlockPos center=new BlockPos(35008,230,35008);
        ContainmentControllerBlockEntity controller=box(level,center);
        BlockPos sleeve=center.east(2), inner=center.east(), outer=center.east(3);
        level.setBlock(sleeve,BwrBlocks.CONTAINMENT_STEAM_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.EAST),3);
        level.setBlock(inner,BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),3);
        level.setBlock(outer,BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),3);
        survey(level,controller);
        helper.assertTrue(controller.isSealed() && controller.failurePressurePsig()==ContainmentPenetrationRatings.containmentPsig()
                        && controller.weakestSpot().position().equals(sleeve),
                "steam sleeve did not seal at its fixed 150-psig rating: "+controller.statusLines());
        helper.assertTrue(SteamLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST)
                        && SteamLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.EAST)
                        && !WaterLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST),
                "steam sleeve connected wrong service or missing an axial face");
        PipeTopology.Graph graph=PipeTopology.get(level,inner,Direction.EAST,true,false);
        helper.assertTrue(graph.cells().contains(outer),"steam line did not cross penetration");
        var valve=(ContainmentPenetrationBlockEntity)level.getBlockEntity(sleeve);
        var energy=level.getCapability(Capabilities.EnergyStorage.BLOCK,sleeve,Direction.UP);
        helper.assertTrue(energy!=null && energy.receiveEnergy(20_000,false)==20_000,
                "steam sleeve did not expose off-axis FE actuator");
        helper.runAfterDelay(80,()->{
            helper.assertTrue(valve.position()>.99,"powered valve failed to open in four seconds: "+valve.position());
            valve.setDemandOpen(false);
        });
        helper.runAfterDelay(160,()->{
            double closed=PipeTopology.routes(level,graph).stream().filter(r->r.node().pos().equals(sleeve))
                    .mapToDouble(PipeTopology.Route::opening).max().orElse(-1);
            helper.assertTrue(closed==0,"closed sleeve still routed steam: "+closed
                    +" graphTruncated="+graph.truncated()+" nodes="+graph.edges().keySet()
                    +" routes="+PipeTopology.routes(level,graph)+" valve="+valve.position()
                    +" state="+level.getBlockState(sleeve));
            helper.assertTrue(controller.ruptureWeakest(level).equals(sleeve)
                            && level.getBlockState(sleeve).getValue(ContainmentPenetrationBlock.RUPTURED)
                            && !ContainmentWall.isWall(level,sleeve,level.getBlockState(sleeve)),
                    "sleeve failure broke wrong column or remained airtight");
            helper.assertTrue(!SteamLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST),
                    "ruptured sleeve still passed traffic");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void waterAndForeignPortsDoNotCrossConnect(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        BlockPos center=new BlockPos(35108,230,35108);
        var controller=box(level,center);
        BlockPos sleeve=center.east(2), outer=center.east(3);
        level.setBlock(sleeve,BwrBlocks.CONTAINMENT_WATER_PENETRATION.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.EAST),3);
        helper.assertTrue(WaterLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST)
                        && !SteamLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST),
                "water sleeve cross-connected to steam");
        level.setBlock(sleeve,BwrBlocks.CONTAINMENT_PASS_THROUGH.get().defaultBlockState()
                .setValue(ContainmentPenetrationBlock.FACING,Direction.EAST),3);
        level.setBlock(outer,Blocks.CHEST.defaultBlockState(),3);
        survey(level,controller);
        var energy=level.getCapability(Capabilities.EnergyStorage.BLOCK,sleeve,Direction.UP);
        helper.assertTrue(energy!=null && energy.receiveEnergy(20_000,false)==20_000,
                "general pass-through did not expose its valve actuator");
        helper.assertTrue(controller.isSealed(),"general pass-through did not seal");
        helper.assertTrue(!WaterLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST)
                        && !SteamLineNetwork.acceptsLineOn(level.getBlockState(sleeve),Direction.WEST),
                "general pass-through accepted BWR coolant pipes");
        helper.assertTrue(ContainmentPenetrationRatings.nameplate(ContainmentPenetrationBlock.Service.WATER).contains("2500")
                        && ContainmentPenetrationRatings.nameplate(ContainmentPenetrationBlock.Service.PASS_THROUGH).contains("1480")
                        && ContainmentPenetrationRatings.nameplate(ContainmentPenetrationBlock.Service.STEAM).contains("1250"),
                "penetration process nameplates are missing");
        helper.runAfterDelay(80,()->{
            var items=level.getCapability(Capabilities.ItemHandler.BLOCK,sleeve,Direction.WEST);
            helper.assertTrue(items!=null && items.getSlots()>0,
                    "powered pass-through failed to expose a foreign item inventory across a sealed wall");
            helper.succeed();
        });
    }
}
