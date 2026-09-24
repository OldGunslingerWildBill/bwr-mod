package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.water.*;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.core.thermal.Saturation;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** Loaded only when CC:Tweaked is present; no GameTest annotations. */
final class ReleaseCcChecks {
    private ReleaseCcChecks() {}
    private static void load(ServerLevel l,BlockPos p){ReleaseRegressionTests.load(l,p);}
    private static void near(GameTestHelper h,double a,double b,String reason){ReleaseRegressionTests.near(h,a,b,reason);}
    static Object computer(ServerLevel l,BlockPos p){return l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),p,Direction.UP);}
    static void stored(GameTestHelper h,Object computer,double expected){
        try{var status=(java.util.Map<?,?>)PeripheralTestCalls.call(computer,"getStatus")[0];near(h,((Number)status.get("storedFE")).doubleValue(),expected,"remote computer energy");}
        catch(dan200.computercraft.api.lua.LuaException ex){throw new AssertionError(ex);}
    }
    static void unavailable(Object computer){
        try{PeripheralTestCalls.call(computer,"getStatus");throw new AssertionError("CC read unloaded controller");}
        catch(dan200.computercraft.api.lua.LuaException expected){}
    }
public static void liveComputerFaceAfterControllerReplacement(GameTestHelper h)throws Exception {
        if(!net.neoforged.fml.ModList.get().isLoaded("computercraft")){h.succeed();return;}
        var l=h.getLevel();var root=new BlockPos(8600,200,8600);load(l,root);l.removeBlock(root,false);
        var pump=CoolingRuntimeCheck.place(l,Design.MAKEUP,root,Direction.EAST);
        var part=CoolingRuntimeCheck.port(pump,CoolingBlock.Port.OUTLET);
        var cached=l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),part,Direction.UP);
        h.assertTrue(cached!=null,"missing spare computer face");PeripheralTestCalls.call(cached,"setSpeed",.25);near(h,pump.target(),.25,"computer actuator did not reach owner");
        var state=l.getBlockState(root);var tag=pump.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        try{PeripheralTestCalls.call(cached,"setSpeed",.9);throw new AssertionError("offline controller accepted command");}catch(dan200.computercraft.api.lua.LuaException expected){}
        var replacement=(CoolingBlockEntity)BlockEntity.loadStatic(root,state,tag,l.registryAccess());l.setBlockEntity(replacement);
        PeripheralTestCalls.call(cached,"setSpeed",.6);near(h,replacement.target(),.6,"retained modem did not resolve new owner");near(h,pump.target(),.25,"stale owner was mutated");
        for(var cell:replacement.layout().cells){var pos=replacement.layout().world(root,Direction.EAST,cell);
            for(var side:Direction.values())h.assertTrue(l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),pos,side)!=null,"missing CC face at "+pos+"/"+side);}
        l.removeBlock(root,false);h.succeed();
    }
public static void computerFacesCoverMachineRegistry(GameTestHelper h){
        if(!net.neoforged.fml.ModList.get().isLoaded("computercraft")){h.succeed();return;}
        var l=h.getLevel();int count=0;
        for(var entry:BwrBlocks.BLOCKS.getEntries()){
            var block=entry.get();var s=block.defaultBlockState();
            if(!(block instanceof net.minecraft.world.level.block.EntityBlock)&&!(block instanceof dev.bwr.mod.flow.JetPumpBlock))continue;
            var pos=new BlockPos(9300+count*16,220,9300);l.getChunkAt(pos);l.removeBlock(pos,false);l.setBlock(pos,s,2);
            for(var side:Direction.values()){
                var p=l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),pos,side);
                h.assertTrue(p instanceof dan200.computercraft.api.peripheral.IDynamicPeripheral,"no computer face on "+entry.getId()+"/"+side);
                h.assertTrue(((dan200.computercraft.api.peripheral.IDynamicPeripheral)p).getMethodNames().length>0,"empty Lua interface on "+entry.getId());
            }
            l.removeBlock(pos,false);count++;
        }
        h.assertTrue(count>=41,"machine coverage unexpectedly shrank: "+count);System.out.println("CC FACES: "+count+" machine block types, all six directions");h.succeed();
    }
}
