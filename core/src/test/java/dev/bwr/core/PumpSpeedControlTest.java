package dev.bwr.core;

import dev.bwr.core.eccs.*;
import dev.bwr.core.feedwater.FeedwaterDesign;

/** Numeric panel speed must command shaft speed, not relabel the old flow throttle. */
public final class PumpSpeedControlTest {
    private static void check(boolean good,String message){if(!good)throw new AssertionError(message);}
    public static void test01_motorSpeedAndHeadFollowCommand(){
        EccsPump p=new EccsPump(EccsDesign.RHR);
        p.setRunning(true);p.setElectricalPowerAvailableWatts(1e9);p.setFlowDemandFraction(.1);p.setSpeedDemandFraction(.5);
        for(int i=0;i<4000;i++)p.step(.05);
        check(Math.abs(p.getSpeedFraction()-.5)<1e-6,"shaft ignored 50% speed command");
        check(p.getFlowKgPerS()>0 && p.getSteamDemandKgPerS()==0,"electric pump required steam");
        double halfHead=p.getDevelopedHeadPsi();p.setSpeedDemandFraction(1);
        for(int i=0;i<4000;i++)p.step(.05);
        check(p.getDevelopedHeadPsi()>halfHead*3.5,"pump head did not increase with shaft speed");
        p.setSpeedDemandFraction(0);for(int i=0;i<4000;i++)p.step(.05);
        check(p.getSpeedFraction()==0 && p.getFlowKgPerS()==0 && p.isRunning(),"zero speed failed or changed run command");
    }
    public static void test02_persistenceAndLegacyDefaults(){
        EccsPump p=new EccsPump(FeedwaterDesign.MOTOR_FEED_PUMP);p.setSpeedDemandFraction(.37);
        EccsPump restored=new EccsPump(FeedwaterDesign.MOTOR_FEED_PUMP);restored.fromArray(p.toArray());
        check(restored.getSpeedDemandFraction()==.37,"speed command lost across save");
        restored.fromArray(new double[]{1,.7,.2});check(restored.getSpeedDemandFraction()==1,"old save changed speed behavior");
        restored.setSpeedDemandFraction(Double.NaN);check(restored.getSpeedDemandFraction()==1,"nonfinite command poisoned pump");
        restored.setSpeedDemandFraction(8);check(restored.getSpeedDemandFraction()==1,"speed exceeded 100%");
        restored.setSpeedDemandFraction(-3);check(restored.getSpeedDemandFraction()==0,"negative speed accepted");
    }
}
