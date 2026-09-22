package dev.bwr.core;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.turbine.*;

public final class SurfaceCondenserTest {
    private static void near(double a,double b){if(Math.abs(a-b)>1e-8*Math.max(1,Math.abs(b)))throw new AssertionError(a+" != "+b);}
    private static void check(boolean b){if(!b)throw new AssertionError();}
    private static SurfaceCondenser loaded(){var c=new SurfaceCondenser();c.steam.offer(new SteamInventory.Packet(5000,2770,1015));return c;}
    public static void test01_dryCondenserCannotCreateWater(){
        var c=loaded();c.tick(.05);near(c.steam.mass(),5000);near(c.condensate(),0);near(c.hot(),0);near(c.rejectedMW(),0);
    }
    public static void test02_massAndHeatBalanceAtDesignPoint(){
        var c=loaded();c.fillCold(100000,false);double energy=c.steam.energyKJ();c.tick(.05);
        near(c.cold()+c.hot(),100000);near(c.steam.mass()+c.condensate(),5000);
        double rise=Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.HOT_C)-Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.COLD_C);
        double liquid=c.condensate()*Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.CONDENSATE_C);
        near(c.steam.energyKJ()+liquid+c.hot()*rise,energy);
        near(c.rejectedMW(),c.hot()*rise/.05/1000);
        check(c.rejectedMW()<=SurfaceCondenser.HEAT_REJECTION_MW+1e-8);
        check(c.coolingRate()<=SurfaceCondenser.COOLING_KG_PER_S+1e-8);
        near(c.coolingRate(),60000);check(c.steamRate()>1000);
        Check.note("Design point rejects %.1f MW and condenses %.1f kg/s",c.rejectedMW(),c.steamRate());
    }
    public static void test03_fullOutputsStallWithoutLosingSteam(){
        var c=loaded();c.restore(100000,SurfaceCondenser.COOLING_CAPACITY,0);c.tick(.05);near(c.steam.mass(),5000);near(c.cold(),100000);
        c.restore(100000,0,SurfaceCondenser.CONDENSATE_CAPACITY);c.tick(.05);near(c.steam.mass(),5000);near(c.hot(),0);
        c.drainCondensate(.125,false);c.tick(.05);near(c.condensate(),SurfaceCondenser.CONDENSATE_CAPACITY);near(c.steam.mass(),4999.875);
    }
    public static void test04_finiteTransfersSimulationAndInvalidInput(){
        var c=loaded();near(c.fillCold(2000000,true),1000000);near(c.cold(),0);near(c.fillCold(2000000,false),1000000);
        near(c.fillCold(Double.NaN,false),0);near(c.fillCold(-5,false),0);c.tick(.05);
        double hot=c.hot(),water=c.condensate();near(c.drainHot(hot/2,true),hot/2);near(c.hot(),hot);
        near(c.drainCondensate(200000,true),water);near(c.condensate(),water);
        near(c.drainHot(2000000,false),hot);near(c.hot(),0);near(c.drainCondensate(2000000,false),water);near(c.condensate(),0);
        double steam=c.steam.mass();c.tick(Double.NaN);c.tick(-1);near(c.steam.mass(),steam);
        c.restore(Double.NaN,Double.POSITIVE_INFINITY,-1);near(c.cold()+c.hot()+c.condensate(),0);
    }
    public static void test05_fractionalInventorySurvivesReload(){
        var c=loaded();c.fillCold(25.25,false);c.tick(.05);check(c.condensate()>0&&c.condensate()<1);
        var copy=new SurfaceCondenser();copy.restore(c.cold(),c.hot(),c.condensate());copy.steam.restore(c.steam.mass(),c.steam.enthalpy(),c.steam.pressure());
        near(copy.cold()+copy.hot(),25.25);near(copy.steam.mass()+copy.condensate(),5000);near(copy.steam.energyKJ(),c.steam.energyKJ());
    }
}
