package dev.bwr.core.pool;

/** Finite secondary inventory and a closed primary pool loop. Water circuits never mix.
 * Ordinary Minecraft water has no temperature; the secondary inlet uses the
 * plant's current 13 C design boundary, as does the surface condenser. */
public final class RhrHeatExchanger {
    public static final int CAPACITY_KG = 12_000;
    public static final double COLD_C = 13, HOT_DESIGN_C = 24, SECONDARY_FLOW = 2_400, RATING_MW = 100;
    private static final double CP = 4.184;
    private double cold, hot, hotEnergyKJ, primaryFlow, secondaryFlow, heatMW, primaryOutC;
    public double cold() { return cold; }
    public double hot() { return hot; }
    public double hotTemperatureC() { return hot > 0 ? COLD_C+hotEnergyKJ/(hot*CP) : COLD_C; }
    public double primaryFlow() { return primaryFlow; }
    public double secondaryFlow() { return secondaryFlow; }
    /** Temperature of water heated this tick, independent of immediate downstream extraction. */
    public double secondaryOutC() { return secondaryFlow>0 ? COLD_C+heatMW*1000/(secondaryFlow*CP) : COLD_C; }
    public double heatMW() { return heatMW; }
    public double primaryOutC() { return primaryOutC; }
    public double hotEnergyKJ() { return hotEnergyKJ; }
    private static double bounded(double n, double room) { return Double.isFinite(n) ? Math.max(0,Math.min(n,room)) : 0; }
    public double fillCold(double kg, boolean simulate) { double n=bounded(kg,CAPACITY_KG-cold); if(!simulate)cold+=n;return n; }
    public double drainHot(double kg, boolean simulate) {
        double n=bounded(kg,hot);
        if(!simulate&&n>0) { hotEnergyKJ*=Math.max(0,(hot-n)/hot);hot-=n; }
        return n;
    }
    public void clearReadouts() { primaryFlow=secondaryFlow=heatMW=0;primaryOutC=0; }
    public void step(SuppressionPool pool, double flow, double seconds) {
        clearReadouts();
        if(pool==null||!Double.isFinite(flow)||flow<=0||!Double.isFinite(seconds)||seconds<=0)return;
        double primaryKg=Math.min(flow*seconds,pool.getAvailableSuctionKg());
        double secondaryKg=Math.min(cold,Math.min(SECONDARY_FLOW*seconds,CAPACITY_KG-hot));
        if(primaryKg<=0||secondaryKg<=0)return;
        double inlet=pool.getTemperatureC(),delta=Math.max(0,inlet-COLD_C);
        double requested=Math.min(RATING_MW*seconds,
                .8*Math.min(primaryKg,secondaryKg)*CP*delta/1000);
        requested=Math.min(requested,secondaryKg*CP*(HOT_DESIGN_C-COLD_C)/1000);
        double removed=pool.removeHeatMJ(requested,COLD_C);
        cold-=secondaryKg;hot+=secondaryKg;hotEnergyKJ+=removed*1000;
        primaryFlow=primaryKg/seconds;secondaryFlow=secondaryKg/seconds;heatMW=removed/seconds;
        primaryOutC=inlet-removed*1000/(primaryKg*CP);
    }
    public void restore(double coldKg,double hotKg,double energyKJ) {
        cold=bounded(coldKg,CAPACITY_KG);hot=bounded(hotKg,CAPACITY_KG);
        hotEnergyKJ=bounded(energyKJ,hot*CP*(HOT_DESIGN_C-COLD_C));clearReadouts();
    }
}
