package dev.bwr.mod.devtest;

import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GeneratorAccountingTests {
    private static final BlockPos ROOT=new BlockPos(77000,245,77000);
    @GameTest(template="empty",timeoutTicks=200)
    public static void deliveryReservationAndRetainedCondition(GameTestHelper h) {
        var l=h.getLevel();var min=ROOT.offset(-8,-2,-8);var max=ROOT.offset(12,9,12);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            long time=l.getGameTime();
            try {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),ROOT,Direction.EAST);
                var tag=gen.saveWithoutMetadata(l.registryAccess());tag.putDouble("Energy",10000.75);gen.loadWithComponents(tag,l.registryAccess());
                var id=gen.deviceId();
                h.assertTrue(gen.energy.extractEnergy(500,true)==500&&gen.delivered().totalFE()==0,"simulation counted delivery");
                h.assertTrue(gen.energy.extractEnergy(500,false)==500&&gen.delivered().totalFE()==500,"external pull uncounted");
                h.assertTrue(gen.energyStored()==9500.75,"pull lost fractional FE");
                var port=gen.block().terminal(ROOT,gen.getBlockState());var face=gen.block().terminalFace(gen.getBlockState());
                var next=port.relative(face);l.setBlock(next,BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),3);
                var sink=l.getCapability(Capabilities.EnergyStorage.BLOCK,next,face.getOpposite());
                PowerTrain.tick(gen);
                h.assertTrue(sink.getEnergyStored()==9500&&gen.delivered().totalFE()==10000,"terminal push counted incorrectly");
                h.assertTrue(gen.energyStored()==.75,"push lost fractional FE");
                l.removeBlock(next,false);
                var saved=gen.saveWithoutMetadata(l.registryAccess());saved.putDouble("Energy",12345.75);gen.loadWithComponents(saved,l.registryAccess());
                var push=PowerModuleBlockEntity.class.getDeclaredMethod("pushEnergy",IEnergyStorage.class);push.setAccessible(true);
                var receiver=new IEnergyStorage(){
                    public int receiveEnergy(int max,boolean simulate){h.assertTrue(gen.energy.extractEnergy(Integer.MAX_VALUE,false)==0,"reentrant extraction spent reserved FE");return Math.min(max,2345);}
                    public int extractEnergy(int n,boolean sim){return 0;}public int getEnergyStored(){return 0;}public int getMaxEnergyStored(){return 2345;}
                    public boolean canReceive(){return true;}public boolean canExtract(){return false;}
                };
                h.assertTrue((int)push.invoke(gen,receiver)==2345,"push returned wrong transfer");
                h.assertTrue(gen.energyStored()==10000.75&&gen.delivered().totalFE()==12345,"reservation did not refund/account exactly");
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+1);PowerTrain.tick(gen);
                h.assertTrue(gen.delivered().lastTickFE()==2345&&gen.bufferDeltaFELastTick()==-2345&&gen.generatedFELastTick()==0,"completed-tick balance invalid");
                gen.addShaftDamage(.6);
                var cached=l.getCapability(Capabilities.EnergyStorage.BLOCK,port,face);
                var oldEnergy=gen.energy;var state=gen.getBlockState();var nbt=gen.saveWithFullMetadata(l.registryAccess());
                l.removeBlockEntity(ROOT);
                h.assertTrue(oldEnergy.extractEnergy(1,false)==0&&cached.extractEnergy(1,false)==0,"obsolete controller served FE");
                var reload=(PowerModuleBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(ROOT,state,nbt,l.registryAccess());l.setBlockEntity(reload);
                h.assertTrue(reload.deviceId().equals(id)&&cached.extractEnergy(1,false)==1,"same-identity reload failed");
                h.assertTrue(reload.delivered().sampleTicks()==0&&reload.damage()==.6,"reload lost condition or restored stale rates");
                var drops=Block.getDrops(state,l,ROOT,reload);h.assertTrue(drops.size()==1,"generator drop missing");
                var item=drops.getFirst();long lifetime=reload.delivered().totalFE();
                l.removeBlock(ROOT,false);l.setBlock(ROOT,state,3);gen.block().setPlacedBy(l,ROOT,state,null,item);
                var replacement=(PowerModuleBlockEntity)l.getBlockEntity(ROOT);
                h.assertTrue(!replacement.deviceId().equals(id)&&replacement.damage()==.6&&replacement.delivered().totalFE()==lifetime,"item lost condition/totals or retained world identity");
                var refill=replacement.saveWithoutMetadata(l.registryAccess());refill.putDouble("Energy",100);replacement.loadWithComponents(refill,l.registryAccess());
                h.assertTrue(cached.extractEnergy(10,false)==0&&replacement.energyStored()==100,"old capability served a different machine");
                h.succeed();
            } finally {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);
            }
        });
    }
}
