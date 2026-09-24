package dev.bwr.mod.devtest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
/** Kept out of GameTest signatures so dedicated servers without CC can load the tests. */
final class AlphaCcChecks {
    static void check(GameTestHelper h,ServerLevel l,BlockPos controller,BlockPos valve)throws Exception{
        for(var pos:java.util.List.of(controller,valve))for(var side:Direction.values()){
            var p=l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),pos,side);
            h.assertTrue(p!=null,"new ADS hardware missing computer face");
            PeripheralTestCalls.call(p,"setDivision",3);Object result=PeripheralTestCalls.call(p,"getDivision");
            h.assertTrue(result!=null,"division getter returned nothing");
        }
        PeripheralTestCalls.call(l.getCapability(dan200.computercraft.api.peripheral.PeripheralCapability.get(),controller,Direction.UP),"setDivision",2);
    }
}
