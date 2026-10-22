package dev.bwr.mod.devtest;

import dev.bwr.mod.grid.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FeMeterTests {
    private static final BlockPos ROOT=new BlockPos(93000,245,93000);
    @GameTest(template="empty",timeoutTicks=200)
    public static void directionalBridgeConservationAndDepth(GameTestHelper h){
        var l=h.getLevel();var min=ROOT.offset(-8,-2,-8);var max=ROOT.offset(80,9,8);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            try {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),ROOT,Direction.NORTH);
                var port=gen.block().terminal(ROOT,gen.getBlockState());var first=port.east();var sinkPos=first.east(1);
                l.setBlock(first,BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING,Direction.EAST),3);
                var meter=(FeMeterBlockEntity)l.getBlockEntity(first);
                var in=meter.capability(Direction.WEST);var out=meter.capability(Direction.EAST);
                var saved=gen.saveWithoutMetadata(l.registryAccess());saved.putDouble("Energy",10000.5);gen.loadWithComponents(saved,l.registryAccess());
                h.assertTrue(meter.capability(Direction.UP)==null&&in.extractEnergy(10,false)==0&&out.receiveEnergy(10,false)==0,"wrong faces transfer");
                h.assertTrue(in.getEnergyStored()==0&&in.getMaxEnergyStored()==0,"meter invented inventory");
                h.assertTrue(in.receiveEnergy(100,false)==0&&meter.ledger().totalFE()==0,"missing downstream accepted FE");
                h.assertTrue(out.extractEnergy(100,true)==100&&meter.ledger().totalFE()==0&&gen.energyStored()==10000.5,"pull simulation mutated state");
                h.assertTrue(out.extractEnergy(100,false)==100&&meter.ledger().totalFE()==100&&gen.energyStored()==9900.5,"pull did not conserve");
                l.setBlock(sinkPos,BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
                var sink=l.getCapability(Capabilities.EnergyStorage.BLOCK,sinkPos,Direction.WEST);
                PowerTrain.tick(gen);
                h.assertTrue(gen.energyStored()==.5&&sink.getEnergyStored()==9900&&meter.ledger().totalFE()==10000,"real terminal push did not conserve");
                meter.label("RHR A");meter.units("FE/t");var id=meter.deviceId();
                var tag=meter.saveWithFullMetadata(l.registryAccess());meter.loadWithComponents(tag,l.registryAccess());
                h.assertTrue(meter.label().equals("RHR A")&&meter.units().equals("FE/t")&&meter.deviceId().equals(id)&&meter.ledger().totalFE()==10000&&meter.ledger().sampleTicks()==0,"meter persistence lost state");
                h.assertTrue(meter.direction(Direction.WEST)&&in.receiveEnergy(100,false)==0&&out.extractEnergy(100,false)==0&&meter.ledger().totalFE()==10000,"rotation retained cached transfer or lost total");
                var drop=Block.getDrops(meter.getBlockState(),l,first,meter).getFirst();var state=meter.getBlockState();
                l.removeBlock(first,false);l.setBlock(first,state,3);state.getBlock().setPlacedBy(l,first,state,null,drop);
                var replacement=(FeMeterBlockEntity)l.getBlockEntity(first);
                h.assertTrue(!id.equals(replacement.deviceId())&&replacement.label().equals("RHR A")&&replacement.ledger().totalFE()==10000,"item condition/identity wrong");
                // A real 65-bridge chain is refused before the endpoint; then 64 succeeds.
                for(int i=0;i<65;i++){l.removeBlock(first.east(i),false);l.setBlock(first.east(i),BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING,Direction.EAST),3);}
                l.setBlock(first.east(65),BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
                var head=(FeMeterBlockEntity)l.getBlockEntity(first);var cap=head.capability(Direction.WEST);
                h.assertTrue(cap.receiveEnergy(123,true)==0&&cap.receiveEnergy(123,false)==0,"depth limit not enforced");
                l.setBlock(first.east(64),BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
                h.assertTrue(cap.receiveEnergy(123,true)==123&&cap.receiveEnergy(123,false)==123,"guard not cleared after refusal");
                for(int i=0;i<64;i++)h.assertTrue(((FeMeterBlockEntity)l.getBlockEntity(first.east(i))).ledger().totalFE()==123,"chain ledger double/missed count");
                // Model a foreign capability calling back into the same meter while it is on the active path.
                var field=FeMeterBlockEntity.class.getDeclaredField("PATH");field.setAccessible(true);
                @SuppressWarnings("unchecked") var path=(ThreadLocal<java.util.Set<java.util.UUID>>)field.get(null);
                path.get().add(head.deviceId());
                try {h.assertTrue(cap.receiveEnergy(1,true)==0&&cap.receiveEnergy(1,false)==0,"reentrant identity not refused");}finally{path.remove();}
                h.assertTrue(cap.receiveEnergy(1,false)==1,"guard leaked across calls");
                h.succeed();
            }finally{for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);}
        });
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void installedCableBridge(GameTestHelper h){
        if(!net.neoforged.fml.ModList.get().isLoaded("mekanism")){h.succeed();return;}
        var l=h.getLevel();var p=ROOT.east(256);var chunk=new net.minecraft.world.level.ChunkPos(p);
        boolean owned=!l.getForcedChunks().contains(chunk.toLong());
        var min=p.offset(-6,-2,-4);var max=p.offset(5,7,4);
        EntityLookupProbe.onFinished(h,()->{for(var at:BlockPos.betweenClosed(min,max))l.setBlock(at,Blocks.AIR.defaultBlockState(),3);if(owned)FixtureChunks.force(l, chunk.x,chunk.z,false);});
        if(owned)FixtureChunks.force(l, chunk.x,chunk.z,true);l.getChunk(chunk.x,chunk.z);
        for(var at:BlockPos.betweenClosed(min,max))l.setBlock(at,Blocks.AIR.defaultBlockState(),3);
        var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),p.offset(-3,-1,0),Direction.NORTH);
        var saved=gen.saveWithoutMetadata(l.registryAccess());saved.putDouble("Energy",10000);gen.loadWithComponents(saved,l.registryAccess());
        var cable=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_universal_cable"));
        l.setBlock(p,cable.defaultBlockState(),3);
        l.setBlock(p.east(),BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING,Direction.EAST),3);
        l.setBlock(p.east(2),BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
        h.runAfterDelay(30,()->{
            var cap=l.getCapability(Capabilities.EnergyStorage.BLOCK,p,Direction.WEST);
            h.assertTrue(cap!=null&&cap.getMaxEnergyStored()>0,"installed cable network not formed");
            h.runAfterDelay(30,()->{
                var meter=(FeMeterBlockEntity)l.getBlockEntity(p.east());var sink=l.getCapability(Capabilities.EnergyStorage.BLOCK,p.east(2),Direction.WEST);
                long served=meter.ledger().totalFE();
                h.assertTrue(served==sink.getEnergyStored()&&Math.abs(10000-gen.energyStored()-cap.getEnergyStored()-served)<1,"installed cable bridge violated conservation");
                com.mojang.logging.LogUtils.getLogger().info("FE METER CABLE Mekanism {}: generator -> cable -> meter -> fabricator source={} cable={} served={} (zero capacity bridge)",net.neoforged.fml.ModList.get().getModContainerById("mekanism").orElseThrow().getModInfo().getVersion(),gen.energyStored(),cap.getEnergyStored(),served);
                // Either result is a compatibility finding, not a hidden capacity workaround.
                h.succeed();
            });
        });
    }
}
