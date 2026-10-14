package dev.bwr.core;

import dev.bwr.core.harness.TransientHarness;
import java.util.Locale;

/** Coupled 51h10m acceptance. Only operator inputs change after the initial rated state. */
public final class LoadFollowingLongTest {
    public static void testCoupledPowerReductionAndRecovery(){
        var core=TransientHarness.ratedCore();
        var op=new TransientHarness.PlantOperator(core);
        op.setPressureRegulatorEnabled(true);op.setLevelControlEnabled(true);
        double dt=core.getConfig().tickSeconds;
        final double down=600,low=4200,up=90600,recovery=97800,end=184200;
        double baseSum=0,lowSum=0,endSum=0;long baseN=0,lowN=0,endN=0;
        double initialXe=core.getXenonAtomsPerCm3(),peakXe=initialXe,peakTime=0,returnXe=0,troughXe=Double.POSITIVE_INFINITY,troughTime=0;
        double downPower=0,upPower=0,downRods=0,upRods=0;boolean rodsMoved=false;double initialRods=TransientHarness.averageNotch(core);
        System.out.println("LOAD FOLLOW hours,power,flow,rods,iodine,xenon,pressure_psig,steam_kg_s");
        long steps=Math.round(end/dt);long minute=Math.round(60/dt),hour=Math.round(3600/dt);
        for(long tick=0;tick<steps;tick++){
            double t=tick*dt;
            double target=t<down?1:t<low?1-.3*(t-down)/(low-down):t<up?.7:t<recovery?.7+.3*(t-up)/(recovery-up):1;
            double flow=t<down?1:t<low?1-.4*(t-down)/(low-down):t<up?.6:t<recovery?.6+.4*(t-up)/(recovery-up):1;
            if(tick==Math.round(down/dt)){downPower=core.getTotalPowerFractionOfRated();downRods=TransientHarness.averageNotch(core);}
            if(tick==Math.round(up/dt)){upPower=core.getTotalPowerFractionOfRated();upRods=TransientHarness.averageNotch(core);returnXe=core.getXenonAtomsPerCm3();}
            // Hold rods for the first five minutes of each ramp, proving the flow handle initiates it.
            boolean held=t>=down&&t<down+300||t>=up&&t<up+300;
            core.setRecirculationFlowFraction(flow);
            // The operator now compares the thermal target with the heat balance.
            // Decay heat belongs in that measurement; do not subtract it again.
            op.setTargetTotalPowerFraction(target);
            op.setRodControlEnabled(!held);op.tick();core.step();
            double power=core.getTotalPowerFractionOfRated(),xe=core.getXenonAtomsPerCm3(),iodine=core.getIodineAtomsPerCm3();
            if(!Double.isFinite(power)||!Double.isFinite(xe)||!Double.isFinite(iodine)||xe<0||iodine<0||core.isScramActive()||power<.1||core.getFuelThermal().getPeakFuelTemperatureFractionOfMelt()>=1)
                throw new AssertionError("Invalid coupled plant at "+t+" s: power="+power+" Xe="+xe+" I="+iodine);
            if(tick==Math.round((down+300)/dt)-1){
                if(!(power<downPower&&TransientHarness.averageNotch(core)==downRods))throw new AssertionError("Flow reduction did not initiate power reduction with rods held");
                System.out.printf(Locale.ROOT,"FLOW DOWN held rods: power %.6f -> %.6f at %.1f s%n",downPower,power,t);
            }
            if(tick==Math.round((up+300)/dt)-1){
                if(!(power>upPower&&TransientHarness.averageNotch(core)==upRods))throw new AssertionError("Flow recovery did not initiate power increase with rods held");
                System.out.printf(Locale.ROOT,"FLOW UP held rods: power %.6f -> %.6f at %.1f s%n",upPower,power,t);
            }
            if(t<down){baseSum+=power;baseN++;}if(t>=up-600&&t<up){lowSum+=power;lowN++;}if(t>=end-600){endSum+=power;endN++;}
            if(t>=down&&t<up&&xe>peakXe){peakXe=xe;peakTime=t;}
            if(t>=up&&xe<troughXe){troughXe=xe;troughTime=t;}
            if(tick%minute==0){
                double rods=TransientHarness.averageNotch(core);rodsMoved|=Math.abs(rods-initialRods)>.001;
                for(int i=0;i<core.getControlRodCount();i++)if(core.getRodNotchDemand(i)<0||core.getRodNotchDemand(i)>24)throw new AssertionError("Rod outside physical range");
            }
            if(tick%hour==0||tick==steps-1){System.out.printf(Locale.ROOT,"LOAD FOLLOW %.4f,%.6f,%.6f,%.4f,%.7e,%.7e,%.3f,%.3f%n",t/3600,power,flow,TransientHarness.averageNotch(core),iodine,xe,core.getPressurePsig(),core.getTurbineSteamFlowKgPerS());System.out.flush();}
        }
        double base=baseSum/baseN,reduced=lowSum/lowN,restored=endSum/endN;
        System.out.printf(Locale.ROOT,"LOAD FOLLOW means %.6f / %.6f / %.6f; Xe peak %.7e at %.3f h, trough %.7e at %.3f h, end %.7e%n",base,reduced,restored,peakXe,peakTime/3600,troughXe,troughTime/3600,core.getXenonAtomsPerCm3());
        if(Math.abs(base-1)>.05||Math.abs(reduced-.7)>.05||Math.abs(restored-1)>.05)throw new AssertionError("Final 10-minute means outside predeclared 5% bands");
        if(!rodsMoved||!(peakXe>initialXe&&returnXe<peakXe&&troughXe<returnXe&&core.getXenonAtomsPerCm3()>troughXe))throw new AssertionError("Required coupled rod/poison trends absent");
    }
}
