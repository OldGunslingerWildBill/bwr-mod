package dev.bwr.mod.devtest;

import dev.bwr.mod.grid.*;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.*;

/** Real installed cable with a base branch and metered export. No cable-priority claim. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GridCableBranchTests {
    @GameTest(template="empty",timeoutTicks=240)
    public static void meteredCableExportAndOpenBreakerBaseSupply(GameTestHelper h){
        if(!net.neoforged.fml.ModList.get().isLoaded("mekanism")){h.succeed();return;}
        var l=h.getLevel();var p=new BlockPos(101000,245,101000);var min=p.offset(-6,-2,-4);var max=p.offset(5,7,4);
        var owned=new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();
        EntityLookupProbe.onFinished(h,()->{for(var at:BlockPos.betweenClosed(min,max))l.setBlock(at,Blocks.AIR.defaultBlockState(),3);for(var c:owned)l.setChunkForced(c.x,c.z,false);});
        for(int x=min.getX()>>4;x<=max.getX()>>4;x++)for(int z=min.getZ()>>4;z<=max.getZ()>>4;z++){
            var c=new net.minecraft.world.level.ChunkPos(x,z);if(!l.getForcedChunks().contains(c.toLong())){owned.add(c);l.setChunkForced(x,z,true);}l.getChunk(x,z);
        }
        for(var at:BlockPos.betweenClosed(min,max))l.setBlock(at,Blocks.AIR.defaultBlockState(),3);
        var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),p.offset(-3,-1,0),Direction.NORTH);
        var cable=net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_universal_cable"));l.setBlock(p,cable.defaultBlockState(),3);
        l.setBlock(p.east(),BwrBlocks.FE_METER.get().defaultBlockState().setValue(FeMeterBlock.FACING,Direction.EAST),3);
        l.setBlock(p.east(2),BwrBlocks.GRID_TIE.get().defaultBlockState(),3);
        l.setBlock(p.above(),BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
        var tie=(GridTieBlockEntity)l.getBlockEntity(p.east(2));var meter=(FeMeterBlockEntity)l.getBlockEntity(p.east());
        tie.bindGenerator(gen.getBlockPos());tie.configure(new dev.bwr.core.grid.GridDemand(0,1,0,0,1,200));tie.closeBreaker();
        var tag=gen.saveWithoutMetadata(l.registryAccess());tag.putDouble("Energy",10000);gen.loadWithComponents(tag,l.registryAccess());
        h.runAfterDelay(50,()->{
            var base=l.getCapability(Capabilities.EnergyStorage.BLOCK,p.above(),Direction.DOWN);var wire=l.getCapability(Capabilities.EnergyStorage.BLOCK,p,Direction.WEST);
            long export=tie.ledger().totalFE();int initialBase=base.getEnergyStored();
            h.assertTrue(export>0&&export==meter.ledger().totalFE()&&initialBase>0,"actual parallel cable did not serve both branches");
            h.assertTrue(Math.abs(gen.energyStored()+wire.getEnergyStored()+initialBase+export-10000)<1,"parallel cable lost FE");
            tie.tripBreaker();var refill=gen.saveWithoutMetadata(l.registryAccess());refill.putDouble("Energy",gen.energyStored()+10000);gen.loadWithComponents(refill,l.registryAccess());
            h.runAfterDelay(40,()->{
                h.assertTrue(tie.ledger().totalFE()==export&&meter.ledger().totalFE()==export&&!tie.closed(),"open export accepted FE");
                h.assertTrue(base.getEnergyStored()>initialBase&&Math.abs(gen.energyStored()+wire.getEnergyStored()+base.getEnergyStored()+export-20000)<1,"open grid breaker interrupted real base branch or lost FE");
                com.mojang.logging.LogUtils.getLogger().info("GRID CABLE branches: before trip export={} base={}; after trip export={} base={}",export,initialBase,tie.ledger().totalFE(),base.getEnergyStored());h.succeed();
            });
        });
    }
}
