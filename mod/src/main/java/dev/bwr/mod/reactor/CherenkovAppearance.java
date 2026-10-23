package dev.bwr.mod.reactor;

/** Visual approximation driven by total fission + decay power, not a dose calculation. */
public final class CherenkovAppearance {
    public static double strength(double powerFraction,boolean fuelPresent,boolean waterPresent,double waterHeight) {
        if(!fuelPresent||!waterPresent||!Double.isFinite(powerFraction)||!Double.isFinite(waterHeight)||waterHeight<=2.01)return 0;
        return Math.clamp(Math.sqrt(Math.max(0,powerFraction-1e-7))*3.5,0,1)
                *Math.clamp((waterHeight-2)/.5,0,1);
    }
    /**
     * The light of the core seen up through water over the flange: the well's water is deep and clear, and a translucent surface lies over the glow, so
     * the strength is raised by up to 90 % as the surface climbs 2.5 m above the lip. 1 at or below the lip. A visual approximation, like the rest.
     */
    public static double wellBoost(double surface,double lip) {
        return 1+.9*Math.clamp((surface-lip)/2.5,0,1);
    }
    private CherenkovAppearance(){}
}
