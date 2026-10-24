package dev.bwr.core.turbine;

/** Conservative game-scale expansion, not a manufacturer performance curve.
 * Every kg can traverse HP then LP once. Both stages discharge steam; only a condenser removes its residual heat.
 */
public final class PowerTurbine {
    public static final double GENERATOR_EFFICIENCY=.985;
    public static final double GENERATOR_RATING_W=1_500_000_000;
    public static final double CONDENSATE_ENTHALPY=167.2; // 40 C approximation
    /** Moisture the rated work already includes: the separators' design 0.1 % (SteamSeparation.DESIGN_MOISTURE). */
    public static final double DESIGN_MOISTURE=.001;
    /**
     * Fraction of the train's work lost for each unit of moisture over design. Wet steam carries its water through the
     * expansion without doing work: the lost work is the water's share of the latent heat, weighted by the Carnot factor
     * of the cycle, {@code h_fg (1 - T_cond / T_sat) / w_specific} = 1500 kJ/kg x (1 - 313 K / 558 K) / 850 kJ/kg = 0.77
     * (HP 280 plus LP 570 kJ/kg). Baumann's rule of thumb, 1 % efficiency for 1 % mean moisture, is the same order.
     */
    public static final double WORK_LOSS_PER_MOISTURE=.77;
    /**
     * Liquid, kg, that must pass through a turbine's blades over design before they are eroded through. Game calibration:
     * about 150 hours of rated flow at 0.76 % excess moisture (the 17x17x28 reference), 30 hours at 4 %. Real blade
     * erosion is years, not hours; this is compressed so a wrong vessel shows in a play session.
     */
    public static final double EROSION_LIQUID_KG_TO_FAIL=8.0e6;
    public enum Stage { HP, LP }
    /** Work multiplier for steam of this moisture: 1 at or below the design 0.1 %. */
    public static double moistureWorkFactor(double moisture) {
        double excess=Double.isFinite(moisture)?Math.max(0,moisture-DESIGN_MOISTURE):0;
        return Math.max(0,1-WORK_LOSS_PER_MOISTURE*excess);
    }
    /** Blade wear (0..1, added to the turbine's existing damage) from passing {@code massKg} of steam of this moisture. */
    public static double erosionIncrement(double massKg,double moisture) {
        double excess=Double.isFinite(moisture)?Math.max(0,moisture-DESIGN_MOISTURE):0;
        return Double.isFinite(massKg)&&massKg>0?massKg*excess/EROSION_LIQUID_KG_TO_FAIL:0;
    }
    public record Expansion(double mass,double workKJ,double outletEnthalpy,double outletPressure,double rejectedHeatKJ) {}
    private PowerTurbine() {}
    public static double outletPressure(Stage stage,double inlet) { return stage==Stage.HP?Math.min(145,inlet*.25):1.07; }
    public static double specificWork(Stage stage,double h,double pressure) {
        return specificWork(stage,h,pressure,outletPressure(stage,pressure));
    }
    public static double specificWork(Stage stage,double h,double pressure,double outlet) {
        if(!Double.isFinite(h)||!Double.isFinite(pressure)||!Double.isFinite(outlet)||outlet<=0||pressure<=outlet)return 0;
        double nominal=stage==Stage.HP?280:570;
        double reference=stage==Stage.HP?1015.0/145:145.0/1.07;
        double drop=nominal*Math.max(0,Math.min(1,Math.log(pressure/outlet)/Math.log(reference)));
        return Math.max(0,Math.min(drop,h-CONDENSATE_ENTHALPY));
    }
    public static Expansion expand(Stage stage,SteamInventory inlet,SteamInventory exhaust,double wantedKg,double workBudgetKJ) {
        return expand(stage,inlet,exhaust,wantedKg,workBudgetKJ,outletPressure(stage,inlet.pressure()));
    }
    public static Expansion expand(Stage stage,SteamInventory inlet,SteamInventory exhaust,double wantedKg,double workBudgetKJ,double outletPsia) {
        double specific=specificWork(stage,inlet.enthalpy(),inlet.pressure(),outletPsia);
        double moisture=inlet.moisture();
        if(moisture>0)specific*=moistureWorkFactor(moisture);
        if(specific<=0||!Double.isFinite(wantedKg)||!Double.isFinite(workBudgetKJ))return new Expansion(0,0,0,0,0);
        double room=exhaust==null?0:exhaust.space();
        double n=Math.max(0,Math.min(Math.min(wantedKg,room),Math.min(inlet.mass(),workBudgetKJ/specific)));
        var steam=inlet.take(n);
        double work=n*specific, h=steam.enthalpy()-specific, pressure=outletPsia;
        if(exhaust!=null)exhaust.offer(new SteamInventory.Packet(n,h,pressure,steam.moisture()));
        return new Expansion(n,work,h,pressure,0);
    }
}
