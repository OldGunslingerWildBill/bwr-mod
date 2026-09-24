package dev.bwr.core;

import dev.bwr.core.flow.RecirculationSizing;

/** Volume scaling, finite drive ratings and preservation of physical flow on resize. */
public final class RecirculationSizingTest {
    public static void test07_inverseMatchesSharedManifoldIncludingSaturation() {
        near(RecirculationSizing.speedForDemand(.5,12,12,2,0),.3);
        for(double jets:new double[]{0,2,12,32,48}) for(int external=0;external<=5;external++)
            for(int internal=0;internal<=4;internal++) for(double fraction:new double[]{0,.1,.5,.9,1}) {
                double speed=RecirculationSizing.speedForDemand(fraction,12,jets,external,internal);
                double[] speeds=new double[external];java.util.Arrays.fill(speeds,speed);
                double delivered=RecirculationSizing.externalDeliveryUnits(jets,speeds)+internal*RecirculationSizing.INTERNAL_PUMP_UNITS*speed;
                double capacity=RecirculationSizing.externalCapacityUnits(jets,external)+internal*RecirculationSizing.INTERNAL_PUMP_UNITS;
                near(delivered,Math.min(12*fraction,capacity));
            }
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    private static void near(double actual,double expected) { check(Math.abs(actual-expected)<1e-8,"expected "+expected+", got "+actual); }

    public static void test01_volumeIncludesHeightAndUsesWholeOpposingPairs() {
        var small=RecirculationSizing.forDimensions(5,8,5);
        check(small.interiorVolume()==200 && small.requiredJets()==12 && small.requiredExternalPumps()==2,"small vessel calibration");
        check(RecirculationSizing.forDimensions(5,16,5).requiredJets()==16,"height did not raise the jet requirement");
        var large=RecirculationSizing.forDimensions(21,8,21);
        check(large.requiredJets()==32 && large.requiredExternalPumps()==4,"23 x 23 x 10 outer vessel target");
        var tall=RecirculationSizing.forDimensions(21,21,21);
        check(tall.requiredJets()==44 && tall.requiredExternalPumps()==5,"tall 23-block vessel target");
        check(RecirculationSizing.forDimensions(21,127,21).requiredJets()==80,"maximum scan-height sizing");
        check(RecirculationSizing.forDimensions(5,24,15).equals(RecirculationSizing.forDimensions(15,24,5)),"rotation changed sizing");
        int last=0;
        for(int height=8;height<=127;height++) {
            int jets=RecirculationSizing.forDimensions(21,height,21).requiredJets();
            check(jets>=last && jets%2==0,"nonmonotonic or unpaired requirement");last=jets;
        }
        Check.note("200 interior blocks: 12 jets / 2 RCPs; 3,528: 32 jets / 4 RCPs; 9,261: 44 jets / 5 RCPs");
    }

    public static void test02_extraJetsPlateauAtTheDriveLimit() {
        near(RecirculationSizing.externalDeliveryUnits(12,1,1),12);
        near(RecirculationSizing.externalDeliveryUnits(16,1,1),16);
        near(RecirculationSizing.externalDeliveryUnits(10,1),10);
        near(RecirculationSizing.externalDeliveryUnits(12,1),10);
        near(RecirculationSizing.externalDeliveryUnits(20,1,1),20);
        near(RecirculationSizing.externalDeliveryUnits(32,1,1),20);
        near(RecirculationSizing.externalDeliveryUnits(32,1,1,1),30);
        near(RecirculationSizing.externalDeliveryUnits(32,1,1,1,1),32);
        near(RecirculationSizing.externalDeliveryUnits(48,1,1,1,1),40);
        near(RecirculationSizing.externalCapacityUnits(32,4),32);
        Check.note("32-jet vessel: two RCPs deliver 62.5%%, three 93.75%%, four 100%%; extra jets cannot exceed drive capacity");
    }

    public static void test03_speedCoastdownAndStoppedParallelDrive() {
        near(RecirculationSizing.externalDeliveryUnits(32,.5,.5),10);
        near(RecirculationSizing.externalDeliveryUnits(2,.5),1);
        near(RecirculationSizing.externalDeliveryUnits(2,1,0),2);
        near(RecirculationSizing.externalDeliveryUnits(32,1,0,0,0),10);
        near(RecirculationSizing.externalDeliveryUnits(32,0,0,0,0),0);
        near(RecirculationSizing.externalDeliveryUnits(32,Double.NaN,Double.POSITIVE_INFINITY,-1),0);
    }

    public static void test04_noJetLoopRemainsWeakAndNeedsDrive() {
        near(RecirculationSizing.externalDeliveryUnits(0,1),1);
        near(RecirculationSizing.externalDeliveryUnits(0,.5),.5);
        near(RecirculationSizing.externalDeliveryUnits(32),0);
        check(RecirculationSizing.externalDeliveryUnits(0,1)<RecirculationSizing.externalDeliveryUnits(2,1),"jets gave no benefit");
    }

    public static void test05_changingRatingPreservesMassFlowAndOperatingState() {
        var core=new ReactorCore(new CoreConfig());core.initialiseCold();core.setRecirculationFlowFraction(.7);
        double rating=core.getVoidModel().getRatedCoreFlowKgPerS(), flow=core.getCoreFlowKgPerS();
        double mass=core.getLiquidMassKg(),temp=core.getCoolantTemperatureC(),inlet=core.getVoidModel().getCoreInletEnthalpyKJPerKg();
        double fraction=core.getCoreFlowFraction();
        core.setRatedCoreFlowKgPerS(rating*2);
        near(core.getCoreFlowKgPerS(),flow);near(core.getCoreFlowFraction(),fraction/2);near(core.getRecirculationFlowFractionDemand(),.35);
        near(core.getLiquidMassKg(),mass);near(core.getCoolantTemperatureC(),temp);near(core.getVoidModel().getCoreInletEnthalpyKJPerKg(),inlet);
        core.setRatedCoreFlowKgPerS(rating*2);near(core.getCoreFlowKgPerS(),flow);
        core.setRatedCoreFlowKgPerS(rating);near(core.getCoreFlowFraction(),fraction);near(core.getRecirculationFlowFractionDemand(),.7);
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            boolean rejected=false;try { core.setRatedCoreFlowKgPerS(invalid); } catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid reference accepted");near(core.getVoidModel().getRatedCoreFlowKgPerS(),rating);near(core.getCoreFlowKgPerS(),flow);
        }
    }

    public static void test06_customReferenceRestoresWithoutMultiplyingFlow() {
        var original=new ReactorCore(new CoreConfig());original.initialiseCold();
        double rating=RecirculationSizing.forDimensions(21,8,21).ratedFlowKgPerS();
        original.setRatedCoreFlowKgPerS(rating);
        var restored=new ReactorCore(new CoreConfig());
        // The caller persists geometry/reference alongside the operating snapshot.
        restored.getVoidModel().setRatedCoreFlowKgPerS(rating);restored.fromState(original.toState());
        near(restored.getCoreFlowKgPerS(),original.getCoreFlowKgPerS());
        restored.setRatedCoreFlowKgPerS(rating);near(restored.getCoreFlowFraction(),original.getCoreFlowFraction());
    }
}
