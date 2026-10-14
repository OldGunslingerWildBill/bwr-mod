package dev.bwr.core;

import dev.bwr.core.grid.*;

public final class GridArithmeticTest {
    private static void near(double a,double b){Check.isTrue(Math.abs(a-b)<1e-8*Math.max(1,Math.abs(b)),a+" != "+b);}
    public static void test01_unitsAndCompletedWindows(){
        near(EnergyUnits.mw(EnergyUnits.fePerTick(1500)),1500);near(EnergyUnits.mwh(2_000_000_000L),.3722222222222222);
        var l=new EnergyLedger();l.beginTick(10);l.record(200);Check.exactly(0L,l.lastTickFE(),"current tick hidden");
        l.beginTick(11);Check.exactly(200L,l.lastTickFE(),"completed tick");near(l.averageFE(20),10);
        l.beginTick(31);near(l.averageFE(20),0);Check.exactly(21,l.sampleTicks(),"idle zeros");
        l.restore(l.totalFE(),false);near(l.averageFE(20),0);Check.exactly(200L,l.totalFE(),"durable total");Check.exactly(0,l.sampleTicks(),"warmup reset");
    }
    public static void test02_saturationAndLongTick(){
        var l=new EnergyLedger();l.beginTick(0);l.record(Integer.MAX_VALUE);l.record(Integer.MAX_VALUE);l.beginTick(1);
        Check.exactly(2L*Integer.MAX_VALUE,l.lastTickFE(),"tick narrowed to int");
        l.restore(Long.MAX_VALUE-2,false);l.beginTick(0);l.record(3);Check.isTrue(l.saturated(),"overflow unreported");Check.exactly(Long.MAX_VALUE,l.totalFE(),"total wrapped");
    }
    public static void test03_fractionalBudgetSimulationAndExpiration(){
        var b=new DemandBudget();long actual=0;double mw=.00002;
        for(int n=0;n<10000;n++){
            double carry=b.carry();int potential=b.accept(n,mw,true,100,true);
            Check.exactly(carry,b.carry(),"simulation changed carry");
            int got=b.accept(n,mw,true,100,false);Check.exactly(potential,got,"simulation differs");actual+=got;
            Check.exactly(0,b.accept(n,mw,true,100,false),"budget reused");
        }
        near(actual+b.carry(),10000*EnergyUnits.fePerTick(mw));
        b.beginTick(10001,1500,true);b.beginTick(10002,0,true);Check.exactly(0,b.accept(10002,0,true,100,false),"old allowance banked");
        b.beginTick(10003,mw,false);near(b.carry(),0);Check.exactly(0,b.accept(10003,mw,false,100,false),"open intake");
    }
    public static void test04_largeBudgetAcrossCallsAndFaces(){
        var b=new DemandBudget();int a=b.accept(1,48000,true,Integer.MAX_VALUE,false);
        int c=b.accept(1,48000,true,Integer.MAX_VALUE,false);
        Check.isTrue((long)a+c>Integer.MAX_VALUE,"multi-call budget narrowed");
        near((long)a+c+b.carry(),EnergyUnits.fePerTick(48000));
        Check.exactly(0,b.accept(1,48000,true,Integer.MAX_VALUE,false),"spent allowance reused");
    }
    public static void test05_curveAndValidation(){
        var d=new GridDemand(1,0,0,1050,1500,24000);
        near(d.mw(0,1500),1050);near(d.mw(12000,1500),1500);near(d.mw(24000,1500),1050);near(d.mw(12000,1100),1100);
        boolean bad=false;try{new GridDemand(0,Double.NaN,0,0,0,20);}catch(IllegalArgumentException e){bad=true;}Check.isTrue(bad,"nonfinite demand accepted");
    }
    public static void test06_syncWindowAndConsequence(){
        near(Synchronizer.slip(1500),0);near(Synchronizer.advance(0,1503,1),36);
        near(Synchronizer.wrap(180),-180);near(Synchronizer.wrap(-181),179);
        Check.isTrue(Synchronizer.withinWindow(10,.067),"boundary refused");near(Synchronizer.closingDamage(10,.067),0);
        Check.isTrue(Synchronizer.closingDamage(10.01,0)>0,"angle violation ignored");
        Check.isTrue(Synchronizer.closingDamage(0,.06701)>0,"slip violation ignored");near(Synchronizer.closingDamage(180,1),1);
    }
}
