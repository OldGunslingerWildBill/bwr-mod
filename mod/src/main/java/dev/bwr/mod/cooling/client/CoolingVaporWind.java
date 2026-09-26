package dev.bwr.mod.cooling.client;

/** Coherent visual wind, in blocks/tick. No server weather or pipe simulation. */
public final class CoolingVaporWind {
    private CoolingVaporWind() {}
    public record Sample(double x,double z,double liftFactor) {}

    public static Sample sample(double seconds,double sourceX,double sourceZ,double height,long salt,boolean raining) {
        // Nearby towers share the weather. Distant plants get a different phase,
        // without a visible seam at a chunk/region boundary.
        double t=seconds+sourceX*.0013+sourceZ*.0009;
        double exposed=smooth(Math.clamp(height/35,0,1));
        double weather=smooth(Math.clamp((noise(t/14,salt+17)-.20)/.60,0,1));
        double gust=.65+.55*noise(t/4.5,salt+41);
        double speed=(.012+(.52+(raining?.12:0))*weather)*gust*(.18+.82*exposed);
        // Gusts reach successively higher parts of the column at different times.
        // Coupled with particle inertia, this bends successive vapor parcels instead
        // of rotating the entire plume as a rigid straight column.
        double front=t-height*.075;
        double direction=unit(salt)*Math.PI*2+signed(t/38,salt+101)*1.6
                +signed(front/6,salt+211)*(.25+.8*exposed);
        double curl=Math.sin(height*.085-front*.43+signed(t/17,salt+307)*2)
                *(.015+.15*weather)*exposed;
        double c=Math.cos(direction),s=Math.sin(direction);
        return new Sample(c*speed-s*curl,s*speed+c*curl,1-.23*weather*exposed);
    }

    private static double signed(double time,long salt) { return noise(time,salt)*2-1; }
    private static double noise(double time,long salt) {
        long n=(long)Math.floor(time);
        double f=smooth(time-n),a=unit(n*0x9E3779B97F4A7C15L+salt);
        return a+(unit((n+1)*0x9E3779B97F4A7C15L+salt)-a)*f;
    }
    /** Quintic interpolation has zero slope and curvature at random sample boundaries. */
    private static double smooth(double t) { return t*t*t*(t*(t*6-15)+10); }
    private static double unit(long value) {
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return ((value^(value>>>31))>>>11)*0x1.0p-53;
    }
}
