package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;

/** Optional CC API references stay behind the installed-mod guard. */
final class MsivCcChecks {
    static void verify(GameTestHelper h,BlockPos root,MainSteamIsolationValveBlockEntity valve)throws Exception{
        for(int part=0;part<3;part++)for(var side:Direction.values()){
            var peripheral=h.getLevel().getCapability(PeripheralCapability.get(),root.above(part),side);
            h.assertTrue(peripheral!=null&&peripheral.getType().equals("bwr_msiv"),"missing MSIV modem face");
            var status=(java.util.Map<?,?>)PeripheralTestCalls.call(peripheral,"getStatus")[0];
            h.assertTrue(Boolean.TRUE.equals(status.get("powered"))&&((Number)status.get("energyStoredFe")).intValue()==valve.energy().getEnergyStored(),"CC power telemetry incorrect");
            PeripheralTestCalls.call(peripheral,"close");h.assertTrue(!valve.isDemandOpen()&&valve.isComputerControlled(),"modem did not command closure");
            PeripheralTestCalls.call(peripheral,"open");h.assertTrue(valve.isDemandOpen(),"modem did not command opening");
        }
    }
}
