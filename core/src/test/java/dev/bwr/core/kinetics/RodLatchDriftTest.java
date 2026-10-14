package dev.bwr.core.kinetics;

import java.util.Arrays;
public final class RodLatchDriftTest {
    private static void require(boolean v,String m) {if(!v)throw new AssertionError(m);}
    private static boolean drift(dev.bwr.core.ReactorCore core) {
        return !core.isScramActive() && core.isRodBetweenNotches(0) && !core.isRodBeingDriven(0);
    }
    public static void testDisabledHazardAndUncommandedOutwardMotion() {
        var model=new RodLatchDrift(1);double pos=12;
        for(int i=0;i<100;i++)pos=model.advance(0,pos,false,false,0,.1,1);
        require(pos==12,"default-off hazard moved a latched rod");
        model.setFailed(0,true);pos=model.advance(0,pos,false,false,0,.1,2);
        require(Math.abs(pos-12.2)<1e-9,"failed collet did not drift out between notches");
        require(model.advance(0,pos,true,false,0,.1,1)==pos,"drift fought an active drive");
        require(model.advance(0,pos,false,true,0,.1,1)==pos,"drift fought scram insertion");
        model.setFailed(0,false);pos=model.advance(0,pos,false,false,0,.1,20);
        require(pos==13,"repaired collet did not catch the next notch");
        require(model.advance(0,pos,false,false,0,.1,20)==13,"repaired collet did not hold");
    }
    public static void testCoreDriftAlarmCommandsAndSave() {
        var core=dev.bwr.core.harness.TransientHarness.ratedCore();core.setAllRodNotchDemand(12);
        for(int i=0;i<800;i++)core.step();
        double initial=core.getRodPositionNotches(0);require(initial==12,"test rod did not latch at 12");
        core.setRodColletFailed(0,true);core.step();
        require(core.getRodPositionNotches(0)>initial&&drift(core),"core did not alarm on passive travel");
        core.refreshRodNotchDemand(0,12);core.step();
        require(core.getRodPositionNotches(0)>initial+.005,"standing demand erased drift");
        var state=core.toState();var restored=dev.bwr.core.harness.TransientHarness.ratedCore();restored.fromState(state);
        require(restored.isRodColletFailed(0)&&drift(restored),"save lost drift hardware/alarm");
        core.step();restored.step();require(core.getRodPositionNotches(0)==restored.getRodPositionNotches(0),"saved drift resumed differently");
        core.setRodNotchDemand(0,10);core.step();require(!drift(core),"deliberate motion reported as drift");
        core.scram();core.step();require(!drift(core),"scram insertion reported as drift");
    }

    public static void testSavedRandomSequenceAndFaultsResumeExactly() {
        var a=new RodLatchDrift(4);a.seed(1234);a.setFailed(1,true);
        for(int i=0;i<8;i++)a.advance(i%4,12,false,false,.01,.1,.05);
        var b=new RodLatchDrift(4);b.restore(a.snapshot());
        for(int i=0;i<2000;i++) {
            int r=i%4;
            require(a.advance(r,12,false,false,.01,.1,.05)==b.advance(r,12,false,false,.01,.1,.05),"reload rerolled the collet hazard");
        }
        require(Arrays.equals(a.snapshot(),b.snapshot()),"fault/RNG snapshot diverged");
        var enabled=new RodLatchDrift(1);enabled.seed(1);
        require(enabled.advance(0,12,false,false,1,.1,100)>12 && enabled.failed(0),"enabled hazard never releases a collet");
    }
}
