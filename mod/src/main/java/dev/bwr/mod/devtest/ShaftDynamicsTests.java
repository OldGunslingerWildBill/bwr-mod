package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.TurbineValveBlock;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ShaftDynamicsTests {
    private static final BlockPos ROOT=new BlockPos(75000,245,75000);
    @GameTest(template="empty",timeoutTicks=200)
    public static void rejectionAndManualSteamIsolation(GameTestHelper h) {
        var l=h.getLevel();var min=ROOT.offset(-28,-8,-12);var max=ROOT.offset(18,9,8);
        EntityLookupProbe.runWhenLive(h,new AABB(min.getX(),min.getY(),min.getZ(),max.getX(),max.getY(),max.getZ()),()->{
            long time=l.getGameTime();
            try {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                var supply=PowerModuleRuntimeCheck.place(l,BwrBlocks.HP_TURBINE.get(),ROOT.west(20),Direction.EAST);
                var lp=PowerModuleRuntimeCheck.place(l,BwrBlocks.LP_TURBINE.get(),ROOT,Direction.EAST);
                var gen=PowerModuleRuntimeCheck.place(l,BwrBlocks.NUCLEAR_GENERATOR.get(),ROOT.east(8),Direction.EAST);
                var condenser=PowerModuleRuntimeCheck.attachCondenser(lp);
                var a=supply.block().portPosition(supply.getBlockPos(),supply.getBlockState(),AssemblyPort.STEAM_EXHAUST)
                        .relative(supply.block().portFace(supply.getBlockState(),AssemblyPort.STEAM_EXHAUST));
                var b=lp.block().portPosition(lp.getBlockPos(),lp.getBlockState(),AssemblyPort.STEAM_INLET)
                        .relative(lp.block().portFace(lp.getBlockState(),AssemblyPort.STEAM_INLET));
                var ah=new BlockPos(a.getX(),ROOT.getY()+6,ROOT.getZ()-8);
                var bh=new BlockPos(b.getX(),ah.getY(),ah.getZ());
                PowerModuleRuntimeCheck.pipe(l,a,new BlockPos(a.getX(),ah.getY(),a.getZ()));
                PowerModuleRuntimeCheck.pipe(l,new BlockPos(a.getX(),ah.getY(),a.getZ()),ah);
                PowerModuleRuntimeCheck.pipe(l,ah,bh);
                PowerModuleRuntimeCheck.pipe(l,bh,new BlockPos(b.getX(),bh.getY(),b.getZ()));
                PowerModuleRuntimeCheck.pipe(l,new BlockPos(b.getX(),bh.getY(),b.getZ()),b);
                var valvePos=new BlockPos(ROOT.getX()-10,ah.getY(),ah.getZ());
                l.setBlock(valvePos,BwrBlocks.STEAM_STOP_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST),3);
                var valve=(TurbineValveBlockEntity)l.getBlockEntity(valvePos);valve.setTarget(1);valve.stroke(1);
                double loadedRpm=0,peak=0;int stopTick=-1;
                for(int tick=0;tick<1800;tick++) {
                    ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time+tick+1);
                    // Fixed external steam supply and heat sink, independent of electrical demand.
                    // The turbine and generator start at rest; no RPM/phase seed or override.
                    var tag=supply.saveWithoutMetadata(l.registryAccess());var steam=new CompoundTag();
                    steam.putDouble("Mass",400);steam.putDouble("Enthalpy",2490);steam.putDouble("Pressure",145);
                    tag.put("Exhaust",steam);supply.loadWithComponents(tag,l.registryAccess());
                    condenser.plant().steam.take(condenser.plant().steam.mass());
                    PowerTrain.tick(gen);
                    h.assertTrue(Math.abs(gen.generatedFELastTick()-gen.delivered().lastTickFE()-gen.bufferDeltaFELastTick())<1e-5,
                            "generator production minus delivery differs from buffer change");
                    if(tick<400)gen.energy.extractEnergy(Integer.MAX_VALUE,false);
                    if(tick==399){loadedRpm=gen.rpm;h.assertTrue(gen.electricMW>100,"fixture never supplied electrical load");}
                    if(tick>=400&&stopTick<0&&gen.rpm>1700){peak=gen.rpm;stopTick=tick;valve.setTarget(0);valve.stroke(1);}
                    if(stopTick>=0&&tick>=stopTick+40)break;
                }
                h.assertTrue(stopTick>=400,"load rejection did not overspeed with steam admitted");
                h.assertTrue(peak>loadedRpm&&gen.damage()>0,"rejection did not accelerate and damage the generator");
                h.assertTrue(lp.flowKgPerS==0&&gen.trainMW==0,"closed stop valve still admitted fresh steam torque");
                h.assertTrue(gen.rpm<peak,"isolated shaft failed to coast down");
                var saved=gen.saveWithoutMetadata(l.registryAccess());double speed=gen.rpm,damage=gen.damage();
                gen.loadWithComponents(saved,l.registryAccess());
                h.assertTrue(gen.rpm==speed&&gen.damage()==damage&&gen.rpm>1500,"save/load clamped overspeed or repaired damage");
                com.mojang.logging.LogUtils.getLogger().info("SHAFT rejection: loaded={} rpm peak={} rpm at {} s; isolated={} rpm damage={}",loadedRpm,peak,(stopTick-400)/20.0,gen.rpm,damage);
                h.succeed();
            } finally {
                for(var p:BlockPos.betweenClosed(min,max))l.setBlock(p,Blocks.AIR.defaultBlockState(),3);
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);
            }
        });
    }
}
