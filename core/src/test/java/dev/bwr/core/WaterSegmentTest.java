package dev.bwr.core;

import dev.bwr.core.thermal.*;
import dev.bwr.core.turbine.*;
import dev.bwr.core.pool.*;

/** Conservation across machine segments, with pipes carrying packets but storing no water. */
public final class WaterSegmentTest {
    private static double h(double c){return Saturation.subcooledLiquidEnthalpyKJPerKg(c);}
    private static void near(double a,double b){if(Math.abs(a-b)>1e-8*Math.max(1,Math.abs(b)))throw new AssertionError(a+" != "+b);}
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void test01_mixingSimulationAndLastDrain(){
        var tank=new WaterInventory(100,13);
        near(tank.fill(30,h(20),true),30);near(tank.mass(),0);
        tank.fill(30,h(20),false);tank.fill(70,h(90),false);
        double energy=30*h(20)+70*h(90);near(tank.energyKJ(),energy);
        near(tank.enthalpy(),energy/100);near(h(tank.temperatureC()),tank.enthalpy());
        near(tank.fill(10,h(100),false),0);near(tank.drain(20,true),20);near(tank.mass(),100);
        tank.drain(99.75,false);near(tank.energyKJ(),.25*energy/100);
        var copy=new WaterInventory(100,13);copy.restore(tank.mass(),tank.enthalpy());near(copy.energyKJ(),tank.energyKJ());
        copy.drain(100,false);near(copy.energyKJ(),0);near(copy.mass(),0);
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1})near(copy.fill(invalid,h(13),false),0);
        near(copy.fill(1,Double.NaN,false),0);near(copy.fill(1,-1,false),0);
    }
    public static void test02_pumpMovesHeatWithoutCreatingColdWater(){
        var pump=new CoolingWaterUnit(CoolingWaterUnit.Design.CIRCULATING);
        pump.fillInput(1500,h(67),false);pump.tick(.05,1);
        near(pump.output(),1500);near(pump.outputH(),h(67));near(pump.input(),0);
        var copy=new CoolingWaterUnit(pump.design);copy.restore(pump.input(),pump.output(),pump.totalLoss(),pump.inputH(),pump.outputH());
        near(copy.outputH(),pump.outputH());near(copy.outputC(),67);
    }
    public static void test03_warmWaterRaisesBackpressureAndReducesLpWork(){
        var cold=new SurfaceCondenser();cold.fillCold(10000,h(13),false);
        var warm=new SurfaceCondenser();warm.fillCold(10000,h(50),false);
        check(warm.backpressurePsia()>cold.backpressurePsia());check(warm.vacuumInHg()<cold.vacuumInHg());
        double good=PowerTurbine.specificWork(PowerTurbine.Stage.LP,2490,145,cold.backpressurePsia());
        double bad=PowerTurbine.specificWork(PowerTurbine.Stage.LP,2490,145,warm.backpressurePsia());
        check(good>bad&&bad>0);near(PowerTurbine.specificWork(PowerTurbine.Stage.LP,2490,145,145),0);
        var inlet=new SteamInventory(100);inlet.offer(new SteamInventory.Packet(100,2490,145));
        var exit=new SteamInventory(100);double before=inlet.energyKJ();
        var work=PowerTurbine.expand(PowerTurbine.Stage.LP,inlet,exit,80,1e9,warm.backpressurePsia());
        near(inlet.mass()+exit.mass(),100);near(inlet.energyKJ()+exit.energyKJ()+work.workKJ(),before);
        near(exit.pressure(),warm.backpressurePsia());
    }
    public static void test04_lossOfCoolingAccumulatesSteam(){
        var c=new SurfaceCondenser();double empty=c.backpressurePsia();
        c.steam.offer(new SteamInventory.Packet(9000,2100,145));c.tick(.05);
        near(c.condensate(),0);near(c.steam.mass(),9000);check(c.backpressurePsia()>empty);
        c.fillCold(1e6,h(13),false);c.tick(.05);check(c.condensate()>0);check(c.steam.mass()<9000);
        var copy=new SurfaceCondenser();copy.restore(c.cold(),c.hot(),c.condensate(),c.coldH(),c.hotH(),c.condensateH());
        copy.steam.restore(c.steam.mass(),c.steam.enthalpy(),c.steam.pressure());
        near(copy.backpressurePsia(),c.backpressurePsia());near(copy.hotC(),c.hotC());near(copy.condensateC(),c.condensateC());
    }
    public static void test05_closedCoolingLoopBalancesMassAndEnergy(){
        var tower=new CoolingWaterUnit(CoolingWaterUnit.Design.NATURAL);
        var pump=new CoolingWaterUnit(CoolingWaterUnit.Design.CIRCULATING);var c=new SurfaceCondenser();
        tower.fillMakeup(100000,h(13),false);c.steam.offer(new SteamInventory.Packet(1000,2300,145));
        double initial=100000*h(13)+c.steam.energyKJ(),heatToAir=0,lostEnergy=0;
        for(int i=0;i<800;i++){
            double n=tower.drain(1500,true),in=tower.outputH();n=pump.fillInput(n,in,false);tower.drain(n,false);pump.tick(.05,1);
            in=pump.outputH();n=c.fillCold(pump.drain(1500,true),in,false);pump.drain(n,false);c.tick(.05);
            in=c.hotH();n=tower.fillInput(c.drainHot(3000,true),in,false);c.drainHot(n,false);
            double lostH=Math.min(tower.inputH(),h(Math.max(13,tower.inputC()-11)));
            tower.tick(.05,1);heatToAir+=tower.heatMW()*50;lostEnergy+=tower.loss()*.05*lostH;
        }
        near(c.steam.mass()+c.condensate(),1000);
        near(tower.input()+tower.output()+pump.input()+pump.output()+c.cold()+c.hot()+tower.totalLoss(),100000);
        double stored=tower.input()*tower.inputH()+tower.output()*tower.outputH()+pump.input()*pump.inputH()+pump.output()*pump.outputH()
                +c.cold()*c.coldH()+c.hot()*c.hotH()+c.condensate()*c.condensateH()+c.steam.energyKJ();
        near(stored+heatToAir+lostEnergy,initial);check(c.condensate()>990);
    }
    public static void test06_heatExchangerUsesActualSecondaryTemperature(){
        var pool=new SuppressionPool(10000,70);var hx=new RhrHeatExchanger();hx.fillCold(1000,h(55),false);
        double before=hx.cold()*hx.coldH();hx.step(pool,1000,.05);
        near(hx.cold()*hx.coldH()+hx.hot()*hx.hotH()-before,hx.heatMW()*50);
        check(hx.secondaryOutC()>55&&hx.secondaryOutC()<=70);check(hx.primaryOutC()<70);
        var tooHot=new RhrHeatExchanger();tooHot.fillCold(1000,h(80),false);tooHot.step(pool,1000,.05);
        near(tooHot.heatMW(),0);near(tooHot.hotTemperatureC(),80);
    }
}
