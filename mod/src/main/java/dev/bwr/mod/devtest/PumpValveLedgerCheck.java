package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.reactor.RpvSteamOutletBlockEntity;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import java.util.function.DoubleSupplier;

/** A second consumer spends the same restricted branch's steam allowance as a live pump. */
public final class PumpValveLedgerCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void run(ServerLevel l,BlockPos valvePos,RpvSteamOutletBlockEntity nozzle,double pressure,Runnable tickPump,DoubleSupplier draw) {
        var other=valvePos.east().above();var unrestricted=valvePos.west().above();
        var original=l.getBlockState(valvePos);var beforeOther=l.getBlockState(other);var beforeFree=l.getBlockState(unrestricted);
        check(original.is(BwrBlocks.PRESSURISED_TUBE.get()),"ledger fixture valve not on steam pipe");
        try {
            l.setBlock(valvePos,BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(TurbineValveBlock.FACING,Direction.EAST),3);
            l.setBlock(other,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),3);
            l.setBlock(unrestricted,BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState(),3);
            var valve=(TurbineValveBlockEntity)l.getBlockEntity(valvePos);valve.setTarget(.01);valve.stroke(3);
            var time=(net.minecraft.world.level.storage.ServerLevelData)l.getLevelData();time.setGameTime(l.getGameTime()+1);nozzle.refreshLine(l);
            double supply=nozzle.flowKgPerS(pressure);check(supply>0,"ledger fixture has no steam");
            double first=SteamValveRouting.claim(l,other,nozzle,Double.MAX_VALUE);
            check(Math.abs(first-supply*.01)<1e-6,"restricted route allowance is incorrect");
            tickPump.run();check(draw.getAsDouble()==0,"pump bypassed steam already claimed by another branch consumer");
            time.setGameTime(l.getGameTime()+1);nozzle.flowKgPerS(pressure);tickPump.run();double used=draw.getAsDouble();
            check(used>0&&used<=supply*.01+1e-7,"pump did not obey its restricted steam route");
            check(Math.abs(valve.flowKgPerS()-used)<1e-6,"pump bypassed valve meter");
            double rest=SteamValveRouting.claim(l,other,nozzle,Double.MAX_VALUE);
            check(Math.abs(used+rest-supply*.01)<1e-6,"mixed consumers duplicated valve allowance");
            valve.setTarget(0);valve.stroke(3);time.setGameTime(l.getGameTime()+1);nozzle.flowKgPerS(pressure);tickPump.run();
            check(draw.getAsDouble()==0,"closed admission passed pump steam");
            System.out.println("REGRESSION PUMP VALVE LEDGER: live pump, competing consumer and closed admission passed");
        } finally {l.setBlock(valvePos,original,3);l.setBlock(other,beforeOther,3);l.setBlock(unrestricted,beforeFree,3);}
    }
}
