package dev.bwr.core.eccs;

import dev.bwr.core.Check;

public final class PipedTurbineTest {
    private PipedTurbineTest() {}
    private static EccsPump pump(double steam, double inlet, double discharge) {
        EccsPump p = new EccsPump(EccsDesign.RCIC);
        p.setRunning(true);
        p.setVesselPressurePsig(discharge);
        p.setSteamInletPressurePsig(inlet);
        p.setSteamSupplyLimitKgPerS(steam);
        for (int i=0; i<2400; i++) p.step(.05);
        return p;
    }
    public static void test01_emptySteamPipeCannotStartDrive() {
        EccsPump p=pump(0,1000,500);
        Check.exactly(0,p.getSpeedFraction(),"empty inlet shaft speed");
        Check.exactly(0,p.getFlowKgPerS(),"empty inlet water delivery");
        Check.isTrue(p.isRunning(),"loss of steam must preserve the player's run command");
    }
    public static void test02_supplyLimitsSpeedAndMass() {
        EccsPump full=pump(100,1000,500), weak=pump(.01,1000,500);
        Check.isTrue(full.getFlowKgPerS()>0,"full supply delivers water");
        Check.isTrue(weak.getSpeedFraction()<full.getSpeedFraction(),"restricted inlet lowers speed");
        Check.isTrue(weak.getSteamDemandKgPerS()<=.01,"steam demand cannot exceed supplied mass");
        Check.note("Full speed %.4f; restricted speed %.4f, steam %.6f kg/s",full.getSpeedFraction(),weak.getSpeedFraction(),weak.getSteamDemandKgPerS());
    }
    public static void test03_drivePressureIndependentOfWaterBackpressure() {
        EccsPump supplied=pump(100,1000,0), equalized=pump(100,0,0);
        Check.isTrue(supplied.getFlowKgPerS()>0,"pressurized steam can pump into an unpressurized vessel");
        Check.exactly(0,equalized.getSpeedFraction(),"equalized turbine has no shaft work");
        EccsPump blocked=pump(100,1000,10000);
        Check.exactly(0,blocked.getFlowKgPerS(),"water backpressure still blocks discharge");
    }
    public static void test04_supplyLossCoastsWithoutPhantomSteam() {
        EccsPump p=pump(100,1000,500);
        double initial=p.getSpeedFraction();
        p.setSteamSupplyLimitKgPerS(Double.NaN);
        p.step(.05);
        Check.isTrue(p.getSpeedFraction()<initial && p.getSpeedFraction()>0,"shaft coasts after lost supply");
        Check.exactly(0,p.getSteamDemandKgPerS(),"invalid supply gives no steam");
        Check.isTrue(p.isRunning(),"physics must not alter run command");
    }
}
