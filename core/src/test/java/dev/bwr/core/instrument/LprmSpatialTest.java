package dev.bwr.core.instrument;

import dev.bwr.core.harness.TransientHarness;
import java.util.*;

public final class LprmSpatialTest {
    private static void require(boolean good,String message) { if(!good) throw new AssertionError(message); }
    public static void testLocalAxialFluxAndUniqueRackInputs() {
        var core=TransientHarness.ratedCore();var layout=core.getLprmLayout();var seen=new HashSet<String>();
        var solution=core.getNodalFluxSolver().lastSolution();var refs=LprmState.decode(core.toState().lprmState()).referenceMeans();
        double low=Double.POSITIVE_INFINITY,high=0;
        for(int s=0;s<layout.count();s++)for(int l=0;l<4;l++) {
            int c=layout.channel(s,l),i=layout.input(s,l);require(seen.add(c+":"+i),"aliased LPRM chamber");
            int z=(int)(LprmLayout.heightFraction(l)*solution.mesh().axialNodes());
            double expected=solution.flux(layout.strings().get(s).latticePosition(),z)/refs.get(c);
            double actual=core.getAveragePowerRangeMonitor(c).getLprmLocalFluxWeight(i);
            require(Math.abs(actual-expected)<1e-9,"detector did not sample its local axial node");
            low=Math.min(low,actual);high=Math.max(high,actual);
        }
        require(layout.count()>20&&high-low>.2,"uniform decorative LPRMs");
        for(int c=0;c<6;c++)require(Math.abs(core.getAveragePowerRangePercent(c)-100)<8,"rated APRM outside existing tolerance");
        System.out.printf("LPRM strings=%d distinct chambers=%d axial/local range=%.3f..%.3f%n",layout.count(),seen.size(),low,high);
    }
    public static void testBeyond144DoesNotAliasAndRackSurvivesReload() {
        var core=TransientHarness.ratedCore();var original=core.getLprmLayout().strings();var strings=new ArrayList<LprmLayout.StringLocation>(original);
        while(strings.size()<40) { var p=original.get(strings.size()%original.size());strings.add(new LprmLayout.StringLocation(100+strings.size(),p.y(),p.latticePosition())); }
        core.installLprmLayout(new LprmLayout(strings));var layout=core.getLprmLayout();
        int c=layout.channel(36,0),i=layout.input(36,0);var m=core.getAveragePowerRangeMonitor(c);
        m.setLprmFailed(i,true);m.setLprmBypassed(i,true);m.setGainAdjustmentFactor(1.07);
        core.settleInstruments();
        require(m.getLprmIndicatedPercent(i)==0,"failed final chamber still reads");
        require(!core.getAveragePowerRangeMonitor(layout.channel(0,0)).isLprmFailed(layout.input(0,0)),"final chamber aliases first");
        var state=core.toState();var copy=TransientHarness.ratedCore();copy.fromState(state);
        require(Arrays.equals(copy.toState().lprmState(),state.lprmState()),"rack wiring/calibration/flags lost on reload");
    }
    public static void testRodShadowChangesSampleWithoutRecalibrating() {
        var core=TransientHarness.ratedCore();var before=LprmState.decode(core.toState().lprmState());
        var layout=core.getLprmLayout();double[] values=new double[layout.count()*4];
        for(int s=0;s<layout.count();s++)for(int l=0;l<4;l++)values[s*4+l]=core.getAveragePowerRangeMonitor(layout.channel(s,l)).getLprmLocalFluxWeight(layout.input(s,l));
        core.setAllRodNotchDemand(0);
        for(int tick=0;tick<160;tick++)core.step();
        double change=0;
        for(int s=0;s<layout.count();s++)for(int l=0;l<4;l++)change=Math.max(change,Math.abs(values[s*4+l]-core.getAveragePowerRangeMonitor(layout.channel(s,l)).getLprmLocalFluxWeight(layout.input(s,l))));
        require(change>.05,"rods did not change local detector response");
        require(before.referenceMeans().equals(LprmState.decode(core.toState().lprmState()).referenceMeans()),"live automatic recalibration hid flux change");
    }
    public static void testUnwiredChannelsHaveNoPhantomDetectors() {
        var core=TransientHarness.ratedCore();var one=core.getLprmLayout().strings().getFirst();
        core.installLprmLayout(new LprmLayout(List.of(one)));core.settleInstruments();
        int total=0;
        for(int c=0;c<6;c++) {
            var monitor=core.getAveragePowerRangeMonitor(c);total+=monitor.getAssignedLprmInputCount();
            if(c>=4) require(monitor.getAssignedLprmInputCount()==0&&monitor.getStatus()==NeutronDetector.DetectorStatus.INOPERATIVE,
                    "unwired APRM channel has a phantom detector");
        }
        require(total==4,"one physical string must have exactly four chambers");
    }

}
