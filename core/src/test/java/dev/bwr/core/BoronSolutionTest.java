package dev.bwr.core;
import dev.bwr.core.eccs.BoronSolution;
public final class BoronSolutionTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void near(double a,double b){if(Math.abs(a-b)>1e-8*Math.max(1,Math.abs(b)))throw new AssertionError(a+" != "+b);}
    public static void test01_finiteChargesAndMixing(){
        var tank=new BoronSolution();check(!tank.charge(true));
        near(tank.addWater(1000,30,false),1000);near(tank.massKg(),0);
        tank.addWater(1000,30,true);check(tank.charge(false));near(tank.massKg(),1000);
        check(tank.charge(true));near(tank.massKg(),1025);near(tank.borateKg(),25);
        double boron=25*BoronSolution.BORON_FRACTION_OF_BORATE;
        var simulated=tank.remove(400,false);near(simulated.boronKg(),boron*400/1025);near(tank.massKg(),1025);
        var a=tank.remove(400,true);var b=tank.remove(10000,true);
        near(a.massKg()+b.massKg(),1025);near(a.boronKg()+b.boronKg(),boron);near(tank.massKg(),0);near(tank.borateKg(),0);
        tank.addWater(1000,60,true);near(tank.boronFraction(),0);check(tank.remove(10,true).boronKg()==0);
    }
    public static void test02_capacityConcentrationAndSave(){
        var tank=new BoronSolution();tank.addWater(1000,20,true);
        int charges=0;while(tank.charge(true))charges++;
        check(charges==5);near(tank.borateKg(),125);check(tank.borateKg()/tank.massKg()<=.13);
        tank.addWater(1000,60,true);near(tank.temperatureC(),(1125*20.0+1000*60)/2125);
        var restored=new BoronSolution();restored.restore(tank.massKg(),tank.borateKg(),tank.temperatureC());
        near(restored.boronFraction(),tank.boronFraction());near(restored.temperatureC(),tank.temperatureC());
        near(restored.addWater(100000,13,true),15000-2125);check(!restored.charge(true));
        near(restored.addWater(Double.NaN,20,true),0);near(restored.remove(Double.POSITIVE_INFINITY,true).massKg(),0);
        restored.restore(Double.NaN,Double.POSITIVE_INFINITY,Double.NaN);near(restored.massKg(),0);near(restored.borateKg(),0);
    }
}
