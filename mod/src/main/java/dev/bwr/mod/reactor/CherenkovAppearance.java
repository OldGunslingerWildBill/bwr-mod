package dev.bwr.mod.reactor;

/** Visual approximation driven by total fission + decay power, not a dose calculation. */
public final class CherenkovAppearance {
    public static double strength(double powerFraction,boolean fuelPresent,boolean waterPresent,double waterHeight) {
        if(!fuelPresent||!waterPresent||!Double.isFinite(powerFraction)||!Double.isFinite(waterHeight)||waterHeight<=2.01)return 0;
        return Math.clamp(Math.sqrt(Math.max(0,powerFraction-1e-7))*3.5,0,1)
                *Math.clamp((waterHeight-2)/.5,0,1);
    }
    private CherenkovAppearance(){}
}
