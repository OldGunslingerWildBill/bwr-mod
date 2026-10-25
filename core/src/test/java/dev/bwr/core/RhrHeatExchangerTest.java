package dev.bwr.core;
import dev.bwr.core.pool.*;

public final class RhrHeatExchangerTest {
    private static void near(double a,double b){if(!Double.isFinite(a)||Math.abs(a-b)>1e-8*Math.max(1,Math.abs(b)))throw new AssertionError(a+" != "+b);}
    public static void test01_separateCircuitsConserveMassAndHeat(){
        var pool=new SuppressionPool(64_000,80);var h=new RhrHeatExchanger();h.fillCold(12_000,false);
        double energy=pool.getCumulativeRhrRemovedMJ();h.step(pool,448,.05);
        near(pool.getMassKg(),64_000);near(h.cold()+h.hot(),12_000);
        near(h.hotEnergyKJ()/1000,pool.getCumulativeRhrRemovedMJ()-energy);
        near(h.heatMW()*.05,h.hotEnergyKJ()/1000);
        if(h.heatMW()<=0||pool.getTemperatureC()>=80||h.primaryOutC()>=80||h.hotTemperatureC()>24.000001)throw new AssertionError("Heat did not move from primary to secondary");
        double outlet=h.secondaryOutC();h.drainHot(h.hot(),false);near(h.secondaryOutC(),outlet);
        if(outlet<=13)throw new AssertionError("Immediate extraction erased the outlet temperature readout");
    }
    public static void test02_dryStoppedAndBlockedCannotCool(){
        var p=new SuppressionPool(64_000,80);var h=new RhrHeatExchanger();h.step(p,448,.05);near(p.getTemperatureC(),80);
        h.fillCold(12_000,false);h.step(p,0,.05);near(h.cold(),12_000);near(p.getTemperatureC(),80);
        h.restore(12_000,12_000,0);h.step(p,448,.05);near(h.heatMW(),0);near(p.getTemperatureC(),80);near(h.cold()+h.hot(),24_000);
        h.drainHot(120,false);h.step(p,448,.05);if(h.heatMW()<=0)throw new AssertionError("Draining outlet did not restore transfer");
    }
    public static void test03_finiteSinkAndTemperatureBounds(){
        var p=new SuppressionPool(64_000,80);var h=new RhrHeatExchanger();h.fillCold(10,false);
        h.step(p,1e9,.05);near(h.secondaryFlow(),200);near(h.cold(),0);near(h.hot(),10);
        if(h.heatMW()>100||h.primaryOutC()<13)throw new AssertionError("Exceeded exchanger rating or cold boundary");
        double temp=p.getTemperatureC();h.step(p,448,.05);near(p.getTemperatureC(),temp);
        p=new SuppressionPool(64_000,10);h.restore(100,0,0);h.step(p,448,.05);near(p.getTemperatureC(),10);near(h.heatMW(),0);
    }
    public static void test04_fractionalPersistenceAndSimulation(){
        var h=new RhrHeatExchanger();near(h.fillCold(123.75,true),123.75);near(h.cold(),0);h.fillCold(123.75,false);
        var p=new SuppressionPool(64_000,80);h.step(p,100,.0123);double temp=h.hotTemperatureC();
        var copy=new RhrHeatExchanger();copy.restore(h.cold(),h.hot(),h.hotEnergyKJ());near(copy.hotTemperatureC(),temp);
        near(copy.drainHot(1.25,true),1.25);near(copy.hot(),h.hot());copy.drainHot(1.25,false);near(copy.hotTemperatureC(),temp);
        near(copy.cold()+copy.hot()+1.25,123.75);
        copy.restore(Double.NaN,Double.POSITIVE_INFINITY,-3);near(copy.cold()+copy.hot()+copy.hotEnergyKJ(),0);
    }
    public static void test06_inletAndOutletReadoutsOnBothSides(){
        var p=new SuppressionPool(64_000,80);var h=new RhrHeatExchanger();
        near(h.primaryInC(),0);near(h.secondaryInC(),13);
        h.fillCold(12_000,false);h.step(p,448,.05);
        near(h.primaryInC(),80);near(h.secondaryInC(),13);
        if(h.primaryOutC()>=h.primaryInC()||h.secondaryOutC()<=h.secondaryInC())throw new AssertionError("Tube side must cool and shell side must warm");
        // Each side's heat balance (flow x cp x temperature change) is the duty, within the constant-cp approximation.
        if(Math.abs(h.primaryFlow()*4.184*(h.primaryInC()-h.primaryOutC())/1000-h.heatMW())>.01*h.heatMW())throw new AssertionError("Tube side balance");
        if(Math.abs(h.secondaryFlow()*4.184*(h.secondaryOutC()-h.secondaryInC())/1000-h.heatMW())>.02*h.heatMW())throw new AssertionError("Shell side balance");
        h.step(p,0,.05);near(h.primaryInC(),0);near(h.heatMW(),0);
        // The shell outlet reports backing up before cooling stops.
        h.restore(12_000,11_900,0);if(h.hotFill()<.99)throw new AssertionError("hot buffer fill");
        // Vessel loop: the same readouts, mass untouched.
        var r=new RhrHeatExchanger();r.fillCold(12_000,false);r.stepReactor(120,50_000,448,.05);
        near(r.primaryInC(),120);near(r.secondaryInC(),13);if(r.primaryOutC()>=120||r.heatMW()<=0)throw new AssertionError("Shutdown cooling readouts");
    }
    public static void test05_basinSurveyPreservesCondensedWater(){
        var p=new SuppressionPool(64_000,32);p.condenseSteam(100,1000,1);
        double water=p.getMassKg(),temperature=p.getTemperatureC();
        p.resizeCapacityKeepingInventory(64_000);near(p.getMassKg(),water);near(p.getTemperatureC(),temperature);
        p.resizeCapacityKeepingInventory(32_000);near(p.getMassKg(),water);near(p.getTemperatureC(),temperature);
    }
}
