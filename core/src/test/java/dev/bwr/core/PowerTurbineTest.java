package dev.bwr.core;

import dev.bwr.core.turbine.*;

public final class PowerTurbineTest {
    private static void near(double a,double b){if(Math.abs(a-b)>1e-7*Math.max(1,Math.abs(b)))throw new AssertionError(a+" != "+b);}
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void test01_massAndEnergyAcrossBothStages(){
        var main=new SteamInventory(200);var cross=new SteamInventory(400);main.offer(new SteamInventory.Packet(100,2770,1015));
        var hp=PowerTurbine.expand(PowerTurbine.Stage.HP,main,cross,100,1e9);
        near(hp.mass(),100);near(hp.workKJ(),28000);near(cross.energyKJ()+hp.workKJ(),277000);
        var condenser=new SurfaceCondenser();
        var lp=PowerTurbine.expand(PowerTurbine.Stage.LP,cross,condenser.steam,100,1e9);
        near(lp.mass(),100);near(lp.workKJ(),57000);near(cross.mass(),0);
        near(hp.workKJ()+lp.workKJ()+lp.rejectedHeatKJ()+100*lp.outletEnthalpy(),277000);
        near((hp.workKJ()+lp.workKJ())*20/1000*.985,1674.5);
        near(condenser.steam.mass(),100);near(condenser.condensate(),0);near(lp.rejectedHeatKJ(),0);
        condenser.tick(1);near(condenser.condensate(),0); // A dry condenser cannot make water.
        condenser.fillCold(100000,false);condenser.tick(10);
        near(condenser.condensate(),100);near(condenser.steam.mass(),0);
    }
    public static void test02_bufferAndLoadLimitsNeverConsumeUnaccountedSteam(){
        var main=new SteamInventory(200);var cross=new SteamInventory(10);main.offer(new SteamInventory.Packet(100,2770,1015));
        var hp=PowerTurbine.expand(PowerTurbine.Stage.HP,main,cross,100,1400);near(hp.mass(),5);near(main.mass(),95);
        hp=PowerTurbine.expand(PowerTurbine.Stage.HP,main,cross,100,1e9);near(hp.mass(),5);near(main.mass(),90);
        hp=PowerTurbine.expand(PowerTurbine.Stage.HP,main,cross,100,1e9);near(hp.mass(),0);near(main.mass(),90);
        var exhaust=new SteamInventory(2.25);
        var lp=PowerTurbine.expand(PowerTurbine.Stage.LP,cross,exhaust,100,1e9);near(lp.mass(),2.25);near(cross.mass(),7.75);
        lp=PowerTurbine.expand(PowerTurbine.Stage.LP,cross,exhaust,100,1e9);near(lp.mass(),0);near(cross.mass(),7.75);
        lp=PowerTurbine.expand(PowerTurbine.Stage.LP,cross,new SteamInventory(200),100,0);near(lp.mass(),0);near(cross.mass(),7.75);
        lp=PowerTurbine.expand(PowerTurbine.Stage.LP,cross,null,100,1e9);near(lp.mass(),0);near(cross.mass(),7.75);
    }
    public static void test03_parallelDrawsConserveHeaderInventory(){
        var header=new SteamInventory(400);header.offer(new SteamInventory.Packet(40.75,2490,145));
        double mass=0,work=0;var exhaust=new SteamInventory(200);
        for(int i=0;i<4;i++){var lp=PowerTurbine.expand(PowerTurbine.Stage.LP,header,exhaust,20,1e9);mass+=lp.mass();work+=lp.workKJ();near(lp.rejectedHeatKJ(),0);}
        near(mass,40.75);near(header.mass(),0);near(work+exhaust.energyKJ(),40.75*2490);
    }
    public static void test04_invalidInventoryAndPressureCannotCreateWork(){
        var tank=new SteamInventory(200);
        near(tank.offer(new SteamInventory.Packet(Double.NaN,2770,1015)),0);
        near(tank.offer(new SteamInventory.Packet(5,Double.POSITIVE_INFINITY,1015)),0);
        near(tank.offer(new SteamInventory.Packet(5,2770,-1)),0);
        near(PowerTurbine.specificWork(PowerTurbine.Stage.LP,100,145),0);
        near(PowerTurbine.specificWork(PowerTurbine.Stage.LP,2770,1.07),0);
        check(PowerTurbine.specificWork(PowerTurbine.Stage.HP,2770,200)<280);
    }
    public static void test05_mixingAndReloadPreserveEnergyAndFractionalMass(){
        var tank=new SteamInventory(200);tank.offer(new SteamInventory.Packet(12.25,2490,145));tank.offer(new SteamInventory.Packet(5.75,2200,100));
        near(tank.energyKJ(),12.25*2490+5.75*2200);near(tank.pressure(),100);
        var copy=new SteamInventory(200);copy.restore(tank.mass(),tank.enthalpy(),tank.pressure());
        near(copy.mass(),18);near(copy.energyKJ(),tank.energyKJ());near(copy.take(1.1).mass(),1.1);near(copy.mass(),16.9);
    }
    public static void test06_legacyCondensateMigrationIsBounded(){
        var condenser=new SurfaceCondenser();near(condenser.acceptLegacyCondensate(15.75),15.75);
        near(condenser.acceptLegacyCondensate(Double.NaN),0);near(condenser.acceptLegacyCondensate(-1),0);
        near(condenser.acceptLegacyCondensate(200000),200000-15.75);near(condenser.acceptLegacyCondensate(.25),0);
        near(condenser.condensate(),200000);near(condenser.steam.mass(),0);
    }
}
