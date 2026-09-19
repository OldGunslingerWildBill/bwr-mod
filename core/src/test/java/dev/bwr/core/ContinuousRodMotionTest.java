package dev.bwr.core;

import dev.bwr.core.kinetics.RodWorth;

/** Physical travel, as distinct from discrete commands and notch indications. */
public final class ContinuousRodMotionTest {
    private static ReactorCore atLabel24() {
        CoreConfig config=new CoreConfig();config.nodalSolveIntervalTicks=Integer.MAX_VALUE;
        ReactorCore core=new ReactorCore(config);
        core.setRodNotchLabelDemand(0,24);core.step(12*ReactorCore.NORMAL_SECONDS_PER_NOTCH);
        Check.exactly(12.0,core.getRodPositionNotches(0),"test rod at label 24");return core;
    }
    public static void test01_fractionalWorthRetainsNotchEndpoints() {
        var worth=new RodWorth(new CoreConfig());
        for(int i=0;i<=24;i++)Check.exactly(worth.integralWorthShape(i),worth.integralWorthShape((double)i),"original notch worth");
        double previous=worth.integralWorthShape(12.0);
        for(int n=1;n<=200;n++) {
            double current=worth.integralWorthShape(12+n/100.0);
            Check.isTrue(current<previous,"worth must change at every intermediate position");previous=current;
        }
    }
    public static void test02_label24To28MovesBeforeFirstLatch() {
        var core=atLabel24();core.setRodNotchLabelDemand(0,28);
        double initialWorth=core.getReactivityBalance().getRodsDkOverK();
        core.step(.05);
        Check.relative(12+.05/ReactorCore.NORMAL_SECONDS_PER_NOTCH,core.getRodPositionNotches(0),1e-12,"distance at normal drive speed");
        Check.exactly(24,core.getRodNotchLabel(0),"first latch not reached yet");
        Check.isTrue(core.getReactivityBalance().getRodsDkOverK()>initialWorth,"physics waited for a notch instead of following travel");
        double last=core.getReactivityBalance().getRodsDkOverK(),largest=0,smallest=Double.POSITIVE_INFINITY;
        for(int i=0;i<80;i++) {
            core.step(.05);double now=core.getReactivityBalance().getRodsDkOverK();
            double delta=now-last;Check.isTrue(delta>0,"withdrawal plateau or negative worth jump");
            largest=Math.max(largest,delta);smallest=Math.min(smallest,delta);last=now;
        }
        Check.lessThan(1.1,largest/smallest,"no whole-notch impulse at the intermediate latch");
        core.step(.2);Check.exactly(14.0,core.getRodPositionNotches(0),"stops at label 28");
        Check.isTrue(core.isRodMotionComplete(),"completion uses actual position");
        Check.note("24 -> 28: every 50 ms changes worth; largest/smallest increment %.5f",largest/smallest);
    }
    public static void test03_powerLossAndReversalDoNotTeleport() {
        var core=atLabel24();core.setRodNotchLabelDemand(0,28);core.step(.8);
        double held=core.getRodPositionNotches(0);core.setRodNormalMotionAvailable(0,false);core.step(.2);
        Check.exactly(held,core.getRodPositionNotches(0),"failed individual drive holds fractional travel");
        core.setRodNormalMotionAvailable(0,true);core.setCrdPowered(false);core.step(.2);
        Check.exactly(held,core.getRodPositionNotches(0),"bus loss holds position");
        core.setCrdPowered(true);core.setRodNotchLabelDemand(0,24);core.step(.05);
        Check.relative(held-.05/ReactorCore.NORMAL_SECONDS_PER_NOTCH,core.getRodPositionNotches(0),1e-12,"reversal starts at current position");
        core.step(1);Check.exactly(12,core.getRodPositionNotches(0),"returns to latch");
    }
    public static void test04_inFlightSnapshotAndScram() {
        var core=atLabel24();core.setRodNotchLabelDemand(0,28);core.step(.8);
        var saved=core.toState();var restored=new ReactorCore(new CoreConfig());restored.fromState(saved);
        Check.exactly(core.getRodPositionNotches(0),restored.getRodPositionNotches(0),"fractional travel survives reload");
        Check.exactly(saved.reactivityTotal(),restored.toState().reactivityTotal(),"in-flight reactivity survives reload");
        restored.step(.05);
        Check.exactly(core.getRodPositionNotches(0),restored.getRodPositionNotches(0),"absent standing command must hold between notches");
        double[] copy=saved.rodPositionsNotches();copy[0]=24;
        Check.exactly(core.getRodPositionNotches(0),saved.rodPositionsNotches()[0],"snapshot position array not aliased");
        restored.setRodNotchLabelDemand(0,28);double before=restored.getRodPositionNotches(0);
        restored.scram();Check.exactly(before,restored.getRodPositionNotches(0),"scram command itself cannot teleport");
        restored.setCrdPowered(false);restored.step(.01);
        Check.relative(before-.01*24/ReactorCore.SCRAM_FULL_STROKE_SECONDS,restored.getRodPositionNotches(0),1e-12,"accumulator drives continuous scram");
        restored.step(3);Check.exactly(0,restored.getRodPositionNotches(0),"charged scram completes");
    }
    public static void test05_startupPowerAndPeriodFollowTravel() {
        var core=new ReactorCore(new CoreConfig());core.initialiseAtTotalPowerFraction(1e-8);core.setBurnupEnabled(false);
        core.setRodNotchDemand(0,core.getRodNotchIndex(0)+2);
        double previous=core.getNeutronPowerFraction(),largest=0;int changed=0;
        for(int i=0;i<80;i++) {
            core.step();double power=core.getNeutronPowerFraction();
            Check.isTrue(power>previous,"startup withdrawal unexpectedly dropped power");
            largest=Math.max(largest,Math.abs(power/previous-1));previous=power;changed++;
            if(i>0)Check.finiteAndPositive(core.getSourceRangePeriodSeconds(0),"SRM period during withdrawal");
        }
        Check.lessThan(.02,largest,"startup response has no large single-tick notch jump");
        Check.note("Power advanced on %d consecutive ticks; largest step %.4f%%; SRM period %.3f s",changed,largest*100,core.getSourceRangePeriodSeconds(0));
    }
}
