package dev.bwr.core.instrument;

import dev.bwr.core.harness.TransientHarness;

/** Exercise fixed detector commissioning through real operator-driven startup. */
public final class LprmStartupTest {
    public static void testAllSixChannelsAfterStartupRetainCalibration() {
        var core=TransientHarness.shutdownCore();var reference=LprmState.decode(core.toState().lprmState()).referenceMeans();
        var operator=new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(true);operator.setFlowControlEnabled(true);operator.setTargetTotalPowerFraction(1);
        while(core.getElapsedSeconds()<14000) {
            operator.tick();core.step();
            if(core.getTotalPowerFractionOfRated()>.995&&operator.isPowerSettled())break;
        }
        if(core.getTotalPowerFractionOfRated()<=.995)throw new AssertionError("startup never reached rated thermal power");
        if(!reference.equals(LprmState.decode(core.toState().lprmState()).referenceMeans()))
            throw new AssertionError("live recalibration hid the detector response");
        for(int c=0;c<6;c++) {
            double indicated=core.getAveragePowerRangePercent(c);
            System.out.printf(java.util.Locale.ROOT,"STARTUP APRM %d: %.4f%% at %.4f%% thermal, %.1f s%n",c+1,indicated,100*core.getTotalPowerFractionOfRated(),core.getElapsedSeconds());
            if(Math.abs(indicated-100)>8)throw new AssertionError("APRM "+(c+1)+" outside the existing +/-8% rated tolerance: "+indicated);
        }
    }
}
