package dev.bwr.core;

import dev.bwr.core.turbine.*;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;

public final class CoolingWaterUnitTest {
    private static void near(double a,double b){if(!Double.isFinite(a)||Math.abs(a-b)>1e-8*Math.max(1,Math.abs(b)))throw new AssertionError(a+" != "+b);}
    public static void test01_towerMassLossAndMakeup(){
        var u=new CoolingWaterUnit(Design.MECHANICAL);u.fillInput(20000,false);u.tick(1,1);
        near(u.input(),10000);near(u.output(),9800);near(u.loss(),200);near(u.totalLoss(),200);near(u.flow(),10000);
        u.fillMakeup(200,false);near(u.input()+u.output(),20000);
        if(u.heatMW()<400||u.heatMW()>500)throw new AssertionError("Wrong design-point heat rejection");
    }
    public static void test02_stoppedAndBlockedCannotDestroyWater(){
        var u=new CoolingWaterUnit(Design.MECHANICAL);u.restore(10000,200000,0);u.tick(1,1);
        near(u.input(),10000);near(u.loss(),0);u.drain(980,false);u.tick(1,0);near(u.input(),10000);
        u.tick(1,1);near(u.input(),9000);near(u.output(),200000);near(u.totalLoss(),20);
    }
    public static void test03_pumpSpeedCapacityAndMass(){
        for(var d:new Design[]{Design.CIRCULATING,Design.MAKEUP,Design.INTAKE}){
            var u=new CoolingWaterUnit(d);u.fillInput(d.capacity,false);u.tick(.05,.25);
            near(u.flow(),d.flow*.25);near(u.input()+u.output(),d.capacity);near(u.loss(),0);
            double n=u.output();near(u.drain(Double.MAX_VALUE,true),n);near(u.output(),n);near(u.drain(Double.MAX_VALUE,false),n);near(u.output(),0);
        }
    }
    public static void test04_simulationInvalidDataAndFractionalReload(){
        var u=new CoolingWaterUnit(Design.NATURAL);near(u.fillInput(2e6,true),1e6);near(u.input(),0);
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}){near(u.fillInput(bad,false),0);near(u.fillMakeup(bad,false),0);near(u.drain(bad,false),0);}
        u.fillInput(123.45,false);u.tick(.05,1);var copy=new CoolingWaterUnit(Design.NATURAL);copy.restore(u.input(),u.output(),u.totalLoss());
        near(copy.output()+copy.totalLoss(),123.45);copy.tick(Double.NaN,1);copy.tick(1,Double.NaN);near(copy.output(),u.output());
        copy.restore(Double.NaN,Double.POSITIVE_INFINITY,-1);near(copy.input()+copy.output()+copy.totalLoss(),0);
    }
    public static void test05_closedLoopConservesWaterIncludingMakeup(){
        var c=new SurfaceCondenser();var tower=new CoolingWaterUnit(Design.NATURAL);var pump=new CoolingWaterUnit(Design.CIRCULATING);
        c.fillCold(100000,false);double makeup=0;
        for(int i=0;i<200;i++){
            c.steam.offer(new SteamInventory.Packet(100,2770,1015));c.tick(.05);c.drainCondensate(100000,false);
            double hot=c.drainHot(tower.fillInput(c.hot(),true),false);tower.fillInput(hot,false);tower.tick(.05,1);
            double loss=tower.loss()/20;makeup+=tower.fillMakeup(loss,false);
            double cold=tower.drain(pump.fillInput(tower.output(),true),false);pump.fillInput(cold,false);pump.tick(.05,1);
            c.fillCold(pump.drain(c.fillCold(pump.output(),true),false),false);
            near(c.cold()+c.hot()+tower.input()+tower.output()+pump.input()+pump.output()+tower.totalLoss(),100000+makeup);
        }
        if(tower.totalLoss()<=0)throw new AssertionError("Loop never circulated");
    }
    public static void test06_parallelTowerSizing(){
        var natural=new CoolingWaterUnit(Design.NATURAL);natural.fillInput(100000,false);natural.tick(1,1);
        var mechanical=new CoolingWaterUnit(Design.MECHANICAL);mechanical.fillInput(100000,false);mechanical.tick(1,1);
        near(natural.flow(),6*mechanical.flow());near(natural.heatMW(),6*mechanical.heatMW());
    }
}
