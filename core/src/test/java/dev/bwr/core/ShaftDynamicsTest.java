package dev.bwr.core;

import dev.bwr.core.turbine.ShaftDynamics;

public final class ShaftDynamicsTest {
    private static void near(double actual, double expected) {
        Check.isTrue(Math.abs(actual-expected) <= 1e-9*Math.max(1,Math.abs(expected)), actual+" != "+expected);
    }
    public static void test01_startupAndConservation() {
        double i=ShaftDynamics.REFERENCE_INERTIA;
        var s=ShaftDynamics.step(i,0,50000,0,.05);
        near(s.energyKJ(),50000);Check.isTrue(s.rpm()>0,"steam must roll an unloaded shaft");
        for(int n=0;n<10000;n++) {
            double before=s.energyKJ();
            s=ShaftDynamics.step(i,s.rpm(),10000,9000,.05);
            near(before+10000,s.energyKJ()+s.generatorWorkKJ()+s.lossKJ());
        }
    }
    public static void test02_loadRejectionAndManualSteamRemoval() {
        double i=ShaftDynamics.REFERENCE_INERTIA, speed=1500;
        for(int n=0;n<40;n++) speed=ShaftDynamics.step(i,speed,75000,0,.05).rpm();
        Check.isTrue(speed>1650,"load rejection must overspeed with steam still admitted");
        double peak=speed, damage=ShaftDynamics.damage(0,speed,1);
        Check.isTrue(damage>0,"overspeed must accumulate damage");
        for(int n=0;n<400;n++) speed=ShaftDynamics.step(i,speed,0,0,.05).rpm();
        Check.isTrue(speed<peak,"manual steam removal leaves coast-down");
        near(ShaftDynamics.damage(damage,1500,10),damage);
        Check.note("Rejection peak %.3f rpm; after steam removal %.3f rpm; damage %.6f",peak,speed,damage);
    }
    public static void test03_rotorCanSupplyLoadAndNeverNegative() {
        double i=ShaftDynamics.REFERENCE_INERTIA;
        var s=ShaftDynamics.step(i,1500,0,1e12,.05);
        near(s.energyKJ(),0);near(s.rpm(),0);
        near(s.generatorWorkKJ()+s.lossKJ(),ShaftDynamics.RATED_ENERGY_KJ);
        near(ShaftDynamics.step(i,0,0,100,.05).generatorWorkKJ(),0);
    }
    public static void test04_joinAndSplitConserveEnergyIncludingCouplingHeat() {
        double i=ShaftDynamics.REFERENCE_INERTIA;
        var join=ShaftDynamics.couple(new double[]{i*.4,i*.6},new double[]{1500,0});
        near(join.rpm(),600);
        near(ShaftDynamics.energyKJ(i*.4,1500),ShaftDynamics.energyKJ(i,join.rpm())+join.lossKJ());
        near(ShaftDynamics.energyKJ(i,join.rpm()),ShaftDynamics.energyKJ(i*.4,join.rpm())+ShaftDynamics.energyKJ(i*.6,join.rpm()));
        near(ShaftDynamics.couple(new double[]{i,i},new double[]{1500,1500}).lossKJ(),0);
    }
    public static void test05_limitsAndSteadyState() {
        double i=ShaftDynamics.REFERENCE_INERTIA;
        near(ShaftDynamics.damage(0,1650,100),0);near(ShaftDynamics.damage(.9,3300,20),1);
        var s=ShaftDynamics.step(i,1500,75000,75000-375,.05);
        near(s.rpm(),1500);near(s.lossKJ(),375);
        boolean rejected=false;try{ShaftDynamics.step(i,Double.NaN,0,0,.05);}catch(IllegalArgumentException e){rejected=true;}
        Check.isTrue(rejected,"nonfinite shaft state must be rejected");
    }
}
