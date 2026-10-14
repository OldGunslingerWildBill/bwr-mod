package dev.bwr.core.grid;

/** Manual breaker arithmetic; the window is indication only, never a permissive. */
public final class Synchronizer {
    public static final double ANGLE_LIMIT=10,SLIP_LIMIT=.067;
    private Synchronizer() {}
    public static double slip(double rpm){return rpm/30-50;}
    public static double wrap(double angle){return ((angle+180)%360+360)%360-180;}
    public static double advance(double angle,double rpm,double dt){return wrap(angle+360*slip(rpm)*dt);}
    public static boolean withinWindow(double angle,double slip){return Double.isFinite(angle)&&Double.isFinite(slip)&&Math.abs(wrap(angle))<=ANGLE_LIMIT&&Math.abs(slip)<=SLIP_LIMIT;}
    public static double closingDamage(double angle,double slip){
        if(!Double.isFinite(angle)||!Double.isFinite(slip))throw new IllegalArgumentException("Invalid sync sample");
        double ratio=Math.max(Math.abs(wrap(angle))/ANGLE_LIMIT,Math.abs(slip)/SLIP_LIMIT);
        return ratio<=1?0:Math.min(1,.25*ratio);
    }
}
